package com.example.sakhi

import android.app.Application
import com.example.sakhi.core.database.AppDatabaseHelper
import com.example.sakhi.core.database.ContactRepository
import com.example.sakhi.core.database.SessionHistoryRepository
import com.example.sakhi.core.permissions.PermissionsManager
import com.example.sakhi.core.preferences.SakhiPreferences
import com.example.sakhi.core.security.KeystoreSecretStorage
import com.example.sakhi.data.ai.AssessmentEngine
import com.example.sakhi.data.ai.GeminiAiAssessmentEngine
import com.example.sakhi.data.ai.LocalDeterministicAssessmentEngine
import com.example.sakhi.data.alerts.AlertDispatcher
import com.example.sakhi.data.alerts.AndroidAlertDispatcher
import com.example.sakhi.data.audio.AndroidAudioPipeline
import com.example.sakhi.data.audio.AudioPipeline
import com.example.sakhi.data.ble.AndroidBleManager
import com.example.sakhi.data.ble.BleManager
import com.example.sakhi.data.ble.SimulatedBleManager
import com.example.sakhi.data.ble.SwitchableBleManager
import com.example.sakhi.data.location.AndroidLocationProvider
import com.example.sakhi.data.location.LocationProvider
import com.example.sakhi.domain.emergency.EmergencyStateManager
import com.example.sakhi.domain.escalation.EscalationPolicy
import com.example.sakhi.domain.escalation.PoliceCallManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class SakhiApplication : Application() {

    val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    lateinit var dbHelper: AppDatabaseHelper
        private set
    lateinit var contactRepository: ContactRepository
        private set
    lateinit var sessionHistoryRepository: SessionHistoryRepository
        private set
    lateinit var preferences: SakhiPreferences
        private set
    lateinit var keystoreStorage: KeystoreSecretStorage
        private set
    lateinit var permissionsManager: PermissionsManager
        private set

    lateinit var locationProvider: LocationProvider
        private set
    lateinit var alertDispatcher: AlertDispatcher
        private set
    lateinit var audioPipeline: AudioPipeline
        private set

    lateinit var simulatedBleManager: SimulatedBleManager
        private set
    lateinit var androidBleManager: AndroidBleManager
        private set
    lateinit var switchableBleManager: SwitchableBleManager
        private set
    val bleManager: BleManager
        get() = switchableBleManager

    lateinit var localAssessmentEngine: LocalDeterministicAssessmentEngine
        private set
    lateinit var geminiAssessmentEngine: GeminiAiAssessmentEngine
        private set
    lateinit var assessmentEngine: AssessmentEngine
        private set

    lateinit var escalationPolicy: EscalationPolicy
        private set
    lateinit var policeCallManager: PoliceCallManager
        private set
    lateinit var liveTrackingClient: com.example.sakhi.data.tracking.LiveTrackingClient
        private set
    lateinit var btAudioReceiver: com.example.sakhi.data.bluetooth.BluetoothClassicAudioReceiver
        private set
    lateinit var emergencyStateManager: EmergencyStateManager
        private set

    lateinit var transcriptionService: com.example.sakhi.data.assistant.SpeechTranscriptionService
        private set
    lateinit var assistantManager: com.example.sakhi.data.assistant.AssistantManager
        private set
    lateinit var voskModelManager: com.example.sakhi.data.assistant.VoskModelManager
        private set
    lateinit var externalTranscriptionService: com.example.sakhi.data.assistant.VoskAudioTranscriptionService
        private set
    lateinit var audioDiagnosticRecorder: com.example.sakhi.data.audio.AudioDiagnosticRecorder
        private set
    lateinit var phoneAudioSource: com.example.sakhi.data.audio.PhoneMicrophoneAudioSource
        private set
    lateinit var unifiedAudioRouter: com.example.sakhi.data.audio.UnifiedAudioRouter
        private set

    override fun onCreate() {
        super.onCreate()

        dbHelper = AppDatabaseHelper(this)
        contactRepository = ContactRepository(dbHelper)
        sessionHistoryRepository = SessionHistoryRepository(dbHelper)

        // Eagerly load contacts and session history from SQLite so flows are populated on startup
        applicationScope.launch(Dispatchers.IO) {
            try {
                contactRepository.refreshContacts()
                sessionHistoryRepository.refreshSessions()
            } catch (_: Exception) {}
        }
        preferences = SakhiPreferences(this)
        keystoreStorage = KeystoreSecretStorage(this)
        permissionsManager = PermissionsManager(this)

        locationProvider = AndroidLocationProvider(this)
        alertDispatcher = AndroidAlertDispatcher(this)
        audioPipeline = AndroidAudioPipeline(this)

        simulatedBleManager = SimulatedBleManager(applicationScope)
        androidBleManager = AndroidBleManager(this, applicationScope)
        // Default to real Android BLE manager, starts disconnected
        switchableBleManager = SwitchableBleManager(
            scope = applicationScope,
            realBleManager = androidBleManager,
            simBleManager = simulatedBleManager
        )

        localAssessmentEngine = LocalDeterministicAssessmentEngine()
        geminiAssessmentEngine = GeminiAiAssessmentEngine(keystoreStorage)
        assessmentEngine = if (geminiAssessmentEngine.isConfigured) geminiAssessmentEngine else localAssessmentEngine

        escalationPolicy = EscalationPolicy()
        policeCallManager = PoliceCallManager(this)
        liveTrackingClient = com.example.sakhi.data.tracking.HttpLiveTrackingClient()
        transcriptionService = com.example.sakhi.data.assistant.AndroidSpeechTranscriptionService(this)
        btAudioReceiver = com.example.sakhi.data.bluetooth.BluetoothClassicAudioReceiver(
            context = this,
            scope = applicationScope
        )

        phoneAudioSource = com.example.sakhi.data.audio.PhoneMicrophoneAudioSource(
            context = this,
            scope = applicationScope
        )

        unifiedAudioRouter = com.example.sakhi.data.audio.UnifiedAudioRouter(
            esp32AudioReceiver = btAudioReceiver,
            phoneAudioSource = phoneAudioSource,
            scope = applicationScope
        )

        voskModelManager = com.example.sakhi.data.assistant.VoskModelManager(this, applicationScope)
        voskModelManager.initialize()

        externalTranscriptionService = com.example.sakhi.data.assistant.VoskAudioTranscriptionService(
            context = this,
            audioRouter = unifiedAudioRouter,
            modelManager = voskModelManager,
            scope = applicationScope,
            preferences = preferences
        )

        // Pre-warm Vosk bilingual models and recognizers in the background so initial speech attempts have zero latency
        applicationScope.launch(Dispatchers.IO) {
            try {
                externalTranscriptionService.prewarmModels()
            } catch (e: Exception) {
                android.util.Log.w("SakhiApplication", "ASR model prewarm deferred: ${e.message}")
            }
        }

        emergencyStateManager = EmergencyStateManager(
            scope = applicationScope,
            bleManager = switchableBleManager,
            locationProvider = locationProvider,
            alertDispatcher = alertDispatcher,
            audioPipeline = audioPipeline,
            assessmentEngine = assessmentEngine,
            escalationPolicy = escalationPolicy,
            policeCallManager = policeCallManager,
            contactRepository = contactRepository,
            sessionHistoryRepository = sessionHistoryRepository,
            preferences = preferences,
            liveTrackingClient = liveTrackingClient,
            transcriptionService = transcriptionService,
            voskTranscriptionService = externalTranscriptionService,
            btAudioReceiver = btAudioReceiver,
            foregroundServiceStarter = { com.example.sakhi.service.EmergencyForegroundService.startEmergency(this) },
            foregroundServiceStopper = { com.example.sakhi.service.EmergencyForegroundService.notifyEmergencyEnded(this) }
        )

        assistantManager = com.example.sakhi.data.assistant.AssistantManager(
            transcriptionService = transcriptionService,
            assessmentEngineProvider = { assessmentEngine },
            scope = applicationScope
        )

        audioDiagnosticRecorder = com.example.sakhi.data.audio.AudioDiagnosticRecorder(
            context = this,
            audioReceiver = btAudioReceiver,
            scope = applicationScope
        )
    }

    fun switchToRealBle() {
        switchableBleManager.switchToReal()
    }

    fun switchToSimulatedBle() {
        switchableBleManager.switchToSimulated()
    }

    fun isUsingSimulatedBle(): Boolean {
        return switchableBleManager.isCurrentlySimulated()
    }

    fun refreshAssessmentEngine() {
        assessmentEngine = if (geminiAssessmentEngine.isConfigured) geminiAssessmentEngine else localAssessmentEngine
    }
}
