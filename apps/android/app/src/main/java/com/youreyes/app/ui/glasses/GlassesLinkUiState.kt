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
}
