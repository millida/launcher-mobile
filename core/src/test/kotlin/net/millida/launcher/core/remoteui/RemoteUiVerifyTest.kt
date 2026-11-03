package net.millida.launcher.core.remoteui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

class RemoteUiVerifyTest {
    /** A manifest the site really signed (millida.net/launcher-ui/manifest.json, 20.10.2026). */
    private val real: RemoteUiManifest = RemoteUiRules.parse(
        javaClass.getResourceAsStream("/remote-ui/manifest.json")!!.readBytes().toString(Charsets.UTF_8),
    )!!

    @Test
    fun theRealManifestVerifies() {
        assertTrue(real.chunks.size > 50)
        assertTrue(real.chunks.any { it.core == 1 })
        assertTrue(RemoteUiRules.verify(real))
    }

    @Test
    fun aForgedChunkHashBreaksTheSignature() {
        val chunks = real.chunks.toMutableList()
        chunks[0] = chunks[0].copy(sha256 = "0".repeat(64))
        assertFalse(RemoteUiRules.verify(real.copy(chunks = chunks)))
    }

    @Test
    fun aSwappedMainFileOrCssHashBreaksTheSignature() {
        assertFalse(RemoteUiRules.verify(real.copy(sha256 = "1".repeat(64))))
        assertFalse(RemoteUiRules.verify(real.copy(cssSha256 = "2".repeat(64))))
    }

    @Test
    fun anAddedChunkBreaksTheSignature() {
        val extra = RemoteUiChunk(file = real.version + "/c-evil.js", sha256 = "3".repeat(64))
        assertFalse(RemoteUiRules.verify(real.copy(chunks = real.chunks + extra)))
    }

    @Test
    fun aForgedOrMissingSignatureIsRefused() {
        val bytes = Base64.getDecoder().decode(real.sig!!)
        bytes[5] = (bytes[5].toInt() xor 1).toByte()
        assertFalse(RemoteUiRules.verify(real.copy(sig = Base64.getEncoder().encodeToString(bytes))))
        assertFalse(RemoteUiRules.verify(real.copy(sig = null)))
        assertFalse(RemoteUiRules.verify(real.copy(sig = "not base64 !!")))
        assertFalse(RemoteUiRules.verify(real.copy(sig = Base64.getEncoder().encodeToString(ByteArray(10)))))
    }

    @Test
    fun anotherKeyDoesNotVerify() {
        assertFalse(RemoteUiRules.verify(real, ByteArray(32) { 7 }))
    }

    @Test
    fun badShapeIsRefusedBeforeTheSignature() {
        assertFalse(RemoteUiRules.verify(real.copy(api = 2)))
        assertFalse(RemoteUiRules.verify(real.copy(version = "XYZ")))
        val traversal = real.chunks.toMutableList()
        traversal[0] = traversal[0].copy(file = real.version + "/../x.js")
        assertFalse(RemoteUiRules.verify(real.copy(chunks = traversal)))
    }

    @Test
    fun theSignedTextHasTheDesktopShape() {
        val lines = RemoteUiRules.signedText(real).split("\n")
        assertEquals(9, lines.size)
        assertEquals("millida-launcher-ui/1", lines[0])
        assertEquals(real.version, lines[2])
        assertNotNull(real.sig)
    }
}
