# Android demo

The P0 Android project contains one `app` module, a minimal Compose Material 3
Overview screen, preserved architecture boundaries, and deterministic JVM unit
tests. Firebase, Room, WorkManager, Hilt, and provider integrations are deferred
until their implementation phases.

## Toolchain

- JDK 17 or newer supported by Gradle.
- Android SDK Platform 37 and Build Tools 36.0.0.
- Android Gradle Plugin 9.3.0 with Gradle 9.5.0.
- Kotlin/Compose compiler 2.3.21 and Compose BOM 2026.06.00.
- `minSdk 24`, `targetSdk 36`, and `compileSdk 37`.

## Bootstrap the Windows toolchain

From the repository root, run the bootstrap without the package-install switch:

```powershell
.\scripts\bootstrap-android.ps1
```

It downloads checksum-verified Eclipse Temurin 17 and Android command-line
tools into `D:\tmp\app-demo-android-toolchain` by default. Override that
location with `-RootPath`. The script never accepts Android SDK licenses.

Run the exact interactive `sdkmanager --licenses` command printed by the
script, review each license, and answer the prompts yourself. After acceptance,
install the project SDK packages with:

```powershell
.\scripts\bootstrap-android.ps1 -InstallSdkPackages
```

That second phase installs `platforms;android-37`, `build-tools;36.0.0`, and
`platform-tools`. It still does not pipe answers or accept licenses for you.

Set `ANDROID_HOME` (or configure `sdk.dir` in uncommitted `local.properties`),
then run from this directory:

```powershell
.\gradlew.bat test lint assembleDebug
```

Never commit `local.properties`, signing material, Firebase configuration, API
keys, tokens, or provider credentials.
