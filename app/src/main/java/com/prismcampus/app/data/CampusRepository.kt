package com.prismcampus.app.data

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

class CampusRepository(private val context: Context) {

    private val gson = Gson()
    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _prismPack = MutableStateFlow<PrismPack?>(null)
    val prismPack: StateFlow<PrismPack?> = _prismPack.asStateFlow()

    private val _activeCampusId = MutableStateFlow(prefs.getString(KEY_ACTIVE_CAMPUS_ID, "MSRIT") ?: "MSRIT")
    val activeCampusId: StateFlow<String> = _activeCampusId.asStateFlow()

    private val _isOnboarded = MutableStateFlow(prefs.getBoolean(KEY_IS_ONBOARDED, false))
    val isOnboarded: StateFlow<Boolean> = _isOnboarded.asStateFlow()

    private val _serverBaseUrl = MutableStateFlow(
        prefs.getString(KEY_SERVER_BASE_URL, DEFAULT_SERVER_URL) ?: DEFAULT_SERVER_URL
    )
    val serverBaseUrl: StateFlow<String> = _serverBaseUrl.asStateFlow()

    private val _selectedSectionId = MutableStateFlow(prefs.getString(KEY_ACTIVE_SECTION, "A") ?: "A")
    val selectedSectionId: StateFlow<String> = _selectedSectionId.asStateFlow()

    private val _selectedSemester = MutableStateFlow(prefs.getInt(KEY_ACTIVE_SEMESTER, 1))
    val selectedSemester: StateFlow<Int> = _selectedSemester.asStateFlow()

    private val _overrides = MutableStateFlow<List<ScheduleOverride>>(emptyList())
    val overrides: StateFlow<List<ScheduleOverride>> = _overrides.asStateFlow()

    private val _campusMessages = MutableStateFlow<List<CampusBroadcastMessage>>(emptyList())
    val campusMessages: StateFlow<List<CampusBroadcastMessage>> = _campusMessages.asStateFlow()

    init {
        loadPrismPack()
        loadStoredOverrides()
        loadCachedMessages(_activeCampusId.value)
    }

    /**
     * Loads the campus bundle from active custom pack, per-campus cache, or default assets/msrit_pack.json.
     */
    fun loadPrismPack() {
        try {
            val campusId = _activeCampusId.value
            val perCampusFile = File(context.filesDir, "campus_${campusId}.prismpack")
            val customFile = File(context.filesDir, CUSTOM_PACK_FILENAME)

            var pack: PrismPack = when {
                perCampusFile.exists() -> {
                    val json = perCampusFile.readText()
                    gson.fromJson(json, PrismPack::class.java)
                }
                customFile.exists() -> {
                    val json = customFile.readText()
                    gson.fromJson(json, PrismPack::class.java)
                }
                else -> {
                    context.assets.open(if (campusId == "IITG_DEMO") "iitg_demo_pack.json" else "msrit_pack.json").use { inputStream ->
                        InputStreamReader(inputStream).use { reader ->
                            gson.fromJson(reader, PrismPack::class.java)
                        }
                    }
                }
            }
            if (campusId == "IITG_DEMO" && pack.campusId == campusId) {
                val bundled = context.assets.open("iitg_demo_pack.json").bufferedReader().use { gson.fromJson(it,PrismPack::class.java) }
                val existingCodes = pack.buildings.map { it.code }.toSet()
                val added = bundled.buildings.filter { it.code !in existingCodes }
                if (added.isNotEmpty()) {
                    pack = pack.copy(buildings = pack.buildings + added)
                    perCampusFile.writeText(gson.toJson(pack))
                }
            }
            _prismPack.value = pack
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /**
     * Onboarding action: Connects to Uno Q server or loads local cache for a selected Campus ID.
     */
    suspend fun setupCampus(campusIdInput: String, customServerUrl: String? = null): Result<PrismPack> =
        withContext(Dispatchers.IO) {
            val normId = campusIdInput.trim().uppercase()
            if (normId.isBlank()) {
                return@withContext Result.failure(IllegalArgumentException("Please enter a valid Campus ID (e.g. MSRIT)."))
            }

            if (!customServerUrl.isNullOrBlank()) {
                setServerBaseUrl(customServerUrl.trim().removeSuffix("/"))
            }

            val currentServer = _serverBaseUrl.value.removeSuffix("/")
            if (!normId.matches(Regex("[A-Z0-9_-]{1,80}"))) {
                return@withContext Result.failure(IllegalArgumentException("Use the campus ID exactly as shown in campus studio."))
            }
            // Explicit rehearsal campus: instant, independent of credentials or Wi-Fi.
            if (normId == "IITG_DEMO") {
                return@withContext try {
                    val json = context.assets.open("iitg_demo_pack.json").bufferedReader().use { it.readText() }
                    val demo = gson.fromJson(json, PrismPack::class.java)
                    saveCampusPackLocally(normId, json, demo)
                    Result.success(demo)
                } catch (error: Exception) { Result.failure(error) }
            }
            val cloudRegistry = com.prismcampus.app.BuildConfig.CAMPUS_REGISTRY_URL.removeSuffix("/")
            val cloudFirst = if (customServerUrl.isNullOrBlank() && currentServer == DEFAULT_SERVER_URL && cloudRegistry.isNotBlank())
                listOf("$cloudRegistry/api/campus/$normId/package") else emptyList()
            val candidateEndpoints = (cloudFirst + listOf(
                "$currentServer/api/campus/$normId/package",
                "$currentServer/api/pack?campus=$normId",
                "http://192.168.1.8:8000/api/campus/$normId/package",
                "http://10.0.2.2:8000/api/campus/$normId/package",
                "http://192.168.4.1:8000/api/campus/$normId/package"
            ) + if (cloudRegistry.isNotBlank()) listOf("$cloudRegistry/api/campus/$normId/package") else emptyList()).distinct()

            // 1. Attempt to fetch live from server
            for (urlStr in candidateEndpoints) {
                try {
                    val url = URL(urlStr)
                    val conn = (url.openConnection() as HttpURLConnection).apply {
                        requestMethod = "GET"
                        connectTimeout = 2500
                        readTimeout = 3000
                    }
                    if (conn.responseCode in 200..299) {
                        val body = conn.inputStream.bufferedReader().use { it.readText() }
                        val pack = gson.fromJson(body, PrismPack::class.java)
                        if (pack != null && pack.campusName.isNotBlank() && pack.campusId.equals(normId, ignoreCase = true)) {
                            // Persist locally per campus
                            saveCampusPackLocally(normId, body, pack)
                            return@withContext Result.success(pack)
                        }
                    }
                } catch (e: Exception) {
                    // Try next candidate endpoint
                }
            }

            // 2. Offline Fallback: Check if cached package exists on device
            val perCampusFile = File(context.filesDir, "campus_${normId}.prismpack")
            if (perCampusFile.exists()) {
                try {
                    val json = perCampusFile.readText()
                    val cachedPack = gson.fromJson(json, PrismPack::class.java)
                    if (cachedPack != null && cachedPack.campusId.equals(normId, ignoreCase = true)) {
                        applyActiveCampus(normId, cachedPack)
                        return@withContext Result.success(cachedPack)
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }

            // 3. Bundled Fallback: If MSRIT, use bundled assets
            if (normId == "MSRIT") {
                try {
                    context.assets.open("msrit_pack.json").use { inputStream ->
                        InputStreamReader(inputStream).use { reader ->
                            val defaultPack = gson.fromJson(reader, PrismPack::class.java)
                            val json = gson.toJson(defaultPack)
                            saveCampusPackLocally(normId, json, defaultPack)
                            return@withContext Result.success(defaultPack)
                        }
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }

            // 4. Server unreachable and no local cache
            Result.failure(
                IllegalStateException(
                    "Cannot reach campus server at '$currentServer'.\n" +
                    "Connect to the local campus Wi-Fi and enter the laptop server address, or connect to the internet with a configured public campus registry. Check the campus ID."
                )
            )
        }

    private fun saveCampusPackLocally(campusId: String, json: String, pack: PrismPack) {
        try {
            val perCampusFile = File(context.filesDir, "campus_${campusId}.prismpack")
            perCampusFile.writeText(json)

            val customFile = File(context.filesDir, CUSTOM_PACK_FILENAME)
            customFile.writeText(json)
        } catch (e: Exception) {
            e.printStackTrace()
        }
        applyActiveCampus(campusId, pack)
    }

    private fun applyActiveCampus(campusId: String, pack: PrismPack) {
        if (_activeCampusId.value != campusId) {
            _overrides.value = emptyList()
            _campusMessages.value = emptyList()
            prefs.edit().remove(KEY_OVERRIDES).apply()
            prefs.edit().remove("remote_override_ids").apply()
        }
        _activeCampusId.value = campusId
        _prismPack.value = pack
        loadCachedMessages(campusId)
        _isOnboarded.value = true

        // Default section to first section if current section is missing
        val hasCurrentSec = pack.sections.any { it.sectionId.equals(_selectedSectionId.value, ignoreCase = true) }
        if (!hasCurrentSec && pack.sections.isNotEmpty()) {
            setSelectedSection(pack.sections.first().sectionId)
        }

        prefs.edit()
            .putString(KEY_ACTIVE_CAMPUS_ID, campusId)
            .putBoolean(KEY_IS_ONBOARDED, true)
            .apply()
    }

    private fun loadCachedMessages(campusId: String) {
        _campusMessages.value = try {
            val json = prefs.getString("campus_messages_$campusId", "[]")
            gson.fromJson(json, object : TypeToken<List<CampusBroadcastMessage>>() {}.type) ?: emptyList()
        } catch (_: Exception) { emptyList() }
    }

    fun changeCampus() {
        _isOnboarded.value = false
        prefs.edit().putBoolean(KEY_IS_ONBOARDED, false).apply()
    }

    fun setServerBaseUrl(url: String) {
        val clean = url.trim().removeSuffix("/")
        _serverBaseUrl.value = clean
        prefs.edit().putString(KEY_SERVER_BASE_URL, clean).apply()
    }

    /**
     * Saves a custom .prismpack from Admin Agent 37.
     */
    fun saveCustomPrismPack(packJson: String): Boolean {
        return try {
            val parsed = gson.fromJson(packJson, PrismPack::class.java)
            val customFile = File(context.filesDir, CUSTOM_PACK_FILENAME)
            customFile.writeText(packJson)
            _prismPack.value = parsed
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    fun resetToDefaultMSRITPack() {
        val customFile = File(context.filesDir, CUSTOM_PACK_FILENAME)
        if (customFile.exists()) {
            customFile.delete()
        }
        _activeCampusId.value = "MSRIT"
        prefs.edit().putString(KEY_ACTIVE_CAMPUS_ID, "MSRIT").apply()
        loadPrismPack()
        clearOverrides()
    }

    fun setSelectedSection(sectionId: String) {
        val normalized = sectionId.uppercase().trim()
        _selectedSectionId.value = normalized
        prefs.edit().putString(KEY_ACTIVE_SECTION, normalized).apply()
    }

    fun setSelectedSemester(semester: Int) {
        val valid = semester.coerceIn(1, 8)
        _selectedSemester.value = valid
        prefs.edit().putInt(KEY_ACTIVE_SEMESTER, valid).apply()
    }

    /**
     * Updates timetable slots for a section and day in the current active .prismpack.
     * Persists the changes to active_custom_pack.prismpack and campus_{campusId}.prismpack.
     */
    fun updateScheduleSlots(
        sectionId: String,
        dayOfWeek: String,
        newSlots: List<TimetableSlot>
    ): Boolean {
        val currentPack = _prismPack.value ?: return false
        val normSec = sectionId.trim().uppercase()
        val normDay = dayOfWeek.trim().uppercase()

        val updatedSections = currentPack.sections.map { sec ->
            if (sec.sectionId.equals(normSec, ignoreCase = true)) {
                val updatedSchedule = sec.schedule.toMutableMap()
                updatedSchedule[normDay] = newSlots
                sec.copy(schedule = updatedSchedule)
            } else {
                sec
            }
        }

        val updatedPack = currentPack.copy(sections = updatedSections)
        val json = gson.toJson(updatedPack)
        saveCampusPackLocally(_activeCampusId.value, json, updatedPack)
        return true
    }

    fun addOverride(override: ScheduleOverride) {
        val current = _overrides.value.toMutableList()
        current.removeAll { it.targetCourseCode.equals(override.targetCourseCode, ignoreCase = true) }
        current.add(override)
        _overrides.value = current
        saveOverridesToPrefs(current)
    }

    fun clearOverrides() {
        _overrides.value = emptyList()
        prefs.edit().remove(KEY_OVERRIDES).apply()
    }

    private fun loadStoredOverrides() {
        val json = prefs.getString(KEY_OVERRIDES, null) ?: return
        try {
            val type = object : TypeToken<List<ScheduleOverride>>() {}.type
            val list: List<ScheduleOverride> = gson.fromJson(json, type)
            _overrides.value = list
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun saveOverridesToPrefs(list: List<ScheduleOverride>) {
        val json = gson.toJson(list)
        prefs.edit().putString(KEY_OVERRIDES, json).apply()
    }

    /**
     * Samples live on-device JVM telemetry without incurring garbage collection overhead.
     */
    fun sampleMemoryTelemetry(inferenceMode: String = "Regex Fast-Path (<1ms)", latencyMs: Long = 8L): MemoryTelemetryInfo {
        val runtime = Runtime.getRuntime()
        val totalMem = runtime.totalMemory()
        val freeMem = runtime.freeMemory()
        val usedMem = totalMem - freeMem

        val usedMb = usedMem / (1024 * 1024)
        val maxMb = com.prismcampus.app.ai.AppMemoryBudget.MIB
        val freeMb = (maxMb - usedMb).coerceAtLeast(0L)
        val ratio = if (maxMb > 0) usedMb.toFloat() / maxMb.toFloat() else 0.15f

        return MemoryTelemetryInfo(
            usedMemoryMb = usedMb,
            maxMemoryMb = maxMb,
            freeMemoryMb = freeMb,
            memoryUsageRatio = ratio.coerceIn(0f, 1f),
            inferenceMode = inferenceMode,
            averageInferenceLatencyMs = latencyMs
        )
    }

    /**
     * Samples Android's reported total PSS using Debug.MemoryInfo, including proportional swap.
     * This is distinct from the resident RSS used by the native generation guard.
     */
    fun sampleAppProcessMemory(context: android.content.Context): MemoryTelemetryInfo {
        return try {
            val memInfo = android.os.Debug.MemoryInfo()
            android.os.Debug.getMemoryInfo(memInfo)
            val pssKb = memInfo.totalPss
            val pssMb = (pssKb / 1024).toLong()

            val runtime = Runtime.getRuntime()
            val heapUsedMb = (runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024)

            val actualUsedMb = if (pssMb > 0) pssMb else heapUsedMb.coerceAtLeast(1L)
            val budgetMb = com.prismcampus.app.ai.AppMemoryBudget.MIB
            val ratio = (actualUsedMb.toFloat() / budgetMb.toFloat()).coerceIn(0f, 1f)

            MemoryTelemetryInfo(
                usedMemoryMb = actualUsedMb,
                maxMemoryMb = budgetMb,
                freeMemoryMb = (budgetMb - actualUsedMb).coerceAtLeast(0L),
                memoryUsageRatio = ratio,
                inferenceMode = if (pssMb > 0) "Process PSS" else "JVM Heap",
                averageInferenceLatencyMs = 8L
            )
        } catch (_: Throwable) {
            sampleMemoryTelemetry(inferenceMode = "JVM Heap (PSS unavailable)")
        }
    }

    /**
     * Polls and syncs announcements/overrides broadcast by the Uno Q / Laptop Edge Server.
     */
    suspend fun syncAnnouncementsFromUnoQ(): Boolean = withContext(Dispatchers.IO) {
        val campusId = _activeCampusId.value
        // The bundled rehearsal campus never consumes a live campus feed.
        if (campusId == "IITG_DEMO") return@withContext false
        val currentBase = _serverBaseUrl.value.removeSuffix("/")
        val registry = com.prismcampus.app.BuildConfig.CAMPUS_REGISTRY_URL.trimEnd('/')
        val cloudFirst = if (currentBase == DEFAULT_SERVER_URL && registry.isNotBlank()) listOf("$registry/api/campus/$campusId/announcements") else emptyList()
        val candidateUrls = (cloudFirst + listOf(
            "$currentBase/api/campus/$campusId/announcements",
            "http://192.168.1.8:8000/api/campus/$campusId/announcements",
            "http://10.0.2.2:8000/api/campus/$campusId/announcements",
            "http://192.168.4.1:8000/api/campus/$campusId/announcements"
        ) + (if (registry.isNotBlank()) listOf("$registry/api/campus/$campusId/announcements") else emptyList())
            + (if (campusId == "MSRIT") listOf("$currentBase/api/announcements") else emptyList())).distinct()

        for (urlStr in candidateUrls) {
            try {
                val url = URL(urlStr)
                val conn = (url.openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 2000
                    readTimeout = 2500
                }
                if (conn.responseCode in 200..299) {
                    val body = conn.inputStream.bufferedReader().use { it.readText() }
                    val type = object : TypeToken<Map<String, Any>>() {}.type
                    val map: Map<String, Any> = gson.fromJson(body, type)
                    if (_activeCampusId.value != campusId || (map["campus_id"] != null && map["campus_id"] != campusId)) continue

                    val remoteVersion = map["pack_version"] as? String
                    if (!remoteVersion.isNullOrBlank() && remoteVersion != _prismPack.value?.packVersion) {
                        val packageUrl = URL(urlStr.substringBefore("/api/") + "/api/campus/$campusId/package")
                        val packageConn = (packageUrl.openConnection() as HttpURLConnection).apply { connectTimeout=2500; readTimeout=3000 }
                        try {
                            val json = packageConn.inputStream.bufferedReader().use { it.readText() }
                            val updated = gson.fromJson(json, PrismPack::class.java)
                            if (updated.campusId == campusId && _activeCampusId.value == campusId) saveCampusPackLocally(campusId,json,updated)
                        } finally { packageConn.disconnect() }
                    }

                    val announcementsObj = map["announcements"]
                    if (announcementsObj != null) {
                        val annJson = gson.toJson(announcementsObj)
                        val overrideListType = object : TypeToken<List<ScheduleOverride>>() {}.type
                        val fetched: List<ScheduleOverride> = gson.fromJson(annJson, overrideListType)
                        val oldRemote = prefs.getStringSet("remote_override_ids", emptySet()) ?: emptySet()
                        val updated = (_overrides.value.filterNot { it.id in oldRemote } + fetched).distinctBy { it.id }
                        _overrides.value = updated
                        prefs.edit().putStringSet("remote_override_ids",fetched.map { it.id }.toSet())
                            .putString(KEY_OVERRIDES,gson.toJson(updated)).apply()
                        Log.i("CampusRepository", "Synced ${fetched.size} announcements from $urlStr")
                    }

                    val messagesObj = map["messages"]
                    if (messagesObj != null) {
                        val msgJson = gson.toJson(messagesObj)
                        val msgListType = object : TypeToken<List<CampusBroadcastMessage>>() {}.type
                        val fetchedMsgs: List<CampusBroadcastMessage> = gson.fromJson(msgJson, msgListType)
                        _campusMessages.value = fetchedMsgs
                        prefs.edit().putString("campus_messages_$campusId",msgJson).apply()
                        Log.i("CampusRepository", "Synced ${fetchedMsgs.size} broadcast messages from $urlStr")
                    }
                    return@withContext true
                }
            } catch (e: Exception) {
                // Ignore connection errors and try next fallback endpoint
            }
        }
        false
    }

    companion object {
        private const val PREFS_NAME = "prism_campus_prefs"
        private const val KEY_ACTIVE_CAMPUS_ID = "key_active_campus_id"
        private const val KEY_IS_ONBOARDED = "key_is_onboarded"
        private const val KEY_SERVER_BASE_URL = "key_server_base_url"
        private const val KEY_ACTIVE_SECTION = "key_active_section"
        private const val KEY_ACTIVE_SEMESTER = "key_active_semester"
        private const val KEY_OVERRIDES = "key_active_overrides"
        private const val CUSTOM_PACK_FILENAME = "active_custom_pack.prismpack"
        const val DEFAULT_SERVER_URL = "http://192.168.1.8:8000"
    }
}
