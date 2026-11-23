package cn.xux.cloudworkbench

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

private val newsLanguages = linkedMapOf("zh" to "简体中文", "ja" to "日本語", "ko" to "한국어",
    "fr" to "Français", "de" to "Deutsch", "es" to "Español")

@Composable
internal fun NewsTranslationDialog(article: NewsArticle, feed: NewsFeed, connected: Boolean, initialLanguage: String,
    onLanguage: (String) -> Unit, onDismiss: () -> Unit) {
    var language by remember { mutableStateOf(initialLanguage) }
    var expanded by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<NewsTranslation?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var original by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    AlertDialog(onDismissRequest = onDismiss, title = { Text("资讯翻译") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Box {
                OutlinedButton(onClick = { expanded = true }, enabled = !loading, modifier = Modifier.testTag("newsLanguage")) {
                    Text(newsLanguages.getValue(language))
                }
                DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                    newsLanguages.forEach { (code, label) ->
                        DropdownMenuItem(text = { Text(label) }, modifier = Modifier.testTag("newsLanguage-$code"), onClick = {
                            expanded = false
                            if (language != code) {
                                language = code
                                result = null
                                error = null
                                original = false
                            }
                            onLanguage(code)
                        })
                    }
                }
            }
            if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("newsTranslationError")) }
            if (result != null) {
                TextButton(onClick = { original = !original }, modifier = Modifier.testTag("toggleNewsOriginal")) {
                    Text(if (original) "查看译文" else "查看原文")
                }
            }
            val translated = result?.takeUnless { original }
            Text(if (translated != null) "机器译文" else "原文", style = MaterialTheme.typography.labelMedium)
            Text(translated?.title ?: article.title, Modifier.testTag("newsTranslationTitle"),
                style = MaterialTheme.typography.titleMedium)
            val summary = translated?.summary ?: article.summary.orEmpty()
            if (summary.isNotEmpty()) Text(summary, Modifier.testTag("newsTranslationSummary"))
        }
    }, confirmButton = {
        TextButton(onClick = {
            loading = true
            error = null
            val target = language
            scope.launch {
                try {
                    result = feed.translate(article, target)
                    original = false
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    error = when ((e as? GatewayException)?.status) {
                        429 -> "正在处理另一条资讯，请稍后重试"
                        401, 403 -> "SSH 授权未通过，请重新连接"
                        else -> "翻译暂未完成，请重试"
                    }
                } finally {
                    loading = false
                }
            }
        }, enabled = connected && !loading && result == null,
            modifier = Modifier.testTag("requestNewsTranslation")) { Text("翻译") }
    }, dismissButton = { TextButton(onClick = onDismiss) { Text("关闭") } })
}
