package cn.xux.cloudworkbench

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowUpward
import androidx.compose.material.icons.outlined.Keyboard
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

@Composable
internal fun ConversationComposer(vm: WorkbenchModel, onEditorFocusChanged: (Boolean) -> Unit) {
    val key = vm.draftKey
    val speech = vm.dictation
    val stage = speech.stage
    val ownsRecording = speech.owner == key
    val listening = ownsRecording && stage == OfflineDictation.Stage.Listening
    val context = LocalContext.current
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val requester = remember { FocusRequester() }
    var editing by rememberSaveable { mutableStateOf(false) }
    var requestFocus by remember { mutableStateOf(false) }
    var permissionDenied by remember { mutableStateOf(false) }
    var requestingPermission by remember { mutableStateOf(false) }
    var elapsed by remember { mutableIntStateOf(0) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        requestingPermission = false
        permissionDenied = !granted
        if (granted) vm.startDictation(key)
    }
    DisposableEffect(speech, key) {
        onDispose { speech.stop(key) }
    }
    DisposableEffect(editing) {
        onDispose { if (editing) onEditorFocusChanged(false) }
    }
    LaunchedEffect(editing, requestFocus) {
        if (editing && requestFocus) {
            requester.requestFocus()
            keyboard?.show()
            requestFocus = false
        }
    }
    LaunchedEffect(listening) {
        elapsed = 0
        if (listening) {
            val start = android.os.SystemClock.elapsedRealtime()
            while (true) {
                delay(1000)
                elapsed = ((android.os.SystemClock.elapsedRealtime() - start) / 1000).toInt()
            }
        }
    }

    val canAct = vm.connected && vm.hostOnline && !vm.connecting && !vm.busy
    val canSend = canAct && stage == null && !vm.importingAttachment && vm.activeThread == null &&
        (vm.draft.isNotBlank() || vm.draftImages.isNotEmpty())
    val partial = speech.partial.takeIf { ownsRecording }.orEmpty()
    val images = vm.draftImages
    if (editing || vm.draft.isNotBlank() || partial.isNotBlank() || listening || images.isNotEmpty()) {
        Surface(color = Background, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.testTag("draftContent")) {
                if (editing) {
                    TextField(
                        value = vm.draft,
                        onValueChange = { if (it.length <= MAX_DRAFT_LENGTH) vm.draft = it },
                        modifier = Modifier.fillMaxWidth().focusRequester(requester)
                            .onFocusChanged { onEditorFocusChanged(it.isFocused) }.testTag("messageInput"),
                        placeholder = { Text("给 Codex 发消息…") },
                        maxLines = 5,
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent,
                            focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent
                        )
                    )
                } else if (vm.draft.isNotBlank() || partial.isNotBlank() || listening) {
                    val scroll = rememberScrollState()
                    LaunchedEffect(vm.draft, partial, scroll.maxValue) { scroll.scrollTo(scroll.maxValue) }
                    Column(
                        Modifier.fillMaxWidth().heightIn(max = 132.dp).verticalScroll(scroll)
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        if (vm.draft.isNotBlank()) Text(vm.draft,
                            Modifier.fillMaxWidth().testTag("voiceDraft")
                                .clickable(enabled = stage == null, onClickLabel = "编辑草稿") {
                                    editing = true
                                    requestFocus = true
                                }, style = MaterialTheme.typography.bodyLarge)
                        if (partial.isNotBlank()) Text(partial, Modifier.testTag("voicePartial"),
                            color = Green, style = MaterialTheme.typography.bodyLarge)
                        else if (listening && vm.draft.isBlank()) Text("请开始说话", color = Muted)
                    }
                }
                DraftImageTray(images, canRemove = !vm.busy && !vm.importingAttachment, onRemove = vm::removeDraftImage)
            }
        }
    }

    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        val finishing = stage == OfflineDictation.Stage.Finishing
        val canRecord = !requestingPermission && !vm.busy && !vm.importingAttachment && vm.draft.length < MAX_DRAFT_LENGTH
        FilledTonalIconButton(
            onClick = {
                if (stage != null) {
                    speech.stop(key)
                } else {
                    editing = false
                    focus.clearFocus()
                    keyboard?.hide()
                    permissionDenied = false
                    speech.clearError()
                    if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                        vm.startDictation(key)
                    } else {
                        requestingPermission = true
                        permission.launch(Manifest.permission.RECORD_AUDIO)
                    }
                }
            },
            enabled = if (stage == null) canRecord else ownsRecording && !finishing,
            colors = if (listening) IconButtonDefaults.filledTonalIconButtonColors(
                containerColor = MaterialTheme.colorScheme.errorContainer,
                contentColor = MaterialTheme.colorScheme.onErrorContainer
            ) else IconButtonDefaults.filledTonalIconButtonColors(containerColor = SoftGreen, contentColor = Green),
            modifier = Modifier.size(48.dp).testTag("voiceToggle")
        ) {
            val label = when {
                finishing -> "正在整理转写…"
                stage == OfflineDictation.Stage.Preparing -> "正在启动 · 点击取消"
                stage == OfflineDictation.Stage.Listening -> "正在听 · 点击结束"
                else -> "点击说话"
            }
            Icon(if (ownsRecording) Icons.Outlined.Stop else Icons.Outlined.Mic, label, Modifier.size(22.dp))
        }
        if (listening) {
            Box(Modifier.size(6.dp).background(MaterialTheme.colorScheme.error, CircleShape))
            Text("%d:%02d".format(elapsed / 60, elapsed % 60), style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onErrorContainer)
        }
        AttachmentButton(vm, enabled = stage == null && !vm.busy && !vm.importingAttachment) {
            focus.clearFocus()
            keyboard?.hide()
        }
        FilledTonalIconButton(
            onClick = {
                if (editing) {
                    editing = false
                    focus.clearFocus()
                    keyboard?.hide()
                } else {
                    editing = true
                    requestFocus = true
                }
            },
            enabled = stage == null,
            modifier = Modifier.testTag("keyboardToggle"),
            colors = IconButtonDefaults.filledTonalIconButtonColors(
                containerColor = if (editing) SoftGreen else Background, contentColor = Ink
            )
        ) { Icon(Icons.Outlined.Keyboard, if (editing) "收起键盘输入" else "键盘输入或修改草稿", Modifier.size(20.dp)) }
        Spacer(Modifier.weight(1f))
        if (vm.running) {
            FilledTonalIconButton(onClick = { vm.currentId?.let(vm::cancel) }, enabled = canAct, modifier = Modifier.size(48.dp)) {
                Icon(Icons.Outlined.Stop, "取消任务")
            }
        } else {
            FilledIconButton(onClick = { vm.send() }, enabled = canSend, modifier = Modifier.size(48.dp).testTag("send")) {
                Icon(Icons.Outlined.ArrowUpward, "发送", Modifier.size(20.dp))
            }
        }
    }
    val error = if (permissionDenied) "请在系统设置中允许麦克风权限，或使用键盘输入" else speech.error
    error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
    if (vm.importingAttachment) {
        Text("正在读取附件…", color = Muted, style = MaterialTheme.typography.bodySmall)
    }
    vm.attachmentError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
}
