package com.youreyes.app.ui.auth

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.youreyes.app.core.AppConfig
import com.youreyes.app.network.AuthLoginPayload
import com.youreyes.app.network.AuthOtpVerifyPayload
import com.youreyes.app.network.AuthRegisterPayload
import com.youreyes.app.network.AuthSession
import com.youreyes.app.network.DeviceApiClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Real per-user identity: POST /auth/register -> /auth/otp/verify, or /auth/login
 * (server/src/app/api/auth.py) -- distinct from the fixed shared Device Bearer
 * token used by /device/register and /device/glasses/link.
 *
 * On successful login, this also overwrites [AppConfig.KEY_USER_ID] with the
 * real `public_user_id`. [com.youreyes.app.ui.overview.OverviewViewModel] and
 * [com.youreyes.app.ui.glasses.GlassesLinkViewModel] both already read that exact key
 * for the `user_id` they send to `/device/register` / `/device/glasses/link` -- so
 * device registration and glasses pairing pick up the real identity automatically,
 * with no change to how those two calls authenticate (still the Device Bearer token;
 * only *which* user_id value flows through changes). Those two screens must call
 * `refreshUserId()` when re-entered for this to show up without an app restart --
 * see their `LaunchedEffect(Unit)` in OverviewScreen.kt / GlassesLinkScreen.kt.
 */
class AuthViewModel(application: Application) : AndroidViewModel(application) {

    private val prefs = AppConfig.prefs(application)

    private val _uiState = MutableStateFlow(loadInitialState())
    val uiState: StateFlow<AuthUiState> = _uiState.asStateFlow()

    private fun loadInitialState(): AuthUiState {
        val hasSession = !prefs.getString(AppConfig.KEY_ACCESS_TOKEN, "").isNullOrBlank()
        return AuthUiState(
            loggedInPhoneNumber = if (hasSession) prefs.getString(AppConfig.KEY_PHONE_NUMBER, "") ?: "" else "",
            loggedInDisplayName = if (hasSession) prefs.getString(AppConfig.KEY_DISPLAY_NAME, "") ?: "" else "",
        )
    }

    fun onModeChange(mode: AuthMode) = _uiState.update {
        it.copy(mode = mode, step = AuthStep.CREDENTIALS, message = "", isError = false)
    }

    fun onPhoneNumberChange(value: String) = _uiState.update { it.copy(phoneNumber = value) }
    fun onPasswordChange(value: String) = _uiState.update { it.copy(password = value) }
    fun onDisplayNameChange(value: String) = _uiState.update { it.copy(displayName = value) }
    fun onOtpCodeChange(value: String) = _uiState.update { it.copy(otpCode = value) }

    private fun baseUrl(): String = prefs.getString(AppConfig.KEY_BASE_URL, "") ?: ""

    fun submitCredentials() {
        val state = _uiState.value
        if (!state.canSubmitCredentials) return

        _uiState.update { it.copy(isLoading = true, message = "", isError = false) }
        viewModelScope.launch(Dispatchers.IO) {
            if (state.mode == AuthMode.REGISTER) {
                val result = DeviceApiClient().registerAccount(
                    baseUrl(),
                    AuthRegisterPayload(
                        phoneNumber = state.phoneNumber.trim(),
                        password = state.password,
                        displayName = state.displayName.trim().ifBlank { null },
                    ),
                )
                if (result.isSuccess) {
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            step = AuthStep.OTP,
                            message = "✅ Đã đăng ký. Nhập mã OTP (server demo ghi mã vào log, chưa gửi SMS thật).",
                            isError = false,
                        )
                    }
                } else {
                    _uiState.update {
                        it.copy(isLoading = false, isError = true, message = "❌ ${result.exceptionOrNull()?.message}")
                    }
                }
            } else {
                val result = DeviceApiClient().login(
                    baseUrl(),
                    AuthLoginPayload(phoneNumber = state.phoneNumber.trim(), password = state.password),
                )
                applySessionResult(result)
            }
        }
    }

    fun submitOtp() {
        val state = _uiState.value
        if (!state.canSubmitOtp) return

        _uiState.update { it.copy(isLoading = true, message = "", isError = false) }
        viewModelScope.launch(Dispatchers.IO) {
            val result = DeviceApiClient().verifyOtp(
                baseUrl(),
                AuthOtpVerifyPayload(phoneNumber = state.phoneNumber.trim(), otpCode = state.otpCode.trim()),
            )
            applySessionResult(result)
        }
    }

    private fun applySessionResult(result: Result<AuthSession>) {
        if (result.isSuccess) {
            val session = result.getOrThrow()
            prefs.edit()
                .putString(AppConfig.KEY_ACCESS_TOKEN, session.accessToken)
                .putString(AppConfig.KEY_ACCOUNT_UUID, session.userId)
                .putString(AppConfig.KEY_PHONE_NUMBER, session.phoneNumber)
                .putString(AppConfig.KEY_DISPLAY_NAME, session.displayName ?: "")
                .putString(AppConfig.KEY_USER_ID, session.publicUserId)
                .apply()
            _uiState.update {
                it.copy(
                    isLoading = false,
                    isError = false,
                    message = "✅ Đăng nhập thành công.",
                    loggedInPhoneNumber = session.phoneNumber,
                    loggedInDisplayName = session.displayName ?: "",
                    password = "",
                    otpCode = "",
                )
            }
        } else {
            _uiState.update {
                it.copy(isLoading = false, isError = true, message = "❌ ${result.exceptionOrNull()?.message}")
            }
        }
    }

    /**
     * Best-effort server-side revoke, then always clears the local session regardless
     * of whether that call succeeded — a person tapping "log out" expects this device
     * signed out immediately even if the network is down; the session row still
     * expires server-side on its own.
     *
     * Judgment call: [AppConfig.KEY_USER_ID] is deliberately left untouched here
     * — clearing it would blank the id already in active use by device-register /
     * glasses-link, breaking already-configured pairing for no contract reason. Logging
     * back in (or editing it manually, same as before this feature existed) is how it
     * changes again.
     */
    fun logout() {
        val accessToken = prefs.getString(AppConfig.KEY_ACCESS_TOKEN, "") ?: ""
        _uiState.update { it.copy(isLoading = true) }
        viewModelScope.launch(Dispatchers.IO) {
            if (accessToken.isNotBlank()) {
                DeviceApiClient().logout(baseUrl(), accessToken)
            }
            prefs.edit()
                .remove(AppConfig.KEY_ACCESS_TOKEN)
                .remove(AppConfig.KEY_ACCOUNT_UUID)
                .remove(AppConfig.KEY_PHONE_NUMBER)
                .remove(AppConfig.KEY_DISPLAY_NAME)
                .apply()
            _uiState.update { AuthUiState(message = "Đã đăng xuất.") }
        }
    }
}
