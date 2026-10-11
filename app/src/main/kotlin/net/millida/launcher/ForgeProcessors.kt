package net.millida.launcher

import android.app.ActivityManager
import android.app.ForegroundServiceStartNotAllowedException
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.Process
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import net.millida.launcher.core.GameLayout
import net.millida.launcher.core.SafePath
import net.millida.launcher.core.loader.ForgePlanFile
import net.millida.launcher.core.loader.ForgeProcessorPlan
import net.millida.launcher.core.loader.ForgeProcessorRunner
import net.millida.launcher.core.runtime.InstalledRuntime
import net.millida.launcher.runtime.RuntimeBridge
import java.io.File
import java.io.IOException

/** What the driver wrote so far: the last step it reached and how it ended. */
internal data class ProcessorStatus(
    val done: Int,
    val total: Int,
    val current: String,
    val finished: Boolean,
    val failure: String?,
)

internal fun readProcessorStatus(lines: List<String>): ProcessorStatus {
    var done = 0
    var total = 0
    var current = ""
    var finished = false
    var failure: String? = null
    for (line in lines) {
        val parts = line.split(' ', limit = 4)
        when (parts.firstOrNull()) {
            "start" -> {
                done = (parts.getOrNull(1)?.toIntOrNull() ?: 1) - 1
                total = parts.getOrNull(2)?.toIntOrNull() ?: total
                current = parts.getOrNull(3).orEmpty()
            }
            "skip", "done" -> {
                done = parts.getOrNull(1)?.toIntOrNull() ?: done
                total = parts.getOrNull(2)?.toIntOrNull() ?: total
            }
            "ok" -> finished = true
            "fail" -> {
                finished = true
                failure = line.removePrefix("fail").trim().ifEmpty { "без описания" }
            }
        }
    }
    return ProcessorStatus(done, total, current, finished, failure)
}

/**
 * Runs the Forge and NeoForge patch steps on the phone. The desktop Java
 * machine can start only once per process and the launcher process must never
 * hold one, so the steps go to a service in a process of its own, and this side
 * follows the status file the driver writes.
 */
class AndroidProcessorRunner(
    private val context: Context,
    private val layout: GameLayout,
    private val runtime: suspend () -> InstalledRuntime,
    private val heapMb: () -> Int,
) : ForgeProcessorRunner {

    override suspend fun run(plan: ForgeProcessorPlan, onStep: (done: Int, total: Int) -> Unit) {
        if (plan.jobs.isEmpty()) return
        val java = try {
            runtime()
        } catch (error: IOException) {
            throw IOException("для установки загрузчика нужен пакет рантайма: ${error.message}", error)
        }
        val work = File(File(layout.caches, "loaders"), "${plan.versionId}-run").apply {
            deleteRecursively()
            mkdirs()
        }
        val driver = withContext(Dispatchers.IO) { copyDriver(work) }
        val planFile = File(work, "plan.bin")
        val status = File(work, "status.txt")
        val log = File(layout.logs, "install-${plan.versionId}.log")
        withContext(Dispatchers.IO) { ForgePlanFile.write(plan, planFile) }

        val job = ForgeProcessorService.Job(
            javaHome = java.javaHome.absolutePath,
            driver = driver.absolutePath,
            plan = planFile.absolutePath,
            status = status.absolutePath,
            log = log.absolutePath,
            workDir = work.absolutePath,
            heapMb = heapMb(),
        )
        try {
            startWhenAllowed(job)
            follow(status, log, plan.jobs.size, onStep)
        } finally {
            ForgeProcessorService.stop(context)
            work.deleteRecursively()
        }
    }

    /**
     * The libraries before this step download for minutes, and a player who
     * switched apps meanwhile left the launcher in the background, where
     * Android 12+ refuses a foreground service start. The run waits for the
     * launcher to come back instead of failing the whole install.
     */
    private suspend fun startWhenAllowed(job: ForgeProcessorService.Job) {
        val deadline = System.currentTimeMillis() + BACKGROUND_WAIT_MS
        while (!ForgeProcessorService.start(context, job)) {
            if (System.currentTimeMillis() > deadline) {
                throw IOException(
                    "установка загрузчика ждала, пока лаунчер откроют: Android не даёт начать её в свёрнутом приложении. " +
                        "Открой лаунчер и повтори установку",
                )
            }
            delay(BACKGROUND_RETRY_MS)
        }
    }

    private suspend fun follow(status: File, log: File, total: Int, onStep: (Int, Int) -> Unit) {
        val started = System.currentTimeMillis()
        var seenAlive = false
        var reported = -1
        while (true) {
            val state = withContext(Dispatchers.IO) {
                readProcessorStatus(if (status.isFile) status.readLines() else emptyList())
            }
            if (state.done != reported) {
                reported = state.done
                onStep(state.done, state.total.takeIf { it > 0 } ?: total)
            }
            if (state.finished) {
                state.failure?.let {
                    throw IOException(
                        "установщик загрузчика остановился на шаге ${state.done + 1} из ${state.total}: $it. " +
                            "Журнал: ${log.name}",
                    )
                }
                return
            }
            val alive = ForgeProcessorService.isAlive(context)
            seenAlive = seenAlive || alive
            val waited = System.currentTimeMillis() - started
            if (!alive && (seenAlive || waited > START_WAIT_MS)) {
                val step = if (state.current.isEmpty()) "" else " (${state.current})"
                throw IOException(
                    "установщик загрузчика закрылся на шаге ${state.done + 1} из ${state.total.takeIf { it > 0 } ?: total}$step — " +
                        "скорее всего, не хватило памяти. Закрой другие приложения и повтори. Журнал: ${log.name}",
                )
            }
            if (waited > RUN_LIMIT_MS) {
                throw IOException("установщик загрузчика не закончил за ${RUN_LIMIT_MS / 60_000} минут — повтори установку")
            }
            delay(POLL_MS)
        }
    }

    private fun copyDriver(work: File): File {
        val target = File(work, DRIVER_ASSET)
        context.assets.open(DRIVER_ASSET).use { source ->
            target.outputStream().use { out -> source.copyTo(out) }
        }
        return target
    }

    private companion object {
        const val DRIVER_ASSET = "millida-processors.jar"
        const val POLL_MS = 400L
        const val START_WAIT_MS = 30_000L
        const val RUN_LIMIT_MS = 40 * 60_000L
        const val BACKGROUND_WAIT_MS = 15 * 60_000L
        const val BACKGROUND_RETRY_MS = 1_000L
    }
}

/**
 * Hosts the Java machine for one install. It is a foreground service because a
 * patch run takes minutes, and a background process is taken away by the
 * system long before that when the player switches apps.
 */
class ForgeProcessorService : Service() {

    data class Job(
        val javaHome: String,
        val driver: String,
        val plan: String,
        val status: String,
        val log: String,
        val workDir: String,
        val heapMb: Int,
    )

    private var started = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (!confirm()) return START_NOT_STICKY
        if (started) return START_NOT_STICKY
        val job = intent?.let(::jobOf)
        if (job == null) {
            Log.w(TAG, "задание установщика отклонено")
            stopSelf()
            return START_NOT_STICKY
        }
        started = true
        Thread({ execute(job) }, "millida-processors").start()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        if (started) Process.killProcess(Process.myPid())
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    /** The service takes paths only inside the application's own storage. */
    private fun jobOf(intent: Intent): Job? {
        val root = File(applicationInfo.dataDir)
        val paths = listOf(EXTRA_JAVA, EXTRA_DRIVER, EXTRA_PLAN, EXTRA_STATUS, EXTRA_LOG, EXTRA_WORK)
            .associateWith { intent.getStringExtra(it).orEmpty() }
        if (paths.values.any { it.isEmpty() || !SafePath.isInside(root, File(it)) }) return null
        val heap = intent.getIntExtra(EXTRA_HEAP, 0).takeIf { it in MIN_HEAP_MB..MAX_HEAP_MB } ?: return null
        return Job(
            javaHome = paths.getValue(EXTRA_JAVA),
            driver = paths.getValue(EXTRA_DRIVER),
            plan = paths.getValue(EXTRA_PLAN),
            status = paths.getValue(EXTRA_STATUS),
            log = paths.getValue(EXTRA_LOG),
            workDir = paths.getValue(EXTRA_WORK),
            heapMb = heap,
        )
    }

    private fun execute(job: Job) {
        val status = File(job.status)
        val ok = try {
            File(job.log).parentFile?.mkdirs()
            val libraries = File(job.javaHome, "lib").absolutePath
            RuntimeBridge.startGame(
                javaHome = job.javaHome,
                jvmArgs = arrayOf(
                    "-Djava.class.path=${job.driver}",
                    "-Djava.library.path=$libraries",
                    "-Djava.io.tmpdir=${job.workDir}",
                    "-Duser.home=${job.workDir}",
                    "-Djava.awt.headless=true",
                    "-XX:+UseSerialGC",
                    "-Xmx${job.heapMb}M",
                ),
                mainClass = DRIVER_MAIN,
                gameArgs = arrayOf(job.plan, job.status),
                runtimeDir = File(job.javaHome).parentFile?.absolutePath.orEmpty(),
                workDir = job.workDir,
                logPath = job.log,
            )
        } catch (error: Throwable) {
            Log.e(TAG, "установщик не запустился: ${error.message}", error)
            appendFailure(status, "виртуальная машина Java не запустилась: ${error.message}")
            false
        }
        if (!ok && !readProcessorStatus(status.readLinesOrEmpty()).finished) {
            appendFailure(status, "виртуальная машина Java не запустилась")
        }
        stopSelf()
    }

    private fun appendFailure(status: File, text: String) {
        runCatching { status.appendText("fail ${text.replace('\n', ' ')}\n") }
            .onFailure { Log.e(TAG, "статус установщика не записан: ${it.message}") }
    }

    private fun File.readLinesOrEmpty(): List<String> = runCatching { readLines() }.getOrDefault(emptyList())

    private fun confirm(): Boolean {
        prepareChannel()
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0
        return try {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification(), type)
            true
        } catch (denied: Exception) {
            Log.w(TAG, "служба установщика не подтверждена: ${denied.message}")
            stopSelf()
            false
        }
    }

    private fun prepareChannel() {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Установка загрузчика", NotificationManager.IMPORTANCE_LOW)
                .also { it.setShowBadge(false) },
        )
    }

    private fun notification(): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Ставим загрузчик")
            .setContentText("Собираем клиент — это займёт пару минут")
            .setSmallIcon(net.millida.launcher.runtime.R.drawable.ic_stat_millida)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

    companion object {
        private const val TAG = "millida-processors"
        private const val CHANNEL_ID = "millida-processors"
        private const val NOTIFICATION_ID = 4504
        private const val PROCESS_SUFFIX = ":installer"
        private const val DRIVER_MAIN = "net.millida.launcher.processors.ProcessorDriver"
        private const val ACTION_STOP = "net.millida.launcher.STOP_PROCESSORS"
        private const val EXTRA_JAVA = "java"
        private const val EXTRA_DRIVER = "driver"
        private const val EXTRA_PLAN = "plan"
        private const val EXTRA_STATUS = "status"
        private const val EXTRA_LOG = "log"
        private const val EXTRA_WORK = "work"
        private const val EXTRA_HEAP = "heap"
        const val MIN_HEAP_MB = 384
        const val MAX_HEAP_MB = 2048

        /** False while Android refuses the start because the launcher is in the background. */
        fun start(context: Context, job: Job): Boolean {
            val intent = Intent(context, ForgeProcessorService::class.java)
                .putExtra(EXTRA_JAVA, job.javaHome)
                .putExtra(EXTRA_DRIVER, job.driver)
                .putExtra(EXTRA_PLAN, job.plan)
                .putExtra(EXTRA_STATUS, job.status)
                .putExtra(EXTRA_LOG, job.log)
                .putExtra(EXTRA_WORK, job.workDir)
                .putExtra(EXTRA_HEAP, job.heapMb)
            try {
                context.startForegroundService(intent)
                return true
            } catch (refused: Exception) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && refused is ForegroundServiceStartNotAllowedException) {
                    return false
                }
                throw IOException("служба установщика не запустилась: ${refused.message}", refused)
            }
        }

        // Stopping goes through the service's own queue: stopping it before
        // startForeground ran makes the system kill the app for a broken promise.
        fun stop(context: Context) {
            if (!isAlive(context)) return
            try {
                context.startService(Intent(context, ForgeProcessorService::class.java).setAction(ACTION_STOP))
            } catch (refused: Exception) {
                Log.w(TAG, "служба установщика не остановлена: ${refused.message}")
            }
        }

        fun isAlive(context: Context): Boolean {
            val manager = context.getSystemService(ActivityManager::class.java) ?: return false
            val name = context.packageName + PROCESS_SUFFIX
            return manager.runningAppProcesses.orEmpty().any { it.processName == name }
        }

        /** Enough for binary patching of the client, never more than the phone can spare. */
        fun heapFor(availableMb: Int): Int =
            (availableMb - SYSTEM_RESERVE_MB).coerceIn(MIN_HEAP_MB, DEFAULT_HEAP_MB)

        private const val SYSTEM_RESERVE_MB = 900
        private const val DEFAULT_HEAP_MB = 1024
    }
}
