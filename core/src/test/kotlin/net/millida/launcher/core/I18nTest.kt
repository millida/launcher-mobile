package net.millida.launcher.core

import net.millida.launcher.core.http.LanguageHeaders
import net.millida.launcher.core.i18n.I18n
import net.millida.launcher.core.i18n.Locales
import net.millida.launcher.core.i18n.Market
import net.millida.launcher.core.i18n.Phrases
import net.millida.launcher.core.i18n.PluralKind
import net.millida.launcher.core.i18n.Plurals
import net.millida.launcher.core.i18n.Scripts
import net.millida.launcher.core.i18n.tr
import net.millida.launcher.core.i18n.trPlural
import net.millida.launcher.core.i18n.trSource
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test

class I18nTest {

    @After
    fun backToRussian() {
        I18n.install(read = { null }, initial = Locales.SOURCE)
    }

    private data class LookupCase(val locale: String, val key: String, val want: String, val why: String)

    @Test
    fun `lookup falls back own language, then English, then the Russian key`() {
        val own = mapOf("Далее" to "Weiter")
        val english = mapOf("Далее" to "Next", "Назад" to "Back")
        listOf(
            LookupCase("ru", "Далее", "Далее", "Russian is the source: the key itself is the text"),
            LookupCase("de", "Далее", "Weiter", "an own translation wins"),
            LookupCase("de", "Назад", "Back", "a missing translation shows English, which far more players read than Russian"),
            LookupCase("de", "Новое", "Новое", "with neither table the key still shows instead of an empty button"),
            LookupCase("en", "Назад", "Back", "English reads its own table"),
        ).forEach { case ->
            val table = when (case.locale) {
                "en" -> english
                "ru" -> null
                else -> own
            }
            assertEquals(case.why, case.want, Phrases.lookup(case.locale, case.key, table, english))
        }
    }

    @Test
    fun `an empty translation counts as missing`() {
        assertEquals("an empty string in a table must not blank a button", "Next", Phrases.lookup("de", "Далее", mapOf("Далее" to ""), mapOf("Далее" to "Next")))
    }

    private data class SlotCase(val template: String, val key: String, val args: List<Any?>, val want: String, val why: String)

    @Test
    fun `values land in slots by the order the slots first appear in the Russian key`() {
        listOf(
            SlotCase("{nick} has {n} rubies", "У {nick} {n} рубинов", listOf("Steve", 5), "Steve has 5 rubies", "every value lands in its slot"),
            SlotCase("{n} rubies for {nick}", "У {nick} {n} рубинов", listOf("Steve", 5), "5 rubies for Steve", "a translation may reorder slots, the key decides which value is which"),
            SlotCase("{0} of {1} MB", "{0} из {1} МБ", listOf(12, 40), "12 of 40 MB", "numbered slots work like the desktop's core templates"),
            SlotCase("{a} and {a}", "{a} и снова {a}", listOf("x"), "x and x", "a repeated slot takes one value"),
            SlotCase("Hi {nick}", "Привет, {nick}", emptyList(), "Hi {nick}", "a missing value stays visible as a slot instead of vanishing"),
            SlotCase("Hi {nick}", "Привет, {nick}", listOf(null), "Hi null", "a null value is shown as Kotlin would print it, not dropped"),
        ).forEach { case ->
            assertEquals(case.why, case.want, Phrases.interpolate(case.template, case.key, case.args.toTypedArray()))
        }
    }

    private data class SystemCase(val tags: List<String>, val want: String, val why: String)

    @Test
    fun `system language picks a table the way the desktop does`() {
        listOf(
            SystemCase(listOf("ru-RU"), "ru", "Russian system"),
            SystemCase(listOf("uk-UA"), "uk", "Ukrainian has its own table and no longer falls back to Russian"),
            SystemCase(listOf("sr"), "sr", "Serbian goes to the Serbian table"),
            SystemCase(listOf("sr-Latn-RS"), "sr", "Serbian in Latin script is what the table is written in"),
            SystemCase(listOf("sr-Cyrl-RS"), "sr", "Cyrillic Serbian readers read the Latin table rather than English"),
            SystemCase(listOf("be-BY"), "ru", "Belarusian readers get Russian"),
            SystemCase(listOf("kk-KZ"), "ru", "Kazakh readers get Russian"),
            SystemCase(listOf("pt-PT"), "pt-BR", "the only Portuguese table is Brazilian"),
            SystemCase(listOf("zh-TW"), "zh", "Chinese goes to the one Chinese table"),
            SystemCase(listOf("nl-NL", "de-DE"), "de", "an unsupported first choice falls through to the next one the system lists"),
            SystemCase(listOf("nl-NL"), "en", "nothing supported means English"),
            SystemCase(listOf("pl_PL"), "pl", "Android may write the region with an underscore"),
            SystemCase(listOf("sk-SK"), "sk", "Slovak has its own table"),
            SystemCase(listOf("sk"), "sk", "a bare Slovak tag gets the Slovak table"),
            SystemCase(listOf("cs-CZ"), "en", "Czech is not mapped to Slovak, that is a separate language decision"),
            SystemCase(listOf("in-ID"), "id", "old Android reports Indonesian as in"),
            SystemCase(emptyList(), "en", "no system answer means English"),
        ).forEach { case ->
            assertEquals(case.why, case.want, Locales.fromSystem(case.tags))
        }
    }

    @Test
    fun `the launcher knows exactly the 21 agreed languages in picker order, right-to-left only for Arabic and Urdu`() {
        assertEquals(
            "the language list and its order in the picker are a product decision shared with the site; change them on purpose, not by accident",
            listOf("ru", "en", "zh", "hi", "es", "ar", "fr", "bn", "pt-BR", "id", "ur", "de", "ja", "vi", "te", "tr", "ko", "pl", "uk", "sr", "sk"),
            Locales.ALL.map { it.code },
        )
        assertEquals("only these scripts are written right to left", listOf("ar", "ur"), Locales.ALL.filter { it.rtl }.map { it.code })
    }

    @Test
    fun `the picker offers the desktop's public languages`() {
        val want = if (Locales.EXTRA_PUBLIC) Locales.ALL.map { it.code }.toSet() else setOf("en", "ru")
        assertEquals("offering a language is the owner decision behind EXTRA_PUBLIC", want, Locales.AVAILABLE_LOCALES.map { it.code }.toSet())
        assertEquals("a language outside the picker is shown in English", "en", Locales.available("nl"))
    }

    private data class PluralCase(val locale: String, val count: Long, val want: PluralKind, val why: String)

    @Test
    fun `plural rules follow CLDR for whole numbers`() {
        listOf(
            PluralCase("ru", 1, PluralKind.ONE, "one"),
            PluralCase("ru", 2, PluralKind.FEW, "two takes the paucal form"),
            PluralCase("ru", 5, PluralKind.MANY, "five is plural"),
            PluralCase("ru", 11, PluralKind.MANY, "11 ends in 1 but is plural"),
            PluralCase("ru", 12, PluralKind.MANY, "12 ends in 2 but is plural"),
            PluralCase("ru", 21, PluralKind.ONE, "21 is singular again"),
            PluralCase("ru", 22, PluralKind.FEW, "22 is paucal again"),
            PluralCase("ru", 111, PluralKind.MANY, "the teen exception holds in hundreds"),
            PluralCase("ru", 0, PluralKind.MANY, "zero is plural"),
            PluralCase("pl", 1, PluralKind.ONE, "Polish one"),
            PluralCase("pl", 21, PluralKind.MANY, "Polish 21 is plural, unlike Russian"),
            PluralCase("pl", 22, PluralKind.FEW, "Polish 22 is paucal"),
            PluralCase("pl", 12, PluralKind.MANY, "Polish 12 is plural"),
            PluralCase("sk", 1, PluralKind.ONE, "Slovak one"),
            PluralCase("sk", 3, PluralKind.FEW, "Slovak 2 to 4 take the paucal form"),
            PluralCase("sk", 5, PluralKind.OTHER, "Slovak five and up take the plural genitive"),
            PluralCase("sk", 22, PluralKind.OTHER, "Slovak 22 is plural, unlike Russian and Polish"),
            PluralCase("sk", 0, PluralKind.OTHER, "Slovak zero is plural"),
            PluralCase("en", 1, PluralKind.ONE, "English one"),
            PluralCase("en", 0, PluralKind.OTHER, "English zero is plural"),
            PluralCase("en", 21, PluralKind.OTHER, "English 21 is plural"),
            PluralCase("de", 1, PluralKind.ONE, "German one"),
            PluralCase("tr", 2, PluralKind.OTHER, "Turkish plural"),
            PluralCase("es", 1_000_000, PluralKind.MANY, "Spanish takes a separate form for whole millions"),
            PluralCase("pt-BR", 0, PluralKind.ONE, "Brazilian Portuguese treats zero as singular"),
            PluralCase("pt-BR", 2, PluralKind.OTHER, "Brazilian Portuguese plural"),
            PluralCase("ru", -2, PluralKind.FEW, "a negative count is declined by its size"),
            PluralCase("ar", 0, PluralKind.ZERO, "Arabic has its own zero"),
            PluralCase("ar", 1, PluralKind.ONE, "Arabic one"),
            PluralCase("ar", 2, PluralKind.TWO, "Arabic dual"),
            PluralCase("ar", 3, PluralKind.FEW, "Arabic 3 to 10 take the plural"),
            PluralCase("ar", 10, PluralKind.FEW, "Arabic 10 still takes the plural"),
            PluralCase("ar", 11, PluralKind.MANY, "Arabic 11 to 99 take the singular accusative"),
            PluralCase("ar", 99, PluralKind.MANY, "Arabic 99 is the last of many"),
            PluralCase("ar", 100, PluralKind.OTHER, "Arabic hundreds are other"),
            PluralCase("ar", 103, PluralKind.FEW, "Arabic looks at the last two digits: 103 is few"),
            PluralCase("ar", 111, PluralKind.MANY, "Arabic 111 is many"),
            PluralCase("fr", 0, PluralKind.ONE, "French zero is singular"),
            PluralCase("fr", 1, PluralKind.ONE, "French one"),
            PluralCase("fr", 2, PluralKind.OTHER, "French plural"),
            PluralCase("fr", 1_000_000, PluralKind.MANY, "French whole millions take de"),
            PluralCase("hi", 0, PluralKind.ONE, "Hindi zero is singular"),
            PluralCase("hi", 2, PluralKind.OTHER, "Hindi plural"),
            PluralCase("bn", 1, PluralKind.ONE, "Bengali one"),
            PluralCase("bn", 5, PluralKind.OTHER, "Bengali plural"),
            PluralCase("ur", 0, PluralKind.OTHER, "Urdu zero is plural, unlike Hindi"),
            PluralCase("ur", 1, PluralKind.ONE, "Urdu one"),
            PluralCase("ur", 21, PluralKind.OTHER, "Urdu 21 is plural"),
            PluralCase("te", 1, PluralKind.ONE, "Telugu one"),
            PluralCase("te", 2, PluralKind.OTHER, "Telugu plural"),
            PluralCase("zh", 1, PluralKind.OTHER, "Chinese has no plural forms"),
            PluralCase("ja", 1, PluralKind.OTHER, "Japanese has no plural forms"),
            PluralCase("ko", 1, PluralKind.OTHER, "Korean has no plural forms"),
            PluralCase("vi", 1, PluralKind.OTHER, "Vietnamese has no plural forms"),
            PluralCase("id", 1, PluralKind.OTHER, "Indonesian has no plural forms"),
            PluralCase("uk", 1, PluralKind.ONE, "Ukrainian one"),
            PluralCase("uk", 3, PluralKind.FEW, "Ukrainian paucal"),
            PluralCase("uk", 11, PluralKind.MANY, "Ukrainian 11 is plural like Russian"),
            PluralCase("uk", 21, PluralKind.ONE, "Ukrainian 21 is singular like Russian"),
            PluralCase("sr", 1, PluralKind.ONE, "Serbian one"),
            PluralCase("sr", 21, PluralKind.ONE, "Serbian 21 is singular"),
            PluralCase("sr", 3, PluralKind.FEW, "Serbian paucal"),
            PluralCase("sr", 12, PluralKind.OTHER, "Serbian teens are other"),
            PluralCase("sr", 5, PluralKind.OTHER, "Serbian has no separate many form"),
        ).forEach { case ->
            assertEquals("${case.locale} ${case.count}: ${case.why}", case.want, Plurals.kind(case.locale, case.count))
        }
    }

    private data class ScriptCase(val code: String, val taller: Boolean, val systemFace: Boolean, val why: String)

    @Test
    fun `tall scripts get taller lines and Vietnamese the system face, the rest stay as designed`() {
        listOf(
            ScriptCase("hi", true, false, "Devanagari marks rise above and hang below the Latin line"),
            ScriptCase("bn", true, false, "Bengali vowel signs need room above and below"),
            ScriptCase("te", true, false, "Telugu conjuncts stack below the letter"),
            ScriptCase("ar", true, false, "Arabic dots and marks need room above and below"),
            ScriptCase("ur", true, false, "Nastaliq slopes down and is the tallest script here"),
            ScriptCase("vi", false, true, "the bundled faces lack part of the Vietnamese letters and would mix two faces in a word"),
            ScriptCase("ru", false, false, "Cyrillic is what the type was designed for"),
            ScriptCase("en", false, false, "Latin is what the type was designed for"),
            ScriptCase("zh", false, false, "Han characters fit the designed line"),
        ).forEach { case ->
            assertEquals("${case.code}: ${case.why}", case.taller, Scripts.lineScale(case.code) > 1f)
            assertEquals("${case.code}: ${case.why}", case.systemFace, Scripts.systemFace(case.code))
        }
        assertEquals("Urdu Nastaliq needs the most room of all", "ur", Locales.ALL.maxBy { Scripts.lineScale(it.code) }.code)
    }

    @Test
    fun `the three Russian forms map onto any language`() {
        assertEquals("English 21 takes the plural text, not the Russian singular", "days", Plurals.pick("en", 21, "day", "days", "days"))
        assertEquals("English 1 takes the singular text", "day", Plurals.pick("en", 1, "day", "days", "days"))
        assertEquals("Russian 3 takes the paucal text", "дня", Plurals.pick("ru", 3, "день", "дня", "дней"))
    }

    @Test
    fun `tr reads the installed table and switches live`() {
        val tables = mapOf("en" to """{"Сборка «{name}» готова": "Build “{name}” is ready", "день": "day", "дня": "days", "дней": "days"}""")
        I18n.install(read = { tables[it] }, initial = "en")
        assertEquals("the English table is used", "Build “Vanilla” is ready", tr("Сборка «{name}» готова", "Vanilla"))
        assertEquals("plural forms follow English rules", "days", trPlural(21, tr("день"), tr("дня"), tr("дней")))
        I18n.setLocale("ru")
        assertEquals("switching back needs no restart", "Сборка «Vanilla» готова", tr("Сборка «{name}» готова", "Vanilla"))
        I18n.setLocale("de")
        assertEquals("German without its own table falls back to English", "Build “Vanilla” is ready", tr("Сборка «{name}» готова", "Vanilla"))
    }

    @Test
    fun `a broken table is ignored instead of crashing the launcher`() {
        I18n.install(read = { "{ not json" }, initial = "en")
        assertEquals("the Russian key shows when the table cannot be read", "Далее", tr("Далее"))
    }

    private data class SourceCase(val text: String, val want: String, val why: String)

    @Test
    fun `a translated message reads back into its Russian source`() {
        val en = mapOf(
            "сервер ответил {code}" to "server replied {code}",
            "нет связи" to "no connection",
            "Не удалось открыть папку: {0}" to "Could not open the folder: {0}",
            "Готово" to "Done",
        )
        listOf(
            SourceCase("server replied 503", "сервер ответил 503", "the status matchers read the Russian wording"),
            SourceCase("no connection", "нет связи", "an exact text maps back whole"),
            SourceCase("Could not open the folder: no connection", "Не удалось открыть папку: нет связи", "a nested message inside a template reads back too"),
            SourceCase("сервер ответил 500", "сервер ответил 500", "a text already in Russian stays as it is"),
            SourceCase("HTTP 500", "HTTP 500", "an unknown text is kept"),
        ).forEach { case ->
            assertEquals(case.why, case.want, Phrases.source("en", case.text, en, en))
        }
        assertEquals("Russian needs no reading back", "Done", Phrases.source("ru", "Done", en, en))
    }

    @Test
    fun `trSource follows the current language`() {
        I18n.install(read = { if (it == "en") """{"сервер ответил {code}": "server replied {code}"}""" else null }, initial = "en")
        assertEquals("FriendsErrors and HostingErrors match the source of what tr produced", "сервер ответил 429", trSource(tr("сервер ответил {code}", 429)))
    }

    private data class CoreCase(val locale: String, val raw: String, val want: String, val why: String)

    @Test
    fun `Russian texts from outside the tables are translated whole, with their inserted parts too`() {
        val en = mapOf(
            "Не удалось открыть папку: {0}" to "Could not open the folder: {0}",
            "Скачиваем {0}…" to "Downloading {0}…",
            "{0} из {1} МБ" to "{0} of {1} MB",
            "нет связи" to "no connection",
            "Готово" to "Done",
        )
        val de = mapOf("Готово" to "Fertig")
        listOf(
            CoreCase("en", "Готово", "Done", "a plain message is looked up whole"),
            CoreCase("en", "Error: Готово", "Done", "the \"Error: \" prefix is not part of the text"),
            CoreCase("en", "Не удалось открыть папку: нет связи", "Could not open the folder: no connection", "a nested message inside a template is translated as well"),
            CoreCase("en", "Скачиваем sodium-0.6.jar…", "Downloading sodium-0.6.jar…", "a file name in the slot is kept as is"),
            CoreCase("en", "12 из 40 МБ", "12 of 40 MB", "numbers in several slots land in their places"),
            CoreCase("de", "Готово", "Fertig", "the own table wins over English"),
            CoreCase("de", "Скачиваем x…", "Downloading x…", "a template missing in the own table falls back to English, not Russian"),
            CoreCase("en", "Совсем новая ошибка", "Совсем новая ошибка", "an unknown text is shown as written rather than dropped"),
            CoreCase("ru", "Error: Готово", "Готово", "Russian shows the text itself"),
            CoreCase("en", "HTTP 500", "HTTP 500", "text without Cyrillic is never touched"),
        ).forEach { case ->
            val own = when (case.locale) {
                "en" -> en
                "de" -> de
                else -> null
            }
            assertEquals(case.why, case.want, Phrases.localize(case.locale, case.raw, own, en))
        }
    }

    private data class MarketCase(val path: String, val market: Market, val want: String?, val why: String)

    @Test
    fun `the API is asked for the market rating like marketFor on the desktop`() {
        listOf(
            MarketCase("/v2/rating/servers", Market.INTL, "EN", "the rating is split by market"),
            MarketCase("/v2/launcher/premium/abc", Market.INTL, "EN", "premium packs follow the rating market"),
            MarketCase("/v2/rating/servers", Market.RU, null, "the Russian market is the default and sends nothing"),
            MarketCase("/v2/launcher/catalog", Market.INTL, null, "only the rating and premium paths are split"),
        ).forEach { case ->
            assertEquals(case.why, case.want, LanguageHeaders.marketFor(case.path, case.market))
        }
    }

    @Test
    fun `decimals follow the reader's notation`() {
        I18n.install(read = { null }, initial = "en")
        assertEquals("English writes a point", "1.5", I18n.decimal(1.5, 1))
        I18n.setLocale("ru")
        assertEquals("Russian writes a comma", "1,5", I18n.decimal(1.5, 1))
        assertEquals("a whole value drops the zero decimal", "2", I18n.decimal(2.0, 1))
    }
}
