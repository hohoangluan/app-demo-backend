package com.youreyes.app.ui.activitylog

import com.youreyes.app.command.CommandRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ActivityLogUiStateTest {

    private fun record(
        action: String = "music_play",
        status: String = "succeeded",
        resultJson: String? = """{"title":"Test Song","playback_state":"playing"}""",
        errorJson: String? = null,
    ) = CommandRecord(
        id = 1,
        requestId = "req-1",
        userId = "user-1",
        deviceId = "device-1",
        action = action,
        paramsJson = "{}",
        receivedAt = 1_700_000_000_000L,
        status = status,
        resultJson = resultJson,
        errorJson = errorJson,
    )

    @Test
    fun `known action maps to its Vietnamese label`() {
        val row = record(action = "ride_quote").toActivityLogRow()
        assertTrue(row.actionLabel.contains("Báo giá đặt xe"))
    }

    @Test
    fun `unknown action falls back to the raw action string instead of crashing`() {
        val row = record(action = "some_future_action").toActivityLogRow()
        assertEquals("some_future_action", row.actionLabel)
    }

    @Test
    fun `succeeded status maps to SUCCEEDED with a result summary`() {
        val row = record(status = "succeeded").toActivityLogRow()
        assertEquals(ActivityLogStatus.SUCCEEDED, row.status)
        assertTrue(row.summary.contains("Test Song"))
    }

    @Test
    fun `failed status maps to FAILED and prefers the error message`() {
        val row = record(
            status = "failed",
            resultJson = null,
            errorJson = """{"code":"PLAYBACK_FAILED","message":"No audio started"}""",
        ).toActivityLogRow()
        assertEquals(ActivityLogStatus.FAILED, row.status)
        assertEquals("No audio started", row.summary)
    }

    @Test
    fun `unrecognized status string is treated as processing rather than crashing`() {
        val row = record(status = "some_future_status").toActivityLogRow()
        assertEquals(ActivityLogStatus.PROCESSING, row.status)
    }

    @Test
    fun `PROCESSING status with no result yet shows a waiting message`() {
        val row = record(status = "PROCESSING", resultJson = null).toActivityLogRow()
        assertEquals(ActivityLogStatus.PROCESSING, row.status)
        assertTrue(row.summary.isNotBlank())
    }

    @Test
    fun `malformed result JSON does not throw and still produces a summary`() {
        val row = record(status = "succeeded", resultJson = "not valid json{{{").toActivityLogRow()
        assertTrue(row.summary.isNotBlank())
    }

    @Test
    fun `null result on success still produces a safe summary`() {
        val row = record(status = "succeeded", resultJson = null).toActivityLogRow()
        assertTrue(row.summary.isNotBlank())
    }

    @Test
    fun `isEmpty is true only when not loading and there are no rows`() {
        assertTrue(ActivityLogUiState(rows = emptyList(), isLoading = false).isEmpty)
        assertFalse(ActivityLogUiState(rows = emptyList(), isLoading = true).isEmpty)
        assertFalse(ActivityLogUiState(rows = listOf(record().toActivityLogRow()), isLoading = false).isEmpty)
    }
}
