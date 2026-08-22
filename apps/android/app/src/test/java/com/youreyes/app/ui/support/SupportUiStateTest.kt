package com.youreyes.app.ui.support

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SupportUiStateTest {

    @Test
    fun `cannot submit when logged out`() {
        assertFalse(SupportUiState(isLoggedIn = false, messageText = "hello").canSubmit)
    }

    @Test
    fun `cannot submit a blank message`() {
        assertFalse(SupportUiState(isLoggedIn = true, messageText = "   ").canSubmit)
    }

    @Test
    fun `can submit a non-blank message within the length limit while logged in`() {
        assertTrue(SupportUiState(isLoggedIn = true, messageText = "Cần hỗ trợ pairing kính").canSubmit)
    }

    @Test
    fun `cannot submit a message over the backend's max length`() {
        val tooLong = "a".repeat(SupportUiState.MAX_MESSAGE_LENGTH + 1)
        assertFalse(SupportUiState(isLoggedIn = true, messageText = tooLong).canSubmit)
    }

    @Test
    fun `cannot submit while a request is already loading`() {
        assertFalse(SupportUiState(isLoggedIn = true, messageText = "hi", isLoading = true).canSubmit)
    }

    @Test
    fun `categories match the backend's Literal domain, in order`() {
        assertEquals(listOf("feedback", "support_request"), SupportUiState.CATEGORIES.map { it.first })
    }
}
