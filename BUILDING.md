# How to build Millida Launcher for Android yourself

Russian version: [BUILDING.ru.md](BUILDING.ru.md)

Three commands. The first build takes longer: Gradle downloads the dependencies once. After that, a couple of minutes.

## 1. Install the tools

The easiest way is [Android Studio](https://developer.android.com/studio): it already has a JDK and the Android SDK. Then go to "Settings" → "Languages & Frameworks" → "Android SDK":

- the "SDK Platforms" tab: **Android 15 (API 35)**;
- the "SDK Tools" tab: **NDK (Side by side) 27** and **CMake 3.22.1**.

Without Android Studio: JDK 17 or newer and the [command-line tools](https://developer.android.com/studio#command-line-tools-only):

```bash
sdkmanager "platforms;android-35" "ndk;27.0.12077973" "cmake;3.22.1"
```

Tell the build where the SDK is, with the `ANDROID_HOME` variable or a `local.properties` file in the root of the repository:

```properties
sdk.dir=C\:\\Users\\<name>\\AppData\\Local\\Android\\Sdk
```

Android Studio creates this file itself when you open the project in it.

## 2. Build

```bash
git clone https://github.com/millida/launcher-mobile.git
cd launcher-mobile
./gradlew :app:assembleDebug
```

In PowerShell: `.\gradlew :app:assembleDebug`.

To build exactly the version served on the website, take the latest "Millida Mobile X.Y.Z" commit in the [history](https://github.com/millida/launcher-mobile/commits/main).

## 3. Get the result

`app/build/outputs/apk/debug/app-debug.apk`: copy it to the phone and open it, or install it over a cable:

```bash
adb install app/build/outputs/apk/debug/app-debug.apk
```

You need a phone with Android 8.0 or newer and a 64-bit processor (arm64).

## How your build differs from ours

| | Your build | From millida.net |
| --- | --- | --- |
| Code | The same as in this repository | The same: every release here is a "Millida Mobile X.Y.Z" commit |
| Signature | The debug key of the Android SDK | The Millida key. No developer publishes it: with it, anyone could release an app in our name |
| App | A separate one, `net.millida.launcher.debug`: it installs next to the official one and does not touch it | `net.millida.launcher` |
| Java and graphics | Downloaded on the first game launch | Already inside the APK |

## How to check the official APK

- Download only from [millida.net/launcher](https://millida.net/launcher). Anything else is not ours.
- The SHA-256 of the file must match the one published in [latest.json](https://launcher-storage.millida.net/mobile/latest.json): on Windows `certutil -hashfile <file> SHA256`, on macOS and Linux `sha256sum <file>`.
- The signing key fingerprint, `apksigner verify --print-certs <file>`, must match `sha256_cert_fingerprints` in [assetlinks.json](https://millida.net/.well-known/assetlinks.json).

## If it does not build

| Error | What to do |
| --- | --- |
| `SDK location not found` | The SDK path is not set: `ANDROID_HOME` or `local.properties`, step 1 |
| `Android Gradle plugin requires Java 17 to run` | Install JDK 17 or newer, or point `JAVA_HOME` to the `jbr` folder of Android Studio |
| An error about the NDK or CMake | Install NDK 27 and CMake 3.22.1 in "SDK Tools", step 1 |
| Something else | [Open an issue](https://github.com/millida/launcher-mobile/issues/new) and attach the output of the command |
