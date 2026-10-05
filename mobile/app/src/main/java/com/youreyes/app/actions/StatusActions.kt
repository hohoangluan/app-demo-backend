package com.youreyes.app.actions

import android.Manifest
import android.content.Context
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import com.youreyes.app.core.AppConfig
import com.youreyes.app.core.Timestamps

/**
 * `location_get`: where the phone is, with a spoken-friendly address when the
 * geocoder answers in time (`address` is null otherwise).
 */
class LocationGetHandler : ActionHandler {
    override fun execute(context: Context?, paramsJson: String): ActionExecutionResult {
        if (context == null) return failure("CURRENT_LOCATION_UNAVAILABLE", "No context available")
        if (!hasLocationPermission(context)) {
            return failure("LOCATION_PERMISSION_DENIED", "Location permission not granted")
        }
        val location = currentLocation(context)
            ?: return failure("CURRENT_LOCATION_UNAVAILABLE", "No location fix available on this device")
        return success(
            "lat" to location.latitude,
            "lng" to location.longitude,
            "address" to reverseGeocode(context, location),
            "captured_at" to Timestamps.utc(location.time),
        )
    }
}

/** `capabilities_get`: which permissions and settings the actions above can rely on. */
class CapabilitiesGetHandler : ActionHandler {
    override fun execute(context: Context?, paramsJson: String): ActionExecutionResult {
        if (context == null) return failure("INVALID_PARAMS", "Context is required for capabilities_get")
        fun granted(permission: String) = hasPermission(context, permission)

        val backgroundLocation = when {
            !hasLocationPermission(context) -> "never"
            Build.VERSION.SDK_INT < Build.VERSION_CODES.Q ||
                granted(Manifest.permission.ACCESS_BACKGROUND_LOCATION) -> "always"
            else -> "while_using"
        }
        val power = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        val emergencyContact = AppConfig.prefs(context).getString(AppConfig.KEY_EMERGENCY_CONTACT, null)

        return success(
            "capabilities" to linkedMapOf(
                "notification_listener" to hasNotificationAccess(context),
                "system_alert_window" to Settings.canDrawOverlays(context),
                "battery_optimization_off" to (power?.isIgnoringBatteryOptimizations(context.packageName) == true),
                "background_location" to backgroundLocation,
                "fine_location" to granted(Manifest.permission.ACCESS_FINE_LOCATION),
                "read_contacts" to granted(Manifest.permission.READ_CONTACTS),
                "read_phone_state" to granted(Manifest.permission.READ_PHONE_STATE),
                "call_phone" to granted(Manifest.permission.CALL_PHONE),
                "send_sms" to granted(Manifest.permission.SEND_SMS),
                "emergency_contact_set" to !emergencyContact.isNullOrBlank(),
            )
        )
    }
}
