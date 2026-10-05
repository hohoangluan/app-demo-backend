package com.youreyes.app.core

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * ISO-8601 UTC timestamps such as `2026-10-05T08:00:00Z`.
 *
 * `java.time` needs API 26 and minSdk is 24 without desugaring, hence SimpleDateFormat.
 */
object Timestamps {
    fun utc(epochMillis: Long): String =
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
            .apply { timeZone = TimeZone.getTimeZone("UTC") }
            .format(Date(epochMillis))

    fun utcNowPlusMinutes(minutes: Long): String =
        utc(System.currentTimeMillis() + minutes * 60_000L)
}
