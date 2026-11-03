package net.millida.launcher.core.i18n

/**
 * The bundled faces carry only Latin and Cyrillic, so Android draws every other script with its own
 * fonts glyph by glyph. Those stand taller than the fixed line heights were set for, and Vietnamese
 * would mix two faces inside one word, since the bundled ones have only part of its letters.
 */
object Scripts {
    private val LINE_SCALE: Map<String, Float> = mapOf(
        "hi" to 1.2f,
        "bn" to 1.2f,
        "te" to 1.25f,
        "ar" to 1.15f,
        "ur" to 1.5f,
    )

    private val SYSTEM_FACE: Set<String> = setOf("vi")

    fun lineScale(code: String): Float = LINE_SCALE[code] ?: 1f

    fun systemFace(code: String): Boolean = code in SYSTEM_FACE
}
