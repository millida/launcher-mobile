package net.millida.launcher.core

/**
 * The settings search of the desktop launcher (src/components/SetKit.tsx
 * useMatch): every word of the query must occur in the text, case and «ё»
 * ignored, in any order.
 */
object SettingsSearch {
    private val SPACES = Regex("\\s+")

    private fun norm(text: String): String = text.lowercase().replace('ё', 'е')

    fun matches(query: String, text: String): Boolean {
        val words = norm(query).split(SPACES).filter { it.isNotEmpty() }
        if (words.isEmpty()) return true
        val hay = norm(text)
        return words.all { it in hay }
    }
}
