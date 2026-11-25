package cn.xux.cloudworkbench

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.SystemClock
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.coroutineContext
import kotlin.coroutines.resume

internal class OfflineDictation(private val context: Context, private val scope: CoroutineScope) {
    enum class Stage { Preparing, Listening, Finishing }

    var stage by mutableStateOf<Stage?>(null)
        private set
    var partial by mutableStateOf("")
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var owner by mutableStateOf<String?>(null)
        private set
    var voiceLevel by mutableFloatStateOf(0f)
        private set
    private val stopping = AtomicBoolean()
    private var discarding = false

    fun clearError() { error = null }

    fun start(draftKey: String, append: (String) -> Boolean) =
        startRecording(draftKey, append = append)

    suspend fun captureTurn(key: String): String? = suspendCancellableCoroutine { waiting ->
        startRecording(key, onTurn = { text -> if (waiting.isActive) waiting.resume(text) })
        waiting.invokeOnCancellation { scope.launch(Dispatchers.Main.immediate) { discard(key) } }
    }

    private fun startRecording(draftKey: String, append: ((String) -> Boolean)? = null, onTurn: ((String?) -> Unit)? = null) {
        if (stage != null) {
            onTurn?.invoke(null)
            return
        }
        error = null
        partial = ""
        stopping.set(false)
        discarding = false
        owner = draftKey
        stage = Stage.Preparing
        scope.launch {
            val segments = mutableListOf<String>()
            var characters = 0
            var shouldCommit = true
            try {
                withContext(Dispatchers.IO) {
                    StreamingSpeech(context.assets).use { engine ->
                        coroutineContext.ensureActive()
                        if (stopping.get()) return@use
                        engine.stream().use { stream ->
                            record(stream, oneTurn = onTurn != null, onPartial = { text ->
                                if (!discarding) partial = text.ifBlank { segments.lastOrNull().orEmpty() }
                            }, onSegment = { text ->
                                if (!discarding && characters + text.length <= MAX_DRAFT_LENGTH) {
                                    segments.add(text)
                                    characters += text.length
                                    partial = text
                                    if (onTurn != null) stop(draftKey)
                                } else if (!discarding) {
                                    error = "草稿已达到长度上限"
                                    stop()
                                }
                            })
                        }
                    }
                }
                if (!discarding && segments.isEmpty() && error == null && stage != Stage.Preparing) {
                    error = "没有识别到语音，请重试"
                }
            } catch (e: CancellationException) {
                shouldCommit = false
                throw e
            } catch (_: SecurityException) {
                if (!discarding) error = "请允许使用麦克风后重试"
            } catch (e: Exception) {
                Log.w("OfflineDictation", "Local dictation failed", e)
                if (!discarding) error = if (stage == Stage.Preparing) "语音识别启动失败，请重试" else "录音中断，请重试"
            } finally {
                if (shouldCommit && !discarding && append != null) {
                    for (segment in segments) {
                        if (!append(segment)) {
                            error = "草稿已达到长度上限"
                            break
                        }
                    }
                }
                stage = null
                partial = ""
                voiceLevel = 0f
                owner = null
                if (shouldCommit) onTurn?.invoke(
                    segments.joinToString(" ").takeIf { !discarding && error == null && it.isNotBlank() }
                )
            }
        }
    }

    fun discard(draftKey: String) {
        if (stage == null || owner != draftKey) return
        discarding = true
        partial = ""
        error = null
        stop(draftKey)
    }

    fun stop(draftKey: String? = null) {
        if (stage == null || (draftKey != null && owner != draftKey)) return
        stopping.set(true)
        voiceLevel = 0f
        if (stage == Stage.Listening) stage = Stage.Finishing
    }

    @SuppressLint("MissingPermission")
    private suspend fun record(
        stream: StreamingSpeech.Stream,
        oneTurn: Boolean,
        onPartial: (String) -> Unit,
        onSegment: (String) -> Unit
    ) {
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            throw SecurityException("Microphone permission missing")
        }
        val channels = AudioFormat.CHANNEL_IN_MONO
        val format = AudioFormat.ENCODING_PCM_16BIT
        val blockSize = VOICE_SAMPLE_RATE / 10
        val minimum = AudioRecord.getMinBufferSize(VOICE_SAMPLE_RATE, channels, format)
        check(minimum > 0) { "Microphone format unavailable" }
        val microphone = AudioRecord(
            MediaRecorder.AudioSource.MIC, VOICE_SAMPLE_RATE, channels, format, maxOf(minimum, blockSize * 8)
        )
        var started = false
        try {
            check(microphone.state == AudioRecord.STATE_INITIALIZED)
            if (stopping.get()) return
            microphone.startRecording()
            started = microphone.recordingState == AudioRecord.RECORDSTATE_RECORDING
            check(started)
            withContext(Dispatchers.Main.immediate) { stage = if (stopping.get()) Stage.Finishing else Stage.Listening }
            val pcm = ShortArray(blockSize)
            var previous = ""
            val startedAt = SystemClock.elapsedRealtime()
            var heardSpeech = false
            while (!stopping.get()) {
                coroutineContext.ensureActive()
                val count = microphone.read(pcm, 0, pcm.size, AudioRecord.READ_BLOCKING)
                if (count < 0) {
                    previous.takeIf { it.isNotBlank() }?.let { withContext(Dispatchers.Main.immediate) { onSegment(it) } }
                    error("AudioRecord.read failed: $count")
                }
                if (count == 0) continue
                val samples = FloatArray(count) { pcm[it] / 32768f }
                if (oneTurn) {
                    val level = voiceWaveLevel((samples.sumOf { (it * it).toDouble() } / count).toFloat())
                    withContext(Dispatchers.Main.immediate) {
                        if (!stopping.get()) voiceLevel = level
                    }
                }
                val text = stream.accept(samples)
                heardSpeech = heardSpeech || text.isNotBlank()
                val elapsed = SystemClock.elapsedRealtime() - startedAt
                if (oneTurn && (elapsed >= 60_000 || !heardSpeech && elapsed >= 25_000)) break
                if (stream.isEndpoint()) {
                    withContext(Dispatchers.Main.immediate) {
                        if (text.isNotBlank()) onSegment(text) else onPartial("")
                    }
                    if (oneTurn && text.isNotBlank()) return
                    stream.reset()
                    previous = ""
                } else if (text != previous) {
                    withContext(Dispatchers.Main.immediate) { onPartial(text) }
                    previous = text
                }
            }
        } finally {
            try {
                if (started) microphone.stop()
            } finally {
                microphone.release()
            }
        }
        stream.finish().takeIf { it.isNotBlank() }?.let { withContext(Dispatchers.Main.immediate) { onSegment(it) } }
    }
}
