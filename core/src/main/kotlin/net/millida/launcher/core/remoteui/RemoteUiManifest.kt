package net.millida.launcher.core.remoteui

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import java.security.MessageDigest
import java.util.Base64

/**
 * Screens shipped from the website: millida.net/launcher-ui serves a signed JS/CSS module built from
 * the site's code (the same one the desktop launcher loads). The rules below mirror the desktop
 * launcher's `remoteUi.ts` byte for byte: what is signed, which names are allowed, how a chunk is hashed.
 */
object RemoteUiRules {
    const val API = 1

    /** Public half of the signing key (raw Ed25519, base64). The private key never leaves the site's CI. */
    const val PUBLIC_KEY = "TgYsbCG1NTtZWwyTeaj2dozeEUWkBxqgNmbhrr9frjE="

    const val BASE = "https://millida.net/launcher-ui"

    private const val TAG = "millida-launcher-ui/1"
    private val VERSION = Regex("^[0-9a-f]{16}$")
    private val SHA256 = Regex("^[0-9a-f]{64}$")
    private val NAME = Regex("^[\\w.-]+\\.(?:js|css)$")

    val json = Json { ignoreUnknownKeys = true }

    fun parse(text: String): RemoteUiManifest? = try {
        json.decodeFromString(RemoteUiManifest.serializer(), text)
    } catch (broken: Exception) {
        null
    }

    fun chunksText(chunks: List<RemoteUiChunk>): String =
        chunks.joinToString(",") { "${it.file}:${it.sha256}${if (it.core == 1) ":1" else ""}" }

    /** The exact text the site signs; chunk hashes are appended only when there are any. */
    fun signedText(m: RemoteUiManifest): String {
        val base = mutableListOf(TAG, m.api.toString(), m.version, m.file, m.sha256, m.css, m.cssSha256, m.builtAt)
        if (m.chunks.isNotEmpty()) base.add(chunksText(m.chunks))
        return base.joinToString("\n")
    }

    /** The file name inside the version folder («c-api-XYZ.js» out of «<version>/c-api-XYZ.js»). */
    fun nameOf(m: RemoteUiManifest, file: String): String = file.removePrefix(m.version + "/")

    fun validName(name: String): Boolean = NAME.matches(name) && !name.contains("..")

    /** Shape, names and signature. Hashes of the files themselves are checked where the files are read. */
    fun verify(m: RemoteUiManifest, publicKey: ByteArray = Base64.getDecoder().decode(PUBLIC_KEY)): Boolean {
        val sig = m.sig ?: return false
        if (m.api != API || !VERSION.matches(m.version)) return false
        val prefix = m.version + "/"
        if (!m.file.startsWith(prefix) || !m.css.startsWith(prefix)) return false
        if (!SHA256.matches(m.sha256) || !SHA256.matches(m.cssSha256)) return false
        if (!validName(nameOf(m, m.file)) || !validName(nameOf(m, m.css))) return false
        for (c in m.chunks) {
            if (!SHA256.matches(c.sha256) || !c.file.startsWith(prefix) || !validName(nameOf(m, c.file))) return false
        }
        return try {
            val signature = Base64.getDecoder().decode(sig)
            val text = signedText(m).toByteArray(Charsets.UTF_8)
            val verifier = Ed25519Signer()
            verifier.init(false, Ed25519PublicKeyParameters(publicKey, 0))
            verifier.update(text, 0, text.size)
            signature.size == 64 && verifier.verifySignature(signature)
        } catch (broken: Exception) {
            false
        }
    }

    fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}

@Serializable
data class RemoteUiChunk(
    val file: String,
    val sha256: String,
    /** Statically imported by ui.js: fetched together with the manifest. */
    val core: Int? = null,
)

@Serializable
data class RemoteUiManifest(
    val api: Int,
    val version: String,
    val file: String,
    val sha256: String,
    val css: String,
    val cssSha256: String,
    val builtAt: String,
    val chunks: List<RemoteUiChunk> = emptyList(),
    val sig: String? = null,
)
