package cn.xux.cloudworkbench

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import android.content.Intent
import android.view.WindowManager
import androidx.test.platform.app.InstrumentationRegistry
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Run after replacing the configured 0.4.25 acceptance APK with 0.4.26 using install -r. */
@RunWith(AndroidJUnit4::class)
class UpgradeConnectionTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun retainsSshKeyAndReconnectsAfterUpgrade() {
        val instrument = InstrumentationRegistry.getInstrumentation()
        val app = instrument.startActivitySync(Intent(instrument.targetContext, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        instrument.runOnMainSync { app.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
        try {
        val expected = File(app.filesDir, "download-ssh-fixture.key").readText().trim()
        assertTrue("The encrypted SSH credential must survive the APK replacement",
            Vault(app).load().privateKey.trim() == expected)
        val model = ViewModelProvider(app)[WorkbenchModel::class.java]
        try {
            compose.waitUntil(60_000) { model.connected && model.hostOnline }
        } catch (error: Throwable) {
            File(app.filesDir, "upgrade-state.txt").writeText("connected=${model.connected}, hostOnline=${model.hostOnline}, reconnecting=${model.reconnecting}, configured=${model.configured}, error=${model.error}")
            throw error
        }
        compose.openWorkbenchTab("设置")
        compose.onNodeWithText("状态：已连接").assertIsDisplayed()
        compose.onNodeWithTag("connectionHelp").assertDoesNotExist()
        compose.onNodeWithTag("importPrivateKey").assertDoesNotExist()
        compose.onNodeWithText("SSH 绑定").assertDoesNotExist()
        captureReviewScreen("upgrade-settings-0.4.26")
        } finally { instrument.runOnMainSync { app.finish() }; instrument.waitForIdleSync() }
    }
}
