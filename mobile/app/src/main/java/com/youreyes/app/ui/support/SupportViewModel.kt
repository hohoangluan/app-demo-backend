package com.youreyes.app.ui.support

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.youreyes.app.core.AppConfig
import com.youreyes.app.network.DeviceApiClient
import com.youreyes.app.ui.auth.AuthViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Real `POST /support/tickets` (server/src/app/api/support.py), gated by the
 * same session Bearer token as preferences/logout ([AppConfig.KEY_ACCESS_TOKEN]).
 */
class SupportViewModel(application: Application) : AndroidViewModel(application) {

    private val prefs = AppConfig.prefs(application)

    private val _uiState = MutableStateFlow(SupportUiState(isLoggedIn = hasSession()))
    val uiState: StateFlow<SupportUiState> = _uiState.asStateFlow()

    private fun hasSession(): Boolean = !accessToken().isNullOrBlank()
    private fun accessToken(): String? = prefs.getString(AppConfig.KEY_ACCESS_TOKEN, null)
    private fun baseUrl(): String = prefs.getString(AppConfig.KEY_BASE_URL, "") ?: ""

    /** Re-checks login state; call when this screen is re-entered — same reason as [com.youreyes.app.ui.preferences.PreferencesViewModel.refresh]. */
    fun refresh() = _uiState.update { it.copy(isLoggedIn = hasSession()) }

    fun onCategoryChange(value: String) = _uiState.update { it.copy(category = value) }

    fun onMessageChange(value: String) =
        _uiState.update { it.copy(messageText = value.take(SupportUiState.MAX_MESSAGE_LENGTH)) }

    fun submit() {
        val state = _uiState.value
        val token = accessToken()
        if (!state.canSubmit || token == null) return

        _uiState.update { it.copy(isLoading = true, statusMessage = "", isError = false) }
        viewModelScope.launch(Dispatchers.IO) {
            val result = DeviceApiClient().submitSupportTicket(
                baseUrl(), token, state.category, state.messageText.trim(),
            )
            if (result.isSuccess) {
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        isError = false,
                        statusMessage = "✅ Đã gửi. Cảm ơn bạn đã phản hồi!",
                        messageText = "",
                    )
                }
            } else {
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        isError = true,
                        statusMessage = "❌ Gửi thất bại: ${result.exceptionOrNull()?.message}",
                    )
                }
            }
        }
    }
}
