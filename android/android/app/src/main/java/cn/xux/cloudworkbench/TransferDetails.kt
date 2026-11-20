package cn.xux.cloudworkbench

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.delay
import java.util.Locale

private val ReceiveBlue = Color(0xFF245E92)
private val ReceiveSurface = Color(0xFFEAF2FA)

private enum class OperationFilter(val title: String) {
    All("全部"), Sessions("会话"), Files("文件"), Management("管理");

    fun matches(kind: TransferKind): Boolean = this == All || this == when (kind) {
        TransferKind.Create, TransferKind.Conversation, TransferKind.History, TransferKind.Send -> Sessions
        TransferKind.Upload, TransferKind.Image -> Files
        else -> Management
    }
}

@Composable internal fun TransferDetails(
    tracker: ConnectionTransfers, connection: String, task: String?, onClose: () -> Unit
) {
    var summary by remember(tracker) { mutableStateOf(tracker.snapshot()) }
    var filter by rememberSaveable { mutableStateOf(OperationFilter.All) }
    LaunchedEffect(tracker) {
        while (true) {
            summary = tracker.snapshot()
            delay(250)
        }
    }
    val current = summary.operations.filter { it.result == null }
    val recent = summary.operations.filter { it.result != null && filter.matches(it.kind) }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.padding(horizontal = 16.dp, vertical = 24.dp)
            .widthIn(max = 660.dp).fillMaxWidth().heightIn(max = 760.dp).testTag("transferPanel"),
            shape = RoundedCornerShape(24.dp), color = Background, tonalElevation = 4.dp) {
            Column {
                Row(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface)
                    .padding(start = 20.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text("传输详情", Modifier.semantics { heading() }, style = MaterialTheme.typography.titleLarge)
                        Text(connection, color = if (connection == "已连接") Accent else Muted,
                            style = MaterialTheme.typography.bodySmall)
                    }
                    IconButton(onClick = onClose) { Icon(Icons.Outlined.Close, "关闭") }
                }
                HorizontalDivider(color = Outline)
                LazyColumn(Modifier.weight(1f, fill = false), contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    item("totals") { TransferOverview(summary) }
                    if (current.isNotEmpty() || task != null) item("active") {
                        Panel(Modifier.fillMaxWidth()) {
                            Text("当前活动", Modifier.semantics { heading() }, style = MaterialTheme.typography.titleMedium)
                            current.forEach { entry ->
                                TransferRow(entry)
                                if (entry.phase == TransferPhase.Uploading && entry.uploadSize > 0) {
                                    val progress by animateFloatAsState(
                                        (entry.sent.toFloat() / entry.uploadSize).coerceIn(0f, 1f),
                                        tween(200), label = "附件上传"
                                    )
                                    LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth(),
                                        color = Accent, trackColor = AccentSurface)
                                }
                            }
                            task?.let { Text(it, color = Accent, style = MaterialTheme.typography.bodyMedium) }
                        }
                    }
                    item("sync") { SyncSummary(summary) }
                    item("history") {
                        Column(Modifier.padding(horizontal = 4.dp)) {
                            Text("最近操作", Modifier.semantics { heading() }, style = MaterialTheme.typography.titleMedium)
                            ActionRow {
                                OperationFilter.entries.forEach { category ->
                                    FilterChip(selected = filter == category, onClick = { filter = category },
                                        label = { Text(category.title) })
                                }
                            }
                            if (recent.isEmpty()) Text(
                                if (filter == OperationFilter.All) "本次连接暂无已结束的手动操作" else "此类暂无记录",
                                color = Muted, style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                    items(recent) { entry ->
                        Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp),
                            border = BorderStroke(1.dp, Outline)) {
                            Box(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) { TransferRow(entry) }
                        }
                    }
                }
            }
        }
    }
}

@Composable private fun TransferOverview(summary: TransferSummary) {
    Panel(Modifier.fillMaxWidth()) {
        Text("本次连接累计", Modifier.semantics { heading() }, style = MaterialTheme.typography.titleMedium)
        Row(Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 10.dp).testTag("transferTotals"),
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TotalTile("↑ 发送", summary.sent, Accent, AccentSurface, Modifier.weight(1f))
            TotalTile("↓ 接收", summary.received, ReceiveBlue, ReceiveSurface, Modifier.weight(1f))
        }
        Text("近 1 分钟收发", Modifier.semantics { heading() }, style = MaterialTheme.typography.titleSmall)
        TrafficChart(summary.traffic)
        Text("按每 2 秒的正文大小统计，含后台同步和取材图片。", color = Muted,
            style = MaterialTheme.typography.bodySmall)
    }
}

@Composable private fun TotalTile(label: String, bytes: Long, tint: Color, background: Color, modifier: Modifier) {
    Surface(modifier, color = background, shape = RoundedCornerShape(12.dp)) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            Text(label, color = tint, style = MaterialTheme.typography.labelLarge)
            Text(transferSize(bytes), color = tint, style = MaterialTheme.typography.headlineSmall)
        }
    }
}

@Composable private fun TrafficChart(buckets: List<TransferBucket>) {
    val peak = buckets.maxOfOrNull { maxOf(it.sent, it.received) } ?: 0L
    val latest = buckets.lastOrNull() ?: TransferBucket()
    val latestSent by animateFloatAsState(latest.sent.toFloat(), tween(220), label = "发送量")
    val latestReceived by animateFloatAsState(latest.received.toFloat(), tween(220), label = "接收量")
    val scale by animateFloatAsState(peak.coerceAtLeast(1).toFloat(), tween(220), label = "流量刻度")
    val peakLabel = transferSize(peak)
    val description = "近 1 分钟，每 2 秒一组，峰值 $peakLabel"
    Column(Modifier.fillMaxWidth().testTag("transferChart").semantics { contentDescription = description }) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            ChartLegend("发送", Accent)
            ChartLegend("接收", ReceiveBlue)
            Spacer(Modifier.weight(1f))
            Text("峰值 $peakLabel", color = Muted, style = MaterialTheme.typography.bodySmall)
        }
        Canvas(Modifier.fillMaxWidth().height(112.dp).padding(top = 6.dp, bottom = 4.dp)) {
            val baseline = size.height - 1.dp.toPx()
            for (line in 0..2) {
                val y = baseline * line / 2
                drawLine(Outline, Offset(0f, y), Offset(size.width, y), 1.dp.toPx())
            }
            val slot = size.width / buckets.size.coerceAtLeast(1)
            val bar = slot * .36f
            fun drawBar(value: Float, x: Float, color: Color) {
                if (value <= 0f) return
                val height = (baseline * (value / scale).coerceAtMost(1f)).coerceAtLeast(1.dp.toPx())
                drawRect(color, Offset(x, baseline - height), Size(bar, height))
            }
            buckets.forEachIndexed { index, bucket ->
                val x = index * slot + slot * .12f
                val last = index == buckets.lastIndex
                drawBar(if (last) latestSent else bucket.sent.toFloat(), x, Accent)
                drawBar(if (last) latestReceived else bucket.received.toFloat(), x + bar + slot * .04f, ReceiveBlue)
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("1 分钟前", color = Muted, style = MaterialTheme.typography.bodySmall)
            Text("现在", color = Muted, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable private fun ChartLegend(label: String, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        Box(Modifier.size(8.dp).background(color, CircleShape))
        Text(label, color = color, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable private fun SyncSummary(summary: TransferSummary) {
    Panel(Modifier.fillMaxWidth().testTag("transferSync")) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("后台同步", Modifier.weight(1f).semantics { heading() }, style = MaterialTheme.typography.titleMedium)
            if (summary.syncing > 0) {
                CircularProgressIndicator(Modifier.size(12.dp), strokeWidth = 2.dp)
                Text("同步中", color = Accent, style = MaterialTheme.typography.bodySmall)
            }
        }
        Text("发送 ${transferSize(summary.syncSent)} · 接收 ${transferSize(summary.syncReceived)}",
            color = Muted, style = MaterialTheme.typography.bodySmall)
        Text("成功 ${summary.syncCompleted} 次 · 失败 ${summary.syncFailed} 次",
            color = if (summary.syncFailed > 0) MaterialTheme.colorScheme.error else Muted,
            style = MaterialTheme.typography.bodySmall)
    }
}

@Composable private fun TransferRow(entry: TransferEntry) {
    val result = entry.result ?: transferLabel(entry)
    val elapsed = if (entry.elapsedMs < 1000) "${entry.elapsedMs} ms"
        else String.format(Locale.getDefault(), "%.1f 秒", entry.elapsedMs / 1000.0)
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(entry.kind.title, style = MaterialTheme.typography.labelLarge)
        Text(result, style = MaterialTheme.typography.bodySmall, color = when {
            result.startsWith("失败") -> MaterialTheme.colorScheme.error
            result == "已取消" -> Muted
            else -> Accent
        })
        Text("发送 ${transferSize(entry.sent)} · 接收 ${transferSize(entry.received)} · $elapsed",
            style = MaterialTheme.typography.bodySmall, color = Muted)
    }
}
