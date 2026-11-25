package cn.xux.cloudworkbench

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import java.util.UUID

internal enum class VoiceChatStage { Preparing, Waiting, Reading }

internal suspend fun runVoiceTurns(
    capture: suspend () -> String?,
    exchange: suspend (String) -> String,
    speak: suspend (String) -> Unit,
    onStage: (VoiceChatStage, String?) -> Unit
) {
    while (currentCoroutineContext().isActive) {
        onStage(VoiceChatStage.Preparing, null)
        val spoken = capture() ?: return
        onStage(VoiceChatStage.Waiting, spoken)
        val answer = exchange(spoken)
        onStage(VoiceChatStage.Reading, answer)
        speak(answer)
    }
}

@Composable
internal fun VoiceChatControl(vm: WorkbenchModel) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var open by remember { mutableStateOf(false) }
    var denied by remember { mutableStateOf(false) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        denied = !granted
        if (granted && vm.canStartVoiceChat) open = true
    }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) open = false
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    TextButton(enabled = vm.canStartVoiceChat, modifier = Modifier.testTag("startVoiceChat"), onClick = {
        denied = false
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) open = true
        else permission.launch(Manifest.permission.RECORD_AUDIO)
    }) {
        Icon(Icons.Outlined.Mic, null)
        Text("开启语音聊天", modifier = Modifier.testTag("voiceChatEntryLabel"))
    }
    if (denied) {
        AlertDialog(onDismissRequest = { denied = false }, title = { Text("麦克风权限") },
            text = { Text("开启语音聊天需要允许使用麦克风。可以在系统设置中开启。") },
            confirmButton = { TextButton(onClick = { denied = false }) { Text("知道了") } })
    }
    if (open) VoiceChatDialog(vm) { open = false }
}

@Composable
private fun VoiceChatDialog(vm: WorkbenchModel, onClose: () -> Unit) {
    val context = LocalContext.current
    val playback = remember { VoicePlayback(context) }
    val owner = remember { "voice-chat-${UUID.randomUUID()}" }
    var phase by remember { mutableStateOf("正在准备朗读") }
    var heard by remember { mutableStateOf("") }
    var reply by remember { mutableStateOf("") }
    var failed by remember { mutableStateOf(false) }
    val listening = vm.dictation.owner == owner && vm.dictation.stage == OfflineDictation.Stage.Listening
    val partial = vm.dictation.partial.takeIf { listening && it.isNotBlank() }
    DisposableEffect(playback, owner) {
        onDispose {
            vm.dictation.discard(owner)
            playback.close()
        }
    }
    LaunchedEffect(playback, owner) {
        try {
            check(playback.prepare()) { "设备暂无可用的离线中文朗读音色，请在系统文字转语音设置中安装" }
            runVoiceTurns(
                capture = {
                    check(vm.canStartVoiceChat) { "当前会话暂不可继续语音聊天" }
                    vm.dictation.captureTurn(owner) ?: error(vm.dictation.error ?: "没有识别到语音")
                },
                exchange = { vm.awaitVoiceReply(vm.sendVoiceTurn(it).await()) },
                speak = {
                    check(playback.speak(it)) { "朗读已中断，可在会话中查看文字回复" }
                    delay(250)
                },
                onStage = { stage, text ->
                    phase = when (stage) {
                        VoiceChatStage.Preparing -> "正在准备麦克风"
                        VoiceChatStage.Waiting -> {
                            heard = text.orEmpty()
                            "正在等待回复"
                        }
                        VoiceChatStage.Reading -> {
                            reply = text.orEmpty()
                            "正在朗读"
                        }
                    }
                }
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            failed = true
            phase = e.message ?: "语音聊天暂时不可用"
        }
    }
    AlertDialog(onDismissRequest = onClose, modifier = Modifier.testTag("voiceChat"),
        title = { Text("语音聊天") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(if (listening) "正在聆听" else phase, Modifier.testTag("voiceChatStatus"),
                    color = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
                (partial ?: heard).takeIf(String::isNotBlank)?.let {
                    Text(it, Modifier.testTag("voiceChatTranscript"), maxLines = 4, overflow = TextOverflow.Ellipsis)
                }
                if (reply.isNotBlank()) Text(reply, Modifier.testTag("voiceChatReply"),
                    color = Muted, maxLines = 5, overflow = TextOverflow.Ellipsis)
            }
        },
        dismissButton = if (listening) ({
            TextButton(onClick = { vm.dictation.stop(owner) }) { Text("说完了") }
        }) else null,
        confirmButton = {
            TextButton(onClick = onClose, modifier = Modifier.testTag("endVoiceChat")) {
                Text(if (failed) "返回对话" else "结束语音聊天")
            }
        })
}
