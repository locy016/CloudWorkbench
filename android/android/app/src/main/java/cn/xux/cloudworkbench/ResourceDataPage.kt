package cn.xux.cloudworkbench

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

internal data class ResourceOption(val value: String, val label: String)
internal data class ResourceFilter(val key: String, val label: String, val options: List<ResourceOption>, val maxLength: Int?)
internal data class ResourceSource(val id: String, val title: String, val publisher: String, val filters: List<ResourceFilter>)
internal data class ResourceResult(val source: String, val filters: Map<String, String>, val payload: JSONObject)

internal class ResourceData(private val connection: () -> WorkbenchConnection?) {
    var sources by mutableStateOf(emptyList<ResourceSource>()); private set
    var loading by mutableStateOf(false); private set
    var error by mutableStateOf<String?>(null); private set
    var result by mutableStateOf<ResourceResult?>(null); private set
    private var pendingExport: String? = null

    suspend fun refreshCatalog() = operation("无法读取资源目录，请重试") { transport ->
        val list = transport.sync("/v1/server-data/resources").getJSONArray("sources").objects().map { source ->
            val id = source.getString("id")
            require(id.matches(Regex("[a-z][a-z0-9-]{0,47}")))
            val filters = source.getJSONArray("filters").objects().map { filter ->
                val key = filter.getString("key")
                val label = filter.getString("label")
                when (filter.getString("type")) {
                    "choice" -> ResourceFilter(key, label, filter.getJSONArray("options").objects().map {
                        ResourceOption(it.getString("value"), it.getString("label"))
                    }, null)
                    "text" -> ResourceFilter(key, label, emptyList(), filter.getInt("maxLength").also { require(it in 1..200) })
                    else -> error("Unsupported resource filter")
                }
            }
            ResourceSource(id, source.getString("title"), source.getString("publisher"), filters)
        }
        if (transport === connection()) sources = list
    }

    suspend fun download(source: ResourceSource, choices: Map<String, String>) =
        operation("获取失败，请稍后重试") { transport ->
            val query = Uri.Builder()
            choices.forEach { (key, value) -> query.appendQueryParameter(key, value) }
            val path = "/v1/server-data/resources/${source.id}" + query.build().encodedQuery?.let { "?$it" }.orEmpty()
            val payload = transport.sync(path)
            require(payload.getString("id") == source.id && payload.getJSONArray("rows").length() <= 100)
            payload.getJSONArray("columns").objects()
            if (transport === connection()) result = ResourceResult(source.id, choices, payload)
        }

    private suspend fun operation(message: String, request: suspend (WorkbenchConnection) -> Unit) {
        val transport = connection() ?: return
        if (loading) return
        loading = true
        error = null
        try {
            request(transport)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            if (transport === connection()) error = message
        } finally {
            loading = false
        }
    }

    fun prepareExport(result: ResourceResult) {
        pendingExport = result.payload.toString(2)
    }

    suspend fun saveExport(context: Context, destination: Uri?): Boolean {
        val text = pendingExport
        pendingExport = null
        if (destination == null || text == null) return false
        return withContext(Dispatchers.IO) {
            val output = context.contentResolver.openOutputStream(destination) ?: error("无法打开保存位置")
            output.bufferedWriter(Charsets.UTF_8).use { it.write(text) }
            true
        }
    }
}

@Composable
internal fun ResourceDataPage(data: ResourceData, connected: Boolean, visible: Boolean) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var sourceId by rememberSaveable { mutableStateOf("") }
    var exportStatus by rememberSaveable { mutableStateOf<String?>(null) }
    val source = data.sources.find { it.id == sourceId } ?: data.sources.firstOrNull()
    val selections = remember(source) { mutableStateMapOf<String, String>() }
    val choices = source?.filters.orEmpty().associate {
        it.key to (selections[it.key] ?: it.options.firstOrNull()?.value.orEmpty()).trim()
    }
    val result = data.result?.takeIf { it.source == source?.id && it.filters == choices }
    val document = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        scope.launch {
            try {
                if (data.saveExport(context, uri)) exportStatus = "已保存 JSON"
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                exportStatus = "保存失败，请重试"
            }
        }
    }
    LaunchedEffect(visible, connected) {
        if (visible && connected) data.refreshCatalog()
    }

    LazyColumn(
        Modifier.fillMaxSize().testTag("onDemandResources"), horizontalAlignment = Alignment.CenterHorizontally,
        contentPadding = PaddingValues(horizontal = LocalPagePadding.current, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            Panel(Modifier.widthIn(max = 640.dp).fillMaxWidth()) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("按需获取", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                    TextButton(onClick = { scope.launch { data.refreshCatalog() } }, enabled = connected && !data.loading) {
                        Text("刷新目录")
                    }
                }
                if (source != null) {
                    ResourcePicker("资源", source.title, data.sources.map { ResourceOption(it.id, it.title) },
                        Modifier.testTag("resourcePicker")) { sourceId = it }
                    Text("来源：${source.publisher}", color = Muted)
                    source.filters.forEach { filter ->
                        val value = choices.getValue(filter.key)
                        if (filter.maxLength != null) {
                            OutlinedTextField(selections[filter.key].orEmpty(), { selections[filter.key] = it.take(filter.maxLength) },
                                modifier = Modifier.fillMaxWidth().testTag("resourceInput-${filter.key}"),
                                label = { Text(filter.label) }, singleLine = true)
                        } else {
                            ResourcePicker(filter.label, filter.options.firstOrNull { it.value == value }?.label.orEmpty(),
                                filter.options, Modifier.testTag("resourceInput-${filter.key}")) { selections[filter.key] = it }
                        }
                    }
                    Button(onClick = {
                        exportStatus = null
                        scope.launch { data.download(source, choices) }
                    }, enabled = connected && !data.loading && choices.values.none { it.isBlank() },
                        modifier = Modifier.testTag("fetchResource")) { Text("获取数据") }
                }
                if (data.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
                val status = when {
                    !connected -> "连接云端后可获取资源"
                    data.error != null -> data.error
                    !data.loading && source == null -> "服务器暂无可用资源"
                    else -> null
                }
                status?.let { Text(it, color = Amber) }
            }
        }
        if (result != null) {
            val payload = result.payload
            item {
                Panel(Modifier.widthIn(max = 640.dp).fillMaxWidth().testTag("resourceResult")) {
                    Text("获取时间：${resourceTime(payload.getString("fetchedAt"))}", color = Muted)
                    if (!payload.isNull("updatedAt")) {
                        Text("来源更新时间：${resourceTime(payload.getString("updatedAt"))}", color = Muted)
                    }
                    val count = payload.getJSONArray("rows").length()
                    Text(if (count == 0) "该条件下暂无记录" else "本次返回 $count 条记录")
                    OutlinedButton(onClick = {
                        data.prepareExport(result)
                        document.launch("${result.source}-${payload.getString("fetchedAt").take(10)}.json")
                    }, modifier = Modifier.testTag("exportResource")) { Text("保存 JSON") }
                    exportStatus?.let { Text(it, color = Muted) }
                }
            }
            val columns = payload.getJSONArray("columns").objects()
            items(payload.getJSONArray("rows").objects()) { row ->
                Panel(Modifier.widthIn(max = 640.dp).fillMaxWidth()) {
                    columns.forEach { column ->
                        val key = column.getString("key")
                        val cell = if (row.isNull(key)) "—" else row.getString(key)
                        Text("${column.getString("label")}：$cell")
                    }
                }
            }
        }
    }
}

@Composable
private fun ResourcePicker(label: String, value: String, options: List<ResourceOption>,
    modifier: Modifier, onSelect: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { expanded = true }, modifier = modifier.fillMaxWidth()) {
            Text("$label：$value")
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { option ->
                DropdownMenuItem(text = { Text(option.label) }, onClick = { expanded = false; onSelect(option.value) })
            }
        }
    }
}

private fun resourceTime(raw: String): String = runCatching {
    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault()).format(Instant.parse(raw))
}.getOrDefault(raw)
