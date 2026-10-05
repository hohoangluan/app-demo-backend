package com.youreyes.app.actions

import android.content.Context
import com.youreyes.app.command.ActionType
import com.youreyes.app.command.ReportErrorPayload

sealed class ActionExecutionResult {
    data class Success(val resultData: Map<String, Any?>) : ActionExecutionResult()
    data class Error(val errorPayload: ReportErrorPayload) : ActionExecutionResult()
}

/**
 * Runs one action on the phone. Handlers block until the phone confirms the
 * outcome (call off-hook, audio playing), so they run on a background thread.
 * A null [Context] only happens in JVM tests.
 */
fun interface ActionHandler {
    fun execute(context: Context?, paramsJson: String): ActionExecutionResult
}

internal fun success(vararg fields: Pair<String, Any?>) = ActionExecutionResult.Success(mapOf(*fields))

internal fun failure(code: String, message: String, details: Map<String, Any?> = emptyMap()) =
    ActionExecutionResult.Error(ReportErrorPayload(code, message, details))

/** Maps each server action to its handler; anything else gets `UNSUPPORTED_ACTION`. */
class ActionRegistry(
    private val handlers: Map<String, ActionHandler> = defaultHandlers(),
) {
    fun getHandler(action: String): ActionHandler =
        handlers[action] ?: ActionHandler { _, _ ->
            failure("UNSUPPORTED_ACTION", "Action '$action' is not supported by Android client")
        }

    companion object {
        fun defaultHandlers(): Map<String, ActionHandler> = ActionType.entries.associate { type ->
            type.value to when (type) {
                ActionType.RIDE_QUOTE -> RideQuoteHandler()
                ActionType.RIDE_CONFIRM -> RideConfirmHandler()
                ActionType.MUSIC_PLAY -> MusicPlayHandler()
                ActionType.MUSIC_STOP -> MusicStopHandler()
                ActionType.MUSIC_VOLUME -> MusicVolumeHandler()
                ActionType.NAVIGATION_START -> NavigationStartHandler()
                ActionType.NAVIGATION_STOP -> NavigationStopHandler()
                ActionType.EMERGENCY_CALL -> EmergencyCallHandler()
                ActionType.CONTACT_CALL -> ContactCallHandler()
                ActionType.LOCATION_GET -> LocationGetHandler()
                ActionType.CAPABILITIES_GET -> CapabilitiesGetHandler()
                ActionType.CALL_ANSWER -> CallAnswerHandler()
                ActionType.CALL_REJECT -> CallRejectHandler()
            }
        }
    }
}
