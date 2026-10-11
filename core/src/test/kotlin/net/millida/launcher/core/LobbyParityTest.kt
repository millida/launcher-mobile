package net.millida.launcher.core

import net.millida.launcher.core.cosmetics.EmoteBubble
import net.millida.launcher.core.cosmetics.EmoteBubble.Mood
import net.millida.launcher.core.hub.HubTileCopy
import net.millida.launcher.core.profile.PlayTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The lobby must read the same on the phone as on the desktop launcher: same words, same numbers, same pacing. */
class LobbyParityTest {

    private class MoodCase(val why: String, val name: String, val want: Mood)

    @Test
    fun `настроение эмоции как у десктопа`() {
        val cases = listOf(
            MoodCase("неизвестная эмоция весёлая, как и на десктопе", "DAB", Mood.HAPPY),
            MoodCase("поцелуй ведёт к любовному смайлу", "Воздушный поцелуй KISS", Mood.LOVE),
            MoodCase("плач — грустный смайл", "Cry baby", Mood.SAD),
            MoodCase("грусть проверяется раньше любви: «sad heart» грустная", "sad heart", Mood.SAD),
            MoodCase("злость ловится и по-русски", "Злой топот", Mood.ANGRY),
            MoodCase("«look» — задумчивая", "LOOK_AROUND", Mood.THINK),
            MoodCase("крутость", "Swag walk", Mood.COOL),
            MoodCase("«спать» — сонная, а «спасибо» — нет", "Пора спать", Mood.SLEEPY),
            MoodCase("«спасибо» не путается со сном", "Спасибо", Mood.HAPPY),
            MoodCase("удивление", "Shock", Mood.WOW),
        )
        cases.forEach { assertEquals(it.why, it.want, EmoteBubble.mood(it.name)) }
    }

    @Test
    fun `смайл облачка не повторяет прошлый и берётся из набора настроения`() {
        Mood.entries.forEach { mood ->
            val pool = EmoteBubble.emojiPool(mood)
            pool.forEach { last ->
                repeat(pool.size) { roll ->
                    val id = EmoteBubble.emoji(mood, last) { roll % it }
                    assertTrue("смайл $id не из набора $mood — облачко покажет чужое настроение", id in pool)
                    if (pool.size > 1) assertNotEquals("тот же смайл дважды подряд приедается ($mood)", last, id)
                }
            }
        }
    }

    private class GateCase(val why: String, val now: Long, val nextAt: Long, val roll: Double, val want: Boolean)

    @Test
    fun `облачко не чаще раза в 8-15 секунд и не на каждую эмоцию`() {
        val cases = listOf(
            GateCase("пауза ещё не прошла — облачка нет", now = 5_000, nextAt = 10_000, roll = 0.0, want = false),
            GateCase("пауза прошла и кубик выпал — облачко есть", now = 10_000, nextAt = 10_000, roll = 0.75, want = true),
            GateCase("пауза прошла, но кубик против — четверть эмоций без облачка", now = 20_000, nextAt = 10_000, roll = 0.76, want = false),
        )
        cases.forEach { assertEquals(it.why, it.want, EmoteBubble.due(it.now, it.nextAt, it.roll)) }
        assertEquals("нижняя граница паузы — 8 секунд", 8_000L, EmoteBubble.nextAt(0, 0.0))
        assertEquals("верхняя граница паузы — 15 секунд", 15_000L, EmoteBubble.nextAt(0, 1.0))
    }

    private class TimeCase(val why: String, val seconds: Long, val want: String)

    @Test
    fun `наигранное время пишется как fmtPlaytime`() {
        val cases = listOf(
            TimeCase("меньше минуты не считаем минутами", 59, "меньше минуты"),
            TimeCase("до часа — только минуты", 45 * 60L, "45 мин"),
            TimeCase("часы с минутами коротко: «12 ч 30 м»", 12 * 3600L + 30 * 60, "12 ч 30 м"),
            TimeCase("ровно час без минут", 3600, "1 ч 0 м"),
            TimeCase("от сотни часов минуты не пишем", 150 * 3600L + 59 * 60, "150 ч"),
            TimeCase("отрицательное не ломает строку", -5, "меньше минуты"),
        )
        cases.forEach { assertEquals(it.why, it.want, PlayTime.format(it.seconds)) }
    }

    private class InGameCase(val why: String, val seconds: Long, val want: String?)

    @Test
    fun `строка под «Играть» — N часов в игре от часа`() {
        val cases = listOf(
            InGameCase("меньше часа — строки нет, это пустяк", 3599, null),
            InGameCase("один час", 3600, "1 час в игре"),
            InGameCase("два часа", 2 * 3600L, "2 часа в игре"),
            InGameCase("пять часов", 5 * 3600L + 1800, "5 часов в игре"),
            InGameCase("двадцать один час — снова «час»", 21 * 3600L, "21 час в игре"),
        )
        cases.forEach { assertEquals(it.why, it.want, PlayTime.inGame(it.seconds)) }
    }

    private class TierCase(val why: String, val seconds: Long, val want: PlayTime.Tier)

    @Test
    fun `ступени часов как playTier`() {
        val cases = listOf(
            TierCase("ноль — путь к первой ступени в 1 час", 0, PlayTime.Tier(0, 1, 0f)),
            TierCase("полпути от 1 до 10 часов", (5.5 * 3600).toLong(), PlayTime.Tier(1, 10, 0.5f)),
            TierCase("ровно 10 часов — ступень взята, дальше 50", 10 * 3600L, PlayTime.Tier(2, 50, 0f)),
            TierCase("все ступени взяты — полоски больше нет", 6000 * 3600L, PlayTime.Tier(9, null, 1f)),
        )
        cases.forEach { assertEquals(it.why, it.want, PlayTime.tier(it.seconds)) }
    }

    private class TileCase(
        val why: String,
        val kind: HubTileCopy.Kind,
        val bonus: Boolean,
        val shopNew: Boolean,
        val skinSeen: Boolean,
        val hosted: Int,
        val want: HubTileCopy.Copy,
    )

    @Test
    fun `плитки лобби как HubTile`() {
        val cases = listOf(
            TileCase("спокойный магазин — одно название", HubTileCopy.Kind.SHOP, false, false, true, 0, HubTileCopy.Copy("Магазин", null, null, false)),
            TileCase("бонус не забран — «Бонус ждёт» и «!»", HubTileCopy.Kind.SHOP, true, true, true, 0, HubTileCopy.Copy("Магазин", "Бонус ждёт", null, true)),
            TileCase("в магазине новое без бонуса — «Есть новое»", HubTileCopy.Kind.SHOP, false, true, true, 0, HubTileCopy.Copy("Магазин", "Есть новое", null, true)),
            TileCase("скин ещё не открывали — зовём загрузить свой", HubTileCopy.Kind.WARDROBE, false, false, false, 0, HubTileCopy.Copy("Мой скин", "Загрузи свой", null, true)),
            TileCase("скин открывали — плитка тихая", HubTileCopy.Kind.WARDROBE, true, true, true, 0, HubTileCopy.Copy("Мой скин", null, null, false)),
            TileCase("своего сервера нет — лента «Бесплатно»", HubTileCopy.Kind.SERVER, false, false, true, 0, HubTileCopy.Copy("Играть с друзьями", null, "Бесплатно", false)),
            TileCase("сервер уже есть — ленты нет", HubTileCopy.Kind.SERVER, false, false, true, 2, HubTileCopy.Copy("Играть с друзьями", null, null, false)),
        )
        cases.forEach { assertEquals(it.why, it.want, HubTileCopy.of(it.kind, it.bonus, it.shopNew, it.skinSeen, it.hosted)) }
    }
}
