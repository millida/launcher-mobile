plugins {
    id("java-library")
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

dependencies {
    // Only the core of the desktop library is borrowed for buffer types; the
    // window and input part of it is exactly what this module replaces.
    compileOnly("org.lwjgl:lwjgl:3.3.3")
    compileOnly("org.lwjgl:lwjgl-opengl:3.3.3")
}

tasks.jar {
    archiveBaseName.set("millida-glfw")
    manifest {
        attributes(
            "Implementation-Title" to "Millida GLFW bridge",
            "Implementation-Version" to (project.version.toString()),
        )
    }
}
