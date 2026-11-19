package cn.xux.cloudworkbench

import android.text.format.Formatter
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

@Composable internal fun ConnectionStatus(vm: WorkbenchModel) {
    val label = when {
        vm.connecting -> "连接中"
        vm.reconnecting -> "正在重连"
        vm.connected && vm.hostOnline -> "已连接"
        vm.connected -> "服务异常"
        else -> "离线"
    }
    val online = vm.connected && vm.hostOnline
    val tracker = vm.transfers
    val transfer = tracker?.foreground?.collectAsState()?.value
    val taskState = vm.activeThread?.optString("status")
    val task = when (taskState) {
        "starting" -> "Codex 正在启动"
        "running" -> "Codex 正在执行"
        "approval" -> "Codex 等待审批"
        else -> null
    }
    val activity = if (online) transfer?.let { transferLabel(it) } ?: task else null
    var details by rememberSaveable { mutableStateOf(false) }
    Surface(onClick = { details = true }, modifier = Modifier.testTag("connectionDetails"),
        enabled = tracker != null, shape = RoundedCornerShape(14.dp),
        color = if (online) SoftGreen else MaterialTheme.colorScheme.surfaceContainerHigh) {
        Row(Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Column(Modifier.widthIn(max = 142.dp)) {
                Text(label, style = MaterialTheme.typography.labelMedium, color = if (online) Green else Muted, maxLines = 1)
                activity?.let {
                    Text(it, style = MaterialTheme.typography.labelSmall, color = Muted,
                        maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.testTag("connectionActivity"))
                }
            }
            if (tracker != null) Icon(Icons.Outlined.ExpandMore, "传输详情", Modifier.size(16.dp), tint = Muted)
        }
    }
    if (details && tracker != null) {
        TransferDetails(tracker, label, task.takeIf { online }) { details = false }
    }
}

@Composable internal fun transferLabel(entry: TransferEntry): String = when (entry.phase) {
    TransferPhase.Uploading -> "上传附件 ${transferSize(entry.sent)} / ${transferSize(entry.uploadSize)}"
    TransferPhase.Waiting -> "等待服务器响应"
    TransferPhase.Receiving -> "正在接收 ${transferSize(entry.received)}"
}

@Composable internal fun transferSize(bytes: Long) = Formatter.formatShortFileSize(LocalContext.current, bytes)
