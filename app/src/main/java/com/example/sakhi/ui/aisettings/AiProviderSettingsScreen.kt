package com.example.sakhi.ui.aisettings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.sakhi.core.designsystem.SakhiButton
import com.example.sakhi.core.designsystem.SakhiOutlinedButton
import com.example.sakhi.core.designsystem.StatusPill
import com.example.sakhi.core.security.KeystoreSecretStorage
import com.example.sakhi.data.ai.AssessmentEngine
import com.example.sakhi.data.ai.GeminiAiAssessmentEngine
import com.example.sakhi.theme.SakhiEmergency
import com.example.sakhi.theme.SakhiPrimary
import com.example.sakhi.theme.SakhiSuccess
import com.example.sakhi.theme.SakhiWarning
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AiProviderSettingsScreen(
    keystoreStorage: KeystoreSecretStorage,
    geminiAssessmentEngine: GeminiAiAssessmentEngine,
    onKeyUpdated: () -> Unit,
    onNavigateBack: () -> Unit
) {
    val scope = rememberCoroutineScope()
    var existingKey by remember { mutableStateOf(keystoreStorage.getSecret(GeminiAiAssessmentEngine.KEY_API_KEY) ?: "") }
    var inputKey by remember { mutableStateOf(existingKey) }
    var isKeyVisible by remember { mutableStateOf(false) }

    var testStatus by remember { mutableStateOf<String?>(null) }
    var isTesting by remember { mutableStateOf(false) }
    var testSuccess by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("AI Provider Configuration", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(imageVector = Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface)
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
            // Security Architecture Note
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = SakhiPrimary.copy(alpha = 0.08f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.Top) {
                    Icon(imageVector = Icons.Default.Lock, contentDescription = null, tint = SakhiPrimary)
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Text(text = "Android KeyStore Hardware Encryption", fontWeight = FontWeight.Bold)
                        Text(
                            text = "API keys are encrypted using hardware-backed AES-256 GCM in AndroidKeyStore. Never exposed in logs, crash dumps, or BuildConfig.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            // Configuration Form Card
            Card(
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(20.dp)) {
                    Text(text = "Active Provider: Google Gemini 2.5 Flash", fontWeight = FontWeight.Bold)
                    Text(
                        text = "Used during active sessions for contextual audio distress verification.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(16.dp))

                    OutlinedTextField(
                        value = inputKey,
                        onValueChange = { inputKey = it },
                        label = { Text("Gemini API Key") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        visualTransformation = if (isKeyVisible) VisualTransformation.None else PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        trailingIcon = {
                            IconButton(onClick = { isKeyVisible = !isKeyVisible }) {
                                Icon(
                                    imageVector = if (isKeyVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                    contentDescription = if (isKeyVisible) "Hide key" else "Show key"
                                )
                            }
                        }
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        SakhiButton(
                            text = "Save Key",
                            onClick = {
                                keystoreStorage.storeSecret(GeminiAiAssessmentEngine.KEY_API_KEY, inputKey.trim())
                                existingKey = inputKey.trim()
                                testStatus = "Key securely stored in AndroidKeyStore."
                                testSuccess = true
                                onKeyUpdated()
                            },
                            modifier = Modifier.weight(1f),
                            height = 48.dp
                        )

                        if (existingKey.isNotBlank()) {
                            SakhiOutlinedButton(
                                text = "Delete Key",
                                onClick = {
                                    keystoreStorage.removeSecret(GeminiAiAssessmentEngine.KEY_API_KEY)
                                    existingKey = ""
                                    inputKey = ""
                                    testStatus = "API key removed from KeyStore."
                                    testSuccess = false
                                    onKeyUpdated()
                                },
                                borderColor = SakhiEmergency,
                                contentColor = SakhiEmergency,
                                modifier = Modifier.weight(1f),
                                height = 48.dp
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    // Test Connection Button
                    SakhiOutlinedButton(
                        text = if (isTesting) "Testing Connection..." else "Test Connection",
                        onClick = {
                            isTesting = true
                            testStatus = null
                            scope.launch {
                                val result = geminiAssessmentEngine.testConnection()
                                isTesting = false
                                if (result.isSuccess) {
                                    testSuccess = true
                                    testStatus = "✓ Success: ${result.getOrNull()}"
                                } else {
                                    testSuccess = false
                                    testStatus = "✗ Failed: ${result.exceptionOrNull()?.message}"
                                }
                            }
                        },
                        height = 48.dp
                    )

                    testStatus?.let { status ->
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = status,
                            color = if (testSuccess) SakhiSuccess else SakhiEmergency,
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}
