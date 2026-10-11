package net.millida.launcher.core

import kotlinx.serialization.encodeToString
import net.millida.launcher.core.hud.HudAction
import net.millida.launcher.core.hud.HudControl
import net.millida.launcher.core.hud.HudKeys
import net.millida.launcher.core.hud.HudKind
import net.millida.launcher.core.hud.HudLayout
import net.millida.launcher.core.hud.HudPresets
import net.millida.launcher.core.hud.HudProfiles
import net.millida.launcher.core.hud.HudStore
import net.millida.launcher.core.hud.HudTransfer
import net.millida.launcher.core.hud.HudVisibility
import net.millida.launcher.core.mojang.MojangJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * The new controls replace what players have been arranging for months. The
 * old arrangement must survive the update as a profile one tap away, and a
 * layout pasted from a stranger must not be able to break the screen.
 */
class HudProfilesTest {

    @get:Rule
    val temp = TemporaryFolder()

    private fun store(): Pair<GameLayout, HudStore> {
        val game = GameLayout(temp.newFolder()).also { it.ensure() }
        return game to HudStore(game)
    }

    @Test
    fun `файл старого лаунчера становится профилем, а активной встаёт раскладка как в Bedrock`() {
        data class Case(val text: String, val active: String, val names: List<String>, val note: Boolean, val why: String)

        val old = MojangJson.encodeToString(HudPresets.stick().withOpacity(0.5f))
        val cases = listOf(
            Case(old, HudPresets.BEDROCK, listOf(HudPresets.BEDROCK, HudProfiles.LEGACY), true,
                "файл без версии — это одна раскладка старой сборки, её нельзя потерять и надо предложить вернуть"),
            Case("""{"name":"x","controls":[]}""", HudPresets.BEDROCK, listOf(HudPresets.BEDROCK), false,
                "пустая старая раскладка ничего не хранит, предлагать её вернуть незачем"),
            Case("{ битый", HudPresets.BEDROCK, listOf(HudPresets.BEDROCK), false,
                "битый файл не оставляет игрока без управления"),
            Case("""{"version":2,"active":"Нет такого","profiles":{"Моя":${MojangJson.encodeToString(HudPresets.minimal())}}}""",
                "Моя", listOf("Моя"), false,
                "активный профиль, которого нет в файле, не должен оставлять пустой экран"),
            Case("""{"version":2,"profiles":{}}""", HudPresets.BEDROCK, listOf(HudPresets.BEDROCK), false,
                "файл новой версии без профилей даёт раскладку по умолчанию"),
        )

        for (case in cases) {
            val (game, store) = store()
            File(game.root, "hud.json").writeText(case.text)
            val loaded = store.load()
            assertEquals("активный профиль: ${case.why}", case.active, loaded.activeName)
            assertEquals("список профилей: ${case.why}", case.names, loaded.names)
            assertEquals("подсказка о старом управлении: ${case.why}", case.note, loaded.legacyNote)
        }
    }

    @Test
    fun `перенесённая старая раскладка сохраняет всё, что игрок настроил`() {
        val (game, store) = store()
        val old = HudPresets.stick().withOpacity(0.5f).withLookTap(HudKeys.MOUSE_LEFT)
        File(game.root, "hud.json").writeText(MojangJson.encodeToString(old))

        val migrated = store.load()
        assertEquals(
            "старая раскладка должна переехать целиком — с прозрачностью, касанием и каждой кнопкой",
            old,
            migrated.profiles[HudProfiles.LEGACY],
        )

        store.save(migrated)
        val again = HudStore(game).load()
        assertEquals("сохранённые профили не должны снова приниматься за старый файл", migrated, again)

        val restored = again.restoreLegacy()
        assertEquals("кнопка «вернуть старое» включает старую раскладку", old, restored.layout)
        assertFalse("после возврата подсказка больше не показывается", restored.legacyNote)
    }

    @Test
    fun `переключение, копия, переименование и удаление профилей`() {
        data class Case(val step: (HudProfiles) -> HudProfiles, val active: String, val names: List<String>, val why: String)

        val start = HudProfiles.initial()
        val bedrock = HudPresets.BEDROCK
        val cases = listOf(
            Case({ it.switchTo("Нет такого") }, bedrock, listOf(bedrock), "переход на несуществующий профиль ничего не ломает"),
            Case({ it.duplicate() }, "$bedrock копия", listOf(bedrock, "$bedrock копия"), "копия становится активной, чтобы правки не задели оригинал"),
            Case({ it.duplicate().duplicate() }, "$bedrock копия копия", listOf(bedrock, "$bedrock копия", "$bedrock копия копия"), "копия копии получает своё имя"),
            Case({ it.create(bedrock) }, "$bedrock 2", listOf(bedrock, "$bedrock 2"), "одинаковые имена разводятся номером, иначе один профиль затрёт другой"),
            Case({ it.create("Бой").switchTo(bedrock) }, bedrock, listOf(bedrock, "Бой"), "переключение возвращает выбранный профиль"),
            Case({ it.create("Бой").rename("Бой", "ПвП") }, "ПвП", listOf(bedrock, "ПвП"), "переименование активного оставляет его активным"),
            Case({ it.create("Бой").rename("Бой", "   ") }, "Бой", listOf(bedrock, "Бой"), "пустое имя не принимается"),
            Case({ it.create("Бой").rename("Бой", bedrock) }, "$bedrock 2", listOf(bedrock, "$bedrock 2"), "имя занятого профиля разводится номером"),
            Case({ it.create("Бой").delete("Бой") }, bedrock, listOf(bedrock), "удаление активного переводит на оставшийся"),
            Case({ it.delete(bedrock) }, bedrock, listOf(bedrock), "последний профиль удалить нельзя — иначе нечем играть"),
        )

        for (case in cases) {
            val result = case.step(start)
            assertEquals("активный: ${case.why}", case.active, result.activeName)
            assertEquals("профили: ${case.why}", case.names, result.names)
        }
    }

    @Test
    fun `правка раскладки меняет только активный профиль`() {
        val profiles = HudProfiles.initial().create("Бой", HudPresets.buttons())
        val changed = profiles.withLayout(profiles.layout.withOpacity(0.4f))
        assertEquals("правка дошла до активного", 0.4f, changed.profiles.getValue("Бой").opacity, 0.001f)
        assertEquals(
            "соседний профиль не должен меняться от правки другого",
            profiles.profiles[HudPresets.BEDROCK],
            changed.profiles[HudPresets.BEDROCK],
        )
    }

    @Test
    fun `экспорт и импорт возвращают ту же раскладку`() {
        data class Case(val layout: HudLayout, val why: String)

        val tuned = HudPresets.bedrock().let { preset ->
            val jump = preset.controls.first { it.kind == HudKind.JUMP }
            preset.replace(jump.withOpacity(0.6f).withKeys(listOf(HudKeys.SPACE, HudKeys.W)).copy(hidden = true))
                .withHoldDelay(400)
        }
        val cases = listOf(
            Case(HudPresets.bedrock(), "готовая раскладка"),
            Case(tuned, "прозрачность кнопки, клавиши, скрытие и задержка удержания переживают перенос"),
            Case(HudPresets.buttons(), "старые виды кнопок тоже переносятся"),
        )
        for (case in cases) {
            val back = HudTransfer.decode(HudTransfer.encode(case.layout))
            assertEquals(case.why, case.layout, back)
        }
    }

    @Test
    fun `чужой текст из буфера не ломает управление`() {
        data class Case(val text: String, val imported: Boolean, val why: String)

        val huge = "{\"controls\":[" + List(200) { """{"id":"k$it","label":"K","kind":"KEY","code":87}""" }.joinToString(",") + "]}"
        val cases = listOf(
            Case("", false, "пустой буфер — не раскладка"),
            Case("привет", false, "обычный текст — не раскладка"),
            Case("[1,2,3]", false, "массив — не раскладка"),
            Case("""{"controls":[]}""", false, "раскладка без кнопок оставит игрока без управления"),
            Case("""{"controls":[{"id":"x","label":"X","kind":"ROCKET"}]}""", false, "неизвестный вид кнопки не угадывается"),
            Case("""{"controls":[{"id":"x","label":"X","kind":"KEY","code":99999}]}""", false, "кнопка с несуществующей клавишей отбрасывается"),
            Case(huge, true, "слишком много кнопок урезается, а не отвергается целиком"),
            Case("x".repeat(HudTransfer.MAX_TEXT + 1), false, "огромный текст не разбирается вовсе"),
        )
        for (case in cases) {
            val result = HudTransfer.decode(case.text)
            assertEquals(case.why, case.imported, result != null)
        }
        assertEquals(
            "кнопок после импорта не больше предела",
            HudTransfer.MAX_CONTROLS,
            HudTransfer.decode(huge)?.controls?.size,
        )
    }

    @Test
    fun `импорт зажимает значения в рамки редактора`() {
        val text = """
            {"name":"","opacity":-5,"holdDelay":99999,"lookTap":7,"controls":[
              {"id":"a b/../c","label":"${"Д".repeat(100)}","kind":"KEY","code":32,"x":40,"y":-3,"width":9000,"height":1,
               "opacity":0,"codes":[32,99999,87,65,68],"icon":"../../evil"}
            ]}
        """.trimIndent()
        val layout = HudTransfer.decode(text)
        assertNotNull("корректная по форме раскладка принимается", layout)
        layout!!
        val control = layout.controls.single()
        assertTrue("место на экране", control.x in 0.02f..0.98f && control.y in 0.02f..0.98f)
        assertTrue("размер в разумных пределах", control.width <= 400f && control.height >= 16f)
        assertEquals("прозрачность не ниже порога видимости", HudLayout.MIN_OPACITY, control.opacity!!, 0.001f)
        assertEquals("не больше трёх клавиш и только существующие", listOf(32, 87, 65), control.codes)
        assertNull("неизвестная иконка не принимается", control.icon)
        assertEquals("в id нет путей и пробелов", "abc", control.id)
        assertTrue("подпись укорочена", control.label.length <= HudTransfer.MAX_LABEL)
        assertEquals("задержка удержания в пределах настройки", HudLayout.MAX_HOLD_MS, layout.holdDelay)
        assertEquals("неизвестная кнопка касания сводится к удару", HudKeys.MOUSE_LEFT, layout.lookTap)
        assertEquals("пустое имя заменяется", HudProfiles.NEW_NAME, layout.name)
    }

    @Test
    fun `раскладка как в Bedrock содержит то, что обещано`() {
        data class Case(val id: String, val kind: HudKind, val visibility: HudVisibility, val hidden: Boolean, val why: String)

        val preset = HudPresets.bedrock()
        val cases = listOf(
            Case("stick", HudKind.FLOAT_STICK, HudVisibility.LOOK, false, "левый палец ходит там, где коснулся"),
            Case("jump", HudKind.JUMP, HudVisibility.LOOK, false, "прыжок, двойное касание включает полёт"),
            Case("sneak", HudKind.KEY, HudVisibility.WALKING, false, "присесть уступает место кнопке «вниз» в полёте"),
            Case("fly-down", HudKind.KEY, HudVisibility.FLYING, false, "вниз видна только в полёте"),
            Case("menu", HudKind.ACTION, HudVisibility.ALWAYS, false, "меню лаунчера доступно всегда, иначе не выйти из игры"),
            Case("chat", HudKind.ACTION, HudVisibility.LOOK, false, "чат открывается из мира"),
            Case("escape", HudKind.KEY, HudVisibility.CURSOR, false, "открытый экран игры закрывается с экрана"),
            Case("camera", HudKind.KEY, HudVisibility.LOOK, false, "вид от третьего лица"),
            Case("players", HudKind.KEY, HudVisibility.LOOK, true, "список игроков есть, но спрятан"),
            Case("more", HudKind.HOTBAR_MORE, HudVisibility.LOOK, false, "инвентарь открывается кнопкой у хотбара"),
        )
        for (case in cases) {
            val control = preset.controls.firstOrNull { it.id == case.id }
            assertNotNull("нет кнопки «${case.id}»: ${case.why}", control)
            assertEquals("вид «${case.id}»: ${case.why}", case.kind, control!!.kind)
            assertEquals("когда видна «${case.id}»: ${case.why}", case.visibility, control.visibility)
            assertEquals("скрыта ли «${case.id}»: ${case.why}", case.hidden, control.hidden)
        }
        val gone = listOf("hit", "use", "inventory", "keyboard", "mode", "fly-up")
        gone.forEach { id ->
            assertNull("кнопки «$id» нет в новой раскладке: её можно добавить вручную", preset.controls.firstOrNull { it.id == id })
        }
        assertEquals("касание в новой раскладке ставит блок, а не бьёт", HudKeys.MOUSE_RIGHT, preset.lookTap)
        assertEquals("меню открывается иконкой паузы", HudAction.MENU, preset.controls.first { it.id == "menu" }.action)
        assertEquals("кнопка у хотбара открывает инвентарь", listOf(HudKeys.E), preset.controls.first { it.id == "more" }.keys)
    }

    @Test
    fun `кнопка видна ровно в тех состояниях игры, которые выбраны`() {
        data class Case(val visibility: HudVisibility, val look: Boolean, val flying: Boolean, val shown: Boolean, val why: String)

        val cases = listOf(
            Case(HudVisibility.LOOK, true, false, true, "в мире видна"),
            Case(HudVisibility.LOOK, true, true, true, "в мире видна и в полёте"),
            Case(HudVisibility.LOOK, false, false, false, "в меню игры кнопки мира мешают нажимать по экрану"),
            Case(HudVisibility.CURSOR, false, false, true, "только в меню"),
            Case(HudVisibility.CURSOR, true, false, false, "в мире кнопки меню не нужны"),
            Case(HudVisibility.ALWAYS, false, true, true, "всегда — это всегда"),
            Case(HudVisibility.FLYING, true, true, true, "в полёте видна"),
            Case(HudVisibility.FLYING, true, false, false, "на земле кнопка полёта не нужна"),
            Case(HudVisibility.FLYING, false, true, false, "открытый экран прячет и полёт"),
            Case(HudVisibility.WALKING, true, false, true, "на земле видна"),
            Case(HudVisibility.WALKING, true, true, false, "в полёте её место занимает «вниз»"),
        )
        for (case in cases) {
            assertEquals(case.why, case.shown, case.visibility.shown(case.look, case.flying))
        }
    }

    @Test
    fun `перетаскивание в редакторе прилипает к сетке 8 dp`() {
        data class Case(val x: Float, val y: Float, val expectedX: Float, val expectedY: Float, val why: String)

        val control = HudControl("k", "K", HudKind.KEY)
        val cases = listOf(
            Case(101f, 99f, 104f, 96f, "ближайший узел сетки по обеим осям"),
            Case(100f, 100f, 104f, 104f, "ровно посередине между узлами — вверх, как при округлении"),
            Case(320f, 180f, 320f, 184f, "середина экрана остаётся на узле"),
            Case(-50f, 900f, 0.02f * 640f, 0.98f * 360f, "сетка не выталкивает кнопку за экран"),
        )
        for (case in cases) {
            val snapped = control.snappedTo(case.x, case.y, 640f, 360f)
            assertEquals("по горизонтали: ${case.why}", case.expectedX, snapped.x * 640f, 0.01f)
            assertEquals("по вертикали: ${case.why}", case.expectedY, snapped.y * 360f, 0.01f)
        }
    }

    @Test
    fun `переназначение клавиш принимает только настоящие клавиши`() {
        data class Case(val keys: List<Int>, val expected: List<Int>, val why: String)

        val control = HudControl("k", "K", HudKind.KEY, code = HudKeys.SPACE)
        val cases = listOf(
            Case(listOf(HudKeys.W), listOf(HudKeys.W), "одна клавиша заменяет прежнюю"),
            Case(listOf(HudKeys.LEFT_CONTROL, HudKeys.W), listOf(HudKeys.LEFT_CONTROL, HudKeys.W), "сочетание держится вместе"),
            Case(listOf(HudKeys.W, HudKeys.W), listOf(HudKeys.W), "повтор одной клавиши не жмёт её дважды"),
            Case(listOf(-1, 99999), listOf(HudKeys.SPACE), "мусор не стирает рабочую клавишу"),
            Case(listOf(HudKeys.A, HudKeys.S, HudKeys.D, HudKeys.W), listOf(HudKeys.A, HudKeys.S, HudKeys.D), "больше трёх не бывает"),
        )
        for (case in cases) {
            assertEquals(case.why, case.expected, control.withKeys(case.keys).keys)
        }
    }
}
