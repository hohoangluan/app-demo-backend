package com.youreyes.app.core

import android.content.Context
import android.content.SharedPreferences

/**
 * Everything the app keeps in SharedPreferences, under one file and one set of keys.
 *
 * The file name and keys are unchanged from earlier releases so an update keeps
 * the user's registration, login and emergency contact.
 */
object AppConfig {
    const val PREFS_NAME = "appdemo_prefs"

    // Server connection, saved when the phone registers.
    const val KEY_BASE_URL = "base_url"
    const val KEY_BEARER_TOKEN = "bearer_token"
    const val KEY_USER_ID = "user_id"
    const val KEY_DEVICE_ID = "device_id"
    const val KEY_FCM_TOKEN = "fcm_token"

    // Local settings used by actions.
    const val KEY_EMERGENCY_CONTACT = "emergency_contact"

    // Phone-app login session.
    const val KEY_ACCESS_TOKEN = "auth_access_token"
    const val KEY_ACCOUNT_UUID = "auth_account_uuid"
    const val KEY_PHONE_NUMBER = "auth_phone_number"
    const val KEY_DISPLAY_NAME = "auth_display_name"

    // Glasses paired from the phone.
    const val KEY_PAIRED_GLASSES_ID = "glasses_paired_device_id"

    fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** Server URL and Device token, or null until the phone has registered. */
    fun deviceCredentials(context: Context): DeviceCredentials? {
        val prefs = prefs(context)
        val baseUrl = prefs.getString(KEY_BASE_URL, null)?.takeIf { it.isNotBlank() }
        val token = prefs.getString(KEY_BEARER_TOKEN, null)?.takeIf { it.isNotBlank() }
        return if (baseUrl != null && token != null) DeviceCredentials(baseUrl, token) else null
    }
}

/** Where to report and with which Device API token. */
data class DeviceCredentials(val baseUrl: String, val bearerToken: String)
