package net.millida.launcher.core.runtime

import java.io.File

import net.millida.launcher.core.i18n.tr
/**
 * What a version of the game asks of the Java machine. The minimum comes from
 * the version description; the ceiling exists because LaunchWrapper, the main
 * class of Forge before 1.13, casts the system class loader to URLClassLoader,
 * and that class loader is gone since Java 9. Forge 1.13-1.16.5 starts through
 * ModLauncher and runs its core mods on the Nashorn script engine, which left
 * Java in 15: on a newer Java builds with such mods stop before the game. That
 * ceiling is not [strict]: most of those builds do start on a newer Java, so a
 * phone that cannot fetch Java 8 right now still plays on the Java it has.
 */
data class JavaRequirement(val minimum: Int, val maximum: Int? = null, val strict: Boolean = true) {

    fun accepts(major: Int): Boolean = major >= minimum && (maximum == null || major <= maximum)

    /** The oldest fitting Java: the newest one only adds risk for an old game. */
    fun pick(available: Collection<Int>): Int? = available.filter(::accepts).minOrNull()

    /**
     * The same requirement for a build whose mods need Java [modsJava]. A ceiling below it stays:
     * old Forge cannot start on a newer Java at all, and its mods are not compiled for a newer one.
     */
    fun withMods(modsJava: Int?): JavaRequirement = when {
        modsJava == null || modsJava <= minimum -> this
        maximum != null && modsJava > maximum -> this
        else -> copy(minimum = modsJava)
    }

    fun describe(): String = when (maximum) {
        null -> tr("Java {minimum} или новее", minimum)
        minimum -> "Java $minimum"
        else -> tr("Java от {minimum} до {maximum}", minimum, maximum)
    }

    companion object {
        const val LAUNCHWRAPPER_MAIN = "net.minecraft.launchwrapper.Launch"
        const val MODLAUNCHER_MAIN = "cpw.mods.modlauncher.Launcher"
        private const val LEGACY_LAST_JAVA = 8
        private val LEGACY_MAINS = setOf(LAUNCHWRAPPER_MAIN, MODLAUNCHER_MAIN)

        fun of(javaMajor: Int, mainClass: String): JavaRequirement {
            val ceiling = LEGACY_LAST_JAVA.takeIf { mainClass in LEGACY_MAINS && javaMajor <= LEGACY_LAST_JAVA }
            return JavaRequirement(javaMajor, ceiling, strict = ceiling == null || mainClass == LAUNCHWRAPPER_MAIN)
        }
    }
}

/**
 * Java 8 keeps its native libraries under `lib/<arch>`, Java 9 and newer
 * straight under `lib`. The launcher inside the runtime builds its library
 * path from this folder, so the two layouts cannot share one hardcoded path.
 * The machine itself sits in `server` there, and in `client` for the 32-bit
 * ARM builds of Java 8, which have no server compiler.
 */
object JavaHomeLayout {
    private val ARCH_FOLDERS = listOf("aarch64", "arm64", "aarch32", "arm")
    private val JVM_FOLDERS = listOf("server", "client")
    private const val JVM = "libjvm.so"

    fun nativeDir(javaHome: File): File {
        val lib = File(javaHome, "lib")
        return ARCH_FOLDERS.map { File(lib, it) }.firstOrNull { jvmIn(it) != null } ?: lib
    }

    /** The folder that holds libjvm.so; `server` when the Java home has none yet. */
    fun jvmDir(javaHome: File): File {
        val native = nativeDir(javaHome)
        return jvmIn(native) ?: File(native, JVM_FOLDERS.first())
    }

    private fun jvmIn(dir: File): File? = JVM_FOLDERS.map { File(dir, it) }.firstOrNull { File(it, JVM).isFile }

    /** The same folder relative to the Java home, for paths built next to it. */
    fun nativeDirName(javaHome: File): String =
        nativeDir(javaHome).relativeTo(javaHome).invariantSeparatorsPath
}
