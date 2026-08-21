package com.youreyes.app.ui.translation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TranslationUiStateTest {

    @Test
    fun `can swap languages by default`() {
        assertTrue(TranslationUiState().canSwapLanguages)
    }

    @Test
    fun `cannot swap languages while listening`() {
        assertFalse(TranslationUiState(isListening = true).canSwapLanguages)
    }

    @Test
    fun `swapping exchanges source and target`() {
        val state = TranslationUiState(sourceLanguage = "Tiếng Việt", targetLanguage = "English")
        val swapped = state.withLanguagesSwapped()
        assertEquals("English", swapped.sourceLanguage)
        assertEquals("Tiếng Việt", swapped.targetLanguage)
    }

    @Test
    fun `swapping twice returns to the original languages`() {
        val state = TranslationUiState(sourceLanguage = "Tiếng Việt", targetLanguage = "English")
        assertEquals(state, state.withLanguagesSwapped().withLanguagesSwapped())
    }
}
