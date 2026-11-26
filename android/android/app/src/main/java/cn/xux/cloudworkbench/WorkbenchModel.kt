package cn.xux.cloudworkbench

import android.app.Application
import android.content.SharedPreferences
import android.net.Uri
import android.os.SystemClock
import android.util.Base64
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONException
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.UUID

fun JSONArray.objects(): List<JSONObject> = (0 until length()).map { getJSONObject(it) }
internal val activeTaskStates = setOf("starting", "running", "approval")
internal const val MAX_DRAFT_LENGTH = 12000
internal data class Continuation(val id: String, val title: String, val project: String, val state: String,
    val approvalId: String? = null, val updated: Long? = null)
internal data class SavedDraft(val key: String, val title: String, val projectId: String?, val projectName: String,
    val preview: String, val images: Int, val updatedAt: Long)
internal data class VoiceSubmission(val threadId: String, val requestId: String)
internal enum class SshConnectionBlock(val message: String) {
    CREDENTIALS("SSH 密钥验证失败，请确认公钥已在服务器授权"),
    HOST_IDENTITY("服务器身份校验失败，请联系管理员核对 SSH 主机密钥")
}

enum class WorkbenchPage(val title: String) {
    Cloud("云端"),
    Sessions("会话"),
    Browse("浏览"),
    Settings("设置")
}

class WorkbenchModel internal constructor(
    app: Application,
    private val newConnection: () -> WorkbenchConnection,
    initialCredentials: Credentials? = null,
    private val prefs: SharedPreferences = app.getSharedPreferences("ui", 0)
) : AndroidViewModel(app) {
    constructor(app: Application) : this(app, { Connection() })

    private val vault = Vault(app)
    private val persistBinding = initialCredentials == null
    private var connection = newConnection()
    internal val appUpdates = AppUpdates(app, viewModelScope) { connection }
    internal var transfers by mutableStateOf<ConnectionTransfers?>(null); private set
    private var credentials by mutableStateOf(initialCredentials ?: vault.load())
    private var connectionMonitor: Job? = null
    private var backgroundPause: Job? = null
    private var foreground = false
    private var reconnectAutomatically = prefs.getBoolean("reconnectAutomatically", true)
    internal var connectionBlock by mutableStateOf<SshConnectionBlock?>(null); private set
    private var observedNetwork = false
    private var defaultNetwork: Long? = null
    private var routeChanged = false
    private val networkUnavailable get() = observedNetwork && defaultNetwork == null
    var connected by mutableStateOf(false); private set
    var connecting by mutableStateOf(false); private set
    var reconnecting by mutableStateOf(false); private set
    var busy by mutableStateOf(false); private set
    var error by mutableStateOf<String?>(null); private set
    internal var serverOverview by mutableStateOf(prefs.getString(OVERVIEW_CACHE, null)?.let {
        runCatching { ServerOverview.parse(JSONObject(it)) }.getOrNull()
    }); private set
    internal var overviewUnavailable by mutableStateOf(false); private set
    internal var overviewRefreshing by mutableStateOf(false); private set
    private var overviewFresh by mutableStateOf(false)
    private var manualOverview: Job? = null
    internal val overviewLive get() = connected && !connecting && !reconnecting && overviewFresh && !overviewUnavailable
    internal val canRefreshOverview get() = connected && !connecting && !reconnecting && !overviewRefreshing
    var threads by mutableStateOf<List<JSONObject>>(emptyList()); private set
    var historyThreads by mutableStateOf<List<JSONObject>>(emptyList()); private set
    private var historyThreadsBefore by mutableStateOf<String?>(null)
    internal var historyFilter by mutableStateOf<HistoryFilter?>(null); private set
    internal var historyLoading by mutableStateOf(false); private set
    internal var historyLoadError by mutableStateOf<String?>(null); private set
    private var historyLoad: Job? = null
    val canLoadMoreConversations get() = historyThreadsBefore != null
    private var historyBefore by mutableStateOf<String?>(null)
    val canLoadOlderMessages get() = historyBefore != null
    private var updateCursor: String? = null
    private var lastOverviewCheck: Long? = null
    private var lastProjects = 0L
    var projects by mutableStateOf<List<JSONObject>>(emptyList()); private set
    private var projectsWithEmptyThreads by mutableStateOf(emptySet<String>())
    var projectId by mutableStateOf(prefs.getString("project", "default") ?: "default"); private set
    internal var defaultWorkspaceId by mutableStateOf<String?>(null); private set
    private var workspaceInitialized = false
    private var preferredWorkspace = prefs.getString("preferred-workspace", null)
    private var draftModel by mutableStateOf(prefs.getString("draft-model:$projectId", null))
    internal var models by mutableStateOf<List<ConversationModel>>(emptyList()); private set
    internal var modelsLoading by mutableStateOf(false); private set
    internal var modelError by mutableStateOf<String?>(null); private set
    internal val conversationModel get() = if (currentId == null) draftModel else
        current?.optString("model")?.takeUnless { it.isBlank() || it == "null" }
    internal val conversationModelLabel get() = models.firstOrNull { it.id == conversationModel }?.name
        ?: conversationModel ?: if (currentId != null) "沿用会话模型"
        else models.firstOrNull { it.default }?.name ?: "默认模型"
    internal val canChooseConversationModel get() =
        connected && hostOnline && !connecting && !reconnecting && !busy && !running && canSendInConversation
    var hostOnline by mutableStateOf(false); private set
    val hostName = "阿里云 · Ubuntu"
    var currentId by mutableStateOf(prefs.getString("thread-cloud", prefs.getString("thread", null))); private set
    var current by mutableStateOf<JSONObject?>(null); private set
    internal var draftThreadMissing by mutableStateOf(false); private set
    var messages by mutableStateOf<List<JSONObject>>(emptyList()); private set
    var loadingConversation by mutableStateOf<String?>(null); private set
    var approvals by mutableStateOf<List<JSONObject>>(emptyList()); private set
    private var otherApprovals by mutableStateOf<List<JSONObject>>(emptyList())
    private var recentResults by mutableStateOf<List<JSONObject>>(emptyList())
    internal var focusedApprovalId by mutableStateOf<String?>(null); private set
    var tab by mutableStateOf(WorkbenchPage.Cloud)
    var projectSection by mutableStateOf("对话")
    internal val browserReady get() =
        androidx.webkit.WebViewFeature.isFeatureSupported(androidx.webkit.WebViewFeature.DOCUMENT_START_SCRIPT)
    internal val browserStatus get() = if (browserReady) "设备直连" else "请更新 Android WebView"
    var browserUrl by mutableStateOf("")
    var browserSaved: android.os.Bundle? = null
    internal val browserBookmarks = BrowserBookmarks(prefs)
    internal val serverDataConnected get() = connected && !connecting && !reconnecting
    internal val newsFeed = NewsFeed(prefs) { connection.takeIf { serverDataConnected } }
    internal val liveExchangeRates = LiveExchangeRates(prefs) { connection.takeIf { serverDataConnected } }
    internal val buildStatus = BuildStatus(prefs) { connection.takeIf { serverDataConnected && hostOnline } }
    internal val releaseApprovals = ReleaseApprovals { connection.takeIf { serverDataConnected && hostOnline } }
    private val draftStore = DraftStore(prefs, File(app.noBackupFilesDir,
        if (persistBinding) "workbench-drafts" else "workbench-test-drafts"))
    private val drafts = mutableStateMapOf<String, DraftRecord>().apply { putAll(draftStore.restore()) }
    private var receivedThread by mutableStateOf(draftStore.lastReceivedThread())
    private val receipts = mutableStateMapOf<String, SubmissionReceipt>()
    private val attachmentErrors = mutableStateMapOf<String, String>()
    private var importingKey by mutableStateOf<String?>(null)
    internal val draftKey get() = currentId ?: "new:$projectId"
    internal val dictation = OfflineDictation(app, viewModelScope)
    internal val draftImages get() = drafts[draftKey]?.images.orEmpty()
    internal val imageSlots get() = MAX_MESSAGE_IMAGES - drafts[draftKey]?.uploads.orEmpty().size
    internal val importingAttachment get() = importingKey == draftKey
    internal val attachmentError get() = attachmentErrors[draftKey]
    internal val hasDraft get() = draftRecord(draftKey).messageText() != null
    internal val retrySubmission get() = draftRecord(draftKey).let { record ->
        record.attempt?.matches(record.messageText(), record.uploads.map { it.localId }) == true
    }
    internal val submissionHint get() = when {
        retrySubmission && receipts[draftKey]?.state == "missing" -> "服务器未收到，可手动重试"
        retrySubmission && receipts[draftKey]?.state == "failed" -> "任务启动失败，可手动重试"
        retrySubmission && receipts[draftKey]?.state == "starting" -> "服务器正在受理"
        retrySubmission && receipts[draftKey]?.state == "unknown" -> "受理结果未明，请核对会话"
        retrySubmission -> "发送结果待确认"
        drafts[draftKey]?.attempt != null -> "上一条发送待确认"
        currentId != null && currentId == receivedThread -> "服务器已接收"
        else -> null
    }
    var draft: String
        get() = drafts[draftKey]?.text.orEmpty()
        set(value) { setDraftText(draftKey, value) }

    init {
        if (prefs.contains("world-bank-country-catalog") || prefs.contains("world-bank-trade-observation")) {
            prefs.edit().remove("world-bank-country-catalog").remove("world-bank-trade-observation").apply()
        }
        val unused = draftStore.unusedFiles(drafts.values)
        if (unused.isNotEmpty()) viewModelScope.launch(Dispatchers.IO) { unused.forEach { it.delete() } }
    }

    private fun draftRecord(key: String) = drafts[key] ?: DraftRecord()

    private fun setDraftText(key: String, text: String) {
        val record = draftRecord(key)
        if (record.text == text) return
        if (receivedThread == key) receivedThread = null
        saveDraft(key, record.copy(text = text))
    }

    private fun saveDraft(key: String, record: DraftRecord) = saveDrafts(mapOf(key to record))

    private fun saveDrafts(changes: Map<String, DraftRecord>) {
        val previous = changes.keys.flatMap { draftRecord(it).uploads }.map { it.file }
        val records = changes.mapValues { (key, record) ->
            if (record.isEmpty) DraftRecord() else record.copy(updatedAt = System.currentTimeMillis(),
                projectId = draftProjectId(key, record), title = draftTitle(key, record),
                projectName = draftProjectId(key, record)?.let { draftProjectName(it, record) })
        }
        for ((key, record) in records) {
            if (record.isEmpty) drafts.remove(key) else drafts[key] = record
        }
        draftStore.save(records, receivedThread)
        if (previous.isEmpty()) return
        val retained = changes.values.flatMap { it.uploads }.mapTo(hashSetOf()) { it.file }
        val removed = previous.filterNot { it in retained }
        if (removed.isNotEmpty()) viewModelScope.launch(Dispatchers.IO) {
            if (draftStore.flush()) removed.forEach { it.delete() }
        }
    }

    internal fun reportAttachmentError(key: String, message: String?) {
        if (message == null) attachmentErrors.remove(key) else attachmentErrors[key] = message
    }

    internal fun removeDraftImage(image: DraftImage) {
        if (busy || importingAttachment) return
        saveDraft(draftKey, draftRecord(draftKey).copy(images = draftImages - image))
        reportAttachmentError(draftKey, null)
    }

    internal fun importImages(key: String, uris: List<Uri>, photo: File? = null) {
        if (uris.isEmpty() || importingKey != null || busy) {
            photo?.delete()
            return
        }
        val remaining = MAX_MESSAGE_IMAGES - draftRecord(key).uploads.size
        val selected = uris.distinct()
        importAttachment(key, onComplete = { photo?.delete() }) {
            if (remaining <= 0) {
                reportAttachmentError(key, "每条消息最多附 4 张图片")
                return@importAttachment
            }
            val resolver = getApplication<Application>().contentResolver
            var unreadable = 0
            for (uri in selected.take(remaining)) {
                try {
                    val image = withContext(Dispatchers.IO) {
                        resolver.readDraftImage(uri, photo?.let { "拍摄的照片" }, draftStore)
                    }
                    val record = draftRecord(key)
                    if (receivedThread == key) receivedThread = null
                    saveDraft(key, record.copy(images = record.images + image))
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    unreadable++
                }
            }
            val notices = listOfNotNull(
                "每条消息最多附 4 张图片".takeIf { selected.size > remaining },
                "有 ${unreadable} 张图片无法读取".takeIf { unreadable > 0 }
            )
            reportAttachmentError(key, notices.joinToString("。").ifEmpty { null })
        }
    }

    internal fun importText(key: String, uri: Uri) {
        if (importingKey != null || busy) return
        importAttachment(key) {
            val resolver = getApplication<Application>().contentResolver
            val text = withContext(Dispatchers.IO) { resolver.readDraftText(uri) }
            require(appendDraft(key, text)) { "导入后超过 12000 字的草稿上限" }
        }
    }

    private fun importAttachment(key: String, onComplete: () -> Unit = {}, block: suspend () -> Unit) {
        importingKey = key
        reportAttachmentError(key, null)
        viewModelScope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: IllegalArgumentException) {
                reportAttachmentError(key, e.message ?: "无法读取附件")
            } catch (_: Exception) {
                reportAttachmentError(key, "无法读取附件")
            } finally {
                importingKey = null
                onComplete()
            }
        }
    }

    internal fun startDictation(key: String) {
        if (!foreground || tab != WorkbenchPage.Sessions || projectSection != "对话" || key != draftKey || busy || importingAttachment) return
        dictation.start(key) { appendDraft(key, it) }
    }

    private fun appendDraft(key: String, text: String): Boolean {
        val existing = draftRecord(key).text
        val combined = if (existing.isBlank()) text else "$existing\n$text"
        if (combined.length > MAX_DRAFT_LENGTH) return false
        setDraftText(key, combined)
        return true
    }
    val configured get() = credentials.configured
    val activeProjects get() = projects.filterNot { it.optBoolean("archived") }
        .sortedByDescending { it.optString("id") == defaultWorkspaceId }
    val selectedConversationArchived get() = currentId != null &&
        projects.any { it.optString("id") == threadProjectId(current) && it.optBoolean("archived") }
    internal val canSendInConversation get() = if (currentId == null) {
        projects.isEmpty() || activeProjects.any { it.optString("id") == projectId }
    } else current != null && !selectedConversationArchived && !draftThreadMissing
    internal val isCloudAssistant get() = projectId == "cloud-assistant" &&
        (currentId == null || current?.let(::threadProjectId) == "cloud-assistant")
    internal val canStartVoiceChat get() = isCloudAssistant && connected && hostOnline && !connecting && !reconnecting &&
        canSendInConversation && !busy && activeThread == null && dictation.stage == null && !hasDraft && !importingAttachment
    val canCleanEmptyThreads get() = projectId in projectsWithEmptyThreads
    val activeThread get() = current?.takeIf { it.optString("status") in activeTaskStates }
        ?: threads.firstOrNull { it.optString("id") != currentId && it.optString("status") in activeTaskStates }
    val pendingApprovals get() = approvals + otherApprovals.filter { it.optString("threadId") != currentId }
    internal val latestResults get() = recentResults.map { thread ->
        Continuation(thread.getString("id"), threadTitle(thread), threadProjectId(thread),
            when (thread.optString("status")) {
                "failed" -> "执行失败"
                "interrupted" -> "执行中断"
                else -> "已完成"
            }, updated = thread.optLong("updated"))
    }
    internal val continuations: List<Continuation>
        get() {
            val summaries = (threads + listOfNotNull(current)).associateBy { it.getString("id") }
            val items = mutableListOf<Continuation>()
            fun add(id: String, state: String, attempt: MessageAttempt? = null, approvalId: String? = null) {
                if (id.isBlank() || approvalId == null && items.any { it.id == id }) return
                val thread = summaries[id]
                val title = thread?.let(::threadTitle) ?: attempt?.title ?: "会话"
                val project = thread?.let(::threadProjectId) ?: attempt?.projectId.orEmpty()
                items += Continuation(id, title, project, state, approvalId)
            }
            pendingApprovals.distinctBy { it.getString("id") }.forEach {
                add(it.getString("threadId"), if (it.optString("method").endsWith("requestUserInput")) "待回答" else "待审批",
                    approvalId = it.getString("id"))
            }
            summaries.values.filter { it.optString("status") in activeTaskStates }.forEach {
                add(it.getString("id"), if (it.optString("status") == "approval") "待审批" else "执行中")
            }
            drafts.forEach { (id, record) -> record.attempt?.let {
                val state = when (receipts[id]?.state) {
                    "missing", "failed" -> "可重试"
                    "starting" -> "受理中"
                    else -> "发送待确认"
                }
                add(id, state, it)
            } }
            if (recentResults.isEmpty()) current?.takeIf { it.optString("status") in setOf("failed", "interrupted") }?.let {
                add(it.getString("id"), if (it.optString("status") == "failed") "执行失败" else "执行中断")
            }
            return items
        }

    val running get() = current?.optString("status") in activeTaskStates
    fun clearError() { error = null }
    fun foreground(value: Boolean) {
        if (foreground == value) return
        foreground = value
        if (!value) {
            dictation.stop()
            backgroundPause = viewModelScope.launch {
                delay(BACKGROUND_SYNC_GRACE_MS)
                stopMonitoring()
            }
        } else {
            backgroundPause?.cancel()
            backgroundPause = null
            if (!busy && !connecting && (routeChanged || connected && !connection.connected)) {
                stopMonitoring()
                if (connected) resetTransport()
            }
            if (connectionMonitor?.isActive != true) {
                lastOverviewCheck = null
                lastProjects = 0L
            }
            monitorConnection()
        }
    }

    internal fun defaultNetworkChanged(handle: Long?) {
        if (observedNetwork && defaultNetwork == handle) return
        routeChanged = routeChanged || observedNetwork || handle == null
        observedNetwork = true
        defaultNetwork = handle
        if (routeChanged && foreground && reconnectAutomatically && connectionBlock == null && configured) {
            hostOnline = false
            overviewFresh = false
            if (!busy && !connecting) {
                stopMonitoring()
                monitorConnection()
            }
        }
    }

    fun connect(privateKey: String? = null, onSuccess: () -> Unit = {}) {
        if (connecting || busy) return
        if (networkUnavailable) {
            error = "请先连接网络，再连接服务器"
            return
        }
        val updated = privateKey?.let(::Credentials) ?: credentials
        if (!updated.configured) {
            error = "请先导入已授权的工作台 SSH 私钥"
            return
        }
        stopMonitoring()
        viewModelScope.launch {
            connecting = true
            error = null
            var resumeMonitoring = true
            try {
                routeChanged = false
                establishConnection(updated)
                connectionBlock = null
                reconnectAutomatically = true
                prefs.edit().putBoolean("reconnectAutomatically", true).apply()
                onSuccess()
            } catch (e: CancellationException) {
                resumeMonitoring = false
                throw e
            } catch (e: Exception) {
                error = friendly(e)
                val block = sshConnectionBlock(e)
                if (!hostOnline && updated == credentials && block != null) {
                    resetTransport()
                    connectionBlock = block
                    resumeMonitoring = false
                }
            } finally {
                connecting = false
                if (resumeMonitoring) monitorConnection()
            }
        }
    }

    private suspend fun establishConnection(updated: Credentials) {
        val candidate = newConnection()
        var installed = false
        try {
            candidate.connect(updated)
            if (persistBinding) withContext(Dispatchers.IO) { vault.saveVerified(updated) }
            connection.disconnect()
            connection = candidate
            transfers = candidate.transfers
            installed = true
            credentials = updated
            connected = true
            hostOnline = false
            overviewFresh = false
            lastOverviewCheck = null
            lastProjects = 0L
        } finally {
            if (!installed) candidate.disconnect()
        }
    }

    fun disconnect() {
        if (connecting || busy) return
        reconnectAutomatically = false
        prefs.edit().putBoolean("reconnectAutomatically", false).apply()
        stopMonitoring()
        resetTransport()
        error = null
    }

    private fun stopMonitoring() {
        connectionMonitor?.cancel()
        connectionMonitor = null
        reconnecting = false
    }

    private fun resetTransport() {
        manualOverview?.cancel()
        connection.disconnect()
        connected = false
        hostOnline = false
        overviewFresh = false
    }

    private fun sshConnectionBlock(e: Exception): SshConnectionBlock? {
        if (e !is com.jcraft.jsch.JSchException) return null
        val message = e.message.orEmpty()
        return when {
            message.contains("HostKey", true) || message.contains("host key", true) -> SshConnectionBlock.HOST_IDENTITY
            message.contains("Auth fail", true) -> SshConnectionBlock.CREDENTIALS
            else -> null
        }
    }

    private fun friendly(e: Exception): String {
        return sshConnectionBlock(e)?.message ?: (e.message ?: "连接失败").take(240)
    }
    private fun monitorConnection() {
        if (!foreground || busy || connecting || connectionMonitor?.isActive == true ||
            !reconnectAutomatically || connectionBlock != null || (!connected && !configured)) return
        if (routeChanged) {
            routeChanged = false
            if (connected) resetTransport()
        }
        if (networkUnavailable) {
            reconnecting = true
            return
        }
        connectionMonitor = viewModelScope.launch {
            var failures = 0
            var retryDelay = 1000L
            while (foreground && reconnectAutomatically) {
                try {
                    if (!connected) {
                        reconnecting = true
                        establishConnection(credentials)
                    }
                    val transport = connection
                    coroutineScope {
                        val syncLock = Mutex()
                        var syncPending = false
                        val syncFailure = "SSH 已连接，会话数据暂不可用，正在重试"
                        suspend fun synchronize(notice: ChangeNotice) = syncLock.withLock {
                            try {
                                val request = if (syncPending) ChangeNotice(catchUp = true) else notice
                                refresh(request)
                                syncPending = false
                                if (error == syncFailure) error = null
                            } catch (e: Exception) {
                                if (e !is JSONException && e !is GatewayPayloadException &&
                                    (e !is GatewayException || e.status == 401)
                                ) throw e
                                syncPending = true
                                hostOnline = false
                                error = syncFailure
                            }
                        }
                        val overview = launch {
                            var lastStatus = SystemClock.elapsedRealtime()
                            while (isActive) {
                                delay(5_000)
                                if (syncPending) {
                                    synchronize(ChangeNotice(catchUp = true))
                                } else if (tab == WorkbenchPage.Cloud && hostOnline &&
                                    SystemClock.elapsedRealtime() - lastStatus >= 60_000
                                ) {
                                    lastStatus = SystemClock.elapsedRealtime()
                                    synchronize(ChangeNotice(overview = true))
                                }
                            }
                        }
                        val metrics = launch {
                            while (isActive) {
                                val now = SystemClock.elapsedRealtime()
                                val last = lastOverviewCheck
                                val interval = if (overviewUnavailable) 5_000L else 30_000L
                                if ((tab == WorkbenchPage.Cloud || serverOverview == null) && (last == null || now - last >= interval)) {
                                    refreshOverview(transport)
                                }
                                delay(5_000L)
                            }
                        }
                        try {
                            transport.changes().collect { notice ->
                                synchronize(notice)
                                reconnecting = false
                                failures = 0
                                retryDelay = 1000L
                            }
                            throw IOException("事件连接已断开")
                        } finally {
                            overview.cancel()
                            metrics.cancel()
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    if (e is GatewayException && e.status == 401 && connected) {
                        // The gateway may have restarted. Open and verify a new SSH session.
                        resetTransport()
                        reconnecting = true
                        delay(retryDelay)
                        retryDelay = (retryDelay * 2).coerceAtMost(15000L)
                        continue
                    }
                    val block = sshConnectionBlock(e)
                    if (block != null) {
                        resetTransport()
                        connectionBlock = block
                        reconnecting = false
                        error = friendly(e)
                        break
                    }
                    if (connected && (e is GatewayException || e is JSONException)) {
                        hostOnline = false
                        reconnecting = false
                        error = "SSH 已连接，会话数据暂不可用，正在重试"
                        delay(retryDelay)
                        retryDelay = (retryDelay * 2).coerceAtMost(15000L)
                        continue
                    }
                    failures++
                    hostOnline = false
                    reconnecting = true
                    if (connected && (!connection.connected || failures >= 2)) resetTransport()
                    delay(retryDelay)
                    retryDelay = (retryDelay * 2).coerceAtMost(15000L)
                }
            }
        }
    }
    internal fun refreshOverviewNow() {
        if (!canRefreshOverview) return
        val transport = connection
        manualOverview = viewModelScope.launch { refreshOverview(transport, force = true) }
    }

    private suspend fun refreshOverview(transport: WorkbenchConnection, force: Boolean = false) {
        if (overviewRefreshing) return
        overviewRefreshing = true
        try {
            val path = if (force) "/v1/status?refresh=1" else "/v1/status"
            val overview = ServerOverview.parse(transport.sync(path))
            if (transport === connection && connected) {
                serverOverview = overview
                overviewUnavailable = false
                overviewFresh = true
                lastOverviewCheck = SystemClock.elapsedRealtime()
                prefs.edit().putString(OVERVIEW_CACHE, overview.metrics.toString()).apply()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            if (transport === connection && connected) {
                overviewUnavailable = true
                lastOverviewCheck = SystemClock.elapsedRealtime()
            }
        } finally {
            overviewRefreshing = false
        }
    }

    private suspend fun refresh(notice: ChangeNotice) {
        val selectedId = currentId
        val now = SystemClock.elapsedRealtime()
        if (lastProjects == 0L || now - lastProjects >= PROJECTS_INTERVAL_MS) {
            val directory = connection.sync("/v1/projects")
            val availableProjects = directory.getJSONArray("projects").objects()
            if (busy || selectedId != currentId) return
            projects = availableProjects
            lastProjects = now
            defaultWorkspaceId = directory.optString("defaultProjectId").takeIf { advertised ->
                availableProjects.any { it.optString("id") == advertised && !it.optBoolean("archived") }
            }
            if ((!workspaceInitialized && selectedId == null && defaultWorkspaceId != null) ||
                (activeProjects.none { it.optString("id") == projectId } && drafts["new:$projectId"]?.messageText() == null)) {
                preferredDraftWorkspace()?.let(::setProject)
            }
            workspaceInitialized = true
        }
        if (notice.overview || notice.catchUp) {
            val summary = connection.sync("/v1/threads")
            val recent = summary.getJSONArray("threads").objects()
            val emptyProjects = summary.getJSONArray("projectsWithEmptyThreads")
            val pending = summary.getJSONArray("approvals").objects()
                .filter { it.optString("threadId") != selectedId }
            if (busy || selectedId != currentId) return
            threads = recent
            recentResults = summary.optJSONArray("recentResults")?.objects().orEmpty()
            projectsWithEmptyThreads = (0 until emptyProjects.length()).mapTo(mutableSetOf(), emptyProjects::getString)
            otherApprovals = pending
        }
        if (selectedId != null && (notice.catchUp || notice.overview || selectedId in notice.threads)) {
            var hasMore: Boolean
            do {
                val update = try {
                    readUpdates(selectedId, updateCursor)
                } catch (e: GatewayException) {
                    if (e.status != 404) throw e
                    if (!busy && selectedId == currentId) {
                        if (drafts[selectedId]?.messageText() != null) draftThreadMissing = true else openDraft()
                        hostOnline = true
                    }
                    return
                }
                if (busy || selectedId != currentId) return
                applyThreadProject(update.thread)
                applyUpdates(update)
                hasMore = update.hasMore
            } while (hasMore)
        }
        hostOnline = true
    }

    private suspend fun readUpdates(id: String, cursor: String? = null, foregroundRequest: Boolean = false): ConversationUpdates {
        // A larger bootstrap window makes the uncertain send receipt visible after a restart.
        val attempt = drafts[id]?.attempt
        val limit = if (attempt != null) 160 else 40
        suspend fun load(from: String?): ConversationUpdates {
            val path = updatesPath(id, from, limit, attempt?.requestId)
            return ConversationUpdates(if (foregroundRequest) request(path) else connection.sync(path))
        }
        return try {
            load(cursor)
        } catch (e: GatewayException) {
            if (e.status != 400 || cursor == null) throw e
            load(null)
        }
    }

    private fun applyUpdates(update: ConversationUpdates) {
        updateCursor = update.cursor
        if (update.reset) historyBefore = update.historyBefore
        val window = if (update.reset) update.messages else mergeMessages(messages, update.messages)
        applyConversation(update.thread, window, update.approvals, update.receipt)
    }
    fun selectProject(id: String) {
        if (busy || activeProjects.none { it.optString("id") == id } ||
            (projectId == id && !selectedConversationArchived)) return
        setProject(id)
        preferredWorkspace = id
        prefs.edit().putString("preferred-workspace", id).apply()
        openDraft()
    }
    private fun preferredDraftWorkspace(): String? =
        preferredWorkspace?.takeIf { id -> activeProjects.any { it.optString("id") == id } }
            ?: defaultWorkspaceId ?: activeProjects.firstOrNull()?.optString("id")

    private fun setProject(id: String) {
        projectId = id
        draftModel = prefs.getString("draft-model:$id", null)
        prefs.edit().putString("project", id).apply()
    }

    internal fun loadConversationModels() {
        if (!canChooseConversationModel || modelsLoading) return
        val transport = connection
        viewModelScope.launch {
            modelsLoading = true
            modelError = null
            try {
                val options = transport.sync("/v1/models").getJSONArray("models").objects().map {
                    ConversationModel(it.getString("id"), it.getString("name"), it.optBoolean("default"))
                }
                if (transport === connection) models = options
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                if (transport === connection) modelError = "暂时无法读取模型，请重试"
            } finally {
                modelsLoading = false
            }
        }
    }

    internal fun chooseConversationModel(model: ConversationModel) {
        if (!canChooseConversationModel || model !in models || model.id == conversationModel) return
        val id = currentId
        if (id == null) {
            draftModel = model.id
            prefs.edit().putString("draft-model:$projectId", model.id).apply()
        } else action {
            val changed = request("/v1/threads/$id/model", JSONObject().put("model", model.id)).getJSONObject("thread")
            check(changed.getString("id") == id)
            if (currentId == id) current = changed
            threads = threads.map { if (it.optString("id") == id) changed else it }
        }
    }
    fun projectName(id: String): String =
        projects.find { it.optString("id") == id }?.optString("name")
            ?: id.takeUnless { it.isBlank() || it == "null" }
            ?: projects.firstOrNull()?.optString("name")
            ?: "默认项目"

    private fun draftThread(key: String) = current?.takeIf { it.optString("id") == key }
        ?: threads.find { it.optString("id") == key } ?: historyThreads.find { it.optString("id") == key }

    private fun draftProjectId(key: String, record: DraftRecord): String? =
        key.takeIf { it.startsWith("new:") }?.removePrefix("new:")
            ?: draftThread(key)?.let(::threadProjectId)?.takeIf { it.isNotBlank() }
            ?: record.projectId ?: record.attempt?.projectId ?: projectId.takeIf { key == currentId }

    private fun draftProjectName(id: String, record: DraftRecord): String =
        projects.find { it.optString("id") == id }?.optString("name")?.takeIf { it.isNotBlank() }
            ?: record.projectName ?: projectName(id)

    private fun draftTitle(key: String, record: DraftRecord): String = when {
        key.startsWith("new:") -> "新会话草稿"
        else -> draftThread(key)?.let(::threadTitle) ?: record.title ?: record.attempt?.title ?: "会话草稿"
    }

    internal val savedDrafts: List<SavedDraft> get() = drafts.entries.asSequence()
        .filter { it.value.messageText() != null }.map { (key, record) ->
            val id = draftProjectId(key, record)
            SavedDraft(key, draftTitle(key, record), id, id?.let { draftProjectName(it, record) }.orEmpty(),
                record.text.trim().lineSequence().first().take(100), record.uploads.size, record.updatedAt)
        }.sortedWith(compareByDescending<SavedDraft> { it.updatedAt }.thenBy { it.key }).toList()

    internal fun openSavedDraft(item: SavedDraft) {
        if (busy || drafts[item.key]?.messageText() == null) return
        if (item.key.startsWith("new:")) {
            item.projectId?.let(::setProject)
            openDraft()
            openProject()
        } else if (hostOnline && connected && !connecting) {
            select(item.key, openLocalIfMissing = true)
        } else {
            openLocalThreadDraft(item.key)
        }
    }

    private fun openLocalThreadDraft(id: String, missing: Boolean = false) {
        val project = draftProjectId(id, draftRecord(id))
        selectThreadId(id)
        project?.let(::setProject)
        draftThreadMissing = missing
        openProject()
    }
    fun threadProjectId(thread: JSONObject?): String =
        thread?.optString("projectId").orEmpty().takeUnless { it.isBlank() || it == "null" }
            ?: projects.firstOrNull()?.optString("id").orEmpty()
    val conversationTitle: String
        get() {
            val id = currentId ?: return "新对话"
            val thread = current ?: threads.firstOrNull { it.optString("id") == id }
            return thread?.let(::threadTitle) ?: drafts[id]?.let { draftTitle(id, it) } ?: "历史会话"
        }
    internal fun threadTitle(thread: JSONObject): String {
        val title = thread.optString("title").trim()
        if (title.isNotEmpty() && title !in setOf("新的对话", "新对话", "null")) return title
        if (thread.optString("id") == current?.optString("id")) {
            val first = messages.firstOrNull { it.optString("role") == "user" }?.optString("text").orEmpty()
            if (first.isNotBlank()) return first.trim().replace(Regex("\\s+"), " ").take(36)
        }
        return "未命名会话"
    }
    fun openProject(section: String = "对话") {
        tab = WorkbenchPage.Sessions
        projectSection = section
        focusedApprovalId = null
    }

    internal fun openContinuation(item: Continuation) {
        select(item.id, onSuccess = {
            val approvalId = item.approvalId
            if (approvalId != null) {
                openProject("任务")
                focusedApprovalId = approvalId
            }
        }, refresh = item.approvalId != null)
    }
    private suspend fun request(path: String, body: JSONObject? = null) = connection.request(path, body)
    suspend fun browserImage(url: String, cookie: String, agent: String): ByteArray {
        check(browserReady) { browserStatus }
        return downloadDirectBrowserImage(url, cookie, agent)
    }
    fun sendBrowser(text: String, title: String, url: String, material: String, image: ByteArray? = null) {
        if (busy || connecting || !connected || !hostOnline || activeThread != null) return
        action {
            val created = request("/v1/threads", JSONObject().put("title", "浏览：" + title.take(30)).put("projectId", projectId))
            val id = selectSnapshot(created)
            val browserImage = image?.let { withContext(Dispatchers.IO) { draftStore.stageImage("image/png", it) } }
            val browser = BrowserSubmission(
                JSONObject().put("title", title).put("url", url).put("text", material.take(30000)), browserImage
            )
            saveDraft(id, DraftRecord(text = text, browser = browser))
            openProject()
            submitMessage(id, text, text)
        }
    }
    private fun snapshot(j: JSONObject) {
        applyConversation(j.getJSONObject("thread"), mergeMessages(messages, j.getJSONArray("messages").objects()),
            j.getJSONArray("approvals").objects(), SubmissionReceipt.parse(j))
    }

    private fun applyConversation(thread: JSONObject, window: List<JSONObject>, pending: List<JSONObject>, receipt: SubmissionReceipt?) {
        draftThreadMissing = false
        current = thread
        messages = window
        approvals = pending
        val id = thread.getString("id")
        threads = (listOf(thread) + threads.filter { it.optString("id") != id }).sortedByDescending { it.optLong("updated") }
        val attempt = drafts[id]?.attempt ?: return
        if (receipt != null) {
            if (receipt.requestId != attempt.requestId) return
            if (receipt.state == "accepted") completeSubmission(id, attempt)
            else {
                receipts[id] = receipt
                if (receipt.state in setOf("missing", "failed") && error == SUBMISSION_UNCONFIRMED) error = null
            }
            return
        }
        // Older gateways sent user messages as the only evidence of receipt.
        val threadStatus = thread.optString("status")
        if (threadStatus != "failed" && threadStatus != "starting" &&
            window.any { it.optString("id") == "u-${attempt.requestId}" }) {
            completeSubmission(id, attempt)
        }
    }
    private fun action(onFailure: (String) -> Unit = { error = it }, block: suspend () -> Unit) {
        if (busy || connecting || !connected || !hostOnline) return
        stopMonitoring()
        viewModelScope.launch {
            busy = true; error = null
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (e is IOException || e is GatewayException && (e.status >= 500 || e.status == 401)) {
                    hostOnline = false
                }
                onFailure(friendly(e))
            } finally {
                busy = false
                monitorConnection()
            }
        }
    }
    fun select(id: String, onSuccess: () -> Unit = {}, onFailure: (String) -> Unit = { error = it },
        refresh: Boolean = false, openLocalIfMissing: Boolean = false) {
        if (!refresh && id == currentId && current != null) {
            openProject()
            onSuccess()
            return
        }
        action(onFailure) {
            loadingConversation = id
            try {
                val update = readUpdates(id, foregroundRequest = true)
                selectConversation(update.thread)
                applyUpdates(update)
                openProject()
                onSuccess()
            } catch (e: GatewayException) {
                if (!openLocalIfMissing || e.status != 404) throw e
                openLocalThreadDraft(id, missing = true)
            } finally {
                loadingConversation = null
            }
        }
    }
    internal fun loadConversations(filter: HistoryFilter, firstPage: Boolean = true) {
        val before = if (firstPage) null else {
            if (historyLoading || historyFilter != filter) return
            historyThreadsBefore ?: return
        }
        historyLoad?.cancel()
        historyFilter = filter
        historyLoadError = null
        if (firstPage) {
            historyThreads = emptyList()
            historyThreadsBefore = null
        }
        historyLoading = hostOnline
        if (!hostOnline) return
        historyLoad = viewModelScope.launch {
            try {
                if (firstPage && filter.query.isNotEmpty()) delay(250L)
                suspend fun load(cursor: String?): ConversationPage {
                    val response = request(threadPagePath(cursor, filter))
                    check(filter == HistoryFilter() || response.optBoolean("searchSupported")) { "服务器暂不支持历史会话筛选" }
                    return ConversationPage.parse(response, "threads")
                }
                var replace = firstPage
                val page = try {
                    load(before)
                } catch (e: GatewayException) {
                    if (e.status != 400 || before == null) throw e
                    replace = true
                    load(null)
                }
                val previous = if (replace) emptyList() else historyThreads
                historyThreads = (previous + page.entries).distinctBy { it.getString("id") }
                historyThreadsBefore = page.nextBefore
                historyLoading = false
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                historyLoadError = friendly(e)
                historyLoading = false
            }
        }
    }

    internal fun cancelHistoryLoad() {
        historyLoad?.cancel()
        historyLoading = false
    }

    fun loadOlderMessages() {
        val id = currentId ?: return
        val before = historyBefore ?: return
        action {
            val page = try {
                ConversationPage.parse(request(historyPagePath(id, before)), "messages")
            } catch (e: GatewayException) {
                if (e.status != 400 && e.status != 404) throw e
                updateCursor = null
                historyBefore = null
                throw e
            }
            messages = mergeMessages(page.entries, messages)
            historyBefore = page.nextBefore
        }
    }

    fun removeConversations(id: String?, onSuccess: (Int) -> Unit, onFailure: (String) -> Unit) {
        action(onFailure) {
            val result = if (id == null) request("/v1/threads/cleanup", JSONObject().put("projectId", projectId))
                else connection.request("/v1/threads/$id", delete = true)
            val deleted = result.getJSONArray("deleted")
            val ids = (0 until deleted.length()).mapTo(mutableSetOf()) { deleted.getString(it) }
            if (id == null) projectsWithEmptyThreads = projectsWithEmptyThreads - projectId
            if (currentId in ids) openDraft()
            threads = threads.filterNot { it.optString("id") in ids }
            historyThreads = historyThreads.filterNot { it.optString("id") in ids }
            recentResults = recentResults.filterNot { it.optString("id") in ids }
            otherApprovals = otherApprovals.filterNot { it.optString("threadId") in ids }
            if (receivedThread in ids) receivedThread = null
            ids.forEach { attachmentErrors.remove(it); receipts.remove(it) }
            saveDrafts(ids.associateWith { DraftRecord() })
            onSuccess(ids.size)
        }
    }
    private fun selectSnapshot(result: JSONObject): String {
        val id = selectConversation(result.getJSONObject("thread"))
        snapshot(result)
        return id
    }

    private fun selectConversation(thread: JSONObject): String {
        val id = thread.getString("id")
        selectThreadId(id)
        applyThreadProject(thread)
        return id
    }
    private fun selectThreadId(id: String) {
        if (currentId != id) {
            keepPendingApprovals()
            current = null
            messages = emptyList()
            approvals = emptyList()
            historyBefore = null
            updateCursor = null
            draftThreadMissing = false
        }
        currentId = id
        prefs.edit().putString("thread-cloud", id).apply()
    }
    private fun applyThreadProject(thread: JSONObject) {
        val id = threadProjectId(thread)
        if (id != projectId && activeProjects.any { it.optString("id") == id }) setProject(id)
    }
    fun newThread() {
        if (!busy) {
            if (currentId != null && defaultWorkspaceId != null) preferredDraftWorkspace()?.let(::setProject)
            openDraft()
            openProject()
        }
    }
    private fun openDraft() {
        keepPendingApprovals()
        draftThreadMissing = false
        currentId = null
        prefs.edit().putString("thread-cloud", null).remove("thread").apply()
        current = null
        messages = emptyList()
        historyBefore = null
        updateCursor = null
        approvals = emptyList()
    }
    private fun keepPendingApprovals() {
        otherApprovals = otherApprovals.filter { it.optString("threadId") != currentId } + approvals
    }
    fun send() {
        if (!canSendInConversation || activeThread != null || importingAttachment || dictation.stage != null) return
        val draftAtSend = draftRecord(draftKey)
        val text = draftAtSend.messageText() ?: return
        action { submitDraft(text, draftAtSend.text) }
    }

    internal fun sendVoiceTurn(text: String): Deferred<VoiceSubmission> {
        check(foreground && canStartVoiceChat) { "暂时无法发送语音，请查看连接和当前会话" }
        setDraftText(draftKey, text)
        val submission = CompletableDeferred<VoiceSubmission>()
        action(onFailure = {
            error = it
            submission.completeExceptionally(IOException(it))
        }) {
            submission.complete(submitDraft(text, text))
        }
        return submission
    }

    internal suspend fun awaitVoiceReply(submission: VoiceSubmission): String {
        return withTimeoutOrNull(180_000) {
            snapshotFlow {
                check(currentId == submission.threadId && isCloudAssistant) { "语音聊天已离开原会话" }
                check(connected && hostOnline) { "连接已断开，可在会话中查看回复" }
                val status = current?.optString("status")
                when {
                    status == "approval" || approvals.isNotEmpty() -> error("助理需要确认，请在任务页处理")
                    status in setOf("failed", "interrupted", "cancelled") -> error("本轮任务已结束，请查看会话状态")
                    status != "idle" -> null
                    else -> {
                        val sent = messages.indexOfFirst { it.optString("id") == "u-${submission.requestId}" }
                        if (sent < 0) null else {
                            val reply = messages.asSequence().drop(sent + 1).takeWhile { it.optString("role") != "user" }
                                .lastOrNull { it.optString("role") == "assistant" && it.optString("status") == "completed" }
                                ?.optString("text")?.takeIf(String::isNotBlank)
                            reply ?: error("本轮没有可朗读的文字回复")
                        }
                    }
                }
            }.filterNotNull().first()
        } ?: error("助理仍在处理，可回到会话查看进度")
    }

    private suspend fun submitDraft(text: String, draftAtSend: String): VoiceSubmission {
        val id = currentId ?: run {
            val key = draftKey
            val create = JSONObject().put("title", "新的对话").put("projectId", projectId)
            draftModel?.let { create.put("model", it) }
            val new = selectSnapshot(request("/v1/threads", create))
            draftModel = null
            prefs.edit().remove("draft-model:$projectId").apply()
            saveDrafts(mapOf(key to DraftRecord(), new to draftRecord(key)))
            attachmentErrors.remove(key)
            new
        }
        return VoiceSubmission(id, submitMessage(id, text, draftAtSend))
    }
    private suspend fun submitMessage(id: String, text: String, draftAtSend: String): String {
        val record = draftRecord(id)
        val images = record.uploads
        val localIds = images.map { it.localId }
        val requestId = record.attempt?.takeIf { it.matches(text, localIds) }?.requestId
            ?: UUID.randomUUID().toString()
        val attempt = MessageAttempt(requestId, text, localIds, draftAtSend, projectId, conversationTitle)
        val body = JSONObject().put("text", text).put("requestId", requestId)
        record.browser?.let { body.put("browserContext", it.context) }
        receivedThread = null
        receipts.remove(id)
        saveDraft(id, record.copy(attempt = attempt))
        ensureDraftSaved()
        if (images.isNotEmpty()) {
            val ids = JSONArray()
            for (image in images) ids.put(uploadImage(id, image))
            body.put("attachmentIds", ids)
            saveDraft(id, draftRecord(id))
            ensureDraftSaved()
        }
        val response = try {
            request("/v1/threads/$id/messages", body)
        } catch (e: IOException) {
            throw IOException(SUBMISSION_UNCONFIRMED, e)
        }
        snapshot(response)
        if (!response.has("receipt") && drafts[id]?.attempt == attempt) completeSubmission(id, attempt)
        return requestId
    }

    private suspend fun ensureDraftSaved() {
        check(withContext(Dispatchers.IO) { draftStore.flush() }) { "无法保存发送记录，请检查设备存储后重试" }
    }

    private fun completeSubmission(id: String, attempt: MessageAttempt) {
        val record = draftRecord(id)
        val text = record.text.takeUnless { it == attempt.draftAtSend }.orEmpty()
        val images = record.images.filterNot { it.upload.localId in attempt.imageIds }
        if (images.isEmpty()) attachmentErrors.remove(id)
        if (text.isEmpty() && images.isEmpty()) receivedThread = id
        receipts.remove(id)
        saveDraft(id, DraftRecord(text, images))
        if (error == SUBMISSION_UNCONFIRMED) error = null
    }
    private suspend fun uploadImage(id: String, image: MessageImage): String {
        image.uploadedId?.let { return it }
        val data = withContext(Dispatchers.IO) { Base64.encodeToString(image.readBytes(), Base64.NO_WRAP) }
        return request("/v1/attachments", JSONObject().put("threadId", id).put("mime", image.mime).put("data", data))
            .getString("id").also { image.uploadedId = it }
    }
    fun cancel(id: String) = action { applyActionSnapshot(request("/v1/threads/$id/cancel", JSONObject())) }
    fun answer(a: JSONObject, decision: String) = action {
        applyActionSnapshot(request("/v1/approvals/${a.getString("id")}", JSONObject().put("decision", decision)))
    }
    fun answers(a: JSONObject, answers: Map<String, String>) = action {
        applyActionSnapshot(request("/v1/approvals/${a.getString("id")}", JSONObject().put("answers", JSONObject(answers))))
    }
    private fun applyActionSnapshot(result: JSONObject) {
        val thread = result.getJSONObject("thread")
        val id = thread.getString("id")
        if (id == currentId) snapshot(result)
        else {
            threads = threads.map { if (it.optString("id") == id) thread else it }
            otherApprovals = otherApprovals.filter { it.optString("threadId") != id } + result.getJSONArray("approvals").objects()
        }
    }
    override fun onCleared() {
        connection.disconnect()
        super.onCleared()
    }

    private companion object {
        const val OVERVIEW_CACHE = "last-server-overview"
        const val BACKGROUND_SYNC_GRACE_MS = 30_000L
        const val PROJECTS_INTERVAL_MS = 30000L
        const val SUBMISSION_UNCONFIRMED = "发送结果待确认。连接恢复后会核对会话，如未收到可手动重试。"
    }
}
