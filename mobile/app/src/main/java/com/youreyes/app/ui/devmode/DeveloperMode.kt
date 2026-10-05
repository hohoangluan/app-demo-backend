package com.youreyes.app.ui.devmode

import android.content.Context
import androidx.core.content.edit
import com.youreyes.app.core.AppConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Whether the developer sections of Hồ sơ are visible.
 *
 * Hồ sơ shipped with two sections aimed at the team, sitting in the middle of a
 * screen a blind user is expected to operate: "Cấu Hình Server Backend & Đăng
 * Ký", with free-text fields for the server URL, the device bearer token, the
 * user_id and the device_id; and "Công Cụ Cho Nhà Phát Triển", which fires all
 * nine native action handlers. Between them they can point the app at the wrong
 * server, break its pairing, or place a real phone call, and none of that is
 * recoverable by someone who cannot read what changed.
 *
 * Nothing is deleted — the team still needs both, including on a release build
 * during field testing, so a `BuildConfig.DEBUG` gate would be too blunt. They
 * are reached the way Android itself does it: seven taps on the version row at
 * the bottom of Hồ sơ. The choice persists, so the team taps once per install.
 */
object DeveloperMode {

    private const val KEY_ENABLED = "developer_mode_enabled"

    /** Matches the Android build-number gesture, for the same reason: it cannot be hit by accident. */
    const val TAPS_TO_ENABLE = 7

    private val _enabled = MutableStateFlow(false)
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    private var appContext: Context? = null

    fun load(context: Context) {
        val app = context.applicationContext
        appContext = app
        _enabled.value = app
            .getSharedPreferences(AppConfig.PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_ENABLED, false)
    }

    fun setEnabled(value: Boolean) {
        _enabled.value = value
        appContext
            ?.getSharedPreferences(AppConfig.PREFS_NAME, Context.MODE_PRIVATE)
            ?.edit { putBoolean(KEY_ENABLED, value) }
    }
}
