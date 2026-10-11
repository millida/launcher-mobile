package net.millida.launcher.core.content

import net.millida.launcher.core.GameLayout
import net.millida.launcher.core.SafePath
import net.millida.launcher.core.catalog.ContentKind
import net.millida.launcher.core.profile.ContentFile
import net.millida.launcher.core.profile.DISABLED_SUFFIX
import java.io.File
import java.io.IOException

/**
 * What the build already has, in the three spellings a catalogue answer can
 * arrive under: the identifier inside the jar, the human title and the file
 * name. A dependency is counted as satisfied when any of them matches, because
 * the same mod reaches a build under all three at different times.
 */
class InstalledIndex private constructor(
    private val ids: MutableSet<String>,
    private val titles: MutableSet<String>,
    private val files: MutableSet<String>,
) {
    fun has(vararg names: String): Boolean = names.any { raw ->
        val key = normalize(raw)
        key.isNotEmpty() && (key in ids || key in titles || key in files)
    }

    fun remember(vararg names: String) {
        names.forEach { raw ->
            val key = normalize(raw)
            if (key.isNotEmpty()) ids += key
        }
    }

    companion object {
        fun of(files: List<ContentFile>): InstalledIndex = InstalledIndex(
            ids = files.mapNotNull { it.modId.takeIf(String::isNotBlank) }.map(::normalize).toMutableSet(),
            titles = files.map { normalize(it.title) }.filter { it.isNotEmpty() }.toMutableSet(),
            files = files.map { normalize(baseName(it.name)) }.filter { it.isNotEmpty() }.toMutableSet(),
        )

        /**
         * Titles meet as "Fabric API", "fabric-api" and "fabricapi" depending on
         * who answered, so comparison happens on letters and digits only.
         */
        fun normalize(raw: String): String =
            raw.lowercase().filter { it.isLetterOrDigit() }

        fun baseName(fileName: String): String =
            fileName.removeSuffix(DISABLED_SUFFIX).substringBeforeLast('.')
    }
}

/**
 * The content folder of a build, checked against the store root before anything
 * reads or writes inside it: the directory arrives from the interface layer.
 */
@Throws(IOException::class)
internal fun contentFolder(layout: GameLayout, profileDir: File, kind: ContentKind): File {
    if (!SafePath.isInside(layout.profiles, profileDir)) {
        throw IOException("папка сборки лежит вне хранилища профилей: ${profileDir.name}")
    }
    return File(profileDir, kind.folder)
}
