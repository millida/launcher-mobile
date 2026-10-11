package net.millida.launcher.core

import java.io.File
import java.io.InputStream
import java.security.MessageDigest

object Hashing {
    fun sha1(file: File): String = digest(file, "SHA-1")

    fun sha512(file: File): String = digest(file, "SHA-512")

    fun sha1(bytes: ByteArray): String = MessageDigest.getInstance("SHA-1").digest(bytes).hex()

    fun digest(file: File, algorithm: String): String {
        val md = MessageDigest.getInstance(algorithm)
        file.inputStream().use { feed(it, md) }
        return md.digest().hex()
    }

    fun matches(file: File, expected: String?): Boolean {
        if (expected.isNullOrBlank()) return file.isFile
        if (!file.isFile) return false
        if (!expected.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }) return false
        val algorithm = when (expected.length) {
            40 -> "SHA-1"
            64 -> "SHA-256"
            128 -> "SHA-512"
            else -> return false
        }
        return digest(file, algorithm).equals(expected, ignoreCase = true)
    }

    private fun feed(stream: InputStream, md: MessageDigest) {
        val buffer = ByteArray(1 shl 16)
        while (true) {
            val read = stream.read(buffer)
            if (read <= 0) break
            md.update(buffer, 0, read)
        }
    }

    private fun ByteArray.hex(): String {
        val out = StringBuilder(size * 2)
        for (b in this) {
            val v = b.toInt() and 0xff
            out.append(HEX[v ushr 4]).append(HEX[v and 0x0f])
        }
        return out.toString()
    }

    private val HEX = "0123456789abcdef".toCharArray()
}
