package cn.xux.cloudworkbench

import android.content.Context
import android.net.Uri
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import com.jcraft.jsch.JSch
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Dispatcher
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okio.Buffer
import okio.BufferedSink
import okio.ForwardingSink
import okio.buffer
import org.json.JSONException
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.security.KeyStore
import java.util.concurrent.TimeUnit
import java.util.concurrent.Executors
import java.net.Proxy
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlin.coroutines.resumeWithException

const val DEFAULT_HOST = "43.110.34.152"
internal const val WORKBENCH_DOMAIN = "workbench.qiwuli.com"
// Public host key obtained over the previously verified SSH administration channel.
const val VERIFIED_KEY = "ecdsa-sha2-nistp256 AAAAE2VjZHNhLXNoYTItbmlzdHAyNTYAAAAIbmlzdHAyNTYAAABBBD62q1WVKyml7xI+oswLh3TrCBupjYjnVeJokSIRq8vDpipWWHv40PSiblIfOZM3p5bKwTrn35D3YxrkUQPqKeE="

internal data class Credentials(val privateKey: String = "") {
    val configured get() = privateKey.isNotBlank()
    override fun toString() = "Credentials(configured=$configured)"
}

private fun JSch.addWorkbenchIdentity(privateKey: String) {
    val bytes = privateKey.toByteArray(Charsets.UTF_8)
    try {
        addIdentity("cloud-workbench", bytes, null, null)
        require(identityRepository.identities.single().isEncrypted.not()) { "请选择不带口令的工作台专用私钥" }
    } finally {
        bytes.fill(0)
    }
}

fun validateWorkbenchKey(privateKey: String) {
    val jsch = JSch()
    try {
        jsch.addWorkbenchIdentity(privateKey)
    } finally {
        jsch.removeAllIdentity()
    }
}

internal suspend fun readWorkbenchPrivateKey(context: Context, uri: Uri): String = withContext(Dispatchers.IO) {
    val key = context.contentResolver.openInputStream(uri)?.bufferedReader(Charsets.UTF_8)?.use { reader ->
        val buffer = CharArray(16385)
        try {
            var count = 0
            while (count < buffer.size) {
                val read = reader.read(buffer, count, buffer.size - count)
                if (read < 0) break
                count += read
            }
            require(count in 1..16384)
            String(buffer, 0, count).trim()
        } finally {
            buffer.fill('\u0000')
        }
    } ?: error("无法打开私钥文件")
    validateWorkbenchKey(key)
    key
}

internal fun workbenchSsh(privateKey: String): Pair<JSch, com.jcraft.jsch.Session> {
    val jsch = JSch()
    jsch.setKnownHosts(ByteArrayInputStream("$DEFAULT_HOST $VERIFIED_KEY\n".toByteArray()))
    val session = jsch.getSession("xux", DEFAULT_HOST, 22)
    session.setConfig("StrictHostKeyChecking", "yes")
    session.setConfig("PreferredAuthentications", "publickey")
    session.setConfig("server_host_key", "ecdsa-sha2-nistp256")
    session.setConfig("kex", "ecdh-sha2-nistp256,diffie-hellman-group14-sha256")
    try {
        jsch.addWorkbenchIdentity(privateKey)
    } catch (error: Exception) {
        jsch.removeAllIdentity()
        throw error
    }
    return jsch to session
}

internal class EncryptedPreferences(private val context: Context, name: String, private val alias: String) {
    private val preferences = context.getSharedPreferences(name, Context.MODE_PRIVATE)

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    fun save(value: JSONObject) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding"); cipher.init(Cipher.ENCRYPT_MODE, key())
        val plain = value.toString().toByteArray()
        val encrypted = try { cipher.doFinal(plain) } finally { plain.fill(0) }
        val encoded = Base64.encodeToString(cipher.iv + encrypted, Base64.NO_WRAP)
        check(preferences.edit().putString("data", encoded).commit()) {
            "无法保存连接凭据"
        }
    }
    fun load(): JSONObject? {
        val encoded = preferences.getString("data", null) ?: return null
        val all = Base64.decode(encoded, Base64.NO_WRAP)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, all.copyOfRange(0, 12)))
        val plain = cipher.doFinal(all.copyOfRange(12, all.size))
        return try { JSONObject(plain.toString(Charsets.UTF_8)) } finally { plain.fill(0) }
    }
    fun clear(destroyKey: Boolean = false) {
        check(preferences.edit().clear().commit()) { "无法清除本机连接凭据" }
        if (destroyKey) KeyStore.getInstance("AndroidKeyStore").apply { load(null); deleteEntry(alias) }
    }
    fun hasValue(): Boolean = preferences.contains("data")
}

internal class Vault(private val context: Context) {
    private val storage = EncryptedPreferences(context, "vault", "cloud-workbench-connection-v1")
    // Read the Android SSH credential used by older installs until the new vault is verified.
    private val legacy = EncryptedPreferences(context, "windows-pairing-admin", "cloud-workbench-windows-pairing-admin-v1")

    fun saveVerified(c: Credentials) {
        val current = runCatching { storage.load() }.getOrNull()
        if (current?.length() != 1 || current.optString("privateKey") != c.privateKey) {
            storage.save(JSONObject().put("privateKey", c.privateKey))
        }
        if (legacy.hasValue()) runCatching { legacy.clear(destroyKey = true) }
    }

    fun load(): Credentials {
        val privateKey = runCatching { storage.load()?.optString("privateKey").orEmpty() }.getOrDefault("")
            .ifBlank { runCatching { legacy.load()?.optString("privateKey").orEmpty() }.getOrDefault("") }
        // USB provisioning is possible only for a debuggable build.
        val bootstrap = File(context.filesDir, "bootstrap.json")
        if (BuildConfig.DEBUG && bootstrap.isFile) {
            try {
                if (bootstrap.length() in 1..65536) {
                    val key = JSONObject(bootstrap.readText()).optString("privateKey").trim()
                    if (key.isNotEmpty()) {
                        validateWorkbenchKey(key)
                        return Credentials(key)
                    }
                }
            } catch (_: Exception) {
                Log.w("Connection", "Could not import USB SSH key")
            } finally {
                bootstrap.delete()
            }
        }
        return Credentials(privateKey)
    }
}

internal class GatewayException(val status: Int, message: String) : IllegalStateException(message)
internal class GatewayPayloadException(cause: JSONException) : IllegalStateException("网关响应不完整", cause)

internal suspend fun workbenchJsonCall(http: OkHttpClient, request: Request): JSONObject =
    suspendCancellableCoroutine { result ->
        val call = http.newCall(request)
        call.timeout().timeout(15, TimeUnit.SECONDS)
        result.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) = result.resumeWithException(e)
            override fun onResponse(call: Call, response: Response) {
                result.resumeWith(runCatching {
                    response.use {
                        if (!it.isSuccessful) throw GatewayException(it.code, "工作台连接失败（HTTP ${it.code}）")
                        JSONObject(it.body?.string().orEmpty())
                    }
                })
            }
        })
    }

internal data class ChangeNotice(val overview: Boolean = false, val threads: Set<String> = emptySet(), val catchUp: Boolean = false)

internal interface WorkbenchConnection {
    val connected: Boolean
    val transfers: ConnectionTransfers? get() = null
    fun disconnect()
    suspend fun connect(c: Credentials)
    fun changes(): Flow<ChangeNotice> = flow {
        emit(ChangeNotice(catchUp = true))
        while (true) {
            delay(2000)
            emit(ChangeNotice(catchUp = true))
        }
    }
    suspend fun request(path: String, body: JSONObject? = null, delete: Boolean = false): JSONObject
    suspend fun sync(path: String): JSONObject = request(path)
    suspend fun downloadUpdate(release: AppRelease, file: File, progress: (Long) -> Unit) {
        throw IOException("当前连接无法下载更新")
    }
}

internal class Connection(
    private val openGateway: suspend (String) -> MobileGatewaySession = ::openMobileSshGateway,
    private val configureClient: OkHttpClient.Builder.() -> Unit = {}
) : WorkbenchConnection {
    override val transfers = ConnectionTransfers()
    private companion object {
        // Independent of the ViewModel scope so onCleared cannot cancel cleanup.
        val cleanupExecutor = Executors.newSingleThreadExecutor { task ->
            Thread(task, "connection-cleanup").apply { isDaemon = true }
        }

    }

    private fun newClient(
                          activeGateway: () -> MobileGatewaySession? = { gateway }) = OkHttpClient.Builder()
            .proxy(Proxy.NO_PROXY)
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(65, TimeUnit.SECONDS)
            .retryOnConnectionFailure(false)
            .followRedirects(false)
            .followSslRedirects(false)
            .addInterceptor { chain ->
                val active = activeGateway() ?: throw IOException("SSH 连接已断开")
                val request = chain.request()
                if (!request.url.toString().startsWith(active.origin + "/")) throw IOException("SSH 转发已变更")
                val token = active.bearer()
                chain.proceed(request.newBuilder().header("Authorization", "Bearer $token").build()).also {
                    if (it.code == 401) active.invalidate(token)
                }
            }
            .apply(configureClient)
            .build()

    @Volatile private var gateway: MobileGatewaySession? = null
    private data class UpdateChannel(val session: MobileGatewaySession, val client: OkHttpClient)
    private val extraUpdateChannels = mutableSetOf<UpdateChannel>()
    private var client = newClient()
    private var readClient = client.newBuilder().retryOnConnectionFailure(true).build()
    private var eventClient = newClient()
    private var downloadClient = newDownloadClient()
    private fun newDownloadClient(activeGateway: () -> MobileGatewaySession? = { gateway }) =
        newClient(activeGateway = activeGateway).newBuilder()
        .protocols(listOf(Protocol.HTTP_1_1))
        // APK reads are idempotent. Reconnect a stale pooled socket before any body
        // is delivered; interrupted bodies still resume from their persisted offset.
        .retryOnConnectionFailure(true)
        .dispatcher(Dispatcher().apply { maxRequestsPerHost = UPDATE_STREAMS })
        .build()
    override val connected get() = gateway?.connected == true
    @Synchronized override fun disconnect() {
        val oldGateway = gateway
        val oldUpdateChannels = extraUpdateChannels.toList()
        extraUpdateChannels.clear()
        val oldClients = listOf(client, eventClient, downloadClient) + oldUpdateChannels.map { it.client }
        gateway = null
        client = newClient()
        readClient = client.newBuilder().retryOnConnectionFailure(true).build()
        eventClient = newClient()
        downloadClient = newDownloadClient()
        cleanupExecutor.execute {
            try {
                oldClients.forEach {
                    it.dispatcher.cancelAll()
                    it.connectionPool.evictAll()
                }
            } finally {
                oldUpdateChannels.forEach { it.session.close() }
                oldGateway?.close()
            }
        }
    }

    private suspend fun openUpdateChannels(original: MobileGatewaySession): List<UpdateChannel> {
        val first = synchronized(this@Connection) {
            if (gateway !== original) throw IOException("SSH 连接已变更")
            UpdateChannel(original, downloadClient)
        }
        // Multiple HTTP channels on one SSH socket share one congestion window.
        // A bounded, download-only pool uses independent TCP connections; credentials
        // and host verification remain the same as the established business session.
        val owned = mutableListOf<UpdateChannel>()
        try {
            return coroutineScope {
                val extra = List(UPDATE_STREAMS - 1) {
                    async(Dispatchers.IO) {
                        var opened: MobileGatewaySession? = null
                        try {
                            opened = original.forkForDownload() ?: return@async null
                            synchronized(this@Connection) {
                                if (gateway !== original) throw IOException("SSH 连接已变更")
                                val session = checkNotNull(opened)
                                val channel = UpdateChannel(session, newDownloadClient { session })
                                extraUpdateChannels.add(channel)
                                owned.add(channel)
                                opened = null // Ownership transferred to download cleanup/disconnect.
                                channel
                            }
                        } catch (error: CancellationException) { throw error }
                        catch (_: Exception) { null } // Extra-route failure must not discard progress.
                        finally { opened?.close() }
                    }
                }.awaitAll().filterNotNull()
                listOf(first) + extra
            }
        } catch (error: Throwable) {
            closeUpdateChannels(listOf(first) + owned)
            throw error
        }
    }

    private suspend fun closeUpdateChannels(channels: List<UpdateChannel>) = withContext(NonCancellable + Dispatchers.IO) {
        for (channel in channels.drop(1)) {
            synchronized(this) { extraUpdateChannels.remove(channel) }
            channel.client.dispatcher.cancelAll()
            channel.client.connectionPool.evictAll()
            channel.client.dispatcher.executorService.shutdown()
            channel.session.close()
        }
    }

    override suspend fun connect(c: Credentials) {
        require(c.configured) { "请导入已授权的工作台 SSH 私钥" }
        val opened = openGateway(c.privateKey)
        gateway = opened
        try {
            workbenchJsonCall(client, endpoint("/v1/status"))
        } catch (error: Throwable) {
            disconnect()
            throw error
        }
    }

    private fun endpoint(path: String): Request {
        val active = gateway ?: throw IOException("SSH 连接已断开")
        return Request.Builder().url(active.origin + path).build()
    }



    override fun changes(): Flow<ChangeNotice> = flow {
        val incoming = Channel<ChangeNotice>(64)
        val call = eventClient.newCall(endpoint("/v1/events"))
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) { incoming.close(e) }
            override fun onResponse(call: Call, response: Response) {
                runCatching {
                    response.use {
                        if (!it.isSuccessful) throw GatewayException(it.code, "事件连接失败（HTTP ${it.code}）")
                        val source = it.body?.source() ?: throw IOException("事件响应为空")
                        while (!call.isCanceled()) {
                            val json = JSONObject(source.readUtf8LineStrict(16 * 1024))
                            val notice = when (json.getString("type")) {
                                "ready" -> ChangeNotice(catchUp = true)
                                "change" -> {
                                    val threads = json.getJSONArray("threads")
                                    ChangeNotice(json.optBoolean("overview"), (0 until threads.length()).mapTo(mutableSetOf(), threads::getString))
                                }
                                else -> continue
                            }
                            if (!incoming.trySend(notice).isSuccess) throw IOException("事件处理积压，正在补齐")
                        }
                    }
                }.onFailure { incoming.close(it) }
                incoming.close()
            }
        })
        try {
            for (notice in incoming) emit(notice)
            throw IOException("事件连接已断开")
        } finally {
            call.cancel()
            incoming.cancel()
        }
    }
    @Synchronized private fun gatewayCall(
        path: String, body: JSONObject?, delete: Boolean, transfer: ConnectionTransfers.Operation,
        readCallTimeoutSeconds: Long = 8
    ): Call {
        val builder = endpoint(path).newBuilder()
        if (body != null || delete) {
            val payload = body?.toString().orEmpty().toRequestBody(body?.let { "application/json".toMediaType() })
            builder.method(if (delete) "DELETE" else "POST", object : RequestBody() {
                override fun contentType() = payload.contentType()
                override fun contentLength() = payload.contentLength()
                override fun writeTo(sink: BufferedSink) {
                    val attachment = path == "/v1/attachments"
                    if (attachment) transfer.uploading(contentLength())
                    val measured = object : ForwardingSink(sink) {
                        override fun write(source: Buffer, byteCount: Long) {
                            var remaining = byteCount
                            while (remaining > 0) {
                                val chunk = minOf(remaining, 16_384L)
                                super.write(source, chunk)
                                transfer.sent(chunk)
                                remaining -= chunk
                            }
                        }
                    }.buffer()
                    payload.writeTo(measured)
                    measured.emit()
                    if (attachment) sink.flush()
                    transfer.waiting()
                }
                // A write can reach the gateway even when its response is lost.
                override fun isOneShot() = true
            })
        }
        val boundedRead = body == null && !delete && readCallTimeoutSeconds > 0
        return (if (boundedRead) readClient else client).newCall(builder.build()).also {
            if (boundedRead) {
                it.timeout().timeout(readCallTimeoutSeconds, TimeUnit.SECONDS)
            }
        }
    }

    override suspend fun request(path: String, body: JSONObject?, delete: Boolean): JSONObject =
        gatewayRequest(path, body, delete, background = false)

    override suspend fun sync(path: String): JSONObject = gatewayRequest(path, null, false, background = true)

    private suspend fun gatewayRequest(path: String, body: JSONObject?, delete: Boolean, background: Boolean): JSONObject =
        transfers.track(TransferKind.gateway(path, body != null, delete), background) {
            gatewayResponse(path, body, delete, it)
        }

    private suspend fun gatewayResponse(
        path: String, body: JSONObject?, delete: Boolean, transfer: ConnectionTransfers.Operation
    ): JSONObject = withContext(Dispatchers.IO) {
        val call = gatewayCall(path, body, delete, transfer)
        suspendCancellableCoroutine { result ->
            result.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) = result.resumeWithException(e)

                override fun onResponse(call: Call, response: Response) {
                    result.resumeWith(runCatching {
                        response.use {
                            transfer.receiving()
                            val data = it.body?.let(transfer::measure)?.string().orEmpty()
                            val json = try {
                                JSONObject(data)
                            } catch (e: JSONException) {
                                if (it.isSuccessful) throw GatewayPayloadException(e)
                                JSONObject()
                            }
                            if (!it.isSuccessful) throw GatewayException(it.code, json.optString("error", "请求失败 ${it.code}"))
                            json
                        }
                    })
                }
            })
        }
    }
    override suspend fun downloadUpdate(release: AppRelease, file: File, progress: (Long) -> Unit) =
        transfers.track(TransferKind.AppUpdateDownload) { transfer ->
            val offset = file.length()
            require(offset in 0..release.size) { "安装包超出发布清单大小" }
            if (offset == release.size) return@track
            if (release.size >= PARALLEL_UPDATE_MIN_SIZE) {
                val original = gateway ?: throw IOException("SSH 连接已断开")
                val channels = openUpdateChannels(original)
                try {
                    downloadUpdateRanges(release, file, transfer, progress) { worker, first, last ->
                        val channel = channels[worker % channels.size]
                        channel.client.newCall(Request.Builder()
                            .url(channel.session.origin + "/v1/app-update/" + release.sha256 + "/apk")
                            .header("Accept-Encoding", "identity")
                            .header("Range", "bytes=$first-$last")
                            .header("If-Range", "\"" + release.sha256 + "\"")
                            .build())
                    }
                    return@track
                } catch (_: UpdateRangesUnavailable) {
                    withContext(Dispatchers.IO) { file.updateParts().deleteRecursively() }
                } finally {
                    closeUpdateChannels(channels)
                }
            }
            val call = synchronized(this) {
                val request = endpoint("/v1/app-update/${release.sha256}/apk").newBuilder()
                    .header("Accept-Encoding", "identity")
                if (offset > 0) {
                    request.header("Range", "bytes=$offset-").header("If-Range", "\"${release.sha256}\"")
                }
                downloadClient.newCall(request.build())
            }
            val finished = CompletableDeferred<Unit>()
            try {
            suspendCancellableCoroutine<Unit> { result ->
                result.invokeOnCancellation { call.cancel() }
                call.enqueue(object : Callback {
                    override fun onFailure(call: Call, e: IOException) {
                        finished.complete(Unit)
                        result.resumeWithException(e)
                    }

                    override fun onResponse(call: Call, response: Response) {
                        val download = runCatching {
                            response.use {
                                if (!it.isSuccessful) throw GatewayException(it.code, "安装包下载失败（HTTP ${it.code}）")
                                val body = it.body ?: throw IOException("安装包内容为空")
                                val start = when (it.code) {
                                    200 -> 0L
                                    206 -> {
                                        check(offset > 0 &&
                                            it.header("ETag") == "\"${release.sha256}\"" &&
                                            it.header("Content-Range") == "bytes $offset-${release.size - 1}/${release.size}") {
                                            "服务器返回的续传范围与发布清单不符"
                                        }
                                        offset
                                    }
                                    else -> error("服务器安装包响应无效")
                                }
                                check(body.contentType()?.toString() == UPDATE_APK_TYPE &&
                                    body.contentLength() == release.size - start) { "服务器安装包大小或格式与发布清单不符" }
                                transfer.receiving()
                                transfer.measure(body).byteStream().use { input ->
                                    java.io.FileOutputStream(file, start != 0L).buffered().use { output ->
                                        val buffer = ByteArray(65536)
                                        var total = start
                                        progress(total)
                                        while (result.isActive) {
                                            val count = input.read(buffer)
                                            if (count == -1) break
                                            total += count
                                            check(total <= release.size) { "安装包超出发布清单大小" }
                                            output.write(buffer, 0, count)
                                            progress(total)
                                        }
                                        if (total != release.size) throw IOException("安装包下载不完整")
                                    }
                                }
                            }
                        }
                        finished.complete(Unit)
                        result.resumeWith(download)
                    }
                })
            }
            } finally {
                withContext(NonCancellable) { finished.await() }
            }
        }

}

private val directBrowserClient by lazy {
    OkHttpClient.Builder().proxy(Proxy.NO_PROXY)
        .callTimeout(30, TimeUnit.SECONDS)
        .followRedirects(false)
        .followSslRedirects(false)
        .build()
}

internal suspend fun downloadDirectBrowserImage(url: String, cookie: String, agent: String): ByteArray =
    downloadBrowserImage(directBrowserClient, url, cookie, agent)

private suspend fun downloadBrowserImage(
    client: OkHttpClient, url: String, cookie: String, agent: String
): ByteArray {
    require(url.startsWith("https://") || url.startsWith("http://")) { "此图片需使用区域截图" }
    val request = Request.Builder().url(url).header("User-Agent", agent)
        .apply { if (cookie.isNotBlank()) header("Cookie", cookie) }.build()
    val call = client.newCall(request)
    return suspendCancellableCoroutine { result ->
        result.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) = result.resumeWithException(e)

            override fun onResponse(call: Call, response: Response) {
                result.resumeWith(runCatching {
                    response.use { reply ->
                        if (!reply.isSuccessful) throw GatewayException(reply.code, "图片下载失败，请使用区域截图")
                        val body = reply.body ?: error("图片为空")
                        val maxBytes = 5L * 1024 * 1024
                        check(body.contentLength() <= maxBytes) { "图片过大，请使用区域截图" }
                        val source = body.source()
                        check(!source.request(maxBytes + 1)) { "图片过大，请使用区域截图" }
                        source.readByteArray()
                    }
                })
            }
        })
    }
}
