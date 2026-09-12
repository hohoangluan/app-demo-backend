package com.youreyes.app.dispatcher

import android.content.Context
import android.util.Log
import com.youreyes.app.data.AppDatabaseHelper
import com.youreyes.app.fcm.FcmPushReceiver
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

/**
 * Gửi lại những báo cáo hành động chưa tới được máy chủ.
 *
 * 🔴 Vì sao khối này tồn tại
 * --------------------------
 * `CommandDispatcher` lưu mỗi kết quả vào bảng `pending_reports` rồi thử gửi
 * **đúng một lần**. Hỏng lần đó thì bản ghi nằm lại ở `FAILED` — và trước
 * 2026-08-25 **không một dòng mã nào đọc `getPendingReports()`**. Nó là một
 * hàng đợi chỉ có người ghi.
 *
 * Hậu quả không dừng ở điện thoại. Server kính chờ `ActionResult` để biết việc
 * đã xong; không có nó thì nó chờ hết `VISIONCARE_FAST_ACTION_SECONDS` rồi nói
 * *"Điện thoại vẫn đang thực hiện, tôi sẽ cập nhật khi có kết quả"* — và **lời
 * hứa đó không bao giờ được giữ**. Người khiếm thị không có cách nào biết cuộc
 * gọi đã nối hay chưa.
 *
 * Đo được cùng ngày: mọi chức năng có bàn giao việc đều ra `action ≈ 2000 ms`,
 * đúng bằng cửa sổ chờ — tức app nhận `202 Accepted` rồi im.
 *
 * Chạy khi nào
 * ------------
 * Gọi ở đầu MỖI lần có việc mới (`CommandExecutionService`): đó chính là lúc
 * điện thoại chắc chắn có mạng và có đủ giấy tờ. Rẻ khi hàng đợi rỗng — một
 * lượt đọc SQLite.
 */
object PendingReportFlusher {

    private const val TAG = "PendingReportFlusher"

    /**
     * Bỏ cuộc sau ngần này lần.
     *
     * 🔴 Có hạn, không thử mãi: một báo cáo mà máy chủ **từ chối** (sai chữ ký,
     * operation đã chốt trạng thái khác) sẽ hỏng y hệt ở mọi lần sau, và thử
     * lại vô hạn chỉ đốt pin. Máy chủ vốn tự hết giờ cho việc đó rồi.
     *
     * 8 lần, mỗi lần cách nhau ít nhất một lần bấm nút — đủ để vượt qua một
     * quãng mất sóng bình thường.
     */
    private const val MAX_ATTEMPTS = 8

    private val timestampFormat: SimpleDateFormat
        get() = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }

    /**
     * Thử gửi lại mọi báo cáo còn treo. Trả về số bản gửi được.
     *
     * KHÔNG ném ra ngoài: khối này chạy trên đường một lệnh mới đang tới, và
     * làm hỏng lệnh đó vì một báo cáo cũ là đổi một lỗi nhỏ lấy một lỗi to.
     */
    fun flush(
        context: Context,
        baseUrl: String?,
        bearerToken: String?,
        dbHelper: AppDatabaseHelper = AppDatabaseHelper(context),
        apiClient: DeviceApiClient = DeviceApiClient(),
    ): Int {
        val url = baseUrl?.takeIf { it.isNotBlank() }
            ?: prefs(context).getString(FcmPushReceiver.KEY_BASE_URL, null)
        val token = bearerToken?.takeIf { it.isNotBlank() }
            ?: prefs(context).getString(FcmPushReceiver.KEY_BEARER_TOKEN, null)

        if (url.isNullOrBlank() || token.isNullOrBlank()) {
            // Chưa đăng nhập lần nào. Giữ nguyên hàng đợi — đây KHÔNG phải một
            // lần thử hỏng, nên không cộng `attempts`.
            return 0
        }

        val pending = runCatching { dbHelper.getPendingReports() }.getOrElse {
            Log.w(TAG, "Không đọc được hàng đợi báo cáo", it)
            return 0
        }
        if (pending.isEmpty()) return 0

        Log.i(TAG, "Còn ${pending.size} báo cáo chưa tới máy chủ — thử lại")
        var sent = 0
        for (record in pending) {
            if (record.attempts >= MAX_ATTEMPTS) {
                dbHelper.updateReportStatus(record.requestId, "ABANDONED", record.attempts)
                Log.w(
                    TAG,
                    "Bỏ báo cáo ${record.requestId} sau ${record.attempts} lần — " +
                        "máy chủ vẫn không nhận",
                )
                continue
            }

            val payload = rebuildPayload(record) ?: run {
                // Bản ghi hỏng thì thử lại bao nhiêu lần cũng thế.
                dbHelper.updateReportStatus(record.requestId, "ABANDONED", record.attempts)
                Log.w(TAG, "Bản ghi ${record.requestId} không dựng lại được payload")
                null
            } ?: continue

            val attempts = record.attempts + 1
            val ok = apiClient.sendReport(url, token, payload).getOrDefault(false)
            if (ok) {
                dbHelper.updateReportStatus(record.requestId, "SENT", attempts)
                sent++
            } else {
                dbHelper.updateReportStatus(record.requestId, "FAILED", attempts)
            }
        }
        if (sent > 0) Log.i(TAG, "Đã gửi lại được $sent báo cáo")
        return sent
    }

    private fun prefs(context: Context) = context.getSharedPreferences(
        FcmPushReceiver.PREFS_NAME,
        Context.MODE_PRIVATE,
    )

    /**
     * Dựng lại payload từ bản ghi trong SQLite.
     *
     * 🔴 `timestamp` lấy theo `lastAttemptAt`, KHÔNG phải lúc này. Máy chủ dùng
     * nó để biết việc xảy ra khi nào; đóng dấu lại bằng giờ hiện tại sẽ biến
     * một báo cáo trễ nửa tiếng thành một báo cáo vừa xong.
     */
    internal fun rebuildPayload(record: PendingReportRecord): DeviceReportPayload? = with(record) {
        val state = ExecutionState.entries.firstOrNull { it.value == executionState }
            ?: return null

        val result = resultJson?.let { json ->
            runCatching {
                val obj = JSONObject(json)
                buildMap<String, Any> {
                    obj.keys().forEach { key -> put(key, obj.get(key)) }
                }
            }.getOrNull()
        }

        val error = errorJson?.let { json ->
            runCatching {
                val obj = JSONObject(json)
                ReportErrorPayload(
                    code = obj.optString("code"),
                    message = obj.optString("message"),
                )
            }.getOrNull()
        }

        return DeviceReportPayload(
            userId = userId,
            deviceId = deviceId,
            requestId = requestId,
            action = action,
            executionState = state,
            result = result,
            error = error,
            timestamp = timestampFormat.format(Date(lastAttemptAt)),
        )
    }
}
