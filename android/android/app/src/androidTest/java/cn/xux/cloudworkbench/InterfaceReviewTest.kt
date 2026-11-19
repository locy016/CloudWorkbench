package cn.xux.cloudworkbench

import android.graphics.Bitmap
import android.app.UiAutomation
import android.os.Build
import android.view.WindowManager
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

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
        compose.onNodeWithTag("newChat").assertTextEquals("新对话")
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
        compose.onNodeWithTag("voiceToggle").assertContentDescriptionEquals("点击说话")
        compose.onNodeWithText("点击说话").assertDoesNotExist()
        screenshot("conversation")
        compose.onNodeWithTag("keyboardToggle").performClick()
        val input = compose.onNodeWithTag("messageInput")
        input.performClick().performTextReplacement("界面验收草稿，不发送")
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
        val window = compose.activity.window.decorView
        val imeBottom = androidx.core.view.ViewCompat.getRootWindowInsets(window)
            ?.getInsets(androidx.core.view.WindowInsetsCompat.Type.ime())?.bottom ?: 0
        // Floating IMEs report zero bottom inset and can be moved by the user.
        if (imeBottom > 0) {
            val bottom = input.fetchSemanticsNode().boundsInWindow.bottom
            org.junit.Assert.assertTrue("Composer is covered by the docked keyboard", bottom <= window.height - imeBottom + 1)
        }
        screenshot("keyboard")
        compose.onNodeWithContentDescription("移除 拍摄的照片").assertIsDisplayed().performClick()
        compose.onNodeWithTag("draftImages").assertDoesNotExist()
        input.performTextClearance()
        compose.activity.runOnUiThread {
            androidx.core.view.WindowCompat.getInsetsController(compose.activity.window, compose.activity.window.decorView)
                .hide(androidx.core.view.WindowInsetsCompat.Type.ime())
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
