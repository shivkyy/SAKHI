package com.example.sakhi.ui.settings

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CrisisAlert
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
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
import com.example.sakhi.SakhiApplication
import com.example.sakhi.core.designsystem.SakhiButton
import com.example.sakhi.core.designsystem.SakhiOutlinedButton
import com.example.sakhi.core.designsystem.StatusPill
import com.example.sakhi.core.permissions.PermissionsManager
import com.example.sakhi.core.preferences.SakhiPreferences
import com.example.sakhi.theme.SakhiEmergency
import com.example.sakhi.theme.SakhiLavenderAccent
import com.example.sakhi.theme.SakhiPrimary
import com.example.sakhi.theme.SakhiSuccess
import com.example.sakhi.theme.SakhiWarning
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(
    app: SakhiApplication,
    preferences: SakhiPreferences,
    permissionsManager: PermissionsManager,
    onNavigateToAiSettings: () -> Unit,
    onNavigateToDiagnostics: () -> Unit = {}
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val countdown by preferences.countdownSecondsFlow.collectAsState(initial = 5)
    val messageTemplate by preferences.messageTemplateFlow.collectAsState(initial = SakhiPreferences.DEFAULT_MESSAGE_TEMPLATE)
    val includeAccuracy by preferences.includeLocationAccuracyFlow.collectAsState(initial = true)
    val aiEscalation by preferences.aiEscalationEnabledFlow.collectAsState(initial = true)
    val sensitivity by preferences.escalationSensitivityFlow.collectAsState(initial = "BALANCED")
    val policeNumber by preferences.policeNumberFlow.collectAsState(initial = "112")
    val liveTrackingEnabled by preferences.liveTrackingEnabledFlow.collectAsState(initial = true)
    val liveTrackingUrl by preferences.liveTrackingUrlFlow.collectAsState(initial = SakhiPreferences.DEFAULT_LIVE_TRACKING_URL)
    val periodicSmsEnabled by preferences.periodicSmsEnabledFlow.collectAsState(initial = true)
    val periodicSmsInterval by preferences.periodicSmsIntervalFlow.collectAsState(initial = SakhiPreferences.DEFAULT_PERIODIC_SMS_INTERVAL)

    // Emergency Call Escalation & Speech Intelligence preferences
    val autoCallEscalationEnabled by preferences.autoCallEscalationEnabledFlow.collectAsState(initial = false)
    val escalationPhoneNumber by preferences.escalationPhoneNumberFlow.collectAsState(initial = "")
    val escalationPolicy by preferences.escalationPolicyFlow.collectAsState(initial = SakhiPreferences.DEFAULT_ESCALATION_POLICY)
    val emergencySpeechMonitoring by preferences.emergencySpeechMonitoringFlow.collectAsState(initial = true)
    val includeSpeechInSms by preferences.includeSpeechInSmsFlow.collectAsState(initial = false)

    var editingTemplate by remember(messageTemplate) { mutableStateOf(messageTemplate) }
    var editingPoliceNumber by remember(policeNumber) { mutableStateOf(policeNumber) }
    var editingEscalationNumber by remember(escalationPhoneNumber) { mutableStateOf(escalationPhoneNumber) }
    var editingLiveUrl by remember(liveTrackingUrl) { mutableStateOf(liveTrackingUrl) }
    var connectionTestStatus by remember { mutableStateOf<String?>(null) }
    var isTestingConnection by remember { mutableStateOf(false) }
    var intervalDropdownExpanded by remember { mutableStateOf(false) }

    var isSimulatedMode by remember { mutableStateOf(app.isUsingSimulatedBle()) }
    var permissionStatus by remember { mutableStateOf(permissionsManager.checkPermissions()) }
    val callPermissionLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
    ) {
        permissionStatus = permissionsManager.checkPermissions()
    }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                permissionStatus = permissionsManager.checkPermissions()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 16.dp)
    ) {
        // Header
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = stringResource(R.string.settings_title),
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Black,
                    color = MaterialTheme.colorScheme.onBackground
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = "Emergency protocols, hardware modes & privacy",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Box(
                modifier = Modifier
                    .size(42.dp)
                    .clip(CircleShape)
                    .background(SakhiPrimary.copy(alpha = 0.16f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Tune,
                    contentDescription = null,
                    tint = SakhiPrimary,
                    modifier = Modifier.size(24.dp)
                )
            }
        }

        Spacer(modifier = Modifier.height(18.dp))

        // Section 1: Keychain Hardware Mode
        SettingsSectionCard(
            title = "KEYCHAIN HARDWARE MODE",
            icon = Icons.Default.Key
        ) {
            Text(
                text = "Choose between physical ESP32-S3 BLE connection or software demo simulation.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(12.dp))

            // Real Hardware Option
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        isSimulatedMode = false
                        app.switchToRealBle()
                        Toast.makeText(context, "Switched to Physical BLE Mode", Toast.LENGTH_SHORT).show()
                    },
                verticalAlignment = Alignment.CenterVertically
            ) {
                RadioButton(
                    selected = !isSimulatedMode,
                    onClick = {
                        isSimulatedMode = false
                        app.switchToRealBle()
                        Toast.makeText(context, "Switched to Physical BLE Mode", Toast.LENGTH_SHORT).show()
                    },
                    colors = RadioButtonDefaults.colors(selectedColor = SakhiPrimary)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Column {
                    Text(
                        text = "Physical BLE Keychain (Default)",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "Connects to physical ESP32-S3 keychain via Bluetooth GATT.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Demo Simulation Option
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        isSimulatedMode = true
                        app.switchToSimulatedBle()
                        Toast.makeText(context, "Switched to Demo Simulation Mode", Toast.LENGTH_SHORT).show()
                    },
                verticalAlignment = Alignment.CenterVertically
            ) {
                RadioButton(
                    selected = isSimulatedMode,
                    onClick = {
                        isSimulatedMode = true
                        app.switchToSimulatedBle()
                        Toast.makeText(context, "Switched to Demo Simulation Mode", Toast.LENGTH_SHORT).show()
                    },
                    colors = RadioButtonDefaults.colors(selectedColor = SakhiPrimary)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Column {
                    Text(
                        text = "Demo Mode Simulation",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "Simulates button gestures and telemetry for lab verification without hardware.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Android 13+ Restricted Settings Warning (for Sideloaded APK)
        if (!permissionStatus.hasSms && permissionsManager.isSmsRestrictedByAndroid()) {
            Card(
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = SakhiEmergency.copy(alpha = 0.12f)),
                border = CardDefaults.outlinedCardBorder().copy(brush = SolidColor(SakhiEmergency.copy(alpha = 0.5f))),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(18.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Lock,
                            contentDescription = null,
                            tint = SakhiEmergency,
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = "SMS RESTRICTED BY ANDROID",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Black,
                            color = SakhiEmergency,
                            letterSpacing = 1.sp
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Android 13+ restricts SMS permissions for sideloaded APKs by default. To unlock automatic direct SMS dispatch:",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(text = "1. Tap 'Open App Settings' below.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(text = "2. Tap the three dots (⋮) in the top-right corner.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(text = "3. Tap 'Allow restricted settings' and confirm PIN / Fingerprint.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(text = "4. Return to Permissions > SMS > Allow.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        text = "🛡️ Fallback active: If direct SMS is denied, SAKHI automatically hands off your emergency SOS alert to your messaging app or WhatsApp without failure.",
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.SemiBold,
                        color = SakhiLavenderAccent
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    SakhiButton(
                        text = "Open App Settings",
                        onClick = { PermissionsManager.openAppDetailsSettings(context) },
                        height = 44.dp,
                        containerColor = SakhiEmergency
                    )
                }
            }
            Spacer(modifier = Modifier.height(16.dp))
        }

        // Section: App Theme & Appearance
        SettingsSectionCard(
            title = "APP THEME & APPEARANCE",
            icon = Icons.Default.Palette
        ) {
            val appTheme by preferences.themeFlow.collectAsState(initial = "SYSTEM")
            val themeOptions = listOf(
                Triple("SYSTEM", "Follow System", "Adapts dynamically to Android Dark/Light system setting"),
                Triple("DARK", "Midnight Indigo", "Deep midnight background (#0A0D1B) with refined violet accents"),
                Triple("LIGHT", "Refined Violet", "High contrast daytime palette with violet primary controls")
            )

            themeOptions.forEach { (key, title, subtitle) ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .clickable {
                            scope.launch { preferences.setTheme(key) }
                        }
                        .padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RadioButton(
                        selected = appTheme == key,
                        onClick = { scope.launch { preferences.setTheme(key) } },
                        colors = RadioButtonDefaults.colors(selectedColor = SakhiPrimary)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Column {
                        Text(
                            text = title,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = subtitle,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Section 2: Alert Timing & Message
        SettingsSectionCard(
            title = "EMERGENCY ALERT CONFIGURATION",
            icon = Icons.Default.CrisisAlert
        ) {
            Text(
                text = "Cancellation Countdown Duration",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = "Seconds you have to tap & hold the keychain to cancel a false alarm before SMS dispatch.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(10.dp))

            // Equal-width chips preventing vertical wrapping on narrow screens
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                listOf(3, 5, 10).forEach { seconds ->
                    val isSelected = countdown == seconds
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(44.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(if (isSelected) SakhiPrimary else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                            .border(
                                width = 1.dp,
                                color = if (isSelected) SakhiPrimary else MaterialTheme.colorScheme.outline.copy(alpha = 0.5f),
                                shape = RoundedCornerShape(12.dp)
                            )
                            .clickable {
                                scope.launch { preferences.setCountdownSeconds(seconds) }
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "${seconds}s",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                            color = if (isSelected) Color.White else MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f))
            Spacer(modifier = Modifier.height(14.dp))

            Text(
                text = "Emergency Message Template",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = "Core dispatch template with dynamic safety tags: {time}, {location}, {accuracy}.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedTextField(
                value = editingTemplate,
                onValueChange = { editingTemplate = it },
                modifier = Modifier.fillMaxWidth(),
                minLines = 3,
                supportingText = {
                    Text(
                        text = "Tags: {time} = timestamp, {location} = maps link, {accuracy} = GPS accuracy",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            )

            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                SakhiOutlinedButton(
                    text = "Save Template",
                    onClick = {
                        scope.launch {
                            preferences.setMessageTemplate(editingTemplate)
                            Toast.makeText(context, "Emergency template saved", Toast.LENGTH_SHORT).show()
                        }
                    },
                    modifier = Modifier.weight(1f),
                    height = 42.dp
                )
                SakhiOutlinedButton(
                    text = "Reset Default",
                    onClick = {
                        editingTemplate = SakhiPreferences.DEFAULT_MESSAGE_TEMPLATE
                        scope.launch {
                            preferences.setMessageTemplate(SakhiPreferences.DEFAULT_MESSAGE_TEMPLATE)
                            Toast.makeText(context, "Reset to default template", Toast.LENGTH_SHORT).show()
                        }
                    },
                    modifier = Modifier.weight(1f),
                    height = 42.dp
                )
            }

            Spacer(modifier = Modifier.height(14.dp))

            // Live SMS Preview Card
            Card(
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text(
                        text = "LIVE DISPATCH PREVIEW",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = SakhiPrimary
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    val sampleTime = SimpleDateFormat("HH:mm:ss dd MMM", Locale.getDefault()).format(Date())
                    val sampleLoc = "https://maps.google.com/?q=28.6139,77.2090"
                    val sampleAcc = if (includeAccuracy) "15m" else "N/A"
                    val previewText = editingTemplate
                        .replace("{time}", sampleTime)
                        .replace("{location}", sampleLoc)
                        .replace("{accuracy}", sampleAcc)
                    Text(
                        text = previewText,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }

            Spacer(modifier = Modifier.height(14.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(text = "Include GPS Accuracy Metric", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                    Text(text = "Appends 'Accuracy: ±X meters' to dispatched SMS.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(
                    checked = includeAccuracy,
                    onCheckedChange = { scope.launch { preferences.setIncludeLocationAccuracy(it) } },
                    colors = SwitchDefaults.colors(checkedTrackColor = SakhiPrimary)
                )
            }

            Spacer(modifier = Modifier.height(16.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f))
            Spacer(modifier = Modifier.height(14.dp))

            Text(
                text = "Emergency Authority Helpline",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = "Helpline number dialed when police escalation is triggered (Default: 112).",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = editingPoliceNumber,
                    onValueChange = { editingPoliceNumber = it },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    label = { Text("Helpline") }
                )
                SakhiOutlinedButton(
                    text = "Save",
                    onClick = {
                        if (editingPoliceNumber.isNotBlank()) {
                            scope.launch {
                                preferences.setPoliceNumber(editingPoliceNumber.trim())
                                Toast.makeText(context, "Emergency helpline saved: ${editingPoliceNumber.trim()}", Toast.LENGTH_SHORT).show()
                            }
                        }
                    },
                    modifier = Modifier.width(90.dp),
                    height = 54.dp
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Section: Live Web Tracking Gateway
        SettingsSectionCard(
            title = "LIVE WEB TRACKING GATEWAY",
            icon = Icons.Default.CrisisAlert
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(text = "Enable Live Web Tracking", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                    Text(
                        text = "Generates secure tracking links in emergency SMS. Trusted contacts view real-time OpenStreetMap breadcrumbs.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = liveTrackingEnabled,
                    onCheckedChange = { scope.launch { preferences.setLiveTrackingEnabled(it) } },
                    colors = SwitchDefaults.colors(checkedTrackColor = SakhiPrimary)
                )
            }

            if (liveTrackingEnabled) {
                Spacer(modifier = Modifier.height(14.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f))
                Spacer(modifier = Modifier.height(14.dp))

                Text(
                    text = "Gateway Server Endpoint URL",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "Local LAN/tunnel (e.g. http://10.0.2.2:3000) or deployed HTTPS service.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(8.dp))

                OutlinedTextField(
                    value = editingLiveUrl,
                    onValueChange = { editingLiveUrl = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text("Server Base URL") }
                )

                Spacer(modifier = Modifier.height(8.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    SakhiOutlinedButton(
                        text = "Save URL",
                        onClick = {
                            if (editingLiveUrl.isNotBlank()) {
                                scope.launch {
                                    preferences.setLiveTrackingUrl(editingLiveUrl.trim())
                                    Toast.makeText(context, "Live Tracking URL saved", Toast.LENGTH_SHORT).show()
                                }
                            }
                        },
                        modifier = Modifier.weight(1f),
                        height = 42.dp
                    )

                    SakhiOutlinedButton(
                        text = if (isTestingConnection) "Testing..." else "Test Connection",
                        onClick = {
                            scope.launch {
                                isTestingConnection = true
                                connectionTestStatus = "Pinging gateway /health..."
                                val res = app.liveTrackingClient.testConnection(editingLiveUrl.trim())
                                res.onSuccess { ok ->
                                    connectionTestStatus = if (ok) "ONLINE: Gateway verified (HTTP 200 OK)" else "OFFLINE: Unexpected response"
                                }.onFailure { e ->
                                    connectionTestStatus = "OFFLINE: ${e.message}"
                                }
                                isTestingConnection = false
                            }
                        },
                        modifier = Modifier.weight(1f),
                        height = 42.dp
                    )
                }

                if (connectionTestStatus != null) {
                    Spacer(modifier = Modifier.height(10.dp))
                    val isSuccess = connectionTestStatus?.startsWith("ONLINE") == true
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (isSuccess) SakhiSuccess.copy(alpha = 0.15f) else SakhiEmergency.copy(alpha = 0.15f))
                            .border(1.dp, if (isSuccess) SakhiSuccess else SakhiEmergency, RoundedCornerShape(8.dp))
                            .padding(10.dp)
                    ) {
                        Text(
                            text = connectionTestStatus ?: "",
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.SemiBold,
                            color = if (isSuccess) SakhiSuccess else SakhiEmergency
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(14.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f))
            Spacer(modifier = Modifier.height(14.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(text = "Periodic Location SMS Updates", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                    Text(
                        text = "Dispatches SMS updates to trusted contacts with updated coordinates during active emergency.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = periodicSmsEnabled,
                    onCheckedChange = { scope.launch { preferences.setPeriodicSmsEnabled(it) } },
                    colors = SwitchDefaults.colors(checkedTrackColor = SakhiPrimary)
                )
            }

            if (periodicSmsEnabled) {
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = "Location-Update Interval",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "Select how frequently SAKHI dispatches location-update SMS messages and syncs coordinates.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(8.dp))

                val intervalOptions = listOf(
                    15 to "15 seconds",
                    30 to "30 seconds",
                    45 to "45 seconds",
                    60 to "60 seconds",
                    120 to "2 minutes",
                    300 to "5 minutes"
                )
                val currentIntervalLabel = intervalOptions.firstOrNull { it.first == periodicSmsInterval }?.second
                    ?: "${periodicSmsInterval} seconds"

                Box(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f))
                            .border(
                                width = 1.dp,
                                color = SakhiPrimary.copy(alpha = 0.6f),
                                shape = RoundedCornerShape(12.dp)
                            )
                            .clickable { intervalDropdownExpanded = true }
                            .padding(horizontal = 16.dp, vertical = 14.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(
                                text = "Selected Update Interval",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = currentIntervalLabel,
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                        Icon(
                            imageVector = Icons.Default.ArrowDropDown,
                            contentDescription = "Select interval",
                            tint = SakhiPrimary
                        )
                    }

                    DropdownMenu(
                        expanded = intervalDropdownExpanded,
                        onDismissRequest = { intervalDropdownExpanded = false },
                        modifier = Modifier.fillMaxWidth(0.85f)
                    ) {
                        intervalOptions.forEach { (secs, label) ->
                            DropdownMenuItem(
                                text = {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = label,
                                            fontWeight = if (periodicSmsInterval == secs) FontWeight.Bold else FontWeight.Normal,
                                            color = if (periodicSmsInterval == secs) SakhiPrimary else MaterialTheme.colorScheme.onSurface
                                        )
                                        if (periodicSmsInterval == secs) {
                                            Icon(
                                                imageVector = Icons.Default.CheckCircle,
                                                contentDescription = null,
                                                tint = SakhiPrimary,
                                                modifier = Modifier.size(18.dp)
                                            )
                                        }
                                    }
                                },
                                onClick = {
                                    intervalDropdownExpanded = false
                                    scope.launch {
                                        preferences.setPeriodicSmsInterval(secs)
                                        app.emergencyStateManager.onPeriodicIntervalChanged()
                                        Toast.makeText(context, "Location update interval set to $label", Toast.LENGTH_SHORT).show()
                                    }
                                }
                            )
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Dedicated Section: Emergency Call Escalation
        SettingsSectionCard(
            title = "EMERGENCY CALL ESCALATION",
            icon = Icons.Default.Call
        ) {
            val normalizedSavedEscalation = preferences.normalizePhoneNumber(escalationPhoneNumber)
            val isEscalationConfigured = normalizedSavedEscalation.filter { it.isDigit() }.length >= 3

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Escalation Status",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    StatusPill(
                        text = if (isEscalationConfigured) "Configured" else "Not Configured",
                        color = if (isEscalationConfigured) SakhiSuccess else SakhiWarning
                    )
                    StatusPill(
                        text = "Dialer Ready",
                        color = SakhiSuccess
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Enable Automatic Call Escalation",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "Automatically opens the system dialer to call your dedicated escalation number (at most once per emergency session).",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = autoCallEscalationEnabled,
                    onCheckedChange = { enabled ->
                        scope.launch { preferences.setAutoCallEscalationEnabled(enabled) }
                        if (enabled && !permissionsManager.checkPermissions().hasCallPhone) {
                            callPermissionLauncher.launch(android.Manifest.permission.CALL_PHONE)
                        }
                    },
                    colors = SwitchDefaults.colors(checkedTrackColor = SakhiPrimary)
                )
            }

            Spacer(modifier = Modifier.height(14.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f))
            Spacer(modifier = Modifier.height(14.dp))

            Text(
                text = "Dedicated Escalation Phone Number",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = "Separate from SMS contacts. Enter one dedicated number (local, international +91..., or emergency helpline) to call when escalation triggers.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(8.dp))

            OutlinedTextField(
                value = editingEscalationNumber,
                onValueChange = { editingEscalationNumber = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text("Escalation Phone Number (e.g. +919876543210 or 112)") }
            )

            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                SakhiOutlinedButton(
                    text = "Save Number",
                    onClick = {
                        val normalized = preferences.normalizePhoneNumber(editingEscalationNumber)
                        val digitCount = normalized.filter { it.isDigit() }.length
                        if (digitCount in 3..15) {
                            editingEscalationNumber = normalized
                            scope.launch {
                                preferences.setEscalationPhoneNumber(normalized)
                                Toast.makeText(context, "Escalation number saved: $normalized", Toast.LENGTH_SHORT).show()
                            }
                            if (!permissionsManager.checkPermissions().hasCallPhone) {
                                callPermissionLauncher.launch(android.Manifest.permission.CALL_PHONE)
                            }
                        } else {
                            Toast.makeText(context, "Please enter a valid phone or helpline number (3-15 digits)", Toast.LENGTH_SHORT).show()
                        }
                    },
                    modifier = Modifier.weight(1f),
                    height = 42.dp
                )
                SakhiOutlinedButton(
                    text = "Clear",
                    onClick = {
                        editingEscalationNumber = ""
                        scope.launch {
                            preferences.setEscalationPhoneNumber("")
                            Toast.makeText(context, "Escalation number cleared", Toast.LENGTH_SHORT).show()
                        }
                    },
                    modifier = Modifier.weight(1f),
                    height = 42.dp
                )
            }

            Spacer(modifier = Modifier.height(14.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f))
            Spacer(modifier = Modifier.height(14.dp))

            Text(
                text = "Escalation Policy",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(6.dp))

            val policyOptions = listOf(
                Triple(
                    "CALL_ON_SOS_START",
                    "Call When SOS Starts",
                    "Initiates escalation call immediately when an emergency SOS session activates alongside SMS alerts."
                ),
                Triple(
                    "CALL_ON_RISK_ESCALATION",
                    "Call When Risk Escalates",
                    "Initiates escalation call when SAKHI Intelligence detects high-risk spoken distress phrases (Hindi/English)."
                )
            )

            policyOptions.forEach { (policyKey, title, desc) ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .clickable {
                            scope.launch { preferences.setEscalationPolicy(policyKey) }
                        }
                        .padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RadioButton(
                        selected = escalationPolicy == policyKey,
                        onClick = { scope.launch { preferences.setEscalationPolicy(policyKey) } },
                        colors = RadioButtonDefaults.colors(selectedColor = SakhiPrimary)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Column {
                        Text(
                            text = title,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = desc,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Note: On Android, escalation launches the phone dialer pre-populated with your configured number (ACTION_DIAL) without requiring restricted background call permissions, and never falsely claims a call connected until initiated.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Section 3: AI & Speech Distress Assessment
        SettingsSectionCard(
            title = "EMERGENCY SPEECH & AI INTELLIGENCE",
            icon = Icons.Default.Psychology
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(text = "Continuous Emergency Speech Analysis", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                    Text(
                        text = "Automatically transcribes and analyzes English, Hindi (हिन्दी), and Hinglish speech during active SOS sessions.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = emergencySpeechMonitoring,
                    onCheckedChange = { scope.launch { preferences.setEmergencySpeechMonitoring(it) } },
                    colors = SwitchDefaults.colors(checkedTrackColor = SakhiPrimary)
                )
            }

            Spacer(modifier = Modifier.height(12.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f))
            Spacer(modifier = Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(text = "Include Relevant Speech in Emergency SMS", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                    Text(
                        text = "When enabled, appends concise, deduplicated distress speech excerpts to periodic location-update SMS messages (never delays initial SOS SMS).",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = includeSpeechInSms,
                    onCheckedChange = { scope.launch { preferences.setIncludeSpeechInSms(it) } },
                    colors = SwitchDefaults.colors(checkedTrackColor = SakhiPrimary)
                )
            }

            Spacer(modifier = Modifier.height(12.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f))
            Spacer(modifier = Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(text = "AI-Assisted Escalation", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                    Text(text = "Evaluates recognized speech for distress patterns using local heuristics and optional Gemini AI.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(
                    checked = aiEscalation,
                    onCheckedChange = { scope.launch { preferences.setAiEscalationEnabled(it) } },
                    colors = SwitchDefaults.colors(checkedTrackColor = SakhiPrimary)
                )
            }

            if (aiEscalation) {
                Spacer(modifier = Modifier.height(14.dp))
                Text(text = "Escalation Sensitivity", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("CONSERVATIVE", "BALANCED", "SENSITIVE").forEach { level ->
                        FilterChip(
                            selected = sensitivity == level,
                            onClick = { scope.launch { preferences.setEscalationSensitivity(level) } },
                            label = { Text(level) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = SakhiPrimary,
                                selectedLabelColor = Color.White
                            )
                        )
                    }
                }
                Spacer(modifier = Modifier.height(14.dp))
                SakhiOutlinedButton(
                    text = "Configure Gemini API Key",
                    onClick = onNavigateToAiSettings,
                    height = 44.dp
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Privacy: Microphone capture runs only during active emergencies or explicit SAKHI Intelligence sessions. Raw audio is never stored. If Gemini Cloud AI is configured, only bounded text snippets (max 300 chars) are analyzed with offline local fallback.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Section 4: Live Permissions
        SettingsSectionCard(
            title = "DEVICE PERMISSIONS STATUS",
            icon = Icons.Default.Security
        ) {
            PermissionStatusRow(name = "GPS Location", isGranted = permissionStatus.hasLocation)
            PermissionStatusRow(name = "SMS Services", isGranted = permissionStatus.hasSms)
            PermissionStatusRow(name = "Bluetooth / Nearby", isGranted = permissionStatus.hasBluetooth)
            PermissionStatusRow(name = "Audio Recording", isGranted = permissionStatus.hasMicrophone)
            PermissionStatusRow(name = "Notifications", isGranted = permissionStatus.hasNotifications)
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Section: System & Audio Diagnostics
        SettingsSectionCard(
            title = "SYSTEM & AUDIO DIAGNOSTICS",
            icon = Icons.Default.Tune
        ) {
            Text(
                text = "Audio & Speech Recognition Diagnostics",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "Inspect Bluetooth SPP & phone microphone audio streams, test offline bilingual Vosk recognition, monitor SpeexDSP noise cancellation, and export diagnostic WAV recordings.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                lineHeight = 18.sp
            )
            Spacer(modifier = Modifier.height(14.dp))
            SakhiButton(
                text = "Open Audio Diagnostics",
                onClick = onNavigateToDiagnostics,
                height = 46.dp
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Section 5: About SAKHI
        SettingsSectionCard(
            title = "ABOUT SAKHI",
            icon = Icons.Default.Info
        ) {
            Text(
                text = "SAKHI — Safety, in your keychain.",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "Version 1.2.0 (Build 36)",
                style = MaterialTheme.typography.bodySmall,
                color = SakhiLavenderAccent
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Designed for seamless pairing with the ESP32-S3 keychain wearable over low-energy Bluetooth. All credentials and encryption keys are stored within the Android Keystore system with hardware-backed encryption.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                lineHeight = 18.sp
            )
        }

        Spacer(modifier = Modifier.height(24.dp))
    }
}

@Composable
fun SettingsSectionCard(
    title: String,
    icon: ImageVector,
    content: @Composable () -> Unit
) {
    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = CardDefaults.outlinedCardBorder().copy(brush = SolidColor(MaterialTheme.colorScheme.outline)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = SakhiPrimary,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = title,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    letterSpacing = 1.2.sp
                )
            }
            Spacer(modifier = Modifier.height(14.dp))
            content()
        }
    }
}

@Composable
fun PermissionStatusRow(name: String, isGranted: Boolean) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text = name, style = MaterialTheme.typography.bodyMedium)
        StatusPill(
            text = if (isGranted) "Granted" else "Missing",
            color = if (isGranted) SakhiSuccess else SakhiWarning
        )
    }
}
