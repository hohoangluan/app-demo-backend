package com.innostar.appdemo.actions

import com.innostar.appdemo.model.ActionType
import com.innostar.appdemo.model.ReportErrorPayload
import org.json.JSONObject

sealed class ActionExecutionResult {
    data class Success(val resultData: Map<String, Any>) : ActionExecutionResult()
    data class Error(val errorPayload: ReportErrorPayload) : ActionExecutionResult()
}

interface ActionHandler {
    fun execute(paramsJson: String): ActionExecutionResult
}

class MusicVolumeHandler : ActionHandler {
    override fun execute(paramsJson: String): ActionExecutionResult {
        return runCatching {
            val json = JSONObject(paramsJson)
            val level = json.optInt("level", 50)
            ActionExecutionResult.Success(mapOf("level" to level, "status" to "applied"))
        }.getOrElse {
            ActionExecutionResult.Error(
                ReportErrorPayload("INVALID_PARAMS", "Invalid music_volume params: ${it.message}")
            )
        }
    }
}

class EmergencyCallHandler : ActionHandler {
    override fun execute(paramsJson: String): ActionExecutionResult {
        return runCatching {
            val json = JSONObject(paramsJson)
            val number = json.optString("number", "911")
            ActionExecutionResult.Success(mapOf("dialed_number" to number, "call_status" to "initiated"))
        }.getOrElse {
            ActionExecutionResult.Error(
                ReportErrorPayload("INVALID_PARAMS", "Invalid emergency_call params: ${it.message}")
            )
        }
    }
}

class ContactCallHandler : ActionHandler {
    override fun execute(paramsJson: String): ActionExecutionResult {
        return runCatching {
            val json = JSONObject(paramsJson)
            val contactId = json.optString("contact_id", "default")
            ActionExecutionResult.Success(mapOf("contact_id" to contactId, "call_status" to "initiated"))
        }.getOrElse {
            ActionExecutionResult.Error(
                ReportErrorPayload("INVALID_PARAMS", "Invalid contact_call params: ${it.message}")
            )
        }
    }
}

class QuotesSpeakHandler : ActionHandler {
    override fun execute(paramsJson: String): ActionExecutionResult {
        return runCatching {
            val json = JSONObject(paramsJson)
            val text = json.optString("text", "Hello world")
            ActionExecutionResult.Success(mapOf("spoken_text" to text, "tts_status" to "completed"))
        }.getOrElse {
            ActionExecutionResult.Error(
                ReportErrorPayload("INVALID_PARAMS", "Invalid quotes_speak params: ${it.message}")
            )
        }
    }
}

class MediaPlayHandler : ActionHandler {
    override fun execute(paramsJson: String): ActionExecutionResult {
        return runCatching {
            val json = JSONObject(paramsJson)
            val song = json.optString("song", "Track 1")
            ActionExecutionResult.Success(mapOf("song" to song, "playback_status" to "playing"))
        }.getOrElse {
            ActionExecutionResult.Error(
                ReportErrorPayload("INVALID_PARAMS", "Invalid media_play params: ${it.message}")
            )
        }
    }
}

class NavigationStartHandler : ActionHandler {
    override fun execute(paramsJson: String): ActionExecutionResult {
        return runCatching {
            val json = JSONObject(paramsJson)
            val navId = json.optString("navigation_id", "nav-1")
            ActionExecutionResult.Success(mapOf("navigation_id" to navId, "nav_status" to "started"))
        }.getOrElse {
            ActionExecutionResult.Error(
                ReportErrorPayload("INVALID_PARAMS", "Invalid navigation_start params: ${it.message}")
            )
        }
    }
}

class CameraCaptureHandler : ActionHandler {
    override fun execute(paramsJson: String): ActionExecutionResult {
        return runCatching {
            val json = JSONObject(paramsJson)
            val mode = json.optString("mode", "photo")
            ActionExecutionResult.Success(mapOf("mode" to mode, "capture_status" to "captured"))
        }.getOrElse {
            ActionExecutionResult.Error(
                ReportErrorPayload("INVALID_PARAMS", "Invalid camera_capture params: ${it.message}")
            )
        }
    }
}

class DisplayShowHandler : ActionHandler {
    override fun execute(paramsJson: String): ActionExecutionResult {
        return runCatching {
            val json = JSONObject(paramsJson)
            val message = json.optString("message", "Display ON")
            ActionExecutionResult.Success(mapOf("message" to message, "display_status" to "rendered"))
        }.getOrElse {
            ActionExecutionResult.Error(
                ReportErrorPayload("INVALID_PARAMS", "Invalid display_show params: ${it.message}")
            )
        }
    }
}

class SystemSettingsHandler : ActionHandler {
    override fun execute(paramsJson: String): ActionExecutionResult {
        return runCatching {
            val json = JSONObject(paramsJson)
            val key = json.optString("setting_key", "wifi")
            ActionExecutionResult.Success(mapOf("setting_key" to key, "status" to "updated"))
        }.getOrElse {
            ActionExecutionResult.Error(
                ReportErrorPayload("INVALID_PARAMS", "Invalid system_settings params: ${it.message}")
            )
        }
    }
}

class UnsupportedActionHandler(private val actionName: String) : ActionHandler {
    override fun execute(paramsJson: String): ActionExecutionResult {
        return ActionExecutionResult.Error(
            ReportErrorPayload("UNSUPPORTED_ACTION", "Action '$actionName' is not supported by Android client")
        )
    }
}

class ActionRegistry {
    private val handlers = mapOf<String, ActionHandler>(
        ActionType.MUSIC_VOLUME.value to MusicVolumeHandler(),
        ActionType.EMERGENCY_CALL.value to EmergencyCallHandler(),
        ActionType.CONTACT_CALL.value to ContactCallHandler(),
        ActionType.QUOTES_SPEAK.value to QuotesSpeakHandler(),
        ActionType.MEDIA_PLAY.value to MediaPlayHandler(),
        ActionType.NAVIGATION_START.value to NavigationStartHandler(),
        ActionType.CAMERA_CAPTURE.value to CameraCaptureHandler(),
        ActionType.DISPLAY_SHOW.value to DisplayShowHandler(),
        ActionType.SYSTEM_SETTINGS.value to SystemSettingsHandler()
    )

    fun getHandler(action: String): ActionHandler {
        return handlers[action] ?: UnsupportedActionHandler(action)
    }
}
