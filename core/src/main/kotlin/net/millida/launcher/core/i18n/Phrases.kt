package net.millida.launcher.core.i18n

import java.util.IdentityHashMap
import java.util.concurrent.ConcurrentHashMap

typealias Table = Map<String, String>

object Phrases {
    // Android compiles patterns with ICU, which rejects the bare closing brace the JVM accepts.
    private val PLACEHOLDER = Regex("""\{(\w+)\}""")
    private val CYRILLIC = Regex("[А-Яа-яЁё]")
    private const val MAX_DEPTH = 3

    private val slotCache = ConcurrentHashMap<String, List<String>>()

    fun placeholdersOf(text: String): List<String> = PLACEHOLDER.findAll(text).map { it.value }.sorted().toList()

    /** Distinct slot names of a key in the order they first appear: the order the caller passes values in. */
    fun slotsOf(key: String): List<String> = slotCache.getOrPut(key) {
        PLACEHOLDER.findAll(key).map { it.groupValues[1] }.distinct().toList()
    }

    fun interpolate(template: String, key: String, args: Array<out Any?>): String {
        if (args.isEmpty() || !template.contains('{')) return template
        val slots = slotsOf(key)
        if (slots.isEmpty()) return template
        return PLACEHOLDER.replace(template) { match ->
            val index = slots.indexOf(match.groupValues[1])
            if (index in args.indices) args[index].toString() else match.value
        }
    }

    /**
     * The Russian source string is the key, so Russian needs no table. A missing translation
     * falls back to English before Russian: a half-translated screen in English is readable for
     * far more players than one in Russian.
     */
    fun lookup(locale: String, key: String, own: Table?, english: Table?): String {
        if (locale == Locales.SOURCE) return own?.get(key)?.takeIf { it.isNotEmpty() } ?: key
        own?.get(key)?.takeIf { it.isNotEmpty() }?.let { return it }
        return english?.get(key)?.takeIf { it.isNotEmpty() } ?: key
    }

    fun translate(locale: String, key: String, args: Array<out Any?>, own: Table?, english: Table?): String =
        interpolate(lookup(locale, key, own, english), key, args)

    private class Template(val key: String, val pattern: Regex, val slots: List<String>, val weight: Int)

    private val forward = IdentityHashMap<Table, List<Template>>()
    private val backward = IdentityHashMap<Table, Pair<Map<String, String>, List<Template>>>()

    /** Tables are swapped whole, never edited, so the instance is the cache key; hashing a whole table per call would cost more than the lookup. */
    private fun <V> IdentityHashMap<Table, V>.memo(table: Table, build: () -> V): V =
        synchronized(this) { get(table) } ?: build().also { built -> synchronized(this) { put(table, built) } }

    private fun pattern(text: String): Pair<Regex, List<String>> {
        val slots = mutableListOf<String>()
        val source = StringBuilder("^")
        var last = 0
        for (match in PLACEHOLDER.findAll(text)) {
            if (match.range.first > last) source.append(Regex.escape(text.substring(last, match.range.first)))
            source.append("([\\s\\S]*?)")
            slots += match.groupValues[1]
            last = match.range.last + 1
        }
        if (last < text.length) source.append(Regex.escape(text.substring(last)))
        source.append('$')
        return Regex(source.toString()) to slots
    }

    private fun literalLength(text: String): Int = PLACEHOLDER.replace(text, "").length

    private fun forwardTemplates(table: Table): List<Template> = forward.memo(table) {
        table.keys
            .filter { PLACEHOLDER.containsMatchIn(it) && CYRILLIC.containsMatchIn(PLACEHOLDER.replace(it, "")) }
            .map { key -> pattern(key).let { (re, slots) -> Template(key, re, slots, literalLength(key)) } }
            .sortedByDescending { it.weight }
    }

    /**
     * A text that left the code already translated, read back into its Russian source. The
     * code that recognises its own messages (error classes, failure statistics) matches the
     * Russian wording, and must keep doing so whatever language the player reads.
     */
    fun source(locale: String, text: String, own: Table?, english: Table?, depth: Int = 0): String {
        if (locale == Locales.SOURCE || text.isEmpty() || depth > MAX_DEPTH) return text
        for (table in listOfNotNull(own, english)) {
            val (exact, templates) = backward.memo(table) { reverse(table) }
            exact[text]?.let { return it }
            for (template in templates) {
                val match = template.pattern.matchEntire(text) ?: continue
                val values = template.slots.mapIndexed { i, slot ->
                    slot to source(locale, match.groupValues[i + 1], own, english, depth + 1)
                }.toMap()
                return PLACEHOLDER.replace(template.key) { m -> values[m.groupValues[1]] ?: m.value }
            }
        }
        return text
    }

    private fun reverse(table: Table): Pair<Map<String, String>, List<Template>> {
        val exact = HashMap<String, String>(table.size)
        val templates = mutableListOf<Template>()
        for ((key, value) in table) {
            if (value.isEmpty()) continue
            if (PLACEHOLDER.containsMatchIn(value) && literalLength(value) > 0) {
                val (re, slots) = pattern(value)
                templates += Template(key, re, slots, literalLength(value))
            } else {
                exact.putIfAbsent(value, key)
            }
        }
        templates.sortByDescending { it.weight }
        return exact to templates
    }

    /** A Russian text built outside our tables (a server message, an old saved label) translated whole or by template. */
    fun localize(locale: String, raw: String, own: Table?, english: Table?, depth: Int = 0): String {
        val text = raw.removePrefix("Error: ")
        if (locale == Locales.SOURCE) return own?.get(text)?.takeIf { it.isNotEmpty() } ?: text
        if (!CYRILLIC.containsMatchIn(text) || depth > MAX_DEPTH) return text
        (own?.get(text)?.takeIf { it.isNotEmpty() } ?: english?.get(text)?.takeIf { it.isNotEmpty() })?.let { return it }
        val table = english ?: own ?: return text
        for (template in forwardTemplates(table)) {
            val match = template.pattern.matchEntire(text) ?: continue
            val values = slotsOf(template.key).map { slot ->
                localize(locale, match.groupValues[template.slots.indexOf(slot) + 1], own, english, depth + 1)
            }
            return interpolate(lookup(locale, template.key, own, english), template.key, values.toTypedArray())
        }
        return text
    }
}
