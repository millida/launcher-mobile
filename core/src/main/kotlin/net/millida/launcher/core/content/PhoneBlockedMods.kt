package net.millida.launcher.core.content

import net.millida.launcher.core.profile.DISABLED_SUFFIX
import java.io.File

/**
 * Mods that load desktop-only native code while the game starts, so the whole
 * game dies before its menu on a phone. They are switched off the same way the
 * player switches a mod off, and can be switched back on by hand.
 */
object PhoneBlockedMods {

    /** Mod id to the reason shown to the player. */
    val KNOWN: Map<String, String> = mapOf(
        "flashback" to "Flashback: запись повторов работает только на ПК",
        "axiom" to "Axiom: редактор мира работает только на ПК",
    )

    fun blocked(modId: String): String? = KNOWN[modId.lowercase()]

    /** Switches off every known mod in [modsDir]; returns the reasons for the ones it switched off. */
    fun disableIn(modsDir: File): List<String> {
        val jars = modsDir.listFiles { file -> file.isFile && file.name.endsWith(".jar") }.orEmpty()
        return jars.sortedBy { it.name }.mapNotNull { jar ->
            val reason = blocked(ContentInspector.inspect(jar).modId) ?: return@mapNotNull null
            val target = File(jar.parentFile, jar.name + DISABLED_SUFFIX)
            if (target.exists() || !jar.renameTo(target)) return@mapNotNull null
            reason
        }
    }
}
