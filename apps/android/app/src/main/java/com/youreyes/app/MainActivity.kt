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
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.ActivityCompat
import com.youreyes.app.ui.community.CommunityScreen
import com.youreyes.app.ui.features.FeaturesScreen
import com.youreyes.app.ui.functiontest.FunctionTestRoute
import com.youreyes.app.ui.glasses.GlassesLinkRoute
import com.youreyes.app.ui.overview.OverviewRoute
import com.youreyes.app.ui.profile.ProfileRoute
import com.youreyes.app.ui.safety.SafetyScreen
import com.youreyes.app.ui.theme.AppDemoTheme
import com.youreyes.app.ui.theme.YourEyesBorder
import com.youreyes.app.ui.theme.YourEyesCyan
import com.youreyes.app.ui.theme.YourEyesInk
import com.youreyes.app.ui.theme.YourEyesMintSoft
import com.youreyes.app.ui.theme.YourEyesMuted

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

private data class NavigationItemData(
    val label: String,
    val icon: ImageVector,
)

@Composable
private fun AppRoot() {
    var selectedTab by remember { mutableIntStateOf(0) }

    val navItems = listOf(
        NavigationItemData("Trang chủ", Icons.Default.Home),
        NavigationItemData("Tính năng", Icons.Default.Star),
        NavigationItemData("An toàn", Icons.Default.Favorite),
        NavigationItemData("Cộng đồng", Icons.Default.Share),
        NavigationItemData("Hồ sơ", Icons.Default.Person),
    )

    Scaffold(
        bottomBar = {
            NavigationBar(
                containerColor = Color.White,
                contentColor = YourEyesInk,
                tonalElevation = 8.dp,
                modifier = Modifier.height(68.dp),
            ) {
                navItems.forEachIndexed { index, item ->
                    val isSelected = selectedTab == index
                    NavigationBarItem(
                        selected = isSelected,
                        onClick = { selectedTab = index },
                        icon = {
                            Box(
                                modifier = Modifier
                                    .size(38.dp)
                                    .clip(CircleShape)
                                    .background(if (isSelected) YourEyesMintSoft else Color.Transparent),
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(
                                    imageVector = item.icon,
                                    contentDescription = item.label,
                                    tint = if (isSelected) YourEyesCyan else YourEyesMuted,
                                    modifier = Modifier.size(20.dp),
                                )
                            }
                        },
                        label = {
                            Text(
                                text = item.label,
                                style = androidx.compose.material3.MaterialTheme.typography.labelSmall.copy(
                                    fontWeight = if (isSelected) FontWeight.Black else FontWeight.Bold,
                                    fontSize = 11.sp,
                                    color = if (isSelected) YourEyesCyan else YourEyesMuted,
                                ),
                            )
                        },
                        colors = NavigationBarItemDefaults.colors(
                            indicatorColor = Color.Transparent,
                        ),
                    )
                }
            }
        },
    ) { padding ->
        val modifier = Modifier
            .fillMaxSize()
            .padding(padding)

        when (selectedTab) {
            0 -> OverviewRoute(
                modifier = modifier,
                onNavigateToFeatures = { selectedTab = 1 },
                onNavigateToProfile = { selectedTab = 4 },
            )
            1 -> FeaturesScreen(modifier = modifier)
            2 -> SafetyScreen(
                modifier = modifier,
                onNavigateToProfile = { selectedTab = 4 },
            )
            3 -> CommunityScreen(modifier = modifier)
            4 -> ProfileRoute(
                modifier = modifier,
                onNavigateToDevTest = { selectedTab = 5 },
            )
            5 -> FunctionTestRoute(modifier = modifier)
            else -> GlassesLinkRoute(modifier = modifier)
        }
    }
}
