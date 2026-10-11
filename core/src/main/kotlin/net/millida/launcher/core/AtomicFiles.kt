package net.millida.launcher.core

import java.io.File
import java.io.IOException

/**
 * Writes through a neighbour file and one rename. The old copy is never deleted
 * before the new one is complete, so a process killed mid-write leaves the
 * previous state readable instead of nothing at all.
 */
fun File.atomicWriteText(text: String) {
    parentFile?.mkdirs()
    val temp = File(parentFile, "$name.new")
    temp.writeText(text)
    replaceWith(temp)
}

/** The same guarantee for a copy of a local file: a half-copied jar never takes the old one's place. */
fun File.atomicCopyFrom(source: File) {
    parentFile?.mkdirs()
    val temp = File(parentFile, "$name.new")
    try {
        source.copyTo(temp, overwrite = true)
    } catch (error: IOException) {
        temp.delete()
        throw error
    }
    replaceWith(temp)
}

private fun File.replaceWith(temp: File) {
    if (temp.renameTo(this)) return

    val backup = File(parentFile, "$name.old")
    if (backup.exists()) backup.delete()
    if (exists() && !renameTo(backup)) throw IOException("не удалось заменить файл $name")
    if (!temp.renameTo(this)) {
        backup.renameTo(this)
        temp.delete()
        throw IOException("не удалось записать файл $name")
    }
    backup.delete()
}
