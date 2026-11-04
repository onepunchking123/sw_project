package com.example.careguardcamera.ui.nav

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccessTime
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.runtime.remember
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.example.careguardcamera.data.SetupRepository
import com.example.careguardcamera.ui.screens.ContactManagementScreen
import com.example.careguardcamera.ui.screens.ManagedCamerasScreen
import com.example.careguardcamera.ui.screens.SetupCompleteScreen
import com.example.careguardcamera.data.BackendRepository
import com.example.careguardcamera.ui.screens.ServerSettingsScreen
import com.example.careguardcamera.ui.screens.RecordDetailScreen
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import com.example.careguardcamera.ui.screens.EmergencyScreen
import com.example.careguardcamera.ui.screens.EventDetailScreen
import com.example.careguardcamera.ui.screens.EventNoteScreen
import com.example.careguardcamera.ui.screens.HomeScreen
import com.example.careguardcamera.ui.screens.LiveScreen
import com.example.careguardcamera.ui.screens.PatientInfoScreen
import com.example.careguardcamera.ui.screens.QrScanScreen
import com.example.careguardcamera.ui.screens.RecordsScreen
import com.example.careguardcamera.ui.screens.SettingsScreen
import com.example.careguardcamera.ui.screens.SettingsDestination
import com.example.careguardcamera.ui.screens.PatientEditScreen
import com.example.careguardcamera.ui.screens.PrivacySettingsScreen
import com.example.careguardcamera.ui.screens.StillAlertSettingsScreen
import com.example.careguardcamera.ui.screens.DataUsageScreen
import com.example.careguardcamera.ui.screens.AppInfoScreen
import com.example.careguardcamera.ui.screens.TimelineScreen
import com.example.careguardcamera.ui.theme.CGColor
import com.example.careguardcamera.ui.theme.CGType

/** 경로 상수. */
object Routes {
    const val PATIENT_INFO = "onboarding/patient"
    const val CONTACT_SETUP = "onboarding/contacts"
    const val COMPLETE = "onboarding/complete"
    const val ADD_CAMERA = "settings/cameras/add"
    const val QR_SCAN = "onboarding/qr"
    const val SERVER = "settings/server"
    const val RECORD_DETAIL = "record/{id}"
    fun recordDetail(id: String) = "record/$id"
    const val RECORD_NOTE = "record/{id}/note"
    fun recordNote(id: String) = "record/$id/note"
    const val HOME = "home"
    const val TIMELINE = "timeline"
    const val RECORDS = "records"
    const val SETTINGS = "settings"
    const val LIVE = "live"
    const val EMERGENCY = "emergency"
    const val EVENT_DETAIL = "event/{id}"
    fun eventDetail(id: String) = "event/$id"
    const val EVENT_NOTE = "event/{id}/note"
    fun eventNote(id: String) = "event/$id/note"
}

private data class Tab(val route: String, val label: String, val icon: ImageVector)

private val tabs = listOf(
    Tab(Routes.HOME, "홈", Icons.Outlined.Home),
    Tab(Routes.TIMELINE, "타임라인", Icons.Outlined.AccessTime),
    Tab(Routes.RECORDS, "기록", Icons.Outlined.Edit),
    Tab(Routes.SETTINGS, "환자·설정", Icons.Outlined.Person)
)

@Composable
fun CaregiverApp(startRoute: String = if (BackendRepository.completed) Routes.HOME else Routes.PATIENT_INFO, onLogout: () -> Unit) {
    val nav = rememberNavController()
    val backStack by nav.currentBackStackEntryAsState()
    val currentRoute = backStack?.destination?.hierarchy?.firstOrNull { d -> tabs.any { it.route == d.route } }?.route
    val showTabs = currentRoute != null
    LaunchedEffect(currentRoute) { BackendRepository.refresh() }
    val scope = rememberCoroutineScope()

    // 앱이 화면에 있는 동안 카운트다운이 끝난 낙상 긴급 상황을 확인하고, 새로 생기면 긴급 화면을 바로 띄운다.
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                BackendRepository.refreshIncident()
                delay(INCIDENT_POLL_MILLIS)
            }
        }
    }
    val activeIncident by BackendRepository.activeIncident.collectAsState()
    var alertedIncidentId by rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(activeIncident?.id) {
        val incident = activeIncident ?: return@LaunchedEffect
        if (incident.id == alertedIncidentId) return@LaunchedEffect
        alertedIncidentId = incident.id
        if (nav.currentDestination?.route != Routes.EMERGENCY) nav.navigate(Routes.EMERGENCY)
    }

    Scaffold(
        containerColor = CGColor.Surface,
        bottomBar = {
            if (showTabs) CGTabBar(current = currentRoute, onSelect = { nav.navigateTab(it) })
        }
    ) { inner ->
        Box(Modifier.fillMaxSize().padding(bottom = inner.calculateBottomPadding())) {
            NavHost(navController = nav, startDestination = startRoute) {
                composable(Routes.PATIENT_INFO) {
                    PatientInfoScreen(onNext = { nav.navigate(Routes.CONTACT_SETUP) })
                }
                composable(Routes.CONTACT_SETUP) {
                    ContactManagementScreen(onBack = { nav.popBackStack() }, onNext = { nav.navigate(Routes.QR_SCAN) })
                }
                composable(Routes.COMPLETE) {
                    SetupCompleteScreen(onDone = {
                        nav.navigate(Routes.HOME) { popUpTo(Routes.PATIENT_INFO) { inclusive = true } }
                    })
                }
                composable(Routes.ADD_CAMERA) {
                    QrScanScreen(onBack = { nav.popBackStack() }, onDone = { nav.popBackStack() }, allowSkip = false)
                }
                composable(Routes.QR_SCAN) {
                    QrScanScreen(
                        onBack = { nav.popBackStack() },
                        onDone = { nav.navigate(Routes.COMPLETE) }
                    )
                }
                composable(Routes.HOME) {
                    HomeScreen(
                        onOpenLive = { nav.navigate(Routes.LIVE) },
                        onOpenTimeline = { nav.navigateTab(Routes.TIMELINE) },
                        onOpenEmergency = { nav.navigate(Routes.EMERGENCY) }
                    )
                }
                composable(Routes.TIMELINE) {
                    TimelineScreen(onOpenEvent = { nav.navigate(Routes.eventDetail(it)) }, onOpenRecord = { nav.navigate(Routes.recordDetail(it)) })
                }
                composable(Routes.RECORDS) { RecordsScreen(onOpenRecord = { nav.navigate(Routes.recordDetail(it)) }) }
                composable(Routes.SERVER) {
                    ServerSettingsScreen(onBack = { nav.popBackStack() }, onChanged = {
                        nav.navigate(Routes.PATIENT_INFO) { popUpTo(nav.graph.id) { inclusive = true } }
                    }, onSelected = {
                        nav.navigate(Routes.HOME) { popUpTo(nav.graph.id) { inclusive = true } }
                    })
                }
                composable(Routes.RECORD_DETAIL) { entry ->
                    val id = entry.arguments?.getString("id").orEmpty()
                    RecordDetailScreen(recordId = id, onBack = { nav.popBackStack() }, onAddNote = { nav.navigate(Routes.recordNote(id)) })
                }
                composable(Routes.RECORD_NOTE) { entry ->
                    EventNoteScreen(eventId = entry.arguments?.getString("id").orEmpty(), manual = true, onBack = { nav.popBackStack() })
                }
                composable(Routes.SETTINGS) {
                    SettingsScreen(onOpen = { nav.navigate(it.route) }, onLogout = onLogout)
                }
                composable(SettingsDestination.PATIENT.route) { PatientEditScreen(onBack = { nav.popBackStack() }) }
                composable(SettingsDestination.CAREGIVERS.route) { ContactManagementScreen(onBack = { nav.popBackStack() }) }
                composable(SettingsDestination.CAMERAS.route) { ManagedCamerasScreen(onBack = { nav.popBackStack() }, onAdd = { nav.navigate(Routes.ADD_CAMERA) }) }
                composable(SettingsDestination.PRIVACY.route) { PrivacySettingsScreen(onBack = { nav.popBackStack() }) }
                composable(SettingsDestination.CONTACTS.route) { ContactManagementScreen(onBack = { nav.popBackStack() }) }
                composable(SettingsDestination.STILL_ALERT.route) { StillAlertSettingsScreen(onBack = { nav.popBackStack() }) }
                composable(SettingsDestination.DATA_USAGE.route) { DataUsageScreen(onBack = { nav.popBackStack() }) }
                composable(SettingsDestination.APP_INFO.route) { AppInfoScreen(onBack = { nav.popBackStack() }) }
                composable(Routes.LIVE) { LiveScreen(onBack = { nav.popBackStack() }) }
                composable(Routes.EMERGENCY) { EmergencyScreen(onBack = { nav.popBackStack() }, onResolve = { scope.launch { if (BackendRepository.resolveIncident() == null) nav.popBackStack() } }) }
                composable(Routes.EVENT_DETAIL) { entry ->
                    val id = entry.arguments?.getString("id") ?: ""
                    EventDetailScreen(eventId = id, onBack = { nav.popBackStack() }, onAddNote = { nav.navigate(Routes.eventNote(id)) })
                }
                composable(Routes.EVENT_NOTE) { entry ->
                    EventNoteScreen(eventId = entry.arguments?.getString("id") ?: "", onBack = { nav.popBackStack() })
                }
            }
        }
    }
}

private fun NavHostController.navigateTab(route: String) {
    navigate(route) {
        // 온보딩은 QR 단계에서 이미 pop 되므로 HOME 을 탭 루트로 둔다
        popUpTo(Routes.HOME) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}

/** 4탭 바텀 바. 선택 탭은 brand 색 + brandSoft 알약 배경. */
@Composable
fun CGTabBar(current: String?, onSelect: (String) -> Unit) {
    Column(Modifier.fillMaxWidth().shadow(elevation = 14.dp, spotColor = Color(0x1A3C286E), ambientColor = Color(0x143C286E)).background(CGColor.Paper).navigationBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp)) {
            tabs.forEach { tab ->
                val on = tab.route == current
                val tint = if (on) CGColor.Brand else CGColor.Ink3
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier
                        .weight(1f)
                        .height(54.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(if (on) CGColor.BrandSoft else Color.Transparent)
                        .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { onSelect(tab.route) }
                        .padding(top = 8.dp)
                ) {
                    Icon(tab.icon, contentDescription = tab.label, tint = tint, modifier = Modifier.size(24.dp))
                    Text(tab.label, style = CGType.Caption.copy(fontSize = 11.sp), color = tint)
                }
            }
        }
    }
}

private const val INCIDENT_POLL_MILLIS = 5_000L
