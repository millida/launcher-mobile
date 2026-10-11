package net.millida.launcher.core

import net.millida.launcher.core.catalog.CatalogFile
import net.millida.launcher.core.catalog.CatalogVersion
import net.millida.launcher.core.catalog.ContentKind
import net.millida.launcher.core.cosmetics.CosmeticsModState
import net.millida.launcher.core.cosmetics.CosmeticsModStatus
import net.millida.launcher.core.cosmetics.MillidaModOffer
import net.millida.launcher.core.profile.ContentFile
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The question before a start is the only way the mod reaches most phone builds,
 * and «Без мода» must stick: asking a build that has the jar, or one whose owner
 * refused, nags; not asking a build that can take it leaves voice and cosmetics out.
 */
class MillidaModOfferTest {

    private fun state(installedFile: String = "", availableFile: String = "", enabled: Boolean = true) =
        CosmeticsModState(
            profileId = "p",
            gameVersion = "1.21.11",
            loaderId = "fabric",
            status = CosmeticsModStatus.MISSING,
            installedFile = installedFile,
            installedEnabled = enabled,
            availableFile = availableFile,
        )

    private fun mod(modId: String, title: String = modId, name: String = "$modId.jar") =
        ContentFile(name, ContentKind.MOD, 1L, true, modId, title, "1")

    @Test
    fun asksOnlyWhereTheAnswerChangesSomething() {
        data class Case(val why: String, val state: CosmeticsModState?, val declined: Boolean, val ask: Boolean)
        val cases = listOf(
            Case("build can take the mod and has none", state(availableFile = "m.jar"), false, true),
            Case("owner answered «Без мода» for this build", state(availableFile = "m.jar"), true, false),
            Case("jar already in the build", state(installedFile = "m.jar", availableFile = "m.jar"), false, false),
            Case("player switched the jar off by hand", state(installedFile = "m.jar.disabled", availableFile = "m.jar", enabled = false), false, false),
            Case("no variant for this version or loader", state(), false, false),
            Case("catalogue unreadable or too slow", null, false, false),
        )
        for (case in cases) {
            assertEquals("offer decision broke for: ${case.why}", case.ask, MillidaModOffer.wanted(case.state, case.declined))
        }
    }

    @Test
    fun fabricApiIsCheckedOnlyWhereTheModRequiresIt() {
        val cases = mapOf("fabric" to true, "quilt" to true, "forge" to false, "neoforge" to false, "vanilla" to false)
        for ((loader, needs) in cases) {
            assertEquals("Fabric API requirement wrong for $loader: the mod would not load or an extra jar lands", needs, MillidaModOffer.needsFabricApi(loader))
        }
    }

    @Test
    fun fabricApiIsRecognisedUnderEverySpelling() {
        data class Case(val why: String, val files: List<ContentFile>, val has: Boolean)
        val cases = listOf(
            Case("modern jar id", listOf(mod("fabric-api")), true),
            Case("1.16 era jar id", listOf(mod("fabric")), true),
            Case("Quilted Fabric API provides it", listOf(mod("quilted_fabric_api")), true),
            Case("only catalogue title known", listOf(mod("", title = "Fabric API", name = "x.jar")), true),
            Case("unrelated mods only", listOf(mod("sodium"), mod("iris")), false),
            Case("empty build", emptyList(), false),
        )
        for (case in cases) {
            assertEquals("Fabric API detection broke for: ${case.why}", case.has, MillidaModOffer.hasFabricApi(case.files))
        }
    }

    @Test
    fun picksAReleaseWithAFile() {
        val file = CatalogFile(url = "u", filename = "f.jar", primary = true)
        val beta = CatalogVersion(id = "b", versionType = "beta", files = listOf(file))
        val bare = CatalogVersion(id = "n", versionType = "release")
        val release = CatalogVersion(id = "r", versionType = "release", files = listOf(file))
        assertEquals("a release with a file must win over a beta", "r", MillidaModOffer.pickFabricApi(listOf(beta, bare, release))?.id)
        assertEquals("a beta is better than no Fabric API at all", "b", MillidaModOffer.pickFabricApi(listOf(bare, beta))?.id)
        assertEquals("a version without files cannot be installed", null, MillidaModOffer.pickFabricApi(listOf(bare))?.id)
    }
}
