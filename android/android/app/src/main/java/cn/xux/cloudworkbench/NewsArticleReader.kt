package cn.xux.cloudworkbench

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.CancellationException

internal data class NewsBodyBlock(val kind: String, val text: String)
internal data class NewsArticleBody(val blocks: List<NewsBodyBlock>, val truncated: Boolean, val language: String?)

@Composable
internal fun NewsArticleReader(article: NewsArticle, feed: NewsFeed, connected: Boolean, initialOriginal: Boolean,
    onClose: () -> Unit) {
    val id = article.readerId
    var body by remember(article) { mutableStateOf(feed.cachedArticle(id)) }
    var error by remember(article) { mutableStateOf<String?>(null) }
    var retry by remember { mutableIntStateOf(0) }
    var showOriginal by remember(article) { mutableStateOf(initialOriginal) }
    var translationError by remember(article) { mutableStateOf(false) }
    var translationRetry by remember { mutableIntStateOf(0) }
    val listState = rememberLazyListState()
    val target = feed.translations.preferredLanguage
    val content = body?.blocks.orEmpty()
    val canTranslate = (body?.language ?: article.language) == "en"
    val titleChunks = remember(article.title) { newsParagraphChunks(article.title) }
    LaunchedEffect(id, connected, retry) {
        if (body != null) return@LaunchedEffect
        error = when {
            id == null -> "此来源的正文可在原网页阅读"
            !connected -> "连接云端后可读取正文"
            else -> {
                try {
                    body = feed.readArticle(id)
                    null
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    when ((e as? GatewayException)?.status) {
                        404 -> "请刷新资讯后重新打开正文"
                        429 -> "正文请求较多，请稍后重试"
                        401, 403 -> "SSH 授权未通过，请重新连接"
                        else -> "暂时无法读取正文，可打开原网页"
                    }
                }
            }
        }
    }
    LaunchedEffect(content, connected, showOriginal, canTranslate, target, translationRetry) {
        translationError = false
        if (!connected || showOriginal || !canTranslate || content.isEmpty()) return@LaunchedEffect
        snapshotFlow {
            val visible = listState.layoutInfo.visibleItemsInfo
            val start = ((visible.firstOrNull()?.index ?: 1) - 1).coerceIn(0, content.lastIndex)
            val until = ((visible.lastOrNull()?.index ?: 1) + 1).coerceIn(start + 1, content.size)
            start until until
        }.collect { range ->
            try {
                val chunks = range.flatMap { index -> newsParagraphChunks(content[index].text) }
                val title = if (range.first == 0 && feed.translations.get(article, target) == null) titleChunks else emptyList()
                feed.translations.translateParagraphs(title + chunks, target)
                translationError = false
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                translationError = true
            }
        }
    }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize().testTag("newsReader"), color = MaterialTheme.colorScheme.surface) {
            Column {
                Row(Modifier.fillMaxWidth().padding(end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "返回资讯") }
                    Text("资讯正文",
                        Modifier.weight(1f), style = MaterialTheme.typography.titleLarge, maxLines = 1)
                    if (canTranslate) TextButton(onClick = { showOriginal = !showOriginal },
                        modifier = Modifier.testTag("toggleNewsReaderLanguage")) {
                        Text(if (showOriginal) "查看译文" else "查看原文", maxLines = 1)
                    }
                    article.link?.let { OpenNewsOriginalLink(it) }
                }
                HorizontalDivider(color = Outline)
                LazyColumn(Modifier.weight(1f).fillMaxWidth().testTag("newsReaderContent"),
                    state = listState,
                    horizontalAlignment = Alignment.CenterHorizontally,
                    contentPadding = PaddingValues(horizontal = LocalPagePadding.current, vertical = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    item {
                        Column(Modifier.widthIn(max = 720.dp).fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            val title = if (canTranslate && !showOriginal) feed.translations.get(article, target)?.title
                                ?: feed.translations.paragraph(titleChunks, target) else null
                            Text(title ?: article.title, Modifier.testTag("newsReaderTitle"), style = MaterialTheme.typography.headlineSmall)
                            val date = article.time?.take(16)?.replace('T', ' ')?.let { "$it UTC" }
                            Text(listOfNotNull(article.publisher, date).joinToString(" · "), color = Muted)
                            if (body != null && canTranslate && !showOriginal) {
                                Text("机器翻译 · ${newsLanguages.getValue(target)}，译文随阅读加载", color = Muted,
                                    style = MaterialTheme.typography.bodySmall)
                                if (translationError && connected) TextButton(onClick = { translationRetry++ }) { Text("重试翻译") }
                            }
                            error?.let { message ->
                                Text(message, Modifier.testTag("newsReaderError"), color = Amber)
                                if (id != null && connected) TextButton(onClick = { error = null; retry++ }) { Text("重试正文") }
                            }
                            if (error == null && body == null) {
                                Text("正在通过 SSH 获取原文…", color = Muted)
                                LinearProgressIndicator(Modifier.fillMaxWidth())
                            }
                        }
                    }
                    itemsIndexed(content) { index, block ->
                        val chunks = remember(block.text) { newsParagraphChunks(block.text) }
                        val translated = if (canTranslate && !showOriginal) feed.translations.paragraph(chunks, target)
                            ?.takeUnless { it == chunks.joinToString(" ") } else null
                        Box(Modifier.widthIn(max = 720.dp).fillMaxWidth().then(
                            if (index == content.lastIndex) Modifier.testTag("newsReaderLastBlock") else Modifier
                        )) {
                            SelectionContainer {
                                val style = if (block.kind == "heading") MaterialTheme.typography.titleMedium
                                    else MaterialTheme.typography.bodyLarge
                                val prefix = if (block.kind == "bullet") "• " else ""
                                Text(prefix + (translated ?: block.text), Modifier.testTag(
                                    if (translated == null) "newsReaderBlock" else "newsReaderTranslatedBlock"), style = style)
                            }
                        }
                    }
                    if (body?.truncated == true) item {
                        Text("正文较长，余下内容可在原网页阅读。", Modifier.widthIn(max = 720.dp).fillMaxWidth(), color = Amber)
                    }
                }
            }
        }
    }
}
