package cn.xux.cloudworkbench

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

internal data class ResourceOption(val value: String, val label: String)
internal data class ResourceFilter(val key: String, val label: String, val options: List<ResourceOption>, val maxLength: Int?)
internal enum class ResourceCategory(val key: String, val label: String) {
    Headlines("headlines", "要闻"), Current("current", "时事"), Trade("trade", "经贸"),
    Society("society", "社会"), Humanities("humanities", "人文"), Military("military", "军事"),
    Science("science", "科技"), Health("health", "健康"),
    Diplomacy("diplomacy", "外交"), Environment("environment", "环境"), Education("education", "教育"),
    Energy("energy", "能源"), Transport("transport", "交通"),
    Logistics("logistics", "物流"), Other("other", "其他");

    val isNews get() = this != Logistics && this != Other

    companion object {
        fun fromKey(key: String) = entries.firstOrNull { it.key == key } ?: Other
    }
}

internal data class ResourceSource(val id: String, val title: String, val publisher: String, val filters: List<ResourceFilter>,
    val format: String, val searchable: Boolean, val dateFilter: Boolean, val fullDownload: Boolean,
    val category: ResourceCategory)
internal data class ResourceResult(val source: String, val filters: Map<String, String>, val payload: JSONObject)
internal enum class ResourceExportFormat(val extension: String) { Json("json"), Csv("csv") }

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
            ResourceSource(id, source.getString("title"), source.getString("publisher"), filters,
                source.optString("format", "json"), source.optBoolean("searchable"), source.optBoolean("dateFilter"),
                source.optBoolean("fullDownload"), ResourceCategory.fromKey(source.optString("category")))
        }
        if (transport === connection()) sources = list
    }

    suspend fun download(source: ResourceSource, choices: Map<String, String>) =
        operation("获取失败，请稍后重试") { transport ->
            val query = Uri.Builder()
            choices.forEach { (key, value) -> query.appendQueryParameter(key, value) }
            if (source.fullDownload) query.appendQueryParameter("export", "1")
            val path = "/v1/server-data/resources/${source.id}" + query.build().encodedQuery?.let { "?$it" }.orEmpty()
            val payload = transport.sync(path)
            require(payload.getString("id") == source.id && payload.getJSONArray("rows").length() <= 1000)
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

    fun prepareExport(result: ResourceResult, format: ResourceExportFormat = ResourceExportFormat.Json) {
        pendingExport = when (format) {
            ResourceExportFormat.Json -> result.payload.toString(2)
            ResourceExportFormat.Csv -> resourceCsv(result.payload)
        }
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
    var categoryKey by rememberSaveable { mutableStateOf(ResourceCategory.Logistics.key) }
    var sourceId by rememberSaveable { mutableStateOf("") }
    var exportStatus by rememberSaveable { mutableStateOf<String?>(null) }
    val categories = (listOf(ResourceCategory.Logistics, ResourceCategory.Other) + ResourceCategory.entries.filter { it.isNews })
        .filter { category -> data.sources.any { it.category == category } }
    val category = categories.find { it.key == categoryKey } ?: categories.firstOrNull()
    val sourcesInCategory = data.sources.filter { it.category == category }
    val source = sourcesInCategory.find { it.id == sourceId } ?: sourcesInCategory.firstOrNull()
    var filtersOpen by remember(source) { mutableStateOf(source?.category?.isNews != true) }
    val selections = remember(source) { mutableStateMapOf<String, String>() }
    var keyword by remember(source) { mutableStateOf("") }
    var fromText by remember(source) { mutableStateOf("") }
    var toText by remember(source) { mutableStateOf("") }
    val from = filterDate(fromText)
    val to = filterDate(toText)
    val rangeError = (fromText.isNotEmpty() && from == null) || (toText.isNotEmpty() && to == null) ||
        (from != null && to != null && from > to)
    val choices = buildMap {
        source?.filters.orEmpty().forEach {
            put(it.key, (selections[it.key] ?: it.options.firstOrNull()?.value.orEmpty()).trim())
        }
        if (source?.searchable == true && keyword.isNotBlank()) put("q", keyword.trim())
        if (source?.dateFilter == true) {
            from?.let { put("from", it.toString()) }
            to?.let { put("to", it.toString()) }
        }
    }
    val result = data.result?.takeIf { it.source == source?.id && it.filters == choices }
    val completeExport: (Uri?) -> Unit = { uri ->
        scope.launch {
            try {
                if (data.saveExport(context, uri)) exportStatus = "已保存文件"
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                exportStatus = "保存失败，请重试"
            }
        }
    }
    val jsonDocument = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json"), completeExport)
    val csvDocument = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv"), completeExport)
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
                if (categories.isNotEmpty()) {
                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        categories.forEach { option ->
                            FilterChip(selected = category == option, onClick = {
                                categoryKey = option.key
                                sourceId = ""
                                exportStatus = null
                            }, label = { Text(option.label) }, modifier = Modifier.testTag("resourceCategory-${option.key}"))
                        }
                    }
                }
                if (source != null) {
                    ResourcePicker("来源", source.title, sourcesInCategory.map { ResourceOption(it.id, it.title) },
                        Modifier.testTag("resourcePicker")) { sourceId = it }
                    Text("来源：${source.publisher} · ${source.format.uppercase()}", color = Muted)
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
                    if (source.category.isNews && (source.searchable || source.dateFilter)) {
                        TextButton(onClick = { filtersOpen = !filtersOpen }, modifier = Modifier.testTag("toggleResourceFilters")) {
                            Text(if (filtersOpen) "收起筛选" else "筛选条件")
                        }
                    }
                    if (source.searchable && filtersOpen) {
                        OutlinedTextField(keyword, { keyword = it.take(100) },
                            modifier = Modifier.fillMaxWidth().testTag("resourceKeyword"),
                            label = { Text("关键词（可选）") }, singleLine = true)
                    }
                    if (source.dateFilter && filtersOpen) {
                        Text("日期范围（UTC，可选）", color = Muted, style = MaterialTheme.typography.bodySmall)
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            ResourceDateField(fromText, { fromText = it }, "开始日期", Modifier.weight(1f).testTag("resourceFrom"))
                            ResourceDateField(toText, { toText = it }, "结束日期", Modifier.weight(1f).testTag("resourceTo"))
                        }
                        if (rangeError) Text("请输入有效的 YYYY-MM-DD，开始日期不得晚于结束日期",
                            color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = {
                            exportStatus = null
                            scope.launch { data.download(source, choices) }
                        }, enabled = connected && !data.loading && !rangeError && choices.values.none { it.isBlank() },
                            modifier = Modifier.testTag("fetchResource")) { Text("查询数据") }
                        TextButton(onClick = { scope.launch { data.refreshCatalog() } }, enabled = connected && !data.loading) {
                            Text("刷新目录")
                        }
                    }
                } else {
                    TextButton(onClick = { scope.launch { data.refreshCatalog() } }, enabled = connected && !data.loading) {
                        Text("刷新目录")
                    }
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
            val rows = payload.getJSONArray("rows").objects()
            val previewRows = rows.take(payload.optInt("previewLimit", 100).coerceIn(1, 100))
            val matched = payload.optInt("matchedCount", rows.size)
            item {
                Panel(Modifier.widthIn(max = 640.dp).fillMaxWidth().testTag("resourceResult")) {
                    Text("获取时间：${resourceTime(payload.getString("fetchedAt"))}", color = Muted)
                    if (!payload.isNull("updatedAt")) {
                        Text("来源更新时间：${resourceTime(payload.getString("updatedAt"))}", color = Muted)
                    }
                    Text(if (matched == 0) "该条件下暂无记录" else "匹配 $matched 条 · 预览 ${previewRows.size} 条 · 可保存 ${rows.size} 条",
                        Modifier.testTag("resourceCounts"))
                    val base = "${result.source}-${payload.getString("fetchedAt").take(10)}"
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = {
                            data.prepareExport(result)
                            jsonDocument.launch("$base.json")
                        }, modifier = Modifier.testTag("exportResource")) { Text("保存 JSON") }
                        OutlinedButton(onClick = {
                            data.prepareExport(result, ResourceExportFormat.Csv)
                            csvDocument.launch("$base.csv")
                        }, modifier = Modifier.testTag("exportResourceCsv")) { Text("保存 CSV") }
                    }
                    exportStatus?.let { Text(it, color = Muted) }
                }
            }
            val columns = payload.getJSONArray("columns").objects()
            items(previewRows) { row ->
                Panel(Modifier.widthIn(max = 640.dp).fillMaxWidth()) {
                    if (source?.category?.isNews == true && source.format in listOf("rss", "atom")) {
                        ResourceHeadline(row, source.publisher)
                    } else {
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
}

@Composable
private fun ResourceHeadline(row: JSONObject, publisher: String) {
    fun text(key: String) = row.optString(key).takeUnless { row.isNull(key) || it.isBlank() }
    FeedArticleCard(text("headline") ?: "未提供标题", publisher, text("published"), text("summary"),
        titleTag = "resourceHeadline")
    text("link")?.let { CopyFeedLink(it, "resourceHeadlineLink", "复制原文地址") }
}

private val feedTime = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneOffset.UTC)

@Composable
internal fun FeedArticleCard(title: String, publisher: String, time: String?, summary: String?,
    titleTag: String, timeLabel: String? = null) {
    Text(title, Modifier.testTag(titleTag), style = MaterialTheme.typography.titleMedium)
    val date = time?.let { runCatching { feedTime.format(Instant.parse(it)) + " UTC" }.getOrNull() }
    val timeText = when {
        date != null && timeLabel != null -> "$timeLabel $date"
        date != null -> date
        timeLabel != null -> "未提供发布时间"
        else -> null
    }
    Text(listOfNotNull(publisher, timeText).joinToString(" · "), color = Muted,
        style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("${titleTag}Metadata"))
    summary?.let {
        Text(it, Modifier.testTag("${titleTag}Summary"), maxLines = 4, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
internal fun CopyFeedLink(link: String, tag: String, label: String = "复制链接", modifier: Modifier = Modifier) {
    val context = LocalContext.current
    TextButton(onClick = {
        context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("原文地址", link))
    }, modifier = modifier.testTag(tag), contentPadding = PaddingValues(horizontal = 8.dp)) {
        Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
internal fun ResourceDateField(value: String, onChange: (String) -> Unit, label: String, modifier: Modifier) {
    OutlinedTextField(value, { onChange(it.filter { ch -> ch in '0'..'9' || ch == '-' }.take(10)) },
        modifier = modifier, label = { Text(label) }, placeholder = { Text("YYYY-MM-DD") }, singleLine = true)
}

internal fun filterDate(value: String): LocalDate? = if (value.length == 10) {
    runCatching { LocalDate.parse(value) }.getOrNull()
} else null

internal fun resourceCsv(payload: JSONObject): String {
    val columns = payload.getJSONArray("columns").objects()
    fun line(values: List<String>) = values.joinToString(",", transform = ::csvCell)
    return buildString {
        append('\uFEFF')
        append(line(listOf("来源", "来源网址", "获取时间（UTC）") + columns.map { it.getString("label") }))
        append("\r\n")
        for (row in payload.getJSONArray("rows").objects()) {
            append(line(listOf(payload.optString("publisher"), payload.optString("sourceUrl"), payload.getString("fetchedAt")) +
                columns.map { val key = it.getString("key"); if (row.isNull(key)) "" else row.getString(key) }))
            append("\r\n")
        }
    }
}

private val plainCsvNumber = Regex("""[-+]?\d+(?:\.\d+)?(?:[eE][-+]?\d+)?""")

private fun csvCell(value: String): String {
    val trimmed = value.trimStart()
    val formula = trimmed.firstOrNull() in listOf('=', '+', '-', '@') && !plainCsvNumber.matches(trimmed)
    val prefix = if (formula) "'" else ""
    return "\"" + prefix + value.replace("\"", "\"\"") + "\""
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
