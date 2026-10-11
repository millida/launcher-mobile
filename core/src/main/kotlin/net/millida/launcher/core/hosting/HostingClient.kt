package net.millida.launcher.core.hosting

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import net.millida.launcher.core.account.MILLIDA_API
import net.millida.launcher.core.account.MillidaAuth
import net.millida.launcher.core.account.SecretVault
import net.millida.launcher.core.account.Unauthorized
import net.millida.launcher.core.http.Downloader
import net.millida.launcher.core.mojang.MojangJson
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.URLEncoder

/**
 * The hosting panel as the phone sees it: the same endpoints the desktop
 * launcher talks to, wrapped so that every failure reaches the player as a
 * sentence in Russian instead of a status code.
 */
class HostingClient(
    private val auth: MillidaAuth,
    private val vault: SecretVault,
    // The shared client is the one that carries our timeouts and the name
    // resolver that survives a dead system DNS; a private client has neither.
    private val client: OkHttpClient = Downloader.shared(),
    private val apiBase: String = MILLIDA_API,
    private val exportPollMs: Long = WORLD_EXPORT_POLL_MS,
) {

    val console: ServerConsole = ServerConsole(auth, vault, client, apiBase)

    suspend fun servers(): List<HostingServerState> = guard("Не удалось получить список серверов") {
        auth.authorizedElement("/hosting/servers/me", "GET", null)
            .asArray("servers", "items")
            .map { it.toServerState() }
    }

    suspend fun details(serverId: String): HostingDetails {
        val id = HostingPaths.serverId(serverId)
        return guard("Не удалось открыть сервер") {
            val body = auth.authorized(base(id), "GET", null)
            val access = body.child("access")
            HostingDetails(
                server = body.toServerState(),
                settings = body.child("rules")?.mapValues { (_, value) ->
                    (value as? JsonPrimitive)?.content.orEmpty()
                } ?: emptyMap(),
                players = body.asArray("players").map {
                    HostingPlayer(
                        id = it.str("id"),
                        nickname = it.str("nickname"),
                        role = it.str("role"),
                        banned = it.flag("banned"),
                        lastJoinAt = it.str("lastJoinAt"),
                    )
                },
                customDomain = body.str("customDomain"),
                iconLocked = body.flag("iconLocked"),
                canDelete = body.flag("canDelete"),
                crashReason = body.str("crashReason"),
                subscription = body.child("subscription")?.let {
                    HostingSubscription(
                        status = it.str("status"),
                        autoRenew = it.flag("autoRenew"),
                        paidUntil = it.str("paidUntil"),
                        periodDays = it.int("periodDays"),
                        priceKopecks = it.long("priceKopecks"),
                    )
                },
                owner = access?.flag("owner", true) ?: true,
                permissions = access?.strings("permissions") ?: HOSTING_PERMISSIONS.map { it.first },
                upgradeCreditKopecks = body.long("upgradeCreditKopecks"),
            )
        }
    }

    suspend fun stats(serverId: String): HostingStats {
        val id = HostingPaths.serverId(serverId)
        return guard("Не удалось получить нагрузку сервера") {
            val body = auth.authorized("${base(id)}/stats", "GET", null)
            HostingStats(
                running = body.flag("running"),
                memoryUsedMb = body.int("memoryUsedMb"),
                memoryLimitMb = body.int("memoryLimitMb"),
                cpuPercent = body.real("cpuPercent"),
                diskUsedMb = body.int("diskUsedMb"),
                diskLimitMb = body.int("diskLimitMb"),
            )
        }
    }

    suspend fun online(serverId: String): List<HostingOnlinePlayer> {
        val id = HostingPaths.serverId(serverId)
        return guard("Не удалось получить список игроков") {
            auth.authorizedElement("${base(id)}/online", "GET", null)
                .asArray("players")
                .map { HostingOnlinePlayer(name = it.str("name"), uuid = it.str("uuid")) }
        }
    }

    suspend fun events(serverId: String, limit: Int = 30): List<HostingEvent> {
        val id = HostingPaths.serverId(serverId)
        return guard("Не удалось получить журнал событий") {
            auth.authorizedElement("${base(id)}/events?limit=$limit", "GET", null)
                .asArray("events", "items")
                .map {
                    HostingEvent(
                        id = it.str("id"),
                        kind = it.str("kind"),
                        message = it.str("message"),
                        actorLabel = it.str("actorLabel"),
                        createdAt = it.str("createdAt"),
                    )
                }
        }
    }

    suspend fun crash(serverId: String): HostingCrash {
        val id = HostingPaths.serverId(serverId)
        return guard("Не удалось прочитать причину падения") {
            val body = auth.authorized("${base(id)}/crash", "GET", null)
            val hint = body.child("hint")
            HostingCrash(
                reason = body.str("reason"),
                hintTitle = hint?.str("title").orEmpty(),
                hintAdvice = hint?.str("advice").orEmpty(),
                lines = body.strings("lines"),
            )
        }
    }

    suspend fun start(serverId: String): HostingActionResult =
        power(serverId, "start", "Не удалось запустить сервер", "Сервер запускается")

    suspend fun stop(serverId: String): HostingActionResult =
        power(serverId, "stop", "Не удалось остановить сервер", "Сервер останавливается")

    suspend fun restart(serverId: String): HostingActionResult =
        power(serverId, "restart", "Не удалось перезапустить сервер", "Перезапуск запущен")

    suspend fun kill(serverId: String): HostingActionResult =
        power(serverId, "kill", "Не удалось завершить процесс сервера", "Процесс сервера завершён")

    suspend fun updateSettings(serverId: String, patch: JsonObject): HostingActionResult {
        val id = HostingPaths.serverId(serverId)
        if (patch.isEmpty()) throw HostingError("Нечего сохранять: настройки не изменились")
        return guard("Не удалось сохранить настройки") {
            auth.authorized("${base(id)}/settings", "PATCH", patch)
            HostingActionResult("Настройки сохранены — применятся после перезапуска")
        }
    }

    suspend fun updateSetting(serverId: String, key: String, value: Boolean): HostingActionResult =
        updateSettings(serverId, buildJsonObject { put(key, value) })

    suspend fun updateSetting(serverId: String, key: String, value: Int): HostingActionResult =
        updateSettings(serverId, buildJsonObject { put(key, value) })

    suspend fun updateSetting(serverId: String, key: String, value: String): HostingActionResult =
        updateSettings(serverId, buildJsonObject { put(key, value) })

    suspend fun sendCommand(serverId: String, command: String): HostingActionResult =
        console.send(serverId, command)

    suspend fun subscription(serverId: String): HostingSubscription? = details(serverId).subscription

    suspend fun setAutoRenew(serverId: String, enabled: Boolean): HostingActionResult {
        val id = HostingPaths.serverId(serverId)
        return guard("Не удалось изменить автопродление") {
            val body = auth.authorized(
                "${base(id)}/plan/auto-renew",
                "POST",
                buildJsonObject { put("enabled", enabled) },
            )
            val until = body.str("paidUntil")
            HostingActionResult(
                if (enabled) "Автопродление включено" else "Автопродление выключено",
                if (until.isEmpty()) "" else "оплачено до $until",
            )
        }
    }

    suspend fun usage(serverId: String, days: Int = 30): HostingUsage {
        val id = HostingPaths.serverId(serverId)
        return guard("Не удалось получить статистику тарифа") {
            val body = auth.authorized("${base(id)}/usage?days=$days", "GET", null)
            val summary = body.child("summary") ?: buildJsonObject { }
            HostingUsage(
                days = body.int("days", days),
                runningHours = summary.real("runningHours"),
                ramGbHours = summary.real("ramGbHours"),
                peakPlayers = summary.int("peakPlayers"),
                crashes = summary.int("crashes"),
                uptimePercent = summary.real("uptimePercent"),
            )
        }
    }

    suspend fun deleteServer(serverId: String): HostingActionResult {
        val id = HostingPaths.serverId(serverId)
        return guard("Не удалось удалить сервер") {
            auth.authorized(base(id), "DELETE", null)
            HostingActionResult("Сервер удалён")
        }
    }

    suspend fun rename(serverId: String, name: String): HostingActionResult {
        val id = HostingPaths.serverId(serverId)
        if (name.isBlank()) throw HostingError("Имя сервера не может быть пустым")
        return guard("Не удалось переименовать сервер") {
            auth.authorized("${base(id)}/name", "PATCH", buildJsonObject { put("name", name) })
            HostingActionResult("Сервер переименован")
        }
    }

    suspend fun network(serverId: String): HostingNetwork {
        val id = HostingPaths.serverId(serverId)
        return guard("Не удалось получить адреса и порты") {
            val body = auth.authorized("${base(id)}/ports", "GET", null)
            HostingNetwork(
                address = body.str("address"),
                limit = body.int("limit"),
                ports = body.asArray("ports").map { HostingPort(port = it.int("port"), note = it.str("note")) },
            )
        }
    }

    suspend fun addPort(serverId: String, note: String = ""): HostingActionResult {
        val id = HostingPaths.serverId(serverId)
        return guard("Не удалось выдать порт") {
            val body = auth.authorized(
                "${base(id)}/ports",
                "POST",
                buildJsonObject { if (note.isNotBlank()) put("note", note) },
            )
            HostingActionResult("Порт выдан: ${body.int("port")}", body.str("address"))
        }
    }

    suspend fun removePort(serverId: String, port: Int): HostingActionResult {
        val id = HostingPaths.serverId(serverId)
        return guard("Не удалось убрать порт") {
            auth.authorized("${base(id)}/ports/$port", "DELETE", null)
            HostingActionResult("Порт $port убран")
        }
    }

    suspend fun changeAddress(serverId: String, slug: String): HostingActionResult {
        val id = HostingPaths.serverId(serverId)
        if (slug.isBlank()) throw HostingError("Адрес не может быть пустым")
        return guard("Не удалось сменить адрес") {
            auth.authorized("${base(id)}/address", "PATCH", buildJsonObject { put("slug", slug) })
            HostingActionResult("Адрес сервера изменён")
        }
    }

    suspend fun setDomain(serverId: String, domain: String): HostingActionResult {
        val id = HostingPaths.serverId(serverId)
        return guard("Не удалось привязать домен") {
            val body = auth.authorized("${base(id)}/domain", "PATCH", buildJsonObject { put("domain", domain) })
            val target = body.str("dnsTarget")
            HostingActionResult(
                if (domain.isBlank()) "Домен отвязан" else "Домен привязан",
                if (target.isEmpty()) "" else "пропиши в DNS запись на $target",
            )
        }
    }

    suspend fun shares(serverId: String): List<HostingShare> {
        val id = HostingPaths.serverId(serverId)
        return guard("Не удалось получить доступы") {
            auth.authorizedElement("${base(id)}/shares", "GET", null).asArray("shares", "items").map { it.toShare() }
        }
    }

    suspend fun addShare(serverId: String, email: String, permissions: List<String>): List<HostingShare> {
        val id = HostingPaths.serverId(serverId)
        if (email.isBlank()) throw HostingError("Укажи почту или ник того, кому даёшь доступ")
        return guard("Не удалось выдать доступ") {
            val payload = buildJsonObject {
                put("email", email)
                putJsonArray("permissions") { permissions.forEach { add(it) } }
            }
            auth.authorizedElement("${base(id)}/shares", "POST", payload).asArray("shares", "items").map { it.toShare() }
        }
    }

    suspend fun updateShare(serverId: String, userId: String, permissions: List<String>): List<HostingShare> {
        val id = HostingPaths.serverId(serverId)
        return guard("Не удалось изменить доступ") {
            val payload = buildJsonObject { putJsonArray("permissions") { permissions.forEach { add(it) } } }
            auth.authorizedElement("${base(id)}/shares/${encode(userId)}", "PATCH", payload)
                .asArray("shares", "items")
                .map { it.toShare() }
        }
    }

    suspend fun removeShare(serverId: String, userId: String): HostingActionResult {
        val id = HostingPaths.serverId(serverId)
        return guard("Не удалось забрать доступ") {
            auth.authorizedElement("${base(id)}/shares/${encode(userId)}", "DELETE", null)
            HostingActionResult("Доступ забран")
        }
    }

    suspend fun apiKeys(serverId: String): List<HostingApiKey> {
        val id = HostingPaths.serverId(serverId)
        return guard("Не удалось получить ключи API") {
            auth.authorizedElement("${base(id)}/api-keys", "GET", null).asArray("keys", "items").map {
                HostingApiKey(
                    id = it.str("id"),
                    name = it.str("name"),
                    prefix = it.str("prefix"),
                    permissions = it.strings("permissions"),
                    lastUsedAt = it.str("lastUsedAt"),
                    createdAt = it.str("createdAt"),
                )
            }
        }
    }

    suspend fun createApiKey(serverId: String, name: String, permissions: List<String>): HostingApiKeyIssued {
        val id = HostingPaths.serverId(serverId)
        if (name.isBlank()) throw HostingError("Дай ключу имя, иначе его не отличить от других")
        return guard("Не удалось создать ключ API") {
            val payload = buildJsonObject {
                put("name", name)
                putJsonArray("permissions") { permissions.forEach { add(it) } }
            }
            val body = auth.authorized("${base(id)}/api-keys", "POST", payload)
            HostingApiKeyIssued(id = body.str("id"), token = body.str("token"))
        }
    }

    suspend fun revokeApiKey(serverId: String, keyId: String): HostingActionResult {
        val id = HostingPaths.serverId(serverId)
        return guard("Не удалось отозвать ключ API") {
            auth.authorized("${base(id)}/api-keys/${encode(keyId)}", "DELETE", null)
            HostingActionResult("Ключ отозван")
        }
    }

    suspend fun sftp(serverId: String): HostingSftp {
        val id = HostingPaths.serverId(serverId)
        return guard("Не удалось получить доступ по SFTP") {
            auth.authorized("${base(id)}/sftp", "GET", null).toSftp()
        }
    }

    suspend fun issueSftp(serverId: String): HostingSftp {
        val id = HostingPaths.serverId(serverId)
        return guard("Не удалось выдать доступ по SFTP") {
            auth.authorized("${base(id)}/sftp", "POST", buildJsonObject { }).toSftp().copy(active = true)
        }
    }

    suspend fun revokeSftp(serverId: String): HostingActionResult {
        val id = HostingPaths.serverId(serverId)
        return guard("Не удалось отозвать доступ по SFTP") {
            auth.authorized("${base(id)}/sftp", "DELETE", null)
            HostingActionResult("Доступ по SFTP отозван")
        }
    }

    suspend fun worlds(serverId: String): List<HostingWorld> {
        val id = HostingPaths.serverId(serverId)
        return guard("Не удалось получить список миров") {
            auth.authorizedElement("${base(id)}/worlds", "GET", null).asArray("worlds", "items").map {
                HostingWorld(
                    name = it.str("name"),
                    sizeMb = it.long("sizeMb"),
                    dimension = it.flag("dimension"),
                    active = it.flag("active"),
                )
            }
        }
    }

    suspend fun switchWorld(serverId: String, name: String): HostingActionResult {
        val id = HostingPaths.serverId(serverId)
        if (name.isBlank()) throw HostingError("Не выбран мир")
        return guard("Не удалось переключить мир") {
            auth.authorized("${base(id)}/worlds/switch", "POST", buildJsonObject { put("name", name) })
            HostingActionResult("Мир переключён — применится после перезапуска")
        }
    }

    suspend fun files(serverId: String, path: String = "."): List<HostingFileEntry> {
        val id = HostingPaths.serverId(serverId)
        val remote = HostingPaths.remote(path)
        return guard("Не удалось открыть папку") {
            auth.authorizedElement("${base(id)}/files?path=${encode(remote)}", "GET", null)
                .asArray("files", "items")
                .map {
                    HostingFileEntry(
                        name = it.str("name"),
                        size = it.long("size"),
                        dir = it.flag("dir"),
                        modTime = it.str("modTime"),
                    )
                }
        }
    }

    suspend fun readFile(serverId: String, path: String): HostingFileContent {
        val id = HostingPaths.serverId(serverId)
        val remote = HostingPaths.remote(path)
        return guard("Не удалось прочитать файл") {
            val body = auth.authorized("${base(id)}/files/content?path=${encode(remote)}", "GET", null)
            HostingFileContent(path = body.str("path").ifEmpty { remote }, content = body.str("content"))
        }
    }

    suspend fun writeFile(serverId: String, path: String, content: String): HostingActionResult {
        val id = HostingPaths.serverId(serverId)
        val remote = HostingPaths.remote(path)
        if (remote == ".") throw HostingError("Не указан файл для записи")
        return guard("Не удалось сохранить файл") {
            auth.authorized(
                "${base(id)}/files/content?path=${encode(remote)}",
                "PUT",
                buildJsonObject { put("content", content) },
            )
            HostingActionResult("Файл сохранён: $remote")
        }
    }

    suspend fun deleteFile(serverId: String, path: String): HostingActionResult {
        val id = HostingPaths.serverId(serverId)
        val remote = HostingPaths.remote(path)
        if (remote == ".") throw HostingError("Корневую папку сервера удалить нельзя")
        return guard("Не удалось удалить файл") {
            auth.authorized("${base(id)}/files?path=${encode(remote)}", "DELETE", null)
            HostingActionResult("Удалено: $remote")
        }
    }

    suspend fun makeDirectory(serverId: String, path: String): HostingActionResult {
        val id = HostingPaths.serverId(serverId)
        val remote = HostingPaths.remote(path)
        if (remote == ".") throw HostingError("Не указано имя папки")
        return guard("Не удалось создать папку") {
            auth.authorized("${base(id)}/files/mkdir?path=${encode(remote)}", "POST", buildJsonObject { })
            HostingActionResult("Папка создана: $remote")
        }
    }

    suspend fun renameFile(serverId: String, from: String, to: String): HostingActionResult {
        val id = HostingPaths.serverId(serverId)
        val source = HostingPaths.remote(from)
        val destination = HostingPaths.remote(to)
        return guard("Не удалось переименовать файл") {
            auth.authorized(
                "${base(id)}/files/rename",
                "POST",
                buildJsonObject {
                    put("from", source)
                    put("to", destination)
                },
            )
            HostingActionResult("Переименовано в $destination")
        }
    }

    suspend fun extractFile(serverId: String, path: String, destination: String = "."): HostingActionResult {
        val id = HostingPaths.serverId(serverId)
        val source = HostingPaths.remote(path)
        val target = HostingPaths.remote(destination)
        return guard("Не удалось распаковать архив") {
            auth.authorized(
                "${base(id)}/files/extract",
                "POST",
                buildJsonObject {
                    put("path", source)
                    put("dest", target)
                },
            )
            HostingActionResult("Архив распакован в $target")
        }
    }

    suspend fun backups(serverId: String): List<HostingBackup> {
        val id = HostingPaths.serverId(serverId)
        return guard("Не удалось получить резервные копии") {
            auth.authorizedElement("${base(id)}/backups", "GET", null).asArray("backups", "items").map {
                HostingBackup(
                    id = it.str("id"),
                    kind = it.str("kind"),
                    sizeMb = it.long("sizeMb"),
                    locked = it.flag("locked"),
                    status = it.str("status"),
                    createdAt = it.str("createdAt"),
                )
            }
        }
    }

    suspend fun createBackup(serverId: String): HostingActionResult {
        val id = HostingPaths.serverId(serverId)
        return guard("Не удалось создать копию") {
            auth.authorized("${base(id)}/backups", "POST", buildJsonObject { })
            HostingActionResult("Копия поставлена в очередь")
        }
    }

    suspend fun restoreBackup(serverId: String, backupId: String): HostingActionResult {
        val id = HostingPaths.serverId(serverId)
        return guard("Не удалось восстановить из копии") {
            auth.authorized("${base(id)}/backups/${encode(backupId)}/restore", "POST", buildJsonObject { })
            HostingActionResult("Восстановление запущено")
        }
    }

    suspend fun deleteBackup(serverId: String, backupId: String): HostingActionResult {
        val id = HostingPaths.serverId(serverId)
        return guard("Не удалось удалить копию") {
            auth.authorized("${base(id)}/backups/${encode(backupId)}", "DELETE", null)
            HostingActionResult("Копия удалена")
        }
    }

    suspend fun plans(): List<HostingPlan> = guard("Не удалось получить тарифы") {
        auth.authorizedElement("/hosting/plans", "GET", null)
            .asArray("plans", "items")
            .map { it.toPlan() }
            .filter { it.code.isNotEmpty() }
    }

    suspend fun freeGate(): HostingFreeGate = guard("Не удалось проверить условия бесплатного сервера") {
        auth.authorized("/hosting/free-gate", "GET", null).toFreeGate()
    }

    suspend fun recheckFreeGate(): HostingFreeGate = guard("Не удалось проверить условия бесплатного сервера") {
        auth.authorized("/hosting/free-gate/recheck", "POST", buildJsonObject { }).toFreeGate()
    }

    suspend fun createServer(playKind: String, planCode: String?, name: String?): HostingServerState {
        if (playKind !in HOSTING_PLAY_KINDS) throw HostingError("Не выбрано, во что играем")
        val title = name?.trim().orEmpty()
        if (title.isNotEmpty() && title.length !in HOSTING_NAME_MIN..HOSTING_NAME_MAX) {
            throw HostingError("Имя сервера — от $HOSTING_NAME_MIN до $HOSTING_NAME_MAX символов")
        }
        return guard("Не удалось создать сервер") {
            val payload = buildJsonObject {
                put("playKind", playKind)
                if (!planCode.isNullOrBlank()) put("planCode", planCode)
                if (title.isNotEmpty()) put("name", title)
            }
            val server = auth.authorized("/hosting/servers", "POST", payload).toServerState()
            if (server.id.isEmpty()) throw HostingError("Не удалось создать сервер: ответ пришёл без номера сервера")
            server
        }
    }

    suspend fun changePlan(serverId: String, planCode: String): HostingActionResult {
        val id = HostingPaths.serverId(serverId)
        if (planCode.isBlank()) throw HostingError("Не выбран тариф")
        return guard("Не удалось сменить тариф") {
            auth.authorized(
                "${base(id)}/plan",
                "POST",
                buildJsonObject {
                    put("code", planCode)
                    put("months", 1)
                },
            )
            HostingActionResult("Тариф изменён")
        }
    }

    suspend fun playerAction(serverId: String, nickname: String, action: String): HostingActionResult {
        val id = HostingPaths.serverId(serverId)
        val nick = nickname.trim()
        if (!HOSTING_NICKNAME.matches(nick) || nick.length !in 3..16) {
            throw HostingError("Ник «$nickname» не подходит для команды сервера")
        }
        if (action !in HOSTING_PLAYER_ACTIONS) throw HostingError("Такого действия с игроком нет")
        return guard("Не удалось выполнить действие с игроком") {
            auth.authorized(
                "${base(id)}/online/action",
                "POST",
                buildJsonObject {
                    put("nickname", nick)
                    put("action", action)
                },
            )
            HostingActionResult("Готово", nick)
        }
    }

    suspend fun searchMaps(query: String): List<HostingMap> = guard("Каталог карт не ответил") {
        val text = query.trim().take(60)
        val suffix = if (text.isEmpty()) "" else "&query=${encode(text)}"
        val body = auth.authorized("/hosting/catalog/curseforge?type=map$suffix", "GET", null)
        if (!body.flag("enabled", true)) throw HostingError("Каталог карт сейчас недоступен — попробуй позже")
        if (body.str("error").isNotEmpty()) throw HostingError("Каталог карт не ответил — попробуй позже")
        body.asArray("hits").map {
            HostingMap(
                id = it.long("id"),
                name = it.str("name"),
                summary = it.str("summary"),
                iconUrl = it.str("iconUrl"),
                downloads = it.long("downloads"),
            )
        }.filter { it.id > 0 && it.name.isNotEmpty() }
    }

    suspend fun mapGameVersion(mapId: Long): String? = guard("Не удалось узнать версию карты") {
        auth.authorized("/hosting/catalog/curseforge/$mapId", "GET", null)
            .asArray("files")
            .asSequence()
            .flatMap { it.strings("gameVersions").asSequence() }
            .firstOrNull { MAP_RELEASE.matches(it) || MAP_SNAPSHOT.containsMatchIn(it) }
    }

    suspend fun setServerVersion(serverId: String, version: String): HostingActionResult {
        val id = HostingPaths.serverId(serverId)
        if (version.isBlank()) throw HostingError("Не указана версия игры")
        return guard("Не удалось сменить версию сервера") {
            auth.authorized("${base(id)}/core", "PATCH", buildJsonObject { put("version", version) })
            HostingActionResult("Версия сервера изменена")
        }
    }

    suspend fun installFromCatalog(
        serverId: String,
        projectId: String,
        source: String,
        versionId: String? = null,
    ): HostingActionResult {
        val id = HostingPaths.serverId(serverId)
        if (projectId.isBlank()) throw HostingError("Не выбрано, что ставить")
        return guard("Не удалось поставить на сервер") {
            auth.authorized(
                "${base(id)}/installs",
                "POST",
                buildJsonObject {
                    put("projectId", projectId)
                    put("source", source)
                    if (!versionId.isNullOrBlank()) put("versionId", versionId)
                },
            )
            HostingActionResult("Ставим на сервер")
        }
    }

    suspend fun installs(serverId: String): List<HostingInstall> {
        val id = HostingPaths.serverId(serverId)
        return guard("Не удалось получить список установленного") {
            auth.authorizedElement("${base(id)}/installs", "GET", null).asArray("installs", "items").map {
                HostingInstall(
                    id = it.str("id"),
                    kind = it.str("kind"),
                    source = it.str("source"),
                    projectId = it.str("projectId"),
                    versionId = it.str("versionId"),
                    name = it.str("name"),
                    versionName = it.str("versionName"),
                    iconUrl = it.str("iconUrl"),
                    status = it.str("status"),
                    error = it.str("error"),
                )
            }.filter { it.id.isNotEmpty() }
        }
    }

    suspend fun installUpdates(serverId: String): List<HostingInstallUpdate> {
        val id = HostingPaths.serverId(serverId)
        return guard("Не удалось проверить обновления") {
            auth.authorizedElement("${base(id)}/installs/updates", "GET", null).asArray("updates", "items").map {
                HostingInstallUpdate(
                    installId = it.str("installId"),
                    name = it.str("name"),
                    current = it.str("current"),
                    latest = it.str("latest"),
                    latestVersionId = it.str("latestVersionId"),
                    updateAvailable = it.flag("updateAvailable"),
                    compatible = it.flag("compatible", true),
                )
            }
        }
    }

    suspend fun removeInstall(serverId: String, installId: String): HostingActionResult {
        val id = HostingPaths.serverId(serverId)
        if (installId.isBlank()) throw HostingError("Не выбрано, что убрать")
        return guard("Не удалось убрать с сервера") {
            auth.authorizedElement("${base(id)}/installs/${encode(installId)}", "DELETE", null)
            HostingActionResult("Убрали — применится после перезапуска")
        }
    }

    suspend fun cores(): List<HostingCore> = guard("Каталог ядер не ответил") {
        auth.authorizedElement("/hosting/catalog/cores", "GET", null).asArray("cores").map {
            HostingCore(
                id = it.str("id"),
                name = it.str("name"),
                family = it.str("family"),
                recommended = it.flag("recommended"),
                versions = it.strings("versions"),
                latest = it.str("latest"),
            )
        }.filter { it.id.isNotEmpty() }
    }

    suspend fun changeCore(serverId: String, core: String, version: String): HostingActionResult {
        val id = HostingPaths.serverId(serverId)
        if (core.isBlank() || version.isBlank()) throw HostingError("Выбери ядро и версию")
        return guard("Не удалось сменить ядро") {
            auth.authorized(
                "${base(id)}/core",
                "PATCH",
                buildJsonObject {
                    put("core", core)
                    put("version", version)
                },
            )
            HostingActionResult("Ядро сменено")
        }
    }

    suspend fun searchContent(
        source: String,
        kind: String,
        query: String,
        version: String,
        offset: Int,
    ): HostingCatalogPage = guard("Каталог не ответил") {
        val text = query.trim().take(100)
        when (source) {
            HOSTING_SOURCE_FTB -> {
                val suffix = if (text.isEmpty()) "" else "?query=${encode(text)}"
                val packs = auth.authorized("/hosting/catalog/ftb$suffix", "GET", null).asArray("packs").map {
                    val first = (it["versions"] as? JsonArray)?.firstOrNull() as? JsonObject
                    HostingCatalogHit(
                        id = it.str("id"),
                        name = it.str("name"),
                        summary = it.str("summary"),
                        iconUrl = it.str("iconUrl"),
                        downloads = it.long("installs"),
                        minRamMb = first?.int("minRamMb") ?: 0,
                    )
                }
                HostingCatalogPage(packs.filter { it.id.isNotEmpty() && it.name.isNotEmpty() }, null)
            }
            HOSTING_SOURCE_CURSEFORGE -> {
                val params = StringBuilder("type=${encode(kind)}")
                if (text.isNotEmpty()) params.append("&query=${encode(text)}")
                if (version.isNotBlank()) params.append("&version=${encode(version)}")
                if (offset > 0) params.append("&offset=$offset")
                val body = auth.authorized("/hosting/catalog/curseforge?$params", "GET", null)
                if (!body.flag("enabled", true)) throw HostingError("CurseForge сейчас недоступен — выбери Modrinth")
                if (body.str("error").isNotEmpty()) throw HostingError("CurseForge не ответил — попробуй позже")
                HostingCatalogPage(
                    body.asArray("hits").map { it.toCatalogHit() }.filter { it.id.isNotEmpty() && it.name.isNotEmpty() },
                    body.int("nextOffset").takeIf { body.flag("hasMore") },
                )
            }
            else -> {
                val params = StringBuilder("type=${encode(kind)}")
                if (text.isNotEmpty()) params.append("&query=${encode(text)}")
                params.append(if (text.isNotEmpty()) "&sort=relevance" else "&sort=downloads")
                if (offset > 0) params.append("&offset=$offset")
                val body = auth.authorized("/hosting/catalog/search?$params", "GET", null)
                HostingCatalogPage(
                    body.asArray("hits").map { it.toCatalogHit() }.filter { it.id.isNotEmpty() && it.name.isNotEmpty() },
                    body.int("nextOffset").takeIf { body.flag("hasMore") },
                )
            }
        }
    }

    private fun JsonObject.toCatalogHit() = HostingCatalogHit(
        id = str("id"),
        name = str("name"),
        summary = str("summary"),
        iconUrl = str("iconUrl"),
        downloads = long("downloads"),
        author = str("author"),
    )

    private fun JsonObject.toPlan() = HostingPlan(
        code = str("code"),
        name = str("name"),
        tagline = str("tagline"),
        ramMb = int("ramMb"),
        maxPlayers = int("maxPlayers"),
        playersComfort = int("playersComfort"),
        diskMb = int("diskMb"),
        priceKopecks = long("priceKopecks"),
        currency = str("currency").ifEmpty { "RUB" },
        featured = flag("featured"),
        sleeps = flag("sleeps"),
        worldKeepDays = int("worldKeepDays"),
    )

    private suspend fun power(
        serverId: String,
        action: String,
        failure: String,
        success: String,
    ): HostingActionResult {
        val id = HostingPaths.serverId(serverId)
        return guard(failure) {
            auth.authorized("${base(id)}/$action", "POST", buildJsonObject { })
            HostingActionResult(success)
        }
    }

    private fun base(serverId: String) = "/hosting/servers/${encode(serverId)}"

    private fun JsonObject.toShare() = HostingShare(
        userId = str("userId"),
        email = str("email"),
        nickname = str("nickname"),
        permissions = strings("permissions"),
        createdAt = str("createdAt"),
    )

    private fun JsonObject.toSftp() = HostingSftp(
        active = flag("active"),
        host = str("host"),
        port = int("port"),
        login = str("login"),
        password = str("password"),
        expiresAt = str("expiresAt"),
    )

    private fun JsonObject.toFreeGate() = HostingFreeGate(
        passed = flag("passed"),
        botStarted = flag("botStarted"),
        subscribed = flag("subscribed"),
        botUrl = str("botUrl"),
        botUsername = str("botUsername"),
        channelUrl = str("channelUrl"),
    )

    suspend fun toolAccess(serverId: String): HostingToolAccess {
        val id = HostingPaths.serverId(serverId)
        return guard("Не удалось открыть сервер") {
            val body = auth.authorized(base(id), "GET", null)
            val access = body.child("access")
            val status = body.str("status")
            HostingToolAccess(
                fullAccess = body.flag("planFullAccess", true),
                owner = access?.flag("owner", true) ?: true,
                permissions = access?.strings("permissions") ?: HOSTING_PERMISSIONS.map { it.first },
                running = status == "RUNNING" || status == "STARTING",
                slug = body.str("slug"),
                address = body.str("address").ifEmpty { body.str("ip") },
                customDomain = body.str("customDomain"),
            )
        }
    }

    suspend fun extractJob(serverId: String): HostingJob =
        toolsJob(serverId, "files/extract/job", "Не удалось узнать, как идёт распаковка")

    suspend fun worldImportJob(serverId: String): HostingJob =
        toolsJob(serverId, "worlds/import/job", "Не удалось узнать, как идёт загрузка мира")

    suspend fun restoreJob(serverId: String): HostingJob =
        toolsJob(serverId, "backups/restore/job", "Не удалось узнать, как идёт восстановление")

    suspend fun startWorldImport(serverId: String, path: String, name: String): Boolean {
        val id = HostingPaths.serverId(serverId)
        val remote = HostingPaths.remote(path)
        if (remote == ".") throw HostingError("Не указан архив мира")
        if (!HOSTING_WORLD_NAME.matches(name)) {
            throw HostingError("Имя мира — латиница, цифры, точка, тире и подчёркивание, до 40 знаков")
        }
        return guard("Не удалось загрузить мир") {
            val body = auth.authorized(
                "${base(id)}/worlds/import",
                "POST",
                buildJsonObject {
                    put("path", remote)
                    put("name", name)
                },
            )
            body.str("state") == "running"
        }
    }

    suspend fun regenerateWorld(serverId: String, backup: Boolean): HostingActionResult {
        val id = HostingPaths.serverId(serverId)
        return guard("Не удалось пересоздать мир") {
            auth.authorized("${base(id)}/world/regenerate", "POST", buildJsonObject { put("backup", backup) })
            HostingActionResult(
                "Мир удалён — новый создастся при запуске",
                if (backup) "прежний сохранён в резервных копиях" else "",
            )
        }
    }

    suspend fun downloadWorldTo(
        serverId: String,
        sink: OutputStream,
        onPacking: () -> Unit = {},
        onProgress: (Long, Long) -> Unit = { _, _ -> },
    ): Long {
        val id = HostingPaths.serverId(serverId)
        onPacking()
        if (!startWorldExport(id).finished) awaitWorldExport(id, onPacking)
        return downloadRanged("${base(id)}/world/download", sink, "Не удалось скачать мир", onProgress)
    }

    private suspend fun startWorldExport(serverId: String): HostingJob =
        guard("Не удалось собрать архив мира") {
            val body = auth.authorized("${base(serverId)}/world/export", "POST", buildJsonObject { })
            HostingJob(state = body.str("state").ifEmpty { "running" })
        }

    private suspend fun worldExportJob(serverId: String): HostingJob =
        guard("Не удалось узнать, как идёт сборка архива") {
            val body = auth.authorized("${base(serverId)}/world/export/job", "GET", null)
            HostingJob(
                state = body.str("state").ifEmpty { "none" },
                error = body.str("error"),
                bytes = body.long("sizeBytes"),
                total = body.long("sizeBytes"),
            )
        }

    private suspend fun awaitWorldExport(serverId: String, onPacking: () -> Unit) {
        val started = System.nanoTime()
        var misses = 0
        while (System.nanoTime() - started < WORLD_EXPORT_WAIT_NS) {
            delay(exportPollMs)
            val job = try {
                worldExportJob(serverId)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: HostingError) {
                misses += 1
                if (misses >= WORLD_EXPORT_MISSES) throw error
                continue
            }
            misses = 0
            when (job.state) {
                "ready" -> return
                "failed" -> throw HostingError(job.error.ifBlank { "Архив мира не собрался — повтори" })
                "none" -> throw HostingError("Сборка архива прервалась — повтори")
                else -> onPacking()
            }
        }
        throw HostingError("Архив мира собирается слишком долго — попробуй скачать позже")
    }

    suspend fun downloadFileTo(
        serverId: String,
        path: String,
        sink: OutputStream,
        onProgress: (Long, Long) -> Unit = { _, _ -> },
    ): Long {
        val id = HostingPaths.serverId(serverId)
        val remote = HostingPaths.remote(path)
        if (remote == ".") throw HostingError("Не выбран файл для скачивания")
        return downloadRanged("${base(id)}/files/download?path=${encode(remote)}", sink, "Не удалось скачать файл", onProgress)
    }

    // Pieces are addressed by offset, so a resent piece overwrites itself instead of being appended twice.
    suspend fun uploadStream(
        serverId: String,
        path: String,
        source: InputStream,
        total: Long,
        onProgress: (Long, Long) -> Unit = { _, _ -> },
    ): HostingActionResult = withContext(Dispatchers.IO) {
        val id = HostingPaths.serverId(serverId)
        val remote = HostingPaths.remote(path)
        val failure = "Не удалось загрузить файл"
        if (remote == ".") throw HostingError("Не указано имя файла")
        if (total <= 0L) throw HostingError("$failure: файл пустой или его размер неизвестен — выбери другой")
        val buffer = ByteArray(TOOLS_PIECE_START)
        var token = toolsToken(failure)
        var renewed = false
        var piece = TOOLS_PIECE_START
        var sent = 0L
        var sizeOnNode = -1L
        while (sent < total) {
            ensureActive()
            val want = minOf(buffer.size.toLong(), total - sent).toInt()
            val filled = toolsReadFully(source, buffer, want)
            if (filled < want) {
                throw HostingError("$failure: файл на телефоне короче, чем заявлен — выбери его заново")
            }
            var within = 0
            var failures = 0
            while (within < filled) {
                ensureActive()
                val size = minOf(piece, filled - within)
                val offset = sent + within
                try {
                    val request = Request.Builder()
                        .url("$apiBase${base(id)}/files/upload?path=${encode(remote)}&offset=$offset&total=$total")
                        .header("Authorization", "Bearer $token")
                        .post(buffer.toRequestBody(TOOLS_OCTET_STREAM, within, size))
                        .build()
                    val reported = client.newCall(request).execute().use { response ->
                        val text = response.body?.string().orEmpty()
                        if (!response.isSuccessful) toolsRefusal(response.code, text, failure)
                        toolsSizeField(text)
                    }
                    if (reported >= 0L) sizeOnNode = maxOf(sizeOnNode, reported)
                    within += size
                    failures = 0
                    onProgress(sent + within, total)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (refused: HostingError) {
                    throw refused
                } catch (unauthorized: Unauthorized) {
                    if (renewed) throw hostingFailure(failure, unauthorized)
                    token = renewedToken(token, failure)
                    renewed = true
                } catch (error: IOException) {
                    failures += 1
                    if (failures >= TOOLS_ATTEMPTS) throw toolsNetworkFailure(failure, error)
                    piece = maxOf(piece / 2, TOOLS_PIECE_MIN)
                    delay(toolsRetryDelay(failures))
                }
            }
            sent += filled
        }
        if (source.read() >= 0) {
            throw HostingError("$failure: файл на телефоне длиннее, чем заявлен — загрузи его заново")
        }
        if (sizeOnNode >= 0L && sizeOnNode != total) {
            throw HostingError("$failure: на сервер доехало $sizeOnNode из $total байт — загрузи файл заново")
        }
        HostingActionResult("Файл загружен: $remote")
    }

    // Every stored field is kept: dropping a repeat interval on save would turn an hourly task into a daily one.
    suspend fun scheduleEntries(serverId: String): List<JsonObject> {
        val id = HostingPaths.serverId(serverId)
        return guard("Не удалось получить расписание") {
            auth.authorizedElement("${base(id)}/schedules", "GET", null).asArray("schedules", "items")
        }
    }

    suspend fun replaceScheduleEntries(serverId: String, entries: List<JsonObject>): List<JsonObject> {
        val id = HostingPaths.serverId(serverId)
        return guard("Не удалось сохранить расписание") {
            val payload = buildJsonObject { putJsonArray("schedules") { entries.forEach { add(it) } } }
            auth.authorizedElement("${base(id)}/schedules", "PUT", payload).asArray("schedules", "items")
        }
    }

    private suspend fun toolsJob(serverId: String, tail: String, failure: String): HostingJob {
        val id = HostingPaths.serverId(serverId)
        return guard(failure) {
            val body = auth.authorized("${base(id)}/$tail", "GET", null)
            HostingJob(
                state = body.str("state").ifEmpty { "none" },
                name = body.str("name"),
                error = body.str("error"),
                bytes = body.long("bytes"),
                total = body.long("total"),
            )
        }
    }

    // The shield cuts any request at about two minutes, so an endpoint that honours Range
    // is read as a chain of short requests; one that answers 200 cannot resume after a break.
    private suspend fun downloadRanged(
        path: String,
        sink: OutputStream,
        failure: String,
        onProgress: (Long, Long) -> Unit,
    ): Long = withContext(Dispatchers.IO) {
        var token = toolsToken(failure)
        var renewed = false
        var received = 0L
        var total = -1L
        var piece = TOOLS_PIECE_START
        var failures = 0
        var single = false
        val buffer = ByteArray(TOOLS_COPY_BUFFER)
        while (total < 0L || received < total) {
            ensureActive()
            val started = System.currentTimeMillis()
            val before = received
            try {
                val request = Request.Builder()
                    .url(apiBase + path)
                    .header("Authorization", "Bearer $token")
                    .header("Range", "bytes=$received-${received + piece - 1}")
                    .build()
                client.newCall(request).execute().use { response ->
                    val code = response.code
                    if (code != 200 && code != 206) toolsRefusal(code, response.body?.string().orEmpty(), failure)
                    val body = response.body ?: throw HostingTransientFailure("ответ пришёл пустым")
                    if (code == 200) {
                        if (received > 0L) throw HostingError("$failure: $TOOLS_NO_RESUME")
                        single = !response.header("Accept-Ranges").orEmpty().contains("bytes", ignoreCase = true)
                        total = body.contentLength()
                    } else {
                        val range = TOOLS_CONTENT_RANGE.find(response.header("Content-Range").orEmpty())
                            ?: throw HostingError("$failure: сервер отдал кусок в непонятном виде — повтори")
                        total = range.groupValues[1].toLong()
                    }
                    body.byteStream().use { input ->
                        while (true) {
                            ensureActive()
                            val read = input.read(buffer)
                            if (read < 0) break
                            sink.write(buffer, 0, read)
                            received += read
                            onProgress(received, total)
                        }
                    }
                    if (code == 200) {
                        if (total < 0L) total = received
                        if (received < total) throw HostingError("$failure: файл пришёл не целиком — скачай его заново")
                    } else if (received == before) {
                        throw HostingError("$failure: сервер перестал отдавать данные — повтори")
                    }
                }
                failures = 0
                piece = toolsTunedPiece(piece, received - before, System.currentTimeMillis() - started)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (refused: HostingError) {
                throw refused
            } catch (unauthorized: Unauthorized) {
                if (renewed) throw hostingFailure(failure, unauthorized)
                token = renewedToken(token, failure)
                renewed = true
            } catch (error: IOException) {
                if (single && received > 0L) throw HostingError("$failure: $TOOLS_NO_RESUME", error)
                failures = if (received > before) 1 else failures + 1
                if (failures >= TOOLS_ATTEMPTS) throw toolsNetworkFailure(failure, error)
                piece = maxOf(piece / 2, TOOLS_PIECE_MIN)
                delay(toolsRetryDelay(failures))
            }
        }
        sink.flush()
        received
    }

    private fun toolsToken(failure: String): String =
        vault.read(MillidaAuth.KEY_ACCESS)?.takeIf { it.isNotEmpty() }
            ?: throw HostingError("$failure: ${statusText(401)}")

    private suspend fun renewedToken(spent: String, failure: String): String {
        try {
            auth.profile()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            throw hostingFailure(failure, error)
        }
        val fresh = vault.read(MillidaAuth.KEY_ACCESS)
        if (fresh.isNullOrEmpty() || fresh == spent) throw HostingError("$failure: ${statusText(401)}")
        return fresh
    }

    private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")

    private companion object {
        val MAP_RELEASE = Regex("""^\d+(\.\d+)+$""")
        val MAP_SNAPSHOT = Regex("""^2\d\.\d""")
    }
}

data class HostingPlan(
    val code: String = "",
    val name: String = "",
    val tagline: String = "",
    val ramMb: Int = 0,
    val maxPlayers: Int = 0,
    val playersComfort: Int = 0,
    val diskMb: Int = 0,
    val priceKopecks: Long = 0,
    val currency: String = "RUB",
    val featured: Boolean = false,
    val sleeps: Boolean = false,
    val worldKeepDays: Int = 0,
) {
    val free: Boolean get() = priceKopecks <= 0
}

data class HostingMap(
    val id: Long = 0,
    val name: String = "",
    val summary: String = "",
    val iconUrl: String = "",
    val downloads: Long = 0,
)

data class HostingInstall(
    val id: String = "",
    val kind: String = "",
    val source: String = "",
    val projectId: String = "",
    val versionId: String = "",
    val name: String = "",
    val versionName: String = "",
    val iconUrl: String = "",
    val status: String = "",
    val error: String = "",
)

data class HostingInstallUpdate(
    val installId: String = "",
    val name: String = "",
    val current: String = "",
    val latest: String = "",
    val latestVersionId: String = "",
    val updateAvailable: Boolean = false,
    val compatible: Boolean = true,
)

data class HostingCore(
    val id: String = "",
    val name: String = "",
    val family: String = "",
    val recommended: Boolean = false,
    val versions: List<String> = emptyList(),
    val latest: String = "",
)

data class HostingCatalogHit(
    val id: String = "",
    val name: String = "",
    val summary: String = "",
    val iconUrl: String = "",
    val downloads: Long = 0,
    val author: String = "",
    val minRamMb: Int = 0,
)

data class HostingCatalogPage(val hits: List<HostingCatalogHit>, val nextOffset: Int?)

const val HOSTING_SOURCE_MODRINTH = "modrinth"
const val HOSTING_SOURCE_CURSEFORGE = "curseforge"
const val HOSTING_SOURCE_FTB = "ftb"

val HOSTING_PLAY_KINDS: List<String> = listOf("vanilla", "modpack", "map", "minigames")

val HOSTING_PLAYER_ACTIONS: Set<String> = setOf(
    "kick", "ban", "banIp", "pardon", "whitelistAdd", "whitelistRemove", "op", "deop", "spawn",
    "creative", "survival", "adventure", "spectator", "heal", "clearInventory", "message", "teleport",
)

const val HOSTING_NAME_MIN = 2
const val HOSTING_NAME_MAX = 32

// Java and Bedrock share one rule: an Xbox gamertag may hold single spaces inside.
private val HOSTING_NICKNAME = Regex("""^[A-Za-z0-9_]+(?: [A-Za-z0-9_]+)*$""")

data class HostingToolAccess(
    val fullAccess: Boolean = true,
    val owner: Boolean = true,
    val permissions: List<String> = emptyList(),
    val running: Boolean = false,
    val slug: String = "",
    val address: String = "",
    val customDomain: String = "",
) {
    fun can(permission: String): Boolean = owner || permission in permissions
}

data class HostingJob(
    val state: String = "none",
    val name: String = "",
    val error: String = "",
    val bytes: Long = 0,
    val total: Long = 0,
) {
    val running: Boolean get() = state == "running"
    val finished: Boolean get() = state == "done" || state == "ready"
    val failed: Boolean get() = state == "failed"
}

val HOSTING_WORLD_NAME = Regex("""^[A-Za-z0-9._-]{1,40}$""")

private class HostingTransientFailure(message: String) : IOException(message)

private const val TOOLS_PIECE_START = 8 * 1024 * 1024
private const val TOOLS_PIECE_MIN = 1024 * 1024
private const val TOOLS_PIECE_MAX = 64 * 1024 * 1024
private const val TOOLS_COPY_BUFFER = 64 * 1024
private const val TOOLS_ATTEMPTS = 5
private const val TOOLS_FAST_MS = 10_000L
private const val TOOLS_SLOW_MS = 40_000L
private val TOOLS_RETRY_DELAYS_MS = longArrayOf(1_000L, 3_000L, 6_000L)
private val TOOLS_OCTET_STREAM = "application/octet-stream".toMediaType()
private val TOOLS_CONTENT_RANGE = Regex("""^bytes \d+-\d+/(\d+)$""")
private const val TOOLS_MESSAGE_MAX = 300
private const val TOOLS_NO_RESUME = "файл большой, а связь оборвалась — скачай его через SFTP"
private const val WORLD_EXPORT_POLL_MS = 2_000L
private const val WORLD_EXPORT_WAIT_NS = 60L * 60 * 1_000_000_000
private const val WORLD_EXPORT_MISSES = 5

private fun toolsRetryDelay(failures: Int): Long =
    TOOLS_RETRY_DELAYS_MS[(failures - 1).coerceIn(0, TOOLS_RETRY_DELAYS_MS.size - 1)]

private fun toolsTunedPiece(current: Int, got: Long, elapsedMs: Long): Int = when {
    got < current -> current
    elapsedMs <= TOOLS_FAST_MS -> minOf(current * 2, TOOLS_PIECE_MAX)
    elapsedMs >= TOOLS_SLOW_MS -> maxOf(current / 2, TOOLS_PIECE_MIN)
    else -> current
}

private fun toolsReadFully(source: InputStream, buffer: ByteArray, want: Int): Int {
    var filled = 0
    while (filled < want) {
        val read = source.read(buffer, filled, want - filled)
        if (read < 0) break
        filled += read
    }
    return filled
}

private fun toolsServerMessage(text: String): String? {
    val body = try {
        MojangJson.parseToJsonElement(text) as? JsonObject
    } catch (malformed: Exception) {
        null
    } ?: return null
    val said = when (val raw = body["message"]) {
        null, is JsonNull -> null
        is JsonPrimitive -> raw.content
        is JsonArray -> (raw.firstOrNull() as? JsonPrimitive)?.content
        else -> null
    }?.trim()
    return said?.takeIf { it.isNotEmpty() && it.length <= TOOLS_MESSAGE_MAX }
}

private fun toolsSizeField(text: String): Long {
    val body = try {
        MojangJson.parseToJsonElement(text) as? JsonObject
    } catch (malformed: Exception) {
        null
    } ?: return -1L
    return (body["size"] as? JsonPrimitive)?.content?.toDoubleOrNull()?.toLong() ?: -1L
}

private fun toolsRefusal(code: Int, text: String, failure: String): Nothing {
    if (code == 401) throw Unauthorized()
    val reason = toolsServerMessage(text) ?: statusText(code)
    if (code >= 500 || code == 429 || code == 408) throw HostingTransientFailure(reason)
    throw HostingError("$failure: $reason")
}

private fun toolsNetworkFailure(failure: String, error: IOException): HostingError =
    if (error is HostingTransientFailure) {
        HostingError("$failure: ${error.message}", error)
    } else {
        HostingError("$failure: связь оборвалась — проверь интернет и повтори", error)
    }
