package cn.xux.cloudworkbench

import android.content.Intent
import android.content.pm.PackageManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest

@RunWith(AndroidJUnit4::class)
class UpdateUiTest {
    @get:Rule val compose = createEmptyComposeRule()
    @Test fun showsRateEtaAndSavedCancellation() {
        val instrument = InstrumentationRegistry.getInstrumentation()
        val app = instrument.targetContext
        File(app.filesDir, "app-updates").deleteRecursively()
        val fixture = File(app.filesDir, "offline-fixture.apk")
        val data = fixture.readBytes()
        fun digest(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        val info = checkNotNull(app.packageManager.getPackageArchiveInfo(fixture.path, PackageManager.GET_SIGNING_CERTIFICATES))
        val manifest = JSONObject().put("packageId", app.packageName).put("versionCode", info.longVersionCode)
            .put("versionName", info.versionName).put("size", data.size).put("notes", "界面验收安装包")
            .put("sha256", digest(data)).put("certificate", digest(info.signingInfo!!.apkContentsSigners.single().toByteArray()))
        val connection = object : WorkbenchConnection {
            override val connected = true
            override suspend fun connect(c: Credentials) {}
            override fun disconnect() {}
            override suspend fun request(path: String, body: JSONObject?, delete: Boolean) = JSONObject().put("update", manifest)
            override suspend fun downloadUpdate(release: AppRelease, file: File, progress: (Long) -> Unit) {
                var position = file.length().toInt()
                file.outputStream().use { output ->
                    while (position < data.size) {
                        delay(250)
                        val count = minOf(256, data.size - position)
                        output.write(data, position, count)
                        output.flush()
                        position += count
                        progress(position.toLong())
                    }
                }
            }
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        val activity = instrument.startActivitySync(Intent(app, ComponentActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as ComponentActivity
        lateinit var updates: AppUpdates
        try {
            compose.runOnUiThread {
                updates = AppUpdates(app, scope) { connection }
                activity.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                activity.setContent { WorkbenchTheme { PageColumn { AppUpdatePanel(updates, true) } } }
            }
            compose.waitUntil(5000) { compose.onAllNodesWithTag("appUpdatePanel").fetchSemanticsNodes().isNotEmpty() }
            compose.waitUntil(5000) { !updates.checking }
            compose.onNodeWithTag("appUpdateCheck").performClick()
            compose.waitUntil(5000) { updates.release != null && !updates.checking }
            compose.onNodeWithTag("appUpdateDownload").performClick()
            compose.waitUntil(6000) { updates.speed.value > 0 }
            compose.onNodeWithTag("appUpdateRate").assertTextContains("预计剩余", substring = true)
            compose.onNodeWithTag("appUpdateCancel").performClick()
            try {
                compose.waitUntil(5000) { !updates.downloading }
            } catch (error: Throwable) {
                File(app.filesDir, "update-ui-state.json").writeText("phase=${updates.phase}, cancelling=${updates.cancelling}, bytes=${updates.progress.value}, status=${updates.status}")
                captureReviewScreen("cancel-debug")
                throw error
            }
            assertTrue(updates.progress.value > 0)
            assertEquals(0L, updates.speed.value)
            compose.onNodeWithText("继续下载").assertIsDisplayed()
            compose.onNodeWithTag("appUpdateStatus").assertTextEquals("已取消，可继续下载")
            captureReviewScreen("update-cancelled-0.4.26")
        } finally {
            scope.cancel()
            instrument.runOnMainSync { activity.finish() }
            instrument.waitForIdleSync()
            File(app.filesDir, "app-updates").deleteRecursively()
        }
    }
}
