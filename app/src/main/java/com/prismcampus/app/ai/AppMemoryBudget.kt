package com.prismcampus.app.ai

/** MiB (1,048,576 bytes). A runtime generation budget, not an OS process ceiling. */
object AppMemoryBudget {
    const val MIB = 1024L
    fun generationLimitMiB(profiling: Boolean): Long = if (profiling) 0L else MIB
}
