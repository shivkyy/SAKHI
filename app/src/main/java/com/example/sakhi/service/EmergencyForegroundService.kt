package com.example.sakhi.service

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.example.sakhi.MainActivity
import com.example.sakhi.SakhiApplication
import com.example.sakhi.core.model.ContactAlertState
import com.example.sakhi.core.model.EmergencyState
import com.example.sakhi.core.model.PoliceState
import com.example.sakhi.data.bluetooth.BtAudioConnectionState
import com.example.sakhi.data.bluetooth.BtAudioTelemetry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.launch

open class EmergencyForegroundService : Service {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var wakeLock: PowerManager.WakeLock? = null
    private var stateObservationJob: Job? = null

    @Volatile
    var isEmergencyMode: Boolean = false
        protected set

    @Volatile
    var isMonitoringActive: Boolean = false
        protected set

    @Volatile
    var lastDisplayedText: String? = null
        protected set

    private data class EmergencyNotificationKey(
        val isEmergencyActive: Boolean,
        val isCountdownActive: Boolean,
        val countdownRemainingSeconds: Int,
        val contactAlertState: ContactAlertState,
        val policeState: PoliceState,
        val escalationDestinationNumber: String?
    )

    private data class MonitoringNotificationKey(
        val state: BtAudioConnectionState,
        val deviceName: String?,
        val isAutoReconnecting: Boolean
    )

    protected open fun performStopSelf() {
        stopSelf()
    }

    constructor() : super()

    companion object {
        private const val TAG = "EmergencyForegroundService"

        const val CHANNEL_ID_EMERGENCY = "sakhi_emergency_service_channel"
        const val CHANNEL_ID_MONITORING = "sakhi_monitoring_service_channel"

        // Backward compatibility
        const val CHANNEL_ID = CHANNEL_ID_EMERGENCY
        const val NOTIFICATION_ID = 1001

        const val ACTION_START_MONITORING = "com.example.sakhi.action.START_MONITORING"
        const val ACTION_START_EMERGENCY = "com.example.sakhi.action.START_EMERGENCY"
        const val ACTION_EMERGENCY_ENDED = "com.example.sakhi.action.EMERGENCY_ENDED"
        const val ACTION_STOP_MONITORING = "com.example.sakhi.action.STOP_MONITORING"
        const val ACTION_STOP_EMERGENCY = "com.example.sakhi.action.STOP_EMERGENCY" // Alias for STOP_MONITORING
        const val ACTION_NOTIFICATION_CANCEL = "com.example.sakhi.action.NOTIFICATION_CANCEL"
        const val ACTION_NOTIFICATION_POLICE = "com.example.sakhi.action.NOTIFICATION_POLICE"
        const val ACTION_TRIGGER_SOS = "com.example.sakhi.action.TRIGGER_SOS"

        fun startMonitoring(context: Context) {
            val intent = Intent(context, EmergencyForegroundService::class.java).apply {
                action = ACTION_START_MONITORING
            }
            startServiceSafely(context, intent)
        }

        fun startEmergency(context: Context) {
            val intent = Intent(context, EmergencyForegroundService::class.java).apply {
                action = ACTION_START_EMERGENCY
            }
            startServiceSafely(context, intent)
        }

        fun notifyEmergencyEnded(context: Context) {
            val intent = Intent(context, EmergencyForegroundService::class.java).apply {
                action = ACTION_EMERGENCY_ENDED
            }
            startServiceSafely(context, intent)
        }

        fun start(context: Context) {
            startEmergency(context)
        }

        fun stop(context: Context) {
            val intent = Intent(context, EmergencyForegroundService::class.java).apply {
                action = ACTION_STOP_MONITORING
            }
            try {
                context.startService(intent)
            } catch (_: Exception) {}
        }

        private fun startServiceSafely(context: Context, intent: Intent) {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Could not start foreground service: ${e.message}")
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannels()
        acquireWakeLock()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val app = application as? SakhiApplication

        when (intent?.action) {
            ACTION_STOP_MONITORING, ACTION_STOP_EMERGENCY -> {
                Log.i(TAG, "Explicit stop requested. Shutting down service.")
                isMonitoringActive = false
                isEmergencyMode = false
                lastDisplayedText = null
                stateObservationJob?.cancel()
                stateObservationJob = null
                try {
                    app?.btAudioReceiver?.disconnect()
                } catch (_: Exception) {}
                releaseWakeLock()
                stopForeground(STOP_FOREGROUND_REMOVE)
                performStopSelf()
                return START_NOT_STICKY
            }

            ACTION_TRIGGER_SOS -> {
                Log.i(TAG, "SOS Triggered from notification action.")
                try {
                    app?.emergencyStateManager?.handleDoublePress()
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to trigger SOS from notification: ${e.message}")
                }
                return START_STICKY
            }

            ACTION_NOTIFICATION_CANCEL -> {
                Log.i(TAG, "Emergency cancellation requested from notification action.")
                try {
                    app?.emergencyStateManager?.handleTapHold()
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to cancel from notification: ${e.message}")
                }
                return START_STICKY
            }

            ACTION_NOTIFICATION_POLICE -> {
                Log.i(TAG, "Police call requested from notification action.")
                try {
                    app?.emergencyStateManager?.triggerManualPoliceCall()
                } catch (e: Exception) {
                    try {
                        val targetNum = app?.emergencyStateManager?.state?.value?.escalationDestinationNumber?.ifBlank { "112" } ?: "112"
                        val dialIntent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:$targetNum")).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        startActivity(dialIntent)
                    } catch (_: Exception) {}
                }
                return START_STICKY
            }

            ACTION_START_EMERGENCY -> {
                isEmergencyMode = true
                isMonitoringActive = true
                acquireWakeLock()
                val emergencyState = app?.emergencyStateManager?.state?.value ?: EmergencyState()
                val text = getEmergencyStatusText(emergencyState)
                lastDisplayedText = text
                val notification = buildEmergencyNotification(emergencyState, text)
                startForegroundWithType(notification, isEmergency = true)
                startStateObservation()
            }

            ACTION_EMERGENCY_ENDED -> {
                isEmergencyMode = false
                acquireWakeLock()
                if (isMonitoringActive) {
                    val telemetry = app?.btAudioReceiver?.telemetry?.value ?: BtAudioTelemetry()
                    val text = getMonitoringStatusText(telemetry)
                    lastDisplayedText = text
                    val notification = buildMonitoringNotification(telemetry, text)
                    startForegroundWithType(notification, isEmergency = false)
                    startStateObservation()
                } else {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                    return START_NOT_STICKY
                }
            }

            else -> {
                // Default: ACTION_START_MONITORING or restart by system
                isEmergencyMode = app?.emergencyStateManager?.state?.value?.isEmergencyActive == true
                isMonitoringActive = true
                acquireWakeLock()

                if (isEmergencyMode) {
                    val emergencyState = app?.emergencyStateManager?.state?.value ?: EmergencyState()
                    val text = getEmergencyStatusText(emergencyState)
                    lastDisplayedText = text
                    val notification = buildEmergencyNotification(emergencyState, text)
                    startForegroundWithType(notification, isEmergency = true)
                } else {
                    val telemetry = app?.btAudioReceiver?.telemetry?.value ?: BtAudioTelemetry()
                    val text = getMonitoringStatusText(telemetry)
                    lastDisplayedText = text
                    val notification = buildMonitoringNotification(telemetry, text)
                    startForegroundWithType(notification, isEmergency = false)
                    // Proactively attempt connection to paired SAKHI_AUDIO if offline
                    try {
                        app?.btAudioReceiver?.triggerReconnectIfAppropriate()
                    } catch (_: Exception) {}
                }
                startStateObservation()
            }
        }
        return START_STICKY
    }

    private fun startStateObservation() {
        if (stateObservationJob != null && stateObservationJob?.isActive == true) return
        val app = application as? SakhiApplication ?: return

        stateObservationJob = serviceScope.launch {
            launch {
                app.emergencyStateManager.state
                    .distinctUntilChangedBy { state ->
                        EmergencyNotificationKey(
                            isEmergencyActive = state.isEmergencyActive,
                            isCountdownActive = state.isCountdownActive,
                            countdownRemainingSeconds = state.countdownRemainingSeconds,
                            contactAlertState = state.contactAlertState,
                            policeState = state.policeState,
                            escalationDestinationNumber = state.escalationDestinationNumber
                        )
                    }
                    .collect { emergencyState ->
                        if (emergencyState.isEmergencyActive) {
                            val text = getEmergencyStatusText(emergencyState)
                            if (!isEmergencyMode || text != lastDisplayedText) {
                                isEmergencyMode = true
                                val notification = buildEmergencyNotification(emergencyState, text)
                                updateNotification(notification, text)
                            }
                        } else if (isEmergencyMode) {
                            // Emergency ended
                            isEmergencyMode = false
                            if (isMonitoringActive) {
                                val telemetry = app.btAudioReceiver.telemetry.value
                                val text = getMonitoringStatusText(telemetry)
                                val notification = buildMonitoringNotification(telemetry, text)
                                lastDisplayedText = text
                                startForegroundWithType(notification, isEmergency = false)
                            }
                        }
                    }
            }

            launch {
                app.btAudioReceiver.telemetry
                    .distinctUntilChangedBy { telemetry ->
                        MonitoringNotificationKey(
                            state = telemetry.state,
                            deviceName = telemetry.deviceName,
                            isAutoReconnecting = telemetry.isAutoReconnecting
                        )
                    }
                    .collect { btTelemetry ->
                        if (!isEmergencyMode && isMonitoringActive) {
                            val text = getMonitoringStatusText(btTelemetry)
                            if (text != lastDisplayedText) {
                                val notification = buildMonitoringNotification(btTelemetry, text)
                                updateNotification(notification, text)
                            }
                        }
                    }
            }
        }
    }

    private fun startForegroundWithType(notification: Notification, isEmergency: Boolean) {
        val hasLocationPerm = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val hasMicPerm = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        val hasBtPerm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
        } else true

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                var serviceTypes = 0
                if (hasBtPerm) {
                    serviceTypes = serviceTypes or ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
                }
                if (isEmergency) {
                    if (hasLocationPerm) serviceTypes = serviceTypes or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
                    if (hasMicPerm) serviceTypes = serviceTypes or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                }
                // Fallback / specialUse for emergency safety alerting
                serviceTypes = serviceTypes or ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE

                startForeground(NOTIFICATION_ID, notification, serviceTypes)
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                var serviceTypes = 0
                if (isEmergency) {
                    if (hasLocationPerm) serviceTypes = serviceTypes or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
                    if (hasMicPerm) serviceTypes = serviceTypes or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                }
                if (serviceTypes == 0) {
                    startForeground(NOTIFICATION_ID, notification)
                } else {
                    startForeground(NOTIFICATION_ID, notification, serviceTypes)
                }
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (e: Exception) {
            Log.w(TAG, "FGS type registration failed (${e.message}), falling back to specialUse/default.")
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
                } else {
                    startForeground(NOTIFICATION_ID, notification)
                }
            } catch (e2: Exception) {
                Log.e(TAG, "Fatal: unable to enter foreground: ${e2.message}")
                releaseWakeLock()
                stopSelf()
            }
        }
    }

    open fun updateNotification(notification: Notification, newText: String) {
        if (newText == lastDisplayedText) {
            return
        }
        lastDisplayedText = newText
        try {
            val manager = getSystemService(NotificationManager::class.java)
            manager?.notify(NOTIFICATION_ID, notification)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to update notification: ${e.message}")
        }
    }

    open fun updateNotification(notification: Notification) {
        try {
            val manager = getSystemService(NotificationManager::class.java)
            manager?.notify(NOTIFICATION_ID, notification)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to update notification: ${e.message}")
        }
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val emergencyChannel = NotificationChannel(
                CHANNEL_ID_EMERGENCY,
                "SAKHI Emergency Alert Channel",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Shows live emergency session countdown and safety controls"
                enableVibration(true)
                setShowBadge(true)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }

            val monitoringChannel = NotificationChannel(
                CHANNEL_ID_MONITORING,
                "SAKHI Safety Monitoring Channel",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Persistent safety monitoring status and keychain connection"
                enableVibration(false)
                enableLights(false)
                setShowBadge(false)
                setSound(null, null)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }

            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(emergencyChannel)
            manager?.createNotificationChannel(monitoringChannel)
        }
    }

    open fun getEmergencyStatusText(state: EmergencyState): String {
        return when {
            state.isCountdownActive -> "Alert sending in ${state.countdownRemainingSeconds}s • Tap Cancel to abort"
            state.contactAlertState == ContactAlertState.SENDING -> "Dispatching SMS alerts to contacts..."
            state.policeState == PoliceState.REQUESTED || state.policeState == PoliceState.CALL_INITIATED ->
                "Emergency Call: ${state.escalationDestinationNumber?.ifBlank { "112" } ?: "112"} • Tap to connect"
            state.contactAlertState == ContactAlertState.SENT -> "Alert Sent to Contacts • Live Tracking Active"
            else -> "Emergency session active. Monitoring safety."
        }
    }

    open fun getMonitoringStatusText(telemetry: BtAudioTelemetry): String {
        return when (telemetry.state) {
            BtAudioConnectionState.CONNECTED, BtAudioConnectionState.STREAMING ->
                "Keychain Connected (${telemetry.deviceName ?: "SAKHI_AUDIO"}) • Double-press for SOS"
            BtAudioConnectionState.CONNECTING ->
                if (telemetry.isAutoReconnecting) "Reconnecting to Keychain..."
                else "Connecting to SAKHI_AUDIO keychain..."
            BtAudioConnectionState.ERROR ->
                "Keychain Disconnected • Tap to open SAKHI"
            BtAudioConnectionState.DISCONNECTED ->
                "Keychain Offline • Safety monitoring active"
        }
    }

    open fun buildEmergencyNotification(
        state: EmergencyState,
        statusText: String = getEmergencyStatusText(state)
    ): Notification {
        val openAppIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val openAppPendingIntent = PendingIntent.getActivity(
            this,
            0,
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val fullScreenIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val fullScreenPendingIntent = PendingIntent.getActivity(
            this,
            101,
            fullScreenIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val cancelIntent = Intent(this, EmergencyForegroundService::class.java).apply {
            action = ACTION_NOTIFICATION_CANCEL
        }
        val cancelPendingIntent = PendingIntent.getService(
            this,
            1,
            cancelIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val targetNumber = state.escalationDestinationNumber?.ifBlank { "112" } ?: "112"
        val policeDialIntent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:$targetNumber")).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        val policePendingIntent = PendingIntent.getActivity(
            this,
            2,
            policeDialIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID_EMERGENCY)
            .setContentTitle("🚨 SAKHI Emergency Active")
            .setContentText(statusText)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openAppPendingIntent)
            .setFullScreenIntent(fullScreenPendingIntent, true)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "CANCEL ALERT", cancelPendingIntent)
            .addAction(android.R.drawable.ic_menu_call, "CALL ($targetNumber)", policePendingIntent)
            .build()
    }

    open fun buildMonitoringNotification(
        telemetry: BtAudioTelemetry,
        statusText: String = getMonitoringStatusText(telemetry)
    ): Notification {
        val openAppIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val openAppPendingIntent = PendingIntent.getActivity(
            this,
            0,
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val triggerSosIntent = Intent(this, EmergencyForegroundService::class.java).apply {
            action = ACTION_TRIGGER_SOS
        }
        val triggerSosPendingIntent = PendingIntent.getService(
            this,
            3,
            triggerSosIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = Intent(this, EmergencyForegroundService::class.java).apply {
            action = ACTION_STOP_MONITORING
        }
        val stopPendingIntent = PendingIntent.getService(
            this,
            4,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID_MONITORING)
            .setContentTitle("🛡️ SAKHI Safety Monitoring Active")
            .setContentText(statusText)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setShowWhen(false)
            .setContentIntent(openAppPendingIntent)
            .addAction(android.R.drawable.ic_menu_send, "TRIGGER SOS", triggerSosPendingIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "STOP", stopPendingIntent)
            .build()
    }

    private fun acquireWakeLock() {
        try {
            if (wakeLock == null) {
                val powerManager = getSystemService(Context.POWER_SERVICE) as? PowerManager
                wakeLock = powerManager?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Sakhi:EmergencyForegroundWakeLock")?.apply {
                    setReferenceCounted(false)
                }
            }
            if (wakeLock?.isHeld != true) {
                wakeLock?.acquire(60 * 60 * 1000L) // 1-hour fail-safe timeout
            }
        } catch (_: Exception) {}
    }

    private fun releaseWakeLock() {
        try {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
            }
        } catch (_: Exception) {}
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        // When user swipes SAKHI from Recents:
        // If an emergency is active OR safety monitoring is active:
        // Do NOT call stopSelf(). The foreground service remains running
        // with its persistent notification to safeguard the user.
        Log.i(TAG, "onTaskRemoved: SAKHI task removed from Recents. Maintaining safety foreground service.")
    }

    override fun onDestroy() {
        Log.i(TAG, "EmergencyForegroundService destroyed.")
        stateObservationJob?.cancel()
        serviceScope.cancel()
        releaseWakeLock()
        lastDisplayedText = null
        super.onDestroy()
    }
}
