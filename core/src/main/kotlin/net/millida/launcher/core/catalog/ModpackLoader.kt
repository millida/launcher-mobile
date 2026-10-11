package net.millida.launcher.core.catalog

import net.millida.launcher.core.loader.LoaderInstaller
import net.millida.launcher.core.loader.LoaderKind
import net.millida.launcher.core.packs.PackVersionChoice
import net.millida.launcher.core.packs.PackVersionResolver
import net.millida.launcher.core.packs.PhoneFit
import java.io.IOException

/**
 * A modpack names its loader, and the build is launched by the loader's own
 * version id, not by the game version. The loader is installed before the pack
 * is moved into place, so a pack whose loader cannot be installed never shows
 * up as a build that silently starts vanilla.
 */
class ModpackLoader(
    private val installerOf: (LoaderKind) -> LoaderInstaller?,
) : PackVersionResolver {

    override suspend fun resolve(gameVersion: String, loader: LoaderKind, loaderVersion: String): PackVersionChoice {
        if (loader == LoaderKind.VANILLA) return PackVersionChoice(gameVersion, loaderVersion)
        val installer = installerOf(loader)
            ?: throw IOException("${loader.label} на телефоне пока не ставится — выбери сборку на другом загрузчике")
        val version = loaderVersion.ifBlank {
            val candidates = installer.versions(gameVersion)
            (candidates.firstOrNull { it.stable } ?: candidates.firstOrNull())?.version
                ?: throw IOException("для $gameVersion нет версий ${loader.label} — выбери другую версию сборки")
        }
        return PackVersionChoice(installer.install(gameVersion, version), version)
    }

    companion object {
        /** Loaders a phone installs; Forge only from 1.13, which [phoneFits] decides per game version. */
        val PHONE_LOADERS: List<LoaderKind> = listOf(LoaderKind.FABRIC, LoaderKind.QUILT, LoaderKind.FORGE, LoaderKind.NEOFORGE)

        /**
         * Whether some loader and game version of a catalogue pack or pack
         * version starts on a phone. Unknown loaders or versions do not refuse:
         * the archive is read before the files and says it then.
         */
        fun phoneFits(loaders: List<String>, gameVersions: List<String>): Boolean {
            if (loaders.isEmpty()) return true
            val phone = PHONE_LOADERS.map { it.id }
            return loaders.map { it.trim().lowercase() }.filter { it in phone }.any { loader ->
                gameVersions.isEmpty() || gameVersions.any { PhoneFit.verdict(loader, it) == null }
            }
        }
    }
}
