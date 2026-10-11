package net.millida.launcher.core

import kotlinx.serialization.json.Json
import net.millida.launcher.core.cosmetics.CosmeticAnimation
import net.millida.launcher.core.cosmetics.EmotePart
import net.millida.launcher.core.cosmetics.EmotePlay
import net.millida.launcher.core.cosmetics.EmoteSequence
import net.millida.launcher.core.cosmetics.EmoteSkeleton
import net.millida.launcher.core.cosmetics.FigurePose
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * Signs and timing of clips are the mod's: a wrong sign folds wings forward, a
 * wrong sequence ends an emote on its wind-up before the part it is named after.
 */
class CosmeticAnimationTest {

    private val clips = CosmeticAnimation.read(
        Json.parseToJsonElement(
            """
            {"animations":{
              "animation.wings.idle":{"loop":true,"animation_length":2,"bones":{
                "wing":{"rotation":{"0":[10,20,30],"1":[20,20,30]},"position":[0,4,0]},
                "flap":{"position":{"0":[0,0,0],"1":{"pre":[1,1,1],"post":[5,5,5]},"2":[5,5,5]}},
                "ease":{"position":{"0":{"post":[0,0,0],"lerp_mode":"catmullrom"},"1":[1,1,1]}},
                "molang":{"rotation":"math.sin(q.anim_time)"}
              }},
              "animation.wings.walk":{"bones":{"wing":{"rotation":[0,0,0]}}},
              "animation.empty":{"bones":{}}
            }}
            """.trimIndent(),
        ),
    )

    private val idle = clips.getValue("animation.wings.idle")

    private data class PoseCase(val bone: String, val seconds: Float, val rotation: FloatArray?, val position: FloatArray?, val why: String)

    @Test
    fun `позы костей во времени`() {
        val cases = listOf(
            PoseCase("wing", 0f, floatArrayOf(10f, 20f, 30f), floatArrayOf(0f, -4f, 0f), "углы клипа доходят до кости в знаках файла, высота — вниз"),
            PoseCase("wing", 0.5f, floatArrayOf(15f, 20f, 30f), null, "между ключами — прямая"),
            PoseCase("wing", 2.5f, floatArrayOf(15f, 20f, 30f), null, "зацикленный клип идёт по кругу"),
            PoseCase("flap", 0.5f, null, floatArrayOf(0.5f, -0.5f, 0.5f), "к ключу с pre/post кривая идёт к pre"),
            PoseCase("flap", 1.5f, null, floatArrayOf(5f, -5f, 5f), "после ключа кривая уходит от post — мгновенная смена"),
            PoseCase("ease", 0.25f, null, floatArrayOf(0.15625f, -0.15625f, 0.15625f), "сглаживание задаёт ключ, от которого кривая уходит"),
            PoseCase("molang", 0f, floatArrayOf(0f, 0f, 0f), null, "выражение molang не число — кость остаётся в покое"),
        )
        for (case in cases) {
            val pose = CosmeticAnimation.poseOf(idle, case.bone, case.seconds)
            case.rotation?.let { assertArrayEquals(case.why, it, pose!!.rotation, 0.0001f) }
            case.position?.let { assertArrayEquals(case.why, it, pose!!.position, 0.0001f) }
        }
    }

    @Test
    fun `клип без костей не клип, длина считается по ключам`() {
        assertEquals("пустой клип выпадает", setOf("animation.wings.idle", "animation.wings.walk"), clips.keys)
        assertEquals("без animation_length длина — последний ключ", 0f, clips.getValue("animation.wings.walk").length)
        assertEquals("без флага loop вещь всё равно зациклена", true, clips.getValue("animation.wings.walk").loop)
        assertEquals("без флага loop — не петля эмоции", false, clips.getValue("animation.wings.walk").endless)
    }

    @Test
    fun `выбор клипа`() {
        assertSame("имя из каталога главнее", clips["animation.wings.walk"], CosmeticAnimation.pickClip(clips, "animation.wings.walk"))
        assertSame("без имени — покой, а не первый попавшийся", idle, CosmeticAnimation.pickClip(clips, "нет такого"))
    }

    private data class FramesCase(val image: Pair<Int, Int>, val unwrap: Pair<Float, Float>, val frames: Int, val why: String)

    @Test
    fun `лента кадров`() {
        val cases = listOf(
            FramesCase(64 to 64, 64f to 64f, 1, "картинка размером с развёртку — один кадр"),
            FramesCase(64 to 2048, 64f to 64f, 32, "лента сверху вниз"),
            FramesCase(128 to 128, 64f to 64f, 1, "двойное разрешение — не лента"),
            FramesCase(144 to 2560, 144f to 80f, 32, "кадр высотой с развёртку, даже если лента шире: 18 кадров резали бы кадр пополам"),
        )
        for (case in cases) {
            assertEquals(case.why, case.frames, CosmeticAnimation.atlasFrames(case.image.first, case.image.second, case.unwrap.first, case.unwrap.second))
        }
        assertEquals("лента шире в нецелое число раз — развёртка у левого края", 64f / 96f, CosmeticAnimation.atlasWidthShare(96, 64f), 0.0001f)
        assertEquals("целое число раз — вся ширина", 1f, CosmeticAnimation.atlasWidthShare(128, 64f), 0.0001f)
    }

    private fun emoteClips(json: String) = CosmeticAnimation.read(Json.parseToJsonElement(json))

    @Test
    fun `эмоция идёт вступлением, потом петлёй`() {
        val withStart = emoteClips(
            """{"e.start":{"animation_length":1,"bones":{"body":{"rotation":[0,0,0]}}},
               "e.loop":{"loop":true,"animation_length":2,"bones":{"body":{"rotation":[0,0,0]}}}}""",
        )
        val endless = EmoteSequence.of(withStart, withStart["e.loop"])!!
        assertSame("петля из каталога открывается своим вступлением", withStart["e.start"], endless.clipAt(0.5f))
        assertSame("после вступления — петля", withStart["e.loop"], endless.clipAt(1.5f))
        assertEquals("время петли идёт от конца вступления", 0.5f, endless.timeAt(1.5f), 0.0001f)

        val withBackup = emoteClips(
            """{"e.intro":{"animation_length":1,"bones":{"body":{"rotation":[0,0,0]}}},
               "e.loop_bkp":{"loop":true,"bones":{"body":{"rotation":[0,0,0]}}},
               "e.loop":{"bones":{"body":{"rotation":[0,0,0]}}}}""",
        )
        val intro = EmoteSequence.of(withBackup, withBackup["e.intro"])!!
        assertSame("каталог назвал вступление — за ним идёт петля", withBackup["e.loop"], intro.main)
        assertSame(withBackup["e.intro"], intro.intro)
    }

    @Test
    fun `эмоция без скелета крутит части и поднимает фигуру`() {
        val jump = emoteClips(
            """{"e":{"loop":true,"animation_length":1,"bones":{
                 "arm_right":{"rotation":[90,0,0]},
                 "root":{"position":[0,3,0]}}}}""",
        )
        val play = EmotePlay(EmoteSequence.solo(jump["e"])!!, skeleton = null)
        val pose = FigurePose()
        play.pose(0f, pose)
        val arm = net.millida.launcher.core.cosmetics.EmotePart.RIGHT_ARM.ordinal * 3
        assertEquals("рука поднята на прямой угол", Math.toRadians(90.0).toFloat(), pose.rotation[arm], 0.0001f)
        assertEquals("подъём корня доходит до фигуры, как на ПК", -3f, pose.lift, 0.0001f)
        assertEquals("показ в лобби — не короче 2,5 с", 3f, play.showSeconds(), 0.0001f)
    }

    @Test
    fun `скелет эмоции ведёт руку вслед за корпусом`() {
        val geometry = Json.parseToJsonElement(
            """{"minecraft:geometry":[{"bones":[
                 {"name":"body","pivot":[0,24,0]},
                 {"name":"arm_right","parent":"body","pivot":[-5,22,0]}]}]}""",
        )
        val clips = emoteClips("""{"e":{"loop":true,"animation_length":1,"bones":{"body":{"rotation":[0,90,0]}}}}""")
        val poses = EmoteSkeleton.of(geometry)!!.poseAt(clips.getValue("e"), 0f)
        val arm = poses.getValue(EmotePart.RIGHT_ARM)
        assertEquals("рука наследует поворот корпуса, а не стоит на месте", (Math.PI / 2).toFloat(), arm.turn[1], 0.0001f)
        assertArrayEquals("плечо уезжает вместе с повёрнутым корпусом", floatArrayOf(5f, 0f, 5f), arm.shift, 0.0001f)
    }
}
