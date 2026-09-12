package com.youreyes.app.ui.glasses

import com.youreyes.app.network.GlassesStatus
import com.youreyes.app.network.VisibleNetwork

data class GlassesLinkUiState(
    val serverUrl: String = "",
    val bearerToken: String = "",
    val userId: String = "",
    val glassesDeviceId: String = "",
    val isLoading: Boolean = false,
    val message: String = "",
    /**
     * Serial of the glasses this account last successfully linked, remembered locally
     * — the backend exposes no "current pairing" read endpoint (only `/link` and
     * `/unlink`, both write-only), so this is the app's own best record, not a live
     * server read. It can go stale if the pairing was changed from elsewhere (another
     * install, or the demo `test_live_server_calls.py` script pairing a different
     * account); [canSubmit]/[canUnlink] still always hit the server rather than trust it.
     */
    val pairedDeviceId: String = "",

    // ── Server Kính: live glasses state ───────────────────────────────────
    /**
     * Base URL and token of **Server Kính** (port 8000 / the Cloudflare tunnel).
     * Deliberately separate fields from [serverUrl]/[bearerToken], which point at
     * the App Communication Server on 8001 — they are two different machines and
     * reusing one pair for both was the first bug this screen grew.
     */
    val glassesServerUrl: String = "",
    val glassesServerToken: String = "",
    /** Live read from `GET /device/status`; null until the first successful refresh. */
    val status: GlassesStatus? = null,
    val statusError: String = "",
    val isRefreshing: Boolean = false,

    // ── Wi-Fi provisioning ────────────────────────────────────────────────
    val wifiSsid: String = "",
    val wifiPassword: String = "",
    /** Networks the glasses themselves can see (`GET /wifi/scan` on the board). */
    val visibleNetworks: List<VisibleNetwork> = emptyList(),
    val isScanning: Boolean = false,

    val isUpdatingFirmware: Boolean = false,
) {
    val isPaired: Boolean
        get() = pairedDeviceId.isNotBlank()

    val canSubmit: Boolean
        get() = !isLoading &&
            serverUrl.isNotBlank() &&
            bearerToken.isNotBlank() &&
            userId.isNotBlank() &&
            glassesDeviceId.isNotBlank()

    /** Unlike [canSubmit], unlink doesn't need a glasses serial — it just clears whatever pairing is currently active for [userId]. */
    val canUnlink: Boolean
        get() = !isLoading &&
            serverUrl.isNotBlank() &&
            bearerToken.isNotBlank() &&
            userId.isNotBlank()

    /** The glasses id every Server Kính call is keyed by: whatever is typed, else the remembered pairing. */
    val effectiveDeviceId: String
        get() = glassesDeviceId.ifBlank { pairedDeviceId }

    val canRefreshStatus: Boolean
        get() = !isRefreshing &&
            glassesServerUrl.isNotBlank() &&
            glassesServerToken.isNotBlank() &&
            effectiveDeviceId.isNotBlank()

    /**
     * Remote Wi-Fi push needs the glasses to be holding the SSE channel. Offering
     * the button when they are not is worse than hiding it: the request would
     * 409, and the user would read that as "the app is broken" rather than
     * "the glasses cannot hear me, use the hotspot".
     */
    /**
     * 🔴 Không còn điều kiện "kính phải đang giữ kênh SSE".
     *
     * Điều kiện đó đúng cho đường qua máy chủ, nhưng nó ẩn mất nút ở ĐÚNG ca
     * người ta cần nó nhất: kính vừa mất mạng, kênh SSE đương nhiên đóng, và
     * việc phải làm là khai mạng mới qua SoftAP. Giờ ViewModel tự chọn đường
     * (`sendWifi()`), nên nút chỉ cần biết đã có tên mạng hay chưa.
     */
    val canSendWifi: Boolean
        get() = !isLoading && wifiSsid.isNotBlank()

    val canUpdateFirmware: Boolean
        get() = !isUpdatingFirmware &&
            canRefreshStatus &&
            status?.updateAvailable == true &&
            status.downlinkOpen

    companion object {
        /** The glasses' own address while running the `VisionCare-Setup` SoftAP. */
        const val DEFAULT_SETUP_HOST = "192.168.4.1"
    }
}
