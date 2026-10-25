package com.prismcampus.app.data

import kotlin.math.hypot
import kotlin.math.max

/** Projection shared by drawing and hit testing; phone GPS never changes campus framing. */
object CampusMapGeometry {
    data class Bounds(val minLat: Double, val maxLat: Double, val minLng: Double, val maxLng: Double) {
        fun contains(lat: Double, lng: Double) = lat in minLat..maxLat && lng in minLng..maxLng
    }
    data class Point(val x: Float, val y: Float)

    fun validCoordinate(lat: Double, lng: Double) = lat.isFinite() && lng.isFinite() &&
        lat in -90.0..90.0 && lng in -180.0..180.0 && !(lat == 0.0 && lng == 0.0)

    fun pins(pack: PrismPack?): List<Building> = pack?.buildings.orEmpty()
        .filter { validCoordinate(it.latitude, it.longitude) }.distinctBy { it.code }

    fun bounds(pack: PrismPack?): Bounds {
        val points = pins(pack).map { it.latitude to it.longitude }.toMutableList()
        val anchor = pack?.baseLocation
        if (anchor != null && validCoordinate(anchor.latitude, anchor.longitude) &&
            (points.isEmpty() || points.any { kotlin.math.abs(it.first - anchor.latitude) < .05 && kotlin.math.abs(it.second - anchor.longitude) < .05 })) {
            points.add(anchor.latitude to anchor.longitude)
        }
        if (points.isEmpty()) return Bounds(-.001, .001, -.001, .001)
        val south = points.minOf { it.first }; val north = points.maxOf { it.first }
        val west = points.minOf { it.second }; val east = points.maxOf { it.second }
        val latPad = max((north - south) * .18, .0005)
        val lngPad = max((east - west) * .18, .0005)
        return Bounds(south - latPad, north + latPad, west - lngPad, east + lngPad)
    }

    fun project(lat: Double, lng: Double, bounds: Bounds, width: Float, height: Float,
                scale: Float = 1f, offsetX: Float = 0f, offsetY: Float = 0f): Point {
        val x = ((lng - bounds.minLng) / (bounds.maxLng - bounds.minLng)).toFloat() * width
        val y = (1 - (lat - bounds.minLat) / (bounds.maxLat - bounds.minLat)).toFloat() * height
        return Point((x - width/2) * scale + width/2 + offsetX, (y - height/2) * scale + height/2 + offsetY)
    }

    fun nearest(pins: List<Building>, bounds: Bounds, x: Float, y: Float, width: Float, height: Float,
                scale: Float, offsetX: Float, offsetY: Float, radius: Float): Building? = pins
        .map { b -> b to project(b.latitude, b.longitude, bounds, width, height, scale, offsetX, offsetY) }
        .map { (b,p) -> b to hypot(p.x-x,p.y-y) }.filter { it.second <= radius }.minByOrNull { it.second }?.first
}
