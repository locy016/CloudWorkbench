package cn.xux.cloudworkbench

import android.content.SharedPreferences
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DateRange
import androidx.compose.material.icons.automirrored.outlined.List
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.io.InterruptedIOException

internal data class NewsQuery(val sourceId: String? = null, val keyword: String = "",
    val category: ResourceCategory? = null, val from: String? = null, val to: String? = null)
internal data class NewsSource(val id: String, val title: String, val category: ResourceCategory)
internal data class NewsArticle(val title: String, val publisher: String, val time: String?, val timeKind: String?,
    val summary: String?, val link: String?, val language: String?, val readerId: String?)
internal data class NewsSnapshot(val query: NewsQuery, val sources: List<NewsSource>, val articles: List<NewsArticle>,
    val matchedCount: Int, val unavailable: Set<String>, val cached: Map<String, String>, val requestedSources: Int)

internal class NewsFeed(prefs: SharedPreferences, private val connection: () -> WorkbenchConnection?) {
    val translations = NewsTranslations(prefs, connection)
    private val articleCache = LinkedHashMap<String, NewsArticleBody>()
    var snapshot by mutableStateOf<NewsSnapshot?>(null); private set
    var loading by mutableStateOf(false); private set
    var refreshError by mutableStateOf<String?>(null); private set

    suspend fun refresh(query: NewsQuery) {
        val transport = connection() ?: return
        if (loading) return
        loading = true
        refreshError = null
        val started = SystemClock.elapsedRealtime()
        try {
            val builder = Uri.Builder()
            query.sourceId?.let { builder.appendQueryParameter("source", it) }
            if (query.keyword.isNotEmpty()) builder.appendQueryParameter("q", query.keyword)
            query.category?.let { builder.appendQueryParameter("category", it.key) }
            query.from?.let { builder.appendQueryParameter("from", it) }
            query.to?.let { builder.appendQueryParameter("to", it) }
            val suffix = builder.build().encodedQuery?.let { "?$it" }.orEmpty()
            val response = readNews(transport, "/v1/server-data/news$suffix", started)
            val sources = response.getJSONArray("sources").objects().map {
                NewsSource(it.getString("id"), it.getString("title"), ResourceCategory.fromKey(it.optString("category")))
            }
            val payload = response.getJSONArray("articles")
            require(payload.length() <= 120)
            val articles = payload.objects().map {
                require(query.sourceId == null || query.sourceId == it.getString("sourceId"))
                fun optional(key: String) = it.optString(key).takeUnless { text -> it.isNull(key) || text.isBlank() }
                NewsArticle(it.getString("title"), it.getString("publisher"), optional("time"), optional("timeKind"),
                    optional("summary"), optional("link"), optional("language"),
                    optional("readerId")?.takeIf { id -> id.matches(Regex("[0-9a-f]{64}")) })
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
            if (transport === connection()) {
                refreshError = when (e) {
                    is GatewayException -> when (e.status) {
                        400 -> "资讯筛选条件无效"
                        401, 403 -> "SSH 授权未通过，请重新连接"
                        else -> "服务器暂时无法返回资讯（HTTP ${e.status}）"
                    }
                    is InterruptedIOException -> "等待服务器资讯超时"
                    is IOException -> "读取服务器资讯时连接中断"
                    is GatewayPayloadException, is JSONException, is IllegalArgumentException -> "服务器返回的资讯格式异常"
                    else -> "服务器资讯请求失败"
                }
                logReadFailure(e, started, "failed")
            }
        } finally {
            loading = false
        }
    }

    fun cachedArticle(id: String?): NewsArticleBody? = id?.let(articleCache::get)

    suspend fun readArticle(id: String): NewsArticleBody {
        articleCache[id]?.let { return it }
        val transport = connection() ?: throw IOException("SSH 连接已断开")
        val response = transport.sync("/v1/server-data/news/article?id=$id")
        check(transport === connection() && response.getString("id") == id)
        val rows = response.getJSONArray("blocks")
        require(rows.length() in 1..500)
        val blocks = rows.objects().map { row ->
            NewsBodyBlock(row.getString("kind"), row.getString("text")).also { require(it.text.isNotBlank()) }
        }
        require(blocks.sumOf { it.text.length } <= 80_000)
        val language = response.optString("language").takeUnless { response.isNull("language") || it.isBlank() }
        return NewsArticleBody(blocks, response.getBoolean("truncated"), language).also { body ->
            articleCache[id] = body
            if (articleCache.size > 12) articleCache.remove(articleCache.keys.first())
        }
    }

    private suspend fun readNews(transport: WorkbenchConnection, path: String, started: Long): JSONObject {
        try {
            return transport.sync(path)
        } catch (e: Exception) {
            val transient = e is IOException || (e is GatewayException && e.status in 502..504)
            if (!transient || SystemClock.elapsedRealtime() - started >= 8_000L ||
                transport !== connection() || !transport.connected) throw e
            logReadFailure(e, started, "retrying")
            delay(350L)
            if (transport !== connection() || !transport.connected) throw e
            return transport.sync(path)
        }
    }

    private fun logReadFailure(error: Exception, started: Long, action: String) {
        val reason = if (error is GatewayException) "HTTP ${error.status}" else error.javaClass.simpleName
        Log.w("WorkbenchNews", "News read $action ($reason, ${SystemClock.elapsedRealtime() - started} ms)")
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun NewsFeedPage(feed: NewsFeed, connected: Boolean, visible: Boolean) {
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
    var languageMenuOpen by remember { mutableStateOf(false) }
    var showOriginals by rememberSaveable { mutableStateOf(false) }
    var translationError by remember { mutableStateOf(false) }
    var translationRetry by remember { mutableIntStateOf(0) }
    var reading by remember { mutableStateOf<Pair<NewsArticle, Boolean>?>(null) }
    val listState = rememberLazyListState()
    val preferredLanguage = feed.translations.preferredLanguage
    var appliedKeyword by rememberSaveable { mutableStateOf(snapshot?.query?.keyword.orEmpty()) }
    val query = NewsQuery(sourceId, appliedKeyword, category, from, to)
    val result = snapshot?.takeIf { it.query == query }
    val articles = result?.articles.orEmpty()
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
    LaunchedEffect(visible, connected, preferredLanguage, showOriginals, articles, translationRetry, reading != null) {
        translationError = false
        if (!visible || !connected || showOriginals || articles.isEmpty() || reading != null) return@LaunchedEffect
        snapshotFlow {
            val visibleRows = listState.layoutInfo.visibleItemsInfo
            val start = ((visibleRows.firstOrNull()?.index ?: 1) - 1).coerceIn(0, articles.lastIndex)
            val end = ((visibleRows.lastOrNull()?.index ?: 1) + 2).coerceIn(start + 1, articles.size)
            articles.subList(start, end)
        }.collect { window ->
            try {
                feed.translations.translate(window, preferredLanguage)
                translationError = false
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val reason = if (e is GatewayException) "HTTP ${e.status}" else e.javaClass.simpleName
                Log.w("WorkbenchNews", "Automatic translation failed ($reason)")
                translationError = true
            }
        }
    }
    if (datesOpen) {
        NewsDateDialog(from.orEmpty(), to.orEmpty(), canSearch, onDismiss = { datesOpen = false }) { start, end ->
            datesOpen = false
            search(query.copy(from = start, to = end))
        }
    }
    reading?.let { (article, original) -> NewsArticleReader(article, feed, connected, original) { reading = null } }
    LazyColumn(
        Modifier.fillMaxSize().testTag("aggregatedNews"), horizontalAlignment = Alignment.CenterHorizontally,
        state = listState,
        contentPadding = PaddingValues(horizontal = LocalPagePadding.current, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item {
            Panel(Modifier.widthIn(max = 640.dp).fillMaxWidth()) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f, fill = false)) {
                        OutlinedButton(onClick = { languageMenuOpen = true }, modifier = Modifier.testTag("newsLanguage")) {
                            Text("译文语言 · ${newsLanguages.getValue(preferredLanguage)}")
                        }
                        DropdownMenu(expanded = languageMenuOpen, onDismissRequest = { languageMenuOpen = false }) {
                            newsLanguages.forEach { (code, label) ->
                                DropdownMenuItem(text = { Text(label) }, modifier = Modifier.testTag("newsLanguage-$code"),
                                    onClick = {
                                        languageMenuOpen = false
                                        feed.translations.setLanguage(code)
                                        showOriginals = false
                                    })
                            }
                        }
                    }
                    FilterChip(selected = showOriginals, onClick = { showOriginals = !showOriginals },
                        label = { Text("显示原文") }, modifier = Modifier.testTag("newsOriginals"))
                }
                FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
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
                val selectedSource = sources.firstOrNull { it.id == sourceId }?.title
                BoxWithConstraints(Modifier.fillMaxWidth()) {
                    val compact = maxWidth < 480.dp
                    val controlHeight = 56.dp
                    val shape = MaterialTheme.shapes.medium
                    Column {
                        Row(Modifier.fillMaxWidth().testTag("newsSearchToolbar"),
                            horizontalArrangement = Arrangement.spacedBy(if (compact) 0.dp else 8.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            OutlinedTextField(keyword, { keyword = it.take(100) }, placeholder = { Text("搜索资讯", maxLines = 1) },
                                modifier = Modifier.weight(1f).height(controlHeight).testTag("newsKeyword")
                                    .semantics { contentDescription = "搜索资讯" }, singleLine = true, shape = shape,
                                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                                keyboardActions = KeyboardActions(onSearch = { search() }))
                            if (compact) IconButton(onClick = { search() }, enabled = canSearch,
                                modifier = Modifier.height(controlHeight).testTag("searchNews")) { Icon(Icons.Outlined.Search, "查询") }
                            else Button(onClick = { search() }, enabled = canSearch,
                                modifier = Modifier.height(controlHeight).testTag("searchNews"), shape = shape) { Text("查询", maxLines = 1) }

                            Box {
                                val sourceLabel = selectedSource ?: "全部来源"
                                if (compact) IconButton(onClick = { sourcesOpen = true }, enabled = canSearch && sources.isNotEmpty(),
                                    modifier = Modifier.height(controlHeight).testTag("pickNewsSource")) {
                                    Icon(Icons.AutoMirrored.Outlined.List, sourceLabel)
                                }
                                else OutlinedButton(onClick = { sourcesOpen = true }, enabled = canSearch && sources.isNotEmpty(),
                                    modifier = Modifier.height(controlHeight).widthIn(max = 160.dp).testTag("pickNewsSource"),
                                    shape = shape,
                                    contentPadding = PaddingValues(horizontal = 12.dp)) {
                                    Text(sourceLabel, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                                DropdownMenu(expanded = sourcesOpen, onDismissRequest = { sourcesOpen = false }) {
                                    DropdownMenuItem(text = { Text("全部来源") }, modifier = Modifier.testTag("newsAllSources"), onClick = {
                                        sourcesOpen = false
                                        search(query.copy(sourceId = null))
                                    })
                                    sources.forEach { source ->
                                        DropdownMenuItem(text = { Text(source.title) }, modifier = Modifier.testTag("newsSource-${source.id}"),
                                            onClick = { sourcesOpen = false; search(query.copy(sourceId = source.id)) })
                                    }
                                }
                            }
                            if (compact) IconButton(onClick = { datesOpen = true }, enabled = canSearch,
                                modifier = Modifier.height(controlHeight).testTag("pickNewsDates")) { Icon(Icons.Outlined.DateRange, "发布时间") }
                            else OutlinedButton(onClick = { datesOpen = true }, enabled = canSearch,
                                modifier = Modifier.height(controlHeight).testTag("pickNewsDates"), shape = shape) {
                                Text("发布时间", maxLines = 1)
                            }
                        }
                        if (compact && selectedSource != null) Text(selectedSource, color = Muted,
                            style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
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
                if (translationError && !showOriginals && connected) {
                    TextButton(onClick = { translationRetry++ }) { Text("部分资讯翻译未完成，重试翻译") }
                }
                if (result != null && result.articles.isNotEmpty()) {
                    val count = result.matchedCount
                    Text(if (count > result.articles.size) "显示 ${result.articles.size} / $count 条" else "$count 条资讯",
                        color = Muted, style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("newsCount"))
                }
            }
        }
        items(articles) { item ->
            var showOriginal by rememberSaveable(item.title, item.summary, item.link, preferredLanguage) { mutableStateOf(false) }
            val translation = feed.translations.get(item, preferredLanguage)
            val translated = translation?.takeUnless { showOriginals || showOriginal }
            Panel(Modifier.widthIn(max = 640.dp).fillMaxWidth().testTag("newsArticle")) {
                if (translated != null) Text("机器译文", Modifier.testTag("newsTranslated"), color = Muted,
                    style = MaterialTheme.typography.labelSmall)
                FeedArticleCard(translated?.title ?: item.title, item.publisher, item.time,
                    translated?.summary?.takeIf { it.isNotBlank() } ?: item.summary,
                    timeLabel = if (item.timeKind == "updated") "更新" else "发布",
                    titleTag = "newsHeadline")
                Row(Modifier.fillMaxWidth().testTag("newsArticleFooter"), verticalAlignment = Alignment.CenterVertically) {
                    val actionSize = Modifier.height(48.dp)
                    item.link?.let { CopyFeedLink(it, "newsHeadlineLink", modifier = actionSize) }
                    if (translation != null && !showOriginals) {
                        TextButton(onClick = { showOriginal = !showOriginal }, modifier = actionSize.testTag("toggleNewsOriginal"),
                            contentPadding = PaddingValues(horizontal = 8.dp)) {
                            Text(if (showOriginal) "查看译文" else "查看原文", maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    if (item.link != null) {
                        TextButton(onClick = { reading = item to (showOriginals || showOriginal) },
                            modifier = actionSize.testTag("readNewsArticle"),
                            contentPadding = PaddingValues(horizontal = 8.dp)) {
                            Text("阅读全文", maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
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
