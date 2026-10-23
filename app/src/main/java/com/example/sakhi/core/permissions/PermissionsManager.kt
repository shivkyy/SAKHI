package com.example.sakhi.core.permissions

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import androidx.core.content.ContextCompat

data class PermissionStatus(
    val hasBluetooth: Boolean,
    val hasLocation: Boolean,
    val hasMicrophone: Boolean,
    val hasNotifications: Boolean,
    val hasSms: Boolean,
    val isBluetoothEnabled: Boolean,
    val isLocationEnabled: Boolean,
    val hasCallPhone: Boolean = true
) {
    val isCoreEmergencyReady: Boolean
        get() = hasBluetooth && hasLocation && hasNotifications && hasSms && isBluetoothEnabled && isLocationEnabled

    val allGranted: Boolean
        get() = hasBluetooth && hasLocation && hasMicrophone && hasNotifications && hasSms && hasCallPhone
}

class PermissionsManager(private val context: Context) {

    fun checkPermissions(): PermissionStatus {
        val hasBluetooth = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            hasPermission(Manifest.permission.BLUETOOTH_SCAN) &&
                    hasPermission(Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            hasPermission(Manifest.permission.BLUETOOTH) &&
                    hasPermission(Manifest.permission.BLUETOOTH_ADMIN)
        }

        val hasLocation = hasPermission(Manifest.permission.ACCESS_FINE_LOCATION) ||
                hasPermission(Manifest.permission.ACCESS_COARSE_LOCATION)

        val hasMicrophone = hasPermission(Manifest.permission.RECORD_AUDIO)

        val hasNotifications = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            hasPermission(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            true
        }

        val hasSms = hasPermission(Manifest.permission.SEND_SMS)

        val hasCallPhone = hasPermission(Manifest.permission.CALL_PHONE)

        val bluetoothAdapter = try {
            @Suppress("DEPRECATION")
            BluetoothAdapter.getDefaultAdapter()
        } catch (e: Exception) {
            null
        }

        val isBluetoothEnabled = try {
            bluetoothAdapter?.isEnabled == true
        } catch (e: SecurityException) {
            false
        } catch (e: Exception) {
            false
        }

        val locationManager = try {
            context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
        } catch (e: Exception) {
            null
        }

        val isLocationEnabled = try {
            locationManager?.isProviderEnabled(LocationManager.GPS_PROVIDER) == true ||
                    locationManager?.isProviderEnabled(LocationManager.NETWORK_PROVIDER) == true
        } catch (e: Exception) {
            false
        }

        return PermissionStatus(
            hasBluetooth = hasBluetooth,
            hasLocation = hasLocation,
            hasMicrophone = hasMicrophone,
            hasNotifications = hasNotifications,
            hasSms = hasSms,
            isBluetoothEnabled = isBluetoothEnabled,
            isLocationEnabled = isLocationEnabled,
            hasCallPhone = hasCallPhone
        )
    }

    private fun hasPermission(permission: String): Boolean {
        return ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
    }

    fun getRequiredRuntimePermissions(): List<String> {
        val list = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.SEND_SMS,
            Manifest.permission.CALL_PHONE
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            list.add(Manifest.permission.BLUETOOTH_SCAN)
            list.add(Manifest.permission.BLUETOOTH_CONNECT)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            list.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        return list
    }

    fun isSmsRestrictedByAndroid(): Boolean {
        return !hasPermission(Manifest.permission.SEND_SMS) && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
    }

    companion object {
        fun openAppDetailsSettings(context: Context) {
            try {
                val intent = android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = android.net.Uri.fromParts("package", context.packageName, null)
                    flags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK
                }
                context.startActivity(intent)
            } catch (_: Exception) {}
        }
    }
}
