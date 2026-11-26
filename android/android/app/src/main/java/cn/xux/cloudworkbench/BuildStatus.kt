package cn.xux.cloudworkbench

import android.content.SharedPreferences
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.text.DateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

internal data class BuildRun(val id: Long, val number: Long, val source: String, val state: String, val updatedAt: Long)

internal data class BuildOutput(val id: Long, val kind: String, val expiresAt: Long)
internal data class BuildJob(val id: Long, val name: String, val state: String)

internal data class BuildSnapshot(
    val fetchedAt: Long,
    val stale: Boolean,
    val runs: List<BuildRun>,
    val checks: Map<String, String>,
    val outputs: List<BuildOutput>,
    val detailAvailable: Boolean,
    val jobs: List<BuildJob> = emptyList()
) {
    companion object {
        fun parse(payload: JSONObject): BuildSnapshot {
            val time = payload.getLong("fetchedAt")
            require(time > 0L)
            val runs = payload.getJSONArray("runs").objects().take(5).map { run ->
                BuildRun(run.getLong("id"), run.getLong("number"), run.getString("source"),
                    run.getString("state"), run.getLong("updatedAt"))
            }
            val checks = payload.optJSONArray("checks")?.objects().orEmpty()
                .associate { it.getString("id") to it.getString("state") }
            val outputs = payload.optJSONArray("outputs")?.objects().orEmpty().map {
                BuildOutput(it.getLong("id"), it.getString("kind"), it.getLong("expiresAt"))
            }
            val jobs = payload.optJSONArray("jobs")?.objects().orEmpty().map {
                BuildJob(it.getLong("id"), it.getString("name"), it.getString("state"))
            }
            return BuildSnapshot(time, payload.optBoolean("stale"), runs, checks, outputs,
                payload.optBoolean("detailAvailable", true), jobs)
        }
    }
}

internal class BuildStatus(private val prefs: SharedPreferences, private val connection: () -> WorkbenchConnection?) {
    private val cacheKey = "last-github-actions-builds"
    private val selectionKey = "github-actions-request"
    private val resultKey = "github-actions-request-result"
    private fun cached(key: String) = prefs.getString(key, null)?.let { runCatching { JSONObject(it) }.getOrNull() }
    var snapshot by mutableStateOf(prefs.getString(cacheKey, null)?.let {
        runCatching { BuildSnapshot.parse(JSONObject(it)) }.getOrNull()
    }); private set
    var selection by mutableStateOf(cached(selectionKey)?.let { runCatching { BuildSelection.parse(it) }.getOrNull() }); private set
    var tracked by mutableStateOf(cached(resultKey)?.let { data ->
        runCatching { BuildRequestResult.parse(data) }.getOrNull()?.takeIf { it.id == selection?.id }
    }); private set
    var sending by mutableStateOf(false); private set
    var issue by mutableStateOf<String?>(null); private set
    var loading by mutableStateOf(false); private set
    var failed by mutableStateOf(false); private set

    val active: Boolean get() = selection != null && tracked?.finished != true

    suspend fun sources(branch: String?): BuildSources {
        val transport = connection() ?: error("连接服务器后选择源码")
        val path = if (branch == null) "/v1/builds/sources" else
            "/v1/builds/sources?branch=${android.net.Uri.encode(branch)}"
        return BuildSources.parse(transport.sync(path))
    }

    suspend fun log(job: BuildJob): JSONObject {
        val transport = connection() ?: error("连接服务器后读取日志")
        val current = selection ?: error("构建记录不存在")
        return transport.sync("/v1/builds/requests/${current.id}/logs/${job.id}")
    }

    suspend fun start(branch: String, commit: String?) {
        if (sending || active || connection() == null) return
        val input = BuildSelection(UUID.randomUUID().toString(), branch, commit)
        if (!prefs.edit().putString(selectionKey, input.json().toString()).remove(resultKey).commit()) {
            issue = "无法保存构建请求，请检查设备存储"
            return
        }
        selection = input
        tracked = null
        follow()
    }

    suspend fun follow() {
        val transport = connection() ?: return
        val input = selection ?: return
        if (sending) return
        sending = true
        issue = null
        try {
            val payload = try {
                transport.sync("/v1/builds/requests/${input.id}")
            } catch (e: GatewayException) {
                if (e.status != 404 || tracked != null) throw e
                try {
                    transport.request("/v1/builds", input.json())
                } catch (rejected: GatewayException) {
                    if (rejected.status in setOf(400, 409, 412, 422) && selection == input &&
                        prefs.edit().remove(selectionKey).remove(resultKey).commit()) {
                        selection = null
                    }
                    throw rejected
                }
            }
            val next = BuildRequestResult.parse(payload)
            require(next.id == input.id)
            if (selection == input) {
                val previous = tracked?.snapshot?.runs?.firstOrNull()
                if (next.snapshot == null && previous != null && previous.id == next.runId) {
                    cached(resultKey)?.optJSONObject("snapshot")?.let { payload.put("snapshot", it.put("stale", true)) }
                }
                tracked = BuildRequestResult.parse(payload)
                prefs.edit().putString(resultKey, payload.toString()).apply()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: GatewayException) {
            issue = e.message ?: "暂时无法查询构建请求"
        } catch (_: Exception) {
            issue = "暂时无法确认构建状态，连接恢复后将继续核对"
        } finally {
            sending = false
        }
    }

    suspend fun refreshRecent() {
        val transport = connection() ?: return
        if (loading) return
        loading = true
        failed = false
        try {
            val payload = transport.sync("/v1/builds")
            val next = BuildSnapshot.parse(payload)
            if (transport === connection()) {
                snapshot = next
                prefs.edit().putString(cacheKey, payload.toString()).apply()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            if (transport === connection()) failed = true
        } finally {
            loading = false
        }
    }
}

private const val runRoot = "https://github.com/locy016/CloudWorkbench/actions/runs/"
private val checkLabels = linkedMapOf("gateway" to "网关测试", "lint" to "Android Lint", "apk" to "未签名 APK 构建")
private val outputLabels = mapOf("apk" to "未签名 APK", "lint" to "Lint 报告")

internal fun buildState(state: String) = when (state) {
    "queued" -> "排队中"
    "running" -> "运行中"
    "success" -> "通过"
    "failed" -> "失败"
    "cancelled" -> "已取消"
    "skipped" -> "已跳过"
    else -> "暂无结果"
}

@Composable
internal fun BuildStatusPanel(builds: BuildStatus, connected: Boolean) {
    val scope = rememberCoroutineScope()
    val uri = LocalUriHandler.current
    val snapshot = builds.snapshot
    val latest = snapshot?.runs?.firstOrNull()
    var choose by remember { mutableStateOf(false) }
    var jobToRead by remember { mutableStateOf<BuildJob?>(null) }
    var openError by remember { mutableStateOf(false) }
    fun open(address: String) {
        openError = runCatching { uri.openUri(address) }.isFailure
    }
    LaunchedEffect(connected) {
        while (connected) {
            builds.refreshRecent()
            delay(150_000L)
        }
    }
    LaunchedEffect(connected, builds.selection?.id, builds.active) {
        if (connected && builds.selection != null) {
            do {
                builds.follow()
                if (!builds.active) break
                delay(15_000L)
            } while (builds.active)
        }
    }
    if (choose) BuildSourceDialog(builds, dismiss = { choose = false }) { branch, commit ->
        choose = false
        scope.launch { builds.start(branch, commit) }
    }
    jobToRead?.let { BuildLogDialog(builds, it) { jobToRead = null } }

    Panel(Modifier.fillMaxWidth().testTag("githubBuilds")) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("GitHub Actions 构建", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
            TextButton(onClick = { scope.launch { builds.follow(); builds.refreshRecent() } }, enabled = connected && !builds.loading,
                modifier = Modifier.testTag("refreshGithubBuilds")) {
                Text(if (builds.loading) "刷新中" else "刷新")
            }
        }
        ActionRow {
            TextButton(onClick = { choose = true }, enabled = connected && !builds.active && !builds.sending,
                modifier = Modifier.testTag("startGithubBuild")) { Text("发起构建") }
        }
        builds.issue?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        val selected = builds.selection
        val tracked = builds.tracked
        val trackedSnapshot = tracked?.snapshot
        val trackedRun = trackedSnapshot?.runs?.firstOrNull()
        if (selected != null) {
            Text("本次构建 · ${selected.branch}", style = MaterialTheme.typography.titleSmall)
            val status = when {
                trackedRun != null -> "构建 #${trackedRun.number} · ${buildState(trackedRun.state)}"
                tracked?.state == "rejected" -> "GitHub 拒绝了构建请求（${tracked.rejection}）"
                tracked?.runId != null -> "GitHub 已接收 · 正在读取进度"
                !connected -> "等待 SSH 重连后核对构建请求"
                tracked?.state == "pending" -> "正在核对 GitHub 回执"
                else -> "正在提交构建请求"
            }
            Text(status, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.testTag("trackedGithubBuild"))
            (tracked?.source ?: selected.commit)?.let {
                Text(it, color = Muted, style = MaterialTheme.typography.bodySmall)
            }
            if (trackedSnapshot != null) {
                if (!connected || trackedSnapshot.stale || builds.issue != null) {
                    Text("上次查询 · ${DateFormat.getDateTimeInstance().format(Date(trackedSnapshot.fetchedAt))}",
                        color = Muted, style = MaterialTheme.typography.bodySmall)
                }
                BuildChecks(trackedSnapshot)
            }
            tracked?.runId?.let { id ->
                val address = runRoot + id
                ActionRow {
                    TextButton(onClick = { open(address) }) { Text("GitHub 运行") }
                    for (job in trackedSnapshot?.jobs.orEmpty()) {
                        TextButton(enabled = connected && job.state !in setOf("queued", "running"),
                            onClick = { jobToRead = job }) { Text("${job.name}日志") }
                    }
                    BuildOutputs(trackedSnapshot, address, ::open)
                }
            }
        }
        Text("仓库最近构建", style = MaterialTheme.typography.titleSmall)
        if (snapshot != null) {
            val time = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT, Locale.CHINA)
                .format(Date(snapshot.fetchedAt))
            val cached = !connected || builds.failed || snapshot.stale
            Text(if (cached) "上次查询 · $time" else "查询时间 · $time",
                color = Muted, style = MaterialTheme.typography.bodySmall)
        }
        if (snapshot == null || latest == null) {
            val message = when {
                builds.loading -> "正在读取构建结果…"
                !connected -> "连接服务器后查看构建结果"
                builds.failed -> "暂时无法读取构建结果，请刷新"
                else -> "暂无构建运行记录"
            }
            Text(message, color = Muted, style = MaterialTheme.typography.bodyMedium)
        } else {
            val address = runRoot + latest.id
            Text("构建 #${latest.number} · ${latest.source.take(8)} · ${buildState(latest.state)}",
                style = MaterialTheme.typography.bodyLarge, modifier = Modifier.testTag("latestGithubBuild"))
            if (latest.id != tracked?.runId) {
                BuildChecks(snapshot)
                ActionRow {
                    TextButton(onClick = { open(address) }) { Text("运行日志") }
                    BuildOutputs(snapshot, address, ::open)
                }
            }
            for (run in snapshot.runs.drop(1)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically) {
                    Text("#${run.number} · ${run.source.take(8)} · ${buildState(run.state)}",
                        color = Muted, style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = { open(runRoot + run.id) }) { Text("查看运行") }
                }
            }
        }
        if (openError) Text("暂时无法打开 GitHub 页面", color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun BuildChecks(snapshot: BuildSnapshot) {
    for ((id, label) in checkLabels) {
        val state = snapshot.checks[id] ?: continue
        Text("$label · ${buildState(state)}", color = Muted, style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.testTag("githubCheck-$id"))
    }
    if (!snapshot.detailAvailable) Text("构建明细暂不可用，可查看运行日志",
        color = Muted, style = MaterialTheme.typography.bodySmall)
}

@Composable
private fun BuildOutputs(snapshot: BuildSnapshot?, address: String, open: (String) -> Unit) {
    for (output in snapshot?.outputs.orEmpty().filter { it.expiresAt > System.currentTimeMillis() }) {
        val label = outputLabels[output.kind] ?: continue
        TextButton(onClick = { open("$address/artifacts/${output.id}") },
            modifier = Modifier.testTag("githubArtifact-${output.kind}")) { Text(label) }
    }
}
