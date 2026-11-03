package net.millida.launcher.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import net.millida.launcher.core.friends.Referrals
import net.millida.launcher.core.looks.AppIcon
import net.millida.launcher.core.looks.LookAccess
import net.millida.launcher.core.looks.PremiumLook
import org.junit.Assert.assertEquals
import org.junit.Test

class LooksTest {

    private val none = LookAccess()
    private val plus = LookAccess(tier = PremiumLook.TIER_PLUS)
    private val diamond = LookAccess(tier = PremiumLook.TIER_DIAMOND)

    @Test
    fun `themes open by tier as on the desktop`() {
        val cases = listOf(
            Triple(PremiumLook.OFF, null, true) to "the standard theme never locks",
            Triple(PremiumLook.PLUS, null, false) to "no subscription, no gold theme",
            Triple(PremiumLook.PLUS, PremiumLook.TIER_PLUS, true) to "PLUS opens its own theme",
            Triple(PremiumLook.DIAMOND, PremiumLook.TIER_PLUS, false) to "PLUS must not open the Diamond theme",
            Triple(PremiumLook.PLUS, PremiumLook.TIER_DIAMOND, true) to "Diamond opens the gold theme too",
            Triple(PremiumLook.DIAMOND, PremiumLook.TIER_DIAMOND, true) to "Diamond opens its own theme",
        )
        cases.forEach { (case, why) ->
            assertEquals(why, case.third, PremiumLook.allowed(case.first, case.second))
        }
    }

    @Test
    fun `premium swatches follow the subscription`() {
        val cases = listOf(
            Triple(PremiumLook.ACCENT_PLUS, null, false) to "gold swatch is locked without a subscription",
            Triple(PremiumLook.ACCENT_PLUS, PremiumLook.TIER_PLUS, true) to "gold swatch opens with PLUS",
            Triple(PremiumLook.ACCENT_DIAMOND, PremiumLook.TIER_PLUS, false) to "diamond swatch stays locked on PLUS",
            Triple(PremiumLook.ACCENT_DIAMOND, PremiumLook.TIER_DIAMOND, true) to "diamond swatch opens with Diamond",
            Triple("green", null, true) to "ordinary colours are free",
        )
        cases.forEach { (case, why) ->
            assertEquals(why, case.third, PremiumLook.accentAllowed(case.first, case.second))
        }
    }

    @Test
    fun `a payment is a new subscription or an upgrade`() {
        val cases = listOf(
            Triple(null, PremiumLook.TIER_PLUS, true) to "buying PLUS switches the theme on once",
            Triple(PremiumLook.TIER_PLUS, PremiumLook.TIER_DIAMOND, true) to "the upgrade to Diamond is a payment",
            Triple(PremiumLook.TIER_PLUS, PremiumLook.TIER_PLUS, false) to "a renewal must not repaint over the player's choice",
            Triple(PremiumLook.TIER_DIAMOND, PremiumLook.TIER_PLUS, false) to "a downgrade is not a payment",
            Triple(PremiumLook.TIER_PLUS, null, false) to "the end of a subscription is not a payment",
        )
        cases.forEach { (case, why) ->
            assertEquals(why, case.third, PremiumLook.paid(case.first, case.second))
        }
    }

    @Test
    fun `icons open by the desktop's rules`() {
        val cases = listOf(
            Triple(AppIcon.DEFAULT, none, true) to "the default icon is free",
            Triple(AppIcon.SPARK, none, false) to "the friends ladder needs its reward",
            Triple(AppIcon.SPARK, LookAccess(perks = setOf("icon-spark")), true) to "icon-spark opens Искра",
            Triple(AppIcon.FLAME, LookAccess(perks = setOf("icon-spark")), false) to "a lower reward must not open a higher icon",
            Triple(AppIcon.GOLD, plus, true) to "PLUS opens the gold icon",
            Triple(AppIcon.GOLD, diamond, true) to "Diamond opens the gold icon too",
            Triple(AppIcon.GOLD, none, false) to "no subscription, no gold icon",
            Triple(AppIcon.DIAMOND, plus, false) to "PLUS must not open the diamond icon",
            Triple(AppIcon.DIAMOND, diamond, true) to "Diamond opens the diamond icon",
            Triple(AppIcon.DIAMOND, LookAccess(perks = setOf("icon-diamond")), true) to "the old 25-friends reward keeps the diamond icon",
        )
        cases.forEach { (case, why) ->
            assertEquals(why, case.third, AppIcon.unlocked(case.first, case.second))
        }
    }

    @Test
    fun `a lost icon shows the default but keeps the choice`() {
        assertEquals("PLUS ran out: the gold icon gives way to the default", AppIcon.DEFAULT, AppIcon.effective(AppIcon.GOLD, none))
        assertEquals("PLUS is back: the kept choice returns", AppIcon.GOLD, AppIcon.effective(AppIcon.GOLD, plus))
        assertEquals("an unknown stored id falls back to the default", AppIcon.DEFAULT, AppIcon.of("rainbow"))
    }

    @Test
    fun `the ladder rewards arrive with the invite overview`() {
        val body = Json.parseToJsonElement(
            """{"code":"abc12","perks":[{"id":"icon-spark","kind":"icon","name":"Искра"},{"kind":"badge"}]}""",
        ).jsonObject
        assertEquals("the icon picker reads perk ids; one without an id is skipped", setOf("icon-spark"), Referrals.read(body).perks)
    }
}
