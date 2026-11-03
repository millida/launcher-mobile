package net.millida.launcher.core.remoteui

import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

/** One GET of a file of the website; the answer is never trusted by itself. */
fun interface RemoteUiFetcher {
    @Throws(IOException::class)
    fun get(url: String): ByteArray
}

/**
 * The verified copy of the website screens on the phone. Nothing here runs the code: it only decides
 * which bytes are allowed to be handed to the WebView. A file is served after its sha256 matched the
 * hash under the Ed25519 signature of the manifest; a manifest is taken after its signature matched.
 *
 * Disk: `manifest.json` (the last verified one) and `files/<sha256>` (content-addressed, so a chunk
 * that did not change survives an update). With no network the last verified copy is used as is.
 * All calls block: callers keep them off the main thread.
 */
class RemoteUiStore(
    private val root: File,
    private val fetcher: RemoteUiFetcher,
    private val publicKey: ByteArray = java.util.Base64.getDecoder().decode(RemoteUiRules.PUBLIC_KEY),
    private val base: String = RemoteUiRules.BASE,
) {
    private val files = File(root, "files")
    private val manifestFile = File(root, "manifest.json")
    private val badFile = File(root, "bad.txt")

    /** Manifests handed out in this process: a page that started on a version keeps getting that version's files. */
    private val live = ConcurrentHashMap<String, RemoteUiManifest>()

    /** The last verified manifest whose core files are all on disk and intact, or null. */
    @Synchronized
    fun cached(): RemoteUiManifest? {
        val m = RemoteUiRules.parse(runCatching { manifestFile.readText() }.getOrNull() ?: return null) ?: return null
        if (m.version in bad() || !RemoteUiRules.verify(m, publicKey)) return null
        val needed = listOf(m.sha256, m.cssSha256) + m.chunks.filter { it.core == 1 }.map { it.sha256 }
        if (!needed.all { onDisk(it) != null }) return null
        live[m.version] = m
        return m
    }

    /**
     * Looks at the site: null when the version is the one already known or nothing trustworthy came
     * back, otherwise the new manifest, with the main files and the core chunks stored.
     */
    fun refresh(known: String?): RemoteUiManifest? = try {
        val m = RemoteUiRules.parse(String(fetcher.get("$base/manifest.json"), Charsets.UTF_8))
        when {
            m == null || m.version == known -> null
            m.version in bad() || !RemoteUiRules.verify(m, publicKey) -> null
            else -> {
                store(m, m.sha256, RemoteUiRules.nameOf(m, m.file))
                store(m, m.cssSha256, RemoteUiRules.nameOf(m, m.css))
                m.chunks.filter { it.core == 1 }.forEach { store(m, it.sha256, RemoteUiRules.nameOf(m, it.file)) }
                live[m.version] = m
                commit(m)
                m
            }
        }
    } catch (failed: IOException) {
        null
    }

    /**
     * The bytes of [name] inside [version], hash-checked: from disk, or fetched once and kept. Null when
     * the version or the name is unknown; an IOException when the network is gone or the hash differs.
     */
    @Throws(IOException::class)
    fun open(version: String, name: String): ByteArray? {
        val m = live[version] ?: return null
        if (!RemoteUiRules.validName(name)) return null
        val hash = hashOf(m, name) ?: return null
        return onDisk(hash) ?: store(m, hash, name)
    }

    fun markBad(version: String) {
        val all = (bad() + version).takeLast(20)
        root.mkdirs()
        badFile.writeText(all.joinToString("\n"))
        if (RemoteUiRules.parse(runCatching { manifestFile.readText() }.getOrNull().orEmpty())?.version == version) manifestFile.delete()
        live.remove(version)
    }

    private fun hashOf(m: RemoteUiManifest, name: String): String? = when (name) {
        RemoteUiRules.nameOf(m, m.file) -> m.sha256
        RemoteUiRules.nameOf(m, m.css) -> m.cssSha256
        else -> m.chunks.firstOrNull { RemoteUiRules.nameOf(m, it.file) == name }?.sha256
    }

    private fun onDisk(hash: String): ByteArray? {
        val f = File(files, hash)
        val bytes = runCatching { f.readBytes() }.getOrNull() ?: return null
        if (RemoteUiRules.sha256Hex(bytes) == hash) return bytes
        f.delete()
        return null
    }

    @Throws(IOException::class)
    private fun store(m: RemoteUiManifest, hash: String, name: String): ByteArray {
        onDisk(hash)?.let { return it }
        val bytes = fetcher.get("$base/${m.version}/$name")
        if (RemoteUiRules.sha256Hex(bytes) != hash) throw IOException("hash mismatch $name")
        files.mkdirs()
        val temp = File(files, "$hash.part")
        temp.writeBytes(bytes)
        if (!temp.renameTo(File(files, hash))) temp.delete()
        return bytes
    }

    /** The manifest goes last: a half-downloaded update never replaces a complete copy. */
    @Synchronized
    private fun commit(m: RemoteUiManifest) {
        root.mkdirs()
        val temp = File(root, "manifest.json.part")
        temp.writeText(RemoteUiRules.json.encodeToString(RemoteUiManifest.serializer(), m))
        if (!temp.renameTo(manifestFile)) {
            manifestFile.delete()
            temp.renameTo(manifestFile)
        }
        val keep = (listOf(m.sha256, m.cssSha256) + m.chunks.map { it.sha256 }).toSet()
        files.listFiles()?.filter { it.name !in keep }?.forEach { it.delete() }
    }

    private fun bad(): List<String> = runCatching { badFile.readLines().filter { it.isNotBlank() } }.getOrDefault(emptyList())
}
