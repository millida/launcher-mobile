package net.millida.launcher.core.remoteui

import org.bouncycastle.crypto.generators.Ed25519KeyPairGenerator
import org.bouncycastle.crypto.params.Ed25519KeyGenerationParameters
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.security.SecureRandom
import java.util.Base64

class RemoteUiStoreTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val pair = Ed25519KeyPairGenerator().apply { init(Ed25519KeyGenerationParameters(SecureRandom())) }.generateKeyPair()
    private val publicKey = (pair.public as Ed25519PublicKeyParameters).encoded

    private val base = "https://site.test/launcher-ui"
    private val version = "0123456789abcdef"
    private val main = "export const UI_API = 1".toByteArray()
    private val css = ".a{}".toByteArray()
    private val core = "export const core = 1".toByteArray()
    private val lazy = "export const lazy = 1".toByteArray()

    private fun signed(
        ver: String = version,
        mainBytes: ByteArray = main,
        lazyBytes: ByteArray = lazy,
    ): RemoteUiManifest {
        val unsigned = RemoteUiManifest(
            api = 1, version = ver,
            file = "$ver/ui.js", sha256 = RemoteUiRules.sha256Hex(mainBytes),
            css = "$ver/ui.css", cssSha256 = RemoteUiRules.sha256Hex(css),
            builtAt = "2026-10-20T00:00:00.000Z",
            chunks = listOf(
                RemoteUiChunk("$ver/c-core.js", RemoteUiRules.sha256Hex(core), 1),
                RemoteUiChunk("$ver/c-lazy.js", RemoteUiRules.sha256Hex(lazyBytes)),
            ),
        )
        val signer = Ed25519Signer().apply { init(true, pair.private as Ed25519PrivateKeyParameters) }
        val text = RemoteUiRules.signedText(unsigned).toByteArray()
        signer.update(text, 0, text.size)
        return unsigned.copy(sig = Base64.getEncoder().encodeToString(signer.generateSignature()))
    }

    private class Site(val files: MutableMap<String, ByteArray> = mutableMapOf(), var online: Boolean = true) : RemoteUiFetcher {
        val asked = mutableListOf<String>()
        override fun get(url: String): ByteArray {
            asked += url
            if (!online) throw IOException("offline")
            return files[url] ?: throw IOException("404 $url")
        }
    }

    private fun site(m: RemoteUiManifest = signed(), lazyBytes: ByteArray = lazy, mainBytes: ByteArray = main) = Site().apply {
        files["$base/manifest.json"] = RemoteUiRules.json.encodeToString(RemoteUiManifest.serializer(), m).toByteArray()
        files["$base/${m.version}/ui.js"] = mainBytes
        files["$base/${m.version}/ui.css"] = css
        files["$base/${m.version}/c-core.js"] = core
        files["$base/${m.version}/c-lazy.js"] = lazyBytes
    }

    private fun store(dir: File, site: Site) = RemoteUiStore(dir, site, publicKey, base)

    @Test
    fun refreshStoresVerifiedFilesAndTheyServeWithoutNetwork() {
        val dir = tmp.newFolder()
        val online = site()
        val first = store(dir, online).refresh(null)
        assertNotNull(first)

        // a new process, no network: the cache alone is enough
        val offline = Site(online = false)
        val again = store(dir, offline)
        val m = again.cached()
        assertNotNull(m)
        assertArrayEquals(main, again.open(version, "ui.js"))
        assertArrayEquals(css, again.open(version, "ui.css"))
        assertArrayEquals(core, again.open(version, "c-core.js"))
        assertTrue(offline.asked.isEmpty())
    }

    @Test
    fun aLazyChunkIsFetchedOnceThenKept() {
        val dir = tmp.newFolder()
        val s = site()
        val st = store(dir, s)
        st.refresh(null)
        assertTrue(s.asked.none { it.endsWith("c-lazy.js") })
        assertArrayEquals(lazy, st.open(version, "c-lazy.js"))
        assertArrayEquals(lazy, st.open(version, "c-lazy.js"))
        assertEquals(1, s.asked.count { it.endsWith("c-lazy.js") })
    }

    @Test
    fun aTamperedChunkIsRefusedAndNotKept() {
        val dir = tmp.newFolder()
        val s = site(lazyBytes = "export const evil = 1".toByteArray())
        s.files["$base/$version/c-lazy.js"] = "export const evil = 1".toByteArray()
        // the manifest still lists the hash of the honest chunk
        s.files["$base/manifest.json"] = RemoteUiRules.json.encodeToString(RemoteUiManifest.serializer(), signed()).toByteArray()
        val st = store(dir, s)
        assertNotNull(st.refresh(null))
        try {
            st.open(version, "c-lazy.js")
            fail("a chunk with a wrong hash must not be served")
        } catch (expected: IOException) {
            assertTrue(expected.message!!.contains("hash"))
        }
        assertTrue(File(dir, "files").listFiles()!!.none { it.name == RemoteUiRules.sha256Hex("export const evil = 1".toByteArray()) })
    }

    @Test
    fun aTamperedMainFileMakesTheRefreshFailAndKeepsTheOldCopy() {
        val dir = tmp.newFolder()
        assertNotNull(store(dir, site()).refresh(null))

        val newer = signed(ver = "fedcba9876543210", mainBytes = "export const UI_API = 2".toByteArray())
        val evil = site(newer, mainBytes = "export const evil = 1".toByteArray())
        assertNull(store(dir, evil).refresh(version))
        assertEquals(version, store(dir, Site(online = false)).cached()?.version)
    }

    @Test
    fun aForgedManifestIsNeverTaken() {
        val dir = tmp.newFolder()
        val forged = signed().copy(sha256 = RemoteUiRules.sha256Hex("export const evil = 1".toByteArray()))
        val s = site(forged, mainBytes = "export const evil = 1".toByteArray())
        assertNull(store(dir, s).refresh(null))
        assertNull(store(dir, Site(online = false)).cached())
    }

    @Test
    fun aManifestSignedByAnotherKeyIsRefused() {
        val dir = tmp.newFolder()
        val other = RemoteUiStore(dir, site(), ByteArray(32) { 9 }, base)
        assertNull(other.refresh(null))
    }

    @Test
    fun theSameVersionIsNotDownloadedAgain() {
        val dir = tmp.newFolder()
        val s = site()
        val st = store(dir, s)
        assertNotNull(st.refresh(null))
        s.asked.clear()
        assertNull(st.refresh(version))
        assertEquals(listOf("$base/manifest.json"), s.asked)
    }

    @Test
    fun aDamagedCacheFileMeansNoCacheInsteadOfBadCode() {
        val dir = tmp.newFolder()
        store(dir, site()).refresh(null)
        File(dir, "files/" + RemoteUiRules.sha256Hex(main)).writeText("export const evil = 1")
        assertNull(store(dir, Site(online = false)).cached())
    }

    @Test
    fun aBadVersionIsSkippedAndNeverReturns() {
        val dir = tmp.newFolder()
        val st = store(dir, site())
        st.refresh(null)
        st.markBad(version)
        assertNull(store(dir, Site(online = false)).cached())
        assertNull(store(dir, site()).refresh(null))
    }

    @Test
    fun unknownVersionsAndNamesAreNotServed() {
        val dir = tmp.newFolder()
        val st = store(dir, site())
        st.refresh(null)
        assertNull(st.open("ffffffffffffffff", "ui.js"))
        assertNull(st.open(version, "../manifest.json"))
        assertNull(st.open(version, "c-unknown.js"))
    }
}
