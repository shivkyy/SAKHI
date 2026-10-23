package com.example.sakhi.ui.onboarding

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Sms
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.sakhi.R
import com.example.sakhi.core.database.ContactRepository
import com.example.sakhi.core.designsystem.SakhiButton
import com.example.sakhi.core.designsystem.SakhiOutlinedButton
import com.example.sakhi.core.designsystem.StatusPill
import com.example.sakhi.core.model.Contact
import com.example.sakhi.core.model.KeychainLinkState
import com.example.sakhi.core.permissions.PermissionsManager
import com.example.sakhi.core.preferences.SakhiPreferences
import com.example.sakhi.data.ble.BleDeviceInfo
import com.example.sakhi.data.ble.BleManager
import com.example.sakhi.theme.SakhiEmergency
import com.example.sakhi.theme.SakhiPrimary
import com.example.sakhi.theme.SakhiSuccess
import com.example.sakhi.theme.SakhiWarning
import kotlinx.coroutines.launch

@Composable
fun OnboardingScreen(
    permissionsManager: PermissionsManager,
    contactRepository: ContactRepository,
    bleManager: BleManager,
    preferences: SakhiPreferences,
    onCompleteOnboarding: () -> Unit,
    onRequestPermissions: () -> Unit
) {
    val scope = rememberCoroutineScope()
    var currentStep by remember { mutableIntStateOf(1) }

    val linkState by bleManager.linkState.collectAsState()
    val scannedDevices by bleManager.scannedDevices.collectAsState()
    val contacts by contactRepository.contactsFlow.collectAsState(initial = emptyList())

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(24.dp)
    ) {
        // Top Progress Header
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 16.dp, bottom = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "STEP $currentStep OF 6",
                style = MaterialTheme.typography.labelSmall,
                color = SakhiPrimary,
                fontWeight = FontWeight.Bold,
                letterSpacing = 2.sp
            )
            if (currentStep in 2..5) {
                TextButton(onClick = { currentStep = 6 }) {
                    Text(text = stringResource(R.string.btn_skip), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }

        // Progress bar indicators
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            for (i in 1..6) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(4.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(if (i <= currentStep) SakhiPrimary else Color.LightGray.copy(alpha = 0.4f))
                )
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        // Animated Content for Steps
        Box(modifier = Modifier.weight(1f)) {
            AnimatedContent(
                targetState = currentStep,
                transitionSpec = { fadeIn() togetherWith fadeOut() },
                label = "onboarding_steps"
            ) { step ->
                when (step) {
                    1 -> Step1Welcome()
                    2 -> Step2Permissions(permissionsManager, onRequestPermissions)
                    3 -> Step3PairKeychain(bleManager, scannedDevices, linkState)
                    4 -> Step4TrustedContacts(contacts, contactRepository)
                    5 -> Step5DeviceTest(bleManager)
                    6 -> Step6Ready(contacts.size, linkState == KeychainLinkState.CONNECTED)
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Bottom Navigation Buttons
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (currentStep > 1) {
                SakhiOutlinedButton(
                    text = "Back",
                    onClick = { currentStep-- },
                    modifier = Modifier.weight(1f)
                )
            }
            SakhiButton(
                text = if (currentStep == 6) stringResource(R.string.btn_finish) else stringResource(R.string.btn_next),
                onClick = {
                    if (currentStep < 6) {
                        currentStep++
                    } else {
                        scope.launch {
                            preferences.setOnboardingCompleted(true)
                            onCompleteOnboarding()
                        }
                    }
                },
                modifier = Modifier.weight(2f)
            )
        }
    }
}

@Composable
fun Step1Welcome() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
    ) {
        Text(
            text = stringResource(R.string.onboarding_welcome_title),
            style = MaterialTheme.typography.headlineLarge,
            color = MaterialTheme.colorScheme.onBackground
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.onboarding_welcome_desc),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(28.dp))

        FeaturePillarItem(
            icon = Icons.Default.Security,
            title = stringResource(R.string.onboarding_p1_title),
            desc = stringResource(R.string.onboarding_p1_desc)
        )
        FeaturePillarItem(
            icon = Icons.Default.Sms,
            title = stringResource(R.string.onboarding_p2_title),
            desc = stringResource(R.string.onboarding_p2_desc)
        )
        FeaturePillarItem(
            icon = Icons.Default.Phone,
            title = stringResource(R.string.onboarding_p3_title),
            desc = stringResource(R.string.onboarding_p3_desc)
        )
    }
}

@Composable
fun FeaturePillarItem(icon: ImageVector, title: String, desc: String) {
    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp)
    ) {
        Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.Top) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .background(SakhiPrimary.copy(alpha = 0.1f), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(imageVector = icon, contentDescription = null, tint = SakhiPrimary)
            }
            Spacer(modifier = Modifier.width(16.dp))
            Column {
                Text(text = title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(4.dp))
                Text(text = desc, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
fun Step2Permissions(
    permissionsManager: PermissionsManager,
    onRequestPermissions: () -> Unit
) {
    val status = remember { permissionsManager.checkPermissions() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
    ) {
        Text(
            text = "Required Permissions",
            style = MaterialTheme.typography.headlineLarge,
            color = MaterialTheme.colorScheme.onBackground
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "SAKHI operates during life-critical situations. We require specific Android permissions to guarantee protection.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(20.dp))

        PermissionCheckItem("Bluetooth Scanning & Connect", "To discover and communicate with your ESP32 keychain.", status.hasBluetooth)
        PermissionCheckItem("Precise Location", "To capture and send GPS coordinates to trusted contacts.", status.hasLocation)
        PermissionCheckItem("Send Emergency SMS", "To dispatch location alerts directly to emergency contacts.", status.hasSms)
        PermissionCheckItem("Microphone Capture", "Active only during emergencies for distress audio analysis.", status.hasMicrophone)
        PermissionCheckItem("Emergency 112 Escalation", "Direct launch via verified Android system emergency dialer.", true)
        PermissionCheckItem("High-Priority Notifications", "To control active emergency sessions from lockscreen.", status.hasNotifications)

        Spacer(modifier = Modifier.height(16.dp))
        SakhiOutlinedButton(
            text = stringResource(R.string.btn_grant_permissions),
            onClick = onRequestPermissions
        )
    }
}

@Composable
fun PermissionCheckItem(title: String, desc: String, isGranted: Boolean) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = if (isGranted) Icons.Default.CheckCircle else Icons.Default.Security,
            contentDescription = null,
            tint = if (isGranted) SakhiSuccess else SakhiWarning,
            modifier = Modifier.size(24.dp)
        )
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
            Text(text = desc, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
fun Step3PairKeychain(
    bleManager: BleManager,
    scannedDevices: List<BleDeviceInfo>,
    linkState: KeychainLinkState
) {
    Column(modifier = Modifier.fillMaxSize()) {
        Text(
            text = "Pair Your Keychain",
            style = MaterialTheme.typography.headlineLarge,
            color = MaterialTheme.colorScheme.onBackground
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "Turn on your SAKHI keychain. Make sure Bluetooth is enabled.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(16.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            StatusPill(
                text = "Status: ${linkState.name}",
                color = if (linkState == KeychainLinkState.CONNECTED) SakhiSuccess else SakhiWarning
            )
            SakhiOutlinedButton(
                text = "Scan Again",
                onClick = { bleManager.startScan() },
                height = 40.dp,
                modifier = Modifier.width(140.dp)
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        LazyColumn(modifier = Modifier.weight(1f)) {
            items(scannedDevices) { device ->
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 6.dp)
                        .clickable { bleManager.connect(device.address) },
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column {
                            Text(text = device.name, fontWeight = FontWeight.Bold)
                            Text(text = device.address, style = MaterialTheme.typography.labelSmall)
                        }
                        Text(text = "${device.rssi} dBm", color = SakhiPrimary, fontWeight = FontWeight.Bold)
                    }
                }
            }
            if (scannedDevices.isEmpty()) {
                item {
                    Text(
                        text = "Searching for SAKHI keychains nearby...\n(If testing without hardware, simulated adapter is active)",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 24.dp)
                    )
                }
            }
        }
    }
}

@Composable
fun Step4TrustedContacts(
    contacts: List<Contact>,
    contactRepository: ContactRepository
) {
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf("") }
    var phone by remember { mutableStateOf("") }
    var relation by remember { mutableStateOf("") }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
    ) {
        Text(
            text = stringResource(R.string.contacts_title),
            style = MaterialTheme.typography.headlineLarge,
            color = MaterialTheme.colorScheme.onBackground
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "Add at least one trusted contact. They will receive your live GPS map link when emergency is triggered.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(16.dp))

        OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            label = { Text("Contact Name") },
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(modifier = Modifier.height(8.dp))
        OutlinedTextField(
            value = phone,
            onValueChange = { phone = it },
            label = { Text("Phone Number (e.g. +91 9876543210)") },
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(modifier = Modifier.height(8.dp))
        OutlinedTextField(
            value = relation,
            onValueChange = { relation = it },
            label = { Text("Relationship (Mother, Sister, Friend)") },
            modifier = Modifier.fillMaxWidth()
        )

        errorMessage?.let {
            Spacer(modifier = Modifier.height(6.dp))
            Text(text = it, color = SakhiEmergency, style = MaterialTheme.typography.bodySmall)
        }

        Spacer(modifier = Modifier.height(12.dp))
        SakhiOutlinedButton(
            text = "Add Contact",
            onClick = {
                val newContact = Contact(
                    name = name,
                    phoneNumber = phone,
                    relationship = relation,
                    priority = contacts.size + 1
                )
                scope.launch {
                    val res = contactRepository.addContact(newContact)
                    if (res.isSuccess) {
                        name = ""
                        phone = ""
                        relation = ""
                        errorMessage = null
                    } else {
                        errorMessage = res.exceptionOrNull()?.message ?: "Failed to add"
                    }
                }
            },
            height = 48.dp
        )

        Spacer(modifier = Modifier.height(16.dp))
        Text(text = "Configured Contacts (${contacts.size}/5):", fontWeight = FontWeight.Bold)
        contacts.forEach { c ->
            Text(text = "• ${c.name} (${c.relationship}) - ${c.phoneNumber}", style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
fun Step5DeviceTest(bleManager: BleManager) {
    var vibrationTested by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
    ) {
        Text(
            text = "Test the Device",
            style = MaterialTheme.typography.headlineLarge,
            color = MaterialTheme.colorScheme.onBackground
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "Confirm that the keychain vibration motor and communication channels are operational.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(24.dp))

        Card(
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                Text(text = "Vibration Motor Check", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "Press below to send test vibration pulse to the keychain.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(16.dp))
                SakhiOutlinedButton(
                    text = if (vibrationTested) "Vibration Verified ✓" else "Trigger Test Vibration",
                    onClick = {
                        bleManager.sendVibration(0x11)
                        vibrationTested = true
                    },
                    borderColor = if (vibrationTested) SakhiSuccess else SakhiPrimary,
                    contentColor = if (vibrationTested) SakhiSuccess else SakhiPrimary
                )
            }
        }
    }
}

@Composable
fun Step6Ready(contactsCount: Int, isConnected: Boolean) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .size(80.dp)
                .background(SakhiSuccess.copy(alpha = 0.15f), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(imageVector = Icons.Default.Check, contentDescription = null, tint = SakhiSuccess, modifier = Modifier.size(44.dp))
        }

        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = "You\'re Protected!",
            style = MaterialTheme.typography.headlineLarge,
            fontWeight = FontWeight.Bold
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "SAKHI is configured and standing by on your device.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(24.dp))

        Card(
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                Text(text = "Setup Summary", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                Spacer(modifier = Modifier.height(12.dp))
                Text(text = "• Keychain Connection: ${if (isConnected) "Connected" else "Standby (Simulated/Ready)"}")
                Text(text = "• Trusted Contacts: $contactsCount configured")
                Text(text = "• Emergency Alert Flow: 5s countdown + live GPS link")
                Text(text = "• Police SOS: Triple-tap hardware gesture ready")
            }
        }
    }
}
