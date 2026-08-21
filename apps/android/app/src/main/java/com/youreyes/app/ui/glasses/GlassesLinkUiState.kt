package com.youreyes.app.ui.glasses

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
}
