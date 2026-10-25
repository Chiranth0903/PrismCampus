package com.prismcampus.app.facilities

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.util.Log
import com.google.gson.Gson
import com.prismcampus.app.data.CampusDatabase
import com.prismcampus.app.data.CampusIssueReport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.sync.Mutex

data class IssueClusterSummary(
    val building: String,
    val room: String,
    val count: Int,
    val highestUrgency: String,
    val recentCategory: String,
    val isEscalated: Boolean
)

data class MeshNodeStatus(
    val detectedSsid: String?,
    val isConnectedToMesh: Boolean,
    val pendingReportCount: Int,
    val lastSyncStatus: String?
)

class IssueReporter(
    private val context: Context,
    private val database: CampusDatabase
) {
    private val gson = Gson()
    private val syncMutex = Mutex()
    private val connectionPrefs = context.getSharedPreferences("prism_campus_prefs", Context.MODE_PRIVATE)
    private val identityPrefs = context.getSharedPreferences("prism_installation", Context.MODE_PRIVATE)
    private val installationId = identityPrefs.getString("id", null) ?: java.util.UUID.randomUUID().toString().also {
        identityPrefs.edit().putString("id", it).apply()
    }

    private val _meshStatus = MutableStateFlow(
        MeshNodeStatus(
            detectedSsid = null,
            isConnectedToMesh = false,
            pendingReportCount = 0,
            lastSyncStatus = "Idle (Waiting for Campus Edge Mesh)"
        )
    )
    val meshStatus: StateFlow<MeshNodeStatus> = _meshStatus.asStateFlow()

    private val _isSyncing = MutableStateFlow(false)
    val isSyncing: StateFlow<Boolean> = _isSyncing.asStateFlow()

    /**
     * Checks current Wi-Fi SSID for Uno Q / ESP32 edge node presence (e.g. "MSRIT_Edge_Node_01").
     * Automatically triggers sync when in proximity.
     */
    suspend fun checkAndAutoFlushMesh(): Boolean = withContext(Dispatchers.IO) {
        val issues = database.issuesFlow.value
        val unsynced = issues.filter { !it.isSynced }
        val count = unsynced.size

        val ssid = getCurrentWifiSsid()
        val isUnoQMesh = ssid != null && (
            ssid.contains("MSRIT_Edge", ignoreCase = true) ||
            ssid.contains("Uno_Q", ignoreCase = true) ||
            ssid.contains("ESP32_Edge", ignoreCase = true) ||
            ssid.contains("Prism_Mesh", ignoreCase = true)
        )

        _meshStatus.value = _meshStatus.value.copy(
            detectedSsid = ssid,
            isConnectedToMesh = isUnoQMesh,
            pendingReportCount = count
        )

        if (isUnoQMesh && count > 0) {
            return@withContext flushReportsToUnoQ(unsynced)
        }
        false
    }

    /**
     * Flushes unsynced reports to Arduino Uno Q / ESP32 edge server endpoint.
     * Default Uno Q AP IP: http://192.168.4.1:8000/api/reports/sync
     */
    suspend fun flushReportsToUnoQ(
        reportsToSync: List<CampusIssueReport>? = null,
        targetEndpoint: String = DEFAULT_UNO_Q_ENDPOINT
    ): Boolean = withContext(Dispatchers.IO) {
        if (!syncMutex.tryLock()) return@withContext false
        try {
        _isSyncing.value = true
        val campusId = connectionPrefs.getString("key_active_campus_id", "MSRIT") ?: "MSRIT"
        if (campusId == "IITG_DEMO") return@withContext false
        val targetList = (reportsToSync ?: database.issuesFlow.value.filter { !it.isSynced }).filter { it.campusId == campusId }

        if (targetList.isEmpty()) {
            _meshStatus.value = _meshStatus.value.copy(
                lastSyncStatus = "All reports already synced to Edge Node."
            )
            _isSyncing.value = false
            return@withContext true
        }

        val currentBase = connectionPrefs.getString("key_server_base_url", "")?.trimEnd('/') ?: ""
        val registry = com.prismcampus.app.BuildConfig.CAMPUS_REGISTRY_URL.trimEnd('/')
        val configured = listOf(currentBase, registry).filter { it.isNotBlank() }.map { "$it/api/reports/sync" }
        val endpoints = if (targetEndpoint == DEFAULT_UNO_Q_ENDPOINT) (configured + FALLBACK_UNO_Q_ENDPOINTS).distinct() else listOf(targetEndpoint)
        var syncSuccess = false

        for (ep in endpoints) {
            try {
                _meshStatus.value = _meshStatus.value.copy(
                    lastSyncStatus = "Connecting to Uno Q edge node at $ep..."
                )

                val url = URL(ep)
                val conn = (url.openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    setRequestProperty("Content-Type", "application/json")
                    connectTimeout = 2000
                    readTimeout = 3000
                    doOutput = true
                }

                val payload = gson.toJson(mapOf(
                    "device_id" to installationId,
                    "campus_id" to campusId,
                    "sync_timestamp" to System.currentTimeMillis(),
                    "reports" to targetList
                ))

                OutputStreamWriter(conn.outputStream).use { writer ->
                    writer.write(payload)
                    writer.flush()
                }

                val code = conn.responseCode
                if (code in 200..299) {
                    val response = gson.fromJson(conn.inputStream.bufferedReader().use { it.readText() }, Map::class.java)
                    if (response["status"] != "synced") continue
                    val ids = targetList.map { it.id }
                    database.markIssuesSynced(ids)
                    _meshStatus.value = _meshStatus.value.copy(
                        lastSyncStatus = "✓ Successfully synced ${targetList.size} reports to Edge Node ($ep)",
                        pendingReportCount = 0
                    )
                    _isSyncing.value = false
                    syncSuccess = true
                    return@withContext true
                }
            } catch (e: Exception) {
                // Try next endpoint
            }
        }

        // If edge server could not be reached over Wi-Fi, keep buffered in local database with status
        Log.w("IssueReporter", "Uno Q HTTP sync offline. Reports remain safely buffered in local Room SQLite storage.")
        _meshStatus.value = _meshStatus.value.copy(
            lastSyncStatus = "Buffered locally in edge SQLite: ${targetList.size} reports awaiting Wi-Fi sync.",
            pendingReportCount = targetList.size
        )
        _isSyncing.value = false
        return@withContext false
        } finally {
            _isSyncing.value = false
            syncMutex.unlock()
        }
    }

    /**
     * Aggregates issues into clusters to show management pre-emption hotspots
     * (e.g. "ESB Room 402: 5 complaints logged in 48 hours - Escalated to Head of Maintenance").
     */
    fun computeIssueClusters(issues: List<CampusIssueReport>): List<IssueClusterSummary> {
        val grouped = issues.groupBy { "${it.building}|${it.room}" }
        return grouped.map { (key, groupList) ->
            val parts = key.split("|")
            val building = parts[0]
            val room = parts.getOrElse(1) { "General" }
            val count = groupList.size
            val hasCritical = groupList.any { it.urgency.contains("Critical", ignoreCase = true) }
            val highestUrgency = if (hasCritical) "Critical/Safety Hazard" else if (count >= 3) "High" else "Medium"
            val isEscalated = count >= 3 || hasCritical

            IssueClusterSummary(
                building = building,
                room = room,
                count = count,
                highestUrgency = highestUrgency,
                recentCategory = groupList.firstOrNull()?.category ?: "General",
                isEscalated = isEscalated
            )
        }.sortedByDescending { it.count }
    }

    private fun getCurrentWifiSsid(): String? {
        return try {
            val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            val info = wifiManager?.connectionInfo
            val raw = info?.ssid?.replace("\"", "")
            if (raw == "<unknown ssid>" || raw.isNullOrBlank()) null else raw
        } catch (e: Exception) {
            null
        }
    }

    companion object {
        const val DEFAULT_UNO_Q_ENDPOINT = "http://192.168.1.8:8000/api/reports/sync"
        val FALLBACK_UNO_Q_ENDPOINTS = listOf(
            "http://192.168.1.8:8000/api/reports/sync",
            "http://10.0.2.2:8000/api/reports/sync",
            "http://192.168.4.1:8000/api/reports/sync"
        )
        const val SIMULATED_UNO_Q_SSID = "MSRIT_Edge_Node_01"
    }
}
