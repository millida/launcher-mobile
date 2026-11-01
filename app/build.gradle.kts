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
val runtimeArchivePath = providers.gradleProperty("millida.runtimeArchive")
    .getOrElse("../dist/millida-runtime-arm64.zip")

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
            abiFilters += "arm64-v8a"
        }
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
        }
        debug {
            applicationIdSuffix = ".debug"
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

    sourceSets["main"].assets.srcDir(layout.buildDirectory.dir("bridgeAssets"))
    sourceSets["main"].assets.srcDir(layout.buildDirectory.dir("processorAssets"))

    packaging {
        resources.excludes += setOf("META-INF/DEPENDENCIES", "META-INF/LICENSE*", "META-INF/NOTICE*")
        // The desktop virtual machine loads our bridge by file name through
        // java.library.path, so the library has to exist as a file on disk.
        // Without extraction it lives inside the apk and only the Android
        // linker can reach it.
        jniLibs.useLegacyPackaging = true
    }

    // Pure helpers live next to Android code; a stray framework call in a
    // static initialiser must return a default instead of failing the suite.
    testOptions {
        unitTests.isReturnDefaultValues = true
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
    implementation(libs.stream.webrtc)
    implementation(libs.kotlin.coroutines.android)
    implementation("androidx.security:security-crypto:1.1.0-alpha06")

    testImplementation(libs.junit)
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
