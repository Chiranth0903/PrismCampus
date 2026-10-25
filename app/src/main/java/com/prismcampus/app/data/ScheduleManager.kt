package com.prismcampus.app.data

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

class ScheduleManager(private val repository: CampusRepository) {

    private val timeFormat = SimpleDateFormat("HH:mm", Locale.US)

    /**
     * Evaluates current active or upcoming class.
     * @param simulatedDayOfWeek Optional day string (e.g. "MONDAY") for testing/demoing at any hour.
     * @param simulatedTime Optional "HH:mm" time string.
     */
    fun evaluateSchedule(
        simulatedDayOfWeek: String? = null,
        simulatedTime: String? = null
    ): ScheduleEvaluationResult {
        val pack = repository.prismPack.value ?: return ScheduleEvaluationResult.Empty
        val activeSectionId = repository.selectedSectionId.value
        val sectionData = pack.sections.find { it.sectionId.equals(activeSectionId, ignoreCase = true) }
            ?: pack.sections.firstOrNull()
            ?: return ScheduleEvaluationResult.Empty

        val calendar = Calendar.getInstance()
        val currentDay = simulatedDayOfWeek ?: when (calendar.get(Calendar.DAY_OF_WEEK)) {
            Calendar.MONDAY -> "MONDAY"
            Calendar.TUESDAY -> "TUESDAY"
            Calendar.WEDNESDAY -> "WEDNESDAY"
            Calendar.THURSDAY -> "THURSDAY"
            Calendar.FRIDAY -> "FRIDAY"
            Calendar.SATURDAY -> "SATURDAY"
            else -> "MONDAY" // Default Sunday demo to Monday
        }

        val currentTimeStr = simulatedTime ?: timeFormat.format(calendar.time)
        val currentMinutes = timeToMinutes(currentTimeStr)

        val daySlots = sectionData.schedule[currentDay] ?: emptyList()
        if (daySlots.isEmpty()) {
            return ScheduleEvaluationResult(
                activeSection = sectionData,
                evaluatedDay = currentDay,
                evaluatedTime = currentTimeStr,
                currentClass = null,
                nextClass = null,
                allDaySlots = emptyList(),
                message = "No scheduled classes for $currentDay"
            )
        }

        val resolvedList = daySlots.map { slot ->
            resolveDestination(pack, sectionData, slot, currentMinutes)
        }

        val currentClass = resolvedList.find { it.status == ClassStatus.IN_PROGRESS }
        val nextClass = resolvedList.find { it.status == ClassStatus.UPCOMING }

        return ScheduleEvaluationResult(
            activeSection = sectionData,
            evaluatedDay = currentDay,
            evaluatedTime = currentTimeStr,
            currentClass = currentClass,
            nextClass = nextClass ?: currentClass,
            allDaySlots = resolvedList,
            message = if (currentClass != null) "Class currently in session"
                      else if (nextClass != null) "Next class in ${nextClass.minutesUntil}m"
                      else "All classes completed for $currentDay"
        )
    }

    private fun resolveDestination(
        pack: PrismPack,
        sectionData: SectionData,
        slot: TimetableSlot,
        currentMinutes: Int
    ): ResolvedClassDestination {
        val startMin = timeToMinutes(slot.startTime)
        val endMin = timeToMinutes(slot.endTime)

        val status = when {
            currentMinutes in startMin until endMin -> ClassStatus.IN_PROGRESS
            currentMinutes < startMin -> ClassStatus.UPCOMING
            else -> ClassStatus.COMPLETED
        }

        val minutesUntil = if (startMin > currentMinutes) (startMin - currentMinutes).toLong() else 0L
        val progress = if (status == ClassStatus.IN_PROGRESS && (endMin - startMin) > 0) {
            (currentMinutes - startMin).toFloat() / (endMin - startMin).toFloat()
        } else 0f

        // 1. Check dynamic overrides first (e.g. from OCR snap or admin notice)
        val activeOverrides = repository.overrides.value
        val override = activeOverrides.find { ov ->
            val matchCourse = ov.targetCourseCode.equals(slot.courseCode, ignoreCase = true) ||
                    (ov.targetCourseCode.uppercase() in listOf("MAT11", "MAC11") && slot.courseCode.uppercase() in listOf("MAT11", "MAC11"))
            matchCourse && (ov.targetSection == null || ov.targetSection.equals(sectionData.sectionId, ignoreCase = true))
        }

        if (override != null) {
            val building = pack.buildings.find { it.code.equals(override.newBuildingCode, ignoreCase = true) }
            return ResolvedClassDestination(
                slot = slot,
                buildingCode = override.newBuildingCode,
                buildingName = override.newBuildingName,
                floor = override.newFloor,
                floorLabel = override.newFloorLabel,
                roomId = override.newRoomId,
                latitude = building?.latitude ?: pack.baseLocation.latitude,
                longitude = building?.longitude ?: pack.baseLocation.longitude,
                isOverride = true,
                overrideNote = override.reason,
                status = status,
                minutesUntil = minutesUntil,
                progressPercent = progress
            )
        }

        // A reviewed campus timetable may assign a different room to each class.
        val assignedBuilding = pack.buildings.find { it.code == slot.assignedBuildingCode }
        val assignedRoom = assignedBuilding?.rooms?.find { it.roomId == slot.assignedRoomId }
        if (assignedBuilding != null && assignedRoom != null) {
            return ResolvedClassDestination(slot, assignedBuilding.code, assignedBuilding.name,
                assignedRoom.floor, assignedRoom.floorLabel, assignedRoom.roomId,
                assignedBuilding.latitude, assignedBuilding.longitude, status = status,
                minutesUntil = minutesUntil, progressPercent = progress)
        }

        // 2. Check lab routing rules (e.g. MELC19 -> ESB 402, HSCP15 -> LHC 212, PYC12(L) -> Apex 602, PSCLCS18 -> Apex 703)
        val labRule = pack.labRoutingRules.find {
            it.courseCode.equals(slot.courseCode, ignoreCase = true) ||
                    (slot.isLab && slot.courseName.contains(it.courseCode, ignoreCase = true))
        }

        if (labRule != null && (slot.isLab || labRule.courseCode == "PSCLCS18" || labRule.courseCode == "MELC19" || labRule.courseCode == "HSCP15")) {
            val building = pack.buildings.find { it.code.equals(labRule.buildingCode, ignoreCase = true) }
            return ResolvedClassDestination(
                slot = slot,
                buildingCode = labRule.buildingCode,
                buildingName = labRule.buildingName,
                floor = labRule.floor,
                floorLabel = labRule.floorLabel,
                roomId = labRule.roomId,
                latitude = building?.latitude ?: pack.baseLocation.latitude,
                longitude = building?.longitude ?: pack.baseLocation.longitude,
                isOverride = false,
                status = status,
                minutesUntil = minutesUntil,
                progressPercent = progress
            )
        }

        // 3. Fallback to default Section Classroom (e.g. Section A -> AB-501, D -> AB-504, I -> AB-514)
        val defaultBuilding = pack.buildings.find { it.code.equals(sectionData.defaultBuildingCode, ignoreCase = true) }
            ?: pack.buildings.firstOrNull()

        val defaultFloorLabel = when (sectionData.defaultFloor) {
            1 -> "1st Floor"
            2 -> "2nd Floor"
            3 -> "3rd Floor"
            4 -> "4th Floor"
            5 -> "5th Floor"
            else -> "${sectionData.defaultFloor}th Floor"
        }

        return ResolvedClassDestination(
            slot = slot,
            buildingCode = sectionData.defaultBuildingCode,
            buildingName = defaultBuilding?.name ?: "Apex Block",
            floor = sectionData.defaultFloor,
            floorLabel = defaultFloorLabel,
            roomId = sectionData.defaultRoomId,
            latitude = defaultBuilding?.latitude ?: pack.baseLocation.latitude,
            longitude = defaultBuilding?.longitude ?: pack.baseLocation.longitude,
            isOverride = false,
            status = status,
            minutesUntil = minutesUntil,
            progressPercent = progress
        )
    }

    private fun timeToMinutes(time: String): Int {
        return try {
            val parts = time.trim().split(":")
            val hours = parts[0].toInt()
            val minutes = parts[1].toInt()
            hours * 60 + minutes
        } catch (e: Exception) {
            9 * 60 // 09:00 default
        }
    }
}

data class ScheduleEvaluationResult(
    val activeSection: SectionData?,
    val evaluatedDay: String,
    val evaluatedTime: String,
    val currentClass: ResolvedClassDestination?,
    val nextClass: ResolvedClassDestination?,
    val allDaySlots: List<ResolvedClassDestination>,
    val message: String
) {
    companion object {
        val Empty = ScheduleEvaluationResult(
            activeSection = null,
            evaluatedDay = "MONDAY",
            evaluatedTime = "09:00",
            currentClass = null,
            nextClass = null,
            allDaySlots = emptyList(),
            message = "No schedule available"
        )
    }
}
