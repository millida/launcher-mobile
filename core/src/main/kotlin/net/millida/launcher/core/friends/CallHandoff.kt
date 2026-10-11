package net.millida.launcher.core.friends

import java.net.URLEncoder

/**
 * The way out when the phone cannot call by itself: the voice engine failed to
 * load on this device. The conversation is then handed to the site, opening
 * the very thread the call button lives in.
 *
 * The thread key is the site's own (`friends:<userId>`, `friends:room-<id>`):
 * the same key its notifications and deep links already use, so a link built
 * here keeps working as long as the site's own links do.
 */
object CallHandoff {

    const val WEB_BASE = "https://millida.net"

    fun threadUrl(target: ChatTarget, base: String = WEB_BASE): String {
        val key = when (target) {
            is ChatTarget.Dm -> "friends:" + requireId(target.userId)
            is ChatTarget.Room -> "friends:room-" + requireId(target.roomId)
        }
        return base.trimEnd('/') + "/chats?tab=friends&t=" + URLEncoder.encode(key, "UTF-8")
    }

    /** Shown next to the call button so the handover is not a surprise. */
    const val HINT = "Звонок откроется на сайте Millida — разговор идёт там"
}
