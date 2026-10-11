package net.millida.launcher.core

import java.io.File

class GameLayout(val root: File) {
    val versions: File get() = File(root, "versions")
    val libraries: File get() = File(root, "libraries")
    val assets: File get() = File(root, "assets")
    val assetIndexes: File get() = File(assets, "indexes")
    val assetObjects: File get() = File(assets, "objects")
    val objects: File get() = File(root, "objects")
    val profiles: File get() = File(root, "profiles")
    val runtimes: File get() = File(root, "runtimes")
    val caches: File get() = File(root, "caches")
    val logs: File get() = File(root, "logs")

    fun versionDir(id: String): File = File(versions, id)

    fun versionJson(id: String): File = File(versionDir(id), "$id.json")

    fun versionJar(id: String): File = File(versionDir(id), "$id.jar")

    fun library(path: String): File = File(libraries, path)

    fun assetIndex(id: String): File = File(assetIndexes, "$id.json")

    fun logConfig(id: String): File = SafePath.join(File(assets, "log_configs"), id)

    fun assetObject(hash: String): File = File(File(assetObjects, hash.substring(0, 2)), hash)

    fun profileDir(id: String): File = File(profiles, id)

    fun ensure() {
        listOf(versions, libraries, assetIndexes, assetObjects, objects, profiles, runtimes, caches, logs)
            .forEach { it.mkdirs() }
    }
}
