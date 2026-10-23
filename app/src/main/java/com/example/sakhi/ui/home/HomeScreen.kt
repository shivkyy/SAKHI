package com.example.sakhi.ui.home

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BatteryAlert
import androidx.compose.material.icons.filled.BatteryFull
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.CrisisAlert
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Sms
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.sakhi.R
import com.example.sakhi.core.database.ContactRepository
import com.example.sakhi.core.database.SessionHistoryRepository
import com.example.sakhi.core.designsystem.DoublePressToActivateSosButton
import com.example.sakhi.core.designsystem.QuickActionCard
import com.example.sakhi.core.designsystem.SakhiButton
import com.example.sakhi.core.designsystem.SakhiOutlinedButton
import com.example.sakhi.core.designsystem.StatusPill
import com.example.sakhi.core.designsystem.TruthfulStatusBadge
import com.example.sakhi.core.model.Contact
import com.example.sakhi.core.model.ContactAlertState
import com.example.sakhi.core.model.EmergencySession
import com.example.sakhi.core.model.EmergencyState
import com.example.sakhi.core.model.KeychainLinkState
import com.example.sakhi.core.model.SessionState
import com.example.sakhi.core.permissions.PermissionStatus
import com.example.sakhi.core.permissions.PermissionsManager
import com.example.sakhi.data.location.SakhiLocation
import com.example.sakhi.domain.emergency.EmergencyStateManager
import com.example.sakhi.theme.SakhiEmergency
import com.example.sakhi.theme.SakhiLavenderAccent
import com.example.sakhi.theme.SakhiPrimary
import com.example.sakhi.theme.SakhiSuccess
import com.example.sakhi.theme.SakhiWarning
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun HomeScreen(
    emergencyStateManager: EmergencyStateManager,
    permissionsManager: PermissionsManager,
    contactRepository: ContactRepository,
    sessionHistoryRepository: SessionHistoryRepository,
    onNavigateToDemoLab: () -> Unit,
    onNavigateToAiSettings: () -> Unit,
    onNavigateToEmergency: () -> Unit,
    onNavigateToContacts: () -> Unit = {},
    onNavigateToDevice: () -> Unit = {}
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val state by emergencyStateManager.state.collectAsState()
    val contacts by contactRepository.contactsFlow.collectAsState(initial = emptyList())
    val pastSessions by sessionHistoryRepository.sessionsFlow.collectAsState(initial = emptyList())

    androidx.compose.runtime.LaunchedEffect(Unit) {
        contactRepository.refreshContacts()
        sessionHistoryRepository.refreshSessions()
    }

    var permissionStatus by remember { mutableStateOf(permissionsManager.checkPermissions()) }
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    androidx.compose.runtime.DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                permissionStatus = permissionsManager.checkPermissions()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    var showDevMenu by remember { mutableStateOf(false) }
    var showLocationShareDialog by remember { mutableStateOf(false) }
    var showPoliceCallDialog by remember { mutableStateOf(false) }
    var sharedLocationData by remember { mutableStateOf<SakhiLocation?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 16.dp)
    ) {
        // Top App Bar with Long-Pressable Logo
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 18.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.combinedClickable(
                        onClick = {},
                        onLongClick = { showDevMenu = true }
                    )
                ) {
                    Box(
                        modifier = Modifier
                            .size(44.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .background(SakhiPrimary.copy(alpha = 0.2f))
                            .border(1.dp, SakhiPrimary.copy(alpha = 0.4f), RoundedCornerShape(14.dp)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Shield,
                            contentDescription = "SAKHI Logo (Long-press for Developer Menu)",
                            tint = SakhiPrimary,
                            modifier = Modifier.size(26.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Text(
                            text = "SAKHI",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Black,
                            letterSpacing = 2.sp,
                            color = MaterialTheme.colorScheme.onBackground
                        )
                        Text(
                            text = stringResource(R.string.app_tagline),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                DropdownMenu(
                    expanded = showDevMenu,
                    onDismissRequest = { showDevMenu = false }
                ) {
                    DropdownMenuItem(
                        text = { Text("🛠️ Open Demo Lab") },
                        onClick = {
                            showDevMenu = false
                            onNavigateToDemoLab()
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("🔑 AI Provider Settings") },
                        onClick = {
                            showDevMenu = false
                            onNavigateToAiSettings()
                        }
                    )
                }
            }

            // Truthful Hardware Status Badge
            TruthfulStatusBadge(
                linkState = state.keychainLinkState,
                isSimulated = state.isSimulatedDevice
            )
        }

        // Hero Status Card (Truthful State Display)
        HeroSafetyStatusCard(
            state = state,
            contactsCount = contacts.size,
            onEmergencyClick = onNavigateToEmergency,
            onSetupContactsClick = onNavigateToContacts
        )

        Spacer(modifier = Modifier.height(18.dp))

        // Phone-Based Standalone SOS Button (Double-Press with 1000ms confirmation window)
        DoublePressToActivateSosButton(
            isEmergencyActive = state.isEmergencyActive,
            onActivated = {
                emergencyStateManager.triggerManualSosBackup()
                onNavigateToEmergency()
            }
        )

        if (!permissionStatus.hasSms && permissionsManager.isSmsRestrictedByAndroid()) {
            Spacer(modifier = Modifier.height(14.dp))
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = SakhiEmergency.copy(alpha = 0.12f)),
                border = CardDefaults.outlinedCardBorder().copy(brush = SolidColor(SakhiEmergency.copy(alpha = 0.4f))),
                modifier = Modifier.fillMaxWidth().clickable {
                    PermissionsManager.openAppDetailsSettings(context)
                }
            ) {
                Row(
                    modifier = Modifier.padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Warning,
                        contentDescription = null,
                        tint = SakhiEmergency,
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "SMS Permission Restricted by Android",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = SakhiEmergency
                        )
                        Text(
                            text = "Tap to open App Info > ⋮ > Allow restricted settings.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    TextButton(onClick = { PermissionsManager.openAppDetailsSettings(context) }) {
                        Text("Fix", fontWeight = FontWeight.Bold, color = SakhiEmergency)
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(22.dp))

        // Quick Emergency Actions Section
        Text(
            text = "QUICK EMERGENCY ACTIONS",
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            letterSpacing = 1.2.sp
        )
        Spacer(modifier = Modifier.height(10.dp))

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            QuickActionCard(
                icon = Icons.Default.LocationOn,
                title = "Share Location",
                subtitle = "Send GPS link",
                accentColor = SakhiLavenderAccent,
                onClick = {
                    scope.launch {
                        val loc = emergencyStateManager.locationProvider.getBestLocation()
                        sharedLocationData = loc
                        showLocationShareDialog = true
                    }
                },
                modifier = Modifier.weight(1f)
            )

            QuickActionCard(
                icon = Icons.Default.Phone,
                title = "Call 112",
                subtitle = "Emergency dialer",
                accentColor = SakhiEmergency,
                onClick = {
                    showPoliceCallDialog = true
                },
                modifier = Modifier.weight(1f)
            )
        }

        Spacer(modifier = Modifier.height(20.dp))

        // Truthful Device Summary Card
        DeviceSummaryCard(
            state = state,
            onReconnect = {
                emergencyStateManager.reconnectDevice()
            },
            onOpenDeviceScreen = onNavigateToDevice
        )

        Spacer(modifier = Modifier.height(20.dp))

        // Readiness Checklist
        ReadinessChecklistCard(
            state = state,
            contactsCount = contacts.size,
            permissions = permissionStatus,
            onManageContacts = onNavigateToContacts
        )

        Spacer(modifier = Modifier.height(20.dp))

        // Gesture Guide Card
        GestureGuideCard()

        Spacer(modifier = Modifier.height(20.dp))

        // Last Session Summary Card (if available)
        if (pastSessions.isNotEmpty()) {
            val lastSession = pastSessions.first()
            LastSessionSummaryCard(lastSession)
            Spacer(modifier = Modifier.height(20.dp))
        }
    }

    // Location Share Dialog
    if (showLocationShareDialog) {
        val loc = sharedLocationData
        val mapsUrl = if (loc != null) "https://maps.google.com/?q=${loc.latitude},${loc.longitude}" else null

        AlertDialog(
            onDismissRequest = { showLocationShareDialog = false },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(imageVector = Icons.Default.LocationOn, contentDescription = null, tint = SakhiLavenderAccent)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Share Live Location", fontWeight = FontWeight.Bold)
                }
            },
            text = {
                Column {
                    if (loc != null) {
                        Text(
                            text = loc.address ?: "Coordinates: ${"%.4f".format(loc.latitude)}, ${"%.4f".format(loc.longitude)}",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "Accuracy: ${loc.accuracy?.toInt() ?: 15}m (GPS Verified)",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        Text(
                            text = "Acquiring current GPS location...",
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showLocationShareDialog = false
                        if (mapsUrl != null) {
                            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(Intent.EXTRA_SUBJECT, "SAKHI Emergency Location")
                                putExtra(Intent.EXTRA_TEXT, "I am sharing my current location: $mapsUrl")
                            }
                            context.startActivity(Intent.createChooser(shareIntent, "Share Location via"))
                        }
                    },
                    enabled = mapsUrl != null
                ) {
                    Text("Share via Apps", fontWeight = FontWeight.Bold, color = SakhiPrimary)
                }
            },
            dismissButton = {
                TextButton(onClick = { showLocationShareDialog = false }) {
                    Text("Close")
                }
            }
        )
    }

    // Call 112 Confirmation Dialog
    if (showPoliceCallDialog) {
        AlertDialog(
            onDismissRequest = { showPoliceCallDialog = false },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(imageVector = Icons.Default.Call, contentDescription = null, tint = SakhiEmergency)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Call 112 Emergency", fontWeight = FontWeight.Bold)
                }
            },
            text = {
                Text(
                    text = "This will open your phone's system phone dialer with 112 (National Emergency Helpline). You can confirm the call from the dialer.",
                    style = MaterialTheme.typography.bodyMedium
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showPoliceCallDialog = false
                        emergencyStateManager.triggerManualPoliceCall()
                    }
                ) {
                    Text("Open Dialer", fontWeight = FontWeight.Bold, color = SakhiEmergency)
                }
            },
            dismissButton = {
                TextButton(onClick = { showPoliceCallDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
fun HeroSafetyStatusCard(
    state: EmergencyState,
    contactsCount: Int,
    onEmergencyClick: () -> Unit,
    onSetupContactsClick: () -> Unit
) {
    val isEmergency = state.isEmergencyActive
    val isConnected = state.keychainLinkState == KeychainLinkState.CONNECTED
    val isSetupComplete = contactsCount > 0

    val (cardColor, title, subtitle, icon) = when {
        isEmergency -> Quadruple(
            SakhiEmergency,
            "EMERGENCY ACTIVE",
            "Emergency dispatch in progress. Tap here to view live session details.",
            Icons.Default.CrisisAlert
        )
        !isSetupComplete -> Quadruple(
            SakhiWarning,
            "SETUP REQUIRED",
            "Add at least 1 trusted contact to enable automatic emergency alerts.",
            Icons.Default.Warning
        )
        !isConnected && !state.isSimulatedDevice -> Quadruple(
            Color(0xFF252B4E),
            "STANDALONE READY",
            "Physical keychain disconnected. Phone-based manual SOS and location sharing are fully armed.",
            Icons.Default.Security
        )
        state.isSimulatedDevice -> Quadruple(
            SakhiPrimary,
            "DEMO LAB MODE",
            "Simulated keychain adapter active. Ready for functional testing.",
            Icons.Default.Shield
        )
        else -> Quadruple(
            SakhiSuccess,
            "SYSTEM READY",
            "Physical keychain linked and trusted contacts armed. Protection active.",
            Icons.Default.CheckCircle
        )
    }

    Card(
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = cardColor),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(22.dp))
            .clickable(enabled = isEmergency || !isSetupComplete) {
                if (isEmergency) onEmergencyClick() else if (!isSetupComplete) onSetupContactsClick()
            }
    ) {
        Column(modifier = Modifier.padding(22.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Black,
                    letterSpacing = 1.sp,
                    color = Color.White
                )
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(28.dp)
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White.copy(alpha = 0.92f)
            )
            if (isEmergency) {
                Spacer(modifier = Modifier.height(14.dp))
                StatusPill(
                    text = "VIEW LIVE DISPATCH",
                    color = Color.White
                )
            } else if (!isSetupComplete) {
                Spacer(modifier = Modifier.height(14.dp))
                StatusPill(
                    text = "TAP TO ADD CONTACTS",
                    color = Color.White
                )
            }
        }
    }
}

private data class Quadruple<A, B, C, D>(val first: A, val second: B, val third: C, val fourth: D)

@Composable
fun DeviceSummaryCard(
    state: EmergencyState,
    onReconnect: () -> Unit,
    onOpenDeviceScreen: () -> Unit
) {
    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = CardDefaults.outlinedCardBorder().copy(brush = SolidColor(MaterialTheme.colorScheme.outline)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(18.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(38.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(SakhiPrimary.copy(alpha = 0.15f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Key,
                            contentDescription = null,
                            tint = SakhiPrimary,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Text(
                            text = if (state.isSimulatedDevice) "SAKHI Keychain (Simulated)" else state.deviceName,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = if (state.isSimulatedDevice) "Demo Adapter" else (state.deviceAddress ?: "Bluetooth Classic SPP"),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                Icon(
                    imageVector = Icons.Default.Bluetooth,
                    contentDescription = null,
                    tint = if (state.keychainLinkState == KeychainLinkState.CONNECTED) SakhiSuccess else SakhiWarning
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Truthful device telemetry for physical SPP audio link
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                val isConnected = state.keychainLinkState == KeychainLinkState.CONNECTED
                InfoTag(
                    label = "Transport",
                    value = if (state.isSimulatedDevice) "Simulated" else "BT Classic SPP"
                )
                InfoTag(
                    label = "Audio Stream",
                    value = if (isConnected) "${state.audioSampleRateHz} Hz Mono" else "Offline"
                )
                InfoTag(
                    label = "Link Status",
                    value = when (state.keychainLinkState) {
                        KeychainLinkState.CONNECTED -> "Connected"
                        KeychainLinkState.CONNECTING -> "Connecting..."
                        KeychainLinkState.RECONNECTING -> "Reconnecting..."
                        KeychainLinkState.LOST -> "Error"
                        else -> "Disconnected"
                    }
                )
            }

            Spacer(modifier = Modifier.height(14.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f))
            Spacer(modifier = Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                if (state.keychainLinkState != KeychainLinkState.CONNECTED) {
                    SakhiOutlinedButton(
                        text = if (state.keychainLinkState == KeychainLinkState.CONNECTING || state.keychainLinkState == KeychainLinkState.RECONNECTING) "Connecting..." else "Connect Audio",
                        onClick = onReconnect,
                        height = 44.dp,
                        modifier = Modifier.weight(1f)
                    )
                }
                SakhiOutlinedButton(
                    text = "Manage Keychain",
                    onClick = onOpenDeviceScreen,
                    height = 44.dp,
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

@Composable
fun InfoTag(label: String, value: String) {
    Column {
        Text(text = label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            color = if (value == "Unavailable") MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface
        )
    }
}

@Composable
fun ReadinessChecklistCard(
    state: EmergencyState,
    contactsCount: Int,
    permissions: PermissionStatus,
    onManageContacts: () -> Unit
) {
    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = CardDefaults.outlinedCardBorder().copy(brush = SolidColor(MaterialTheme.colorScheme.outline)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(18.dp)) {
            Text(
                text = "SYSTEM READINESS",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                letterSpacing = 1.2.sp
            )
            Spacer(modifier = Modifier.height(12.dp))

            ChecklistItem(
                title = "Emergency Contacts",
                isReady = contactsCount > 0,
                statusText = if (contactsCount > 0) "$contactsCount Armed" else "None Added",
                onClick = onManageContacts
            )
            ChecklistItem(
                title = "GPS Location",
                isReady = permissions.hasLocation,
                statusText = if (permissions.hasLocation) "Ready" else "Missing Permission"
            )
            ChecklistItem(
                title = "SMS Dispatch",
                isReady = permissions.hasSms,
                statusText = if (permissions.hasSms) "Ready" else "Missing Permission"
            )
            ChecklistItem(
                title = "Bluetooth Subsystem",
                isReady = permissions.hasBluetooth,
                statusText = if (permissions.hasBluetooth) "Armed" else "Missing Permission"
            )
            ChecklistItem(
                title = "Audio Assessment",
                isReady = permissions.hasMicrophone,
                statusText = if (permissions.hasMicrophone) "Armed" else "Missing Permission"
            )
        }
    }
}

@Composable
fun ChecklistItem(
    title: String,
    isReady: Boolean,
    statusText: String,
    onClick: (() -> Unit)? = null
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = if (isReady) Icons.Default.CheckCircle else Icons.Default.ErrorOutline,
                contentDescription = null,
                tint = if (isReady) SakhiSuccess else SakhiWarning,
                modifier = Modifier.size(18.dp)
            )
            Spacer(modifier = Modifier.width(10.dp))
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
        Text(
            text = statusText,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = if (isReady) SakhiSuccess else SakhiWarning
        )
    }
}

@Composable
fun GestureGuideCard() {
    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = CardDefaults.outlinedCardBorder().copy(brush = SolidColor(MaterialTheme.colorScheme.outline)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(18.dp)) {
            Text(
                text = "PHYSICAL KEYCHAIN GESTURES",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                letterSpacing = 1.2.sp
            )
            Spacer(modifier = Modifier.height(14.dp))

            GestureItem(
                gesture = "Double Press",
                meaning = "Activate SOS Countdown (5s)",
                accentColor = SakhiEmergency
            )
            Spacer(modifier = Modifier.height(10.dp))
            GestureItem(
                gesture = "Tap & Hold (1.5s)",
                meaning = "Cancel SOS Countdown or Session",
                accentColor = SakhiWarning
            )
            Spacer(modifier = Modifier.height(10.dp))
            GestureItem(
                gesture = "Triple Tap",
                meaning = "Active-Session Police Escalation (112)",
                accentColor = SakhiLavenderAccent
            )
        }
    }
}

@Composable
fun GestureItem(
    gesture: String,
    meaning: String,
    accentColor: Color
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .background(accentColor.copy(alpha = 0.16f))
                .border(1.dp, accentColor.copy(alpha = 0.35f), RoundedCornerShape(8.dp))
                .padding(horizontal = 8.dp, vertical = 4.dp)
        ) {
            Text(
                text = gesture,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = accentColor
            )
        }
        Spacer(modifier = Modifier.width(12.dp))
        Text(
            text = meaning,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
fun LastSessionSummaryCard(session: EmergencySession) {
    val dateFormat = SimpleDateFormat("dd MMM, HH:mm", Locale.getDefault())
    val formattedDate = dateFormat.format(Date(session.startTime))

    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = CardDefaults.outlinedCardBorder().copy(brush = SolidColor(MaterialTheme.colorScheme.outline)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(18.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "LAST INCIDENT",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    letterSpacing = 1.2.sp
                )
                Text(
                    text = formattedDate,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Outcome: ${session.outcome}",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold
                )
                StatusPill(
                    text = session.contactAlertState.name,
                    color = if (session.contactAlertState == ContactAlertState.DELIVERED || session.contactAlertState == ContactAlertState.SENT) SakhiSuccess else SakhiWarning
                )
            }
        }
    }
}
