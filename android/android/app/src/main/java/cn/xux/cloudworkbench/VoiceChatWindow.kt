package cn.xux.cloudworkbench

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.VolumeUp
import androidx.compose.material.icons.outlined.GraphicEq
import androidx.compose.material.icons.outlined.HourglassTop
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

internal enum class VoiceDialogPhase(val label: String) {
    Preparing("正在准备语音"), Listening("正在聆听"), Transcribing("正在整理语音"),
    Waiting("正在等待回复"), Synthesizing("正在生成朗读"), Reading("正在朗读"), Failed("语音聊天已暂停")
}

@Composable
internal fun VoiceChatWindow(
    phase: VoiceDialogPhase,
    failure: String?,
    transcript: String,
    reply: String,
    level: () -> Float,
    onFinishSpeech: () -> Unit,
    onClose: () -> Unit
) {
    val failed = phase == VoiceDialogPhase.Failed
    val statusColor = when (phase) {
        VoiceDialogPhase.Reading -> Color(0xFF087078)
        VoiceDialogPhase.Waiting, VoiceDialogPhase.Synthesizing -> Color(0xFF51419F)
        VoiceDialogPhase.Failed -> MaterialTheme.colorScheme.error
        else -> Accent
    }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.padding(horizontal = 18.dp, vertical = 16.dp).widthIn(max = 428.dp).fillMaxWidth()
            .testTag("voiceChat"), shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.padding(horizontal = 20.dp, vertical = 18.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Column(Modifier.weight(1f, fill = false).fillMaxWidth().verticalScroll(rememberScrollState()),
                    horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("语音聊天", style = MaterialTheme.typography.titleMedium)
                    VoiceWaveRing(phase, statusColor, level)
                    Text(failure ?: phase.label, Modifier.testTag("voiceChatStatus")
                        .semantics { liveRegion = LiveRegionMode.Polite },
                        style = if (failed) MaterialTheme.typography.bodyMedium else MaterialTheme.typography.titleLarge,
                        color = statusColor, textAlign = TextAlign.Center)
                    if (phase == VoiceDialogPhase.Listening && transcript.isBlank()) {
                        Text("说完后自动发送，也可以点「说完了」。", color = Muted,
                            style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center)
                    }
                    if (transcript.isNotBlank()) VoiceChatText("你说", transcript, "voiceChatTranscript", AccentSurface, 4)
                    if (reply.isNotBlank()) VoiceChatText("助理回复", reply, "voiceChatReply", Background, 6)
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally)) {
                    if (phase == VoiceDialogPhase.Listening) {
                        OutlinedButton(onClick = onFinishSpeech, modifier = Modifier.heightIn(min = 48.dp)) { Text("说完了") }
                    }
                    FilledTonalButton(onClick = onClose, modifier = Modifier.heightIn(min = 48.dp).testTag("endVoiceChat")) {
                        Text(if (failed) "返回对话" else "结束语音聊天")
                    }
                }
            }
        }
    }
}

@Composable
private fun VoiceChatText(label: String, text: String, tag: String, fill: Color, lines: Int) {
    Surface(Modifier.fillMaxWidth(), color = fill, shape = RoundedCornerShape(14.dp)) {
        Column(Modifier.padding(horizontal = 13.dp, vertical = 9.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(label, color = Muted, style = MaterialTheme.typography.labelMedium)
            Text(text, Modifier.testTag(tag), style = MaterialTheme.typography.bodyMedium,
                maxLines = lines, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun VoiceWaveRing(phase: VoiceDialogPhase, color: Color, level: () -> Float) {
    val reading = phase == VoiceDialogPhase.Reading
    val reactsToAudio = phase == VoiceDialogPhase.Listening || reading
    val amplitude by animateFloatAsState(if (reactsToAudio) level() else 0f, tween(155), label = "语音音量")
    val failed = phase == VoiceDialogPhase.Failed
    val turn = if (failed) null else key(reactsToAudio, reading) {
        val period = if (reading) 2700 else if (reactsToAudio) 3100 else 4800
        rememberInfiniteTransition(label = "声纹圈").animateFloat(
            initialValue = 0f, targetValue = fullTurn, animationSpec = infiniteRepeatable(tween(period, easing = LinearEasing)),
            label = "声波沿圆周移动"
        )
    }
    val colors = when (phase) {
        VoiceDialogPhase.Reading -> listOf(color, Color(0xFF087E9B), Color(0xFF3974BB))
        VoiceDialogPhase.Waiting, VoiceDialogPhase.Synthesizing -> listOf(color, Color(0xFF6258BE), Color(0xFF8D63C2))
        VoiceDialogPhase.Failed -> listOf(color, color)
        else -> listOf(color, Color(0xFF3B60D7), Color(0xFF7250C5))
    }
    val trace = remember { Path() }
    val directions = remember {
        List(144) { index ->
            val angle = index * fullTurn / 144
            Offset(sin(angle), -cos(angle))
        }
    }
    Box(Modifier.size(176.dp).testTag("voiceWaveRing"), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val radius = size.minDimension / 2
            val spin = turn?.value ?: 0f
            val energy = amplitude
            drawCircle(Brush.radialGradient(listOf(colors[1].copy(alpha = .10f), Color.Transparent),
                center = center, radius = radius), radius)
            trace.reset()
            directions.forEachIndexed { index, direction ->
                val angle = fullTurn * index / directions.size
                val displacement = when {
                    reactsToAudio -> {
                        val movement = if (reading) spin else -spin
                        val spread = .72f + .19f * sin(2 * angle + .8f) + .09f * sin(3 * angle - 1.1f)
                        val wave = .54f * sin(8 * angle + 3 * movement) + .29f * sin(15 * angle + 5 * movement + .52f) +
                            .17f * cos(23 * angle + 9 * movement - .6f)
                        val gain = if (reading) .87f else 1f
                        .02f * sin(3 * angle) + gain * (.024f + .22f * energy) * spread * wave
                    }
                    failed -> 0f
                    else -> {
                        val travel = angle - spin
                        val crest = ((1f + cos(travel - .7f)) / 2f).pow(5)
                        .006f * sin(2 * angle + .3f) + crest * (.012f + .03f * sin(7 * travel))
                    }
                }
                val point = center + direction * (radius * (.64f + displacement))
                if (index == 0) trace.moveTo(point.x, point.y) else trace.lineTo(point.x, point.y)
            }
            trace.close()
            drawPath(trace, Brush.linearGradient(colors, start = Offset(0f, size.height * .78f),
                end = Offset(size.width, size.height * .18f)), style = Stroke(2.2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
        Box(Modifier.size(52.dp).background(Brush.linearGradient(listOf(color, colors.last())), CircleShape),
            contentAlignment = Alignment.Center) {
            Icon(when (phase) {
                VoiceDialogPhase.Listening -> Icons.Outlined.Mic
                VoiceDialogPhase.Transcribing -> Icons.Outlined.GraphicEq
                VoiceDialogPhase.Reading -> Icons.AutoMirrored.Outlined.VolumeUp
                VoiceDialogPhase.Failed -> Icons.Outlined.Info
                else -> Icons.Outlined.HourglassTop
            }, contentDescription = null, tint = Color.White, modifier = Modifier.size(26.dp))
        }
    }
}

private val fullTurn = (2 * PI).toFloat()

internal fun voiceWaveLevel(meanSquare: Float): Float =
    ((sqrt(meanSquare.takeIf { it.isFinite() && it > 0f } ?: 0f) - .012f) * 6f).coerceIn(0f, 1f)
