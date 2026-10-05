package com.youreyes.app.actions

import android.content.Context
import org.json.JSONObject

/**
 * Small persistent state that links one action to a later one:
 * a ride quote to its confirmation, a navigation start to its stop.
 */
internal class ActionSessions(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun put(kind: String, id: String, value: JSONObject) {
        prefs.edit().putString("$kind:$id", value.toString()).apply()
    }

    fun get(kind: String, id: String): JSONObject? =
        prefs.getString("$kind:$id", null)?.let { runCatching { JSONObject(it) }.getOrNull() }

    companion object {
        private const val PREFS_NAME = "action_sessions"
        const val QUOTE = "quote"
        const val NAVIGATION = "navigation"
    }
}

/** A destination after resolving an address to coordinates where possible. */
internal data class ResolvedPlace(val address: String?, val lat: Double?, val lng: Double?) {
    val hasCoordinates: Boolean get() = lat != null && lng != null

    fun toMap(): Map<String, Any?> = mapOf("address" to address, "lat" to lat, "lng" to lng)

    companion object {
        /** Reads `{address?, lat?, lng?}` and geocodes the address when coordinates are missing. */
        fun from(context: Context, json: JSONObject?): ResolvedPlace? {
            if (json == null) return null
            val address = json.optString("address").trim().takeIf { it.isNotEmpty() }
            if (json.has("lat") && json.has("lng")) {
                return ResolvedPlace(address, json.getDouble("lat"), json.getDouble("lng"))
            }
            if (address == null) return null
            val coords = forwardGeocode(context, address)
            return ResolvedPlace(address, coords?.first, coords?.second)
        }
    }
}
