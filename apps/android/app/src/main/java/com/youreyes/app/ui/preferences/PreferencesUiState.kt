package com.youreyes.app.ui.preferences

/**
 * Accessibility preferences UI state. Values must stay inside the same fixed sets the
 * backend enforces (`apps/backend/src/app/schemas/preferences.py`'s `Literal` types) —
 * [FONT_SIZE_OPTIONS]/[VOICE_OPTIONS] below are that same domain, not free-typed labels.
 */
data class PreferencesUiState(
    val isLoggedIn: Boolean = false,
    val fontSizeOption: String = "Vừa",
    val voiceOption: String = "Giọng Nữ",
    val highContrast: Boolean = false,
    val hapticsEnabled: Boolean = true,
    val isLoading: Boolean = false,
    val message: String = "",
    val isError: Boolean = false,
) {
    /** Controls are editable only once logged in (the API requires a real user session). */
    val canEdit: Boolean
        get() = isLoggedIn && !isLoading

    companion object {
        val FONT_SIZE_OPTIONS = listOf("Nhỏ", "Vừa", "To")
        val VOICE_OPTIONS = listOf("Giọng Nữ", "Giọng Nam")
    }
}
