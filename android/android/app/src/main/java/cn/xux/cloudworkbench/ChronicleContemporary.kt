package cn.xux.cloudworkbench

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalLayoutApi::class)
@Composable internal fun TechnologyPanel(open: (HistoryTrail) -> Unit) {
    var selection by rememberSaveable { mutableIntStateOf(0) }
    val topics = TechnologyHistory.topics
    val trail = topics[selection].second
    Panel(Modifier.fillMaxWidth()) {
        Text("战后科技发展", color = Ink, style = MaterialTheme.typography.titleLarge)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            topics.forEachIndexed { index, (label, _) ->
                FilterChip(selected = index == selection, onClick = { selection = index }, label = { Text(label) })
            }
        }
        Column(Modifier.fillMaxWidth().clickable(role = Role.Button) { open(trail) }.padding(vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(trail.span + " · " + trail.steps.size + " 个节点", style = MaterialTheme.typography.labelLarge, color = Accent)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(trail.title, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, color = Ink)
                Icon(Icons.Outlined.ChevronRight, "查看科技轨迹", tint = Accent)
            }
            HistoryParagraph(trail.introduction, Muted)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable internal fun ChronicleDailyPanel() {
    val context = LocalContext.current.applicationContext
    val uri = LocalUriHandler.current
    val scope = rememberCoroutineScope()
    val batches by ChronicleDaily.batches.collectAsState()
    val failures by ChronicleDaily.failures.collectAsState()
    val loading by ChronicleDaily.loading.collectAsState()
    var topic by rememberSaveable { mutableStateOf(DailyTopic.World) }
    var more by rememberSaveable(topic) { mutableStateOf(false) }
    var badLink by remember { mutableStateOf(false) }
    LaunchedEffect(context) {
        ChronicleDaily.load(context)
        while (true) {
            ChronicleDaily.refresh(context)
            delay(3_600_000)
        }
    }
    val sources = DailySource.entries.filter { it.topic == topic }
    val rows = sources.flatMap { source -> batches[source]?.items.orEmpty().map { source to it } }
        .sortedByDescending { it.second.date }.distinctBy { it.second.url }
    val fmt = remember { DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault()) }
    Panel(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("每日动态", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge, color = Ink)
            TextButton(enabled = !loading, onClick = { scope.launch { ChronicleDaily.refresh(context, force = true) } }) {
                Text(if (loading) "正在刷新" else "刷新动态")
            }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            DailyTopic.entries.forEach { option ->
                FilterChip(selected = option == topic, onClick = { topic = option; badLink = false }, label = { Text(option.label) })
            }
        }
        val checked = sources.mapNotNull { batches[it] }.filterNot { it.bundled }.minOfOrNull { it.checked }
        val cached = sources.filter { it in failures }
        val status = when {
            cached.isNotEmpty() -> cached.joinToString("、") { it.label } + "暂未连通，保留上次资料"
            sources.any { batches[it]?.bundled == true } -> "含随版本收录的条目，联网后更新"
            checked != null -> "每日联网检查 · 最近核对 " + fmt.format(Instant.ofEpochMilli(checked))
            else -> "正在读取资料"
        }
        Text(status, style = MaterialTheme.typography.bodySmall, color = if (cached.isEmpty()) Muted else Amber)
        val visible = if (topic == DailyTopic.Models) {
            val (announcements, repositories) = rows.partition { it.first == DailySource.GoogleAI }
            val featured = if (more) repositories else repositories.distinctBy { it.first }
            featured + if (more) announcements else announcements.take(2)
        } else if (more) rows else rows.take(3)
        visible.forEachIndexed { index, (source, item) ->
            if (topic == DailyTopic.Models && (index == 0 || source == DailySource.GoogleAI && visible[index - 1].first != source)) {
                Text(if (source == DailySource.GoogleAI) "厂商动态" else "模型仓库", color = Accent, style = MaterialTheme.typography.labelLarge)
            }
            HorizontalDivider(color = Outline)
            Row(Modifier.fillMaxWidth().clickable(role = Role.Button) {
                badLink = runCatching { uri.openUri(item.url) }.isFailure
            }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    val action = when {
                        item.url.startsWith("https://modelscope.cn/models/") -> "魔搭仓库更新"
                        item.url.startsWith("https://huggingface.co/") -> "Hugging Face 仓库创建"
                        else -> "来源日期"
                    }
                    HistoryReadingPair(primary = {
                        Text(item.title, style = MaterialTheme.typography.titleMedium, color = Ink)
                        if (item.summary.isNotBlank()) Text(item.summary, style = MaterialTheme.typography.bodyMedium,
                            color = Muted, maxLines = 2, lineHeight = 24.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Justify,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                    }, secondary = {
                        Text(item.date + " · " + source.label + " · " + action, style = MaterialTheme.typography.bodySmall, color = Muted)
                    })
                }
                Icon(Icons.AutoMirrored.Outlined.OpenInNew, null, Modifier.size(16.dp), tint = Accent)
            }
        }
        if (badLink) Text("无法打开原文", style = MaterialTheme.typography.bodySmall, color = Amber)
        if (more || rows.size > visible.size) TextButton(onClick = { more = !more }) { Text(if (more) "收起动态" else "查看全部 " + rows.size + " 条") }
    }
}
