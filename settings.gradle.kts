pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven("https://maven.fabricmc.net")
    }
}

rootProject.name = "millida-mobile"

include(":core")
include(":runtime")
include(":app")
include(":bridge")
include(":processors")
