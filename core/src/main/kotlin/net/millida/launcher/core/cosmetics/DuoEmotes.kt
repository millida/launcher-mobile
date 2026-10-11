package net.millida.launcher.core.cosmetics

/**
 * Where the two figures of a paired emote stand, copied from the mod's
 * emotes/duo.json ("millida_scenes") as the desktop launcher does
 * (src/lib/duoEmotes.ts): distance in blocks from A to B, turns in degrees,
 * and B's extra spin between two moments of the clip.
 */
data class DuoScene(
    val clip: String,
    val distance: Float,
    val turnA: Float = 0f,
    val turnB: Float = 0f,
    val spinB: Float = 0f,
    val spinFrom: Float = 0f,
    val spinTo: Float = 0f,
)

enum class DuoRole(val wire: String) { A("a"), B("b") }

object DuoEmotes {
    val SCENES: Map<String, DuoScene> = linkedMapOf(
        "DUO_HIGH_FIVE" to DuoScene("high_five", 1.0f),
        "DUO_HUG" to DuoScene("hug", 0.65f),
        "DUO_FIST_BUMP" to DuoScene("fist_bump", 0.95f),
        "DUO_HANDSHAKE" to DuoScene("handshake", 0.85f),
        "DUO_BOW" to DuoScene("bow", 1.5f),
        "DUO_SELFIE" to DuoScene("selfie", 0.7f, turnA = -90f, turnB = 90f),
        "DUO_DANCE" to DuoScene("dance", 1.1f),
        "DUO_WALTZ" to DuoScene("waltz", 0.6f),
        "DUO_TWIRL" to DuoScene("twirl", 0.7f, spinB = 720f, spinFrom = 0.5f, spinTo = 2.5f),
        "DUO_SWORD_DUEL" to DuoScene("sword_duel", 2.0f),
    )

    fun sceneOf(item: CosmeticItem?): DuoScene? = item?.let { SCENES[it.id] }

    fun isDuo(code: String?): Boolean = code != null && code in SCENES

    fun clipName(scene: DuoScene, role: DuoRole): String = "animation.duo.${scene.clip}.${role.wire}"
}
