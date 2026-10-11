package net.millida.launcher.core

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.zip.ZipException
import java.util.zip.ZipFile

/**
 * The one unzipper for archives a stranger built: packs, worlds, a runtime
 * bundle. Read through the central directory, never as a stream: archives
 * packed from a folder often store files uncompressed with a data descriptor,
 * and Android's ZipInputStream reads such an entry as empty and then ends the
 * archive without an error. Every entry name goes through SafePath, so an
 * absolute name or one climbing out with ".." stops the unpacking.
 */
object SafeUnzip {

    class Limits(
        val maxEntries: Int,
        val maxBytes: Long,
        val broken: (String?) -> String,
        val tooMany: String,
        val tooBig: String,
    )

    /** Unpacks [archive] into [target] and returns how many files were written. */
    @Throws(IOException::class)
    fun unpack(archive: File, target: File, limits: Limits, onFile: (String) -> Unit = {}): Int {
        target.mkdirs()
        val zip = try {
            ZipFile(archive)
        } catch (broken: ZipException) {
            throw IOException(limits.broken(broken.message), broken)
        }
        var written = 0L
        var files = 0
        zip.use {
            if (zip.size() > limits.maxEntries) throw IOException(limits.tooMany)
            val entries = zip.entries()
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                val name = entry.name.replace('\\', '/').trim()
                if (name.isEmpty()) continue
                val destination = SafePath.join(target, name)
                if (entry.isDirectory || name.endsWith("/")) {
                    destination.mkdirs()
                    continue
                }
                destination.parentFile?.mkdirs()
                zip.getInputStream(entry).use { input ->
                    destination.outputStream().buffered().use { out ->
                        val buffer = ByteArray(BUFFER)
                        while (true) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            written += read
                            if (written > limits.maxBytes) throw IOException(limits.tooBig)
                            out.write(buffer, 0, read)
                        }
                    }
                }
                files += 1
                onFile(name)
            }
        }
        return files
    }

    /** A stream has no central directory: it is saved to [file] first and read from there. */
    @Throws(IOException::class)
    fun spool(source: InputStream, file: File): File {
        file.parentFile?.mkdirs()
        file.outputStream().use { out -> source.copyTo(out, BUFFER) }
        return file
    }

    private const val BUFFER = 1 shl 16
}
