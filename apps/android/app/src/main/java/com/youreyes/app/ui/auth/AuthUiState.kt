package com.youreyes.app.ui.auth

enum class AuthMode { LOGIN, REGISTER }
enum class AuthStep { CREDENTIALS, OTP }

data class AuthUiState(
    val mode: AuthMode = AuthMode.LOGIN,
    val step: AuthStep = AuthStep.CREDENTIALS,
    val phoneNumber: String = "",
    val password: String = "",
    val displayName: String = "",
    val otpCode: String = "",
    val isLoading: Boolean = false,
    val message: String = "",
    val isError: Boolean = false,
    // Non-blank only once a session is active (see AuthViewModel.loadInitialState);
    // drives AuthScreen's "already logged in" branch.
    val loggedInPhoneNumber: String = "",
    val loggedInDisplayName: String = "",
) {
    /** Mirrors the backend's own validation (app/schemas/auth.py: >=8 digits, password min_length=6) so the button disables before a doomed request is even sent. */
    val canSubmitCredentials: Boolean
        get() = !isLoading &&
            phoneNumber.count { it.isDigit() } >= 8 &&
            password.length >= 6

    val canSubmitOtp: Boolean
        get() = !isLoading && otpCode.isNotBlank()

    val isLoggedIn: Boolean
        get() = loggedInPhoneNumber.isNotBlank()
}
