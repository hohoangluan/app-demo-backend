package com.youreyes.app.ui.meeting

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MeetingUiStateTest {

    @Test
    fun `isEmpty true only with no records and not recording`() {
        assertTrue(MeetingUiState(records = emptyList(), isRecording = false).isEmpty)
        assertFalse(MeetingUiState(records = emptyList(), isRecording = true).isEmpty)
    }

    @Test
    fun `isEmpty false once a record exists`() {
        val record = MeetingRecord(
            id = 1,
            title = "Cuộc họp #1",
            transcriptSnippet = "...",
            durationSeconds = 30,
            recordedAtMillis = 0L,
        )
        assertFalse(MeetingUiState(records = listOf(record)).isEmpty)
    }

    @Test
    fun `formatDuration under an hour is mm colon ss`() {
        assertEquals("0:00", formatDuration(0))
        assertEquals("0:09", formatDuration(9))
        assertEquals("1:05", formatDuration(65))
        assertEquals("59:59", formatDuration(3599))
    }

    @Test
    fun `formatDuration past an hour includes the hour component`() {
        assertEquals("1:00:00", formatDuration(3600))
        assertEquals("1:00:01", formatDuration(3601))
        assertEquals("2:03:04", formatDuration(2 * 3600 + 3 * 60 + 4))
    }
}
