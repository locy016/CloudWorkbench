package cn.xux.cloudworkbench

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

@Composable internal fun HistoryTimelineDialog(current: Int, close: () -> Unit, enter: (Int) -> Unit) {
    var preview by rememberSaveable { mutableIntStateOf(current) }
    Dialog(onDismissRequest = close, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        BoxWithConstraints(Modifier.fillMaxSize().safeDrawingPadding().padding(12.dp), contentAlignment = Alignment.Center) {
            val wide = maxWidth >= 580.dp
            Surface(Modifier.widthIn(max = 1000.dp).fillMaxWidth().heightIn(max = 900.dp).fillMaxHeight(.94f),
                shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surface) {
                Column {
                    Row(Modifier.padding(start = 20.dp, end = 6.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("选择历史阶段", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge, color = Ink)
                        IconButton(onClick = close) { Icon(Icons.Outlined.Close, "关闭", tint = Muted) }
                    }
                    HorizontalDivider(color = Outline)
                    if (wide) {
                        Row(Modifier.weight(1f)) {
                            EraTimeline(preview, { preview = it }, Modifier.weight(.34f).fillMaxHeight(), horizontal = false)
                            VerticalDivider(color = Outline)
                            EraSummary(historyEras[preview], Modifier.weight(.66f).fillMaxHeight())
                        }
                    } else {
                        EraTimeline(preview, { preview = it }, Modifier.fillMaxWidth(), horizontal = true)
                        HorizontalDivider(color = Outline)
                        EraSummary(historyEras[preview], Modifier.weight(1f).fillMaxWidth())
                    }
                    HorizontalDivider(color = Outline)
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                        horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
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

@Composable private fun EraTimeline(selected: Int, preview: (Int) -> Unit, modifier: Modifier, horizontal: Boolean) {
    val list = rememberLazyListState(initialFirstVisibleItemIndex = if (horizontal) selected else (selected - 1).coerceAtLeast(0))
    val entries: LazyListScope.() -> Unit = {
        itemsIndexed(historyEras, key = { _, era -> era.name }) { index, era ->
            val active = index == selected
            val itemWidth = if (horizontal) Modifier.width(170.dp) else Modifier.fillMaxWidth()
            Column(itemWidth.clip(MaterialTheme.shapes.small)
                .selectable(selected = active, role = Role.RadioButton) { preview(index) }
                .drawBehind {
                    val point = Offset(12.dp.toPx(), (if (horizontal) 10.dp else 26.dp).toPx())
                    val start = if (horizontal) Offset(0f, point.y) else Offset(point.x, 0f)
                    val end = if (horizontal) Offset(size.width, point.y) else Offset(point.x, size.height)
                    if (index > 0) drawLine(Outline, start, point, 2.dp.toPx())
                    if (index < historyEras.lastIndex) drawLine(Outline, point, end, 2.dp.toPx())
                    if (active) drawCircle(AccentSurface, 10.dp.toPx(), point)
                    drawCircle(if (active) Accent else Background, 5.dp.toPx(), point)
                    drawCircle(if (active) Accent else Muted, 5.dp.toPx(), point, style = Stroke(1.5.dp.toPx()))
                }.padding(start = if (horizontal) 0.dp else 26.dp, top = if (horizontal) 20.dp else 3.dp,
                    end = 3.dp, bottom = 3.dp)) {
                Column(Modifier.fillMaxWidth().clip(MaterialTheme.shapes.small)
                    .background(if (active) AccentSurface else Color.Transparent).padding(horizontal = 10.dp, vertical = 9.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(era.name, style = MaterialTheme.typography.titleMedium, color = if (active) Accent else Ink)
                    Text(era.dates, style = MaterialTheme.typography.bodySmall, color = Muted)
                }
            }
        }
    }
    val axis = modifier.background(Background).selectableGroup().testTag("eraTimeline")
    val inset = PaddingValues(horizontal = 8.dp, vertical = 10.dp)
    if (horizontal) LazyRow(axis, state = list, contentPadding = inset, content = entries)
    else LazyColumn(axis, state = list, contentPadding = inset, content = entries)
}

@Composable private fun EraSummary(era: HistoryEra, modifier: Modifier) {
    val scroll = key(era.name) { rememberScrollState() }
    Column(modifier.verticalScroll(scroll).testTag("eraSummary").padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Surface(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium, color = Ink, contentColor = Color.White) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(era.dates, style = MaterialTheme.typography.labelLarge, color = AccentSurface)
                Text(era.name, style = MaterialTheme.typography.headlineSmall)
                Text(era.summary, style = MaterialTheme.typography.bodyMedium, color = AccentSurface)
            }
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("区域摘要", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, color = Ink)
            Text("${era.regionalSummaries.size} 个区域 · ${era.events.size} 条记录", style = MaterialTheme.typography.bodySmall, color = Muted)
        }
        BoxWithConstraints {
            val columns = if (maxWidth >= 520.dp) 2 else 1
            val regions = HistoryRegion.entries.filter { it in era.regionalSummaries }
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                regions.chunked(columns).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        row.forEach { region ->
                            Surface(Modifier.weight(1f), shape = MaterialTheme.shapes.small, color = Background) {
                                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Text(region.label, style = MaterialTheme.typography.titleMedium, color = Accent)
                                    Text(era.regionalSummaries.getValue(region), style = MaterialTheme.typography.bodyMedium, color = Ink)
                                }
                            }
                        }
                        if (row.size < columns) Spacer(Modifier.weight(1f))
                    }
                }
            }
        }
    }
}
