package net.millida.launcher.core

import net.millida.launcher.core.bedrock.BEDROCK_TARGET_FALLBACK
import net.millida.launcher.core.bedrock.BedrockAddress
import net.millida.launcher.core.bedrock.BedrockBridge
import net.millida.launcher.core.bedrock.VIAPROXY_VERSION
import net.millida.launcher.core.bedrock.bedrockTargetVersionInJar
import net.millida.launcher.core.bedrock.viaProxyArguments
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Our own way into Bedrock: the game talks to a translator on the phone. Both
 * things that silently break it are pinned here - a wrong protocol name, which
 * the translator answers by guessing the server, and a jar that is not the one
 * we checked.
 */
class ViaProxyTest {

    @get:Rule
    val folder = TemporaryFolder()

    /** A string in a class file: the tag, its length, then the bytes. */
    private fun utf8Constant(text: String): ByteArray {
        val body = text.toByteArray(Charsets.UTF_8)
        return byteArrayOf(1, (body.size shr 8).toByte(), body.size.toByte()) + body
    }

    private fun jarWithConstant(text: String): File {
        val jar = folder.newFile("ViaProxy-test-$text.jar")
        ZipOutputStream(jar.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("net/raphimc/viabedrock/protocol/data/ProtocolConstants.class"))
            val magic = byteArrayOf(0xCA.toByte(), 0xFE.toByte(), 0xBA.toByte(), 0xBE.toByte())
            zip.write(
                magic +
                    utf8Constant("BEDROCK_VERSION_NAME") +
                    utf8Constant(text) +
                    utf8Constant("Ljava/lang/String;"),
            )
            zip.closeEntry()
        }
        return jar
    }

    @Test
    fun `имя версии Bedrock читается из того jar, который стоит на телефоне`() {
        assertEquals(
            "имя протокола берётся из пакета, а не из нашей памяти",
            "Bedrock 1.26.30",
            bedrockTargetVersionInJar(jarWithConstant("1.26.30")),
        )
        assertEquals(
            "обновлённый пакет приносит новое имя сам",
            "Bedrock 1.27.5",
            bedrockTargetVersionInJar(jarWithConstant("1.27.5")),
        )
    }

    @Test
    fun `нечитаемый пакет не выдаёт себя за рабочий`() {
        val broken = folder.newFile("broken.jar")
        broken.writeText("это не архив")
        assertNull("битый файл не даёт имени версии", bedrockTargetVersionInJar(broken))
        assertNull("отсутствующего файла тоже нет", bedrockTargetVersionInJar(File(folder.root, "нет.jar")))

        val empty = folder.newFile("empty.jar")
        ZipOutputStream(empty.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("META-INF/MANIFEST.MF"))
            zip.write("Main-Class: x".toByteArray())
            zip.closeEntry()
        }
        assertNull("архив без переводчика Bedrock не подходит", bedrockTargetVersionInJar(empty))
    }

    @Test
    fun `недокачанный переводчик не считается установленным`() {
        val bridge = BedrockBridge(folder.root)
        assertFalse("пустая папка — переводчика нет", bridge.installed())
        bridge.directory.mkdirs()
        bridge.jar.writeText("половина файла")
        assertFalse("файл не того размера — не запускаем", bridge.installed())
        assertEquals(
            "имя версии подставляется, пока пакет не прочитан",
            BEDROCK_TARGET_FALLBACK,
            bridge.targetVersion(),
        )
        assertTrue("версия закреплена в имени файла", bridge.jar.name.contains(VIAPROXY_VERSION))
    }

    @Test
    fun `переводчик слушает только сам телефон и знает, куда переводить`() {
        val args = viaProxyArguments(BedrockAddress("play.millida.net", 19132), "Bedrock 1.26.30")
        assertEquals("режим без окна", "cli", args.first())
        assertTrue(
            "порт открыт только для самого телефона, иначе им воспользуется вся сеть Wi-Fi",
            args.contains("--bind-address=127.0.0.1:25568"),
        )
        assertTrue("адрес сервера доезжает", args.contains("--target-address=play.millida.net:19132"))
        assertTrue("имя протокола доезжает", args.contains("--target-version=Bedrock 1.26.30"))
        assertTrue("вход по учётной записи не требуется", args.contains("--auth-method=none"))
    }
}
