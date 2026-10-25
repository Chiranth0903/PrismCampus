package com.prismcampus.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.prismcampus.app.ai.HybridRouter
import com.prismcampus.app.ai.ChatSession
import androidx.compose.runtime.rememberCoroutineScope
import com.prismcampus.app.ai.LocalSLMManager
import com.prismcampus.app.ai.OcrManager
import com.prismcampus.app.ai.VoiceAssistantManager
import com.prismcampus.app.data.Building
import com.prismcampus.app.data.CampusDatabase
import com.prismcampus.app.data.CampusRepository
import com.prismcampus.app.data.ResolvedClassDestination
import com.prismcampus.app.data.ScheduleManager
import com.prismcampus.app.facilities.IssueReporter
import com.prismcampus.app.facilities.ReportIssueModalDialog
import com.prismcampus.app.location.CampusLocationManager
import com.prismcampus.app.schedule.ScheduleTasksScreen
import com.prismcampus.app.ui.screens.CameraOcrScreen
import com.prismcampus.app.ui.screens.CampusMapScreen
import com.prismcampus.app.ui.screens.CampusOnboardingScreen
import com.prismcampus.app.ui.screens.HomeScreen
import com.prismcampus.app.ui.theme.CyberEmerald
import com.prismcampus.app.ui.theme.PrismBackground
import com.prismcampus.app.ui.theme.PrismCampusTheme
import com.prismcampus.app.ui.theme.PrismSurface
import com.prismcampus.app.ui.theme.TextMuted
import com.prismcampus.app.ui.theme.TextPrimary
import com.prismcampus.app.ui.theme.TextSecondary

enum class CampusNavTab(val label: String, val icon: ImageVector) {
    HOME("Home", Icons.Default.Home),
    SCHEDULE("Schedule", Icons.Default.CalendarMonth),
    MAP("Map & Tour", Icons.Default.Map)
}

class MainActivity : ComponentActivity() {

    private lateinit var database: CampusDatabase
    private lateinit var repository: CampusRepository
    private lateinit var scheduleManager: ScheduleManager
    private lateinit var localSlmManager: LocalSLMManager
    private lateinit var ocrManager: OcrManager
    private lateinit var voiceManager: VoiceAssistantManager
    private lateinit var hybridRouter: HybridRouter
    private lateinit var issueReporter: IssueReporter
    private lateinit var locationManager: CampusLocationManager

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { _ ->
        locationManager.startLocationUpdates()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Initialize core on-device layers
        database = CampusDatabase(applicationContext)
        repository = CampusRepository(applicationContext)
        scheduleManager = ScheduleManager(repository)
        localSlmManager = LocalSLMManager(applicationContext)
        ocrManager = OcrManager(repository, localSlmManager)
        voiceManager = VoiceAssistantManager(this)
        hybridRouter = HybridRouter(applicationContext, repository, scheduleManager, database, localSlmManager)
        issueReporter = IssueReporter(applicationContext, database)
        locationManager = CampusLocationManager(applicationContext)

        requestCampusPermissions()

        setContent {
            PrismCampusTheme {
                val isOnboarded by repository.isOnboarded.collectAsState()

                if (!isOnboarded) {
                    CampusOnboardingScreen(
                        repository = repository,
                        onOnboardingCompleted = {
                            // Automatically advances to MainAppScaffold when isOnboarded updates
                        }
                    )
                } else {
                    MainAppScaffold(
                        database = database,
                        repository = repository,
                        scheduleManager = scheduleManager,
                        hybridRouter = hybridRouter,
                        issueReporter = issueReporter,
                        ocrManager = ocrManager,
                        voiceManager = voiceManager,
                        locationManager = locationManager,
                        localSlmManager = localSlmManager
                    )
                }
            }
        }
    }

    private fun requestCampusPermissions() {
        val permissions = mutableListOf<String>()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            permissions.add(Manifest.permission.CAMERA)
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            permissions.add(Manifest.permission.RECORD_AUDIO)
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            permissions.add(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            permissions.add(Manifest.permission.ACCESS_COARSE_LOCATION)
        }
        if (permissions.isNotEmpty()) {
            permissionLauncher.launch(permissions.toTypedArray())
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        locationManager.stopLocationUpdates()
        voiceManager.shutdown()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainAppScaffold(
    database: CampusDatabase,
    repository: CampusRepository,
    scheduleManager: ScheduleManager,
    hybridRouter: HybridRouter,
    issueReporter: IssueReporter,
    ocrManager: OcrManager,
    voiceManager: VoiceAssistantManager,
    locationManager: CampusLocationManager,
    localSlmManager: LocalSLMManager
) {
    var currentTab by remember { mutableStateOf(CampusNavTab.HOME) }
    val homeScrollState = androidx.compose.foundation.lazy.rememberLazyListState()
    val chatScope = rememberCoroutineScope()
    val chatContext = androidx.compose.ui.platform.LocalContext.current.applicationContext
    val chatPrefs = remember(chatContext) { chatContext.getSharedPreferences("assistant_chat", 0) }
    // Chat belongs to this open activity session, including all three tabs.
    // Remove history written by older versions; messages never go back to disk.
    LaunchedEffect(chatPrefs) { chatPrefs.edit().remove("history").apply() }
    val chatSession = remember(hybridRouter, voiceManager) {
        ChatSession(
            scope = chatScope,
            initialMessages = emptyList(),
            save = {},
            answer = { query, language -> hybridRouter.routeQuery(query, language) },
            speak = { voiceManager.speakDirectly(it) },
            onIssue = { issueReporter.flushReportsToUnoQ() }
        )
    }
    var showOcrScreen by remember { mutableStateOf(false) }
    var showIssueDialog by remember { mutableStateOf(false) }
    var showChangeCampusDialog by remember { mutableStateOf(false) }

    val pack by repository.prismPack.collectAsState()
    val activeCampusId by repository.activeCampusId.collectAsState()

    var mapTargetDestination by remember { mutableStateOf<ResolvedClassDestination?>(null) }
    var mapTargetBuilding by remember { mutableStateOf<Building?>(null) }

    // Real-time continuous background sync over Wi-Fi: Fetches published overrides & notices in ~3s
    LaunchedEffect(activeCampusId) {
        while (true) {
            repository.syncAnnouncementsFromUnoQ()
            issueReporter.flushReportsToUnoQ()
            kotlinx.coroutines.delay(3000L)
        }
    }

    val campusDisplayName = pack?.shortName?.ifBlank { pack?.campusName } ?: activeCampusId

    Scaffold(
        containerColor = PrismBackground,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = when {
                            showOcrScreen -> "Scan your timetable"
                            currentTab == CampusNavTab.HOME -> "PrismCampus"
                            currentTab == CampusNavTab.SCHEDULE -> "Your week"
                            else -> "$campusDisplayName campus"
                        },
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        color = TextPrimary
                    )
                },
                actions = {
                    // Quick Action: Switch Campus
                    IconButton(
                        onClick = { showChangeCampusDialog = true }
                    ) {
                        Icon(
                            imageVector = Icons.Default.SwapHoriz,
                            contentDescription = "Change Campus",
                            tint = CyberEmerald,
                            modifier = Modifier.size(22.dp)
                        )
                    }

                    // Quick Action: Snap OCR for Students
                    IconButton(onClick = {
                        showOcrScreen = !showOcrScreen
                    }) {
                        Icon(
                            imageVector = Icons.Default.CameraAlt,
                            contentDescription = "Snap OCR",
                            tint = if (showOcrScreen) CyberEmerald else TextSecondary,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = PrismSurface,
                    titleContentColor = TextPrimary
                )
            )
        },
        bottomBar = {
            NavigationBar(
                containerColor = PrismSurface,
                contentColor = TextPrimary
            ) {
                CampusNavTab.values().forEach { tab ->
                    val isSelected = !showOcrScreen && currentTab == tab
                    NavigationBarItem(
                        selected = isSelected,
                        onClick = {
                            showOcrScreen = false
                            currentTab = tab
                        },
                        icon = {
                            Icon(
                                imageVector = tab.icon,
                                contentDescription = tab.label
                            )
                        },
                        label = {
                            Text(
                                text = tab.label,
                                style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp)
                            )
                        },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = CyberEmerald,
                            selectedTextColor = CyberEmerald,
                            unselectedIconColor = TextMuted,
                            unselectedTextColor = TextMuted,
                            indicatorColor = CyberEmerald.copy(alpha = 0.15f)
                        )
                    )
                }
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .background(PrismBackground)
        ) {
            when {
                showOcrScreen -> {
                    CameraOcrScreen(
                        ocrManager = ocrManager,
                        repository = repository,
                        database = database,
                        onScheduleUpdated = {
                            showOcrScreen = false
                            currentTab = CampusNavTab.SCHEDULE
                        }
                    )
                }
                currentTab == CampusNavTab.HOME -> {
                    HomeScreen(
                        scrollState = homeScrollState,
                        chatSession = chatSession,
                        repository = repository,
                        scheduleManager = scheduleManager,
                        hybridRouter = hybridRouter,
                        voiceManager = voiceManager,
                        localSlmManager = localSlmManager,
                        issueReporter = issueReporter,
                        onNavigateToDestination = { dest ->
                            if (dest?.slot?.routeAvailable != false) {
                                mapTargetDestination = dest
                                mapTargetBuilding = null
                                currentTab = CampusNavTab.MAP
                            } else {
                                android.widget.Toast.makeText(chatContext, "Lab location is not confirmed in this demo. Check with the department.", android.widget.Toast.LENGTH_LONG).show()
                            }
                        },
                        onNavigateToBuilding = { bldg ->
                            mapTargetBuilding = bldg
                            mapTargetDestination = null
                            currentTab = CampusNavTab.MAP
                        },
                        onOpenOcr = { showOcrScreen = true },
                        onOpenScheduleTab = { currentTab = CampusNavTab.SCHEDULE },
                        onOpenIssueReporterModal = { showIssueDialog = true }
                    )
                }
                currentTab == CampusNavTab.SCHEDULE -> {
                    ScheduleTasksScreen(
                        repository = repository,
                        scheduleManager = scheduleManager,
                        database = database,
                        onNavigateToClass = { dest ->
                            if (dest.slot.routeAvailable) {
                                mapTargetDestination = dest
                                mapTargetBuilding = null
                                currentTab = CampusNavTab.MAP
                            } else {
                                android.widget.Toast.makeText(chatContext, "Lab location is not confirmed in this demo. Check with the department.", android.widget.Toast.LENGTH_LONG).show()
                            }
                        }
                    )
                }
                currentTab == CampusNavTab.MAP -> {
                    CampusMapScreen(
                        repository = repository,
                        hybridRouter = hybridRouter,
                        voiceManager = voiceManager,
                        locationManager = locationManager,
                        targetDestination = mapTargetDestination,
                        targetBuilding = mapTargetBuilding
                    )
                }
            }

            // Maintenance Voice / Text Reporting Modal Dialog (Student reporting)
            if (showIssueDialog) {
                ReportIssueModalDialog(
                    hybridRouter = hybridRouter,
                    voiceManager = voiceManager,
                    onDismiss = { showIssueDialog = false },
                    onIssueReported = {
                        showIssueDialog = false
                    }
                )
            }

            // Change Campus Confirmation Dialog
            if (showChangeCampusDialog) {
                AlertDialog(
                    onDismissRequest = { showChangeCampusDialog = false },
                    title = {
                        Text(
                            text = "Switch College Campus?",
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                            color = TextPrimary
                        )
                    },
                    text = {
                        Text(
                            text = "Currently viewing $campusDisplayName ($activeCampusId). Switching campuses lets you connect to another college. Your recorded assignments, issue history, and cached packs will be preserved.",
                            style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
                            color = TextSecondary
                        )
                    },
                    confirmButton = {
                        TextButton(
                            onClick = {
                                showChangeCampusDialog = false
                                repository.changeCampus()
                            }
                        ) {
                            Text("Switch Campus", color = CyberEmerald, fontWeight = FontWeight.Bold)
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { showChangeCampusDialog = false }) {
                            Text("Cancel", color = TextMuted)
                        }
                    },
                    containerColor = PrismSurface,
                    textContentColor = TextPrimary
                )
            }
        }
    }
}
