package cn.xux.cloudworkbench

import android.content.Intent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs

@RunWith(AndroidJUnit4::class)
class TaskPresentationTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun tasksKeepOneTrackedRunAndDiscloseReleaseDetailsOnDemand() {
        val now = System.currentTimeMillis()
        val request = BuildSelection("synthetic-tracked-build", "main", "a".repeat(40))
        val checks = """[{"id":"gateway","state":"success"}]"""
        val tracked = """{"id":42,"number":4,"source":"${request.commit}","state":"success","updatedAt":$now}"""
        val recent = """{"id":43,"number":5,"source":"${"b".repeat(40)}","state":"running","updatedAt":$now}"""
        val older = """{"id":41,"number":3,"source":"${"c".repeat(40)}","state":"failed","updatedAt":$now}"""
        val snapshot = """{"fetchedAt":$now,"runs":[$recent,$tracked,$older],"checks":$checks}"""
        val result = """{"requestId":"${request.id}","source":"${request.commit}","state":"accepted","runId":42,
            "snapshot":{"fetchedAt":$now,"runs":[$tracked],"checks":$checks}}"""
        val approval = """{"fingerprint":null,"releases":[{"state":"published","approval":{"by":"tablet"},"candidate":{
            "runId":"45754513517","versionName":"0.4.74","versionCode":87,"artifactId":15086754474,"digestVersion":2,
            "source":"f57fef3cf29052f4126682ca4ec1f660ca11902d","apkSha256":"4106ce8571bf78959b54e08f1bba8a082a5202c24646f1110a3e2798a9dad5f5",
            "approvalSha256":"c4c09d6bec2b0b033632b8ee68826496c40d01f4838c4dfe7b69073381145f64",
            "notes":"通过平板批准正式发布，验证独立签名和私有更新流程。","checks":{"gateway":"success","lint":"success","apk":"success"}}}]}"""
        val connection = object : WorkbenchConnection {
            override val connected = true
            override fun disconnect() = Unit
            override suspend fun connect(c: Credentials) = Unit
            override suspend fun request(path: String, body: JSONObject?, delete: Boolean): JSONObject = JSONObject(when (path) {
                "/v1/builds" -> snapshot
                "/v1/builds/requests/${request.id}" -> result
                "/v1/release-approvals" -> approval
                else -> error("Unexpected test request $path")
            })
        }
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val preferences = context.getSharedPreferences("task-presentation-test", 0)
        preferences.edit().clear().putString("github-actions-request", request.json().toString()).commit()
        val builds = BuildStatus(preferences) { connection }
        val releases = ReleaseApprovals { connection }
        val narrow = mutableStateOf(false)
        val activity = instrumentation.startActivitySync(Intent(context, ComponentActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as ComponentActivity
        try {
            compose.runOnUiThread {
                activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                activity.setContent {
                    WorkbenchTheme {
                        PageColumn {
                            Box(if (narrow.value) Modifier.widthIn(max = 340.dp) else Modifier) {
                                TaskOperationPanels(releases, builds, connected = true)
                            }
                        }
                    }
                }
            }
            compose.waitUntil(10_000) { releases.rows.isNotEmpty() && builds.snapshot != null && builds.tracked != null }
            val releaseBounds = compose.onNodeWithTag("releaseApprovals").fetchSemanticsNode().boundsInRoot
            val buildBounds = compose.onNodeWithTag("githubBuilds").fetchSemanticsNode().boundsInRoot
            val config = context.resources.configuration
            if (config.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE &&
                config.screenWidthDp - 32 >= 880 * config.fontScale.coerceAtLeast(1f)) {
                assertTrue(abs(releaseBounds.top - buildBounds.top) < 1f && releaseBounds.right < buildBounds.left)
                assertTrue(releaseBounds.width > buildBounds.width * 1.8f)
            } else {
                assertTrue(releaseBounds.top < buildBounds.top)
            }
            compose.onAllNodesWithText("构建 #4").assertCountEquals(1)
            compose.onNodeWithText("构建 #3").assertDoesNotExist()
            compose.onNodeWithText("批准摘要", substring = true).assertDoesNotExist()
            compose.onNodeWithTag("releaseDetails-45754513517").performScrollTo().performClick()
            compose.onNodeWithText("批准摘要 c4c09d6bec2b0b033632b8ee68826496c40d01f4838c4dfe7b69073381145f64").performScrollTo().assertIsDisplayed()
            compose.onNodeWithTag("releaseDetails-45754513517").performScrollTo().performClick()
            compose.onNodeWithTag("trackedBuildDetails").performScrollTo().performClick()
            compose.onAllNodesWithTag("githubCheck-gateway").assertCountEquals(2)
            compose.onNodeWithTag("githubBuildHistory").performScrollTo().performClick()
            compose.onNodeWithText("构建 #3").performScrollTo().assertIsDisplayed()
            compose.onNodeWithTag("toggleBuildPanel").performScrollTo().performClick()
            compose.onNodeWithTag("startGithubBuild").assertDoesNotExist()

            compose.runOnIdle { narrow.value = true }
            val narrowRelease = compose.onNodeWithTag("releaseApprovals").fetchSemanticsNode().positionInRoot
            val narrowBuild = compose.onNodeWithTag("githubBuilds").fetchSemanticsNode().positionInRoot
            assertTrue(abs(narrowRelease.x - narrowBuild.x) < 1f && narrowRelease.y < narrowBuild.y)
        } finally {
            compose.runOnUiThread { activity.finish() }
        }
    }

    @Test fun releaseDecisionStaysWithVersionThroughServiceReview() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val activity = instrumentation.startActivitySync(Intent(instrumentation.targetContext, ComponentActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as ComponentActivity
        val sample = ReleaseCandidate("901795766735255", "0.4.79", 92, "f".repeat(40), 1,
            "b".repeat(64), "a".repeat(64), "史时补充了中国、美国与苏联的近现代时间线。区域总览优先展示代表事件，其他记录可按需展开。正文通栏阅读，史料出处随文查阅。",
            mapOf("gateway" to "success", "lint" to "success", "apk" to "success"))
        val row = mutableStateOf(ReleaseApprovalRow(sample, "awaiting-approval", false, false))
        try {
            compose.runOnUiThread {
                activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                activity.setContent {
                    WorkbenchTheme {
                        PageColumn {
                            Panel {
                                androidx.compose.material3.Text("正式发布审批", style = androidx.compose.material3.MaterialTheme.typography.titleLarge)
                                ReleaseCandidateRow(row.value, row.value.state == "awaiting-approval" && !row.value.pending, true,
                                    viewRun = {}, decide = { row.value = row.value.copy(pending = true) })
                            }
                        }
                    }
                }
            }
            compose.onNodeWithText("已通过 3 项构建校验").assertIsDisplayed()
            compose.onNodeWithText("批准发布").assertIsDisplayed()
            captureReviewScreen("tasks-release-pending")
            compose.onNodeWithText("批准发布").performClick()
            compose.onNodeWithText("等待服务复核").assertIsDisplayed()
            compose.onNodeWithText("拒绝发布").assertDoesNotExist()
            compose.runOnIdle { row.value = row.value.copy(state = "published", pending = false, byTablet = true, receiptAtMs = 1795766953000) }
            compose.onNodeWithText("已发布").assertIsDisplayed()
            compose.onNodeWithText("回执更新", substring = true).assertIsDisplayed()
            captureReviewScreen("tasks-release-published")
        } finally { compose.runOnUiThread { activity.finish() } }
    }
}
