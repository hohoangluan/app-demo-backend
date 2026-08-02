package com.youreyes.app.dispatcher

import com.youreyes.app.actions.ActionExecutionResult
import com.youreyes.app.actions.ActionRegistry
import com.youreyes.app.data.AppDatabaseHelper
import com.youreyes.app.model.CommandRecord
import com.youreyes.app.model.DeviceReportPayload
import com.youreyes.app.model.ExecutionState
import com.youreyes.app.model.PendingReportRecord
import com.youreyes.app.model.ReportErrorPayload
import com.youreyes.app.network.DeviceApiClient
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

sealed class DispatchResult {
    data class DuplicateIgnored(val requestId: String) : DispatchResult()
    data class Executed(
        val requestId: String,
        val executionState: ExecutionState,
        val reportSent: Boolean
    ) : DispatchResult()
}

class CommandDispatcher(
    private val dbHelper: AppDatabaseHelper,
    private val apiClient: DeviceApiClient = DeviceApiClient(),
    private val actionRegistry: ActionRegistry = ActionRegistry(),
    private val context: android.content.Context? = null
) {

    fun processPushCommand(
        requestId: String,
        userId: String,
        deviceId: String,
        action: String,
        paramsJson: String,
        baseUrl: String? = null,
        bearerToken: String? = null
    ): DispatchResult {
        // 1. Deduplication check
        if (dbHelper.isDuplicateRequestId(requestId)) {
            return DispatchResult.DuplicateIgnored(requestId)
        }

        // 2. Persist command as RECEIVED
        val now = System.currentTimeMillis()
        val commandRecord = CommandRecord(
            requestId = requestId,
            userId = userId,
            deviceId = deviceId,
            action = action,
            paramsJson = paramsJson,
            receivedAt = now,
            status = "PROCESSING"
        )
        dbHelper.saveCommand(commandRecord)

        // 3. Dispatch to handler
        val handler = actionRegistry.getHandler(action)
        val executionResult = handler.execute(context, paramsJson)


        val (executionState, resultData, errorPayload) = when (executionResult) {
            is ActionExecutionResult.Success -> Triple(ExecutionState.SUCCEEDED, executionResult.resultData, null)
            is ActionExecutionResult.Error -> Triple(ExecutionState.FAILED, null, executionResult.errorPayload)
        }

        val resultJson = resultData?.let { JSONObject(it).toString() }
        val errorJson = errorPayload?.let {
            JSONObject().apply {
                put("code", it.code)
                put("message", it.message)
            }.toString()
        }

        // 4. Update command record in DB
        dbHelper.updateCommandStatus(requestId, executionState.value, resultJson, errorJson)

        // 5. Prepare DeviceReportPayload
        val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
        val timestampStr = sdf.format(Date(now))

        val reportPayload = DeviceReportPayload(
            userId = userId,
            deviceId = deviceId,
            requestId = requestId,
            action = action,
            executionState = executionState,
            result = resultData,
            error = errorPayload,
            timestamp = timestampStr
        )
        val hash = reportPayload.computePayloadHash()

        // 6. Save PendingReport to DB
        val pendingReport = PendingReportRecord(
            requestId = requestId,
            userId = userId,
            deviceId = deviceId,
            action = action,
            executionState = executionState.value,
            resultJson = resultJson,
            errorJson = errorJson,
            reportPayloadHash = hash,
            status = "PENDING"
        )
        dbHelper.savePendingReport(pendingReport)

        // 7. Attempt immediate report delivery if credentials provided
        var reportSent = false
        if (!baseUrl.isNullOrBlank() && !bearerToken.isNullOrBlank()) {
            val sendResult = apiClient.sendReport(baseUrl, bearerToken, reportPayload)
            if (sendResult.isSuccess) {
                reportSent = true
                dbHelper.updateReportStatus(requestId, "SENT", 1)
            } else {
                dbHelper.updateReportStatus(requestId, "FAILED", 1)
            }
        }



        return DispatchResult.Executed(
            requestId = requestId,
            executionState = executionState,
            reportSent = reportSent
        )
    }

    private fun String?.isNull_or_blank(): Boolean = this == null || this.trim().isEmpty()
}
