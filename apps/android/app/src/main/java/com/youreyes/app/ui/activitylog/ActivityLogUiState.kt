package com.youreyes.app.ui.activitylog

import com.youreyes.app.model.ActionType
import com.youreyes.app.model.CommandRecord
import org.json.JSONException
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Outcome of one dispatched command, coarsened from [CommandRecord.status]'s free-text values. */
enum class ActivityLogStatus { PROCESSING, SUCCEEDED, FAILED }

/** One row in the Activity Log screen — a display-ready projection of [CommandRecord]. */
data class ActivityLogRow(
    val requestId: String,
    val actionLabel: String,
    val receivedAtText: String,
    val status: ActivityLogStatus,
    val summary: String,
)

data class ActivityLogUiState(
    val rows: List<ActivityLogRow> = emptyList(),
    val isLoading: Boolean = false,
) {
    val isEmpty: Boolean
        get() = rows.isEmpty() && !isLoading
}

/** Vietnamese label per contract action (project_context.md §6); unknown actions keep their raw name rather than being hidden. */
private val actionLabels: Map<String, String> = mapOf(
    ActionType.RIDE_QUOTE.value to "🚖 Báo giá đặt xe",
    ActionType.RIDE_CONFIRM.value to "🚖 Xác nhận đặt xe",
    ActionType.MUSIC_PLAY.value to "🎵 Phát nhạc",
    ActionType.MUSIC_STOP.value to "🎵 Dừng nhạc",
    ActionType.MUSIC_VOLUME.value to "🔊 Chỉnh âm lượng",
    ActionType.NAVIGATION_START.value to "🗺️ Bắt đầu điều hướng",
    ActionType.NAVIGATION_STOP.value to "🗺️ Dừng điều hướng",
    ActionType.EMERGENCY_CALL.value to "🆘 Gọi khẩn cấp",
    ActionType.CONTACT_CALL.value to "📞 Gọi liên hệ",
    ActionType.LOCATION_GET.value to "📍 Lấy vị trí hiện tại",
    ActionType.QUOTES_SPEAK.value to "💬 Đọc câu nói",
    ActionType.MEDIA_PLAY.value to "🎵 Phát nhạc (demo)",
    ActionType.CAMERA_CAPTURE.value to "📷 Chụp ảnh",
    ActionType.DISPLAY_SHOW.value to "🖥️ Hiển thị thông báo",
    ActionType.SYSTEM_SETTINGS.value to "⚙️ Mở cài đặt hệ thống",
)

/**
 * Maps [CommandDispatcher][com.youreyes.app.dispatcher.CommandDispatcher]'s free-text
 * `status` column ("PROCESSING", then [com.youreyes.app.model.ExecutionState.value])
 * to a fixed enum. Anything unrecognized is treated as still in flight rather than
 * crashing the screen — this table is read-only display, so an unknown value here is
 * not something the user needs a hard failure over.
 */
private fun parseStatus(status: String): ActivityLogStatus = when (status) {
    "succeeded" -> ActivityLogStatus.SUCCEEDED
    "failed" -> ActivityLogStatus.FAILED
    else -> ActivityLogStatus.PROCESSING
}

/**
 * Compact single-line rendering of a result/error JSON object's top-level fields.
 * Result shapes differ per action (ride vs music vs navigation...), so this stays
 * generic instead of hand-mapping 15 action-specific summaries. Bounded so one
 * unusually large payload cannot blow out the row's layout.
 */
private fun summarizeJson(json: String?, maxLength: Int = 140): String? {
    if (json.isNullOrBlank()) return null
    return try {
        val obj = JSONObject(json)
        val keys = obj.keys()
        val parts = mutableListOf<String>()
        while (keys.hasNext()) {
            val key = keys.next()
            parts.add("$key: ${obj.get(key)}")
        }
        parts.joinToString(", ").let { if (it.length > maxLength) it.take(maxLength) + "…" else it }
    } catch (_: JSONException) {
        json.take(maxLength)
    }
}

/** Pulls just the human-readable `message` out of an error payload, when present. */
private fun errorMessage(errorJson: String?): String? {
    if (errorJson.isNullOrBlank()) return null
    return try {
        JSONObject(errorJson).optString("message").ifBlank { null }
    } catch (_: JSONException) {
        null
    }
}

/**
 * Pure projection used by [ActivityLogViewModel]; kept free of Android framework
 * calls so it is unit-testable on the plain JVM (see `ActivityLogUiStateTest`).
 */
fun CommandRecord.toActivityLogRow(): ActivityLogRow {
    val status = parseStatus(this.status)
    val summary = when (status) {
        ActivityLogStatus.FAILED ->
            errorMessage(errorJson) ?: summarizeJson(errorJson) ?: "Không rõ nguyên nhân lỗi"
        ActivityLogStatus.SUCCEEDED ->
            summarizeJson(resultJson) ?: "Hoàn tất, không có dữ liệu kết quả"
        ActivityLogStatus.PROCESSING ->
            "Đang chờ kết quả từ thiết bị..."
    }
    // Local instance (not a shared top-level val): SimpleDateFormat is not thread-safe,
    // and this can run per-row from a background dispatcher (see CommandDispatcher's
    // own local `sdf` for the same reason).
    val displayFormat = SimpleDateFormat("HH:mm dd/MM/yyyy", Locale.forLanguageTag("vi-VN"))
    return ActivityLogRow(
        requestId = requestId,
        actionLabel = actionLabels[action] ?: action,
        receivedAtText = displayFormat.format(Date(receivedAt)),
        status = status,
        summary = summary,
    )
}
