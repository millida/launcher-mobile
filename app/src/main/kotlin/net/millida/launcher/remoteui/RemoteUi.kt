package net.millida.launcher.remoteui

import android.content.Context
import android.webkit.CookieManager
import android.webkit.WebResourceResponse
import androidx.webkit.WebViewAssetLoader
import androidx.webkit.WebViewAssetLoader.AssetsPathHandler
import androidx.webkit.WebViewAssetLoader.PathHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.millida.launcher.core.http.Downloader
import net.millida.launcher.core.remoteui.RemoteUiHttp
import net.millida.launcher.core.remoteui.RemoteUiManifest
import net.millida.launcher.core.remoteui.RemoteUiRules
import net.millida.launcher.core.remoteui.RemoteUiStore
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import java.io.ByteArrayInputStream
import java.io.File

/** The page origin of the screens: the shell and the verified module live here, nothing else does. */
const val REMOTE_UI_HOST = "appassets.androidplatform.net"
const val REMOTE_UI_ORIGIN = "https://$REMOTE_UI_HOST"

/**
 * Website screens inside the app: the verified copy of the signed module (core/remoteui) handed to a
 * WebView through WebViewAssetLoader, so the page, the module and its chunks share one origin and the
 * ES modules load by relative paths. Only bytes that matched the signature and the sha256 are served.
 */
class RemoteUi private constructor(context: Context) {
    private val app = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // The edge in front of the site hands out a cookie ticket: it goes to the WebView too, for the images of the screens.
    private val client = Downloader.shared().newBuilder().cookieJar(WebViewCookies).build()
    val store = RemoteUiStore(File(app.filesDir, "remote-ui"), RemoteUiHttp(client), base = devBase(app))

    @Volatile
    private var lastCheck = 0L

    /**
     * The manifest to start a screen with: the verified copy at once, or (first start) one download.
     * With a copy on disk the site is looked at in the background; a newer version is taken at the next opening.
     */
    suspend fun prepare(): RemoteUiManifest? = withContext(Dispatchers.IO) {
        val cached = store.cached()
        if (cached == null) {
            store.refresh(null)?.also { lastCheck = System.currentTimeMillis() }
        } else {
            val now = System.currentTimeMillis()
            if (now - lastCheck > CHECK_EVERY_MS) {
                lastCheck = now
                scope.launch { store.refresh(cached.version) }
            }
            cached
        }
    }

    fun markBad(version: String) {
        scope.launch { store.markBad(version) }
    }

    val assetLoader: WebViewAssetLoader = WebViewAssetLoader.Builder()
        .setDomain(REMOTE_UI_HOST)
        .addPathHandler("/assets/", AssetsPathHandler(app))
        .addPathHandler("/ui/", ModuleHandler())
        .build()

    /** `/ui/<version>/<name>`: a file of the verified module, from disk or fetched once and checked. */
    private inner class ModuleHandler : PathHandler {
        override fun handle(path: String): WebResourceResponse {
            val parts = path.split('/')
            val bytes = if (parts.size == 2) runCatching { store.open(parts[0], parts[1]) }.getOrNull() else null
            if (bytes == null) {
                return WebResourceResponse("text/plain", "utf-8", 404, "Not Found", emptyMap(), ByteArrayInputStream(ByteArray(0)))
            }
            val mime = if (parts[1].endsWith(".css")) "text/css" else "text/javascript"
            return WebResourceResponse(
                mime, "utf-8", 200, "OK",
                mapOf("Cache-Control" to "no-store", "X-Content-Type-Options" to "nosniff"),
                ByteArrayInputStream(bytes),
            )
        }
    }

    companion object {
        /** Debug builds only: `filesDir/remote-ui-base.txt` points the module at a local stand (the signature is still checked). */
        private fun devBase(app: Context): String {
            if (!net.millida.launcher.BuildConfig.TEST_HOOKS) return RemoteUiRules.BASE
            val file = File(app.filesDir, "remote-ui-base.txt")
            return runCatching { file.readText().trim() }.getOrNull()?.takeIf { it.startsWith("http") } ?: RemoteUiRules.BASE
        }

        private const val CHECK_EVERY_MS = 10 * 60 * 1000L

        @Volatile
        private var instance: RemoteUi? = null

        fun get(context: Context): RemoteUi = instance ?: synchronized(this) {
            instance ?: RemoteUi(context).also { instance = it }
        }
    }
}

/** OkHttp cookies kept in the WebView's own jar: one ticket for the app's requests and for the page. */
private object WebViewCookies : CookieJar {
    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        val manager = CookieManager.getInstance()
        cookies.forEach { manager.setCookie(url.toString(), it.toString()) }
        manager.flush()
    }

    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        val header = CookieManager.getInstance().getCookie(url.toString()) ?: return emptyList()
        return header.split(';').mapNotNull { Cookie.parse(url, it.trim()) }
    }
}
