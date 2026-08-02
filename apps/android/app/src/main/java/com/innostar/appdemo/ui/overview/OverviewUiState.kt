package com.innostar.appdemo.ui.overview

data class OverviewUiState(
    val serverConfigured: Boolean = false,
    val deviceRegistered: Boolean = false,
    val deliveryMode: String = "fake",
) {
    val isReady: Boolean
        get() = serverConfigured && deviceRegistered
}
