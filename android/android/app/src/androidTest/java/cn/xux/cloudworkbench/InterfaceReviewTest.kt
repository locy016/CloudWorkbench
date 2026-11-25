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
import androidx.compose.ui.unit.dp
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

    @Test fun workspacePickerShowsEveryServerDirectory() {
        compose.waitUntil(90_000) {
            compose.onAllNodesWithText("已连接").fetchSemanticsNodes().isNotEmpty()
        }
        tab("会话")
        compose.waitUntil(25_000) {
            compose.onAllNodes(hasTestTag("projectPicker") and isEnabled()).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("projectPicker").performClick()
        compose.onNodeWithTag("workspacePickerMenu").assertWidthIsEqualTo(344.dp)
        compose.onNodeWithTag("project-cloud-assistant").assertTextContains("云端助理")
        compose.onNodeWithText("云端助理 · 默认", useUnmergedTree = true).assertDoesNotExist()
        for (name in listOf("cloud-assistant", "qiwuli-site", "server-ops")) {
            compose.onNodeWithText("/home/xux/projects/$name", useUnmergedTree = true).assertIsDisplayed()
        }
        compose.onNodeWithTag("project-server-gateway").assertDoesNotExist()
        compose.onNodeWithText("/home/xux/.local/lib/cloud-workbench", useUnmergedTree = true).assertDoesNotExist()
        screenshot("workspace-paths")
    }

    @Test fun navigationAndCredentialConfirmation() {
        compose.onNodeWithTag("nav-云端").assertDoesNotExist()
        compose.onNodeWithTag("menuToggle").assertIsDisplayed().performClick()
        listOf("云端", "会话", "浏览", "设置").forEach { name ->
            compose.onNodeWithTag("nav-$name").assertIsDisplayed().assertTextEquals(name)
        }
        compose.onNodeWithTag("nav-授权").assertDoesNotExist()
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
        }
        compose.onNodeWithTag("conversationSearch").assertIsDisplayed()
        screenshot("history")
        compose.onNodeWithText("关闭").performClick()
        compose.onNodeWithTag("project-任务").assertIsDisplayed().performClick()
        screenshot("tasks")
        compose.onNodeWithTag("project-升级").assertDoesNotExist()
        tab("浏览")
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

    @Test fun fixedServerDisclosesRecoveryOnlyOnDemand() {
        compose.connectWorkbenchIfNeeded()
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
