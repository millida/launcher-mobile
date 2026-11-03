package net.millida.launcher.ui.settings

import net.millida.launcher.R
import net.millida.launcher.core.i18n.Locales
import org.junit.Assert.assertEquals
import org.junit.Test

class LanguageFlagsTest {

    @Test
    fun `every language in the picker has a flag`() {
        val bare = Locales.AVAILABLE_LOCALES.map { it.code }.filter { languageFlag(it) == null }
        assertEquals("a tile without a flag stands out in the grid; add its flag to languageFlag", emptyList<String>(), bare)
    }

    @Test
    fun `flags follow the site's picker`() {
        data class Case(val code: String, val flag: Int, val why: String)
        listOf(
            Case("en", R.drawable.flag_us, "English shows the US flag, as on the site"),
            Case("te", R.drawable.flag_in, "Telugu is spoken in India and shares the Indian flag with Hindi"),
            Case("ur", R.drawable.flag_pk, "Urdu is Pakistan's language"),
            Case("ar", R.drawable.flag_sa, "Arabic shows Saudi Arabia, as on the site"),
            Case("uk", R.drawable.flag_ua, "Ukrainian shows Ukraine"),
            Case("sr", R.drawable.flag_rs, "Serbian shows Serbia"),
        ).forEach { case -> assertEquals("${case.code}: ${case.why}", case.flag, languageFlag(case.code)) }
    }
}
