package cn.xux.cloudworkbench

import android.app.Application
import android.os.SystemClock
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException

@RunWith(AndroidJUnit4::class)
class ReconnectionTest {
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
    fun reconnectsAfterGatewayRestartAndLostSubmissionResponse() {
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
            gateway.threadStatus = "interrupted"
            gateway.available = true
        }
        await { model.hostOnline && !model.reconnecting }
        onMain {
            assertEquals(THREAD, model.currentId)
            assertEquals("interrupted", model.current?.getString("status"))
            assertEquals("u-${gateway.posted.single()}", model.messages.single().getString("id"))
            assertEquals("", model.draft)
            assertEquals(1, gateway.posted.size)
        }
    }

    @Test
    fun keepsUnreceivedDraftAndReusesRequestIdOnlyWhenUserRetries() {
        onMain {
            model.draft = "保留草稿"
            gateway.dropNextBeforeReceipt = true
            model.send()
        }
        await { model.reconnecting && gateway.posted.size == 1 }
        onMain { gateway.available = true }
        await { model.hostOnline && !model.reconnecting }
        onMain {
            assertEquals("保留草稿", model.draft)
            assertEquals(1, gateway.posted.size)
            assertTrue(model.messages.isEmpty())
            model.send()
        }
        await { !model.busy && model.messages.size == 1 }
        onMain {
            assertEquals(2, gateway.posted.size)
            assertEquals(gateway.posted.first(), gateway.posted.last())
            assertEquals("", model.draft)
        }
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
        }
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
        var dropNextResponse = false
        var dropNextBeforeReceipt = false
        var threadStatus = "idle"
        val posted = mutableListOf<String>()
        val updatePaths = mutableListOf<String>()
        val pages = ArrayDeque<JSONObject>()
        var rejectCursor = false
        var overviewApprovals = JSONArray()
        var businessFailure: Exception? = null
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

            override suspend fun request(path: String, body: JSONObject?, delete: Boolean): JSONObject {
                if (!available || !connected) throw IOException("模拟网络中断")
                if (path == "/v1/threads") businessFailure?.let { throw it }
                if (path.startsWith("/v1/threads/") && "/updates?" in path) updatePaths.add(path)
                if (path.startsWith("/v1/threads/$THREAD/updates?")) {
                    if (rejectCursor && "&cursor=" in path) {
                        rejectCursor = false
                        throw GatewayException(400, "分页参数无效")
                    }
                    return pages.removeFirstOrNull() ?: if (path.endsWith("&cursor=done")) update("done", emptyList())
                        else snapshot().put("cursor", "fixture-cursor").put("hasMore", false)
                            .put("reset", "&cursor=" !in path || threadStatus == "interrupted")
                }
                return when (path) {
                    "/v1/status" -> JSONObject()
                    "/v1/projects" -> JSONObject().put("projects", JSONArray().put(JSONObject().put("id", "default")))
                    "/v1/threads" -> JSONObject().put("threads", JSONArray().put(thread()).apply {
                        if (overviewApprovals.length() > 0) put(JSONObject().put("id", OTHER_THREAD)
                            .put("status", "approval").put("projectId", "default"))
                    })
                        .put("projectsWithEmptyThreads", JSONArray())
                        .put("approvals", overviewApprovals)
                    "/v1/threads?limit=40" -> JSONObject().put("threads", JSONArray().put(thread())).put("hasMore", false)
                    "/v1/threads/$THREAD/history?limit=40&before=older" -> JSONObject()
                        .put("messages", JSONArray(listOf(message("old", "更早", 10), message("new", "旧内容", 20))))
                        .put("hasMore", false)
                    "/v1/threads/$THREAD/messages" -> {
                        val submission = checkNotNull(body)
                        val id = submission.getString("requestId")
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
                        snapshot()
                    }
                    else -> error("Unexpected API path $path")
                }
            }

        }

        private fun thread() = JSONObject().put("id", THREAD).put("status", threadStatus).put("projectId", "default")
        fun message(id: String, text: String, created: Long) = JSONObject().put("id", id).put("role", "assistant")
            .put("text", text).put("created", created)
        fun update(cursor: String, messages: List<JSONObject>, more: Boolean = false, historyBefore: String? = null,
            reset: Boolean = false) = JSONObject().put("thread", thread()).put("approvals", JSONArray())
            .put("messages", JSONArray(messages)).put("cursor", cursor).put("reset", reset).put("hasMore", more)
            .put("historyBefore", historyBefore ?: JSONObject.NULL)

        private fun snapshot() = JSONObject().put("thread", thread())
            .put("messages", JSONArray(received.map { (id, text) ->
                JSONObject().put("id", "u-$id").put("role", "user").put("text", text)
            })).put("approvals", JSONArray())
    }

    private companion object {
        const val THREAD = "reconnect-test-thread"
        const val OTHER_THREAD = "reconnect-test-other-thread"
    }
}
