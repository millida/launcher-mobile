package net.millida.launcher

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import net.millida.launcher.core.cabinet.CabinetWalletClient
import net.millida.launcher.core.hosting.HostingClient
import net.millida.launcher.core.hosting.HostingCrash
import net.millida.launcher.core.hosting.HostingError
import net.millida.launcher.core.hosting.HostingFreeGate
import net.millida.launcher.core.hosting.HostingMap
import net.millida.launcher.core.hosting.HostingOnlinePlayer
import net.millida.launcher.core.hosting.HostingPlan
import net.millida.launcher.core.hosting.HostingServerState
import net.millida.launcher.core.hosting.HostingStats
import net.millida.launcher.core.hosting.HostingUsage
import net.millida.launcher.core.hosting.HOSTING_NAME_MAX
import net.millida.launcher.core.hosting.HOSTING_NAME_MIN

enum class ConsoleLink { Idle, Connecting, Live, Waiting, Failed }

enum class ConsoleTone { Command, Error, Warning, Good, Plain }

data class ConsoleEntry(val id: Long, val text: String, val tone: ConsoleTone)

data class HostingPanel(
    val serverId: String = "",
    val powering: Map<String, String> = emptyMap(),
    val plans: List<HostingPlan> = emptyList(),
    val plansLoading: Boolean = false,
    val plansError: String? = null,
    val balanceKopecks: Long? = null,
    val buying: String? = null,
    val maps: List<HostingMap>? = null,
    val mapsError: String? = null,
    val console: List<ConsoleEntry> = emptyList(),
    val consoleLink: ConsoleLink = ConsoleLink.Idle,
    val consoleRetryAt: Long = 0L,
    val consoleError: String? = null,
    val history: List<String> = emptyList(),
    val stats: HostingStats? = null,
    val cpuTrail: List<Float> = emptyList(),
    val memoryTrail: List<Float> = emptyList(),
    val statsStale: Boolean = false,
    val online: List<HostingOnlinePlayer>? = null,
    val onlineError: Boolean = false,
    val playerBusy: String? = null,
    val crash: HostingCrash? = null,
    val usage: HostingUsage? = null,
    val usageDays: Int = 30,
    val usageLoading: Boolean = false,
    val usageError: String? = null,
    val autoRenew: Boolean? = null,
    val renewBusy: Boolean = false,
    val renaming: Boolean = false,
    val deleting: Boolean = false,
    val awaitingTopUp: Boolean = false,
    val freeGate: HostingFreeGate? = null,
    val gateChecking: Boolean = false,
    val savingSetting: String? = null,
)

class HostingModel(
    private val client: HostingClient,
    private val wallet: CabinetWalletClient,
    private val scope: CoroutineScope,
    private val notice: (String, Boolean) -> Unit,
    private val onServerChanged: (String) -> Unit,
    private val onListChanged: () -> Unit,
) {
    private val panel = MutableStateFlow(HostingPanel())
    val state: StateFlow<HostingPanel> = panel.asStateFlow()

    private var consoleJob: Job? = null
    private var liveJob: Job? = null
    private var mapsJob: Job? = null
    private var usageJob: Job? = null
    private var consoleSeq = 0L
    private var liveSeq = 0L
    private var lineSeq = 0L

    fun select(serverId: String) {
        if (panel.value.serverId == serverId) return
        closeConsole()
        stopLive()
        usageJob?.cancel()
        panel.update {
            it.copy(
                serverId = serverId,
                console = emptyList(),
                consoleLink = ConsoleLink.Idle,
                consoleError = null,
                stats = null,
                cpuTrail = emptyList(),
                memoryTrail = emptyList(),
                statsStale = false,
                online = null,
                onlineError = false,
                playerBusy = null,
                crash = null,
                usage = null,
                usageLoading = false,
                usageError = null,
                autoRenew = null,
                renewBusy = false,
                renaming = false,
                deleting = false,
            )
        }
    }

    fun loadPlans(force: Boolean = false) {
        val current = panel.value
        if (current.plansLoading || (!force && current.plans.isNotEmpty())) return
        panel.update { it.copy(plansLoading = true, plansError = null) }
        scope.launch {
            hostingAttempt { client.plans() }
                .onSuccess { plans -> panel.update { it.copy(plans = plans, plansLoading = false) } }
                .onFailure { error -> panel.update { it.copy(plansLoading = false, plansError = loadErrorText(error)) } }
        }
    }

    fun loadBalance() {
        scope.launch {
            hostingAttempt { wallet.balance() }
                .onSuccess { balance -> panel.update { it.copy(balanceKopecks = balance.availableKopecks) } }
        }
    }

    fun awaitTopUp() {
        panel.update { it.copy(awaitingTopUp = true) }
    }

    fun resumed() {
        if (!panel.value.awaitingTopUp) return
        panel.update { it.copy(awaitingTopUp = false) }
        loadBalance()
        onListChanged()
    }

    fun searchMaps(query: String) {
        mapsJob?.cancel()
        panel.update { it.copy(maps = null, mapsError = null) }
        mapsJob = scope.launch {
            delay(MAP_DEBOUNCE_MS)
            hostingAttempt { client.searchMaps(query) }
                .onSuccess { hits -> panel.update { it.copy(maps = hits) } }
                .onFailure { error -> panel.update { it.copy(maps = emptyList(), mapsError = loadErrorText(error)) } }
        }
    }

    fun buy(
        plan: HostingPlan,
        playKind: String,
        name: String,
        map: HostingMap?,
        onCreated: (HostingServerState) -> Unit,
        onNoFunds: () -> Unit,
    ) {
        if (panel.value.buying != null) return
        panel.update { it.copy(buying = plan.code) }
        scope.launch {
            if (plan.free) {
                val gate = hostingAttempt { client.freeGate() }.getOrNull()
                if (gate != null && !gate.passed) {
                    panel.update { it.copy(buying = null, freeGate = gate) }
                    return@launch
                }
            }
            val created = hostingAttempt {
                client.createServer(playKind, plan.code.takeIf { !plan.free }, name.trim().ifEmpty { null })
            }
            val server = created.getOrElse { error ->
                val noFunds = error.isNoFunds()
                val gate = if (plan.free && !noFunds) {
                    hostingAttempt { client.freeGate() }.getOrNull()?.takeIf { !it.passed }
                } else {
                    null
                }
                panel.update { it.copy(buying = null, freeGate = gate ?: it.freeGate) }
                when {
                    gate != null -> Unit
                    noFunds -> {
                        notice(error.fundsText() ?: NO_FUNDS_TEXT, true)
                        onNoFunds()
                    }
                    else -> notice(loadErrorText(error), true)
                }
                return@launch
            }
            val warnings = ArrayList<String>()
            if (plan.free) {
                hostingAttempt { client.updateSetting(server.id, "onlineMode", false) }
                    .onFailure { warnings += "вход без лицензии не включился — включи его в веб-панели" }
            }
            if (map != null) {
                val version = hostingAttempt { client.mapGameVersion(map.id) }.getOrNull()
                if (version != null) {
                    hostingAttempt { client.setServerVersion(server.id, version) }
                        .onFailure { warnings += "версия под карту не выставилась" }
                }
                hostingAttempt { client.installFromCatalog(server.id, map.id.toString(), "curseforge") }
                    .onFailure { warnings += "карта «${map.name}» не поставилась — поставь её в веб-панели" }
            }
            panel.update { it.copy(buying = null) }
            if (warnings.isEmpty()) {
                notice("Сервер создаётся — через минуту он будет готов", false)
            } else {
                notice("Сервер создан, но " + warnings.joinToString("; "), true)
            }
            onListChanged()
            onCreated(server)
        }
    }

    fun changePlan(serverId: String, plan: HostingPlan, onDone: () -> Unit, onNoFunds: () -> Unit) {
        if (panel.value.buying != null) return
        panel.update { it.copy(buying = plan.code) }
        scope.launch {
            hostingAttempt { client.changePlan(serverId, plan.code) }
                .onSuccess { result ->
                    notice("${result.message}: «${plan.name}»", false)
                    onServerChanged(serverId)
                    onDone()
                }
                .onFailure { error ->
                    if (error.isNoFunds()) {
                        notice(error.fundsText() ?: NO_FUNDS_TEXT, true)
                        onNoFunds()
                    } else {
                        notice(loadErrorText(error), true)
                    }
                }
            panel.update { it.copy(buying = null) }
        }
    }

    fun recheckFreeGate(onPassed: () -> Unit) {
        if (panel.value.gateChecking) return
        panel.update { it.copy(gateChecking = true) }
        scope.launch {
            hostingAttempt { client.recheckFreeGate() }
                .onSuccess { gate ->
                    if (gate.passed) {
                        panel.update { it.copy(freeGate = null, gateChecking = false) }
                        onPassed()
                        return@launch
                    }
                    panel.update { it.copy(freeGate = gate) }
                    notice(
                        if (gate.botStarted) {
                            "Подписки пока не видно — подпишись на канал и нажми «Проверить»"
                        } else {
                            "Бот ещё не запущен — открой его и нажми «Старт»"
                        },
                        true,
                    )
                }
                .onFailure { error -> notice(loadErrorText(error), true) }
            panel.update { it.copy(gateChecking = false) }
        }
    }

    fun closeFreeGate() {
        panel.update { it.copy(freeGate = null) }
    }

    fun power(serverId: String, action: String) {
        if (serverId.isEmpty() || panel.value.powering.containsKey(serverId)) return
        panel.update { it.copy(powering = it.powering + (serverId to action)) }
        scope.launch {
            val outcome = hostingAttempt {
                when (action) {
                    START -> client.start(serverId)
                    STOP -> client.stop(serverId)
                    RESTART -> client.restart(serverId)
                    KILL -> client.kill(serverId)
                    else -> throw HostingError("Такой команды у сервера нет")
                }
            }
            panel.update { it.copy(powering = it.powering - serverId) }
            outcome
                .onSuccess { result -> notice(result.message, false) }
                .onFailure { error -> notice(loadErrorText(error), true) }
            onServerChanged(serverId)
            if (outcome.isSuccess) {
                delay(FOLLOW_UP_MS)
                onServerChanged(serverId)
            }
        }
    }

    fun startLive(serverId: String) {
        liveJob?.cancel()
        val ticket = ++liveSeq
        liveJob = scope.launch {
            var tick = 0
            while (ticket == liveSeq) {
                val stats = hostingAttempt { client.stats(serverId) }
                if (ticket != liveSeq) break
                stats
                    .onSuccess { value -> writeFor(serverId) { it.withStats(value) } }
                    .onFailure { writeFor(serverId) { it.copy(statsStale = true) } }
                val running = stats.getOrNull()?.running ?: false
                if (tick % ONLINE_EVERY_TICKS == 0) {
                    if (running) {
                        val players = hostingAttempt { client.online(serverId) }
                        if (ticket == liveSeq) {
                            players
                                .onSuccess { list -> writeFor(serverId) { it.copy(online = list, onlineError = false) } }
                                .onFailure { writeFor(serverId) { it.copy(onlineError = true) } }
                        }
                    } else {
                        writeFor(serverId) { it.copy(online = emptyList(), onlineError = false) }
                    }
                }
                tick += 1
                delay(LIVE_PERIOD_MS)
            }
        }
    }

    fun stopLive() {
        liveSeq += 1
        liveJob?.cancel()
        liveJob = null
    }

    fun loadCrash(serverId: String) {
        scope.launch {
            hostingAttempt { client.crash(serverId) }
                .onSuccess { crash -> writeFor(serverId) { it.copy(crash = crash) } }
        }
    }

    fun clearCrash() {
        if (panel.value.crash != null) panel.update { it.copy(crash = null) }
    }

    fun playerAction(serverId: String, nickname: String, action: String, done: String) {
        if (panel.value.playerBusy != null) return
        panel.update { it.copy(playerBusy = "$nickname:$action") }
        scope.launch {
            hostingAttempt { client.playerAction(serverId, nickname, action) }
                .onSuccess { notice(done, false) }
                .onFailure { error -> notice(loadErrorText(error), true) }
            panel.update { it.copy(playerBusy = null) }
            hostingAttempt { client.online(serverId) }
                .onSuccess { players -> writeFor(serverId) { it.copy(online = players, onlineError = false) } }
                .onFailure { writeFor(serverId) { it.copy(onlineError = true) } }
        }
    }

    // The node replays the log tail on each connect, so that tail replaces the buffer instead of being appended twice.
    fun openConsole(serverId: String) {
        consoleJob?.cancel()
        val ticket = ++consoleSeq
        consoleJob = scope.launch {
            var failures = 0
            while (ticket == consoleSeq) {
                linkFor(ticket, ConsoleLink.Connecting, null, 0L)
                val batch = ConsoleBatch()
                var received = false
                var firstLine = true
                val flusher = launch {
                    while (true) {
                        delay(FLUSH_MS)
                        flushConsole(ticket, serverId, batch)
                    }
                }
                val failure: Throwable? = try {
                    client.console.stream(serverId).collect { line ->
                        if (!received) {
                            received = true
                            failures = 0
                            linkFor(ticket, ConsoleLink.Live, null, 0L)
                        }
                        if (firstLine) {
                            firstLine = false
                            batch.replace = line.replay
                        }
                        batch.lines += consoleEntry(line.text)
                    }
                    null
                } catch (cancelled: CancellationException) {
                    flusher.cancel()
                    throw cancelled
                } catch (error: Throwable) {
                    error
                }
                flusher.cancel()
                flushConsole(ticket, serverId, batch)
                if (ticket != consoleSeq) break
                if (failure != null || !received) failures += 1
                if (failures > CONSOLE_MAX_FAILURES) {
                    linkFor(ticket, ConsoleLink.Failed, failure?.let(::loadErrorText) ?: CONSOLE_SILENT_TEXT, 0L)
                    break
                }
                val wait = (CONSOLE_BACKOFF_MS shl (failures - 1).coerceIn(0, 4)).coerceAtMost(CONSOLE_BACKOFF_CAP_MS)
                val reason = failure?.let(::loadErrorText)
                linkFor(ticket, ConsoleLink.Waiting, reason, System.currentTimeMillis() + wait)
                delay(wait)
            }
        }
    }

    fun closeConsole() {
        consoleSeq += 1
        consoleJob?.cancel()
        consoleJob = null
        if (panel.value.consoleLink != ConsoleLink.Idle) {
            panel.update { it.copy(consoleLink = ConsoleLink.Idle, consoleRetryAt = 0L) }
        }
    }

    fun clearConsole(serverId: String) {
        writeFor(serverId) { it.copy(console = emptyList()) }
    }

    fun sendCommand(serverId: String, command: String, onSent: () -> Unit) {
        val text = command.trim()
        if (text.isEmpty()) return
        appendLocal(serverId, "> $text", ConsoleTone.Command)
        panel.update { current ->
            current.copy(history = (listOf(text) + current.history.filter { it != text }).take(HISTORY_LIMIT))
        }
        scope.launch {
            hostingAttempt { client.sendCommand(serverId, text) }
                .onSuccess { onSent() }
                .onFailure { error ->
                    val reason = loadErrorText(error)
                    appendLocal(serverId, "Ошибка: команда не ушла — $reason", ConsoleTone.Error)
                    notice(reason, true)
                }
        }
    }

    fun loadUsage(serverId: String, days: Int) {
        usageJob?.cancel()
        panel.update { it.copy(usageDays = days, usageLoading = true, usageError = null) }
        usageJob = scope.launch {
            hostingAttempt { client.usage(serverId, days) }
                .onSuccess { usage ->
                    writeFor(serverId) { if (it.usageDays == days) it.copy(usage = usage, usageLoading = false) else it }
                }
                .onFailure { error ->
                    writeFor(serverId) {
                        if (it.usageDays == days) it.copy(usageLoading = false, usageError = loadErrorText(error)) else it
                    }
                }
        }
    }

    fun setAutoRenew(serverId: String, enabled: Boolean) {
        if (panel.value.renewBusy) return
        panel.update { it.copy(renewBusy = true, autoRenew = enabled) }
        scope.launch {
            hostingAttempt { client.setAutoRenew(serverId, enabled) }
                .onSuccess {
                    notice(if (enabled) "Продление включено" else "Продление выключено — оплаченный срок остаётся", false)
                    onServerChanged(serverId)
                }
                .onFailure { error ->
                    writeFor(serverId) { it.copy(autoRenew = !enabled) }
                    notice(loadErrorText(error), true)
                }
            panel.update { it.copy(renewBusy = false) }
        }
    }

    fun rename(serverId: String, name: String, onDone: () -> Unit) {
        val title = name.trim()
        if (title.length !in NAME_RANGE) {
            notice("Имя сервера — от ${NAME_RANGE.first} до ${NAME_RANGE.last} символов", true)
            return
        }
        if (panel.value.renaming) return
        panel.update { it.copy(renaming = true) }
        scope.launch {
            hostingAttempt { client.rename(serverId, title) }
                .onSuccess { result ->
                    notice(result.message, false)
                    onServerChanged(serverId)
                    onDone()
                }
                .onFailure { error -> notice(loadErrorText(error), true) }
            panel.update { it.copy(renaming = false) }
        }
    }

    /** One rule at a time, as the desktop panel saves them; the confirmation comes only after the service accepted it. */
    fun saveSetting(serverId: String, key: String, value: JsonElement, running: Boolean) {
        if (panel.value.savingSetting != null) return
        panel.update { it.copy(savingSetting = key) }
        scope.launch {
            hostingAttempt { client.updateSettings(serverId, buildJsonObject { put(key, value) }) }
                .onSuccess {
                    notice(if (running) "Сохранено — применится после перезапуска" else "Настройки сохранены", false)
                    onServerChanged(serverId)
                }
                .onFailure { error -> notice("Не удалось сохранить: " + loadErrorText(error), true) }
            panel.update { it.copy(savingSetting = null) }
        }
    }

    fun deleteServer(serverId: String, onDeleted: () -> Unit) {
        if (panel.value.deleting) return
        panel.update { it.copy(deleting = true) }
        scope.launch {
            hostingAttempt { client.deleteServer(serverId) }
                .onSuccess { result ->
                    notice(result.message, false)
                    onListChanged()
                    onDeleted()
                }
                .onFailure { error -> notice(loadErrorText(error), true) }
            panel.update { it.copy(deleting = false) }
        }
    }

    private fun HostingPanel.withStats(value: HostingStats): HostingPanel {
        val memory = if (value.memoryLimitMb > 0) value.memoryUsedMb * 100f / value.memoryLimitMb else 0f
        return copy(
            stats = value,
            statsStale = false,
            cpuTrail = (cpuTrail + value.cpuPercent.toFloat().coerceIn(0f, 100f)).takeLast(TRAIL_LIMIT),
            memoryTrail = (memoryTrail + memory.coerceIn(0f, 100f)).takeLast(TRAIL_LIMIT),
        )
    }

    private fun writeFor(serverId: String, change: (HostingPanel) -> HostingPanel) {
        panel.update { if (it.serverId == serverId) change(it) else it }
    }

    private fun linkFor(ticket: Long, link: ConsoleLink, error: String?, retryAt: Long) {
        if (ticket != consoleSeq) return
        panel.update { it.copy(consoleLink = link, consoleError = error, consoleRetryAt = retryAt) }
    }

    private fun flushConsole(ticket: Long, serverId: String, batch: ConsoleBatch) {
        if (batch.lines.isEmpty()) return
        val lines = batch.lines.toList()
        val replace = batch.replace
        batch.lines.clear()
        batch.replace = false
        if (ticket != consoleSeq) return
        writeFor(serverId) { current ->
            val merged = if (replace) lines else current.console + lines
            current.copy(console = merged.takeLast(CONSOLE_LIMIT))
        }
    }

    private fun appendLocal(serverId: String, text: String, tone: ConsoleTone) {
        lineSeq += 1
        val entry = ConsoleEntry(lineSeq, text, tone)
        writeFor(serverId) { it.copy(console = (it.console + entry).takeLast(CONSOLE_LIMIT)) }
    }

    private fun consoleEntry(raw: String): ConsoleEntry {
        lineSeq += 1
        val text = raw.replace(ANSI_ESCAPE, "").replace(COLOR_CODE, "")
        return ConsoleEntry(lineSeq, text, consoleTone(text))
    }

    private class ConsoleBatch {
        val lines = ArrayList<ConsoleEntry>()
        var replace = false
    }

    companion object {
        const val START = "start"
        const val STOP = "stop"
        const val RESTART = "restart"
        const val KILL = "kill"

        private const val NO_FUNDS_TEXT = "Не хватает средств на балансе Millida — пополни и повтори"
        private const val FUNDS_LEAD = "Не хватает"
        private const val CONSOLE_SILENT_TEXT = "Консоль не отвечает — проверь, что сервер запущен, и подключись снова"
        private const val MAP_DEBOUNCE_MS = 350L
        private const val FOLLOW_UP_MS = 2_500L
        private const val LIVE_PERIOD_MS = 5_000L
        private const val ONLINE_EVERY_TICKS = 2
        private const val TRAIL_LIMIT = 24
        private const val FLUSH_MS = 90L
        private const val CONSOLE_LIMIT = 1_500
        private const val CONSOLE_MAX_FAILURES = 6
        private const val CONSOLE_BACKOFF_MS = 1_000L
        private const val CONSOLE_BACKOFF_CAP_MS = 15_000L
        private const val HISTORY_LIMIT = 20
        private val NAME_RANGE = HOSTING_NAME_MIN..HOSTING_NAME_MAX
        private val ANSI_ESCAPE = Regex("""\u001B\[[0-9;?]*[A-Za-z]""")
        private val COLOR_CODE = Regex("""§[0-9a-fk-orA-FK-OR]""")
        private val NO_FUNDS = Regex("баланс|средств|insufficient|not enough|hosting:funds", RegexOption.IGNORE_CASE)
        private val ERROR_LINE = Regex("""\b(ERROR|SEVERE|FATAL|Exception|Caused by)\b""")
        private val WARN_LINE = Regex("""\bWARN(ING)?\b""")
        private val GOOD_LINE = Regex("""joined the game|left the game|Done \(|Starting minecraft server""", RegexOption.IGNORE_CASE)

        fun consoleTone(text: String): ConsoleTone = when {
            text.startsWith("> ") -> ConsoleTone.Command
            text.startsWith("Ошибка: ") || ERROR_LINE.containsMatchIn(text) -> ConsoleTone.Error
            WARN_LINE.containsMatchIn(text) -> ConsoleTone.Warning
            GOOD_LINE.containsMatchIn(text) -> ConsoleTone.Good
            else -> ConsoleTone.Plain
        }

        private fun Throwable.isNoFunds(): Boolean =
            generateSequence(this) { current -> current.cause?.takeIf { it !== current } }
                .take(8)
                .any { NO_FUNDS.containsMatchIn(it.message.orEmpty()) }

        private fun Throwable.fundsText(): String? =
            generateSequence(this) { current -> current.cause?.takeIf { it !== current } }
                .take(8)
                .mapNotNull { it.message?.trim() }
                .firstNotNullOfOrNull { text ->
                    when {
                        text.startsWith(FUNDS_LEAD) -> text
                        else -> text.substringAfter(": ", "").takeIf { it.startsWith(FUNDS_LEAD) }
                    }
                }
    }
}

private inline fun <T> hostingAttempt(block: () -> T): Result<T> = try {
    Result.success(block())
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (error: Throwable) {
    Result.failure(error)
}
