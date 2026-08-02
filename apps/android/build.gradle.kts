buildscript {
    dependencies {
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.3.21")
    }
}

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.compose.compiler) apply false
    // Firebase Google Services plugin (reads google-services.json)
    id("com.google.gms.google-services") version "4.5.0" apply false
}
