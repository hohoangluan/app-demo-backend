package com.youreyes.app.actions

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.youreyes.app.core.Timestamps
import org.json.JSONObject
import java.util.UUID

private const val GRAB_PACKAGE = "com.grabtaxi.passenger"
private const val QUOTE_VALID_MINUTES = 5L

/**
 * Opens Grab's booking screen for the trip. Grab is the ride app that operates
 * in Vietnam; its public App Link needs no partner API, and the user still taps
 * to confirm the booking inside Grab.
 */
private fun grabIntent(quote: JSONObject): Intent {
    val pickup = if (quote.has("pickup_lat")) {
        "&pickupLatitude=${quote.getDouble("pickup_lat")}&pickupLongitude=${quote.getDouble("pickup_lng")}"
    } else {
        ""
    }
    val uri = Uri.parse(
        "grab://open?screenType=BOOKING$pickup" +
            "&dropOffLatitude=${quote.getDouble("lat")}&dropOffLongitude=${quote.getDouble("lng")}" +
            "&dropOffAddress=${Uri.encode(quote.optString("address"))}"
    )
    return Intent(Intent.ACTION_VIEW, uri).setPackage(GRAB_PACKAGE).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
}

/** Opens Grab on the quoted trip; false when Grab is not installed. */
private fun openGrab(context: Context, quote: JSONObject): Boolean {
    val intent = grabIntent(quote)
    if (!canOpen(context, intent)) return false
    launchUiIntent(context, intent, "Đặt xe Grab", quote.optString("address"))
    return true
}

/**
 * `ride_quote`: open Grab on the trip and return a quote id for `ride_confirm`.
 *
 * Grab exposes no public price/ETA API, so the price and ETA are fixed demo
 * figures; the destination and quote id are real.
 */
class RideQuoteHandler : ActionHandler {
    override fun execute(context: Context?, paramsJson: String): ActionExecutionResult {
        if (context == null) return failure("RIDE_PROVIDER_ERROR", "No context available")
        val json = runCatching { JSONObject(paramsJson) }.getOrElse {
            return failure("INVALID_DESTINATION", "Invalid ride_quote params: ${it.message}")
        }
        val place = ResolvedPlace.from(context, json.optJSONObject("destination"))
        if (place == null || !place.hasCoordinates) {
            return failure("INVALID_DESTINATION", "Destination could not be located")
        }

        val quoteId = "quote-${UUID.randomUUID().toString().take(8)}"
        val expiresAt = System.currentTimeMillis() + QUOTE_VALID_MINUTES * 60_000L
        val quote = JSONObject()
            .put("lat", place.lat)
            .put("lng", place.lng)
            .put("address", place.address ?: "")
            .put("expires_at", expiresAt)
            .put("used", false)
        json.optJSONObject("current_location")?.let {
            if (it.has("lat") && it.has("lng")) {
                quote.put("pickup_lat", it.getDouble("lat")).put("pickup_lng", it.getDouble("lng"))
            }
        }
        if (!openGrab(context, quote)) return failure("RIDE_PROVIDER_ERROR", "Grab is not installed")
        ActionSessions(context).put(ActionSessions.QUOTE, quoteId, quote)

        return success(
            "quote_id" to quoteId,
            "product_type" to "GrabCar",
            "price_estimate" to mapOf("currency" to "VND", "amount" to 85000),
            "eta_minutes" to 6,
            "expires_at" to Timestamps.utc(expiresAt),
        )
    }
}

/** `ride_confirm`: book (reopen Grab on the quoted trip) or cancel a quote, once. */
class RideConfirmHandler : ActionHandler {
    override fun execute(context: Context?, paramsJson: String): ActionExecutionResult {
        if (context == null) return failure("RIDE_CONFIRM_FAILED", "No context available")
        val json = runCatching { JSONObject(paramsJson) }.getOrElse {
            return failure("RIDE_CONFIRM_FAILED", "Invalid ride_confirm params: ${it.message}")
        }
        val quoteId = json.optString("quote_id").trim()
        val sessions = ActionSessions(context)
        val quote = sessions.get(ActionSessions.QUOTE, quoteId)
            ?: return failure("QUOTE_NOT_FOUND", "No quote '$quoteId' on this phone")
        if (quote.optBoolean("used")) return failure("QUOTE_ALREADY_USED", "Quote '$quoteId' was already used")
        if (System.currentTimeMillis() > quote.optLong("expires_at")) {
            return failure("QUOTE_EXPIRED", "Quote '$quoteId' has expired")
        }

        if (!json.optBoolean("confirm", false)) {
            sessions.put(ActionSessions.QUOTE, quoteId, quote.put("used", true))
            return success("quote_id" to quoteId, "ride_id" to null, "ride_status" to "cancelled")
        }
        if (!openGrab(context, quote)) return failure("RIDE_CONFIRM_FAILED", "Grab is not installed")
        sessions.put(ActionSessions.QUOTE, quoteId, quote.put("used", true))
        return success(
            "quote_id" to quoteId,
            "ride_id" to "ride-${UUID.randomUUID().toString().take(8)}",
            "ride_status" to "requested",
        )
    }
}
