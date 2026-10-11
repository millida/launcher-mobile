package net.millida.launcher.core.servers

import net.millida.launcher.core.GameLayout
import net.millida.launcher.core.profile.profileFolder
import java.io.File
import java.io.IOException

/**
 * The multiplayer list of one profile, backed by its servers.dat.
 *
 * Every operation rereads the file, changes the list in memory and writes the
 * whole file back through a neighbour file, so a save interrupted halfway
 * leaves the player with the list the game last saw instead of nothing.
 */
class ServerList(private val layout: GameLayout) {

    @Throws(IOException::class)
    fun file(profileDir: File): File = profileFolder(layout, profileDir, ServersDat.FILE_NAME)

    @Throws(IOException::class)
    fun list(profileDir: File): List<ServerEntry> = ServersDat.read(file(profileDir))

    @Throws(IOException::class)
    fun save(profileDir: File, entries: List<ServerEntry>): List<ServerEntry> {
        val trimmed = entries.take(MAX_SERVERS)
        ServersDat.write(file(profileDir), trimmed)
        return trimmed
    }

    @Throws(IOException::class)
    fun add(
        profileDir: File,
        name: String,
        address: String,
        onTop: Boolean = false,
    ): List<ServerEntry> {
        val entry = ServerEntry(name = cleanName(name, address), address = cleanAddress(address))
        val current = list(profileDir)
        if (current.size >= MAX_SERVERS) {
            throw IOException("в списке уже $MAX_SERVERS серверов, удалите лишние")
        }
        val next = if (onTop) listOf(entry) + current else current + entry
        return save(profileDir, next)
    }

    @Throws(IOException::class)
    fun remove(profileDir: File, address: String): List<ServerEntry> {
        val current = list(profileDir)
        val index = indexOf(current, address) ?: return current
        return save(profileDir, current.filterIndexed { at, _ -> at != index })
    }

    @Throws(IOException::class)
    fun rename(profileDir: File, address: String, name: String): List<ServerEntry> =
        edit(profileDir, address, name, address)

    @Throws(IOException::class)
    fun edit(
        profileDir: File,
        address: String,
        name: String,
        newAddress: String,
    ): List<ServerEntry> {
        val current = list(profileDir)
        val index = indexOf(current, address) ?: return current
        val cleanAddress = cleanAddress(newAddress)
        val updated = current[index].copy(name = cleanName(name, cleanAddress), address = cleanAddress)
        return save(profileDir, current.toMutableList().also { it[index] = updated })
    }

    /** Drag and drop in the list: the row at [from] lands at [to]. */
    @Throws(IOException::class)
    fun move(profileDir: File, from: Int, to: Int): List<ServerEntry> {
        val current = list(profileDir)
        if (from !in current.indices || to !in current.indices || from == to) return current
        val next = current.toMutableList()
        next.add(to, next.removeAt(from))
        return save(profileDir, next)
    }

    /** The game shows the first entry at the top, so pinning is a move to zero. */
    @Throws(IOException::class)
    fun pin(profileDir: File, address: String): List<ServerEntry> {
        val current = list(profileDir)
        val index = indexOf(current, address) ?: return current
        if (index == 0) return current
        val next = current.toMutableList()
        next.add(0, next.removeAt(index))
        return save(profileDir, next)
    }

    private fun indexOf(entries: List<ServerEntry>, address: String): Int? =
        entries.indexOfFirst { it.address.equals(address.trim(), ignoreCase = true) }.takeIf { it >= 0 }

    private fun cleanAddress(raw: String): String {
        val value = raw.trim().filterNot { it.isWhitespace() || it.code < 0x20 }
        if (value.isEmpty()) throw IOException("укажите адрес сервера")
        if (value.length > MAX_ADDRESS_CHARS) throw IOException("адрес сервера слишком длинный")
        return value
    }

    private fun cleanName(raw: String, fallback: String): String {
        val value = raw.trim().filterNot { it.code < 0x20 }
        return (if (value.isEmpty()) fallback else value).take(MAX_NAME_CHARS)
    }

    private companion object {
        const val MAX_SERVERS = 4096
        const val MAX_NAME_CHARS = 120
        const val MAX_ADDRESS_CHARS = 255
    }
}
