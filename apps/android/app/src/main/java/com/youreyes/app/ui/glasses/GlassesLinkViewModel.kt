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
        pairedDeviceId = prefs.getString(KEY_PAIRED_DEVICE_ID, "") ?: "",
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

    /** Auto-uppercase as the user types — every real device_id seen so far (glasses serials, Server Kính's own "glasses-123" demo default) reads more consistently normalized than mixed-case free text. */
    fun onGlassesDeviceIdChange(id: String) = _uiState.update { it.copy(glassesDeviceId = id.uppercase()) }

    fun link() {
        val state = _uiState.value
        if (!state.canSubmit) return

        val normalizedDeviceId = state.glassesDeviceId.trim()
        _uiState.update { it.copy(isLoading = true, message = "Đang pairing...") }
        viewModelScope.launch(Dispatchers.IO) {
            val result = DeviceApiClient().linkGlassesDevice(
                baseUrl = state.serverUrl.trimEnd('/'),
                bearerToken = state.bearerToken,
                payload = GlassesLinkPayload(
                    userId = state.userId.trim(),
                    deviceId = normalizedDeviceId,
                ),
            )

            if (result.isSuccess) {
                prefs.edit().putString(KEY_PAIRED_DEVICE_ID, normalizedDeviceId).apply()
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        message = "✅ Đã pairing kính thành công.",
                        pairedDeviceId = normalizedDeviceId,
                    )
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
                // Clear the local record either way: even "unlinked: false" (nothing
                // server-side to clear) means this account is not paired right now,
                // so a stale local pairedDeviceId would be wrong either way.
                prefs.edit().remove(KEY_PAIRED_DEVICE_ID).apply()
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        message = if (unlinked) {
                            "✅ Đã hủy liên kết kính."
                        } else {
                            "Không có kính nào đang liên kết với tài khoản này."
                        },
                        pairedDeviceId = "",
                    )
                }
            } else {
                val err = result.exceptionOrNull()?.message ?: "Unknown error"
                _uiState.update { it.copy(isLoading = false, message = "❌ Lỗi: $err") }
            }
        }
    }

    companion object {
        const val KEY_PAIRED_DEVICE_ID = "glasses_paired_device_id"
    }
}
