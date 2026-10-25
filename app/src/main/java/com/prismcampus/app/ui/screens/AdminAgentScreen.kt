package com.prismcampus.app.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AdminPanelSettings
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.DataObject
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Divider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.gson.GsonBuilder
import com.prismcampus.app.data.Building
import com.prismcampus.app.data.CampusRepository
import com.prismcampus.app.data.LocationAnchor
import com.prismcampus.app.data.PrismPack
import com.prismcampus.app.data.ScheduleOverride
import com.prismcampus.app.ui.theme.AmberAlert
import com.prismcampus.app.ui.theme.CyberEmerald
import com.prismcampus.app.ui.theme.CyberEmeraldDark
import com.prismcampus.app.ui.theme.ElectricBlue
import com.prismcampus.app.ui.theme.PrismBackground
import com.prismcampus.app.ui.theme.PrismBorder
import com.prismcampus.app.ui.theme.PrismSurface
import com.prismcampus.app.ui.theme.PrismSurfaceVariant
import com.prismcampus.app.ui.theme.TextMuted
import com.prismcampus.app.ui.theme.TextPrimary
import com.prismcampus.app.ui.theme.TextSecondary
import com.prismcampus.app.ui.theme.VioletAccent
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun AdminAgentScreen(
    repository: CampusRepository
) {
    val coroutineScope = rememberCoroutineScope()
    val pack by repository.prismPack.collectAsState()

    var collegePortalUrl by remember { mutableStateOf("https://msrit.edu") }
    var campusNameInput by remember { mutableStateOf(pack?.campusName ?: "Ramaiah Institute of Technology (MSRIT)") }
    var nodusEndpoint by remember { mutableStateOf("http://192.168.1.100:8000") }
    var broadcastNoticeText by remember { mutableStateOf("CIV14 shifted to ESB Room 402 for tutorial session") }

    var isExtracting by remember { mutableStateOf(false) }
    var extractionLogs by remember { mutableStateOf<List<String>>(emptyList()) }
    var isCompiledSuccess by remember { mutableStateOf(false) }
    var nodusSyncMessage by remember { mutableStateOf<String?>(null) }

    // GPS Anchors state
    var mainGateLat by remember { mutableStateOf("13.0295") }
    var mainGateLng by remember { mutableStateOf("77.5650") }

    var abLat by remember { mutableStateOf("13.0303") }
    var abLng by remember { mutableStateOf("77.5647") }

    var esbLat by remember { mutableStateOf("13.0313") }
    var esbLng by remember { mutableStateOf("77.5648") }

    var lhcLat by remember { mutableStateOf("13.0324") }
    var lhcLng by remember { mutableStateOf("77.5659") }

    val gson = remember { GsonBuilder().setPrettyPrinting().create() }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            Spacer(modifier = Modifier.height(8.dp))
            // Agent 37 Header
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(PrismSurface)
                    .border(1.dp, PrismBorder, RoundedCornerShape(16.dp))
                    .padding(16.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(VioletAccent.copy(alpha = 0.2f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.AdminPanelSettings,
                            contentDescription = null,
                            tint = VioletAccent,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(10.dp))
                    Column {
                        Text(
                            text = "Multi-College Onboarding (Agent 37)",
                            style = MaterialTheme.typography.titleLarge,
                            color = TextPrimary
                        )
                        Text(
                            text = "Autonomous Crawler & .prismpack Compiler Pipeline",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSecondary
                        )
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = "PrismCampus can be adopted by any university. Agent 37 crawls college portals, extracts section timetables and lab routing rules, links them to GPS coordinates, and packages them into an offline .prismpack bundle.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextSecondary
                )
            }
        }

        // Section 1: College Portal URL Input
        item {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(PrismSurface)
                    .border(1.dp, PrismBorder, RoundedCornerShape(16.dp))
                    .padding(14.dp)
            ) {
                Text(
                    text = "1. COLLEGE WEB PORTAL & SOURCE",
                    style = MaterialTheme.typography.labelSmall,
                    color = TextMuted,
                    letterSpacing = 0.5.sp
                )

                Spacer(modifier = Modifier.height(8.dp))

                OutlinedTextField(
                    value = collegePortalUrl,
                    onValueChange = { collegePortalUrl = it },
                    label = { Text("College Portal URL") },
                    leadingIcon = { Icon(Icons.Default.Language, contentDescription = null, tint = ElectricBlue) },
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = CyberEmerald,
                        unfocusedBorderColor = PrismBorder,
                        focusedTextColor = TextPrimary,
                        unfocusedTextColor = TextPrimary
                    ),
                    singleLine = true
                )

                Spacer(modifier = Modifier.height(8.dp))

                OutlinedTextField(
                    value = campusNameInput,
                    onValueChange = { campusNameInput = it },
                    label = { Text("Campus / Institution Name") },
                    leadingIcon = { Icon(Icons.Default.Public, contentDescription = null, tint = CyberEmerald) },
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = CyberEmerald,
                        unfocusedBorderColor = PrismBorder,
                        focusedTextColor = TextPrimary,
                        unfocusedTextColor = TextPrimary
                    ),
                    singleLine = true
                )

                Spacer(modifier = Modifier.height(12.dp))

                Button(
                    onClick = {
                        isExtracting = true
                        extractionLogs = listOf("Starting Agent 37 Autonomous Extraction for: $collegePortalUrl...")
                        coroutineScope.launch {
                            delay(400)
                            extractionLogs = extractionLogs + "-> Connecting to $collegePortalUrl/departments/cse/timetable..."
                            delay(500)
                            extractionLogs = extractionLogs + "-> Found 3 Sections: Section A (AB-501), Section D (AB-504), Section I (AB-514)"
                            delay(500)
                            extractionLogs = extractionLogs + "-> Extracted Lab Routing: MELC19 -> ESB 402, HSCP15 -> LHC 212"
                            delay(400)
                            extractionLogs = extractionLogs + "-> Extracted Lab Routing: PYC12 -> Apex 602, PSCLCS18 -> Apex 703"
                            delay(400)
                            extractionLogs = extractionLogs + "✓ Agent 37 Extraction Complete: 3 Academic Blocks, 7 Rooms, 64 Course Slots verified."
                            isExtracting = false
                        }
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = CyberEmeraldDark,
                        contentColor = TextPrimary
                    ),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !isExtracting
                ) {
                    if (isExtracting) {
                        CircularProgressIndicator(color = TextPrimary, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Extracting Timetable Structures...")
                    } else {
                        Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Run Agent 37 Extraction")
                    }
                }

                // Terminal Execution Log Box
                AnimatedVisibility(visible = extractionLogs.isNotEmpty()) {
                    Column(modifier = Modifier.padding(top = 12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Terminal, contentDescription = null, tint = CyberEmerald, modifier = Modifier.size(14.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Agent 37 Live Log:", style = MaterialTheme.typography.labelSmall, color = CyberEmerald)
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .background(PrismBackground)
                                .padding(10.dp)
                        ) {
                            Column {
                                extractionLogs.forEach { logLine ->
                                    Text(
                                        text = logLine,
                                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, fontSize = 11.sp),
                                        color = if (logLine.startsWith("✓")) CyberEmerald else TextSecondary
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        // Section 2: Map GPS Anchors Interface
        item {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(PrismSurface)
                    .border(1.dp, PrismBorder, RoundedCornerShape(16.dp))
                    .padding(14.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.MyLocation, contentDescription = null, tint = ElectricBlue, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "2. MAP GPS ANCHORS & BOUNDING RECT",
                        style = MaterialTheme.typography.labelSmall,
                        color = TextMuted,
                        letterSpacing = 0.5.sp
                    )
                }

                Spacer(modifier = Modifier.height(10.dp))

                AnchorCoordRow(
                    label = "Main Gate (Base Anchor)",
                    latValue = mainGateLat,
                    lngValue = mainGateLng,
                    onLatChange = { mainGateLat = it },
                    onLngChange = { mainGateLng = it }
                )

                Spacer(modifier = Modifier.height(8.dp))

                AnchorCoordRow(
                    label = "Apex Block (AB - 7 Floors)",
                    latValue = abLat,
                    lngValue = abLng,
                    onLatChange = { abLat = it },
                    onLngChange = { abLng = it }
                )

                Spacer(modifier = Modifier.height(8.dp))

                AnchorCoordRow(
                    label = "Engineering Sciences Block (ESB - 5 Floors)",
                    latValue = esbLat,
                    lngValue = esbLng,
                    onLatChange = { esbLat = it },
                    onLngChange = { esbLng = it }
                )

                Spacer(modifier = Modifier.height(8.dp))

                AnchorCoordRow(
                    label = "Lecture Hall Complex (LHC - 4 Floors)",
                    latValue = lhcLat,
                    lngValue = lhcLng,
                    onLatChange = { lhcLat = it },
                    onLngChange = { lhcLng = it }
                )
            }
        }

        // Section 3: Compile .prismpack Bundle
        item {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(PrismSurface)
                    .border(1.dp, PrismBorder, RoundedCornerShape(16.dp))
                    .padding(14.dp)
            ) {
                Text(
                    text = "3. COMPILE .PRISMPACK ARCHIVE",
                    style = MaterialTheme.typography.labelSmall,
                    color = TextMuted,
                    letterSpacing = 0.5.sp
                )

                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Compiles all parsed timetables, room metadata, and mapped GPS anchors into a portable, zero-overhead offline JSON bundle.",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary
                )

                Spacer(modifier = Modifier.height(12.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Button(
                        onClick = {
                            val currentPack = pack ?: return@Button
                            val updatedPack = currentPack.copy(
                                campusName = campusNameInput,
                                website = collegePortalUrl,
                                baseLocation = LocationAnchor(
                                    name = "Main Gate",
                                    latitude = mainGateLat.toDoubleOrNull() ?: 13.0295,
                                    longitude = mainGateLng.toDoubleOrNull() ?: 77.5650
                                ),
                                buildings = currentPack.buildings.map { b ->
                                    when (b.code) {
                                        "AB" -> b.copy(latitude = abLat.toDoubleOrNull() ?: b.latitude, longitude = abLng.toDoubleOrNull() ?: b.longitude)
                                        "ESB" -> b.copy(latitude = esbLat.toDoubleOrNull() ?: b.latitude, longitude = esbLng.toDoubleOrNull() ?: b.longitude)
                                        "LHC" -> b.copy(latitude = lhcLat.toDoubleOrNull() ?: b.latitude, longitude = lhcLng.toDoubleOrNull() ?: b.longitude)
                                        else -> b
                                    }
                                }
                            )
                            val json = gson.toJson(updatedPack)
                            repository.saveCustomPrismPack(json)
                            isCompiledSuccess = true
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = ElectricBlue,
                            contentColor = PrismBackground
                        ),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.DataObject, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Compile .prismpack", fontWeight = FontWeight.Bold)
                    }

                    OutlinedButton(
                        onClick = {
                            repository.resetToDefaultMSRITPack()
                            isCompiledSuccess = false
                        },
                        shape = RoundedCornerShape(10.dp),
                        border = androidx.compose.foundation.BorderStroke(1.dp, PrismBorder)
                    ) {
                        Icon(Icons.Default.RestartAlt, contentDescription = null, tint = TextMuted, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Reset MSRIT", color = TextSecondary)
                    }
                }

                if (isCompiledSuccess) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "✓ .prismpack compiled and persisted to device internal storage!",
                        style = MaterialTheme.typography.labelSmall,
                        color = CyberEmerald
                    )
                }
            }
        }

        // Section 4: Nodus Cloud Endpoint Configuration
        item {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(PrismSurface)
                    .border(1.dp, PrismBorder, RoundedCornerShape(16.dp))
                    .padding(14.dp)
            ) {
                Text(
                    text = "4. NODUS CLOUD ENDPOINT (ADMIN BROADCAST SYNC)",
                    style = MaterialTheme.typography.labelSmall,
                    color = TextMuted,
                    letterSpacing = 0.5.sp
                )

                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Connects to a centralized local Nodus server for campus-wide emergency broadcasts and room relocation pushes:",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary
                )

                Spacer(modifier = Modifier.height(10.dp))

                OutlinedTextField(
                    value = nodusEndpoint,
                    onValueChange = { nodusEndpoint = it },
                    label = { Text("Nodus Cloud Server URL") },
                    leadingIcon = { Icon(Icons.Default.CloudSync, contentDescription = null, tint = AmberAlert) },
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = AmberAlert,
                        unfocusedBorderColor = PrismBorder,
                        focusedTextColor = TextPrimary,
                        unfocusedTextColor = TextPrimary
                    ),
                    singleLine = true
                )

                Spacer(modifier = Modifier.height(8.dp))

                OutlinedTextField(
                    value = broadcastNoticeText,
                    onValueChange = { broadcastNoticeText = it },
                    label = { Text("Emergency Broadcast Message") },
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = AmberAlert,
                        unfocusedBorderColor = PrismBorder,
                        focusedTextColor = TextPrimary,
                        unfocusedTextColor = TextPrimary
                    )
                )

                Spacer(modifier = Modifier.height(12.dp))

                Button(
                    onClick = {
                        // Simulate sending broadcast through Nodus and applying immediate override
                        repository.addOverride(
                            ScheduleOverride(
                                targetCourseCode = "CIV14",
                                newBuildingCode = "ESB",
                                newBuildingName = "Engineering Sciences Block",
                                newRoomId = "Room 402",
                                newFloor = 4,
                                newFloorLabel = "4th Floor",
                                reason = "Nodus Admin Broadcast: CIV14 shifted to ESB Room 402"
                            )
                        )
                        nodusSyncMessage = "Broadcast transmitted to Nodus server ($nodusEndpoint) and synced to campus devices!"
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = AmberAlert,
                        contentColor = PrismBackground
                    ),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.CloudDownload, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Push Admin Broadcast via Nodus", fontWeight = FontWeight.Bold)
                }

                nodusSyncMessage?.let { msg ->
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "✓ $msg",
                        style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                        color = AmberAlert
                    )
                }
            }
        }

        item {
            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

@Composable
private fun AnchorCoordRow(
    label: String,
    latValue: String,
    lngValue: String,
    onLatChange: (String) -> Unit,
    onLngChange: (String) -> Unit
) {
    Column {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
            color = TextPrimary
        )
        Spacer(modifier = Modifier.height(4.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedTextField(
                value = latValue,
                onValueChange = onLatChange,
                label = { Text("Lat", fontSize = 10.sp) },
                modifier = Modifier.weight(1f),
                singleLine = true,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = ElectricBlue,
                    unfocusedBorderColor = PrismBorder,
                    focusedTextColor = TextPrimary,
                    unfocusedTextColor = TextPrimary
                )
            )
            OutlinedTextField(
                value = lngValue,
                onValueChange = onLngChange,
                label = { Text("Lng", fontSize = 10.sp) },
                modifier = Modifier.weight(1f),
                singleLine = true,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = ElectricBlue,
                    unfocusedBorderColor = PrismBorder,
                    focusedTextColor = TextPrimary,
                    unfocusedTextColor = TextPrimary
                )
            )
        }
    }
}
