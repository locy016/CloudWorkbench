package cn.xux.cloudworkbench

import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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

internal data class NewsQuery(val sourceId: String? = null, val keyword: String = "")
internal data class NewsSource(val id: String, val title: String)
internal data class NewsArticle(val title: String, val publisher: String, val time: String?, val timeKind: String?,
    val summary: String?, val link: String?)
internal data class NewsSnapshot(val query: NewsQuery, val sources: List<NewsSource>, val articles: List<NewsArticle>,
    val matchedCount: Int, val unavailable: Set<String>)

internal class NewsFeed(private val connection: () -> WorkbenchConnection?) {
    var snapshot by mutableStateOf<NewsSnapshot?>(null); private set
    var loading by mutableStateOf(false); private set
    var refreshFailed by mutableStateOf(false); private set

    suspend fun refresh(query: NewsQuery) {
        val transport = connection() ?: return
        if (loading) return
        loading = true
        refreshFailed = false
        try {
            val builder = Uri.Builder()
            query.sourceId?.let { builder.appendQueryParameter("source", it) }
            if (query.keyword.isNotEmpty()) builder.appendQueryParameter("q", query.keyword)
            val suffix = builder.build().encodedQuery?.let { "?$it" }.orEmpty()
            val response = transport.sync("/v1/server-data/news$suffix")
            val sources = response.getJSONArray("sources").objects().map {
                NewsSource(it.getString("id"), it.getString("title"))
            }
            val payload = response.getJSONArray("articles")
            require(payload.length() <= 120)
            val articles = payload.objects().map {
                require(query.sourceId == null || query.sourceId == it.getString("sourceId"))
                fun optional(key: String) = it.optString(key).takeUnless { text -> it.isNull(key) || text.isBlank() }
                NewsArticle(it.getString("title"), it.getString("publisher"), optional("time"), optional("timeKind"),
                    optional("summary"), optional("link"))
            }
            val unavailable = response.getJSONArray("unavailable")
            if (transport === connection()) {
                snapshot = NewsSnapshot(query, sources, articles, response.getInt("matchedCount"),
                    (0 until unavailable.length()).map(unavailable::getString).toSet())
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            if (transport === connection()) refreshFailed = true
        } finally {
            loading = false
        }
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
    var sourcesOpen by remember { mutableStateOf(false) }
    var appliedKeyword by rememberSaveable { mutableStateOf(snapshot?.query?.keyword.orEmpty()) }
    val query = NewsQuery(sourceId, appliedKeyword)
    val result = snapshot?.takeIf { it.query == query }
    val sources = snapshot?.sources.orEmpty()
    fun search(source: String? = sourceId) {
        if (!connected || feed.loading) return
        focus.clearFocus()
        sourceId = source
        appliedKeyword = keyword.trim()
        scope.launch { feed.refresh(NewsQuery(source, appliedKeyword)) }
    }
    LaunchedEffect(visible, connected) {
        if (visible && connected) feed.refresh(query)
    }
    LazyColumn(
        Modifier.fillMaxSize().testTag("aggregatedNews"), horizontalAlignment = Alignment.CenterHorizontally,
        contentPadding = PaddingValues(horizontal = LocalPagePadding.current, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item {
            Panel(Modifier.widthIn(max = 640.dp).fillMaxWidth()) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(keyword, { keyword = it.take(100) }, label = { Text("搜索资讯") },
                        modifier = Modifier.weight(1f).testTag("newsKeyword"), singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = { search() }))
                    Button(onClick = { search() }, enabled = connected && !feed.loading,
                        modifier = Modifier.testTag("searchNews")) { Text("查询") }
                }
                Box {
                    OutlinedButton(onClick = { sourcesOpen = true }, enabled = connected && !feed.loading && sources.isNotEmpty(),
                        modifier = Modifier.testTag("pickNewsSource")) {
                        Text(sources.firstOrNull { it.id == sourceId }?.title ?: "全部来源")
                    }
                    DropdownMenu(expanded = sourcesOpen, onDismissRequest = { sourcesOpen = false }) {
                        DropdownMenuItem(text = { Text("全部来源") }, onClick = { sourcesOpen = false; search(null) })
                        sources.forEach { source ->
                            DropdownMenuItem(text = { Text(source.title) }, modifier = Modifier.testTag("newsSource-${source.id}"),
                                onClick = { sourcesOpen = false; search(source.id) })
                        }
                    }
                }
                if (feed.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
                val message = when {
                    !connected && result != null -> "SSH 未连接，显示上次资讯"
                    !connected -> "连接云端后可获取资讯"
                    feed.refreshFailed && result != null -> "更新失败，显示上次资讯"
                    feed.refreshFailed -> "暂时无法获取资讯，请重试"
                    result != null && result.unavailable.isNotEmpty() -> {
                        val names = result.sources.filter { it.id in result.unavailable }.joinToString("、") { it.title }
                        "暂不可用：$names"
                    }
                    result?.articles?.isEmpty() == true -> "没有符合条件的资讯"
                    else -> null
                }
                message?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = Amber) }
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
            }
        }
    }
}
