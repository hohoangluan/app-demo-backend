package com.youreyes.app.network

import org.json.JSONObject
import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.URL

/**
 * Talks to **Server Kính** (the glasses server, port 8000 / the Cloudflare
 * tunnel) — a different machine, a different base URL and a different token
 * from [DeviceApiClient], which targets the App Communication Server on 8001.
 *
 * Two transports, because the glasses can be reached two very different ways
 * and the difference is exactly what the user needs the app to explain:
 *
 * | | over the internet | over the local network |
 * |---|---|---|
 * | Works when | glasses still have working Wi-Fi | phone and glasses share a network, **or** the glasses are broadcasting their `VisionCare-Setup` hotspot |
 * | Path | server kính → SSE `/events` → glasses | phone → `http://<glasses-ip>/…` directly |
 * | Used for | status, remote Wi-Fi change, "update now" | first-time setup, and rescuing glasses that lost Wi-Fi |
 *
 * The remote path is the nice one; the LAN path is the one that still works
 * when the glasses are off the network, which is precisely when the user needs
 * to change their Wi-Fi. Neither replaces the other.
 */
class GlassesServerClient {

    // ── over the internet: server kính ────────────────────────────────────

    /**
     * Everything the "My glasses" screen shows, in one round trip: serial,
     * which Wi-Fi the glasses are registered on, firmware version, whether a
     * newer build is released, and whether the downlink is open.
     */
    fun deviceStatus(baseUrl: String, token: String, deviceId: String): Result<GlassesStatus> =
        runCatching {
            val url = "${baseUrl.trimEnd('/')}/device/status?device_id=$deviceId"
            val body = get(url, token)
            val data = body.getJSONObject("data")
            GlassesStatus(
                deviceId = data.optString("device_id", deviceId),
                serial = data.optString("serial", ""),
                known = data.optBoolean("known", false),
                online = data.optBoolean("online", false),
                wifiSsid = data.optStringOrNull("wifi_ssid"),
                firmwareVersion = data.optStringOrNull("fw_version"),
                latestVersion = data.optStringOrNull("latest_version"),
                updateAvailable = data.optBoolean("update_available", false),
                downlinkOpen = data.optBoolean("downlink_open", false),
                pairedTo = data.optStringOrNull("paired_to"),
            )
        }

    /**
     * Push a new Wi-Fi network to the glasses through the SSE downlink.
     *
     * Fails with a 409 when the glasses are not holding the channel — that is
     * a real answer, not a hiccup: it means the glasses cannot hear us and the
     * user has to go through the `VisionCare-Setup` hotspot instead. Never
     * retry it silently, and never report it as success.
     */
    fun setWifi(
        baseUrl: String,
        token: String,
        deviceId: String,
        ssid: String,
        password: String,
    ): Result<Unit> = runCatching {
        val payload = JSONObject().apply {
            put("device_id", deviceId)
            put("ssid", ssid)
            put("password", password)
        }
        post("${baseUrl.trimEnd('/')}/device/wifi", token, payload)
        Unit
    }

    /** Ask the glasses to fetch and install the released firmware now. */
    fun requestFirmwareUpdate(baseUrl: String, token: String, deviceId: String): Result<String> =
        runCatching {
            val payload = JSONObject().put("device_id", deviceId)
            val body = post("${baseUrl.trimEnd('/')}/device/firmware-update", token, payload)
            body.getJSONObject("data").optString("version", "")
        }

    // ── over the local network: the glasses themselves ────────────────────

    /**
     * Read the glasses' own status page. [host] is `192.168.4.1` while they are
     * broadcasting `VisionCare-Setup`, otherwise their address on the shared
     * network.
     */
    fun localStatus(host: String): Result<GlassesLocalStatus> = runCatching {
        val body = get("http://$host/wifi/status", token = null)
        GlassesLocalStatus(
            serial = body.optString("serial", ""),
            deviceId = body.optString("device_id", ""),
            firmwareVersion = body.optString("fw_version", ""),
            ssid = body.optString("ssid", ""),
            fromNvs = body.optBoolean("from_nvs", false),
            apMode = body.optBoolean("ap_mode", false),
            connected = body.optBoolean("connected", false),
            ip = body.optString("ip", ""),
            rssi = body.optInt("rssi", 0),
        )
    }

    /** Networks the glasses can actually see — only 2.4 GHz, which is the point. */
    fun localScan(host: String): Result<List<VisibleNetwork>> = runCatching {
        // 15 s: the ESP32 scan is synchronous and takes 2-4 s on its own, and
        // it runs on the same core as the web server.
        val body = get("http://$host/wifi/scan", token = null, timeoutMs = 15_000)
        val array = body.getJSONArray("networks")
        (0 until array.length()).map { index ->
            val item = array.getJSONObject(index)
            VisibleNetwork(
                ssid = item.optString("ssid", ""),
                rssi = item.optInt("rssi", 0),
                secure = item.optBoolean("secure", true),
            )
        }
    }

    /**
     * Write Wi-Fi credentials straight into the glasses' NVS. They reply first
     * and reboot ~1.2 s later, so a dropped connection right after this call is
     * the expected outcome, not a failure.
     */
    fun localSetWifi(host: String, ssid: String, password: String): Result<Unit> = runCatching {
        val payload = JSONObject().apply {
            put("ssid", ssid)
            put("password", password)
        }
        val body = post("http://$host/wifi/config", token = null, payload = payload)
        if (!body.optBoolean("ok", false)) {
            throw IllegalStateException(body.optString("error", "Kính từ chối cấu hình"))
        }
        Unit
    }

    // ── plumbing ─────────────────────────────────────────────────────────

    private fun get(url: String, token: String?, timeoutMs: Int = 8_000): JSONObject {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            token?.let { setRequestProperty("Authorization", "Bearer $it") }
            connectTimeout = timeoutMs
            readTimeout = timeoutMs
        }
        return conn.readJsonOrThrow()
    }

    private fun post(url: String, token: String?, payload: JSONObject): JSONObject {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            setRequestProperty("Content-Type", "application/json")
            token?.let { setRequestProperty("Authorization", "Bearer $it") }
            doOutput = true
            connectTimeout = 8_000
            readTimeout = 8_000
        }
        conn.outputStream.use { it.write(payload.toString().toByteArray()) }
        return conn.readJsonOrThrow()
    }

    private fun HttpURLConnection.readJsonOrThrow(): JSONObject {
        val code = responseCode
        if (code !in 200..299) {
            val text = errorStream?.bufferedReader()?.use(BufferedReader::readText).orEmpty()
            // Surface the server's own `detail` string: it carries the one
            // sentence the user can act on ("dùng hotspot VisionCare-Setup"),
            // and a bare status code carries none of it.
            val detail = runCatching { JSONObject(text).optString("detail") }.getOrNull()
            throw IllegalStateException(
                if (!detail.isNullOrBlank()) "$detail ($code)" else "HTTP $code: $text"
            )
        }
        val text = inputStream.bufferedReader().use(BufferedReader::readText)
        return JSONObject(text)
    }

    /** `optString` turns JSON null into the string "null"; this does not. */
    private fun JSONObject.optStringOrNull(key: String): String? =
        if (isNull(key)) null else optString(key, "").ifBlank { null }
}

data class GlassesStatus(
    val deviceId: String,
    val serial: String,
    /** false = these glasses have never contacted the server. Not the same as [online]. */
    val known: Boolean,
    val online: Boolean,
    /** Which Wi-Fi the glasses are registered on — what the user needs in order to know which network to provide. */
    val wifiSsid: String?,
    val firmwareVersion: String?,
    val latestVersion: String?,
    val updateAvailable: Boolean,
    /** Whether the glasses are holding the SSE channel; remote Wi-Fi changes and "update now" only work when true. */
    val downlinkOpen: Boolean,
    val pairedTo: String?,
)

data class GlassesLocalStatus(
    val serial: String,
    val deviceId: String,
    val firmwareVersion: String,
    val ssid: String,
    /** true = came from the app's own configuration, false = the network compiled into the firmware. */
    val fromNvs: Boolean,
    val apMode: Boolean,
    val connected: Boolean,
    val ip: String,
    val rssi: Int,
)

data class VisibleNetwork(
    val ssid: String,
    val rssi: Int,
    val secure: Boolean,
)
