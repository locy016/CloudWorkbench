package cn.xux.cloudworkbench

import android.Manifest
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.provider.Settings
import android.view.WindowManager
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class VoiceAcceptanceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun offlineMicrophoneWritesEditableDraft() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        assertEquals(1, Settings.Global.getInt(context.contentResolver, Settings.Global.AIRPLANE_MODE_ON))
        assertEquals(0, Settings.Global.getInt(context.contentResolver, Settings.Global.WIFI_ON))
        compose.openWorkbenchTab("会话")
        compose.onNodeWithTag("messageInput").assertDoesNotExist()
        val result = recordSample()
        android.util.Log.i("VoiceAcceptance", "TB322FC speaker-to-microphone draft: $result")
        org.junit.Assert.assertTrue(result.contains(Regex("[\\u4e00-\\u9fff]")))
        org.junit.Assert.assertTrue(result.contains(Regex("[a-zA-Z]{3,}")))
        compose.onNodeWithTag("send").assertIsNotEnabled()
        compose.onNodeWithTag("keyboardToggle").performClick()
        compose.onNodeWithTag("messageInput").assertTextContains(result)
        val edited = "$result\n平板语音验收"
        compose.onNodeWithTag("messageInput").performTextReplacement(edited)
        compose.onNodeWithTag("keyboardToggle").performClick()
        compose.onNodeWithTag("voiceDraft").assertTextContains(edited)
        compose.onNodeWithTag("voiceToggle").performClick()
        compose.waitUntil(12000) { compose.onAllNodesWithContentDescription("正在听 · 点击结束").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("project-任务").performClick()
        compose.onNodeWithTag("project-对话").performClick()
        compose.waitUntil(10000) { compose.onAllNodesWithContentDescription("点击说话").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("voiceDraft").assertTextContains(edited)
    }

    @Test fun editedVoiceDraftRequiresManualSend() {
        compose.connectWorkbenchIfNeeded()
        compose.openWorkbenchTab("会话")
        compose.onNodeWithTag("newChat").performClick()
        val result = recordSample(capture = true)
        compose.onNodeWithTag("messages").assertDoesNotExist()
        compose.onNodeWithTag("voiceDraft").performClick()
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
        compose.onNodeWithTag("keyboardToggle").performClick()
        compose.onNodeWithTag("voiceDraft").assertTextContains(edited)
        compose.onNodeWithTag("messages").assertDoesNotExist()
        compose.onNodeWithTag("send").assertIsEnabled().performClick()
        compose.waitUntil(90000) {
            compose.onAllNodes(hasTestTag("message-assistant") and hasAnyDescendant(hasText(marker, substring = true)),
                useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("voiceDraft").assertDoesNotExist()
        captureReviewScreen("voice-sent")
    }

    private fun recordSample(capture: Boolean = false): String {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.RECORD_AUDIO)
        compose.activity.runOnUiThread { compose.activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
        compose.onNodeWithTag("voiceToggle").assertContentDescriptionEquals("点击说话")
        compose.onNodeWithTag("voiceToggle").performClick()
        compose.waitUntil(12000) { compose.onAllNodesWithContentDescription("正在听 · 点击结束").fetchSemanticsNodes().isNotEmpty() }
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
            compose.waitUntil(7000) { compose.onAllNodesWithTag("voicePartial").fetchSemanticsNodes().isNotEmpty() }
            org.junit.Assert.assertTrue(track.playbackHeadPosition < pcmBytes / 2)
            if (capture) captureReviewScreen("voice-recording")
            compose.waitUntil(14000) { track.playbackHeadPosition >= pcmBytes / 2 }
        } finally {
            track.release()
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, originalVolume, 0)
        }
        compose.onNodeWithTag("voiceToggle").performClick()
        compose.waitUntil(10000) {
            compose.onAllNodesWithContentDescription("点击说话").fetchSemanticsNodes().isNotEmpty() &&
                compose.onAllNodesWithTag("voiceDraft").fetchSemanticsNodes().isNotEmpty()
        }
        if (capture) captureReviewScreen("voice-draft")
        return compose.onNodeWithTag("voiceDraft").fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsProperties.Text]
            .joinToString("") { it.text }
    }
}
