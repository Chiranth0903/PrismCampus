package com.prismcampus.app.ai

import android.content.Context
import android.util.Log
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader

/**
 * Loads and manages natural-language assistant rules from an editable Markdown file.
 * Both Gemini (online) and Qwen (offline) share these rules for system prompts.
 *
 * Dynamically reloads when the rules file is edited on disk (checked via file timestamp),
 * so changes take effect without recompiling the app.
 */
class AssistantRulesManager(private val context: Context) {

    private var cachedRules: String? = null
    private var lastLoadedTimestamp: Long = 0L
    private var activeSourceDescription: String = "Default Fallback"

    companion object {
        const val RULES_FILENAME = "assistant_rules.md"
        private const val TAG = "AssistantRules"

        val DEFAULT_RULES = """
            - Keep answers concise and direct (typically 2 to 3 sentences) unless more detail is requested.
            - Use simple, accessible language and briefly explain technical terms.
            - Use the selected campus records only. Never substitute another college's facts.
            - Answer general definitions generally; do not add campus placement or salary examples.
            - If you do not know something, state so clearly instead of inventing an answer.
            - When offline, clearly state when a question cannot be answered reliably using local information.
        """.trimIndent()
    }

    init {
        loadRules()
    }

    /**
     * Resolves the candidate file locations in order of editability priority:
     * 1. External files directory (e.g. /sdcard/Android/data/.../files/config/assistant_rules.md)
     * 2. Internal app files directory (e.g. /data/data/.../files/assistant_rules.md)
     */
    fun getCandidateFiles(): List<File> {
        val list = mutableListOf<File>()
        context.getExternalFilesDir(null)?.let { ext ->
            list.add(File(ext, "config/$RULES_FILENAME"))
            list.add(File(ext, RULES_FILENAME))
        }
        list.add(File(context.filesDir, "config/$RULES_FILENAME"))
        list.add(File(context.filesDir, RULES_FILENAME))
        return list
    }

    /**
     * Retrieves the current active assistant rules, automatically reloading
     * if the underlying configuration file was modified on disk.
     */
    @Synchronized
    fun getRules(): String {
        val candidate = getCandidateFiles().find { it.exists() && it.isFile }
        if (candidate != null) {
            val mtime = candidate.lastModified()
            if (mtime > lastLoadedTimestamp || cachedRules == null) {
                try {
                    val content = candidate.readText().trim()
                    if (content.isNotBlank()) {
                        cachedRules = content
                        lastLoadedTimestamp = mtime
                        activeSourceDescription = candidate.absolutePath
                        Log.i(TAG, "Reloaded updated assistant rules from: ${candidate.absolutePath}")
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Error reading candidate rules file: ${e.message}")
                }
            }
        }

        if (cachedRules != null) {
            return cachedRules!!
        }

        return loadRules()
    }

    /**
     * Initial or full load with asset fallback.
     */
    @Synchronized
    private fun loadRules(): String {
        // 1. Try file candidates
        val candidate = getCandidateFiles().find { it.exists() && it.isFile }
        if (candidate != null) {
            try {
                val content = candidate.readText().trim()
                if (content.isNotBlank()) {
                    cachedRules = content
                    lastLoadedTimestamp = candidate.lastModified()
                    activeSourceDescription = candidate.absolutePath
                    Log.i(TAG, "Loaded assistant rules from: ${candidate.absolutePath}")
                    return content
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed reading ${candidate.absolutePath}: ${e.message}")
            }
        }

        // 2. Try pre-bundled APK asset
        try {
            context.assets.open(RULES_FILENAME).use { inputStream ->
                InputStreamReader(inputStream).use { reader ->
                    val content = reader.readText().trim()
                    if (content.isNotBlank()) {
                        cachedRules = content
                        lastLoadedTimestamp = System.currentTimeMillis()
                        activeSourceDescription = "Bundled Asset ($RULES_FILENAME)"
                        Log.i(TAG, "Loaded assistant rules from bundled APK asset")

                        // Copy to internal filesDir so it can be edited on-device
                        copyAssetToFile(content)
                        return content
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Bundled asset $RULES_FILENAME not found or unreadable: ${e.message}")
        }

        // 3. Sensible default fallback
        cachedRules = DEFAULT_RULES
        activeSourceDescription = "Built-in Defaults"
        Log.w(TAG, "Using built-in default assistant rules")
        return DEFAULT_RULES
    }

    private fun copyAssetToFile(content: String) {
        try {
            val target = File(context.filesDir, RULES_FILENAME)
            if (!target.exists()) {
                target.writeText(content)
                Log.i(TAG, "Seeded editable rules file to: ${target.absolutePath}")
            }
        } catch (_: Exception) {}
    }

    /**
     * Saves updated rules received over local Wi-Fi from Uno Q edge server or admin.
     */
    @Synchronized
    fun updateRules(newContent: String): Boolean {
        if (newContent.isBlank()) return false
        return try {
            val target = File(context.filesDir, RULES_FILENAME)
            target.writeText(newContent.trim())
            cachedRules = newContent.trim()
            lastLoadedTimestamp = target.lastModified()
            activeSourceDescription = target.absolutePath
            Log.i(TAG, "Successfully updated assistant rules file: ${target.absolutePath}")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to write rules file: ${e.message}", e)
            false
        }
    }

    fun getActiveSourceDescription(): String = activeSourceDescription
}
