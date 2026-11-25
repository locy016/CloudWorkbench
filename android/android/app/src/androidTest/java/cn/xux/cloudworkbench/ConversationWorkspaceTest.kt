package cn.xux.cloudworkbench

import android.content.Intent
import android.view.WindowManager
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ConversationWorkspaceTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun twoModelsCanContinueTheSameAssistantConversation() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val marker = requireNotNull(InstrumentationRegistry.getArguments().getString("workspaceAcceptanceMarker"))
        require(marker.matches(Regex("WB060-[A-Z0-9]{8}")))
        val activity = instrumentation.startActivitySync(
            Intent(instrumentation.targetContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ) as MainActivity
        val prefs = activity.getSharedPreferences("ui", 0)
        val keys = listOf("project", "preferred-workspace", "thread-cloud", "thread", "draft-model:cloud-assistant")
        val initial = keys.associateWith { prefs.getString(it, null) }
        try {
            compose.runOnUiThread { activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
            compose.waitUntil(90_000) { compose.onAllNodesWithText("已连接").fetchSemanticsNodes().isNotEmpty() }
            compose.openWorkbenchTab("会话")
            compose.onNodeWithTag("newChat").performClick()
            compose.onNodeWithTag("projectPicker").performClick()
            compose.onNodeWithTag("project-qiwuli-site").assertExists()
            compose.onNodeWithTag("project-server-ops").assertExists()
            compose.onNodeWithTag("project-cloud-assistant").assertTextContains("云端助理").performClick()

            val picker = compose.onNodeWithTag("conversationModelPicker")
            fun choose(id: String) {
                compose.waitUntil(25_000) {
                    compose.onAllNodes(hasTestTag("conversationModelPicker") and isEnabled()).fetchSemanticsNodes().isNotEmpty()
                }
                picker.performClick()
                val tag = "conversationModel-$id"
                compose.waitUntil(25_000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }
                compose.onNodeWithTag(tag).performClick()
            }
            fun send(text: String, reply: String) {
                compose.onNodeWithTag("messageInput").performTextReplacement(text)
                compose.onNodeWithTag("send").performClick()
                compose.waitUntil(150_000) {
                    compose.onAllNodes(hasTestTag("message-assistant") and hasAnyDescendant(hasText(reply, substring = true)),
                        useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
                }
            }
            choose("gpt-6.2-sol")
            send("$marker 只回复 ${marker}-FIRST，不调用工具。", "${marker}-FIRST")
            val id = requireNotNull(prefs.getString("thread-cloud", null))
            choose("gpt-6-luna")
            compose.waitUntil(25_000) {
                compose.onAllNodes(hasTestTag("conversationModelPicker") and hasText("GPT-6-Luna", substring = true))
                    .fetchSemanticsNodes().isNotEmpty()
            }
            send("记住上一轮，仅回复 ${marker}-SECOND，不调用工具。", "${marker}-SECOND")
            org.junit.Assert.assertEquals(id, prefs.getString("thread-cloud", null))
        } finally {
            compose.runOnUiThread { activity.finish() }
            val edit = prefs.edit()
            initial.forEach { (key, value) -> edit.putString(key, value) }
            edit.commit()
        }
    }
}
