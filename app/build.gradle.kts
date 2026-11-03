import java.time.Duration
import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.compose.compiler)
}

/**
 * The runtime pack can travel inside the application instead of next to it:
 * one file to hand a player, nothing to import by hand. Off by default, so an
 * everyday build stays a couple of megabytes.
 */
abstract class BundleRuntimeTask : DefaultTask() {

    @get:InputFile
    abstract val archive: RegularFileProperty

    @get:OutputDirectory
    abstract val assets: DirectoryProperty

    @TaskAction
    fun copyIntoAssets() {
        val target = assets.get().asFile
        target.mkdirs()
        target.listFiles()?.forEach { it.delete() }
        archive.get().asFile.copyTo(File(target, "millida-runtime.zip"), overwrite = true)
    }
}

val bundleRuntime = providers.gradleProperty("millida.bundleRuntime").isPresent
val releaseVersionCode = providers.gradleProperty("millida.versionCode").get().trim().toInt()
val releaseVersionName = providers.gradleProperty("millida.versionName").get().trim()
// A release-signed build for the test phone: the debug hooks ride along so it installs over the
// store build (same key) and scripts drive it. Never published, see release.yml.
val testHooks = providers.gradleProperty("millida.testHooks").isPresent
// Pictures of the landscape lobby for review, rendered by Robolectric into this folder.
val lobbyShots = providers.gradleProperty("millida.shots").map { rootProject.file(it).absolutePath }
val runtimeArchivePath = providers.gradleProperty("millida.runtimeArchive")
    .getOrElse("../dist/millida-runtime-arm64.zip")

// 64-bit ARM alone unless asked otherwise: the 32-bit build joins a release only
// once its runtime is published, since an application that installs on a phone
// and then finds no Java for it is worse than one that does not install.
val gameAbis = providers.gradleProperty("millida.abis").getOrElse("arm64-v8a")
    .split(',').map { it.trim() }.filter { it.isNotEmpty() }

android {
    namespace = "net.millida.launcher"
    compileSdk = 35

    defaultConfig {
        applicationId = "net.millida.launcher"
        minSdk = 26
        targetSdk = 35
        versionCode = releaseVersionCode
        versionName = releaseVersionName
        ndk {
            abiFilters += gameAbis
        }
        // The launcher is landscape-only, like the game and the desktop lobby.
        manifestPlaceholders["launcherOrientation"] = "sensorLandscape"
        buildConfigField("boolean", "SELF_UPDATE", "true")
        buildConfigField("boolean", "OFFLINE_LOGIN", "true")
        buildConfigField("boolean", "OTHER_STORE_LINKS", "true")
        buildConfigField("boolean", "PD_CONSENT", "false")
        // Test-only hooks (offline nick, scripted pack runs, stand override); on in debug and preview.
        buildConfigField("boolean", "TEST_HOOKS", "false")
        // The offline nick for players without a Minecraft licence. 1.10.2 shipped without it and 92% of
        // new installs never got past «Как будешь играть?» (telemetry 03.11.2026), so it stays on.
        buildConfigField("boolean", "GUEST_LOGIN", "true")
    }

    signingConfigs {
        // The key never lives in the project: the path and the password come
        // from the release job environment or local.properties, never from git.
        create("millida") {
            val local = Properties()
            val file = rootProject.file("local.properties")
            if (file.exists()) file.inputStream().use { local.load(it) }
            fun setting(env: String, key: String): String? =
                providers.environmentVariable(env).orNull?.takeIf { it.isNotBlank() } ?: local.getProperty(key)
            val store = setting("MILLIDA_KEYSTORE_FILE", "millida.release.keystore")
            val password = setting("MILLIDA_KEYSTORE_PASSWORD", "millida.release.password")
            if (store != null && File(store).isFile) {
                storeFile = File(store)
                storePassword = password
                keyAlias = setting("MILLIDA_KEY_ALIAS", "millida.release.alias")
                keyPassword = setting("MILLIDA_KEY_PASSWORD", "millida.release.key.password") ?: password
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("millida")
            buildConfigField("boolean", "TEST_HOOKS", testHooks.toString())
        }
        debug {
            applicationIdSuffix = ".debug"
            buildConfigField("boolean", "TEST_HOOKS", "true")
            buildConfigField("boolean", "GUEST_LOGIN", "true")
        }
        // The debug build with the debug hooks but not debuggable: ART compiles it ahead of
        // time like the release, so the test phone shows the speed players get (a debuggable
        // APK runs interpreted, 40-57 slow UI frames per 15 s against 0-2).
        create("preview") {
            initWith(getByName("debug"))
            isDebuggable = false
            matchingFallbacks += "debug"
        }
        // The RuStore build is the release with the store's rules applied: updates
        // come from the store only, the game runs on a Minecraft licence only,
        // no links to other stores, and the player agrees to the processing of
        // personal data before anything else.
        create("rustore") {
            initWith(getByName("release"))
            matchingFallbacks += "release"
            buildConfigField("boolean", "SELF_UPDATE", "false")
            buildConfigField("boolean", "OFFLINE_LOGIN", "false")
            buildConfigField("boolean", "OTHER_STORE_LINKS", "false")
            buildConfigField("boolean", "PD_CONSENT", "true")
        }

    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    androidResources {
        // The pack is a zip already: packing it again costs build time and
        // gives nothing back.
        noCompress += "zip"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    if (testHooks) sourceSets["release"].apply {
        kotlin.srcDir("src/debug/kotlin")
        res.srcDir("src/debug/res")
        manifest.srcFile("src/debug/AndroidManifest.xml")
    }
    sourceSets["preview"].apply {
        kotlin.srcDir("src/debug/kotlin")
        res.srcDir("src/debug/res")
        manifest.srcFile("src/debug/AndroidManifest.xml")
    }
    sourceSets["main"].assets.srcDir(layout.buildDirectory.dir("bridgeAssets"))
    sourceSets["main"].assets.srcDir(layout.buildDirectory.dir("processorAssets"))

    packaging {
        resources.excludes += setOf("META-INF/DEPENDENCIES", "META-INF/LICENSE*", "META-INF/NOTICE*")
        // The desktop virtual machine loads our bridge by file name through
        // java.library.path, so the library has to exist as a file on disk.
        // Without extraction it lives inside the apk and only the Android
        // linker can reach it.
        jniLibs.useLegacyPackaging = true
        // Only the Java class of bytehook is needed; the library itself comes from the runtime pack.
        jniLibs.excludes += "**/libbytehook.so"
    }

    // Pure helpers live next to Android code; a stray framework call in a
    // static initialiser must return a default instead of failing the suite.
    testOptions {
        unitTests.isReturnDefaultValues = true
        unitTests.isIncludeAndroidResources = true
        unitTests.all { test ->
            if (lobbyShots.isPresent) {
                test.systemProperty("millida.shots", lobbyShots.get())
                test.systemProperty("roborazzi.test.record", "true")
                test.timeout.set(Duration.ofMinutes(20))
                test.maxHeapSize = "2g"
                test.testLogging {
                    events("started", "passed", "skipped", "failed")
                    showStandardStreams = true
                    exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
                }
            } else {
                test.exclude("**/LobbyShotsTest*")
            }
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(project(":core"))
    implementation(project(":runtime"))

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.coil.compose)
    implementation(libs.androidx.webkit)
    implementation(libs.stream.webrtc)
    implementation(libs.kotlin.coroutines.android)
    implementation("androidx.security:security-crypto:1.1.0-alpha06")
    // The pack's libbytehook.so looks up this Java class while it loads. Without it the first two
    // starts of every build abort (ClassNotFoundException inside JNI_OnLoad) before the interceptor is given up.
    implementation("com.bytedance:bytehook:1.1.1")

    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.roborazzi)
    testImplementation(libs.androidx.test.core)
    testImplementation(platform(libs.compose.bom))
    testImplementation(libs.compose.ui.test.junit4)
    debugImplementation(libs.compose.ui.test.manifest)
}

/**
 * The window bridge travels inside the application, not inside the runtime pack:
 * a fix in it then ships with a 14 MB update instead of a 119 MB one.
 */
val copyBridgeJar by tasks.registering(Copy::class) {
    from(project(":bridge").tasks.named("jar"))
    into(layout.buildDirectory.dir("bridgeAssets"))
    rename { "millida-glfw.jar" }
}

/** The driver that runs the Forge and NeoForge install steps inside the Java machine. */
val copyProcessorsJar by tasks.registering(Copy::class) {
    from(project(":processors").tasks.named("jar"))
    into(layout.buildDirectory.dir("processorAssets"))
    rename { "millida-processors.jar" }
}

tasks.named("preBuild") { dependsOn(copyBridgeJar, copyProcessorsJar) }

// The generated folder is handed to the build through AGP itself, so every
// task that reads assets knows it has to wait for the copy.
androidComponents {
    onVariants { variant ->
        if (!bundleRuntime) return@onVariants
        val copy = tasks.register<BundleRuntimeTask>("bundleRuntime${variant.name.replaceFirstChar { it.uppercase() }}") {
            archive.set(project.file(runtimeArchivePath))
        }
        variant.sources.assets?.addGeneratedSourceDirectory(copy, BundleRuntimeTask::assets)
    }
}
