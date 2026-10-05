import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
    // Apply Google Services plugin — processes google-services.json
    id("com.google.gms.google-services")
}

val localProperties = Properties().apply {
    rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use(::load)
}

android {
    namespace = "com.youreyes.app"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.youreyes.app"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // Per-machine values from `-Pyoureyes.<key>=...` or local.properties (never committed):
        //   serverUrl, deviceApiToken, glassesServerUrl, glassesServerToken, spotifyClientId
        // Tokens have no default: a build without them asks for the token on screen.
        fun config(key: String, default: String = ""): String =
            project.findProperty("youreyes.$key") as String?
                ?: localProperties.getProperty("youreyes.$key")
                ?: default

        buildConfigField("String", "SERVER_URL", "\"${config("serverUrl", "https://app.visioncare-host.uk")}\"")
        buildConfigField("String", "DEVICE_API_TOKEN", "\"${config("deviceApiToken")}\"")
        buildConfigField(
            "String", "GLASSES_SERVER_URL",
            "\"${config("glassesServerUrl", "https://api.visioncare-host.uk")}\"",
        )
        buildConfigField("String", "GLASSES_SERVER_TOKEN", "\"${config("glassesServerToken")}\"")
        // A Spotify client id is public (it ships in every integrating app); the secret stays server-side.
        buildConfigField(
            "String", "SPOTIFY_CLIENT_ID",
            "\"${config("spotifyClientId", "3ba55c4b05cf489085e7461dff9d023a")}\"",
        )
        // Must match a redirect URI registered in the Spotify developer dashboard.
        val spotifyScheme = "youreyes"
        val spotifyHost = "spotify-callback"
        buildConfigField("String", "SPOTIFY_REDIRECT_URI", "\"$spotifyScheme://$spotifyHost\"")
        // spotify-auth's manifest declares the activity that catches the redirect
        // with these two placeholders left open, so the merge fails until they are
        // filled. Split from the same pair above so the uri and the intent filter
        // cannot drift apart — if they did, the login screen would come back to
        // nothing and the grant would never land.
        manifestPlaceholders["redirectSchemeName"] = spotifyScheme
        manifestPlaceholders["redirectHostName"] = spotifyHost
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        // android.util.Log and friends return defaults instead of throwing in JVM tests.
        unitTests.isReturnDefaultValues = true
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

dependencies {
    val composeBom = platform(libs.androidx.compose.bom)

    implementation(composeBom)
    implementation(libs.androidx.activity.compose)
    // registerForActivityResult requires Fragment >= 1.3.0 (lint InvalidFragmentVersionForActivityResult)
    implementation("androidx.fragment:fragment-ktx:1.8.5")
    implementation(libs.androidx.compose.material3)
    implementation("androidx.compose.material:material-icons-extended")
    implementation(libs.androidx.compose.ui.tooling.preview)
    debugImplementation(libs.androidx.compose.ui.tooling)

    // Firebase BOM — manages all Firebase SDK versions consistently
    implementation(platform("com.google.firebase:firebase-bom:34.17.0"))
    // Firebase Cloud Messaging — receives push commands from backend
    implementation("com.google.firebase:firebase-messaging")
    // Firebase Analytics (optional — included by default in Firebase setup)
    implementation("com.google.firebase:firebase-analytics")

    testImplementation(libs.junit)
    testImplementation("org.json:json:20240303")

    // ViewModel + Compose integration
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.9.0")

    // Coroutines for background registration call
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")

    // Loads photo/video thumbnails from MediaStore content:// URIs for the Album screen
    // (no network fetcher needed — Coil reads local content:// URIs natively).
    implementation("io.coil-kt.coil3:coil-compose:3.2.0")

    // (Be Vietnam Pro is bundled in res/font — no downloadable-font provider needed)

    // Navigation with a real back stack — replaces the selectedTab:Int switch, so
    // the system Back button returns to the previous screen instead of exiting.
    implementation("androidx.navigation:navigation-compose:2.8.9")

    // edit {} / SharedPreferences KTX used by ui/theme/DisplaySettings.kt
    implementation("androidx.core:core-ktx:1.15.0")

    // Fused location provider required by the location/capability contract (T07/P8).
    implementation("com.google.android.gms:play-services-location:21.3.0")

    // Spotify App Remote — the only supported way to make the Spotify app play a
    // chosen track. Deep links and MediaSession were both measured on-device:
    // ACTION_VIEW on a spotify:track: uri only navigates Spotify's UI and leaves
    // whatever was playing alone, and Spotify's MediaBrowserService refuses
    // clients that are not whitelisted (Android Auto and friends).
    //
    // Shipped as a local .aar because Spotify does not publish it to Maven
    // Central or Google's repo; downloaded from the project's official releases:
    // github.com/spotify/android-sdk (v0.8.0-appremote_v2.1.0-auth).
    implementation(files("libs/spotify-app-remote-release-0.8.0.aar"))
    // App Remote cannot raise the consent dialog on its own — with only the
    // remote .aar present, connect(showAuthView = true) just fails with
    // "Explicit user authorization is required" and nothing appears on screen.
    // This is the library that owns the login activity that collects it.
    implementation(files("libs/spotify-auth-release-2.1.0.aar"))
    implementation("androidx.browser:browser:1.8.0")
    // App Remote's protocol layer serialises over gson and the .aar declares no
    // POM, so this transitive dependency has to be named explicitly.
    implementation("com.google.code.gson:gson:2.11.0")
}
