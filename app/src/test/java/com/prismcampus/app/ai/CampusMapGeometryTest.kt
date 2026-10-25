package com.prismcampus.app.ai

import com.google.gson.Gson
import com.prismcampus.app.data.*
import java.io.File
import org.junit.Assert.*
import org.junit.Test

class CampusMapGeometryTest {
    private fun pack(name: String) = Gson().fromJson(File("src/main/assets/$name").readText(), PrismPack::class.java)

    @Test fun all34IitgPinsFitTheInitialViewportAndAreSelectable() {
        val pack = pack("iitg_demo_pack.json")
        val pins = CampusMapGeometry.pins(pack); val bounds = CampusMapGeometry.bounds(pack)
        assertEquals(34, pins.size)
        assertTrue(bounds.minLat > 26.0);assertTrue(bounds.minLng > 91.0)
        for ((width,height) in listOf(1080f to 600f, 600f to 1080f)) {
            for (b in pins) {
                val p = CampusMapGeometry.project(b.latitude,b.longitude,bounds,width,height)
                assertTrue(b.code,p.x in 0f..width && p.y in 0f..height)
                assertEquals(b.code,CampusMapGeometry.nearest(pins,bounds,p.x,p.y,width,height,1f,0f,0f,32f)?.code)
            }
        }
    }

    @Test fun zoomAndPanHitTestingUsesTheSameProjectionAsDrawing() {
        val pack=pack("iitg_demo_pack.json");val pins=CampusMapGeometry.pins(pack);val bounds=CampusMapGeometry.bounds(pack)
        val b=pins.first { it.code=="CORE1" }
        val p=CampusMapGeometry.project(b.latitude,b.longitude,bounds,1080f,600f,2.3f,180f,-90f)
        assertEquals(b.code,CampusMapGeometry.nearest(pins,bounds,p.x,p.y,1080f,600f,2.3f,180f,-90f,32f)?.code)
    }

    @Test fun campusesHaveIndependentBoundsAndEmptyRoomBuildingsRemainPins() {
        val iitg=pack("iitg_demo_pack.json");val msrit=pack("msrit_pack.json")
        assertTrue(CampusMapGeometry.bounds(msrit).maxLat<14)
        assertTrue(CampusMapGeometry.bounds(iitg).minLat>26)
        assertTrue(CampusMapGeometry.pins(iitg).any { it.code=="CORE4" && it.rooms.isEmpty() })
        assertFalse(CampusMapGeometry.bounds(iitg).contains(13.0303,77.5647))
        assertFalse(CampusMapGeometry.validCoordinate(Double.NaN,91.6))
        assertFalse(CampusMapGeometry.validCoordinate(0.0,0.0))
        assertTrue(CampusMapGeometry.bounds(PrismPack()).maxLat.isFinite())
    }
}
