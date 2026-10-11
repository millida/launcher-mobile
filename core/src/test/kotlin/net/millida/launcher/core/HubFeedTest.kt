package net.millida.launcher.core

import net.millida.launcher.core.hub.HealthTone
import net.millida.launcher.core.hub.HeroSlot
import net.millida.launcher.core.hub.Hub
import net.millida.launcher.core.hub.ModeArts
import net.millida.launcher.core.hub.PackHealth
import net.millida.launcher.core.hub.PopularVersion
import net.millida.launcher.core.hub.PresetLoader
import net.millida.launcher.core.hub.Rotation
import net.millida.launcher.core.media.LobbyMusic
import net.millida.launcher.core.mojang.MojangJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class HubFeedTest {

    @Test
    fun `режимы — только настоящие коды рейтинга, свой ивент первым`() {
        val body = MojangJson.parseToJsonElement(
            """{"modes": [
                {"code": "BEDWARS", "total": 40, "online": 900, "slugs": ["a"]},
                {"code": "ONEBLOCK", "total": 79, "online": 10, "slugs": ["oneblock-7"]},
                {"code": "SKYWARS", "total": 5, "online": 1200, "slugs": ["b"]},
                {"code": "EMPTY", "total": 0, "online": 0, "slugs": []},
                {"code": "bad code", "online": 5000, "slugs": ["c"]},
                {"code": "HIDE_SEEK", "online": 30.6, "slugs": ["d"]}
            ]}""",
        )
        val modes = Hub.modes(body)
        assertEquals(
            "без серверов и с чужим кодом — не режим; свой ивент первым, дальше по онлайну",
            listOf("ONEBLOCK", "SKYWARS", "BEDWARS", "HIDE_SEEK"),
            modes.map { it.code },
        )
        assertEquals("название кода — как на сайте", "Прятки", modes.last().title)
        assertEquals("дробный онлайн округляется, а не роняет разбор", 31, modes.last().online)
        assertEquals("неизвестный код читается словами", "Some mode", Hub.modeTitle("SOME_MODE"))
        assertTrue("пустой ответ — пустой список", Hub.modes(null).isEmpty())
    }

    @Test
    fun `онлайн коротко и только живой`() {
        val cases = listOf(
            19 to null,
            20 to "20 играют",
            999 to "999 играют",
            1_000 to "1 тыс играют",
            1_500 to "1,5 тыс играют",
            9_999 to "10 тыс играют",
            163_400 to "163 тыс играют",
        )
        cases.forEach { (online, expected) -> assertEquals("онлайн $online", expected, Hub.onlineLabel(online)) }
        assertNull("нет числа — нет строки", Hub.onlineLabel(null))
    }

    @Test
    fun `строка здоровья сборки говорит только то, что знает`() {
        val now = Instant.parse("2026-10-02T12:00:00Z")
        fun health(status: String, pct: Int?, lastOk: String?) = PackHealth("pack", status, 10, pct, lastOk)
        val cases = listOf(
            health("unknown", 99, "2026-10-02T11:59:00Z") to null,
            health("ok", null, null) to null,
            health("ok", 97, "2026-10-02T10:00:00Z") to "OK|Работает|запускали 2 ч назад|97|false",
            health("ok", null, "2026-10-02T11:58:00Z") to "OK|Работает|запускали только что|null|false",
            health("warn", 61, null) to "WARN|Бывают сбои||61|true",
            health("warn", null, "2026-09-29T12:00:00Z") to "WARN|Бывают сбои|запускали 3 дн назад|null|true",
        )
        cases.forEach { (input, expected) ->
            val line = Hub.healthLine(input, now)
            assertEquals("здоровье $input", expected, line?.let { "${it.tone}|${it.head}|${it.whenText}|${it.pct}|${it.report}" })
        }
        assertEquals("сломанная дата не превращается в «давно»", "", Hub.ago("вчера", now))
        assertEquals(HealthTone.WARN, Hub.healthLine(health("warn", 50, null), now)?.tone)
    }

    @Test
    fun `здоровье берётся из ответа по своему слагу`() {
        val body = MojangJson.parseToJsonElement(
            """{"packs": [{"slug": "other", "status": "warn"}, {"slug": "arcania", "status": "ok", "launches": 40, "successPct": 97, "lastOkAt": "2026-10-02T10:00:00Z"}]}""",
        )
        val health = Hub.health(body, " Arcania ")
        assertEquals("ok", health?.status)
        assertEquals(97, health?.successPct)
        assertNull("сборки нет в ответе — строки нет", Hub.health(body, "missing"))
    }

    @Test
    fun `популярные версии — проверенные строки или зашитый топ`() {
        val body = MojangJson.parseToJsonElement(
            """{"versions": [
                {"mc": "1.21.1", "loader": "neoforge"},
                {"mc": "latest", "loader": "fabric"},
                {"mc": "1.20.1", "loader": "optifine"},
                {"mc": 1.19, "loader": "fabric"},
                {"mc": "1.20.4", "loader": "vanilla"},
                {"mc": "1.19.2", "loader": "forge"},
                {"mc": "1.18.2", "loader": "quilt"}
            ]}""",
        )
        assertEquals(
            "чужой загрузчик, не-версия и число вместо строки отброшены, не больше трёх",
            listOf(PopularVersion("1.21.1", PresetLoader.NEOFORGE), PopularVersion("1.20.4", PresetLoader.VANILLA), PopularVersion("1.19.2", PresetLoader.FORGE)),
            Hub.popularVersions(body),
        )
        assertEquals("пустой ответ — топ по телеметрии", Hub.POPULAR_FALLBACK, Hub.popularVersions(MojangJson.parseToJsonElement("{}")))
    }

    /** Expected values were computed by the desktop `rotation.ts` itself: both launchers must show the same card of the day. */
    @Test
    fun `позиция дня совпадает с компьютером до бита`() {
        val cases = listOf(
            Triple(20363L, 3, 0) to 0,
            Triple(20363L, 7, 1) to 5,
            Triple(20364L, 7, 1) to 3,
            Triple(2909L, 5, 7) to 1,
            Triple(0L, 10, 0) to 0,
            Triple(1L, 10, 0) to 8,
            Triple(20000L, 13, 2) to 10,
            Triple(123456L, 1000, 3) to 287,
        )
        cases.forEach { (input, expected) ->
            val (day, size, salt) = input
            assertEquals("день $day, длина $size, соль $salt", expected, Rotation.pickIndex(day, size, salt))
        }
        assertEquals("пустой список — нулевая позиция, а не деление на ноль", 0, Rotation.pickIndex(5, 0, 0))
    }

    @Test
    fun `герой дня меняется раз в сутки, а не на каждом запуске`() {
        val cases = listOf(
            (null to "2026-10-02") to (0 to "2026-10-02:0"),
            ("2026-10-02:1" to "2026-10-02") to (1 to "2026-10-02:1"),
            ("2026-10-01:1" to "2026-10-02") to (2 to "2026-10-02:2"),
            ("2026-10-01:2" to "2026-10-02") to (0 to "2026-10-02:0"),
            ("мусор" to "2026-10-02") to (0 to "2026-10-02:0"),
            ("2026-10-01:-4" to "2026-10-02") to (1 to "2026-10-02:1"),
        )
        cases.forEach { (input, expected) ->
            val pick = Rotation.heroPick(input.first, input.second)
            assertEquals("сохранено ${input.first}", expected, pick.index to pick.stored)
        }
        assertEquals(
            "очередь начинается со слота дня, остальные — запас",
            listOf(HeroSlot.WEEK, HeroSlot.ARCANIA, HeroSlot.ONEBLOCK),
            Rotation.heroQueue(2),
        )
    }

    @Test
    fun `подборка недели — разные сборки и не длиннее списка`() {
        val items = listOf("a", "b", "c", "d", "e")
        val picks = Rotation.weekPicks(items, Rotation.weekNumber(20363), 3)
        assertEquals("три карточки", 3, picks.size)
        assertEquals("без повторов", 3, picks.toSet().size)
        assertEquals("короткий список — сколько есть", listOf("a"), Rotation.weekPicks(listOf("a"), 1, 3))
        assertEquals("неделя начинается с понедельника, как на компьютере", 2909L, Rotation.weekNumber(20363))
        assertEquals("порядок дня — поворот, а не перемешивание", setOf("a", "b", "c"), Rotation.ofDay(listOf("a", "b", "c"), 4, 1).toSet())
    }

    @Test
    fun `музыка лобби — треки игры из индекса, как на компьютере`() {
        val hash = "0123456789abcdef0123456789abcdef01234567"
        val objects = buildMap {
            put("minecraft/sounds/music/game/sweden.ogg", hash to 3_000_000L)
            put("minecraft/sounds/music/menu/mutation.ogg", hash.uppercase() to 2_000_000L)
            put("minecraft/sounds/music/game/broken.ogg", "nothex" to 1L)
            put("minecraft/sounds/ambient/cave/cave1.ogg", hash to 1L)
            put("minecraft/sounds/music/game/notes.txt", hash to 1L)
            for (i in 0 until 30) put("minecraft/sounds/music/game/nether/track_$i.ogg", hash to 1L)
        }
        val tracks = LobbyMusic.pickTracks(objects)
        assertEquals("не больше лимита компьютера", LobbyMusic.LIMIT, tracks.size)
        assertTrue("только музыка в ogg с настоящим хешем", tracks.all { it.key.startsWith(LobbyMusic.PREFIX) && it.key.endsWith(".ogg") && it.key != "minecraft/sounds/music/game/broken.ogg" })
        assertTrue("хеш приводится к нижнему регистру — так лежат объекты", tracks.all { it.hash == hash })
        assertEquals("Sweden", LobbyMusic.title("minecraft/sounds/music/game/sweden.ogg"))
        assertEquals("Wet Hands", LobbyMusic.title("minecraft/sounds/music/game/wet_hands.ogg"))
    }

    @Test
    fun `картинки режимов — только коды из манифеста дизайнера`() {
        val arts = ModeArts.parse(
            """{"PVP": {"title": "PvP", "rig": {"src": "hero-46", "w": 1, "h": 1}}, "ONEBLOCK": {"title": "OneBlock"}, "bad": {}, "LIST": []}""",
        )
        assertEquals("неверный код и не-объект отброшены", setOf("PVP", "ONEBLOCK"), arts.keys)
        assertEquals("mode-art/rigs/PVP.webp", arts["PVP"]?.rig)
        assertNull("без рига — значок, а не выдуманный файл", arts["ONEBLOCK"]?.rig)
        assertEquals("mode-art/ONEBLOCK-bg.png", arts["ONEBLOCK"]?.background)
        assertTrue("битый манифест — пусто, а не падение", ModeArts.parse("{").isEmpty())
    }
}
