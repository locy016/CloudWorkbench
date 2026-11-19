package cn.xux.cloudworkbench

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Real USB-device acceptance: uses the private, pre-provisioned SSH connection. */
@RunWith(AndroidJUnit4::class)
class DeviceAcceptanceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    @Before fun keepAcceptanceVisible() {
        compose.activity.runOnUiThread {
            compose.activity.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }
    private fun conversations() {
        compose.openWorkbenchTab("会话")
        compose.onNodeWithTag("project-对话").performClick()
    }
    @Test fun statusAndRealCodexRoundTrip() {
        compose.connectWorkbenchIfNeeded()
        compose.onAllNodesWithText("阿里云 · Ubuntu").onLast().assertIsDisplayed()
        conversations()
        compose.waitUntil(60000) { compose.onAllNodesWithText("正在提交…").fetchSemanticsNodes().isEmpty() }
        compose.onNodeWithTag("newChat").performClick()
        compose.onNodeWithTag("keyboardToggle").performClick()
        compose.waitUntil(60000) { compose.onAllNodesWithText("正在提交…").fetchSemanticsNodes().isEmpty() }
        compose.onNodeWithTag("messageInput").performTextInput("手机真机验收：请实际运行 id -un 和 python3 --version，只读，不修改任何文件。成功后在最后回复 MOBILE_CHECK_OK 并附上实际输出。")
        compose.waitUntil(60000) { compose.onAllNodes(hasTestTag("send") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("send").assertIsEnabled().performClick()
        compose.waitUntil(150000) {
            compose.onAllNodes(hasTestTag("message-assistant") and hasAnyDescendant(hasText("MOBILE_CHECK_OK", substring = true)), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("messages").performScrollToNode(hasTestTag("message-command"))
        compose.onAllNodesWithTag("message-command").onFirst().assertExists()
    }
    @Test fun explicitApprovalCanBeDeclined() {
        compose.connectWorkbenchIfNeeded()
        conversations()
        compose.waitUntil(10000) { compose.onAllNodesWithTag("newChat").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("newChat").performClick()
        compose.onNodeWithTag("keyboardToggle").performClick()
        compose.waitUntil(60000) { compose.onAllNodesWithText("正在提交…").fetchSemanticsNodes().isEmpty() }
        val title = "审批验收-${System.currentTimeMillis()}"
        compose.onNodeWithTag("messageInput").performTextReplacement("$title：请用命令工具显式申请沙箱外权限执行 printf test。收到拒绝后停止执行。")
        compose.waitUntil(60000) { compose.onAllNodes(hasTestTag("send") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("send").performTouchInput { click() }
        compose.waitUntil(120000) { compose.onAllNodesWithText("项请求等待你确认", substring = true).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("newChat").performClick()
        compose.onNodeWithTag("project-任务").performClick()
        compose.waitUntil(10000) { compose.onAllNodesWithText("拒绝").fetchSemanticsNodes().isNotEmpty() }
        captureReviewScreen("approval")
        compose.onNodeWithText("拒绝").performTouchInput { click() }
        compose.waitUntil(20000) { compose.onAllNodesWithText("拒绝").fetchSemanticsNodes().isEmpty() }
        compose.onNodeWithTag("project-对话").performClick()
        compose.onNodeWithTag("conversationHistory").performClick()
        compose.onNodeWithTag("conversationSearch").performTextReplacement(title)
        compose.onNode(hasText(title, substring = true) and hasClickAction() and !hasTestTag("conversationSearch")).performClick()
        compose.waitUntil(20000) {
            compose.onAllNodes(hasTestTag("conversationTitle") and hasText(title, substring = true),
                useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("项请求等待你确认", substring = true).assertDoesNotExist()
    }
}
