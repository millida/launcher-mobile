package net.millida.launcher.core.bedrock

import net.millida.launcher.core.account.MILLIDA_API
import net.millida.launcher.core.http.Downloader
import net.millida.launcher.core.http.RemoteFile
import java.io.File
import java.util.zip.ZipFile

const val VIAPROXY_VERSION = "3.4.12"
const val VIAPROXY_SIZE = 47_136_119L
const val VIAPROXY_SHA256 = "32ce9ad871aeb03286823c29da262ebd75992864e7857db283f103525c7fc0cb"
const val VIAPROXY_URL =
    "https://github.com/ViaVersion/ViaProxy/releases/download/v$VIAPROXY_VERSION/ViaProxy-$VIAPROXY_VERSION.jar"

/** The translator listens on the loopback only: nothing outside the phone may use it. */
const val BEDROCK_PROXY_HOST = "127.0.0.1"
const val BEDROCK_PROXY_PORT = 25568

/**
 * The name the translator knows the Bedrock protocol by. It is taken from the
 * jar we actually ship, because it changes with every Bedrock release; this is
 * the answer for the pinned version and the fallback when the jar cannot be
 * read.
 */
const val BEDROCK_TARGET_FALLBACK = "Bedrock 1.26.30"

private const val CONSTANTS_ENTRY = "net/raphimc/viabedrock/protocol/data/ProtocolConstants.class"

/**
 * Our own way into Bedrock: the launcher already runs a real Java client, and
 * the translator turns a Bedrock server into something that client understands.
 * The player keeps our controls, our account and our skin - and needs no second
 * game installed.
 */
class BedrockBridge(root: File) {

    val directory: File = File(root, "bedrock")
    val jar: File = File(directory, "ViaProxy-$VIAPROXY_VERSION.jar")

    /**
     * The size is enough to tell a finished download from a broken one: the
     * hash is checked while downloading, and hashing 47 MB on every screen
     * would be paid for by the player in waiting.
     */
    fun installed(): Boolean = jar.isFile && jar.length() == VIAPROXY_SIZE

    suspend fun install(downloader: Downloader, onBytes: (Long) -> Unit = {}) {
        directory.mkdirs()
        val file = RemoteFile(url = VIAPROXY_URL, target = jar, sha1 = VIAPROXY_SHA256, size = VIAPROXY_SIZE)
        runCatching { downloader.fetch(file, onBytes) }
            .recoverCatching {
                // Releases on GitHub are not reachable from every network our
                // players sit on; the mirror is the same file through our API.
                downloader.fetch(file.copy(url = "$MILLIDA_API/launcher/dl?url=$VIAPROXY_URL"), onBytes)
            }
            .getOrThrow()
    }

    fun targetVersion(): String = bedrockTargetVersionInJar(jar) ?: BEDROCK_TARGET_FALLBACK
}

/**
 * The translator refuses a version name it does not know and falls back to
 * guessing the server, which a Bedrock server never answers. The name lives as
 * a plain constant in the jar, so it is read from there instead of being
 * guessed by us.
 */
fun bedrockTargetVersionInJar(jar: File): String? {
    if (!jar.isFile) return null
    val raw = runCatching {
        ZipFile(jar).use { zip ->
            val entry = zip.getEntry(CONSTANTS_ENTRY) ?: return null
            zip.getInputStream(entry).use { it.readBytes() }
        }
    }.getOrNull() ?: return null

    val text = String(raw, Charsets.ISO_8859_1)
    val found = Regex("(?<![\\d.])(\\d{1,2})\\.(\\d{1,3})\\.(\\d{1,3})(?![\\d.])")
        .findAll(text)
        .map { match -> match.value to match.groupValues.drop(1).map { it.toInt() } }
        .maxWithOrNull(compareBy({ it.second[0] }, { it.second[1] }, { it.second[2] }))
        ?: return null
    return "Bedrock ${found.first}"
}

/**
 * Everything after the jar: the translator is told once what to translate and
 * where, and never reads its own saved settings for it.
 */
fun viaProxyArguments(
    target: BedrockAddress,
    targetVersion: String,
    bindPort: Int = BEDROCK_PROXY_PORT,
): List<String> = listOf(
    "cli",
    "--bind-address=$BEDROCK_PROXY_HOST:$bindPort",
    "--target-address=${target.host}:${target.port}",
    "--target-version=$targetVersion",
    "--auth-method=none",
    "--chat-signing=false",
)
