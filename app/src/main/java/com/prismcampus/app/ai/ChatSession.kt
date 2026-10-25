package com.prismcampus.app.ai

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class ChatMessage(
    val id: String = java.util.UUID.randomUUID().toString(),
    val isUser: Boolean,
    val text: String,
    val tierLabel: String? = null,
    val latencyMs: Long? = null,
    val result: RouterResult? = null
)

object ChatHistoryCodec {
    private val gson = Gson()
    fun encode(messages: List<ChatMessage>): String = gson.toJson(messages)
    fun decode(json: String?): List<ChatMessage> = try {
        if (json.isNullOrBlank()) emptyList() else
            gson.fromJson<List<ChatMessage>>(json, object : TypeToken<List<ChatMessage>>() {}.type)
                ?.filter { it.text.isNotBlank() } ?: emptyList()
    } catch (_: Exception) { emptyList() }
}

/** Owned above the tab screens: navigation cannot discard history or cancel an answer. */
class ChatSession(
    private val scope: CoroutineScope,
    initialMessages: List<ChatMessage>,
    private val save: (List<ChatMessage>) -> Unit,
    private val answer: suspend (String, SupportedLanguage) -> RouterResult,
    private val speak: (String) -> Unit,
    private val onIssue: suspend () -> Unit = {}
) {
    private val _history = MutableStateFlow(initialMessages.ifEmpty {
        listOf(ChatMessage(isUser = false, text = "Hello! Ask me about your classes, campus, or any question.", tierLabel = "AI Companion"))
    })
    val history = _history.asStateFlow()
    private val _isProcessing = MutableStateFlow(false)
    val isProcessing = _isProcessing.asStateFlow()
    private val requests = Mutex()
    private var pending = 0

    private fun append(message: ChatMessage) {
        _history.value = (_history.value + message).takeLast(200)
        save(_history.value)
    }

    fun submit(query: String, language: SupportedLanguage) {
        if (query.isBlank()) return
        append(ChatMessage(isUser = true, text = query.trim()))
        pending++
        _isProcessing.value = true
        scope.launch {
            try {
                requests.withLock {
                    val result = answer(query.trim(), language)
                    append(ChatMessage(isUser = false, text = result.responseText,
                        tierLabel = result.tierUsed.label, latencyMs = result.latencyMs, result = result))
                    speak(result.responseText)
                    if (result.actionType == RouterAction.ISSUE_RECORDED) {
                        scope.launch { try { onIssue() } catch (_: Exception) {} }
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                val message = "I couldn't finish that answer. Please try again."
                append(ChatMessage(isUser = false, text = message, tierLabel = "Assistant"))
                speak(message)
            } finally {
                pending--
                _isProcessing.value = pending > 0
            }
        }
    }
}
