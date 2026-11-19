package cn.xux.cloudworkbench

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.HelpOutline
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp

@Composable
internal fun AuthorizationHeader(
    connected: Boolean,
    connectionStatus: String,
    retryConnection: (() -> Unit)?,
    onBind: (() -> Unit)?,
    canIssue: Boolean,
    issueMessage: String,
    onIssue: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.primaryContainer,
        shape = MaterialTheme.shapes.large
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Surface(color = MaterialTheme.colorScheme.surface, shape = MaterialTheme.shapes.small) {
                    Icon(Icons.Outlined.Key, null, Modifier.padding(10.dp).size(24.dp), tint = Green)
                }
                Text("设备授权", Modifier.semantics { heading() }, style = MaterialTheme.typography.headlineSmall)
            }
            if (connected) {
                Button(enabled = canIssue, onClick = onIssue, modifier = Modifier.testTag("issueAuthorization")) {
                    AuthorizationButtonLabel(Icons.Outlined.Add, "新增授权")
                }
            } else {
                Text(connectionStatus, style = MaterialTheme.typography.bodyMedium)
                ActionRow {
                    retryConnection?.let { OutlinedButton(onClick = it) { Text("重试连接") } }
                    onBind?.let { OutlinedButton(onClick = it) { Text("连接问题") } }
                }
            }
            AuthorizationNotice(issueMessage)
        }
    }
}

@Composable
internal fun AuthorizationHelp(hasSubscriptions: Boolean) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    Surface(
        onClick = { expanded = !expanded },
        modifier = Modifier.fillMaxWidth().semantics {
            stateDescription = if (expanded) "已展开" else "已收起"
        },
        color = MaterialTheme.colorScheme.surface,
        shape = MaterialTheme.shapes.medium,
        border = BorderStroke(1.dp, Outline)
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Icon(Icons.Outlined.HelpOutline, null, tint = Green)
                Text("功能说明", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                Icon(if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, null, tint = Muted)
            }
            if (expanded) {
                Column(Modifier.padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("授权码在 Windows 云端工作台的授权页使用，只能用一次，24 小时内有效。登记后，设备凭据可在授权期限和流量额度内自动续期。",
                        style = MaterialTheme.typography.bodyMedium)
                    if (hasSubscriptions) {
                        Text("订阅可在领取窗口和下载次数内导入。复制配置会共用同一授权和流量额度。",
                            style = MaterialTheme.typography.bodyMedium)
                    }
                    Text("点「查看」可复制或通过邮件分享有效凭据。撤销后，凭据失效，记录可从列表移除。",
                        style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}

@Composable
internal fun AuthorizationListHeader(
    count: Int?,
    busy: Boolean,
    message: String,
    onRefresh: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f)) {
                Text("授权列表", Modifier.semantics { heading() }, style = MaterialTheme.typography.titleLarge)
                if (count != null) Text("共 $count 条记录", style = MaterialTheme.typography.bodyMedium, color = Muted)
            }
            OutlinedButton(enabled = !busy, onClick = onRefresh) {
                AuthorizationButtonLabel(Icons.Outlined.Refresh, "刷新列表")
            }
        }
        if (busy) {
            LinearProgressIndicator(Modifier.fillMaxWidth().height(3.dp))
            Text(if (count == null) "正在读取授权记录…" else "正在处理…",
                style = MaterialTheme.typography.bodyMedium, color = Muted,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
        }
        AuthorizationNotice(message)
    }
}

@Composable
private fun AuthorizationNotice(message: String) {
    if (message.isBlank()) return
    Surface(Modifier.fillMaxWidth(), color = WarmSurface, shape = MaterialTheme.shapes.small) {
        Text(message, Modifier.padding(12.dp).semantics { liveRegion = LiveRegionMode.Polite },
            style = MaterialTheme.typography.bodyMedium, color = Amber)
    }
}

@Composable
internal fun AuthorizationSection(title: String) {
    Text(title, Modifier.semantics { heading() }, style = MaterialTheme.typography.titleMedium, color = Muted)
}

internal enum class AuthorizationStatusStyle { Active, Attention, Inactive }

@Composable
internal fun AuthorizationRecord(
    icon: ImageVector,
    title: String,
    status: String,
    statusStyle: AuthorizationStatusStyle,
    fields: List<Pair<String, String>>,
    usage: List<Pair<String, String>> = emptyList(),
    enabled: Boolean,
    onView: (() -> Unit)? = null,
    onRename: (() -> Unit)? = null,
    onRevoke: (() -> Unit)? = null,
    onRemove: (() -> Unit)? = null
) {
    Panel(Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Icon(icon, null, Modifier.size(22.dp), tint = Green)
                Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                val (container, foreground) = when (statusStyle) {
                    AuthorizationStatusStyle.Active -> SoftGreen to Green
                    AuthorizationStatusStyle.Attention -> WarmSurface to Amber
                    AuthorizationStatusStyle.Inactive -> Background to Muted
                }
                Surface(color = container, shape = MaterialTheme.shapes.small) {
                    Text(status, Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                        color = foreground, style = MaterialTheme.typography.labelLarge)
                }
            }
            AuthorizationFields(fields)
            if (usage.isNotEmpty()) {
                Surface(Modifier.fillMaxWidth(), color = Background, shape = MaterialTheme.shapes.medium) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("登录与用量", style = MaterialTheme.typography.titleMedium)
                        AuthorizationFields(usage)
                    }
                }
            }
            if (onView != null || onRename != null || onRevoke != null || onRemove != null) {
                HorizontalDivider(color = Outline)
                ActionRow {
                    onView?.let { OutlinedButton(enabled = enabled, onClick = it) { Text("查看") } }
                    onRename?.let { TextButton(enabled = enabled, onClick = it) { Text("重命名") } }
                    onRevoke?.let { RemoveAuthorizationButton("撤销", enabled, it) }
                    onRemove?.let { RemoveAuthorizationButton("移除记录", enabled, it) }
                }
            }
        }
    }
}

@Composable
private fun AuthorizationFields(fields: List<Pair<String, String>>) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val fontScale = LocalDensity.current.fontScale
        val columns = when {
            fields.size == 3 && maxWidth >= 560.dp * fontScale -> 3
            maxWidth >= 270.dp * fontScale -> 2
            else -> 1
        }
        val gap = 12.dp
        val cellWidth = (maxWidth - gap * (columns - 1)) / columns
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            for (group in fields.chunked(columns)) {
                Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
                    for ((label, value) in group) {
                        Column(Modifier.width(cellWidth), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(label, style = MaterialTheme.typography.bodyMedium, color = Muted)
                            Text(value, style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AuthorizationButtonLabel(icon: ImageVector, label: String) {
    Icon(icon, null, Modifier.size(18.dp))
    Spacer(Modifier.width(8.dp))
    Text(label)
}

@Composable
private fun RemoveAuthorizationButton(label: String, enabled: Boolean, onClick: () -> Unit) {
    TextButton(enabled = enabled, onClick = onClick,
        colors = androidx.compose.material3.ButtonDefaults.textButtonColors(
            contentColor = MaterialTheme.colorScheme.error
        )) { Text(label) }
}
