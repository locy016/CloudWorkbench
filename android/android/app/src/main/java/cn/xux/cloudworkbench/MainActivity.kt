package cn.xux.cloudworkbench

import android.net.ConnectivityManager
import android.net.Network
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        enterFullscreen()
        setContent { WorkbenchTheme { Workbench() } }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) enterFullscreen()
    }

    private fun enterFullscreen() {
        WindowCompat.getInsetsController(window, window.decorView).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }
    }
}

@Composable fun Workbench(vm: WorkbenchModel = viewModel()) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val context = LocalContext.current.applicationContext
    DisposableEffect(lifecycle, vm) {
        val manager = context.getSystemService(ConnectivityManager::class.java)
        var network = manager.activeNetwork
        var observing = true
        vm.defaultNetworkChanged(network?.networkHandle)
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(available: Network) {
                if (!observing) return
                network = available
                vm.defaultNetworkChanged(available.networkHandle)
            }

            override fun onLost(lost: Network) {
                if (!observing || network != lost) return
                network = null
                vm.defaultNetworkChanged(null)
            }
        }
        manager.registerDefaultNetworkCallback(callback, Handler(Looper.getMainLooper()))
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_START) vm.foreground(true)
            if (event == Lifecycle.Event.ON_STOP) vm.foreground(false)
        }
        lifecycle.addObserver(observer)
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) vm.foreground(true)
        onDispose {
            observing = false
            manager.unregisterNetworkCallback(callback)
            lifecycle.removeObserver(observer)
            vm.foreground(false)
        }
    }
    var menuOpen by remember { mutableStateOf(false) }
    var connectionHelp by rememberSaveable { mutableStateOf(false) }
    var browserOpened by remember { mutableStateOf(vm.tab == WorkbenchPage.Browse) }
    val browserVisible = vm.tab == WorkbenchPage.Browse
    LaunchedEffect(browserVisible) { if (browserVisible) browserOpened = true }
    Surface(Modifier.fillMaxSize(), color = Background) {
        BoxWithConstraints(Modifier.fillMaxSize().safeDrawingPadding().imePadding()) {
            val keyboardOpen = WindowInsets.ime.getBottom(LocalDensity.current) > 0
            val pagePadding = if (maxWidth < 600.dp) 16.dp else 24.dp
            val menuButton: (@Composable () -> Unit)? = if (keyboardOpen) null else {
                { MenuButton { menuOpen = true } }
            }
            CompositionLocalProvider(LocalPagePadding provides pagePadding) {
                Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
                    if (!keyboardOpen && !browserVisible) {
                        Row(Modifier.fillMaxWidth().padding(start = 6.dp, end = pagePadding).heightIn(min = 48.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            menuButton?.invoke()
                            Spacer(Modifier.width(6.dp))
                            if (vm.tab == WorkbenchPage.Sessions) {
                                PageTitle(vm.tab)
                                Spacer(Modifier.width(12.dp))
                                ProjectSections(vm, Modifier.weight(1f))
                            } else PageTitle(vm.tab, Modifier.weight(1f))
                            ConnectionStatus(vm)
                        }
                        HorizontalDivider(color = Outline)
                    }
                    if ((vm.connecting || vm.reconnecting) && vm.tab != WorkbenchPage.Cloud) {
                        LinearProgressIndicator(Modifier.fillMaxWidth().height(2.dp))
                    }
                    vm.error?.let { message ->
                        Surface(Modifier.fillMaxWidth().padding(horizontal = pagePadding, vertical = 4.dp), color = MaterialTheme.colorScheme.errorContainer, shape = RoundedCornerShape(12.dp)) {
                            Row(Modifier.padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(message, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onErrorContainer)
                                IconButton(onClick = { vm.clearError() }) { Icon(Icons.Outlined.Close, "关闭提示") }
                            }
                        }
                    }
                    Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
                        if (browserVisible || browserOpened) {
                            val browserLayout = if (browserVisible) Modifier.fillMaxSize() else Modifier.layout { measurable, constraints ->
                                // Keep the live document and its scroll position without placing the browser on other screens.
                                measurable.measure(constraints)
                                layout(0, 0) {}
                            }
                            Box(browserLayout) { BrowserPage(vm, visible = browserVisible, menuButton = menuButton.takeIf { browserVisible }) }
                        }
                        if (!browserVisible) Box(Modifier.widthIn(max = 1120.dp).fillMaxWidth().fillMaxHeight()) {
                            when (vm.tab) {
                                WorkbenchPage.Cloud -> Dashboard(vm)
                                WorkbenchPage.Sessions -> ProjectPage(vm)
                                WorkbenchPage.Settings -> Settings(vm, connectionHelp) { connectionHelp = it }
                                WorkbenchPage.Browse -> Unit
                            }
                        }
                    }
                }
                if (menuOpen) {
                    BackHandler { menuOpen = false }
                    Box(Modifier.matchParentSize().background(Color.Black.copy(alpha = .32f))
                        .clickable(onClickLabel = "收起菜单") { menuOpen = false })
                    Navigation(vm, onClose = { menuOpen = false })
                }
            }
        }
    }
}

@Composable private fun MenuButton(onClick: () -> Unit) {
    IconButton(onClick = onClick, modifier = Modifier.testTag("menuToggle"),
        colors = IconButtonDefaults.iconButtonColors(contentColor = Ink)) {
        Icon(Icons.Outlined.Menu, "打开菜单")
    }
}

@Composable private fun ProjectPicker(vm: WorkbenchModel, modifier: Modifier = Modifier) {
    var open by remember { mutableStateOf(false) }
    val id = vm.projectId
    val windowWidth = with(LocalDensity.current) { LocalWindowInfo.current.containerSize.width.toDp() }
    val menuWidth = (windowWidth - 16.dp).coerceIn(1.dp, 344.dp)
    val canSwitch = vm.hostOnline && !vm.busy &&
        (vm.activeProjects.size > 1 || vm.selectedConversationArchived && vm.activeProjects.isNotEmpty())
    Box(modifier) {
        Row(Modifier.testTag("projectPicker").clip(RoundedCornerShape(12.dp)).background(Background)
            .clickable(enabled = canSwitch, onClickLabel = "切换工作区", role = Role.Button) { open = true }
            .heightIn(min = 48.dp).padding(horizontal = 10.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(Icons.Outlined.FolderOpen, null, Modifier.size(18.dp), tint = Accent)
            Text(vm.projectName(id), Modifier.weight(1f, fill = false), style = MaterialTheme.typography.bodyMedium,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (canSwitch) Icon(Icons.Outlined.ExpandMore, null, Modifier.size(16.dp), tint = Muted)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false },
            modifier = Modifier.width(menuWidth).testTag("workspacePickerMenu")) {
            vm.activeProjects.forEach { project ->
                val id = project.getString("id")
                DropdownMenuItem(
                    text = {
                        Column(Modifier.padding(vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(project.getString("name"))
                            project.optString("path").takeIf { it.isNotBlank() }?.let { path ->
                                Text(path, style = MaterialTheme.typography.bodySmall, color = Muted,
                                    fontFamily = FontFamily.Monospace)
                            }
                        }
                    },
                    leadingIcon = { if (id == vm.projectId) Icon(Icons.Outlined.Check, "当前工作区") },
                    onClick = { open = false; vm.selectProject(id) },
                    modifier = Modifier.testTag("project-$id")
                )
            }
        }
    }
}

@Composable internal fun PageTitle(page: WorkbenchPage, modifier: Modifier = Modifier) {
    Text(page.title, modifier.testTag("pageTitle"), style = MaterialTheme.typography.titleMedium, maxLines = 1)
}

@Composable private fun Navigation(vm: WorkbenchModel, onClose: () -> Unit) {
    Surface(Modifier.width(248.dp).fillMaxHeight(), color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(topEnd = 20.dp, bottomEnd = 20.dp), shadowElevation = 8.dp) {
        Column(Modifier.fillMaxHeight().padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(Modifier.fillMaxWidth().padding(start = 12.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("云端工作台", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                IconButton(onClick = onClose, modifier = Modifier.testTag("menuClose")) { Icon(Icons.Outlined.MenuOpen, "隐藏菜单") }
            }
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                WorkbenchPage.entries.filter { it != WorkbenchPage.Settings }.forEach { page ->
                    NavigationItem(vm, page, onClose)
                }
            }
            HorizontalDivider(color = Outline)
            NavigationItem(vm, WorkbenchPage.Settings, onClose)
        }
    }
}

@Composable private fun NavigationItem(vm: WorkbenchModel, page: WorkbenchPage, onClose: () -> Unit) {
    val icon = when (page) {
        WorkbenchPage.Cloud -> Icons.Outlined.CloudQueue
        WorkbenchPage.Sessions -> Icons.Outlined.Forum
        WorkbenchPage.Browse -> Icons.Outlined.Language
        WorkbenchPage.Settings -> Icons.Outlined.Settings
    }
    NavigationDrawerItem(label = { Text(page.title, maxLines = 1) }, selected = vm.tab == page,
        onClick = { vm.tab = page; onClose() }, icon = { Icon(icon, null, Modifier.size(20.dp)) },
        shape = RoundedCornerShape(14.dp), modifier = Modifier.testTag("nav-${page.title}"))
}

@Composable private fun StatusPill(text: String, good: Boolean) {
    Surface(color = if (good) AccentSurface else Background, shape = RoundedCornerShape(30.dp)) {
        Row(Modifier.padding(horizontal = 9.dp, vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(6.dp).background(if (good) Accent else Muted, RoundedCornerShape(3.dp)))
            Text(text, Modifier.padding(start = 7.dp), color = if (good) Accent else Muted, fontSize = 12.sp)
        }
    }
}
private fun bytes(n: Double): String = if (n >= 1073741824) String.format(Locale.US, "%.1f GB", n / 1073741824) else String.format(Locale.US, "%.0f MB", n / 1048576)
private fun whenText(ms: Long) = SimpleDateFormat("MM-dd HH:mm", Locale.CHINA).format(Date(ms))
private fun overviewTime(ms: Long) = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA).format(Date(ms))
private fun stateText(s: String) = mapOf("idle" to "已完成", "running" to "执行中", "starting" to "准备中", "approval" to "等待确认", "failed" to "失败", "interrupted" to "已中断")[s] ?: s

@Composable private fun Dashboard(vm: WorkbenchModel) {
    var showAllDrafts by rememberSaveable { mutableStateOf(false) }
    val drafts = vm.savedDrafts
    PageColumn {
        val heroShape = MaterialTheme.shapes.large
        Column(
            Modifier.fillMaxWidth().clip(heroShape)
                .background(Brush.horizontalGradient(listOf(Background, Color(0xFFDCEBFF))))
                .border(1.dp, Outline, heroShape).padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(Icons.Outlined.Dns, null, Modifier.size(18.dp), tint = Accent)
                Text(vm.hostName, Modifier.weight(1f), style = MaterialTheme.typography.labelLarge, maxLines = 1)
                StatusPill(if (vm.hostOnline) "工作台服务可用" else serverConnectionStatus(vm), vm.hostOnline)
            }
            Text("云端工作台", style = MaterialTheme.typography.headlineSmall)
            Text(WORKBENCH_DOMAIN, color = Muted, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
            ActionRow(Modifier.padding(top = 8.dp)) {
                Button(onClick = { if (!vm.configured) vm.tab = WorkbenchPage.Settings else if (vm.hostOnline) vm.openProject() else vm.connect() },
                    enabled = !vm.connecting && !vm.reconnecting && !vm.busy &&
                        (vm.hostOnline || !vm.configured || canRetryServer(vm))) {
                    Text(when {
                        vm.connecting -> "正在连接…"
                        vm.reconnecting -> "正在重连…"
                        !vm.configured -> "连接服务器"
                        vm.hostOnline && vm.currentId != null -> "继续上次会话"
                        vm.hostOnline -> "与 Codex 对话"
                        vm.connected -> "工作台服务暂不可用"
                        else -> "重试连接"
                    }, maxLines = 1)
                }
            }
        }
        if (drafts.isNotEmpty()) {
            Panel(Modifier.fillMaxWidth().testTag("savedDrafts")) {
                Text("继续草稿", style = MaterialTheme.typography.titleMedium)
                drafts.take(3).forEach { draft -> SavedDraftRow(draft, !vm.busy) { vm.openSavedDraft(draft) } }
                if (drafts.size > 3) TextButton(onClick = { showAllDrafts = true }) { Text("查看全部 ${drafts.size} 条草稿") }
            }
        }
        if (vm.connecting && vm.serverOverview == null) {
            ConnectingDevice(vm.hostName)
            return@PageColumn
        }
        val continuations = vm.continuations
        if (continuations.isNotEmpty()) {
            Panel(Modifier.fillMaxWidth().testTag("taskContinuation")) {
                Text("继续任务", style = MaterialTheme.typography.titleMedium)
                continuations.forEach { ContinuationRow(vm, it) }
            }
        }
        val results = vm.latestResults
        if (results.isNotEmpty()) {
            Panel(Modifier.fillMaxWidth().testTag("latestTaskResults")) {
                Text("最近任务结果", style = MaterialTheme.typography.titleMedium)
                results.forEach { ContinuationRow(vm, it) }
            }
        }
        ServerOverviewPanel(vm)
    }
    if (showAllDrafts && drafts.isNotEmpty()) {
        AlertDialog(onDismissRequest = { showAllDrafts = false }, title = { Text("未发送的草稿") }, text = {
            LazyColumn(Modifier.heightIn(max = 360.dp)) {
                items(drafts, key = { it.key }) { draft -> SavedDraftRow(draft, !vm.busy) {
                    showAllDrafts = false
                    vm.openSavedDraft(draft)
                } }
            }
        }, confirmButton = { TextButton(onClick = { showAllDrafts = false }) { Text("关闭") } })
    }
}

@Composable private fun ServerOverviewPanel(vm: WorkbenchModel) {
    val sample = vm.serverOverview
    val live = vm.overviewLive
    Panel(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("服务器概览", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
            TextButton(onClick = vm::refreshOverviewNow, enabled = vm.canRefreshOverview,
                modifier = Modifier.testTag("overviewRefresh")) {
                Icon(Icons.Outlined.Refresh, null, Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp))
                Text(if (vm.overviewRefreshing) "正在采集" else "刷新")
            }
        }
        if (sample != null) {
            if (!live) Text("上次采集状态", Modifier.testTag("overviewStale"), color = Amber,
                style = MaterialTheme.typography.labelLarge)
            Text("采集于 ${overviewTime(sample.sampledAt)}", Modifier.testTag("overviewSampleTime"),
                color = Muted, style = MaterialTheme.typography.bodySmall)
        }
        if (vm.overviewUnavailable && vm.connected && !vm.reconnecting) {
            Text("本次采集失败，可手动刷新", color = Amber, style = MaterialTheme.typography.bodySmall)
        } else if (sample == null && !vm.overviewRefreshing) {
            Text(if (vm.hostOnline) "等待首次采集" else "连接服务器后采集状态",
                color = Muted, style = MaterialTheme.typography.bodySmall)
        }
    }
    if (sample != null) {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            if (maxWidth >= 840.dp) {
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    CoreServices(sample.services, Modifier.weight(1f))
                    DashboardMetrics(sample.metrics, live, Modifier.weight(1f))
                }
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    CoreServices(sample.services, Modifier.fillMaxWidth())
                    DashboardMetrics(sample.metrics, live, Modifier.fillMaxWidth())
                }
            }
        }
        RecentOverviewIncidents(sample.incidents, live)
    }
}

@Composable private fun RecentOverviewIncidents(incidents: List<OverviewIncident>?, live: Boolean) {
    Panel(Modifier.fillMaxWidth().testTag("overviewIncidents")) {
        Text("最近异常", style = MaterialTheme.typography.titleMedium)
        if (incidents == null) {
            Text("当前服务器暂未提供异常摘要", color = Muted, style = MaterialTheme.typography.bodySmall)
            return@Panel
        }
        Text(if (live) "近 24 小时 · systemd 定时采集" else "上次采集时的近 24 小时 · systemd",
            color = Muted, style = MaterialTheme.typography.bodySmall)
        if (incidents.isEmpty()) Text("未记录到异常状态", color = Muted)
        incidents.forEach { incident ->
            Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Column(Modifier.weight(1f)) {
                    Text(serviceDetails[incident.service.name]?.title ?: incident.service.name,
                        style = MaterialTheme.typography.bodyMedium)
                    Text("发现于 ${overviewTime(incident.observedAt)}", color = Muted,
                        style = MaterialTheme.typography.bodySmall)
                }
                StatusPill(serviceStates[incident.service.state] ?: "状态未知", false)
            }
        }
    }
}

@Composable private fun SavedDraftRow(draft: SavedDraft, enabled: Boolean, open: () -> Unit) {
    Row(Modifier.fillMaxWidth().clip(MaterialTheme.shapes.small).testTag("draft-${draft.key}")
        .clickable(enabled = enabled, onClickLabel = "继续编辑草稿", role = Role.Button, onClick = open)
        .heightIn(min = 56.dp).padding(horizontal = 4.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Column(Modifier.weight(1f)) {
            Text(draft.title, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val details = listOfNotNull(draft.projectName.ifBlank { null },
                draft.images.takeIf { it > 0 }?.let { "$it 张图片" },
                draft.updatedAt.takeIf { it > 0 }?.let(::whenText)).joinToString(" · ")
            if (details.isNotEmpty()) Text(details, color = Muted, style = MaterialTheme.typography.bodySmall, maxLines = 1)
            if (draft.preview.isNotEmpty()) Text(draft.preview, color = Muted, style = MaterialTheme.typography.bodySmall,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Icon(Icons.Outlined.ChevronRight, null, Modifier.size(18.dp), tint = Muted)
    }
}

@Composable private fun ContinuationRow(vm: WorkbenchModel, item: Continuation) {
    val enabled = !vm.busy && (vm.hostOnline || (item.approvalId == null && vm.currentId == item.id && vm.current != null))
    val tag = when {
        item.approvalId != null -> "home-approval-${item.approvalId}"
        item.updated != null -> "home-result-${item.id}"
        else -> "home-task-${item.id}"
    }
    val label = when {
        item.approvalId != null -> "查看待处理请求"
        item.updated != null -> "查看任务结果"
        else -> "打开会话"
    }
    Row(Modifier.fillMaxWidth().clip(MaterialTheme.shapes.small).testTag(tag)
        .clickable(enabled = enabled, role = Role.Button, onClickLabel = label) { vm.openContinuation(item) }
        .heightIn(min = 52.dp).padding(horizontal = 4.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Column(Modifier.weight(1f)) {
            Text(item.title, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            val project = vm.projectName(item.project)
            Text(item.updated?.takeIf { it > 0L }?.let { "$project · ${whenText(it)}" } ?: project,
                color = Muted, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        StatusPill(item.state, item.state == "执行中" || item.state == "已完成")
        Icon(Icons.Outlined.ChevronRight, null, Modifier.size(18.dp), tint = Muted)
    }
}

@Composable private fun DashboardMetrics(s: JSONObject, live: Boolean, modifier: Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        val memory = s.getJSONObject("memory")
        val disk = s.getJSONObject("disk")
        val diskUsed = disk.optDouble("used", disk.getDouble("total") - disk.getDouble("available"))
        Text(if (live) "运行指标" else "上次运行指标", style = MaterialTheme.typography.titleMedium)
        MetricGrid(listOf(
            MetricValue("内存", bytes(memory.getDouble("used")), "共 ${bytes(memory.getDouble("total"))}", (memory.getDouble("used") / memory.getDouble("total")).toFloat()),
            MetricValue("磁盘", bytes(diskUsed), "共 ${bytes(disk.getDouble("total"))}", (diskUsed / disk.getDouble("total")).toFloat()),
            MetricValue("系统负载", String.format(Locale.US, "%.2f", s.getJSONArray("load").getDouble(0)), "${s.getInt("cpus")} 核 · 最近 1 分钟", null),
            MetricValue("运行时间", "${s.getLong("uptime") / 3600} 小时", "交换空间 ${bytes(s.getJSONObject("swap").getDouble("used"))}", null)
        ))
    }
}

@Composable private fun ConnectingDevice(hostName: String) {
    val rotation by rememberInfiniteTransition(label = "连接服务器").animateFloat(
        initialValue = 0f, targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(1800, easing = LinearEasing)), label = "连接进度"
    )
    Box(Modifier.fillMaxSize().padding(24.dp).testTag("deviceConnecting"), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(Modifier.size(144.dp), contentAlignment = Alignment.Center) {
                Canvas(Modifier.fillMaxSize().padding(5.dp)) {
                    val stroke = Stroke(width = 4.dp.toPx(), cap = StrokeCap.Round)
                    drawCircle(Outline, style = stroke)
                    drawArc(Accent, rotation, 105f, useCenter = false, style = stroke)
                }
                Surface(Modifier.size(104.dp), color = AccentSurface, shape = RoundedCornerShape(36.dp)) {
                    Box(contentAlignment = Alignment.Center) { Icon(Icons.Outlined.Dns, null, Modifier.size(42.dp), tint = Accent) }
                }
            }
            Spacer(Modifier.height(4.dp))
            Text("正在连接服务器", style = MaterialTheme.typography.headlineSmall)
            Text(hostName, style = MaterialTheme.typography.bodyMedium, color = Muted)
        }
    }
}

private data class ServiceDetails(val title: String, val purpose: String, val impact: String)

private val serviceDetails = mapOf(
    "ssh" to ServiceDetails(
        "SSH 安全连接",
        "提供 SSH 密钥鉴权和加密连接。",
        "设备可能无法连接服务器。"
    ),
    "cloud-workbench" to ServiceDetails(
        "云端工作台网关",
        "提供设备状态、项目对话、Codex 执行、附件上传和任务审批。",
        "APP 可能无法读取设备状态、打开会话、发送消息、上传附件或处理审批。"
    ),
    "chrony" to ServiceDetails(
        "系统时间同步",
        "通过 NTP 同步服务器时钟，让日志、会话和任务时间可对照。",
        "时钟可能逐渐漂移。偏差过大时可能影响证书或鉴权校验。"
    ),
    "aliyun" to ServiceDetails(
        "阿里云云助手",
        "接收阿里云控制台下发的远程命令和运维任务。",
        "通过云助手下发的命令和运维任务可能失败。"
    ),
    "cloudmonitor" to ServiceDetails(
        "阿里云主机监控",
        "采集并上报 CPU、内存、磁盘和网络等指标，供阿里云控制台查看和告警。",
        "依赖监控插件的指标可能停止更新，相关告警可能缺少数据。"
    ),
    "tuned" to ServiceDetails(
        "系统性能调优",
        "按配置应用性能、延迟或功耗相关的系统参数。",
        "调优策略可能失效，主要影响服务器性能表现。"
    )
)

private val serviceStates = mapOf(
    "active" to "运行中",
    "inactive" to "已停止",
    "failed" to "运行失败",
    "not-found" to "未找到服务",
    "activating" to "启动中",
    "deactivating" to "停止中",
    "reloading" to "重载中",
    "maintenance" to "维护中",
    "refreshing" to "刷新中"
)

@Composable private fun CoreServices(services: List<OverviewService>, modifier: Modifier) {
    val ordered = services.sortedWith(compareBy<OverviewService> { it.order }.thenBy {
        when (it.name) {
            "ssh" -> 0
            "cloud-workbench" -> 1
            else -> 2
        }
    })
    Panel(modifier) {
        Text("核心服务", style = MaterialTheme.typography.titleMedium)
        ordered.forEachIndexed { index, service ->
            val name = service.name
            val state = service.state
            val details = serviceDetails[name]
            key(name) {
                var expanded by rememberSaveable { mutableStateOf(false) }
                if (index > 0) HorizontalDivider(color = Outline)
                val toggle = if (details == null) Modifier else Modifier.clickable(
                    role = Role.Button,
                    onClickLabel = if (expanded) "收起服务说明" else "展开服务说明"
                ) { expanded = !expanded }
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).then(toggle).testTag("service-$name")
                        .heightIn(min = 48.dp).padding(horizontal = 4.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(details?.title ?: name, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                    StatusPill(serviceStates[state] ?: "状态未知", state == "active")
                    if (details != null) {
                        Icon(
                            if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                            contentDescription = null, modifier = Modifier.size(20.dp), tint = Muted
                        )
                    }
                }
                if (expanded && details != null) {
                    Surface(
                        modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp),
                        color = Background, shape = RoundedCornerShape(12.dp)
                    ) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("$name.service", style = MaterialTheme.typography.bodySmall, color = Muted,
                                fontFamily = FontFamily.Monospace)
                            Text("用途", style = MaterialTheme.typography.labelLarge)
                            Text(details.purpose, style = MaterialTheme.typography.bodyMedium)
                            Spacer(Modifier.height(2.dp))
                            Text("异常影响", style = MaterialTheme.typography.labelLarge)
                            Text(details.impact, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }
        }
    }
}

@Composable private fun Metric(label: String, value: String, hint: String, ratio: Float?, modifier: Modifier) {
    Panel(modifier) {
        Text(label, fontSize = 12.sp, color = Muted)
        Text(value, style = MaterialTheme.typography.headlineSmall)
        if (ratio != null) LinearProgressIndicator(
            progress = { ratio.coerceIn(0f, 1f) },
            modifier = Modifier.fillMaxWidth().height(4.dp),
            color = Accent, trackColor = AccentSurface, gapSize = 0.dp, drawStopIndicator = {}
        )
        Text(hint, fontSize = 12.sp, color = Muted)
    }
}

private data class MetricValue(val label: String, val value: String, val hint: String, val ratio: Float?)

@Composable private fun MetricGrid(values: List<MetricValue>) {
    val fontScale = LocalDensity.current.fontScale
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val columns = when { maxWidth / fontScale >= 880.dp -> 4; maxWidth / fontScale >= 320.dp -> 2; else -> 1 }
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            values.chunked(columns).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    row.forEach { value -> Metric(value.label, value.value, value.hint, value.ratio, Modifier.weight(1f)) }
                }
            }
        }
    }
}

@Composable private fun ProjectPage(vm: WorkbenchModel) {
    if (vm.projectSection == "任务") Tasks(vm) else Conversations(vm)
}

@Composable private fun ProjectSections(vm: WorkbenchModel, modifier: Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        listOf("对话", "任务").forEach { section ->
            val selected = vm.projectSection == section
            val pending = vm.pendingApprovals.size
            val label = if (section == "任务" && pending > 0) "任务 ($pending)" else section
            Box(Modifier.heightIn(min = 48.dp).selectable(selected, role = Role.Tab) { vm.openProject(section) }
                .testTag("project-$section").padding(horizontal = 4.dp), contentAlignment = Alignment.Center) {
                Text(label, Modifier.background(if (selected) AccentSurface else Color.Transparent, RoundedCornerShape(10.dp))
                    .padding(horizontal = 8.dp, vertical = 3.dp), color = if (selected) Accent else Muted,
                    style = MaterialTheme.typography.labelLarge, maxLines = 1)
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable private fun Conversations(vm: WorkbenchModel) {
    var history by rememberSaveable { mutableStateOf(false) }
    var editorFocused by remember { mutableStateOf(false) }
    var historyError by remember { mutableStateOf<String?>(null) }
    val openHistory = {
        vm.dictation.stop()
        historyError = null
        history = true
    }
    val keyboardOpen = WindowInsets.ime.getBottom(LocalDensity.current) > 0
    val floatingKeyboard = editorFocused && WindowInsets.isImeVisible && !keyboardOpen
    // Floating IMEs report visibility without a bottom inset. Keep the focused editor above their usual position.
    val floatingKeyboardSpace = if (floatingKeyboard) minOf(352.dp, LocalConfiguration.current.screenHeightDp.dp * .54f) else 0.dp
    Column(Modifier.fillMaxSize().padding(bottom = floatingKeyboardSpace)) {
        if (!keyboardOpen && !floatingKeyboard) Row(
            Modifier.fillMaxWidth().padding(horizontal = LocalPagePadding.current),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                ProjectPicker(vm, Modifier.weight(1f, fill = false).widthIn(max = 176.dp))
                Text(when {
                    vm.currentId == null -> "未发送的草稿"
                    vm.selectedConversationArchived -> "${vm.conversationTitle} · 历史记录"
                    else -> vm.conversationTitle
                },
                    Modifier.weight(2f).padding(horizontal = 4.dp).testTag("conversationTitle"),
                    style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                IconButton(onClick = openHistory, modifier = Modifier.testTag("conversationHistory")) {
                    Icon(Icons.Outlined.History, "历史会话")
                }
                FilledTonalIconButton(onClick = { vm.newThread() }, enabled = !vm.busy, modifier = Modifier.testTag("newChat")) {
                    Icon(Icons.Outlined.AddComment, "新对话")
                }
            }
        }
        if (!keyboardOpen && !floatingKeyboard && !vm.selectedConversationArchived) {
            Row(Modifier.fillMaxWidth().padding(horizontal = LocalPagePadding.current),
                verticalAlignment = Alignment.CenterVertically) {
                ConversationModelPicker(vm, Modifier.weight(1f))
                if (vm.isCloudAssistant) VoiceChatControl(vm)
            }
        }
        val list = key(vm.currentId) { rememberLazyListState() }
        if (vm.messages.isEmpty() && vm.currentId != null) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text(when {
                    vm.draftThreadMissing -> "原会话已不可用，草稿保存在本机"
                    vm.current != null -> "这个会话还没有消息"
                    vm.reconnecting -> "连接恢复后将继续读取会话"
                    vm.connecting || vm.hostOnline -> "正在读取会话…"
                    else -> "连接服务器后读取会话消息"
                }, color = Muted, style = MaterialTheme.typography.bodyMedium)
            }
        } else if (vm.messages.isEmpty()) {
            BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                val compactEmpty = maxHeight < 300.dp
                val inlinePrompts = compactEmpty && maxWidth >= 640.dp
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(LocalPagePadding.current),
                    verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                    if (compactEmpty) Text("今天想完成什么？", style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(bottom = 8.dp))
                    else EmptyState(Icons.Outlined.Forum, "今天想完成什么？", "查看状态、分析日志，或继续一个开发任务。")
                    FlowRow(Modifier.widthIn(max = if (inlinePrompts) 720.dp else 440.dp).fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp),
                        maxItemsInEachRow = if (inlinePrompts) 2 else 1) {
                        val prompts = listOf(
                            "检查设备与项目状态" to "只读检查当前设备和项目状态，并给出简短建议。",
                            "了解当前项目" to "查看项目目录，介绍已有项目，不修改文件。"
                        )
                        for ((label, prompt) in prompts) {
                            OutlinedButton(onClick = { vm.draft = prompt }, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) {
                                Text(label, Modifier.weight(1f), fontSize = 14.sp, maxLines = 1)
                                Icon(Icons.Outlined.ArrowForward, null, Modifier.size(18.dp))
                            }
                        }
                    }
                }
            }
        } else LazyColumn(Modifier.weight(1f).fillMaxWidth().testTag("messages"), state = list, reverseLayout = true,
            contentPadding = PaddingValues(horizontal = LocalPagePadding.current, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.Bottom)) {
            items(vm.messages.asReversed(), key = { it.getString("id") }) { Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) { Box(Modifier.widthIn(max = 800.dp).fillMaxWidth()) { Message(it) } } }
            if (vm.canLoadOlderMessages) item("older-messages") {
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    TextButton(onClick = vm::loadOlderMessages, enabled = vm.hostOnline && !vm.busy,
                        modifier = Modifier.testTag("loadOlderMessages")) { Text("加载更早的消息") }
                }
            }
        }
        vm.current?.optString("error")?.takeIf { it.isNotBlank() && it != "null" }?.let { Text(it, Modifier.padding(horizontal = LocalPagePadding.current), color = Amber, fontSize = 12.sp) }
        if (!keyboardOpen && vm.pendingApprovals.isNotEmpty()) Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            Surface(onClick = { vm.openProject("任务") }, color = WarmSurface, shape = RoundedCornerShape(14.dp),
                modifier = Modifier.widthIn(max = 824.dp).fillMaxWidth().padding(horizontal = LocalPagePadding.current)) {
                Row(Modifier.padding(horizontal = 12.dp).heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.PendingActions, null, Modifier.size(20.dp), tint = Amber)
                    Text("有 ${vm.pendingApprovals.size} 项请求等待你确认", Modifier.weight(1f).padding(start = 10.dp), color = Amber)
                    Icon(Icons.Outlined.ChevronRight, "查看审批", tint = Amber)
                }
            }
        }
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            Box(Modifier.widthIn(max = 824.dp).fillMaxWidth()
                .padding(horizontal = LocalPagePadding.current, vertical = 8.dp)) {
                if (vm.selectedConversationArchived && !vm.hasDraft) {
                    Surface(color = MaterialTheme.colorScheme.surface, shape = MaterialTheme.shapes.medium) {
                        Text("此会话来自已停用的工作区。可在当前工作区开启新对话。",
                            Modifier.padding(12.dp), color = Muted, style = MaterialTheme.typography.bodySmall)
                    }
                } else {
                    val hint = when {
                        vm.busy -> "正在提交…"
                        vm.submissionHint != null -> vm.submissionHint
                        !vm.hostOnline -> "设备未连接，草稿暂存于应用内"
                        !vm.canSendInConversation -> "当前会话或项目不可用，草稿保存在本机"
                        vm.running -> "任务进行中"
                        vm.activeThread != null -> "另一个会话正在执行任务"
                        else -> null
                    }
                    key(vm.draftKey) { ConversationComposer(vm, statusHint = hint, onEditorFocusChanged = { editorFocused = it }) }
                }
            }
        }
    }
    if (history) ConversationHistory(vm, historyError, onSelect = { id ->
        historyError = null
        vm.select(id, onSuccess = { history = false }, onFailure = { historyError = it })
    }, onClose = { history = false })
}

@Composable private fun ConversationHistory(
    vm: WorkbenchModel, selectError: String?, onSelect: (String) -> Unit, onClose: () -> Unit
) {
    var query by rememberSaveable { mutableStateOf("") }
    var filterProject by rememberSaveable { mutableStateOf<String?>(null) }
    var filterStatus by rememberSaveable { mutableStateOf<String?>(null) }
    var deleteId by rememberSaveable { mutableStateOf<String?>(null) }
    var confirmCleanup by rememberSaveable { mutableStateOf(false) }
    var removing by remember { mutableStateOf(false) }
    var feedback by remember { mutableStateOf<String?>(null) }
    var removeError by remember { mutableStateOf<String?>(null) }
    val canManage = vm.hostOnline && !vm.busy && !vm.connecting && !vm.importingAttachment
    val filter = HistoryFilter(query.trim(), filterProject, filterStatus)
    LaunchedEffect(filter, vm.hostOnline) { vm.loadConversations(filter) }
    DisposableEffect(vm) { onDispose { vm.cancelHistoryLoad() } }
    val sameFilter = vm.historyFilter == filter
    val history = vm.historyThreads.takeIf { sameFilter }.orEmpty()
    val recent = vm.threads.associateBy { it.getString("id") }
    val deleteTarget = history.firstOrNull { it.optString("id") == deleteId }?.let { recent[it.getString("id")] ?: it }
    if (deleteTarget != null || confirmCleanup) {
        val cancel = { deleteId = null; confirmCleanup = false }
        AlertDialog(
            onDismissRequest = { if (!removing) cancel() },
            title = { Text(if (confirmCleanup) "清理空会话？" else "删除会话？") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(if (confirmCleanup) "删除「" + vm.projectName(vm.projectId) + "」中没有消息和附件的空会话。"
                        else "将从云端工作台移除「" + vm.threadTitle(deleteTarget!!) + "」及其消息记录。此操作无法撤销。")
                    removeError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
            },
            confirmButton = {
                TextButton(enabled = canManage && !removing, onClick = {
                    removing = true
                    removeError = null
                    vm.removeConversations(deleteTarget?.optString("id"), onSuccess = { count ->
                        feedback = if (confirmCleanup) {
                            if (count == 0) "没有可清理的空会话" else "已清理 ${count} 个空会话"
                        } else "已删除会话"
                        removing = false
                        cancel()
                    }, onFailure = { removeError = it; removing = false })
                }, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) {
                    Text(if (removing) "正在处理…" else if (confirmCleanup) "清理" else "删除")
                }
            },
            dismissButton = { TextButton(onClick = cancel, enabled = !removing) { Text("取消") } }
        )
        return
    }
    AlertDialog(onDismissRequest = { if (vm.loadingConversation == null) onClose() }, title = { Text("历史会话") }, text = {
        Column {
            OutlinedTextField(query, { query = it.take(120) }, label = { Text("搜索会话标题") }, singleLine = true,
                leadingIcon = { Icon(Icons.Outlined.Search, null, Modifier.size(20.dp)) },
                shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth().testTag("conversationSearch"))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                HistoryFilterMenu("全部项目", filterProject, vm.projects.map { it.getString("id") to it.optString("name", it.getString("id")) },
                    Modifier.weight(1f).testTag("historyProjectFilter")) { filterProject = it }
                val states = listOf("starting", "running", "approval", "idle", "failed", "interrupted")
                HistoryFilterMenu("全部状态", filterStatus, states.map { it to stateText(it) },
                    Modifier.weight(1f).testTag("historyStatusFilter")) { filterStatus = it }
            }
            if (history.isEmpty()) Text(
                when {
                    !vm.hostOnline -> "连接服务器后搜索历史会话"
                    !sameFilter || vm.historyLoading -> "正在搜索历史会话…"
                    vm.historyLoadError != null -> "历史会话读取失败"
                    filter == HistoryFilter() -> "还没有历史会话"
                    else -> "没有匹配的会话"
                }, Modifier.padding(vertical = 16.dp), color = Muted
            )
            val loadError = vm.historyLoadError.takeIf { sameFilter }
            (selectError ?: loadError)?.let {
                Text(it, Modifier.padding(vertical = 8.dp), color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall)
            }
            if (loadError != null && vm.hostOnline && !vm.historyLoading) {
                TextButton(onClick = { vm.loadConversations(filter, firstPage = history.isEmpty()) }) { Text("重试搜索") }
            }
            feedback?.let { Text(it, color = Accent, style = MaterialTheme.typography.bodySmall) }
            LazyColumn(Modifier.heightIn(max = 360.dp).testTag("conversationList")) {
                items(history, key = { it.getString("id") }) { t ->
                    val id = t.getString("id")
                    val selected = id == vm.currentId
                    Row(Modifier.fillMaxWidth().background(if (selected) AccentSurface else Color.Transparent, RoundedCornerShape(12.dp)),
                        verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f).selectable(selected, enabled = vm.hostOnline && !vm.busy && !vm.connecting) {
                            onSelect(id)
                        }.padding(horizontal = 12.dp, vertical = 10.dp)) {
                            Text(vm.threadTitle(t), fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text("${vm.projectName(vm.threadProjectId(t))} · ${stateText(t.getString("status"))} · ${whenText(t.getLong("updated"))}", style = MaterialTheme.typography.bodySmall, color = Muted)
                        }
                        if (vm.loadingConversation == id) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        else if (selected) Icon(Icons.Outlined.Check, "当前会话", tint = Accent)
                        IconButton(onClick = { removeError = null; deleteId = id },
                            enabled = canManage && (recent[id] ?: t).optString("status") !in activeTaskStates) {
                            Icon(Icons.Outlined.DeleteOutline, "删除会话 " + vm.threadTitle(t))
                        }
                    }
                }
                if (sameFilter && vm.canLoadMoreConversations) item("more-conversations") {
                    TextButton(onClick = { vm.loadConversations(filter, firstPage = false) },
                        enabled = canManage && !vm.historyLoading, modifier = Modifier.testTag("loadMoreConversations")) {
                        Text(if (vm.historyLoading) "正在加载…" else "加载更多会话")
                    }
                }
            }
        }
    }, confirmButton = { TextButton(onClick = onClose, enabled = vm.loadingConversation == null) { Text("关闭") } },
        dismissButton = {
            if (vm.canCleanEmptyThreads) {
                TextButton(onClick = { removeError = null; confirmCleanup = true }, enabled = canManage) { Text("清理空会话") }
            }
        })
}

@Composable private fun HistoryFilterMenu(all: String, selected: String?, options: List<Pair<String, String>>,
    modifier: Modifier, select: (String?) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box(modifier) {
        OutlinedButton(onClick = { open = true }, enabled = options.isNotEmpty(), modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 8.dp)) {
            Text(options.find { it.first == selected }?.second ?: selected ?: all, Modifier.weight(1f, fill = false),
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            Icon(Icons.Outlined.ExpandMore, null, Modifier.size(16.dp))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(text = { Text(all) }, onClick = { open = false; select(null) })
            options.forEach { (value, label) ->
                DropdownMenuItem(text = { Text(label) }, onClick = { open = false; select(value) })
            }
        }
    }
}

@Composable private fun Message(m: JSONObject) {
    val role = m.getString("role"); val user = role == "user"; val tool = role in listOf("command", "change")
    var expanded by remember(m.getString("id")) { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (user) Arrangement.End else Arrangement.Start) {
        Column(Modifier.widthIn(max = if (user) 640.dp else 800.dp).testTag("message-$role"),
            horizontalAlignment = if (user) Alignment.End else Alignment.Start) {
            Text(if (user) "你" else if (role == "command") "执行记录" else if (role == "change") "文件变更" else if (role == "system") "系统提示" else "Codex",
                color = Muted, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp))
            Surface(color = if (user) AccentSurface else if (tool) MaterialTheme.colorScheme.surfaceContainerHigh else MaterialTheme.colorScheme.surface,
                shape = RoundedCornerShape(16.dp), border = if (user) null else BorderStroke(1.dp, Outline)) {
                Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                    val text = m.optString("text")
                    SelectionContainer { Text(if (tool && !expanded) text.take(360) else text, fontSize = if (tool) 12.sp else 15.sp, lineHeight = if (tool) 18.sp else 23.sp, fontFamily = if (tool) FontFamily.Monospace else FontFamily.Default) }
                    if (tool && text.length > 360) TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "收起" else "展开完整记录") }
                    if (m.optString("status") == "running") Text("进行中…", color = Accent,
                        style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 8.dp))
                }
            }
        }
    }
}

@Composable private fun Tasks(vm: WorkbenchModel) {
    var stopId by remember { mutableStateOf<String?>(null) }
    val task = vm.activeThread
    val canAct = vm.hostOnline && !vm.busy && !vm.connecting
    val connected = vm.hostOnline && vm.serverDataConnected
    PageColumn {
        val focusedId = vm.focusedApprovalId
        if (focusedId != null) {
            Panel(Modifier.fillMaxWidth().testTag("focusedApproval")) {
                Text(vm.conversationTitle, style = MaterialTheme.typography.titleMedium)
                Text(vm.projectName(vm.threadProjectId(vm.current)), color = Muted, style = MaterialTheme.typography.bodySmall)
                ActionRow {
                    TextButton(onClick = { vm.openProject() }) { Text("查看会话") }
                    TextButton(onClick = { vm.openProject("任务") }) { Text("查看全部任务") }
                }
            }
            val approval = vm.approvals.firstOrNull { it.optString("id") == focusedId }
            if (approval != null) key(focusedId) { ApprovalCard(approval, vm) }
            else Text("此请求已处理或已失效", color = Muted, style = MaterialTheme.typography.bodyMedium)
            return@PageColumn
        }
        if (vm.pendingApprovals.isNotEmpty()) {
            Text("待你确认", style = MaterialTheme.typography.titleMedium, modifier = Modifier.testTag("pendingTaskApprovals"))
            vm.pendingApprovals.forEach { a -> key(a.getString("id")) { ApprovalCard(a, vm) } }
        }
        if (task == null && vm.pendingApprovals.isEmpty()) {
            Row(Modifier.padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(if (connected) Icons.Outlined.TaskAlt else Icons.Outlined.CloudOff, null,
                    modifier = Modifier.size(18.dp), tint = Muted)
                Text(if (connected) "Codex 暂无进行中的任务" else "连接服务器后查看任务和审批",
                    style = MaterialTheme.typography.bodyMedium, color = Muted)
            }
        }
        if (task != null) {
            Panel(Modifier.fillMaxWidth()) {
                val id = task.getString("id")
                val state = task.getString("status")
                TaskPanelHeading("当前 Codex 任务", Icons.Outlined.TaskAlt)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(task.getString("title"), Modifier.weight(1f), fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    TaskBadge(stateText(state), if (state == "approval") TaskTone.Waiting else TaskTone.Active)
                }
                Text(vm.projectName(task.optString("projectId")), color = Muted, style = MaterialTheme.typography.bodySmall)
                ActionRow {
                    FilledTonalButton(onClick = { vm.select(id) }, enabled = canAct) { Text("打开会话") }
                    TextButton(onClick = { stopId = id }, enabled = canAct, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text("停止任务") }
                }
            }
        }
        TaskOperationPanels(vm.releaseApprovals, vm.buildStatus, connected)
    }
    stopId?.let { id ->
        AlertDialog(onDismissRequest = { stopId = null }, title = { Text("停止当前任务？") },
            text = { Text("已产生的会话记录会保留。") },
            confirmButton = { TextButton(onClick = { stopId = null; vm.cancel(id) }, enabled = canAct) { Text("确认停止") } },
            dismissButton = { TextButton(onClick = { stopId = null }) { Text("返回") } })
    }
}
@Composable private fun ApprovalCard(a: JSONObject, vm: WorkbenchModel) {
    val p = a.getJSONObject("params")
    val needsInput = a.getString("method").endsWith("requestUserInput")
    val details = if (needsInput) "" else p.optString("command").takeIf { it != "null" && it.isNotBlank() } ?: p.toString(2)
    var confirm by remember { mutableStateOf(false) }
    var expanded by rememberSaveable(a.getString("id")) { mutableStateOf(false) }
    Panel(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(if (needsInput) Icons.Outlined.ChatBubbleOutline else Icons.Outlined.GppMaybe, null, Modifier.size(20.dp), tint = Amber)
            Text(if (needsInput) "Codex 需要补充信息" else "操作审批", color = Amber, style = MaterialTheme.typography.titleMedium)
        }
        if (needsInput) {
            val answers = remember(a.getString("id")) { mutableStateMapOf<String, String>() }
            val questions = p.optJSONArray("questions")?.objects().orEmpty()
            questions.forEach { q ->
                Text(q.getString("question"), Modifier.padding(top = 12.dp))
                q.optJSONArray("options")?.objects()?.takeIf { it.isNotEmpty() }?.let { options ->
                    ActionRow {
                        options.forEach { option ->
                            TextButton(onClick = { answers[q.getString("id")] = option.getString("label") }) { Text(option.getString("label")) }
                        }
                    }
                }
                OutlinedTextField(value = answers[q.getString("id")] ?: "", onValueChange = { answers[q.getString("id")] = it }, label = { Text("你的回答") }, modifier = Modifier.fillMaxWidth())
            }
            Button(onClick = { vm.answers(a, answers.toMap()) }, enabled = vm.hostOnline && !vm.busy && !vm.connecting && questions.all { !answers[it.getString("id")].isNullOrBlank() }) { Text("提交回答") }
        } else {
            Text(p.optString("reason").takeIf { it != "null" && it.isNotBlank() } ?: "此操作需要你的授权", Modifier.padding(vertical = 8.dp))
            var canExpand by remember(details) { mutableStateOf(false) }
            Surface(Modifier.fillMaxWidth(), color = WarmSurface, shape = RoundedCornerShape(12.dp)) {
                SelectionContainer(Modifier.padding(12.dp)) {
                    Text(details, maxLines = if (expanded) Int.MAX_VALUE else 5, overflow = TextOverflow.Ellipsis,
                        fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall,
                        onTextLayout = { if (!expanded) canExpand = it.hasVisualOverflow })
                }
            }
            ActionRow(Modifier.padding(top = 4.dp)) {
                if (canExpand || expanded) TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "收起详情" else "展开完整操作") }
                OutlinedButton(onClick = { vm.answer(a, "decline") }, enabled = vm.hostOnline && !vm.busy && !vm.connecting) { Text("拒绝") }
                Button(onClick = { confirm = true }, enabled = vm.hostOnline && !vm.busy && !vm.connecting) { Text(if (a.getString("method").contains("permissions")) "本轮允许" else "允许本次") }
            }
        }
    }
    if (confirm) AlertDialog(onDismissRequest = { confirm = false }, title = { Text("确认允许这项操作？") }, text = {
        Column(Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(if (a.getString("method").contains("permissions")) "额外权限将在本轮任务中生效，本轮结束后失效。"
                else "批准后 Codex 将继续执行下方操作。本次授权只适用于当前请求。")
            SelectionContainer {
                Text(details, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
            }
        }
    }, confirmButton = { TextButton(onClick = { confirm = false; vm.answer(a, "accept") }) { Text("确认允许") } },
        dismissButton = { TextButton(onClick = { confirm = false }) { Text("返回") } })
}

private fun serverConnectionStatus(vm: WorkbenchModel): String = when {
    vm.connecting -> "连接中"
    vm.reconnecting -> "正在重连"
    vm.hostOnline -> "已连接"
    vm.connected -> "已连接，工作台服务异常"
    !vm.configured -> "尚未连接"
    else -> "离线"
}

private fun canRetryServer(vm: WorkbenchModel): Boolean =
    vm.configured && !vm.connected && !vm.connecting && !vm.reconnecting && !vm.busy

@Composable private fun Settings(vm: WorkbenchModel, connectionHelp: Boolean, showConnectionHelp: (Boolean) -> Unit) {
    var pendingKey by remember { mutableStateOf<String?>(null) }
    var keyReadError by remember { mutableStateOf(false) }
    var readingKey by remember { mutableStateOf(false) }
    var showMaintenance by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val importKey = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            readingKey = true
            keyReadError = false
            try {
                pendingKey = readWorkbenchPrivateKey(context, uri)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: Exception) {
                keyReadError = true
            } finally {
                readingKey = false
            }
        }
    }
    val canChange = !vm.connecting && !vm.busy && !readingKey
    val serverStatus = serverConnectionStatus(vm)
    val retry = canRetryServer(vm)
    val canMaintain = canChange && !vm.reconnecting
    val showImport = !vm.configured || showMaintenance && !vm.reconnecting
    fun closeHelp() {
        pendingKey = null
        keyReadError = false
        showMaintenance = false
        showConnectionHelp(false)
    }
    LaunchedEffect(vm.connected, connectionHelp) {
        if (vm.connected && connectionHelp) closeHelp()
    }

    PageColumn {
        Panel(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.weight(1f)) { SettingsHeading(Icons.Outlined.Dns, "服务器状态") }
                StatusPill("状态：$serverStatus", vm.connected)
            }
            Text(WORKBENCH_DOMAIN, color = Muted, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
            if (!vm.connected) ActionRow {
                if (retry) {
                    Button(onClick = { vm.connect() }, enabled = !readingKey, modifier = Modifier.testTag("reconnect")) {
                        Text("重试连接")
                    }
                }
                TextButton(onClick = { showConnectionHelp(true) }, enabled = canChange,
                    modifier = Modifier.testTag("connectionHelp")) { Text("连接问题") }
            }
        }
        AppUpdatePanel(vm.appUpdates, vm.connected)
    }
    if (connectionHelp && !vm.connected) AlertDialog(
        onDismissRequest = { closeHelp() },
        title = { Text("连接问题") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("状态：$serverStatus")
                if (showImport) {
                    Text("请使用管理员提供的工作台专用私钥。")
                    OutlinedButton(onClick = { importKey.launch(arrayOf("*/*")) }, enabled = canMaintain,
                        modifier = Modifier.testTag("importPrivateKey")) {
                        Text(if (pendingKey == null) "导入维护密钥" else "已选择密钥 · 重新选择")
                    }
                    if (keyReadError) Text("请选择有效且不带口令的工作台私钥", color = Amber)
                } else {
                    Text(if (vm.reconnecting) "正在恢复服务器连接，请稍候。" else "请检查网络后重试连接。")
                    if (canMaintain) TextButton(onClick = { showMaintenance = true },
                        modifier = Modifier.testTag("connectionMaintenance")) { Text("维护选项") }
                }
                if (!vm.reconnecting) vm.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            if (showImport) TextButton(
                onClick = { pendingKey?.let { key -> vm.connect(key, ::closeHelp) } },
                enabled = canMaintain && pendingKey != null,
                modifier = Modifier.testTag("connect")
            ) { Text(if (vm.connecting) "正在连接…" else "验证并连接") }
            else if (retry) TextButton(onClick = { vm.connect(onSuccess = ::closeHelp) }) { Text("重试连接") }
        },
        dismissButton = { TextButton(onClick = { closeHelp() }) { Text("关闭") } }
    )
}

@Composable internal fun SettingsHeading(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Icon(icon, null, Modifier.size(20.dp), tint = Accent)
        Text(label, style = MaterialTheme.typography.titleMedium)
    }
}
