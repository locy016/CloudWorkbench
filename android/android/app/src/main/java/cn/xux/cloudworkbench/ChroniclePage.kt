package cn.xux.cloudworkbench

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog

private val forest = Color(0xFF164A46)
private val paper = Color(0xFFF6F4EB)
private val ivory = Color(0xFFFFFEF8)
private val rule = Color(0xFFD7D8C9)
private val editorialInk = Color(0xFF1D342F)
private val secondaryInk = Color(0xFF52645C)
private val gold = Color(0xFFF2CF8C)
private val rounded = RoundedCornerShape(14.dp)

@Composable internal fun ChroniclePage() {
    var regional by rememberSaveable { mutableStateOf(false) }
    var eraIndex by rememberSaveable { mutableIntStateOf(1) }
    var region by rememberSaveable { mutableStateOf(HistoryRegion.China) }
    var lens by rememberSaveable { mutableStateOf<HistoryLens?>(null) }
    var picking by rememberSaveable { mutableStateOf(false) }
    var about by rememberSaveable { mutableStateOf(false) }
    var detailTitle by rememberSaveable { mutableStateOf<String?>(null) }
    val era = historyEras[eraIndex]
    val groups = historyEras.mapIndexedNotNull { index, period ->
        val matching = period.events.filter { (if (regional) it.region == region else index == eraIndex) && (lens == null || it.lens == lens) }
        if (matching.isEmpty()) null else period to matching
    }
    val detail = groups.flatMap { it.second }.find { it.title == detailTitle }
    val scroll = key(regional, eraIndex, region, lens) { rememberScrollState() }
    Column(Modifier.fillMaxSize().background(paper).verticalScroll(scroll).padding(LocalPagePadding.current),
        verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.weight(1f).selectableGroup(), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                listOf("时代对照", "区域追踪").forEachIndexed { i, label ->
                    val selected = regional == (i == 1)
                    Text(label, Modifier.clip(rounded).background(if (selected) forest else Color.Transparent)
                        .selectable(selected, role = Role.Tab) { if (!selected) { regional = i == 1; lens = null } }
                        .heightIn(min = 44.dp).wrapContentHeight().padding(horizontal = 13.dp),
                        color = if (selected) ivory else secondaryInk, fontWeight = FontWeight.SemiBold)
                }
            }
            IconButton(onClick = { about = true }) { Icon(Icons.Outlined.Info, "分期与选材说明", tint = secondaryInk) }
        }
        HistoryCover(if (regional) "区域追踪" else "世界史分期  ·  ${(eraIndex + 1).toString().padStart(2, '0')} / 09",
            if (regional) region.label else era.name, if (regional) "区域沿革" else era.dates,
            if (regional) region.scope else era.summary, if (regional) "选择区域" else "选择阶段",
            if (regional) null else eraIndex, onStep = { eraIndex += it; lens = null }, select = { picking = true })
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(if (regional) "历时脉络" else "同期动态", color = editorialInk, fontSize = 21.sp, fontWeight = FontWeight.Bold)
                Text(if (regional) "${groups.size} 个阶段 · ${groups.sumOf { it.second.size }} 条记录" else
                    "${groups.flatMap { it.second }.distinctBy { it.region }.size} 个区域 · ${groups.sumOf { it.second.size }} 条记录",
                    color = secondaryInk, style = MaterialTheme.typography.bodySmall)
            }
            var filterOpen by remember { mutableStateOf(false) }
            Box {
                OutlinedButton(onClick = { filterOpen = true }, border = BorderStroke(1.dp, rule), colors = ButtonDefaults.outlinedButtonColors(contentColor = forest)) {
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
        if (groups.isEmpty()) {
            Text("当前选材没有这一维度的记录。", color = secondaryInk)
            TextButton(onClick = { lens = null }) { Text("查看全部维度") }
        }
        groups.forEach { (period, events) ->
            if (regional) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Canvas(Modifier.size(12.dp)) {
                        drawCircle(forest, style = Stroke(1.dp.toPx()))
                        drawCircle(forest, radius = size.width * .22f)
                    }
                    Text(period.name, color = forest, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                    HorizontalDivider(Modifier.weight(1f), color = rule)
                }
            }
            BoxWithConstraints {
                val columns = if (maxWidth >= 600.dp && !regional) 2 else 1
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    events.chunked(columns).forEach { row ->
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            row.forEach { event -> HistoryCard(event, regional, Modifier.weight(1f)) { detailTitle = event.title } }
                            if (row.size < columns) Spacer(Modifier.weight(1f))
                        }
                    }
                }
            }
        }
    }
    if (picking) HistoryDialog(if (regional) "选择区域" else "选择历史阶段", { picking = false }) {
        if (regional) HistoryRegion.entries.forEach { option ->
            PickerRow(option.label, null, option == region) { region = option; lens = null; picking = false }
        } else historyEras.forEachIndexed { index, option ->
            PickerRow(option.name, option.dates, index == eraIndex) { eraIndex = index; lens = null; picking = false }
        }
    }
    if (about) HistoryDialog("分期与选材", { about = false }) {
        DialogSection("如何读时间", "九个阶段参照 UCLA 美国国家学校历史中心的世界史教学标准。相邻时段有重叠；跨界事件按主题归入一处。年代前的“约”表示估年。")
        DialogSection("如何读区域", "古代以古埃及、两河流域、南亚和中国为主线。当代选取安理会五个常任理事国，加上印度、日本、德国和巴西作为观察样本。地区标签用于串联地理空间，疆界与政治实体随时代变化。")
        DialogSection("如何读选材", "每条记录归入一个主要维度。同一事件可能同时影响多个领域；各阶段展示代表性节点。")
        ReadingLink("查阅世界史分期标准", HISTORY_STANDARD)
    }
    if (detail != null) HistoryDialog(detail.title, { detailTitle = null }) {
        val period = groups.first { detail in it.second }.first
        Text("${detail.date}  ·  ${detail.region.label}", color = forest, fontWeight = FontWeight.SemiBold)
        Text("${period.name}   /   ${detail.lens.label}", color = secondaryInk, style = MaterialTheme.typography.bodySmall)
        Text(detail.detail, color = editorialInk, lineHeight = 25.sp)
        if (detail.reading != null && detail.url != null) ReadingLink(detail.reading, detail.url)
        HorizontalDivider(color = rule)
        TextButton(onClick = {
            region = detail.region
            eraIndex = historyEras.indexOf(period)
            regional = !regional
            lens = null
            detailTitle = null
        }) { Text(if (regional) "对照这个阶段的其他区域" else "追踪${detail.region.label}的历史", color = forest) }
    }
}

@Composable private fun HistoryCover(kicker: String, title: String, date: String, summary: String, action: String,
    eraIndex: Int?, onStep: (Int) -> Unit, select: () -> Unit) {
    Box(Modifier.fillMaxWidth().clip(rounded).background(forest)) {
        // A graticule on the cover echoes the geographic scope of the selected folio.
        Canvas(Modifier.align(Alignment.TopEnd).size(126.dp)) {
            val radius = size.width * .55f
            val center = Offset(size.width * .81f, size.height * .22f)
            val line = gold.copy(alpha = .20f)
            val stroke = Stroke(1.dp.toPx())
            rotate(-22f, center) {
                drawCircle(line, radius, center, style = stroke)
                listOf(.32f, .67f).forEach { ratio ->
                    drawOval(line, center - Offset(radius * ratio, radius), Size(radius * ratio * 2, radius * 2), style = stroke)
                    drawOval(line, center - Offset(radius, radius * ratio), Size(radius * 2, radius * ratio * 2), style = stroke)
                }
                drawLine(line, center - Offset(radius, 0f), center + Offset(radius, 0f))
                drawCircle(line, radius * 1.13f, center, style = stroke)
            }
        }
        Column(Modifier.padding(horizontal = 20.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(kicker, color = gold, style = MaterialTheme.typography.labelLarge)
            Text(title, color = ivory, fontSize = 29.sp, lineHeight = 38.sp, fontFamily = FontFamily.Serif, fontWeight = FontWeight.Bold)
            Text(date, color = gold, fontSize = 14.sp)
            HorizontalDivider(Modifier.padding(vertical = 5.dp), color = ivory.copy(alpha = .24f))
            Text(summary, color = ivory, style = MaterialTheme.typography.bodyMedium)
            Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Surface(onClick = select, color = Color.Transparent, shape = RoundedCornerShape(8.dp),
                    border = BorderStroke(1.dp, gold.copy(alpha = .65f))) {
                    Row(Modifier.heightIn(min = 44.dp).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(action, color = gold, style = MaterialTheme.typography.labelLarge)
                        Icon(Icons.Outlined.ExpandMore, null, Modifier.size(18.dp), tint = gold)
                    }
                }
                if (eraIndex != null) {
                    Spacer(Modifier.weight(1f))
                    val colors = IconButtonDefaults.iconButtonColors(contentColor = gold, disabledContentColor = gold.copy(alpha = .28f))
                    IconButton(onClick = { onStep(-1) }, enabled = eraIndex > 0, colors = colors) { Icon(Icons.Outlined.ChevronLeft, "上一阶段") }
                    IconButton(onClick = { onStep(1) }, enabled = eraIndex < historyEras.lastIndex, colors = colors) { Icon(Icons.Outlined.ChevronRight, "下一阶段") }
                }
            }
        }
    }
}

@Composable private fun HistoryCard(event: HistoryEvent, regional: Boolean, modifier: Modifier, open: () -> Unit) {
    val (icon, tint) = when (event.lens) {
        HistoryLens.Governance -> Icons.Outlined.AccountBalance to Color(0xFF805037)
        HistoryLens.Economy -> Icons.Outlined.SwapHoriz to Color(0xFF386859)
        HistoryLens.Society -> Icons.Outlined.Groups to Color(0xFF4D6375)
        HistoryLens.Ideas -> Icons.Outlined.AutoStories to Color(0xFF725579)
        HistoryLens.Technology -> Icons.Outlined.Architecture to Color(0xFF826328)
    }
    Surface(onClick = open, modifier = modifier, shape = rounded, color = ivory, border = BorderStroke(1.dp, rule)) {
        Column(Modifier.padding(15.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, null, Modifier.size(17.dp), tint = tint)
                Text(if (regional) event.date else event.region.label, Modifier.weight(1f).padding(start = 7.dp), color = editorialInk, style = MaterialTheme.typography.labelLarge)
                Text(event.lens.label, color = tint, style = MaterialTheme.typography.bodySmall)
            }
            if (!regional) Text(event.date, color = secondaryInk, style = MaterialTheme.typography.bodySmall)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(event.title, Modifier.weight(1f), fontSize = 17.sp, lineHeight = 24.sp, fontWeight = FontWeight.SemiBold, color = editorialInk)
                Icon(Icons.Outlined.ChevronRight, null, Modifier.size(18.dp), tint = secondaryInk)
            }
        }
    }
}

@Composable private fun HistoryDialog(title: String, close: () -> Unit, body: @Composable ColumnScope.() -> Unit) {
    Dialog(onDismissRequest = close) {
        Surface(shape = rounded, color = ivory, modifier = Modifier.widthIn(max = 480.dp).fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(title, Modifier.weight(1f), color = editorialInk, style = MaterialTheme.typography.titleLarge)
                    IconButton(onClick = close) { Icon(Icons.Outlined.Close, "关闭", tint = secondaryInk) }
                }
                HorizontalDivider(color = rule)
                Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(vertical = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(7.dp), content = body)
            }
        }
    }
}

@Composable private fun PickerRow(title: String, dates: String?, selected: Boolean, select: () -> Unit) {
    Row(Modifier.fillMaxWidth().clip(rounded).background(if (selected) Color(0xFFE1ECE2) else Color.Transparent)
        .selectable(selected, role = Role.RadioButton, onClick = select).heightIn(min = 48.dp).padding(10.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, color = editorialInk, fontWeight = FontWeight.Medium)
            dates?.let { Text(it, color = secondaryInk, style = MaterialTheme.typography.bodySmall) }
        }
        if (selected) Icon(Icons.Outlined.Check, "已选", tint = forest)
    }
}

@Composable private fun DialogSection(title: String, body: String) {
    Text(title, color = forest, fontWeight = FontWeight.Bold)
    Text(body, color = editorialInk, style = MaterialTheme.typography.bodyMedium)
}

@Composable private fun ReadingLink(label: String, url: String) {
    val handler = LocalUriHandler.current
    var unavailable by remember { mutableStateOf(false) }
    TextButton(onClick = { unavailable = runCatching { handler.openUri(url) }.isFailure }) {
        Icon(Icons.AutoMirrored.Outlined.OpenInNew, null, Modifier.size(16.dp), tint = forest)
        Spacer(Modifier.width(6.dp))
        Text(label, color = forest)
    }
    if (unavailable) Text("当前设备无法打开此链接。", color = secondaryInk)
}
