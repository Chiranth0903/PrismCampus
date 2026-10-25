package com.prismcampus.app.ui.screens

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Assignment
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Collections
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.prismcampus.app.ai.OcrManager
import com.prismcampus.app.data.CampusDatabase
import com.prismcampus.app.data.CampusRepository
import com.prismcampus.app.data.OCRParseResult
import com.prismcampus.app.ui.theme.AmberAlert
import com.prismcampus.app.ui.theme.CyberEmerald
import com.prismcampus.app.ui.theme.CyberEmeraldDark
import com.prismcampus.app.ui.theme.ElectricBlue
import com.prismcampus.app.ui.theme.PillActiveBackground
import com.prismcampus.app.ui.theme.PillActiveBorder
import com.prismcampus.app.ui.theme.PillInactiveBackground
import com.prismcampus.app.ui.theme.PillInactiveBorder
import com.prismcampus.app.ui.theme.PrismBackground
import com.prismcampus.app.ui.theme.PrismBorder
import com.prismcampus.app.ui.theme.PrismSurface
import com.prismcampus.app.ui.theme.PrismSurfaceVariant
import com.prismcampus.app.ui.theme.TextMuted
import com.prismcampus.app.ui.theme.TextPrimary
import com.prismcampus.app.ui.theme.TextSecondary
import kotlinx.coroutines.launch

@Composable
fun CameraOcrScreen(
    ocrManager: OcrManager,
    repository: CampusRepository,
    database: CampusDatabase,
    onScheduleUpdated: () -> Unit
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val isProcessing by ocrManager.isProcessing.collectAsState()
    val activeSection by repository.selectedSectionId.collectAsState()
    val activeSemester by repository.selectedSemester.collectAsState()
    val pack by repository.prismPack.collectAsState()

    var capturedBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var currentParseResult by remember { mutableStateOf<OCRParseResult?>(null) }
    var detectedSectionResult by remember { mutableStateOf<String?>(null) }
    var successNotification by remember { mutableStateOf<String?>(null) }

    fun applyAndActivateScan(res: OCRParseResult) {
        currentParseResult = res
        val detected = res.detectedSection ?: activeSection
        val sem = if (res.detectedSemester > 0) res.detectedSemester else activeSemester
        detectedSectionResult = detected

        // 1. Immediately switch the active section & semester in repository
        repository.setSelectedSection(detected)
        repository.setSelectedSemester(sem)

        // 2. If an urgent relocation override was detected in the photo, apply it
        res.detectedOverride?.let { ov ->
            repository.addOverride(ov)
        }

        // 3. If any assignments/tasks were read from the notice, insert them
        coroutineScope.launch {
            for (task in res.detectedAssignments) {
                database.insertAssignment(task.copy(campusId = repository.activeCampusId.value))
            }
        }

        val deptName = when (detected.uppercase()) {
            "A" -> "Computer Science & Engineering (AB-501)"
            "D" -> "Information Science & Engineering (AB-504)"
            "I" -> "Artificial Intelligence & Machine Learning (AB-514)"
            else -> "Section $detected"
        }
        successNotification = "Activated Semester $sem • Section $detected • $deptName"
    }

    // System Image Picker from Gallery
    val galleryLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let {
            coroutineScope.launch {
                try {
                    context.contentResolver.openInputStream(it)?.use { stream ->
                        val bitmap = BitmapFactory.decodeStream(stream)
                        if (bitmap != null) {
                            capturedBitmap = bitmap
                            val res = ocrManager.processImage(bitmap)
                            applyAndActivateScan(res)
                        }
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
    }

    // Native Direct Camera Capture
    val cameraLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.TakePicturePreview()
    ) { bitmap: Bitmap? ->
        bitmap?.let {
            capturedBitmap = it
            coroutineScope.launch {
                val res = ocrManager.processImage(it)
                applyAndActivateScan(res)
            }
        }
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(PrismBackground)
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            Spacer(modifier = Modifier.height(8.dp))

            // Scanner Control Header Card
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
                            .size(38.dp)
                            .clip(CircleShape)
                            .background(CyberEmerald.copy(alpha = 0.2f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.CameraAlt,
                            contentDescription = null,
                            tint = CyberEmerald,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(10.dp))
                    Column {
                        Text(
                            text = "Timetable & Notice OCR Scanner",
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                            color = TextPrimary
                        )
                        Text(
                            text = "Snap photo of your timetable • Auto-detects Section & updates schedule instantly",
                            style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                            color = TextSecondary
                        )
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                // Primary Capture & Pick Buttons
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Button(
                        onClick = { cameraLauncher.launch(null) },
                        modifier = Modifier
                            .weight(1f)
                            .height(46.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = CyberEmeraldDark,
                            contentColor = TextPrimary
                        ),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Icon(Icons.Default.CameraAlt, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Snap Photo", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                    }

                    Button(
                        onClick = { galleryLauncher.launch("image/*") },
                        modifier = Modifier
                            .weight(1f)
                            .height(46.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = PrismSurfaceVariant,
                            contentColor = TextPrimary
                        ),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Icon(Icons.Default.Collections, contentDescription = null, tint = ElectricBlue, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Choose Image", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Quick Simulation Presets
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Instant Test:", style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp), color = TextMuted)
                    listOf(
                        "Sem 5 Sec A (AB-501)" to {
                            coroutineScope.launch {
                                val sampleA = "RAMAIAH INSTITUTE OF TECHNOLOGY\nDEPARTMENT OF CSE\n5TH SEMESTER | SECTION: A\nROOM: AB-501\nMON: CS501 (09:00), CS502 (10:00)"
                                val res = ocrManager.processTextDirectly(sampleA)
                                applyAndActivateScan(res)
                            }
                        },
                        "Sem 3 Sec D (AB-504)" to {
                            coroutineScope.launch {
                                val sampleD = "RAMAIAH INSTITUTE OF TECHNOLOGY\nDEPARTMENT OF ISE\n3RD SEMESTER B.E. | SECTION: D\nROOM: AB-504\nMON: PYC12 (09:00), MAT11 (10:00)"
                                val res = ocrManager.processTextDirectly(sampleD)
                                applyAndActivateScan(res)
                            }
                        },
                        "Sem 1 Sec I (AB-514)" to {
                            coroutineScope.launch {
                                val res = ocrManager.simulateSectionIScan()
                                applyAndActivateScan(res)
                            }
                        },
                        "Maths Notice Next Mon" to {
                            coroutineScope.launch {
                                val notice = "RAMAIAH INSTITUTE OF TECHNOLOGY\n5TH SEMESTER SECTION A\nNOTICE: Maths assignment on next Monday. Submit in AB-501.\nPython Programming project submission due on 28-Oct."
                                val res = ocrManager.processTextDirectly(notice)
                                applyAndActivateScan(res)
                            }
                        },
                        "MAC11 Shift Notice" to {
                            coroutineScope.launch {
                                val res = ocrManager.simulateUrgentShiftScan()
                                applyAndActivateScan(res)
                            }
                        }
                    ).forEach { (label, action) ->
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(PrismSurfaceVariant)
                                .border(1.dp, PrismBorder, RoundedCornerShape(6.dp))
                                .clickable { action() }
                                .padding(horizontal = 8.dp, vertical = 4.dp)
                        ) {
                            Text(label, style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp), color = ElectricBlue)
                        }
                    }
                }
            }
        }

        // Processing Spinner
        if (isProcessing) {
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CircularProgressIndicator(color = CyberEmerald, modifier = Modifier.size(24.dp))
                    Spacer(modifier = Modifier.width(12.dp))
                    Text("Recognizing timetable & section with ML Kit OCR...", color = CyberEmerald, fontSize = 13.sp)
                }
            }
        }

        // Captured Image Thumbnail Preview
        capturedBitmap?.let { bmp ->
            item {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(PrismSurface)
                        .border(1.dp, CyberEmerald.copy(alpha = 0.5f), RoundedCornerShape(12.dp))
                        .padding(10.dp)
                ) {
                    Text(
                        text = "SCANNED IMAGE PREVIEW",
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold
                        ),
                        color = CyberEmerald
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Image(
                        bitmap = bmp.asImageBitmap(),
                        contentDescription = "Captured timetable",
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(180.dp)
                            .clip(RoundedCornerShape(8.dp)),
                        contentScale = ContentScale.Fit
                    )
                }
            }
        }

        // AUTO-DETECTION RESULT & ACTIVATION CARD
        if (currentParseResult != null || detectedSectionResult != null) {
            val sec = detectedSectionResult ?: activeSection
            val sem = currentParseResult?.detectedSemester ?: activeSemester
            val secData = pack?.sections?.find { it.sectionId.equals(sec, ignoreCase = true) }
            val roomName = secData?.defaultRoomId ?: if (sec == "A") "AB-501" else if (sec == "D") "AB-504" else "AB-514"
            val deptName = secData?.department ?: when (sec.uppercase()) {
                "A" -> "Computer Science & Engineering"
                "D" -> "Information Science & Engineering"
                "I" -> "Artificial Intelligence & Machine Learning"
                else -> "Engineering Department"
            }

            item {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(PrismSurface)
                        .border(1.dp, CyberEmerald, RoundedCornerShape(14.dp))
                        .padding(14.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.CheckCircle, contentDescription = null, tint = CyberEmerald, modifier = Modifier.size(22.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "TIMETABLE AUTOMATICALLY ACTIVATED",
                            style = MaterialTheme.typography.titleSmall.copy(
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 11.sp
                            ),
                            color = CyberEmerald
                        )
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    // Semester & Section Badges & Details
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(
                                text = "Active: Semester $sem • Section $sec",
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                color = TextPrimary
                            )
                            Text(
                                text = deptName,
                                style = MaterialTheme.typography.bodySmall,
                                color = ElectricBlue
                            )
                        }

                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(ElectricBlue.copy(alpha = 0.2f))
                                    .border(1.dp, ElectricBlue, RoundedCornerShape(8.dp))
                                    .padding(horizontal = 8.dp, vertical = 6.dp)
                            ) {
                                Text(
                                    text = "SEM $sem",
                                    fontWeight = FontWeight.Bold,
                                    color = ElectricBlue,
                                    fontSize = 13.sp
                                )
                            }
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(CyberEmerald.copy(alpha = 0.2f))
                                    .border(1.dp, CyberEmerald, RoundedCornerShape(8.dp))
                                    .padding(horizontal = 8.dp, vertical = 6.dp)
                            ) {
                                Text(
                                    text = "SEC $sec",
                                    fontWeight = FontWeight.Bold,
                                    color = CyberEmerald,
                                    fontSize = 13.sp
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    Text(
                        text = "Default Classroom: $roomName • Apex Block",
                        style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                        color = TextSecondary
                    )

                    // Urgent Shift Notice if detected
                    currentParseResult?.detectedOverride?.let { ov ->
                        Spacer(modifier = Modifier.height(10.dp))
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .background(AmberAlert.copy(alpha = 0.15f))
                                .border(1.dp, AmberAlert, RoundedCornerShape(8.dp))
                                .padding(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.Warning, contentDescription = null, tint = AmberAlert, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "Relocation: ${ov.targetCourseCode} moved to ${ov.newBuildingName} ${ov.newRoomId} (${ov.newFloorLabel})",
                                style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold, fontSize = 11.sp),
                                color = AmberAlert
                            )
                        }
                    }

                    // Detected Assignment Tasks if any (with due dates parsed from text)
                    val assignments = currentParseResult?.detectedAssignments ?: emptyList()
                    if (assignments.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(10.dp))
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .background(Color(0xFF0F1A2E))
                                .border(1.dp, PrismBorder, RoundedCornerShape(8.dp))
                                .padding(10.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Assignment, contentDescription = null, tint = ElectricBlue, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = "Auto-Saved Assignment Tasks (${assignments.size}):",
                                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                    color = ElectricBlue
                                )
                            }
                            assignments.forEach { task ->
                                Text(
                                    text = "• [${task.subject}] ${task.title} • Due: ${task.dueDate}",
                                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                                    color = TextPrimary,
                                    modifier = Modifier.padding(top = 4.dp)
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    // Primary Button: Go to Schedule
                    Button(
                        onClick = onScheduleUpdated,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = CyberEmeraldDark),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Icon(Icons.Default.CalendarMonth, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("View Updated Sem $sem Sec $sec Schedule ➜", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    }
                }
            }
        }

        item {
            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}
