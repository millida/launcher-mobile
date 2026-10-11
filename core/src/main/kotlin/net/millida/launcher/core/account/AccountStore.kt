package net.millida.launcher.core.account

import kotlinx.serialization.builtins.ListSerializer
import net.millida.launcher.core.GameLayout
import net.millida.launcher.core.atomicWriteText
import net.millida.launcher.core.mojang.MojangJson
import java.io.File

class AccountStore(private val layout: GameLayout) {
    private val file: File get() = File(layout.root, "accounts.json")

    fun load(): List<Account> {
        if (!file.isFile) return emptyList()
        return try {
            MojangJson.decodeFromString(ListSerializer(Account.serializer()), file.readText())
        } catch (error: Exception) {
            val broken = File(file.parentFile, "accounts.json.broken")
            if (broken.exists()) broken.delete()
            file.renameTo(broken)
            emptyList()
        }
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

    private fun save(accounts: List<Account>) {
        file.atomicWriteText(MojangJson.encodeToString(ListSerializer(Account.serializer()), accounts))
    }
}
