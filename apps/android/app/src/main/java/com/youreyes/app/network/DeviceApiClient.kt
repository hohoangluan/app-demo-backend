package com.youreyes.app.network

import com.youreyes.app.model.DeviceRegisterPayload
import com.youreyes.app.model.DeviceReportPayload
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
