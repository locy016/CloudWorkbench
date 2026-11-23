package cn.xux.cloudworkbench

import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

internal enum class NewsCategory(val key: String, val label: String) {
    Headlines("headlines", "要闻"), Current("current", "时事"), Trade("trade", "经贸"),
    Society("society", "社会"), Humanities("humanities", "人文"), Military("military", "军事"),
    Science("science", "科技"), Health("health", "健康"), Diplomacy("diplomacy", "外交"),
    Environment("environment", "环境"), Education("education", "教育"),
    Energy("energy", "能源"), Transport("transport", "交通");

    companion object {
        fun fromKey(key: String) = entries.firstOrNull { it.key == key }
    }
}

private val newsTime = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneOffset.UTC)

@Composable
internal fun NewsArticlePreview(title: String, publisher: String, time: String?, summary: String?, timeLabel: String) {
    Text(title, Modifier.testTag("newsHeadline"), style = MaterialTheme.typography.titleMedium)
    val date = time?.let { runCatching { newsTime.format(Instant.parse(it)) + " UTC" }.getOrNull() }
    val timeText = if (date == null) "未提供发布时间" else "$timeLabel $date"
    Text("$publisher · $timeText", color = Muted, style = MaterialTheme.typography.bodySmall,
        modifier = Modifier.testTag("newsHeadlineMetadata"))
    summary?.let {
        Text(it, Modifier.testTag("newsHeadlineSummary"), maxLines = 4, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
internal fun CopyNewsLink(link: String, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    TextButton(onClick = {
        context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("原文地址", link))
    }, modifier = modifier.testTag("newsHeadlineLink"), contentPadding = PaddingValues(horizontal = 8.dp)) {
        Text("复制链接", maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
internal fun NewsDateField(value: String, onChange: (String) -> Unit, label: String, modifier: Modifier) {
    OutlinedTextField(value, { onChange(it.filter { ch -> ch in '0'..'9' || ch == '-' }.take(10)) },
        modifier = modifier, label = { Text(label) }, placeholder = { Text("YYYY-MM-DD") }, singleLine = true)
}

internal fun parseNewsDate(value: String): LocalDate? = if (value.length == 10) {
    runCatching { LocalDate.parse(value) }.getOrNull()
} else null
