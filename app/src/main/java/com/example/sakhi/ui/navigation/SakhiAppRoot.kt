package com.example.sakhi.ui.navigation

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import com.example.sakhi.R
import com.example.sakhi.SakhiApplication
import com.example.sakhi.core.model.SessionState
import com.example.sakhi.service.EmergencyForegroundService
import com.example.sakhi.theme.SAKHITheme
import com.example.sakhi.theme.SakhiPrimary
import com.example.sakhi.ui.aisettings.AiProviderSettingsScreen
import com.example.sakhi.ui.contacts.ContactsScreen
import com.example.sakhi.ui.demolab.DemoLabScreen
import com.example.sakhi.ui.device.DeviceScreen
import com.example.sakhi.ui.emergency.ActiveEmergencyScreen
import com.example.sakhi.ui.history.HistoryScreen
import com.example.sakhi.ui.home.HomeScreen
import com.example.sakhi.ui.settings.SettingsScreen
import com.example.sakhi.ui.onboarding.OnboardingScreen
import com.example.sakhi.ui.splash.SplashScreen
import com.example.sakhi.ui.diagnostics.DiagnosticsScreen

enum class SakhiScreen {
    SPLASH,
    ONBOARDING,
    HOME,
    CONTACTS,
    DEVICE,
    SETTINGS,
    HISTORY,
    DEMO_LAB,
    AI_SETTINGS,
    DIAGNOSTICS,
    ACTIVE_EMERGENCY
}

object SakhiNavigationDecider {
    /**
     * Resolves target screen when emergency sessionState changes.
     * When emergency becomes ACTIVATING or ACTIVE, screen transitions to ACTIVE_EMERGENCY.
     * When emergency session terminates (CANCELLED or ENDED), ACTIVE_EMERGENCY cleanly returns to HOME.
     * All other screens remain on their current view.
     */
    fun resolveScreenOnSessionStateChange(
        currentScreen: SakhiScreen,
        sessionState: SessionState
    ): SakhiScreen {
        return when (sessionState) {
            SessionState.ACTIVATING, SessionState.ACTIVE -> SakhiScreen.ACTIVE_EMERGENCY
            SessionState.CANCELLED, SessionState.ENDED -> {
                if (currentScreen == SakhiScreen.ACTIVE_EMERGENCY) SakhiScreen.HOME else currentScreen
            }
            SessionState.IDLE -> currentScreen
        }
    }
}

data class BottomNavItem(
    val screen: SakhiScreen,
    val titleRes: Int,
    val icon: ImageVector
)

@Composable
fun SakhiAppRoot(
    app: SakhiApplication,
    onRequestPermissions: () -> Unit
) {
    val state by app.emergencyStateManager.state.collectAsState()
    val onboardingCompleted by app.preferences.onboardingCompletedFlow.collectAsState(initial = true)
    val appThemePref by app.preferences.themeFlow.collectAsState(initial = "SYSTEM")

    val isDarkTheme = when (appThemePref) {
        "LIGHT" -> false
        "DARK" -> true
        else -> androidx.compose.foundation.isSystemInDarkTheme()
    }

    var currentScreen by remember { mutableStateOf(SakhiScreen.SPLASH) }

    // Start/Stop Foreground Service based on emergency state & ensure clean screen navigation
    LaunchedEffect(state.sessionState) {
        if (state.sessionState == SessionState.ACTIVATING || state.sessionState == SessionState.ACTIVE) {
            EmergencyForegroundService.startEmergency(app)
        } else if (state.sessionState == SessionState.CANCELLED || state.sessionState == SessionState.ENDED) {
            EmergencyForegroundService.notifyEmergencyEnded(app)
        }
        currentScreen = SakhiNavigationDecider.resolveScreenOnSessionStateChange(currentScreen, state.sessionState)
    }

    // Keep background safety monitoring active whenever on the Home screen
    LaunchedEffect(currentScreen) {
        if (currentScreen == SakhiScreen.HOME && !state.isEmergencyActive) {
            EmergencyForegroundService.startMonitoring(app)
        }
    }

    val isEmergencyMode = currentScreen == SakhiScreen.ACTIVE_EMERGENCY || state.isEmergencyActive

    SAKHITheme(darkTheme = isDarkTheme, isEmergencyMode = isEmergencyMode) {
        val navItems = listOf(
            BottomNavItem(SakhiScreen.HOME, R.string.nav_home, Icons.Default.Home),
            BottomNavItem(SakhiScreen.CONTACTS, R.string.nav_contacts, Icons.Default.People),
            BottomNavItem(SakhiScreen.DEVICE, R.string.nav_device, Icons.Default.Key),
            BottomNavItem(SakhiScreen.HISTORY, R.string.nav_history, Icons.Default.History),
            BottomNavItem(SakhiScreen.SETTINGS, R.string.nav_settings, Icons.Default.Settings)
        )

        Scaffold(
            bottomBar = {
                val showBottomBar = currentScreen in listOf(
                    SakhiScreen.HOME,
                    SakhiScreen.CONTACTS,
                    SakhiScreen.DEVICE,
                    SakhiScreen.SETTINGS,
                    SakhiScreen.HISTORY
                ) && !state.isEmergencyActive

                if (showBottomBar) {
                    NavigationBar(
                        containerColor = MaterialTheme.colorScheme.surface,
                        contentColor = MaterialTheme.colorScheme.onSurface
                    ) {
                        navItems.forEach { item ->
                            val selected = currentScreen == item.screen
                            NavigationBarItem(
                                selected = selected,
                                onClick = { currentScreen = item.screen },
                                icon = { Icon(imageVector = item.icon, contentDescription = stringResource(item.titleRes)) },
                                label = { Text(stringResource(item.titleRes)) },
                                colors = NavigationBarItemDefaults.colors(
                                    selectedIconColor = SakhiPrimary,
                                    selectedTextColor = SakhiPrimary,
                                    indicatorColor = SakhiPrimary.copy(alpha = 0.12f)
                                )
                            )
                        }
                    }
                }
            }
        ) { paddingValues ->
            Box(modifier = Modifier.fillMaxSize().padding(paddingValues)) {
                when (currentScreen) {
                    SakhiScreen.SPLASH -> {
                        SplashScreen(
                            onNavigateNext = {
                                currentScreen = if (!onboardingCompleted) SakhiScreen.ONBOARDING else SakhiScreen.HOME
                            }
                        )
                    }

                    SakhiScreen.ONBOARDING -> {
                        OnboardingScreen(
                            permissionsManager = app.permissionsManager,
                            contactRepository = app.contactRepository,
                            bleManager = app.bleManager,
                            preferences = app.preferences,
                            onCompleteOnboarding = { currentScreen = SakhiScreen.HOME },
                            onRequestPermissions = onRequestPermissions
                        )
                    }

                    SakhiScreen.HOME -> {
                        HomeScreen(
                            emergencyStateManager = app.emergencyStateManager,
                            permissionsManager = app.permissionsManager,
                            contactRepository = app.contactRepository,
                            sessionHistoryRepository = app.sessionHistoryRepository,
                            onNavigateToDemoLab = { currentScreen = SakhiScreen.DEMO_LAB },
                            onNavigateToAiSettings = { currentScreen = SakhiScreen.AI_SETTINGS },
                            onNavigateToEmergency = { currentScreen = SakhiScreen.ACTIVE_EMERGENCY },
                            onNavigateToContacts = { currentScreen = SakhiScreen.CONTACTS },
                            onNavigateToDevice = { currentScreen = SakhiScreen.DEVICE }
                        )
                    }

                    SakhiScreen.CONTACTS -> {
                        ContactsScreen(
                            contactRepository = app.contactRepository,
                            alertDispatcher = app.alertDispatcher
                        )
                    }

                    SakhiScreen.DEVICE -> {
                        DeviceScreen(
                            state = state,
                            bleManager = app.bleManager,
                            btAudioReceiver = app.btAudioReceiver,
                            onNavigateToDiagnostics = { currentScreen = SakhiScreen.DIAGNOSTICS }
                        )
                    }

                    SakhiScreen.SETTINGS -> {
                        SettingsScreen(
                            app = app,
                            preferences = app.preferences,
                            permissionsManager = app.permissionsManager,
                            onNavigateToAiSettings = { currentScreen = SakhiScreen.AI_SETTINGS },
                            onNavigateToDiagnostics = { currentScreen = SakhiScreen.DIAGNOSTICS }
                        )
                    }

                    SakhiScreen.DIAGNOSTICS -> {
                        DiagnosticsScreen(
                            app = app,
                            onNavigateBack = { currentScreen = SakhiScreen.SETTINGS }
                        )
                    }

                    SakhiScreen.HISTORY -> {
                        HistoryScreen(
                            sessionHistoryRepository = app.sessionHistoryRepository,
                            contactRepository = app.contactRepository,
                            alertDispatcher = app.alertDispatcher
                        )
                    }

                    SakhiScreen.DEMO_LAB -> {
                        DemoLabScreen(
                            emergencyStateManager = app.emergencyStateManager,
                            simulatedBleManager = app.simulatedBleManager,
                            assessmentEngine = app.assessmentEngine,
                            onNavigateBack = { currentScreen = SakhiScreen.HOME }
                        )
                    }

                    SakhiScreen.AI_SETTINGS -> {
                        AiProviderSettingsScreen(
                            keystoreStorage = app.keystoreStorage,
                            geminiAssessmentEngine = app.geminiAssessmentEngine,
                            onKeyUpdated = { app.refreshAssessmentEngine() },
                            onNavigateBack = { currentScreen = SakhiScreen.SETTINGS }
                        )
                    }

                    SakhiScreen.ACTIVE_EMERGENCY -> {
                        ActiveEmergencyScreen(
                            emergencyStateManager = app.emergencyStateManager,
                            contactRepository = app.contactRepository,
                            onNavigateBack = { currentScreen = SakhiScreen.HOME }
                        )
                    }
                }
            }
        }
    }
}
