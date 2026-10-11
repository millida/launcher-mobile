package net.millida.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class PackRuntimeRulesTest {

    @Test
    fun `идентификатор рендерера для нативной стороны`() {
        data class Case(val nativeId: String, val library: String, val expected: String, val why: String)
        listOf(
            Case("", "libng_gl4es.so", "opengles3", "новый слой перевода отвечает на opengles3, а не на имя файла"),
            Case("", "libgl4es_114.so", "opengles2", "старый слой перевода отвечает на opengles2"),
            Case("", "libglxshim.so", "opengles3_desktopgl_zink_kopper", "настольный слой идёт через zink"),
            Case("", "libOSMesa_8.so", "gallium_freedreno", "программный драйвер Adreno"),
            Case("", "libOSMesa_2300d.so", "gallium_panfrost", "программный драйвер Mali"),
            Case("", "libOSMesa_2121.so", "gallium_virgl", "virgl"),
            Case("", "", "opengles2", "без библиотеки остаётся самый совместимый путь"),
            Case("", "libunknown.so", "opengles2", "незнакомая библиотека не оставляет нативную сторону без идентификатора"),
            Case("vulkan_zink", "libng_gl4es.so", "vulkan_zink", "идентификатор из пакета рантайма важнее догадки по имени файла"),
        ).forEach { case ->
            assertEquals("${case.nativeId}/${case.library}: ${case.why}", case.expected, packRendererId(case.nativeId, case.library))
        }
    }

    @Test
    fun `окружение настольного слоя через zink`() {
        val env = packRendererEnvironment("opengles3_desktopgl_zink_kopper", "/cache/shaders")

        assertEquals("без принудительного zink драйвер берёт системный GL и игра ничего не рисует", "zink", env["MESA_LOADER_DRIVER_OVERRIDE"])
        assertEquals("zink", env["GALLIUM_DRIVER"])
        assertEquals("без поднятой версии игра просит профиль, которого не получит", "4.6", env["MESA_GL_VERSION_OVERRIDE"])
        assertEquals("460", env["MESA_GLSL_VERSION_OVERRIDE"])
        assertEquals("кэш шейдеров лежит в папке приложения", "/cache/shaders", env["MESA_GLSL_CACHE_DIR"])
        assertNull("переменные gl4es настольному слою не нужны", env["LIBGL_ES"])
    }

    @Test
    fun `окружение программных драйверов gallium`() {
        val env = packRendererEnvironment("gallium_freedreno", "/c")

        assertEquals("4.6", env["MESA_GL_VERSION_OVERRIDE"])
        assertEquals("/c", env["MESA_GLSL_CACHE_DIR"])
        assertFalse("gallium не переключается на zink", env.containsKey("GALLIUM_DRIVER"))
        assertFalse(env.containsKey("MESA_LOADER_DRIVER_OVERRIDE"))
    }

    @Test
    fun `окружение слоёв перевода gl4es`() {
        assertEquals(
            "новый слой перевода просит только ES 3",
            mapOf("LIBGL_ES" to "3"),
            packRendererEnvironment("opengles3", "/c"),
        )
        val old = packRendererEnvironment("opengles2", "/c")
        assertEquals("старый слой перевода работает на ES 2", "2", old["LIBGL_ES"])
        assertEquals("1", old["LIBGL_NOERROR"])
        assertEquals("3", old["LIBGL_MIPMAP"])
        assertNull("кэш Mesa старому слою не передаётся", old["MESA_GLSL_CACHE_DIR"])
    }

    @Test
    fun `аргументы версии, которые решает лаунчер`() {
        data class Case(val argument: String, val owned: Boolean, val why: String)
        listOf(
            Case("-Xmx4G", true, "память решает лаунчер по телефону, не описание версии"),
            Case("-Xms1G", true, "стартовая куча тоже от лаунчера"),
            Case("-Djava.library.path=\${natives_directory}", true, "пути библиотек на телефоне свои"),
            Case("-Djava.class.path=x", true, "класспуть собирает лаунчер вместе с мостом окна"),
            Case("-Dorg.lwjgl.util.Debug=true", true, "LWJGL настраивается под пакет рантайма"),
            Case("-Djna.tmpdir=/tmp", true, "JNA смотрит в папку нативных библиотек пакета"),
            Case("-Djava.home=/usr", true, "Java живёт в пакете рантайма"),
            Case("-Djava.io.tmpdir=/tmp", true, "временная папка внутри приложения"),
            Case("-Duser.home=/home", true, "домом служит папка сборки"),
            Case("-XX:MaxMetaspaceSize=256m", true, "предел метапространства ломает моды на телефоне"),
            Case("-XX:TieredStopAtLevel=1", true, "уровень JIT не берётся из описания"),
            Case("-Dmillida.debug=1", true, "свои свойства лаунчер ставит сам"),
            Case("-Duser.language=en", false, "язык не путается с домашней папкой из-за общего начала"),
            Case("-Dfml.ignorePatchDiscrepancies=true", false, "флаги загрузчика из описания нужны игре"),
            Case("-XX:+UseG1GC", false, "прочие флаги машины передаются как есть"),
            Case("-Dlog4j.configurationFile=client.xml", false, "настройка журнала версии остаётся"),
            Case("--add-opens", false, "открытия модулей нужны современным загрузчикам"),
        ).forEach { case ->
            assertEquals("${case.argument}: ${case.why}", case.owned, isLauncherOwnedJvmArg(case.argument))
        }
    }
}
