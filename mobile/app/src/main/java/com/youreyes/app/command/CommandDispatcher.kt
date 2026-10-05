package com.youreyes.app.command

import android.content.Context
import android.util.Log
import com.youreyes.app.actions.ActionExecutionResult
import com.youreyes.app.actions.ActionRegistry
import com.youreyes.app.core.DeviceCredentials
import com.youreyes.app.core.Timestamps
import org.json.JSONObject

/** One command pushed by the server (FCM data message). */
data class PushCommand(
    val requestId: String,
    val userId: String,
    val deviceId: String,
    val action: String,
    val paramsJson: String,
) {
    companion object {
        /** Parses an FCM data map; null when a required field is missing. */
        fun fromData(data: Map<String, String>): PushCommand? {
            return PushCommand(
                requestId = data["request_id"]?.takeIf { it.isNotBlank() } ?: return null,
                userId = data["user_id"]?.takeIf { it.isNotBlank() } ?: return null,
                deviceId = data["device_id"]?.takeIf { it.isNotBlank() } ?: return null,
                action = data["action"]?.takeIf { it.isNotBlank() } ?: return null,
                paramsJson = data["params_json"]?.takeIf { it.isNotBlank() } ?: "{}",
            )
        }
    }
}

/** What the dispatcher and flusher need from local storage ([CommandStore] in the app). */
interface CommandLog {
    fun insertCommandIfNew(record: CommandRecord): Boolean
    fun finishCommand(requestId: String, status: String, resultJson: String?, errorJson: String?)
    fun savePendingReport(record: PendingReportRecord)
    fun updateReportStatus(requestId: String, status: String, attempts: Int)
    fun unsentReports(): List<PendingReportRecord>
}

/** Sends one report; true when the server accepted it. */
fun interface ReportSender {
    fun send(credentials: DeviceCredentials, payload: DeviceReportPayload): Boolean
}

sealed class DispatchResult {
    data class DuplicateIgnored(val requestId: String) : DispatchResult()
    data class Executed(
        val requestId: String,
        val executionState: ExecutionState,
        val reportSent: Boolean,
    ) : DispatchResult()
}

/**
 * Runs one pushed command exactly once and reports its result.
 *
 * 1. Record the `request_id`; a duplicate push stops here without running anything.
 * 2. Run the action's handler.
 * 3. Store the result as a pending report, then try to send it once.
 *    Unsent reports are retried later by [ReportFlusher], never by re-running the handler.
 */
class CommandDispatcher(
    private val store: CommandLog,
    private val registry: ActionRegistry,
    private val sender: ReportSender,
    private val context: Context?,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    fun dispatch(command: PushCommand, credentials: DeviceCredentials?): DispatchResult {
        val receivedAt = clock()
        val isNew = store.insertCommandIfNew(
            CommandRecord(
                requestId = command.requestId,
                userId = command.userId,
                deviceId = command.deviceId,
                action = command.action,
                paramsJson = command.paramsJson,
                receivedAt = receivedAt,
                status = "PROCESSING",
            )
        )
        if (!isNew) return DispatchResult.DuplicateIgnored(command.requestId)

        val outcome = try {
            registry.getHandler(command.action).execute(context, command.paramsJson)
        } catch (exc: Exception) {
            Log.e(TAG, "Handler for ${command.action} threw", exc)
            ActionExecutionResult.Error(
                ReportErrorPayload("EXECUTION_FAILED", "Handler crashed: ${exc.message}")
            )
        }
        val state: ExecutionState
        val resultJson: String?
        val errorJson: String?
        when (outcome) {
            is ActionExecutionResult.Success -> {
                state = ExecutionState.SUCCEEDED
                resultJson = JSONObject(outcome.resultData).toString()
                errorJson = null
            }
            is ActionExecutionResult.Error -> {
                state = ExecutionState.FAILED
                resultJson = null
                errorJson = outcome.errorPayload.toJson().toString()
            }
        }
        store.finishCommand(command.requestId, state.value, resultJson, errorJson)

        val report = PendingReportRecord(
            requestId = command.requestId,
            userId = command.userId,
            deviceId = command.deviceId,
            action = command.action,
            executionState = state.value,
            resultJson = resultJson,
            errorJson = errorJson,
            executedAt = clock(),
        )
        store.savePendingReport(report)

        val sent = credentials != null && sendOnce(store, sender, credentials, report)
        return DispatchResult.Executed(command.requestId, state, sent)
    }

    private companion object {
        const val TAG = "CommandDispatcher"
    }
}

/** Rebuilds the report body from a stored row; null if the row is unusable. */
fun PendingReportRecord.toPayload(): DeviceReportPayload? {
    val state = ExecutionState.fromValue(executionState) ?: return null
    val result = resultJson?.let { runCatching { jsonToMap(JSONObject(it)) }.getOrNull() }
    val error = errorJson?.let { runCatching { ReportErrorPayload.fromJson(JSONObject(it)) }.getOrNull() }
    return DeviceReportPayload(
        userId = userId,
        deviceId = deviceId,
        requestId = requestId,
        action = action,
        executionState = state,
        result = result,
        error = error,
        timestamp = Timestamps.utc(executedAt),
    )
}

/** Sends one stored report and records the attempt; true when the server accepted it. */
internal fun sendOnce(
    store: CommandLog,
    sender: ReportSender,
    credentials: DeviceCredentials,
    record: PendingReportRecord,
): Boolean {
    val payload = record.toPayload() ?: run {
        store.updateReportStatus(record.requestId, ReportStatus.ABANDONED, record.attempts)
        return false
    }
    val attempts = record.attempts + 1
    val sent = runCatching { sender.send(credentials, payload) }.getOrDefault(false)
    store.updateReportStatus(
        record.requestId, if (sent) ReportStatus.SENT else ReportStatus.FAILED, attempts
    )
    return sent
}
