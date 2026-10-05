package com.youreyes.app.ui.theme

import android.content.Context
import androidx.core.content.edit
import com.youreyes.app.core.AppConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The two display preferences that change how the whole app is drawn: text size
 * and high contrast.
 *
 * Both are already part of `PUT /api/v1/preferences` and both were, until now,
 * stored and never read — the app sent the user's answer to the server and then
 * kept rendering at 12sp on a pale gradient regardless. This object is what
 * makes them mean something.
 *
 * Two reasons it holds a local copy rather than reading the server value at
 * render time:
 *
 *  - `GET /preferences` needs a session, and a logged-out user must still be
 *    able to make the app readable. That is the one setting you may need before
 *    you can see well enough to log in.
 *  - Theme lookups happen on every recomposition; they cannot wait on a network
 *    call or block on disk.
 *
 * [PreferencesViewModel][com.youreyes.app.ui.preferences.PreferencesViewModel]
 * writes through here on every edit and on every server load, so the local copy
 * and the server copy converge without the theme ever knowing about either.
 */
object DisplaySettings {

    private const val KEY_FONT_SIZE = "display_font_size_option"
    private const val KEY_HIGH_CONTRAST = "display_high_contrast"

    private val _fontSizeOption = MutableStateFlow(TypeScale.MEDIUM)
    val fontSizeOption: StateFlow<String> = _fontSizeOption.asStateFlow()

    private val _highContrast = MutableStateFlow(false)
    val highContrast: StateFlow<Boolean> = _highContrast.asStateFlow()

    private var appContext: Context? = null

    /** Call once from Application/Activity creation, before the first frame. */
    fun load(context: Context) {
        val app = context.applicationContext
        appContext = app
        val prefs = AppConfig.prefs(app)
        _fontSizeOption.value = prefs.getString(KEY_FONT_SIZE, TypeScale.MEDIUM) ?: TypeScale.MEDIUM
        _highContrast.value = prefs.getBoolean(KEY_HIGH_CONTRAST, false)
    }

    fun setFontSizeOption(option: String) {
        if (option !in listOf(TypeScale.SMALL, TypeScale.MEDIUM, TypeScale.LARGE)) return
        _fontSizeOption.value = option
        persist { putString(KEY_FONT_SIZE, option) }
    }

    fun setHighContrast(enabled: Boolean) {
        _highContrast.value = enabled
        persist { putBoolean(KEY_HIGH_CONTRAST, enabled) }
    }

    private inline fun persist(crossinline block: android.content.SharedPreferences.Editor.() -> Unit) {
        val prefs = appContext
            ?.getSharedPreferences(AppConfig.PREFS_NAME, Context.MODE_PRIVATE)
            ?: return
        prefs.edit { block() }
    }
}
