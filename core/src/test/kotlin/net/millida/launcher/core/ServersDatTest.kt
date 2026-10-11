package net.millida.launcher.core

import net.millida.launcher.core.servers.BedrockPingUnsupportedException
import net.millida.launcher.core.servers.ServerEntry
import net.millida.launcher.core.servers.ServerList
import net.millida.launcher.core.servers.ServerPing
import net.millida.launcher.core.servers.ServersDat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.util.zip.GZIPOutputStream
import kotlin.concurrent.thread

class ServersDatTest {

    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var layout: GameLayout
    private lateinit var profileDir: File
    private lateinit var servers: ServerList

    private fun prepare() {
        layout = GameLayout(temp.newFolder()).also { it.ensure() }
        profileDir = layout.profileDir("build").also { it.mkdirs() }
        servers = ServerList(layout)
    }

    private fun file(): File = File(profileDir, ServersDat.FILE_NAME)

    @Test
    fun `write then read gives the same list back`() {
        val list = listOf(
            ServerEntry("Мой сервер 🎮", "play.millida.net"),
            ServerEntry("Second", "1.2.3.4:25566", icon = "QUJD", acceptTextures = true),
            ServerEntry("Third", "[::2]:25565", acceptTextures = false),
        )
        val back = ServersDat.decode(ServersDat.encode(list))
        assertEquals("повторное чтение servers.dat вернуло другой список", list, back)
    }

    /**
     * Input -> verdict. A file the game or another launcher wrote badly must
     * cost the player an empty list, never a crash on opening the screen.
     */
    @Test
    fun `broken files read as an empty list instead of throwing`() {
        val cases = listOf(
            "пустой файл" to ByteArray(0),
            "мусор вместо NBT" to byteArrayOf(1, 2, 3, 4, 5),
            "обрезанный корень" to ServersDat.encode(listOf(ServerEntry("A", "a.net"))).copyOf(6),
            "обрезанная запись" to ServersDat.encode(listOf(ServerEntry("A", "a.net"))).let {
                it.copyOf(it.size - 4)
            },
            "битый gzip" to byteArrayOf(0x1f, 0x8b.toByte(), 8, 0, 9, 9, 9, 9),
            "длина строки за пределами файла" to byteArrayOf(10, 0, 0, 8, 0x7f, 0x7f, 1, 2),
            "список с отрицательным размером" to nbt {
                writeByte(10); writeUTF("")
                writeByte(9); writeUTF("servers")
                writeByte(10); writeInt(-5)
                writeByte(0)
            },
            "список с огромным размером" to nbt {
                writeByte(10); writeUTF("")
                writeByte(9); writeUTF("servers")
                writeByte(10); writeInt(Int.MAX_VALUE)
                writeByte(0)
            },
        )
        for ((label, bytes) in cases) {
            val read = try {
                ServersDat.decode(bytes)
            } catch (crash: Exception) {
                fail("$label уронил разбор: $crash")
                return
            }
            assertTrue("$label прочитался как непустой список: $read", read.isEmpty())
        }
    }

    @Test
    fun `a gzipped file written by another tool still reads`() {
        val list = listOf(ServerEntry("A", "a.net"))
        val packed = ByteArrayOutputStream()
        GZIPOutputStream(packed).use { it.write(ServersDat.encode(list)) }
        assertEquals("сжатый servers.dat не прочитался", list, ServersDat.decode(packed.toByteArray()))
    }

    /**
     * Pinning and reordering rewrite the whole file. Tags the launcher does not
     * model (hidden, previewsChat) must survive that trip, otherwise every
     * reorder quietly resets what the game stored.
     */
    @Test
    fun `unknown tags survive a rewrite`() {
        val raw = nbt {
            writeByte(10); writeUTF("")
            writeByte(9); writeUTF("servers")
            writeByte(10); writeInt(2)

            writeByte(8); writeUTF("name"); writeUTF("First")
            writeByte(8); writeUTF("ip"); writeUTF("one.example.net")
            writeByte(8); writeUTF("icon"); writeUTF("BASE64DATA")
            writeByte(1); writeUTF("acceptTextures"); writeByte(1)
            writeByte(1); writeUTF("hidden"); writeByte(1)
            writeByte(3); writeUTF("previewsChat"); writeInt(7)
            writeByte(0)

            writeByte(8); writeUTF("name"); writeUTF("Second")
            writeByte(8); writeUTF("ip"); writeUTF("two.example.net")
            writeByte(0)

            writeByte(0)
        }
        val first = ServersDat.decode(raw)
        assertEquals(
            "запись с байтовым тегом съела остаток списка — игрок теряет свои серверы",
            listOf("First" to "one.example.net", "Second" to "two.example.net"),
            first.map { it.name to it.address },
        )
        assertEquals("иконка не разобрана", "BASE64DATA", first[0].icon)
        assertEquals("acceptTextures не разобран", true, first[0].acceptTextures)
        assertTrue("незнакомые теги потеряны при чтении", first[0].extra.isNotEmpty())

        val second = ServersDat.decode(ServersDat.encode(first))
        assertEquals("перезапись servers.dat потеряла незнакомые теги", first, second)
    }

    @Test
    fun `adding a server keeps the existing ones`() {
        prepare()
        servers.add(profileDir, "Первый", "one.example.net")
        servers.add(profileDir, "Второй", "two.example.net")
        val after = servers.add(profileDir, "Третий", "three.example.net")

        assertEquals(
            "добавление сервера потеряло уже сохранённые",
            listOf("one.example.net", "two.example.net", "three.example.net"),
            after.map { it.address },
        )
        assertEquals("список на диске разошёлся с ответом", after, servers.list(profileDir))
        assertFalse("временный файл остался рядом", File(profileDir, "servers.dat.new").exists())
    }

    @Test
    fun `pinning moves the server to the top and keeps the rest in order`() {
        prepare()
        servers.add(profileDir, "Первый", "one.example.net")
        servers.add(profileDir, "Второй", "two.example.net")
        servers.add(profileDir, "Третий", "three.example.net")

        val pinned = servers.pin(profileDir, "three.example.net")
        assertEquals(
            "закрепление не подняло сервер наверх",
            listOf("three.example.net", "one.example.net", "two.example.net"),
            pinned.map { it.address },
        )
        assertEquals("закрепление не сохранилось на диске", pinned, servers.list(profileDir))
    }

    @Test
    fun `moving renaming and removing touch only the target row`() {
        prepare()
        servers.add(profileDir, "Первый", "one.example.net")
        servers.add(profileDir, "Второй", "two.example.net")
        servers.add(profileDir, "Третий", "three.example.net")

        val moved = servers.move(profileDir, 0, 2)
        assertEquals(
            "перестановка сломала порядок",
            listOf("two.example.net", "three.example.net", "one.example.net"),
            moved.map { it.address },
        )

        val renamed = servers.rename(profileDir, "three.example.net", "Новое имя")
        assertEquals(
            "переименование задело чужие строки",
            listOf("Второй", "Новое имя", "Первый"),
            renamed.map { it.name },
        )

        val removed = servers.remove(profileDir, "TWO.example.net")
        assertEquals(
            "удаление по адресу сняло не ту строку",
            listOf("three.example.net", "one.example.net"),
            removed.map { it.address },
        )
    }

    @Test
    fun `a corrupt servers dat does not stop the player from adding a server`() {
        prepare()
        file().writeBytes(byteArrayOf(9, 9, 9, 9))
        val after = servers.add(profileDir, "Первый", "one.example.net")
        assertEquals("после битого файла сервер не добавился", 1, after.size)
    }

    @Test
    fun `an empty address is refused instead of writing a dead row`() {
        prepare()
        try {
            servers.add(profileDir, "Без адреса", "   ")
            fail("пустой адрес приняли в список")
        } catch (expected: IOException) {
            assertTrue("сообщение не подсказывает, что делать", expected.message.orEmpty().contains("адрес"))
        }
        assertTrue("битая строка всё-таки попала в файл", servers.list(profileDir).isEmpty())
    }

    /**
     * The status answer is longer than 127 bytes, so its length arrives as a
     * multi-byte VarInt: a reader that handled only one byte would cut the JSON.
     */
    @Test
    fun `ping parses the status answer of a real socket`() {
        val icon = "A".repeat(400)
        val json = """
            {"version":{"name":"1.20.1","protocol":763},
             "players":{"online":42,"max":100},
             "description":{"text":"Millida","extra":[{"text":" Trade","color":"gold","bold":true}]},
             "favicon":"data:image/png;base64,$icon"}
        """.trimIndent()

        val seen = ArrayList<String>()
        val server = statusServer(json, seen)
        try {
            val status = ServerPing.pingBlocking("127.0.0.1:${server.localPort}", timeoutMs = 4000)
            assertEquals("онлайн разобран неверно", 42, status.online)
            assertEquals("максимум разобран неверно", 100, status.max)
            assertEquals("версия разобрана неверно", "1.20.1", status.version)
            assertEquals("MOTD потерял цветовые коды", "Millida§6§l Trade", status.motd)
            assertEquals("иконка разобрана неверно", icon, status.iconBase64)
            assertTrue("пинг отрицательный", status.latencyMs >= 0)
            assertEquals(
                "в рукопожатии ушёл не тот адрес — сервер с несколькими доменами ответит чужим статусом",
                listOf("127.0.0.1:${server.localPort}"),
                seen,
            )
        } finally {
            server.close()
        }
    }

    @Test
    fun `a truncated status answer surfaces as an error not as zeros`() {
        val server = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
        thread(isDaemon = true) {
            try {
                server.accept().use { socket ->
                    socket.getOutputStream().write(byteArrayOf(0x05, 0x00, 0x40))
                    socket.getOutputStream().flush()
                }
            } catch (ignored: IOException) {
                return@thread
            }
        }
        try {
            ServerPing.pingBlocking("127.0.0.1:${server.localPort}", timeoutMs = 2000)
            fail("обрезанный ответ прочитался как валидный статус")
        } catch (expected: IOException) {
            assertTrue("пустое сообщение об ошибке", expected.message.orEmpty().isNotEmpty())
        } finally {
            server.close()
        }
    }

    @Test
    fun `bedrock port is reported as unknown without touching the network`() {
        try {
            ServerPing.pingBlocking("play.example.net:19132", timeoutMs = 1)
            fail("Bedrock-адрес опрошен как Java-сервер")
        } catch (expected: BedrockPingUnsupportedException) {
            assertTrue(
                "игрок не поймёт, почему статус не показан",
                expected.message.orEmpty().contains("неизвестно"),
            )
        }
    }

    /** address -> host, port. A wrong split pings a different machine. */
    @Test
    fun `address splitting handles ports and IPv6 literals`() {
        val cases = listOf(
            "play.example.net" to ("play.example.net" to 25565),
            "play.example.net:25566" to ("play.example.net" to 25566),
            "1.2.3.4:19132" to ("1.2.3.4" to 19132),
            "[fd00::1]:25567" to ("fd00::1" to 25567),
            "[fd00::1]" to ("fd00::1" to 25565),
            "::1" to ("::1" to 25565),
            "play.example.net:0" to ("play.example.net" to 25565),
            "play.example.net:99999" to ("play.example.net" to 25565),
            " play.example.net " to ("play.example.net" to 25565),
        )
        for ((address, expected) in cases) {
            assertEquals("адрес $address разобран неверно", expected, ServerPing.splitHostPort(address))
        }
    }

    @Test
    fun `a plain string motd and a missing favicon still parse`() {
        val status = ServerPing.parseStatus("""{"description":"§aПривет"}""", 12)
        assertEquals("простой текстовый MOTD потерян", "§aПривет", status.motd)
        assertNull("иконки не было, а поле не пустое", status.iconBase64)
        assertEquals("онлайн без поля players должен быть нулём", 0, status.online)
    }

    private fun statusServer(json: String, seen: MutableList<String>): ServerSocket {
        val server = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
        thread(isDaemon = true) {
            try {
                server.accept().use { socket ->
                    val input = socket.getInputStream()
                    readVarInt(input)
                    readVarInt(input)
                    readVarInt(input)
                    val host = String(readBytes(input, readVarInt(input)), Charsets.UTF_8)
                    val port = (input.read() shl 8) or input.read()
                    readVarInt(input)
                    seen += "$host:$port"
                    readVarInt(input)
                    readVarInt(input)

                    val body = ByteArrayOutputStream()
                    val payload = json.toByteArray(Charsets.UTF_8)
                    writeVarInt(body, 0x00)
                    writeVarInt(body, payload.size)
                    body.write(payload)
                    val packet = ByteArrayOutputStream()
                    writeVarInt(packet, body.size())
                    packet.write(body.toByteArray())
                    socket.getOutputStream().write(packet.toByteArray())
                    socket.getOutputStream().flush()
                }
            } catch (ignored: IOException) {
                return@thread
            }
        }
        return server
    }

    private fun readVarInt(input: InputStream): Int {
        var value = 0
        var shift = 0
        while (true) {
            val byte = input.read()
            if (byte < 0) throw IOException("поток кончился")
            value = value or ((byte and 0x7f) shl shift)
            if (byte and 0x80 == 0) return value
            shift += 7
        }
    }

    private fun writeVarInt(out: ByteArrayOutputStream, value: Int) {
        var rest = value
        while (true) {
            val chunk = rest and 0x7f
            rest = rest ushr 7
            if (rest == 0) {
                out.write(chunk)
                return
            }
            out.write(chunk or 0x80)
        }
    }

    private fun readBytes(input: InputStream, count: Int): ByteArray {
        val buffer = ByteArray(count)
        var read = 0
        while (read < count) {
            val step = input.read(buffer, read, count - read)
            if (step < 0) throw IOException("поток кончился")
            read += step
        }
        return buffer
    }

    private fun nbt(body: DataOutputStream.() -> Unit): ByteArray {
        val out = ByteArrayOutputStream()
        DataOutputStream(out).use { it.body() }
        return out.toByteArray()
    }
}
