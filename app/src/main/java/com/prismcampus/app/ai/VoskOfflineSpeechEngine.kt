package com.prismcampus.app.ai

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonObject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.vosk.Model
import org.vosk.Recognizer
import java.io.File

/**
 * 100% Offline Speech Recognition Engine powered by Vosk-Android.
 * Runs completely on-device without internet access, streaming captured PCM audio
 * directly into the acoustic model to produce real partial and final transcripts.
 */
class VoskOfflineSpeechEngine(private val context: Context) {

    private val gson = Gson()
    private var model: Model? = null
    private var recognizer: Recognizer? = null
    private var recordingJob: Job? = null
    private var audioRecord: AudioRecord? = null
    @Volatile private var latestLiveRecognizedText: String = ""

    private val _isModelInstalled = MutableStateFlow(false)
    val isModelInstalled: StateFlow<Boolean> = _isModelInstalled.asStateFlow()

    private val _isModelLoaded = MutableStateFlow(false)
    val isModelLoaded: StateFlow<Boolean> = _isModelLoaded.asStateFlow()

    private val _statusText = MutableStateFlow<String?>(null)
    val statusText: StateFlow<String?> = _statusText.asStateFlow()

    companion object {
        const val MODEL_DIR_NAME = "vosk-model-small-en-us-0.15"
        const val SAMPLE_RATE = 16000
    }

    init {
        checkModelInstallation()
        if (!_isModelInstalled.value) {
            checkAndExtractBundledModel()
        }
    }

    private fun checkAndExtractBundledModel() {
        val hasAsset = try {
            context.assets.open(OfflineModelDownloader.VOSK_ASSET_NAME).use { true }
        } catch (_: Exception) { false }

        if (hasAsset) {
            CoroutineScope(Dispatchers.IO).launch {
                Log.i("VoskOfflineSpeech", "Background auto-extracting bundled Vosk model...")
                OfflineModelDownloader(context).downloadAndInstallModel()
                checkModelInstallation()
                ensureModelLoaded()
            }
        }
    }

    /**
     * Resolves candidate model directories in internal app storage.
     */
    fun getCandidateModelDirs(): List<File> {
        return listOf(
            File(context.filesDir, "models/$MODEL_DIR_NAME"),
            File(context.filesDir, MODEL_DIR_NAME),
            File(context.getExternalFilesDir(null), "models/$MODEL_DIR_NAME"),
            File(context.getExternalFilesDir(null), MODEL_DIR_NAME)
        )
    }

    fun getPreferredModelDir(): File {
        return File(context.filesDir, "models/$MODEL_DIR_NAME")
    }

    fun checkModelInstallation(): Boolean {
        val candidate = getCandidateModelDirs().find { isValidVoskModelDir(it) }
        val installed = candidate != null
        _isModelInstalled.value = installed
        if (installed) {
            Log.i("VoskOfflineSpeech", "Verified offline Vosk speech model at: ${candidate?.absolutePath}")
            _statusText.value = "Offline model ready"
        } else {
            Log.d("VoskOfflineSpeech", "No Vosk offline model found. Storage checked: ${getPreferredModelDir().absolutePath}")
            _statusText.value = "Offline model not installed (~40MB)"
        }
        return installed
    }

    private fun isValidVoskModelDir(dir: File): Boolean {
        if (!dir.exists() || !dir.isDirectory) return false
        // Vosk models contain am/ or model/ or conf/
        val hasAm = File(dir, "am").exists() || File(dir, "model").exists()
        val hasConf = File(dir, "conf").exists() || File(dir, "conf/model.conf").exists() || File(dir, "mfcc.conf").exists()
        return hasAm || hasConf || File(dir, "final.mdl").exists()
    }

    /**
     * Loads the model into memory. Keeps model persistent across listening sessions.
     */
    @Synchronized
    fun ensureModelLoaded(): Boolean {
        if (_isModelLoaded.value && model != null) return true

        var candidate = getCandidateModelDirs().find { isValidVoskModelDir(it) }
        if (candidate == null) {
            val hasAsset = try {
                context.assets.open(OfflineModelDownloader.VOSK_ASSET_NAME).use { true }
            } catch (_: Exception) { false }

            if (hasAsset) {
                Log.i("VoskOfflineSpeech", "Auto-extracting bundled Vosk model synchronously...")
                _statusText.value = "Extracting offline speech model..."
                val ok = kotlinx.coroutines.runBlocking {
                    OfflineModelDownloader(context).downloadAndInstallModel()
                }
                if (ok) {
                    checkModelInstallation()
                    candidate = getCandidateModelDirs().find { isValidVoskModelDir(it) }
                }
            }
        }

        if (candidate == null) {
            _isModelInstalled.value = false
            _isModelLoaded.value = false
            _statusText.value = "Offline speech model not installed (~40MB)"
            Log.w("VoskOfflineSpeech", "Cannot load Vosk model: directory not found")
            return false
        }

        return try {
            Log.i("VoskOfflineSpeech", "Loading Vosk acoustic model from ${candidate.absolutePath}...")
            model = Model(candidate.absolutePath)
            _isModelLoaded.value = true
            _isModelInstalled.value = true
            _statusText.value = "Model loaded"
            Log.i("VoskOfflineSpeech", "Vosk acoustic model loaded successfully into memory")
            true
        } catch (t: Throwable) {
            Log.e("VoskOfflineSpeech", "Failed to load Vosk model: ${t.message}", t)
            _isModelLoaded.value = false
            _statusText.value = "Model load error: ${t.message}"
            false
        }
    }

    /**
     * Starts listening and transcribing offline speech via AudioRecord and Vosk.
     */
    fun startListening(
        onPartialResult: (String) -> Unit,
        onFinalResult: (String) -> Unit,
        onRmsChanged: (Float) -> Unit,
        onError: (String) -> Unit
    ) {
        if (!ensureModelLoaded() || model == null) {
            onError("Offline model not installed. Please download the ~40MB speech model in settings.")
            return
        }

        stopListening()

        recordingJob = CoroutineScope(Dispatchers.IO).launch {
            val channelConfig = AudioFormat.CHANNEL_IN_MONO
            val audioFormat = AudioFormat.ENCODING_PCM_16BIT
            val minBufSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, channelConfig, audioFormat).coerceAtLeast(4096)

            var accumulatedPhrases = ""
            var currentPartial = ""
            var speechStarted = false
            var hasFinalResultEmitted = false
            val sessionStartMs = System.currentTimeMillis()
            val endpointer = SpeechEndpointer(sessionStartMs)

            try {
                audioRecord = AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    SAMPLE_RATE,
                    channelConfig,
                    audioFormat,
                    minBufSize
                )

                if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                    withContext(Dispatchers.Main) {
                        onError("Microphone hardware busy or uninitialized")
                    }
                    return@launch
                }

                recognizer = Recognizer(model, SAMPLE_RATE.toFloat())
                audioRecord?.startRecording()
                Log.i("VoskOfflineSpeech", "Audio capture started at ${SAMPLE_RATE}Hz PCM mono. Vosk listening with continuous live transcription...")

                val buffer = ShortArray(1024)
                var totalSamplesProcessed = 0L

                while (isActive) {
                    val readCount = audioRecord?.read(buffer, 0, buffer.size) ?: 0
                    if (readCount > 0) {
                        totalSamplesProcessed += readCount

                        // 1. Calculate live RMS volume for pulsing audio wave
                        var sum = 0.0
                        for (i in 0 until readCount) {
                            val sample = buffer[i].toDouble()
                            sum += sample * sample
                        }
                        val rms = Math.sqrt(sum / readCount).toFloat()
                        val normRms = ((rms - 40f) / 1200f).coerceIn(0.05f, 1.0f)
                        withContext(Dispatchers.Main) {
                            onRmsChanged(normRms)
                        }

                        // 2. Feed audio buffer to Vosk Recognizer
                        val rec = recognizer ?: break
                        val isCompletePhrase = rec.acceptWaveForm(buffer, readCount)

                        if (isCompletePhrase) {
                            val resultJson = rec.result
                            val phrase = extractTextFromJson(resultJson).trim()
                            if (phrase.isNotBlank()) {
                                accumulatedPhrases = if (accumulatedPhrases.isBlank()) phrase else "$accumulatedPhrases $phrase"
                                currentPartial = ""
                                latestLiveRecognizedText = accumulatedPhrases
                                speechStarted = true
                                Log.i("VoskOfflineSpeech", "Phrase complete: '$phrase' | Total accumulated: '$accumulatedPhrases'")
                                withContext(Dispatchers.Main) {
                                    onPartialResult(accumulatedPhrases)
                                }
                            }
                        } else {
                            val partialJson = rec.partialResult
                            val partial = extractPartialFromJson(partialJson).trim()
                            if (partial.isNotBlank()) {
                                currentPartial = partial
                                speechStarted = true
                                val liveDisplay = if (accumulatedPhrases.isBlank()) partial else "$accumulatedPhrases $partial"
                                latestLiveRecognizedText = liveDisplay
                                Log.d("VoskOfflineSpeech", "Live partial: '$liveDisplay'")
                                withContext(Dispatchers.Main) {
                                    onPartialResult(liveDisplay)
                                }
                            }
                        }

                        // 3. Auto-Execution on Silence Detection: Automatically execute query when user pauses speaking
                        if (speechStarted) {
                            val candidate = when {
                                accumulatedPhrases.isNotBlank() && currentPartial.isNotBlank() && !accumulatedPhrases.endsWith(currentPartial) -> "$accumulatedPhrases $currentPartial"
                                accumulatedPhrases.isNotBlank() -> accumulatedPhrases
                                else -> currentPartial
                            }.trim()

                            if (candidate.isNotBlank()) {
                                val nowMs = System.currentTimeMillis()
                                endpointer.observe(candidate, nowMs)
                                if (endpointer.shouldFinish(nowMs) && !hasFinalResultEmitted) {
                                    hasFinalResultEmitted = true
                                    Log.i("VoskOfflineSpeech", "Speech completed. Executing query: '$candidate'")
                                    withContext(Dispatchers.Main) {
                                        onRmsChanged(0f)
                                        onFinalResult(candidate)
                                    }
                                    break // Complete listening session and release audio capture
                                }
                            }
                        } else if (System.currentTimeMillis() - sessionStartMs > 8000L) {
                            // Initial silence timeout: No speech detected for 8 seconds
                            Log.i("VoskOfflineSpeech", "Initial silence timeout (8s). Stopping session.")
                            withContext(Dispatchers.Main) {
                                onRmsChanged(0f)
                                onError("No speech detected. Tap mic to speak.")
                            }
                            break
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e("VoskOfflineSpeech", "Vosk audio loop error: ${e.message}", e)
                withContext(Dispatchers.Main) {
                    onError("Offline recognition error: ${e.message}")
                }
            } finally {
                // Finalize remaining audio if not already emitted
                if (!hasFinalResultEmitted) {
                    try {
                        val finalJson = recognizer?.finalResult
                        val finalPhrase = extractTextFromJson(finalJson).trim()
                        val confirmedText = when {
                            accumulatedPhrases.isNotBlank() && finalPhrase.isNotBlank() && !accumulatedPhrases.endsWith(finalPhrase) -> "$accumulatedPhrases $finalPhrase"
                            finalPhrase.isNotBlank() -> finalPhrase
                            accumulatedPhrases.isNotBlank() -> accumulatedPhrases
                            else -> currentPartial
                        }.trim()
                        Log.i("VoskOfflineSpeech", "Final transcript in finally block: '$confirmedText'")
                        if (confirmedText.isNotBlank()) {
                            hasFinalResultEmitted = true
                            withContext(Dispatchers.Main) {
                                onRmsChanged(0f)
                                onFinalResult(confirmedText)
                            }
                        }
                    } catch (_: Exception) {}
                }

                cleanupAudioRecord()
            }
        }
    }

    private fun extractTextFromJson(jsonStr: String?): String {
        if (jsonStr.isNullOrBlank()) return ""
        return try {
            val json = gson.fromJson(jsonStr, JsonObject::class.java)
            json.get("text")?.asString?.trim() ?: ""
        } catch (e: Exception) {
            ""
        }
    }

    private fun extractPartialFromJson(jsonStr: String?): String {
        if (jsonStr.isNullOrBlank()) return ""
        return try {
            val json = gson.fromJson(jsonStr, JsonObject::class.java)
            json.get("partial")?.asString?.trim() ?: ""
        } catch (e: Exception) {
            ""
        }
    }

    fun stopListening(): String {
        val capturedLive = latestLiveRecognizedText.trim()
        recordingJob?.cancel()
        recordingJob = null

        val fromFinalResult = try {
            val finalJson = recognizer?.finalResult
            extractTextFromJson(finalJson).trim()
        } catch (_: Exception) {
            ""
        }

        cleanupAudioRecord()
        latestLiveRecognizedText = ""
        val result = if (fromFinalResult.isNotBlank()) fromFinalResult else capturedLive
        Log.i("VoskOfflineSpeech", "stopListening returning: '$result'")
        return result
    }

    private fun cleanupAudioRecord() {
        try {
            audioRecord?.stop()
            audioRecord?.release()
        } catch (e: Exception) {}
        audioRecord = null

        try {
            recognizer?.close()
        } catch (e: Exception) {}
        recognizer = null
    }

    fun shutdown() {
        stopListening()
        try {
            model?.close()
        } catch (e: Exception) {}
        model = null
        _isModelLoaded.value = false
    }
}
