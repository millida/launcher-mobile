package net.millida.launcher.core.content

/**
 * Version ranges as mods write them: Fabric and Quilt (`>=1.20 <1.21`, `~1.20.1`, `^0.5`,
 * `1.20.x`, `*`, `a || b`) and Forge and NeoForge (`[1.20,1.21)`, `(,2.0]`). Meant for
 * judging a mod against the game or against another mod, so the answer is lenient: a form
 * that is not understood is `null` ("no opinion"), and a refusal needs every part understood.
 */
object ModVersionRange {

    fun satisfies(range: String, version: String): Boolean? {
        val text = range.trim()
        val have = numbers(version)
        if (text.isEmpty() || have.isEmpty()) return null
        if (text == "*") return true
        val verdicts = text.split("||").map { it.trim() }.filter { it.isNotEmpty() }.map { union(it, have) }
        if (verdicts.isEmpty()) return null
        if (verdicts.any { it == true }) return true
        return if (verdicts.any { it == null }) null else false
    }

    /** One `||` branch: Maven intervals joined by commas, or comparators joined by spaces (all must hold). */
    private fun union(part: String, have: List<Int>): Boolean? {
        if (part.first() == '[' || part.first() == '(') {
            val verdicts = MAVEN_SPLIT.split(part).map { maven(it.trim(), have) }
            if (verdicts.any { it == true }) return true
            return if (verdicts.any { it == null }) null else false
        }
        val tokens = ArrayList<String>()
        var pending = ""
        for (token in part.split(WHITESPACE).filter { it.isNotEmpty() }) {
            if (token in OPERATORS) pending = token else {
                tokens += pending + token
                pending = ""
            }
        }
        if (pending.isNotEmpty() || tokens.isEmpty()) return null
        val verdicts = tokens.map { comparator(it, have) }
        if (verdicts.any { it == false }) return false
        return if (verdicts.any { it == null }) null else true
    }

    private fun comparator(token: String, have: List<Int>): Boolean? {
        if (token == "*" || token.equals("x", ignoreCase = true)) return true
        val operator = OPERATORS.firstOrNull { token.startsWith(it) }.orEmpty()
        val bound = token.removePrefix(operator)
        if (bound.isEmpty()) return null
        if (bound.endsWith(".x") || bound.endsWith(".X") || bound.endsWith(".*")) {
            val prefix = numbers(bound.dropLast(2))
            if (prefix.isEmpty() || operator.isNotEmpty()) return null
            return prefix.indices.all { have.getOrElse(it) { 0 } == prefix[it] }
        }
        val want = numbers(bound)
        if (want.isEmpty()) return null
        val order = compare(have, want)
        return when (operator) {
            ">=" -> order >= 0
            ">" -> order > 0
            "<=" -> order <= 0
            "<" -> order < 0
            "", "=", "==" -> order == 0
            "~" -> order >= 0 && compare(have, bump(want, if (want.size == 1) 0 else 1)) < 0
            "^" -> order >= 0 && compare(have, bump(want, 0)) < 0
            else -> null
        }
    }

    /** `[1.20,1.21)`, `(,2]`, `[1.20]`. */
    private fun maven(text: String, have: List<Int>): Boolean? {
        if (text.length < 3) return null
        val open = text.first()
        val close = text.last()
        if ((open != '[' && open != '(') || (close != ']' && close != ')')) return null
        val body = text.substring(1, text.length - 1)
        val lowerText = body.substringBefore(',').trim()
        val upperText = if (body.contains(',')) body.substringAfter(',').trim() else lowerText
        if (lowerText.isNotEmpty()) {
            val lower = numbers(lowerText).ifEmpty { return null }
            val order = compare(have, lower)
            if (order < 0 || (order == 0 && open == '(')) return false
        }
        if (upperText.isNotEmpty()) {
            val upper = numbers(upperText).ifEmpty { return null }
            val order = compare(have, upper)
            if (order > 0 || (order == 0 && close == ')')) return false
        }
        return true
    }

    /** The lowest version past everything that shares the first [index] + 1 numbers of [from]. */
    private fun bump(from: List<Int>, index: Int): List<Int> =
        from.take(index + 1).mapIndexed { i, n -> if (i == index) n + 1 else n }

    fun compare(left: String, right: String): Int = compare(numbers(left), numbers(right))

    private fun compare(a: List<Int>, b: List<Int>): Int {
        for (index in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(index) { 0 }
            val y = b.getOrElse(index) { 0 }
            if (x != y) return x.compareTo(y)
        }
        return 0
    }

    /** `0.5.8+mc1.21` is 0.5.8: numbers up to the first part that is not one. */
    internal fun numbers(version: String): List<Int> =
        version.trim().removePrefix("v").split('.', '-', '+', '_')
            .map { it.takeWhile { char -> char.isDigit() } }
            .takeWhile { it.isNotEmpty() }
            .mapNotNull { it.take(MAX_DIGITS).toIntOrNull() }

    private const val MAX_DIGITS = 9
    private val OPERATORS = listOf(">=", "<=", "==", ">", "<", "=", "~", "^")
    private val WHITESPACE = Regex("\\s+")
    private val MAVEN_SPLIT = Regex("(?<=[\\])]),\\s*(?=[\\[(])")
}
