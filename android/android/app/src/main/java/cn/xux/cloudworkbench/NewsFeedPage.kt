package cn.xux.cloudworkbench

import android.net.Uri
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.json.JSONObject

internal data class NewsQuery(val sourceId: String? = null, val keyword: String = "",
    val category: ResourceCategory? = null, val from: String? = null, val to: String? = null)
internal data class NewsSource(val id: String, val title: String, val category: ResourceCategory)
internal data class NewsArticle(val title: String, val publisher: String, val time: String?, val timeKind: String?,
    val summary: String?, val link: String?, val language: String?)
internal data class NewsSnapshot(val query: NewsQuery, val sources: List<NewsSource>, val articles: List<NewsArticle>,
    val matchedCount: Int, val unavailable: Set<String>, val cached: Map<String, String>, val requestedSources: Int)

internal data class NewsTranslation(val title: String, val summary: String)

internal class NewsFeed(private val connection: () -> WorkbenchConnection?) {
    var snapshot by mutableStateOf<NewsSnapshot?>(null); private set
    var loading by mutableStateOf(false); private set
    var refreshError by mutableStateOf<String?>(null); private set

    suspend fun refresh(query: NewsQuery) {
        val transport = connection() ?: return
        if (loading) return
        loading = true
        refreshError = null
        try {
            val builder = Uri.Builder()
            query.sourceId?.let { builder.appendQueryParameter("source", it) }
            if (query.keyword.isNotEmpty()) builder.appendQueryParameter("q", query.keyword)
            query.category?.let { builder.appendQueryParameter("category", it.key) }
            query.from?.let { builder.appendQueryParameter("from", it) }
            query.to?.let { builder.appendQueryParameter("to", it) }
            val suffix = builder.build().encodedQuery?.let { "?$it" }.orEmpty()
            val response = transport.sync("/v1/server-data/news$suffix")
            val sources = response.getJSONArray("sources").objects().map {
                NewsSource(it.getString("id"), it.getString("title"), ResourceCategory.fromKey(it.optString("category")))
            }
            val payload = response.getJSONArray("articles")
            require(payload.length() <= 120)
            val articles = payload.objects().map {
                require(query.sourceId == null || query.sourceId == it.getString("sourceId"))
                fun optional(key: String) = it.optString(key).takeUnless { text -> it.isNull(key) || text.isBlank() }
                NewsArticle(it.getString("title"), it.getString("publisher"), optional("time"), optional("timeKind"),
                    optional("summary"), optional("link"), optional("language"))
            }
            val unavailable = response.getJSONArray("unavailable")
            val cached = response.optJSONArray("cached")?.objects()?.associate {
                it.getString("id") to it.getString("fetchedAt")
            }.orEmpty()
            val requestedSources = response.optInt("requestedSourceCount", sources.count {
                (query.sourceId == null || it.id == query.sourceId) && (query.category == null || it.category == query.category)
            })
            if (transport === connection()) {
                if (requestedSources > 0 && unavailable.length() == requestedSources && snapshot?.query == query) {
                    refreshError = "本次查询的资讯源均未响应"
                } else {
                    snapshot = NewsSnapshot(query, sources, articles, response.getInt("matchedCount"),
                        (0 until unavailable.length()).map(unavailable::getString).toSet(), cached, requestedSources)
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (transport === connection()) refreshError = when ((e as? GatewayException)?.status) {
                401, 403 -> "SSH 授权未通过，请重新连接"
                else -> "服务器资讯请求失败"
            }
        } finally {
            loading = false
        }
    }

    suspend fun translate(article: NewsArticle, target: String): NewsTranslation {
        val transport = connection() ?: error("未连接")
        val body = JSONObject().put("source", article.language).put("target", target)
            .put("title", article.title).put("summary", article.summary.orEmpty())
        val response = transport.request("/v1/server-data/news/translate", body)
        check(transport === connection() && response.getString("target") == target)
        return NewsTranslation(response.getString("title"), response.getString("summary"))
    }
}

@Composable
internal fun NewsFeedPage(feed: NewsFeed, connected: Boolean, visible: Boolean) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val focus = LocalFocusManager.current
    val snapshot = feed.snapshot
    var keyword by rememberSaveable { mutableStateOf(snapshot?.query?.keyword.orEmpty()) }
    var sourceId by rememberSaveable { mutableStateOf(snapshot?.query?.sourceId) }
    var category by rememberSaveable { mutableStateOf(snapshot?.query?.category) }
    var from by rememberSaveable { mutableStateOf(snapshot?.query?.from) }
    var to by rememberSaveable { mutableStateOf(snapshot?.query?.to) }
    var sourcesOpen by remember { mutableStateOf(false) }
    var datesOpen by rememberSaveable { mutableStateOf(false) }
    var translatingArticle by remember { mutableStateOf<NewsArticle?>(null) }
    var preferredLanguage by rememberSaveable { mutableStateOf("zh") }
    var appliedKeyword by rememberSaveable { mutableStateOf(snapshot?.query?.keyword.orEmpty()) }
    val query = NewsQuery(sourceId, appliedKeyword, category, from, to)
    val result = snapshot?.takeIf { it.query == query }
    val sources = snapshot?.sources.orEmpty().filter { category == null || it.category == category }
    val canSearch = connected && !feed.loading
    fun search(next: NewsQuery = query) {
        if (!canSearch) return
        focus.clearFocus()
        val requested = next.copy(keyword = keyword.trim())
        sourceId = requested.sourceId
        category = requested.category
        from = requested.from
        to = requested.to
        appliedKeyword = requested.keyword
        scope.launch { feed.refresh(requested) }
    }
    LaunchedEffect(visible, connected) {
        if (visible && connected) feed.refresh(query)
    }
    if (datesOpen) {
        NewsDateDialog(from.orEmpty(), to.orEmpty(), canSearch, onDismiss = { datesOpen = false }) { start, end ->
            datesOpen = false
            search(query.copy(from = start, to = end))
        }
    }
    translatingArticle?.let { article ->
        NewsTranslationDialog(article, feed, connected, preferredLanguage, { preferredLanguage = it }) {
            translatingArticle = null
        }
    }
    LazyColumn(
        Modifier.fillMaxSize().testTag("aggregatedNews"), horizontalAlignment = Alignment.CenterHorizontally,
        contentPadding = PaddingValues(horizontal = LocalPagePadding.current, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item {
            Panel(Modifier.widthIn(max = 640.dp).fillMaxWidth()) {
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    val categories = listOf(null) + ResourceCategory.entries.filter { it.isNews }
                    categories.forEach { option ->
                        FilterChip(selected = option == category, enabled = canSearch, onClick = {
                            val retainedSource = sourceId?.takeIf { id ->
                                option == null || snapshot?.sources?.any { it.id == id && it.category == option } == true
                            }
                            search(query.copy(category = option, sourceId = retainedSource))
                        }, label = { Text(option?.label ?: "全部") },
                            modifier = Modifier.testTag("newsCategory-${option?.key ?: "all"}"))
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(keyword, { keyword = it.take(100) }, label = { Text("搜索资讯") },
                        modifier = Modifier.weight(1f).testTag("newsKeyword"), singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = { search() }))
                    Button(onClick = { search() }, enabled = canSearch,
                        modifier = Modifier.testTag("searchNews")) { Text("查询") }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f, fill = false)) {
                        OutlinedButton(onClick = { sourcesOpen = true }, enabled = canSearch && sources.isNotEmpty(),
                            modifier = Modifier.testTag("pickNewsSource")) {
                            Text(sources.firstOrNull { it.id == sourceId }?.title ?: "全部来源")
                        }
                        DropdownMenu(expanded = sourcesOpen, onDismissRequest = { sourcesOpen = false }) {
                            DropdownMenuItem(text = { Text("全部来源") }, onClick = {
                                sourcesOpen = false
                                search(query.copy(sourceId = null))
                            })
                            sources.forEach { source ->
                                DropdownMenuItem(text = { Text(source.title) }, modifier = Modifier.testTag("newsSource-${source.id}"),
                                    onClick = { sourcesOpen = false; search(query.copy(sourceId = source.id)) })
                            }
                        }
                    }
                    OutlinedButton(onClick = { datesOpen = true }, enabled = canSearch,
                        modifier = Modifier.testTag("pickNewsDates")) { Text("发布时间") }
                }
                val dateLabel = when {
                    from != null && to != null -> "$from 至 $to（UTC）"
                    from != null -> "$from 起（UTC）"
                    to != null -> "截至 $to（UTC）"
                    else -> null
                }
                dateLabel?.let { Text(it, color = Muted, style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.testTag("newsDates")) }
                if (feed.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
                val message = when {
                    !connected && result != null -> "SSH 未连接，显示上次资讯"
                    !connected -> "连接云端后可获取资讯"
                    feed.refreshError != null -> feed.refreshError + if (result?.articles?.isNotEmpty() == true) "，显示上次资讯" else ""
                    result?.articles?.isEmpty() == true && result.unavailable.isEmpty() && result.cached.isEmpty() -> "没有符合条件的资讯"
                    else -> null
                }
                message?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = Amber) }
                if (connected && feed.refreshError == null && result != null) {
                    if (result.unavailable.isNotEmpty()) {
                        val names = result.sources.filter { it.id in result.unavailable }.joinToString("、") { it.title }
                        val prefix = if (result.unavailable.size == result.requestedSources) "资讯源均未响应" else "部分资讯源未响应"
                        Text("$prefix · $names", style = MaterialTheme.typography.bodySmall, color = Amber)
                    }
                    if (result.cached.isNotEmpty()) {
                        val names = result.sources.filter { it.id in result.cached }.joinToString("、") { it.title }
                        Text("显示服务器上次采集的资讯 · $names", style = MaterialTheme.typography.bodySmall, color = Amber)
                    }
                }
                if (connected && (feed.refreshError != null || result?.unavailable?.isNotEmpty() == true || result?.cached?.isNotEmpty() == true)) {
                    TextButton(onClick = { search() }, enabled = canSearch, modifier = Modifier.testTag("retryNews")) { Text("重试") }
                }
                if (result != null && result.articles.isNotEmpty()) {
                    val count = result.matchedCount
                    Text(if (count > result.articles.size) "显示 ${result.articles.size} / $count 条" else "$count 条资讯",
                        color = Muted, style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("newsCount"))
                }
            }
        }
        items(result?.articles.orEmpty()) { item ->
            Panel(Modifier.widthIn(max = 640.dp).fillMaxWidth().testTag("newsArticle")) {
                FeedArticleCard(item.title, item.publisher, item.time, item.summary, item.link, context,
                    timeLabel = if (item.timeKind == "updated") "更新" else "发布",
                    titleTag = "newsHeadline")
                if (item.language == "en") {
                    TextButton(onClick = { translatingArticle = item }, enabled = connected,
                        modifier = Modifier.testTag("translateNewsArticle")) { Text("翻译") }
                }
            }
        }
    }
}

@Composable
private fun NewsDateDialog(initialFrom: String, initialTo: String, canSearch: Boolean, onDismiss: () -> Unit,
    onApply: (String?, String?) -> Unit) {
    var fromText by rememberSaveable { mutableStateOf(initialFrom) }
    var toText by rememberSaveable { mutableStateOf(initialTo) }
    val from = filterDate(fromText)
    val to = filterDate(toText)
    val invalid = (fromText.isNotEmpty() && from == null) || (toText.isNotEmpty() && to == null) ||
        (from != null && to != null && from > to)
    AlertDialog(onDismissRequest = onDismiss, title = { Text("发布时间范围") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("按 UTC 日期筛选，包含开始和结束当天。可只填一端。")
            ResourceDateField(fromText, { fromText = it }, "开始日期", Modifier.fillMaxWidth().testTag("newsFrom"))
            ResourceDateField(toText, { toText = it }, "结束日期", Modifier.fillMaxWidth().testTag("newsTo"))
            if (invalid) Text("请输入有效的 YYYY-MM-DD，开始日期不得晚于结束日期",
                color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
    }, confirmButton = {
        TextButton(onClick = { onApply(from?.toString(), to?.toString()) }, enabled = canSearch && !invalid,
            modifier = Modifier.testTag("applyNewsDates")) { Text("应用") }
    }, dismissButton = {
        Row {
            TextButton(onClick = onDismiss) { Text("取消") }
            TextButton(onClick = { onApply(null, null) }, enabled = canSearch,
                modifier = Modifier.testTag("clearNewsDates")) { Text("清除日期") }
        }
    })
}
