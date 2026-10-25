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
    // Campus bounding box coordinates centered around MSRIT (13.0303, 77.5647)
    // Main Gate (13.0295, 77.5650), Apex (13.0303, 77.5647), ESB (13.0313, 77.5648), LHC (13.0324, 77.5659)
    val minLat = 13.0288
    val maxLat = 13.0335
    val minLng = 77.5635
    val maxLng = 77.5670

    var scale by remember { mutableFloatStateOf(1.1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var selectedBuilding by remember { mutableStateOf<Building?>(null) }

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
                .pointerInput(Unit) {
                    detectTapGestures { tapOffset ->
                        val width = size.width
                        val height = size.height
                        pack?.buildings?.forEach { b ->
                            val pinX = lngToX(b.longitude, minLng, maxLng, width.toFloat(), scale, offset.x)
                            val pinY = latToY(b.latitude, minLat, maxLat, height.toFloat(), scale, offset.y)
                            val distance = (Offset(pinX, pinY) - tapOffset).getDistance()
                            if (distance < 60f) {
                                selectedBuilding = b
                                onBuildingSelected?.invoke(b)
                                return@detectTapGestures
                            }
                        }
                    }
                }
        ) {
            val canvasW = size.width
            val canvasH = size.height

            // 1. Draw Campus Grid & Pathways
            drawCampusGround(canvasW, canvasH, scale, offset)

            // 2. Project Base Anchors
            val mainGateX = lngToX(13.0295, 77.5650, minLat, maxLat, minLng, maxLng, canvasW, canvasH, scale, offset).first
            val mainGateY = lngToX(13.0295, 77.5650, minLat, maxLat, minLng, maxLng, canvasW, canvasH, scale, offset).second

            drawCampusAvenues(canvasW, canvasH, minLat, maxLat, minLng, maxLng, scale, offset)

            // 3. Draw Building Footprints & Markers
            pack?.buildings?.forEach { b ->
                val (bx, by) = lngToX(b.latitude, b.longitude, minLat, maxLat, minLng, maxLng, canvasW, canvasH, scale, offset)
                val isTarget = activeDestination?.buildingCode.equals(b.code, ignoreCase = true)

                val bWidth = 84f * scale
                val bHeight = 52f * scale
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
                    drawText(b.code, bx, by + 4f * scale, paint)

                    val subPaint = android.graphics.Paint().apply {
                        color = android.graphics.Color.rgb(148, 163, 184)
                        textSize = (9f * scale).coerceIn(14f, 22f)
                        textAlign = android.graphics.Paint.Align.CENTER
                    }
                    drawText(b.name, bx, by + bHeight / 2 + 16f, subPaint)
                }
            }

            // 4. Resolve Navigation Origin (User GPS location vs Main Gate)
            val hasUserGps = userLatitude != null && userLongitude != null
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
                drawText("MAIN GATE", mainGateX, mainGateY + 28f, gatePaint)
            }
        }

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
                    scale = 1.1f
                    offset = Offset.Zero
                },
                containerColor = PrismSurface,
                contentColor = CyberEmerald,
                shape = CircleShape
            ) {
                Icon(Icons.Default.GpsFixed, contentDescription = "Center Map")
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
                        text = "${building.totalFloors} Academic Floors • ${building.description}",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "Rooms: ${building.rooms.joinToString { it.name }}",
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

private fun DrawScope.drawCampusAvenues(
    canvasW: Float,
    canvasH: Float,
    minLat: Double,
    maxLat: Double,
    minLng: Double,
    maxLng: Double,
    scale: Float,
    offset: Offset
) {
    val p1 = lngToX(13.0292, 77.5650, minLat, maxLat, minLng, maxLng, canvasW, canvasH, scale, offset)
    val p2 = lngToX(13.0305, 77.5649, minLat, maxLat, minLng, maxLng, canvasW, canvasH, scale, offset)
    val p3 = lngToX(13.0326, 77.5658, minLat, maxLat, minLng, maxLng, canvasW, canvasH, scale, offset)

    drawLine(
        color = PrismSurfaceVariant,
        start = Offset(p1.first, p1.second),
        end = Offset(p2.first, p2.second),
        strokeWidth = 16f * scale,
        cap = StrokeCap.Round
    )
    drawLine(
        color = PrismSurfaceVariant,
        start = Offset(p2.first, p2.second),
        end = Offset(p3.first, p3.second),
        strokeWidth = 14f * scale,
        cap = StrokeCap.Round
    )
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
    val normX = ((lng - minLng) / (maxLng - minLng)).toFloat()
    val normY = (1.0f - ((lat - minLat) / (maxLat - minLat)).toFloat())

    val centerX = width / 2f
    val centerY = height / 2f

    val rawX = normX * width
    val rawY = normY * height

    val projectedX = (rawX - centerX) * scale + centerX + offset.x
    val projectedY = (rawY - centerY) * scale + centerY + offset.y

    return Pair(projectedX, projectedY)
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
