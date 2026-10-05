package com.youreyes.app.actions

import com.youreyes.app.command.ActionType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ActionRegistryTest {

    private val registry = ActionRegistry()

    /** Must equal the `Action` enum in server/src/app/actions.py. */
    private val serverActions = setOf(
        "ride_quote", "ride_confirm", "music_play", "music_stop", "music_volume",
        "navigation_start", "navigation_stop", "emergency_call", "contact_call",
        "location_get", "capabilities_get", "call_answer", "call_reject",
    )

    private fun errorCode(action: String, params: String = "{}"): String {
        val result = registry.getHandler(action).execute(null, params)
        assertTrue("$action without a device must fail", result is ActionExecutionResult.Error)
        return (result as ActionExecutionResult.Error).errorPayload.code
    }

    @Test
    fun `every server action has exactly one handler`() {
        assertEquals(serverActions, ActionType.entries.map { it.value }.toSet())
        assertEquals(serverActions, ActionRegistry.defaultHandlers().keys)
    }

    @Test
    fun `unknown action is reported as unsupported`() {
        assertEquals("UNSUPPORTED_ACTION", errorCode("camera_capture"))
    }

    @Test
    fun `actions that need the phone refuse instead of faking success without one`() {
        val expected = mapOf(
            "ride_quote" to "RIDE_PROVIDER_ERROR",
            "ride_confirm" to "RIDE_CONFIRM_FAILED",
            "music_play" to "PLAYBACK_FAILED",
            "music_stop" to "PLAYBACK_STOP_FAILED",
            "navigation_start" to "NAVIGATION_START_FAILED",
            "navigation_stop" to "NAVIGATION_STOP_FAILED",
            "emergency_call" to "CALL_FAILED",
            "location_get" to "CURRENT_LOCATION_UNAVAILABLE",
            "capabilities_get" to "INVALID_PARAMS",
            "call_answer" to "CALL_CONTROL_FAILED",
            "call_reject" to "CALL_CONTROL_FAILED",
        )
        for ((action, code) in expected) {
            val params = if (action == "music_play") """{"song":"Lạc trôi"}""" else "{}"
            assertEquals(action, code, errorCode(action, params))
        }
    }

    @Test
    fun `music play without a song is song not found`() {
        assertEquals("SONG_NOT_FOUND", errorCode("music_play", """{"volume":60}"""))
    }

    @Test
    fun `contact call with an empty name is contact not found`() {
        assertEquals("CONTACT_NOT_FOUND", errorCode("contact_call", """{"name":"  "}"""))
    }

    @Test
    fun `music volume with an absolute level returns contract fields`() {
        val result = registry.getHandler("music_volume").execute(null, """{"level":175}""")
        val data = (result as ActionExecutionResult.Success).resultData
        assertEquals("changed", data["volume_state"])
        assertEquals(100, data["level"])
    }

    @Test
    fun `music volume needs a level or a direction`() {
        assertEquals("INVALID_VOLUME", errorCode("music_volume", "{}"))
        assertEquals("VOLUME_CHANGE_FAILED", errorCode("music_volume", """{"direction":"up"}"""))
    }

    @Test
    fun `song query splits title and artist`() {
        assertEquals(SongQuery("Nơi này có anh", "Sơn Tùng M-TP"), SongQuery.parse("Nơi này có anh - Sơn Tùng M-TP"))
        assertEquals(SongQuery("Lạc trôi", null), SongQuery.parse(" Lạc trôi "))
    }

    @Test
    fun `title match ignores case and accepts containment`() {
        assertTrue(titleMatches("Nơi Này Có Anh", "nơi này có anh"))
        assertTrue(titleMatches("Lạc Trôi (Remix)", "Lạc trôi"))
        assertFalse(titleMatches("Chạy Ngay Đi", "Lạc trôi"))
        assertFalse(titleMatches(null, "Lạc trôi"))
    }

    @Test
    fun `phone numbers are masked to their last three digits`() {
        assertEquals("***768", ContactLookup.maskPhoneNumber("+84 797 173 768"))
        assertEquals("***", ContactLookup.maskPhoneNumber("12"))
        assertTrue(ContactLookup.looksLikePhoneNumber("+84 (797) 173-768"))
        assertFalse(ContactLookup.looksLikePhoneNumber("Mẹ"))
    }
}
