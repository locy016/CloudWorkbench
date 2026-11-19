package cn.xux.cloudworkbench

import android.content.Context
import android.webkit.ServiceWorkerController
import android.webkit.ServiceWorkerWebSettings
import android.webkit.WebView
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.webkit.ProxyConfig
import androidx.webkit.ProxyController
import androidx.webkit.WebViewFeature
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.resume

internal enum class BrowserMode(val title: String) {
    Cloud("云端代理"), Direct("平板直连")
}

internal data class BrowserRoute(val mode: BrowserMode, val port: Int = 0)

internal class BrowserNetwork {
    var mode by mutableStateOf(BrowserMode.Cloud)
        private set
    var attempt by mutableIntStateOf(0)
        private set
    var failed by mutableStateOf(false)
        private set
    val supported by lazy {
        WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE) &&
            WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)
    }

    private var applied by mutableStateOf<BrowserRoute?>(null)
    private var view: WebView? = null
    private var onPause: (() -> Unit)? = null
    private var workers: ServiceWorkerWebSettings? = null

    fun ready(route: BrowserRoute?) = route != null && route == applied

    fun attach(webView: WebView, onPause: () -> Unit) {
        view = webView
        this.onPause = onPause
        webView.settings.blockNetworkLoads = applied == null
    }

    fun detach(webView: WebView) {
        if (view !== webView) return
        view = null
        onPause = null
    }

    fun select(value: BrowserMode) {
        if (value == mode) return
        pause()
        mode = value
        attempt++
    }

    fun retry() {
        pause()
        attempt++
    }

    fun pause() {
        applied = null
        failed = false
        workers?.blockNetworkLoads = true
        view?.settings?.blockNetworkLoads = true
        onPause?.invoke()
    }

    suspend fun apply(context: Context, route: BrowserRoute?, isCurrent: () -> Boolean) {
        pause()
        if (!supported) return
        try {
            val workerSettings = ServiceWorkerController.getInstance().serviceWorkerWebSettings
            workerSettings.blockNetworkLoads = true
            workers = workerSettings
            if (route == null) return
            val builder = ProxyConfig.Builder()
            when (route.mode) {
                BrowserMode.Cloud -> builder.addProxyRule("http://127.0.0.1:${route.port}").removeImplicitRules()
                BrowserMode.Direct -> builder.addDirect()
            }
            withTimeout(8000) {
                suspendCancellableCoroutine<Unit> { ready ->
                    ProxyController.getInstance().setProxyOverride(builder.build(), ContextCompat.getMainExecutor(context)) {
                        if (ready.isActive) ready.resume(Unit)
                    }
                }
            }
            if (!isCurrent()) return
            workerSettings.blockNetworkLoads = false
            view?.settings?.blockNetworkLoads = false
            applied = route
        } catch (_: kotlinx.coroutines.TimeoutCancellationException) {
            failed = true
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            failed = true
        }
    }
}
