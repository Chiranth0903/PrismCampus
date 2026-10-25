package com.prismcampus.app.data

import com.google.gson.annotations.SerializedName

/**
 * Root data bundle representing a .prismpack campus definition.
 */
data class PrismPack(
    @SerializedName("pack_version") val packVersion: String = "1.0.0",
    @SerializedName("generated_at") val generatedAt: String = "",
    @SerializedName("campus_id") val campusId: String = "MSRIT",
    @SerializedName("campus_name") val campusName: String = "",
    @SerializedName("short_name") val shortName: String = "",
    @SerializedName("website") val website: String = "",
    @SerializedName("city") val city: String = "",
    @SerializedName("country") val country: String = "",
    @SerializedName("address") val address: String = "",
    @SerializedName("description") val description: String = "",
    @SerializedName("base_location") val baseLocation: LocationAnchor = LocationAnchor(),
    @SerializedName("buildings") val buildings: List<Building> = emptyList(),
    @SerializedName("lab_routing_rules") val labRoutingRules: List<LabRoutingRule> = emptyList(),
    @SerializedName("sections") val sections: List<SectionData> = emptyList()
)

data class LocationAnchor(
    @SerializedName("name") val name: String = "Main Gate",
    @SerializedName("latitude") val latitude: Double = 13.0295,
    @SerializedName("longitude") val longitude: Double = 77.5650,
    @SerializedName("description") val description: String = ""
)

data class Building(
    @SerializedName("id") val id: String = "",
    @SerializedName("code") val code: String = "",
    @SerializedName("name") val name: String = "",
    @SerializedName("latitude") val latitude: Double = 0.0,
    @SerializedName("longitude") val longitude: Double = 0.0,
    @SerializedName("total_floors") val totalFloors: Int = 1,
    @SerializedName("description") val description: String = "",
    @SerializedName("rooms") val rooms: List<Room> = emptyList()
)

data class Room(
    @SerializedName("room_id") val roomId: String = "",
    @SerializedName("name") val name: String = "",
    @SerializedName("floor") val floor: Int = 1,
    @SerializedName("floor_label") val floorLabel: String = "",
    @SerializedName("building_code") val buildingCode: String = "",
    @SerializedName("category") val category: String = "classroom",
    @SerializedName("assigned_section") val assignedSection: String? = null,
    @SerializedName("assigned_course") val assignedCourse: String? = null,
    @SerializedName("department") val department: String = "",
    @SerializedName("description") val description: String = ""
)

data class LabRoutingRule(
    @SerializedName("course_code") val courseCode: String = "",
    @SerializedName("building_code") val buildingCode: String = "",
    @SerializedName("building_name") val buildingName: String = "",
    @SerializedName("room_id") val roomId: String = "",
    @SerializedName("floor") val floor: Int = 1,
    @SerializedName("floor_label") val floorLabel: String = "",
    @SerializedName("description") val description: String = ""
)

data class SectionData(
    @SerializedName("section_id") val sectionId: String = "",
    @SerializedName("name") val name: String = "",
    @SerializedName("department") val department: String = "",
    @SerializedName("default_room_id") val defaultRoomId: String = "",
    @SerializedName("default_building_code") val defaultBuildingCode: String = "",
    @SerializedName("default_floor") val defaultFloor: Int = 1,
    @SerializedName("schedule") val schedule: Map<String, List<TimetableSlot>> = emptyMap()
)

data class TimetableSlot(
    @SerializedName("period") val period: Int = 1,
    @SerializedName("start_time") val startTime: String = "09:00",
    @SerializedName("end_time") val endTime: String = "10:00",
    @SerializedName("course_code") val courseCode: String = "",
    @SerializedName("course_name") val courseName: String = "",
    @SerializedName("instructor") val instructor: String = "",
    @SerializedName("is_lab") val isLab: Boolean = false,
    @SerializedName("building_code") val assignedBuildingCode: String? = null,
    @SerializedName("room_id") val assignedRoomId: String? = null,
    @SerializedName("route_available") val routeAvailable: Boolean = true
)

/**
 * Resolved routing destination for an active timetable slot.
 */
data class ResolvedClassDestination(
    val slot: TimetableSlot,
    val buildingCode: String,
    val buildingName: String,
    val floor: Int,
    val floorLabel: String,
    val roomId: String,
    val latitude: Double,
    val longitude: Double,
    val isOverride: Boolean = false,
    val overrideNote: String? = null,
    val status: ClassStatus = ClassStatus.UPCOMING,
    val minutesUntil: Long = 0,
    val progressPercent: Float = 0f
)

enum class ClassStatus {
    IN_PROGRESS,
    UPCOMING,
    COMPLETED,
    NO_MORE_TODAY
}

/**
 * Dynamic schedule override parsed from OCR or Admin broadcast.
 */
data class ScheduleOverride(
    val id: String = java.util.UUID.randomUUID().toString(),
    val targetCourseCode: String,
    val targetSection: String? = null,
    val newBuildingCode: String,
    val newBuildingName: String,
    val newRoomId: String,
    val newFloor: Int,
    val newFloorLabel: String,
    val reason: String,
    val timestamp: Long = System.currentTimeMillis()
)

/** Values use MiB despite legacy Mb names; freeMemoryMb is remaining budget, not free device RAM. */
data class MemoryTelemetryInfo(
    val usedMemoryMb: Long,
    val maxMemoryMb: Long,
    val freeMemoryMb: Long,
    val memoryUsageRatio: Float,
    val inferenceMode: String,
    val averageInferenceLatencyMs: Long
)

data class OCRParseResult(
    val rawText: String,
    val detectedSection: String?,
    val detectedOverride: ScheduleOverride?,
    val confidence: Float,
    val matchedTokens: List<String>,
    val parsedJsonDump: String,
    val parsedSlots: List<TimetableSlot> = emptyList(),
    val detectedDay: String = "MONDAY",
    val detectedSemester: Int = 1,
    val detectedAssignments: List<AssignmentTask> = emptyList()
)

data class CampusBroadcastMessage(
    val id: String = "",
    val title: String = "",
    val priority: String = "Important",
    val audience: String = "All Students",
    val content: String = "",
    val timestamp: Long = System.currentTimeMillis()
)
