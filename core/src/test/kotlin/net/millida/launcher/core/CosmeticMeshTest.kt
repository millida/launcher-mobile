package net.millida.launcher.core

import kotlinx.serialization.json.Json
import net.millida.launcher.core.cosmetics.CosmeticAnchor
import net.millida.launcher.core.cosmetics.CosmeticGeometry
import net.millida.launcher.core.cosmetics.CosmeticPlacement
import net.millida.launcher.core.cosmetics.CosmeticPose
import net.millida.launcher.core.cosmetics.MeshQuad
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The mod reads models by rules taken from the game's bytecode; a launcher that
 * reads them otherwise shows a hat where the game does not put it.
 */
class CosmeticMeshTest {

    private fun model(cube: String, bone: String = """"name":"head","pivot":[0,24,0]"""): String = """
        {"format_version":"1.12.0","minecraft:geometry":[{"description":{"texture_width":64,"texture_height":64},
         "bones":[{$bone,"cubes":[$cube]}]}]}
    """.trimIndent()

    private fun quads(json: String): List<MeshQuad> =
        CosmeticGeometry.read(Json.parseToJsonElement(json))!!.bones.single().quads

    private data class QuadCase(
        val cube: String,
        val count: Int,
        val firstCorner: FloatArray,
        val firstUv: FloatArray,
        val why: String,
    )

    @Test
    fun `кубы, развёртки и зеркало`() {
        val cases = listOf(
            QuadCase(
                """{"origin":[-4,24,-4],"size":[8,8,8],"uv":[0,0]}""",
                6,
                floatArrayOf(4f, -8f, -4f),
                floatArrayOf(0.25f, 0.125f),
                "рост переворачивается: макушка на 32 в файле — это 8 над шеей",
            ),
            QuadCase(
                """{"origin":[-4,24,-4],"size":[8,8,8],"uv":[0,0],"mirror":true}""",
                6,
                floatArrayOf(-4f, 0f, -4f),
                floatArrayOf(0.25f, 0.25f),
                "у зеркального куба обход граней разворачивается вместе с развёрткой",
            ),
            QuadCase(
                """{"origin":[-4,24,-4],"size":[8,8,8],"uv":{"north":{"uv":[10,12],"uv_size":[-4,4]}}}""",
                1,
                floatArrayOf(4f, -8f, -4f),
                floatArrayOf(6f / 64f, 12f / 64f),
                "отрицательный размер грани — зеркало самой картинки, а не отказ",
            ),
            QuadCase(
                """{"origin":[-4,24,-4],"size":[8,8,8],"uv":{"east":{"uv":[0,0]}}}""",
                1,
                floatArrayOf(-4f, -8f, -4f),
                floatArrayOf(8f / 64f, 0f),
                "«east» в файле смотрит в минус x — имена противоположны направлениям игры",
            ),
        )
        for (case in cases) {
            val list = quads(model(case.cube))
            assertEquals(case.why, case.count, list.size)
            assertArrayEquals(case.why, case.firstCorner, list.first().positions.copyOfRange(0, 3), 0.0001f)
            assertArrayEquals(case.why, case.firstUv, list.first().uvs.copyOfRange(0, 2), 0.0001f)
        }
    }

    @Test
    fun `пустой куб и чужой файл не ломают разбор`() {
        assertEquals("куб нулевого размера не рисуется", 0, quads(model("""{"origin":[0,0,0],"size":[0,0,0],"uv":[0,0]}""")).size)
        assertNull("файл без костей — не модель", CosmeticGeometry.read(Json.parseToJsonElement("""{"minecraft:geometry":[{"bones":[]}]}""")))
        assertNull("не объект — не модель", CosmeticGeometry.read(Json.parseToJsonElement("[1,2]")))
    }

    private data class AnchorCase(val bone: String, val anchor: CosmeticAnchor?, val parent: Int, val offset: FloatArray, val why: String)

    @Test
    fun `кости садятся на части игрока как в моде`() {
        val json = """
            {"minecraft:geometry":[{"description":{"texture_width":32,"texture_height":32},"bones":[
              {"name":"body","pivot":[0,24,0]},
              {"name":"wing","parent":"body","pivot":[0,20,2]},
              {"name":"arm_right","pivot":[-5,22,0]},
              {"name":"head","parent":"body","pivot":[0,24,0]},
              {"name":"spark","pivot":[3,30,0]}
            ]}]}
        """.trimIndent()
        val rig = CosmeticPlacement.rig(CosmeticGeometry.read(Json.parseToJsonElement(json))!!)
        val cases = listOf(
            AnchorCase("body", CosmeticAnchor.BODY, -1, floatArrayOf(0f, 0f, 0f), "кость body на корне едет с торсом"),
            AnchorCase("wing", null, 0, floatArrayOf(0f, 4f, 2f), "дочерняя кость меряется от родителя"),
            AnchorCase("arm_right", CosmeticAnchor.RIGHT_ARM, -1, floatArrayOf(0f, 0f, 0f), "крепление руки уже в плече: сдвиг ноль"),
            AnchorCase("head", null, 0, floatArrayOf(0f, 0f, 0f), "head глубже корня принадлежит художнику, а не голове"),
            AnchorCase("spark", CosmeticAnchor.ROOT, -1, floatArrayOf(3f, -6f, 0f), "безымянная кость висит на корне у шеи"),
        )
        for (case in cases) {
            val bone = rig.bones.first { it.name == case.bone }
            assertEquals(case.why, case.anchor, bone.anchor)
            assertEquals(case.why, case.parent, bone.parent)
            assertArrayEquals(case.why, case.offset, bone.offset, 0.0001f)
        }
    }

    @Test
    fun `поза кости наследует родителя`() {
        val json = """
            {"minecraft:geometry":[{"bones":[
              {"name":"body","pivot":[0,24,0],"rotation":[0,90,0]},
              {"name":"tip","parent":"body","pivot":[4,24,0]}
            ]}]}
        """.trimIndent()
        val rig = CosmeticPlacement.rig(CosmeticGeometry.read(Json.parseToJsonElement(json))!!)
        val out = CosmeticPose.buffers(rig)
        CosmeticPose.bake(rig, null, 0f, out)
        val tip = out[1]
        assertNotNull(tip)
        assertArrayEquals(
            "поворот родителя на 90 вокруг y уносит дочернюю кость с оси x на минус z",
            floatArrayOf(0f, 0f, -4f),
            floatArrayOf(tip[3], tip[7], tip[11]),
            0.0001f,
        )
    }
}
