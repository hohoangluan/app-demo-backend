package com.youreyes.app

import com.youreyes.app.actions.ActionExecutionResult
import com.youreyes.app.actions.ActionRegistry
import com.youreyes.app.model.ActionType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ActionRegistryTest {

    private val registry = ActionRegistry()

    // Backend Public API contract actions, including T23 capability discovery.
    private val contractActions = setOf(
        "ride_quote", "ride_confirm", "music_play", "music_stop", "music_volume",
        "navigation_start", "navigation_stop", "emergency_call", "contact_call",
        "location_get", "capabilities_get",
        "call_answer", "call_reject",
    )

    // These do real system/context work and require a real Android Context; a
    // plain JVM unit test only has null.
    //
    // music_play and music_stop joined this set when they stopped guessing: they
    // now answer from AudioManager.isMusicActive, so without a Context there is
    // no audio stack to ask and the only honest answer is an error. They used to
    // return `playback_state: "playing"` here — with no device at all — which is
    // exactly the bug the tests below now guard against.
    private val contextRequiredActions = setOf(
        "emergency_call", "contact_call", "location_get", "capabilities_get",
        "music_play", "music_stop",
        "call_answer", "call_reject",
    )

    @Test
    fun testAllBackendContractActionsAreRegistered() {
        val registered = ActionType.entries.map { it.value }.toSet()
        assertTrue(
            "Missing contract actions: ${contractActions - registered}",
            registered.containsAll(contractActions),
        )
    }

    @Test
    fun testActionsSucceedOrRequireContextWithDefaultParams() {
        for (action in ActionType.entries.map { it.value }) {
            val handler = registry.getHandler(action)
            val result = handler.execute(null, "{}")
            if (action in contextRequiredActions) {
                assertTrue("Expected error without context for $action", result is ActionExecutionResult.Error)
            } else {
                assertTrue("Expected success for action $action", result is ActionExecutionResult.Success)
            }
        }
    }

    @Test
    fun testUnknownActionReturnsError() {
        val handler = registry.getHandler("invalid_action_unknown")
        val result = handler.execute(null, "{}")
        assertTrue(result is ActionExecutionResult.Error)
        val error = result as ActionExecutionResult.Error
        assertEquals("UNSUPPORTED_ACTION", error.errorPayload.code)
    }

    // -- Contract result-shape regression tests (project_context.md 6.4-6.10) --
    // These previously failed: handlers returned ad-hoc field names instead of the
    // published contract fields (e.g. {level,status} instead of {volume_state,level}).

    @Test
    fun testMusicVolumeHandlerReturnsContractFields() {
        val result = registry.getHandler("music_volume").execute(null, """{"level": 75}""")
        assertTrue(result is ActionExecutionResult.Success)
        val data = (result as ActionExecutionResult.Success).resultData
        assertEquals("changed", data["volume_state"])
        assertEquals(75, data["level"])
    }

    /**
     * The previous version of this test asserted `playback_state == "playing"`
     * from a call with a null Context — no handset, no audio stack, nothing that
     * could possibly be playing. It passed, which is how the action shipped
     * telling blind users their song was playing into silence.
     *
     * `playing` is now only ever reported after AudioManager confirms audio, so
     * with nothing to ask, the handler must refuse rather than assume.
     */
    @Test
    fun testMusicPlayWithoutContextReportsFailureInsteadOfAssumingPlayback() {
        val result = registry.getHandler("music_play")
            .execute(null, """{"song": "Noi nay co anh - Son Tung M-TP", "volume": 60}""")
        assertTrue("music_play must not claim success without a device", result is ActionExecutionResult.Error)
        assertEquals("PLAYBACK_FAILED", (result as ActionExecutionResult.Error).errorPayload.code)
    }

    /** A play request that names no song is a bad request, not a playback failure. */
    @Test
    fun testMusicPlayWithoutSongIsSongNotFound() {
        val result = registry.getHandler("music_play").execute(null, """{"volume": 60}""")
        assertTrue(result is ActionExecutionResult.Error)
        assertEquals("SONG_NOT_FOUND", (result as ActionExecutionResult.Error).errorPayload.code)
    }

    /** Same reasoning as music_play: silence has to be confirmed, not assumed. */
    @Test
    fun testMusicStopWithoutContextReportsFailureInsteadOfAssumingStopped() {
        val result = registry.getHandler("music_stop").execute(null, "{}")
        assertTrue("music_stop must not claim success without a device", result is ActionExecutionResult.Error)
        assertEquals("PLAYBACK_STOP_FAILED", (result as ActionExecutionResult.Error).errorPayload.code)
    }

    @Test
    fun testNavigationStartHandlerReturnsContractFields() {
        val result = registry.getHandler("navigation_start")
            .execute(null, """{"destination":{"address":"Buu dien TP.HCM"}}""")
        assertTrue(result is ActionExecutionResult.Success)
        val data = (result as ActionExecutionResult.Success).resultData
        assertEquals("navigating", data["navigation_state"])
        assertEquals("walking", data["travel_mode"])
        assertTrue(data.containsKey("navigation_id"))
        assertTrue(data.containsKey("destination"))
    }

    @Test
    fun testNavigationStopHandlerReturnsContractFields() {
        val result = registry.getHandler("navigation_stop").execute(null, """{"navigation_id":"nav-123"}""")
        assertTrue(result is ActionExecutionResult.Success)
        val data = (result as ActionExecutionResult.Success).resultData
        assertEquals("nav-123", data["navigation_id"])
        assertEquals("stopped", data["navigation_state"])
    }

    @Test
    fun testRideQuoteHandlerReturnsContractFields() {
        val result = registry.getHandler("ride_quote").execute(
            null,
            """{"current_location":{"lat":10.7769,"lng":106.7009},"destination":{"address":"BK","lat":10.7721,"lng":106.6578}}""",
        )
        assertTrue(result is ActionExecutionResult.Success)
        val data = (result as ActionExecutionResult.Success).resultData
        assertTrue(data.containsKey("quote_id"))
        assertTrue(data.containsKey("product_type"))
        assertTrue(data.containsKey("price_estimate"))
        assertTrue(data.containsKey("eta_minutes"))
        assertTrue(data.containsKey("expires_at"))
    }

    @Test
    fun testRideConfirmHandlerCancelledHasNullRideId() {
        val result = registry.getHandler("ride_confirm")
            .execute(null, """{"quote_id":"quote-123","confirm":false}""")
        assertTrue(result is ActionExecutionResult.Success)
        val data = (result as ActionExecutionResult.Success).resultData
        assertEquals("quote-123", data["quote_id"])
        assertEquals("cancelled", data["ride_status"])
    }

    @Test
    fun testRideConfirmHandlerRequestedHasRideId() {
        val result = registry.getHandler("ride_confirm")
            .execute(null, """{"quote_id":"quote-123","confirm":true}""")
        assertTrue(result is ActionExecutionResult.Success)
        val data = (result as ActionExecutionResult.Success).resultData
        assertEquals("requested", data["ride_status"])
        assertTrue((data["ride_id"] as String).isNotBlank())
    }

    @Test
    fun testEmergencyCallHandlerWithoutContextIsError() {
        val result = registry.getHandler("emergency_call").execute(null, "{}")
        assertTrue(result is ActionExecutionResult.Error)
    }

    @Test
    fun testContactCallHandlerEmptyNameIsContactNotFound() {
        val result = registry.getHandler("contact_call").execute(null, """{"name":""}""")
        assertTrue(result is ActionExecutionResult.Error)
        val error = result as ActionExecutionResult.Error
        assertEquals("CONTACT_NOT_FOUND", error.errorPayload.code)
    }
}
