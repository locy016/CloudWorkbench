package cn.xux.cloudworkbench

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.Sort
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun BrowserStartPage(
    entries: List<BrowserBookmark>, message: String?, onOpen: (BrowserBookmark) -> Unit, onManage: () -> Unit,
    onConnectionAction: (() -> Unit)?, connectionAction: String, onServerData: () -> Unit
) {
    Surface(Modifier.fillMaxSize(), color = Background) {
        LazyColumn(Modifier.fillMaxSize().testTag("browserStart"), horizontalAlignment = Alignment.CenterHorizontally,
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 28.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                FilledTonalButton(onClick = onServerData,
                    modifier = Modifier.widthIn(max = 640.dp).fillMaxWidth().testTag("openServerData")) {
                    Icon(Icons.Outlined.Storage, null, Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("服务器数据")
                }
            }
            item {
                Row(Modifier.widthIn(max = 640.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("常用地址", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                    TextButton(onClick = onManage) { Text("管理书签", maxLines = 1) }
                }
            }
            if (message != null) item {
                Surface(Modifier.widthIn(max = 640.dp).fillMaxWidth(), color = AccentSurface,
                    shape = MaterialTheme.shapes.medium) {
                    Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                        Text(message, color = Muted, style = MaterialTheme.typography.bodyMedium)
                        onConnectionAction?.let { TextButton(onClick = it) { Text(connectionAction, maxLines = 1) } }
                    }
                }
            }
            item {
                val shortcuts = entries.filter { it.shortcut && !it.isFolder }
                if (shortcuts.isEmpty()) {
                    Text("可将书签固定到常用地址", Modifier.widthIn(max = 640.dp).fillMaxWidth(),
                        color = Muted, style = MaterialTheme.typography.bodyMedium)
                } else {
                    FlowRow(Modifier.widthIn(max = 640.dp).fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        shortcuts.forEach { entry ->
                            Column(Modifier.width(92.dp).clip(RoundedCornerShape(12.dp))
                                .background(MaterialTheme.colorScheme.surface).clickable(onClick = { onOpen(entry) })
                                .padding(vertical = 10.dp, horizontal = 6.dp),
                                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                BookmarkIcon(entry, compact = false)
                                Text(entry.title, style = MaterialTheme.typography.labelMedium,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun BrowserBookmarkBar(bookmarks: BrowserBookmarks, onOpen: (BrowserBookmark) -> Boolean) {
    val items = bookmarks.entries.filter { if (it.isFolder) it.parentId == null else it.shortcut }
    if (items.isEmpty()) return
    LazyRow(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface).testTag("bookmarkBar"),
        contentPadding = PaddingValues(horizontal = LocalPagePadding.current),
        horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        items(items, key = { it.id }) { entry ->
            var folderId by remember { mutableStateOf<String?>(null) }
            Box {
                TextButton(onClick = { if (entry.isFolder) folderId = entry.id else onOpen(entry) },
                    contentPadding = PaddingValues(horizontal = 8.dp), modifier = Modifier.widthIn(max = 180.dp)) {
                    Icon(if (entry.isFolder) Icons.Outlined.Folder else Icons.Outlined.Language, null, Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(entry.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                val folder = bookmarks.find(folderId)
                DropdownMenu(expanded = folder != null, onDismissRequest = { folderId = null }) {
                    if (folder != null && folder.id != entry.id) {
                        DropdownMenuItem(text = { Text("上一级") }, leadingIcon = { Icon(Icons.AutoMirrored.Outlined.ArrowBack, null) },
                            onClick = { folderId = folder.parentId })
                    }
                    val children = bookmarks.visible(folderId, "")
                    if (children.isEmpty()) DropdownMenuItem(text = { Text("此文件夹为空") }, onClick = {}, enabled = false)
                    children.forEach { child ->
                        DropdownMenuItem(text = { Text(child.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            leadingIcon = { Icon(if (child.isFolder) Icons.Outlined.Folder else Icons.Outlined.Language, null) },
                            onClick = {
                                if (child.isFolder) folderId = child.id
                                else if (onOpen(child)) folderId = null
                            })
                    }
                }
            }
        }
    }
}

@Composable
internal fun BrowserBookmarkManager(
    bookmarks: BrowserBookmarks, onOpen: (BrowserBookmark) -> Boolean, onClose: () -> Unit
) {
    var folderId by rememberSaveable { mutableStateOf(bookmarks.lastFolder) }
    var query by rememberSaveable { mutableStateOf("") }
    var options by remember { mutableStateOf(false) }
    var editing by rememberSaveable { mutableStateOf<String?>(null) }
    var editingFolder by rememberSaveable { mutableStateOf(false) }
    var moving by rememberSaveable { mutableStateOf<String?>(null) }
    var deleting by rememberSaveable { mutableStateOf<String?>(null) }
    var openError by rememberSaveable { mutableStateOf(false) }
    val folder = bookmarks.find(folderId)
    val visible = bookmarks.visible(folder?.id, query.trim())

    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnBackPress = false)) {
        val focus = LocalFocusManager.current
        fun selectFolder(id: String?) {
            focus.clearFocus()
            folderId = id
            bookmarks.rememberFolder(id)
            query = ""
        }
        fun back() {
            when {
                query.isNotEmpty() -> { query = ""; focus.clearFocus() }
                folder != null -> selectFolder(folder.parentId)
                else -> onClose()
            }
        }
        BackHandler { back() }
        Surface(Modifier.fillMaxSize().testTag("bookmarkManager"), color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.safeDrawingPadding().imePadding()) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp).heightIn(min = 56.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = ::back) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "返回") }
                    Text(folder?.title ?: "书签", Modifier.weight(1f).padding(horizontal = 8.dp),
                        style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    IconButton(onClick = { editingFolder = true; editing = "" }) { Icon(Icons.Outlined.CreateNewFolder, "新建文件夹") }
                    Box {
                        IconButton(onClick = { options = true }) { Icon(Icons.AutoMirrored.Outlined.Sort, "排序和视图选项") }
                        DropdownMenu(expanded = options, onDismissRequest = { options = false }) {
                            BookmarkOrder.entries.forEach { order ->
                                DropdownMenuItem(text = { Text(order.label) },
                                    leadingIcon = { if (bookmarks.order == order) Icon(Icons.Outlined.Check, "已选") },
                                    onClick = { bookmarks.changeOrder(order); options = false })
                            }
                            HorizontalDivider()
                            DropdownMenuItem(text = { Text(if (bookmarks.compact) "切换到详细视图" else "切换到紧凑视图") },
                                onClick = { bookmarks.toggleCompact(); options = false })
                            DropdownMenuItem(text = { Text(if (bookmarks.barVisible) "隐藏书签栏" else "显示书签栏") },
                                onClick = { bookmarks.toggleBar(); options = false })
                            DropdownMenuItem(text = { Text("添加书签") }, leadingIcon = { Icon(Icons.Outlined.StarBorder, null) },
                                onClick = { options = false; editingFolder = false; editing = "" })
                        }
                    }
                    IconButton(onClick = onClose) { Icon(Icons.Outlined.Close, "关闭书签") }
                }
                HorizontalDivider()
                OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
                    .testTag("bookmarkSearch"), placeholder = { Text("搜索书签") }, singleLine = true,
                    leadingIcon = { Icon(Icons.Outlined.Search, null) },
                    trailingIcon = if (query.isBlank()) null else ({
                        IconButton(onClick = { query = "" }) { Icon(Icons.Outlined.Clear, "清空搜索") }
                    }), shape = RoundedCornerShape(24.dp))
                if (openError) Text("浏览代理就绪后可打开书签", Modifier.padding(horizontal = 16.dp), color = Amber)
                if (visible.isEmpty()) {
                    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                        Text(if (query.isNotBlank()) "没有匹配的书签" else "这里还没有书签", color = Muted)
                    }
                } else {
                    key(folderId) {
                        LazyColumn(Modifier.fillMaxSize().testTag("bookmarkList"), contentPadding = PaddingValues(bottom = 16.dp)) {
                            items(visible, key = { it.id }) { entry ->
                                val manual = bookmarks.order == BookmarkOrder.Manual && query.isBlank()
                                val siblings = if (manual) visible.filter { it.isFolder == entry.isFolder } else emptyList()
                                val index = siblings.indexOf(entry)
                                BookmarkRow(entry, bookmarks.compact,
                                    subtitle = when {
                                        query.isNotBlank() -> listOfNotNull(entry.host.takeIf { it.isNotEmpty() },
                                            bookmarks.find(entry.parentId)?.title).joinToString(" · ")
                                        entry.isFolder -> "${bookmarks.children(entry.id).size} 项"
                                        else -> entry.host
                                    },
                                    onOpen = {
                                        if (entry.isFolder) selectFolder(entry.id)
                                        else if (onOpen(entry)) onClose() else openError = true
                                    },
                                    onEdit = { editingFolder = entry.isFolder; editing = entry.id },
                                    onMove = { moving = entry.id },
                                    onDelete = { deleting = entry.id },
                                    onShiftUp = if (index > 0) ({ bookmarks.shift(entry, -1) }) else null,
                                    onShiftDown = if (index >= 0 && index < siblings.lastIndex) ({ bookmarks.shift(entry, 1) }) else null)
                            }
                        }
                    }
                }
            }
        }
    }

    editing?.let { id ->
        key(id, editingFolder) {
            val entry = bookmarks.find(id)
            if (editingFolder) FolderEditor(entry, onSave = { bookmarks.saveFolder(entry, it, folder?.id); editing = null },
                onClose = { editing = null })
            else BookmarkEditor(entry, onSave = { title, url, shortcut ->
                bookmarks.save(entry, title, url, shortcut, if (entry == null) folder?.id else entry.parentId)
                editing = null
            }, onClose = { editing = null })
        }
    }
    bookmarks.find(moving)?.let { entry ->
        BookmarkDestination(bookmarks, entry, onSave = { bookmarks.move(entry, it); moving = null }, onClose = { moving = null })
    }
    bookmarks.find(deleting)?.let { entry ->
        AlertDialog(onDismissRequest = { deleting = null }, title = { Text(if (entry.isFolder) "删除文件夹？" else "删除书签？") },
            text = { Text(if (entry.isFolder) "「${entry.title}」及其中的书签会被删除。" else entry.title) },
            confirmButton = { TextButton(onClick = { bookmarks.remove(entry); deleting = null }) { Text("删除") } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("取消") } })
    }
}

@Composable
private fun BookmarkIcon(entry: BrowserBookmark, compact: Boolean) {
    val size = if (compact) 32.dp else 44.dp
    Box(Modifier.size(size).background(if (entry.isFolder) WarmSurface else AccentSurface, CircleShape),
        contentAlignment = Alignment.Center) {
        if (entry.isFolder) Icon(Icons.Outlined.FolderOpen, null, Modifier.size(20.dp), tint = Amber)
        else Text(entry.host.removePrefix("www.").take(1).uppercase().ifEmpty { "·" }, color = Accent,
            style = MaterialTheme.typography.titleMedium)
    }
}

@Composable
private fun BookmarkRow(
    entry: BrowserBookmark, compact: Boolean, subtitle: String, onOpen: () -> Unit, onEdit: () -> Unit,
    onMove: () -> Unit, onDelete: () -> Unit, onShiftUp: (() -> Unit)?, onShiftDown: (() -> Unit)?
) {
    var menu by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Row(Modifier.weight(1f).clip(RoundedCornerShape(10.dp)).clickable(onClick = onOpen)
            .heightIn(min = if (compact) 48.dp else 64.dp).padding(horizontal = 6.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            BookmarkIcon(entry, compact)
            Column(Modifier.weight(1f)) {
                Text(entry.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (!compact && subtitle.isNotEmpty()) Text(subtitle, style = MaterialTheme.typography.bodySmall,
                    color = Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (entry.isFolder) Icon(Icons.Outlined.ChevronRight, null, tint = Muted)
        }
        Box {
            IconButton(onClick = { menu = true }) { Icon(Icons.Outlined.MoreVert, "${entry.title}的更多操作") }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(text = { Text(if (entry.isFolder) "重命名" else "编辑") }, onClick = { menu = false; onEdit() })
                DropdownMenuItem(text = { Text("移动到…") }, onClick = { menu = false; onMove() })
                onShiftUp?.let { shift -> DropdownMenuItem(text = { Text("上移") }, onClick = { menu = false; shift() }) }
                onShiftDown?.let { shift -> DropdownMenuItem(text = { Text("下移") }, onClick = { menu = false; shift() }) }
                DropdownMenuItem(text = { Text("删除") }, onClick = { menu = false; onDelete() })
            }
        }
    }
}

@Composable
internal fun BookmarkEditor(entry: BrowserBookmark?, onSave: (String, String, Boolean) -> Unit, onClose: () -> Unit) {
    var title by rememberSaveable { mutableStateOf(entry?.title.orEmpty()) }
    var url by rememberSaveable { mutableStateOf(entry?.url.orEmpty()) }
    var shortcut by rememberSaveable { mutableStateOf(entry?.shortcut ?: false) }
    var error by rememberSaveable { mutableStateOf<String?>(null) }
    AlertDialog(onDismissRequest = onClose, title = { Text(if (entry == null) "添加书签" else "编辑书签") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(title, { title = it.take(120) }, Modifier.fillMaxWidth().testTag("bookmarkName"),
                    label = { Text("名称") }, singleLine = true)
                OutlinedTextField(url, { url = it.take(4000); error = null }, Modifier.fillMaxWidth().testTag("bookmarkUrl"),
                    label = { Text("网址") }, singleLine = true, isError = error != null,
                    supportingText = error?.let { message -> { Text(message) } })
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("固定到常用地址和书签栏", Modifier.weight(1f))
                    Switch(checked = shortcut, onCheckedChange = { shortcut = it })
                }
            }
        }, confirmButton = {
            TextButton(enabled = url.isNotBlank(), onClick = {
                try { onSave(title, url, shortcut) } catch (e: IllegalArgumentException) { error = e.message }
            }) { Text("保存") }
        }, dismissButton = { TextButton(onClick = onClose) { Text("取消") } })
}

@Composable
private fun FolderEditor(entry: BrowserBookmark?, onSave: (String) -> Unit, onClose: () -> Unit) {
    var name by rememberSaveable { mutableStateOf(entry?.title.orEmpty()) }
    AlertDialog(onDismissRequest = onClose, title = { Text(if (entry == null) "新建文件夹" else "重命名文件夹") },
        text = {
            OutlinedTextField(name, { name = it.take(120) }, Modifier.fillMaxWidth().testTag("bookmarkFolderName"),
                label = { Text("名称") }, singleLine = true)
        }, confirmButton = { TextButton(onClick = { onSave(name) }, enabled = name.isNotBlank()) { Text("保存") } },
        dismissButton = { TextButton(onClick = onClose) { Text("取消") } })
}

@Composable
private fun BookmarkDestination(bookmarks: BrowserBookmarks, entry: BrowserBookmark, onSave: (String?) -> Unit, onClose: () -> Unit) {
    var parentId by rememberSaveable(entry.id) { mutableStateOf(entry.parentId) }
    val parent = bookmarks.find(parentId)
    val destinations = bookmarks.children(parent?.id).filter { it.isFolder && bookmarks.canMove(entry, it.id) }
    AlertDialog(onDismissRequest = onClose, title = { Text("移动到…") }, text = {
        Column {
            if (parent != null) {
                TextButton(onClick = { parentId = parent.parentId }) {
                    Icon(Icons.AutoMirrored.Outlined.ArrowBack, null, Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("上一级")
                }
            }
            Text(parent?.title ?: "书签", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(8.dp))
            LazyColumn(Modifier.heightIn(max = 260.dp)) {
                items(destinations, key = { it.id }) { folder ->
                    Row(Modifier.fillMaxWidth().testTag("bookmarkDestination-${folder.id}")
                        .clickable { parentId = folder.id }.heightIn(min = 48.dp).padding(8.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(Icons.Outlined.FolderOpen, null, tint = Amber)
                        Text(folder.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }, confirmButton = {
        TextButton(enabled = parentId != entry.parentId && bookmarks.canMove(entry, parentId),
            onClick = { onSave(parentId) }) { Text("移到这里") }
    }, dismissButton = { TextButton(onClick = onClose) { Text("取消") } })
}
