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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable internal fun ChroniclePage() {
    var regional by rememberSaveable { mutableStateOf(false) }
    var eraIndex by rememberSaveable { mutableIntStateOf(1) }
    var region by rememberSaveable { mutableStateOf(HistoryRegion.China) }
    var modernOnly by rememberSaveable { mutableStateOf(false) }
    var lens by rememberSaveable { mutableStateOf<HistoryLens?>(null) }
    var picking by rememberSaveable { mutableStateOf(false) }
    var about by rememberSaveable { mutableStateOf(false) }
    var detailTitle by rememberSaveable { mutableStateOf<String?>(null) }
    var trailTitle by rememberSaveable { mutableStateOf<String?>(null) }
    val era = historyEras[eraIndex]
    val isPostwar = !regional && eraIndex == historyEras.lastIndex
    var sliceStart by rememberSaveable { mutableIntStateOf(0) }
    val slice = postwarPeriods.find { isPostwar && it.start == sliceStart }
    val groups = historyEras.mapIndexedNotNull { index, period ->
        val matching = period.events.filter {
            (if (regional) it.region == region else index == eraIndex) && (lens == null || it.lens == lens) &&
                (slice == null || slice.contains(it)) && (!regional || !modernOnly || it.inModernRegionalHistory())
        }
        if (matching.isEmpty()) null else period to matching
    }
    val visibleEvents = groups.flatMap { it.second }
    val detail = visibleEvents.find { it.title == detailTitle }
    val trails = visibleEvents.mapNotNull { it.trail }.distinct()
    val openTrail = trails.find { it.title == trailTitle }
    val scroll = key(regional, eraIndex, region, lens, modernOnly) { rememberScrollState() }
    Box(Modifier.fillMaxSize().background(Background), contentAlignment = Alignment.TopCenter) {
        Column(Modifier.widthIn(max = 1040.dp).fillMaxWidth().fillMaxHeight().historyScrollbar(scroll).verticalScroll(scroll)
            .padding(horizontal = LocalPagePadding.current, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Row(Modifier.weight(1f).selectableGroup(), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                    listOf("时代对照", "区域追踪").forEachIndexed { i, label ->
                        val selected = regional == (i == 1)
                        Text(label, Modifier.clip(MaterialTheme.shapes.large).background(if (selected) AccentSurface else Color.Transparent)
                            .selectable(selected, role = Role.Tab) { if (!selected) { regional = i == 1; lens = null; sliceStart = 0 } }
                            .heightIn(min = 44.dp).wrapContentHeight().padding(horizontal = 13.dp),
                            color = if (selected) Accent else Muted, fontWeight = FontWeight.SemiBold)
                    }
                }
                IconButton(onClick = { about = true }) { Icon(Icons.Outlined.Info, "分期与选材说明", tint = Muted) }
            }
            HistoryCover(if (regional) "区域追踪" else "世界史分期 · ${eraIndex + 1} / ${historyEras.size}",
                if (regional) region.label else slice?.title ?: era.name,
                if (regional) region.periodBasis else if (slice != null) "${era.name} · ${slice.dates}" else era.dates,
                if (regional) region.scope else slice?.context ?: era.summary, if (regional) "选择区域" else "选择阶段",
                if (regional) null else eraIndex, onStep = { eraIndex += it; lens = null; sliceStart = 0 }, select = { picking = true })
            if (isPostwar) PostwarPeriodPanel(slice) { sliceStart = it?.start ?: 0 }
            if (isPostwar && slice == null) {
                Text("从区域时间轴阅读", color = Ink, style = MaterialTheme.typography.titleMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    modernRegionLabels.forEach { (option, label) ->
                        OutlinedButton(onClick = { region = option; regional = true; modernOnly = true; lens = null },
                            modifier = Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 6.dp)) { Text(label) }
                    }
                }
            }
            if (regional && region in modernRegionLabels) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(!modernOnly, { modernOnly = false }, { Text("全部年代") })
                    FilterChip(modernOnly, { modernOnly = true }, { Text(if (region == HistoryRegion.China) "1949年至今" else "二战至今") })
                }
            }
            if (isPostwar && slice == null) {
                if (lens == null || lens == HistoryLens.Technology) TechnologyPanel { trailTitle = it.title }
                if (lens == null) ChronicleDailyPanel()
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(if (regional) "历时脉络" else if (lens != null || slice != null) "同期区域对照" else "同期动态", color = Ink, fontSize = 21.sp, fontWeight = FontWeight.Bold)
                    Text(if (regional) "${visibleEvents.flatMap { it.localPeriodIds }.distinct().size} 个分期有记录 · ${visibleEvents.size} 条记录" else
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
                    val menuScroll = rememberScrollState()
                    DropdownMenu(expanded = filterOpen, onDismissRequest = { filterOpen = false }, scrollState = menuScroll,
                        modifier = Modifier.widthIn(min = 168.dp).heightIn(max = 360.dp).historyScrollbar(menuScroll)) {
                        (listOf(null) + HistoryLens.entries).forEach { choice ->
                            DropdownMenuItem(text = { Text(choice?.label ?: "全部维度") }, onClick = { lens = choice; filterOpen = false },
                                trailingIcon = { if (choice == lens) Icon(Icons.Outlined.Check, "已选") })
                        }
                    }
                }
            }
            if (!regional && slice == null && trails.isNotEmpty()) {
                val (recent, historical) = trails.filterNot { trail -> TechnologyHistory.topics.any { it.second == trail } }.partition { it.newsStatus != null }
                if (recent.isNotEmpty()) TrailIndex("时事专题", recent) { trailTitle = it.title }
                if (historical.isNotEmpty()) TrailIndex("重点事件轨迹", historical) { trailTitle = it.title }
            }
            if (groups.isEmpty()) {
                Text("当前选材没有这一维度的记录。", color = Muted)
                TextButton(onClick = { lens = null }) { Text("查看全部维度") }
            }
            if (regional) {
                BoxWithConstraints(Modifier.fillMaxWidth()) {
                    val wide = maxWidth >= 540.dp * LocalDensity.current.fontScale.coerceAtLeast(1f)
                    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        key(region, lens, modernOnly) { RegionalTimeline(region, visibleEvents, lens, modernOnly, wide) { detailTitle = it.title } }
                    }
                }
            } else groups.forEach { (_, events) ->
                if (lens != null || slice != null) {
                    RegionComparison(events, showLens = slice != null && lens == null) { detailTitle = it.title }
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
            if (slice == postwarPeriods.last() && lens == null) ChronicleDailyPanel()
        }
    }
    if (picking && regional) HistoryDialog("选择区域", { picking = false }, maxWidth = 460.dp) {
        HistoryRegion.entries.forEach { option ->
            PickerRow(option, option == region) { region = option; modernOnly = false; lens = null; picking = false }
        }
    }
    if (picking && !regional) HistoryTimelineDialog(eraIndex, sliceStart, { picking = false }) { index, start ->
        eraIndex = index
        sliceStart = start
        lens = null
        picking = false
    }
    if (about) HistoryDialog("分期与选材", { about = false }) {
        DialogSection("如何读时间", "九个阶段参照 UCLA 美国国家学校历史中心的世界史教学标准。相邻时段有重叠；跨界事件按主题归入一处。年代前的“约”表示估年。")
        DialogSection("如何读区域", "古代以古埃及、两河流域、南亚和中国为主线。当代选取安理会五个常任理事国，加上印度、日本、德国、巴西及南非等区域样本。地区标签用于串联地理空间，疆界与政治实体随时代变化。")
        DialogSection("如何读选材", "每条记录归入一个主要维度。同一事件可能同时影响多个领域；各阶段展示代表性节点。")
        ReadingLink("查阅世界史分期标准", HISTORY_STANDARD)
    }
    if (openTrail != null) HistoryDialog(openTrail.title, { trailTitle = null }, maxWidth = 656.dp) {
        var expandedStep by rememberSaveable(openTrail.title) { mutableIntStateOf(0) }
        Text(openTrail.subjects, color = Accent, style = MaterialTheme.typography.titleMedium)
        HistoryParagraph(openTrail.introduction)
        Text(openTrail.newsStatus ?: openTrail.span, color = Muted, style = MaterialTheme.typography.labelLarge)
        Column {
            openTrail.steps.forEachIndexed { index, step ->
                Column(Modifier.fillMaxWidth().drawBehind {
                    val point = Offset(6.dp.toPx(), 12.dp.toPx())
                    if (index > 0) drawLine(Outline, Offset(point.x, 0f), point, 2.dp.toPx())
                    if (index < openTrail.steps.lastIndex) drawLine(Outline, point, Offset(point.x, size.height), 2.dp.toPx())
                    drawCircle(Accent, 4.dp.toPx(), point)
                }.padding(start = 24.dp, bottom = 14.dp)) {
                    val expanded = expandedStep == index
                    Row(Modifier.fillMaxWidth().clickable(role = Role.Button) { expandedStep = if (expanded) -1 else index }
                        .semantics { stateDescription = if (expanded) "已展开" else "已收起" }
                        .heightIn(min = 56.dp).padding(vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(step.title, Modifier.weight(1f), color = if (expanded) Accent else Ink, style = MaterialTheme.typography.titleMedium)
                        Text(step.date, Modifier.widthIn(max = 108.dp), color = Muted, style = MaterialTheme.typography.bodySmall)
                        Icon(if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, null, Modifier.size(18.dp), tint = Muted)
                    }
                    if (expanded) {
                        Column(Modifier.padding(top = 4.dp, bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            HistoryParagraph(step.body, prominent = true)
                            ReadingLink(step.source.label, step.source.url)
                        }
                    }
                }
            }
        }
    } else if (detail != null) HistoryDialog(detail.title, { detailTitle = null }, maxWidth = 744.dp) {
        val period = groups.first { detail in it.second }.first
        val periodLabel = when {
            regional -> regionalChronologies.getValue(region).filter { it.id in detail.localPeriodIds }.joinToString(" / ") { it.name }
            period == historyEras.last() -> postwarPeriods.first { it.contains(detail) }.title
            else -> period.name
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
            Text("${detail.date} · ${detail.region.label}", Modifier.weight(1f), color = Accent, style = MaterialTheme.typography.labelLarge)
            Text(detail.lens.label, color = Muted, style = MaterialTheme.typography.labelLarge)
        }
        Text(periodLabel, color = Muted, style = MaterialTheme.typography.bodySmall)
        Column(Modifier.fillMaxWidth().testTag("historyDetailBody").padding(top = 10.dp, bottom = 12.dp)) {
            HistoryParagraph(detail.detail, prominent = true)
        }
        HorizontalDivider(color = Outline)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Text("史料出处", color = Muted, style = MaterialTheme.typography.labelLarge)
            Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) { ReadingLink(detail.reading, detail.url) }
        }
        detail.trail?.let { trail ->
            OutlinedButton(onClick = { trailTitle = trail.title }) { Text("查看完整事件轨迹") }
        }
        HorizontalDivider(color = Outline)
        TextButton(onClick = {
            region = detail.region
            modernOnly = false
            eraIndex = historyEras.indexOf(period)
            sliceStart = if (regional && period == historyEras.last()) postwarPeriods.first { it.contains(detail) }.start else 0
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
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) {
                    Row(Modifier.clip(MaterialTheme.shapes.medium).background(AccentSurface)
                        .clickable(role = Role.Button, onClickLabel = action, onClick = select)
                        .heightIn(min = 48.dp).padding(horizontal = 12.dp, vertical = 5.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Text(title, Modifier.weight(1f, fill = false), color = Ink, style = MaterialTheme.typography.headlineSmall)
                        Spacer(Modifier.width(6.dp))
                        Icon(Icons.Outlined.ExpandMore, action, Modifier.size(20.dp), tint = Accent)
                    }
                }
                if (eraIndex != null) {
                    val colors = IconButtonDefaults.iconButtonColors(contentColor = Accent)
                    IconButton(onClick = { onStep(-1) }, enabled = eraIndex > 0, colors = colors) { Icon(Icons.Outlined.ChevronLeft, "上一阶段") }
                    IconButton(onClick = { onStep(1) }, enabled = eraIndex < historyEras.lastIndex, colors = colors) { Icon(Icons.Outlined.ChevronRight, "下一阶段") }
                }
            }
            Text(date, color = Muted, fontSize = 14.sp)
            HorizontalDivider(Modifier.padding(vertical = 5.dp), color = Outline)
            HistoryParagraph(summary)
        }
    }
}

@Composable private fun RegionComparison(events: List<HistoryEvent>, showLens: Boolean, open: (HistoryEvent) -> Unit) {
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
                                    Text(if (showLens) "${event.date} · ${event.lens.label}" else event.date, color = Muted, style = MaterialTheme.typography.bodySmall)
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(event.title, Modifier.weight(1f), color = Ink, style = MaterialTheme.typography.titleMedium)
                                        Icon(Icons.Outlined.ChevronRight, null, Modifier.size(18.dp), tint = Accent)
                                    }
                                    HistoryParagraph(event.detail.substringBefore('。') + "。", Muted)
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

private val modernRegionLabels = mapOf(HistoryRegion.China to "中国 · 1949年起",
    HistoryRegion.America to "美国 · 二战起", HistoryRegion.Russia to "苏联及俄罗斯 · 二战起")

internal fun HistoryEvent.inModernRegionalHistory(): Boolean {
    val start = if (region == HistoryRegion.China) 1949 else 1939
    return region in modernRegionLabels && (date.take(4).toIntOrNull() ?: 0) >= start
}

@Composable private fun RegionalTimeline(region: HistoryRegion, events: List<HistoryEvent>, lens: HistoryLens?, modernOnly: Boolean,
    wide: Boolean, open: (HistoryEvent) -> Unit) {
    val periods = regionalChronologies.getValue(region)
    val matches = periods.associateWith { period -> events.filter { period.id in it.localPeriodIds } }
    val available = if (lens == null && !modernOnly) periods else periods.filter { matches.getValue(it).isNotEmpty() }
    var selected by rememberSaveable { mutableStateOf<String?>(null) }
    var picking by rememberSaveable { mutableStateOf(false) }
    val current = available.find { it.id == selected }
    val shown = available.filter { current == null || it == current }
    val unit = if (region == HistoryRegion.America) "任期与前史" else "本地分期"
    if (available.isEmpty()) return
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        OutlinedButton(onClick = { picking = true }) {
            Text(current?.name ?: unit)
            Icon(Icons.Outlined.ExpandMore, "选择本地分期", Modifier.size(18.dp))
        }
        if (current != null) TextButton(onClick = { selected = null }) { Text("显示全线") }
    }
    if (picking) HistoryDialog(unit, { picking = false }) {
        if (region == HistoryRegion.America) ReadingLink("总统任期年表 · 弗吉尼亚大学米勒中心", "https://millercenter.org/president")
        if (region == HistoryRegion.China) ReadingLink("古代王朝对照年表 · 大都会艺术博物馆", "https://resources.metmuseum.org/resources/metpublications/pdf/The_Arts_of_Korea_A_Resource_for_Educators.pdf")
        var query by rememberSaveable { mutableStateOf("") }
        if (available.size > 12) OutlinedTextField(query, onValueChange = { query = it }, modifier = Modifier.fillMaxWidth(),
            label = { Text("按名称或年代查找") }, singleLine = true)
        val results = available.filter { it.name.contains(query.trim()) || it.dates.contains(query.trim()) }
        if (results.isEmpty()) Text("没有匹配的分期", color = Muted)
        results.forEach { period ->
            Row(Modifier.fillMaxWidth().clickable(role = Role.Button) { selected = period.id; picking = false }
                .heightIn(min = 48.dp).padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(period.dates, color = Muted, style = MaterialTheme.typography.bodySmall)
                    Text(period.name, color = Ink, style = MaterialTheme.typography.titleMedium)
                }
                if (current == period) Icon(Icons.Outlined.Check, "已选", tint = Accent)
            }
            HorizontalDivider(color = Outline)
        }
    }
    Column(Modifier.fillMaxWidth()) {
        shown.forEachIndexed { index, period ->
            val entries = matches.getValue(period)
            val lead = entries.firstOrNull { it.regionalLead } ?: entries.firstOrNull { it.trail != null } ?: entries.firstOrNull()
            var showMore by rememberSaveable(period.id) { mutableStateOf(false) }
            val preview = current == null && lens == null && entries.size > 1
            val visible = if (preview) listOfNotNull(lead) + if (showMore) entries.filter { it != lead } else emptyList() else entries
            Column(Modifier.fillMaxWidth().drawBehind {
                val node = Offset(8.dp.toPx(), 10.dp.toPx())
                if (index > 0) drawLine(Outline, Offset(node.x, 0f), node, 2.dp.toPx())
                if (index < shown.lastIndex) drawLine(Outline, node, Offset(node.x, size.height), 2.dp.toPx())
                drawCircle(Accent, 5.dp.toPx(), node)
            }.padding(start = 26.dp, bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (wide) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                    Text(period.name, Modifier.weight(1f), color = Ink, style = MaterialTheme.typography.titleLarge)
                    Text(period.dates, color = Accent, style = MaterialTheme.typography.labelLarge)
                } else {
                    Text(period.dates, color = Accent, style = MaterialTheme.typography.labelLarge)
                    Text(period.name, color = Ink, style = MaterialTheme.typography.titleLarge)
                }
                HistoryParagraph(period.summary, Muted)
                if (entries.isNotEmpty()) Surface(Modifier.fillMaxWidth().padding(top = 4.dp), shape = MaterialTheme.shapes.large,
                    color = MaterialTheme.colorScheme.surface, border = BorderStroke(1.dp, Outline)) {
                    Column {
                        visible.forEachIndexed { i, event ->
                            if (i > 0) HorizontalDivider(Modifier.padding(horizontal = 12.dp), color = Outline)
                            Row(Modifier.fillMaxWidth().clickable(role = Role.Button) { open(event) }
                                .heightIn(min = if (i == 0) 60.dp else 52.dp).padding(horizontal = 14.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically) {
                                val overlap = if (event.localPeriodIds.size > 1) " · 跨期" else ""
                                val context = "${event.date} · ${event.lens.label}$overlap"
                                if (wide) {
                                    Text(event.title, Modifier.weight(1f).padding(end = 18.dp), color = Ink, style = MaterialTheme.typography.titleMedium)
                                    Text(context, Modifier.widthIn(max = 180.dp), color = Muted, style = MaterialTheme.typography.bodySmall)
                                } else Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Text(context, color = Muted, style = MaterialTheme.typography.bodySmall)
                                    Text(event.title, color = Ink, style = MaterialTheme.typography.titleMedium)
                                }
                                Icon(Icons.Outlined.ChevronRight, null, Modifier.size(18.dp), tint = Accent)
                            }
                        }
                        if (preview) {
                            HorizontalDivider(Modifier.padding(horizontal = 14.dp), color = Outline)
                            Row(Modifier.fillMaxWidth().testTag("more-${period.id}").clickable(role = Role.Button) { showMore = !showMore }
                                .heightIn(min = 44.dp).padding(horizontal = 14.dp),
                                horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(if (showMore) "收起同期记录" else "另有 ${entries.size - 1} 条同期记录",
                                    color = Muted, style = MaterialTheme.typography.labelLarge)
                                Icon(if (showMore) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, null, Modifier.size(16.dp), tint = Muted)
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

@Composable private fun PickerRow(region: HistoryRegion, selected: Boolean, select: () -> Unit) {
    Row(Modifier.fillMaxWidth().clip(MaterialTheme.shapes.large).background(if (selected) AccentSurface else Color.Transparent)
        .selectable(selected, role = Role.RadioButton, onClick = select).heightIn(min = 52.dp)
        .padding(horizontal = 12.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(region.label, color = if (selected) Accent else Ink, style = MaterialTheme.typography.titleMedium)
            Text(region.scope, color = Muted, style = MaterialTheme.typography.bodySmall)
        }
        if (selected) Icon(Icons.Outlined.Check, "已选", tint = Accent)
    }
}

@Composable private fun DialogSection(title: String, body: String) {
    Text(title, color = Accent, fontWeight = FontWeight.Bold)
    HistoryParagraph(body)
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
