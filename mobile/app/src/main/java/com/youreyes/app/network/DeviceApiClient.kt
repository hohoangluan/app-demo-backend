package com.youreyes.app.network

import com.youreyes.app.command.DeviceReportPayload
import com.youreyes.app.command.ReportSender
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

/**
 * Blocking HTTP client for the server's phone-facing APIs. Call it off the main thread.
 *
 * - Device API (`/api/v1/device/...`): shared Device bearer token.
 * - Phone-app API (`/auth`, `/preferences`, `/support`): the login session token.
 *
 * Every call returns a [Result]; non-2xx responses fail with the server's body.
 */
class DeviceApiClient {

    fun registerDevice(baseUrl: String, bearerToken: String, payload: DeviceRegisterPayload): Result<Boolean> =
        runCatching {
            val body = JSONObject()
                .put("user_id", payload.userId)
                .put("device_id", payload.deviceId)
                .put("platform", payload.platform)
                .put("push_token", payload.pushToken)
            request(baseUrl, "POST", "/api/v1/device/register", body, bearerToken)
            true
        }

    fun sendReport(baseUrl: String, bearerToken: String, payload: DeviceReportPayload): Result<Boolean> =
        runCatching {
            request(baseUrl, "POST", "/api/v1/device/report", payload.toJson(), bearerToken)
            true
        }

    fun sendEvent(baseUrl: String, bearerToken: String, payload: DeviceEventPayload): Result<Boolean> =
        runCatching {
            val body = JSONObject().put("device_id", payload.deviceId).put("type", payload.type)
            payload.caller?.let { caller ->
                body.put(
                    "caller",
                    JSONObject().apply {
                        caller.contactId?.let { put("contact_id", it) }
                        caller.name?.let { put("name", it) }
                        caller.numberTail?.let { put("number_tail", it) }
                        if (caller.duplicateName) put("duplicate_name", true)
                    },
                )
            }
            request(baseUrl, "POST", "/api/v1/device/event", body, bearerToken)
            true
        }

    fun linkGlassesDevice(baseUrl: String, bearerToken: String, payload: GlassesLinkPayload): Result<Boolean> =
        runCatching {
            val body = JSONObject().put("user_id", payload.userId).put("device_id", payload.deviceId)
            request(baseUrl, "POST", "/api/v1/device/glasses/link", body, bearerToken)
            true
        }

    /** Returns false when nothing was linked (not an error). */
    fun unlinkGlassesDevice(baseUrl: String, bearerToken: String, userId: String): Result<Boolean> =
        runCatching {
            val body = JSONObject().put("user_id", userId)
            request(baseUrl, "POST", "/api/v1/device/glasses/unlink", body, bearerToken)
                .getJSONObject("data").getBoolean("unlinked")
        }

    fun registerAccount(baseUrl: String, payload: AuthRegisterPayload): Result<AuthRegisterResult> = runCatching {
        val body = JSONObject()
            .put("phone_number", payload.phoneNumber)
            .put("password", payload.password)
        payload.displayName?.let { body.put("display_name", it) }
        val data = request(baseUrl, "POST", "/api/v1/auth/register", body).getJSONObject("data")
        AuthRegisterResult(
            userId = data.getString("user_id"),
            publicUserId = data.getString("public_user_id"),
            phoneNumber = data.getString("phone_number"),
        )
    }

    fun verifyOtp(baseUrl: String, payload: AuthOtpVerifyPayload): Result<AuthSession> = runCatching {
        val body = JSONObject().put("phone_number", payload.phoneNumber).put("otp_code", payload.otpCode)
        request(baseUrl, "POST", "/api/v1/auth/otp/verify", body).getJSONObject("data").toAuthSession()
    }

    fun login(baseUrl: String, payload: AuthLoginPayload): Result<AuthSession> = runCatching {
        val body = JSONObject().put("phone_number", payload.phoneNumber).put("password", payload.password)
        request(baseUrl, "POST", "/api/v1/auth/login", body).getJSONObject("data").toAuthSession()
    }

    fun logout(baseUrl: String, accessToken: String): Result<Boolean> = runCatching {
        request(baseUrl, "POST", "/api/v1/auth/logout", JSONObject(), accessToken)
        true
    }

    fun getPreferences(baseUrl: String, accessToken: String): Result<PreferencesPayload> = runCatching {
        request(baseUrl, "GET", "/api/v1/preferences", bearerToken = accessToken)
            .getJSONObject("data").toPreferences()
    }

    fun updatePreferences(
        baseUrl: String,
        accessToken: String,
        payload: PreferencesPayload,
    ): Result<PreferencesPayload> = runCatching {
        val body = JSONObject()
            .put("font_size_option", payload.fontSizeOption)
            .put("voice_option", payload.voiceOption)
            .put("high_contrast", payload.highContrast)
            .put("haptics_enabled", payload.hapticsEnabled)
        request(baseUrl, "PUT", "/api/v1/preferences", body, accessToken).getJSONObject("data").toPreferences()
    }

    fun submitSupportTicket(
        baseUrl: String,
        accessToken: String,
        category: String,
        message: String,
    ): Result<SupportTicketResult> = runCatching {
        val body = JSONObject().put("category", category).put("message", message)
        val data = request(baseUrl, "POST", "/api/v1/support/tickets", body, accessToken).getJSONObject("data")
        SupportTicketResult(
            id = data.getString("id"),
            category = data.getString("category"),
            createdAt = data.getString("created_at"),
        )
    }

    /** Adapts [sendReport] to the command pipeline's [ReportSender]. */
    fun asReportSender() = ReportSender { credentials, payload ->
        sendReport(credentials.baseUrl, credentials.bearerToken, payload).getOrDefault(false)
    }

    /** Sends one request; returns the parsed JSON body or throws with the error body. */
    private fun request(
        baseUrl: String,
        method: String,
        path: String,
        body: JSONObject? = null,
        bearerToken: String? = null,
    ): JSONObject {
        val conn = URL("${baseUrl.trimEnd('/')}$path").openConnection() as HttpURLConnection
        try {
            conn.requestMethod = method
            conn.connectTimeout = TIMEOUT_MS
            conn.readTimeout = TIMEOUT_MS
            bearerToken?.let { conn.setRequestProperty("Authorization", "Bearer $it") }
            if (body != null) {
                conn.setRequestProperty("Content-Type", "application/json")
                conn.doOutput = true
                OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { it.write(body.toString()) }
            }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            check(code in 200..299) { "$method $path failed ($code): $text" }
            return if (text.isBlank()) JSONObject() else JSONObject(text)
        } finally {
            conn.disconnect()
        }
    }

    private fun JSONObject.toAuthSession() = AuthSession(
        accessToken = getString("access_token"),
        userId = getString("user_id"),
        publicUserId = getString("public_user_id"),
        phoneNumber = getString("phone_number"),
        displayName = if (isNull("display_name")) null else getString("display_name"),
    )

    private fun JSONObject.toPreferences() = PreferencesPayload(
        fontSizeOption = getString("font_size_option"),
        voiceOption = getString("voice_option"),
        highContrast = getBoolean("high_contrast"),
        hapticsEnabled = getBoolean("haptics_enabled"),
    )

    private companion object {
        const val TIMEOUT_MS = 5_000
    }
}
