package net.millida.launcher.core.i18n

import java.text.NumberFormat
import java.util.Locale
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

object I18n {
    private val json = Json { ignoreUnknownKeys = true }

    @Volatile
    private var reader: (String) -> String? = { null }

    @Volatile
    private var tables: Map<String, Table> = emptyMap()

    private val current = MutableStateFlow(Locales.SOURCE)

    val locale: StateFlow<String> = current.asStateFlow()

    val code: String get() = current.value

    val market: Market get() = Locales.marketOf(code)

    val javaLocale: Locale get() = Locales.javaLocale(code)

    /** Where the tables live is the application's business (assets on the phone, files in tests). */
    fun install(read: (String) -> String?, initial: String) {
        reader = read
        tables = emptyMap()
        setLocale(initial)
    }

    fun setLocale(code: String) {
        val chosen = Locales.available(code)
        ensure(Locales.FALLBACK)
        ensure(chosen)
        current.value = chosen
    }

    fun table(code: String): Table? = tables[code]

    private fun ensure(code: String) {
        if (code == Locales.SOURCE || tables.containsKey(code)) return
        val loaded = parse(runCatching { reader(code) }.getOrNull())
        synchronized(this) { tables = tables + (code to loaded) }
    }

    fun parse(text: String?): Table {
        if (text.isNullOrBlank()) return emptyMap()
        val root = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return emptyMap()
        return parseObject(root)
    }

    private fun parseObject(root: JsonObject): Table =
        root.entries.mapNotNull { (key, value) -> (value as? JsonPrimitive)?.takeIf { it.isString }?.let { key to it.content } }.toMap()

    fun translate(key: String, args: Array<out Any?>): String {
        val code = current.value
        return Phrases.translate(code, key, args, tables[code], tables[Locales.FALLBACK])
    }

    fun source(text: String): String {
        val code = current.value
        return Phrases.source(code, text, tables[code], tables[Locales.FALLBACK])
    }

    fun localize(text: String): String {
        val code = current.value
        return Phrases.localize(code, text, tables[code], tables[Locales.FALLBACK])
    }

    fun plural(count: Long, one: String, few: String, many: String): String = Plurals.pick(code, count, one, few, many)

    fun number(value: Number): String = NumberFormat.getNumberInstance(javaLocale).format(value)

    /** Up to [digits] decimals in the reader's notation, without trailing zeros and without grouping, as the desktop's formatFixed. */
    fun decimal(value: Double, digits: Int): String = NumberFormat.getNumberInstance(javaLocale).apply {
        minimumFractionDigits = 0
        maximumFractionDigits = digits
        isGroupingUsed = false
    }.format(value)
}

/** A text the player reads: the Russian source is the key, `{name}` slots take [args] in the order they first appear. */
fun tr(key: String, vararg args: Any?): String = I18n.translate(key, args)

/** A key kept as data (an enum label, a saved default) and translated where it is shown; the scan of the sources finds it here. */
fun trKey(key: String): String = key

/** Picks the form for [count] by the rules of the current language; the forms are the Russian three, each passed through [tr]. */
fun trPlural(count: Long, one: String, few: String, many: String): String = I18n.plural(count, one, few, many)

fun trPlural(count: Int, one: String, few: String, many: String): String = I18n.plural(count.toLong(), one, few, many)

/** The Russian source of a text this code produced earlier, for code that recognises its own messages. */
fun trSource(text: String): String = I18n.source(text)

/** A Russian text that did not come from [tr] (a server answer, a stored label), translated whole or by template. */
fun trText(text: String): String = I18n.localize(text)
