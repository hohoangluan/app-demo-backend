package com.youreyes.app.ui.glasses

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.youreyes.app.BuildConfig
import com.youreyes.app.core.AppConfig
import com.youreyes.app.network.GlassesLinkPayload
import com.youreyes.app.network.DeviceApiClient
import com.youreyes.app.network.GlassesBleProvisioner
import com.youreyes.app.network.GlassesServerClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Everything the user does with a pair of glasses, on one screen:
 *
 *  1. **Pair** a glasses serial to this account (`POST /api/v1/device/glasses/link`
 *     on the App Communication Server) so commands route to this phone.
 *  2. **See** which Wi-Fi the glasses are registered on, which firmware they run,
 *     and whether the server can reach them at all — read from Server Kính's
 *     `GET /device/status`.
 *  3. **Change** their Wi-Fi, either through the server's downlink or, when the
 *     glasses have already lost Wi-Fi, by talking to the board directly.
 *  4. **Update** their firmware over the air.
 *
 * Steps 2-4 talk to **Server Kính** (port 8000), not the server this screen's
 * pairing call goes to (8001). Two base URLs, two tokens, deliberately kept in
 * separate fields — see [GlassesLinkUiState].
 */
class GlassesLinkViewModel(application: Application) : AndroidViewModel(application) {

    private val prefs = AppConfig.prefs(application)
    private val glassesServer = GlassesServerClient()
    private val ble = GlassesBleProvisioner(application)

    private val _uiState = MutableStateFlow(loadInitialState())
    val uiState: StateFlow<GlassesLinkUiState> = _uiState.asStateFlow()

    private fun loadInitialState(): GlassesLinkUiState = GlassesLinkUiState(
        serverUrl = prefs.getString(AppConfig.KEY_BASE_URL, "") ?: "",
        bearerToken = prefs.getString(AppConfig.KEY_BEARER_TOKEN, "") ?: "",
        userId = prefs.getString(AppConfig.KEY_USER_ID, "") ?: "",
        pairedDeviceId = prefs.getString(AppConfig.KEY_PAIRED_GLASSES_ID, "") ?: "",
        // 🔴 Không còn là ô nhập. Người dùng chốt 2026-08-25: app chỉ phơi ra
        // ĐỔI WI-FI và CẬP NHẬT FIRMWARE. Một địa chỉ máy chủ và một chuỗi bí
        // mật không phải thứ người khiếm thị có lý do để gõ.
        //
        // Vẫn cho phép đè bằng `local.properties` lúc dựng (xem build.gradle.kts).
        glassesServerUrl = BuildConfig.GLASSES_SERVER_URL,
        glassesServerToken = BuildConfig.GLASSES_SERVER_TOKEN,
    )

    /**
     * Re-reads `user_id` from SharedPreferences. Call when this screen is re-entered —
     * see [com.youreyes.app.ui.overview.OverviewViewModel.refreshUserId] for why this
     * ViewModel's own one-time [loadInitialState] can otherwise miss a login that
     * happened on a different tab.
     */
    fun refreshUserId() {
        val saved = prefs.getString(AppConfig.KEY_USER_ID, null) ?: return
        _uiState.update { if (it.userId != saved) it.copy(userId = saved) else it }
    }

    /**
     * Auto-uppercase as the user types. Every device_id seen so far reads more
     * consistently normalized than mixed-case free text.
     *
     * The serial printed on the frame is a bare number ("01"); the glasses send
     * `glasses-01`. [normalizedDeviceId] bridges the two so the user never has
     * to know that.
     */
    fun onGlassesDeviceIdChange(id: String) = _uiState.update { it.copy(glassesDeviceId = id.uppercase()) }

    fun onWifiSsidChange(value: String) = _uiState.update { it.copy(wifiSsid = value) }
    fun onWifiPasswordChange(value: String) = _uiState.update { it.copy(wifiPassword = value) }

    fun link() {
        val state = _uiState.value
        if (!state.canSubmit) return

        val normalizedDeviceId = normalizedDeviceId(state.glassesDeviceId)
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
                prefs.edit().putString(AppConfig.KEY_PAIRED_GLASSES_ID, normalizedDeviceId).apply()
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        message = "✅ Đã pairing kính thành công.",
                        pairedDeviceId = normalizedDeviceId,
                    )
                }
                refreshStatus()
            } else {
                val err = result.exceptionOrNull()?.message ?: "Lỗi không rõ nguyên nhân"
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
                prefs.edit().remove(AppConfig.KEY_PAIRED_GLASSES_ID).apply()
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        message = if (unlinked) {
                            "✅ Đã hủy liên kết kính."
                        } else {
                            "Không có kính nào đang liên kết với tài khoản này."
                        },
                        pairedDeviceId = "",
                        status = null,
                    )
                }
            } else {
                val err = result.exceptionOrNull()?.message ?: "Lỗi không rõ nguyên nhân"
                _uiState.update { it.copy(isLoading = false, message = "❌ Lỗi: $err") }
            }
        }
    }

    /** Pull the live picture of the glasses from Server Kính. */
    fun refreshStatus() {
        val state = _uiState.value
        if (!state.canRefreshStatus) return

        _uiState.update { it.copy(isRefreshing = true, statusError = "") }
        viewModelScope.launch(Dispatchers.IO) {
            val result = glassesServer.deviceStatus(
                baseUrl = state.glassesServerUrl,
                token = state.glassesServerToken,
                deviceId = normalizedDeviceId(state.effectiveDeviceId),
            )
            result.fold(
                onSuccess = { status ->
                    _uiState.update {
                        it.copy(
                            isRefreshing = false,
                            status = status,
                            statusError = "",
                            // Pre-fill the Wi-Fi box with the network the glasses
                            // are already on: the common case is retyping the same
                            // name after a password change, not a brand new network.
                            wifiSsid = it.wifiSsid.ifBlank { status.wifiSsid.orEmpty() },
                        )
                    }
                },
                onFailure = { error ->
                    _uiState.update {
                        it.copy(
                            isRefreshing = false,
                            statusError = error.message ?: "Không đọc được trạng thái kính",
                        )
                    }
                },
            )
        }
    }

    /** Ask the glasses (over the LAN) which networks they can actually see. */
    fun scanNetworks() {
        // Quét chỉ chạy được khi điện thoại đang nối vào SoftAP của kính —
        // đó là lúc kính mất mạng và người ta cần khai báo mạng mới. Địa chỉ
        // cố định, không còn là ô nhập.
        val host = GlassesLinkUiState.DEFAULT_SETUP_HOST
        if (_uiState.value.isScanning) return

        _uiState.update { it.copy(isScanning = true, message = "Đang hỏi kính xem có mạng nào...") }
        viewModelScope.launch(Dispatchers.IO) {
            glassesServer.localScan(host).fold(
                onSuccess = { networks ->
                    _uiState.update {
                        it.copy(
                            isScanning = false,
                            visibleNetworks = networks,
                            message = if (networks.isEmpty()) {
                                "Kính không thấy mạng 2.4 GHz nào."
                            } else {
                                "Kính thấy ${networks.size} mạng."
                            },
                        )
                    }
                },
                onFailure = { error ->
                    _uiState.update {
                        it.copy(
                            isScanning = false,
                            message = "❌ Không nối được tới kính ở $host: ${error.message}",
                        )
                    }
                },
            )
        }
    }

    fun sendWifi() {
        val state = _uiState.value
        if (!state.canSendWifi) return

        _uiState.update { it.copy(isLoading = true, message = "Đang gửi cấu hình mạng...") }
        viewModelScope.launch(Dispatchers.IO) {
            // 🔴 TỰ chọn đường, không hỏi người dùng.
            //
            // Trước đây màn hình có một cặp nút "Qua máy chủ / Nối thẳng tới
            // kính" cộng một ô địa chỉ IP. Người dùng chốt bỏ, và bỏ được vì
            // điều kiện chọn đường là thứ MÁY biết chắc còn người thì phải đoán:
            //
            //   kính còn giữ kênh /events  -> đi qua máy chủ (kính ở đâu cũng tới)
            //   không                      -> kính đã mất mạng, đường duy nhất
            //                                 còn lại là SoftAP "VisionCare-Setup"
            //
            // 🔴 KHÔNG bỏ nhánh cục bộ đi. Đó là đường cứu hộ DUY NHẤT khi kính
            // mất mạng, và bỏ nó là lặp lại đúng lỗi `wifiProvisionClear()` bên
            // firmware: một đường cứu hộ được ghi trong tài liệu mà không tồn
            // tại trong mã.
            //
            // 🔴 ĐỔI 2026-08-26: BA đường, không phải hai. BLE chen vào GIỮA.
            //
            //   kính đang poll /downlink  -> qua máy chủ (kính ở đâu cũng tới)
            //   không, nhưng thấy BLE     -> BLE  (điện thoại GIỮ NGUYÊN mạng)
            //   không thấy BLE            -> SoftAP "VisionCare-Setup"
            //
            // Vì sao BLE đứng trên SoftAP: đường SoftAP bắt người dùng RỜI mạng
            // của họ để nối vào một mạng lạ không Internet, rồi nhớ quay lại.
            // Với người khiếm thị, "vào Cài đặt → Wi-Fi → chọn mạng lạ" là chỗ
            // bỏ cuộc. BLE không đụng tới mạng của điện thoại.
            //
            // Vì sao BLE KHÔNG đứng trên đường máy chủ: kính chỉ quảng bá BLE
            // khi nó KHÔNG có mạng, nên khi đường máy chủ dùng được thì BLE
            // chắc chắn không có gì để thấy — thử nó trước chỉ tốn 40 giây quét.
            //
            // 🔴 KHÔNG bỏ nhánh SoftAP đi. Nó là lưới cuối khi Bluetooth tắt,
            // máy không có BLE, hoặc người dùng từ chối quyền — và bỏ nó là lặp
            // lại đúng lỗi cũ: một đường cứu hộ có trong tài liệu mà không có
            // trong mã.
            val viaServer = state.status?.downlinkOpen == true
            val result = if (viaServer) {
                glassesServer.setWifi(
                    baseUrl = state.glassesServerUrl,
                    token = state.glassesServerToken,
                    deviceId = normalizedDeviceId(state.effectiveDeviceId),
                    ssid = state.wifiSsid.trim(),
                    password = state.wifiPassword,
                )
            } else {
                sendWifiOffline(state.wifiSsid.trim(), state.wifiPassword)
            }

            result.fold(
                onSuccess = {
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            // Say what happens next, not just that it worked. The
                            // glasses go silent for ~20 s while they reboot onto
                            // the new network, and silence with no warning reads
                            // exactly like a device that has just died.
                            message = "✅ Đã gửi. Kính sẽ khởi động lại và vào mạng " +
                                "\"${state.wifiSsid.trim()}\" sau khoảng 20 giây.",
                            // Never keep the password in memory longer than the
                            // request that needed it.
                            wifiPassword = "",
                        )
                    }
                },
                onFailure = { error ->
                    _uiState.update {
                        it.copy(isLoading = false, message = "❌ ${error.message}")
                    }
                },
            )
        }
    }

    /**
     * Kính không còn giữ kênh xuống: thử BLE trước, rơi về SoftAP nếu không được.
     *
     * Trả về `Result` để chỗ gọi xử lý chung với đường máy chủ. Câu lỗi ở đây
     * cố ý nói NGƯỜI DÙNG PHẢI LÀM GÌ, không mô tả lỗi kỹ thuật — màn hình này
     * được đọc lên bằng TalkBack.
     */
    private suspend fun sendWifiOffline(ssid: String, password: String): Result<Unit> {
        _uiState.update { it.copy(message = "Đang tìm kính qua Bluetooth...") }

        return when (val r = ble.provision(ssid, password)) {
            is GlassesBleProvisioner.Result.Saved -> Result.success(Unit)

            // 🔴 Ba ca dưới đây đều RƠI VỀ SoftAP chứ không báo lỗi. Người dùng
            // chỉ cần mạng được đổi; đường nào làm được thì đi đường đó.
            is GlassesBleProvisioner.Result.NotFound,
            is GlassesBleProvisioner.Result.Unavailable,
            is GlassesBleProvisioner.Result.MissingPermissions -> {
                _uiState.update {
                    it.copy(message = "Không thấy kính qua Bluetooth — thử qua mạng cài đặt...")
                }
                glassesServer.localSetWifi(
                    GlassesLinkUiState.DEFAULT_SETUP_HOST, ssid, password
                )
            }

            // Ca này thì KHÔNG rơi về: BLE đã nối được và đã ghi, nên kính rất
            // có thể ĐÃ nhận và đang khởi động lại. Thử tiếp qua SoftAP lúc này
            // là gửi lần thứ hai vào một chiếc kính đang reboot — và câu báo sẽ
            // nói dối theo cả hai chiều. Nói đúng thứ ta biết.
            is GlassesBleProvisioner.Result.Failed -> Result.failure(Exception(r.reason))
        }
    }

    fun updateFirmware() {
        val state = _uiState.value
        if (!state.canUpdateFirmware) return

        _uiState.update { it.copy(isUpdatingFirmware = true, message = "Đang báo kính cập nhật...") }
        viewModelScope.launch(Dispatchers.IO) {
            glassesServer.requestFirmwareUpdate(
                baseUrl = state.glassesServerUrl,
                token = state.glassesServerToken,
                deviceId = normalizedDeviceId(state.effectiveDeviceId),
            ).fold(
                onSuccess = { version ->
                    _uiState.update {
                        it.copy(
                            isUpdatingFirmware = false,
                            message = "✅ Kính đang tải bản $version. Đừng tắt kính " +
                                "trong khoảng 1-2 phút tới.",
                        )
                    }
                },
                onFailure = { error ->
                    _uiState.update {
                        it.copy(isUpdatingFirmware = false, message = "❌ ${error.message}")
                    }
                },
            )
        }
    }

    /**
     * Turn whatever the user typed into the `device_id` the glasses actually send.
     *
     * The serial laser-etched on the frame is a bare number ("01"), but the
     * glasses identify themselves as `glasses-01` on every request — that prefix
     * is fixed in `src/net/device_identity.h`. Making the user type the prefix
     * would be making them memorize an implementation detail; leaving it off
     * would key every server call to a device that does not exist.
     */
    private fun normalizedDeviceId(raw: String): String {
        // Lowercased on purpose, even though the text field uppercases as the
        // user types: the firmware builds this string itself as
        // `"glasses-" + serial` and never uppercases it. Sending "GLASSES-01"
        // would key the request to a device the server has never seen, and the
        // failure would look like "the glasses are offline".
        val trimmed = raw.trim().trimStart('#').lowercase()
        if (trimmed.isEmpty()) return trimmed
        return if (trimmed.startsWith(DEVICE_ID_PREFIX)) trimmed else DEVICE_ID_PREFIX + trimmed
    }

    companion object {
        /** Server Kính's public address (Cloudflare tunnel), see `app_config.h`. */
        const val DEFAULT_GLASSES_SERVER_URL = "https://api.visioncare-host.uk"

        /** Fixed by the firmware: `device_id` is `"glasses-" + serial`. */
        private const val DEVICE_ID_PREFIX = "glasses-"
    }
}
