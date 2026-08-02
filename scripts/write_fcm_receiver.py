"""Write FcmPushReceiver.kt with proper UTF-8 encoding."""
import pathlib

output = pathlib.Path(
    "apps/android/app/src/main/java/com/innostar/appdemo/fcm/FcmPushReceiver.kt"
)
output.parent.mkdir(parents=True, exist_ok=True)

# Build content avoiding PowerShell variable-interpolation issues by writing via Python file
content = (
    "package com.innostar.appdemo.fcm\n"
    "\n"
    "import android.content.Context\n"
    "import android.util.Log\n"
    "import com.google.firebase.messaging.FirebaseMessagingService\n"
    "import com.google.firebase.messaging.RemoteMessage\n"
    "import com.innostar.appdemo.data.AppDatabaseHelper\n"
    "import com.innostar.appdemo.dispatcher.CommandDispatcher\n"
    "\n"
    "// FCM Push Receiver - entry point for commands pushed from the backend.\n"
    "// Backend sends DATA messages: request_id, user_id, device_id, action, params_json, base_url, bearer_token\n"
    "class FcmPushReceiver : FirebaseMessagingService() {\n"
    "\n"
    "    override fun onNewToken(token: String) {\n"
    "        super.onNewToken(token)\n"
    '        Log.i(TAG, "New FCM token: $token")\n'
    "        // TODO: re-register with backend /api/v1/device/register\n"
    "    }\n"
    "\n"
    "    override fun onMessageReceived(message: RemoteMessage) {\n"
    "        super.onMessageReceived(message)\n"
    "        val data = message.data\n"
    '        Log.d(TAG, "FCM message received, keys=${data.keys}")\n'
    "\n"
    '        val requestId   = data["request_id"]   ?: return logMissing("request_id")\n'
    '        val userId      = data["user_id"]       ?: return logMissing("user_id")\n'
    '        val deviceId    = data["device_id"]     ?: return logMissing("device_id")\n'
    '        val action      = data["action"]        ?: return logMissing("action")\n'
    '        val paramsJson  = data["params_json"]   ?: "{}"\n'
    '        val baseUrl     = data["base_url"]\n'
    '        val bearerToken = data["bearer_token"]\n'
    "\n"
    "        val ctx: Context = applicationContext\n"
    "        val dispatcher = CommandDispatcher(\n"
    "            dbHelper = AppDatabaseHelper(ctx),\n"
    "            context  = ctx,\n"
    "        )\n"
    "\n"
    "        val result = dispatcher.processPushCommand(\n"
    "            requestId   = requestId,\n"
    "            userId      = userId,\n"
    "            deviceId    = deviceId,\n"
    "            action      = action,\n"
    "            paramsJson  = paramsJson,\n"
    "            baseUrl     = baseUrl,\n"
    "            bearerToken = bearerToken,\n"
    "        )\n"
    "\n"
    '        Log.i(TAG, "Dispatch result for $requestId")\n'
    "        Log.d(TAG, result.toString())\n"
    "    }\n"
    "\n"
    "    private fun logMissing(field: String) {\n"
    '        Log.w(TAG, "FCM message missing field: $field - ignoring")\n'
    "    }\n"
    "\n"
    "    companion object {\n"
    '        private const val TAG = "FcmPushReceiver"\n'
    "    }\n"
    "}\n"
)

output.write_text(content, encoding="utf-8")
print(f"Written {len(content)} bytes to {output}")
