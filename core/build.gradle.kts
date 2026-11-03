import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

val launcherVersionSource = tasks.register("launcherVersionSource") {
    val version = providers.gradleProperty("millida.versionName").map { it.trim() }
    val target = layout.buildDirectory.dir("generated/launcherVersion")
    inputs.property("version", version)
    outputs.dir(target)
    doLast {
        val name = version.get()
        require(Regex("[0-9A-Za-z._-]+").matches(name)) {
            "millida.versionName=$name: в User-Agent идут только буквы, цифры, точка, дефис и подчёркивание"
        }
        val file = target.get().file("net/millida/launcher/core/http/LauncherVersion.kt").asFile
        file.parentFile.mkdirs()
        file.writeText("package net.millida.launcher.core.http\n\ninternal const val LAUNCHER_VERSION = \"$name\"\n")
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
    sourceSets.main {
        kotlin.srcDir(launcherVersionSource)
    }
}

dependencies {
    api(libs.kotlin.coroutines.core)
    api(libs.kotlin.serialization.json)
    api(libs.okhttp)
    // Ed25519 for the signed website screens: the JDK has it since 15, Android 8 to 12 does not.
    implementation(libs.bouncycastle)

    testImplementation(libs.junit)
    testImplementation(libs.kotlin.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
}

tasks.test {
    testLogging {
        events("failed", "skipped")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
        showStandardStreams = false
    }
}
