package com.prismcampus.app.ai

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

/**
 * Cloud Gemini AI Provider.
 * First checks Uno Q backend edge proxy (keeps API key secure on server),
 * with fallback to direct Google AI Studio API for client-configured keys.
 */
class GeminiProvider(
    private val context: Context,
    private val retrievalService: CampusRetrievalService,
    private val rulesManager: AssistantRulesManager = AssistantRulesManager(context)
) {
    private val gson = Gson()

    // Upgraded model list with active v1beta endpoints
    // Flash Lite has its own quota; try it before an alias that may share Flash's quota.
    private val supportedModels = listOf("gemini-2.5-flash", "gemini-2.5-flash-lite", "gemini-flash-latest")

    var lastConfigError: String? = null
        private set

    // Uno Q Edge Server endpoints (keeps production Gemini key on backend server)
    private val unoQChatEndpoints = listOf(
        "http://192.168.1.8:8000/api/ai/chat",
        "http://10.0.2.2:8000/api/ai/chat",
        "http://192.168.4.1:8000/api/ai/chat"
    )

    fun isOnline(): Boolean {
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

    suspend fun generateResponse(
        query: String,
        lang: SupportedLanguage,
        timeoutMs: Int = 3500
    ): ProviderResult? = withContext(Dispatchers.IO) {
        lastConfigError = null
        val startTime = System.currentTimeMillis()
        val deadline = android.os.SystemClock.elapsedRealtime() + timeoutMs

        if (!isOnline()) {
            Log.d("GeminiProvider", "Offline: skipping Gemini cloud call")
            return@withContext null
        }

        val rules = CampusAnswerPolicy.rules(rulesManager.getRules())
        val campusContext = retrievalService.buildRetrievalContext(query)

        // 1. Direct Gemini API call first (Fastest, zero intermediary hops, works anywhere online)
        val apiKey = try {
            val clazz = Class.forName("com.prismcampus.app.BuildConfig")
            clazz.getField("GEMINI_API_KEY").get(null) as? String ?: ""
        } catch (_: Throwable) {
            ""
        }

        val systemPrompt = """
            ${retrievalService.campusIdentity()}
            
            Assistant Rules & Guidelines:
            $rules
            
            Ground Truth Campus Information:
            $campusContext
            
            Language: ${lang.displayName}
            Question: "$query"
        """.trimIndent()

        if (apiKey.isNotBlank() && !apiKey.contains("sample_key_here") && !apiKey.contains("YOUR_API_KEY")) {
            for (model in supportedModels) {
                val remainingMs = (deadline - android.os.SystemClock.elapsedRealtime()).toInt()
                if (remainingMs <= 0) return@withContext null
                try {
                    val endpoint = "https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent"
                    val conn = (URL(endpoint).openConnection() as HttpURLConnection).apply {
                        requestMethod = "POST"
                        setRequestProperty("Content-Type", "application/json")
                        setRequestProperty("x-goog-api-key", apiKey)
                        connectTimeout = remainingMs.coerceAtMost(3000)
                        readTimeout = remainingMs
                        doOutput = true
                    }

                    val requestJson = JsonObject().apply {
                        val contentsArr = JsonArray()
                        val contentObj = JsonObject()
                        val partsArr = JsonArray()
                        val partObj = JsonObject()
                        partObj.addProperty("text", systemPrompt)
                        partsArr.add(partObj)
                        contentObj.add("parts", partsArr)
                        contentsArr.add(contentObj)
                        add("contents", contentsArr)

                        val genConfig = JsonObject().apply {
                            addProperty("maxOutputTokens", 300)
                            addProperty("temperature", 0.7)
                            if (model.startsWith("gemini-2.5-flash")) {
                                add("thinkingConfig", JsonObject().apply { addProperty("thinkingBudget", 0) })
                            }
                        }
                        add("generationConfig", genConfig)
                    }

                    OutputStreamWriter(conn.outputStream).use { it.write(requestJson.toString()) }

                    if (conn.responseCode == 200) {
                        val resp = conn.inputStream.bufferedReader().use(BufferedReader::readText)
                        val json = gson.fromJson(resp, JsonObject::class.java)
                        val answer = json.getAsJsonArray("candidates")
                            ?.get(0)?.asJsonObject
                            ?.getAsJsonObject("content")
                            ?.getAsJsonArray("parts")
                            ?.get(0)?.asJsonObject
                            ?.get("text")?.asString

                        if (!answer.isNullOrBlank()) {
                            val latency = System.currentTimeMillis() - startTime
                            Log.i("GeminiProvider", "Direct Gemini ($model) succeeded in ${latency}ms")
                            return@withContext ProviderResult(
                                text = answer.trim(),
                                tier = ExecutionTier.TIER_3_CLOUD_GEMINI,
                                providerName = "Gemini ($model)",
                                latencyMs = latency
                            )
                        }
                    } else {
                        Log.w("GeminiProvider", "Gemini HTTP ${conn.responseCode} on model $model")
                        if (conn.responseCode in listOf(400, 401, 403)) {
                            lastConfigError = "Gemini Authentication / Configuration Error (HTTP ${conn.responseCode}): Please verify Gemini API key."
                            return@withContext null
                        }
                    }
                } catch (e: Exception) {
                    Log.w("GeminiProvider", "Gemini call exception on model $model: ${e.message}")
                }
            }
        }

        // 2. Fallback to Uno Q / Laptop Edge Server backend proxy
        val activeBase = try {
            retrievalService.repository.serverBaseUrl.value.removeSuffix("/")
        } catch (_: Throwable) { "" }
        val endpointsToTry = listOf(
            "$activeBase/api/ai/chat",
            "http://127.0.0.1:8000/api/ai/chat",
            "http://10.0.2.2:8000/api/ai/chat",
            "http://192.168.1.8:8000/api/ai/chat"
        ).filter { it.startsWith("http://") || it.startsWith("https://") }.distinct()

        for (backendUrl in endpointsToTry) {
            val remainingMs = (deadline - android.os.SystemClock.elapsedRealtime()).toInt()
            if (remainingMs <= 0) return@withContext null
            try {
                val conn = (URL(backendUrl).openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    setRequestProperty("Content-Type", "application/json")
                    connectTimeout = remainingMs.coerceAtMost(1500)
                    readTimeout = remainingMs
                    doOutput = true
                }

                val payload = JsonObject().apply {
                    addProperty("campus_id", retrievalService.repository.activeCampusId.value)
                    addProperty("query", query)
                    addProperty("language", lang.code)
                    addProperty("context", campusContext)
                    addProperty("rules", rules)
                    if (apiKey.isNotBlank()) {
                        addProperty("api_key", apiKey)
                    }
                }

                OutputStreamWriter(conn.outputStream).use { it.write(payload.toString()) }

                if (conn.responseCode == 200) {
                    val resp = conn.inputStream.bufferedReader().use(BufferedReader::readText)
                    val json = gson.fromJson(resp, JsonObject::class.java)
                    if (json.has("response") && !json.get("response").asString.isNullOrBlank()) {
                        val answer = json.get("response").asString
                        val latency = System.currentTimeMillis() - startTime
                        Log.i("GeminiProvider", "Uno Q Backend Gemini response received in ${latency}ms")
                        return@withContext ProviderResult(
                            text = answer.trim(),
                            tier = ExecutionTier.TIER_3_CLOUD_GEMINI,
                            providerName = json.get("provider")?.asString ?: "Gemini (Uno Q Edge)",
                            latencyMs = latency
                        )
                    }
                }
            } catch (_: Exception) {
                // Backend proxy unavailable or offline, try next
            }
        }

        null
    }
}

data class ProviderResult(
    val text: String,
    val tier: ExecutionTier,
    val providerName: String,
    val latencyMs: Long
)
