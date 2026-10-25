package com.prismcampus.app.ai

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.provider.Settings
import com.prismcampus.app.data.Building
import com.prismcampus.app.data.CampusDatabase
import com.prismcampus.app.data.CampusIssueReport
import com.prismcampus.app.data.CampusRepository
import com.prismcampus.app.data.ResolvedClassDestination
import com.prismcampus.app.data.ScheduleManager

enum class ExecutionTier(val label: String, val latencyDescriptor: String) {
    TIER_1_DETERMINISTIC("Tier 1: Campus Search", "0 ms"),
    TIER_2_EDGE_SLM("Tier 2: On-Device Qwen SLM", "12 ms"),
    TIER_3_CLOUD_GEMINI("Tier 3: Cloud Gemini", "310 ms")
}

data class RouterResult(
    val query: String,
    val responseText: String,
    val tierUsed: ExecutionTier,
    val latencyMs: Long,
    val targetDestination: ResolvedClassDestination? = null,
    val targetBuilding: Building? = null,
    val createdIssue: CampusIssueReport? = null,
    val actionType: RouterAction = RouterAction.NONE
)

enum class RouterAction {
    NONE,
    NAVIGATE_TO_ROOM,
    NAVIGATE_TO_BUILDING,
    SHOW_TIMETABLE,
    ISSUE_RECORDED,
    ASSIGNMENT_ADDED,
    ASK_FOLLOWUP
}

/**
 * HybridRouter serves as the unified entry point for all campus navigation & AI routing.
 * Delegates to AIOrchestrator for clean, multi-tier execution:
 * Campus commands first; general questions use online Gemini or offline Qwen.
 */
class HybridRouter(
    private val context: Context,
    private val repository: CampusRepository,
    private val scheduleManager: ScheduleManager,
    private val database: CampusDatabase,
    private val localSlmManager: LocalSLMManager
) {
    val orchestrator = AIOrchestrator(
        context = context,
        repository = repository,
        scheduleManager = scheduleManager,
        database = database,
        localSlmManager = localSlmManager
    )

    suspend fun routeQuery(
        rawQuery: String,
        preferredLanguage: SupportedLanguage = SupportedLanguage.ENGLISH
    ): RouterResult {
        return orchestrator.processQuery(rawQuery, preferredLanguage)
    }

    fun isAirplaneModeOn(): Boolean {
        return try {
            Settings.Global.getInt(context.contentResolver, Settings.Global.AIRPLANE_MODE_ON, 0) != 0
        } catch (e: Exception) {
            false
        }
    }

    fun isNetworkAvailable(): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
        val network = cm.activeNetwork ?: return false
        val capabilities = cm.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }
}
