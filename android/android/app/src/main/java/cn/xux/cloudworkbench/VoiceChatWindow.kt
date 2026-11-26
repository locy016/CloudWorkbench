package cn.xux.cloudworkbench

import androidx.compose.animation.animateColor
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.Transition
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.updateTransition
import androidx.compose.animation.core.withInfiniteAnimationFrameNanos
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
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
    val errorColor = MaterialTheme.colorScheme.error
    val mode = updateTransition(phase, label = "语音聊天状态")
    val statusColor = mode.animateColor(transitionSpec = { tween(voiceModeTransitionMillis) }, label = "状态颜色") { state ->
        when (state) {
            VoiceDialogPhase.Reading -> Color(0xFF087078)
            VoiceDialogPhase.Waiting, VoiceDialogPhase.Synthesizing -> Color(0xFF51419F)
            VoiceDialogPhase.Failed -> errorColor
            else -> Accent
        }
    }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.padding(horizontal = 18.dp, vertical = 16.dp).widthIn(max = 428.dp).fillMaxWidth()
            .testTag("voiceChat"), shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.padding(horizontal = 20.dp, vertical = 18.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Column(Modifier.weight(1f, fill = false).fillMaxWidth().verticalScroll(rememberScrollState()),
                    horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("语音聊天", style = MaterialTheme.typography.titleMedium)
                    VoiceWaveRing(mode, statusColor, errorColor, level)
                    Text(failure ?: phase.label, Modifier.testTag("voiceChatStatus")
                        .semantics { liveRegion = LiveRegionMode.Polite },
                        style = if (failed) MaterialTheme.typography.bodyMedium else MaterialTheme.typography.titleLarge,
                        color = statusColor.value, textAlign = TextAlign.Center)
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
private fun VoiceWaveRing(mode: Transition<VoiceDialogPhase>, tint: State<Color>, errorColor: Color, level: () -> Float) {
    val phase = mode.targetState
    val inputLevel = when (phase) {
        VoiceDialogPhase.Listening -> level()
        VoiceDialogPhase.Reading -> level() * .87f
        else -> 0f
    }
    val amplitude = remember { Animatable(0f) }
    LaunchedEffect(inputLevel) {
        val rising = inputLevel > amplitude.value
        amplitude.animateTo(inputLevel, tween(
            durationMillis = if (rising) 85 else 210,
            easing = if (rising) LinearOutSlowInEasing else FastOutSlowInEasing
        ))
    }
    val speechMix by mode.animateFloat(transitionSpec = { tween(voiceModeTransitionMillis) }, label = "声波轮廓") { state ->
        if (state == VoiceDialogPhase.Listening || state == VoiceDialogPhase.Reading) 1f else 0f
    }
    val motion by mode.animateFloat(transitionSpec = { tween(voiceModeTransitionMillis) }, label = "声波动效") { state ->
        if (state == VoiceDialogPhase.Failed) 0f else 1f
    }
    val turnsPerSecond by remember {
        derivedStateOf {
            val energy = amplitude.value
            val waiting = (motion - speechMix).coerceAtLeast(0f)
            waiting * .24f + speechMix * energy * (.14f + .24f * energy)
        }
    }
    val moving by remember { derivedStateOf { turnsPerSecond > .0001f } }
    var angleOffset by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(moving) {
        if (!moving) return@LaunchedEffect
        val durationScale = coroutineContext[MotionDurationScale]
        var previousFrame: Long? = null
        while (isActive) {
            val scale = durationScale?.scaleFactor ?: 1f
            if (scale <= 0f) {
                snapshotFlow { durationScale?.scaleFactor ?: 1f }.first { it > 0f }
                previousFrame = null
                continue
            }
            withInfiniteAnimationFrameNanos { now ->
                previousFrame?.let { previous ->
                    val elapsed = ((now - previous) / 1_000_000_000f).coerceIn(0f, .05f)
                    angleOffset = (angleOffset + turnsPerSecond * fullTurn * elapsed / scale) % fullTurn
                }
                previousFrame = now
            }
        }
    }
    val middleColor by mode.animateColor(transitionSpec = { tween(voiceModeTransitionMillis) }, label = "波形渐变中点") { state ->
        when (state) {
            VoiceDialogPhase.Reading -> Color(0xFF087E9B)
            VoiceDialogPhase.Waiting, VoiceDialogPhase.Synthesizing -> Color(0xFF6258BE)
            VoiceDialogPhase.Failed -> errorColor
            else -> Color(0xFF3B60D7)
        }
    }
    val edgeColor by mode.animateColor(transitionSpec = { tween(voiceModeTransitionMillis) }, label = "波形渐变终点") { state ->
        when (state) {
            VoiceDialogPhase.Reading -> Color(0xFF3974BB)
            VoiceDialogPhase.Waiting, VoiceDialogPhase.Synthesizing -> Color(0xFF8D63C2)
            VoiceDialogPhase.Failed -> errorColor
            else -> Color(0xFF7250C5)
        }
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
            val spin = angleOffset
            val energy = amplitude.value
            val voiceWeight = speechMix
            val motionWeight = motion
            val waitingWeight = (motionWeight - voiceWeight).coerceAtLeast(0f)
            val orbit = waitingWeight + voiceWeight * energy
            val waveCenter = center + Offset(
                orbit * radius * (.048f + .020f * energy) * sin(spin),
                orbit * radius * (.034f + .017f * energy) * cos(spin)
            )
            val color = tint.value
            val colors = listOf(color, middleColor, edgeColor)
            val waveGain = voiceWeight * .244f * energy
            trace.reset()
            directions.forEachIndexed { index, direction ->
                val angle = fullTurn * index / directions.size
                val speech = if (waveGain > 0f) {
                    val spread = .72f + .19f * sin(2 * angle + .8f) + .09f * sin(3 * angle - 1.1f)
                    val wave = .54f * sin(8 * angle - spin) + .29f * sin(15 * angle - 2 * spin + .52f) +
                        .17f * cos(23 * angle - 3 * spin - .6f)
                    waveGain * spread * wave
                } else 0f
                val waiting = if (waitingWeight > 0f) {
                    val travel = angle - spin
                    val crest = ((1f + cos(travel - .7f)) / 2f).pow(5)
                    waitingWeight * (.006f * sin(2 * angle + .3f) + crest * (.012f + .03f * sin(7 * travel)))
                } else 0f
                val point = waveCenter + direction * (radius * (.64f + speech + waiting))
                if (index == 0) trace.moveTo(point.x, point.y) else trace.lineTo(point.x, point.y)
            }
            trace.close()
            drawPath(trace, Brush.linearGradient(colors, start = Offset(0f, size.height * .78f),
                end = Offset(size.width, size.height * .18f)), style = Stroke(2.2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))

            val breath = orbit * (.5f + .5f * sin(spin))
            val iconRingRadius = 26.dp.toPx() * (1f + .025f * breath + .015f * energy)
            rotate(degrees = spin * 360f / fullTurn) {
                drawCircle(Brush.sweepGradient(listOf(color, colors.last(), color), center = center),
                    radius = iconRingRadius, style = Stroke(1.7.dp.toPx()))
            }
        }
        Icon(when (phase) {
            VoiceDialogPhase.Listening -> Icons.Outlined.Mic
            VoiceDialogPhase.Transcribing -> Icons.Outlined.GraphicEq
            VoiceDialogPhase.Reading -> Icons.AutoMirrored.Outlined.VolumeUp
            VoiceDialogPhase.Failed -> Icons.Outlined.Info
            else -> Icons.Outlined.HourglassTop
        }, contentDescription = null, tint = tint.value, modifier = Modifier.size(26.dp).graphicsLayer {
            scaleX = 1f + .035f * amplitude.value
            scaleY = scaleX
        })
    }
}

private val fullTurn = (2 * PI).toFloat()
private const val voiceModeTransitionMillis = 200

internal fun voiceWaveLevel(meanSquare: Float): Float =
    ((sqrt(meanSquare.takeIf { it.isFinite() && it > 0f } ?: 0f) - .012f) * 6f).coerceIn(0f, 1f)
