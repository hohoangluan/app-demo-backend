package com.youreyes.app.ui.preferences

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PreferencesUiStateTest {

    @Test
    fun `not editable when logged out, even if not loading`() {
        assertFalse(PreferencesUiState(isLoggedIn = false, isLoading = false).canEdit)
    }

    @Test
    fun `not editable while a save or load is in flight`() {
        assertFalse(PreferencesUiState(isLoggedIn = true, isLoading = true).canEdit)
    }

    @Test
    fun `editable once logged in and not loading`() {
        assertTrue(PreferencesUiState(isLoggedIn = true, isLoading = false).canEdit)
    }

    @Test
    fun `font size options match the backend's Literal domain, not the old wrong 'Lon' label`() {
        // Regression: server/src/app/schemas/preferences.py defines
        // FontSizeOption = Literal["Nhỏ", "Vừa", "To"]; the pre-wiring UI offered
        // "Lớn" instead of "To", which the server would have rejected as invalid.
        assertEquals(listOf("Nhỏ", "Vừa", "To"), PreferencesUiState.FONT_SIZE_OPTIONS)
        assertFalse(PreferencesUiState.FONT_SIZE_OPTIONS.contains("Lớn"))
    }

    @Test
    fun `voice options match the backend's Literal domain`() {
        assertEquals(listOf("Giọng Nữ", "Giọng Nam"), PreferencesUiState.VOICE_OPTIONS)
    }
}
