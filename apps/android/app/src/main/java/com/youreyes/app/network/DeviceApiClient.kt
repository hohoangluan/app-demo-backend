package com.youreyes.app.network

import com.youreyes.app.model.AuthLoginPayload
import com.youreyes.app.model.AuthOtpVerifyPayload
import com.youreyes.app.model.AuthRegisterPayload
import com.youreyes.app.model.AuthRegisterResult
import com.youreyes.app.model.AuthSession
import com.youreyes.app.model.DeviceRegisterPayload
import com.youreyes.app.model.DeviceReportPayload
import com.youreyes.app.model.GlassesLinkPayload
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

class DeviceApiClient {

    fun registerDevice(baseUrl: String, bearerToken: String, payload: DeviceRegisterPayload): Result<Boolean> {
        return runCatching {
            val endpointUrl = "${baseUrl.trimEnd('/')}/api/v1/device/register"
            val url = URL(endpointUrl)
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("Authorization", "Bearer $bearerToken")
            conn.doOutput = true
            conn.connectTimeout = 5000
            conn.readTimeout = 5000

            val jsonBody = JSONObject().apply {
                put("user_id", payload.userId)
                put("device_id", payload.deviceId)
                put("platform", payload.platform)
                put("push_token", payload.pushToken)
            }

            OutputStreamWriter(conn.outputStream).use { it.write(jsonBody.toString()) }

            val responseCode = conn.responseCode
            if (responseCode in 200..299) {
                true
            } else {
                val errorStream = conn.errorStream
                val responseText = errorStream?.bufferedReader()?.use(BufferedReader::readText) ?: ""
                throw IllegalStateException("Device registration failed ($responseCode): $responseText")
            }
        }
    }

    fun sendReport(baseUrl: String, bearerToken: String, payload: DeviceReportPayload): Result<Boolean> {
        return runCatching {
            val endpointUrl = "${baseUrl.trimEnd('/')}/api/v1/device/report"
            val url = URL(endpointUrl)
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("Authorization", "Bearer $bearerToken")
            conn.doOutput = true
            conn.connectTimeout = 5000
            conn.readTimeout = 5000

            val jsonBody = JSONObject().apply {
                put("user_id", payload.userId)
                put("device_id", payload.deviceId)
                put("request_id", payload.requestId)
                put("action", payload.action)
                put("execution_state", payload.executionState.value)
                put("timestamp", payload.timestamp)

                if (payload.result != null) {
                    put("result", JSONObject(payload.result))
                }
                if (payload.error != null) {
                    val errObj = JSONObject().apply {
                        put("code", payload.error.code)
                        put("message", payload.error.message)
                        put("details", JSONObject(payload.error.details))
                    }
                    put("error", errObj)
                }
            }

            OutputStreamWriter(conn.outputStream).use { it.write(jsonBody.toString()) }

            val responseCode = conn.responseCode
            if (responseCode in 200..299) {
                true
            } else {
                val errorStream = conn.errorStream
                val responseText = errorStream?.bufferedReader()?.use(BufferedReader::readText) ?: ""
                throw IllegalStateException("Device report failed ($responseCode): $responseText")
            }
        }
    }
    fun linkGlassesDevice(baseUrl: String, bearerToken: String, payload: GlassesLinkPayload): Result<Boolean> {
        return runCatching {
            val endpointUrl = "${baseUrl.trimEnd('/')}/api/v1/device/glasses/link"
            val url = URL(endpointUrl)
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("Authorization", "Bearer $bearerToken")
            conn.doOutput = true
            conn.connectTimeout = 5000
            conn.readTimeout = 5000

            val jsonBody = JSONObject().apply {
                put("user_id", payload.userId)
                put("device_id", payload.deviceId)
            }

            OutputStreamWriter(conn.outputStream).use { it.write(jsonBody.toString()) }

            val responseCode = conn.responseCode
            if (responseCode in 200..299) {
                true
            } else {
                val errorStream = conn.errorStream
                val responseText = errorStream?.bufferedReader()?.use(BufferedReader::readText) ?: ""
                throw IllegalStateException("Glasses link failed ($responseCode): $responseText")
            }
        }
    }

    // -- Demo phone-app auth (apps/backend/src/app/api/auth.py) --
    // Unlike the 3 methods above (fixed shared Device Bearer token, boolean-only
    // result), these carry no bearer token except logout's, and need the response
    // body parsed -- hence the two small private helpers below, used only by them.

    /** Reads the response body from [conn] (input stream on 2xx, error stream otherwise); never null. */
    private fun readBody(conn: HttpURLConnection): String {
        val stream = if (conn.responseCode in 200..299) conn.inputStream else conn.errorStream
        return stream?.bufferedReader()?.use(BufferedReader::readText) ?: ""
    }

    /** POSTs [jsonBody] to [path]; returns the parsed `data` object on 2xx, throws with the raw error body otherwise. */
    private fun postJson(baseUrl: String, path: String, jsonBody: JSONObject, bearerToken: String? = null): JSONObject {
        val url = URL("${baseUrl.trimEnd('/')}$path")
        val conn = url.openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.setRequestProperty("Content-Type", "application/json")
        if (bearerToken != null) {
            conn.setRequestProperty("Authorization", "Bearer $bearerToken")
        }
        conn.doOutput = true
        conn.connectTimeout = 5000
        conn.readTimeout = 5000

        OutputStreamWriter(conn.outputStream).use { it.write(jsonBody.toString()) }

        val body = readBody(conn)
        if (conn.responseCode !in 200..299) {
            throw IllegalStateException("$path failed (${conn.responseCode}): $body")
        }
        return JSONObject(body).getJSONObject("data")
    }

    private fun JSONObject.toAuthSession() = AuthSession(
        accessToken = getString("access_token"),
        userId = getString("user_id"),
        publicUserId = getString("public_user_id"),
        phoneNumber = getString("phone_number"),
        displayName = if (isNull("display_name")) null else getString("display_name"),
    )

    fun registerAccount(baseUrl: String, payload: AuthRegisterPayload): Result<AuthRegisterResult> = runCatching {
        val body = JSONObject().apply {
            put("phone_number", payload.phoneNumber)
            put("password", payload.password)
            if (payload.displayName != null) put("display_name", payload.displayName)
        }
        val data = postJson(baseUrl, "/api/v1/auth/register", body)
        AuthRegisterResult(
            userId = data.getString("user_id"),
            publicUserId = data.getString("public_user_id"),
            phoneNumber = data.getString("phone_number"),
        )
    }

    fun verifyOtp(baseUrl: String, payload: AuthOtpVerifyPayload): Result<AuthSession> = runCatching {
        val body = JSONObject().apply {
            put("phone_number", payload.phoneNumber)
            put("otp_code", payload.otpCode)
        }
        postJson(baseUrl, "/api/v1/auth/otp/verify", body).toAuthSession()
    }

    fun login(baseUrl: String, payload: AuthLoginPayload): Result<AuthSession> = runCatching {
        val body = JSONObject().apply {
            put("phone_number", payload.phoneNumber)
            put("password", payload.password)
        }
        postJson(baseUrl, "/api/v1/auth/login", body).toAuthSession()
    }

    /** POST /auth/logout takes no body -- only the session Bearer token identifies which session to revoke. */
    fun logout(baseUrl: String, accessToken: String): Result<Boolean> = runCatching {
        postJson(baseUrl, "/api/v1/auth/logout", JSONObject(), bearerToken = accessToken)
        true
    }

    // Convenience method used by FcmPushReceiver on token refresh
    fun register(
        userId: String,
        deviceId: String,
        platform: String,
        pushToken: String,
        baseUrl: String,
        bearerToken: String,
    ): Result<Boolean> = registerDevice(
        baseUrl = baseUrl,
        bearerToken = bearerToken,
        payload = com.youreyes.app.model.DeviceRegisterPayload(
            userId   = userId,
            deviceId = deviceId,
            platform = platform,
            pushToken = pushToken,
        ),
    )
}
