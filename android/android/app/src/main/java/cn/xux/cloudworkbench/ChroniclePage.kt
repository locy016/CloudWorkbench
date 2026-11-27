package cn.xux.cloudworkbench

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog

@Composable internal fun ChroniclePage() {
    var regional by rememberSaveable { mutableStateOf(false) }
    var eraIndex by rememberSaveable { mutableIntStateOf(1) }
    var region by rememberSaveable { mutableStateOf(HistoryRegion.China) }
    var lens by rememberSaveable { mutableStateOf<HistoryLens?>(null) }
    var picking by rememberSaveable { mutableStateOf(false) }
    var about by rememberSaveable { mutableStateOf(false) }
    var detailTitle by rememberSaveable { mutableStateOf<String?>(null) }
    var trailTitle by rememberSaveable { mutableStateOf<String?>(null) }
    val era = historyEras[eraIndex]
    val groups = historyEras.mapIndexedNotNull { index, period ->
        val matching = period.events.filter { (if (regional) it.region == region else index == eraIndex) && (lens == null || it.lens == lens) }
        if (matching.isEmpty()) null else period to matching
    }
    val visibleEvents = groups.flatMap { it.second }
    val detail = visibleEvents.find { it.title == detailTitle }
    val trails = visibleEvents.mapNotNull { it.trail }.distinct()
    val openTrail = trails.find { it.title == trailTitle }
    val scroll = key(regional, eraIndex, region, lens) { rememberScrollState() }
    Column(Modifier.fillMaxSize().background(Background).verticalScroll(scroll).padding(LocalPagePadding.current),
        verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.weight(1f).selectableGroup(), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                listOf("时代对照", "区域追踪").forEachIndexed { i, label ->
                    val selected = regional == (i == 1)
                    Text(label, Modifier.clip(MaterialTheme.shapes.large).background(if (selected) AccentSurface else Color.Transparent)
                        .selectable(selected, role = Role.Tab) { if (!selected) { regional = i == 1; lens = null } }
                        .heightIn(min = 44.dp).wrapContentHeight().padding(horizontal = 13.dp),
                        color = if (selected) Accent else Muted, fontWeight = FontWeight.SemiBold)
                }
            }
            IconButton(onClick = { about = true }) { Icon(Icons.Outlined.Info, "分期与选材说明", tint = Muted) }
        }
        HistoryCover(if (regional) "区域追踪" else "世界史分期 · ${eraIndex + 1} / ${historyEras.size}",
            if (regional) region.label else era.name, if (regional) "区域沿革" else era.dates,
            if (regional) region.scope else era.summary, if (regional) "选择区域" else "选择阶段",
            if (regional) null else eraIndex, onStep = { eraIndex += it; lens = null }, select = { picking = true })
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(if (regional) "历时脉络" else if (lens != null) "同期区域对照" else "同期动态", color = Ink, fontSize = 21.sp, fontWeight = FontWeight.Bold)
                Text(if (regional) "${groups.size} 个阶段 · ${groups.sumOf { it.second.size }} 条记录" else
                    "${groups.flatMap { it.second }.distinctBy { it.region }.size} 个区域 · ${groups.sumOf { it.second.size }} 条记录",
                    color = Muted, style = MaterialTheme.typography.bodySmall)
            }
            var filterOpen by remember { mutableStateOf(false) }
            Box {
                OutlinedButton(onClick = { filterOpen = true }, border = BorderStroke(1.dp, Outline), colors = ButtonDefaults.outlinedButtonColors(contentColor = Accent)) {
                    Icon(Icons.Outlined.Tune, "筛选维度", Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(lens?.label ?: "全部维度")
                }
                DropdownMenu(expanded = filterOpen, onDismissRequest = { filterOpen = false }) {
                    (listOf(null) + HistoryLens.entries).forEach { choice ->
                        DropdownMenuItem(text = { Text(choice?.label ?: "全部维度") }, onClick = { lens = choice; filterOpen = false },
                            trailingIcon = { if (choice == lens) Icon(Icons.Outlined.Check, "已选") })
                    }
                }
            }
        }
        if (!regional && trails.isNotEmpty()) {
            val (recent, historical) = trails.partition { it.newsStatus != null }
            if (recent.isNotEmpty()) TrailIndex("时事追踪", recent) { trailTitle = it.title }
            if (historical.isNotEmpty()) TrailIndex("重点事件轨迹", historical) { trailTitle = it.title }
        }
        if (groups.isEmpty()) {
            Text("当前选材没有这一维度的记录。", color = Muted)
            TextButton(onClick = { lens = null }) { Text("查看全部维度") }
        }
        if (regional) {
            RegionalTimeline(region, groups) { detailTitle = it.title }
        } else groups.forEach { (_, events) ->
            if (lens != null) {
                RegionComparison(events) { detailTitle = it.title }
                return@forEach
            }
            BoxWithConstraints {
                val columns = if (maxWidth >= 600.dp) 2 else 1
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    events.chunked(columns).forEach { row ->
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            row.forEach { event -> HistoryCard(event, Modifier.weight(1f)) { detailTitle = event.title } }
                            if (row.size < columns) Spacer(Modifier.weight(1f))
                        }
                    }
                }
            }
        }
    }
    if (picking && regional) HistoryDialog("选择区域", { picking = false }) {
        HistoryRegion.entries.forEach { option ->
            PickerRow(option.label, option == region) { region = option; lens = null; picking = false }
        }
    }
    if (picking && !regional) HistoryTimelineDialog(eraIndex, { picking = false }) { index ->
        eraIndex = index
        lens = null
        picking = false
    }
    if (about) HistoryDialog("分期与选材", { about = false }) {
        DialogSection("如何读时间", "九个阶段参照 UCLA 美国国家学校历史中心的世界史教学标准。相邻时段有重叠；跨界事件按主题归入一处。年代前的“约”表示估年。")
        DialogSection("如何读区域", "古代以古埃及、两河流域、南亚和中国为主线。当代选取安理会五个常任理事国，加上印度、日本、德国和巴西作为观察样本。地区标签用于串联地理空间，疆界与政治实体随时代变化。")
        DialogSection("如何读选材", "每条记录归入一个主要维度。同一事件可能同时影响多个领域；各阶段展示代表性节点。")
        ReadingLink("查阅世界史分期标准", HISTORY_STANDARD)
    }
    if (openTrail != null) HistoryDialog(openTrail.title, { trailTitle = null }) {
        Text(openTrail.subjects, color = Accent, style = MaterialTheme.typography.titleMedium)
        Text(openTrail.introduction, color = Ink, style = MaterialTheme.typography.bodyMedium)
        Text(openTrail.newsStatus ?: openTrail.span, color = Muted, style = MaterialTheme.typography.labelLarge)
        Column {
            openTrail.steps.forEachIndexed { index, step ->
                Column(Modifier.fillMaxWidth().drawBehind {
                    val point = Offset(6.dp.toPx(), 12.dp.toPx())
                    if (index > 0) drawLine(Outline, Offset(point.x, 0f), point, 2.dp.toPx())
                    if (index < openTrail.steps.lastIndex) drawLine(Outline, point, Offset(point.x, size.height), 2.dp.toPx())
                    drawCircle(Accent, 4.dp.toPx(), point)
                }.padding(start = 22.dp, bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(step.date, color = Accent, style = MaterialTheme.typography.labelLarge)
                    Text(step.title, color = Ink, style = MaterialTheme.typography.titleMedium)
                    Text(step.body, color = Ink, style = MaterialTheme.typography.bodyMedium)
                    ReadingLink(step.source.label, step.source.url)
                }
            }
        }
    } else if (detail != null) HistoryDialog(detail.title, { detailTitle = null }) {
        val period = groups.first { detail in it.second }.first
        Text("${detail.date}  ·  ${detail.region.label}", color = Accent, fontWeight = FontWeight.SemiBold)
        Text("${period.name}   /   ${detail.lens.label}", color = Muted, style = MaterialTheme.typography.bodySmall)
        Text(detail.detail, color = Ink, lineHeight = 25.sp)
        Text("史料出处", color = Muted, style = MaterialTheme.typography.labelLarge)
        ReadingLink(detail.reading, detail.url)
        detail.trail?.let { trail ->
            OutlinedButton(onClick = { trailTitle = trail.title }) { Text("查看完整事件轨迹") }
        }
        HorizontalDivider(color = Outline)
        TextButton(onClick = {
            region = detail.region
            eraIndex = historyEras.indexOf(period)
            regional = !regional
            lens = null
            detailTitle = null
        }) { Text(if (regional) "对照这个阶段的其他区域" else "追踪${detail.region.label}的历史", color = Accent) }
    }
}

@Composable private fun TrailIndex(title: String, trails: List<HistoryTrail>, open: (HistoryTrail) -> Unit) {
    Panel(Modifier.fillMaxWidth()) {
        Text(title, color = Ink, style = MaterialTheme.typography.titleMedium)
        trails.forEach { trail ->
            HorizontalDivider(color = Outline)
            Row(Modifier.fillMaxWidth().clickable(role = Role.Button) { open(trail) }.padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(trail.title, color = Accent, style = MaterialTheme.typography.titleMedium)
                    Text(trail.newsStatus ?: "${trail.span} · ${trail.subjects}", color = Muted, style = MaterialTheme.typography.bodySmall)
                    if (trail.newsStatus != null) Text(trail.steps.last().title, color = Ink, style = MaterialTheme.typography.bodyMedium)
                }
                Icon(Icons.Outlined.ChevronRight, null, Modifier.size(18.dp), tint = Accent)
            }
        }
    }
}

@Composable private fun HistoryCover(kicker: String, title: String, date: String, summary: String, action: String,
    eraIndex: Int?, onStep: (Int) -> Unit, select: () -> Unit) {
    Panel(Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(kicker, color = Accent, style = MaterialTheme.typography.labelLarge)
            Text(title, color = Ink, style = MaterialTheme.typography.headlineSmall)
            Text(date, color = Muted, fontSize = 14.sp)
            HorizontalDivider(Modifier.padding(vertical = 5.dp), color = Outline)
            Text(summary, color = Ink, style = MaterialTheme.typography.bodyMedium)
            Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                FilledTonalButton(onClick = select) {
                    Text(action)
                    Icon(Icons.Outlined.ExpandMore, null, Modifier.size(18.dp))
                }
                if (eraIndex != null) {
                    Spacer(Modifier.weight(1f))
                    val colors = IconButtonDefaults.iconButtonColors(contentColor = Accent)
                    IconButton(onClick = { onStep(-1) }, enabled = eraIndex > 0, colors = colors) { Icon(Icons.Outlined.ChevronLeft, "上一阶段") }
                    IconButton(onClick = { onStep(1) }, enabled = eraIndex < historyEras.lastIndex, colors = colors) { Icon(Icons.Outlined.ChevronRight, "下一阶段") }
                }
            }
        }
    }
}

@Composable private fun RegionComparison(events: List<HistoryEvent>, open: (HistoryEvent) -> Unit) {
    val regions = events.groupBy { it.region }.entries.sortedBy { it.key.ordinal }
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val columns = if (maxWidth >= 600.dp) 2 else 1
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            regions.chunked(columns).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    row.forEach { (region, entries) ->
                        Panel(Modifier.weight(1f)) {
                            Text(region.label, color = Accent, style = MaterialTheme.typography.titleMedium)
                            entries.forEach { event ->
                                HorizontalDivider(color = Outline)
                                Column(Modifier.fillMaxWidth().clickable(role = Role.Button) { open(event) }
                                    .padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Text(event.date, color = Muted, style = MaterialTheme.typography.bodySmall)
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(event.title, Modifier.weight(1f), color = Ink, style = MaterialTheme.typography.titleMedium)
                                        Icon(Icons.Outlined.ChevronRight, null, Modifier.size(18.dp), tint = Accent)
                                    }
                                    Text(event.detail.substringBefore('。') + "。", color = Muted, style = MaterialTheme.typography.bodyMedium)
                                }
                            }
                        }
                    }
                    if (row.size < columns) Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable private fun RegionalTimeline(region: HistoryRegion, groups: List<Pair<HistoryEra, List<HistoryEvent>>>, open: (HistoryEvent) -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        groups.forEachIndexed { index, (era, events) ->
            Column(Modifier.fillMaxWidth().drawBehind {
                val node = Offset(8.dp.toPx(), 10.dp.toPx())
                if (index > 0) drawLine(Outline, Offset(node.x, 0f), node, 2.dp.toPx())
                if (index < groups.lastIndex) drawLine(Outline, node, Offset(node.x, size.height), 2.dp.toPx())
                drawCircle(Accent, 5.dp.toPx(), node)
            }.padding(start = 25.dp, bottom = 20.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text(era.dates, color = Accent, style = MaterialTheme.typography.labelLarge)
                Text(era.name, color = Ink, style = MaterialTheme.typography.titleLarge)
                Text(era.regionalSummaries.getValue(region), color = Muted, style = MaterialTheme.typography.bodyMedium)
                Surface(Modifier.fillMaxWidth().padding(top = 4.dp), shape = MaterialTheme.shapes.large,
                    color = MaterialTheme.colorScheme.surface, border = BorderStroke(1.dp, Outline)) {
                    Column {
                        events.forEachIndexed { i, event ->
                            if (i > 0) HorizontalDivider(Modifier.padding(horizontal = 12.dp), color = Outline)
                            Row(Modifier.fillMaxWidth().clickable(role = Role.Button) { open(event) }.padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                    Text("${event.date}   ·   ${event.lens.label}", color = Muted, style = MaterialTheme.typography.bodySmall)
                                    Text(event.title, color = Ink, style = MaterialTheme.typography.titleMedium)
                                }
                                Icon(Icons.Outlined.ChevronRight, null, Modifier.size(18.dp), tint = Accent)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable private fun HistoryCard(event: HistoryEvent, modifier: Modifier, open: () -> Unit) {
    val icon = when (event.lens) {
        HistoryLens.Governance -> Icons.Outlined.AccountBalance
        HistoryLens.Economy -> Icons.Outlined.SwapHoriz
        HistoryLens.Society -> Icons.Outlined.Groups
        HistoryLens.Ideas -> Icons.Outlined.AutoStories
        HistoryLens.Technology -> Icons.Outlined.Architecture
    }
    Surface(onClick = open, modifier = modifier, shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surface, border = BorderStroke(1.dp, Outline)) {
        Column(Modifier.padding(15.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, null, Modifier.size(17.dp), tint = Accent)
                Text(event.region.label, Modifier.weight(1f).padding(start = 7.dp), color = Ink, style = MaterialTheme.typography.labelLarge)
                Text(event.lens.label, color = Muted, style = MaterialTheme.typography.bodySmall)
            }
            Text(event.date, color = Muted, style = MaterialTheme.typography.bodySmall)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(event.title, Modifier.weight(1f), fontSize = 17.sp, lineHeight = 24.sp, fontWeight = FontWeight.SemiBold, color = Ink)
                Icon(Icons.Outlined.ChevronRight, null, Modifier.size(18.dp), tint = Muted)
            }
        }
    }
}

@Composable private fun HistoryDialog(title: String, close: () -> Unit, body: @Composable ColumnScope.() -> Unit) {
    Dialog(onDismissRequest = close) {
        Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surface, modifier = Modifier.widthIn(max = 480.dp).fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(title, Modifier.weight(1f), color = Ink, style = MaterialTheme.typography.titleLarge)
                    IconButton(onClick = close) { Icon(Icons.Outlined.Close, "关闭", tint = Muted) }
                }
                HorizontalDivider(color = Outline)
                Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(vertical = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(7.dp), content = body)
            }
        }
    }
}

@Composable private fun PickerRow(title: String, selected: Boolean, select: () -> Unit) {
    Row(Modifier.fillMaxWidth().clip(MaterialTheme.shapes.large).background(if (selected) AccentSurface else Color.Transparent)
        .selectable(selected, role = Role.RadioButton, onClick = select).heightIn(min = 48.dp).padding(10.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Text(title, Modifier.weight(1f), color = Ink, fontWeight = FontWeight.Medium)
        if (selected) Icon(Icons.Outlined.Check, "已选", tint = Accent)
    }
}

@Composable private fun DialogSection(title: String, body: String) {
    Text(title, color = Accent, fontWeight = FontWeight.Bold)
    Text(body, color = Ink, style = MaterialTheme.typography.bodyMedium)
}

@Composable private fun ReadingLink(label: String, url: String) {
    val handler = LocalUriHandler.current
    var unavailable by remember { mutableStateOf(false) }
    TextButton(onClick = { unavailable = runCatching { handler.openUri(url) }.isFailure }) {
        Icon(Icons.AutoMirrored.Outlined.OpenInNew, null, Modifier.size(16.dp), tint = Accent)
        Spacer(Modifier.width(6.dp))
        Text(label, color = Accent)
    }
    if (unavailable) Text("当前设备无法打开此链接。", color = Muted)
}
