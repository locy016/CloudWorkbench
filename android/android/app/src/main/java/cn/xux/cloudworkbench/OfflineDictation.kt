package cn.xux.cloudworkbench

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.coroutineContext

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
    private val stopping = AtomicBoolean()
    private var discarding = false

    fun clearError() { error = null }

    fun start(draftKey: String, append: (String) -> Boolean) {
        if (stage != null) return
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
                            record(stream, onPartial = { text ->
                                if (!discarding) partial = text.ifBlank { segments.lastOrNull().orEmpty() }
                            }, onSegment = { text ->
                                if (!discarding && characters + text.length <= MAX_DRAFT_LENGTH) {
                                    segments.add(text)
                                    characters += text.length
                                    partial = text
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
                if (shouldCommit && !discarding) {
                    for (segment in segments) {
                        if (!append(segment)) {
                            error = "草稿已达到长度上限"
                            break
                        }
                    }
                }
                stage = null
                partial = ""
                owner = null
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
        if (stage == Stage.Listening) stage = Stage.Finishing
    }

    @SuppressLint("MissingPermission")
    private suspend fun record(
        stream: StreamingSpeech.Stream,
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
            while (!stopping.get()) {
                coroutineContext.ensureActive()
                val count = microphone.read(pcm, 0, pcm.size, AudioRecord.READ_BLOCKING)
                if (count < 0) {
                    previous.takeIf { it.isNotBlank() }?.let { withContext(Dispatchers.Main.immediate) { onSegment(it) } }
                    error("AudioRecord.read failed: $count")
                }
                if (count == 0) continue
                val text = stream.accept(FloatArray(count) { pcm[it] / 32768f })
                if (stream.isEndpoint()) {
                    withContext(Dispatchers.Main.immediate) {
                        if (text.isNotBlank()) onSegment(text) else onPartial("")
                    }
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
