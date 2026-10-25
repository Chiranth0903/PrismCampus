package com.prismcampus.app.ui.screens

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.BatteryManager
import android.util.Log
import android.speech.RecognizerIntent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
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
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AirplanemodeActive
import androidx.compose.material.icons.filled.BatteryStd
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Campaign
import androidx.compose.material.icons.filled.DirectionsWalk
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.NearMe
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.prismcampus.app.ai.HybridRouter
import com.prismcampus.app.ai.ChatSession
import com.prismcampus.app.ai.LocalSLMManager
import com.prismcampus.app.ai.RouterAction
import com.prismcampus.app.ai.RouterResult
import com.prismcampus.app.ai.SpeechState
import com.prismcampus.app.ai.SupportedLanguage
import com.prismcampus.app.ai.VoiceAssistantManager
import com.prismcampus.app.data.Building
import com.prismcampus.app.data.CampusRepository
import com.prismcampus.app.data.ResolvedClassDestination
import com.prismcampus.app.data.ScheduleManager
import com.prismcampus.app.facilities.IssueReporter
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
import com.prismcampus.app.ui.theme.VioletAccent
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun HomeScreen(
    scrollState: androidx.compose.foundation.lazy.LazyListState,
    chatSession: ChatSession,
    repository: CampusRepository,
    scheduleManager: ScheduleManager,
    hybridRouter: HybridRouter,
    voiceManager: VoiceAssistantManager,
    localSlmManager: LocalSLMManager? = null,
    issueReporter: IssueReporter? = null,
    onNavigateToDestination: (ResolvedClassDestination?) -> Unit,
    onNavigateToBuilding: (Building?) -> Unit,
    onOpenOcr: () -> Unit,
    onOpenScheduleTab: () -> Unit,
    onOpenIssueReporterModal: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    val activeSection by repository.selectedSectionId.collectAsState()
    val overrides by repository.overrides.collectAsState()
    val campusMessages by repository.campusMessages.collectAsState()
    val pack by repository.prismPack.collectAsState()
    val activeCampusId by repository.activeCampusId.collectAsState()

    val sectionPills = remember(pack) {
        val list = pack?.sections?.map { s ->
            s.sectionId to (s.defaultRoomId.ifBlank { "Class" })
        }
        if (!list.isNullOrEmpty()) list else listOf("A" to "AB-501", "D" to "AB-504", "I" to "AB-514")
    }

    val selectedLanguage by voiceManager.selectedLanguage.collectAsState()
    val isSpeaking by voiceManager.isSpeaking.collectAsState()
    val isListening by voiceManager.isListening.collectAsState()
    val speechState by voiceManager.speechState.collectAsState()
    val interimSpeechText by voiceManager.interimSpeechText.collectAsState()
    val finalSpeechQuery by voiceManager.finalSpeechQuery.collectAsState()
    val soundLevelRms by voiceManager.soundLevelRms.collectAsState()
    val statusMessage by voiceManager.statusMessage.collectAsState()
    val isRecognitionAvailable by voiceManager.isRecognitionAvailable.collectAsState()

    val eval = scheduleManager.evaluateSchedule()
    val nextClass = eval.nextClass

    // Sample dynamic battery percentage
    val batteryManager = remember { context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager }
    val batteryLevel = remember { batteryManager?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: 85 }

    // Airplane Mode Detection
    var isAirplaneMode by remember(context) { mutableStateOf(hybridRouter.isAirplaneModeOn()) }
    var isOnline by remember(context) { mutableStateOf(hybridRouter.orchestrator.geminiProvider.isOnline()) }

    // Live RAM Telemetry: lifecycle-aware periodic sampling of actual app process PSS memory
    var memoryTelemetry by remember { mutableStateOf(repository.sampleAppProcessMemory(context)) }
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                isAirplaneMode = hybridRouter.isAirplaneModeOn()
                isOnline = hybridRouter.orchestrator.geminiProvider.isOnline()
                memoryTelemetry = withContext(Dispatchers.IO) { repository.sampleAppProcessMemory(context) }
                delay(1000)
            }
        }
    }
    val ramUsedMb = memoryTelemetry.usedMemoryMb
    val ramBudgetMb = memoryTelemetry.maxMemoryMb
    val ramRatio = memoryTelemetry.memoryUsageRatio

    // Voice & Text AI Query Input State
    var textInput by remember { mutableStateOf("") }
    var showDiagnostics by remember { mutableStateOf(false) }
    val isRoutingAi by chatSession.isProcessing.collectAsState()
    var showModelSetupDialog by remember { mutableStateOf(false) }

    val chatHistory by chatSession.history.collectAsState()

    fun executeQuery(query: String) {
        chatSession.submit(query, selectedLanguage)
    }
    val audioPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            voiceManager.startListening()
        }
    }

    // Live feedback: display words in the input box as the user is speaking into the mic
    LaunchedEffect(interimSpeechText) {
        if (isListening && interimSpeechText.isNotBlank()) {
            textInput = interimSpeechText
        }
    }

    // When the speech recognizer finishes, execute the final recognized query (Single-dispatch path)
    LaunchedEffect(finalSpeechQuery) {
        if (finalSpeechQuery.isNotBlank()) {
            val pending = finalSpeechQuery.trim()
            voiceManager.clearFinalSpeechQuery()
            textInput = ""
            executeQuery(pending)
        }
    }

    LazyColumn(
        state = scrollState,
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Column(
                modifier = Modifier.fillMaxWidth()
                    .padding(top = 8.dp, bottom = 4.dp)
                    .clip(RoundedCornerShape(28.dp))
                    .background(PrismSurface)
                    .padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = "${pack?.shortName?.ifBlank { activeCampusId } ?: activeCampusId}  /  YOUR CAMPUS COMPANION",
                    style = MaterialTheme.typography.labelSmall,
                    color = CyberEmerald,
                    letterSpacing = 1.5.sp
                )
                Text("Your campus.\nOne step ahead.",
                    style = MaterialTheme.typography.headlineLarge,
                    color = TextPrimary)
                Text("Everything you need for the day ahead.",
                    style = MaterialTheme.typography.bodyLarge, color = TextSecondary)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = onOpenScheduleTab,
                        colors = ButtonDefaults.buttonColors(containerColor = CyberEmerald, contentColor = Color(0xFF102B24)),
                        shape = RoundedCornerShape(14.dp)) {
                        Icon(Icons.Default.CalendarMonth, null, modifier = Modifier.size(17.dp))
                        Spacer(Modifier.width(7.dp))
                        Text("My schedule", fontWeight = FontWeight.SemiBold)
                    }
                    IconButton(onClick = onOpenOcr) {
                        Icon(Icons.Default.CameraAlt, "Scan timetable", tint = TextPrimary)
                    }
                }
                if (activeCampusId == "IITG_DEMO") {
                    Text("DEMO BACKUP · Provisional times, approximate pins. Verify rooms before travelling.",
                        style = MaterialTheme.typography.labelSmall, color = AmberAlert)
                }
            }
        }
        // ==========================================
        // 3. UPCOMING CLASS ROW
        // ==========================================
        item {
            if (nextClass != null) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(PrismSurface)
                        .border(1.dp, PrismBorder, RoundedCornerShape(14.dp))
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(CyberEmerald.copy(alpha = 0.15f))
                                    .padding(horizontal = 6.dp, vertical = 2.dp)
                            ) {
                                Text(
                                    text = "NEXT CLASS",
                                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp, fontWeight = FontWeight.Bold),
                                    color = CyberEmerald
                                )
                            }
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "${nextClass.slot.startTime} - ${nextClass.slot.endTime}",
                                style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace, fontSize = 10.sp),
                                color = TextMuted
                            )
                        }

                        Spacer(modifier = Modifier.height(3.dp))

                        Text(
                            text = nextClass.slot.courseName,
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold, fontSize = 14.sp),
                            color = TextPrimary,
                            maxLines = 3
                        )

                        Text(
                            text = "${nextClass.buildingName} • ${nextClass.floorLabel} • ${nextClass.roomId}",
                            style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                            color = ElectricBlue
                        )
                    }

                    Spacer(modifier = Modifier.width(8.dp))

                    Button(
                        onClick = { onNavigateToDestination(nextClass) },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = CyberEmeraldDark,
                            contentColor = TextPrimary
                        ),
                        shape = RoundedCornerShape(8.dp),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 10.dp, vertical = 6.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.NearMe,
                            contentDescription = null,
                            modifier = Modifier.size(15.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "Go",
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold)
                        )
                    }
                }
            }
        }

        // ==========================================
        // 1. TOP TELEMETRY BAR (Compact Section Pills + Airplane Mode Indicator)
        // ==========================================
        item {
            Spacer(modifier = Modifier.height(6.dp))
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(PrismSurface)
                    .border(1.dp, PrismBorder, RoundedCornerShape(14.dp))
                    .padding(12.dp)
            ) {
                // Airplane Mode / Connectivity Banner + Battery
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Airplane Mode / Offline Edge Status
                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(20.dp))
                            .background(if (!isOnline) AmberAlert.copy(alpha = 0.15f) else CyberEmerald.copy(alpha = 0.15f))
                            .border(1.dp, if (!isOnline) AmberAlert else CyberEmerald, RoundedCornerShape(20.dp))
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = if (isAirplaneMode) Icons.Default.AirplanemodeActive else Icons.Default.Wifi,
                            contentDescription = null,
                            tint = if (isAirplaneMode) AmberAlert else CyberEmerald,
                            modifier = Modifier.size(13.dp)
                        )
                        Spacer(modifier = Modifier.width(5.dp))
                        Text(
                            text = if (isOnline) "Prism is online" else "Offline · Ready to help",
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold, fontSize = 10.sp),
                            color = if (!isOnline) AmberAlert else CyberEmerald
                        )
                    }

                    // Battery
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.BatteryStd,
                            contentDescription = null,
                            tint = if (batteryLevel > 20) CyberEmerald else AmberAlert,
                            modifier = Modifier.size(15.dp)
                        )
                        Spacer(modifier = Modifier.width(3.dp))
                        Text(
                            text = "$batteryLevel%",
                            style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace, fontSize = 11.sp),
                            color = TextSecondary
                        )
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                androidx.compose.material3.TextButton(
                    onClick = { showDiagnostics = !showDiagnostics },
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)
                ) {
                    Text(if (showDiagnostics) "Hide device details" else "Device details",
                        color = TextMuted, style = MaterialTheme.typography.labelMedium)
                }
                AnimatedVisibility(visible = showDiagnostics) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("App memory: $ramUsedMb / $ramBudgetMb MiB",
                            color = TextSecondary, style = MaterialTheme.typography.bodySmall)
                        LinearProgressIndicator(progress = { ramRatio },
                            modifier = Modifier.fillMaxWidth().height(4.dp),
                            color = CyberEmerald, trackColor = PrismSurfaceVariant)
                        Spacer(Modifier.height(8.dp))
                    }
                }
                // Compact Active Section Selector (Small & Concise)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "SECTION",
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                        color = TextMuted,
                        letterSpacing = 0.5.sp
                    )

                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        sectionPills.forEach { (sec, room) ->
                            val isSelected = activeSection.equals(sec, ignoreCase = true)
                            val bg = if (isSelected) PillActiveBackground else PillInactiveBackground
                            val border = if (isSelected) PillActiveBorder else PillInactiveBorder
                            val textColor = if (isSelected) CyberEmerald else TextSecondary

                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(bg)
                                    .border(1.dp, border, RoundedCornerShape(6.dp))
                                    .clickable { repository.setSelectedSection(sec) }
                                    .padding(horizontal = 8.dp, vertical = 3.dp)
                            ) {
                                Text(
                                    text = "Sec $sec ($room)",
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        fontSize = 10.sp,
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                                    ),
                                    color = textColor
                                )
                            }
                        }
                    }
                }
            }
        }

        // ==========================================
        // 2. VOICE & MULTIMODAL AI HUB (PLACED HIGH UP AS REQUESTED!)
        // ==========================================
        item {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(24.dp))
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(PrismSurfaceVariant, PrismSurface)
                        )
                    )
                    .border(1.dp, ElectricBlue.copy(alpha = 0.18f), RoundedCornerShape(24.dp))
                    .padding(18.dp)
            ) {
                // Header with Language selector
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(28.dp)
                                .clip(CircleShape)
                                .background(ElectricBlue.copy(alpha = 0.2f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.RecordVoiceOver,
                                contentDescription = null,
                                tint = ElectricBlue,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Ask Prism",
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                            color = TextPrimary
                        )
                    }

                    // Trilingual Chips: EN, HI, KN
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        SupportedLanguage.values().forEach { lang ->
                            val isSelected = lang == selectedLanguage
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(if (isSelected) CyberEmerald.copy(alpha = 0.2f) else PrismSurface)
                                    .border(1.dp, if (isSelected) CyberEmerald else PrismBorder, RoundedCornerShape(6.dp))
                                    .clickable { voiceManager.setLanguage(lang) }
                                    .padding(horizontal = 10.dp, vertical = 12.dp)
                            ) {
                                Text(
                                    text = lang.code.uppercase(),
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        fontSize = 10.sp,
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                                    ),
                                    color = if (isSelected) CyberEmerald else TextSecondary
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                // Input Bar with Mic button & Send button
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    OutlinedTextField(
                        value = textInput,
                        onValueChange = { textInput = it },
                        placeholder = {
                            Text(
                                text = when {
                                    isListening -> "Listening in ${selectedLanguage.displayName}..."
                                    speechState == SpeechState.PROCESSING || isRoutingAi -> "Processing query..."
                                    isSpeaking -> "Speaking response..."
                                    else -> "Ask or report an issue..."
                                },
                                fontSize = 14.sp,
                                color = if (isListening) CyberEmerald else TextMuted
                            )
                        },
                        modifier = Modifier.weight(1f),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                        keyboardActions = KeyboardActions(onSend = {
                            if (textInput.isNotBlank()) {
                                val q = textInput.trim()
                                textInput = ""
                                executeQuery(q)
                            }
                        }),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = CyberEmerald,
                            unfocusedBorderColor = PrismBorder,
                            focusedTextColor = TextPrimary,
                            unfocusedTextColor = TextPrimary
                        ),
                        singleLine = true
                    )

                    // In-App Push-To-Talk Mic Button with Live Audio Wave (100% Offline, no external Google dialog!)
                    val animatedBorderWidth by animateDpAsState(
                        targetValue = if (isListening) (2.dp + (soundLevelRms * 6).dp) else 1.dp,
                        label = "micBorderPulse"
                    )

                    Box(
                        modifier = Modifier
                            .size(44.dp)
                            .clip(CircleShape)
                            .border(
                                width = animatedBorderWidth,
                                color = if (isListening) CrimsonAlert else CyberEmerald.copy(alpha = 0.5f),
                                shape = CircleShape
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        IconButton(
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
                            modifier = Modifier
                                .fillMaxSize()
                                .background(if (isListening) CrimsonAlert else CyberEmerald)
                        ) {
                            Icon(
                                imageVector = if (isListening) Icons.Default.Stop else Icons.Default.Mic,
                                contentDescription = "Voice Input",
                                tint = Color.Black,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }

                    // Send text button (Prominently visible in Electric Blue!)
                    IconButton(
                        onClick = {
                            if (textInput.isNotBlank()) {
                                val q = textInput.trim()
                                textInput = ""
                                executeQuery(q)
                            }
                        },
                        modifier = Modifier
                            .size(42.dp)
                            .clip(CircleShape)
                            .background(ElectricBlue)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Send,
                            contentDescription = "Send",
                            tint = Color.Black,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }

                // Status message for listening / retry / permission / model setup
                statusMessage?.let { status ->
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = status,
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp, fontWeight = FontWeight.SemiBold),
                        color = if (isListening) CyberEmerald else AmberAlert,
                        modifier = Modifier
                            .padding(start = 4.dp)
                            .clickable {
                                if (status.contains("model", ignoreCase = true) || status.contains("setup", ignoreCase = true)) {
                                    showModelSetupDialog = true
                                }
                            }
                    )
                }

                // Quick 1-Tap Prompt Chips (Easy testing on emulator or phone!)
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    listOf(
                        "Next class",
                        "Where is the library?",
                        "What is on my schedule?",
                        "Explain Ohm's Law"
                    ).forEach { prompt ->
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(12.dp))
                                .background(Color(0xFF0F1A2E))
                                .border(1.dp, PrismBorder, RoundedCornerShape(12.dp))
                                .clickable { executeQuery(prompt) }
                                .padding(horizontal = 12.dp, vertical = 12.dp)
                        ) {
                            Text(
                                text = prompt,
                                style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                                color = ElectricBlue
                            )
                        }
                    }
                }

                // Loading Indicator
                if (isRoutingAi) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(color = CyberEmerald, modifier = Modifier.size(14.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Finding an answer…",
                            style = MaterialTheme.typography.labelSmall,
                            color = CyberEmerald
                        )
                    }
                }

                // Interactive Chat Stream Feed
                Spacer(modifier = Modifier.height(10.dp))
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    chatHistory.forEach { item ->
                        if (item.isUser) {
                            // User Query Bubble (Right-aligned)
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.End
                            ) {
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(topStart = 12.dp, topEnd = 2.dp, bottomStart = 12.dp, bottomEnd = 12.dp))
                                        .background(Color(0xFF1E2E4A))
                                        .border(1.dp, ElectricBlue.copy(alpha = 0.4f), RoundedCornerShape(topStart = 12.dp, topEnd = 2.dp, bottomStart = 12.dp, bottomEnd = 12.dp))
                                        .padding(horizontal = 12.dp, vertical = 8.dp)
                                ) {
                                    Text(
                                        text = item.text,
                                        style = MaterialTheme.typography.bodySmall.copy(fontSize = 14.sp),
                                        color = TextPrimary
                                    )
                                }
                            }
                        } else {
                            // AI Response Bubble (Left-aligned)
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(topStart = 2.dp, topEnd = 12.dp, bottomStart = 12.dp, bottomEnd = 12.dp))
                                    .background(Color(0xFF0B132B))
                                    .border(1.dp, ElectricBlue.copy(alpha = 0.4f), RoundedCornerShape(topStart = 2.dp, topEnd = 12.dp, bottomStart = 12.dp, bottomEnd = 12.dp))
                                    .padding(10.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = item.tierLabel ?: "AI Assistant",
                                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold, fontSize = 10.sp),
                                        color = if (item.tierLabel?.contains("Gemini") == true) ElectricBlue else CyberEmerald
                                    )
                                    item.latencyMs?.let { ms ->
                                        Text(
                                            text = "$ms ms",
                                            style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace, fontSize = 9.sp),
                                            color = TextMuted
                                        )
                                    }
                                }

                                Spacer(modifier = Modifier.height(4.dp))

                                Text(
                                    text = item.text,
                                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 14.sp),
                                    color = TextPrimary
                                )

                                item.result?.let { res ->
                                    if (res.actionType == RouterAction.NAVIGATE_TO_ROOM ||
                                        res.actionType == RouterAction.NAVIGATE_TO_BUILDING ||
                                        res.targetDestination != null || res.targetBuilding != null) {
                                        Spacer(modifier = Modifier.height(6.dp))
                                        Button(
                                            onClick = {
                                                if (res.targetDestination != null) {
                                                    onNavigateToDestination(res.targetDestination)
                                                } else if (res.targetBuilding != null) {
                                                    onNavigateToBuilding(res.targetBuilding)
                                                }
                                            },
                                            colors = ButtonDefaults.buttonColors(containerColor = ElectricBlue),
                                            shape = RoundedCornerShape(6.dp),
                                            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                                            modifier = Modifier.fillMaxWidth()
                                        ) {
                                            Icon(Icons.Default.DirectionsWalk, contentDescription = null, tint = Color.Black, modifier = Modifier.size(14.dp))
                                            Spacer(modifier = Modifier.width(4.dp))
                                            Text("📍 Route Me There on Campus Map", color = Color.Black, fontWeight = FontWeight.Bold, fontSize = 11.sp)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // ==========================================
        // 4. ANNOUNCEMENTS & CAMPUS MESSAGES BANNER
        // ==========================================
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                // 4A. General Campus Broadcast Message (if any from Uno Q Admin Dashboard)
                if (campusMessages.isNotEmpty()) {
                    val latestMsg = campusMessages.first()
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(Color(0xFF0F1E36))
                            .border(1.dp, ElectricBlue.copy(alpha = 0.5f), RoundedCornerShape(12.dp))
                            .padding(10.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.Campaign,
                                contentDescription = null,
                                tint = ElectricBlue,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = latestMsg.title.ifBlank { "OFFICIAL CAMPUS BROADCAST" },
                                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold, fontSize = 10.sp),
                                color = ElectricBlue
                            )
                            Spacer(modifier = Modifier.weight(1f))
                            Text(
                                text = latestMsg.priority,
                                style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp, fontWeight = FontWeight.SemiBold),
                                color = if (latestMsg.priority.contains("Emergency", ignoreCase = true)) CrimsonAlert else CyberEmerald
                            )
                        }

                        Spacer(modifier = Modifier.height(3.dp))

                        Text(
                            text = latestMsg.content,
                            style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                            color = TextPrimary
                        )
                    }
                }

                // 4B. Classroom Shift Override Alert (if any active override)
                if (overrides.isNotEmpty()) {
                    val ov = overrides.first()
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(AmberAlert.copy(alpha = 0.12f))
                            .border(1.dp, AmberAlert.copy(alpha = 0.6f), RoundedCornerShape(12.dp))
                            .padding(10.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.Warning,
                                contentDescription = null,
                                tint = AmberAlert,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "OFFICIAL ADMIN OVERRIDE: ${ov.targetCourseCode}",
                                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold, fontSize = 10.sp),
                                color = AmberAlert
                            )
                            Spacer(modifier = Modifier.weight(1f))
                            Text(
                                text = "➜ ${ov.newRoomId}",
                                style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace, fontSize = 10.sp, fontWeight = FontWeight.Bold),
                                color = TextPrimary
                            )
                        }

                        Spacer(modifier = Modifier.height(3.dp))

                        Text(
                            text = "${ov.reason} (Moved to ${ov.newBuildingName}, ${ov.newFloorLabel})",
                            style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                            color = TextPrimary
                        )
                    }
                } else if (campusMessages.isEmpty()) {
                    // Default notice when no overrides and no messages
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(Color(0xFF0F1E36))
                            .border(1.dp, ElectricBlue.copy(alpha = 0.3f), RoundedCornerShape(12.dp))
                            .padding(10.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.Campaign,
                                contentDescription = null,
                                tint = ElectricBlue,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "CAMPUS UPDATES",
                                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold, fontSize = 10.sp),
                                color = ElectricBlue
                            )
                        }

                        Spacer(modifier = Modifier.height(3.dp))

                        Text(
                            text = "You're all caught up. Published campus announcements and room changes will appear here.",
                            style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                            color = TextPrimary
                        )
                    }
                }
            }
        }

        item {
            Spacer(modifier = Modifier.height(24.dp))
        }
    }

    if (showModelSetupDialog && localSlmManager != null) {
        ModelSetupDialog(
            voiceManager = voiceManager,
            localSlmManager = localSlmManager,
            onDismiss = { showModelSetupDialog = false }
        )
    }
}

