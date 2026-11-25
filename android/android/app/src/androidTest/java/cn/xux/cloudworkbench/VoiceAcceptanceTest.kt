package cn.xux.cloudworkbench

import android.Manifest
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.provider.Settings
import android.view.WindowManager
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class VoiceAcceptanceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun cloudAssistantContinuesTwoHandsFreeVoiceTurns() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val id = requireNotNull(InstrumentationRegistry.getArguments().getString("voiceAcceptanceThread"))
        require(id.matches(Regex("[a-f0-9-]{36}")))
        val activity = compose.activity
        val prefs = activity.getSharedPreferences("ui", 0)
        val previous = listOf("project", "thread-cloud", "thread").associateWith { prefs.getString(it, null) }
        val audio = activity.getSystemService(AudioManager::class.java)
        val volume = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
        val vm = ViewModelProvider(activity)[WorkbenchModel::class.java]
        fun waitForListening() {
            compose.waitUntil(90_000) {
                if (compose.onAllNodes(hasTestTag("endVoiceChat") and hasText("返回对话")).fetchSemanticsNodes().isNotEmpty()) {
                    val status = compose.onNodeWithTag("voiceChatStatus").fetchSemanticsNode()
                        .config[androidx.compose.ui.semantics.SemanticsProperties.Text].joinToString()
                    throw AssertionError(status)
                }
                compose.onAllNodes(hasTestTag("voiceChatStatus") and hasText("正在聆听")).fetchSemanticsNodes().isNotEmpty()
            }
        }
        try {
            instrumentation.uiAutomation.grantRuntimePermission(activity.packageName, Manifest.permission.RECORD_AUDIO)
            compose.runOnUiThread { activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
            compose.connectWorkbenchIfNeeded()
            compose.openWorkbenchTab("会话")
            compose.runOnIdle { vm.select(id) }
            compose.waitUntil(30_000) {
                compose.runOnIdle { vm.currentId == id } &&
                    compose.onAllNodes(hasTestTag("startVoiceChat") and isEnabled()).fetchSemanticsNodes().isNotEmpty()
            }
            audio.setStreamVolume(AudioManager.STREAM_MUSIC, audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC) / 2, 0)
            compose.onNodeWithTag("startVoiceChat").performClick()
            waitForListening()
            val phrases = listOf("你好，请简单地打个招呼。", "请用一句话和我说再见。")
            for ((index, phrase) in phrases.withIndex()) {
                runBlocking {
                    withContext(Dispatchers.Main) {
                        VoicePlayback(activity).use { source ->
                            org.junit.Assert.assertTrue("An offline Chinese voice is required", source.prepare())
                            org.junit.Assert.assertTrue("Playback must reach the microphone", source.speak(phrase))
                        }
                    }
                }
                compose.waitUntil(120_000) {
                    compose.runOnIdle { vm.messages.count { it.optString("role") == "assistant" &&
                        it.optString("status") == "completed" } >= index + 1 }
                }
                waitForListening()
            }
            compose.onNodeWithTag("endVoiceChat").performClick()
            compose.onNodeWithTag("voiceChatStatus").assertDoesNotExist()
            compose.waitUntil(10_000) {
                compose.onAllNodes(hasTestTag("startVoiceChat") and isEnabled()).fetchSemanticsNodes().isNotEmpty()
            }
            compose.runOnIdle {
                assertEquals(id, vm.currentId)
                assertEquals(2, vm.messages.count { it.optString("role") == "user" })
                org.junit.Assert.assertTrue(vm.messages.filter { it.optString("role") == "user" }
                    .all { it.optString("text").contains(Regex("[\\u4e00-\\u9fff]")) })
            }
        } finally {
            audio.setStreamVolume(AudioManager.STREAM_MUSIC, volume, 0)
            compose.runOnUiThread { activity.finish() }
            val edit = prefs.edit()
            previous.forEach { (key, value) -> edit.putString(key, value) }
            edit.commit()
        }
    }

    @Test fun offlineMicrophoneWritesEditableDraft() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        assertEquals(1, Settings.Global.getInt(context.contentResolver, Settings.Global.AIRPLANE_MODE_ON))
        assertEquals(0, Settings.Global.getInt(context.contentResolver, Settings.Global.WIFI_ON))
        microphoneWritesEditableDraft()
    }

    @Test fun microphoneWritesEditableDraft() {
        compose.openWorkbenchTab("会话")
        compose.onNodeWithTag("messageInput").assertIsDisplayed()
        val result = recordSample()
        android.util.Log.i("VoiceAcceptance", "TB322FC speaker-to-microphone draft: $result")
        org.junit.Assert.assertTrue(result.contains(Regex("[\\u4e00-\\u9fff]")))
        org.junit.Assert.assertTrue(result.contains(Regex("[a-zA-Z]{3,}")))
        compose.onNodeWithTag("send").assertIsNotEnabled()
        compose.onNodeWithTag("messageInput").assertTextContains(result)
        val edited = "$result\n平板语音验收"
        compose.onNodeWithTag("messageInput").performTextReplacement(edited)
        compose.onNodeWithTag("voiceToggle").performClick()
        compose.onNodeWithTag("voiceDraft").assertTextContains(edited)
        compose.onNodeWithTag("voiceHold").performSemanticsAction(SemanticsActions.OnClick)
        compose.waitUntil(12000) { compose.runOnIdle { dictation.stage == OfflineDictation.Stage.Listening } }
        compose.onNodeWithTag("project-任务").performClick()
        compose.onNodeWithTag("project-对话").performClick()
        compose.waitUntil(10000) { compose.onAllNodesWithTag("messageInput").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("messageInput").assertTextContains(edited)
    }

    @Test fun editedVoiceDraftRequiresManualSend() {
        compose.connectWorkbenchIfNeeded()
        compose.openWorkbenchTab("会话")
        compose.onNodeWithTag("newChat").performClick()
        val result = recordSample(capture = true)
        compose.onNodeWithTag("messages").assertDoesNotExist()
        compose.onNodeWithTag("messageInput").performClick()
        compose.onNodeWithTag("messageInput").assertTextContains(result)
        val marker = "语音验收通过"
        val edited = "请只回复「$marker」。\n$result"
        compose.onNodeWithTag("messageInput").performTextReplacement(edited)
        compose.waitUntil(10000) {
            androidx.core.view.ViewCompat.getRootWindowInsets(compose.activity.window.decorView)
                ?.isVisible(androidx.core.view.WindowInsetsCompat.Type.ime()) == true
        }
        compose.mainClock.advanceTimeBy(350)
        compose.waitForIdle()
        val input = compose.onNodeWithTag("messageInput").assertIsDisplayed()
        val window = compose.activity.window.decorView
        val imeBottom = androidx.core.view.ViewCompat.getRootWindowInsets(window)
            ?.getInsets(androidx.core.view.WindowInsetsCompat.Type.ime())?.bottom ?: 0
        if (imeBottom > 0) org.junit.Assert.assertTrue(input.fetchSemanticsNode().boundsInWindow.bottom <= window.height - imeBottom + 1)
        captureReviewScreen("voice-edit")
        compose.onNodeWithTag("messages").assertDoesNotExist()
        compose.onNodeWithTag("send").assertIsEnabled().performClick()
        compose.waitUntil(90000) {
            compose.onAllNodes(hasTestTag("message-assistant") and hasAnyDescendant(hasText(marker, substring = true)),
                useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("send").assertDoesNotExist()
        captureReviewScreen("voice-sent")
    }

    private fun recordSample(capture: Boolean = false): String {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.RECORD_AUDIO)
        compose.activity.runOnUiThread { compose.activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
        compose.onNodeWithTag("voiceToggle").assertContentDescriptionEquals("切换到语音输入")
        compose.onNodeWithTag("voiceToggle").performClick()
        val hold = compose.onNodeWithTag("voiceHold")
        hold.performTouchInput { down(center) }
        compose.waitUntil(12000) { compose.runOnIdle { dictation.stage == OfflineDictation.Stage.Listening } }
        val audio = instrumentation.context.assets.open("voice-sample.wav").use { it.readBytes() }
        check(String(audio, 36, 4, Charsets.US_ASCII) == "data")
        val pcmBytes = audio.size - 44
        val track = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).build())
            .setAudioFormat(AudioFormat.Builder().setSampleRate(16000).setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
            .setTransferMode(AudioTrack.MODE_STATIC)
            .setBufferSizeInBytes(pcmBytes)
            .build()
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val originalVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        try {
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC) / 2, 0)
            assertEquals(pcmBytes, track.write(audio, 44, pcmBytes))
            track.play()
            compose.waitUntil(7000) { compose.runOnIdle { dictation.partial.isNotBlank() } }
            org.junit.Assert.assertTrue(track.playbackHeadPosition < pcmBytes / 2)
            if (capture) captureReviewScreen("voice-recording")
            compose.waitUntil(14000) { track.playbackHeadPosition >= pcmBytes / 2 }
        } finally {
            track.release()
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, originalVolume, 0)
        }
        hold.performTouchInput { up() }
        compose.waitUntil(10000) {
            compose.onAllNodesWithTag("messageInput").fetchSemanticsNodes().isNotEmpty()
        }
        if (capture) captureReviewScreen("voice-draft")
        return compose.onNodeWithTag("messageInput").fetchSemanticsNode()
            .config[androidx.compose.ui.semantics.SemanticsProperties.EditableText].text
    }

    private val dictation
        get() = ViewModelProvider(compose.activity)[WorkbenchModel::class.java].dictation
}
