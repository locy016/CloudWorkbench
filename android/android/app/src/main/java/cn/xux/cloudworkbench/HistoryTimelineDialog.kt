package cn.xux.cloudworkbench

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

@Composable internal fun HistoryTimelineDialog(current: Int, close: () -> Unit, enter: (Int) -> Unit) {
    var preview by rememberSaveable { mutableIntStateOf(current) }
    var showDetails by rememberSaveable { mutableStateOf(true) }
    val list = rememberLazyListState(initialFirstVisibleItemIndex = (current - 1).coerceAtLeast(0))
    Dialog(onDismissRequest = close, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().safeDrawingPadding().padding(12.dp), contentAlignment = Alignment.Center) {
            Surface(Modifier.widthIn(max = 760.dp).fillMaxWidth().heightIn(max = 900.dp).fillMaxHeight(.94f),
                shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surface) {
                Column {
                    Row(Modifier.padding(start = 20.dp, end = 6.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("选择历史阶段", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge, color = Ink)
                        IconButton(onClick = close) { Icon(Icons.Outlined.Close, "关闭", tint = Muted) }
                    }
                    HorizontalDivider(color = Outline)
                    LazyColumn(Modifier.weight(1f).fillMaxWidth().background(Background).testTag("eraTimeline"), state = list,
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp)) {
                        itemsIndexed(historyEras, key = { _, era -> era.name }) { index, era ->
                            val selected = index == preview
                            EraNode(era, index, selected, selected && showDetails) {
                                showDetails = !selected || !showDetails
                                preview = index
                                if (showDetails) list.requestScrollToItem(index)
                            }
                        }
                    }
                    HorizontalDivider(color = Outline)
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Text("第 ${preview + 1} / ${historyEras.size} 阶段", Modifier.weight(1f), color = Muted, style = MaterialTheme.typography.bodySmall)
                        TextButton(onClick = close) { Text("取消") }
                        Spacer(Modifier.width(8.dp))
                        Button(onClick = { enter(preview) }) {
                            Text("进入本阶段")
                            Spacer(Modifier.width(6.dp))
                            Icon(Icons.AutoMirrored.Outlined.ArrowForward, null, Modifier.size(16.dp))
                        }
                    }
                }
            }
        }
    }
}

@Composable private fun EraNode(era: HistoryEra, index: Int, selected: Boolean, expanded: Boolean, toggle: () -> Unit) {
    Box(Modifier.fillMaxWidth().drawBehind {
        val point = Offset(8.dp.toPx(), 26.dp.toPx())
        if (index > 0) drawLine(Outline, Offset(point.x, 0f), point, 2.dp.toPx())
        if (index < historyEras.lastIndex) drawLine(Outline, point, Offset(point.x, size.height), 2.dp.toPx())
        if (selected) drawCircle(AccentSurface, 8.dp.toPx(), point)
        drawCircle(if (selected) Accent else Background, 4.dp.toPx(), point)
        drawCircle(if (selected) Accent else Muted, 4.dp.toPx(), point, style = Stroke(1.5.dp.toPx()))
    }.padding(start = 21.dp, top = 4.dp, bottom = 4.dp)) {
        Surface(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(1.dp, if (selected) Accent else Outline)) {
            Column {
                Column(Modifier.fillMaxWidth().background(if (selected) AccentSurface else MaterialTheme.colorScheme.surface)
                    .clickable(role = Role.Button, onClickLabel = if (expanded) "收起详情" else "展开详情", onClick = toggle)
                    .semantics { stateDescription = if (expanded) "已展开" else "已收起" }
                    .padding(horizontal = 14.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(era.dates, style = MaterialTheme.typography.bodySmall, color = Muted)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(era.name, Modifier.weight(1f), style = MaterialTheme.typography.titleLarge, color = if (selected) Accent else Ink)
                        Icon(if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, null,
                            modifier = Modifier.size(22.dp), tint = if (selected) Accent else Muted)
                    }
                    Text(era.summary, style = MaterialTheme.typography.bodyMedium, color = Ink)
                }
                if (expanded) RegionalSummaries(era)
            }
        }
    }
}

@Composable private fun RegionalSummaries(era: HistoryEra) {
    Column(Modifier.fillMaxWidth().testTag("eraSummary").padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("区域摘要", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, color = Ink)
            Text("${era.regionalSummaries.size} 个区域 · ${era.events.size} 条记录", style = MaterialTheme.typography.bodySmall, color = Muted)
        }
        for (region in HistoryRegion.entries) {
            val summary = era.regionalSummaries[region] ?: continue
            HorizontalDivider(color = Outline)
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(region.label, style = MaterialTheme.typography.labelLarge, color = Accent)
                Text(summary, style = MaterialTheme.typography.bodyMedium, color = Ink)
            }
        }
    }
}
