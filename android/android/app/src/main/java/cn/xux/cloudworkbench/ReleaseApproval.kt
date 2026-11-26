package cn.xux.cloudworkbench

import android.app.KeyguardManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricPrompt
import android.os.Build
import android.os.CancellationSignal
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.annotation.RequiresApi
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.NewReleases
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

private const val approvalsPath = "/v1/release-approvals"
private const val keyAlias = "workbench-release-approval-p256-v1"
private val checkNames = linkedMapOf("gateway" to "网关测试", "lint" to "Android Lint", "apk" to "未签名 APK 构建")
private val sha256 = Regex("[0-9a-f]{64}")
private fun base64(value: ByteArray) = Base64.encodeToString(value, Base64.NO_WRAP)
private fun fingerprint(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
    .joinToString("") { "%02x".format(it.toInt() and 0xff) }

internal data class ReleaseCandidate(
    val runId: String, val version: String, val code: Long, val source: String, val artifact: Long,
    val apkSha256: String, val approvalSha256: String, val notes: String, val checks: Map<String, String>
) {
    fun matchesDigest(): Boolean {
        val parts = listOf(runId, source, artifact.toString(), apkSha256, code.toString(), version, notes) +
            checkNames.keys.map { checks[it].orEmpty() }
        val bytes = parts.joinToString("\n", "WB-RELEASE-CANDIDATE/2\n", "\n") { base64(it.toByteArray()) }.toByteArray()
        return fingerprint(bytes) == approvalSha256
    }

    fun decisionMessage(action: String) = "WB-RELEASE-DECISION/1\n$runId\n$approvalSha256\n$action\n".toByteArray()

    companion object {
        fun parse(json: JSONObject): ReleaseCandidate {
            require(json.getInt("digestVersion") == 2)
            val checks = json.getJSONObject("checks")
            return ReleaseCandidate(json.getString("runId"), json.getString("versionName"), json.getLong("versionCode"),
                json.getString("source"), json.getLong("artifactId"), json.getString("apkSha256"),
                json.getString("approvalSha256"), json.getString("notes"), checkNames.keys.associateWith { checks.getString(it) })
                .also {
                    require(it.runId.matches(Regex("[1-9][0-9]{0,17}")) && it.code > 0 && it.artifact > 0 &&
                        it.version.matches(Regex("[0-9A-Za-z][0-9A-Za-z.+-]{0,39}")) &&
                        it.source.matches(Regex("[0-9a-f]{40}")) && sha256.matches(it.apkSha256) &&
                        sha256.matches(it.approvalSha256) && it.notes.codePointCount(0, it.notes.length) in 1..2000 &&
                        it.checks.values.all { state -> state == "success" } &&
                        it.matchesDigest())
                }
        }
    }
}

internal data class ReleaseApprovalRow(val candidate: ReleaseCandidate, val state: String, val pending: Boolean,
    val byTablet: Boolean, val reason: String? = null)

private fun keyStore() = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

private fun localFingerprint() = keyStore().getCertificate(keyAlias)?.publicKey?.encoded?.let(::fingerprint)

@RequiresApi(30)
private fun publicApprovalKey(context: Context): ByteArray {
    check(context.getSystemService(KeyguardManager::class.java).isDeviceSecure) { "请先设置平板屏幕锁，再绑定审批设备" }
    return keyStore().getCertificate(keyAlias)?.publicKey?.encoded ?: KeyPairGenerator.getInstance("EC", "AndroidKeyStore").run {
        initialize(KeyGenParameterSpec.Builder(keyAlias, KeyProperties.PURPOSE_SIGN)
            .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
            .setDigests(KeyProperties.DIGEST_SHA256)
            .setUserAuthenticationRequired(true)
            .setUserAuthenticationParameters(0, KeyProperties.AUTH_BIOMETRIC_STRONG or KeyProperties.AUTH_DEVICE_CREDENTIAL)
            .build())
        generateKeyPair().public.encoded
    }
}

@RequiresApi(30)
private suspend fun authenticateDecision(context: Context, candidate: ReleaseCandidate, action: String): ByteArray {
    val signature = withContext(Dispatchers.IO) {
        val key = keyStore().getKey(keyAlias, null) as? PrivateKey ?: error("此设备缺少审批密钥")
        Signature.getInstance("SHA256withECDSA").apply { initSign(key) }
    }
    val label = if (action == "approve") "批准正式发布" else "拒绝正式发布"
    return suspendCancellableCoroutine { result ->
        val cancellation = CancellationSignal()
        result.invokeOnCancellation { cancellation.cancel() }
        val prompt = BiometricPrompt.Builder(context)
            .setTitle(label)
            .setSubtitle("${candidate.version} · 构建 ${candidate.code}")
            .setDescription("批准摘要 ${candidate.approvalSha256.take(16)}…")
            .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.DEVICE_CREDENTIAL)
            .build()
        prompt.authenticate(BiometricPrompt.CryptoObject(signature), cancellation, context.mainExecutor,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(auth: BiometricPrompt.AuthenticationResult) {
                    if (!result.isActive) return
                    try {
                        val validated = auth.cryptoObject?.signature ?: error("系统未返回已授权的签名操作")
                        validated.update(candidate.decisionMessage(action))
                        result.resume(validated.sign())
                    } catch (e: Exception) { result.resumeWithException(e) }
                }

                override fun onAuthenticationError(code: Int, message: CharSequence) {
                    if (result.isActive) result.resumeWithException(IllegalStateException(message.toString()))
                }
            })
    }
}

internal class ReleaseApprovals(private val connection: () -> WorkbenchConnection?) {
    var rows by mutableStateOf<List<ReleaseApprovalRow>>(emptyList()); private set
    var pinned by mutableStateOf<String?>(null); private set
    var enrolledKey by mutableStateOf<String?>(null); private set
    var enrollment by mutableStateOf<String?>(null); private set
    var available by mutableStateOf(false); private set
    var loading by mutableStateOf(false); private set
    var busy by mutableStateOf(false); private set
    var issue by mutableStateOf<String?>(null); private set

    suspend fun refresh() {
        val transport = connection() ?: return
        if (loading) return
        loading = true
        try {
            val result = transport.sync(approvalsPath)
            enrolledKey = withContext(Dispatchers.IO) { localFingerprint() }
            pinned = result.optString("fingerprint").takeIf(sha256::matches)
            rows = result.getJSONArray("releases").objects().mapNotNull { item ->
                runCatching {
                    val candidate = ReleaseCandidate.parse(item.getJSONObject("candidate"))
                    ReleaseApprovalRow(candidate, item.getString("state"), item.optBoolean("pending"),
                        item.optJSONObject("approval")?.optString("by") == "tablet",
                        item.optString("reason").takeIf { it.isNotBlank() && it != "null" })
                }.getOrNull()
            }.sortedBy { it.state != "awaiting-approval" }
            available = true
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) {
            if (e is GatewayException && e.status == 404) available = false
        } finally { loading = false }
    }

    @RequiresApi(30)
    suspend fun register(context: Context) = runOperation {
        val transport = connection() ?: error("请先连接服务器")
        val public = withContext(Dispatchers.IO) { publicApprovalKey(context) }
        val local = fingerprint(public)
        val answer = transport.request("$approvalsPath/key", JSONObject().put("publicKey", base64(public)))
        check(local == answer.getString("fingerprint")) { "审批公钥指纹校验失败" }
        enrollment = local
        enrolledKey = local
    }

    @RequiresApi(30)
    suspend fun decide(context: Context, candidate: ReleaseCandidate, action: String) = runOperation {
        val transport = connection() ?: error("请先连接服务器")
        val local = enrolledKey ?: error("请先绑定审批设备")
        check(local == pinned && candidate.matchesDigest()) { "审批设备或候选包校验失败" }
        val signature = authenticateDecision(context, candidate, action)
        val body = JSONObject().put("fingerprint", local).put("approvalSha256", candidate.approvalSha256)
            .put("decision", action).put("signature", base64(signature))
        rows = rows.map { if (it.candidate.runId == candidate.runId) it.copy(pending = true) else it }
        transport.request("$approvalsPath/${candidate.runId}/decision", body)
    }

    private suspend fun runOperation(block: suspend () -> Unit) {
        if (busy) return
        busy = true
        issue = null
        try { block() }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { issue = e.message ?: "暂时无法提交发布审批" }
        finally {
            busy = false
            refresh()
        }
    }
}

@Composable
internal fun ReleaseApprovalPanel(model: ReleaseApprovals, connected: Boolean) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val uri = LocalUriHandler.current
    val supported = Build.VERSION.SDK_INT >= 30
    val bound = model.pinned != null && model.pinned == model.enrolledKey
    var confirmation by remember { mutableStateOf<Pair<ReleaseCandidate, String>?>(null) }
    var showHistory by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(connected) {
        while (connected) {
            model.refresh()
            delay(12_000)
        }
    }
    Panel(Modifier.fillMaxWidth().testTag("releaseApprovals")) {
        TaskPanelHeading("正式发布审批", Icons.Outlined.NewReleases) {
            TextButton(enabled = connected && !model.loading, onClick = { scope.launch { model.refresh() } }) { Text("刷新审批") }
        }
        val status = when {
            !connected -> "连接服务器后查看发布审批"
            model.loading && !model.available -> "正在读取发布审批…"
            !model.available -> "发布审批服务暂不可用"
            !supported -> "此设备可查看候选包，审批需要 Android 11 或更新版本"
            bound -> "此设备已获发布审批授权"
            else -> "此设备尚未绑定发布审批"
        }
        Text(status, color = Muted, style = MaterialTheme.typography.bodySmall)
        if (supported && connected && model.available && !bound) {
            TextButton(enabled = !model.busy, onClick = { scope.launch {
                if (Build.VERSION.SDK_INT >= 30) model.register(context)
            } }) { Text("生成审批公钥") }
        }
        if (!bound) model.enrollment?.let { fingerprint ->
            Text("请管理员核对并绑定以下指纹", color = Muted, style = MaterialTheme.typography.bodySmall)
            Text(fingerprint, style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("releaseKeyFingerprint"))
            TextButton(onClick = { context.getSystemService(ClipboardManager::class.java)
                .setPrimaryClip(ClipData.newPlainText("审批公钥指纹", fingerprint)) }) { Text("复制指纹") }
        }
        model.issue?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        if (model.available && model.rows.isEmpty()) Text("暂无发布候选包", color = Muted)
        val (openRows, processedRows) = model.rows.partition { it.state in setOf("awaiting-approval", "signing-failed") }
        val featuredRows = openRows.ifEmpty { processedRows.take(1) }
        val history = if (openRows.isEmpty()) processedRows.drop(1) else processedRows
        for (row in featuredRows + (if (showHistory) history else emptyList())) {
            key(row.candidate.runId) {
                HorizontalDivider()
                ReleaseCandidateRow(row, showDecision = bound && row.state == "awaiting-approval" && !row.pending,
                    canDecide = connected && !model.busy,
                    viewRun = { runCatching { uri.openUri("https://github.com/locy016/CloudWorkbench/actions/runs/${row.candidate.runId}") } },
                    decide = { action -> confirmation = row.candidate to action })
            }
        }
        if (history.isNotEmpty()) TextButton(onClick = { showHistory = !showHistory }) {
            Text(if (showHistory) "收起发布历史" else "发布历史 (${history.size})")
        }
    }
    confirmation?.let { (candidate, action) ->
        val label = if (action == "approve") "批准发布" else "拒绝发布"
        AlertDialog(onDismissRequest = { confirmation = null }, title = { Text(label) }, text = {
            Column(Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState())) {
                Text("${candidate.version} · 构建 ${candidate.code}")
                ReleaseCandidateDetails(candidate)
            }
        }, confirmButton = {
            Button(onClick = {
                confirmation = null
                scope.launch { if (Build.VERSION.SDK_INT >= 30) model.decide(context, candidate, action) }
            }) { Text("验证身份并$label") }
        }, dismissButton = { TextButton(onClick = { confirmation = null }) { Text("取消") } })
    }
}

@Composable
private fun ReleaseCandidateRow(row: ReleaseApprovalRow, showDecision: Boolean, canDecide: Boolean,
    viewRun: () -> Unit, decide: (String) -> Unit) {
    val candidate = row.candidate
    var expanded by rememberSaveable(candidate.runId) { mutableStateOf(false) }
    val (status, tone) = when (row.state) {
        "awaiting-approval" -> if (row.pending) "等待服务复核" to TaskTone.Active else "待审批" to TaskTone.Waiting
        "published" -> "已发布" to TaskTone.Complete
        "rejected-by-operator" -> "已拒绝" to TaskTone.Neutral
        "signing-failed" -> "签名失败" to TaskTone.Error
        else -> row.state to TaskTone.Neutral
    }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(candidate.version, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
        TaskBadge(status, tone)
    }
    Text("构建 ${candidate.code} · ${candidate.source.take(8)}${if (row.byTablet && row.state == "published") " · 平板批准" else ""}",
        color = Muted, style = MaterialTheme.typography.bodySmall)
    if (expanded) {
        ReleaseCandidateDetails(candidate)
        row.reason?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
    } else {
        Text(row.reason ?: candidate.notes, color = if (row.reason == null) Muted else MaterialTheme.colorScheme.error,
            maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
    }
    ActionRow {
        if (showDecision) {
            Button(enabled = canDecide, onClick = { decide("approve") }) { Text("批准发布") }
            TextButton(enabled = canDecide, onClick = { decide("reject") }) { Text("拒绝发布") }
        }
        TextButton(onClick = { expanded = !expanded }, modifier = Modifier.testTag("releaseDetails-${candidate.runId}")) {
            Text(if (expanded) "收起详情" else "查看详情")
        }
        if (expanded) TextButton(onClick = viewRun) { Text("验收运行") }
    }
}

@Composable
private fun ReleaseCandidateDetails(candidate: ReleaseCandidate) {
    Text(candidate.notes, style = MaterialTheme.typography.bodyMedium)
    Text("网关测试、Android Lint、未签名 APK 构建均通过", style = MaterialTheme.typography.bodySmall)
    SelectionContainer {
        Column {
            Text("源码 ${candidate.source}", color = Muted, style = MaterialTheme.typography.bodySmall)
            Text("批准摘要 ${candidate.approvalSha256}", color = Muted, style = MaterialTheme.typography.bodySmall)
        }
    }
}
