package cn.xux.cloudworkbench

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import android.util.AtomicFile
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.SystemUpdate
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicReference

internal const val UPDATE_APK_TYPE = "application/vnd.android.package-archive"

internal enum class UpdatePhase { PREPARING, RECEIVING, VERIFYING }

private data class UpdateSample(val at: Long, val bytes: Long)

internal data class AppRelease(
    val versionCode: Long,
    val versionName: String,
    val sha256: String,
    val certificate: String,
    val size: Long,
    val notes: String
) {
    companion object {
        fun parse(json: JSONObject, packageId: String): AppRelease {
            val release = AppRelease(json.getLong("versionCode"), json.getString("versionName"),
                json.getString("sha256"), json.getString("certificate"), json.getLong("size"),
                json.getString("notes"))
            val digest = Regex("[a-f0-9]{64}")
            require(json.getString("packageId") == packageId &&
                release.versionCode in 1..2_100_000_000L &&
                release.versionName.matches(Regex("[0-9A-Za-z][0-9A-Za-z.+-]{0,39}")) &&
                release.sha256.matches(digest) && release.certificate.matches(digest) &&
                release.size in 1..(1024L * 1024 * 1024) && release.notes.length <= 2000) {
                "服务器发布清单无效"
            }
            return release
        }
    }
}

internal class AppUpdates(
    private val app: Context,
    private val scope: CoroutineScope,
    private val connection: () -> WorkbenchConnection
) {
    var release by mutableStateOf<AppRelease?>(null)
        private set
    var status by mutableStateOf<String?>(null)
        private set
    var checking by mutableStateOf(false)
        private set
    var phase by mutableStateOf<UpdatePhase?>(null)
        private set
    val downloading get() = phase != null
    var cancelling by mutableStateOf(false)
        private set
    var ready by mutableStateOf(false)
        private set
    val progress = MutableStateFlow(0L)
    val speed = MutableStateFlow(0L)
    private var job: Job? = null
    private var apk: File? = null
    private val directory = File(app.filesDir, "app-updates")

    private fun target(candidate: AppRelease) = File(directory, "${candidate.versionCode}-${candidate.sha256}.apk")
    private fun manifest(candidate: AppRelease) = File(directory, "${candidate.versionCode}-${candidate.sha256}.json")

    init {
        scope.launch {
            withContext(Dispatchers.IO) {
            // A successful system update starts this version in a new process.
            directory.listFiles()?.filter { file ->
                file.name.substringBefore('-').toIntOrNull()?.let { it <= BuildConfig.VERSION_CODE } == true
            }?.forEach { it.deleteRecursively() }
            }
            discoverSavedDownload()
        }
    }

    suspend fun discoverSavedDownload() {
        if (release != null || checking || downloading) return
        checking = true
        try {
            val saved = withContext(Dispatchers.IO) {
                directory.listFiles().orEmpty().filter { it.extension == "json" }.mapNotNull { file ->
                    runCatching { AppRelease.parse(JSONObject(AtomicFile(file).readFully().toString(Charsets.UTF_8)), app.packageName) }
                        .getOrNull()?.takeIf { it.versionCode > BuildConfig.VERSION_CODE && it.certificate in installedSigners() }
                }.maxByOrNull { it.versionCode }
            } ?: return
            release = saved
            progress.value = withContext(Dispatchers.IO) { target(saved).savedUpdateBytes(saved) }
        } finally {
            checking = false
        }
        // A complete saved APK is reverified locally, even before SSH reconnects.
        if (progress.value >= (release?.size ?: Long.MAX_VALUE)) download()
    }

    fun check() {
        if (checking || downloading) return
        checking = true
        status = null
        job = scope.launch {
            try {
                val response = connection().request("/v1/app-update")
                val candidate = if (response.isNull("update") && response.has("update")) null
                    else AppRelease.parse(response.getJSONObject("update"), app.packageName)
                val newer = candidate?.takeIf { it.versionCode > BuildConfig.VERSION_CODE }
                val signed = newer?.takeIf { it.certificate in installedSigners() }
                if (signed != null) withContext(Dispatchers.IO) {
                    check(directory.isDirectory || directory.mkdirs()) { "无法保存更新清单" }
                    val cache = AtomicFile(manifest(signed))
                    val output = cache.startWrite()
                    try {
                        output.write(response.getJSONObject("update").toString().toByteArray(Charsets.UTF_8))
                        cache.finishWrite(output)
                    } catch (e: Exception) {
                        cache.failWrite(output)
                        throw e
                    }
                }
                val file = signed?.let(::target)
                val saved = withContext(Dispatchers.IO) { signed?.let { target(it).savedUpdateBytes(it) } ?: 0L }
                if (file == null || apk != file || !file.isFile) {
                    apk = null
                    ready = false
                }
                release = signed
                progress.value = saved
                if (newer == null) status = "当前已是最新版本"
                else if (signed == null) status = "发布签名与当前 App 不符"
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                status = "检查更新失败，请确认服务器连接后重试"
            } finally {
                checking = false
            }
        }
    }

    fun download() {
        val candidate = release ?: return
        if (checking || downloading || ready) return
        phase = UpdatePhase.PREPARING
        cancelling = false
        speed.value = 0
        status = null
        job = scope.launch {
            var keepPartial = true
            val file = target(candidate)
            try {
                val saved = withContext(Dispatchers.IO) {
                    check(directory.isDirectory || directory.mkdirs()) { "无法创建安装包目录" }
                    val parts = file.updateParts()
                    directory.listFiles()?.filter { it != file && it != parts && it != manifest(candidate) }?.forEach { it.deleteRecursively() }
                    if (file.length() > candidate.size) file.delete()
                    val bytes = file.savedUpdateBytes(candidate)
                    val mergeReserve = maxOf(64L * 1024 * 1024, (candidate.size + UPDATE_STREAMS - 1) / UPDATE_STREAMS)
                    if (file.length() < candidate.size && directory.usableSpace < candidate.size - bytes + mergeReserve) {
                        throw IOException("平板存储空间不足")
                    }
                    bytes
                }
                progress.value = saved
                phase = UpdatePhase.RECEIVING
                if (saved < candidate.size) receive(connection(), candidate, file)
                else if (file.length() < candidate.size) {
                    ConnectionTransfers().track(TransferKind.AppUpdateDownload) { transfer ->
                        downloadUpdateRanges(candidate, file, transfer, { progress.value = it }) { _, _, _ ->
                            throw IOException("下载分片不完整，请连接服务器后继续下载")
                        }
                    }
                }
                phase = UpdatePhase.VERIFYING
                keepPartial = false
                withContext(Dispatchers.IO) { verify(file, candidate) }
                progress.value = candidate.size
                apk = file
                ready = true
                status = "安装包校验通过"
            } catch (e: CancellationException) {
                keepPartial = true
                status = "已取消，可继续下载"
                throw e
            } catch (e: GatewayException) {
                status = if (e.status == 404) "发布版本已变更，请重新检查更新" else e.message
            } catch (e: IOException) {
                keepPartial = phase != UpdatePhase.VERIFYING
                status = when (phase) {
                    UpdatePhase.PREPARING -> e.message ?: "无法准备下载"
                    UpdatePhase.VERIFYING -> "读取安装包失败，请重新下载"
                    else -> "下载中断，可继续下载"
                }
            } catch (e: Exception) {
                status = e.message ?: "安装包校验失败"
            } finally {
                // Keep both disk cleanup and the UI transition inside the cancellation
                // shield. Returning from IO directly into a cancelled job skips later code.
                withContext(NonCancellable) {
                    if (!ready) {
                        progress.value = withContext(Dispatchers.IO) {
                            if (!keepPartial) {
                                file.delete()
                                file.updateParts().deleteRecursively()
                            }
                            if (keepPartial) file.savedUpdateBytes(candidate) else 0L
                        }
                    }
                    speed.value = 0L
                    cancelling = false
                    phase = null
                }
            }
        }
    }

    fun cancel() {
        if (downloading && !cancelling) {
            cancelling = true
            status = "正在保存下载进度…"
            job?.cancel()
        }
    }

    private suspend fun receive(active: WorkbenchConnection, candidate: AppRelease, file: File) = coroutineScope {
        val first = AtomicReference<UpdateSample?>()
        val ticker = launch {
            val samples = ArrayDeque<UpdateSample>()
            while (true) {
                delay(500)
                val initial = first.get() ?: continue
                if (samples.isEmpty()) samples.addLast(initial)
                val now = UpdateSample(SystemClock.elapsedRealtime(), progress.value)
                samples.addLast(now)
                while (samples.size > 2 && samples[1].at <= now.at - 4_000L) samples.removeFirst()
                val previous = samples.first()
                val elapsed = now.at - previous.at
                speed.value = if (elapsed > 0) ((now.bytes - previous.bytes).coerceAtLeast(0L) * 1000L / elapsed) else 0L
            }
        }
        try {
            active.downloadUpdate(candidate, file) { bytes ->
                val received = bytes.coerceIn(0L, candidate.size)
                if (first.get() == null) first.compareAndSet(null, UpdateSample(SystemClock.elapsedRealtime(), received))
                progress.value = received
            }
        } finally {
            ticker.cancel()
            speed.value = 0L
        }
    }

    @Suppress("DEPRECATION")
    fun install(context: Context) {
        val file = apk?.takeIf { ready && it.isFile && it.length() == release?.size }
        if (file == null) {
            apk = null
            ready = false
            progress.value = 0L
            status = "安装包已被清理，请重新下载"
            return
        }
        try {
            if (!context.packageManager.canRequestPackageInstalls()) {
                status = "请在系统设置允许安装，然后返回点击「安装更新」"
                context.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.fromParts("package", app.packageName, null)))
                return
            }
            val intent = updateInstallIntent(app, file)
            val readableBytes = app.contentResolver.openFileDescriptor(checkNotNull(intent.data), "r")?.use { it.statSize }
            if (readableBytes != file.length()) {
                status = "系统安装器无法读取完整安装包"
                return
            }
            context.startActivity(intent)
        } catch (_: Exception) {
            status = "无法打开系统安装界面，请检查平板的安装权限"
        }
    }

    private suspend fun verify(file: File, candidate: AppRelease) {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(65536)
            while (true) {
                kotlin.coroutines.coroutineContext.ensureActive()
                val count = input.read(buffer)
                if (count == -1) break
                digest.update(buffer, 0, count)
            }
        }
        check(file.length() == candidate.size && digest.digest().hex() == candidate.sha256) { "安装包 SHA-256 校验失败" }
        val info = app.packageManager.getPackageArchiveInfo(file.path, signatureFlags) ?: error("无法解析安装包")
        check(info.packageName == app.packageName && info.buildCode == candidate.versionCode &&
            info.buildCode > BuildConfig.VERSION_CODE && info.versionName == candidate.versionName) {
            "安装包版本或包名不符"
        }
        val signers = info.signers()
        check(candidate.certificate in signers && signers == installedSigners()) { "安装包签名与当前 App 不符" }
    }

    @Suppress("DEPRECATION")
    private fun installedSigners() = app.packageManager.getPackageInfo(app.packageName, signatureFlags).signers()

    @Suppress("DEPRECATION")
    private val signatureFlags get() = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES
        else PackageManager.GET_SIGNATURES

    @Suppress("DEPRECATION")
    private val PackageInfo.buildCode get() = if (Build.VERSION.SDK_INT >= 28) longVersionCode else versionCode.toLong()

    @Suppress("DEPRECATION")
    private fun PackageInfo.signers(): Set<String> {
        val certificates = if (Build.VERSION.SDK_INT >= 28) signingInfo?.apkContentsSigners else signatures
        return certificates.orEmpty().map { MessageDigest.getInstance("SHA-256").digest(it.toByteArray()).hex() }.toSet()
    }

    private fun ByteArray.hex() = joinToString("") { "%02x".format(it) }
}

@Suppress("DEPRECATION")
internal fun updateInstallIntent(context: Context, file: File): Intent {
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.updates", file)
    return Intent(Intent.ACTION_INSTALL_PACKAGE)
        .setDataAndType(uri, UPDATE_APK_TYPE)
        .apply { clipData = ClipData.newRawUri("安装包", uri) }
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
}

@Composable internal fun AppUpdatePanel(updates: AppUpdates, connected: Boolean) {
    val context = LocalContext.current
    val progress by updates.progress.collectAsState()
    val speed by updates.speed.collectAsState()
    LaunchedEffect(updates) {
        updates.discoverSavedDownload()
    }
    Panel(Modifier.fillMaxWidth().testTag("appUpdatePanel")) {
        SettingsHeading(Icons.Outlined.SystemUpdate, "应用更新")
        Text("当前版本 ${BuildConfig.VERSION_NAME} · 构建 ${BuildConfig.VERSION_CODE}",
            color = Muted, style = MaterialTheme.typography.bodySmall)
        updates.release?.let { release ->
            Text("新版本 ${release.versionName} · ${transferSize(release.size)}",
                style = MaterialTheme.typography.titleSmall, modifier = Modifier.testTag("appUpdateAvailable"))
            if (release.notes.isNotBlank()) Text(release.notes, style = MaterialTheme.typography.bodyMedium)
            if (updates.downloading || (progress > 0L && !updates.ready)) {
                val percent = (progress * 100L / release.size).coerceIn(0L, 100L)
                val label = when (updates.phase) {
                    UpdatePhase.PREPARING -> "准备下载"
                    UpdatePhase.RECEIVING -> if (progress >= release.size) "正在合并安装包" else "正在下载"
                    UpdatePhase.VERIFYING -> "正在校验安装包"
                    null -> "已保存下载进度"
                }
                LinearProgressIndicator(progress = { percent / 100f }, modifier = Modifier.fillMaxWidth())
                Text("$label · $percent% · ${transferSize(progress)} / ${transferSize(release.size)}",
                    style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("appUpdateProgress"))
                if (updates.phase == UpdatePhase.RECEIVING && progress < release.size && !updates.cancelling) {
                    val eta = if (speed > 0L) formatUpdateEta((release.size - progress + speed - 1) / speed) else "—"
                    Text("速度 ${transferSize(speed)}/秒 · 预计剩余 $eta", style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.testTag("appUpdateRate"))
                }
            }
        }
        updates.status?.let { Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("appUpdateStatus")) }
        if (!connected && !updates.ready) Text("连接服务器后可检查和下载更新", color = Muted, style = MaterialTheme.typography.bodySmall)
        ActionRow {
            when {
                updates.downloading -> OutlinedButton(onClick = updates::cancel, enabled = !updates.cancelling,
                    modifier = Modifier.testTag("appUpdateCancel")) { Text(if (updates.cancelling) "取消中…" else "取消下载") }
                updates.ready -> Button(onClick = { updates.install(context) }, enabled = !updates.checking,
                    modifier = Modifier.testTag("appUpdateInstall")) { Text("安装更新") }
                updates.release != null -> Button(onClick = updates::download,
                    enabled = (connected || progress >= (updates.release?.size ?: Long.MAX_VALUE)) && !updates.checking,
                    modifier = Modifier.testTag("appUpdateDownload")) {
                    Text(when {
                        progress >= (updates.release?.size ?: Long.MAX_VALUE) -> "继续处理安装包"
                        progress > 0L -> "继续下载"
                        else -> "下载更新"
                    })
                }
            }
            if (!updates.downloading) OutlinedButton(onClick = updates::check, enabled = connected && !updates.checking,
                modifier = Modifier.testTag("appUpdateCheck")) {
                Text(if (updates.checking) "检查中…" else "检查更新")
            }
        }
    }
}

private fun formatUpdateEta(seconds: Long): String = when {
    seconds < 60L -> "${seconds}秒"
    seconds < 3600L -> "${seconds / 60}分${seconds % 60}秒"
    seconds >= 360_000L -> "超过99小时"
    else -> "${seconds / 3600}小时${seconds / 60 % 60}分"
}
