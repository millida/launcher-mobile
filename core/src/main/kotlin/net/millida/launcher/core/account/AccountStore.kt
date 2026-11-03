package net.millida.launcher.core.account

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import net.millida.launcher.core.GameLayout
import net.millida.launcher.core.atomicWriteText
import net.millida.launcher.core.mojang.MojangJson
import java.io.File

/** What reading the accounts file found: the accounts, the active one and the rows that were retired on the way. */
data class AccountsRead(
    val accounts: List<Account>,
    val active: String,
    val retired: List<String> = emptyList(),
)

/**
 * Ely.by sign-in is gone from the launcher, as on the desktop (retireElyby). Its rows
 * become offline nicks instead of vanishing: the player keeps the name they played
 * under and Play keeps working, while nothing is left that could use the stored
 * Ely.by session. An offline row with the same nick already there takes its place.
 */
object RetiredElyBy {
    const val KIND = "ELYBY"

    fun tokenKey(accountId: String): String = "$accountId:ely-token"

    fun retire(rows: JsonArray, active: String): AccountsRead {
        val objects = rows.map { it.jsonObject }
        val retired = ArrayList<String>()
        val next = ArrayList<Account>()
        var current = active
        for (row in objects) {
            if (row.kindName() != KIND) {
                next += MojangJson.decodeFromJsonElement(Account.serializer(), row)
                continue
            }
            val id = row.text("id")
            val nick = row.text("nick")
            retired += id
            val twin = objects.firstOrNull { it.kindName() == AccountKind.OFFLINE.name && it.text("nick") == nick }
            if (twin != null) {
                if (current == id) current = twin.text("id")
                continue
            }
            next += Account(
                id = id,
                kind = AccountKind.OFFLINE,
                nick = nick,
                uuid = OfflineAuth.offlineUuid(nick),
                addedAt = row["addedAt"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: 0L,
            )
        }
        return AccountsRead(next, current, retired)
    }

    private fun JsonObject.kindName(): String? = this["kind"]?.jsonPrimitive?.contentOrNull

    private fun JsonObject.text(key: String): String = this[key]?.jsonPrimitive?.contentOrNull.orEmpty()
}

class AccountStore(private val layout: GameLayout) {
    private val file: File get() = File(layout.root, "accounts.json")

    fun load(): List<Account> = read("").accounts

    /**
     * Runs once at start: retired rows are written back as their replacements and
     * their stored tokens are deleted, so no later read meets them again.
     */
    fun migrate(active: String, vault: SecretVault): AccountsRead {
        val result = read(active)
        if (result.retired.isEmpty()) return result
        save(result.accounts)
        result.retired.forEach { vault.remove(RetiredElyBy.tokenKey(it)) }
        return result
    }

    fun upsert(account: Account): List<Account> {
        val current = load().toMutableList()
        val index = current.indexOfFirst { it.id == account.id }
        if (index >= 0) current[index] = account else current += account
        save(current)
        return current
    }

    fun remove(id: String): List<Account> {
        val remaining = load().filterNot { it.id == id }
        save(remaining)
        return remaining
    }

    private fun read(active: String): AccountsRead {
        if (!file.isFile) return AccountsRead(emptyList(), active)
        return try {
            RetiredElyBy.retire(MojangJson.parseToJsonElement(file.readText()).jsonArray, active)
        } catch (error: Exception) {
            val broken = File(file.parentFile, "accounts.json.broken")
            if (broken.exists()) broken.delete()
            file.renameTo(broken)
            AccountsRead(emptyList(), active)
        }
    }

    private fun save(accounts: List<Account>) {
        file.atomicWriteText(MojangJson.encodeToString(ListSerializer(Account.serializer()), accounts))
    }
}
