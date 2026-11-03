package net.millida.launcher.core.runtime

import java.io.File
import java.io.DataInputStream
import java.util.zip.ZipFile

/**
 * The Java a build's mods were compiled for. A mod built for a newer Java than the machine the game
 * starts on dies at class loading with "Unsupported class file major version" (516 native/Java
 * failures of 195 installs, 19-26.10.2026): the version description asks for the Java of the game,
 * not of its mods. The class files tell: their header carries the major version (52 is Java 8,
 * 61 is 17, 65 is 21, 69 is 25).
 */
object ModJava {
    private const val MAGIC = 0xCAFEBABE.toInt()
    private const val JAVA_OFFSET = 44
    private const val CLASSES_PER_JAR = 3
    private const val MAX_JAVA = 40

    /** The newest Java any mod in [mods] needs, or null when no mod class could be read. */
    fun required(mods: File): Int? =
        mods.listFiles { file -> file.isFile && file.extension.equals("jar", ignoreCase = true) }
            .orEmpty()
            .mapNotNull(::of)
            .maxOrNull()

    /** The Java a jar needs by its first few classes; the classes of `META-INF/versions` are for newer Javas only on purpose. */
    fun of(jar: File): Int? = runCatching {
        ZipFile(jar).use { zip ->
            zip.entries().asSequence()
                .filter { !it.isDirectory && it.name.endsWith(".class") && !it.name.startsWith("META-INF/") }
                .take(CLASSES_PER_JAR)
                .mapNotNull { entry -> zip.getInputStream(entry).use { javaOfClass(DataInputStream(it)) } }
                .maxOrNull()
        }
    }.getOrNull()

    private fun javaOfClass(input: DataInputStream): Int? {
        if (input.readInt() != MAGIC) return null
        input.readUnsignedShort()
        val java = input.readUnsignedShort() - JAVA_OFFSET
        return java.takeIf { it in 1..MAX_JAVA }
    }
}
