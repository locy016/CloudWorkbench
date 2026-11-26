package cn.xux.cloudworkbench

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp

internal enum class TaskTone { Neutral, Active, Waiting, Complete, Error }

@Composable
internal fun TaskOperationPanels(releases: ReleaseApprovals, builds: BuildStatus, connected: Boolean) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        if (maxWidth >= 620.dp) {
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f)) { ReleaseApprovalPanel(releases, connected) }
                Column(Modifier.weight(1f)) { BuildStatusPanel(builds, connected) }
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                ReleaseApprovalPanel(releases, connected)
                BuildStatusPanel(builds, connected)
            }
        }
    }
}

@Composable
internal fun TaskPanelHeading(title: String, icon: ImageVector, action: @Composable () -> Unit = {}) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Surface(color = AccentSurface, shape = MaterialTheme.shapes.extraSmall) {
            Box(Modifier.size(36.dp), contentAlignment = Alignment.Center) {
                Icon(icon, contentDescription = null, tint = Accent, modifier = Modifier.size(20.dp))
            }
        }
        Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
        action()
    }
}

@Composable
internal fun TaskBadge(label: String, tone: TaskTone) {
    val colors = MaterialTheme.colorScheme
    val (foreground, background) = when (tone) {
        TaskTone.Neutral -> Muted to Background
        TaskTone.Active -> Accent to AccentSurface
        TaskTone.Waiting -> Amber to WarmSurface
        TaskTone.Complete -> Color(0xFF206341) to Color(0xFFE8F6ED)
        TaskTone.Error -> colors.onErrorContainer to colors.errorContainer
    }
    Surface(color = background, shape = MaterialTheme.shapes.extraSmall) {
        Text(label, modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
            color = foreground, style = MaterialTheme.typography.labelMedium)
    }
}
