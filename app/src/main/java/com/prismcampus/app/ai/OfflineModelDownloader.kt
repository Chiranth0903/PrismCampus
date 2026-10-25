package com.prismcampus.app.ai

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream

sealed class ModelDownloadState {
    object Idle : ModelDownloadState()
    data class Downloading(val progressPercent: Int, val downloadedBytes: Long, val totalBytes: Long) : ModelDownloadState()
    object Extracting : ModelDownloadState()
    object Completed : ModelDownloadState()
    data class Error(val message: String) : ModelDownloadState()
}

/**
 * Downloads and extracts the lightweight English offline speech model (~40 MB).
 * Strategy:
 * 1. Checks if pre-bundled in APK assets (zero internet needed).
 * 2. Checks local Uno Q edge server over Wi-Fi (LAN speed, zero internet needed).
 * 3. Falls back to public mirrors (Alphacephei, GitHub) if internet is available.
 */
class OfflineModelDownloader(private val context: Context) {

    private val _downloadState = MutableStateFlow<ModelDownloadState>(ModelDownloadState.Idle)
    val downloadState: StateFlow<ModelDownloadState> = _downloadState.asStateFlow()

    companion object {
        const val VOSK_ASSET_NAME = "vosk-model-small-en-us-0.15.zip"
        const val VOSK_MODEL_NAME = "vosk-model-small-en-us-0.15"

        val CANDIDATE_DOWNLOAD_URLS = listOf(
            "http://192.168.1.8:8000/models/vosk-model-small-en-us-0.15.zip",
            "http://10.0.2.2:8000/models/vosk-model-small-en-us-0.15.zip",
            "http://192.168.4.1:8000/models/vosk-model-small-en-us-0.15.zip",
            "https://alphacephei.com/vosk/models/vosk-model-small-en-us-0.15.zip",
            "https://github.com/alphacep/vosk-android-demo/releases/download/v0.1.0/vosk-model-small-en-us-0.15.zip"
        )
    }

    suspend fun downloadAndInstallModel(): Boolean = withContext(Dispatchers.IO) {
        val targetDir = File(context.filesDir, "models")
        if (!targetDir.exists()) targetDir.mkdirs()

        // 1. Check if model zip is pre-bundled in APK assets!
        val bundledAssetStream: InputStream? = try {
            context.assets.open(VOSK_ASSET_NAME)
        } catch (_: Exception) {
            null
        }

        if (bundledAssetStream != null) {
            Log.i("ModelDownloader", "Found pre-bundled Vosk model in assets! Extracting locally with zero internet needed...")
            _downloadState.value = ModelDownloadState.Extracting
            return@withContext try {
                bundledAssetStream.use { input ->
                    ZipInputStream(BufferedInputStream(input)).use { zis ->
                        extractZipStream(zis, targetDir)
                    }
                }
                Log.i("ModelDownloader", "Bundled model extracted successfully to ${File(targetDir, VOSK_MODEL_NAME).absolutePath}")
                _downloadState.value = ModelDownloadState.Completed
                true
            } catch (e: Exception) {
                Log.e("ModelDownloader", "Failed extracting bundled asset: ${e.message}", e)
                _downloadState.value = ModelDownloadState.Error("Extraction failed: ${e.message}")
                false
            }
        }

        // 2. Fallback to candidate download URLs (local Uno Q server or public mirrors)
        val zipFile = File(context.cacheDir, "$VOSK_MODEL_NAME.zip")
        for (candidateUrl in CANDIDATE_DOWNLOAD_URLS) {
            try {
                Log.i("ModelDownloader", "Attempting model download from: $candidateUrl")
                _downloadState.value = ModelDownloadState.Downloading(0, 0, 41205931L)

                val url = URL(candidateUrl)
                val connection = (url.openConnection() as HttpURLConnection).apply {
                    connectTimeout = 4000
                    readTimeout = 25000
                    instanceFollowRedirects = true
                }

                if (connection.responseCode !in 200..299) {
                    Log.w("ModelDownloader", "URL $candidateUrl returned HTTP ${connection.responseCode}")
                    connection.disconnect()
                    continue
                }

                val totalSize = connection.contentLength.toLong().let { if (it > 0) it else 41205931L }
                var downloaded = 0L

                BufferedInputStream(connection.inputStream).use { input ->
                    FileOutputStream(zipFile).use { output ->
                        val buffer = ByteArray(8192)
                        var count: Int
                        var lastUpdateMs = 0L

                        while (input.read(buffer).also { count = it } != -1) {
                            output.write(buffer, 0, count)
                            downloaded += count

                            val now = System.currentTimeMillis()
                            if (now - lastUpdateMs > 150L) {
                                lastUpdateMs = now
                                val pct = ((downloaded * 100) / totalSize).toInt().coerceIn(0, 99)
                                _downloadState.value = ModelDownloadState.Downloading(pct, downloaded, totalSize)
                            }
                        }
                    }
                }

                Log.i("ModelDownloader", "Download complete ($downloaded bytes). Extracting zip...")
                _downloadState.value = ModelDownloadState.Extracting

                ZipInputStream(BufferedInputStream(zipFile.inputStream())).use { zis ->
                    extractZipStream(zis, targetDir)
                }

                zipFile.delete()
                Log.i("ModelDownloader", "Model successfully extracted to ${File(targetDir, VOSK_MODEL_NAME).absolutePath}")
                _downloadState.value = ModelDownloadState.Completed
                return@withContext true
            } catch (e: Exception) {
                Log.w("ModelDownloader", "Download failed from $candidateUrl: ${e.message}")
                try { zipFile.delete() } catch (_: Exception) {}
            }
        }

        val finalError = "Could not reach model server. Connect to Uno Q Wi-Fi or turn on internet."
        Log.e("ModelDownloader", finalError)
        _downloadState.value = ModelDownloadState.Error(finalError)
        false
    }

    private fun extractZipStream(zis: ZipInputStream, targetDir: File) {
        var entry = zis.nextEntry
        val buffer = ByteArray(8192)

        while (entry != null) {
            val entryFile = File(targetDir, entry.name)

            // Prevent zip-slip vulnerability
            if (!entryFile.canonicalPath.startsWith(targetDir.canonicalPath)) {
                throw SecurityException("Zip entry is outside target dir: ${entry.name}")
            }

            if (entry.isDirectory) {
                entryFile.mkdirs()
            } else {
                entryFile.parentFile?.mkdirs()
                FileOutputStream(entryFile).use { fos ->
                    var count: Int
                    while (zis.read(buffer).also { count = it } != -1) {
                        fos.write(buffer, 0, count)
                    }
                }
            }
            zis.closeEntry()
            entry = zis.nextEntry
        }
    }
}
