package com.prismcampus.app.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apartment
import androidx.compose.material.icons.filled.GpsFixed
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material.icons.filled.PersonPinCircle
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.prismcampus.app.data.CampusMapGeometry
import com.prismcampus.app.data.Building
import com.prismcampus.app.data.PrismPack
import com.prismcampus.app.data.ResolvedClassDestination
import com.prismcampus.app.ui.theme.AmberAlert
import com.prismcampus.app.ui.theme.CrimsonAlert
import com.prismcampus.app.ui.theme.CyberEmerald
import com.prismcampus.app.ui.theme.ElectricBlue
import com.prismcampus.app.ui.theme.PrismBackground
import com.prismcampus.app.ui.theme.PrismBorder
import com.prismcampus.app.ui.theme.PrismSurface
import com.prismcampus.app.ui.theme.PrismSurfaceVariant
import com.prismcampus.app.ui.theme.TextMuted
import com.prismcampus.app.ui.theme.TextPrimary
import com.prismcampus.app.ui.theme.TextSecondary

@Composable
fun OfflineCampusCanvasMap(
    pack: PrismPack?,
    activeDestination: ResolvedClassDestination?,
    userLatitude: Double? = null,
    userLongitude: Double? = null,
    onBuildingSelected: ((Building) -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val bounds = remember(pack) { CampusMapGeometry.bounds(pack) }
    val pins = remember(pack) { CampusMapGeometry.pins(pack) }
    val minLat = bounds.minLat; val maxLat = bounds.maxLat
    val minLng = bounds.minLng; val maxLng = bounds.maxLng
    var scale by remember(pack?.campusId, bounds) { mutableFloatStateOf(1f) }
    var offset by remember(pack?.campusId, bounds) { mutableStateOf(Offset.Zero) }
    var selectedBuilding by remember(pack?.campusId) { mutableStateOf<Building?>(null) }

    val transformableState = rememberTransformableState { zoomChange, panChange, _ ->
        scale = (scale * zoomChange).coerceIn(0.6f, 3.5f)
        offset += panChange
    }

    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val pulseProgress by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1500, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "pulseProgress"
    )

    Box(
        modifier = modifier
            .fillMaxSize()
            .clip(RoundedCornerShape(16.dp))
            .background(PrismBackground)
            .border(1.dp, PrismBorder, RoundedCornerShape(16.dp))
    ) {
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .transformable(state = transformableState)
                .pointerInput(pack?.campusId, bounds, pins, scale, offset) {
                    detectTapGestures { tapOffset ->
                        val width = size.width
                        val height = size.height
                        CampusMapGeometry.nearest(pins,bounds,tapOffset.x,tapOffset.y,
                            width.toFloat(),height.toFloat(),scale,offset.x,offset.y,40.dp.toPx())?.let { b ->
                            selectedBuilding = b
                            onBuildingSelected?.invoke(b)
                        }
                    }
                }
        ) {
            val canvasW = size.width
            val canvasH = size.height

            // 1. Draw Campus Grid & Pathways
            drawCampusGround(canvasW, canvasH, scale, offset)

            // 2. Project Base Anchors
            val mainGateX = lngToX(pack?.baseLocation?.latitude ?: 0.0, pack?.baseLocation?.longitude ?: 0.0, minLat, maxLat, minLng, maxLng, canvasW, canvasH, scale, offset).first
            val mainGateY = lngToX(pack?.baseLocation?.latitude ?: 0.0, pack?.baseLocation?.longitude ?: 0.0, minLat, maxLat, minLng, maxLng, canvasW, canvasH, scale, offset).second


            // 3. Draw Building Footprints & Markers
            pins.sortedBy { if (it.code == activeDestination?.buildingCode) 1 else 0 }.forEach { b ->
                val (bx, by) = lngToX(b.latitude, b.longitude, minLat, maxLat, minLng, maxLng, canvasW, canvasH, scale, offset)
                val isTarget = activeDestination?.buildingCode.equals(b.code, ignoreCase = true)

                val bWidth = (if (pins.size > 12) 28f else 84f) * scale
                val bHeight = (if (pins.size > 12) 24f else 52f) * scale
                val rectLeft = bx - bWidth / 2
                val rectTop = by - bHeight / 2

                drawRoundRect(
                    color = if (isTarget) ElectricBlue.copy(alpha = 0.25f) else PrismSurfaceVariant,
                    topLeft = Offset(rectLeft, rectTop),
                    size = Size(bWidth, bHeight),
                    cornerRadius = CornerRadius(10f * scale, 10f * scale)
                )

                drawRoundRect(
                    color = if (isTarget) ElectricBlue else PrismBorder,
                    topLeft = Offset(rectLeft, rectTop),
                    size = Size(bWidth, bHeight),
                    cornerRadius = CornerRadius(10f * scale, 10f * scale),
                    style = Stroke(width = if (isTarget) 2.5f * scale else 1.2f * scale)
                )

                drawCircle(
                    color = if (isTarget) ElectricBlue else CyberEmerald,
                    radius = 8f * scale,
                    center = Offset(bx, by)
                )

                if (isTarget) {
                    drawCircle(
                        color = ElectricBlue.copy(alpha = 1f - pulseProgress),
                        radius = (8f + pulseProgress * 22f) * scale,
                        center = Offset(bx, by),
                        style = Stroke(width = 2f)
                    )
                }

                drawContext.canvas.nativeCanvas.apply {
                    val paint = android.graphics.Paint().apply {
                        color = if (isTarget) android.graphics.Color.WHITE else android.graphics.Color.LTGRAY
                        textSize = (11f * scale).coerceIn(18f, 32f)
                        isFakeBoldText = true
                        textAlign = android.graphics.Paint.Align.CENTER
                    }
                    drawText(b.code.removePrefix("DEPT_").removePrefix("SCHOOL_"), bx, by - 12f * scale, paint)

                    val subPaint = android.graphics.Paint().apply {
                        color = android.graphics.Color.rgb(148, 163, 184)
                        textSize = (9f * scale).coerceIn(14f, 22f)
                        textAlign = android.graphics.Paint.Align.CENTER
                    }
                    if (isTarget || scale > 1.8f) drawText(b.name, bx, by + bHeight / 2 + 16f, subPaint)
                }
            }

            // 4. Resolve Navigation Origin (User GPS location vs Main Gate)
            val hasUserGps = userLatitude != null && userLongitude != null && bounds.contains(userLatitude,userLongitude)
            val startCoords = if (hasUserGps) {
                lngToX(userLatitude!!, userLongitude!!, minLat, maxLat, minLng, maxLng, canvasW, canvasH, scale, offset)
            } else {
                Pair(mainGateX, mainGateY)
            }

            val originX = startCoords.first
            val originY = startCoords.second

            // 5. Draw Walking Polyline Path from Origin to Active Destination Room
            if (activeDestination != null) {
                val destLat = activeDestination.latitude
                val destLng = activeDestination.longitude
                val (destX, destY) = lngToX(destLat, destLng, minLat, maxLat, minLng, maxLng, canvasW, canvasH, scale, offset)

                val walkingPath = Path().apply {
                    moveTo(originX, originY)
                    // Midpoint gentle curve
                    val midX = (originX * 0.45f + destX * 0.55f)
                    val midY = (originY * 0.55f + destY * 0.45f)
                    quadraticBezierTo(midX, midY, destX, destY)
                }

                // Glowing path backing
                drawPath(
                    path = walkingPath,
                    color = CyberEmerald.copy(alpha = 0.35f),
                    style = Stroke(width = 8f * scale, cap = StrokeCap.Round)
                )

                // Animated dashed walking polyline
                val dashIntervals = floatArrayOf(20f * scale, 15f * scale)
                drawPath(
                    path = walkingPath,
                    color = CyberEmerald,
                    style = Stroke(
                        width = 3.5f * scale,
                        cap = StrokeCap.Round,
                        pathEffect = PathEffect.dashPathEffect(dashIntervals, phase = -pulseProgress * 35f)
                    )
                )
            }

            // 6. Draw User GPS "You Are Here" Pin (if available)
            if (hasUserGps) {
                // Pulsing outer ripple ring
                drawCircle(
                    color = ElectricBlue.copy(alpha = 0.4f * (1f - pulseProgress)),
                    radius = (10f + pulseProgress * 26f) * scale,
                    center = Offset(originX, originY)
                )
                // Solid center GPS marker
                drawCircle(
                    color = ElectricBlue,
                    radius = 9f * scale,
                    center = Offset(originX, originY)
                )
                drawCircle(
                    color = Color.White,
                    radius = 4f * scale,
                    center = Offset(originX, originY)
                )

                drawContext.canvas.nativeCanvas.apply {
                    val userLabelPaint = android.graphics.Paint().apply {
                        color = android.graphics.Color.rgb(56, 189, 248)
                        textSize = 19f
                        isFakeBoldText = true
                        textAlign = android.graphics.Paint.Align.CENTER
                    }
                    drawText("YOU ARE HERE (GPS)", originX, originY - 14f * scale, userLabelPaint)
                }
            }

            // 7. Draw Main Gate Pin
            drawCircle(
                color = AmberAlert,
                radius = 9f * scale,
                center = Offset(mainGateX, mainGateY)
            )
            drawCircle(
                color = AmberAlert.copy(alpha = 0.35f),
                radius = 16f * scale,
                center = Offset(mainGateX, mainGateY)
            )

            drawContext.canvas.nativeCanvas.apply {
                val gatePaint = android.graphics.Paint().apply {
                    color = android.graphics.Color.rgb(245, 158, 11)
                    textSize = 19f
                    isFakeBoldText = true
                    textAlign = android.graphics.Paint.Align.CENTER
                }
                drawText(pack?.baseLocation?.name ?: "Campus reference", mainGateX, mainGateY + 28f, gatePaint)
            }
        }

        Text(
            text = "${pins.size} GPS pins · offline campus map",
            color = CyberEmerald,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.align(Alignment.BottomStart).padding(12.dp)
                .background(PrismSurface).padding(6.dp)
        )

        // Top Floating Indoor Floor Badge
        if (activeDestination != null) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(12.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(Color(0xE60F172A))
                    .border(1.dp, ElectricBlue.copy(alpha = 0.6f), RoundedCornerShape(14.dp))
                    .padding(horizontal = 14.dp, vertical = 8.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Apartment,
                        contentDescription = null,
                        tint = ElectricBlue,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Target: ${activeDestination.buildingName} | 🏢 ${activeDestination.floorLabel} • ${activeDestination.roomId}",
                        style = MaterialTheme.typography.labelMedium.copy(
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 12.sp
                        ),
                        color = TextPrimary
                    )
                }
            }
        }

        // Bottom Map Control: Re-Center
        Row(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            SmallFloatingActionButton(
                onClick = {
                    scale = 1f
                    offset = Offset.Zero
                },
                containerColor = PrismSurface,
                contentColor = CyberEmerald,
                shape = CircleShape
            ) {
                Icon(Icons.Default.GpsFixed, contentDescription = "Show all campus pins")
            }
        }

        // Selected Building Mini Inspector Dialog
        selectedBuilding?.let { building ->
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(14.dp)
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(PrismSurface)
                    .border(1.dp, CyberEmerald.copy(alpha = 0.6f), RoundedCornerShape(14.dp))
                    .padding(14.dp)
            ) {
                Column {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "${building.name} (${building.code})",
                            style = MaterialTheme.typography.titleMedium,
                            color = TextPrimary,
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(
                            onClick = { selectedBuilding = null },
                            modifier = Modifier.size(24.dp)
                        ) {
                            Text("✕", color = TextMuted)
                        }
                    }
                    Text(
                        text = "GPS: ${building.latitude}, ${building.longitude} • ${building.description}",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = if (building.rooms.isEmpty()) "Building pin · room details not provided" else "Rooms: ${building.rooms.joinToString { it.name }}",
                        style = MaterialTheme.typography.labelSmall,
                        color = ElectricBlue
                    )
                }
            }
        }
    }
}

private fun DrawScope.drawCampusGround(width: Float, height: Float, scale: Float, offset: Offset) {
    val step = 60f * scale
    val startX = (offset.x % step)
    val startY = (offset.y % step)

    var x = startX
    while (x < width) {
        drawLine(
            color = PrismBorder.copy(alpha = 0.25f),
            start = Offset(x, 0f),
            end = Offset(x, height),
            strokeWidth = 1f
        )
        x += step
    }

    var y = startY
    while (y < height) {
        drawLine(
            color = PrismBorder.copy(alpha = 0.25f),
            start = Offset(0f, y),
            end = Offset(width, y),
            strokeWidth = 1f
        )
        y += step
    }
}

// Convert GPS (Lat, Lng) to Screen Canvas Coordinates
private fun lngToX(
    lat: Double,
    lng: Double,
    minLat: Double,
    maxLat: Double,
    minLng: Double,
    maxLng: Double,
    width: Float,
    height: Float,
    scale: Float,
    offset: Offset
): Pair<Float, Float> {
    val point = CampusMapGeometry.project(lat,lng,
        CampusMapGeometry.Bounds(minLat,maxLat,minLng,maxLng),width,height,scale,offset.x,offset.y)
    return Pair(point.x, point.y)
}

private fun lngToX(lng: Double, minLng: Double, maxLng: Double, width: Float, scale: Float, offsetX: Float): Float {
    val normX = ((lng - minLng) / (maxLng - minLng)).toFloat()
    val centerX = width / 2f
    return (normX * width - centerX) * scale + centerX + offsetX
}

private fun latToY(lat: Double, minLat: Double, maxLat: Double, height: Float, scale: Float, offsetY: Float): Float {
    val normY = (1.0f - ((lat - minLat) / (maxLat - minLat)).toFloat())
    val centerY = height / 2f
    return (normY * height - centerY) * scale + centerY + offsetY
}
