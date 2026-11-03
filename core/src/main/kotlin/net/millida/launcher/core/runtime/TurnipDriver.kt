package net.millida.launcher.core.runtime

import java.io.File
import java.io.IOException

import net.millida.launcher.core.i18n.tr

/**
 * The native side loads the Vulkan driver the way Zalith does: it opens one folder in the
 * linker namespace and takes liblinkerhook.so and libvulkan_freedreno.so from it (DRIVER_PATH).
 * The driver can come from the runtime pack or, in a debug build, from a folder pushed over adb,
 * so the two files are gathered into one folder of their own.
 */
object TurnipDriver {
    const val HOOK = "liblinkerhook.so"

    /** Names the folder the native side reads: the pack's files stay untouched. */
    fun prepare(packDir: File, source: File, target: File): File {
        val hook = File(packDir, HOOK)
        if (!hook.isFile) throw IOException(tr("в пакете рантайма нет {name} — обнови пакет", HOOK))
        if (!source.isFile) throw IOException(tr("нет драйвера {name}", source.name))
        target.mkdirs()
        copyIfChanged(hook, File(target, HOOK))
        copyIfChanged(source, File(target, RendererCatalog.TURNIP_LIBRARY))
        return target
    }

    private fun copyIfChanged(from: File, to: File) {
        if (to.isFile && to.length() == from.length() && to.lastModified() >= from.lastModified()) return
        val partial = File(to.parentFile, to.name + ".part")
        from.copyTo(partial, overwrite = true)
        if (!partial.renameTo(to)) {
            partial.delete()
            throw IOException(tr("{name} не записалась", to.name))
        }
        to.setReadable(true, false)
    }
}
