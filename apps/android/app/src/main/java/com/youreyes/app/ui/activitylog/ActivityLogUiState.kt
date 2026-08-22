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

/**
 * Vietnamese label per contract action (project_context.md §6); unknown actions keep
 * their raw name rather than being hidden.
 *
 * No emoji. TalkBack reads one aloud by name — "automobile", "loudspeaker" — before
 * the label itself, on every row of a screen that is nothing but rows.
 */
private val actionLabels: Map<String, String> = mapOf(
    ActionType.RIDE_QUOTE.value to "Báo giá đặt xe",
    ActionType.RIDE_CONFIRM.value to "Xác nhận đặt xe",
    ActionType.MUSIC_PLAY.value to "Phát nhạc",
    ActionType.MUSIC_STOP.value to "Dừng nhạc",
    ActionType.MUSIC_VOLUME.value to "Chỉnh âm lượng",
    ActionType.NAVIGATION_START.value to "Bắt đầu điều hướng",
    ActionType.NAVIGATION_STOP.value to "Dừng điều hướng",
    ActionType.EMERGENCY_CALL.value to "Gọi khẩn cấp",
    ActionType.CONTACT_CALL.value to "Gọi liên hệ",
    ActionType.LOCATION_GET.value to "Lấy vị trí hiện tại",
    ActionType.QUOTES_SPEAK.value to "Đọc câu nói",
    ActionType.MEDIA_PLAY.value to "Phát nhạc (demo)",
    ActionType.CAMERA_CAPTURE.value to "Chụp ảnh",
    ActionType.DISPLAY_SHOW.value to "Hiển thị thông báo",
    ActionType.SYSTEM_SETTINGS.value to "Mở cài đặt hệ thống",
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
 * Turns a result payload into a sentence a person would say.
 *
 * This previously printed the JSON object's top-level fields as `key: value`
 * pairs, so the screen showed a user things like
 * `track_id: 4f2a..., playback_state: playing, captured_at: 1755...`. Result
 * shapes do differ per action, but their field names are fixed by
 * `apps/backend/src/app/schemas/service_results.py`, so they can be spoken.
 *
 * Fields that carry no meaning for the person who ran the command — ids,
 * timestamps, retry counters, currency codes — are dropped rather than
 * translated. Three fields is the cap: this is a log row, not a receipt.
 */
private val resultFieldLabels: List<Pair<String, String>> = listOf(
    "address" to "Địa chỉ",
    "destination" to "Điểm đến",
    "contact_name" to "Liên hệ",
    "contact" to "Liên hệ",
    "phone_number" to "Số gọi",
    "title" to "Bài hát",
    "artist" to "Ca sĩ",
    "eta_minutes" to "Dự kiến",
    "price_estimate" to "Giá",
    "amount" to "Số tiền",
    "travel_mode" to "Phương tiện",
    "volume" to "Âm lượng",
    "level" to "Âm lượng",
    "ride_status" to "Chuyến xe",
    "playback_state" to "Nhạc",
    "navigation_state" to "Dẫn đường",
    "call_state" to "Cuộc gọi",
    "emergency_state" to "Khẩn cấp",
    "volume_state" to "Âm lượng",
    "answered" to "Bắt máy",
    "sms_sent" to "Tin nhắn",
)

/** State values arrive as API enums; these are what they are called out loud. */
private val stateWords: Map<String, String> = mapOf(
    "playing" to "đang phát", "paused" to "tạm dừng", "stopped" to "đã dừng",
    "started" to "đã bắt đầu", "navigating" to "đang dẫn đường",
    "ringing" to "đang đổ chuông", "answered" to "đã bắt máy",
    "ended" to "đã kết thúc", "failed" to "thất bại",
    "requested" to "đã gửi yêu cầu", "confirmed" to "đã xác nhận",
    "cancelled" to "đã huỷ", "completed" to "hoàn tất",
    "quoted" to "đã báo giá", "triggered" to "đã kích hoạt",
    "muted" to "đã tắt tiếng", "changed" to "đã thay đổi",
    "true" to "có", "false" to "không",
)

private fun speak(key: String, raw: String): String = when (key) {
    "eta_minutes" -> "$raw phút"
    "volume", "level" -> "$raw%"
    else -> stateWords[raw.lowercase()] ?: raw
}

private fun describeResult(json: String?, maxFields: Int = 3): String? {
    if (json.isNullOrBlank()) return null
    val obj = try {
        JSONObject(json)
    } catch (_: JSONException) {
        return null
    }
    val parts = resultFieldLabels.mapNotNull { (key, label) ->
        val raw = obj.opt(key)?.toString()?.trim()
        if (raw.isNullOrBlank() || raw == "null") null else "$label: ${speak(key, raw)}"
    }
    return parts.take(maxFields).joinToString(" · ").ifBlank { null }
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
            errorMessage(errorJson) ?: "Lệnh không thực hiện được. Thử lại lần nữa."
        ActivityLogStatus.SUCCEEDED ->
            describeResult(resultJson) ?: "Đã thực hiện xong."
        ActivityLogStatus.PROCESSING ->
            "Đang chờ kết quả từ điện thoại."
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
