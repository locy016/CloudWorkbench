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
import androidx.compose.material.icons.outlined.GraphicEq
import androidx.compose.material.icons.outlined.HourglassTop
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.VolumeUp
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
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
        VoiceDialogPhase.Reading -> Color(0xFF076E72)
        VoiceDialogPhase.Waiting, VoiceDialogPhase.Synthesizing -> Color(0xFF554397)
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
    val reactsToAudio = phase == VoiceDialogPhase.Listening || phase == VoiceDialogPhase.Reading
    val amplitude by animateFloatAsState(if (reactsToAudio) level() else 0f, tween(155), label = "语音音量")
    val failed = phase == VoiceDialogPhase.Failed
    val turn = if (failed) null else rememberInfiniteTransition(label = "声纹圈").animateFloat(
        initialValue = 0f, targetValue = 1f, animationSpec = infiniteRepeatable(tween(2600, easing = LinearEasing)),
        label = "声纹圈转动"
    )
    val highlight = when (phase) {
        VoiceDialogPhase.Reading -> Color(0xFF198BA8)
        VoiceDialogPhase.Failed -> color
        else -> Color(0xFF7360D4)
    }
    val directions = remember {
        List(72) { index ->
            val angle = (index / 72f - 0.25f) * 2 * PI
            Offset(cos(angle).toFloat(), sin(angle).toFloat())
        }
    }
    Box(Modifier.size(152.dp).testTag("voiceWaveRing"), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val radius = size.minDimension / 2
            val spin = turn?.value ?: 0f
            val ring = radius * 0.74f
            val energy = amplitude
            drawCircle(Brush.radialGradient(listOf(color.copy(alpha = .16f), color.copy(alpha = .07f), Color.Transparent),
                center = center, radius = radius), radius)
            drawCircle(color.copy(alpha = .12f), radius * .94f, style = Stroke(1.dp.toPx()))
            drawCircle(color.copy(alpha = .12f), radius * .65f, style = Stroke(1.dp.toPx()))
            if (reactsToAudio) {
                directions.forEachIndexed { i, direction ->
                    val position = i.toFloat() / directions.size
                    val wave = (sin(2 * PI * (position * 5 - spin)).toFloat() +
                        .45f * cos(2 * PI * (position * 9 + spin)).toFloat() + 1.45f) / 2.9f
                    val halfLength = radius * (.02f + (.035f + .15f * energy) * wave)
                    drawLine(lerp(color, highlight, (direction.x + 1f) / 2f).copy(alpha = .48f + .38f * energy),
                        center + direction * (ring - halfLength), center + direction * (ring + halfLength),
                        strokeWidth = 2.dp.toPx(), cap = StrokeCap.Round)
                }
            } else {
                drawCircle(color.copy(alpha = .18f), ring, style = Stroke(2.dp.toPx()))
                if (!failed) drawArc(Brush.sweepGradient(listOf(highlight, color, highlight), center),
                    startAngle = spin * 360f, sweepAngle = 82f, useCenter = false,
                    topLeft = center - Offset(ring, ring), size = Size(ring * 2, ring * 2),
                    style = Stroke(3.dp.toPx(), cap = StrokeCap.Round))
            }
        }
        Box(Modifier.size(68.dp).background(Brush.linearGradient(listOf(color, highlight)), CircleShape),
            contentAlignment = Alignment.Center) {
            Icon(when (phase) {
                VoiceDialogPhase.Listening -> Icons.Outlined.Mic
                VoiceDialogPhase.Transcribing -> Icons.Outlined.GraphicEq
                VoiceDialogPhase.Reading -> Icons.Outlined.VolumeUp
                VoiceDialogPhase.Failed -> Icons.Outlined.Info
                else -> Icons.Outlined.HourglassTop
            }, contentDescription = null, tint = Color.White, modifier = Modifier.size(30.dp))
        }
    }
}

internal fun voiceWaveLevel(meanSquare: Float): Float =
    ((sqrt(meanSquare.takeIf { it.isFinite() && it > 0f } ?: 0f) - .012f) * 6f).coerceIn(0f, 1f)
