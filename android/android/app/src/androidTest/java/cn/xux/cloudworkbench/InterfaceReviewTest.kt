package cn.xux.cloudworkbench

import android.graphics.Bitmap
import android.app.Application
import android.app.UiAutomation
import android.os.Build
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.lifecycle.ViewModelStore
import org.json.JSONObject
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import kotlin.concurrent.thread

/** UI-only acceptance. Does not send messages, approve tasks, or publish upgrades. */
@RunWith(AndroidJUnit4::class)
class InterfaceReviewTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Before fun keepVisible() {
        compose.activity.runOnUiThread {
            compose.activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    private fun screenshot(name: String) {
        compose.waitForIdle()
        // Compose idleness does not wait for Android window/IME animations or WebView's first frame.
        android.os.SystemClock.sleep(900)
        compose.waitForIdle()
        captureReviewScreen(name)
    }

    private fun tab(name: String) = compose.openWorkbenchTab(name)

    @Test fun navigationAndCredentialConfirmation() {
        compose.onNodeWithTag("nav-云端").assertDoesNotExist()
        compose.onNodeWithTag("menuToggle").assertIsDisplayed().performClick()
        listOf("云端", "会话", "浏览", "授权", "设置").forEach { name ->
            compose.onNodeWithTag("nav-$name").assertIsDisplayed().assertTextEquals(name)
        }
        screenshot("menu")
        compose.onNodeWithTag("menuClose").assertIsDisplayed().performClick()
        compose.onNodeWithTag("nav-云端").assertDoesNotExist()
        compose.waitUntil(10000) {
            val bars = androidx.core.view.ViewCompat.getRootWindowInsets(compose.activity.window.decorView)
            bars != null && !bars.isVisible(androidx.core.view.WindowInsetsCompat.Type.statusBars()) &&
                !bars.isVisible(androidx.core.view.WindowInsetsCompat.Type.navigationBars())
        }
        tab("云端")
        compose.waitUntil(45000) { compose.onAllNodesWithText("运行指标").fetchSemanticsNodes().isNotEmpty() }
        screenshot("dashboard")
        tab("会话")
        compose.onNodeWithTag("conversationTitle").assertHasNoClickAction()
        compose.onNodeWithTag("newChat").assertContentDescriptionEquals("新对话")
        compose.onNodeWithTag("projectPicker").performClick()
        compose.onNodeWithTag("project-default").assertDoesNotExist()
        compose.onNodeWithTag("project-qiwuli-site").assertIsDisplayed().performClick()
        screenshot("conversation")
        compose.onNodeWithTag("conversationHistory").performClick()
        compose.waitUntil(15000) {
            compose.onAllNodes(hasTestTag("conversationSearch") or hasText("还没有历史会话"))
                .fetchSemanticsNodes().isNotEmpty()
        }
        if (compose.onAllNodesWithText("还没有历史会话").fetchSemanticsNodes().isNotEmpty()) {
            compose.onNodeWithText("清理空会话").assertDoesNotExist()
            compose.onNodeWithTag("conversationSearch").assertDoesNotExist()
        } else {
            compose.onNodeWithTag("conversationSearch").assertIsDisplayed()
        }
        screenshot("history")
        compose.onNodeWithText("关闭").performClick()
        compose.onNodeWithTag("project-任务").assertIsDisplayed().performClick()
        screenshot("tasks")
        compose.onNodeWithTag("project-升级").assertDoesNotExist()
        tab("浏览")
        tab("授权")
        compose.onNodeWithText("授权列表").assertExists()
        compose.onNodeWithTag("windowsAdminToken").assertDoesNotExist()
        tab("设置")
        compose.onNodeWithText("查看应用升级").assertDoesNotExist()
        compose.onNodeWithTag("appUpdatePanel").assertExists()
        compose.onNodeWithTag("appUpdateCheck").assertIsEnabled()
        compose.onNodeWithTag("pairingToken").assertDoesNotExist()
        compose.onNodeWithTag("connectionHelp").assertDoesNotExist()
        compose.onNodeWithTag("importPrivateKey").assertDoesNotExist()
        compose.onNodeWithText("服务器状态").performScrollTo()
        screenshot("settings")
    }

    @Test fun composerRemainsVisibleWithKeyboard() {
        tab("会话")
        compose.onNodeWithTag("project-对话").performClick()
        compose.onNodeWithTag("newChat").performClick()
        compose.onNodeWithTag("voiceToggle").assertContentDescriptionEquals("切换到语音输入")
        compose.onNodeWithTag("messageInput").assertIsDisplayed()
        screenshot("conversation")
        val input = compose.onNodeWithTag("messageInput")
        val singleLineHeight = input.fetchSemanticsNode().boundsInWindow.height
        val draft = (1..15).joinToString("\n") { "第 ${it} 行 · 界面验收草稿" }
        input.performClick().performTextReplacement(draft)
        val activity = compose.activity
        val camera = File(activity.cacheDir, "camera").apply { mkdirs() }
        val sample = File.createTempFile("composer-review-", ".png", camera)
        sample.outputStream().use { output ->
            val bitmap = Bitmap.createBitmap(100, 80, Bitmap.Config.ARGB_8888)
            try {
                bitmap.eraseColor(android.graphics.Color.rgb(92, 145, 116))
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)
            } finally {
                bitmap.recycle()
            }
        }
        val uri = androidx.core.content.FileProvider.getUriForFile(activity, "${activity.packageName}.camera", sample)
        compose.runOnIdle {
            val model = androidx.lifecycle.ViewModelProvider(activity)[WorkbenchModel::class.java]
            model.importImages(model.draftKey, listOf(uri), sample)
        }
        compose.waitUntil(10000) { compose.onAllNodesWithTag("draftImagePreview").fetchSemanticsNodes().isNotEmpty() }
        compose.activity.runOnUiThread {
            androidx.core.view.WindowCompat.getInsetsController(compose.activity.window, compose.activity.window.decorView)
                .show(androidx.core.view.WindowInsetsCompat.Type.ime())
        }
        compose.waitUntil(10000) {
            androidx.core.view.ViewCompat.getRootWindowInsets(compose.activity.window.decorView)
                ?.isVisible(androidx.core.view.WindowInsetsCompat.Type.ime()) == true
        }
        android.os.SystemClock.sleep(900)
        compose.waitForIdle()
        input.assertIsDisplayed()
        val expandedHeight = input.fetchSemanticsNode().boundsInWindow.height
        org.junit.Assert.assertTrue(expandedHeight > singleLineHeight)
        if (activity.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE) {
            val density = activity.resources.displayMetrics.density
            org.junit.Assert.assertTrue("Landscape composer exceeds three visible lines",
                expandedHeight <= singleLineHeight + 48 * density + 2)
        }
        compose.onNodeWithTag("attachmentMenu").assertIsDisplayed()
        val window = compose.activity.window.decorView
        val imeBottom = androidx.core.view.ViewCompat.getRootWindowInsets(window)
            ?.getInsets(androidx.core.view.WindowInsetsCompat.Type.ime())?.bottom ?: 0
        // Check docked insets, then use a screen-level tap that the keyboard could intercept.
        if (imeBottom > 0) {
            val bottom = compose.onNodeWithTag("conversationComposer").fetchSemanticsNode().boundsInWindow.bottom
            org.junit.Assert.assertTrue("Composer is covered by the docked keyboard", bottom <= window.height - imeBottom + 1)
        }
        screenshot("keyboard")
        compose.onNodeWithContentDescription("移除 拍摄的照片").assertIsDisplayed().performClick()
        compose.onNodeWithTag("draftImages").assertDoesNotExist()
        val attachmentBounds = compose.onNodeWithTag("attachmentMenu").fetchSemanticsNode().boundsInWindow
        val downTime = SystemClock.uptimeMillis()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
            val event = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action,
                attachmentBounds.center.x, attachmentBounds.bottom - attachmentBounds.height / 4f, 0)
            event.source = InputDevice.SOURCE_TOUCHSCREEN
            try { instrumentation.sendPointerSync(event) } finally { event.recycle() }
        }
        compose.onNodeWithTag("attachmentPanel").assertIsDisplayed()
        compose.onNodeWithTag("composerHint").assertIsDisplayed()
        val hintBounds = compose.onNodeWithTag("composerHint").fetchSemanticsNode().boundsInWindow
        val composerBounds = compose.onNodeWithTag("conversationComposer").fetchSemanticsNode().boundsInWindow
        org.junit.Assert.assertTrue("Hint overlaps the composer", hintBounds.bottom <= composerBounds.top)
        screenshot("attachments")
        compose.onNodeWithTag("attachmentMenu").performClick()
        instrumentation.uiAutomation.grantRuntimePermission(activity.packageName, android.Manifest.permission.RECORD_AUDIO)
        compose.onNodeWithTag("voiceToggle").performClick()
        val hold = compose.onNodeWithTag("voiceHold").assertIsDisplayed()
        hold.performTouchInput { down(center) }
        hold.performTouchInput { moveTo(Offset(center.x, -height * 1.5f)) }
        compose.onNode(hasText("松开手指取消录音") and hasAnyAncestor(hasTestTag("composerHint"))).assertIsDisplayed()
        screenshot("voice-cancel")
        hold.performTouchInput { up() }
        compose.waitUntil(12000) {
            compose.onAllNodesWithText("已取消录音").fetchSemanticsNodes().isNotEmpty() &&
                compose.onAllNodes(hasTestTag("keyboardToggle") and isEnabled()).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("keyboardToggle").performClick()
        compose.onNodeWithTag("messageInput").assertTextContains(draft).performTextClearance()
    }

    @Test fun browserSwitchBookmarksAndMaterial() {
        val activity = compose.activity
        val models = ViewModelStore()
        val prefs = activity.getSharedPreferences("browser-layout-review", 0)
        BrowserReviewServer("直连验收网页").use { origin ->
            BrowserReviewServer("代理验收网页").use { proxy ->
                try {
                    compose.runOnUiThread {
                        prefs.edit().clear().commit()
                        val vm = WorkbenchModel(activity.application as Application, { BrowserReviewConnection(proxy.port) },
                            Credentials("browser-layout-fixture"), prefs)
                        models.put("browser", vm)
                        activity.setContent { WorkbenchTheme { Workbench(vm) } }
                    }
                    compose.waitUntil(15000) { compose.onAllNodesWithText("工作台服务可用").fetchSemanticsNodes().isNotEmpty() }
                    compose.onNodeWithText("核心服务").assertIsDisplayed()
                    compose.onNodeWithText("运行指标").assertIsDisplayed()
                    screenshot("dashboard")
                    tab("设置")
                    compose.onNodeWithText("服务器状态").assertIsDisplayed()
                    compose.onNodeWithTag("appUpdatePanel").assertIsDisplayed()
                    screenshot("settings")
                    tab("浏览")
                    val mode = compose.onNodeWithTag("browserModeIndicator")
                    fun awaitMode(label: String) {
                        compose.waitUntil(15000) {
                            compose.onAllNodes(hasTestTag("browserModeIndicator") and
                                hasContentDescription(label, substring = true)).fetchSemanticsNodes().isNotEmpty()
                        }
                        mode.assertIsDisplayed().assertIsEnabled()
                    }
                    fun navigate(url: String) {
                        compose.onNodeWithTag("browserAddress").performTextReplacement(url)
                        compose.onNodeWithTag("browserGo").performClick()
                        compose.waitUntil(15000) {
                            compose.onAllNodes(hasTestTag("browserText") and isEnabled()).fetchSemanticsNodes().isNotEmpty()
                        }
                    }
                    awaitMode("代理已就绪")
                    mode.performTouchInput { click() }
                    awaitMode("直连已就绪")
                    val url = "http://127.0.0.1:${origin.port}/article"
                    navigate(url)
                    compose.onNodeWithTag("bookmarkBar").assertDoesNotExist()
                    val addressBounds = compose.onNodeWithTag("browserAddress").fetchSemanticsNode().boundsInWindow
                    val titleBounds = compose.onNodeWithTag("browserDocumentTitle").assertTextContains("直连验收网页")
                        .fetchSemanticsNode().boundsInWindow
                    org.junit.Assert.assertEquals(addressBounds.center.y, titleBounds.center.y, 2f)
                    org.junit.Assert.assertTrue(titleBounds.right <= addressBounds.left)
                    val toolbarBounds = compose.onNodeWithTag("browserToolbar").fetchSemanticsNode().boundsInWindow
                    if (activity.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE) {
                        org.junit.Assert.assertEquals(addressBounds.center.y, toolbarBounds.center.y, 2f)
                    }
                    compose.onNodeWithContentDescription("收藏当前网页").performClick()
                    compose.onNodeWithContentDescription("编辑当前网页书签").performClick()
                    compose.onNodeWithTag("bookmarkName").performTextReplacement("平板书签验收")
                    compose.onNodeWithText("保存").performClick()
                    compose.onNodeWithContentDescription("书签").performClick()
                    compose.onNodeWithTag("bookmarkSearch").performTextReplacement("平板书签验收")
                    compose.onNode(hasText("平板书签验收") and hasAnyAncestor(hasTestTag("bookmarkList"))).assertIsDisplayed()
                    compose.onNodeWithContentDescription("排序和视图选项").performClick()
                    compose.onNodeWithText("显示书签栏").performClick()
                    compose.onNodeWithContentDescription("关闭书签").performClick()
                    compose.onNodeWithTag("bookmarkBar").assertIsDisplayed()
                    compose.runOnIdle { org.junit.Assert.assertTrue(BrowserBookmarks(prefs).barVisible) }
                    compose.onNodeWithContentDescription("书签").performClick()
                    compose.onNodeWithContentDescription("排序和视图选项").performClick()
                    compose.onNodeWithText("隐藏书签栏").performClick()
                    compose.onNodeWithContentDescription("关闭书签").performClick()
                    compose.onNodeWithTag("bookmarkBar").assertDoesNotExist()
                    screenshot("browser-toolbar")
                    mode.performTouchInput { click() }
                    awaitMode("代理已就绪")
                    compose.onNodeWithTag("browserAddress").assertTextContains(url)
                    compose.onNodeWithTag("browserText").assertIsEnabled().performClick()
                    compose.onNodeWithText("直连验收网页内容", substring = true).assertIsDisplayed()
                    compose.onNodeWithText("取消").performClick()
                    navigate("http://127.0.0.1:${origin.port}/proxied")
                    compose.onNodeWithTag("browserText").performClick()
                    compose.onNodeWithText("代理验收网页内容", substring = true).assertIsDisplayed()
                    screenshot("browser-text")
                    compose.onNodeWithText("取消").performClick()
                    compose.onNodeWithTag("browserCapture").performClick()
                    compose.onNodeWithTag("captureFull").assertIsDisplayed().performClick()
                    compose.onNodeWithContentDescription("待发送图片").assertIsDisplayed()
                    screenshot("browser-capture")
                } finally {
                    compose.runOnUiThread {
                        models.clear()
                        prefs.edit().clear().commit()
                    }
                }
            }
        }
    }

    @Test fun fixedServerDisclosesRecoveryOnlyOnDemand() {
        compose.connectWorkbenchIfNeeded()
        tab("授权")
        compose.waitUntil(45000) {
            compose.onAllNodesWithText("授权").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("issueAuthorization").assertExists()
        compose.onNodeWithTag("windowsAdminToken").assertDoesNotExist()
        compose.onNodeWithText("连接问题").assertDoesNotExist()
        tab("设置")
        compose.onNodeWithText(WORKBENCH_DOMAIN).assertIsDisplayed()
        compose.onNodeWithText("状态：已连接").assertIsDisplayed()
        compose.onNodeWithTag("disconnect").assertDoesNotExist()
        compose.onNodeWithTag("reconnect").assertDoesNotExist()
        compose.onNodeWithTag("forgetCredentials").assertDoesNotExist()
        compose.onNodeWithTag("connectionHelp").assertDoesNotExist()
        compose.onNodeWithTag("pairingToken").assertDoesNotExist()
        compose.onNodeWithTag("importPrivateKey").assertDoesNotExist()
        compose.onNodeWithText("SSH 绑定").assertDoesNotExist()
        compose.onNodeWithText("状态：已连接").assertIsDisplayed()
    }
}

private class BrowserReviewConnection(override val browserPort: Int) : WorkbenchConnection {
    override var connected = false
        private set
    override suspend fun connect(c: Credentials) { connected = true }
    override fun disconnect() { connected = false }
    override suspend fun request(path: String, body: JSONObject?, delete: Boolean) = JSONObject(when (path) {
        "/v1/projects" -> """{"projects":[]}"""
        "/v1/threads" -> """{"threads":[],"projectsWithEmptyThreads":[],"approvals":[]}"""
        "/v1/status" -> """{"memory":{"used":1400000000,"total":4000000000},"disk":{"used":21000000000,"available":19000000000,"total":40000000000},
            "load":[0.18],"cpus":2,"uptime":273600,"swap":{"used":0},"timestamp":1784500000000,"services":[{"name":"cloud-workbench","state":"active"}]}"""
        else -> "{}"
    })
    override suspend fun browserImage(url: String, cookie: String, agent: String): ByteArray =
        error("This fixture only serves pages and WebView screenshots")
}

private class BrowserReviewServer(title: String) : AutoCloseable {
    private val server = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
    val port get() = server.localPort

    init {
        val page = ("<!doctype html><meta charset=utf-8><title>$title</title>" +
            "<h1>$title</h1><p>${title}内容，供真实 WebView 的取材与截图验收。</p>").toByteArray(Charsets.UTF_8)
        thread(isDaemon = true, name = "browser-review-http") {
            while (!server.isClosed) {
                val connection = try { server.accept() } catch (_: java.net.SocketException) { break }
                connection.use { socket ->
                    socket.soTimeout = 2000
                    runCatching {
                        val input = socket.getInputStream().bufferedReader(Charsets.US_ASCII)
                        while (!input.readLine().isNullOrEmpty()) {}
                        val output = socket.getOutputStream()
                        output.write(("HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\n" +
                            "Content-Length: ${page.size}\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n")
                            .toByteArray(Charsets.US_ASCII))
                        output.write(page)
                    }
                }
            }
        }
    }

    override fun close() = server.close()
}

internal fun captureReviewScreen(name: String) {
    val instrumentation = InstrumentationRegistry.getInstrumentation()
    val directory = File(instrumentation.targetContext.getExternalFilesDir(null), "ui-review").apply { mkdirs() }
    val flags = if (Build.VERSION.SDK_INT >= 31) UiAutomation.FLAG_DONT_USE_ACCESSIBILITY else 0
    val bitmap = instrumentation.getUiAutomation(flags).takeScreenshot()
    try {
        File(directory, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    } finally {
        bitmap.recycle()
    }
}
