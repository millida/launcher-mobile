package net.millida.launcher.core.game

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

sealed interface NbtTag

@JvmInline
value class NbtByte(val value: Byte) : NbtTag

@JvmInline
value class NbtShort(val value: Short) : NbtTag

@JvmInline
value class NbtInt(val value: Int) : NbtTag

@JvmInline
value class NbtLong(val value: Long) : NbtTag

@JvmInline
value class NbtFloat(val value: Float) : NbtTag

@JvmInline
value class NbtDouble(val value: Double) : NbtTag

@JvmInline
value class NbtString(val value: String) : NbtTag

class NbtByteArray(val value: ByteArray) : NbtTag

class NbtIntArray(val value: IntArray) : NbtTag

class NbtLongArray(val value: LongArray) : NbtTag

class NbtList(val elementType: Int, val items: List<NbtTag>) : NbtTag

class NbtCompound(val values: Map<String, NbtTag>) : NbtTag {

    operator fun get(name: String): NbtTag? = values[name]

    fun compound(name: String): NbtCompound? = values[name] as? NbtCompound

    fun text(name: String): String? = (values[name] as? NbtString)?.value

    fun number(name: String): Long? = when (val tag = values[name]) {
        is NbtByte -> tag.value.toLong()
        is NbtShort -> tag.value.toLong()
        is NbtInt -> tag.value.toLong()
        is NbtLong -> tag.value
        else -> null
    }

    fun with(name: String, tag: NbtTag): NbtCompound =
        NbtCompound(LinkedHashMap(values).also { it[name] = tag })
}

/**
 * [complete] is false when the reader stopped early: an unknown tag has an
 * unknown payload length, so nothing after it can be located. Reading a world
 * card still works on what was collected, but rewriting the file from a partial
 * tree would silently drop the rest of the world, so writers must refuse it.
 */
class NbtDocument(val name: String, val root: NbtCompound, val complete: Boolean)

object Nbt {

    const val TAG_END = 0
    const val TAG_BYTE = 1
    const val TAG_SHORT = 2
    const val TAG_INT = 3
    const val TAG_LONG = 4
    const val TAG_FLOAT = 5
    const val TAG_DOUBLE = 6
    const val TAG_BYTE_ARRAY = 7
    const val TAG_STRING = 8
    const val TAG_LIST = 9
    const val TAG_COMPOUND = 10
    const val TAG_INT_ARRAY = 11
    const val TAG_LONG_ARRAY = 12

    private const val MAX_DEPTH = 24

    /** A level.dat is a few hundred kilobytes; the cap only stops a crafted file from asking for a heap. */
    private const val MAX_PAYLOAD_BYTES = 64L * 1024 * 1024

    fun read(file: File): NbtDocument? =
        if (!file.isFile) null else runCatching { FileInputStream(file).use { read(it) } }.getOrNull()

    fun read(source: InputStream): NbtDocument? = try {
        DataInputStream(BufferedInputStream(GZIPInputStream(source))).use { input ->
            val type = input.read()
            if (type != TAG_COMPOUND) {
                null
            } else {
                val name = input.readUTF()
                val reader = Reader(input)
                val root = reader.compound(0)
                NbtDocument(name, root, reader.complete)
            }
        }
    } catch (broken: Exception) {
        null
    }

    @Throws(IOException::class)
    fun write(target: OutputStream, document: NbtDocument) {
        DataOutputStream(BufferedOutputStream(GZIPOutputStream(target))).use { out ->
            out.writeByte(TAG_COMPOUND)
            out.writeUTF(document.name)
            writeCompound(out, document.root)
        }
    }

    private fun writeCompound(out: DataOutputStream, compound: NbtCompound) {
        for ((name, tag) in compound.values) {
            out.writeByte(typeOf(tag))
            out.writeUTF(name)
            writePayload(out, tag)
        }
        out.writeByte(TAG_END)
    }

    private fun typeOf(tag: NbtTag): Int = when (tag) {
        is NbtByte -> TAG_BYTE
        is NbtShort -> TAG_SHORT
        is NbtInt -> TAG_INT
        is NbtLong -> TAG_LONG
        is NbtFloat -> TAG_FLOAT
        is NbtDouble -> TAG_DOUBLE
        is NbtByteArray -> TAG_BYTE_ARRAY
        is NbtString -> TAG_STRING
        is NbtList -> TAG_LIST
        is NbtCompound -> TAG_COMPOUND
        is NbtIntArray -> TAG_INT_ARRAY
        is NbtLongArray -> TAG_LONG_ARRAY
    }

    private fun writePayload(out: DataOutputStream, tag: NbtTag) {
        when (tag) {
            is NbtByte -> out.writeByte(tag.value.toInt())
            is NbtShort -> out.writeShort(tag.value.toInt())
            is NbtInt -> out.writeInt(tag.value)
            is NbtLong -> out.writeLong(tag.value)
            is NbtFloat -> out.writeFloat(tag.value)
            is NbtDouble -> out.writeDouble(tag.value)
            is NbtString -> out.writeUTF(tag.value)
            is NbtByteArray -> {
                out.writeInt(tag.value.size)
                out.write(tag.value)
            }
            is NbtIntArray -> {
                out.writeInt(tag.value.size)
                tag.value.forEach { out.writeInt(it) }
            }
            is NbtLongArray -> {
                out.writeInt(tag.value.size)
                tag.value.forEach { out.writeLong(it) }
            }
            is NbtList -> {
                out.writeByte(if (tag.items.isEmpty()) tag.elementType else typeOf(tag.items.first()))
                out.writeInt(tag.items.size)
                tag.items.forEach { writePayload(out, it) }
            }
            is NbtCompound -> writeCompound(out, tag)
        }
    }

    private class Reader(private val input: DataInputStream) {
        var complete = true
            private set

        private var budget = MAX_PAYLOAD_BYTES

        fun compound(depth: Int): NbtCompound {
            val values = LinkedHashMap<String, NbtTag>()
            if (depth > MAX_DEPTH) {
                complete = false
                return NbtCompound(values)
            }
            while (true) {
                val type = try {
                    input.read()
                } catch (end: IOException) {
                    complete = false
                    return NbtCompound(values)
                }
                if (type == TAG_END) return NbtCompound(values)
                if (type < TAG_END) {
                    complete = false
                    return NbtCompound(values)
                }
                val name = try {
                    input.readUTF()
                } catch (broken: IOException) {
                    complete = false
                    return NbtCompound(values)
                }
                val value = payload(type, depth) ?: run {
                    complete = false
                    return NbtCompound(values)
                }
                values[name] = value
                // A nested compound that stopped early leaves the stream pointing at
                // the middle of a tag, so nothing further can be trusted; what has
                // been collected still describes the world well enough to show it.
                if (!complete) return NbtCompound(values)
            }
        }

        fun payload(type: Int, depth: Int): NbtTag? = try {
            when (type) {
                TAG_BYTE -> NbtByte(input.readByte())
                TAG_SHORT -> NbtShort(input.readShort())
                TAG_INT -> NbtInt(input.readInt())
                TAG_LONG -> NbtLong(input.readLong())
                TAG_FLOAT -> NbtFloat(input.readFloat())
                TAG_DOUBLE -> NbtDouble(input.readDouble())
                TAG_STRING -> NbtString(input.readUTF())
                TAG_BYTE_ARRAY -> byteArray()
                TAG_INT_ARRAY -> intArray()
                TAG_LONG_ARRAY -> longArray()
                TAG_LIST -> list(depth)
                TAG_COMPOUND -> compound(depth + 1)
                else -> null
            }
        } catch (end: EOFException) {
            null
        } catch (broken: IOException) {
            null
        }

        private fun claim(count: Int, elementSize: Int): Boolean {
            if (count < 0) return false
            val bytes = count.toLong() * elementSize
            if (bytes > budget) return false
            budget -= bytes
            return true
        }

        private fun byteArray(): NbtTag? {
            val count = input.readInt()
            if (!claim(count, 1)) return null
            val bytes = ByteArray(count)
            input.readFully(bytes)
            return NbtByteArray(bytes)
        }

        private fun intArray(): NbtTag? {
            val count = input.readInt()
            if (!claim(count, 4)) return null
            return NbtIntArray(IntArray(count) { input.readInt() })
        }

        private fun longArray(): NbtTag? {
            val count = input.readInt()
            if (!claim(count, 8)) return null
            return NbtLongArray(LongArray(count) { input.readLong() })
        }

        private fun list(depth: Int): NbtTag? {
            val elementType = input.read()
            val count = input.readInt()
            if (count < 0) return null
            if (count == 0) return NbtList(if (elementType in TAG_END..TAG_LONG_ARRAY) elementType else TAG_END, emptyList())
            if (elementType <= TAG_END || elementType > TAG_LONG_ARRAY) return null
            if (depth > MAX_DEPTH) return null
            // Every element carries at least one byte, so an element count beyond the
            // remaining budget cannot be honest and must not size an ArrayList.
            if (!claim(count, 1)) return null
            val items = ArrayList<NbtTag>(minOf(count, 1024))
            repeat(count) {
                items += payload(elementType, depth + 1) ?: return null
                if (!complete) return NbtList(elementType, items)
            }
            return NbtList(elementType, items)
        }
    }
}
