package net.millida.launcher.ui.shop

import net.millida.launcher.RevealCard
import net.millida.launcher.RewardKind
import net.millida.launcher.ui.Rarity
import net.millida.launcher.ui.shop.chest.BbInterpolation
import net.millida.launcher.ui.shop.chest.BbKeyframe
import net.millida.launcher.ui.shop.chest.BbModelError
import net.millida.launcher.ui.shop.chest.EulerOrder
import net.millida.launcher.ui.shop.chest.Mat4
import net.millida.launcher.ui.shop.chest.chestModelFile
import net.millida.launcher.ui.shop.chest.chestRigModel
import net.millida.launcher.ui.shop.chest.chestShotName
import net.millida.launcher.ui.shop.chest.findBone
import net.millida.launcher.ui.shop.chest.parseBbModel
import net.millida.launcher.ui.shop.chest.sampleTrack
import net.millida.launcher.ui.shop.chest.tintPixels
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ChestModelTest {
    private fun asset(name: String): File = listOf(File("src/main/assets/chests/$name"), File("app/src/main/assets/chests/$name")).first { it.exists() }

    @Test
    fun `каждый уровень сундука носит свою модель с ПК и она читается`() {
        data class Case(val tier: String, val file: String, val why: String)
        listOf(
            Case("COMMON", "copper", "обычный — медный"),
            Case("RARE", "diamond", "редкий — алмазный"),
            Case("EPIC", "obsidian", "эпический — обсидиановый"),
            Case("LEGEND", "netherite", "легендарный — своя незеритовая модель, не перекраска"),
            Case("LEGENDARY", "netherite", "полное имя легендарного от службы"),
            Case("", "copper", "незнакомый уровень рисуется обычным, а не пустотой"),
        ).forEach { case ->
            assertEquals("${case.tier}: ${case.why}", case.file, chestModelFile(case.tier))
            val rig = chestRigModel(parseBbModel(asset(case.file + ".bbmodel").readText()))
            assertTrue("${case.file}: без кубов сундук невидим", rig.model.roots.isNotEmpty())
            assertEquals("${case.file}: у модели две текстуры, как в Blockbench", 2, rig.model.textures.size)
            assertNotNull("${case.file}: без клипа покоя сундук не дышит", rig.idle)
            assertNotNull("${case.file}: без клипа открытия крышка не откинется", rig.open)
            assertNotNull("${case.file}: крышка (chest_up) нужна для приоткрытия", findBone(rig.model.roots, "chest_up"))
            assertTrue("${case.file}: карточки ждут, пока крышка пройдёт 45°; lidAt=${rig.lidAt}", rig.lidAt > 0f && rig.lidAt < rig.open!!.length)
        }
    }

    @Test
    fun `битая модель отклоняется, а не рисует мусор`() {
        data class Case(val text: String, val why: String)
        listOf(
            Case("{}", "нет elements/outliner/textures"),
            Case("not json", "не json вовсе"),
            Case("""{"elements":[],"outliner":[],"textures":[{"source":"http://x/y.png"}]}""", "текстура не встроена — внешний адрес не грузим"),
            Case("""{"elements":[{"uuid":"a","from":[0,0],"to":[1,1,1]}],"outliner":["a"],"textures":[]}""", "вектор не из трёх чисел"),
        ).forEach { case ->
            assertThrows(case.why, BbModelError::class.java) { parseBbModel(case.text) }
        }
    }

    @Test
    fun `ключи анимации держатся за краями и проходят через свои значения`() {
        val keys = listOf(
            BbKeyframe(0f, floatArrayOf(0f, 0f, 0f), BbInterpolation.CATMULLROM),
            BbKeyframe(1f, floatArrayOf(-90f, 0f, 0f), BbInterpolation.CATMULLROM),
            BbKeyframe(2f, floatArrayOf(-80f, 0f, 0f), BbInterpolation.LINEAR),
        )
        assertEquals("до первого ключа — значение первого", 0f, sampleTrack(keys, -1f)[0], 1e-4f)
        assertEquals("после последнего — значение последнего", -80f, sampleTrack(keys, 5f)[0], 1e-4f)
        assertEquals("в ключе — ровно его значение", -90f, sampleTrack(keys, 1f)[0], 1e-3f)
        val mid = sampleTrack(keys, 0.5f)[0]
        assertTrue("между ключами кривая идёт между значениями: $mid", mid < 0f && mid > -100f)
        val step = listOf(BbKeyframe(0f, floatArrayOf(1f, 2f, 3f), BbInterpolation.STEP), BbKeyframe(1f, floatArrayOf(9f, 9f, 9f), BbInterpolation.STEP))
        assertEquals("ступенька держит значение до следующего ключа", 1f, sampleTrack(step, 0.9f)[0], 1e-6f)
    }

    @Test
    fun `нормали остаются перпендикулярными при сплющивании`() {
        val rot = Mat4.compose(0f, 0f, 0f, 0.3f, -0.5f, 0.2f, 1f, 1f, 1f, EulerOrder.ZYX)
        val n = Mat4.normalMatrix(rot)
        for (col in 0 until 3) for (row in 0 until 3) {
            assertEquals("у чистого поворота матрица нормалей — сам поворот", rot[col * 4 + row], n[col * 3 + row], 1e-5f)
        }
        val squash = Mat4.compose(0f, 0f, 0f, 0f, 0f, 0f, 2f, 0.5f, 1f)
        val s = Mat4.normalMatrix(squash)
        assertEquals("сжатие по y вытягивает нормаль по y, иначе свет на сплющенном сундуке врёт", 2f, s[4], 1e-5f)
        assertEquals("растяжение по x сжимает нормаль по x", 0.5f, s[0], 1e-5f)
    }

    @Test
    fun `перекраска ящика меняет оттенок и не трогает прозрачность`() {
        val px = intArrayOf(0x00000000, 0xFF808080.toInt(), 0xFF2C5BD1.toInt())
        tintPixels(px, 0xFF6A1A)
        assertEquals("прозрачный пиксель остаётся прозрачным — иначе у сундука вырастет фон", 0, px[0])
        assertEquals("серый остаётся непрозрачным", 0xFF, px[1] ushr 24)
        val r = (px[2] shr 16) and 255
        val b = px[2] and 255
        assertTrue("синий корпус становится оранжевым у «Пламени»: r=$r b=$b", r > b)
    }

    @Test
    fun `снимок сундука выбирается по уровню и цвету ящика`() {
        assertEquals("ящик «Пламя» — редкий оранжевый", "rare-ff6a1a", chestShotName("RARE", 0xFF6A1A))
        assertEquals("без цвета — снимок уровня", "legend", chestShotName("LEGENDARY", null))
        assertEquals("ведущие нули цвета не теряются", "common-0a0b0c", chestShotName("COMMON", 0x0A0B0C))
    }

    private fun item(rarity: Rarity) = RevealCard(RewardKind.ITEM, rarity = rarity.name, item = net.millida.launcher.core.rubies.RubyItem(code = rarity.name, name = rarity.title, rarity = rarity.name))

    @Test
    fun `лестница редкости растёт от гарантии до выпавшего и не выше`() {
        assertEquals("гарантия редкой, выпала легендарная — три ступени", listOf(Rarity.RARE, Rarity.EPIC, Rarity.LEGENDARY), revealLadder(Rarity.RARE, Rarity.LEGENDARY))
        assertEquals("выпало ниже гарантии — одна ступень, вниз не откатываемся", listOf(Rarity.EPIC), revealLadder(Rarity.EPIC, Rarity.RARE))
        assertEquals("ответа ещё нет — только пол", listOf(Rarity.UNCOMMON), revealLadder(Rarity.UNCOMMON, null))
        val ladder = revealLadder(Rarity.RARE, Rarity.LEGENDARY)
        assertEquals("первый удар из трёх ещё на полу", 0, ladderAt(ladder, 1, 4))
        assertEquals("последний удар — всегда итог", 2, ladderAt(ladder, 4, 4))
        assertEquals("до ударов — пол", 0, ladderAt(ladder, 0, 4))
    }

    @Test
    fun `в x10 лучшая вещь открывается последней`() {
        val opens = listOf(
            RevealOpen(listOf(item(Rarity.RARE)), OPEN_ITEM, Rarity.RARE),
            RevealOpen(listOf(RevealCard(RewardKind.SHARDS, amount = 5)), "shards", null),
            RevealOpen(listOf(item(Rarity.LEGENDARY)), OPEN_ITEM, Rarity.LEGENDARY),
            RevealOpen(listOf(item(Rarity.EPIC)), OPEN_ITEM, Rarity.EPIC),
        )
        assertEquals("легендарная (индекс 2) уходит в конец, остальные — как пришли", listOf(0, 1, 3, 2), dealOrder(opens))
        assertEquals("итог роста — лучшая вещь десятки", Rarity.LEGENDARY, finalRarity(opens))
        val cash = listOf(RevealOpen(listOf(RevealCard(RewardKind.RUBIES, amount = 3)), "rubies", null))
        assertEquals("без вещей порядок не меняется", listOf(0), dealOrder(cash))
    }

    @Test
    fun `после ударов без ответа 15 с — «Сервер не ответил»`() {
        assertEquals("удары не кончились — «Жми!»", HitView.Tap, hitView(1, 3, answered = false, late = true))
        assertEquals("удары кончились, ответ в пути — ждём", HitView.Wait, hitView(3, 3, answered = false, late = false))
        assertEquals("удары кончились, 15 с без ответа — повторить или закрыть", HitView.Stalled, hitView(3, 3, answered = false, late = true))
        assertEquals("ответ пришёл — заминки нет", HitView.Wait, hitView(3, 3, answered = true, late = true))
        assertEquals("ожидание ответа — 15 секунд, как на ПК", 15_000L, ANSWER_TIMEOUT_MS)
    }

    @Test
    fun `сундук берёт число ударов по уровню, пока служба не ответила`() {
        assertEquals("обычный — 3", 3, chestHits("COMMON"))
        assertEquals("редкий — 4", 4, chestHits("RARE"))
        assertEquals("эпический — 5", 5, chestHits("EPIC"))
        assertEquals("легендарный — 5", 5, chestHits("LEGEND"))
    }

    @Test
    fun `бесплатный первый ящик растёт не ниже редкой`() {
        val case = net.millida.launcher.core.rubies.ShopCase(id = "cozy", guarantee = net.millida.launcher.core.rubies.CaseGuarantee("UNCOMMON", "Необычная+"))
        assertEquals("платный — с гарантии ящика", Rarity.UNCOMMON, caseFloor(case, net.millida.launcher.core.rubies.CaseMode.ONE))
        assertEquals("бесплатный — служба даёт не ниже редкой", Rarity.RARE, caseFloor(case, net.millida.launcher.core.rubies.CaseMode.FREE))
        assertEquals("без гарантии лестницы нет", null, caseFloor(case.copy(guarantee = null), net.millida.launcher.core.rubies.CaseMode.ONE))
    }
}
