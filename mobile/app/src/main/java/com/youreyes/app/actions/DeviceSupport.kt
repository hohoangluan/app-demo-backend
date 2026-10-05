package com.youreyes.app.actions

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Geocoder
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import com.youreyes.app.launch.launchViaFullScreenNotification
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

private const val TAG = "DeviceSupport"
private const val GEOCODE_TIMEOUT_MS = 5_000L
private val notificationIds = AtomicInteger(1000)

internal fun hasPermission(context: Context, permission: String): Boolean =
    ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

internal fun hasLocationPermission(context: Context): Boolean =
    hasPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ||
        hasPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION)

internal fun isInstalled(context: Context, packageName: String): Boolean =
    runCatching { context.packageManager.getPackageInfo(packageName, 0) }.isSuccess

/** True when some installed app can open [intent]. */
internal fun canOpen(context: Context, intent: Intent): Boolean =
    intent.resolveActivity(context.packageManager) != null

/**
 * Opens [intent] from the background.
 *
 * Android drops a plain `startActivity()` from a background service, so the
 * intent goes through a full-screen notification that can open over the lock screen.
 */
internal fun launchUiIntent(context: Context, intent: Intent, title: String, text: String) {
    launchViaFullScreenNotification(context, intent, notificationIds.incrementAndGet(), title, text)
}

/** Newest last-known fix from any enabled provider, or null. */
@SuppressLint("MissingPermission") // checked by hasLocationPermission
internal fun lastKnownLocation(context: Context): Location? {
    if (!hasLocationPermission(context)) return null
    val manager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return null
    return runCatching {
        manager.getProviders(true).mapNotNull(manager::getLastKnownLocation).maxByOrNull { it.time }
    }.getOrNull()
}

/**
 * Last-known fix, or a fresh one when the phone has none yet (e.g. right after
 * a reboot, before any app used GPS). Waits at most [timeoutMs].
 */
@SuppressLint("MissingPermission") // checked by hasLocationPermission
internal fun currentLocation(context: Context, timeoutMs: Long = 10_000L): Location? {
    lastKnownLocation(context)?.let { return it }
    if (!hasLocationPermission(context)) return null
    val manager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return null
    val provider = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
        .firstOrNull { runCatching { manager.isProviderEnabled(it) }.getOrDefault(false) } ?: return null

    val latch = CountDownLatch(1)
    var fix: Location? = null
    val onFix = { location: Location? ->
        fix = location
        latch.countDown()
    }
    runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            manager.getCurrentLocation(provider, null, ContextCompat.getMainExecutor(context), onFix)
        } else {
            @Suppress("DEPRECATION")
            manager.requestSingleUpdate(provider, { onFix(it) }, Looper.getMainLooper())
        }
    }.onFailure {
        Log.w(TAG, "Fresh location request failed", it)
        return null
    }
    latch.await(timeoutMs, TimeUnit.MILLISECONDS)
    return fix
}

/** Google Plus Code such as "2R5J+X8M": a grid reference, noise when read aloud. */
private val PLUS_CODE = Regex("""^[23456789CFGHJMPQRVWX]{4,8}\+[23456789CFGHJMPQRVWX]{2,3}$""")

/** Street address for [location] in Vietnamese, or null within [GEOCODE_TIMEOUT_MS]. */
internal fun reverseGeocode(context: Context, location: Location): String? = geocode {
    Geocoder(context, Locale.forLanguageTag("vi-VN"))
        .getFromLocation(location.latitude, location.longitude, 1)
        ?.firstOrNull()
        ?.let { it.getAddressLine(0) ?: listOfNotNull(it.subAdminArea, it.adminArea).joinToString(", ") }
        ?.split(",")
        ?.map { it.trim() }
        ?.filterNot { PLUS_CODE.matches(it) }
        ?.joinToString(", ")
        ?.takeIf { it.isNotBlank() }
}

/** Coordinates for a free-text [address], or null within [GEOCODE_TIMEOUT_MS]. */
internal fun forwardGeocode(context: Context, address: String): Pair<Double, Double>? = geocode {
    Geocoder(context, Locale.forLanguageTag("vi-VN"))
        .getFromLocationName(address, 1)
        ?.firstOrNull()
        ?.let { it.latitude to it.longitude }
}

/**
 * Runs a blocking [Geocoder] call with a time limit.
 *
 * The geocoder talks to a network service that can hang or be missing; callers
 * treat null as "unknown" rather than failing the action.
 */
@Suppress("DEPRECATION") // the async overloads need API 33; minSdk is 24
private fun <T> geocode(lookup: () -> T?): T? {
    if (!Geocoder.isPresent()) return null
    val latch = CountDownLatch(1)
    var value: T? = null
    Thread {
        value = runCatching(lookup).onFailure { Log.w(TAG, "Geocoder failed", it) }.getOrNull()
        latch.countDown()
    }.start()
    latch.await(GEOCODE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
    return value
}
