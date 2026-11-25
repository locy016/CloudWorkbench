package cn.xux.cloudworkbench

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRouting
import android.media.AudioTrack
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import java.util.UUID

internal class VoicePlayback(context: Context) : AutoCloseable {
    private val app = context.applicationContext
    private val attributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
    private var engine: TextToSpeech? = null

    suspend fun prepare(): Boolean {
        val status = CompletableDeferred<Int>()
        val speech = TextToSpeech(app) { status.complete(it) }
        engine = speech
        if (withTimeoutOrNull(8_000) { status.await() } != TextToSpeech.SUCCESS) return false
        val local = speech.voices.orEmpty().filter {
            it.locale.language == Locale.CHINESE.language && !it.isNetworkConnectionRequired &&
                TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED !in it.features.orEmpty()
        }.maxWithOrNull(compareBy({ it.locale.country == "CN" }, { it.quality }, { it.name }))
        return local != null && speech.setVoice(local) == TextToSpeech.SUCCESS
    }

    suspend fun speak(text: String, onLevel: ((Float) -> Unit)? = null): AudioDeviceInfo? {
        val speech = engine ?: return null
        val spoken = voiceReadingText(text).takeIf(String::isNotBlank) ?: return null
        val audio = app.getSystemService(AudioManager::class.java) ?: return null
        val reading = synthesize(speech, spoken) ?: return null
        val output = audio.voiceOutput() ?: error("没有可用的朗读输出，可在会话中查看文字回复")
        if (!play(audio, output, reading, onLevel)) {
            if (output.isBluetoothPlayback || audio.voiceOutput()?.isBluetoothPlayback == true) {
                error("蓝牙朗读已中断，可在会话中查看文字回复")
            }
            return null
        }
        return output
    }

    private suspend fun synthesize(speech: TextToSpeech, text: String): Reading? = withContext(Dispatchers.IO) {
        val result = CompletableDeferred<Reading?>()
        val bytes = ByteArrayOutputStream()
        var format: AudioFormat? = null
        val id = UUID.randomUUID().toString()
        val file = File.createTempFile("voice-chat-", ".wav", app.cacheDir)
        try {
            val listener = object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) = Unit
                override fun onBeginSynthesis(utteranceId: String?, sampleRateInHz: Int, audioFormat: Int, channelCount: Int) {
                    if (utteranceId != id) return
                    format = runCatching {
                        val channels = when (channelCount) {
                            1 -> AudioFormat.CHANNEL_OUT_MONO
                            2 -> AudioFormat.CHANNEL_OUT_STEREO
                            else -> error("Unsupported channel count")
                        }
                        AudioFormat.Builder().setSampleRate(sampleRateInHz).setEncoding(audioFormat)
                            .setChannelMask(channels).build()
                    }.getOrNull()
                }
                override fun onAudioAvailable(utteranceId: String?, audio: ByteArray) {
                    if (utteranceId != id || result.isCompleted) return
                    if (bytes.size() + audio.size <= 32 * 1024 * 1024) bytes.write(audio)
                    else result.complete(null)
                }
                override fun onDone(utteranceId: String?) {
                    if (utteranceId == id && !result.isCompleted) {
                        result.complete(format?.let { Reading(it, bytes.toByteArray()) })
                    }
                }
                @Deprecated("Android compatibility callback")
                override fun onError(utteranceId: String?) {
                    if (utteranceId == id) result.complete(null)
                }
                override fun onStop(utteranceId: String?, interrupted: Boolean) {
                    if (utteranceId == id) result.complete(null)
                }
            }
            if (speech.setOnUtteranceProgressListener(listener) != TextToSpeech.SUCCESS ||
                speech.synthesizeToFile(text, null, file, id) != TextToSpeech.SUCCESS) return@withContext null
            withTimeoutOrNull(45_000) { result.await() }
        } finally {
            speech.stop()
            file.delete()
        }
    }

    private suspend fun play(audio: AudioManager, output: AudioDeviceInfo, reading: Reading, onLevel: ((Float) -> Unit)?): Boolean =
        withContext(Dispatchers.Main.immediate) {
            val format = reading.format
            val bytesPerSample = when (format.encoding) {
                AudioFormat.ENCODING_PCM_8BIT -> 1
                AudioFormat.ENCODING_PCM_16BIT -> 2
                AudioFormat.ENCODING_PCM_FLOAT -> 4
                else -> return@withContext false
            }
            val frameSize = bytesPerSample * format.channelCount
            val bufferSize = AudioTrack.getMinBufferSize(format.sampleRate, format.channelMask, format.encoding)
            if (bufferSize <= 0 || reading.bytes.isEmpty() || reading.bytes.size % frameSize != 0) return@withContext false
            val track = AudioTrack.Builder().setAudioAttributes(attributes).setAudioFormat(format)
                .setTransferMode(AudioTrack.MODE_STREAM).setBufferSizeInBytes(bufferSize).build()
            try {
                if (!track.setPreferredDevice(output)) return@withContext false
                playOnSelectedOutput(audio, track, output, reading, frameSize, bufferSize, onLevel)
            } finally {
                track.release()
            }
        }

    private suspend fun playOnSelectedOutput(audio: AudioManager, track: AudioTrack, output: AudioDeviceInfo,
        reading: Reading, frameSize: Int, bufferSize: Int, onLevel: ((Float) -> Unit)?): Boolean {
        val handler = Handler(Looper.getMainLooper())
        var allowed = true
        var audible = false
        fun mute() {
            if (allowed) {
                allowed = false
                track.setVolume(0f)
            }
        }
        val routingListener = AudioRouting.OnRoutingChangedListener {
            if (audible && track.routedDevice?.id != output.id) mute()
        }
        val deviceCallback = object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) {
                if (!output.isBluetoothPlayback && addedDevices.any { it.isSink && it.isBluetoothPlayback }) mute()
            }
            override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) {
                if (removedDevices.any { it.id == output.id }) mute()
            }
        }
        val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(attributes).setWillPauseWhenDucked(true)
            .setOnAudioFocusChangeListener({ if (it < 0) mute() }, handler).build()
        if (audio.requestAudioFocus(focus) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) return false
        track.addOnRoutingChangedListener(routingListener, handler)
        audio.registerAudioDeviceCallback(deviceCallback, handler)
        return try {
            track.setVolume(0f)
            val silence = ByteBuffer.allocateDirect(bufferSize)
            if (reading.format.encoding == AudioFormat.ENCODING_PCM_8BIT) {
                while (silence.hasRemaining()) silence.put(0x80.toByte())
                silence.rewind()
            }
            val primedBytes = track.write(silence, bufferSize, AudioTrack.WRITE_NON_BLOCKING)
            if (primedBytes <= 0) return false
            track.play()
            withTimeoutOrNull(2_000) {
                while (allowed && track.routedDevice?.id != output.id) delay(20)
            } ?: return false
            if (!allowed || audio.voiceOutput()?.id != output.id) return false
            audible = true
            track.setVolume(1f)
            val pcm = ByteBuffer.wrap(reading.bytes).order(ByteOrder.nativeOrder())
            val firstFrame = primedBytes / frameSize
            val end = firstFrame + reading.bytes.size / frameSize
            var nextLevelFrame = firstFrame
            withTimeoutOrNull(150_000) {
                while (allowed && (pcm.hasRemaining() || track.playbackHeadPosition < end)) {
                    if (pcm.hasRemaining() && track.write(pcm, pcm.remaining(), AudioTrack.WRITE_NON_BLOCKING) < 0) return@withTimeoutOrNull false
                    if (onLevel != null) {
                        val frame = track.playbackHeadPosition
                        if (frame >= nextLevelFrame) {
                            onLevel(reading.levelAt(pcm, frame - firstFrame, frameSize))
                            nextLevelFrame = frame + reading.format.sampleRate / 16
                        }
                    }
                    delay(20)
                }
                if (allowed) {
                    onLevel?.invoke(0f)
                    delay(250)
                }
                allowed && track.routedDevice?.id == output.id
            } == true
        } finally {
            track.setVolume(0f)
            audible = false
            allowed = false
            track.removeOnRoutingChangedListener(routingListener)
            audio.unregisterAudioDeviceCallback(deviceCallback)
            audio.abandonAudioFocusRequest(focus)
        }
    }

    override fun close() {
        engine?.stop()
        engine?.shutdown()
        engine = null
    }

    private data class Reading(val format: AudioFormat, val bytes: ByteArray) {
        fun levelAt(pcm: ByteBuffer, frame: Int, frameSize: Int): Float {
            val first = frame * frameSize
            val last = minOf(bytes.size, first + format.sampleRate / 20 * frameSize)
            var energy = 0f
            var count = 0
            for (offset in first until last step (frameSize * 4)) {
                val sample = when (format.encoding) {
                    AudioFormat.ENCODING_PCM_8BIT -> ((pcm.get(offset).toInt() and 0xff) - 128) / 128f
                    AudioFormat.ENCODING_PCM_16BIT -> pcm.getShort(offset) / 32768f
                    else -> pcm.getFloat(offset)
                }
                energy += sample * sample
                count++
            }
            return if (count == 0) 0f else voiceWaveLevel(energy / count)
        }
    }
}

private val AudioDeviceInfo.isBluetoothPlayback: Boolean
    get() = type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP || type == AudioDeviceInfo.TYPE_BLE_HEADSET ||
        type == AudioDeviceInfo.TYPE_HEARING_AID || type == AudioDeviceInfo.TYPE_BLE_BROADCAST

private fun AudioManager.voiceOutput(): AudioDeviceInfo? {
    val devices = getDevices(AudioManager.GET_DEVICES_OUTPUTS)
    return devices.firstOrNull { it.isBluetoothPlayback } ?: devices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
}

private val codeBlock = Regex("(?ms)^\\s*(`{3,}|~{3,}).*?^(?:`{3,}|~{3,})[^\\S\\n]*")
private val markdownLink = Regex("""!?\[([^\]]*)]\(https?://[^)]+\)""")
private val webAddress = Regex("""https?://\S+""")
private val lineMarker = Regex("(?m)^\\s*(?:#{1,6}|[-*+]|>)\\s+")
private val whitespace = Regex("\\s+")

internal fun voiceReadingText(text: String): String {
    val plain = text.replace(codeBlock, "。代码见文字回复。")
        .replace(markdownLink, "$1").replace(webAddress, "").replace(lineMarker, "")
        .replace("**", "").replace("`", "").replace(whitespace, " ").trim()
    return if (plain.length <= 480) plain else {
        val end = if (plain[479].isHighSurrogate()) 479 else 480
        plain.take(end) + "。其余内容请查看文字回复。"
    }
}
