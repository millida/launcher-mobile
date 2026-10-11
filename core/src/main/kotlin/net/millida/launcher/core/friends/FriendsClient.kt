package net.millida.launcher.core.friends

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import net.millida.launcher.core.account.MillidaAuth
import java.io.IOException
import java.net.URLEncoder
import java.util.Base64

/**
 * Friends, presence and messages as the portal serves them. Every path here is
 * the one the desktop launcher already uses, so both clients read the same
 * social graph; nothing is computed locally that the server already decided
 * (the status line, the "playing" verdict, unread counts).
 *
 * Call signalling lives in CallClient (core/call).
 */
class FriendsClient(private val auth: MillidaAuth) {

    suspend fun friends(): List<Friend> = guarded("Не удалось загрузить друзей") {
        auth.authorized("/friends", "GET", null).array("friends").map { it.jsonObject.toFriend() }.distinctBy { it.userId }
    }

    suspend fun requests(): FriendRequests = guarded("Не удалось загрузить заявки") {
        val response = auth.authorized("/friends/requests", "GET", null)
        FriendRequests(
            incoming = response.array("incoming").map { it.jsonObject.toRequestCard() },
            outgoing = response.array("outgoing").map { it.jsonObject.toRequestCard() },
        )
    }

    /** The server answers an empty list for a query shorter than two characters. */
    suspend fun search(query: String): List<FoundPlayer> = guarded("Поиск недоступен") {
        val path = "/friends/search?q=" + URLEncoder.encode(query.trim(), "UTF-8")
        auth.authorized(path, "GET", null).array("results").map { it.jsonObject.toFoundPlayer() }
    }

    suspend fun addByNickname(nickname: String): RequestOutcome =
        sendRequest(buildJsonObject { put("nickname", nickname.trim()) })

    /** `id` here is the id of the request row, not of the player. */
    suspend fun acceptRequest(requestId: String) = act("/friends/accept", "Не удалось принять заявку") {
        put("id", requireId(requestId))
    }

    suspend fun declineRequest(requestId: String) = act("/friends/decline", "Не удалось отклонить заявку") {
        put("id", requireId(requestId))
    }

    suspend fun cancelRequest(requestId: String) = act("/friends/cancel", "Не удалось отменить заявку") {
        put("id", requireId(requestId))
    }

    suspend fun removeFriend(userId: String) = act("/friends/remove", "Не удалось убрать из друзей") {
        put("userId", requireId(userId))
    }

    /** Older servers kept the friendship after a block, so the caller removes the friend right after. */
    suspend fun blockPlayer(userId: String) = act("/friends/block", "Не удалось заблокировать") {
        put("userId", requireId(userId))
    }

    suspend fun blocked(): List<BlockedPlayer> = guarded("Не удалось загрузить список блокировок") {
        auth.authorized("/core/blocks", "GET", null).array("items").map { entry ->
            val row = entry.jsonObject
            val user = row["user"] as? JsonObject
            BlockedPlayer(
                blockedId = row.text("blockedId"),
                nickname = user?.text("nickname").orEmpty().ifEmpty { user?.text("displayName").orEmpty() },
                avatarUrl = user?.textOrNull("avatarUrl"),
            )
        }
    }

    suspend fun unblock(userId: String) = guarded("Не удалось разблокировать") {
        auth.authorized("/core/blocks/" + pathSegment(userId), "DELETE", null)
        Unit
    }

    suspend fun roomsPage(): RoomsPage = guarded("Не удалось загрузить группы") {
        val response = auth.authorized("/friends/rooms", "GET", null)
        RoomsPage(me = response.text("me"), rooms = response.array("rooms").map { it.jsonObject.toRoom() })
    }

    /** Hours played come only from here: the friends list does not carry them. */
    suspend fun profile(userId: String): FriendProfileCard = guarded("Профиль не загрузился") {
        val response = auth.authorized("/friends/profile/" + pathSegment(userId), "GET", null)
        val stats = response["stats"] as? JsonObject
        FriendProfileCard(
            userId = userId,
            nickname = response.text("nickname"),
            text = response.text("text"),
            online = response.flag("online"),
            playing = response.flag("playing"),
            serverIp = response.textOrNull("serverIp"),
            serverName = response.textOrNull("serverName"),
            build = response.textOrNull("build"),
            totalSeconds = stats?.number("totalSeconds")?.takeIf { it > 0 },
            sessions = stats?.number("sessions")?.takeIf { it > 0 },
            lastBuild = stats?.textOrNull("lastBuild"),
            lastServer = stats?.textOrNull("lastServer"),
            lastServerName = stats?.textOrNull("lastServerName"),
            lastPlayedAt = stats?.number("lastPlayedAt")?.takeIf { it > 0 },
        )
    }

    /** The server toggles the reaction and answers with the whole message, like an edit does. */
    suspend fun react(messageId: String, emoji: String): ChatMessage = guarded("Реакция не поставилась") {
        val path = "/friends/chat/message/" + pathSegment(messageId) + "/react"
        auth.authorized(path, "POST", buildJsonObject { put("emoji", emoji) }).toMessage()
    }

    /** The server answers with the whole message, the same shape the poll uses for edits. */
    suspend fun editMessage(messageId: String, text: String): ChatMessage = guarded("Сообщение не изменилось") {
        val body = text.trim()
        if (body.isEmpty()) throw FriendsError("Пустым сообщение не сделать — удали его")
        val path = "/friends/chat/message/" + pathSegment(messageId) + "/edit"
        auth.authorized(path, "POST", buildJsonObject { put("text", body) }).toMessage()
    }

    suspend fun deleteMessage(messageId: String): ChatMessage = guarded("Сообщение не удалилось") {
        val path = "/friends/chat/message/" + pathSegment(messageId) + "/delete"
        auth.authorized(path, "POST", buildJsonObject { }).toMessage()
    }

    /**
     * Conversation list: personal chats and groups in one ordering, freshest
     * first. Someone never talked to has no timestamp and sits below those who
     * wrote, which is the order the desktop list ends up with too.
     */
    suspend fun dialogs(): List<Dialog> = dialogs(friends())

    suspend fun dialogs(people: List<Friend>): List<Dialog> {
        val page = roomsPage()
        val groups = page.rooms
        val rows = people.map { friend ->
            Dialog(
                kind = DialogKind.DM,
                id = friend.userId,
                title = friend.nickname,
                subtitle = friend.text,
                avatarUrl = friend.avatarUrl,
                unread = friend.unread,
                lastMessageAt = friend.lastMessageAt,
                online = friend.online,
            )
        } + groups.map { room ->
            Dialog(
                kind = DialogKind.ROOM,
                id = room.id,
                title = room.title,
                subtitle = room.members.filter { it.userId != page.me }.joinToString(", ") { it.nickname },
                unread = room.unread,
                lastMessageAt = room.lastMessageAt,
            )
        }
        return rows.distinctBy { it.kind to it.id }.sortedWith(
            compareByDescending<Dialog> { it.lastMessageAt ?: 0L }.thenBy { it.title.lowercase() },
        )
    }

    /**
     * A page of a conversation. `before` is the cursor: the server answers with
     * messages OLDER than it, so scrolling up loads the next page instead of
     * re-reading the whole thread.
     */
    suspend fun history(target: ChatTarget, before: Long? = null, limit: Int? = null): ChatPage =
        guarded("Не удалось открыть переписку") {
            val query = buildList {
                before?.let { add("before=$it") }
                limit?.let { add("limit=$it") }
            }.joinToString("&")
            val path = chatBase(target) + if (query.isEmpty()) "" else "?$query"
            val response = auth.authorized(path, "GET", null)
            ChatPage(
                messages = response.array("messages").map { it.jsonObject.toMessage() },
                hasMore = response.flag("hasMore"),
                peerReadAt = response.number("peerReadAt"),
            )
        }

    suspend fun send(
        target: ChatTarget,
        text: String,
        attachment: ChatAttachment? = null,
        replyToId: String? = null,
    ): ChatMessage = guarded("Сообщение не отправлено") {
        val body = text.trim()
        if (body.isEmpty() && attachment == null) throw FriendsError("Пустое сообщение отправить нельзя")
        val payload = buildJsonObject {
            if (body.isNotEmpty()) put("text", body)
            attachment?.let { put("attachment", it.toJson()) }
            replyToId?.takeIf { it.isNotBlank() }?.let { put("replyToId", requireId(it)) }
        }
        auth.authorized(chatBase(target), "POST", payload).toMessage()
    }

    suspend fun markRead(target: ChatTarget) = guarded("Не удалось отметить прочитанным") {
        val path = when (target) {
            is ChatTarget.Dm -> "/friends/chat/" + pathSegment(target.userId) + "/read"
            is ChatTarget.Room -> "/friends/rooms/" + pathSegment(target.roomId) + "/read"
        }
        auth.authorized(path, "POST", buildJsonObject { })
        Unit
    }

    /**
     * Image attachment. The portal takes the file as base64 inside JSON, the
     * same way the desktop launcher sends it.
     */
    suspend fun uploadImage(bytes: ByteArray): ChatAttachment = guarded("Картинка не отправилась") {
        if (bytes.isEmpty()) throw FriendsError("Файл пустой")
        if (bytes.size > MAX_IMAGE_BYTES) throw FriendsError("Картинка больше 2 МБ — выбери поменьше")
        val payload = buildJsonObject {
            put("kind", "image")
            put("dataBase64", Base64.getEncoder().encodeToString(bytes))
        }
        val response = auth.authorized("/friends/chat/upload", "POST", payload)
        val url = response.text("url")
        if (url.isEmpty()) throw FriendsError("Millida не вернула ссылку на картинку")
        ChatAttachment(url = url, kind = "image", name = response.text("name"))
    }

    /**
     * Voice note, recorded on the phone as AAC in MP4. The body is the desktop
     * one (chatMedia.ts uploadVoice): the file, its length and its envelope.
     */
    suspend fun uploadVoice(bytes: ByteArray, durationMs: Long, peaks: List<Int>): ChatAttachment = guarded("Голосовое не отправилось") {
        if (bytes.isEmpty()) throw FriendsError("Запись пустая — микрофон ничего не услышал")
        if (bytes.size > VoiceNotes.MAX_BYTES) throw FriendsError("Голосовое больше 3 МБ — запиши покороче")
        if (!VoiceNotes.isM4a(bytes)) throw FriendsError("Телефон записал звук в неизвестном формате — обнови приложение")
        val payload = buildJsonObject {
            put("kind", "voice")
            put("dataBase64", Base64.getEncoder().encodeToString(bytes))
            put("durationMs", durationMs.coerceIn(0, VoiceNotes.MAX_MS))
            put("peaks", JsonArray(peaks.take(MAX_PEAKS).map { JsonPrimitive(it.coerceIn(0, 100)) }))
        }
        val response = auth.authorized("/friends/chat/upload", "POST", payload)
        val url = response.text("url")
        if (url.isEmpty()) throw FriendsError("Millida не вернула ссылку на голосовое")
        ChatAttachment(
            url = url,
            kind = "voice",
            name = response.text("name").ifEmpty { "Голосовое сообщение" },
            durationMs = durationMs,
            peaks = peaks,
        )
    }

    fun unreadTotal(dialogs: List<Dialog>): Int = dialogs.sumOf { it.unread }

    suspend fun poll(since: Long, wait: Boolean = false): FriendsPoll = guarded("Обновление не пришло") {
        val path = "/friends/poll?" + (if (wait) "wait=1&" else "") + "since=$since"
        val response = auth.authorized(path, "GET", null)
        val requests = response["requests"] as? JsonObject
        val direct = response.array("messages").map { it.jsonObject.toMessage() }
        FriendsPoll(
            now = response.number("now") ?: 0L,
            friends = response.array("presence").map { it.jsonObject.toFriend() }.distinctBy { it.userId },
            requests = FriendRequests(
                incoming = requests?.array("incoming").orEmpty().map { it.jsonObject.toRequestCard() },
                outgoing = requests?.array("outgoing").orEmpty().map { it.jsonObject.toRequestCard() },
            ),
            hasPresence = response["presence"] is JsonArray,
            hasRequests = requests != null,
            incoming = direct.filter { it.from.isNotEmpty() }.map { ThreadMessage(ChatTarget.Dm(it.from), it) } +
                response.roomThreads("roomMessages"),
            updates = response.array("updates").mapNotNull { entry ->
                val row = entry as? JsonObject ?: return@mapNotNull null
                val peer = row.text("peer")
                if (peer.isEmpty()) null else ThreadMessage(ChatTarget.Dm(peer), row.toMessage())
            } + response.roomThreads("roomUpdates"),
            reads = response.array("reads").mapNotNull { entry ->
                val row = entry as? JsonObject ?: return@mapNotNull null
                val user = row.text("userId")
                val at = row.number("readAt")
                if (user.isEmpty() || at == null) null else PeerRead(user, at)
            },
            nextPollMs = response.number("nextPollMs"),
            waited = response.flag("waited"),
            typing = response.array("typing").ids(),
            roomTyping = response.array("roomTyping").mapNotNull { entry ->
                val row = entry as? JsonObject ?: return@mapNotNull null
                val room = row.text("roomId")
                if (room.isEmpty()) null else room to row.array("users").ids()
            }.toMap(),
        )
    }

    /**
     * The presence beat. The verdict is not taken from the caller: see
     * Presence.beatStatus - the server credits playtime for every "playing".
     */
    suspend fun heartbeat(
        status: BeatStatus,
        serverName: String? = null,
        serverIp: String? = null,
        build: String? = null,
        gameNick: String? = null,
        telemetry: JsonObject? = null,
    ) = guarded("Присутствие не отправлено") {
        val playing = status == BeatStatus.PLAYING
        val payload = buildJsonObject {
            put("status", status.wire)
            if (playing) {
                serverName?.takeIf { it.isNotBlank() }?.let { put("server", it) }
                serverIp?.takeIf { it.isNotBlank() }?.let { put("serverIp", it) }
                build?.takeIf { it.isNotBlank() }?.let { put("build", it) }
                gameNick?.takeIf { it.isNotBlank() }?.let { put("gameNick", it) }
            }
            // The launcher heartbeat rides along, as on the desktop: one request per beat instead of two.
            telemetry?.let { put("telemetry", it) }
        }
        auth.authorized("/friends/presence/heartbeat", "POST", payload)
        Unit
    }

    private suspend fun sendRequest(payload: JsonObject): RequestOutcome =
        guarded("Не удалось отправить заявку") {
            when (auth.authorized("/friends/request", "POST", payload).text("status")) {
                "accepted" -> RequestOutcome.ACCEPTED
                "already_friends" -> RequestOutcome.ALREADY_FRIENDS
                else -> RequestOutcome.SENT
            }
        }

    private suspend fun act(path: String, fallback: String, build: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit) =
        guarded(fallback) {
            auth.authorized(path, "POST", buildJsonObject(build))
            Unit
        }

    private fun chatBase(target: ChatTarget): String = when (target) {
        is ChatTarget.Dm -> "/friends/chat/" + pathSegment(target.userId)
        is ChatTarget.Room -> "/friends/rooms/" + pathSegment(target.roomId) + "/chat"
    }

    /**
     * A failure keeps whatever the server said about itself and gets a readable
     * line when it said nothing useful - never a bare code on the screen.
     */
    private inline fun <T> guarded(fallback: String, block: () -> T): T = try {
        block()
    } catch (already: FriendsError) {
        throw already
    } catch (error: IOException) {
        throw FriendsErrors.wrap(error, fallback)
    }

    private companion object {
        const val MAX_IMAGE_BYTES = 2 * 1024 * 1024
    }
}

internal fun requireId(value: String): String {
    val trimmed = value.trim()
    if (trimmed.isEmpty() || trimmed.contains('/') || trimmed.contains("..")) {
        throw FriendsError("Неверный идентификатор игрока")
    }
    return trimmed
}

/** Path segments are encoded, not trusted: an id reaches us from the network. */
internal fun pathSegment(value: String): String =
    URLEncoder.encode(requireId(value), "UTF-8").replace("+", "%20")

/** The server keeps at most 64 envelope points of a voice note (ChatUploadDto). */
private const val MAX_PEAKS = 64

private fun ChatAttachment.toJson(): JsonObject = buildJsonObject {
    put("url", url)
    put("kind", kind)
    if (name.isNotEmpty()) put("name", name)
    durationMs?.let { put("durationMs", it) }
    if (peaks.isNotEmpty()) put("peaks", JsonArray(peaks.take(MAX_PEAKS).map { JsonPrimitive(it) }))
}

private fun JsonObject.toFriend(): Friend {
    val nick = text("nickname")
    return Friend(
        userId = text("userId").ifEmpty { text("id") },
        nickname = nick,
        displayName = text("displayName").ifEmpty { nick },
        avatarUrl = textOrNull("avatarUrl"),
        online = flag("online"),
        playing = flag("playing"),
        place = FriendPlace.of(textOrNull("place")),
        text = text("text"),
        serverIp = textOrNull("serverIp"),
        serverName = textOrNull("serverName"),
        build = textOrNull("build"),
        gameNick = textOrNull("gameNick"),
        lastSeen = number("lastSeen"),
        unread = number("unread")?.toInt() ?: 0,
        lastMessageAt = number("lastMessageAt"),
    )
}

private fun JsonObject.toRequestCard(): FriendRequestCard {
    val user = text("userId")
    return FriendRequestCard(
        id = text("id"),
        userId = user,
        nickname = text("nickname").ifEmpty { user },
        avatarUrl = textOrNull("avatarUrl"),
    )
}

private fun JsonObject.toFoundPlayer(): FoundPlayer = FoundPlayer(
    userId = text("userId").ifEmpty { text("id") },
    nickname = text("nickname"),
    avatarUrl = textOrNull("avatarUrl"),
    isFriend = flag("isFriend"),
    pending = flag("pending"),
    online = flag("online"),
    text = text("text"),
)

private fun JsonObject.toRoom(): FriendRoom = FriendRoom(
    id = text("id"),
    title = text("title"),
    ownerId = text("ownerId"),
    members = array("members").map { entry ->
        val member = entry.jsonObject
        RoomMember(
            userId = member.text("userId"),
            nickname = member.text("nickname"),
            role = member.text("role"),
            avatarUrl = member.textOrNull("avatarUrl"),
        )
    },
    unread = number("unread")?.toInt() ?: 0,
    lastMessageAt = number("lastMessageAt"),
)

private fun JsonObject.toMessage(): ChatMessage = ChatMessage(
    id = text("id"),
    text = text("text"),
    me = flag("me"),
    ts = number("ts") ?: 0L,
    from = text("from").ifEmpty { text("fromId") },
    fromNick = text("fromNick"),
    editedAt = number("editedAt"),
    deleted = flag("deleted"),
    attachment = (this["attachment"] as? JsonObject)?.let { item ->
        ChatAttachment(
            url = item.text("url"),
            kind = item.text("kind").ifEmpty { "file" },
            name = item.text("name"),
            durationMs = item.number("durationMs"),
            peaks = item.array("peaks").mapNotNull { (it as? JsonPrimitive)?.content?.toDoubleOrNull()?.toInt() }.take(MAX_PEAKS),
        )
    },
    replyTo = (this["replyTo"] as? JsonObject)?.let { item ->
        ChatReplyPreview(
            id = item.text("id"),
            me = item.flag("me"),
            from = item.text("from"),
            text = item.text("text"),
            deleted = item.flag("deleted"),
            kind = item.textOrNull("kind"),
        )
    },
    reactions = array("reactions").map { entry ->
        val item = entry.jsonObject
        ChatReaction(
            emoji = item.text("emoji"),
            count = item.number("count")?.toInt() ?: 0,
            mine = item.flag("mine"),
        )
    },
)

private fun JsonObject.roomThreads(key: String): List<ThreadMessage> = array(key).mapNotNull { entry ->
    val row = entry as? JsonObject ?: return@mapNotNull null
    val room = row.text("roomId")
    if (room.isEmpty()) null else ThreadMessage(ChatTarget.Room(room), row.toMessage())
}

private fun JsonObject.array(key: String): List<JsonElement> =
    (this[key] as? JsonArray) ?: emptyList()

private fun List<JsonElement>.ids(): List<String> =
    mapNotNull { (it as? JsonPrimitive)?.takeIf { value -> value.isString }?.content?.takeIf { id -> id.isNotEmpty() } }

private fun JsonObject.text(key: String): String {
    val value = this[key]
    if (value == null || value is JsonNull || value !is JsonPrimitive) return ""
    return value.content
}

private fun JsonObject.textOrNull(key: String): String? = text(key).ifEmpty { null }

private fun JsonObject.flag(key: String): Boolean = text(key).toBooleanStrictOrNull() ?: false

private fun JsonObject.number(key: String): Long? = text(key).toDoubleOrNull()?.toLong()
