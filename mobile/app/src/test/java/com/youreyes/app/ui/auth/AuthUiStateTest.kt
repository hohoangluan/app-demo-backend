package com.youreyes.app.ui.auth

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AuthUiStateTest {

    @Test
    fun `cannot submit credentials by default`() {
        assertFalse(AuthUiState().canSubmitCredentials)
    }

    @Test
    fun `credentials need at least 8 phone digits and a 6-char password`() {
        val valid = AuthUiState(phoneNumber = "0901234567", password = "secret1")
        assertTrue(valid.canSubmitCredentials)

        assertFalse(valid.copy(phoneNumber = "090123").canSubmitCredentials)
        assertFalse(valid.copy(password = "abc12").canSubmitCredentials)
    }

    @Test
    fun `phone digit count ignores separators like spaces and dashes`() {
        // "090-123-45" has 8 digits despite punctuation eating into raw length.
        val state = AuthUiState(phoneNumber = "090-123-45", password = "secret1")
        assertTrue(state.canSubmitCredentials)
    }

    @Test
    fun `cannot submit credentials while a request is already loading`() {
        val state = AuthUiState(phoneNumber = "0901234567", password = "secret1", isLoading = true)
        assertFalse(state.canSubmitCredentials)
    }

    @Test
    fun `cannot submit a blank OTP code`() {
        assertFalse(AuthUiState(otpCode = "").canSubmitOtp)
        assertFalse(AuthUiState(otpCode = "   ").canSubmitOtp)
        assertTrue(AuthUiState(otpCode = "123456").canSubmitOtp)
    }

    @Test
    fun `cannot submit OTP while a request is already loading`() {
        assertFalse(AuthUiState(otpCode = "123456", isLoading = true).canSubmitOtp)
    }

    @Test
    fun `isLoggedIn reflects whether a session phone number is present`() {
        assertFalse(AuthUiState().isLoggedIn)
        assertTrue(AuthUiState(loggedInPhoneNumber = "0901234567").isLoggedIn)
    }
}
