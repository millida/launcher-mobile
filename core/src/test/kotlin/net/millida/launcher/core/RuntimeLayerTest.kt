package net.millida.launcher.core

import kotlinx.coroutines.runBlocking
import net.millida.launcher.core.http.Downloader
import net.millida.launcher.core.runtime.RendererVariable
import net.millida.launcher.core.runtime.RuntimeImport
import net.millida.launcher.core.runtime.RuntimeInstaller
import net.millida.launcher.core.runtime.withLayer
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * The pack inside the application is a snapshot of its build day: its window
 * layer still read POJAV_RENDERER (Sodium stops the game on it) and it had no
 * MobileGlues, while every phone that installed the full application kept
 * running on it. The manifest's layer has to take over, and the pack has to
 * stay usable when the manifest cannot be reached.
 */
class RuntimeLayerTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val server = MockWebServer()
    private val files = HashMap<String, ByteArray>()

    @Before
    fun start() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val body = files[request.path.orEmpty()] ?: return MockResponse().setResponseCode(404)
                return MockResponse().setBody(Buffer().write(body))
            }
        }
        server.start()
    }

    @After
    fun stop() {
        server.shutdown()
    }

    private fun zip(vararg entries: Pair<String, String>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { archive ->
            entries.forEach { (name, content) ->
                archive.putNextEntry(ZipEntry(name))
                archive.write(content.toByteArray())
                archive.closeEntry()
            }
        }
        return out.toByteArray()
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun bundledPack(): ByteArray = zip(
        "millida-runtime.json" to """
            {
              "jres": [{"major": 21, "path": "jre-21"}],
              "bridge": ["bridge/millida-glfw.jar", "bridge/lwjgl-glfw-classes.jar"],
              "rendererDir": "native",
              "renderers": [{"id": "krypton", "label": "Krypton", "library": "libng_gl4es.so"}]
            }
        """.trimIndent(),
        "jre-21/bin/java" to "elf",
        "jre-21/lib/server/libjvm.so" to "elf",
        "bridge/millida-glfw.jar" to "jar",
        "bridge/lwjgl-glfw-classes.jar" to "jar",
        "native/libng_gl4es.so" to "elf",
        "native/libpojavexec.so" to "reads ${RendererVariable.LEGACY}",
    )

    private fun publish(): File {
        val bridge = zip("millida-glfw.jar" to "jar", "lwjgl-glfw-classes.jar" to "jar")
        val native = zip(
            "libng_gl4es.so" to "elf",
            "libmobileglues.so" to "elf",
            "libpojavexec.so" to "reads ${RendererVariable.OWN}",
        )
        files["/bridge.zip"] = bridge
        files["/native.zip"] = native
        val base = server.url("/").toString().trimEnd('/')
        val manifest = File(temp.newFolder(), "manifest.json")
        manifest.writeText(
            """
            {
              "revision": 42,
              "bridge": [{"id": "bridge", "url": "$base/bridge.zip", "sha256": "${sha256(bridge)}",
                          "classpath": ["millida-glfw.jar", "lwjgl-glfw-classes.jar"], "lwjglVersion": "3.3.6"}],
              "renderers": [
                {"id": "krypton", "label": "Krypton", "url": "$base/native.zip", "sha256": "${sha256(native)}",
                 "library": "libng_gl4es.so", "component": "native"},
                {"id": "mobileglues", "label": "MobileGlues", "url": "$base/native.zip", "sha256": "${sha256(native)}",
                 "library": "libmobileglues.so", "eglLibrary": "libmobileglues.so", "component": "native"}
              ]
            }
            """.trimIndent(),
        )
        return manifest
    }

    private fun installer(layout: GameLayout, manifest: File) =
        RuntimeInstaller(layout, Downloader(client = OkHttpClient(), attempts = 1), manifestUrl = manifest.absolutePath)

    @Test
    fun `слой из манифеста встаёт под Java встроенного пакета`() {
        val layout = GameLayout(temp.newFolder()).also { it.ensure() }
        val pack = RuntimeImport.install(layout, bundledPack().inputStream())
        assertFalse(
            "исходное условие: оконный слой встроенного пакета читает старую переменную, с ней Sodium роняет игру",
            RendererVariable.windowLayerRenamed(pack.rendererDir),
        )

        val layer = runBlocking { installer(layout, publish()).installLayer("mobileglues") }
        val runtime = pack.withLayer(layer)

        assertEquals("Java остаётся из пакета — её не качают второй раз", pack.javaHome, runtime.javaHome)
        assertEquals("рендерер, которого во встроенном пакете нет, берётся из манифеста", "mobileglues", runtime.renderer.id)
        assertTrue("библиотека выбранного рендерера лежит в папке слоя", File(runtime.rendererDir, runtime.renderer.library).isFile)
        assertTrue(
            "оконный слой из манифеста читает MILLIDA_RENDER: POJAV_RENDERER в окружение больше не попадает",
            RendererVariable.windowLayerRenamed(runtime.rendererDir),
        )
        assertFalse("папка слоя — компонент манифеста, а не встроенный пакет", RuntimeImport.owns(layout, runtime.rendererDir))
        assertEquals("мост идёт из того же манифеста, что и слой", "bridge", runtime.bridgeClasspath.first().parentFile.name)
        assertEquals("3.3.6", runtime.lwjglVersion)
    }

    @Test
    fun `без сети слой не подменяется, а пакет остаётся целым`() {
        val layout = GameLayout(temp.newFolder()).also { it.ensure() }
        val pack = RuntimeImport.install(layout, bundledPack().inputStream())
        val manifest = publish()
        files.clear()

        try {
            runBlocking { installer(layout, manifest).installLayer(null) }
            fail("компонент не скачался — слой из манифеста выдавать нельзя")
        } catch (expected: IOException) {
            assertTrue("ошибка называет причину", expected.message.orEmpty().isNotBlank())
        }
        assertTrue("встроенный пакет на месте: запуск идёт на нём", File(pack.rendererDir, "libng_gl4es.so").isFile)
        assertFalse(
            "недокачанный компонент не должен выглядеть установленным",
            installer(layout, manifest).installedComponents().contains("native"),
        )
    }

    @Test
    fun `распакованный слой берётся без сети, пока манифест на диске его называет`() {
        val layout = GameLayout(temp.newFolder()).also { it.ensure() }
        val manifest = publish()
        val seen = File(layout.caches, "runtime-manifest.json")
        assertEquals("манифеста на диске ещё нет — ответа без сети нет", null, installer(layout, manifest).installedLayer(null))

        seen.parentFile.mkdirs()
        seen.writeText(manifest.readText())
        assertEquals("манифест есть, а компоненты не распакованы", null, installer(layout, manifest).installedLayer(null))

        runBlocking { installer(layout, manifest).installLayer("mobileglues") }
        files.clear()
        server.shutdown()
        val offline = installer(layout, manifest).installedLayer("mobileglues")
        assertEquals("без сети запуск получает тот же слой сразу, а не ждёт отказа сервера", "mobileglues", offline?.renderer?.id)
        assertTrue("и его файлы на месте", File(offline!!.rendererDir, "libmobileglues.so").isFile)

        seen.writeText(manifest.readText().replace(Regex("\"sha256\": \"[0-9a-f]{8}"), "\"sha256\": \"00000000"))
        assertEquals(
            "манифест назвал другую сборку слоя — старая распакованная за новую не выдаётся",
            null,
            installer(layout, manifest).installedLayer("mobileglues"),
        )
        seen.writeText("{ не json")
        assertEquals("испорченная копия манифеста — просто нет ответа", null, installer(layout, manifest).installedLayer(null))
    }

    @Test
    fun `свой пакет из файла закреплён, встроенный нет`() {
        val bundled = GameLayout(temp.newFolder()).also { it.ensure() }
        RuntimeImport.install(bundled, bundledPack().inputStream())
        assertFalse("встроенный пакет уступает слою из манифеста", RuntimeImport.pinned(bundled))

        val own = GameLayout(temp.newFolder()).also { it.ensure() }
        RuntimeImport.install(own, bundledPack().inputStream(), pinned = true)
        assertTrue("пакет, выбранный игроком из файла, остаётся главным", RuntimeImport.pinned(own))

        RuntimeImport.install(own, bundledPack().inputStream())
        assertFalse("переустановка встроенного поверх своего снимает закрепление", RuntimeImport.pinned(own))
    }
}
