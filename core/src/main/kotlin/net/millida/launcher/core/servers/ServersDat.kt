package net.millida.launcher.core.servers

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.IOException
import java.util.zip.GZIPInputStream

/**
 * One row of the in-game multiplayer list.
 *
 * [extra] carries the raw NBT of tags this launcher does not model (hidden,
 * previewsChat, whatever a future game version adds) so that rewriting the file
 * to reorder or pin an entry does not strip what the game put there.
 */
class ServerEntry(
    val name: String,
    val address: String,
    val icon: String? = null,
    val acceptTextures: Boolean? = null,
    internal val extra: ByteArray = EMPTY_EXTRA,
) {
    fun copy(
        name: String = this.name,
        address: String = this.address,
        icon: String? = this.icon,
        acceptTextures: Boolean? = this.acceptTextures,
    ): ServerEntry = ServerEntry(name, address, icon, acceptTextures, extra)

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ServerEntry) return false
        return name == other.name &&
            address == other.address &&
            icon == other.icon &&
            acceptTextures == other.acceptTextures &&
            extra.contentEquals(other.extra)
    }

    override fun hashCode(): Int {
        var result = name.hashCode()
        result = 31 * result + address.hashCode()
        result = 31 * result + (icon?.hashCode() ?: 0)
        result = 31 * result + (acceptTextures?.hashCode() ?: 0)
        result = 31 * result + extra.contentHashCode()
        return result
    }

    override fun toString(): String = "ServerEntry($name, $address)"

    private companion object {
        val EMPTY_EXTRA = ByteArray(0)
    }
}

/**
 * servers.dat is plain, uncompressed NBT: a root Compound holding a "servers"
 * list of Compounds. Files written by third-party tools are sometimes gzipped,
 * so reading accepts both; writing always produces what the game itself writes.
 */
object ServersDat {

    const val FILE_NAME = "servers.dat"

    /** A broken or foreign file reads as an empty list instead of throwing. */
    fun decode(bytes: ByteArray): List<ServerEntry> {
        val plain = unpack(bytes) ?: return emptyList()
        val r = NbtReader(plain)
        if (r.u8() != TAG_COMPOUND) return emptyList()
        if (!r.skipName()) return emptyList()
        while (true) {
            val id = r.u8() ?: return emptyList()
            if (id == TAG_END) return emptyList()
            val key = r.string() ?: return emptyList()
            if (id == TAG_LIST && key == SERVERS) return readServerList(r)
            if (!r.skip(id, 0)) return emptyList()
        }
    }

    fun encode(entries: List<ServerEntry>): ByteArray {
        val out = ByteArrayOutputStream()
        val data = DataOutputStream(out)
        data.writeByte(TAG_COMPOUND)
        data.writeUTF("")
        data.writeByte(TAG_LIST)
        data.writeUTF(SERVERS)
        data.writeByte(TAG_COMPOUND)
        data.writeInt(entries.size)
        for (entry in entries) {
            data.writeByte(TAG_STRING)
            data.writeUTF(NAME)
            data.writeUTF(entry.name.fitUtf())
            data.writeByte(TAG_STRING)
            data.writeUTF(IP)
            data.writeUTF(entry.address.fitUtf())
            val icon = entry.icon?.takeIf { it.isNotEmpty() && it.fitsUtf() }
            if (icon != null) {
                data.writeByte(TAG_STRING)
                data.writeUTF(ICON)
                data.writeUTF(icon)
            }
            entry.acceptTextures?.let {
                data.writeByte(TAG_BYTE)
                data.writeUTF(ACCEPT_TEXTURES)
                data.writeByte(if (it) 1 else 0)
            }
            data.write(entry.extra)
            data.writeByte(TAG_END)
        }
        data.writeByte(TAG_END)
        data.flush()
        return out.toByteArray()
    }

    fun read(file: File): List<ServerEntry> {
        if (!file.isFile || file.length() > MAX_FILE_BYTES) return emptyList()
        val bytes = try {
            file.readBytes()
        } catch (broken: IOException) {
            return emptyList()
        }
        return decode(bytes)
    }

    @Throws(IOException::class)
    fun write(file: File, entries: List<ServerEntry>) {
        file.atomicWriteBytes(encode(entries))
    }

    private fun readServerList(r: NbtReader): List<ServerEntry> {
        val elementType = r.u8() ?: return emptyList()
        val count = r.i32() ?: return emptyList()
        if (elementType != TAG_COMPOUND || count <= 0) return emptyList()
        val out = ArrayList<ServerEntry>(minOf(count, 64))
        repeat(minOf(count, MAX_SERVERS)) {
            val entry = readServerCompound(r) ?: return out
            // A row without an address is either padding from a declared count
            // the file never delivered, or junk the game would show as a dead
            // line the player cannot connect to.
            if (entry.address.isNotEmpty()) out += entry
        }
        return out
    }

    private fun readServerCompound(r: NbtReader): ServerEntry? {
        var name = ""
        var address = ""
        var icon: String? = null
        var acceptTextures: Boolean? = null
        var extra: ByteArrayOutputStream? = null
        while (true) {
            val start = r.position
            val id = r.u8() ?: return null
            if (id == TAG_END) break
            val key = r.string() ?: return null
            when {
                id == TAG_STRING && key == NAME -> name = r.string() ?: return null
                id == TAG_STRING && key == IP -> address = r.string() ?: return null
                id == TAG_STRING && key == ICON -> icon = r.string() ?: return null
                id == TAG_BYTE && key == ACCEPT_TEXTURES -> acceptTextures = (r.u8() ?: return null) != 0
                else -> {
                    if (!r.skip(id, 0)) return null
                    val kept = extra ?: ByteArrayOutputStream().also { extra = it }
                    if (kept.size() + (r.position - start) <= MAX_EXTRA_BYTES) {
                        kept.write(r.bytes, start, r.position - start)
                    }
                }
            }
        }
        return ServerEntry(name, address, icon, acceptTextures, extra?.toByteArray() ?: ByteArray(0))
    }

    private fun unpack(bytes: ByteArray): ByteArray? {
        if (bytes.size < 3 || bytes.size > MAX_FILE_BYTES) return null
        val gzipped = (bytes[0].toInt() and 0xff) == 0x1f && (bytes[1].toInt() and 0xff) == 0x8b
        if (!gzipped) return bytes
        return try {
            GZIPInputStream(ByteArrayInputStream(bytes)).use { input ->
                val out = ByteArrayOutputStream()
                val buffer = ByteArray(16 * 1024)
                while (true) {
                    val read = input.read(buffer)
                    if (read <= 0) break
                    if (out.size() + read > MAX_FILE_BYTES) return null
                    out.write(buffer, 0, read)
                }
                out.toByteArray()
            }
        } catch (broken: IOException) {
            null
        }
    }

    private const val SERVERS = "servers"
    private const val NAME = "name"
    private const val IP = "ip"
    private const val ICON = "icon"
    private const val ACCEPT_TEXTURES = "acceptTextures"
}

internal const val TAG_END = 0
internal const val TAG_BYTE = 1
internal const val TAG_SHORT = 2
internal const val TAG_INT = 3
internal const val TAG_LONG = 4
internal const val TAG_FLOAT = 5
internal const val TAG_DOUBLE = 6
internal const val TAG_BYTE_ARRAY = 7
internal const val TAG_STRING = 8
internal const val TAG_LIST = 9
internal const val TAG_COMPOUND = 10
internal const val TAG_INT_ARRAY = 11
internal const val TAG_LONG_ARRAY = 12

private const val MAX_FILE_BYTES = 16 * 1024 * 1024
private const val MAX_SERVERS = 4096
private const val MAX_EXTRA_BYTES = 256 * 1024
private const val MAX_DEPTH = 64
private const val MAX_UTF_BYTES = 65535

/**
 * Reader over untrusted bytes: every step is bounds-checked and reports failure
 * instead of throwing, so a truncated file costs the caller a short list and
 * never an unhandled exception.
 */
private class NbtReader(val bytes: ByteArray) {
    var position: Int = 0
        private set

    fun u8(): Int? = if (position < bytes.size) (bytes[position++].toInt() and 0xff) else null

    fun u16(): Int? {
        if (position + 2 > bytes.size) return null
        val value = ((bytes[position].toInt() and 0xff) shl 8) or (bytes[position + 1].toInt() and 0xff)
        position += 2
        return value
    }

    fun i32(): Int? {
        if (position + 4 > bytes.size) return null
        var value = 0
        repeat(4) { value = (value shl 8) or (bytes[position + it].toInt() and 0xff) }
        position += 4
        return value
    }

    fun advance(count: Long): Boolean {
        if (count < 0 || position + count > bytes.size) return false
        position += count.toInt()
        return true
    }

    /**
     * NBT strings are modified UTF-8, the encoding DataInput speaks. Decoding
     * them by hand as standard UTF-8 would mangle emoji in server names, so the
     * bytes are handed to readUTF and only a malformed pair falls back.
     */
    fun string(): String? {
        val start = position
        val length = u16() ?: return null
        if (position + length > bytes.size) return null
        position += length
        return try {
            DataInputStream(ByteArrayInputStream(bytes, start, length + 2)).readUTF()
        } catch (malformed: IOException) {
            String(bytes, start + 2, length, Charsets.UTF_8)
        }
    }

    fun skipName(): Boolean {
        val length = u16() ?: return false
        return advance(length.toLong())
    }

    /** Skips the payload of a tag whose name has already been consumed. */
    fun skip(id: Int, depth: Int): Boolean {
        if (depth > MAX_DEPTH) return false
        return when (id) {
            TAG_BYTE -> advance(1)
            TAG_SHORT -> advance(2)
            TAG_INT, TAG_FLOAT -> advance(4)
            TAG_LONG, TAG_DOUBLE -> advance(8)
            TAG_BYTE_ARRAY -> advance(maxOf(i32() ?: return false, 0).toLong())
            TAG_STRING -> advance((u16() ?: return false).toLong())
            TAG_LIST -> {
                val elementType = u8() ?: return false
                val count = i32() ?: return false
                if (count <= 0) return true
                repeat(count) { if (!skip(elementType, depth + 1)) return false }
                true
            }
            TAG_COMPOUND -> skipCompound(depth + 1)
            TAG_INT_ARRAY -> advance(maxOf(i32() ?: return false, 0).toLong() * 4)
            TAG_LONG_ARRAY -> advance(maxOf(i32() ?: return false, 0).toLong() * 8)
            else -> false
        }
    }

    private fun skipCompound(depth: Int): Boolean {
        if (depth > MAX_DEPTH) return false
        while (true) {
            val child = u8() ?: return false
            if (child == TAG_END) return true
            if (!skipName()) return false
            if (!skip(child, depth)) return false
        }
    }
}

private fun String.utfLength(): Long {
    var total = 0L
    for (ch in this) {
        val code = ch.code
        total += when {
            code in 1..0x7f -> 1
            code <= 0x7ff -> 2
            else -> 3
        }
    }
    return total
}

private fun String.fitsUtf(): Boolean = utfLength() <= MAX_UTF_BYTES

/** A value too long for an NBT string would abort the whole save, so it is cut. */
private fun String.fitUtf(): String {
    if (fitsUtf()) return this
    var cut = this
    while (cut.isNotEmpty() && !cut.fitsUtf()) cut = cut.substring(0, cut.length / 2 + cut.length % 2)
    return cut
}

/**
 * Mirrors atomicWriteText for binary payloads: the previous servers.dat stays
 * readable until the replacement is complete on disk.
 */
@Throws(IOException::class)
internal fun File.atomicWriteBytes(data: ByteArray) {
    parentFile?.mkdirs()
    val temp = File(parentFile, "$name.new")
    temp.writeBytes(data)
    if (temp.renameTo(this)) return

    val backup = File(parentFile, "$name.old")
    if (backup.exists()) backup.delete()
    if (exists() && !renameTo(backup)) {
        temp.delete()
        throw IOException("не удалось заменить файл $name")
    }
    if (!temp.renameTo(this)) {
        backup.renameTo(this)
        temp.delete()
        throw IOException("не удалось записать файл $name")
    }
    backup.delete()
}
