package net.millida.launcher.ui

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.net.http.SslError
import android.view.ViewGroup
import android.webkit.RenderProcessGoneDetail
import android.webkit.SslErrorHandler
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.app.Activity
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.webkit.WebMessageCompat
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import kotlinx.coroutines.delay
import net.millida.launcher.LauncherViewModel
import net.millida.launcher.remoteui.REMOTE_UI_HOST
import net.millida.launcher.remoteui.REMOTE_UI_ORIGIN
import net.millida.launcher.remoteui.RemoteBridge
import net.millida.launcher.remoteui.RemoteUi

private val RemoteBg = Color(0xFF161718)
private const val READY_TIMEOUT_MS = 25_000L

private enum class Phase { LOADING, READY, FAILED }

/** Whether this phone's WebView can carry the bridge; without it the built-in screens stay. */
fun remoteUiSupported(): Boolean = WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)

/**
 * A screen of the website («Экраны как на ПК»). [fallback] is the built-in screen: shown when the module is
 * not verified yet or cannot be fetched, when the WebView lacks the bridge or dies, and when the page does
 * not report ready in time.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun RemoteScreen(screen: String, model: LauncherViewModel, fallback: @Composable () -> Unit) {
    val context = LocalContext.current
    val remote = remember { RemoteUi.get(context) }
    var phase by remember(screen) { mutableStateOf(if (remoteUiSupported()) Phase.LOADING else Phase.FAILED) }
    var version by remember(screen) { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val accent = millida.accent.toArgb() and 0xFFFFFF
    val web = remember(screen) { arrayOfNulls<WebView>(1) }

    // Back first goes through the module's own routes (server page, opened card): __MLU_BACK__ answers true when it
    // took a step. Anything else (root of the module, old module without the hook, no answer) leaves the screen as before.
    BackHandler(enabled = phase == Phase.READY) {
        val leave = {
            if (!model.back()) (context as? Activity)?.moveTaskToBack(true)
            Unit
        }
        val view = web[0]
        if (view == null) leave() else view.evaluateJavascript("(function(){try{return !!(window.__MLU_BACK__&&window.__MLU_BACK__())}catch(e){return false}})()") {
            if (it != "true") leave()
        }
    }

    LaunchedEffect(screen) {
        if (phase == Phase.FAILED) return@LaunchedEffect
        val manifest = remote.prepare()
        if (manifest == null) phase = Phase.FAILED else version = manifest.version
    }
    LaunchedEffect(version) {
        if (version == null) return@LaunchedEffect
        delay(READY_TIMEOUT_MS)
        if (phase == Phase.LOADING) phase = Phase.FAILED
    }

    if (phase == Phase.FAILED) {
        fallback()
        return
    }

    Box(Modifier.fillMaxSize().background(RemoteBg).padding(horizontal = 12.dp).navigationBarsPadding()) {
        version?.let { v ->
            val bridge = remember(screen, v) {
                RemoteBridge(
                    model = model,
                    scope = scope,
                    onReady = { if (phase == Phase.LOADING) phase = Phase.READY },
                    onFail = { crash, _ ->
                        if (crash) remote.markBad(v)
                        if (phase != Phase.READY || crash) phase = Phase.FAILED
                    },
                )
            }
            val url = "$REMOTE_UI_ORIGIN/assets/remote-ui/shell.html?screen=$screen&v=$v&accent=%23" + "%06x".format(accent)
            AndroidView(
                modifier = Modifier.fillMaxSize().alpha(if (phase == Phase.READY) 1f else 0f),
                factory = { ctx ->
                    WebView(ctx).apply {
                        layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                        setBackgroundColor(RemoteBg.toArgb())
                        overScrollMode = WebView.OVER_SCROLL_NEVER
                        settings.apply {
                            javaScriptEnabled = true
                            domStorageEnabled = true
                            allowFileAccess = false
                            allowContentAccess = false
                            setSupportMultipleWindows(false)
                            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                            if (WebViewFeature.isFeatureSupported(WebViewFeature.ALGORITHMIC_DARKENING)) {
                                WebSettingsCompat.setAlgorithmicDarkeningAllowed(this, false)
                            }
                        }
                        webViewClient = object : WebViewClient() {
                            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
                                remote.assetLoader.shouldInterceptRequest(request.url)

                            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                                if (request.url.host == REMOTE_UI_HOST) return false
                                model.openExternalFromRemote(request.url.toString())
                                return true
                            }

                            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                                if (request.isForMainFrame && phase != Phase.READY) phase = Phase.FAILED
                            }

                            override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) = handler.cancel()

                            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                                phase = Phase.FAILED
                                return true
                            }
                        }
                        WebViewCompat.addWebMessageListener(
                            this,
                            "MillidaHost",
                            setOf(REMOTE_UI_ORIGIN),
                        ) { _, message: WebMessageCompat, sourceOrigin, isMainFrame, replyProxy ->
                            if (isMainFrame && sourceOrigin.toString() == REMOTE_UI_ORIGIN) {
                                message.data?.let { bridge.handle(it, replyProxy) }
                            }
                        }
                        web[0] = this
                        loadUrl(url)
                    }
                },
                onRelease = {
                    web[0] = null
                    it.destroy()
                },
            )
        }
        if (phase == Phase.LOADING) {
            Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                repeat(5) { MSkeleton(height = 72.dp) }
            }
        }
    }
}
