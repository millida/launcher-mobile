package net.millida.launcher.core.i18n

enum class PluralKind { ZERO, ONE, TWO, FEW, MANY, OTHER }

/**
 * CLDR cardinal rules for whole numbers, the part of Intl.PluralRules the desktop relies on.
 * The JVM has no plural rules of its own and the core does not see Android's ICU.
 */
object Plurals {
    fun kind(locale: String, count: Long): PluralKind {
        val n = if (count < 0) -count else count
        val mod10 = n % 10
        val mod100 = n % 100
        val millions = n != 0L && n % 1_000_000L == 0L
        return when (locale.substringBefore('-')) {
            "ru", "uk" -> when {
                mod10 == 1L && mod100 != 11L -> PluralKind.ONE
                mod10 in 2L..4L && mod100 !in 12L..14L -> PluralKind.FEW
                else -> PluralKind.MANY
            }
            "sr" -> when {
                mod10 == 1L && mod100 != 11L -> PluralKind.ONE
                mod10 in 2L..4L && mod100 !in 12L..14L -> PluralKind.FEW
                else -> PluralKind.OTHER
            }
            "sk" -> when (n) {
                1L -> PluralKind.ONE
                in 2L..4L -> PluralKind.FEW
                else -> PluralKind.OTHER
            }
            "pl" -> when {
                n == 1L -> PluralKind.ONE
                mod10 in 2L..4L && mod100 !in 12L..14L -> PluralKind.FEW
                else -> PluralKind.MANY
            }
            "pt", "fr" -> when {
                n <= 1L -> PluralKind.ONE
                millions -> PluralKind.MANY
                else -> PluralKind.OTHER
            }
            "es", "it" -> when {
                n == 1L -> PluralKind.ONE
                millions -> PluralKind.MANY
                else -> PluralKind.OTHER
            }
            "hi", "bn" -> if (n <= 1L) PluralKind.ONE else PluralKind.OTHER
            "ar" -> when {
                n == 0L -> PluralKind.ZERO
                n == 1L -> PluralKind.ONE
                n == 2L -> PluralKind.TWO
                mod100 in 3L..10L -> PluralKind.FEW
                mod100 in 11L..99L -> PluralKind.MANY
                else -> PluralKind.OTHER
            }
            "zh", "ja", "ko", "vi", "id" -> PluralKind.OTHER
            else -> if (n == 1L) PluralKind.ONE else PluralKind.OTHER
        }
    }

    /** The three Russian forms stand for every language: what is not "one" or "few" takes the "many" text, as on the desktop. */
    fun pick(locale: String, count: Long, one: String, few: String, many: String): String = when (kind(locale, count)) {
        PluralKind.ONE -> one
        PluralKind.FEW -> few
        else -> many
    }
}
