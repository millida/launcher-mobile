package net.millida.launcher.core.i18n

import java.util.Locale

/**
 * [system] is the tag handed to Android's per-app language list and named in locales_config.xml. It differs
 * from [code] only for Serbian: a bare "sr" is Cyrillic to Android, while the table is written in Latin.
 */
data class LocaleInfo(
    val code: String,
    val native: String,
    val english: String,
    val intl: String,
    val rtl: Boolean,
    val system: String = code,
)

enum class Market { RU, INTL }

object Locales {
    const val SOURCE = "ru"
    const val FALLBACK = "en"

    /** The picker shows the languages in this order: Russian, English, then by number of speakers, as on the site. */
    val ALL: List<LocaleInfo> = listOf(
        LocaleInfo("ru", "Русский", "Russian", "ru-RU", false),
        LocaleInfo("en", "English", "English", "en-US", false),
        LocaleInfo("zh", "简体中文", "Chinese (Simplified)", "zh-CN", false),
        LocaleInfo("hi", "हिन्दी", "Hindi", "hi-IN", false),
        LocaleInfo("es", "Español", "Spanish", "es-ES", false),
        LocaleInfo("ar", "العربية", "Arabic", "ar", true),
        LocaleInfo("fr", "Français", "French", "fr-FR", false),
        LocaleInfo("bn", "বাংলা", "Bengali", "bn-BD", false),
        LocaleInfo("pt-BR", "Português (Brasil)", "Portuguese (Brazil)", "pt-BR", false),
        LocaleInfo("id", "Bahasa Indonesia", "Indonesian", "id-ID", false),
        LocaleInfo("ur", "اردو", "Urdu", "ur-PK", true),
        LocaleInfo("de", "Deutsch", "German", "de-DE", false),
        LocaleInfo("ja", "日本語", "Japanese", "ja-JP", false),
        LocaleInfo("vi", "Tiếng Việt", "Vietnamese", "vi-VN", false),
        LocaleInfo("te", "తెలుగు", "Telugu", "te-IN", false),
        LocaleInfo("tr", "Türkçe", "Turkish", "tr-TR", false),
        LocaleInfo("ko", "한국어", "Korean", "ko-KR", false),
        LocaleInfo("pl", "Polski", "Polish", "pl-PL", false),
        LocaleInfo("uk", "Українська", "Ukrainian", "uk-UA", false),
        LocaleInfo("sr", "Srpski", "Serbian", "sr-Latn-RS", false, system = "sr-Latn"),
        LocaleInfo("sk", "Slovenčina", "Slovak", "sk-SK", false),
    )

    /** The same switch as the desktop: the translated tables are offered together, after review. */
    const val EXTRA_PUBLIC = true

    val EXTRA: List<String> = listOf("pt-BR", "es", "de", "tr", "pl", "zh", "hi", "ar", "fr", "bn", "id", "ur", "ja", "vi", "te", "ko", "uk", "sr", "sk")

    private val CODES: Set<String> = ALL.map { it.code }.toSet()

    private val AVAILABLE: Set<String> = setOf(SOURCE, FALLBACK) + if (EXTRA_PUBLIC) EXTRA else emptyList()

    val AVAILABLE_LOCALES: List<LocaleInfo> = ALL.filter { it.code in AVAILABLE }

    /** Players from these systems read Russian far better than English, and there is no table of their own. */
    private val RUSSIAN_READERS = setOf("ru", "be", "kk")

    fun isLocale(code: String?): Boolean = code != null && code in CODES

    fun isAvailable(code: String?): Boolean = code != null && code in AVAILABLE

    fun info(code: String): LocaleInfo = ALL.firstOrNull { it.code == code } ?: ALL.first { it.code == FALLBACK }

    fun fromSystem(tags: List<String>): String {
        for (raw in tags) {
            val tag = raw.trim().lowercase(Locale.ROOT)
            if (tag.isEmpty()) continue
            val base = tag.split('-', '_').first()
            if (base in RUSSIAN_READERS) return SOURCE
            if (base == "pt") return "pt-BR"
            if (base == "in") return "id"
            if (isLocale(base)) return base
        }
        return FALLBACK
    }

    fun available(code: String): String = if (isAvailable(code)) code else FALLBACK

    fun marketOf(code: String): Market = if (code == SOURCE) Market.RU else Market.INTL

    fun javaLocale(code: String): Locale = Locale.forLanguageTag(info(code).intl)
}
