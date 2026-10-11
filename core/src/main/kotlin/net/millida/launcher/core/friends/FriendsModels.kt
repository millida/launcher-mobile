package net.millida.launcher.core.friends

/** Where the player is. The site measures web presence by any token-bearing request. */
enum class FriendPlace(val wire: String) {
    GAME("game"),
    LAUNCHER("launcher"),
    WEB("web"),
    OFFLINE("");

    companion object {
        fun of(value: String?): FriendPlace =
            entries.firstOrNull { it.wire == value && it.wire.isNotEmpty() } ?: OFFLINE
    }
}

data class Friend(
    val userId: String,
    val nickname: String,
    val displayName: String = "",
    val avatarUrl: String? = null,
    val online: Boolean = false,
    val playing: Boolean = false,
    val place: FriendPlace = FriendPlace.OFFLINE,
    /** Ready-made Russian line from the server ("В игре · Выживание", "Не в сети · 2 ч назад"). */
    val text: String = "",
    val serverIp: String? = null,
    val serverName: String? = null,
    val build: String? = null,
    /**
     * Nick the player is seen under inside the game. Only sent when it differs
     * from the Millida nick, which is the case on a Microsoft licence.
     */
    val gameNick: String? = null,
    val lastSeen: Long? = null,
    val unread: Int = 0,
    val lastMessageAt: Long? = null,
)

data class FriendRequestCard(
    val id: String,
    val userId: String,
    val nickname: String,
    val avatarUrl: String? = null,
)

data class FriendRequests(
    val incoming: List<FriendRequestCard> = emptyList(),
    val outgoing: List<FriendRequestCard> = emptyList(),
)

data class FoundPlayer(
    val userId: String,
    val nickname: String,
    val avatarUrl: String? = null,
    val isFriend: Boolean = false,
    val pending: Boolean = false,
    val online: Boolean = false,
    val text: String = "",
)

data class BlockedPlayer(
    val blockedId: String,
    val nickname: String = "",
    val avatarUrl: String? = null,
)

data class RoomMember(
    val userId: String,
    val nickname: String,
    val role: String = "",
    val avatarUrl: String? = null,
)

data class FriendRoom(
    val id: String,
    val title: String,
    val ownerId: String = "",
    val members: List<RoomMember> = emptyList(),
    val unread: Int = 0,
    val lastMessageAt: Long? = null,
)

enum class DialogKind { DM, ROOM }

/**
 * One row of the conversation list. The site keys a thread as
 * `friends:<userId>` / `friends:room-<id>`; the same key is what a web deep
 * link expects, so it is carried here instead of being rebuilt on the screen.
 */
data class Dialog(
    val kind: DialogKind,
    val id: String,
    val title: String,
    val subtitle: String = "",
    val avatarUrl: String? = null,
    val unread: Int = 0,
    val lastMessageAt: Long? = null,
    val online: Boolean = false,
) {
    val threadKey: String get() = if (kind == DialogKind.ROOM) "friends:room-$id" else "friends:$id"
}

data class ChatAttachment(
    val url: String,
    val kind: String,
    val name: String = "",
    val durationMs: Long? = null,
    val peaks: List<Int> = emptyList(),
)

data class ChatReaction(val emoji: String, val count: Int, val mine: Boolean = false)

data class ChatReplyPreview(
    val id: String,
    val me: Boolean = false,
    val from: String = "",
    val text: String = "",
    val deleted: Boolean = false,
    val kind: String? = null,
)

data class ChatMessage(
    val id: String,
    val text: String,
    val me: Boolean = false,
    val ts: Long = 0,
    val from: String = "",
    val fromNick: String = "",
    val editedAt: Long? = null,
    val deleted: Boolean = false,
    val attachment: ChatAttachment? = null,
    val replyTo: ChatReplyPreview? = null,
    val reactions: List<ChatReaction> = emptyList(),
)

data class ChatPage(
    val messages: List<ChatMessage> = emptyList(),
    val hasMore: Boolean = false,
    val peerReadAt: Long? = null,
)

sealed class ChatTarget {
    data class Dm(val userId: String) : ChatTarget()
    data class Room(val roomId: String) : ChatTarget()
}

/** Answer of `/friends/request`: the server may accept it outright. */
enum class RequestOutcome { SENT, ACCEPTED, ALREADY_FRIENDS }

data class ThreadMessage(val target: ChatTarget, val message: ChatMessage)

data class PeerRead(val userId: String, val readAt: Long)

/**
 * One answer of the delta poll. [hasPresence] and [hasRequests] tell an empty
 * list from a missing one: a missing block must not wipe what is on screen.
 */
data class FriendsPoll(
    val now: Long,
    val friends: List<Friend> = emptyList(),
    val requests: FriendRequests = FriendRequests(),
    val hasPresence: Boolean = false,
    val hasRequests: Boolean = false,
    val incoming: List<ThreadMessage> = emptyList(),
    val updates: List<ThreadMessage> = emptyList(),
    val reads: List<PeerRead> = emptyList(),
    val nextPollMs: Long? = null,
    val waited: Boolean = false,
    val typing: List<String> = emptyList(),
    val roomTyping: Map<String, List<String>> = emptyMap(),
)

data class RoomsPage(val me: String, val rooms: List<FriendRoom>)

/** What `/friends/profile` tells about a friend; hours are null when hidden or never played. */
data class FriendProfileCard(
    val userId: String,
    val nickname: String = "",
    val text: String = "",
    val online: Boolean = false,
    val playing: Boolean = false,
    val serverIp: String? = null,
    val serverName: String? = null,
    val build: String? = null,
    val totalSeconds: Long? = null,
    val sessions: Long? = null,
    val lastBuild: String? = null,
    val lastServer: String? = null,
    val lastServerName: String? = null,
    val lastPlayedAt: Long? = null,
)
