package net.millida.launcher

import net.millida.launcher.core.input.TapTarget
import net.millida.launcher.core.input.TapTargetCodec
import java.io.File
import java.io.RandomAccessFile
import java.nio.channels.FileChannel

/**
 * Reads what the Millida mod writes about the crosshair target. The game runs in
 * this process, so a mapping of the same file sees its writes at once. One tap reads
 * 16 bytes, no thread hops. Anything wrong — no file, no mod, stale — gives null and
 * the tap keeps its scheme's button.
 */
object TapTargetFile {
    @Volatile
    private var path: File? = null
    private var raf: RandomAccessFile? = null
    private var map: java.nio.MappedByteBuffer? = null

    /** The game folder; also tells the mod where to write, through the environment. */
    fun point(gameDir: File): String {
        val file = File(gameDir, TapTargetCodec.FILE)
        synchronized(this) {
            close()
            path = file
        }
        return file.absolutePath
    }

    fun current(nowMs: Long = System.currentTimeMillis()): TapTarget? = synchronized(this) {
        val buffer = map ?: open() ?: return null
        val bytes = ByteArray(TapTargetCodec.SIZE)
        for (i in bytes.indices) bytes[i] = buffer.get(i)
        TapTargetCodec.decode(bytes, nowMs)
    }

    private fun open(): java.nio.MappedByteBuffer? {
        val file = path?.takeIf { it.length() >= TapTargetCodec.SIZE } ?: return null
        return runCatching {
            val access = RandomAccessFile(file, "r")
            raf = access
            access.channel.map(FileChannel.MapMode.READ_ONLY, 0, TapTargetCodec.SIZE.toLong()).also { map = it }
        }.getOrNull()
    }

    private fun close() {
        map = null
        runCatching { raf?.close() }
        raf = null
    }
}
