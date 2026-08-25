package com.youreyes.app.dispatcher

import com.youreyes.app.model.ExecutionState
import com.youreyes.app.model.PendingReportRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 🔴 Khối được test ở đây vá một hàng đợi CHỈ CÓ NGƯỜI GHI.
 *
 * `CommandDispatcher` lưu mỗi kết quả vào `pending_reports` rồi thử gửi đúng
 * MỘT lần. Hỏng lần đó thì bản ghi nằm lại `FAILED`, và trước 2026-08-25
 * `getPendingReports()` **không có một lời gọi nào** trong toàn bộ app.
 *
 * Hậu quả đo được trên server kính cùng ngày: mọi chức năng có bàn giao việc
 * đều ra `action ≈ 2000 ms` — đúng bằng `VISIONCARE_FAST_ACTION_SECONDS`. Tức
 * app nhận `202 Accepted` rồi im, và câu "tôi sẽ cập nhật khi có kết quả" của
 * kính không bao giờ được giữ.
 *
 * Test đơn vị ở module này chỉ có JUnit + org.json (không Robolectric), nên
 * phần chạm SQLite/Context không kiểm được ở đây. Phần dựng lại payload thì
 * thuần — và nó là chỗ dễ sai âm thầm nhất.
 */
class PendingReportFlusherTest {

    private fun record(
        executionState: String = "succeeded",
        resultJson: String? = """{"call_state":"dialing"}""",
        errorJson: String? = null,
        lastAttemptAt: Long = 1_600_000_000_000,
    ) = PendingReportRecord(
        requestId = "3f0f6d4e-0000-4000-8000-000000000001",
        userId = "user-1",
        deviceId = "device-1",
        action = "contact_call",
        executionState = executionState,
        resultJson = resultJson,
        errorJson = errorJson,
        reportPayloadHash = "hash",
        attempts = 1,
        lastAttemptAt = lastAttemptAt,
    )

    @Test
    fun `rebuilds a succeeded report from the row`() {
        val payload = PendingReportFlusher.rebuildPayload(record())

        assertNotNull(payload)
        assertEquals(ExecutionState.SUCCEEDED, payload!!.executionState)
        assertEquals("contact_call", payload.action)
        assertEquals("dialing", payload.result?.get("call_state"))
        assertNull(payload.error)
    }

    @Test
    fun `rebuilds a failed report with its error`() {
        val payload = PendingReportFlusher.rebuildPayload(
            record(
                executionState = "failed",
                resultJson = null,
                errorJson = """{"code":"CONTACT_NOT_FOUND","message":"Không thấy"}""",
            )
        )

        assertNotNull(payload)
        assertEquals(ExecutionState.FAILED, payload!!.executionState)
        assertEquals("CONTACT_NOT_FOUND", payload.error?.code)
        assertNull(payload.result)
    }

    @Test
    fun `keeps the original timestamp instead of stamping it now`() {
        // 🔴 Máy chủ dùng `timestamp` để biết việc xảy ra KHI NÀO. Đóng dấu lại
        // bằng giờ hiện tại sẽ biến một báo cáo trễ nửa tiếng thành một báo cáo
        // vừa xong — và mọi phép đo độ trễ dựa trên nó thành vô nghĩa.
        val payload = PendingReportFlusher.rebuildPayload(
            record(lastAttemptAt = 1_600_000_000_000)
        )

        assertEquals("2020-09-13T12:26:40Z", payload?.timestamp)
    }

    @Test
    fun `an unknown execution state is refused rather than guessed`() {
        // Thử lại một bản ghi hỏng bao nhiêu lần cũng thế. Đoán bừa
        // `SUCCEEDED` thì còn tệ hơn: kính sẽ báo "đã gọi xong" cho một cuộc
        // gọi chưa hề nối.
        assertNull(PendingReportFlusher.rebuildPayload(record(executionState = "khong-biet")))
    }

    @Test
    fun `a corrupt result json degrades to no result instead of throwing`() {
        val payload = PendingReportFlusher.rebuildPayload(record(resultJson = "{khong-phai-json"))

        assertNotNull(payload)
        assertNull(payload!!.result)
    }
}
