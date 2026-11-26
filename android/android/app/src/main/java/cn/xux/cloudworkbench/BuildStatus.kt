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

internal data class BuildRun(val id: Long, val number: Long, val source: String, val state: String, val updatedAt: Long)

internal data class BuildOutput(val id: Long, val kind: String, val expiresAt: Long)

internal data class BuildSnapshot(
    val fetchedAt: Long,
    val stale: Boolean,
    val runs: List<BuildRun>,
    val checks: Map<String, String>,
    val outputs: List<BuildOutput>,
    val detailAvailable: Boolean
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
            return BuildSnapshot(time, payload.optBoolean("stale"), runs, checks, outputs,
                payload.optBoolean("detailAvailable", true))
        }
    }
}

internal class BuildStatus(private val prefs: SharedPreferences, private val connection: () -> WorkbenchConnection?) {
    private val cacheKey = "last-github-actions-builds"
    var snapshot by mutableStateOf(prefs.getString(cacheKey, null)?.let {
        runCatching { BuildSnapshot.parse(JSONObject(it)) }.getOrNull()
    }); private set
    var loading by mutableStateOf(false); private set
    var failed by mutableStateOf(false); private set

    suspend fun refresh() {
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

private fun buildState(state: String) = when (state) {
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
    var openError by remember { mutableStateOf(false) }
    fun open(address: String) {
        openError = runCatching { uri.openUri(address) }.isFailure
    }
    LaunchedEffect(connected) {
        while (connected) {
            builds.refresh()
            val active = builds.snapshot?.runs?.firstOrNull()?.state in setOf("queued", "running")
            delay(if (active) 60_000L else 150_000L)
        }
    }

    Panel(Modifier.fillMaxWidth().testTag("githubBuilds")) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("GitHub Actions 构建", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
            TextButton(onClick = { scope.launch { builds.refresh() } }, enabled = connected && !builds.loading,
                modifier = Modifier.testTag("refreshGithubBuilds")) {
                Text(if (builds.loading) "刷新中" else "刷新")
            }
        }
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
            for ((id, label) in checkLabels) {
                val state = snapshot.checks[id] ?: continue
                Text("$label · ${buildState(state)}", color = Muted, style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.testTag("githubCheck-$id"))
            }
            if (!snapshot.detailAvailable) Text("构建明细暂不可用，可查看运行日志",
                color = Muted, style = MaterialTheme.typography.bodySmall)
            ActionRow {
                TextButton(onClick = { open(address) }) { Text("运行日志") }
                for (output in snapshot.outputs.filter { it.expiresAt > System.currentTimeMillis() }) {
                    val label = outputLabels[output.kind] ?: continue
                    TextButton(onClick = { open("$address/artifacts/${output.id}") },
                        modifier = Modifier.testTag("githubArtifact-${output.kind}")) { Text(label) }
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
