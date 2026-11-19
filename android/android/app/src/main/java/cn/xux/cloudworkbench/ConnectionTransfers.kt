package cn.xux.cloudworkbench

import android.os.SystemClock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.ResponseBody
import okio.Buffer
import okio.ForwardingSource
import okio.buffer

internal enum class TransferKind(val title: String) {
    Status("读取服务器状态"),
    Projects("读取项目"),
    History("读取会话列表"),
    Conversation("读取会话"),
    Create("创建会话"),
    Send("发送消息"),
    Upload("上传附件"),
    Image("下载网页图片"),
    AppUpdateCheck("检查 App 更新"),
    AppUpdateDownload("下载安装包"),
    Approval("提交审批"),
    Stop("停止任务"),
    Delete("删除会话"),
    Cleanup("清理会话"),
    Other("服务器请求");

    companion object {
        fun gateway(path: String, write: Boolean, delete: Boolean): TransferKind = when {
            delete -> Delete
            path == "/v1/status" -> Status
            path == "/v1/app-update" -> AppUpdateCheck
            path == "/v1/projects" -> Projects
            path.substringBefore('?') == "/v1/threads" -> if (write) Create else History
            path == "/v1/threads/cleanup" -> Cleanup
            path == "/v1/attachments" -> Upload
            path.startsWith("/v1/approvals/") -> Approval
            path.endsWith("/messages") -> Send
            path.endsWith("/cancel") -> Stop
            path.startsWith("/v1/threads/") -> Conversation
            else -> Other
        }
    }
}

internal enum class TransferPhase { Uploading, Waiting, Receiving }

internal const val TRANSFER_BUCKET_MS = 2000L
private const val TRANSFER_BUCKETS = 30

internal data class TransferBucket(val sent: Long = 0, val received: Long = 0)

internal data class TransferEntry(
    val kind: TransferKind,
    val startedAt: Long,
    val phase: TransferPhase = TransferPhase.Waiting,
    val sent: Long = 0,
    val received: Long = 0,
    val uploadSize: Long = 0,
    val elapsedMs: Long = 0,
    val result: String? = null
)

internal data class TransferSummary(
    val sent: Long = 0,
    val received: Long = 0,
    val syncSent: Long = 0,
    val syncReceived: Long = 0,
    val syncCompleted: Int = 0,
    val syncFailed: Int = 0,
    val syncing: Int = 0,
    val operations: List<TransferEntry> = emptyList(),
    val traffic: List<TransferBucket> = emptyList()
)

/** In-memory HTTP body statistics owned by one connection. */
internal class ConnectionTransfers {
    private val active = linkedSetOf<Operation>()
    private val recent = ArrayDeque<TransferEntry>()
    private val bucketTimes = LongArray(TRANSFER_BUCKETS) { -1 }
    private val bucketSent = LongArray(TRANSFER_BUCKETS)
    private val bucketReceived = LongArray(TRANSFER_BUCKETS)
    private var sent = 0L
    private var received = 0L
    private var syncSent = 0L
    private var syncReceived = 0L
    private var syncCompleted = 0
    private var syncFailed = 0
    private var lastUpdate = 0L
    private val _foreground = MutableStateFlow<TransferEntry?>(null)
    val foreground = _foreground.asStateFlow()

    suspend fun <T> track(kind: TransferKind, background: Boolean = false, block: suspend (Operation) -> T): T {
        val operation = Operation(kind, background)
        synchronized(this) {
            active.add(operation)
            publishForeground()
        }
        return try {
            block(operation).also { operation.finish("成功") }
        } catch (e: CancellationException) {
            operation.finish("已取消")
            throw e
        } catch (e: Exception) {
            operation.finish(if (e is GatewayException) "失败（HTTP ${e.status}）" else "失败")
            throw e
        }
    }

    @Synchronized fun snapshot(): TransferSummary {
        val now = SystemClock.elapsedRealtime()
        val currentBucket = now / TRANSFER_BUCKET_MS
        val traffic = List(TRANSFER_BUCKETS) { position ->
            val bucket = currentBucket - (TRANSFER_BUCKETS - 1 - position)
            val index = Math.floorMod(bucket, TRANSFER_BUCKETS.toLong()).toInt()
            if (bucket >= 0 && bucketTimes[index] == bucket) TransferBucket(bucketSent[index], bucketReceived[index])
            else TransferBucket()
        }
        return TransferSummary(sent, received, syncSent, syncReceived, syncCompleted, syncFailed,
            syncing = active.count { it.background },
            operations = active.filterNot { it.background }.map {
                it.entry.copy(elapsedMs = now - it.entry.startedAt)
            } + recent, traffic = traffic)
    }

    private fun publishForeground() {
        _foreground.value = active.lastOrNull { !it.background && it.entry.phase == TransferPhase.Uploading }
            ?.entry ?: active.lastOrNull { !it.background }?.entry
        lastUpdate = SystemClock.elapsedRealtime()
    }

    internal inner class Operation(kind: TransferKind, val background: Boolean) {
        var entry = TransferEntry(kind, SystemClock.elapsedRealtime())
            private set

        fun uploading(size: Long) = updatePhase(TransferPhase.Uploading, size)
        fun waiting() = updatePhase(TransferPhase.Waiting)
        fun receiving() = updatePhase(TransferPhase.Receiving)

        private fun updatePhase(phase: TransferPhase, size: Long? = null) = synchronized(this@ConnectionTransfers) {
            if (this !in active) return@synchronized
            entry = entry.copy(phase = phase, uploadSize = size ?: entry.uploadSize)
            if (!background) publishForeground()
        }

        fun sent(count: Long) = countBytes(count, upload = true)
        fun received(count: Long) = countBytes(count, upload = false)

        private fun countBytes(count: Long, upload: Boolean) = synchronized(this@ConnectionTransfers) {
            if (this !in active) return@synchronized
            val now = SystemClock.elapsedRealtime()
            val bucket = now / TRANSFER_BUCKET_MS
            val index = (bucket % TRANSFER_BUCKETS).toInt()
            if (bucketTimes[index] != bucket) {
                bucketTimes[index] = bucket
                bucketSent[index] = 0
                bucketReceived[index] = 0
            }
            if (upload) {
                bucketSent[index] += count
                sent += count
                if (background) syncSent += count
                entry = entry.copy(sent = entry.sent + count)
            } else {
                bucketReceived[index] += count
                received += count
                if (background) syncReceived += count
                entry = entry.copy(received = entry.received + count)
            }
            if (!background && now - lastUpdate >= 150) publishForeground()
        }

        fun finish(result: String) = synchronized(this@ConnectionTransfers) {
            if (!active.remove(this)) return@synchronized
            if (background) {
                if (result == "成功") syncCompleted++
                else if (result != "已取消") syncFailed++
            } else {
                recent.addFirst(entry.copy(result = result, elapsedMs = SystemClock.elapsedRealtime() - entry.startedAt))
                if (recent.size > 12) recent.removeLast()
                publishForeground()
            }
        }

        fun measure(body: ResponseBody): ResponseBody = object : ResponseBody() {
            private val measuredSource = object : ForwardingSource(body.source()) {
                override fun read(sink: Buffer, byteCount: Long): Long = super.read(sink, byteCount).also {
                    if (it > 0) received(it)
                }
            }.buffer()
            override fun contentType() = body.contentType()
            override fun contentLength() = body.contentLength()
            override fun source() = measuredSource
        }
    }
}
