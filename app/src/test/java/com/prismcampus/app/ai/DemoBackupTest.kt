package com.prismcampus.app.ai

import com.google.gson.Gson
import com.prismcampus.app.data.PrismPack
import java.io.File
import org.junit.Assert.*
import org.junit.Test

class DemoBackupTest {
    @Test fun demoHasSeparateIdentityValidPinsAndRoomReferences() {
        val pack = Gson().fromJson(File("src/main/assets/iitg_demo_pack.json").readText(), PrismPack::class.java)
        assertEquals("IITG_DEMO", pack.campusId)
        assertEquals(14, pack.sections.size)
        assertTrue(pack.buildings.size >= 10)
        pack.buildings.forEach {
            assertTrue(it.latitude in 26.17..26.21)
            assertTrue(it.longitude in 91.68..91.71)
        }
        var confirmedLab = false
        pack.sections.forEach { section ->
            section.schedule.values.flatten().forEach { slot ->
                val building = pack.buildings.first { it.code == slot.assignedBuildingCode }
                assertTrue(building.rooms.any { it.roomId == slot.assignedRoomId })
                assertTrue(slot.startTime < slot.endTime)
                if (slot.isLab) {
                    assertFalse("Unknown lab rooms must not be routed", slot.routeAvailable)
                    confirmedLab = true
                } else assertTrue("Theory routes must retain default compatibility", slot.routeAvailable)
            }
        }
        assertTrue(confirmedLab)
        val legacy = Gson().fromJson(File("src/main/assets/msrit_pack.json").readText(), PrismPack::class.java)
        assertEquals("MSRIT", legacy.campusId)
        legacy.sections.flatMap { it.schedule.values.flatten() }.forEach { assertTrue(it.routeAvailable) }
    }
}
