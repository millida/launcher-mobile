package net.millida.launcher.core.telemetry

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import net.millida.launcher.core.atomicWriteText
import net.millida.launcher.core.mojang.MojangJson
import java.io.Closeable
import java.io.File
import java.io.RandomAccessFile
import java.nio.channels.OverlappingFileLockException
import java.util.UUID

/** A game that was on screen when its process last reported in. */
data class OpenGame(val startedAt: Long, val aliveAt: Long, val facts: LaunchFacts)

/**
 * Everything telemetry keeps on the phone, in files rather than preferences:
 * the game runs in a process of its own and writes into the same queue, and
 * shared preferences are not safe across processes.
 */
class TelemetryStore(private val dir: File, private val newId: () -> String = { UUID.randomUUID().toString() }) {
    private val queueFile get() = File(dir, "queue.json")
    private val idFile get() = File(dir, "install-id")
    private val offFile get() = File(dir, "off")
    private val gameFile get() = File(dir, "game-open.json")

    /** Same default as the desktop: on until the player turns it off. */
    val enabled: Boolean get() = !offFile.exists()

    fun setEnabled(on: Boolean) {
        locked {
            if (on) {
                offFile.delete()
            } else {
                offFile.atomicWriteText("1")
                queueFile.delete()
                gameFile.delete()
            }
        }
    }

    /** One random id per installation, never derived from the phone, made once by whichever process asks first. */
    fun installId(): String = locked {
        val current = runCatching { idFile.readText().trim() }.getOrNull()
        if (!current.isNullOrEmpty() && ID.matches(current)) return@locked current
        val next = newId()
        idFile.atomicWriteText(next)
        next
    }

    fun add(event: TelemetryEvent) {
        if (!enabled) return
        locked {
            val queue = read() + event
            write(queue.takeLast(MAX_QUEUE))
        }
    }

    fun size(): Int = locked { read().size }

    fun peek(limit: Int = TelemetryPayload.MAX_BATCH): List<TelemetryEvent> = locked { read().take(limit) }

    /** Takes out exactly what was sent: the game process may have added more meanwhile. */
    fun remove(sent: List<TelemetryEvent>) {
        if (sent.isEmpty()) return
        locked {
            val left = read().toMutableList()
            sent.forEach { left.remove(it) }
            write(left)
        }
    }

    /**
     * One sender at a time across processes: the launcher and the game both
     * flush, and two of them taking the same batch would store it twice.
     */
    fun trySending(): Closeable? {
        dir.mkdirs()
        val file = RandomAccessFile(File(dir, "send.lock"), "rw")
        val lock = try {
            file.channel.tryLock()
        } catch (busy: OverlappingFileLockException) {
            null
        }
        if (lock == null) {
            file.close()
            return null
        }
        return Closeable {
            runCatching { lock.release() }
            file.close()
        }
    }

    fun openGame(game: OpenGame) {
        if (!enabled) return
        locked { gameFile.atomicWriteText(encode(game).toString()) }
    }

    fun touchGame(aliveAt: Long) {
        locked {
            readGame()?.let { current -> gameFile.atomicWriteText(encode(current.copy(aliveAt = aliveAt)).toString()) }
        }
    }

    /** The open game, removed in the same step so two processes cannot both report it. */
    fun takeGame(): OpenGame? = locked {
        val game = readGame()
        gameFile.delete()
        game
    }

    fun peekGame(): OpenGame? = locked { readGame() }

    /** A value measured once per system build, such as the GPU name. */
    fun cached(key: String, fingerprint: String): String? = locked {
        val file = File(dir, "cache-$key.json")
        val json = runCatching { MojangJson.parseToJsonElement(file.readText()) as? JsonObject }.getOrNull() ?: return@locked null
        val stamp = (json["fingerprint"] as? JsonPrimitive)?.contentOrNull
        if (stamp != fingerprint) null else (json["value"] as? JsonPrimitive)?.contentOrNull
    }

    fun cache(key: String, fingerprint: String, value: String) {
        locked {
            File(dir, "cache-$key.json").atomicWriteText(
                buildJsonObject {
                    put("fingerprint", fingerprint)
                    put("value", value)
                }.toString(),
            )
        }
    }

    private fun read(): List<TelemetryEvent> {
        val text = runCatching { queueFile.readText() }.getOrNull() ?: return emptyList()
        val array = runCatching { MojangJson.parseToJsonElement(text) as? JsonArray }.getOrNull() ?: return emptyList()
        return array.mapNotNull(TelemetryEvent::fromJson)
    }

    private fun write(events: List<TelemetryEvent>) {
        if (events.isEmpty()) {
            queueFile.delete()
            return
        }
        queueFile.atomicWriteText(buildJsonArray { events.forEach { add(it.toJson()) } }.toString())
    }

    private fun readGame(): OpenGame? {
        val json = runCatching { MojangJson.parseToJsonElement(gameFile.readText()) as? JsonObject }.getOrNull() ?: return null
        val started = (json["startedAt"] as? JsonPrimitive)?.longOrNull ?: return null
        val alive = (json["aliveAt"] as? JsonPrimitive)?.longOrNull ?: started
        val facts = json["facts"] as? JsonObject ?: return null
        fun text(key: String) = (facts[key] as? JsonPrimitive)?.contentOrNull
        fun number(key: String) = (facts[key] as? JsonPrimitive)?.contentOrNull?.toIntOrNull()
        return OpenGame(
            startedAt = started,
            aliveAt = alive,
            facts = LaunchFacts(
                build = text("build").orEmpty(),
                mc = text("mc").orEmpty(),
                loader = text("loader").orEmpty(),
                pack = text("pack"),
                modpack = text("modpack"),
                renderer = text("renderer"),
                layer = text("layer"),
                java = number("java"),
                heapMb = number("heapMb"),
                mods = number("mods"),
            ),
        )
    }

    private fun encode(game: OpenGame): JsonObject = buildJsonObject {
        put("startedAt", game.startedAt)
        put("aliveAt", game.aliveAt)
        put("facts", JsonObject(game.facts.data()))
    }

    /**
     * A file lock keeps the other process out; it is held per process, so a
     * monitor keeps this process's own threads from overlapping on it.
     */
    private fun <T> locked(block: () -> T): T = synchronized(MONITOR) {
        dir.mkdirs()
        RandomAccessFile(File(dir, "store.lock"), "rw").use { file ->
            val lock = file.channel.lock()
            try {
                block()
            } finally {
                lock.release()
            }
        }
    }

    companion object {
        const val MAX_QUEUE = 200
        private val MONITOR = Any()
        private val ID = Regex("""[A-Za-z0-9-]{8,64}""")
    }
}
