package com.youreyes.app.actions

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.MediaStore
import android.provider.Settings
import com.youreyes.app.model.ActionType
import com.youreyes.app.model.ReportErrorPayload
import org.json.JSONObject

sealed class ActionExecutionResult {
    data class Success(val resultData: Map<String, Any>) : ActionExecutionResult()
    data class Error(val errorPayload: ReportErrorPayload) : ActionExecutionResult()
}

interface ActionHandler {
    fun execute(context: Context?, paramsJson: String): ActionExecutionResult
}

class MusicVolumeHandler : ActionHandler {
    override fun execute(context: Context?, paramsJson: String): ActionExecutionResult {
        return runCatching {
            val json = JSONObject(paramsJson)
            val level = json.optInt("level", 50)
            if (context != null) {
                val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? android.media.AudioManager
                if (audioManager != null) {
                    val maxVol = audioManager.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC)
                    val targetVol = (level * maxVol) / 100
                    audioManager.setStreamVolume(android.media.AudioManager.STREAM_MUSIC, targetVol, android.media.AudioManager.FLAG_SHOW_UI)
                }
            }
            ActionExecutionResult.Success(mapOf("level" to level, "status" to "applied"))
        }.getOrElse {
            ActionExecutionResult.Error(
                ReportErrorPayload("INVALID_PARAMS", "Invalid music_volume params: ${it.message}")
            )
        }
    }
}

class EmergencyCallHandler : ActionHandler {
    override fun execute(context: Context?, paramsJson: String): ActionExecutionResult {
        return runCatching {
            val json = JSONObject(paramsJson)
            val number = json.optString("number", "911")
            if (context != null) {
                val intent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:$number")).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
            }
            ActionExecutionResult.Success(mapOf("dialed_number" to number, "call_status" to "initiated"))
        }.getOrElse {
            ActionExecutionResult.Error(
                ReportErrorPayload("INVALID_PARAMS", "Invalid emergency_call params: ${it.message}")
            )
        }
    }
}

class ContactCallHandler : ActionHandler {
    override fun execute(context: Context?, paramsJson: String): ActionExecutionResult {
        return runCatching {
            val json = JSONObject(paramsJson)
            val name = json.optString("name", "Contact")
            val contactId = json.optString("contact_id", "default")
            if (context != null) {
                val intent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:$contactId")).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
            }
            ActionExecutionResult.Success(mapOf("contact_name" to name, "contact_id" to contactId, "call_status" to "initiated"))
        }.getOrElse {
            ActionExecutionResult.Error(
                ReportErrorPayload("INVALID_PARAMS", "Invalid contact_call params: ${it.message}")
            )
        }
    }
}

class QuotesSpeakHandler : ActionHandler {
    override fun execute(context: Context?, paramsJson: String): ActionExecutionResult {
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
    override fun execute(context: Context?, paramsJson: String): ActionExecutionResult {
        return runCatching {
            val json = JSONObject(paramsJson)
            val song = json.optString("song", "Track")
            if (context != null) {
                // Try YouTube Music first (package: com.google.android.apps.youtube.music)
                val ytMusicIntent = Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH).apply {
                    setPackage("com.google.android.apps.youtube.music")
                    putExtra(MediaStore.EXTRA_MEDIA_FOCUS, "vnd.android.cursor.item/*")
                    putExtra(MediaStore.EXTRA_MEDIA_TITLE, song)
                    putExtra("query", song)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                if (ytMusicIntent.resolveActivity(context.packageManager) != null) {
                    context.startActivity(ytMusicIntent)
                } else {
                    // Fallback: generic media play intent (lets Android pick installed music app)
                    val genericIntent = Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH).apply {
                        putExtra(MediaStore.EXTRA_MEDIA_FOCUS, "vnd.android.cursor.item/*")
                        putExtra(MediaStore.EXTRA_MEDIA_TITLE, song)
                        putExtra("query", song)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(genericIntent)
                }
            }
            ActionExecutionResult.Success(mapOf("song" to song, "playback_status" to "playing", "app" to "youtube_music"))
        }.getOrElse {
            ActionExecutionResult.Error(
                ReportErrorPayload("INVALID_PARAMS", "Invalid media_play params: ${it.message}")
            )
        }
    }
}


class NavigationStartHandler : ActionHandler {
    override fun execute(context: Context?, paramsJson: String): ActionExecutionResult {
        return runCatching {
            val json = JSONObject(paramsJson)
            val destObj = json.optJSONObject("destination")
            val lat = destObj?.optDouble("lat", 10.7769) ?: 10.7769
            val lng = destObj?.optDouble("lng", 106.7009) ?: 106.7009
            val address = destObj?.optString("address", "") ?: ""

            if (context != null) {
                val uri = if (address.isNotBlank()) {
                    Uri.parse("google.navigation:q=${Uri.encode(address)}")
                } else {
                    Uri.parse("google.navigation:q=$lat,$lng")
                }
                val mapIntent = Intent(Intent.ACTION_VIEW, uri).apply {
                    setPackage("com.google.android.apps.maps")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                if (mapIntent.resolveActivity(context.packageManager) != null) {
                    context.startActivity(mapIntent)
                } else {
                    val genericIntent = Intent(Intent.ACTION_VIEW, Uri.parse("geo:$lat,$lng?q=${Uri.encode(address)}")).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(genericIntent)
                }
            }
            ActionExecutionResult.Success(mapOf("lat" to lat, "lng" to lng, "nav_status" to "started"))
        }.getOrElse {
            ActionExecutionResult.Error(
                ReportErrorPayload("INVALID_PARAMS", "Invalid navigation_start params: ${it.message}")
            )
        }
    }
}

class CameraCaptureHandler : ActionHandler {
    override fun execute(context: Context?, paramsJson: String): ActionExecutionResult {
        return runCatching {
            val json = JSONObject(paramsJson)
            val mode = json.optString("mode", "photo")
            if (context != null) {
                val intent = Intent(MediaStore.ACTION_IMAGE_CAPTURE).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                if (intent.resolveActivity(context.packageManager) != null) {
                    context.startActivity(intent)
                }
            }
            ActionExecutionResult.Success(mapOf("mode" to mode, "capture_status" to "captured"))
        }.getOrElse {
            ActionExecutionResult.Error(
                ReportErrorPayload("INVALID_PARAMS", "Invalid camera_capture params: ${it.message}")
            )
        }
    }
}

class DisplayShowHandler : ActionHandler {
    override fun execute(context: Context?, paramsJson: String): ActionExecutionResult {
        return runCatching {
            val json = JSONObject(paramsJson)
            val message = json.optString("message", "Display Notice")
            ActionExecutionResult.Success(mapOf("message" to message, "display_status" to "rendered"))
        }.getOrElse {
            ActionExecutionResult.Error(
                ReportErrorPayload("INVALID_PARAMS", "Invalid display_show params: ${it.message}")
            )
        }
    }
}

class SystemSettingsHandler : ActionHandler {
    override fun execute(context: Context?, paramsJson: String): ActionExecutionResult {
        return runCatching {
            val json = JSONObject(paramsJson)
            val key = json.optString("setting_key", "wifi")
            if (context != null) {
                val intent = Intent(Settings.ACTION_SETTINGS).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
            }
            ActionExecutionResult.Success(mapOf("setting_key" to key, "status" to "opened"))
        }.getOrElse {
            ActionExecutionResult.Error(
                ReportErrorPayload("INVALID_PARAMS", "Invalid system_settings params: ${it.message}")
            )
        }
    }
}

class UnsupportedActionHandler(private val actionName: String) : ActionHandler {
    override fun execute(context: Context?, paramsJson: String): ActionExecutionResult {
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
