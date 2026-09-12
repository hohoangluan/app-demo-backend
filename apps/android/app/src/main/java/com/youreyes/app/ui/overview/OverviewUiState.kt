package com.youreyes.app.ui.overview

import com.youreyes.app.model.CommandRecord
import com.youreyes.app.model.PendingReportRecord

data class OverviewUiState(
    val serverUrl: String = OverviewViewModel.DEFAULT_SERVER_URL,
    val bearerToken: String = OverviewViewModel.DEFAULT_DEVICE_BEARER_TOKEN,
    val userId: String = "user-1",
    val deviceId: String = "device-1",
    val emergencyContact: String = "",
    val pushToken: String = "push-token-demo-123",
    val isLoading: Boolean = false,
    val isRegistered: Boolean = false,
    val registrationMessage: String = "Chưa đăng ký",
    val commands: List<CommandRecord> = emptyList(),
    val pendingReports: List<PendingReportRecord> = emptyList()
) {
    val serverConfigured: Boolean
        get() = serverUrl.isNotBlank()

    val deviceRegistered: Boolean
        get() = isRegistered

    val deliveryMode: String
        get() = "HTTP / SQLite"

    val isReady: Boolean
        get() = serverConfigured && deviceRegistered
}
