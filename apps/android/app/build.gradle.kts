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
    }

    buildFeatures {
        compose = true
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
}

