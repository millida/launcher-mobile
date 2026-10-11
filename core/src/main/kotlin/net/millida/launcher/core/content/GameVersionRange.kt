package net.millida.launcher.core.content

/**
 * Mod descriptors state the game they were built for in three different
 * dialects. The answer is deliberately nullable: an unparsed range means "no
 * opinion", and a guess that turns into a refusal would be worse than silence.
 */
object GameVersionRange {

    fun satisfies(range: String, gameVersion: String): Boolean? {
        val text = range.trim()
        if (text.isEmpty() || gameVersion.isBlank()) return null
        if (text == "*") return true

        val parts = text.split("||").map { it.trim() }.filter { it.isNotEmpty() }
        if (parts.size > 1) {
            val verdicts = parts.map { satisfies(it, gameVersion) }
            if (verdicts.any { it == true }) return true
            return if (verdicts.all { it == null }) null else false
        }

        maven(text, gameVersion)?.let { return it }
        prefixed(text, gameVersion)?.let { return it }
        if (text.endsWith(".x")) return gameVersion == text.dropLast(2) || gameVersion.startsWith(text.dropLast(1))
        if (text.first().isDigit()) return gameVersion == text
        return null
    }

    private fun prefixed(text: String, gameVersion: String): Boolean? {
        val operators = listOf(">=", "<=", "==", "~", "^", ">", "<", "=")
        val operator = operators.firstOrNull { text.startsWith(it) } ?: return null
        val bound = text.removePrefix(operator).trim()
        if (bound.isEmpty()) return null
        val compared = compare(gameVersion, bound)
        return when (operator) {
            ">=" -> compared >= 0
            ">" -> compared > 0
            "<=" -> compared <= 0
            "<" -> compared < 0
            "==", "=" -> compared == 0
            // Both ~ and ^ keep the major line; on Minecraft numbering that is
            // "same 1.x, patch may differ".
            "~", "^" -> compared >= 0 && sameMinorLine(gameVersion, bound)
            else -> null
        }
    }

    private fun maven(text: String, gameVersion: String): Boolean? {
        if (text.length < 3) return null
        val open = text.first()
        val close = text.last()
        if (open != '[' && open != '(') return null
        if (close != ']' && close != ')') return null
        val body = text.substring(1, text.length - 1)
        val lower = body.substringBefore(',').trim()
        val upper = if (body.contains(',')) body.substringAfter(',').trim() else lower
        if (lower.isNotEmpty()) {
            val compared = compare(gameVersion, lower)
            if (open == '[' && compared < 0) return false
            if (open == '(' && compared <= 0) return false
        }
        if (upper.isNotEmpty()) {
            val compared = compare(gameVersion, upper)
            if (close == ']' && compared > 0) return false
            if (close == ')' && compared >= 0) return false
        }
        return true
    }

    private fun sameMinorLine(left: String, right: String): Boolean {
        val a = numbers(left)
        val b = numbers(right)
        if (a.size < 2 || b.size < 2) return false
        return a[0] == b[0] && a[1] == b[1]
    }

    private fun compare(left: String, right: String): Int {
        val a = numbers(left)
        val b = numbers(right)
        for (index in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(index) { 0 }
            val y = b.getOrElse(index) { 0 }
            if (x != y) return x.compareTo(y)
        }
        return 0
    }

    private fun numbers(version: String): List<Int> =
        version.trim().split('.', '-', '+')
            .map { it.takeWhile { char -> char.isDigit() } }
            .takeWhile { it.isNotEmpty() }
            .map { it.toInt() }
}
