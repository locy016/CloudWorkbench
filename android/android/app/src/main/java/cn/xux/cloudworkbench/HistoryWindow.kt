package cn.xux.cloudworkbench

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlin.math.abs

@Composable internal fun HistoryWindow(title: String, close: () -> Unit, maxWidth: Dp,
    content: @Composable ColumnScope.(gutter: Dp) -> Unit) {
    Dialog(onDismissRequest = close, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        BoxWithConstraints(Modifier.fillMaxSize().safeDrawingPadding(), contentAlignment = Alignment.Center) {
            val tablet = this.maxWidth >= 600.dp
            val outer = if (tablet) 32.dp else 12.dp
            val vertical = if (maxHeight < 440.dp) 6.dp else if (tablet) 28.dp else 14.dp
            val gutter = if (tablet) 24.dp else 16.dp
            Box(Modifier.matchParentSize().pointerInput(close) { detectTapGestures { close() } })
            Surface(Modifier.padding(horizontal = outer, vertical = vertical)
                .widthIn(max = maxWidth).fillMaxWidth().heightIn(max = 840.dp)
                .semantics { paneTitle = title }.testTag("historyWindow"),
                shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surface,
                border = BorderStroke(1.dp, Outline)) {
                Column {
                    Row(Modifier.fillMaxWidth().padding(start = gutter, end = gutter - 8.dp, top = 6.dp, bottom = 6.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Text(title, Modifier.weight(1f).padding(end = 8.dp), style = MaterialTheme.typography.titleLarge, color = Ink)
                        IconButton(onClick = close) { Icon(Icons.Outlined.Close, "关闭", tint = Muted) }
                    }
                    HorizontalDivider(color = Outline)
                    content(gutter)
                }
            }
        }
    }
}

@Composable internal fun HistoryDialog(title: String, close: () -> Unit, maxWidth: Dp = 560.dp,
    body: @Composable ColumnScope.() -> Unit) {
    HistoryWindow(title, close, maxWidth) { gutter ->
        val scroll = rememberScrollState()
        Column(Modifier.fillMaxWidth().weight(1f, fill = false).testTag("historyReadingPane").historyScrollbar(scroll).verticalScroll(scroll)
            .padding(horizontal = gutter, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp), content = body)
    }
}

@Composable internal fun Modifier.historyScrollbar(scroll: ScrollState): Modifier =
    historyIndicator(scroll.isScrollInProgress, scroll::dispatchRawDelta) { height ->
        val max = scroll.maxValue
        if (max <= 0 || max == Int.MAX_VALUE) null
        else {
            val total = height + max
            (scroll.value / total)..((scroll.value + height) / total)
        }
    }

@Composable internal fun Modifier.historyScrollbar(list: LazyListState): Modifier =
    historyIndicator(list.isScrollInProgress, list::dispatchRawDelta) {
        val info = list.layoutInfo
        val start = info.visibleItemsInfo.firstOrNull()
        val end = info.visibleItemsInfo.lastOrNull()
        if (start == null || end == null || (!list.canScrollForward && !list.canScrollBackward)) null
        else {
            // Use the visible fraction of each end item. A single expanded stage may exceed the viewport.
            val hidden = ((info.viewportStartOffset - start.offset).toFloat() / start.size.coerceAtLeast(1)).coerceIn(0f, 1f)
            val shown = ((info.viewportEndOffset - end.offset).toFloat() / end.size.coerceAtLeast(1)).coerceIn(0f, 1f)
            val count = info.totalItemsCount.toFloat()
            ((start.index + hidden) / count)..((end.index + shown) / count)
        }
    }

@Composable private fun Modifier.historyIndicator(moving: Boolean, scrollBy: (Float) -> Float,
    visibleRange: (Float) -> ClosedFloatingPointRange<Float>?): Modifier {
    val rangeNow by rememberUpdatedState(visibleRange)
    val scrollNow by rememberUpdatedState(scrollBy)
    var dragging by remember { mutableStateOf(false) }
    val active = moving || dragging
    val opacity = animateFloatAsState(if (active) .88f else .34f,
        animationSpec = tween(if (active) 100 else 480), label = "History scroll indicator")
    val direction = LocalLayoutDirection.current
    return pointerInput(direction) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            val edgeDistance = if (direction == LayoutDirection.Ltr) size.width - down.position.x else down.position.x
            val height = size.height.toFloat()
            if (edgeDistance > 14.dp.toPx() || rangeNow(height) == null) return@awaitEachGesture
            var previous = down.position.y
            try {
                do {
                    val change = awaitPointerEvent(PointerEventPass.Initial).changes.firstOrNull { it.id == down.id } ?: break
                    if (!change.pressed) break
                    val delta = change.position.y - previous
                    if (!dragging && abs(delta) < viewConfiguration.touchSlop) continue
                    val range = rangeNow(height) ?: break
                    val fraction = (range.endInclusive - range.start).coerceIn(.0001f, 1f)
                    val track = (height - 16.dp.toPx()).coerceAtLeast(1f)
                    val thumb = (track * fraction).coerceAtLeast(24.dp.toPx())
                    dragging = true
                    change.consume()
                    scrollNow(delta * height * (1f / fraction - 1f) / (track - thumb).coerceAtLeast(1f))
                    previous = change.position.y
                } while (true)
            } finally {
                dragging = false
            }
        }
    }.drawWithContent {
        drawContent()
        val range = visibleRange(size.height) ?: return@drawWithContent
        val margin = 8.dp.toPx()
        val track = size.height - margin * 2
        if (track <= 0f) return@drawWithContent
        val fraction = (range.endInclusive - range.start).coerceIn(0f, 1f)
        val thumb = (track * fraction).coerceIn(24.dp.toPx().coerceAtMost(track), track)
        val position = (range.start / (1f - fraction).coerceAtLeast(.0001f)).coerceIn(0f, 1f)
        val width = 3.dp.toPx()
        val x = if (layoutDirection == LayoutDirection.Ltr) size.width - width - 3.dp.toPx() else 3.dp.toPx()
        val radius = CornerRadius(width / 2)
        drawRoundRect(Outline.copy(alpha = opacity.value * .6f), Offset(x, margin), Size(width, track), radius)
        drawRoundRect(Accent.copy(alpha = opacity.value), Offset(x, margin + position * (track - thumb)), Size(width, thumb), radius)
    }
}
