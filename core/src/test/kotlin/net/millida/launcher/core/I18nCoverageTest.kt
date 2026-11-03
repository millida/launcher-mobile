package net.millida.launcher.core

import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import net.millida.launcher.core.i18n.I18n
import net.millida.launcher.core.i18n.Locales
import net.millida.launcher.core.i18n.Phrases
import net.millida.launcher.core.i18n.Plurals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The phone's side of the desktop's coverage.test.ts and the dictionary checks of i18n.test.ts:
 * every text wrapped in tr() is in the key list the translators work from, and no table breaks a slot.
 */
class I18nCoverageTest {

    private val root: File = generateSequence(File("").absoluteFile) { it.parentFile }
        .first { File(it, "settings.gradle.kts").isFile }

    private val tablesDir = File(root, "app/src/main/assets/i18n")

    /** Languages whose table must hold every key: all of them, as on the desktop, so no player sees a half-English launcher. */
    private val required = Locales.ALL.map { it.code }.toSet() - Locales.SOURCE

    private val call = Regex("""(?<![\w.])(?:tr|trKey)\(\s*(?:""" + "\"\"\"" + """([\s\S]*?)""" + "\"\"\"" + """|"((?:[^"\\\n]|\\.)*)")""")

    private fun unescape(raw: String): String = Regex("""\\(u[0-9a-fA-F]{4}|.)""").replace(raw) { m ->
        val c = m.groupValues[1]
        when {
            c.length == 5 -> c.substring(1).toInt(16).toChar().toString()
            c == "n" -> "\n"
            c == "t" -> "\t"
            c == "r" -> "\r"
            c == "b" -> "\b"
            else -> c
        }
    }

    private fun calls(): List<MatchResult> = listOf("app", "core", "runtime")
        .map { File(root, "$it/src/main") }
        .filter { it.isDirectory }
        .flatMap { dir -> dir.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList() }
        .flatMap { file -> call.findAll(file.readText()).toList() }

    private fun scan(): Set<String> = calls()
        .map { m -> m.groups[1]?.value ?: unescape(m.groupValues[2]) }
        .filter { it.isNotBlank() }
        .toSet()

    private fun table(file: File): Map<String, String> = I18n.parse(file.readText())

    @Test
    fun `the key list is the current scan of the sources`() {
        val scanned = scan()
        assertTrue("the tr() scan found almost nothing: the scanner broke, and this test would pass on an empty list", scanned.size > 2000)
        val listed = Json.parseToJsonElement(File(root, "scripts/i18n_keys.json").readText()).jsonArray.map { it.jsonPrimitive.content }.toSet()
        assertEquals(
            "scripts/i18n_keys.json is what translators work from and it went stale: run `bun scripts/i18n_extract.ts`",
            scanned.sorted(),
            listed.sorted(),
        )
    }

    @Test
    fun `no key is a Kotlin template`() {
        val template = Regex("""(?<!\\)\$(\{|[A-Za-z_])""")
        val templated = calls().filter { template.containsMatchIn(it.groups[1]?.value ?: it.groupValues[2]) }.map { it.value }
        assertEquals("a key with \$value changes with every value and never matches a table; use {name} slots", emptyList<String>(), templated)
    }

    @Test
    fun `every table is named after a supported non-Russian language and keeps every slot`() {
        val files = tablesDir.listFiles { f -> f.extension == "json" }.orEmpty()
        assertTrue("the phone ships the desktop's translated languages", files.map { it.nameWithoutExtension }.toSet().containsAll(Locales.EXTRA + Locales.FALLBACK))
        for (file in files) {
            val code = file.nameWithoutExtension
            assertTrue("${file.name}: a table must be named after a supported non-Russian language", Locales.isLocale(code) && code != Locales.SOURCE)
            for ((key, value) in table(file)) {
                assertEquals(
                    "${file.name}: \"$key\" lost or renamed a placeholder, the player would see {name} as text",
                    Phrases.placeholdersOf(key),
                    Phrases.placeholdersOf(value),
                )
            }
        }
    }

    @Test
    fun `every language but Russian has a table and must fill it`() {
        assertEquals("20 tables besides the Russian source: English and 19 more", 20, required.size)
        val missingTables = required.filter { !File(tablesDir, "$it.json").isFile }
        assertEquals("these languages are offered in the picker but have no table", emptyList<String>(), missingTables)
        assertEquals("a language offered in the picker is held to the full table", required, (Locales.EXTRA + Locales.FALLBACK).toSet())
    }

    @Test
    fun `Android's per-app language list names exactly the launcher languages`() {
        val config = File(root, "app/src/main/res/xml/locales_config.xml").readText()
        val listed = Regex("""android:name="([^"]+)"""").findAll(config).map { it.groupValues[1] }.toList()
        assertEquals(
            "locales_config.xml is the system settings' language list: a missing tag hides the language there, an extra one offers a language the launcher cannot show",
            Locales.ALL.map { it.system }.sorted(),
            listed.sorted(),
        )
    }

    private data class PluralTableCase(val code: String, val count: Long, val form: String, val why: String)

    /**
     * The tables translate the three Russian forms one by one, and the code picks one by the language's own rule,
     * so the pick must land on the translator's text for that role: one for "one", few for "few", many for the rest.
     */
    @Test
    fun `plural forms from the tables land on the right translated text`() {
        val forms = mapOf("one" to "день", "few" to "дня", "many" to "дней")
        listOf(
            PluralTableCase("ar", 1, "one", "Arabic singular"),
            PluralTableCase("ar", 5, "few", "Arabic 3 to 10 read the plural"),
            PluralTableCase("ar", 11, "many", "Arabic 11 and above read the singular accusative"),
            PluralTableCase("ar", 100, "many", "Arabic hundreds read the singular"),
            PluralTableCase("ar", 2, "many", "the Arabic dual has no Russian form and reads the many text"),
            PluralTableCase("fr", 0, "one", "French zero is singular"),
            PluralTableCase("fr", 2, "many", "French plural"),
            PluralTableCase("hi", 1, "one", "Hindi singular"),
            PluralTableCase("hi", 5, "many", "Hindi plural"),
            PluralTableCase("bn", 3, "many", "Bengali plural"),
            PluralTableCase("ur", 1, "one", "Urdu singular"),
            PluralTableCase("ur", 7, "many", "Urdu plural"),
            PluralTableCase("te", 1, "one", "Telugu singular"),
            PluralTableCase("te", 2, "many", "Telugu plural"),
            PluralTableCase("zh", 1, "many", "Chinese reads one form for every count"),
            PluralTableCase("ja", 3, "many", "Japanese reads one form for every count"),
            PluralTableCase("ko", 21, "many", "Korean reads one form for every count"),
            PluralTableCase("vi", 1, "many", "Vietnamese reads one form for every count"),
            PluralTableCase("id", 2, "many", "Indonesian reads one form for every count"),
            PluralTableCase("uk", 1, "one", "Ukrainian singular"),
            PluralTableCase("uk", 3, "few", "Ukrainian paucal"),
            PluralTableCase("uk", 5, "many", "Ukrainian plural"),
            PluralTableCase("sr", 21, "one", "Serbian 21 is singular"),
            PluralTableCase("sr", 3, "few", "Serbian paucal"),
            PluralTableCase("sr", 5, "many", "Serbian other reads the many text"),
            PluralTableCase("sk", 1, "one", "Slovak singular"),
            PluralTableCase("sk", 3, "few", "Slovak paucal"),
            PluralTableCase("sk", 22, "many", "Slovak 22 reads the many text, unlike Serbian"),
        ).forEach { case ->
            val own = table(File(tablesDir, "${case.code}.json"))
            fun text(role: String): String = forms.getValue(role).let { key -> own[key] ?: error("${case.code}.json lacks \"$key\"") }
            val picked = Plurals.pick(case.code, case.count, text("one"), text("few"), text("many"))
            assertEquals("${case.code} ${case.count}: ${case.why}", text(case.form), picked)
        }
        val arabic = table(File(tablesDir, "ar.json"))
        assertTrue(
            "ar.json: the few form (3 to 10) must be the Arabic plural and differ from the many form (the singular accusative), or Arabic counts read wrong",
            arabic["дня"] != arabic["дней"],
        )
    }

    @Test
    fun `required languages cover every key, the rest report what is missing`() {
        val keys = scan()
        val report = StringBuilder()
        for (code in Locales.EXTRA + Locales.FALLBACK) {
            val own = File(tablesDir, "$code.json").takeIf { it.isFile }?.let(::table).orEmpty()
            val missing = keys.filter { own[it].isNullOrEmpty() }.sorted()
            report.append("$code: ${keys.size - missing.size}/${keys.size}, missing ${missing.size}\n")
            if (code in required) {
                assertEquals("$code.json lacks these texts, so a $code player sees them in English or Russian", emptyList<String>(), missing)
            }
        }
        println(report)
    }

    /**
     * Whole sentences that carry the space to the sentence glued after them. Anything else with a space at its
     * edge is a fragment of a longer phrase, and a translation of a fragment cannot reorder or decline the rest.
     */
    private val edgeSpaceSentences = setOf(
        "Minecraft {gameVersion} на Android 10 и старше не запускается: система не даёт игре загрузить её библиотеки. ",
        "{0} в прошлый раз не запустил эту сборку — запускаем на {1}. ",
        "{0} не запустит {gameVersion}: {problem}. ",
        "«{title}» запускается своим способом, написанным для компьютера, — на телефоне она не работает. ",
        "«{title}» открывает окно игры через SDL3, а на телефоне этой библиотеки нет. ",
        "В «{title}» есть библиотеки, собранные только для компьютера. ",
        "В сборке {installedFile}, а нужна {availableFile}. ",
        "Игра запускается только на процессоре ARM, а это устройство — {0} (похоже на эмулятор на компьютере): Java из пакета рантайма на нём не загружается. ",
        "Косметика на месте: скины, плащи, шляпы, крылья и эмоции работают. ",
        "На телефоне {nominal} ГБ памяти — игра может вылетать. ",
        "Оценка: {stars} из 5. ",
        "Свободно всего {availableMb} МБ — игру закроет система. ",
        "Слепок сборок весит {0} КБ — это больше предела в {1} КБ. ",
        "установка загрузчика ждала, пока лаунчер откроют: Android не даёт начать её в свёрнутом приложении. ",
        "установщик загрузчика остановился на шаге {0} из {total}: {it}. ",
    )

    @Test
    fun `a key starts and ends with text, except a whole sentence on the allow-list`() {
        val keys = scan()
        val loose = keys.filter { it != it.trim() && it !in edgeSpaceSentences }.sorted()
        assertEquals(
            "a key with a space at its edge is a piece of a phrase glued to another piece: translate the whole phrase with {name} slots or trPlural forms",
            emptyList<String>(),
            loose,
        )
        assertEquals("allow-list entries that are no longer keys keep the exception alive for nothing: remove them", emptyList<String>(), (edgeSpaceSentences - keys).sorted())
    }

    private class BlockTitle(val key: String, val args: List<String>, val accent: String)

    /** Index of the first [stops] char outside strings and brackets, or the end of [text]. */
    private fun scanTo(text: String, from: Int, stops: String): Int {
        var depth = 0
        var i = from
        while (i < text.length) {
            val c = text[i]
            when {
                c == '"' -> {
                    i++
                    while (i < text.length && text[i] != '"') {
                        if (text[i] == '\\') i++
                        i++
                    }
                }
                c == '(' || c == '{' || c == '[' -> depth++
                (c == ')' || c == '}' || c == ']') && depth > 0 -> depth--
                depth == 0 && c in stops -> return i
            }
            i++
        }
        return text.length
    }

    private fun arguments(list: String): List<String> {
        val out = mutableListOf<String>()
        var from = 0
        while (from < list.length) {
            val end = scanTo(list, from, ",")
            out += list.substring(from, end).trim()
            from = end + 1
        }
        return out.filter { it.isNotEmpty() }
    }

    private fun blockTitles(text: String): List<BlockTitle> = Regex("""title\s*=\s*tr\(""").findAll(text).mapNotNull { m ->
        val open = m.range.last + 1
        val close = scanTo(text, open, ")")
        val parts = arguments(text.substring(open, close))
        val key = parts.firstOrNull()?.takeIf { it.length >= 2 && it.startsWith('"') && it.endsWith('"') } ?: return@mapNotNull null
        val tail = Regex("""^\s*,\s*accent\s*=\s*""").find(text.substring(close + 1)) ?: return@mapNotNull null
        val start = close + 1 + tail.range.last + 1
        val accent = text.substring(start, scanTo(text, start, ",)")).trim()
        BlockTitle(unescape(key.substring(1, key.length - 1)), parts.drop(1), accent)
    }.toList()

    /**
     * The Plus page highlights a block's accent where it stands in the title, so a translated title must still
     * contain it: a literal accent as its own translated text, an argument accent as the slot it fills.
     */
    @Test
    fun `a block accent sits inside its title in Russian and in every table`() {
        val blocks = listOf("app", "core")
            .map { File(root, "$it/src/main") }
            .flatMap { dir -> dir.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList() }
            .flatMap { blockTitles(it.readText()) }
        assertTrue("the scan found fewer title/accent pairs than the Plus page has: the parser broke and the check would pass on nothing", blocks.size >= 8)
        assertTrue("no block title with arguments was seen: the rubies block, whose accent is its number, went unchecked", blocks.any { it.args.isNotEmpty() })
        val literal = Regex("""^tr\("((?:[^"\\]|\\.)*)"\)$""")
        val slot = Regex("""\{(\w+)\}""")
        val tables = listOf(Locales.SOURCE to emptyMap<String, String>()) +
            tablesDir.listFiles { f -> f.extension == "json" }.orEmpty().map { it.nameWithoutExtension to table(it) }
        val broken = mutableListOf<String>()
        for (block in blocks) {
            val accentKey = literal.matchEntire(block.accent)?.let { unescape(it.groupValues[1]) }
            val slotName = if (accentKey == null) slot.findAll(block.key).map { it.groupValues[1] }.distinct().toList().getOrNull(block.args.indexOf(block.accent)) else null
            if (accentKey == null && slotName == null) {
                broken += "\"${block.key}\": the accent `${block.accent}` is neither a tr() text nor an argument of the title"
                continue
            }
            for ((code, own) in tables) {
                val title = own[block.key]?.ifEmpty { null } ?: block.key
                val accent = if (slotName != null) "{$slotName}" else accentKey!!.let { own[it]?.ifEmpty { null } ?: it }
                if (accent.isEmpty() || !title.contains(accent)) broken += "$code: \"$title\" does not contain \"$accent\""
            }
        }
        assertEquals("the accent is found in the title and painted in place; one the title lacks would be drawn as a stray word", emptyList<String>(), broken)
    }
}
