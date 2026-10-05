package com.youreyes.app.command

import org.json.JSONArray
import org.json.JSONObject

/** The 13 actions the server can push; must match `server/src/app/actions.py`. */
enum class ActionType(val value: String) {
    RIDE_QUOTE("ride_quote"),
    RIDE_CONFIRM("ride_confirm"),
    MUSIC_PLAY("music_play"),
    MUSIC_STOP("music_stop"),
    MUSIC_VOLUME("music_volume"),
    NAVIGATION_START("navigation_start"),
    NAVIGATION_STOP("navigation_stop"),
    EMERGENCY_CALL("emergency_call"),
    CONTACT_CALL("contact_call"),
    LOCATION_GET("location_get"),
    CAPABILITIES_GET("capabilities_get"),
    CALL_ANSWER("call_answer"),
    CALL_REJECT("call_reject");

    companion object {
        fun fromValue(value: String): ActionType? = entries.find { it.value == value }
    }
}

enum class ExecutionState(val value: String) {
    SUCCEEDED("succeeded"),
    FAILED("failed");

    companion object {
        fun fromValue(value: String): ExecutionState? = entries.find { it.value == value }
    }
}

/** A handler failure. `details` may nest maps and lists (e.g. contact candidates). */
data class ReportErrorPayload(
    val code: String,
    val message: String,
    val details: Map<String, Any?> = emptyMap(),
) {
    fun toJson(): JSONObject = JSONObject()
        .put("code", code)
        .put("message", message)
        .put("details", JSONObject(details))

    companion object {
        fun fromJson(json: JSONObject) = ReportErrorPayload(
            code = json.optString("code"),
            message = json.optString("message"),
            details = json.optJSONObject("details")?.let(::jsonToMap).orEmpty(),
        )
    }
}

/** Body of `POST /api/v1/device/report`. */
data class DeviceReportPayload(
    val userId: String,
    val deviceId: String,
    val requestId: String,
    val action: String,
    val executionState: ExecutionState,
    val result: Map<String, Any?>? = null,
    val error: ReportErrorPayload? = null,
    val timestamp: String,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("user_id", userId)
        put("device_id", deviceId)
        put("request_id", requestId)
        put("action", action)
        put("execution_state", executionState.value)
        put("timestamp", timestamp)
        result?.let { put("result", JSONObject(it)) }
        error?.let { put("error", it.toJson()) }
    }
}

/** One received command as stored on the phone (doubles as the activity log). */
data class CommandRecord(
    val id: Long = 0,
    val requestId: String,
    val userId: String,
    val deviceId: String,
    val action: String,
    val paramsJson: String,
    val receivedAt: Long,
    val status: String,
    val resultJson: String? = null,
    val errorJson: String? = null,
)

/** A report waiting to reach the server; retried until sent or abandoned. */
data class PendingReportRecord(
    val requestId: String,
    val userId: String,
    val deviceId: String,
    val action: String,
    val executionState: String,
    val resultJson: String? = null,
    val errorJson: String? = null,
    val status: String = ReportStatus.PENDING,
    val attempts: Int = 0,
    val executedAt: Long = System.currentTimeMillis(),
)

object ReportStatus {
    const val PENDING = "PENDING"
    const val SENT = "SENT"
    const val FAILED = "FAILED"
    const val ABANDONED = "ABANDONED"
}

/** Converts a JSON object into plain Kotlin maps and lists, recursively. */
fun jsonToMap(json: JSONObject): Map<String, Any?> =
    json.keys().asSequence().associateWith { key -> jsonValue(json.opt(key)) }

private fun jsonValue(value: Any?): Any? = when (value) {
    JSONObject.NULL, null -> null
    is JSONObject -> jsonToMap(value)
    is JSONArray -> (0 until value.length()).map { jsonValue(value.opt(it)) }
    else -> value
}
