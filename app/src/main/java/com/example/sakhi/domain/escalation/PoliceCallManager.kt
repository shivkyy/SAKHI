package com.example.sakhi.domain.escalation

import android.Manifest
import android.annotation.SuppressLint
import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.telecom.TelecomManager
import android.telephony.PhoneNumberUtils
import android.telephony.TelephonyManager
import android.util.Log
import androidx.core.content.ContextCompat
import com.example.sakhi.core.model.PoliceState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

open class PoliceCallManager(private val context: Context? = null) {

    companion object {
        private const val TAG = "PoliceCallManager"
    }

    private val _policeState = MutableStateFlow(PoliceState.NOT_REQUESTED)
    open val policeState: StateFlow<PoliceState> = _policeState.asStateFlow()

    var lastDialedNumber: String? = null
        protected set

    var lastCallAction: String? = null
        protected set

    fun resetState() {
        _policeState.value = PoliceState.NOT_REQUESTED
        lastDialedNumber = null
        lastCallAction = null
    }

    fun markRequested() {
        if (_policeState.value == PoliceState.NOT_REQUESTED) {
            _policeState.value = PoliceState.REQUESTED
        }
    }

    open fun isEmergencyNumber(number: String): Boolean {
        val cleaned = number.trim().replace(Regex("[^0-9+*#]"), "")
        val knownEmergencyNumbers = setOf("112", "911", "999", "100", "101", "102", "108")
        if (cleaned in knownEmergencyNumbers) return true

        val ctx = context ?: return false
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val telephonyManager = ctx.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
                telephonyManager?.isEmergencyNumber(cleaned) == true
            } else {
                @Suppress("DEPRECATION")
                PhoneNumberUtils.isEmergencyNumber(cleaned)
            }
        } catch (_: Exception) {
            cleaned in knownEmergencyNumbers
        }
    }

    open fun isDeviceLocked(): Boolean {
        val ctx = context ?: return false
        return try {
            val keyguardManager = ctx.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
            val powerManager = ctx.getSystemService(Context.POWER_SERVICE) as? PowerManager
            val isLocked = keyguardManager?.isKeyguardLocked == true
            val isScreenOff = powerManager?.isInteractive == false
            isLocked || isScreenOff
        } catch (_: Exception) {
            false
        }
    }

    open fun hasCallPhonePermission(): Boolean {
        val ctx = context ?: return true
        return ContextCompat.checkSelfPermission(
            ctx,
            Manifest.permission.CALL_PHONE
        ) == PackageManager.PERMISSION_GRANTED
    }

    open fun performDirectCall(cleanedNumber: String): Boolean {
        if (isDeviceLocked()) {
            throw SecurityException("Direct startActivity(ACTION_CALL) is blocked while device is locked or screen is off")
        }
        val ctx = context ?: return true
        val targetUri = Uri.parse("tel:$cleanedNumber")
        val callIntent = Intent(Intent.ACTION_CALL, targetUri).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        ctx.startActivity(callIntent)
        return true
    }

    @SuppressLint("MissingPermission")
    open fun performTelecomPlaceCall(cleanedNumber: String): Boolean {
        val ctx = context ?: return false
        val telecomManager = ctx.getSystemService(Context.TELECOM_SERVICE) as? TelecomManager
        if (telecomManager == null) {
            Log.w(TAG, "TelecomManager unavailable on device")
            return false
        }
        return try {
            val targetUri = Uri.parse("tel:$cleanedNumber")
            val extras = Bundle().apply {
                putBoolean(TelecomManager.EXTRA_START_CALL_WITH_SPEAKERPHONE, true)
            }
            telecomManager.placeCall(targetUri, extras)
            Log.i(TAG, "TelecomManager.placeCall initiated for $cleanedNumber")
            true
        } catch (e: SecurityException) {
            Log.w(TAG, "TelecomManager.placeCall denied by OS security policy for $cleanedNumber: ${e.message}")
            false
        } catch (e: Exception) {
            Log.w(TAG, "TelecomManager.placeCall failed for $cleanedNumber: ${e.message}")
            false
        }
    }

    open fun performDialerFallback(cleanedNumber: String): Boolean {
        val ctx = context ?: return true
        val targetUri = Uri.parse("tel:$cleanedNumber")
        val dialIntent = Intent(Intent.ACTION_DIAL, targetUri).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        return try {
            ctx.startActivity(dialIntent)
            Log.i(TAG, "Dialer fallback intent launched for $cleanedNumber")
            true
        } catch (e: Exception) {
            Log.w(TAG, "Dialer fallback startActivity failed for $cleanedNumber: ${e.message}")
            false
        }
    }

    open suspend fun initiateEmergencyCall(
        emergencyNumber: String = "112",
        forceInitiate: Boolean = false
    ): PoliceState {
        val cleaned = emergencyNumber.trim().replace(Regex("[^0-9+*#]"), "")
        if (cleaned.filter { it.isDigit() }.length < 3) {
            _policeState.value = PoliceState.FAILED
            return PoliceState.FAILED
        }

        val current = _policeState.value
        if (!forceInitiate && (current == PoliceState.CALL_INITIATED || current == PoliceState.CONNECTED)) {
            return current
        }

        lastDialedNumber = cleaned
        return if (context != null) {
            withContext(Dispatchers.Main) {
                executeCallFlow(cleaned)
            }
        } else {
            executeCallFlow(cleaned)
        }
    }

    private fun executeCallFlow(cleaned: String): PoliceState {
        _policeState.value = PoliceState.REQUESTED
        val isEmergency = isEmergencyNumber(cleaned)

        // 1. Where Android permits direct calling (CALL_PHONE granted) and number is NOT a restricted emergency helpline:
        if (hasCallPhonePermission() && !isEmergency) {
            try {
                if (performDirectCall(cleaned)) {
                    lastCallAction = Intent.ACTION_CALL
                    _policeState.value = PoliceState.CALL_INITIATED
                    return PoliceState.CALL_INITIATED
                }
            } catch (e: Exception) {
                Log.w(TAG, "performDirectCall failed (${e.message}); attempting TelecomManager.placeCall.")
                if (performTelecomPlaceCall(cleaned)) {
                    lastCallAction = Intent.ACTION_CALL
                    _policeState.value = PoliceState.CALL_INITIATED
                    return PoliceState.CALL_INITIATED
                }
            }
        } else if (isEmergency) {
            Log.i(TAG, "Number $cleaned is a recognized emergency helpline. Direct background dialing requires platform CALL_PRIVILEGED permission; engaging lock-screen dialer fallback.")
        } else {
            Log.w(TAG, "CALL_PHONE permission not granted; falling back to lock-screen dialer.")
        }

        // 2. Safe fallback to ACTION_DIAL if CALL_PHONE is denied or restricted (e.g. emergency shortcodes like 112)
        return try {
            if (performDialerFallback(cleaned)) {
                lastCallAction = Intent.ACTION_DIAL
                _policeState.value = PoliceState.CALL_INITIATED
                PoliceState.CALL_INITIATED
            } else {
                _policeState.value = PoliceState.FAILED
                PoliceState.FAILED
            }
        } catch (e: Exception) {
            Log.w(TAG, "All emergency calling options failed: ${e.message}")
            _policeState.value = PoliceState.FAILED
            PoliceState.FAILED
        }
    }

    fun confirmConnected() {
        if (_policeState.value == PoliceState.CALL_INITIATED) {
            _policeState.value = PoliceState.CONNECTED
        }
    }

    open fun onCallEnded() {
        if (_policeState.value == PoliceState.CALL_INITIATED || _policeState.value == PoliceState.CONNECTED) {
            _policeState.value = PoliceState.CALL_ENDED
        }
    }

    fun markFailed() {
        _policeState.value = PoliceState.FAILED
    }
}
