package cn.xux.cloudworkbench

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.CancellationException

internal data class NewsBodyBlock(val kind: String, val text: String)
internal data class NewsArticleBody(val blocks: List<NewsBodyBlock>, val truncated: Boolean)

@Composable
internal fun NewsArticleReader(article: NewsArticle, feed: NewsFeed, connected: Boolean, onClose: () -> Unit) {
    val context = LocalContext.current
    val id = article.readerId
    val original = remember(article.link) {
        article.link?.let(Uri::parse)?.takeIf {
            it.scheme in listOf("http", "https") && !it.host.isNullOrBlank() && it.userInfo.isNullOrEmpty()
        }
    }
    var body by remember(article) { mutableStateOf(feed.cachedArticle(id)) }
    var error by remember(article) { mutableStateOf<String?>(null) }
    var openingFailed by remember { mutableStateOf(false) }
    var retry by remember { mutableIntStateOf(0) }
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
                        401, 403 -> "SSH 授权未通过，请重新连接"
                        else -> "暂时无法读取正文，可打开原网页"
                    }
                }
            }
        }
    }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize().testTag("newsReader"), color = MaterialTheme.colorScheme.surface) {
            Column {
                Row(Modifier.fillMaxWidth().padding(end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "返回资讯") }
                    Text("原文阅读", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                    if (original != null) {
                        TextButton(onClick = {
                            openingFailed = runCatching {
                                context.startActivity(Intent(Intent.ACTION_VIEW, original).addCategory(Intent.CATEGORY_BROWSABLE))
                            }.isFailure
                        }, modifier = Modifier.testTag("openOriginalNewsPage")) { Text("打开原网页") }
                    }
                }
                HorizontalDivider(color = Outline)
                LazyColumn(Modifier.weight(1f).fillMaxWidth().testTag("newsReaderContent"),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    contentPadding = PaddingValues(horizontal = LocalPagePadding.current, vertical = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    item {
                        Column(Modifier.widthIn(max = 720.dp).fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(article.title, Modifier.testTag("newsReaderTitle"), style = MaterialTheme.typography.headlineSmall)
                            val date = article.time?.take(16)?.replace('T', ' ')?.let { "$it UTC" }
                            Text(listOfNotNull(article.publisher, date).joinToString(" · "), color = Muted)
                            if (openingFailed) Text("设备上未找到能打开原网页的应用", color = Amber)
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
                    items(body?.blocks.orEmpty()) { block ->
                        Box(Modifier.widthIn(max = 720.dp).fillMaxWidth()) {
                            SelectionContainer {
                                val style = if (block.kind == "heading") MaterialTheme.typography.titleMedium
                                    else MaterialTheme.typography.bodyLarge
                                val prefix = if (block.kind == "bullet") "• " else ""
                                Text(prefix + block.text, Modifier.testTag("newsReaderBlock"), style = style)
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
