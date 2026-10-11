plugins {
    id("java-library")
}

// The driver runs inside whatever Java the runtime pack carries, so it is
// compiled for the oldest one a pack may hold.
tasks.withType<JavaCompile>().configureEach {
    options.release.set(8)
}

tasks.jar {
    archiveBaseName.set("millida-processors")
    manifest {
        attributes(
            "Implementation-Title" to "Millida Forge processor driver",
            "Main-Class" to "net.millida.launcher.processors.ProcessorDriver",
        )
    }
}
