package com.innostar.appdemo

import com.innostar.appdemo.actions.ActionExecutionResult
import com.innostar.appdemo.actions.ActionRegistry
import com.innostar.appdemo.model.ActionType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ActionRegistryTest {

    private val registry = ActionRegistry()

    @Test
    fun testAllNineActionsAreSupported() {
        val actions = ActionType.entries.map { it.value }
        assertEquals(9, actions.size)

        for (action in actions) {
            val handler = registry.getHandler(action)
            val result = handler.execute(null, "{}")
            assertTrue("Expected success for action $action", result is ActionExecutionResult.Success)
        }
    }

    @Test
    fun testMusicVolumeHandlerParsesLevel() {
        val handler = registry.getHandler("music_volume")
        val result = handler.execute(null, """{"level": 75}""")
        assertTrue(result is ActionExecutionResult.Success)
        val success = result as ActionExecutionResult.Success
        assertEquals(75, success.resultData["level"])
    }

    @Test
    fun testUnknownActionReturnsError() {
        val handler = registry.getHandler("invalid_action_unknown")
        val result = handler.execute(null, "{}")
        assertTrue(result is ActionExecutionResult.Error)
        val error = result as ActionExecutionResult.Error
        assertEquals("UNSUPPORTED_ACTION", error.errorPayload.code)
    }
}
