package cn.xux.cloudworkbench

import android.Manifest
import android.content.pm.PackageManager
import android.os.SystemClock
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.GraphicEq
import androidx.compose.material.icons.outlined.Keyboard
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import kotlinx.coroutines.delay

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ConversationComposer(vm: WorkbenchModel, statusHint: String?, onEditorFocusChanged: (Boolean) -> Unit) {
    val key = vm.draftKey
    val speech = vm.dictation
    val stage = speech.stage.takeIf { speech.owner == key }
    val context = LocalContext.current
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val requester = remember { FocusRequester() }
    var voiceMode by rememberSaveable { mutableStateOf(false) }
    var attachments by rememberSaveable { mutableStateOf(false) }
    var requestFocus by remember { mutableStateOf(false) }
    var requestingPermission by remember { mutableStateOf(false) }
    var cancelArmed by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var originalDraft by remember { mutableStateOf<String?>(null) }
    var elapsed by remember { mutableIntStateOf(0) }
    val canRecord = speech.stage == null && !requestingPermission && !vm.busy &&
        !vm.importingAttachment && vm.draft.length < MAX_DRAFT_LENGTH

    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        requestingPermission = false
        message = if (granted) "已允许麦克风，请重新按住说话" else "请在系统设置中允许麦克风权限，或使用键盘输入"
    }
    fun hideEditor() {
        focus.clearFocus()
        keyboard?.hide()
    }
    fun startVoice() {
        if (!canRecord) return
        message = null
        cancelArmed = false
        attachments = false
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            originalDraft = vm.draft
            vm.startDictation(key)
        } else {
            requestingPermission = true
            permission.launch(Manifest.permission.RECORD_AUDIO)
        }
    }
    fun finishVoice(discard: Boolean) {
        if (speech.owner == key && speech.stage != null) {
            if (discard) {
                message = "已取消录音"
                originalDraft = null
                speech.discard(key)
            } else speech.stop(key)
        }
        cancelArmed = false
    }

    DisposableEffect(speech, key) {
        onDispose { speech.stop(key); onEditorFocusChanged(false) }
    }
    LaunchedEffect(requestFocus, voiceMode) {
        if (requestFocus && !voiceMode) {
            requester.requestFocus()
            keyboard?.show()
            requestFocus = false
        }
    }
    LaunchedEffect(stage) {
        if (stage == null) {
            val before = originalDraft
            if (before != null && vm.draft != before) {
                voiceMode = false
                message = "语音已转为草稿，核对后发送"
            }
            originalDraft = null
        }
        if (stage == OfflineDictation.Stage.Listening) {
            val start = SystemClock.elapsedRealtime()
            elapsed = 0
            while (true) {
                delay(1000)
                elapsed = ((SystemClock.elapsedRealtime() - start) / 1000).toInt()
            }
        }
    }
    BackHandler(enabled = attachments || stage == OfflineDictation.Stage.Listening) {
        if (attachments) attachments = false else finishVoice(discard = true)
    }

    val canAct = vm.connected && vm.hostOnline && !vm.connecting && !vm.busy
    val hasDraft = vm.draft.isNotBlank() || vm.draftImages.isNotEmpty()
    val canSend = canAct && speech.stage == null && !vm.importingAttachment && vm.activeThread == null && hasDraft
    val attachmentsEnabled = speech.stage == null && !vm.busy && !vm.importingAttachment
    val error = speech.error ?: vm.attachmentError
    val hint = when {
        cancelArmed -> "松开手指取消录音"
        stage == OfflineDictation.Stage.Preparing -> "正在启动录音…"
        stage == OfflineDictation.Stage.Listening -> {
            val time = "%d:%02d".format(elapsed / 60, elapsed % 60)
            val partial = speech.partial
            if (partial.isBlank()) "$time · 松开结束，上滑取消" else "$time · $partial"
        }
        stage == OfflineDictation.Stage.Finishing -> message ?: "正在整理转写…"
        error != null -> error
        message != null -> message
        vm.importingAttachment -> "正在读取附件…"
        attachments -> "最多附 4 张图片，文本文件导入草稿"
        voiceMode -> "按住说话，松开结束，上滑取消"
        else -> statusHint
    }
    val screenHeight = LocalConfiguration.current.screenHeightDp.dp
    val keyboardInset = with(LocalDensity.current) { WindowInsets.ime.getBottom(this).toDp() }
    val compactInput = screenHeight - keyboardInset < 520.dp ||
        (WindowInsets.isImeVisible && keyboardInset == 0.dp && screenHeight < 800.dp)
    var hintHeight by remember { mutableIntStateOf(0) }
    Box(Modifier.fillMaxWidth().testTag("conversationComposer")) {
        Surface(color = AccentSurface, shape = MaterialTheme.shapes.medium,
            border = BorderStroke(1.dp, Outline), modifier = Modifier.fillMaxWidth()) {
            Box(Modifier.padding(8.dp)) {
                ConversationAttachments(vm, expanded = attachments, enabled = attachmentsEnabled,
                    onClose = { attachments = false }) {
                    DraftImageTray(vm.draftImages, canRemove = !vm.busy && !vm.importingAttachment,
                        onRemove = vm::removeDraftImage)
                    if (voiceMode && vm.draft.isNotBlank()) {
                        Text(vm.draft, Modifier.fillMaxWidth().testTag("voiceDraft")
                            .clickable(enabled = stage == null, onClickLabel = "编辑草稿") {
                                voiceMode = false
                                requestFocus = true
                            }.padding(horizontal = 8.dp, vertical = 6.dp),
                            maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
                    }
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        FilledTonalIconButton(onClick = {
                            attachments = false
                            message = null
                            voiceMode = !voiceMode
                            if (voiceMode) hideEditor() else requestFocus = true
                        }, enabled = speech.stage == null, modifier = Modifier.size(44.dp)
                            .testTag(if (voiceMode) "keyboardToggle" else "voiceToggle")) {
                            Icon(if (voiceMode) Icons.Outlined.Keyboard else Icons.Outlined.GraphicEq,
                                if (voiceMode) "切换到键盘输入" else "切换到语音输入", Modifier.size(21.dp))
                        }
                        if (voiceMode) {
                            val start by rememberUpdatedState({ startVoice() })
                            val finish by rememberUpdatedState({ discard: Boolean -> finishVoice(discard) })
                            val allowed by rememberUpdatedState(canRecord)
                            val holdLabel = when {
                                cancelArmed -> "松开取消"
                                stage == OfflineDictation.Stage.Finishing -> "正在转写…"
                                stage != null -> "松开结束"
                                else -> "按住说话"
                            }
                            Surface(Modifier.weight(1f).heightIn(min = 44.dp).testTag("voiceHold")
                                .pointerInput(key) {
                                    awaitEachGesture {
                                        val down = awaitFirstDown()
                                        if (!allowed) return@awaitEachGesture
                                        down.consume()
                                        start()
                                        var discard = true
                                        try {
                                            do {
                                                val event = awaitPointerEvent()
                                                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                                cancelArmed = down.position.y - change.position.y >= 48.dp.toPx()
                                                if (!change.pressed) discard = cancelArmed
                                                change.consume()
                                            } while (change.pressed)
                                        } finally {
                                            finish(discard)
                                        }
                                    }
                                }.semantics {
                                    role = Role.Button
                                    onClick(label = if (stage == null) "开始录音" else "结束录音") {
                                        if (stage != null) finishVoice(false) else if (canRecord) startVoice() else return@onClick false
                                        true
                                    }
                                },
                                color = when {
                                    cancelArmed -> MaterialTheme.colorScheme.errorContainer
                                    stage != null -> AccentSurface
                                    else -> MaterialTheme.colorScheme.surface
                                }, shape = MaterialTheme.shapes.small, border = BorderStroke(1.dp, Outline)) {
                                Box(Modifier.padding(9.dp), contentAlignment = Alignment.Center) {
                                    Text(holdLabel, style = MaterialTheme.typography.labelLarge)
                                }
                            }
                        } else {
                            Surface(Modifier.weight(1f), color = MaterialTheme.colorScheme.surface,
                                shape = MaterialTheme.shapes.small, border = BorderStroke(1.dp, Outline)) {
                                BasicTextField(
                                    value = vm.draft,
                                    onValueChange = { if (it.length <= MAX_DRAFT_LENGTH) vm.draft = it },
                                    textStyle = MaterialTheme.typography.bodyLarge.copy(color = Ink),
                                    cursorBrush = SolidColor(Accent),
                                    minLines = 1, maxLines = if (compactInput) 3 else 5,
                                    modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp).focusRequester(requester)
                                        .onFocusChanged {
                                            onEditorFocusChanged(it.isFocused)
                                            if (it.isFocused) { attachments = false; message = null }
                                        }.testTag("messageInput"),
                                    decorationBox = { inner ->
                                        Box(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
                                            if (vm.draft.isEmpty()) Text("输入消息", color = Muted, style = MaterialTheme.typography.bodyLarge)
                                            inner()
                                        }
                                    }
                                )
                            }
                        }
                        IconButton(onClick = {
                            if (!attachments) hideEditor()
                            attachments = !attachments
                        }, enabled = attachmentsEnabled, modifier = Modifier.size(44.dp).testTag("attachmentMenu")) {
                            Icon(if (attachments) Icons.Outlined.Close else Icons.Outlined.Add,
                                if (attachments) "关闭附件" else "添加附件", tint = if (attachmentsEnabled) Accent else Muted)
                        }
                        if (vm.running) {
                            FilledTonalIconButton(onClick = { vm.currentId?.let(vm::cancel) }, enabled = canAct, modifier = Modifier.size(44.dp)) {
                                Icon(Icons.Outlined.Stop, "取消任务")
                            }
                        } else if (hasDraft) {
                            Button(onClick = { vm.send() }, enabled = canSend,
                                modifier = Modifier.heightIn(min = 44.dp).testTag("send"),
                                contentPadding = PaddingValues(horizontal = 12.dp)) { Text("发送", maxLines = 1) }
                        }
                    }
                }
            }
        }
        if (hint != null) {
            Surface(Modifier.align(Alignment.TopStart).padding(start = 12.dp, end = 12.dp)
                .offset { IntOffset(0, -hintHeight - 6.dp.roundToPx()) }
                .onSizeChanged { hintHeight = it.height }.zIndex(1f).testTag("composerHint"),
                color = if (cancelArmed || error != null) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surface,
                shape = MaterialTheme.shapes.small, border = BorderStroke(1.dp, Outline), shadowElevation = 3.dp) {
                Text(hint, Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                    color = if (cancelArmed || error != null) MaterialTheme.colorScheme.onErrorContainer else Ink,
                    maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
