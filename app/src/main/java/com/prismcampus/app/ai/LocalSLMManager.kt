package com.prismcampus.app.ai

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/** Runs the bundled Qwen GGUF with llama.cpp, entirely on the device. */
class LocalSLMManager(private val context: Context) {
    private val inferenceMutex = Mutex()
    private val hybridParser = HybridParser()
    private val _isModelLoaded = MutableStateFlow(false)
    val isModelLoaded = _isModelLoaded.asStateFlow()
    private val _engineStatus = MutableStateFlow("Qwen available on first offline question")
    val engineStatus = _engineStatus.asStateFlow()
    private val _lastInferenceLatency = MutableStateFlow(0L)
    val lastInferenceLatency = _lastInferenceLatency.asStateFlow()
    private var activeModelPath: String? = null

    companion object {
        const val MODEL_NAME = "qwen2.5-0.5b-instruct-q4_k_m.gguf"
    }

    fun getCandidateModelPaths(): List<File> = listOfNotNull(
        File(context.filesDir, "models/$MODEL_NAME"),
        context.getExternalFilesDir(null)?.let { File(it, "models/$MODEL_NAME") },
        File(context.filesDir, MODEL_NAME)
    )

    fun getActiveModelPath(): String? = activeModelPath

    /** Called on a worker thread; atomically installs bundled weights once, without a download. */
    @Synchronized
    fun checkAndInitializeModel() {
        try {
            var model = getCandidateModelPaths().firstOrNull { it.isFile && it.length() > 5 * 1024 * 1024 }
            if (model == null) {
                _engineStatus.value = "Installing bundled Qwen model..."
                val target = getCandidateModelPaths().first()
                target.parentFile?.mkdirs()
                val temporary = File(target.parentFile, "$MODEL_NAME.partial")
                try {
                    context.assets.open(MODEL_NAME).use { input ->
                        temporary.outputStream().use { output -> input.copyTo(output) }
                    }
                    check(temporary.renameTo(target)) { "Could not install Qwen model" }
                } finally {
                    temporary.delete()
                }
                model = target
            }
            activeModelPath = model.absolutePath
            _isModelLoaded.value = true
            _engineStatus.value = "Qwen2.5-0.5B ready (llama.cpp, on-device)"
        } catch (e: Exception) {
            _isModelLoaded.value = false
            _engineStatus.value = "Qwen unavailable: ${e.message}"
            Log.w("LocalSLM", "Qwen model installation failed", e)
        }
    }

    suspend fun generateChatResponse(prompt: String): String? = withContext(Dispatchers.IO) {
        inferenceMutex.withLock {
            if (!_isModelLoaded.value) checkAndInitializeModel()
            val path = activeModelPath ?: return@withLock null
            val start = System.currentTimeMillis()
            try {
                _engineStatus.value = "Qwen generating offline..."
                val response = QwenNative.generate(path, prompt.toByteArray(Charsets.UTF_8),
                    AppMemoryBudget.generationLimitMiB(com.prismcampus.app.BuildConfig.MEMORY_PROFILING)).toString(Charsets.UTF_8)
                _lastInferenceLatency.value = System.currentTimeMillis() - start
                _engineStatus.value = "Qwen ready; model memory released"
                response.takeIf { it.isNotBlank() }
            } catch (e: Exception) {
                _engineStatus.value = "Qwen: ${e.message}"
                Log.w("LocalSLM", "Offline inference failed", e)
                null
            } catch (e: LinkageError) {
                _engineStatus.value = "Qwen native engine unavailable for this device"
                Log.w("LocalSLM", "Native library unavailable", e)
                null
            }
        }
    }

    fun testInference(): String = _engineStatus.value

    suspend fun analyzeText(rawText: String): com.prismcampus.app.data.OCRParseResult =
        withContext(Dispatchers.Default) {
            val start = System.currentTimeMillis()
            val result = hybridParser.parseTimetableText(rawText)
            _lastInferenceLatency.value = System.currentTimeMillis() - start
            result
        }
}
