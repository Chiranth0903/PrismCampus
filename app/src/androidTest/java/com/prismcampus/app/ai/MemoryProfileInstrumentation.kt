package com.prismcampus.app.ai

import android.app.Activity
import android.app.ActivityManager
import android.app.Instrumentation
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Debug
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import com.google.gson.GsonBuilder
import com.prismcampus.app.BuildConfig
import com.prismcampus.app.MainActivity
import kotlinx.coroutines.runBlocking
import java.io.File
import java.util.Collections
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Profiling only. No runtime switch can disable the guard in the normal APK. */
class MemoryProfileInstrumentation : Instrumentation() {
    private var mode = "online"
    @Volatile private var phase = "startup"
    private val samples = Collections.synchronizedList(mutableListOf<Map<String, Any>>())
    private val events = mutableListOf<Map<String, Any>>()
    private val errors = mutableListOf<String>()
    private val started = SystemClock.elapsedRealtime()

    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        mode = arguments?.getString("mode") ?: "online"
        start()
    }

    private fun sample(): Map<String, Any> {
        val mem = Debug.MemoryInfo()
        Debug.getMemoryInfo(mem)
        val status = File("/proc/self/status").readLines()
        fun proc(name: String): Long = status.firstOrNull { it.startsWith("$name:") }
            ?.substringAfter(':')?.trim()?.substringBefore(' ')?.toLongOrNull() ?: 0L
        val runtime = Runtime.getRuntime()
        return mapOf("elapsedMs" to SystemClock.elapsedRealtime() - started, "phase" to phase,
            "pssKiB" to mem.totalPss.toLong(), "rssKiB" to proc("VmRSS"),
            "rssHighWaterKiB" to proc("VmHWM"),
            "javaHeapUsedBytes" to runtime.totalMemory() - runtime.freeMemory(),
            "javaHeapCommittedBytes" to runtime.totalMemory(),
            "javaHeapMaxBytes" to runtime.maxMemory())
    }

    private fun field(activity: Activity, name: String): Any = activity.javaClass.getDeclaredField(name).let {
        it.isAccessible = true; it.get(activity)!!
    }

    private fun tab(label: String) {
        phase = "navigation_$label"
        if (android.os.Build.VERSION.SDK_INT >= 33) uiAutomation.clearCache()
        val displayLabel = when (label) {
            "Map" -> com.prismcampus.app.CampusNavTab.MAP.label
            "Schedule" -> com.prismcampus.app.CampusNavTab.SCHEDULE.label
            else -> com.prismcampus.app.CampusNavTab.HOME.label
        }
        val deadline = SystemClock.elapsedRealtime() + 5000
        var root: AccessibilityNodeInfo? = null
        while (root == null && SystemClock.elapsedRealtime() < deadline) {
            root = uiAutomation.rootInActiveWindow?.takeIf { it.packageName == targetContext.packageName }
                ?: uiAutomation.windows.mapNotNull { it.root }.firstOrNull { it.packageName == targetContext.packageName }
            if (root == null) SystemClock.sleep(250)
        }
        val appRoot = root ?: error("App window unavailable; phone must be unlocked")
        check(uiAutomation.rootInActiveWindow?.packageName == targetContext.packageName) { "App is not the active window" }
        val matches = mutableListOf<AccessibilityNodeInfo>()
        fun find(node: AccessibilityNodeInfo) {
            if (node.text?.toString() == displayLabel || node.contentDescription?.toString() == displayLabel) matches.add(node)
            for (index in 0 until node.childCount) node.getChild(index)?.let { find(it) }
        }
        find(appRoot)
        // Home contains schedule/map shortcuts too. Select the bottom navigation label.
        val node = matches.maxByOrNull { android.graphics.Rect().also { r -> it.getBoundsInScreen(r) }.centerY() }
            ?: error("Tab $label not found")
        val bounds = android.graphics.Rect().also { node.getBoundsInScreen(it) }
        events.add(mapOf("event" to "tab_tap", "tab" to label, "displayLabel" to displayLabel,
            "bounds" to bounds.toShortString()))
        val down = SystemClock.uptimeMillis()
        for (action in listOf(android.view.MotionEvent.ACTION_DOWN, android.view.MotionEvent.ACTION_UP)) {
            val event = android.view.MotionEvent.obtain(down, SystemClock.uptimeMillis(), action,
                bounds.centerX().toFloat(), bounds.centerY().toFloat(), 0)
            event.source = android.view.InputDevice.SOURCE_TOUCHSCREEN
            check(uiAutomation.injectInputEvent(event, true)) { "Navigation touch failed" }
            event.recycle()
            SystemClock.sleep(80)
        }
        SystemClock.sleep(1800)
        if (android.os.Build.VERSION.SDK_INT >= 33) uiAutomation.clearCache()
        val texts = mutableListOf<String>()
        fun text(node: AccessibilityNodeInfo) {
            node.text?.toString()?.let { texts.add(it) }
            node.contentDescription?.toString()?.let { texts.add(it) }
            for (index in 0 until node.childCount) node.getChild(index)?.let { text(it) }
        }
        uiAutomation.rootInActiveWindow?.let { it.refresh(); text(it) }
        val header = texts.firstOrNull { when (label) {
            "Schedule" -> it == "Timetable & Tasks" || it == "Weekly Timetable"
            "Map" -> (it.endsWith(" Map") && it.length > 4) || it == "Voice Navigation & Tour" || it == "Pick Room"
            else -> it.startsWith("PrismCampus") || it.startsWith("App Memory:") || it == "Ask PrismCampus Qwen AI"
        } }
        check(header != null) { "Tab $label did not show its expected page title; bounds=${bounds.toShortString()}; visible=${texts.take(15)}" }
        events.add(mapOf("event" to "tab", "tab" to label, "observedPageMarker" to header,
            "elapsedMs" to SystemClock.elapsedRealtime() - started))
    }

    private fun speak(voice: VoiceAssistantManager, text: String) {
        phase = "${mode}_tts_output"
        runOnMainSync { voice.speakDirectly(text) }
        SystemClock.sleep(1000)
        val end = SystemClock.elapsedRealtime() + 15000
        while (voice.isSpeaking.value && SystemClock.elapsedRealtime() < end) SystemClock.sleep(200)
        runOnMainSync { voice.stopSpeaking() }
    }

    override fun onStart() {
        val sampler = Executors.newSingleThreadScheduledExecutor()
        val result = Bundle()
        var success = false
        var voice: VoiceAssistantManager? = null
        try {
            check(BuildConfig.MEMORY_PROFILING && AppMemoryBudget.generationLimitMiB(true) == 0L)
            sampler.scheduleWithFixedDelay({ try { samples.add(sample()) } catch (_: Exception) {} }, 0, 250, TimeUnit.MILLISECONDS)
            val activity = startActivitySync(Intent(targetContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            runOnMainSync { activity.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
            uiAutomation.serviceInfo = uiAutomation.serviceInfo.apply {
                flags = flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
            }
            waitForIdleSync()
            voice = field(activity, "voiceManager") as VoiceAssistantManager
            val router = field(activity, "hybridRouter") as HybridRouter
            val local = field(activity, "localSlmManager") as LocalSLMManager
            val repository = field(activity, "repository") as com.prismcampus.app.data.CampusRepository
            phase = "${mode}_baseline_home"
            SystemClock.sleep(5000)
            if (mode == "navigation") {
                tab("Schedule"); tab("Map"); tab("Home")
                success = true
                return
            }
            if (mode == "offline") {
                phase = "offline_load_vosk"
                check(voice.voskEngine.ensureModelLoaded()) { "Vosk model not ready" }
                phase = "offline_speech_model_resident"
                SystemClock.sleep(2500)
            } else check(router.orchestrator.geminiProvider.isOnline()) { "Phone has no validated internet connection" }

            val queries = if (mode == "online") listOf("Explain recursion in one sentence.", "What is a queue in programming?")
                else listOf("What is a stack in programming?", "Explain recursion in one sentence.", "नमस्ते, अपना परिचय दें।")
            for ((index, query) in queries.withIndex()) {
                if (index == 1) {
                    try { tab("Schedule"); tab("Map"); tab("Home") } catch (failure: Exception) { errors.add("Navigation: ${failure.message}") }
                }
                if (mode == "offline" && index == 2) try { tab("Map") } catch (failure: Exception) { errors.add("Navigation: ${failure.message}") }
                phase = "${mode}_query_${index + 1}"
                val start = SystemClock.elapsedRealtime()
                val answer = runBlocking {
                    if (mode == "online") router.routeQuery(query, SupportedLanguage.ENGLISH).also {
                        check(it.tierUsed == ExecutionTier.TIER_3_CLOUD_GEMINI) { "Expected Gemini, received ${it.tierUsed}" }
                    } else router.orchestrator.qwenProvider.generateResponse(query,
                        if (index == 2) SupportedLanguage.HINDI else SupportedLanguage.ENGLISH)
                }
                val text = when (answer) { is RouterResult -> answer.responseText; is ProviderResult -> answer.text; else -> "" }
                check(text.isNotBlank()) { "Empty answer: ${local.engineStatus.value}" }
                events.add(mapOf("event" to "answer", "query" to query, "latencyMs" to SystemClock.elapsedRealtime() - start,
                    "provider" to if (mode == "online") "Gemini" else "on-device Qwen (direct provider; no LAN/cloud)",
                    "characters" to text.length, "answer" to text))
                phase = "${mode}_post_generation_${index + 1}"
                samples.add(sample())
                SystemClock.sleep(3000)
                speak(voice, text)
                phase = "${mode}_recovery_${index + 1}"
                SystemClock.sleep(5000)
            }
            try { tab("Home") } catch (failure: Exception) { errors.add("Navigation: ${failure.message}") }
            phase = "${mode}_final_recovery"
            SystemClock.sleep(10000)
            check(repository.sampleAppProcessMemory(targetContext).maxMemoryMb == AppMemoryBudget.MIB)
            success = true
        } catch (failure: Throwable) { errors.add("Run: ${failure.javaClass.simpleName}: ${failure.message}") }
        finally {
            voice?.let { runOnMainSync { it.stopSpeaking() } }
            sampler.shutdownNow()
            sampler.awaitTermination(3, TimeUnit.SECONDS)
            try { samples.add(sample()) } catch (_: Exception) {}
            val systemMemory = ActivityManager.MemoryInfo()
            (targetContext.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(systemMemory)
            val data = mapOf("mode" to mode, "success" to success, "guardDisabled" to BuildConfig.MEMORY_PROFILING,
                "sampleIntervalMs" to 250, "device" to "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}",
                "androidSdk" to android.os.Build.VERSION.SDK_INT, "totalDeviceRamBytes" to systemMemory.totalMem,
                "modelFileBytes" to targetContext.assets.openFd(LocalSLMManager.MODEL_NAME).use { it.length }, "liveMicrophoneMeasured" to false,
                "offlineMethod" to "On-device Qwen provider called directly; Vosk acoustic model resident; network unchanged; no LAN fallback exists",
                "events" to events, "errors" to errors, "samples" to synchronized(samples) { samples.toList() })
            val file = File(targetContext.getExternalFilesDir(null), "memory-profile-$mode.json")
            file.writeText(GsonBuilder().setPrettyPrinting().create().toJson(data))
            result.putString("stream", "Memory profile $mode: success=$success, samples=${samples.size}, errors=$errors, file=${file.absolutePath}\n")
            finish(if (success) Activity.RESULT_OK else Activity.RESULT_CANCELED, result)
        }
    }
}
