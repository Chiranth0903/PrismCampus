package com.prismcampus.app.ai

/** Repeated unchanged partial transcripts are silence, not new speech. */
class SpeechEndpointer(private val startMs: Long) {
    private var text = ""
    private var changedAtMs = startMs
    fun observe(transcript: String, nowMs: Long) {
        if (transcript.isNotBlank() && transcript != text) {
            text = transcript
            changedAtMs = nowMs
        }
    }
    fun shouldFinish(nowMs: Long): Boolean = text.isNotBlank() &&
        (nowMs - changedAtMs >= 1200L || nowMs - startMs >= 15000L)
}
