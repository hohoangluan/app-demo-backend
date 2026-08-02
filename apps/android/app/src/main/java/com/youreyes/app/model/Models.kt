package com.youreyes.app.model

import org.json.JSONObject
import java.security.MessageDigest

enum class ActionType(val value: String) {
    MUSIC_VOLUME("music_volume"),
    EMERGENCY_CALL("emergency_call"),
    CONTACT_CALL("contact_call"),
    QUOTES_SPEAK("quotes_speak"),
    MEDIA_PLAY("media_play"),
    NAVIGATION_START("navigation_start"),
    CAMERA_CAPTURE("camera_capture"),
    DISPLAY_SHOW("display_show"),
    SYSTEM_SETTINGS("system_settings");

    companion object {
        fun fromValue(value: String): ActionType? = entries.find { it.value == value }
    }
}

enum class ExecutionState(val value: String) {
    SUCCEEDED("succeeded"),
    FAILED("failed")
}

data class DeviceRegisterPayload(
    val userId: String,
    val deviceId: String,
    val platform: String = "android",
    val pushToken: String
)

data class ReportErrorPayload(
    val code: String,
    val message: String,
    val details: Map<String, String> = emptyMap()
)

data class DeviceReportPayload(
    val userId: String,
    val deviceId: String,
    val requestId: String,
    val action: String,
    val executionState: ExecutionState,
    val result: Map<String, Any>? = null,
    val error: ReportErrorPayload? = null,
    val timestamp: String
) {
    fun computePayloadHash(): String {
        val json = JSONObject()
        json.put("execution_state", executionState.value)
        if (result != null) {
            val resultObj = JSONObject(result)
            json.put("result", resultObj)
        } else {
            json.put("result", JSONObject.NULL)
        }
        if (error != null) {
            val errObj = JSONObject()
            errObj.put("code", error.code)
            errObj.put("message", error.message)
            json.put("error", errObj)
        } else {
            json.put("error", JSONObject.NULL)
        }
        val sortedString = json.toString()
        val md = MessageDigest.getInstance("SHA-256")
        val digest = md.digest(sortedString.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }
}

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
    val errorJson: String? = null
)

data class PendingReportRecord(
    val requestId: String,
    val userId: String,
    val deviceId: String,
    val action: String,
    val executionState: String,
    val resultJson: String? = null,
    val errorJson: String? = null,
    val reportPayloadHash: String,
    val status: String = "PENDING",
    val attempts: Int = 0,
    val lastAttemptAt: Long = System.currentTimeMillis()
)
