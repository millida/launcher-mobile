package net.millida.launcher

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.Process
import android.system.Os
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.movtery.zalithlauncher.bridge.LoggerBridge
import com.movtery.zalithlauncher.bridge.ZLBridge
import com.oracle.dalvik.VMLauncher
import net.millida.launcher.core.bedrock.BEDROCK_PROXY_PORT
import net.millida.launcher.core.bedrock.BedrockBridge
import net.millida.launcher.core.bedrock.parseBedrockAddress
import net.millida.launcher.core.bedrock.viaProxyArguments
import net.millida.launcher.core.runtime.InstalledRuntime
import net.millida.launcher.core.runtime.RuntimeImport
import java.io.File

/**
 * The protocol translator is a desktop Java program, and the virtual machine it
 * needs cannot be started twice in one process - the game already has one. So
 * it lives in a process of its own, started before the game and killed with it.
 */
class BedrockProxyService : Service() {

    private var started = false

    override fun onCreate() {
        super.onCreate()
        prepareChannel()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        val address = intent?.getStringExtra(EXTRA_ADDRESS).orEmpty()
        if (!confirm(address)) return START_NOT_STICKY
        if (started) return START_STICKY
        val target = parseBedrockAddress(address)
        if (target == null) {
            Log.w(TAG, "не разобрал адрес Bedrock: $address")
            stopSelf()
            return START_NOT_STICKY
        }
        started = true
        // The virtual machine never gives the thread back, so it gets its own.
        Thread({ runTranslator(target.host, target.port) }, "millida-bedrock-proxy").start()
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        // Nothing unloads a desktop virtual machine, and a half-dead translator
        // would hold the port the next game start needs.
        if (started) Process.killProcess(Process.myPid())
    }

    override fun onTimeout(startId: Int) {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun runTranslator(host: String, port: Int) {
        val container = Container.get(this)
        val bridge = BedrockBridge(container.layout.root)
        if (!bridge.installed()) {
            Log.w(TAG, "переводчик протокола не установлен")
            stopSelf()
            return
        }
        val runtime = RuntimeImport.installed(container.layout, JAVA_MAJOR)
        if (runtime == null) {
            Log.w(TAG, "нет пакета рантайма с Java $JAVA_MAJOR")
            stopSelf()
            return
        }

        val log = File(bridge.directory, "proxy.log")
        try {
            prepareEnvironment(runtime, bridge.directory)
            System.load(File(runtime.rendererDir, "libpojavexec.so").absolutePath)
            LoggerBridge.start(log.absolutePath)
            LoggerBridge.appendTitle("Millida: перевод протокола Bedrock")
            ZLBridge.setLdLibraryPath(libraryPath(runtime))
            openRuntimeLibraries(runtime)
            ZLBridge.chdir(bridge.directory.absolutePath)

            val argv = arguments(runtime, bridge, host, port)
            LoggerBridge.appendTitle("JVM Args")
            argv.forEach { LoggerBridge.append(it) }
            val code = VMLauncher.launchJVM(argv)
            Log.w(TAG, "переводчик вышел с кодом $code")
        } catch (error: Throwable) {
            Log.e(TAG, "переводчик не запустился: ${error.message}", error)
        }
        stopSelf()
    }

    private fun prepareEnvironment(runtime: InstalledRuntime, workDir: File) {
        val cache = File(cacheDir, "bedrock").apply { mkdirs() }
        mapOf(
            "POJAV_NATIVEDIR" to runtime.rendererDir.absolutePath,
            "JAVA_HOME" to runtime.javaHome.absolutePath,
            "HOME" to workDir.absolutePath,
            "TMPDIR" to cache.absolutePath,
            // ViaProxy keeps its own files next to the run directory, and it is
            // told where that is instead of guessing from the class path.
            "VP_RUN_DIR" to workDir.absolutePath,
            "LD_LIBRARY_PATH" to libraryPath(runtime),
            "PATH" to File(runtime.javaHome, "bin").absolutePath + ":" + (System.getenv("PATH") ?: ""),
        ).forEach { (key, value) ->
            runCatching { Os.setenv(key, value, true) }
                .onFailure { Log.w(TAG, "переменная $key не поставилась: ${it.message}") }
        }
    }

    /**
     * The loader of the virtual machine re-executes itself when it cannot find
     * its own libraries first, and the second run starts without our
     * environment at all.
     */
    private fun libraryPath(runtime: InstalledRuntime): String {
        val lib = File(runtime.javaHome, "lib")
        return listOf(
            File(lib, "server").absolutePath,
            lib.absolutePath,
            File(runtime.javaHome.parentFile, "lib").absolutePath,
            File(lib, "jli").absolutePath,
            runtime.rendererDir.absolutePath,
            applicationInfo.nativeLibraryDir,
            "/system/lib64",
            "/vendor/lib64",
            "/system_ext/lib64",
        ).joinToString(":")
    }

    private fun openRuntimeLibraries(runtime: InstalledRuntime) {
        val lib = File(runtime.javaHome, "lib")
        listOf("libjli.so", "libjvm.so", "libverify.so", "libjava.so", "libnet.so", "libnio.so", "libzip.so")
            .forEach { name ->
                listOf(lib, File(lib, "server"), File(lib, "jli"))
                    .map { File(it, name) }
                    .firstOrNull { it.isFile }
                    ?.let { ZLBridge.dlopen(it.absolutePath) }
            }
    }

    private fun arguments(
        runtime: InstalledRuntime,
        bridge: BedrockBridge,
        host: String,
        port: Int,
    ): Array<String> {
        val cache = File(cacheDir, "bedrock").apply { mkdirs() }
        val out = ArrayList<String>()
        out += File(File(runtime.javaHome, "bin"), "java").absolutePath
        out += "-Djava.home=${runtime.javaHome.absolutePath}"
        out += "-Djava.io.tmpdir=${cache.absolutePath}"
        out += "-Duser.home=${bridge.directory.absolutePath}"
        out += "-Dos.name=Linux"
        out += "-Dos.version=Android-${Build.VERSION.RELEASE}"
        out += "-Djava.awt.headless=true"
        // The translator looks for a new release of itself on start; on a phone
        // that is a wasted request and one more way for the start to hang.
        out += "-DskipUpdateCheck=true"
        // It rewrites its own classes through an agent and attaches to itself
        // to do it; without this the virtual machine refuses the attach and it
        // falls back to a slower loader.
        out += "-Djdk.attach.allowAttachSelf=true"
        out += "-XX:+UseSerialGC"
        out += "-Xmx${HEAP_MB}M"
        out += "-jar"
        out += bridge.jar.absolutePath
        val target = parseBedrockAddress("$host:$port")
            ?: throw IllegalStateException("адрес $host:$port не разобран")
        out += viaProxyArguments(target, bridge.targetVersion())
        return out.toTypedArray()
    }

    private fun confirm(address: String): Boolean {
        val stop = PendingIntent.getService(
            this,
            1,
            Intent(this, BedrockProxyService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        } else {
            0
        }
        return try {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification(address, stop), type)
            true
        } catch (denied: Throwable) {
            Log.w(TAG, "служба переводчика не подтверждена: ${denied.message}")
            stopSelf()
            false
        }
    }

    private fun prepareChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Bedrock", NotificationManager.IMPORTANCE_LOW)
                .also { it.setShowBadge(false) },
        )
    }

    private fun notification(address: String, stop: PendingIntent): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Bedrock через Millida")
            .setContentText(address.ifEmpty { "перевод протокола" })
            .setSmallIcon(net.millida.launcher.runtime.R.drawable.ic_stat_millida)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(0, "Остановить", stop)
            .build()

    companion object {
        private const val TAG = "millida-bedrock"
        private const val CHANNEL_ID = "millida-bedrock"
        private const val NOTIFICATION_ID = 4502
        private const val JAVA_MAJOR = 21
        private const val HEAP_MB = 320
        const val EXTRA_ADDRESS = "address"
        const val ACTION_STOP = "net.millida.launcher.STOP_BEDROCK_PROXY"

        fun start(context: Context, address: String) {
            val intent = Intent(context, BedrockProxyService::class.java)
                .putExtra(EXTRA_ADDRESS, address)
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            }.onFailure { Log.w(TAG, "переводчик не запущен: ${it.message}") }
        }

        fun stop(context: Context) {
            runCatching { context.stopService(Intent(context, BedrockProxyService::class.java)) }
        }

        /** The game connects here, not to the Bedrock server itself. */
        fun localAddress(): String = "127.0.0.1:$BEDROCK_PROXY_PORT"
    }
}
