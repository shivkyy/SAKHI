package com.example.sakhi.ui.demolab

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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Science
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.sakhi.core.designsystem.SakhiButton
import com.example.sakhi.core.designsystem.SakhiOutlinedButton
import com.example.sakhi.core.designsystem.StatusPill
import com.example.sakhi.core.model.DistressAssessment
import com.example.sakhi.core.model.DistressCategory
import com.example.sakhi.core.model.KeychainLinkState
import com.example.sakhi.data.ai.AssessmentEngine
import com.example.sakhi.data.ble.SimulatedBleManager
import com.example.sakhi.domain.emergency.EmergencyStateManager
import com.example.sakhi.domain.escalation.EscalationDecision
import com.example.sakhi.theme.SakhiEmergency
import com.example.sakhi.theme.SakhiPrimary
import com.example.sakhi.theme.SakhiSuccess
import com.example.sakhi.theme.SakhiWarning
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DemoLabScreen(
    emergencyStateManager: EmergencyStateManager,
    simulatedBleManager: SimulatedBleManager,
    assessmentEngine: AssessmentEngine,
    onNavigateBack: () -> Unit
) {
    val state by emergencyStateManager.state.collectAsState()
    val scope = rememberCoroutineScope()

    var testPhrase by remember { mutableStateOf("Someone is following me. Please help.") }
    var benchAssessment by remember { mutableStateOf<DistressAssessment?>(null) }
    var benchDecision by remember { mutableStateOf<EscalationDecision?>(null) }
    var simulationEventLogs by remember { mutableStateOf(listOf("Demo Lab environment initialized. Safeguards active.")) }

    fun logEvent(msg: String) {
        val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        simulationEventLogs = listOf("[$time] $msg") + simulationEventLogs.take(15)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(imageVector = Icons.Default.Science, contentDescription = null, tint = SakhiWarning)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("SAKHI Demo Lab", fontWeight = FontWeight.Bold)
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(imageVector = Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .padding(paddingValues)
                .verticalScroll(rememberScrollState())
                .padding(20.dp)
        ) {
            // Test Mode Safeguard Warning
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = SakhiWarning.copy(alpha = 0.15f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(imageVector = Icons.Default.Security, contentDescription = null, tint = SakhiWarning)
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Text(text = "SIMULATION TEST ENVIRONMENT", fontWeight = FontWeight.Bold, color = SakhiWarning)
                        Text(
                            text = "Explicit safeguards active. Simulated events trigger real state machines without dispatching live SMS or placing real phone calls.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            // 1: Hardware Gestures Simulation
            Text(text = "1. WEARABLE HARDWARE GESTURES", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = SakhiPrimary, letterSpacing = 1.sp)
            Spacer(modifier = Modifier.height(8.dp))
            Card(
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        SakhiOutlinedButton(
                            text = "DOUBLE_PRESS",
                            onClick = {
                                simulatedBleManager.simulateDoublePress()
                                logEvent("Simulated DOUBLE_PRESS button event (Session Activation)")
                            },
                            modifier = Modifier.weight(1f),
                            height = 46.dp
                        )
                        SakhiOutlinedButton(
                            text = "TAP_HOLD (1.5s)",
                            onClick = {
                                simulatedBleManager.simulateTapHold()
                                logEvent("Simulated TAP_HOLD button event (Session Cancellation)")
                            },
                            modifier = Modifier.weight(1f),
                            height = 46.dp
                        )
                    }
                    Spacer(modifier = Modifier.height(10.dp))
                    SakhiButton(
                        text = "TRIPLE_TAP (POLICE SOS)",
                        onClick = {
                            simulatedBleManager.simulateTripleTap()
                            logEvent("Simulated TRIPLE_TAP button event (Manual Police Request)")
                        },
                        containerColor = SakhiEmergency,
                        contentColor = Color.White,
                        height = 46.dp
                    )
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            // 2: BLE & Telemetry Simulation
            Text(text = "2. BLE LINK & TELEMETRY SIMULATION", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = SakhiPrimary, letterSpacing = 1.sp)
            Spacer(modifier = Modifier.height(8.dp))
            Card(
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        SakhiOutlinedButton(
                            text = "Disconnect BLE",
                            onClick = {
                                simulatedBleManager.disconnect()
                                logEvent("Simulated BLE Link Lost (Testing Amber Banner)")
                            },
                            modifier = Modifier.weight(1f),
                            height = 42.dp
                        )
                        SakhiOutlinedButton(
                            text = "Reconnect BLE",
                            onClick = {
                                simulatedBleManager.connect("AA:BB:CC:DD:EE:01")
                                logEvent("Simulated BLE Reconnection")
                            },
                            modifier = Modifier.weight(1f),
                            height = 42.dp
                        )
                    }
                    Spacer(modifier = Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        SakhiOutlinedButton(
                            text = "Low Battery (12%)",
                            onClick = {
                                simulatedBleManager.simulateBatteryStatus(12)
                                logEvent("Simulated Low Battery Alert: 12%")
                            },
                            modifier = Modifier.weight(1f),
                            height = 42.dp
                        )
                        SakhiOutlinedButton(
                            text = "Normal Battery (88%)",
                            onClick = {
                                simulatedBleManager.simulateBatteryStatus(88)
                                logEvent("Simulated Normal Battery: 88%")
                            },
                            modifier = Modifier.weight(1f),
                            height = 42.dp
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            // 3: AI Test Bench
            Text(text = "3. AI DISTRESS ASSESSMENT TEST BENCH", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = SakhiPrimary, letterSpacing = 1.sp)
            Spacer(modifier = Modifier.height(8.dp))
            Card(
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(text = "Quick Test Utterances:", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodySmall)
                    Spacer(modifier = Modifier.height(6.dp))
                    val samplePhrases = listOf(
                        "Bachao!",
                        "Madad karo!",
                        "Someone is following me. Please help.",
                        "Can you help me carry this bag?",
                        "Can you help me with my homework?"
                    )
                    samplePhrases.forEach { sample ->
                        Text(
                            text = "• \"$sample\"",
                            color = SakhiPrimary,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier
                                .padding(vertical = 2.dp)
                                .clickable { testPhrase = sample }
                        )
                    }

                    Spacer(modifier = Modifier.height(12.dp))
                    OutlinedTextField(
                        value = testPhrase,
                        onValueChange = { testPhrase = it },
                        label = { Text("Utterance to Evaluate") },
                        modifier = Modifier.fillMaxWidth()
                    )

                    Spacer(modifier = Modifier.height(12.dp))
                    SakhiButton(
                        text = "Run AI Assessment Benchmark",
                        onClick = {
                            scope.launch {
                                val assessment = assessmentEngine.assessText(testPhrase)
                                val decision = emergencyStateManager.escalationPolicy.evaluateAiAssessment(
                                    state = state,
                                    assessment = assessment,
                                    aiEscalationEnabled = true,
                                    sensitivity = "BALANCED"
                                )
                                benchAssessment = assessment
                                benchDecision = decision
                                logEvent("AI Evaluated: [${assessment.category}] Conf: ${assessment.confidence}. Police Request: ${decision.shouldRequestPolice}")
                            }
                        },
                        height = 46.dp
                    )

                    benchAssessment?.let { assess ->
                        Spacer(modifier = Modifier.height(14.dp))
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(SakhiPrimary.copy(alpha = 0.08f))
                                .padding(12.dp)
                        ) {
                            Column {
                                Text(text = "Category: ${assess.category.name}", fontWeight = FontWeight.Bold, color = if (assess.category == DistressCategory.POSSIBLE_DISTRESS) SakhiEmergency else SakhiSuccess)
                                Text(text = "Confidence: ${assess.confidence?.let { (it * 100).toInt() } ?: "N/A"}%")
                                Text(text = "Summary: ${assess.contextSummary ?: "N/A"}", style = MaterialTheme.typography.bodySmall)
                                benchDecision?.let { dec ->
                                    Spacer(modifier = Modifier.height(6.dp))
                                    Text(
                                        text = "Escalation Policy Decision: ${if (dec.shouldRequestPolice) "POLICE REQUEST CREATED" else "HOLD (CRITERIA NOT MET)"}",
                                        fontWeight = FontWeight.Bold,
                                        color = if (dec.shouldRequestPolice) SakhiEmergency else SakhiWarning
                                    )
                                    Text(text = "Reason: ${dec.reason}", style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            // 4: Live Event Simulator Log
            Text(text = "4. LIVE DEMO EVENT LOG", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = SakhiPrimary, letterSpacing = 1.sp)
            Spacer(modifier = Modifier.height(8.dp))
            Card(
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    simulationEventLogs.forEach { logLine ->
                        Text(
                            text = logLine,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = 2.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}
