package cn.xux.cloudworkbench

import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable internal fun HistoryParagraph(text: String, color: Color = Ink, prominent: Boolean = false) {
    val paragraphs = androidx.compose.runtime.remember(text, prominent) {
        val firstStop = text.indexOf('。') + 1
        when {
            '\n' in text -> text.lines().filter(String::isNotBlank)
            prominent && text.length >= 68 && firstStop in 18..(text.length - 20) ->
                listOf(text.take(firstStop), text.drop(firstStop))
            else -> listOf(text)
        }
    }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        paragraphs.forEach { paragraph ->
            Text(paragraph, Modifier.fillMaxWidth(), color = color, style = MaterialTheme.typography.bodyMedium,
                fontSize = if (prominent) 17.sp else 15.sp, lineHeight = if (prominent) 32.sp else 27.sp,
                textAlign = TextAlign.Justify)
        }
    }
}

@Composable internal fun HistoryReadingPair(primary: @Composable () -> Unit, secondary: @Composable () -> Unit) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        if (maxWidth >= 500.dp * LocalDensity.current.fontScale.coerceAtLeast(1f)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                Column(Modifier.weight(1.9f), verticalArrangement = Arrangement.spacedBy(6.dp)) { primary() }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) { secondary() }
            }
        } else {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                primary()
                secondary()
            }
        }
    }
}
