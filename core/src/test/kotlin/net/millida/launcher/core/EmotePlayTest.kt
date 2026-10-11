package net.millida.launcher.core

import net.millida.launcher.core.cosmetics.AnimationClip
import net.millida.launcher.core.cosmetics.CosmeticItem
import net.millida.launcher.core.cosmetics.DuoEmotes
import net.millida.launcher.core.cosmetics.DuoRole
import net.millida.launcher.core.cosmetics.EmotePlay
import net.millida.launcher.core.cosmetics.EmoteSequence
import net.millida.launcher.core.cosmetics.FigurePose
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EmotePlayTest {

    private fun clip(name: String, length: Float, endless: Boolean = false) =
        AnimationClip(name = name, length = length, loop = true, endless = endless, bones = emptyMap())

    private data class ShowCase(val intro: Float?, val loop: Float, val seconds: Float, val why: String)

    @Test
    fun `эмоция в лобби показывается вступлением и петлями в границах ПК`() {
        val cases = listOf(
            ShowCase(null, 1f, 3f, "секундная петля крутится трижды, чтобы её успели разглядеть"),
            ShowCase(null, 0.5f, 3f, "короткая петля добирает до трёх секунд"),
            ShowCase(null, 2.4f, 4.8f, "петлю не обрывают на середине: две целые петли"),
            ShowCase(null, 10f, 8f, "длинная эмоция не держит лобби дольше восьми секунд"),
            ShowCase(null, 0f, 3f, "петля нулевой длины считается секундной, а не бесконечной"),
            ShowCase(2f, 2f, 6f, "вступление плюс две петли"),
            ShowCase(6f, 1f, 8f, "долгое вступление тоже упирается в потолок"),
        )
        for (case in cases) {
            val sequence = EmoteSequence(case.intro?.let { clip("start", it) }, clip("loop", case.loop, endless = true))
            val shown = EmotePlay(sequence, skeleton = null).showSeconds()
            assertEquals("вступление ${case.intro}, петля ${case.loop}: ${case.why}", case.seconds, shown, 0.001f)
        }
    }

    @Test
    fun `клип без костей не двигает фигуру и сбрасывает прошлую позу`() {
        val pose = FigurePose()
        pose.rotation[0] = 1f
        pose.shift[2] = 5f
        pose.posed[0] = true
        pose.lift = 3f
        pose.lifted = true

        EmotePlay(EmoteSequence(null, clip("idle", 1f)), skeleton = null).pose(0.5f, pose)

        assertTrue("поворот прошлого кадра не остаётся на фигуре", pose.rotation.all { it == 0f })
        assertTrue("сдвиг прошлого кадра не остаётся", pose.shift.all { it == 0f })
        assertFalse("ни одна часть не помечена позированной", pose.posed.any { it })
        assertFalse("без корня фигура не подпрыгивает", pose.lifted)
        assertEquals(0f, pose.lift, 0f)
        assertEquals("на каждую часть по три оси", FigurePose.PARTS * 3, pose.rotation.size)
    }

    @Test
    fun `парные эмоции узнаются по коду и называют клип своей роли`() {
        assertTrue(DuoEmotes.isDuo("DUO_HUG"))
        assertFalse("регистр кода важен — как в моде", DuoEmotes.isDuo("duo_hug"))
        assertFalse("одиночная эмоция — не парная", DuoEmotes.isDuo("WAVE"))
        assertFalse("null — не парная", DuoEmotes.isDuo(null))

        val hug = DuoEmotes.sceneOf(CosmeticItem(id = "DUO_HUG", name = "Обнимашки", slot = "emote"))
        assertEquals("animation.duo.hug.a", DuoEmotes.clipName(hug!!, DuoRole.A))
        assertEquals("animation.duo.hug.b", DuoEmotes.clipName(hug, DuoRole.B))
        assertNull("одиночная вещь сцены не имеет", DuoEmotes.sceneOf(CosmeticItem(id = "WAVE", name = "Привет", slot = "emote")))
        assertNull("нет вещи — нет сцены", DuoEmotes.sceneOf(null))
    }

    @Test
    fun `сцены парных эмоций стоят на разумном расстоянии`() {
        for ((code, scene) in DuoEmotes.SCENES) {
            assertTrue("$code: код парной эмоции начинается с DUO_", code.startsWith("DUO_"))
            assertTrue("$code: фигуры не стоят друг в друге и не разъезжаются дальше двух блоков", scene.distance > 0f && scene.distance <= 2f)
            assertTrue("$code: имя клипа без точек, иначе путь animation.duo.* сломается", '.' !in scene.clip && scene.clip.isNotBlank())
            assertTrue("$code: вращение B идёт вперёд во времени", scene.spinTo >= scene.spinFrom)
        }
        val clips = DuoEmotes.SCENES.values.map { it.clip }
        assertEquals("у каждой сцены свой клип", clips.size, clips.toSet().size)
        val selfie = DuoEmotes.SCENES.getValue("DUO_SELFIE")
        assertEquals("на селфи фигуры повёрнуты навстречу камере", -90f, selfie.turnA, 0f)
        assertEquals(90f, selfie.turnB, 0f)
        assertEquals("в кружении B делает два оборота", 720f, DuoEmotes.SCENES.getValue("DUO_TWIRL").spinB, 0f)
    }
}
