package cn.xux.cloudworkbench

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

internal val Ink = Color(0xFF203B34)
internal val Green = Color(0xFF236653)
internal val Muted = Color(0xFF5B6D65)
internal val Background = Color(0xFFF4F7F4)
internal val Amber = Color(0xFF805015)
internal val WarmSurface = Color(0xFFFFF1DA)
internal val Outline = Color(0xFFDCE5DE)
internal val SoftGreen = Color(0xFFE5F0E8)
internal val LocalPagePadding = compositionLocalOf { 16.dp }

private val scheme = lightColorScheme(
    primary = Green, onPrimary = Color.White,
    primaryContainer = SoftGreen, onPrimaryContainer = Ink,
    secondary = Green, onSecondary = Color.White,
    secondaryContainer = SoftGreen, onSecondaryContainer = Ink,
    background = Background, onBackground = Ink,
    surface = Color.White, onSurface = Ink,
    surfaceVariant = SoftGreen, onSurfaceVariant = Muted,
    surfaceContainer = Background, surfaceContainerHigh = SoftGreen,
    outline = Muted, outlineVariant = Outline,
    error = Color(0xFFA83232), errorContainer = Color(0xFFFFEDEA),
    onErrorContainer = Color(0xFF842A2A)
)

@Composable internal fun WorkbenchTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = scheme,
        typography = Typography(
            headlineSmall = TextStyle(fontWeight = FontWeight.Bold, fontSize = 24.sp, lineHeight = 32.sp),
            titleLarge = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 20.sp, lineHeight = 28.sp),
            titleMedium = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 16.sp, lineHeight = 24.sp),
            bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 24.sp),
            bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 22.sp),
            bodySmall = TextStyle(fontSize = 12.sp, lineHeight = 18.sp),
            labelLarge = TextStyle(fontWeight = FontWeight.Medium, fontSize = 14.sp, lineHeight = 20.sp)
        ),
        shapes = Shapes(extraSmall = RoundedCornerShape(12.dp), small = RoundedCornerShape(12.dp), medium = RoundedCornerShape(16.dp), large = RoundedCornerShape(20.dp)),
        content = content
    )
}

@Composable internal fun Panel(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Surface(modifier, color = Color.White, shape = RoundedCornerShape(20.dp), border = BorderStroke(1.dp, Outline)) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(4.dp), content = content)
    }
}

@Composable internal fun PageColumn(content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(LocalPagePadding.current),
        verticalArrangement = Arrangement.spacedBy(16.dp), content = content)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable internal fun ActionRow(modifier: Modifier = Modifier, content: @Composable FlowRowScope.() -> Unit) {
    FlowRow(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp), content = content)
}

@Composable internal fun EmptyState(icon: ImageVector, title: String, description: String, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Surface(color = SoftGreen, shape = RoundedCornerShape(20.dp)) {
            Icon(icon, null, Modifier.padding(16.dp).size(28.dp), tint = Green)
        }
        Text(title, style = MaterialTheme.typography.titleMedium)
        Text(description, color = Muted, style = MaterialTheme.typography.bodyMedium,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center)
    }
}
