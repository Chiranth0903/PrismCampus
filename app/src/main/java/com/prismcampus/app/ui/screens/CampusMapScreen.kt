package com.prismcampus.app.ui.screens

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.speech.RecognizerIntent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apartment
import androidx.compose.material.icons.filled.DirectionsWalk
import androidx.compose.material.icons.filled.MeetingRoom
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.NearMe
import androidx.compose.material.icons.filled.PinDrop
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.School
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.prismcampus.app.ai.HybridRouter
import com.prismcampus.app.ai.SupportedLanguage
import com.prismcampus.app.ai.VoiceAssistantManager
import com.prismcampus.app.data.Building
import com.prismcampus.app.data.CampusRepository
import com.prismcampus.app.data.ResolvedClassDestination
import com.prismcampus.app.data.Room
import com.prismcampus.app.data.TimetableSlot
import com.prismcampus.app.location.CampusLocationManager
import com.prismcampus.app.ui.components.OfflineCampusCanvasMap
import com.prismcampus.app.ui.theme.AmberAlert
import com.prismcampus.app.ui.theme.CrimsonAlert
import com.prismcampus.app.ui.theme.CyberEmerald
import com.prismcampus.app.ui.theme.CyberEmeraldDark
import com.prismcampus.app.ui.theme.ElectricBlue
import com.prismcampus.app.ui.theme.PillActiveBackground
import com.prismcampus.app.ui.theme.PillActiveBorder
import com.prismcampus.app.ui.theme.PillInactiveBackground
import com.prismcampus.app.ui.theme.PillInactiveBorder
import com.prismcampus.app.ui.theme.PrismBorder
import com.prismcampus.app.ui.theme.PrismSurface
import com.prismcampus.app.ui.theme.PrismSurfaceVariant
import com.prismcampus.app.ui.theme.TextMuted
import com.prismcampus.app.ui.theme.TextPrimary
import com.prismcampus.app.ui.theme.TextSecondary
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CampusMapScreen(
    repository: CampusRepository,
    hybridRouter: HybridRouter,
    voiceManager: VoiceAssistantManager,
    locationManager: CampusLocationManager,
    targetDestination: ResolvedClassDestination? = null,
    targetBuilding: Building? = null,
    modifier: Modifier = Modifier
) {
    val coroutineScope = rememberCoroutineScope()
    val context = LocalContext.current
    val pack by repository.prismPack.collectAsState()

    val selectedLanguage by voiceManager.selectedLanguage.collectAsState()
    val isSpeaking by voiceManager.isSpeaking.collectAsState()
    val isListening by voiceManager.isListening.collectAsState()
    val recognizedQuery by voiceManager.recognizedQuery.collectAsState()

    // Live GPS tracking
    val currentLocation by locationManager.currentLocation.collectAsState()
    val isGpsActive by locationManager.isGpsActive.collectAsState()
    val isSimulated by locationManager.isSimulatedOnCampus.collectAsState()

    var activeBuildingOverride by remember { mutableStateOf<Building?>(targetBuilding) }
    var activeDestinationOverride by remember { mutableStateOf<ResolvedClassDestination?>(targetDestination) }
    var tourAudioNarrationText by remember { mutableStateOf<String?>(null) }
    var showRoomPickerDialog by remember { mutableStateOf(false) }

    var selectedFloor by remember { mutableIntStateOf(targetDestination?.floor ?: 0) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var showSheet by remember { mutableStateOf(false) }

    // Handles voice query or quick prompt: "Where is CAD Lab?"
    fun handleTourQuery(query: String) {
        coroutineScope.launch {
            val result = hybridRouter.routeQuery(query, selectedLanguage)
            tourAudioNarrationText = result.responseText

            if (result.targetBuilding != null) {
                activeBuildingOverride = result.targetBuilding
                activeDestinationOverride = null
            } else if (result.targetDestination != null) {
                activeDestinationOverride = result.targetDestination
                activeBuildingOverride = null
            }

            voiceManager.speakDirectly(result.responseText)
        }
    }

    val audioPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            voiceManager.startListening()
        }
    }

    LaunchedEffect(recognizedQuery) {
        if (recognizedQuery.isNotBlank()) {
            val q = recognizedQuery.trim()
            voiceManager.clearRecognizedQuery()
            handleTourQuery(q)
        }
    }

    // Resolve active target for canvas path
    val effectiveDestination = remember(activeDestinationOverride, activeBuildingOverride, pack) {
        if (activeDestinationOverride != null) {
            activeDestinationOverride
        } else if (activeBuildingOverride != null) {
            val b = activeBuildingOverride!!
            val firstRoom = b.rooms.firstOrNull()
            ResolvedClassDestination(
                slot = TimetableSlot(
                    period = 1,
                    startTime = "Active",
                    endTime = "",
                    courseCode = b.code,
                    courseName = b.name,
                    instructor = ""
                ),
                buildingCode = b.code,
                buildingName = b.name,
                floor = firstRoom?.floor ?: 0,
                floorLabel = firstRoom?.floorLabel ?: "Building entrance / floor unverified",
                roomId = firstRoom?.roomId ?: b.code,
                latitude = b.latitude,
                longitude = b.longitude
            )
        } else {
            val section = pack?.sections?.find { it.sectionId == repository.selectedSectionId.value } ?: pack?.sections?.firstOrNull()
            val defaultB = pack?.buildings?.find { it.code == section?.defaultBuildingCode } ?: pack?.buildings?.firstOrNull()
            if (defaultB != null) {
                val room = defaultB.rooms.find { it.roomId == section?.defaultRoomId } ?: defaultB.rooms.firstOrNull()
                ResolvedClassDestination(
                    slot = TimetableSlot(period = 1, startTime = "Default", endTime = "", courseCode = defaultB.code, courseName = defaultB.name, instructor = ""),
                    buildingCode = defaultB.code,
                    buildingName = defaultB.name,
                    floor = room?.floor ?: 0,
                    floorLabel = room?.floorLabel ?: "Floor unverified",
                    roomId = room?.roomId ?: defaultB.code,
                    latitude = defaultB.latitude,
                    longitude = defaultB.longitude
                )
            } else null
        }
    }

    // Compute live distance from user GPS
    val distanceMeters = remember(currentLocation, effectiveDestination) {
        if (effectiveDestination != null) {
            locationManager.calculateDistanceMeters(effectiveDestination.latitude, effectiveDestination.longitude).toInt()
        } else {
            0
        }
    }

    fun selectRoomDirectly(b: Building, r: Room) {
        activeDestinationOverride = ResolvedClassDestination(
            slot = TimetableSlot(
                period = 1,
                startTime = "Selected",
                endTime = "",
                courseCode = r.assignedCourse ?: r.roomId,
                courseName = r.description.ifBlank { r.name },
                instructor = ""
            ),
            buildingCode = b.code,
            buildingName = b.name,
            floor = r.floor,
            floorLabel = r.floorLabel,
            roomId = r.roomId,
            latitude = b.latitude,
            longitude = b.longitude
        )
        activeBuildingOverride = null
        selectedFloor = r.floor
        showRoomPickerDialog = false

        val msg = "Navigating to ${r.name} in ${b.name}, ${r.floorLabel}. Distance: ${distanceMeters}m."
        tourAudioNarrationText = msg
        voiceManager.speakDirectly(msg)
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(12.dp)
    ) {
        // ==========================================
        // 1. LIVE GPS & LOCATION BAR
        // ==========================================
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(PrismSurface)
                .border(1.dp, if (currentLocation != null) CyberEmerald.copy(alpha = 0.5f) else PrismBorder, RoundedCornerShape(12.dp))
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.MyLocation,
                    contentDescription = null,
                    tint = if (currentLocation != null) CyberEmerald else AmberAlert,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Column {
                    Text(
                        text = if (currentLocation != null) {
                            if (isSimulated) "GPS: Simulated at ${pack?.shortName ?: "campus"}"
                            else "GPS: Live Lat ${String.format("%.4f", currentLocation!!.latitude)}, Lng ${String.format("%.4f", currentLocation!!.longitude)}"
                        } else {
                            "GPS: Searching signal (Using Main Gate anchor)"
                        },
                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold, fontSize = 10.sp),
                        color = if (currentLocation != null) CyberEmerald else AmberAlert
                    )
                    Text(
                        text = if (effectiveDestination != null) "Distance to ${effectiveDestination.roomId}: ~${distanceMeters} meters" else "Ready",
                        style = MaterialTheme.typography.bodySmall.copy(fontSize = 10.sp),
                        color = ElectricBlue
                    )
                }
            }

            // Quick toggle to simulate being directly on campus quad
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(if (isSimulated) AmberAlert.copy(alpha = 0.2f) else PrismSurfaceVariant)
                        .border(1.dp, if (isSimulated) AmberAlert else PrismBorder, RoundedCornerShape(6.dp))
                        .clickable {
                            val anchor = pack?.baseLocation
                            if (anchor != null) locationManager.toggleSimulatedCampusLocation(anchor.latitude, anchor.longitude)
                            else locationManager.toggleSimulatedCampusLocation()
                        }
                        .padding(horizontal = 6.dp, vertical = 3.dp)
                ) {
                    Text(
                        text = if (isSimulated) "On-Campus Sim" else "Sim GPS",
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
                        color = if (isSimulated) AmberAlert else TextSecondary
                    )
                }

                // Choose Room Button
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(CyberEmeraldDark)
                        .clickable { showRoomPickerDialog = true }
                        .padding(horizontal = 8.dp, vertical = 3.dp)
                ) {
                    Text(
                        text = "Pick Room",
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp, fontWeight = FontWeight.Bold),
                        color = TextPrimary
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // ==========================================
        // 2. VOICE TOUR & NAVIGATOR BAR
        // ==========================================
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(PrismSurface)
                .border(1.dp, ElectricBlue.copy(alpha = 0.4f), RoundedCornerShape(14.dp))
                .padding(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.School,
                        contentDescription = null,
                        tint = ElectricBlue,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "Voice Navigation & Tour",
                        style = MaterialTheme.typography.titleMedium.copy(fontSize = 12.sp, fontWeight = FontWeight.Bold),
                        color = TextPrimary
                    )
                }

                // Language chips
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    SupportedLanguage.values().forEach { lang ->
                        val isSelected = lang == selectedLanguage
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(if (isSelected) CyberEmerald.copy(alpha = 0.2f) else PrismSurfaceVariant)
                                .border(1.dp, if (isSelected) CyberEmerald else PrismBorder, RoundedCornerShape(6.dp))
                                .clickable { voiceManager.setLanguage(lang) }
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        ) {
                            Text(
                                text = lang.code.uppercase(),
                                style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp, fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal),
                                color = if (isSelected) CyberEmerald else TextSecondary
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(6.dp))

            // Push-to-talk mic button + Quick Tour Prompts
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Button(
                    onClick = {
                        if (isListening) {
                            voiceManager.stopListening()
                        } else {
                            val hasAudioPerm = ContextCompat.checkSelfPermission(
                                context,
                                Manifest.permission.RECORD_AUDIO
                            ) == PackageManager.PERMISSION_GRANTED

                            if (!hasAudioPerm) {
                                audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                            } else {
                                voiceManager.startListening()
                            }
                        }
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (isListening) CrimsonAlert else CyberEmeraldDark,
                        contentColor = TextPrimary
                    ),
                    shape = RoundedCornerShape(8.dp),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 10.dp, vertical = 5.dp)
                ) {
                    Icon(
                        imageVector = if (isListening) Icons.Default.Stop else Icons.Default.Mic,
                        contentDescription = null,
                        modifier = Modifier.size(15.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = if (isListening) "Listening..." else "Speak Room/Place",
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                Spacer(modifier = Modifier.width(6.dp))

                // Scrollable Quick Tour Prompts
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(5.dp)
                ) {
                    (pack?.buildings?.map { it.name to "where is ${it.name}" } ?: emptyList()).forEach { (label, q) ->
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(Color(0xFF0F1A2E))
                                .border(1.dp, ElectricBlue.copy(alpha = 0.3f), RoundedCornerShape(8.dp))
                                .clickable { handleTourQuery(q) }
                                .padding(horizontal = 7.dp, vertical = 4.dp)
                        ) {
                            Text(
                                text = label,
                                style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                                color = ElectricBlue
                            )
                        }
                    }
                }
            }

            // Audio Narration Banner
            AnimatedVisibility(visible = tourAudioNarrationText != null) {
                Column(modifier = Modifier.padding(top = 6.dp)) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color(0xFF0B132B))
                            .border(1.dp, CyberEmerald.copy(alpha = 0.5f), RoundedCornerShape(8.dp))
                            .padding(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = if (isSpeaking) Icons.Default.VolumeUp else Icons.Default.RecordVoiceOver,
                            contentDescription = null,
                            tint = CyberEmerald,
                            modifier = Modifier.size(15.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = tourAudioNarrationText ?: "",
                            style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                            color = TextPrimary,
                            modifier = Modifier.weight(1f)
                        )
                        if (isSpeaking) {
                            IconButton(
                                onClick = { voiceManager.stopSpeaking() },
                                modifier = Modifier.size(20.dp)
                            ) {
                                Icon(Icons.Default.Stop, contentDescription = "Stop", tint = CrimsonAlert)
                            }
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(6.dp))

        // Floor selector tabs (1st to 7th floor)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            (pack?.buildings?.flatMap { it.rooms }?.map { it.floor }?.distinct()?.sorted() ?: listOf(0)).forEach { floorNum ->
                val isSelected = selectedFloor == floorNum
                val bg = if (isSelected) PillActiveBackground else PillInactiveBackground
                val border = if (isSelected) PillActiveBorder else PillInactiveBorder
                val textColor = if (isSelected) CyberEmerald else TextSecondary

                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(bg)
                        .border(1.dp, border, RoundedCornerShape(6.dp))
                        .clickable { selectedFloor = floorNum }
                        .padding(horizontal = 9.dp, vertical = 3.dp)
                ) {
                    Text(
                        text = if (floorNum == 0) "Ground / unverified" else "Floor $floorNum",
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp, fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal),
                        color = textColor
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(6.dp))

        // ==========================================
        // 3. FULL SCREEN OFFLINE VECTOR CANVAS MAP (WITH LIVE GPS ORIGIN)
        // ==========================================
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
        ) {
            OfflineCampusCanvasMap(
                pack = pack,
                activeDestination = effectiveDestination,
                userLatitude = currentLocation?.latitude,
                userLongitude = currentLocation?.longitude,
                onBuildingSelected = { building ->
                    activeBuildingOverride = building
                    activeDestinationOverride = null
                    showSheet = true
                    handleTourQuery(building.name)
                }
            )
        }

        Spacer(modifier = Modifier.height(6.dp))

        // Turn-by-Turn Guidance from Live GPS Location to Target Room
        val destBuildingName = effectiveDestination?.buildingName ?: "Choose a place"
        val destRoom = effectiveDestination?.roomId ?: ""
        val destFloorLabel = effectiveDestination?.floorLabel ?: "Floor unverified"

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(PrismSurface)
                .border(1.dp, PrismBorder, RoundedCornerShape(12.dp))
                .padding(10.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .clip(CircleShape)
                        .background(ElectricBlue.copy(alpha = 0.2f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.DirectionsWalk,
                        contentDescription = null,
                        tint = ElectricBlue,
                        modifier = Modifier.size(18.dp)
                    )
                }
                Spacer(modifier = Modifier.width(10.dp))
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "Route to $destRoom ($destBuildingName)",
                            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold, fontSize = 11.sp),
                            color = TextPrimary
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "~${distanceMeters}m away",
                            style = MaterialTheme.typography.labelSmall.copy(color = CyberEmerald, fontSize = 10.sp)
                        )
                    }
                    Text(
                        text = "Approximate direction to $destBuildingName. Destination: $destRoom, $destFloorLabel. Follow campus signs and confirm access on site.",
                        style = MaterialTheme.typography.bodySmall.copy(fontSize = 10.sp),
                        color = TextSecondary
                    )
                }
            }
        }
    }

    // ==========================================
    // 4. "SELECT ANY ROOM" PICKER DIALOG
    // ==========================================
    if (showRoomPickerDialog) {
        AlertDialog(
            onDismissRequest = { showRoomPickerDialog = false },
            containerColor = PrismSurfaceVariant,
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Search, contentDescription = null, tint = ElectricBlue, modifier = Modifier.size(20.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Select Any Room on Campus", style = MaterialTheme.typography.titleMedium, color = TextPrimary)
                }
            },
            text = {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(350.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    pack?.buildings?.forEach { b ->
                        item {
                            Text(
                                text = "${b.name} (${b.code})",
                                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                color = CyberEmerald,
                                modifier = Modifier.padding(top = 6.dp)
                            )
                        }
                        items(b.rooms) { r ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(PrismSurface)
                                    .border(1.dp, PrismBorder, RoundedCornerShape(8.dp))
                                    .clickable { selectRoomDirectly(b, r) }
                                    .padding(horizontal = 10.dp, vertical = 8.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column {
                                    Text(
                                        text = "${r.name} (${r.floorLabel})",
                                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                                        color = TextPrimary
                                    )
                                    Text(
                                        text = r.description,
                                        style = MaterialTheme.typography.bodySmall.copy(fontSize = 10.sp),
                                        color = TextSecondary
                                    )
                                }
                                Icon(Icons.Default.DirectionsWalk, contentDescription = null, tint = ElectricBlue, modifier = Modifier.size(16.dp))
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showRoomPickerDialog = false }) {
                    Text("Close", color = TextSecondary)
                }
            }
        )
    }

    // Modal Bottom Sheet with Campus Directory
    if (showSheet) {
        ModalBottomSheet(
            onDismissRequest = { showSheet = false },
            sheetState = sheetState,
            containerColor = PrismSurfaceVariant,
            dragHandle = { BottomSheetDefaults.DragHandle(color = TextMuted) }
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
            ) {
                Text(
                    text = "${pack?.shortName ?: "Campus"} · Places & buildings",
                    style = MaterialTheme.typography.titleMedium,
                    color = TextPrimary
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Tap any building to route from your GPS position",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary
                )

                Spacer(modifier = Modifier.height(10.dp))

                pack?.buildings?.forEach { b ->
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(PrismSurface)
                            .border(1.dp, PrismBorder, RoundedCornerShape(10.dp))
                            .clickable {
                                activeBuildingOverride = b
                                activeDestinationOverride = null
                                showSheet = false
                                handleTourQuery(b.name)
                            }
                            .padding(10.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "${b.name} (${b.code})",
                                style = MaterialTheme.typography.titleSmall,
                                color = CyberEmerald
                            )
                            Text(
                                text = "${b.totalFloors} Floors",
                                style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                                color = TextMuted
                            )
                        }
                        Text(
                            text = b.description,
                            style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                            color = TextSecondary
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                }
            }
        }
    }
}
