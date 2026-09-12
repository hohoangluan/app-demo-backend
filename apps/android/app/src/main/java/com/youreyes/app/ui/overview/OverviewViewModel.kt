package com.youreyes.app.ui.overview

import android.app.Application
import android.content.Context
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.google.firebase.messaging.FirebaseMessaging
import com.youreyes.app.fcm.FcmPushReceiver
import com.youreyes.app.model.DeviceRegisterPayload
import com.youreyes.app.network.DeviceApiClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

class OverviewViewModel(application: Application) : AndroidViewModel(application) {

    private val prefs = application.getSharedPreferences(
        FcmPushReceiver.PREFS_NAME, Context.MODE_PRIVATE
    )

    private val _uiState = MutableStateFlow(loadInitialState())
    val uiState: StateFlow<OverviewUiState> = _uiState.asStateFlow()

    private fun loadInitialState(): OverviewUiState {
        // Provisioned by the backend for this demo build; the user never has to type
        // these in by hand — only user_id/device_id/emergency contact are theirs to set.
        val savedUrl     = prefs.getString(FcmPushReceiver.KEY_BASE_URL, DEFAULT_SERVER_URL) ?: DEFAULT_SERVER_URL
        val savedUserId  = prefs.getString(FcmPushReceiver.KEY_USER_ID, "user-100") ?: "user-100"
        val savedDevice  = prefs.getString(FcmPushReceiver.KEY_DEVICE_ID, "device-100") ?: "device-100"
        val savedToken   = prefs.getString(FcmPushReceiver.KEY_BEARER_TOKEN, DEFAULT_DEVICE_BEARER_TOKEN) ?: DEFAULT_DEVICE_BEARER_TOKEN
        val savedEmergencyContact = prefs.getString(FcmPushReceiver.KEY_EMERGENCY_CONTACT, "") ?: ""
        val registered   = savedUrl.isNotBlank() && savedUserId.isNotBlank()
        return OverviewUiState(
            serverUrl = savedUrl,
            bearerToken = savedToken,
            userId = savedUserId,
            deviceId = savedDevice,
            emergencyContact = savedEmergencyContact,
            isRegistered = registered,
            registrationMessage = if (registered) "Đã đăng ký thành công" else "Chưa đăng ký",
        )
    }

    /**
     * Re-reads `user_id` from SharedPreferences. Call when this screen is re-entered:
     * [com.youreyes.app.ui.auth.AuthViewModel] may have overwritten it with the real
     * logged-in `public_user_id` after this ViewModel's own `loadInitialState()` ran
     * (that constructor call happens once and is cached for the Activity's lifetime,
     * so it would otherwise miss a login that happened on a different tab).
     */
    fun refreshUserId() {
        val saved = prefs.getString(FcmPushReceiver.KEY_USER_ID, null) ?: return
        _uiState.update { if (it.userId != saved) it.copy(userId = saved) else it }
    }

    fun onServerUrlChange(url: String) = _uiState.update { it.copy(serverUrl = url) }
    fun onUserIdChange(id: String)     = _uiState.update { it.copy(userId = id) }
    fun onDeviceIdChange(id: String)   = _uiState.update { it.copy(deviceId = id) }
    fun onBearerTokenChange(t: String) = _uiState.update { it.copy(bearerToken = t) }
    fun onEmergencyContactChange(v: String) = _uiState.update { it.copy(emergencyContact = v) }

    /** Fills user_id/device_id with fresh random-suffixed defaults; still editable afterward. */
    fun generateNewIds() {
        val suffix = (100..999).random()
        _uiState.update { it.copy(userId = "user-$suffix", deviceId = "device-$suffix") }
    }

    fun register() {
        _uiState.update { it.copy(isLoading = true, registrationMessage = "Đang kết nối...") }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                // Get FCM token
                val fcmToken = try {
                    FirebaseMessaging.getInstance().token.await()
                } catch (e: Exception) {
                    Log.w(TAG, "FCM token unavailable: ${e.message}")
                    "push-token-unavailable"
                }

                val state = _uiState.value
                val result = DeviceApiClient().registerDevice(
                    baseUrl = state.serverUrl.trimEnd('/'),
                    bearerToken = state.bearerToken,
                    payload = DeviceRegisterPayload(
                        userId   = state.userId.trim(),
                        deviceId = state.deviceId.trim(),
                        platform = "android",
                        pushToken = fcmToken,
                    ),
                )

                if (result.isSuccess) {
                    // Persist for FcmPushReceiver auto re-register
                    prefs.edit()
                        .putString(FcmPushReceiver.KEY_BASE_URL,      state.serverUrl.trimEnd('/'))
                        .putString(FcmPushReceiver.KEY_USER_ID,       state.userId.trim())
                        .putString(FcmPushReceiver.KEY_DEVICE_ID,     state.deviceId.trim())
                        .putString(FcmPushReceiver.KEY_FCM_TOKEN,     fcmToken)
                        .putString(FcmPushReceiver.KEY_BEARER_TOKEN,  state.bearerToken)
                        .putString(FcmPushReceiver.KEY_EMERGENCY_CONTACT, state.emergencyContact.trim())
                        .apply()

                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            isRegistered = true,
                            pushToken = fcmToken,
                            registrationMessage = "✅ Đăng ký thành công! FCM token đã gửi lên backend.",
                        )
                    }
                } else {
                    val err = result.exceptionOrNull()?.message ?: "Lỗi không rõ nguyên nhân"
                    _uiState.update {
                        it.copy(isLoading = false, registrationMessage = "❌ Lỗi: $err")
                    }
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(isLoading = false, registrationMessage = "❌ Lỗi kết nối: ${e.message}")
                }
            }
        }
    }

    companion object {
        private const val TAG = "OverviewViewModel"

        // Baked-in for this demo build so the end user only ever has to tap "Đăng ký" —
        // matches apps/backend/.env's PUBLIC-facing tunnel + DEVICE_API_TOKEN_HASH raw token.
        const val DEFAULT_SERVER_URL = "https://app.visioncare-host.uk"
        const val DEFAULT_DEVICE_BEARER_TOKEN = "_W7kNZRwdQC9jawhJr7ji_TJ6JzUI5igkHQrE6oAQKE"
    }
}
