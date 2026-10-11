package net.millida.launcher.core

import kotlinx.coroutines.runBlocking
import net.millida.launcher.core.account.MemoryVault
import net.millida.launcher.core.account.MillidaAuth
import net.millida.launcher.core.friends.BeatStatus
import net.millida.launcher.core.friends.CallHandoff
import net.millida.launcher.core.friends.ChatTarget
import net.millida.launcher.core.friends.DialogKind
import net.millida.launcher.core.friends.FriendPlace
import net.millida.launcher.core.friends.FriendsClient
import net.millida.launcher.core.friends.FriendsError
import net.millida.launcher.core.friends.PeerRead
import net.millida.launcher.core.friends.Presence
import net.millida.launcher.core.friends.RequestOutcome
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Friends, presence and messages on the phone read the same portal the desktop
 * launcher reads, so what is pinned here is agreement, not preference: the
 * presence verdict (the site has already shipped a double-status bug once), the
 * shape of the lists, the exact endpoint a message leaves through, and the fact
 * that a refusal reaches the player as a sentence instead of a code.
 */
class FriendsTest {

    private lateinit var server: MockWebServer

    @Before
    fun start() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun stop() {
        server.shutdown()
    }

    private fun client(): FriendsClient {
        val vault = MemoryVault()
        vault.write(MillidaAuth.KEY_ACCESS, "token")
        return FriendsClient(MillidaAuth(OkHttpClient(), vault, server.url("/v2").toString().trimEnd('/')))
    }

    private fun reply(body: String, code: Int = 200) {
        server.enqueue(MockResponse().setResponseCode(code).setBody(body))
    }

    /**
     * Input -> verdict, carried over from the desktop rules (src/lib/presence.ts)
     * row for row. The server credits playtime for every "playing" beat, so a
     * wrong verdict here is hours the player never played.
     */
    @Test
    fun `удар присутствия решается теми же правилами, что и на десктопе`() {
        val cases = listOf(
            Triple("игра идёт — бьём playing", Triple(null, true, true), BeatStatus.PLAYING),
            Triple("launch попросил playing", Triple("playing", true, true), BeatStatus.PLAYING),
            Triple("lobby при живой сессии остаётся игрой (окно свернули)", Triple("lobby", true, true), BeatStatus.PLAYING),
            Triple("сессии нет — только lobby", Triple(null, false, true), BeatStatus.LOBBY),
            Triple("без ядра сессия ничего не доказывает", Triple(null, true, false), BeatStatus.LOBBY),
            Triple("без ядра прямой playing тоже отклоняем", Triple("playing", true, false), BeatStatus.LOBBY),
        )
        for ((name, input, want) in cases) {
            val (asked, hasSession, coreAvailable) = input
            assertEquals(name, want, Presence.beatStatus(asked, hasSession, coreAvailable))
        }
    }

    @Test
    fun `пустой статус и lobby — одно и то же для правил присутствия`() {
        assertEquals(
            "пустая строка приходит от старых вызовов и означает «не просили ничего»",
            BeatStatus.PLAYING,
            Presence.beatStatus("", true, true),
        )
    }

    @Test
    fun `список друзей разбирается со всеми состояниями и игровым ником`() = runBlocking {
        reply(
            """
            {"friends":[
              {"userId":"u1","nickname":"Vasya","displayName":"Вася","avatarUrl":null,
               "online":true,"playing":true,"place":"game","text":"В игре · Выживание",
               "serverIp":"play.millida.net","serverName":"Выживание","build":"Fabric 1.21",
               "gameNick":"MsNick","lastSeen":1700000000000,"unread":3,"lastMessageAt":1700000000001},
              {"userId":"u2","nickname":"Petya","online":true,"playing":false,"place":"launcher","text":"В лаунчере"},
              {"userId":"u3","nickname":"Web","online":true,"playing":false,"place":"web","text":"На сайте"},
              {"userId":"u4","nickname":"Gone","online":false,"playing":false,"place":null,"text":"Не в сети · 2 ч назад"}
            ]}
            """.trimIndent(),
        )

        val friends = client().friends()

        assertEquals("друзей четверо", 4, friends.size)
        assertEquals("в игре", FriendPlace.GAME, friends[0].place)
        assertTrue("в игре значит онлайн", friends[0].online && friends[0].playing)
        assertEquals("адрес сервера доезжает целиком", "play.millida.net", friends[0].serverIp)
        assertEquals(
            "игровой ник лицензии Microsoft — отдельное поле, иначе друга зовут именем, которого нет в игре",
            "MsNick",
            friends[0].gameNick,
        )
        assertEquals("непрочитанное считает сервер", 3, friends[0].unread)
        assertEquals("в лаунчере", FriendPlace.LAUNCHER, friends[1].place)
        assertEquals("на сайте", FriendPlace.WEB, friends[2].place)
        assertEquals("не в сети", FriendPlace.OFFLINE, friends[3].place)
        assertNull("совпадающий ник сервер не присылает", friends[1].gameNick)
        assertEquals("готовая строка статуса берётся у сервера", "Не в сети · 2 ч назад", friends[3].text)
        assertEquals("список друзей запрашивается один раз", "/v2/friends", server.takeRequest().path)
    }

    @Test
    fun `список диалогов сводит личку и группы и ставит свежие сверху`() = runBlocking {
        reply(
            """
            {"friends":[
              {"userId":"u1","nickname":"Старый","unread":0,"lastMessageAt":1000},
              {"userId":"u2","nickname":"Свежий","unread":2,"lastMessageAt":9000,"online":true}
            ]}
            """.trimIndent(),
        )
        reply(
            """
            {"me":"me","rooms":[
              {"id":"r1","title":"Наши","ownerId":"me","unread":4,"lastMessageAt":5000,
               "members":[{"userId":"u1","nickname":"Старый","role":"owner"},{"userId":"u2","nickname":"Свежий","role":"member"}]}
            ]}
            """.trimIndent(),
        )

        val api = client()
        val dialogs = api.dialogs()

        assertEquals("строк три", 3, dialogs.size)
        assertEquals("свежая переписка первая", "Свежий", dialogs[0].title)
        assertEquals("группа между ними по времени", DialogKind.ROOM, dialogs[1].kind)
        assertEquals("участники группы — подпись строки", "Старый, Свежий", dialogs[1].subtitle)
        assertEquals("ключ треда совпадает с ключом сайта", "friends:room-r1", dialogs[1].threadKey)
        assertEquals("непрочитанное суммируется по всем диалогам", 6, api.unreadTotal(dialogs))
    }

    @Test
    fun `страница переписки читается с курсором, а не перечитывается целиком`() = runBlocking {
        reply(
            """
            {"messages":[
              {"id":"m1","me":false,"text":"Привет","ts":100,"deleted":false,
               "attachment":{"url":"https://cdn/x.png","kind":"image","name":"x.png"},
               "reactions":[{"emoji":"👍","count":2,"mine":true}]},
              {"id":"m2","me":true,"text":"Здорово","ts":200,"editedAt":250,
               "replyTo":{"id":"m1","me":false,"from":"u1","text":"Привет","deleted":false,"kind":"image"}}
            ],"hasMore":true,"peerReadAt":150}
            """.trimIndent(),
        )

        val page = client().history(ChatTarget.Dm("u1"), before = 100, limit = 30)

        assertEquals("сообщений два", 2, page.messages.size)
        assertTrue("сервер сказал, что выше есть ещё", page.hasMore)
        assertEquals("отметка прочтения собеседником", 150L, page.peerReadAt)
        assertEquals("вложение разбирается", "image", page.messages[0].attachment?.kind)
        assertEquals("реакция с моим голосом", true, page.messages[0].reactions.first().mine)
        assertEquals("цитата доезжает", "m1", page.messages[1].replyTo?.id)
        assertEquals(
            "курсор уходит в запрос — иначе «вверх» перечитывает всю переписку",
            "/v2/friends/chat/u1?before=100&limit=30",
            server.takeRequest().path,
        )
    }

    @Test
    fun `сообщение уходит в личку по адресу собеседника`() = runBlocking {
        reply("""{"id":"m9","me":true,"text":"Привет","ts":777}""")

        val sent = client().send(ChatTarget.Dm("u1"), "  Привет  ", replyToId = "m1")

        val request = server.takeRequest()
        assertEquals("личка адресуется человеку", "/v2/friends/chat/u1", request.path)
        assertEquals("POST", request.method)
        val body = request.body.readUtf8()
        assertTrue("текст уходит обрезанным по краям: $body", body.contains("\"text\":\"Привет\""))
        assertTrue("ответ на сообщение сохраняется: $body", body.contains("\"replyToId\":\"m1\""))
        assertEquals("ответ сервера — готовое сообщение", "m9", sent.id)
    }

    @Test
    fun `сообщение в группу уходит по адресу группы`() = runBlocking {
        reply("""{"id":"m10","me":true,"text":"Всем привет","ts":778}""")

        client().send(ChatTarget.Room("r1"), "Всем привет")

        assertEquals("группа адресуется комнатой", "/v2/friends/rooms/r1/chat", server.takeRequest().path)
    }

    @Test
    fun `пустое сообщение без вложения до сети не доходит`() = runBlocking {
        val error = runCatching { client().send(ChatTarget.Dm("u1"), "   ") }.exceptionOrNull()
        assertTrue("отказ объясняется словами", error is FriendsError)
        assertEquals("Пустое сообщение отправить нельзя", error?.message)
        assertEquals("сеть не трогали", 0, server.requestCount)
    }

    @Test
    fun `отметка прочитанного бьёт в свой адрес у лички и у группы`() = runBlocking {
        reply("""{"ok":true}""")
        reply("""{"ok":true}""")

        val api = client()
        api.markRead(ChatTarget.Dm("u1"))
        api.markRead(ChatTarget.Room("r1"))

        assertEquals("/v2/friends/chat/u1/read", server.takeRequest().path)
        assertEquals("/v2/friends/rooms/r1/read", server.takeRequest().path)
    }

    @Test
    fun `картинка уходит вложением, голосовые на телефоне не пишутся`() = runBlocking {
        reply("""{"url":"https://cdn/a.png","kind":"image","name":"a.png"}""")

        val attachment = client().uploadImage(byteArrayOf(1, 2, 3))

        val request = server.takeRequest()
        assertEquals("/v2/friends/chat/upload", request.path)
        val body = request.body.readUtf8()
        assertTrue("вид вложения объявлен: $body", body.contains("\"kind\":\"image\""))
        assertTrue("файл уезжает в base64: $body", body.contains("\"dataBase64\":\"AQID\""))
        assertEquals("https://cdn/a.png", attachment.url)
    }

    @Test
    fun `поиск игроков идёт с закодированным запросом`() = runBlocking {
        reply("""{"results":[{"userId":"u7","nickname":"Вася","isFriend":false,"pending":true,"online":true}]}""")

        val found = client().search(" Вася ")

        assertEquals("найден один", 1, found.size)
        assertTrue("заявка уже висит", found[0].pending)
        assertFalse("в друзьях ещё нет", found[0].isFriend)
        assertEquals(
            "запрос кодируется, а не подставляется как есть",
            "/v2/friends/search?q=%D0%92%D0%B0%D1%81%D1%8F",
            server.takeRequest().path,
        )
    }

    @Test
    fun `заявка по нику может быть принята сервером сразу`() = runBlocking {
        reply("""{"status":"accepted"}""")

        val outcome = client().addByNickname(" Vasya ")

        assertEquals("встречная заявка закрывается дружбой", RequestOutcome.ACCEPTED, outcome)
        val request = server.takeRequest()
        assertEquals("/v2/friends/request", request.path)
        assertTrue("ник уходит очищенным", request.body.readUtf8().contains("\"nickname\":\"Vasya\""))
    }

    @Test
    fun `заявки, блокировки и разблокировка идут каждая в свой адрес`() = runBlocking {
        repeat(4) { reply("""{"ok":true}""") }
        reply("""{"items":[{"blockedId":"u5","user":{"id":"u5","nickname":"Грубиян","avatarUrl":null}}]}""")

        val api = client()
        api.acceptRequest("f1")
        api.declineRequest("f2")
        api.removeFriend("u4")
        api.blockPlayer("u5")
        val blocked = api.blocked()

        assertEquals("/v2/friends/accept", server.takeRequest().path)
        assertEquals("/v2/friends/decline", server.takeRequest().path)
        assertEquals("/v2/friends/remove", server.takeRequest().path)
        assertEquals("/v2/friends/block", server.takeRequest().path)
        assertEquals("/v2/core/blocks", server.takeRequest().path)
        assertEquals("заблокированный один", 1, blocked.size)
        assertEquals("Грубиян", blocked[0].nickname)
    }

    @Test
    fun `разблокировка уходит удалением по идентификатору`() = runBlocking {
        reply("""{"ok":true}""")

        client().unblock("u5")

        val request = server.takeRequest()
        assertEquals("/v2/core/blocks/u5", request.path)
        assertEquals("DELETE", request.method)
    }

    @Test
    fun `чужой идентификатор с путём наружу не уходит`() = runBlocking {
        val error = runCatching { client().unblock("../../admin") }.exceptionOrNull()
        assertTrue("подставной идентификатор отклоняется до сети", error is FriendsError)
        assertEquals("сеть не трогали", 0, server.requestCount)
    }

    /**
     * Input -> verdict for refusals. A bare "403" or "Not Found" on the screen
     * tells the player neither what happened nor what to do; a sentence the
     * server wrote about itself is always better than our own guess.
     */
    @Test
    fun `отказы доезжают до игрока словами, а не кодом`() = runBlocking {
        val api = client()

        reply("""{"statusCode":403,"message":"Переписка — только с друзьями"}""", code = 403)
        assertEquals(
            "объяснение сервера важнее нашего запасного текста",
            "Переписка — только с друзьями",
            runCatching { api.history(ChatTarget.Dm("u1")) }.exceptionOrNull()?.message,
        )

        reply("", code = 403)
        assertEquals(
            "403 без тела всё равно объясняется",
            "Millida отказала в этом действии",
            runCatching { api.history(ChatTarget.Dm("u1")) }.exceptionOrNull()?.message,
        )

        reply("""{"statusCode":404,"message":"Not Found"}""", code = 404)
        assertEquals(
            "шум «Not Found» заменяется тем, что делал вызывающий",
            "Не удалось открыть переписку",
            runCatching { api.history(ChatTarget.Dm("u1")) }.exceptionOrNull()?.message,
        )

        reply("", code = 404)
        assertEquals(
            "404 без тела — это «уже удалено»",
            "Не найдено — возможно, это уже удалено",
            runCatching { api.history(ChatTarget.Dm("u1")) }.exceptionOrNull()?.message,
        )

        reply("""{"statusCode":401,"message":"Unauthorized"}""", code = 401)
        assertEquals(
            "истёкшая сессия называется сессией, а не отказом",
            "Сессия Millida закончилась — войди в аккаунт заново",
            runCatching { api.friends() }.exceptionOrNull()?.message,
        )

        reply("""{"statusCode":429,"message":"ThrottlerException: Too Many Requests"}""", code = 429)
        assertEquals(
            "частые попытки объясняются ожиданием",
            "Слишком много попыток подряд — подожди немного и повтори",
            runCatching { api.friends() }.exceptionOrNull()?.message,
        )
    }

    @Test
    fun `удар присутствия не тащит сервер и сборку в лобби`() = runBlocking {
        reply("""{"verifiedSeconds":60}""")

        client().heartbeat(BeatStatus.LOBBY, serverName = "Выживание", serverIp = "1.2.3.4", build = "Fabric")

        val body = server.takeRequest().body.readUtf8()
        assertTrue("статус уходит как есть: $body", body.contains("\"status\":\"lobby\""))
        assertFalse("в лобби сервера нет — иначе друзья видят игру там, где её нет: $body", body.contains("Выживание"))
    }

    /**
     * One full delta round, shaped as friends.service.ts pollOnce and
     * rooms.service.ts pollDelta send it. Every row lands in the thread it
     * belongs to; a row without its thread is dropped rather than shown in the
     * wrong conversation.
     */
    @Test
    fun `опрос раскладывает новое, правки и прочтения по своим перепискам`() = runBlocking {
        reply(
            """
            {"now":5000,"nextPollMs":1500,"waited":true,
             "messages":[
               {"id":"m1","from":"u1","fromNick":"Vasya","me":false,"text":"Привет","ts":4000},
               {"id":"m0","me":false,"text":"без отправителя","ts":3999}],
             "updates":[
               {"id":"m2","peer":"u2","me":true,"text":"поправил","ts":1000,"editedAt":4500},
               {"id":"m3","me":true,"text":"без собеседника","ts":1000}],
             "roomMessages":[{"id":"m4","roomId":"r1","from":"u3","fromNick":"Petya","text":"всем","ts":4100}],
             "roomUpdates":[
               {"id":"m5","roomId":"r1","from":"u3","text":"","ts":900,"deleted":true},
               {"id":"m6","from":"u3","text":"без группы","ts":900}],
             "reads":[{"userId":"u1","readAt":4800},{"userId":"u9"}],
             "presence":[{"userId":"u1","nickname":"Vasya","online":true,"playing":false,"place":"launcher","text":"В лаунчере"}],
             "requests":{"incoming":[{"id":"f1","userId":"u7","nickname":"Новичок"}],"outgoing":[]},
             "typing":[]}
            """.trimIndent(),
        )

        val poll = client().poll(since = 4000, wait = true)

        assertEquals(
            "ожидание просится флагом wait, курсор — since, иначе новое придёт только на следующем круге",
            "/v2/friends/poll?wait=1&since=4000",
            server.takeRequest().path,
        )
        val cases = listOf(
            Triple(
                "новое в личке и в группе попадает каждое в свою переписку; без отправителя — никуда",
                listOf(ChatTarget.Dm("u1") to "m1", ChatTarget.Room("r1") to "m4"),
                poll.incoming.map { it.target to it.message.id },
            ),
            Triple(
                "правка в личке адресуется собеседнику (peer), в группе — комнате; без адреса выбрасывается",
                listOf(ChatTarget.Dm("u2") to "m2", ChatTarget.Room("r1") to "m5"),
                poll.updates.map { it.target to it.message.id },
            ),
            Triple(
                "галочка «прочитано» только с отметкой времени — без неё нечего сравнивать",
                listOf(PeerRead("u1", 4800L)),
                poll.reads,
            ),
            Triple(
                "курсор следующего круга — now ответа, а не часы телефона",
                5000L,
                poll.now,
            ),
            Triple(
                "сервер сам задаёт паузу до следующего опроса",
                1500L,
                poll.nextPollMs,
            ),
        )
        for ((why, want, got) in cases) {
            assertEquals(why, want, got)
        }
        assertEquals("правка несёт отметку времени правки", 4500L, poll.updates[0].message.editedAt)
        assertTrue("удаление в группе приходит правкой с пометкой deleted", poll.updates[1].message.deleted)
        assertTrue("ответ пришёл после ожидания", poll.waited)
        assertTrue("блок друзей был в ответе", poll.hasPresence)
        assertEquals("друзья из блока presence", listOf("u1"), poll.friends.map { it.userId })
        assertTrue("блок заявок был в ответе", poll.hasRequests)
        assertEquals("входящая заявка разобрана", listOf("f1"), poll.requests.incoming.map { it.id })
    }

    /**
     * Input -> verdict for the blocks the poll may leave out. A block that is
     * absent says nothing and must not wipe the screen; a block that is present
     * but empty is the truth and must.
     */
    @Test
    fun `опрос отличает пропущенный блок от пустого`() = runBlocking {
        data class Case(val why: String, val body: String, val presence: Boolean, val requests: Boolean)

        val cases = listOf(
            Case(
                "прерванное ожидание приходит без блоков — друзья и заявки на экране остаются",
                """{"now":10,"waited":true}""",
                false,
                false,
            ),
            Case(
                "пустые списки — правда: друзей в сети и заявок больше нет",
                """{"now":10,"presence":[],"requests":{"incoming":[],"outgoing":[]}}""",
                true,
                true,
            ),
            Case(
                "блоки независимы: друзья пришли, заявки нет",
                """{"now":10,"presence":[{"userId":"u1","nickname":"A"}]}""",
                true,
                false,
            ),
        )
        val api = client()

        for (case in cases) {
            reply(case.body)
            val poll = api.poll(since = 10)
            assertEquals("presence: ${case.why}", case.presence, poll.hasPresence)
            assertEquals("requests: ${case.why}", case.requests, poll.hasRequests)
            assertEquals(
                "без wait опрос короткий, как у старых лаунчеров: ${case.why}",
                "/v2/friends/poll?since=10",
                server.takeRequest().path,
            )
        }
    }

    @Test
    fun `правка и удаление сообщения идут по его id`() = runBlocking {
        reply("""{"id":"m1","me":true,"text":"Исправил","ts":100,"editedAt":200}""")
        reply("""{"id":"m1","me":true,"text":"","ts":100,"deleted":true}""")
        val api = client()

        val edited = api.editMessage("m1", "  Исправил  ")
        val edit = server.takeRequest()
        val editBody = edit.body.readUtf8()
        val deleted = api.deleteMessage("m1")
        val delete = server.takeRequest()

        val routes = listOf(
            Triple("правка — на /edit самого сообщения, а не новым сообщением в переписку", edit, "/v2/friends/chat/message/m1/edit"),
            Triple("удаление — на /delete, иначе у собеседника сообщение останется", delete, "/v2/friends/chat/message/m1/delete"),
        )
        for ((why, request, path) in routes) {
            assertEquals(why, path, request.path)
            assertEquals("метод POST: $why", "POST", request.method)
        }
        assertTrue("текст правки уходит обрезанным по краям: $editBody", editBody.contains("\"text\":\"Исправил\""))
        assertEquals("ответ правки — сообщение с отметкой правки, её экран и рисует", 200L, edited.editedAt)
        assertTrue("ответ удаления помечает сообщение удалённым", deleted.deleted)
    }

    @Test
    fun `правка пустым текстом и подставной id до сети не доходят`() = runBlocking {
        data class Case(val why: String, val call: suspend () -> Unit)

        val api = client()
        val cases = listOf(
            Case("пустая правка — это удаление; просим удалить явно, а не шлём пустоту") { api.editMessage("m1", "   ") },
            Case("id сообщения с путём наружу при правке") { api.editMessage("../m1", "текст") },
            Case("id сообщения с путём наружу при удалении") { api.deleteMessage("a/b") },
            Case("id игрока с путём наружу в профиле") { api.profile("../../admin") },
        )

        for (case in cases) {
            val error = runCatching { case.call() }.exceptionOrNull()
            assertTrue("${case.why}: отказ обязан объясняться словами, а не падением", error is FriendsError)
        }
        assertEquals("ни один такой вызов не должен уйти в сеть", 0, server.requestCount)
    }

    /**
     * Input -> verdict for the hours on a friend's card. The server hides them
     * two ways (no key, or null for a non-friend); neither may turn into
     * "0 h played", which is a false statement about a person.
     */
    @Test
    fun `часы друга берутся из профиля, а скрытые не становятся нулём`() = runBlocking {
        data class Case(val why: String, val body: String, val seconds: Long?)

        val cases = listOf(
            Case(
                "друг с открытой статистикой — секунды как есть",
                """{"nickname":"Vasya","online":true,"playing":true,"serverIp":"play.millida.net","stats":{"totalSeconds":7200,"sessions":3}}""",
                7200L,
            ),
            Case("не друг: stats null — часов нет", """{"nickname":"Vasya","stats":null}""", null),
            Case("скрыто приватностью: ключа stats нет вовсе", """{"nickname":"Vasya","activityHidden":true}""", null),
            Case("ноль секунд не показывается как «0 ч»", """{"nickname":"Vasya","stats":{"totalSeconds":0}}""", null),
        )
        val api = client()

        for (case in cases) {
            reply(case.body)
            val card = api.profile("u1")
            assertEquals(case.why, case.seconds, card.totalSeconds)
            assertEquals("ник на карточке: ${case.why}", "Vasya", card.nickname)
            assertEquals("профиль просится по id друга: ${case.why}", "/v2/friends/profile/u1", server.takeRequest().path)
        }
    }

    @Test
    fun `звонок отдаётся вебу ссылкой на тот же тред`() {
        assertEquals(
            "личка открывается по ключу сайта",
            "https://millida.net/chats?tab=friends&t=friends%3Au1",
            CallHandoff.threadUrl(ChatTarget.Dm("u1")),
        )
        assertEquals(
            "группа — по ключу комнаты",
            "https://millida.net/chats?tab=friends&t=friends%3Aroom-r1",
            CallHandoff.threadUrl(ChatTarget.Room("r1")),
        )
    }
}
