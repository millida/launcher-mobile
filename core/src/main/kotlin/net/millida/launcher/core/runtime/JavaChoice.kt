package net.millida.launcher.core.runtime

import java.io.File

/**
 * What a version of the game asks of the Java machine. The minimum comes from
 * the version description; the ceiling exists because LaunchWrapper, the main
 * class of Forge before 1.13, casts the system class loader to URLClassLoader,
 * and that class loader is gone since Java 9.
 */
data class JavaRequirement(val minimum: Int, val maximum: Int? = null) {

    fun accepts(major: Int): Boolean = major >= minimum && (maximum == null || major <= maximum)

    /** The oldest fitting Java: the newest one only adds risk for an old game. */
    fun pick(available: Collection<Int>): Int? = available.filter(::accepts).minOrNull()

    fun describe(): String = when (maximum) {
        null -> "Java $minimum или новее"
        minimum -> "Java $minimum"
        else -> "Java от $minimum до $maximum"
    }

    companion object {
        const val LAUNCHWRAPPER_MAIN = "net.minecraft.launchwrapper.Launch"
        private const val LAUNCHWRAPPER_LAST_JAVA = 8

        fun of(javaMajor: Int, mainClass: String): JavaRequirement {
            val ceiling = LAUNCHWRAPPER_LAST_JAVA.takeIf {
                mainClass == LAUNCHWRAPPER_MAIN && javaMajor <= LAUNCHWRAPPER_LAST_JAVA
            }
            return JavaRequirement(javaMajor, ceiling)
        }
    }
}

/**
 * Java 8 keeps its native libraries under `lib/<arch>`, Java 9 and newer
 * straight under `lib`. The launcher inside the runtime builds its library
 * path from this folder, so the two layouts cannot share one hardcoded path.
 */
object JavaHomeLayout {
    private val ARCH_FOLDERS = listOf("aarch64", "arm64")

    fun nativeDir(javaHome: File): File {
        val lib = File(javaHome, "lib")
        return ARCH_FOLDERS.map { File(lib, it) }
            .firstOrNull { File(File(it, "server"), "libjvm.so").isFile }
            ?: lib
    }

    /** The same folder relative to the Java home, for paths built next to it. */
    fun nativeDirName(javaHome: File): String =
        nativeDir(javaHome).relativeTo(javaHome).invariantSeparatorsPath
}
