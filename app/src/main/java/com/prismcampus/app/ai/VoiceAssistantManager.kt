package com.prismcampus.app.ai

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import androidx.core.content.ContextCompat
import com.prismcampus.app.data.ResolvedClassDestination
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale

enum class SupportedLanguage(val displayName: String, val code: String, val locale: Locale) {
    ENGLISH("English", "en", Locale.US),
    HINDI("हिंदी", "hi", Locale("hi", "IN")),
    KANNADA("ಕನ್ನಡ", "kn", Locale("kn", "IN"))
}

enum class SpeechState {
    IDLE,
    REQUESTING_PERMISSION,
    INITIALIZING,
    LISTENING,
    PROCESSING,
    SPEAKING,
    RETRYING,
    ERROR,
    UNAVAILABLE
}

/**
 * Enterprise-grade Voice Assistant Manager for PrismCampus.
 * Integrates dual-engine speech recognition:
 * 1. Online: Google Cloud SpeechRecognizer with high accuracy multi-language support (English, Hindi, Kannada).
 * 2. Offline: Vosk-Android on-device acoustic model (100% offline, zero network needed).
 * 3. Mutual exclusion between TTS and Microphone to eliminate audio loopback.
 * 4. Real-time normalized RMS wave generation for Compose UI pulse animations.
 */
class VoiceAssistantManager(private val context: Context) : TextToSpeech.OnInitListener {

    private val mainHandler = Handler(Looper.getMainLooper())

    private var tts: TextToSpeech? = null
    private var speechRecognizer: SpeechRecognizer? = null

    // Dedicated Vosk On-Device Offline Speech Engine & Downloader
    val voskEngine = VoskOfflineSpeechEngine(context)
    val modelDownloader = OfflineModelDownloader(context)

    private val _isTtsReady = MutableStateFlow(false)
    val isTtsReady: StateFlow<Boolean> = _isTtsReady.asStateFlow()

    private val _isSpeaking = MutableStateFlow(false)
    val isSpeaking: StateFlow<Boolean> = _isSpeaking.asStateFlow()

    private val _selectedLanguage = MutableStateFlow(SupportedLanguage.ENGLISH)
    val selectedLanguage: StateFlow<SupportedLanguage> = _selectedLanguage.asStateFlow()

    private val _isListening = MutableStateFlow(false)
    val isListening: StateFlow<Boolean> = _isListening.asStateFlow()

    private val _speechState = MutableStateFlow(SpeechState.IDLE)
    val speechState: StateFlow<SpeechState> = _speechState.asStateFlow()

    private val _isRecognitionAvailable = MutableStateFlow(false)
    val isRecognitionAvailable: StateFlow<Boolean> = _isRecognitionAvailable.asStateFlow()

    // Live audio RMS amplitude normalized between 0f and 1f for Compose pulsing wave
    private val _soundLevelRms = MutableStateFlow(0f)
    val soundLevelRms: StateFlow<Float> = _soundLevelRms.asStateFlow()

    // Live interim text updating as the user speaks into the microphone
    private val _interimSpeechText = MutableStateFlow("")
    val interimSpeechText: StateFlow<String> = _interimSpeechText.asStateFlow()

    // Final confirmed speech query delivered when user completes speech or taps Stop
    private val _finalSpeechQuery = MutableStateFlow("")
    val finalSpeechQuery: StateFlow<String> = _finalSpeechQuery.asStateFlow()
    val recognizedQuery: StateFlow<String> = _finalSpeechQuery

    private val _statusMessage = MutableStateFlow<String?>(null)
    val statusMessage: StateFlow<String?> = _statusMessage.asStateFlow()

    private val _lastSpokenText = MutableStateFlow("")
    val lastSpokenText: StateFlow<String> = _lastSpokenText.asStateFlow()

    private var activeSpeechCallback: ((String) -> Unit)? = null
    private var pendingTextToSpeak: String? = null

    // Session tracking and transient retry state
    private var isUserSessionActive = false
    private var isVoskSessionActive = false
    private var transientRetryCount = 0
    private val MAX_TRANSIENT_RETRIES = 2
    private var lastStartListeningTimeMs = 0L

    fun isNetworkAvailable(): Boolean {
        return try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
            val net = cm.activeNetwork ?: return false
            val caps = cm.getNetworkCapabilities(net) ?: return false
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        } catch (e: Exception) {
            false
        }
    }

    init {
        tts = TextToSpeech(context.applicationContext, this)
        _isRecognitionAvailable.value = SpeechRecognizer.isRecognitionAvailable(context)
        ensureSpeechRecognizerReady()
        voskEngine.checkModelInstallation()
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {
                    _isSpeaking.value = true
                    _speechState.value = SpeechState.SPEAKING
                }
                override fun onDone(utteranceId: String?) {
                    _isSpeaking.value = false
                    if (!_isListening.value) {
                        _speechState.value = SpeechState.IDLE
                    }
                }
                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) {
                    _isSpeaking.value = false
                    if (!_isListening.value) {
                        _speechState.value = SpeechState.IDLE
                    }
                }
            })
            applyLanguage(_selectedLanguage.value)
            _isTtsReady.value = true
            pendingTextToSpeak?.let {
                speakDirectly(it)
                pendingTextToSpeak = null
            }
        } else {
            Log.e("VoiceAssistant", "TextToSpeech initialization failed with code $status")
        }
    }

    fun setLanguage(lang: SupportedLanguage) {
        _selectedLanguage.value = lang
        applyLanguage(lang)
    }

    private fun applyLanguage(lang: SupportedLanguage) {
        try {
            tts?.let { engine ->
                val result = engine.setLanguage(lang.locale)
                if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                    engine.language = Locale.getDefault()
                }
                engine.setPitch(1.0f)
                engine.setSpeechRate(0.95f)
            }
        } catch (t: Throwable) {
            Log.w("VoiceAssistant", "applyLanguage error: ${t.message}")
        }
    }

    fun speakClassNavigation(destination: ResolvedClassDestination?) {
        if (destination == null) {
            val emptyMsg = when (_selectedLanguage.value) {
                SupportedLanguage.KANNADA -> "ಇಂದು ಯಾವುದೇ ತರಗತಿಗಳು ನಿಗದಿಯಾಗಿಲ್ಲ."
                SupportedLanguage.HINDI -> "आज के लिए कोई कक्षा निर्धारित नहीं है।"
                SupportedLanguage.ENGLISH -> "No scheduled classes found for today."
            }
            speakDirectly(emptyMsg)
            return
        }

        val speechText = generateLocalizedSpeech(destination, _selectedLanguage.value)
        speakDirectly(speechText)
    }

    // Mutual exclusion: Cancel listening when TTS starts speaking without re-triggering query!
    fun speakDirectly(text: String) {
        _lastSpokenText.value = text
        if (text.isBlank()) return

        // Mute mic if active so TTS doesn't feed into recording
        if (_isListening.value) {
            cancelListening()
        }

        if (!_isTtsReady.value || tts == null) {
            pendingTextToSpeak = text
            return
        }

        mainHandler.post {
            try {
                tts?.let { engine ->
                    applyLanguage(_selectedLanguage.value)
                    val params = Bundle()
                    params.putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, "prism_tts_${System.currentTimeMillis()}")
                    engine.speak(text, TextToSpeech.QUEUE_FLUSH, params, "prism_tts_utt")
                }
            } catch (t: Throwable) {
                Log.w("VoiceAssistant", "TTS speak failed safely: ${t.message}")
            }
        }
    }

    /**
     * Cancels listening immediately without emitting any final query.
     * Used when TTS begins speaking or dialog is dismissed.
     */
    fun cancelListening() {
        isUserSessionActive = false
        if (isVoskSessionActive) {
            try { voskEngine.stopListening() } catch (_: Exception) {}
            isVoskSessionActive = false
        }
        val action = Runnable {
            try { speechRecognizer?.cancel() } catch (_: Exception) {}
            _soundLevelRms.value = 0f
            _isListening.value = false
            _speechState.value = SpeechState.IDLE
            _interimSpeechText.value = ""
            _statusMessage.value = null
        }
        if (Looper.myLooper() == Looper.getMainLooper()) {
            action.run()
        } else {
            mainHandler.post(action)
        }
    }

    fun stopSpeaking() {
        tts?.stop()
        _isSpeaking.value = false
        if (!_isListening.value) {
            _speechState.value = SpeechState.IDLE
        }
    }

    private fun generateLocalizedSpeech(dest: ResolvedClassDestination, lang: SupportedLanguage): String {
        val course = dest.slot.courseName
        val building = dest.buildingName
        val floorLabel = dest.floorLabel
        val room = dest.roomId

        return when (lang) {
            SupportedLanguage.KANNADA -> {
                val buildingKn = when (dest.buildingCode) {
                    "AB" -> "ಅಪೆಕ್ಸ್ ಬ್ಲಾಕ್"
                    "ESB" -> "ಇಂಜಿನಿಯರಿಂಗ್ ಸೈನ್ಸಸ್ ಬ್ಲಾಕ್"
                    "LHC" -> "ಲೆಕ್ಚರ್ ಹಾಲ್ ಕಾಂಪ್ಲೆಕ್ಸ್"
                    else -> building
                }
                val floorKn = when (dest.floor) {
                    1 -> "1ನೇ ಮಹಡಿ"
                    2 -> "2ನೇ ಮಹಡಿ"
                    3 -> "3ನೇ ಮಹಡಿ"
                    4 -> "4ನೇ ಮಹಡಿ"
                    5 -> "5ನೇ ಮಹಡಿ"
                    else -> "${dest.floor}ನೇ ಮಹಡಿ"
                }
                "ನಿಮ್ಮ ಮುಂದಿನ ತರಗತಿ $buildingKn, $floorKn, ಕೊಠಡಿ $room ರಲ್ಲಿದೆ. ವಿಷಯ: $course."
            }
            SupportedLanguage.HINDI -> {
                val buildingHi = when (dest.buildingCode) {
                    "AB" -> "एपेक्स ब्लॉक"
                    "ESB" -> "इंजीनियरिंग साइंसेज ब्लॉक"
                    "LHC" -> "लेक्चर हॉल कॉम्प्लेक्स"
                    else -> building
                }
                val floorHi = when (dest.floor) {
                    1 -> "पहली मंजिल"
                    2 -> "दूसरी मंजिल"
                    3 -> "तीसरी मंजिल"
                    4 -> "चौथी मंजिल"
                    5 -> "पांचवीं मंजिल"
                    else -> "${dest.floor}वीं मंजिल"
                }
                "आपकी अगली कक्षा $buildingHi, $floorHi, रूम $room में है। विषय: $course."
            }
            SupportedLanguage.ENGLISH -> {
                "Your next class is in $building, $floorLabel, Room $room. Course: $course."
            }
        }
    }

    private fun ensureSpeechRecognizerReady() {
        val action = Runnable {
            try {
                if (!SpeechRecognizer.isRecognitionAvailable(context)) {
                    _isRecognitionAvailable.value = false
                    _speechState.value = SpeechState.UNAVAILABLE
                    Log.w("VoiceAssistant", "SpeechRecognizer service is not available on this device")
                    return@Runnable
                }
                _isRecognitionAvailable.value = true

                try {
                    speechRecognizer?.destroy()
                } catch (e: Exception) {
                    Log.w("VoiceAssistant", "Error destroying previous recognizer: ${e.message}")
                }
                speechRecognizer = null

                speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context)
                setupRecognitionListener()
                Log.i("VoiceAssistant", "Standard SpeechRecognizer initialized successfully")
            } catch (e: Exception) {
                Log.e("VoiceAssistant", "Failed to create SpeechRecognizer: ${e.message}")
                _isRecognitionAvailable.value = false
                _speechState.value = SpeechState.UNAVAILABLE
            }
        }

        if (Looper.myLooper() == Looper.getMainLooper()) {
            action.run()
        } else {
            mainHandler.post(action)
        }
    }

    private fun setupRecognitionListener() {
        speechRecognizer?.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                Log.d("VoiceAssistant", "Online SpeechRecognizer: onReadyForSpeech")
                _isListening.value = true
                _speechState.value = SpeechState.LISTENING
                _statusMessage.value = "Listening in ${_selectedLanguage.value.displayName}..."
            }

            override fun onBeginningOfSpeech() {
                Log.d("VoiceAssistant", "Online SpeechRecognizer: onBeginningOfSpeech")
                _isListening.value = true
                _speechState.value = SpeechState.LISTENING
                _statusMessage.value = "Hearing your voice..."
            }

            override fun onRmsChanged(rmsdB: Float) {
                val normalized = ((rmsdB + 2f) / 14f).coerceIn(0.05f, 1.0f)
                _soundLevelRms.value = normalized
            }

            override fun onBufferReceived(buffer: ByteArray?) {}

            override fun onEndOfSpeech() {
                Log.d("VoiceAssistant", "Online SpeechRecognizer: onEndOfSpeech")
                _soundLevelRms.value = 0f
                _speechState.value = SpeechState.PROCESSING
                _statusMessage.value = "Processing speech..."
            }

            override fun onError(error: Int) {
                _soundLevelRms.value = 0f
                val errorName = getSpeechErrorName(error)
                Log.w("VoiceAssistant", "Speech recognition error: $error ($errorName)")

                // 1. If interim speech text was already captured before error, finalize with it!
                val fallbackText = _interimSpeechText.value.trim()
                if (fallbackText.isNotBlank()) {
                    Log.i("VoiceAssistant", "Finalizing with captured interim text: '$fallbackText'")
                    completeSession(fallbackText)
                    return
                }

                // 2. If offline error, switch to Vosk engine
                if (!isNetworkAvailable()) {
                    Log.i("VoiceAssistant", "Offline error $error: switching to Vosk on-device engine")
                    startOfflineVoiceRecognition()
                    return
                }

                // 3. Handle transient silence errors (6: SPEECH_TIMEOUT, 7: NO_MATCH) with keep-alive retry
                if ((error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT)
                    && isUserSessionActive && transientRetryCount < MAX_TRANSIENT_RETRIES) {
                    transientRetryCount++
                    Log.i("VoiceAssistant", "Transient error $error: keep-alive retry $transientRetryCount of $MAX_TRANSIENT_RETRIES")
                    _speechState.value = SpeechState.RETRYING
                    _statusMessage.value = "Didn't catch that, still listening..."
                    mainHandler.postDelayed({
                        if (isUserSessionActive) {
                            restartOnlineListeningInternal()
                        }
                    }, 300L)
                    return
                }

                // 4. Permanent error
                _isListening.value = false
                isUserSessionActive = false
                _speechState.value = SpeechState.ERROR
                _statusMessage.value = when (error) {
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Microphone permission required"
                    SpeechRecognizer.ERROR_NO_MATCH -> "No speech detected. Tap mic to try again."
                    SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Listening timed out. Tap mic to try again."
                    SpeechRecognizer.ERROR_NETWORK -> "Network unavailable for speech recognition."
                    SpeechRecognizer.ERROR_CLIENT -> "Speech recognition client error. Tap mic or use prompt chips."
                    else -> "Speech recognition error ($error). Tap mic or use prompt chips."
                }
            }

            override fun onResults(results: Bundle?) {
                _soundLevelRms.value = 0f
                val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                val recognized = matches?.firstOrNull()?.trim() ?: _interimSpeechText.value.trim()
                Log.i("VoiceAssistant", "Online SpeechRecognizer: onResults -> '$recognized'")
                completeSession(recognized)
            }

            override fun onPartialResults(partialResults: Bundle?) {
                val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                if (!matches.isNullOrEmpty()) {
                    val partial = matches[0].trim()
                    _interimSpeechText.value = partial
                    Log.d("VoiceAssistant", "Online SpeechRecognizer: onPartialResults -> '$partial'")
                }
            }

            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
    }

    private fun getSpeechErrorName(error: Int): String {
        return when (error) {
            SpeechRecognizer.ERROR_AUDIO -> "ERROR_AUDIO (3)"
            SpeechRecognizer.ERROR_CLIENT -> "ERROR_CLIENT (5)"
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "ERROR_INSUFFICIENT_PERMISSIONS (9)"
            SpeechRecognizer.ERROR_NETWORK -> "ERROR_NETWORK (2)"
            SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "ERROR_NETWORK_TIMEOUT (1)"
            SpeechRecognizer.ERROR_NO_MATCH -> "ERROR_NO_MATCH (7)"
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "ERROR_RECOGNIZER_BUSY (8)"
            SpeechRecognizer.ERROR_SERVER -> "ERROR_SERVER (4)"
            SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "ERROR_SPEECH_TIMEOUT (6)"
            else -> "UNKNOWN ($error)"
        }
    }

    private fun completeSession(text: String) {
        _isListening.value = false
        _speechState.value = if (text.isNotBlank()) SpeechState.PROCESSING else SpeechState.IDLE
        isUserSessionActive = false
        isVoskSessionActive = false
        transientRetryCount = 0
        _interimSpeechText.value = ""
        _soundLevelRms.value = 0f
        _statusMessage.value = null

        if (text.isNotBlank()) {
            _finalSpeechQuery.value = text
            activeSpeechCallback?.invoke(text)
            activeSpeechCallback = null
        }
    }

    /**
     * Start listening to user voice.
     * Routes to Google Cloud SpeechRecognizer if online, or Vosk on-device model if offline.
     */
    fun startListening(onResult: ((String) -> Unit)? = null) {
        val now = System.currentTimeMillis()
        if (now - lastStartListeningTimeMs < 400L) {
            Log.d("VoiceAssistant", "Debouncing rapid startListening call")
            return
        }
        lastStartListeningTimeMs = now

        // 1. Hard mutual exclusion: silence TTS before opening microphone
        stopSpeaking()

        // 2. Check runtime audio permission
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            _statusMessage.value = "Microphone permission required"
            _speechState.value = SpeechState.REQUESTING_PERMISSION
            return
        }

        // 3. Reset session parameters
        activeSpeechCallback = onResult
        _interimSpeechText.value = ""
        _finalSpeechQuery.value = ""
        _statusMessage.value = null
        _speechState.value = SpeechState.INITIALIZING
        isUserSessionActive = true
        transientRetryCount = 0

        // 4. Select engine based on network availability
        if (!isNetworkAvailable()) {
            startOfflineVoiceRecognition()
        } else {
            restartOnlineListeningInternal()
        }
    }

    /**
     * Activates 100% on-device Vosk speech recognition engine when offline.
     */
    private fun startOfflineVoiceRecognition() {
        Log.i("VoiceAssistant", "Recognition engine selected: VoskOfflineSpeechEngine (100% On-Device)")
        val isInstalled = voskEngine.checkModelInstallation()

        if (!isInstalled) {
            _isListening.value = false
            _speechState.value = SpeechState.ERROR
            _statusMessage.value = "Offline speech model not installed (~40 MB). Tap 'Model Setup' or use typed chat."
            Log.w("VoiceAssistant", "Offline model missing. Storage checked: ${voskEngine.getPreferredModelDir().absolutePath}")
            isUserSessionActive = false
            return
        }

        isVoskSessionActive = true
        _isListening.value = true
        _speechState.value = SpeechState.LISTENING
        _statusMessage.value = "Listening offline (Vosk English on-device)..."

        voskEngine.startListening(
            onPartialResult = { partial ->
                _interimSpeechText.value = partial
                Log.d("VoiceAssistant", "Vosk partial: '$partial'")
            },
            onFinalResult = { finalTranscript ->
                Log.i("VoiceAssistant", "Vosk final transcript: '$finalTranscript'")
                completeSession(finalTranscript)
            },
            onRmsChanged = { normRms ->
                _soundLevelRms.value = normRms
            },
            onError = { err ->
                Log.w("VoiceAssistant", "Vosk engine error: $err")
                _isListening.value = false
                _speechState.value = SpeechState.ERROR
                _statusMessage.value = err
                isUserSessionActive = false
                isVoskSessionActive = false
            }
        )
    }

    private fun restartOnlineListeningInternal() {
        val action = Runnable {
            if (!isUserSessionActive) return@Runnable

            try {
                if (speechRecognizer == null) {
                    ensureSpeechRecognizerReady()
                }
                val recognizer = speechRecognizer
                if (recognizer == null) {
                    startOfflineVoiceRecognition()
                    return@Runnable
                }

                val langTag = when (_selectedLanguage.value) {
                    SupportedLanguage.ENGLISH -> "en-US"
                    SupportedLanguage.HINDI -> "hi-IN"
                    SupportedLanguage.KANNADA -> "kn-IN"
                }

                val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, langTag)
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, langTag)
                    putExtra("android.speech.extra.EXTRA_ADDITIONAL_LANGUAGES", arrayOf("en-US", "en-IN"))
                    putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
                    putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                    putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)

                    // Finalize short campus commands promptly after the user pauses.
                    putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1200L)
                    putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 1000L)
                    putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 500L)
                }

                recognizer.startListening(intent)
                _isListening.value = true
                _speechState.value = SpeechState.LISTENING
                _statusMessage.value = "Listening in ${_selectedLanguage.value.displayName}..."
                Log.i("VoiceAssistant", "Google SpeechRecognizer.startListening initiated successfully")
            } catch (e: Exception) {
                Log.e("VoiceAssistant", "Online startListening exception: ${e.message}")
                startOfflineVoiceRecognition()
            }
        }

        if (Looper.myLooper() == Looper.getMainLooper()) {
            action.run()
        } else {
            mainHandler.post(action)
        }
    }

    /**
     * Stop listening immediately and finalize any captured speech.
     */
    fun stopListening() {
        isUserSessionActive = false

        if (isVoskSessionActive) {
            val text = voskEngine.stopListening()
            _soundLevelRms.value = 0f
            isVoskSessionActive = false
            if (text.isNotBlank()) {
                completeSession(text)
            } else {
                val pending = _interimSpeechText.value.trim()
                if (pending.isNotBlank()) {
                    completeSession(pending)
                } else {
                    _isListening.value = false
                    _speechState.value = SpeechState.IDLE
                    _statusMessage.value = null
                }
            }
            return
        }

        val action = Runnable {
            try {
                speechRecognizer?.stopListening()
            } catch (e: Exception) {
                Log.w("VoiceAssistant", "stopListening exception: ${e.message}")
            }

            _soundLevelRms.value = 0f
            val pending = _interimSpeechText.value.trim()

            if (pending.isNotBlank()) {
                completeSession(pending)
            } else {
                _isListening.value = false
                _speechState.value = SpeechState.IDLE
                _statusMessage.value = null
            }
        }

        if (Looper.myLooper() == Looper.getMainLooper()) {
            action.run()
        } else {
            mainHandler.post(action)
        }
    }

    fun clearFinalSpeechQuery() {
        _finalSpeechQuery.value = ""
    }

    fun clearRecognizedQuery() {
        _finalSpeechQuery.value = ""
        _interimSpeechText.value = ""
    }

    fun shutdown() {
        stopSpeaking()
        isUserSessionActive = false
        isVoskSessionActive = false
        voskEngine.shutdown()
        tts?.shutdown()
        tts = null
        try {
            speechRecognizer?.destroy()
        } catch (e: Exception) {}
        speechRecognizer = null
    }
}
