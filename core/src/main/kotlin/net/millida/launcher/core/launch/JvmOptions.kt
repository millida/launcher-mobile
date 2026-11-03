package net.millida.launcher.core.launch

import net.millida.launcher.core.i18n.tr

/**
 * Java options a player adds to one build, as typed into a single field. Quotes
 * keep a value with spaces together. Heap size stays with the memory slider: a
 * second -Xmx on the line would silently win over it.
 */
object JvmOptions {
    const val MAX_OPTIONS = 32
    const val MAX_LENGTH = 300

    data class Parsed(val options: List<String>, val rejected: String? = null)

    fun parse(text: String): Parsed {
        val tokens = split(text) ?: return Parsed(emptyList(), tr("кавычка не закрыта"))
        if (tokens.size > MAX_OPTIONS) return Parsed(emptyList(), tr("больше {max} аргументов", MAX_OPTIONS))
        tokens.forEach { token ->
            when {
                token.any { it.isISOControl() } -> return Parsed(emptyList(), tr("в «{token}» есть служебные символы", token.take(40)))
                token.length > MAX_LENGTH -> return Parsed(emptyList(), tr("аргумент длиннее {max} символов", MAX_LENGTH))
                !token.startsWith("-") -> return Parsed(emptyList(), tr("«{token}» не похож на аргумент Java — они начинаются с минуса", token.take(40)))
                HEAP.any { token.startsWith(it) } -> return Parsed(emptyList(), tr("память задаётся ползунком «Память», а не аргументом {token}", token.take(40)))
            }
        }
        return Parsed(tokens)
    }

    fun format(options: List<String>): String =
        options.joinToString(" ") { if (it.any(Char::isWhitespace)) "\"$it\"" else it }

    private val HEAP = listOf("-Xmx", "-Xms")

    private fun split(text: String): List<String>? {
        val out = mutableListOf<String>()
        val current = StringBuilder()
        var quote: Char? = null
        var started = false
        text.forEach { c ->
            when {
                quote != null && c == quote -> quote = null
                quote == null && (c == '"' || c == '\'') -> {
                    quote = c
                    started = true
                }
                quote == null && c.isWhitespace() -> {
                    if (started) out += current.toString()
                    current.clear()
                    started = false
                }
                else -> {
                    current.append(c)
                    started = true
                }
            }
        }
        if (quote != null) return null
        if (started) out += current.toString()
        return out
    }
}
