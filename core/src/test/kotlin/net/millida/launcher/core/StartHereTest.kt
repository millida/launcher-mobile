package net.millida.launcher.core

import net.millida.launcher.core.loader.LoaderKind
import net.millida.launcher.core.profile.Profile
import net.millida.launcher.core.profile.StartHere
import net.millida.launcher.core.runtime.PhoneVersions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StartHereTest {
    private fun build(id: String, version: String, loader: LoaderKind = LoaderKind.VANILLA) =
        Profile(id = id, name = id, gameVersion = version, loader = loader)

    private val pack = build("fo", "26.2", LoaderKind.FABRIC)

    @Test
    fun `чистая версия вместо сборки, 26_3 пропускаем`() {
        val profiles = listOf(pack, build("v263", "26.3"), build("v262", "26.2"), build("v1211", "1.21.1"))
        assertEquals("v262", StartHere.default(profiles, PhoneVersions.DEFAULT)?.id)
    }

    @Test
    fun `одни сборки - первая из безопасных`() {
        assertEquals("fo", StartHere.default(listOf(build("a", "26.3", LoaderKind.FABRIC), pack), PhoneVersions.DEFAULT)?.id)
        assertNull(StartHere.default(emptyList(), PhoneVersions.DEFAULT))
    }

    @Test
    fun `в списке версии выше сборок, свежая сверху`() {
        val order = StartHere.ordered(listOf(pack, build("old", "1.20.1"), build("new", "26.2"), build("m", "1.21.1", LoaderKind.NEOFORGE)))
        assertEquals(listOf("new", "old", "fo", "m"), order.map { it.id })
    }
}
