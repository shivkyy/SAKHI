package com.example.sakhi.data.alerts

import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.telephony.SmsManager
import androidx.core.content.ContextCompat
import com.example.sakhi.core.model.Contact
import com.example.sakhi.core.model.ContactAlertState
import com.example.sakhi.data.location.SakhiLocation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

interface AlertDispatcher {
    suspend fun dispatchAlertToContacts(
        contacts: List<Contact>,
        location: SakhiLocation?,
        template: String,
        includeAccuracy: Boolean,
        onContactStatusUpdate: (contactId: Long, state: ContactAlertState) -> Unit
    ): ContactAlertState

    suspend fun dispatchAlertToContacts(
        contacts: List<Contact>,
        location: SakhiLocation?,
        template: String,
        includeAccuracy: Boolean,
        liveTrackingUrl: String?,
        onContactStatusUpdate: (contactId: Long, state: ContactAlertState) -> Unit
    ): ContactAlertState = dispatchAlertToContacts(contacts, location, template, includeAccuracy, onContactStatusUpdate)

    suspend fun sendPeriodicLocationUpdate(
        contacts: List<Contact>,
        location: SakhiLocation?,
        liveTrackingUrl: String?
    ): Int = 0

    suspend fun sendPeriodicLocationUpdate(
        contacts: List<Contact>,
        location: SakhiLocation?,
        liveTrackingUrl: String?,
        updateNumber: Int,
        speechExcerpt: String? = null,
        assessmentLabel: String? = null,
        onContactStatusUpdate: (contactId: Long, state: ContactAlertState) -> Unit = { _, _ -> }
    ): Int = sendPeriodicLocationUpdate(contacts, location, liveTrackingUrl)

    suspend fun sendSafeFollowUp(
        contacts: List<Contact>,
        onStatusUpdate: (contactId: Long, state: ContactAlertState) -> Unit
    )
}

class AndroidAlertDispatcher(private val context: Context) : AlertDispatcher {

    companion object {
        const val ACTION_SMS_SENT = "com.example.sakhi.SMS_SENT_ACTION"
        const val ACTION_SMS_DELIVERED = "com.example.sakhi.SMS_DELIVERED_ACTION"

        fun formatPeriodicUpdateMessage(
            location: SakhiLocation?,
            liveTrackingUrl: String?,
            updateNumber: Int,
            speechExcerpt: String?,
            assessmentLabel: String?
        ): String {
            val timeStr = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
            val maps = location?.mapsUrl ?: "Unavailable"
            val acc = when {
                location == null -> "N/A"
                location.accuracy != null && location.isStale -> "±${location.accuracy.toInt()}m (stale)"
                location.accuracy != null -> "±${location.accuracy.toInt()}m"
                location.isStale -> "stale"
                else -> "N/A"
            }
            val sb = StringBuilder()
            sb.append("SAKHI Emergency Update #$updateNumber\n")
            sb.append("Time: $timeStr\n")
            sb.append("Location: $maps ($acc)")
            if (!liveTrackingUrl.isNullOrBlank()) {
                sb.append("\nTracking: $liveTrackingUrl")
            }
            if (!speechExcerpt.isNullOrBlank()) {
                val cleanExcerpt = if (speechExcerpt.length > 80) speechExcerpt.take(77) + "..." else speechExcerpt
                sb.append("\nSpeech detected: \"$cleanExcerpt\"")
                if (!assessmentLabel.isNullOrBlank()) {
                    sb.append("\nAssessment: $assessmentLabel")
                }
            }
            return sb.toString()
        }
    }

    private val smsManager: SmsManager? by lazy {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.getSystemService(SmsManager::class.java)
        } else {
            @Suppress("DEPRECATION")
            SmsManager.getDefault()
        }
    }

    override suspend fun dispatchAlertToContacts(
        contacts: List<Contact>,
        location: SakhiLocation?,
        template: String,
        includeAccuracy: Boolean,
        onContactStatusUpdate: (contactId: Long, state: ContactAlertState) -> Unit
    ): ContactAlertState {
        return dispatchAlertToContacts(contacts, location, template, includeAccuracy, null, onContactStatusUpdate)
    }

    override suspend fun dispatchAlertToContacts(
        contacts: List<Contact>,
        location: SakhiLocation?,
        template: String,
        includeAccuracy: Boolean,
        liveTrackingUrl: String?,
        onContactStatusUpdate: (contactId: Long, state: ContactAlertState) -> Unit
    ): ContactAlertState = withContext(Dispatchers.IO) {
        val enabledContacts = contacts.filter { it.smsEnabled && it.isValid() }
        if (enabledContacts.isEmpty()) {
            return@withContext ContactAlertState.FAILED
        }

        val messageText = formatEmergencyMessage(template, location, includeAccuracy, liveTrackingUrl)
        var atLeastOneSent = false
        var atLeastOneDelivered = false

        for (contact in enabledContacts) {
            onContactStatusUpdate(contact.id, ContactAlertState.SENDING)
            val result = sendSmsWithRetry(contact.phoneNumber, messageText)

            when (result) {
                ContactAlertState.SENT -> {
                    atLeastOneSent = true
                    onContactStatusUpdate(contact.id, ContactAlertState.SENT)
                }
                ContactAlertState.DELIVERED -> {
                    atLeastOneSent = true
                    atLeastOneDelivered = true
                    onContactStatusUpdate(contact.id, ContactAlertState.DELIVERED)
                }
                else -> {
                    onContactStatusUpdate(contact.id, ContactAlertState.FAILED)
                }
            }
        }

        // If direct SMS failed for ALL contacts, fall back to SMS composer for first contact
        if (!atLeastOneSent && !atLeastOneDelivered) {
            val firstContact = enabledContacts.first()
            val handedOff = withContext(Dispatchers.Main) {
                openSmsComposerFallback(firstContact.phoneNumber, messageText)
            }
            if (handedOff) {
                onContactStatusUpdate(firstContact.id, ContactAlertState.HANDED_TO_MESSAGING_APP)
                return@withContext ContactAlertState.HANDED_TO_MESSAGING_APP
            } else {
                onContactStatusUpdate(firstContact.id, ContactAlertState.FAILED)
                return@withContext ContactAlertState.FAILED
            }
        }

        when {
            atLeastOneDelivered -> ContactAlertState.DELIVERED
            atLeastOneSent -> ContactAlertState.SENT
            else -> ContactAlertState.FAILED
        }
    }

    override suspend fun sendPeriodicLocationUpdate(
        contacts: List<Contact>,
        location: SakhiLocation?,
        liveTrackingUrl: String?
    ): Int = sendPeriodicLocationUpdate(
        contacts = contacts,
        location = location,
        liveTrackingUrl = liveTrackingUrl,
        updateNumber = 1,
        speechExcerpt = null,
        assessmentLabel = null,
        onContactStatusUpdate = { _, _ -> }
    )

    override suspend fun sendPeriodicLocationUpdate(
        contacts: List<Contact>,
        location: SakhiLocation?,
        liveTrackingUrl: String?,
        updateNumber: Int,
        speechExcerpt: String?,
        assessmentLabel: String?,
        onContactStatusUpdate: (contactId: Long, state: ContactAlertState) -> Unit
    ): Int = withContext(Dispatchers.IO) {
        val enabledContacts = contacts.filter { it.smsEnabled && it.isValid() }
        if (enabledContacts.isEmpty()) return@withContext 0

        val updateText = formatPeriodicUpdateMessage(
            location = location,
            liveTrackingUrl = liveTrackingUrl,
            updateNumber = updateNumber,
            speechExcerpt = speechExcerpt,
            assessmentLabel = assessmentLabel
        )

        var sentCount = 0
        for (contact in enabledContacts) {
            onContactStatusUpdate(contact.id, ContactAlertState.SENDING)
            val res = sendSmsWithRetry(contact.phoneNumber, updateText)
            onContactStatusUpdate(contact.id, res)
            if (res == ContactAlertState.SENT || res == ContactAlertState.DELIVERED) {
                sentCount++
            }
        }
        sentCount
    }



    override suspend fun sendSafeFollowUp(
        contacts: List<Contact>,
        onStatusUpdate: (contactId: Long, state: ContactAlertState) -> Unit
    ) = withContext(Dispatchers.IO) {
        val safeMessage = "SAKHI UPDATE: The emergency situation has ended. The user has disarmed and confirmed they are safe."
        val enabledContacts = contacts.filter { it.smsEnabled && it.isValid() }
        for (contact in enabledContacts) {
            onStatusUpdate(contact.id, ContactAlertState.SENDING)
            val res = sendSmsWithRetry(contact.phoneNumber, safeMessage)
            onStatusUpdate(contact.id, res)
        }
    }

    private suspend fun sendSmsWithRetry(phoneNumber: String, text: String): ContactAlertState {
        // Attempt 1
        val firstAttempt = attemptDirectSms(phoneNumber, text)
        if (firstAttempt == ContactAlertState.SENT || firstAttempt == ContactAlertState.DELIVERED) {
            return firstAttempt
        }

        // Retry once after brief pause
        delay(1500)
        return attemptDirectSms(phoneNumber, text)
    }

    private fun attemptDirectSms(phoneNumber: String, text: String): ContactAlertState {
        val mgr = smsManager ?: return ContactAlertState.FAILED
        val cleanPhone = phoneNumber.replace(Regex("[^0-9+]"), "")

        return try {
            val parts = mgr.divideMessage(text)
            if (parts.size > 1) {
                mgr.sendMultipartTextMessage(cleanPhone, null, parts, null, null)
            } else {
                mgr.sendTextMessage(cleanPhone, null, text, null, null)
            }
            // Direct submission succeeded to radio
            ContactAlertState.SENT
        } catch (e: SecurityException) {
            // Permission denied
            ContactAlertState.FAILED
        } catch (e: Exception) {
            ContactAlertState.FAILED
        }
    }

    fun openSmsComposerFallback(phoneNumber: String, text: String): Boolean {
        return try {
            val cleanPhone = phoneNumber.replace(Regex("[^0-9+]"), "")
            val intent = Intent(Intent.ACTION_SENDTO).apply {
                data = Uri.parse("smsto:$cleanPhone")
                putExtra("sms_body", text)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
            true
        } catch (e: Exception) {
            false
        }
    }

    fun openWhatsAppFallback(phoneNumber: String, text: String): Boolean {
        return try {
            val cleanPhone = phoneNumber.replace(Regex("[^0-9]"), "")
            val uri = Uri.parse("https://api.whatsapp.com/send?phone=$cleanPhone&text=${Uri.encode(text)}")
            val intent = Intent(Intent.ACTION_VIEW, uri).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
            true
        } catch (e: Exception) {
            false
        }
    }

    private fun formatEmergencyMessage(
        template: String,
        location: SakhiLocation?,
        includeAccuracy: Boolean,
        liveTrackingUrl: String? = null
    ): String {
        val timeStr = SimpleDateFormat("HH:mm:ss dd MMM", Locale.getDefault()).format(Date())
        val locStr = if (!liveTrackingUrl.isNullOrBlank()) {
            "$liveTrackingUrl\n(Maps: ${location?.mapsUrl ?: "N/A"})"
        } else {
            location?.mapsUrl ?: "Location unavailable"
        }
        val accStr = if (includeAccuracy && location?.accuracy != null) {
            "${location.accuracy.toInt()}m${if (location.isStale) " (stale)" else ""}"
        } else {
            "N/A"
        }

        var message = template
            .replace("{time}", timeStr)
            .replace("{location}", locStr)
            .replace("{accuracy}", accStr)

        if (!liveTrackingUrl.isNullOrBlank()) {
            if (message.contains("{tracking_url}")) {
                message = message.replace("{tracking_url}", liveTrackingUrl)
            } else if (!message.contains(liveTrackingUrl)) {
                message = "$message\nLive Tracking: $liveTrackingUrl"
            }
        }

        return message
    }
}
