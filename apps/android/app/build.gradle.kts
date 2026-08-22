plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
    // Apply Google Services plugin — processes google-services.json
    id("com.google.gms.google-services")
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

        // Spotify App Remote needs the client id at connect() time. A Spotify
        // client id is not a secret — it ships inside every app that integrates
        // Spotify — so it lives here rather than in the backend's .env, which the
        // handset cannot read anyway. The matching client SECRET stays server-side.
        //
        // Override per machine by putting spotifyClientId=... in local.properties.
        val spotifyClientId = (
            project.findProperty("spotifyClientId") as String?
                ?: "3ba55c4b05cf489085e7461dff9d023a"
            )
        buildConfigField("String", "SPOTIFY_CLIENT_ID", "\"$spotifyClientId\"")
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
