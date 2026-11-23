package cn.xux.cloudworkbench

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import android.os.SystemClock
import android.view.View
import android.view.ViewOutlineProvider
import android.webkit.*
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.zIndex
import androidx.webkit.WebViewCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.io.ByteArrayOutputStream
import java.net.URLEncoder
import java.net.URI
import kotlin.coroutines.resume
import kotlin.math.max
import kotlin.math.min

internal fun browserDestination(raw: String, allowSearch: Boolean = true): String {
    val value = raw.trim()
    require(value.isNotEmpty()) { "请输入网址或搜索词" }
    val domain = Regex("""^(?:[^\s/:?#]+\.)+[^\s/:?#]+(?::\d+)?(?:[/?#].*)?$""")
    val url = when {
        value.startsWith("http://", true) || value.startsWith("https://", true) -> value
        domain.matches(value) && value.none(Char::isWhitespace) -> "https://$value"
        Regex("^[a-z][a-z0-9+.-]*:", RegexOption.IGNORE_CASE).containsMatchIn(value) ->
            throw IllegalArgumentException("仅支持 HTTP / HTTPS 网页")
        else -> {
            require(allowSearch) { "请输入有效的 HTTP / HTTPS 网址" }
            return "https://www.bing.com/search?q=" + URLEncoder.encode(value, "UTF-8")
        }
    }
    val parsed = runCatching { URI(url) }.getOrNull()
    require(parsed?.host != null && parsed.rawUserInfo == null) { "网址格式无效，请检查地址" }
    return parsed.scheme.lowercase() + url.substringAfter(parsed.scheme)
}

private data class BrowserMaterial(val text: String, val bitmap: Bitmap?, val title: String, val url: String)
private fun limited(bitmap: Bitmap): Bitmap {
    val factor = min(1f, 2048f / max(bitmap.width, bitmap.height))
    return if (factor == 1f) bitmap else Bitmap.createScaledBitmap(bitmap, (bitmap.width * factor).toInt(), (bitmap.height * factor).toInt(), true)
}
private fun png(bitmap: Bitmap): ByteArray = ByteArrayOutputStream().use { out -> limited(bitmap).compress(Bitmap.CompressFormat.PNG, 100, out); out.toByteArray().also { require(it.size <= 5 * 1024 * 1024) { "图片过大，请截取更小区域" } } }

// The Google mobile homepage can keep the pixel height it measured before the keyboard closed.
private val googleHomepageViewport = """
    (() => {
        if (location.protocol !== "https:" || !["www.google.com", "google.com"].includes(location.hostname) ||
            location.pathname !== "/" || new URLSearchParams(location.search).has("q")) return;
        const page = document.querySelector("body > #gb-main");
        const height = CSS.supports("min-height", "100dvh") ? "100dvh" : "100vh";
        page?.style.setProperty("min-height", height, "important");
    })();
""".trimIndent()

private suspend fun WebView.hasPageContent(): Boolean = suspendCancellableCoroutine { continuation ->
    evaluateJavascript("""
        (() => {
            if (document.body?.innerText.trim()) return true;
            return Array.from(document.querySelectorAll('img, svg, canvas, video, iframe, input, button'))
                .some(element => {
                    const bounds = element.getBoundingClientRect();
                    return bounds.width > 0 && bounds.height > 0 &&
                        (element.tagName !== 'IMG' || element.naturalWidth > 0);
                });
        })()
    """.trimIndent()) { value ->
        if (continuation.isActive) continuation.resume(value == "true")
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable fun BrowserPage(vm: WorkbenchModel, visible: Boolean, menuButton: (@Composable () -> Unit)?) {
    val clipboard = LocalClipboardManager.current
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val scope = rememberCoroutineScope()
    var view by remember { mutableStateOf<WebView?>(null) }
    var address by remember { mutableStateOf(TextFieldValue(vm.browserUrl)) }
    var startPage by rememberSaveable { mutableStateOf(vm.browserUrl.isEmpty()) }
    var serverDataOpen by rememberSaveable { mutableStateOf(false) }
    var bookmarksOpen by rememberSaveable { mutableStateOf(false) }
    var bookmarkToEdit by rememberSaveable { mutableStateOf<String?>(null) }
    var addressFocused by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var pageError by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }
    var imageJob by remember { mutableStateOf<Job?>(null) }
    var material by remember { mutableStateOf<BrowserMaterial?>(null) }
    var screenshot by remember { mutableStateOf<Bitmap?>(null) }
    var title by remember { mutableStateOf("网页") }
    var progress by remember { mutableIntStateOf(0) }
    var canGoBack by remember { mutableStateOf(false) }
    var canGoForward by remember { mutableStateOf(false) }
    var pageReady by remember { mutableStateOf(false) }
    var pageLoading by remember { mutableStateOf(false) }
    var pageCommitted by remember { mutableStateOf(false) }
    var pageFinished by remember { mutableStateOf(false) }
    var failedUrl by remember { mutableStateOf<String?>(null) }
    var navigation by remember { mutableIntStateOf(0) }
    var rendererGeneration by remember { mutableIntStateOf(0) }
    var toolsOpen by remember { mutableStateOf(false) }
    var selectedMaterial by remember { mutableStateOf<BrowserMaterial?>(null) }
    val supported = vm.browserReady
    val online = vm.browserReady
    val configureBrowser = { vm.tab = WorkbenchPage.Cloud }
    val canCollect = !startPage && online && pageReady && !loading && screenshot == null
    val screenWidth = LocalConfiguration.current.screenWidthDp
    val starInToolbar = screenWidth >= 440
    val forwardInToolbar = screenWidth >= 480
    val reloadInToolbar = screenWidth >= 340
    val bookmarks = vm.browserBookmarks
    val currentBookmark = bookmarks.forUrl(vm.browserUrl).takeIf { !startPage && pageReady }
    val snackbar = remember { SnackbarHostState() }
    fun updateHistory(w: WebView) {
        canGoBack = w.canGoBack()
        canGoForward = w.canGoForward()
    }
    fun finishAddressEdit() {
        focusManager.clearFocus(force = true)
        keyboardController?.hide()
    }
    fun openServerData() {
        finishAddressEdit()
        imageJob?.cancel()
        view?.stopLoading()
        pageReady = false
        pageLoading = false
        serverDataOpen = true
    }
    if (serverDataOpen) {
        ServerDataPage(vm, visible, menuButton, onBack = { serverDataOpen = false })
        return
    }
    fun startNavigation() {
        pageError = null
        failedUrl = null
        pageReady = false
        pageLoading = true
        pageCommitted = false
        pageFinished = false
        progress = 0
        navigation++
    }
    fun failPage(message: String, url: String? = null) {
        if (startPage || pageError != null) return
        failedUrl = url
        pageLoading = false
        pageReady = false
        progress = 100
        pageError = message
    }
    fun retryPage() {
        if (!vm.browserReady) return
        val w = view ?: return
        val target = failedUrl ?: vm.browserUrl
        val reload = pageError == null && w.url != null
        startNavigation()
        if (reload) w.reload() else w.loadUrl(target)
    }
    fun navigate(raw: String): Boolean {
        if (!vm.browserReady) return false
        val url = try {
            browserDestination(raw)
        } catch (e: IllegalArgumentException) {
            error = e.message
            return false
        }
        finishAddressEdit()
        error = null
        address = TextFieldValue(url)
        vm.browserUrl = url
        startPage = false
        startNavigation()
        view?.loadUrl(url)
        return true
    }
    fun openBookmark(entry: BrowserBookmark): Boolean {
        val url = entry.url ?: return false
        if (!navigate(url)) return false
        bookmarks.recordOpen(entry)
        return true
    }
    fun bookmarkCurrentPage() {
        finishAddressEdit()
        if (currentBookmark != null) {
            bookmarkToEdit = currentBookmark.id
            return
        }
        val folder = bookmarks.find(bookmarks.lastFolder)
        val saved = try {
            bookmarks.save(null, title, vm.browserUrl, shortcut = false, parentId = folder?.id)
        } catch (e: IllegalArgumentException) {
            error = e.message
            return
        }
        scope.launch {
            val message = if (folder == null) "已添加书签" else "已添加到「${folder.title}」"
            if (snackbar.showSnackbar(message, actionLabel = "编辑") == SnackbarResult.ActionPerformed) {
                bookmarkToEdit = saved.id
            }
        }
    }
    fun readMaterial(selection: Boolean, complete: (BrowserMaterial?) -> Unit) {
        val w = view ?: return complete(null)
        val sourceTitle = title
        val sourceUrl = w.url ?: vm.browserUrl
        val script = if (selection) "window.__workbenchSelection?.() || ''" else "(document.body?.innerText || '').slice(0,30000)"
        w.evaluateJavascript(script) { value ->
            val text = runCatching { JSONArray("[$value]").optString(0) }.getOrDefault("")
            complete(text.takeUnless { it.isBlank() || it == "null" }?.let { BrowserMaterial(it, null, sourceTitle, sourceUrl) })
        }
    }
    fun goHome() {
        finishAddressEdit()
        startPage = true
        address = TextFieldValue()
        error = null
        pageError = null
        pageReady = false
        pageLoading = false
        view?.stopLoading()
    }
    fun goBack() {
        if (online && canGoBack) {
            startNavigation()
            view?.goBack()
        } else goHome()
    }
    fun goForward() {
        if (!online || !canGoForward) return
        finishAddressEdit()
        startNavigation()
        view?.goForward()
    }
    val inProgress = pageLoading || (pageReady && progress in 1..99)
    fun reloadOrStop() {
        finishAddressEdit()
        if (inProgress) {
            if (!pageReady) failPage("已停止加载")
            pageLoading = false
            view?.stopLoading()
            progress = 100
        } else retryPage()
    }
    BackHandler(enabled = visible && (screenshot != null || (!startPage && !addressFocused))) {
        if (screenshot != null) screenshot = null else goBack()
    }
    val inlineActions = screenWidth >= 1040 && menuButton != null
    val canOpen = online && address.text.isNotBlank()
    val addressBar: @Composable (Modifier) -> Unit = { modifier ->
        TextField(
            value = address,
            onValueChange = { address = it; error = null },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go, keyboardType = KeyboardType.Uri),
            keyboardActions = KeyboardActions(onGo = { navigate(address.text) }),
            modifier = modifier.onFocusChanged { state ->
                if (state.isFocused && !addressFocused) address = address.copy(selection = TextRange(0, address.text.length))
                addressFocused = state.isFocused
            }.testTag("browserAddress"),
            placeholder = { Text("网址或搜索", maxLines = 1) },
            shape = RoundedCornerShape(16.dp),
            colors = TextFieldDefaults.colors(
                focusedContainerColor = AccentSurface, unfocusedContainerColor = Background,
                focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent
            ),
            trailingIcon = {
                FilledIconButton(onClick = { navigate(address.text) }, enabled = canOpen,
                    modifier = Modifier.size(44.dp).testTag("browserGo")) {
                    Icon(Icons.Outlined.ArrowForward, "打开网页")
                }
            }
        )
    }
    val tools: @Composable (Modifier, Boolean) -> Unit = { modifier, fillWidth ->
        Row(modifier.testTag("browserToolbar"), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { finishAddressEdit(); goBack() }, enabled = !startPage && screenshot == null) { Icon(Icons.Outlined.ArrowBack, "后退") }
            if (forwardInToolbar) {
                IconButton(onClick = ::goForward, enabled = online && canGoForward && screenshot == null) {
                    Icon(Icons.Outlined.ArrowForward, "前进")
                }
            }
            if (screenWidth >= 350) {
                IconButton(onClick = { goHome() }, enabled = !startPage && screenshot == null) { Icon(Icons.Outlined.Home, "主页") }
            }
            IconButton(onClick = { finishAddressEdit(); bookmarksOpen = true }, enabled = screenshot == null) {
                Icon(Icons.Outlined.BookmarkBorder, "书签")
            }
            if (!startPage && starInToolbar) IconButton(onClick = ::bookmarkCurrentPage, enabled = pageReady && screenshot == null,
                modifier = Modifier.testTag("bookmarkCurrentPage")) {
                Icon(if (currentBookmark == null) Icons.Outlined.StarBorder else Icons.Outlined.Star,
                    if (currentBookmark == null) "收藏当前网页" else "编辑当前网页书签",
                    tint = if (currentBookmark == null) LocalContentColor.current else Amber)
            }
            if (reloadInToolbar) {
                IconButton(onClick = ::reloadOrStop, enabled = !startPage && (online || inProgress) && screenshot == null) {
                    Icon(if (inProgress) Icons.Outlined.Close else Icons.Outlined.Refresh, if (inProgress) "停止加载" else "刷新")
                }
            }
            Spacer(if (fillWidth) Modifier.weight(1f) else Modifier.width(4.dp))
            IconButton(onClick = {
                error = null
                readMaterial(false) { result ->
                    if (result == null) error = "没有提取到页面文字" else material = result
                }
            }, enabled = canCollect, modifier = Modifier.testTag("browserText")) { Icon(Icons.Outlined.Article, "页面文字") }
            IconButton(onClick = {
                view?.let { w ->
                    if (w.width > 0 && w.height > 0) {
                        val scale = min(1f, 2048f / max(w.width, w.height))
                        screenshot = Bitmap.createBitmap((w.width * scale).toInt().coerceAtLeast(1), (w.height * scale).toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888).also {
                            val canvas = android.graphics.Canvas(it)
                            canvas.scale(it.width.toFloat() / w.width, it.height.toFloat() / w.height)
                            w.draw(canvas)
                        }
                    }
                }
            }, enabled = canCollect, modifier = Modifier.testTag("browserCapture")) { Icon(Icons.Outlined.Crop, "区域截图") }
            Box {
                IconButton(onClick = {
                    // Capture the selection before the popup takes focus from WebView.
                    selectedMaterial = null
                    if (canCollect) {
                        readMaterial(true) { result -> selectedMaterial = result; toolsOpen = true }
                    } else {
                        toolsOpen = true
                    }
                }, enabled = screenshot == null) { Icon(Icons.Outlined.MoreHoriz, "更多浏览操作") }
                DropdownMenu(expanded = toolsOpen, onDismissRequest = { toolsOpen = false }) {
                    DropdownMenuItem(text = { Text("服务器数据") }, leadingIcon = { Icon(Icons.Outlined.Storage, null) },
                        onClick = { toolsOpen = false; openServerData() })
                    if (screenWidth < 350) DropdownMenuItem(text = { Text("主页") },
                        leadingIcon = { Icon(Icons.Outlined.Home, null) }, onClick = { toolsOpen = false; goHome() })
                    if (!starInToolbar) DropdownMenuItem(
                        text = { Text(if (currentBookmark == null) "收藏当前网页" else "编辑当前网页书签") },
                        leadingIcon = { Icon(if (currentBookmark == null) Icons.Outlined.StarBorder else Icons.Outlined.Star, null) },
                        enabled = pageReady, onClick = { toolsOpen = false; bookmarkCurrentPage() })
                    if (!reloadInToolbar) DropdownMenuItem(text = { Text(if (inProgress) "停止加载" else "刷新", maxLines = 1) },
                        enabled = !startPage && (online || inProgress),
                        leadingIcon = { Icon(if (inProgress) Icons.Outlined.Close else Icons.Outlined.Refresh, null) },
                        onClick = { toolsOpen = false; reloadOrStop() })
                    DropdownMenuItem(text = { Text("前进", maxLines = 1) }, enabled = online && canGoForward,
                        leadingIcon = { Icon(Icons.Outlined.ArrowForward, null) }, onClick = { toolsOpen = false; goForward() })
                    DropdownMenuItem(text = { Text("复制当前网址") }, enabled = pageReady, leadingIcon = { Icon(Icons.Outlined.ContentCopy, null) }, onClick = { toolsOpen = false; clipboard.setText(AnnotatedString(vm.browserUrl)) })
                    DropdownMenuItem(text = { Text("选中文本") }, enabled = canCollect, leadingIcon = { Icon(Icons.Outlined.TextFields, null) }, onClick = {
                        toolsOpen = false
                        val selection = selectedMaterial
                        selectedMaterial = null
                        error = if (selection == null) "请先长按网页选中文字" else null
                        material = selection
                    })
                    DropdownMenuItem(text = { Text("取材方式") }, leadingIcon = { Icon(Icons.Outlined.HelpOutline, null) }, onClick = {
                        toolsOpen = false
                        error = "可提取页面文字、长按图片或框选区域截图"
                    })
                }
            }
        }
    }
    val documentTitle = title.takeIf { pageReady && !startPage }
    Column(Modifier.fillMaxSize().semantics { paneTitle = documentTitle ?: WorkbenchPage.Browse.title }) {
        Row(
            Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface)
                .padding(start = 4.dp, end = if (screenWidth >= 600) LocalPagePadding.current else 8.dp, top = 2.dp, bottom = 2.dp)
                .testTag("browserHeader"),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            menuButton?.invoke()
            if (menuButton != null && screenWidth >= 480) {
                if (documentTitle == null) PageTitle(WorkbenchPage.Browse)
                else Text(documentTitle, Modifier.widthIn(max = 112.dp).testTag("browserDocumentTitle"),
                    style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            addressBar(Modifier.weight(1f))
            if (inlineActions) tools(Modifier, false)
            if (menuButton != null) ConnectionStatus(vm, compact = screenWidth < 600)
        }
        if (!inlineActions) tools(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = if (starInToolbar) LocalPagePadding.current else 4.dp), true)
        if (!startPage && screenshot == null && !addressFocused && bookmarks.barVisible) {
            BoxWithConstraints {
                if (maxWidth >= 600.dp) BrowserBookmarkBar(bookmarks, onOpen = ::openBookmark)
            }
        }
        HorizontalDivider(color = Outline)
        error?.let { Text(it, Modifier.padding(horizontal = 24.dp, vertical = 6.dp), color = Amber, fontSize = 12.sp) }
        if (!startPage && (loading || (online && pageLoading))) LinearProgressIndicator(Modifier.fillMaxWidth().height(2.dp))
        Box(Modifier.weight(1f).fillMaxWidth().clipToBounds().zIndex(-1f)) {
            val generation = rendererGeneration
            if (supported) key(generation) { AndroidView(modifier = Modifier.fillMaxSize().testTag("browserView"), factory = { ctx ->
                WebView(ctx).apply {
                    outlineProvider = ViewOutlineProvider.BOUNDS
                    clipToOutline = true
                    settings.javaScriptEnabled = true; settings.domStorageEnabled = true
                    settings.allowFileAccess = false; settings.allowContentAccess = false
                    settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                    settings.setSupportMultipleWindows(false)
                    settings.builtInZoomControls = true; settings.displayZoomControls = false
                    settings.blockNetworkLoads = true
                    WebViewCompat.addDocumentStartJavaScript(this, """
                        (() => {
                            // Keep page network requests within the WebView request handler.
                            for (const key of ['RTCPeerConnection', 'webkitRTCPeerConnection', 'WebTransport']) {
                                try { Object.defineProperty(window, key, { value: undefined }); } catch {}
                            }
                            if (window !== window.top) return;
                            let selection = '';
                            document.addEventListener('selectionchange', () => {
                                const text = window.getSelection()?.toString().trim();
                                if (text) selection = text.slice(0, 30000);
                            });
                            Object.defineProperty(window, '__workbenchSelection', {
                                value: () => {
                                    const text = window.getSelection()?.toString().trim().slice(0, 30000) || selection;
                                    selection = '';
                                    return text;
                                }
                            });
                        })();
                    """.trimIndent(), setOf("*"))
                    webViewClient = object : WebViewClient() {
                        override fun shouldOverrideUrlLoading(v: WebView, r: WebResourceRequest): Boolean = r.url.scheme !in listOf("http", "https")
                        override fun onPageStarted(v: WebView, url: String, favicon: Bitmap?) {
                            if (startPage || pageError != null || !vm.browserReady || v !== view) return
                            startNavigation()
                            error = null
                        }
                        override fun onPageCommitVisible(v: WebView, url: String) {
                            if (v === view && pageLoading && url == v.url) pageCommitted = true
                        }
                        override fun doUpdateVisitedHistory(v: WebView, url: String, isReload: Boolean) {
                            if (startPage || v !== view || (!url.startsWith("https://") && !url.startsWith("http://"))) return
                            vm.browserUrl = url
                            if (!startPage && !addressFocused) address = TextFieldValue(url)
                            updateHistory(v)
                        }
                        override fun onPageFinished(v: WebView, url: String) {
                            if (startPage || v !== view || url != v.url) return
                            if (url.startsWith("https://www.google.com/") || url.startsWith("https://google.com/")) {
                                v.evaluateJavascript(googleHomepageViewport, null)
                            }
                            title = v.title?.takeIf { it.isNotBlank() } ?: "网页"
                            if (pageLoading && (url.startsWith("https://") || url.startsWith("http://"))) pageFinished = true
                            updateHistory(v)
                        }
                        override fun onReceivedError(v: WebView, r: WebResourceRequest, e: WebResourceError) {
                            if (v === view && r.isForMainFrame) {
                                failPage("页面加载失败（${e.errorCode}）", r.url.toString())
                            }
                        }
                        override fun onReceivedHttpError(v: WebView, r: WebResourceRequest, response: WebResourceResponse) {
                            if (v === view && r.isForMainFrame) {
                                failPage("服务器返回 HTTP ${response.statusCode}", r.url.toString())
                            }
                        }
                        override fun onRenderProcessGone(v: WebView, detail: RenderProcessGoneDetail): Boolean {
                            if (v !== view) return true
                            vm.browserSaved = null
                            imageJob?.cancel()
                            screenshot = null
                            canGoBack = false
                            canGoForward = false
                            failPage("网页进程已退出，请重试")
                            view = null
                            rendererGeneration++
                            return true
                        }
                    }
                    webChromeClient = object : WebChromeClient() {
                        override fun onProgressChanged(v: WebView, value: Int) { progress = value }
                        override fun onPermissionRequest(request: PermissionRequest) { request.deny() }
                    }
                    setOnLongClickListener {
                        val hit = hitTestResult
                        val imageUrl = hit.extra
                        if (vm.browserReady && pageReady && !loading && screenshot == null &&
                            hit.type in listOf(WebView.HitTestResult.IMAGE_TYPE, WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE) && imageUrl != null) {
                            val pageUrl = url ?: vm.browserUrl
                            val pageTitle = title
                            val cookie = CookieManager.getInstance().getCookie(imageUrl).orEmpty(); val userAgent = settings.userAgentString
                            imageJob = scope.launch {
                                loading = true
                                try {
                                    val bytes = vm.browserImage(imageUrl, cookie, userAgent)
                                    val bitmap = withContext(Dispatchers.Default) {
                                        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }; BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                                        require(bounds.outWidth > 0 && bounds.outHeight > 0 && bounds.outWidth.toLong() * bounds.outHeight <= 20000000) { "图片尺寸过大，请使用区域截图" }
                                        limited(BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: throw IllegalStateException("无法解码图片"))
                                    }
                                    material = BrowserMaterial("用户主动选择的网页图片：$imageUrl", bitmap, pageTitle, pageUrl)
                                } catch (e: CancellationException) {
                                    throw e
                                } catch (e: Exception) {
                                    error = e.message ?: "图片读取失败"
                                } finally { loading = false }
                            }; true
                        } else false
                    }
                    view = this
                }
            }, update = { w ->
                w.settings.blockNetworkLoads = !vm.browserReady
                w.visibility = if (visible && !startPage && pageError == null) View.VISIBLE else View.INVISIBLE
                w.isEnabled = pageReady && screenshot == null
                if (visible) w.onResume() else w.onPause()
            }, onRelease = { w ->
                if (generation == rendererGeneration) {
                    val saved = Bundle()
                    vm.browserSaved = if (w.saveState(saved) != null) saved else null
                    w.settings.blockNetworkLoads = true
                    w.stopLoading()
                    view = null
                }
                w.destroy()
            }) }
            if (startPage) {
                BrowserStartPage(vm.browserBookmarks.entries, message = vm.browserStatus.takeUnless { online },
                    onOpen = { if (online) openBookmark(it) }, onManage = {
                    finishAddressEdit()
                    bookmarksOpen = true
                }, onConnectionAction = configureBrowser.takeIf { supported && !online }, connectionAction = "前往云端",
                    onServerData = ::openServerData)
            } else if ((!pageReady || pageError != null) && screenshot == null) Surface(Modifier.fillMaxSize().zIndex(1f), color = Background) {
                Box(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        when {
                            !online -> {
                                Text(vm.browserStatus, modifier = Modifier.testTag("browserReconnect"))
                                if (supported) TextButton(onClick = configureBrowser) { Text("前往云端") }
                            }
                            pageError != null -> {
                                Text(pageError.orEmpty(), color = Amber, modifier = Modifier.testTag("browserPageError"))
                                FilledTonalButton(onClick = { retryPage() }) { Text("重试") }
                            }
                            else -> {
                                CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 3.dp)
                                Text("正在打开网页…", modifier = Modifier.testTag("browserLoading"))
                            }
                        }
                        if (!pageLoading) TextButton(onClick = ::goHome) { Text("返回浏览主页") }
                    }
                }
            } else if (!online && screenshot == null) {
                Surface(Modifier.align(Alignment.TopCenter).padding(12.dp).widthIn(max = 560.dp),
                    color = WarmSurface, shape = RoundedCornerShape(14.dp)) {
                    Row(Modifier.padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(vm.browserStatus, color = Amber, style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.testTag("browserReconnect"))
                        if (supported) TextButton(onClick = configureBrowser) { Text("前往云端") }
                    }
                }
            }
            screenshot?.let { bitmap ->
                RegionPicker(bitmap, onCancel = { screenshot = null }, onSelect = { crop ->
                    screenshot = null
                    material = BrowserMaterial("用户主动截取的网页可见区域", crop, title, view?.url ?: vm.browserUrl)
                })
            }
            if (visible && screenshot == null) SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter))
        }
    }
    LaunchedEffect(online, view, startPage) {
        val w = view ?: return@LaunchedEffect
        if (online && !startPage && w.url == null && !pageLoading && !pageReady &&
            vm.browserUrl.isNotEmpty() && pageError == null) {
            startNavigation()
            val saved = vm.browserSaved
            vm.browserSaved = null
            if (saved == null || w.restoreState(saved) == null) w.loadUrl(vm.browserUrl)
            updateHistory(w)
        }
    }
    LaunchedEffect(visible, view, navigation, pageLoading, pageCommitted, pageFinished) {
        val w = view ?: return@LaunchedEffect
        if (!visible || !pageLoading || (!pageCommitted && !pageFinished)) return@LaunchedEffect
        val load = navigation
        // A committed document can still be an empty shell while the site's scripts render it.
        val finishedAt = SystemClock.elapsedRealtime()
        while (w === view && load == navigation && pageLoading) {
            val hasContent = w.hasPageContent()
            if (w !== view || load != navigation || !pageLoading) return@LaunchedEffect
            // Allow intentionally empty or non-HTML documents once the load has finished.
            if (hasContent || (pageFinished && SystemClock.elapsedRealtime() - finishedAt >= 1500)) {
                title = w.title?.takeIf { it.isNotBlank() } ?: "网页"
                pageReady = true
                pageLoading = false
                return@LaunchedEffect
            }
            delay(150)
        }
    }
    LaunchedEffect(online, navigation, pageLoading) {
        if (online && pageLoading) {
            delay(20000)
            failPage("网页响应超时，请重试")
            view?.stopLoading()
        }
    }
    if (visible && bookmarksOpen) {
        BrowserBookmarkManager(bookmarks, onOpen = ::openBookmark, onClose = { bookmarksOpen = false })
    }
    if (visible) bookmarks.find(bookmarkToEdit)?.let { entry ->
        key(entry.id) {
            BookmarkEditor(entry, onSave = { title, url, shortcut ->
                bookmarks.save(entry, title, url, shortcut, entry.parentId)
                bookmarkToEdit = null
            }, onClose = { bookmarkToEdit = null })
        }
    }
    material?.takeIf { visible }?.let { m ->
        var instruction by remember(m) { mutableStateOf(if (m.bitmap == null) "请总结这些内容，说明关键结论和需要核实的地方。" else "请分析这张图片，解释主要内容并给出建议。") }
        AlertDialog(onDismissRequest = { if (!vm.busy) material = null }, title = { Text("交给服务器 Codex") }, text = {
            Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Surface(color = AccentSurface, shape = MaterialTheme.shapes.small) {
                    Column(Modifier.fillMaxWidth().padding(10.dp)) {
                        Text(m.title, style = MaterialTheme.typography.titleSmall)
                        Text(m.url, style = MaterialTheme.typography.bodySmall, color = Muted)
                    }
                }
                m.bitmap?.let { Image(it.asImageBitmap(), "待发送图片", Modifier.fillMaxWidth().heightIn(max = 230.dp)) }
                if (m.text.isNotBlank()) Text(m.text.take(1200) + if (m.text.length > 1200) "\n…共 ${m.text.length} 字" else "", fontSize = 12.sp)
                OutlinedTextField(value = instruction, onValueChange = { instruction = it.take(4000) }, label = { Text("希望 Codex 怎样处理？") }, modifier = Modifier.fillMaxWidth().testTag("browserInstruction"))
                if (vm.activeThread != null) Text("等待当前任务结束后即可发送。", color = Amber, style = MaterialTheme.typography.bodySmall)
                Text("仅发送上方资料；不会自动提供浏览历史、密码或 Cookie。网页内容不构成操作授权。", color = Muted, fontSize = 12.sp)
            }
        }, confirmButton = { TextButton(onClick = {
            try { val bytes = m.bitmap?.let(::png); vm.sendBrowser(instruction, m.title, m.url, m.text, bytes); material = null } catch (e: Exception) { error = e.message }
        }, enabled = vm.hostOnline && !vm.connecting && !vm.busy && vm.activeThread == null && instruction.isNotBlank(), modifier = Modifier.testTag("browserSend")) { Text("发送并打开会话") } }, dismissButton = { TextButton(onClick = { material = null }) { Text("取消") } })
    }
}

@Composable private fun RegionPicker(bitmap: Bitmap, onCancel: () -> Unit, onSelect: (Bitmap) -> Unit) {
    var start by remember { mutableStateOf(Offset.Zero) }; var end by remember { mutableStateOf(Offset.Zero) }
    var bounds by remember { mutableStateOf(IntSize.Zero) }
    val rectangle = Rect(min(start.x, end.x), min(start.y, end.y), max(start.x, end.x), max(start.y, end.y))
    Box(Modifier.fillMaxSize().onSizeChanged { bounds = it }.pointerInput(Unit) {
        detectDragGestures(onDragStart = { start = it; end = it }, onDrag = { change, _ -> end = change.position; change.consume() })
    }.testTag("captureRegion")) {
        Canvas(Modifier.fillMaxSize()) {
            drawImage(bitmap.asImageBitmap(), dstSize = IntSize(size.width.toInt(), size.height.toInt()))
            drawRect(Color.Black.copy(alpha = .18f))
            if (rectangle.width > 0 && rectangle.height > 0) drawRect(Color(0xFFD9EFAC), rectangle.topLeft, rectangle.size, style = Stroke(4f))
        }
        Surface(Modifier.align(Alignment.BottomCenter).padding(12.dp), color = Color.White) {
            Row(Modifier.padding(8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                TextButton(onClick = onCancel) { Text("取消") }
                TextButton(onClick = { onSelect(limited(bitmap)) }, modifier = Modifier.testTag("captureFull")) { Text("使用可见页") }
                Button(onClick = {
                    val left = (rectangle.left / bounds.width * bitmap.width).toInt().coerceIn(0, bitmap.width - 1)
                    val top = (rectangle.top / bounds.height * bitmap.height).toInt().coerceIn(0, bitmap.height - 1)
                    val right = (rectangle.right / bounds.width * bitmap.width).toInt().coerceIn(left + 1, bitmap.width)
                    val bottom = (rectangle.bottom / bounds.height * bitmap.height).toInt().coerceIn(top + 1, bitmap.height)
                    onSelect(limited(Bitmap.createBitmap(bitmap, left, top, right - left, bottom - top)))
                }, enabled = bounds.width > 0 && bounds.height > 0 && rectangle.width > 10 && rectangle.height > 10, modifier = Modifier.testTag("captureSelected")) { Text("预览选区") }
            }
        }
        Text("拖动框选需要分析的区域", Modifier.align(Alignment.TopCenter).padding(12.dp), color = Color.White)
    }
}
