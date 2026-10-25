package com.prismcampus.app.ai

internal object QwenNative {
    init { System.loadLibrary("prism_qwen") }
    external fun generate(path: String, prompt: ByteArray, memoryBudgetMiB: Long): ByteArray
}
