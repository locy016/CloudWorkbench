package cn.xux.cloudworkbench

import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick

internal fun ComposeTestRule.openWorkbenchTab(name: String) {
    onNodeWithTag("menuToggle").assertIsDisplayed().performClick()
    onNodeWithTag("nav-$name").assertIsDisplayed().performClick()
    onNodeWithTag("pageTitle").assertIsDisplayed().assertTextEquals(name)
}

internal fun ComposeTestRule.connectWorkbenchIfNeeded() {
    waitUntil(45000) { onAllNodes(hasText("已连接") or hasText("离线")).fetchSemanticsNodes().isNotEmpty() }
    if (onAllNodes(hasText("已连接")).fetchSemanticsNodes().isNotEmpty()) return
    openWorkbenchTab("设置")
    waitUntil(45000) {
        onAllNodes(hasTestTag("reconnect") and hasText("重试连接")).fetchSemanticsNodes().isNotEmpty() ||
            onAllNodes(hasText("已连接")).fetchSemanticsNodes().isNotEmpty()
    }
    if (onAllNodes(hasText("已连接")).fetchSemanticsNodes().isEmpty()) onNodeWithTag("reconnect").performClick()
    waitUntil(45000) { onAllNodes(hasText("已连接")).fetchSemanticsNodes().isNotEmpty() }
    openWorkbenchTab("云端")
}
