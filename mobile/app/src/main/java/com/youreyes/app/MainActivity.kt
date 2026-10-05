package com.youreyes.app

import android.Manifest
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import com.spotify.sdk.android.auth.AuthorizationClient
import com.youreyes.app.media.SpotifyAppRemoteManager
import com.youreyes.app.telephony.IncomingCallMonitor
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.ActivityCompat
import com.youreyes.app.ui.devmode.DeveloperMode
import com.youreyes.app.ui.navigation.AppRoot
import com.youreyes.app.ui.theme.AppDemoTheme
import com.youreyes.app.ui.theme.DisplaySettings

class MainActivity : ComponentActivity() {

    private val requestPermissionsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { _ ->
        IncomingCallMonitor.start(applicationContext)
        checkAndRequestBackgroundLocation()
    }

    private val backgroundLocationLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ ->
        checkAndRequestOverlayPermission()
    }

    private val overlayPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { _ ->
        checkAndRequestIgnoreBatteryOptimizations()
    }

    private val batteryOptimizationLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { _ ->
        checkAndRequestFullScreenIntent()
    }

    private val fullScreenIntentLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { _ ->
        requestMiuiAutostart()
    }

    private val miuiAutostartLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { _ ->
        requestMiuiOtherPermissions()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Text size and contrast must be known before the first frame: a user who
        // needs the large scale should never see one frame of the small one.
        DisplaySettings.load(this)
        DeveloperMode.load(this)

        // Request runtime permissions on launch so user just taps "Allow"
        requestAppPermissions()

        // Same idea for Spotify: get the one-time authorisation out of the way
        // while someone is actually looking at the screen.
        //
        // music_play runs from an FCM push with the handset locked, and Android
        // will not let a background app put UI up — so the consent dialog that
        // App Remote wants can only ever appear here. Until it is granted, every
        // play request fails with "Explicit user authorization is required".
        // Spotify remembers the grant, so this costs one tap, once.
        com.youreyes.app.media.SpotifyAppRemoteManager.ensureAuthorized(this)

        setContent {
            AppDemoTheme {
                AppRoot()
            }
        }
    }

    /**
     * Reads why Spotify's login screen closed.
     *
     * Spotify's SSO activity opens and finishes within about 40ms when it refuses
     * a request, showing nothing at all — so without reading the result there is
     * no way to tell "user declined" from "this client id is not registered for
     * this package and fingerprint". The response carries that reason.
     *
     * Spotify's auth SDK still uses startActivityForResult, so this is the only
     * hook available; the modern Activity Result API cannot receive it.
     */
    @Deprecated("Spotify's auth SDK predates the Activity Result APIs")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != SpotifyAppRemoteManager.AUTH_REQUEST_CODE) return

        val response = AuthorizationClient.getResponse(resultCode, data)
        Log.i(
            "SpotifyAuth",
            "auth result: type=${response.type} error=${response.error} " +
                "code=${response.code} resultCode=$resultCode"
        )

        // AUTHENTICATION_SERVICE_UNAVAILABLE is the Spotify app refusing to run
        // the SSO flow at all — it says nothing about why. The browser flow talks
        // to Spotify's web auth instead, which answers in plain language
        // ("INVALID_CLIENT: Invalid redirect URI" and friends) on a page the user
        // can actually read, and can also just succeed when SSO is the only part
        // that is unhappy.
        if (response.error == "AUTHENTICATION_SERVICE_UNAVAILABLE") {
            Log.i("SpotifyAuth", "SSO unavailable, retrying through the browser")
            SpotifyAppRemoteManager.openLoginInBrowser(this)
        }
    }

    private fun requestAppPermissions() {
        val permissions = mutableListOf(
            Manifest.permission.CALL_PHONE,
            Manifest.permission.READ_PHONE_STATE,
            Manifest.permission.READ_CALL_LOG,
            Manifest.permission.ANSWER_PHONE_CALLS,
            Manifest.permission.READ_CONTACTS,
            Manifest.permission.SEND_SMS,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        val missingPermissions = permissions.filter {
            ActivityCompat.checkSelfPermission(this, it) != android.content.pm.PackageManager.PERMISSION_GRANTED
        }

        if (missingPermissions.isNotEmpty()) {
            requestPermissionsLauncher.launch(missingPermissions.toTypedArray())
        }
    }

    private fun checkAndRequestBackgroundLocation() {
        val hasFineLocation = ActivityCompat.checkSelfPermission(
            this, Manifest.permission.ACCESS_FINE_LOCATION
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        val hasBackground = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || ActivityCompat.checkSelfPermission(
            this, Manifest.permission.ACCESS_BACKGROUND_LOCATION
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED

        if (hasFineLocation && !hasBackground) {
            backgroundLocationLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        } else {
            checkAndRequestOverlayPermission()
        }
    }

    private fun checkAndRequestOverlayPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            overlayPermissionLauncher.launch(intent)
        } else {
            checkAndRequestIgnoreBatteryOptimizations()
        }
    }

    private fun checkAndRequestIgnoreBatteryOptimizations() {
        val powerManager = getSystemService(POWER_SERVICE) as? PowerManager ?: return
        if (!powerManager.isIgnoringBatteryOptimizations(packageName)) {
            val intent = Intent(
                Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                Uri.parse("package:$packageName")
            )
            batteryOptimizationLauncher.launch(intent)
        } else {
            checkAndRequestFullScreenIntent()
        }
    }

    private fun checkAndRequestFullScreenIntent() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            requestMiuiAutostart()
            return
        }
        val notificationManager = getSystemService(NotificationManager::class.java)
        if (notificationManager?.canUseFullScreenIntent() == true) {
            requestMiuiAutostart()
            return
        }
        runCatching {
            val intent = Intent(
                Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT,
                Uri.parse("package:$packageName")
            )
            fullScreenIntentLauncher.launch(intent)
        }.onFailure {
            Log.w(TAG, "Full-screen-intent settings screen unavailable: ${it.message}")
            requestMiuiAutostart()
        }
    }

    private val isXiaomi: Boolean
        get() = Build.MANUFACTURER.equals("Xiaomi", ignoreCase = true)

    private fun requestMiuiAutostart() {
        if (!isXiaomi) {
            requestMiuiOtherPermissions()
            return
        }
        runCatching {
            val intent = Intent().setComponent(
                ComponentName(
                    "com.miui.securitycenter",
                    "com.miui.permcenter.autostart.AutoStartManagementActivity",
                )
            )
            miuiAutostartLauncher.launch(intent)
        }.onFailure {
            Log.w(TAG, "MIUI Autostart screen unavailable: ${it.message}")
            requestMiuiOtherPermissions()
        }
    }

    private fun requestMiuiOtherPermissions() {
        if (!isXiaomi) return
        runCatching {
            val intent = Intent().setComponent(
                ComponentName(
                    "com.miui.securitycenter",
                    "com.miui.permcenter.permissions.PermissionsEditorActivity",
                )
            ).putExtra("extra_pkgname", packageName)
            startActivity(intent)
        }.onFailure {
            Log.w(TAG, "MIUI permission editor unavailable: ${it.message}")
        }
    }

    companion object {
        private const val TAG = "MainActivity"
    }
}
