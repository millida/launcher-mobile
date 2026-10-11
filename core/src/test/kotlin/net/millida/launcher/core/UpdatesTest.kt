package net.millida.launcher.core

import kotlinx.coroutines.runBlocking
import net.millida.launcher.core.http.Downloader
import net.millida.launcher.core.update.ReleaseNotesParser
import net.millida.launcher.core.update.UpdateCheck
import net.millida.launcher.core.http.HttpError
import net.millida.launcher.core.update.UpdateClient
import net.millida.launcher.core.update.UpdateFaults
import net.millida.launcher.core.update.UpdateManifest
import net.millida.launcher.core.update.UpdateRelease
import net.millida.launcher.core.update.UpdateVerdict
import net.millida.launcher.core.update.WhatsNewGate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

class UpdatesTest {

    @get:Rule
    val temp = TemporaryFolder()

    private fun manifest(
        version: String = "1.4.0",
        versionCode: Long = 140,
        url: String = "https://launcher-storage.millida.net/mobile/millida-140.apk",
        sha256: String = HASH,
        minSdk: Int = 0,
        platform: String = "android",
    ) = UpdateManifest(
        version = version,
        notes = "### Исправлено\n- мелочь",
        releasedAt = "2026-09-14",
        platforms = mapOf(
            platform to UpdateManifest.AndroidBuild(
                versionCode = versionCode,
                url = url,
                size = 12,
                sha256 = sha256,
                minSdk = minSdk,
            )
        ),
    )

    @Test
    fun `вердикт по манифесту канала`() {
        data class Case(
            val what: String,
            val manifest: UpdateManifest,
            val current: Long,
            val sdk: Int,
            val expect: String,
            val why: String,
        )

        val cases = listOf(
            Case("новее", manifest(), 130, 34, "available", "канал отдал сборку выше — это и есть обновление"),
            Case("ровно та же", manifest(versionCode = 130), 130, 34, "uptodate", "равный versionCode не обновление"),
            Case("старее", manifest(versionCode = 120), 130, 34, "uptodate", "откат канала не должен ставиться"),
            Case(
                "нет версии",
                manifest(version = "  "),
                130,
                34,
                "rejected",
                "манифест без версии показывать нечего",
            ),
            Case(
                "нет сборки под Android",
                manifest(platform = "ios"),
                130,
                34,
                "rejected",
                "чужая платформа не считается сборкой",
            ),
            Case(
                "нет versionCode",
                manifest(versionCode = 0),
                130,
                34,
                "rejected",
                "без номера сборки сравнивать не с чем",
            ),
            Case(
                "нет хеша",
                manifest(sha256 = ""),
                130,
                34,
                "rejected",
                "без sha256 APK не ставим — главное правило канала",
            ),
            Case(
                "хеш обрезан",
                manifest(sha256 = HASH.take(40)),
                130,
                34,
                "rejected",
                "sha1 вместо sha256 тоже не принимается",
            ),
            Case(
                "http вместо https",
                manifest(url = "http://launcher-storage.millida.net/a.apk"),
                130,
                34,
                "rejected",
                "APK по открытому каналу подменяется на лету",
            ),
            Case(
                "чужой хост",
                manifest(url = "https://evil.example/millida-140.apk"),
                130,
                34,
                "rejected",
                "подменённый манифест не должен увести установку на чужую раздачу",
            ),
            Case(
                "похожий хост",
                manifest(url = "https://launcher-storage.millida.net.evil.example/a.apk"),
                130,
                34,
                "rejected",
                "сравнение по префиксу строки пропустило бы чужой домен",
            ),
            Case(
                "логин в адресе",
                manifest(url = "https://launcher-storage.millida.net@evil.example/a.apk"),
                130,
                34,
                "rejected",
                "часть до @ — это логин, настоящий хост стоит после",
            ),
            Case(
                "чужой порт",
                manifest(url = "https://launcher-storage.millida.net:8443/a.apk"),
                130,
                34,
                "rejected",
                "на нестандартном порту раздачи нет — это не наш сервер",
            ),
            Case(
                "хост заглавными",
                manifest(url = "https://Launcher-Storage.Millida.net/mobile/millida-140.apk"),
                130,
                34,
                "available",
                "регистр в имени хоста не значим — отказ здесь сломал бы честный канал",
            ),
            Case(
                "пустой адрес",
                manifest(url = ""),
                130,
                34,
                "rejected",
                "скачивать нечего",
            ),
            Case(
                "требует Android новее",
                manifest(minSdk = 35),
                130,
                30,
                "rejected",
                "иначе игрок скачает 80 МБ и получит отказ установщика",
            ),
            Case(
                "пустой манифест",
                UpdateManifest(),
                130,
                34,
                "rejected",
                "мусор в канале не должен выглядеть как обновление",
            ),
        )

        for (case in cases) {
            val verdict = UpdateCheck.decide(case.manifest, case.current, case.sdk)
            val actual = when (verdict) {
                is UpdateVerdict.Available -> "available"
                UpdateVerdict.UpToDate -> "uptodate"
                is UpdateVerdict.Rejected -> "rejected"
            }
            assertEquals("${case.what}: ${case.why}", case.expect, actual)
            if (verdict is UpdateVerdict.Rejected) {
                assertTrue(
                    "${case.what}: отказ обязан объяснять игроку причину словами",
                    verdict.reason.length > 10,
                )
            }
        }
    }

    @Test
    fun `у найденного обновления поля дошли целиком`() {
        val verdict = UpdateCheck.decide(manifest(), 130, 34)
        val release = (verdict as UpdateVerdict.Available).release
        assertEquals("1.4.0", release.version)
        assertEquals(140L, release.versionCode)
        assertEquals(HASH, release.sha256)
        assertEquals("2026-09-14", release.releasedAt)
        assertTrue("заметки нужны окну «Что нового»", release.notes.contains("Исправлено"))
    }

    @Test
    fun `сравнение версий идёт по versionCode`() {
        assertTrue("141 > 140 — обновление", UpdateCheck.isNewer(141, 140))
        assertFalse("равные — не обновление", UpdateCheck.isNewer(140, 140))
        assertFalse("откат — не обновление", UpdateCheck.isNewer(139, 140))
        assertTrue("первая установка канала", UpdateCheck.isNewer(1, 0))
    }

    @Test
    fun `заметки релиза разбираются по разделам`() {
        val notes = ReleaseNotesParser.parse(
            """
            Кратко о версии.

            ### Исправлено
            - скин возвращается после сброса
            - карточка друга больше не двоится

            ### Добавлено
            * окно «Что нового»
              с продолжением строки

            ### Убрано
            - кнопка «Сохранить аватар»
            """.trimIndent()
        )

        assertEquals("текст до первого заголовка — вступление", "Кратко о версии.", notes.intro)
        assertEquals(
            "порядок разделов канонический, а не как в файле",
            listOf("Добавлено", "Исправлено", "Убрано"),
            notes.sections.map { it.title },
        )
        assertEquals(
            "перенос строки — часть того же пункта, а не новый пункт",
            listOf("окно «Что нового» с продолжением строки"),
            notes.sections.first { it.title == "Добавлено" }.items,
        )
        assertEquals(2, notes.sections.first { it.title == "Исправлено" }.items.size)
        assertTrue(notes.hasContent)
    }

    @Test
    fun `пустые и подставные заметки не считаются содержимым`() {
        assertFalse("пусто — окно не показываем", ReleaseNotesParser.parse("").hasContent)
        assertFalse(
            "строка-заглушка релизного конвейера ничего не говорит игроку",
            ReleaseNotesParser.parse("Обновление Millida Launcher 1.4.0").hasContent,
        )
        assertFalse(
            "заголовок без пунктов — пустой раздел",
            ReleaseNotesParser.parse("### Добавлено\n\n### Убрано").hasContent,
        )
    }

    @Test
    fun `окно «Что нового» показывается один раз и не на первой установке`() {
        val notes = ReleaseNotesParser.parse("### Добавлено\n- что-то")
        val empty = ReleaseNotesParser.parse("")

        data class Case(val current: Long, val seen: Long, val show: Boolean, val why: String)

        val cases = listOf(
            Case(140, 130, true, "обновился с прошлой версии — показываем"),
            Case(140, 140, false, "уже показывали для этой версии"),
            Case(140, 0, false, "первая установка: нового ещё нет"),
            Case(130, 140, false, "откат назад окно не открывает"),
        )

        for (case in cases) {
            val decision = WhatsNewGate.evaluate(case.current, case.seen, notes)
            assertEquals("${case.current}/${case.seen}: ${case.why}", case.show, decision.show)
            assertTrue(
                "метка не должна уезжать назад, иначе окно вернётся",
                decision.seenToStore >= case.seen,
            )
        }
        assertEquals(140L, WhatsNewGate.evaluate(140, 130, notes).seenToStore)
        assertFalse(
            "без заметок показывать нечего",
            WhatsNewGate.evaluate(140, 130, empty).show,
        )
    }

    @Test
    fun `битый и пустой манифест канала отвергаются с объяснением`() {
        for ((body, why) in listOf(
            "" to "пустой файл",
            "   " to "пробелы",
            "<html>404</html>" to "щит отдал страницу вместо json",
            "{\"version\": " to "оборванный json",
        )) {
            val client = clientFor(body)
            try {
                runBlocking { client.manifest() }
                fail("$why: манифест обязан быть отвергнут")
            } catch (error: IOException) {
                assertTrue(
                    "$why: текст ошибки должен быть про манифест",
                    error.message.orEmpty().contains("манифест"),
                )
            }
        }
    }

    @Test
    fun `манифест с лишними полями читается`() {
        val client = clientFor(
            """
            {"version":"1.4.0","notes":"### Добавлено\n- раз","releasedAt":"2026-09-14",
             "channel":"stable","platforms":{"android":{"versionCode":140,
             "url":"https://launcher-storage.millida.net/a.apk","size":12,"sha256":"$HASH","abi":"arm64-v8a"}}}
            """.trimIndent()
        )
        val verdict = runBlocking { client.check(130, 34) }
        assertTrue("неизвестные поля канала не должны ломать разбор", verdict is UpdateVerdict.Available)
    }

    @Test
    fun `скачивание сверяет sha256 и переименовывает только по успеху`() {
        val payload = "apk-bytes"
        val source = File(temp.newFolder("cdn"), "millida-140.apk").apply { writeText(payload) }
        val digest = Hashing.digest(source, "SHA-256")
        val layout = GameLayout(temp.newFolder("home")).also { it.ensure() }
        val client = UpdateClient(layout, Downloader(), temp.newFile("unused.json").absolutePath)

        val good = release(source, digest)
        val file = runBlocking { client.download(good) }
        assertEquals("файл встал под окончательным именем", "millida-140.apk", file.name)
        assertEquals(payload, file.readText())
        assertTrue(
            "временного файла после успеха остаться не должно",
            file.parentFile.listFiles()!!.none { it.name.endsWith(".new") },
        )

        file.delete()
        val bad = release(source, WRONG_HASH)
        try {
            runBlocking { client.download(bad) }
            fail("несовпавший sha256 обязан отменить установку")
        } catch (error: IOException) {
            assertFalse("битый APK не должен доехать до установщика", file.isFile)
            assertTrue(
                "текст ошибки должен быть понятен игроку",
                error.message.orEmpty().isNotBlank(),
            )
        }

        try {
            runBlocking { client.download(release(source, "")) }
            fail("без хеша ставить нельзя")
        } catch (error: IOException) {
            assertTrue(
                "игроку надо сказать, почему отказ",
                error.message.orEmpty().contains("контрольной суммы"),
            )
        }
    }

    @Test
    fun `скачанный APK сверяется ещё раз перед установкой`() {
        val source = File(temp.newFolder("staged"), "millida-140.apk").apply { writeText("apk-bytes") }
        val digest = Hashing.digest(source, "SHA-256")
        val layout = GameLayout(temp.newFolder("home2")).also { it.ensure() }
        val client = UpdateClient(layout, Downloader(), temp.newFile("unused2.json").absolutePath)

        assertTrue("тот же файл — установка разрешена", client.verify(release(source, digest), source))
        assertFalse(
            "подменённый файл до установщика доходить не должен",
            client.verify(release(source, WRONG_HASH), source),
        )
        assertFalse(
            "без контрольной суммы разрешения нет",
            client.verify(release(source, ""), source),
        )
        assertFalse(
            "файла нет — ставить нечего",
            client.verify(release(source, digest), File(source.parentFile, "нет.apk")),
        )
    }

    @Test
    fun `отказ канала объясняет настоящую причину, а не всегда сеть`() {
        data class Case(val what: String, val error: Throwable, val expect: String, val why: String)

        val url = "https://launcher-storage.millida.net/mobile/latest.json"
        val cases = listOf(
            Case(
                "файла в канале нет",
                HttpError(404, url),
                "ещё не открыт",
                "404 при живой сети — это пустой канал; совет проверить сеть отправлял игрока чинить исправное",
            ),
            Case(
                "сервер упал, ошибка завёрнута повтором",
                IOException("не удалось скачать $url", HttpError(502, url)),
                "ошибкой 502",
                "5xx приходит обёрнутым в retry — код обязан достаться из причины",
            ),
            Case(
                "имя не разрешилось",
                IOException("не удалось скачать $url", UnknownHostException("launcher-storage.millida.net")),
                "Не удалось определить адрес",
                "DNS — единственный случай, где совет про сеть и VPN верен",
            ),
            Case(
                "манифест не читается",
                IOException("манифест обновления не читается", IllegalStateException("json")),
                "битый файл",
                "канал ответил, но содержимое испорчено — сеть тут ни при чём",
            ),
            Case(
                "таймаут",
                SocketTimeoutException("timeout"),
                "проверь сеть",
                "без ответа сервера причина действительно на стороне связи",
            ),
        )

        for (case in cases) {
            val text = UpdateFaults.describe(case.error)
            assertTrue("${case.what}: ${case.why}; получили «$text»", text.contains(case.expect))
        }
    }

    private fun release(source: File, sha256: String) = UpdateRelease(
        version = "1.4.0",
        versionCode = 140,
        url = source.absolutePath,
        size = source.length(),
        sha256 = sha256,
        releasedAt = "2026-09-14",
        notes = "",
        minSdk = 0,
    )

    private fun clientFor(body: String): UpdateClient {
        val layout = GameLayout(temp.newFolder()).also { it.ensure() }
        val file = File(layout.caches, "channel-${body.hashCode()}.json").apply { writeText(body) }
        return UpdateClient(layout, Downloader(), file.absolutePath)
    }

    companion object {
        private const val HASH = "9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08"
        private const val WRONG_HASH = "0000000000000000000000000000000000000000000000000000000000000000"
    }
}

class UpdatePolicyTest {
    private val mb = 1024L * 1024

    @Test
    fun unmeteredAlwaysDownloads() {
        assertTrue(net.millida.launcher.core.update.UpdatePolicy.shouldDownloadInBackground(200 * mb, true))
        assertTrue(net.millida.launcher.core.update.UpdatePolicy.shouldDownloadInBackground(0, true))
    }

    @Test
    fun meteredOnlySmallKnownSize() {
        val p = net.millida.launcher.core.update.UpdatePolicy
        assertTrue(p.shouldDownloadInBackground(29 * mb, false))
        assertFalse(p.shouldDownloadInBackground(30 * mb, false))
        assertFalse(p.shouldDownloadInBackground(0, false))
        assertFalse(p.shouldDownloadInBackground(-1, false))
    }

    @Test
    fun postponedIsNeverFetched() {
        assertFalse(net.millida.launcher.core.update.UpdatePolicy.shouldDownloadInBackground(mb, true, postponed = true))
    }

    @Test
    fun installWaitsForGame() {
        val p = net.millida.launcher.core.update.UpdatePolicy
        val m = net.millida.launcher.core.update.InstallMoment.entries.associateBy { it.name }
        assertEquals(m.getValue("Nothing"), p.installMoment(packageReady = false, gameAlive = false))
        assertEquals(m.getValue("Nothing"), p.installMoment(packageReady = false, gameAlive = true))
        assertEquals(m.getValue("WaitForGame"), p.installMoment(packageReady = true, gameAlive = true))
        assertEquals(m.getValue("Install"), p.installMoment(packageReady = true, gameAlive = false))
    }

    @Test
    fun userActionFlagFromApi31() {
        val p = net.millida.launcher.core.update.UpdatePolicy
        assertFalse(p.canSkipUserAction(30))
        assertTrue(p.canSkipUserAction(31))
        assertTrue(p.canSkipUserAction(35))
    }
}
