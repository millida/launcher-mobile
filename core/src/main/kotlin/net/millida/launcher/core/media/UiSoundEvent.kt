package net.millida.launcher.core.media

enum class UiSoundEvent(
    val id: String,
    val gain: Float,
    val variants: List<String>,
    val fallback: List<String> = emptyList(),
    val rates: List<Float> = listOf(1f),
    val maxSeconds: Float = 0f,
    val minGapMs: Long = 60,
    val ui: Boolean = false,
) {
    Click(
        "click", 0.5f, listOf("note/xylobone.ogg"), listOf("random/click.ogg"),
        rates = listOf(1f, 1.122f, 1.26f, 1.498f, 1.682f), maxSeconds = 0.22f, minGapMs = 45, ui = true,
    ),
    Nav(
        "nav", 0.5f, listOf("note/bit.ogg"), listOf("random/click.ogg"),
        rates = listOf(1.19f), maxSeconds = 0.22f, minGapMs = 45, ui = true,
    ),
    Toggle("toggle", 0.45f, listOf("random/pop.ogg"), maxSeconds = 0.2f, minGapMs = 45, ui = true),
    Open("open", 0.45f, listOf("note/pling.ogg"), rates = listOf(1.335f), maxSeconds = 0.32f, minGapMs = 90, ui = true),
    Close("close", 0.45f, listOf("note/pling.ogg"), maxSeconds = 0.32f, minGapMs = 90, ui = true),
    Notify("notify", 0.75f, listOf("note/bell.ogg"), listOf("note/pling.ogg"), rates = listOf(1.12f), maxSeconds = 0.6f),
    Success("success", 0.6f, listOf("random/orb.ogg"), maxSeconds = 0.6f),
    Install("install", 0.55f, listOf("note/icechime.ogg"), listOf("random/orb.ogg"), maxSeconds = 0.7f),
    Achievement("achievement", 0.6f, listOf("random/levelup.ogg")),
    Error("error", 0.6f, listOf("note/bass.ogg"), listOf("note/didgeridoo.ogg"), maxSeconds = 0.4f),
    Delete("delete", 0.5f, listOf("item/bundle/remove_one1.ogg"), listOf("random/pop.ogg"), maxSeconds = 0.3f),
    Login("login", 0.6f, listOf("note/flute.ogg"), listOf("ui/toast/in.ogg"), rates = listOf(1.26f), maxSeconds = 0.6f),
    Launch("launch", 0.5f, listOf("random/levelup.ogg")),
    Crash("crash", 0.45f, listOf("note/didgeridoo.ogg"), listOf("note/bass.ogg")),
    ChestHit("chest_hit", 0.7f, listOf("dig/wood1.ogg", "dig/wood2.ogg"), minGapMs = 40),
    ChestCrack("chest_crack", 0.55f, listOf("random/break.ogg"), minGapMs = 40),
    ChestOpen("chest_open", 0.7f, listOf("random/chestopen.ogg"), minGapMs = 40),
    ChestCard("chest_card", 0.55f, listOf("random/orb.ogg"), minGapMs = 40),
    ChestRare("chest_rare", 0.6f, listOf("block/amethyst/break1.ogg"), listOf("random/orb.ogg"), minGapMs = 40),
    // Android's SoundPool keeps at most 1 MB of decoded audio per sample and cuts this stereo
    // fanfare near 5.4 s, so it fades out just before the cut instead of clicking off.
    ChestEpic(
        "chest_epic", 0.6f, listOf("ui/toast/challenge_complete.ogg"), listOf("random/levelup.ogg"),
        maxSeconds = 5.2f, minGapMs = 40,
    ),
    ChestGold("chest_gold", 0.6f, listOf("item/armor/equip_gold1.ogg"), listOf("random/orb.ogg"), minGapMs = 40);

    companion object {
        fun byId(id: String): UiSoundEvent? = entries.firstOrNull { it.id == id }
    }
}
