package com.youreyes.app.ui.glasses

data class GlassesLinkUiState(
    val serverUrl: String = "",
    val bearerToken: String = "",
    val userId: String = "",
    val glassesDeviceId: String = "",
    val isLoading: Boolean = false,
    val message: String = "",
) {
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
