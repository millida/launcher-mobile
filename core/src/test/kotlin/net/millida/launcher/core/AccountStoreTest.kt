package net.millida.launcher.core

import net.millida.launcher.core.account.Account
import net.millida.launcher.core.account.AccountKind
import net.millida.launcher.core.account.AccountStore
import net.millida.launcher.core.account.GameSession
import net.millida.launcher.core.account.OfflineAuth
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.UUID

class AccountStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun store(): AccountStore = AccountStore(GameLayout(tmp.root))

    private val steve = Account(id = "a1", kind = AccountKind.OFFLINE, nick = "Steve", uuid = "u1", addedAt = 10)
    private val alex = Account(id = "a2", kind = AccountKind.MICROSOFT, nick = "Alex", uuid = "u2", xuid = "x2", addedAt = 20)

    @Test
    fun `без файла аккаунтов список пуст`() {
        assertTrue("первый запуск — ни одного аккаунта и никаких ошибок", store().load().isEmpty())
    }

    @Test
    fun `аккаунт добавляется, заменяется по id и переживает перезапуск`() {
        store().upsert(steve)
        store().upsert(alex)
        val renamed = store().upsert(steve.copy(nick = "Steve2"))

        assertEquals("замена не двигает аккаунт в списке и не дублирует его", listOf("a1", "a2"), renamed.map { it.id })
        assertEquals("Steve2", renamed.first().nick)
        assertEquals("новый экземпляр хранилища читает то же с диска", renamed, store().load())
        assertEquals("xuid Microsoft сохраняется", "x2", store().load().last().xuid)
    }

    @Test
    fun `удаление убирает только свой аккаунт`() {
        store().upsert(steve)
        store().upsert(alex)

        val left = store().remove("a1")
        val unchanged = store().remove("нет такого")

        assertEquals(listOf("a2"), left.map { it.id })
        assertEquals("удаление несуществующего ничего не ломает", listOf("a2"), unchanged.map { it.id })
        assertEquals(listOf("a2"), store().load().map { it.id })
    }

    @Test
    fun `битый файл откладывается в сторону, а не теряется молча`() {
        val file = File(tmp.root, "accounts.json")
        file.writeText("{это не json")
        File(tmp.root, "accounts.json.broken").writeText("старая копия")

        val loaded = store().load()

        assertTrue("битый файл — пустой список, а не падение лаунчера", loaded.isEmpty())
        assertFalse("битый файл не остаётся на месте и не мешает следующей записи", file.exists())
        assertEquals(
            "содержимое сохранено для разбора, прошлая копия заменена",
            "{это не json",
            File(tmp.root, "accounts.json.broken").readText(),
        )

        val saved = store().upsert(steve)
        assertEquals("после сбоя аккаунт снова записывается", listOf("a1"), saved.map { it.id })
    }

    @Test
    fun `лишние поля из новой версии не ломают чтение`() {
        File(tmp.root, "accounts.json").writeText(
            """[{"id":"a1","kind":"MILLIDA","nick":"Steve","uuid":"u1","avatar":"https://x","addedAt":5}]""",
        )

        val loaded = store().load()

        assertEquals("файл, записанный новой версией, читается старой", listOf("a1"), loaded.map { it.id })
        assertEquals(AccountKind.MILLIDA, loaded.single().kind)
        assertEquals("пропущенный xuid — пустая строка", "", loaded.single().xuid)
    }

    @Test
    fun `офлайн uuid совпадает с тем, что считает сервер Minecraft`() {
        for (nick in listOf("Steve", "Alex", "jeb_", "a")) {
            val expected = UUID.nameUUIDFromBytes("OfflinePlayer:$nick".toByteArray()).toString().replace("-", "")
            assertEquals("$nick: иначе на офлайн-сервере игрок получит чужой инвентарь", expected, OfflineAuth.offlineUuid(nick))
        }
        val uuid = OfflineAuth.offlineUuid("Steve")
        assertEquals("uuid без дефисов", 32, uuid.length)
        assertEquals("версия 3 — UUID по имени", '3', uuid[12])
        assertTrue("ник чувствителен к регистру, как на сервере", OfflineAuth.offlineUuid("steve") != uuid)
    }

    private data class NickCase(val nick: String, val valid: Boolean, val why: String)

    @Test
    fun `офлайн ник проверяется по правилам Minecraft`() {
        val cases = listOf(
            NickCase("Steve", true, "обычный ник"),
            NickCase("a_1", true, "три символа с подчёркиванием"),
            NickCase("x".repeat(16), true, "шестнадцать — максимум"),
            NickCase("ab", false, "короче трёх сервер не пустит"),
            NickCase("x".repeat(17), false, "длиннее шестнадцати сервер не пустит"),
            NickCase(" Steve", false, "пробел не срезается молча — ник ушёл бы в игру с пробелом"),
            NickCase("Стив", false, "кириллица в нике ломает вход на сервер"),
            NickCase("St-eve", false, "дефис запрещён"),
            NickCase("", false, "пустой ник"),
        )
        for (case in cases) {
            assertEquals("validNick(«${case.nick}»): ${case.why}", case.valid, OfflineAuth.validNick(case.nick))
        }
    }

    private data class SessionCase(val session: GameSession, val userType: String, val licensed: Boolean, val why: String)

    @Test
    fun `тип сессии и лицензия выводятся из токена и сервера входа`() {
        val cases = listOf(
            SessionCase(OfflineAuth.session("Steve"), "legacy", false, "офлайн без токена — legacy, не лицензия"),
            SessionCase(GameSession("Steve", "u", "mc-token"), "msa", true, "токен Microsoft без своего сервера — лицензия"),
            SessionCase(
                GameSession("Steve", "u", "millida-token", yggdrasil = "https://api.millida.net/v2/yggdrasil"),
                "msa",
                false,
                "токен Millida идёт через свой сервер входа — это не лицензия Mojang",
            ),
        )
        for (case in cases) {
            assertEquals("${case.why}: userType", case.userType, case.session.userType)
            assertEquals("${case.why}: licensed", case.licensed, case.session.licensed)
        }
        assertEquals("офлайн-сессия несёт офлайн uuid", OfflineAuth.offlineUuid("Steve"), OfflineAuth.session("Steve").uuid)
    }
}
