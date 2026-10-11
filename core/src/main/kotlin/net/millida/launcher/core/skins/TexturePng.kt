package net.millida.launcher.core.skins

import java.io.IOException
import java.util.Base64

/** Player-facing failure: the text is shown as is, so it says what to do next. */
class TextureError(message: String, cause: Throwable? = null) : IOException(message, cause)

enum class TextureKind(val dir: String, val wire: String) {
    SKIN("skins", "skin"),
    CAPE("capes", "cape"),
    ;

    companion object {
        fun of(raw: String): TextureKind =
            entries.firstOrNull { it.dir.equals(raw, true) || it.wire.equals(raw, true) }
                ?: throw TextureError("Неизвестный вид текстуры: $raw")
    }
}

/**
 * Geometry of an accepted texture. The scale is kept because HD textures are
 * laid out as an integer multiple of the vanilla grid, and every UV in the
 * renderer is multiplied by it.
 */
data class TextureShape(
    val width: Int,
    val height: Int,
    val scale: Int,
    val legacy: Boolean,
) {
    /** A 64x32 skin has no second layer and mirrors the limbs. */
    val hasOverlay: Boolean get() = !legacy
}

/**
 * PNG checks that run before anything is applied or uploaded. Mojang rejects a
 * wrong-sized texture with a generic error and our own server stores it as is,
 * so the player would only find out by seeing a broken figure in game.
 */
object TexturePng {
    const val MAX_BYTES: Int = 2_000_000

    private val SIGNATURE = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte())

    fun decode(data: String): ByteArray {
        val raw = data.substringAfterLast(',').filterNot { it.isWhitespace() }
        if (raw.isEmpty()) throw TextureError("Пустая картинка")
        // Strict decoding on purpose: the lenient decoder skips whatever it
        // does not understand and turns a pasted sentence into bytes.
        return runCatching { Base64.getDecoder().decode(raw) }
            .getOrElse { throw TextureError("Битый PNG") }
    }

    fun encode(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)

    fun dataUrl(bytes: ByteArray): String = "data:image/png;base64," + encode(bytes)

    fun isPng(bytes: ByteArray): Boolean =
        bytes.size >= SIGNATURE.size && SIGNATURE.indices.all { bytes[it] == SIGNATURE[it] }

    /**
     * Width and height straight out of IHDR. The core module is plain JVM and
     * also runs on Android, where neither ImageIO nor Bitmap is available to
     * both at once, and the header is all that is needed here.
     */
    fun size(bytes: ByteArray): Pair<Int, Int>? {
        // 8 signature bytes, a 4-byte length, the IHDR tag and two 4-byte
        // dimensions: 24 is everything this reads.
        if (!isPng(bytes) || bytes.size < 24) return null
        if (bytes[12] != 'I'.code.toByte() || bytes[13] != 'H'.code.toByte() ||
            bytes[14] != 'D'.code.toByte() || bytes[15] != 'R'.code.toByte()
        ) {
            return null
        }
        val width = int32(bytes, 16)
        val height = int32(bytes, 20)
        if (width <= 0 || height <= 0) return null
        return width to height
    }

    fun validate(kind: TextureKind, bytes: ByteArray): TextureShape {
        if (bytes.isEmpty()) throw TextureError("Пустая картинка")
        if (!isPng(bytes)) throw TextureError("Это не PNG — нужен файл .png")
        if (bytes.size > MAX_BYTES) {
            throw TextureError("PNG больше 2 МБ — возьми файл поменьше")
        }
        val size = size(bytes) ?: throw TextureError("Битый PNG — не читается размер картинки")
        val (width, height) = size
        return when (kind) {
            TextureKind.SKIN -> skinShape(width, height)
            TextureKind.CAPE -> capeShape(width, height)
        }
    }

    private fun skinShape(width: Int, height: Int): TextureShape {
        val scale = scaleOf(width, 64)
        if (scale > 0) {
            if (height == 64 * scale) return TextureShape(width, height, scale, legacy = false)
            if (height == 32 * scale) return TextureShape(width, height, scale, legacy = true)
        }
        throw TextureError("Скин должен быть 64x64 или 64x32, а тут ${width}x$height")
    }

    /**
     * A cape texture is the 64x32 sheet Mojang serves: the back face is the
     * 10x16 rectangle at (1,1). A square picture is a skin, not a cape, and
     * would render as a torn strip.
     */
    private fun capeShape(width: Int, height: Int): TextureShape {
        val scale = scaleOf(width, 64)
        if (scale > 0 && height == 32 * scale) {
            return TextureShape(width, height, scale, legacy = true)
        }
        throw TextureError("Плащ должен быть 64x32, а тут ${width}x$height")
    }

    private fun scaleOf(width: Int, base: Int): Int =
        if (width >= base && width % base == 0) width / base else 0

    private fun int32(bytes: ByteArray, at: Int): Int =
        ((bytes[at].toInt() and 0xff) shl 24) or
            ((bytes[at + 1].toInt() and 0xff) shl 16) or
            ((bytes[at + 2].toInt() and 0xff) shl 8) or
            (bytes[at + 3].toInt() and 0xff)
}
