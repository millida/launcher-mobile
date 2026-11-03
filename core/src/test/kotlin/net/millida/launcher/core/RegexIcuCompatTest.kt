package net.millida.launcher.core

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Android compiles java.util.regex patterns with ICU, which rejects a bare closing
 * brace that the JVM running these tests accepts. Release 1.10.0 crashed on every
 * start because of one such pattern, so every pattern literal in the shipped code
 * is checked here.
 */
class RegexIcuCompatTest {

    private val root: File = generateSequence(File("").absoluteFile) { it.parentFile }
        .first { File(it, "settings.gradle.kts").isFile }

    private val rawLiteral = Regex("(?:Regex|Pattern\\.compile)\\(\\s*\"\"\"([\\s\\S]*?)\"\"\"")
    private val plainLiteral = Regex("(?:Regex|Pattern\\.compile)\\(\\s*\"((?:[^\"\\\\\\n]|\\\\.)*)\"")

    private data class Case(val pattern: String, val icuSafe: Boolean, val why: String)

    @Test
    fun `checker tells ICU-safe patterns from the ones Android rejects`() {
        val cases = listOf(
            Case("""\{(\w+)}""", false, "the 1.10.0 crash: bare closing brace after a group"),
            Case("""a}""", false, "a bare closing brace anywhere is a syntax error on ICU"),
            Case("""\{(\w+)\}""", true, "the fix: both braces escaped"),
            Case("""[0-9a-f]{40}""", true, "a counted quantifier closes its own brace"),
            Case("""\d{1,4}(\.\d{1,3}){1,2}""", true, "ranges and nested groups with quantifiers"),
            Case("""[(\[{][^)\]}]*[)\]}]""", true, "braces inside a character class are literal on ICU too"),
            Case("""[^\p{L}\p{N}]+""", true, "a property name in braces belongs to the escape"),
            Case("""\\}""", false, "an escaped backslash does not escape the brace after it"),
        )
        for (case in cases) {
            assertEquals("«${case.pattern}»: ${case.why}", case.icuSafe, bareClosingBrace(case.pattern) == null)
        }
    }

    @Test
    fun `no pattern literal in the shipped code has a bare closing brace`() {
        val offenders = listOf("app", "core", "runtime", "bridge")
            .map { File(root, "$it/src/main") }
            .filter { it.isDirectory }
            .flatMap { dir -> dir.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList() }
            .flatMap { file ->
                val text = file.readText()
                val raw = rawLiteral.findAll(text).map { it.groupValues[1] }
                val plain = plainLiteral.findAll(text).map { unescape(it.groupValues[1]) }
                (raw + plain)
                    .filterNot { it.contains("\${") }
                    .filter { bareClosingBrace(it) != null }
                    .map { "${file.relativeTo(root)}: $it" }
                    .toList()
            }
        assertTrue(
            "Android (ICU) throws PatternSyntaxException on these, the launcher crashes where they are first used; escape the brace as \\}:\n" +
                offenders.joinToString("\n"),
            offenders.isEmpty(),
        )
    }

    private fun bareClosingBrace(pattern: String): Int? {
        var i = 0
        var classDepth = 0
        var quantifier = false
        var property = false
        while (i < pattern.length) {
            val c = pattern[i]
            when {
                c == '\\' -> {
                    val next = pattern.getOrNull(i + 1)
                    if ((next == 'p' || next == 'P') && pattern.getOrNull(i + 2) == '{') {
                        property = true
                        i += 3
                        continue
                    }
                    if (next == 'Q') {
                        val end = pattern.indexOf("\\E", i + 2)
                        i = if (end < 0) pattern.length else end + 2
                        continue
                    }
                    i += 2
                    continue
                }
                property && c == '}' -> property = false
                property -> Unit
                classDepth > 0 && c == '[' -> classDepth++
                classDepth > 0 && c == ']' -> classDepth--
                classDepth > 0 -> Unit
                c == '[' -> {
                    classDepth = 1
                    if (pattern.getOrNull(i + 1) == '^') i++
                    if (pattern.getOrNull(i + 1) == ']') i++
                }
                c == '{' -> quantifier = true
                c == '}' && quantifier -> quantifier = false
                c == '}' -> return i
            }
            i++
        }
        return null
    }

    private fun unescape(literal: String): String = buildString {
        var i = 0
        while (i < literal.length) {
            val c = literal[i]
            if (c == '\\' && i + 1 < literal.length) {
                when (val next = literal[i + 1]) {
                    'n' -> append('\n')
                    't' -> append('\t')
                    'r' -> append('\r')
                    'u' -> {
                        append(literal.substring(i + 2, minOf(i + 6, literal.length)).toIntOrNull(16)?.toChar() ?: '?')
                        i += 4
                    }
                    else -> append(next)
                }
                i += 2
            } else {
                append(c)
                i++
            }
        }
    }
}
