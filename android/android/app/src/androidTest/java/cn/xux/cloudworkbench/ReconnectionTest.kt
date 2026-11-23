package cn.xux.cloudworkbench

import android.app.Application
import android.content.Intent
import android.graphics.Bitmap
import android.os.SystemClock
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.CompletableDeferred
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException
import java.io.File

@RunWith(AndroidJUnit4::class)
class ReconnectionTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val instrument = InstrumentationRegistry.getInstrumentation()
    private val app = instrument.targetContext.applicationContext as Application
    private val prefs = app.getSharedPreferences("reconnect-test", 0)
    private val models = ViewModelStore()
    private val gateway = GatewayFixture()
    private lateinit var model: WorkbenchModel

    @Before
    fun setUp() {
        onMain {
            prefs.edit().clear().putString("thread-cloud", THREAD).commit()
            model = WorkbenchModel(app, gateway::newConnection, Credentials("fixture"), prefs)
            models.put("workbench", model)
            model.foreground(true)
        }
        await { model.hostOnline && model.currentId == THREAD }
    }

    @After
    fun tearDown() {
        onMain {
            model.foreground(false)
            models.clear()
            prefs.edit().clear().commit()
        }
    }

    @Test
    fun businessErrorsLeaveSshManagementConnectedAndRecoverAutomatically() {
        val attempts = onMain { gateway.connects }
        for (failure in listOf(GatewayException(503, "Unavailable"), GatewayException(403, "Denied"),
            GatewayPayloadException(org.json.JSONException("Invalid JSON")))) {
            onMain { gateway.businessFailure = failure }
            await { !model.hostOnline }
            onMain {
                assertTrue(model.connected)
                assertEquals(null, model.connectionBlock)
                assertEquals(attempts, gateway.connects)
            }
            onMain { gateway.businessFailure = null }
            await { model.hostOnline }
        }
    }

    @Test
    fun reconnectsAndUsesReceiptWhenExecutionFailedAndTheMessageIsOutsideTheWindow() {
        onMain {
            model.draft = "一条任务"
            gateway.dropNextResponse = true
            model.send()
        }
        await { model.reconnecting && !model.connected && gateway.connects >= 2 }
        onMain {
            assertEquals(THREAD, model.currentId)
            assertEquals("一条任务", model.draft)
            assertEquals(1, gateway.posted.size)
            gateway.threadStatus = "failed"
            gateway.hideReceiptMessages = true
            gateway.available = true
            recreateModel()
        }
        await { model.hostOnline && !model.reconnecting }
        onMain {
            assertEquals(THREAD, model.currentId)
            assertEquals("failed", model.current?.getString("status"))
            assertTrue(model.messages.isEmpty())
            assertEquals("", model.draft)
            assertEquals(1, gateway.posted.size)
            assertEquals("服务器已接收", model.submissionHint)
        }
    }

    @Test
    fun keepsUnreceivedDraftAndReusesRequestIdOnlyWhenUserRetries() {
        val photo = File(File(app.cacheDir, "camera").apply { mkdirs() }, "reconnect-draft.png")
        val bitmap = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888)
        try {
            photo.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        } finally {
            bitmap.recycle()
        }
        onMain {
            val uri = FileProvider.getUriForFile(app, "${app.packageName}.camera", photo)
            model.importImages(model.draftKey, listOf(uri), photo)
        }
        await { !model.importingAttachment && model.draftImages.size == 1 }
        val imageId = onMain { model.draftImages.single().upload.localId }
        assertFalse(photo.exists())
        onMain {
            model.draft = "保留草稿"
            gateway.dropNextBeforeReceipt = true
            model.send()
        }
        await { model.reconnecting && gateway.posted.size == 1 }
        onMain {
            gateway.available = true
            recreateModel()
        }
        await { model.hostOnline && !model.reconnecting }
        onMain {
            assertEquals(THREAD, model.currentId)
            assertEquals("保留草稿", model.draft)
            assertEquals(imageId, model.draftImages.single().upload.localId)
            assertTrue(model.draftImages.single().upload.file.exists())
            assertEquals("服务器未收到，可手动重试", model.submissionHint)
            assertEquals("可重试", model.continuations.single().state)
            assertEquals(1, gateway.posted.size)
            assertTrue(model.messages.isEmpty())
            model.send()
        }
        await { !model.busy && model.messages.size == 1 }
        onMain {
            assertEquals(2, gateway.posted.size)
            assertEquals(gateway.posted.first(), gateway.posted.last())
            assertEquals(1, gateway.uploads)
            assertEquals("", model.draft)
            assertEquals(emptyList<DraftImage>(), model.draftImages)
        }
    }

    @Test
    fun aStalledOrFailedOverviewDoesNotBlockConversationAndApprovalSync() {
        val metrics = CompletableDeferred<JSONObject>()
        onMain {
            gateway.metrics = metrics
            gateway.overviewApprovals = JSONArray().put(gateway.approval("other-approval", "只读检查"))
            recreateModel()
        }
        await { gateway.metricsRequests > 0 && model.hostOnline && model.pendingApprovals.size == 1 }
        metrics.completeExceptionally(GatewayException(503, "Metrics failed"))
        await { model.overviewUnavailable }
        onMain {
            assertTrue(model.connected && model.hostOnline)
            assertEquals("other-approval", model.pendingApprovals.single().getString("id"))
            model.openContinuation(model.continuations.first())
        }
        await { model.focusedApprovalId == "other-approval" && !model.busy }
        onMain { model.answer(model.approvals.single(), "decline") }
        await { model.hostOnline && model.approvals.isEmpty() }
        onMain { assertEquals(listOf("other-approval"), gateway.decisions) }
    }

    @Test
    fun homeOpensTheExactApprovalAndTheLatestResultInOneTap() {
        onMain {
            gateway.overviewApprovals = JSONArray().put(gateway.approval("first-approval", "第一项检查"))
                .put(gateway.approval("second-approval", "第二项检查"))
            gateway.hasRecentResult = true
        }
        await { model.continuations.count { it.approvalId != null } == 2 && model.latestResults.size == 1 }
        val activity = instrument.startActivitySync(Intent(app, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        try {
            onMain {
                activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                activity.setContent { WorkbenchTheme { Workbench(model) } }
            }
            compose.onNodeWithTag("home-approval-second-approval").performScrollTo().performClick()
            compose.waitUntil(10_000) { compose.onAllNodesWithTag("focusedApproval").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("第二项检查").assertIsDisplayed()
            compose.onNodeWithText("第一项检查").assertDoesNotExist()
            compose.onNodeWithText("拒绝").performScrollTo().performClick()
            compose.waitUntil(10_000) { compose.onAllNodesWithText("此请求已处理或已失效").fetchSemanticsNodes().isNotEmpty() }
            assertEquals(listOf("second-approval"), onMain { gateway.decisions.toList() })
            compose.openWorkbenchTab("云端")
            compose.onNodeWithTag("home-approval-second-approval").assertDoesNotExist()
            compose.onNodeWithTag("home-result-$RESULT_THREAD").performScrollTo().performClick()
            compose.waitUntil(10_000) { compose.onAllNodesWithText("验收结果已就绪").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("conversationTitle").assertTextEquals("最近完成的检查")
        } finally {
            onMain { activity.finish() }
            instrument.waitForIdleSync()
        }
    }

    @Test
    fun restoresIndependentProjectAndConversationDrafts() {
        onMain {
            model.draft = "已选会话的草稿"
            model.newThread()
            model.draft = "默认项目的新会话草稿"
            model.selectProject(OTHER_PROJECT)
            model.draft = "另一个项目的新会话草稿"
            recreateModel()
        }
        await { model.hostOnline }
        onMain {
            assertEquals(OTHER_PROJECT, model.projectId)
            assertEquals("另一个项目的新会话草稿", model.draft)
            model.selectProject("default")
            assertEquals("默认项目的新会话草稿", model.draft)
            model.select(THREAD)
        }
        await { !model.busy && model.currentId == THREAD }
        onMain { assertEquals("已选会话的草稿", model.draft) }
    }

    @Test
    fun explicitDisconnectCancelsRecovery() {
        onMain {
            model.draft = "保持编辑"
            gateway.available = false
        }
        await { model.reconnecting && !model.connected }
        val attempts = onMain {
            model.disconnect()
            gateway.available = true
            model.foreground(false)
            model.foreground(true)
            model.defaultNetworkChanged(100)
            model.defaultNetworkChanged(101)
            gateway.connects
        }
        SystemClock.sleep(2500)
        onMain {
            assertFalse(model.connected)
            assertFalse(model.reconnecting)
            assertEquals(attempts, gateway.connects)
            assertEquals(THREAD, model.currentId)
            assertEquals("保持编辑", model.draft)
            assertTrue(gateway.posted.isEmpty())
        }
    }

    @Test
    fun networkRecoveryReopensTransportWithoutWaitingForTheFailedConnection() {
        val attempts = onMain {
            model.defaultNetworkChanged(100)
            model.draft = "切网时保留草稿"
            model.defaultNetworkChanged(null)
            assertFalse(model.connected)
            assertTrue(model.reconnecting)
            gateway.connects
        }
        SystemClock.sleep(1200)
        onMain {
            assertEquals(attempts, gateway.connects)
            model.defaultNetworkChanged(101)
        }
        await { model.hostOnline && gateway.connects == attempts + 1 }
        onMain {
            assertEquals(THREAD, model.currentId)
            assertEquals("切网时保留草稿", model.draft)
            assertTrue(gateway.posted.isEmpty())
        }
    }

    @Test
    fun keepsTheExistingSshSessionAndEventStreamDuringAShortBackgroundSwitch() {
        val (connects, streams, updates) = onMain {
            model.defaultNetworkChanged(100)
            model.draft = "交接草稿"
            model.foreground(false)
            Triple(gateway.connects, gateway.streams, gateway.updatePaths.size)
        }
        await { gateway.updatePaths.size > updates }
        onMain {
            model.foreground(true)
            assertTrue(model.hostOnline)
            assertFalse(model.reconnecting)
            assertEquals(connects, gateway.connects)
            assertEquals(streams, gateway.streams)
            assertEquals(THREAD, model.currentId)
            assertEquals("交接草稿", model.draft)
        }
    }

    @Test
    fun reconnectsOnceWhenTheNetworkChangesInTheBackground() {
        val connects = onMain {
            model.defaultNetworkChanged(100)
            model.foreground(false)
            model.defaultNetworkChanged(null)
            model.defaultNetworkChanged(101)
            val previous = gateway.connects
            model.foreground(true)
            previous
        }
        await { model.hostOnline && gateway.connects == connects + 1 }
        onMain {
            assertFalse(model.reconnecting)
            assertEquals(THREAD, model.currentId)
        }
    }

    @Test
    fun reopensTheSshSessionWhenItsSocketClosedInTheBackground() {
        val connects = onMain {
            model.foreground(false)
            gateway.latestConnection?.disconnect()
            val previous = gateway.connects
            model.foreground(true)
            previous
        }
        await { model.hostOnline && gateway.connects == connects + 1 }
    }

    @Test
    fun mergesUpdatesAcrossPagesAndLoadsOlderMessages() {
        onMain {
            gateway.pages.add(gateway.update("next", messages = listOf(gateway.message("new", "片段", 20)), more = true))
            gateway.pages.add(gateway.update("done", messages = listOf(gateway.message("new", "全文", 20)),
                historyBefore = "older", reset = true))
        }
        await { model.canLoadOlderMessages && model.messages.singleOrNull()?.optString("text") == "全文" }
        onMain {
            assertTrue(gateway.updatePaths.any { it.endsWith("&cursor=next") })
            model.loadOlderMessages()
        }
        await { model.messages.size == 2 && !model.busy }
        onMain {
            assertEquals(listOf("old", "new"), model.messages.map { it.getString("id") })
            assertEquals("全文", model.messages.last().getString("text"))
            assertFalse(model.canLoadOlderMessages)
        }
    }

    @Test
    fun retriesAnInvalidUpdatesCursorWithoutUsingTheLegacySnapshot() {
        onMain { gateway.rejectCursor = true }
        await { gateway.updatePaths.size >= 3 && gateway.updatePaths.last() == "/v1/threads/$THREAD/updates?limit=40" }
        onMain {
            assertEquals(THREAD, model.currentId)
            assertTrue(model.hostOnline)
        }
    }

    @Test
    fun readsOtherThreadApprovalsFromTheOverview() {
        onMain {
            gateway.overviewApprovals = JSONArray()
                .put(JSONObject().put("id", "current-approval").put("threadId", THREAD))
                .put(JSONObject().put("id", "other-approval").put("threadId", OTHER_THREAD))
        }
        await { model.pendingApprovals.map { it.getString("id") } == listOf("other-approval") }
        onMain {
            assertEquals(THREAD, model.currentId)
            assertTrue(gateway.updatePaths.all { it.startsWith("/v1/threads/$THREAD/updates?") })
            val item = model.continuations.single()
            assertEquals(OTHER_THREAD, item.id)
            assertEquals(OTHER_PROJECT, item.project)
            assertEquals("待审批", item.state)
            model.select(item.id)
        }
        await { !model.busy && model.currentId == OTHER_THREAD }
        onMain {
            assertEquals(OTHER_PROJECT, model.projectId)
            assertEquals(WorkbenchPage.Sessions, model.tab)
            assertEquals("other-approval", model.approvals.single().getString("id"))
        }
    }

    private fun recreateModel() {
        model.foreground(false)
        models.clear()
        model = WorkbenchModel(app, gateway::newConnection, Credentials("fixture"), prefs)
        models.put("workbench", model)
        model.foreground(true)
    }

    private fun await(condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 20000
        while (SystemClock.elapsedRealtime() < deadline) {
            if (onMain(condition)) return
            SystemClock.sleep(50)
        }
        assertTrue("Expected state was not reached before the deadline", onMain(condition))
    }

    private fun <T> onMain(block: () -> T): T {
        var outcome: Result<T>? = null
        instrument.runOnMainSync { outcome = runCatching(block) }
        return checkNotNull(outcome).getOrThrow()
    }

    private class GatewayFixture {
        var available = true
        var connects = 0
        var streams = 0
        var latestConnection: WorkbenchConnection? = null
        var dropNextResponse = false
        var dropNextBeforeReceipt = false
        var hideReceiptMessages = false
        var threadStatus = "idle"
        var uploads = 0
        val posted = mutableListOf<String>()
        val updatePaths = mutableListOf<String>()
        val pages = ArrayDeque<JSONObject>()
        var rejectCursor = false
        var overviewApprovals = JSONArray()
        var businessFailure: Exception? = null
        var metrics: CompletableDeferred<JSONObject>? = null
        var metricsRequests = 0
        var hasRecentResult = false
        val decisions = mutableListOf<String>()
        private val received = linkedMapOf<String, String>()

        fun newConnection(): WorkbenchConnection = object : WorkbenchConnection {
            override var connected = false
                private set

            override suspend fun connect(c: Credentials) {
                connects++
                if (!available) throw IOException("模拟网络中断")
                connected = true
            }

            override fun disconnect() { connected = false }

            override fun changes() = super<WorkbenchConnection>.changes().onStart { streams++ }

            override suspend fun request(path: String, body: JSONObject?, delete: Boolean): JSONObject {
                if (!available || !connected) throw IOException("模拟网络中断")
                if (path == "/v1/threads") businessFailure?.let { throw it }
                if (path.startsWith("/v1/threads/") && "/updates?" in path) updatePaths.add(path)
                if (path.startsWith("/v1/threads/$THREAD/updates?")) {
                    if (rejectCursor && "&cursor=" in path) {
                        rejectCursor = false
                        throw GatewayException(400, "分页参数无效")
                    }
                    val response = pages.removeFirstOrNull() ?: if (path.endsWith("&cursor=done")) update("done", emptyList())
                        else snapshot().put("cursor", "fixture-cursor").put("hasMore", false)
                            .put("reset", "&cursor=" !in path || threadStatus == "interrupted")
                    path.substringAfter("&requestId=", "").takeIf { it.isNotEmpty() }?.let { id ->
                        response.put("receipt", JSONObject().put("requestId", id).put("state", if (id in received) "accepted" else "missing"))
                    }
                    return response
                }
                if (path.startsWith("/v1/threads/$OTHER_THREAD/updates?")) {
                    val approvals = overviewApprovals.objects().filter { it.optString("threadId") == OTHER_THREAD }
                    return JSONObject().put("thread", otherThread()).put("cursor", "other-fixture-cursor")
                        .put("hasMore", false).put("reset", true).put("messages", JSONArray()).put("approvals", JSONArray(approvals))
                }
                if (path.startsWith("/v1/threads/$RESULT_THREAD/updates?")) {
                    return update("result-cursor", listOf(message("latest-result", "验收结果已就绪", 50)), reset = true)
                        .put("thread", resultThread())
                }
                if (path.startsWith("/v1/approvals/")) {
                    val id = path.substringAfterLast('/')
                    decisions.add(id)
                    overviewApprovals = JSONArray(overviewApprovals.objects().filterNot { it.getString("id") == id })
                    return JSONObject().put("thread", otherThread()).put("messages", JSONArray())
                        .put("approvals", overviewApprovals)
                }
                return when (path) {
                    "/v1/status" -> { metricsRequests++; metrics?.await() ?: JSONObject() }
                    "/v1/projects" -> JSONObject().put("projects", JSONArray().put(JSONObject().put("id", "default"))
                        .put(JSONObject().put("id", OTHER_PROJECT)))
                    "/v1/threads" -> JSONObject().put("threads", JSONArray().put(thread()).apply {
                        if (overviewApprovals.length() > 0) put(otherThread())
                    })
                        .put("projectsWithEmptyThreads", JSONArray())
                        .put("approvals", overviewApprovals)
                        .put("recentResults", if (hasRecentResult) JSONArray().put(resultThread()) else JSONArray())
                    "/v1/threads?limit=40" -> JSONObject().put("threads", JSONArray().put(thread())).put("hasMore", false)
                    "/v1/threads/$THREAD/history?limit=40&before=older" -> JSONObject()
                        .put("messages", JSONArray(listOf(message("old", "更早", 10), message("new", "旧内容", 20))))
                        .put("hasMore", false)
                    "/v1/attachments" -> {
                        assertEquals(THREAD, body?.getString("threadId"))
                        assertEquals("image/png", body?.getString("mime"))
                        JSONObject().put("id", "attachment-${++uploads}")
                    }
                    "/v1/threads/$THREAD/messages" -> {
                        val submission = checkNotNull(body)
                        val id = submission.getString("requestId")
                        if (uploads != 0) assertEquals("attachment-1", submission.getJSONArray("attachmentIds").getString(0))
                        posted.add(id)
                        if (dropNextBeforeReceipt) {
                            dropNextBeforeReceipt = false
                            available = false
                            throw IOException("写入前断网")
                        }
                        received[id] = submission.getString("text")
                        if (dropNextResponse) {
                            dropNextResponse = false
                            available = false
                            throw IOException("写入后回执丢失")
                        }
                        snapshot().put("receipt", JSONObject().put("requestId", id).put("state", "accepted"))
                    }
                    else -> error("Unexpected API path $path")
                }
            }

        }.also { latestConnection = it }

        private fun thread() = JSONObject().put("id", THREAD).put("status", threadStatus).put("projectId", "default")
        private fun otherThread() = JSONObject().put("id", OTHER_THREAD).put("status", "approval").put("projectId", OTHER_PROJECT)
        private fun resultThread() = JSONObject().put("id", RESULT_THREAD).put("title", "最近完成的检查")
            .put("status", "idle").put("projectId", "default").put("updated", 50)
        fun approval(id: String, reason: String) = JSONObject().put("id", id).put("threadId", OTHER_THREAD)
            .put("method", "item/commandExecution/requestApproval")
            .put("params", JSONObject().put("reason", reason).put("command", "printf audit"))
        fun message(id: String, text: String, created: Long) = JSONObject().put("id", id).put("role", "assistant")
            .put("text", text).put("created", created)
        fun update(cursor: String, messages: List<JSONObject>, more: Boolean = false, historyBefore: String? = null,
            reset: Boolean = false) = JSONObject().put("thread", thread()).put("approvals", JSONArray())
            .put("messages", JSONArray(messages)).put("cursor", cursor).put("reset", reset).put("hasMore", more)
            .put("historyBefore", historyBefore ?: JSONObject.NULL)

        private fun snapshot() = JSONObject().put("thread", thread())
            .put("messages", JSONArray(received.filter { !hideReceiptMessages }.map { (id, text) ->
                JSONObject().put("id", "u-$id").put("role", "user").put("text", text)
            })).put("approvals", JSONArray())
    }

    private companion object {
        const val THREAD = "reconnect-test-thread"
        const val OTHER_THREAD = "reconnect-test-other-thread"
        const val RESULT_THREAD = "reconnect-test-result-thread"
        const val OTHER_PROJECT = "fixture-project"
    }
}
