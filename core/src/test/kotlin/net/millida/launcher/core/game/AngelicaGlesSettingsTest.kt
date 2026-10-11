package net.millida.launcher.core.game

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class AngelicaGlesSettingsTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val packOptions = """
        {
          "quality": { "texture_filter_mode": "RGSS" },
          "advanced": {
            "multi_draw_mode": "INDIRECT",
            "streaming_upload_strategy": "MAP_BUFFER_RANGE",
            "use_vertex_array_objects": true
          },
          "performance": { "use_no_error_g_l_context": true, "chunk_builder_threads": 0 }
        }
    """.trimIndent()

    private fun applied(text: String?): JsonObject {
        val dir = folder.newFolder()
        if (text != null) File(dir, AngelicaGlesSettings.FILE_NAME).writeText(text)
        AngelicaGlesSettings.apply(dir)
        return Json.parseToJsonElement(File(dir, AngelicaGlesSettings.FILE_NAME).readText()).jsonObject
    }

    private fun JsonObject.value(section: String, key: String): String? =
        this[section]?.jsonObject?.get(key)?.jsonPrimitive?.content

    @Test
    fun `настройки Angelica переводятся на GLES и не трогают остальное`() {
        data class Case(val input: String?, val section: String, val key: String, val expected: String, val why: String)

        val cases = listOf(
            Case(packOptions, "advanced", "multi_draw_mode", "INDIVIDUAL", "INDIRECT без GL 4.3 падает в DIRECT, а glMultiDrawElementsBaseVertex в GLES 3.2 нет — мир не рисуется"),
            Case(packOptions.replace("INDIRECT", "DIRECT"), "advanced", "multi_draw_mode", "INDIVIDUAL", "DIRECT зовёт ту же функцию, которой нет в GLES 3.2"),
            Case(packOptions, "performance", "use_no_error_g_l_context", "false", "ошибки GL должны доходить до журнала"),
            Case(packOptions, "advanced", "streaming_upload_strategy", "MAP_BUFFER_RANGE", "glMapBufferRange есть в GLES 3.0 — выбор сборки остаётся"),
            Case(packOptions, "advanced", "use_vertex_array_objects", "true", "соседний ключ раздела не теряется при слиянии"),
            Case(packOptions, "quality", "texture_filter_mode", "RGSS", "раздел без замен переносится как есть"),
            Case(packOptions, "performance", "chunk_builder_threads", "0", "выбор игрока в том же разделе сохраняется"),
            Case(null, "advanced", "multi_draw_mode", "INDIVIDUAL", "без файла Angelica взяла бы DIRECT по умолчанию — файл создаётся"),
            Case("{ битый json", "advanced", "multi_draw_mode", "INDIVIDUAL", "нечитаемый файл Angelica всё равно заменит умолчаниями"),
            Case("""{ "advanced": 5 }""", "advanced", "multi_draw_mode", "INDIVIDUAL", "раздел не объектом заменяется объектом"),
        )
        for (case in cases) {
            assertEquals(case.why, case.expected, applied(case.input).value(case.section, case.key))
        }
    }

    @Test
    fun `уже переведённый файл не переписывается`() {
        val dir = folder.newFolder()
        assertTrue("первый проход пишет замены", AngelicaGlesSettings.apply(dir))
        val file = File(dir, AngelicaGlesSettings.FILE_NAME)
        val before = file.readText()
        assertFalse("второй проход ничего не меняет — лишняя запись на каждом запуске не нужна", AngelicaGlesSettings.apply(dir))
        assertEquals("содержимое не тронуто", before, file.readText())
    }
}
