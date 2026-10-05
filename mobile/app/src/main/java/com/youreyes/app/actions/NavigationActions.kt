package com.youreyes.app.actions

import android.content.Context
import android.content.Intent
import android.net.Uri
import org.json.JSONObject
import java.util.UUID

private const val MAPS_PACKAGE = "com.google.android.apps.maps"

/**
 * `navigation_start`: open Google Maps walking guidance to the destination.
 *
 * An address without coordinates is geocoded so the result can report real
 * coordinates; if that fails Maps still navigates by address and lat/lng are null.
 */
class NavigationStartHandler : ActionHandler {
    override fun execute(context: Context?, paramsJson: String): ActionExecutionResult {
        if (context == null) return failure("NAVIGATION_START_FAILED", "No context available")
        val json = runCatching { JSONObject(paramsJson) }.getOrElse {
            return failure("NAVIGATION_START_FAILED", "Invalid navigation_start params: ${it.message}")
        }
        val place = ResolvedPlace.from(context, json.optJSONObject("destination"))
            ?: return failure("INVALID_DESTINATION", "destination needs an address or lat/lng")

        val target = if (place.hasCoordinates) "${place.lat},${place.lng}" else place.address!!
        val maps = Intent(Intent.ACTION_VIEW, Uri.parse("google.navigation:q=${Uri.encode(target)}&mode=w"))
            .setPackage(MAPS_PACKAGE)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val intent = if (isInstalled(context, MAPS_PACKAGE)) {
            maps
        } else {
            Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=${Uri.encode(target)}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        if (!canOpen(context, intent)) {
            return failure("NAVIGATION_PROVIDER_ERROR", "No map app is installed to navigate")
        }
        launchUiIntent(context, intent, "Điều hướng đi bộ", place.address ?: target)

        val navigationId = "nav-${UUID.randomUUID().toString().take(8)}"
        ActionSessions(context).put(
            ActionSessions.NAVIGATION, navigationId, JSONObject().put("state", "navigating")
        )
        return success(
            "navigation_id" to navigationId,
            "navigation_state" to "navigating",
            "travel_mode" to "walking",
            "destination" to place.toMap(),
        )
    }
}

/**
 * `navigation_stop`: end a session started by [NavigationStartHandler].
 *
 * Maps runs as a separate app, so guidance on screen is not force-closed; the
 * session is marked stopped and later stops for it are refused.
 */
class NavigationStopHandler : ActionHandler {
    override fun execute(context: Context?, paramsJson: String): ActionExecutionResult {
        if (context == null) return failure("NAVIGATION_STOP_FAILED", "No context available")
        val navigationId = runCatching { JSONObject(paramsJson).optString("navigation_id") }
            .getOrDefault("").trim()
        val sessions = ActionSessions(context)
        val session = sessions.get(ActionSessions.NAVIGATION, navigationId)
            ?: return failure("NAVIGATION_NOT_FOUND", "No navigation '$navigationId' on this phone")
        if (session.optString("state") == "stopped") {
            return failure("NAVIGATION_ALREADY_STOPPED", "Navigation '$navigationId' is already stopped")
        }
        sessions.put(ActionSessions.NAVIGATION, navigationId, session.put("state", "stopped"))
        return success("navigation_id" to navigationId, "navigation_state" to "stopped")
    }
}
