package com.youreyes.app.ui.glasses

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.youreyes.app.fcm.FcmPushReceiver
import com.youreyes.app.model.GlassesLinkPayload
import com.youreyes.app.network.DeviceApiClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Pairs a glasses hardware device_id to this account's user_id
 * (POST /api/v1/device/glasses/link) so the External API Client ("kính"
 * server) can route commands to this phone by device_id alone.
 *
 * Reuses the server URL / user_id / Device bearer token already saved by
 * [com.youreyes.app.ui.overview.OverviewViewModel]'s registration flow --
 * the user only ever has to type the glasses device_id here.
 */
class GlassesLinkViewModel(application: Application) : AndroidViewModel(application) {

    private val prefs = application.getSharedPreferences(
        FcmPushReceiver.PREFS_NAME, Context.MODE_PRIVATE
    )

    private val _uiState = MutableStateFlow(loadInitialState())
    val uiState: StateFlow<GlassesLinkUiState> = _uiState.asStateFlow()

    private fun loadInitialState(): GlassesLinkUiState = GlassesLinkUiState(
        serverUrl = prefs.getString(FcmPushReceiver.KEY_BASE_URL, "") ?: "",
        bearerToken = prefs.getString(FcmPushReceiver.KEY_BEARER_TOKEN, "") ?: "",
        userId = prefs.getString(FcmPushReceiver.KEY_USER_ID, "") ?: "",
    )

    /**
     * Re-reads `user_id` from SharedPreferences. Call when this screen is re-entered —
     * see [com.youreyes.app.ui.overview.OverviewViewModel.refreshUserId] for why this
     * ViewModel's own one-time [loadInitialState] can otherwise miss a login that
     * happened on a different tab.
     */
    fun refreshUserId() {
        val saved = prefs.getString(FcmPushReceiver.KEY_USER_ID, null) ?: return
        _uiState.update { if (it.userId != saved) it.copy(userId = saved) else it }
    }

    fun onGlassesDeviceIdChange(id: String) = _uiState.update { it.copy(glassesDeviceId = id) }

    fun link() {
        val state = _uiState.value
        if (!state.canSubmit) return

        _uiState.update { it.copy(isLoading = true, message = "Đang pairing...") }
        viewModelScope.launch(Dispatchers.IO) {
            val result = DeviceApiClient().linkGlassesDevice(
                baseUrl = state.serverUrl.trimEnd('/'),
                bearerToken = state.bearerToken,
                payload = GlassesLinkPayload(
                    userId = state.userId.trim(),
                    deviceId = state.glassesDeviceId.trim(),
                ),
            )

            if (result.isSuccess) {
                _uiState.update {
                    it.copy(isLoading = false, message = "✅ Đã pairing kính thành công.")
                }
            } else {
                val err = result.exceptionOrNull()?.message ?: "Unknown error"
                _uiState.update { it.copy(isLoading = false, message = "❌ Lỗi: $err") }
            }
        }
    }

    fun unlink() {
        val state = _uiState.value
        if (!state.canUnlink) return

        _uiState.update { it.copy(isLoading = true, message = "Đang hủy liên kết...") }
        viewModelScope.launch(Dispatchers.IO) {
            val result = DeviceApiClient().unlinkGlassesDevice(
                baseUrl = state.serverUrl.trimEnd('/'),
                bearerToken = state.bearerToken,
                userId = state.userId.trim(),
            )

            if (result.isSuccess) {
                val unlinked = result.getOrThrow()
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        message = if (unlinked) {
                            "✅ Đã hủy liên kết kính."
                        } else {
                            "Không có kính nào đang liên kết với tài khoản này."
                        },
                    )
                }
            } else {
                val err = result.exceptionOrNull()?.message ?: "Unknown error"
                _uiState.update { it.copy(isLoading = false, message = "❌ Lỗi: $err") }
            }
        }
    }
}
