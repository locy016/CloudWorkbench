package cn.xux.cloudworkbench

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import org.json.JSONObject

internal data class BuildSelection(val id: String, val branch: String, val commit: String?) {
    fun json() = JSONObject().put("requestId", id).put("branch", branch).put("commit", commit ?: JSONObject.NULL)

    companion object {
        fun parse(json: JSONObject) = BuildSelection(json.getString("requestId"), json.getString("branch"),
            json.takeUnless { it.isNull("commit") }?.getString("commit"))
    }
}

internal data class BuildRequestResult(
    val id: String, val source: String, val state: String, val runId: Long?, val rejection: Int?, val snapshot: BuildSnapshot?
) {
    val finished get() = state == "rejected" || snapshot?.runs?.firstOrNull()?.state in
        setOf("success", "failed", "cancelled", "skipped")

    companion object {
        fun parse(json: JSONObject) = BuildRequestResult(json.getString("requestId"), json.getString("source"),
            json.getString("state"), json.optLong("runId").takeIf { it > 0 }, json.optInt("rejection").takeIf { it > 0 },
            json.optJSONObject("snapshot")?.let(BuildSnapshot::parse))
    }
}

internal data class BuildCommit(val sha: String, val title: String)

internal data class BuildSources(val enabled: Boolean, val branches: List<String>, val commits: List<BuildCommit>) {
    companion object {
        fun parse(json: JSONObject) = BuildSources(json.getBoolean("enabled"),
            json.getJSONArray("branches").objects().map { it.getString("name") },
            json.getJSONArray("commits").objects().map { BuildCommit(it.getString("sha"), it.getString("title")) })
    }
}

@Composable
internal fun BuildSourceDialog(builds: BuildStatus, dismiss: () -> Unit, start: (String, String?) -> Unit) {
    var catalog by remember { mutableStateOf<BuildSources?>(null) }
    var branch by remember { mutableStateOf<String?>(null) }
    var commit by remember { mutableStateOf<String?>(null) }
    var manual by remember { mutableStateOf(false) }
    var sha by remember { mutableStateOf("") }
    var choices by remember { mutableStateOf<List<BuildCommit>>(emptyList()) }
    var expanded by remember { mutableStateOf(false) }
    var ready by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var retry by remember { mutableStateOf(0) }
    LaunchedEffect(branch, retry) {
        error = null
        ready = false
        choices = emptyList()
        try {
            val result = builds.sources(branch)
            catalog = result
            choices = result.commits
            if (branch == null) branch = result.branches.firstOrNull { it == "main" } ?: result.branches.firstOrNull()
            else ready = true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error = e.message ?: "暂时无法获取源码列表"
        }
    }
    AlertDialog(onDismissRequest = dismiss, title = { Text("选择构建源码") }, text = {
        Column {
            val branches = catalog?.branches.orEmpty()
            if (branches.isNotEmpty()) {
                Box {
                    OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) {
                        Text("分支 · ${branch.orEmpty()}")
                    }
                    DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                        for (name in branches) DropdownMenuItem(text = { Text(name) }, onClick = {
                            expanded = false
                            if (branch != name) {
                                ready = false
                                commit = null
                                sha = ""
                                branch = name
                            }
                        })
                    }
                }
            }
            when {
                error != null -> {
                    Text(error.orEmpty(), color = MaterialTheme.colorScheme.error)
                    TextButton(onClick = { retry++ }) { Text("重新加载") }
                }
                catalog?.enabled == false -> Text("服务器尚未配置 GitHub Actions 授权", color = Muted)
                catalog != null && branches.isEmpty() -> Text("仓库没有可用分支", color = Muted)
                !ready -> Text("正在读取提交…", color = Muted)
                else -> {
                    TextButton(onClick = { manual = !manual }) { Text(if (manual) "选择最近提交" else "输入完整 SHA") }
                    if (manual) {
                        OutlinedTextField(value = sha, onValueChange = { sha = it.trim().take(40) },
                            label = { Text("40 位提交 SHA") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    } else LazyColumn(Modifier.heightIn(max = 240.dp).testTag("githubBuildSources")) {
                        item { BuildSourceRow("分支最新提交", choices.firstOrNull()?.sha?.take(12), commit == null) { commit = null } }
                        items(choices, key = { it.sha }) { item ->
                            BuildSourceRow(item.title.ifBlank { "无提交说明" }, item.sha.take(12), commit == item.sha) { commit = item.sha }
                        }
                    }
                }
            }
        }
    }, confirmButton = {
        val valid = if (manual) sha.matches(Regex("[0-9a-fA-F]{40}")) else choices.isNotEmpty()
        Button(enabled = catalog?.enabled == true && ready && valid, onClick = {
            branch?.let { start(it, if (manual) sha.lowercase() else commit) }
        }, modifier = Modifier.testTag("confirmGithubBuild")) { Text("开始构建") }
    }, dismissButton = { TextButton(onClick = dismiss) { Text("取消") } })
}

@Composable
private fun BuildSourceRow(label: String, sha: String?, selected: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().selectable(selected, role = Role.RadioButton, onClick = onClick).padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected, onClick = null, modifier = Modifier.padding(end = 12.dp))
        Column {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            sha?.let { Text(it, color = Muted, style = MaterialTheme.typography.bodySmall) }
        }
    }
}

@Composable
internal fun BuildLogDialog(builds: BuildStatus, job: BuildJob, dismiss: () -> Unit) {
    var content by remember(job) { mutableStateOf<JSONObject?>(null) }
    var error by remember(job) { mutableStateOf<String?>(null) }
    var retry by remember(job) { mutableStateOf(0) }
    LaunchedEffect(job, retry) {
        error = null
        try {
            content = builds.log(job)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error = e.message ?: "暂时无法读取构建日志"
        }
    }
    AlertDialog(onDismissRequest = dismiss, title = { Text("${job.name}日志") }, text = {
        val result = content
        Column {
            if (result != null) {
                if (result.optBoolean("truncated")) Text("显示部分日志，可在 GitHub 查看完整内容", color = Muted)
                Text(result.optString("text").ifBlank { "日志为空" },
                    Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState()).testTag("githubJobLog"),
                    fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
            } else Text(error ?: "正在读取日志…", color = Muted)
        }
    }, confirmButton = { TextButton(onClick = dismiss) { Text("关闭") } }, dismissButton = {
        if (error != null) TextButton(onClick = { retry++ }) { Text("重试") }
    })
}
