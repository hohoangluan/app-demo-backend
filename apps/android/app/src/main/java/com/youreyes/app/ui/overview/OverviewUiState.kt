package com.youreyes.app.ui.overview

import com.youreyes.app.model.CommandRecord
import com.youreyes.app.model.PendingReportRecord

data class OverviewUiState(
    val serverUrl: String = "http://10.0.2.2:8000",
    val bearerToken: String = "",
    val userId: String = "user-1",
    val deviceId: String = "device-1",
    val pushToken: String = "push-token-demo-123",
    val isRegistered: Boolean = false,
    val registrationMessage: String = "Not registered",
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
