package cn.xux.cloudworkbench

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
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
        }.maxWithOrNull(compareBy({ it.locale.country == "CN" }, { it.quality }))
        if (local == null || speech.setVoice(local) != TextToSpeech.SUCCESS) return false
        return speech.setAudioAttributes(attributes) == TextToSpeech.SUCCESS
    }

    suspend fun speak(text: String): Boolean {
        val speech = engine ?: return false
        val spoken = voiceReadingText(text).takeIf(String::isNotBlank) ?: return false
        val audio = app.getSystemService(AudioManager::class.java) ?: return false
        val result = CompletableDeferred<Boolean>()
        val id = UUID.randomUUID().toString()
        val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(attributes)
            .setWillPauseWhenDucked(true)
            .setOnAudioFocusChangeListener { if (it < 0) result.complete(false) }
            .build()
        if (audio.requestAudioFocus(focus) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) return false
        return try {
            val listener = object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) = Unit
                override fun onDone(utteranceId: String?) {
                    if (utteranceId == id) result.complete(true)
                }
                @Deprecated("Android compatibility callback")
                override fun onError(utteranceId: String?) {
                    if (utteranceId == id) result.complete(false)
                }
                override fun onStop(utteranceId: String?, interrupted: Boolean) {
                    if (utteranceId == id) result.complete(false)
                }
            }
            speech.setOnUtteranceProgressListener(listener) == TextToSpeech.SUCCESS &&
                speech.speak(spoken, TextToSpeech.QUEUE_FLUSH, null, id) == TextToSpeech.SUCCESS &&
                withTimeoutOrNull(150_000) { result.await() } == true
        } finally {
            speech.stop()
            audio.abandonAudioFocusRequest(focus)
        }
    }

    override fun close() {
        engine?.stop()
        engine?.shutdown()
        engine = null
    }
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
