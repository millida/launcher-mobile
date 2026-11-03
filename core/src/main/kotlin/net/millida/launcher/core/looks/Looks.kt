package net.millida.launcher.core.looks


import net.millida.launcher.core.i18n.tr
import net.millida.launcher.core.i18n.trKey
/**
 * What the account opens in the launcher's look: the paid tier (PLUS or
 * DIAMOND, null without a subscription) and the rewards of the friends ladder
 * (`icon-spark` and the like), as the desktop reads them for its icon picker
 * and theme row (src/lib/appIconRules.ts, src/lib/premiumTheme.ts).
 */
data class LookAccess(val tier: String? = null, val perks: Set<String> = emptySet()) {
    val plus: Boolean get() = tier != null
    val diamond: Boolean get() = tier == PremiumLook.TIER_DIAMOND
}

/** The launcher theme: «Стандартная», PLUS or Diamond, open while the subscription lasts. */
object PremiumLook {
    const val OFF = "off"
    const val PLUS = "plus"
    const val DIAMOND = "diamond"
    const val TIER_PLUS = "PLUS"
    const val TIER_DIAMOND = "DIAMOND"
    const val ACCENT_PLUS = "premium-plus"
    const val ACCENT_DIAMOND = "premium-diamond"

    val THEMES = listOf(OFF, PLUS, DIAMOND)

    fun known(theme: String?): String = theme?.takeIf { it in THEMES } ?: OFF

    /** Diamond opens both themes, PLUS only its own; the standard one is always there. */
    fun allowed(theme: String, tier: String?): Boolean = when {
        theme == OFF -> true
        tier == TIER_DIAMOND -> true
        tier == TIER_PLUS -> theme == PLUS
        else -> false
    }

    fun themeFor(tier: String?): String = when (tier) {
        TIER_DIAMOND -> DIAMOND
        TIER_PLUS -> PLUS
        else -> OFF
    }

    fun accentOf(theme: String): String? = when (theme) {
        PLUS -> ACCENT_PLUS
        DIAMOND -> ACCENT_DIAMOND
        else -> null
    }

    fun isPremiumAccent(id: String): Boolean = id == ACCENT_PLUS || id == ACCENT_DIAMOND

    /** The gold swatch is open with any subscription, the diamond one with Diamond only. */
    fun accentAllowed(id: String, tier: String?): Boolean = when (id) {
        ACCENT_PLUS -> tier != null
        ACCENT_DIAMOND -> tier == TIER_DIAMOND
        else -> true
    }

    /** A subscription appeared, or PLUS became Diamond: a payment, which switches the theme on once. */
    fun paid(before: String?, now: String?): Boolean =
        now != null && (before == null || (before == TIER_PLUS && now == TIER_DIAMOND))
}

enum class IconRule { FREE, FRIENDS, PLUS, DIAMOND }

/** The desktop's APP_ICONS in its order; [perk] is the friends-ladder reward that opens the icon. */
enum class AppIcon(val id: String, private val titleKey: String, val rule: IconRule, private val needKey: String, val perk: String? = null) {
    DEFAULT("default", "Millida", IconRule.FREE, ""),
    SPARK("spark", trKey("Искра"), IconRule.FRIENDS, trKey("Пригласи 5 друзей"), "icon-spark"),
    FLAME("flame", trKey("Пламя"), IconRule.FRIENDS, trKey("Пригласи 10 друзей"), "icon-flame"),
    AMETHYST("amethyst", trKey("Аметист"), IconRule.FRIENDS, trKey("Пригласи 25 друзей"), "icon-amethyst"),
    LEGEND("legend", trKey("Легенда"), IconRule.FRIENDS, trKey("Пригласи 50 друзей"), "icon-legend"),
    GOLD("gold", trKey("Золото"), IconRule.PLUS, trKey("Только с PLUS")),

    /** `icon-diamond` was the 25-friends reward before 06.10.2026: who has it keeps the icon. */
    DIAMOND("diamond", trKey("Алмаз"), IconRule.DIAMOND, trKey("Только с Diamond"), "icon-diamond"),
    ;

    val title: String get() = tr(titleKey)
    val need: String get() = tr(needKey)

    companion object {
        fun of(id: String?): AppIcon = entries.firstOrNull { it.id == id } ?: DEFAULT

        fun unlocked(icon: AppIcon, access: LookAccess): Boolean = when {
            icon.perk != null && icon.perk in access.perks -> true
            icon == DEFAULT -> true
            icon == GOLD -> access.plus
            icon == DIAMOND -> access.diamond
            else -> false
        }

        /** A choice is kept while its icon is taken away (PLUS ran out) and comes back with it. */
        fun effective(chosen: AppIcon, access: LookAccess): AppIcon = if (unlocked(chosen, access)) chosen else DEFAULT
    }
}
