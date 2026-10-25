package com.prismcampus.app.ai

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Offline questions stay on this device; do not probe unreachable LAN servers. */
class LocalQwenProvider(
    private val context: Context,
    private val localSlmManager: LocalSLMManager,
    private val retrievalService: CampusRetrievalService,
    private val rulesManager: AssistantRulesManager = AssistantRulesManager(context)
) {
    fun isModelAvailable(): Boolean = localSlmManager.isModelLoaded.value

    suspend fun generateResponse(query: String, lang: SupportedLanguage): ProviderResult? = withContext(Dispatchers.IO) {
        val start = System.currentTimeMillis()
        // General questions don't need the entire campus timetable in their prompt.
        val campusContext = if (!AssistantIntent.needsGeneralAnswer(query)) {
            retrievalService.buildRetrievalContext(query).take(500)
        } else retrievalService.campusIdentity()
        val prompt = OfflinePrompt.build(query, lang.displayName, campusContext,
            CampusAnswerPolicy.rules(rulesManager.getRules()).take(300))
        val answer = localSlmManager.generateChatResponse(prompt) ?: return@withContext null
        ProviderResult(answer, ExecutionTier.TIER_2_EDGE_SLM,
            "Local Qwen2.5-0.5B (On-Device llama.cpp)", System.currentTimeMillis() - start)
    }
}

object OfflinePrompt {
    fun build(query: String, language: String, context: String = "", rules: String = ""): String = """
        <|im_start|>system
        You are PrismCampus AI. Answer in $language using one or two short sentences. If unsure, say so.
        ${rules.take(300)}
        ${context.take(500)}
        <|im_end|>
        <|im_start|>user
        ${query.take(600)}
        <|im_end|>
        <|im_start|>assistant
    """.trimIndent()
}
