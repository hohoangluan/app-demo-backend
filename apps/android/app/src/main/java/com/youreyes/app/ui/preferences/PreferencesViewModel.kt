package com.youreyes.app.ui.preferences

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.youreyes.app.fcm.FcmPushReceiver
import com.youreyes.app.model.PreferencesPayload
import com.youreyes.app.network.DeviceApiClient
import com.youreyes.app.ui.auth.AuthViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Real `GET`/`PUT /preferences` (apps/backend/src/app/api/preferences.py), gated by
 * the same session Bearer token [AuthViewModel] issues (`auth_access_token` in prefs).
 * Replaces ProfileScreen's previous `remember { mutableStateOf(...) }` accessibility
 * fields, which were pure in-memory UI state — never called the backend, reset on
 * every app restart, and (font size "Lớn") did not even match the server's actual
 * `Literal["Nhỏ","Vừa","To"]` domain, so a naive wire-up would have 400'd.
 */
class PreferencesViewModel(application: Application) : AndroidViewModel(application) {

    private val prefs = application.getSharedPreferences(
        FcmPushReceiver.PREFS_NAME, Context.MODE_PRIVATE
    )

    private val _uiState = MutableStateFlow(PreferencesUiState(isLoggedIn = hasSession()))
    val uiState: StateFlow<PreferencesUiState> = _uiState.asStateFlow()

    init {
        if (hasSession()) load()
    }

    private fun hasSession(): Boolean = !accessToken().isNullOrBlank()
    private fun accessToken(): String? = prefs.getString(AuthViewModel.KEY_ACCESS_TOKEN, null)
    private fun baseUrl(): String = prefs.getString(FcmPushReceiver.KEY_BASE_URL, "") ?: ""

    /**
     * Re-checks login state and reloads from the server; call when this screen is
     * re-entered — same "AndroidViewModel is cached per-Activity, not per-tab, and only
     * reads prefs once at construction" reason as [com.youreyes.app.ui.overview.OverviewViewModel.refreshUserId].
     */
    fun refresh() {
        val loggedIn = hasSession()
        _uiState.update { it.copy(isLoggedIn = loggedIn) }
        if (loggedIn) load()
    }

    private fun load() {
        val token = accessToken() ?: return
        _uiState.update { it.copy(isLoading = true, message = "", isError = false) }
        viewModelScope.launch(Dispatchers.IO) {
            val result = DeviceApiClient().getPreferences(baseUrl(), token)
            if (result.isSuccess) {
                val data = result.getOrThrow()
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        fontSizeOption = data.fontSizeOption,
                        voiceOption = data.voiceOption,
                        highContrast = data.highContrast,
                        hapticsEnabled = data.hapticsEnabled,
                    )
                }
            } else {
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        isError = true,
                        message = "❌ Không tải được cài đặt: ${result.exceptionOrNull()?.message}",
                    )
                }
            }
        }
    }

    fun onFontSizeChange(value: String) = applyThenSave { it.copy(fontSizeOption = value) }
    fun onVoiceChange(value: String) = applyThenSave { it.copy(voiceOption = value) }
    fun onHighContrastChange(value: Boolean) = applyThenSave { it.copy(highContrast = value) }
    fun onHapticsChange(value: Boolean) = applyThenSave { it.copy(hapticsEnabled = value) }

    /**
     * Updates the field immediately (the toggle/chip responds right away) and saves to
     * the server in the background — 4 small single-user settings, not worth a separate
     * "Save" button/step.
     */
    private fun applyThenSave(update: (PreferencesUiState) -> PreferencesUiState) {
        if (!_uiState.value.canEdit) return
        val next = update(_uiState.value)
        _uiState.value = next
        save(next)
    }

    private fun save(state: PreferencesUiState) {
        val token = accessToken() ?: return
        viewModelScope.launch(Dispatchers.IO) {
            val result = DeviceApiClient().updatePreferences(
                baseUrl(),
                token,
                PreferencesPayload(
                    fontSizeOption = state.fontSizeOption,
                    voiceOption = state.voiceOption,
                    highContrast = state.highContrast,
                    hapticsEnabled = state.hapticsEnabled,
                ),
            )
            if (result.isFailure) {
                _uiState.update {
                    it.copy(isError = true, message = "❌ Lưu cài đặt thất bại: ${result.exceptionOrNull()?.message}")
                }
            }
        }
    }
}
