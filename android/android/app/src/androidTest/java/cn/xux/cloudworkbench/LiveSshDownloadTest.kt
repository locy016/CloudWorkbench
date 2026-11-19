package cn.xux.cloudworkbench

import android.os.SystemClock
import android.content.Intent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest

@RunWith(AndroidJUnit4::class)
class LiveSshDownloadTest {
    @Test fun resumesCurrentPublishedApkOverSshAndVerifiesSha256() = runBlocking {
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        val key = File(app.filesDir, "download-ssh-fixture.key")
        assumeTrue("The live SSH test requires an injected private app fixture", key.isFile)
        val report = File(app.filesDir, "download-ssh-evidence.json")
        check(!report.exists() || report.delete())
        val apk = File(app.cacheDir, "live-ssh-download.apk")
        val connection = Connection(openGateway = ::openMobileSshGateway)
        val instrument = InstrumentationRegistry.getInstrumentation()
        val activity = instrument.startActivitySync(Intent(app, ComponentActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as ComponentActivity
        instrument.runOnMainSync { activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
        try {
            withTimeout(30 * 60_000L) {
                val connectStart = SystemClock.elapsedRealtime()
                connection.connect(Credentials(key.readText()))
                val connectedAt = SystemClock.elapsedRealtime()
                val update = connection.request("/v1/app-update").getJSONObject("update")
                val release = AppRelease.parse(update, "cn.xux.cloudworkbench")
                val manifestAt = SystemClock.elapsedRealtime()
                val preexisting = apk.savedUpdateBytes(release)
                assertTrue("The published APK must exercise the parallel path", release.size >= PARALLEL_UPDATE_MIN_SIZE)

                val first = DownloadSamples()
                val cancelAt = 1024L * 1024L
                lateinit var interrupted: Job
                interrupted = launch(start = CoroutineStart.LAZY) {
                    connection.downloadUpdate(release, apk) { bytes ->
                        first.add(bytes)
                        if (bytes >= cancelAt) interrupted.cancel()
                    }
                }
                interrupted.start()
                interrupted.join()
                assertTrue("The first download must end by cancellation", interrupted.isCancelled)
                val saved = apk.savedUpdateBytes(release)
                first.add(saved, force = true)
                assertTrue("Cancellation must leave resumable bytes", saved in cancelAt until release.size)

                val resumed = DownloadSamples()
                val beforeResume = connection.transfers.snapshot().received
                val resumeStarted = SystemClock.elapsedRealtime()
                var lastReportAt = 0L
                var firstResumeProgress: Long? = null
                connection.downloadUpdate(release, apk) { bytes ->
                    if (firstResumeProgress == null) firstResumeProgress = bytes
                    resumed.add(bytes)
                    val elapsed = SystemClock.elapsedRealtime() - resumeStarted
                    if (elapsed - lastReportAt >= 10_000) {
                        File(app.filesDir, "download-ssh-progress.json").writeText(JSONObject()
                            .put("elapsedMs", elapsed).put("savedBeforeResume", saved)
                            .put("bytes", bytes).put("total", release.size).toString())
                        lastReportAt = elapsed
                    }
                }
                val resumeReceived = connection.transfers.snapshot().received - beforeResume
                resumed.add(apk.length(), force = true)
                assertEquals(saved, firstResumeProgress ?: -1L)
                assertEquals(release.size - saved, resumeReceived)
                assertEquals(release.size, apk.length())
                assertFalse(apk.updateParts().exists())

                val hashStart = SystemClock.elapsedRealtime()
                val sha256 = apkSha256(apk)
                val hashMs = SystemClock.elapsedRealtime() - hashStart
                assertEquals(release.sha256, sha256)
                report.writeText(JSONObject()
                    .put("transport", "ssh")
                    .put("versionCode", release.versionCode)
                    .put("versionName", release.versionName)
                    .put("connectMs", connectedAt - connectStart)
                    .put("manifestMs", manifestAt - connectedAt)
                    .put("apkBytes", release.size)
                    .put("apkSha256", sha256)
                    .put("cancelledSavedBytes", saved)
                    .put("preexistingSavedBytes", preexisting)
                    .put("resumeReceivedBytes", resumeReceived)
                    .put("sha256Ms", hashMs)
                    .put("interruptedSamples", first.samples)
                    .put("resumedSamples", resumed.samples)
                    .toString(2))
            }
        } finally {
            connection.disconnect()
            instrument.runOnMainSync { activity.finish() }
            if (report.exists()) {
                apk.delete()
                apk.updateParts().deleteRecursively()
            }
        }
    }
}

private class DownloadSamples {
    private val startedAt = SystemClock.elapsedRealtime()
    private var lastAt = -500L
    val samples = JSONArray()

    @Synchronized fun add(bytes: Long, force: Boolean = false) {
        val elapsed = SystemClock.elapsedRealtime() - startedAt
        if (force || elapsed - lastAt >= 500) {
            samples.put(JSONObject().put("elapsedMs", elapsed).put("bytes", bytes))
            lastAt = elapsed
        }
    }
}

private fun apkSha256(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().use { input ->
        val buffer = ByteArray(65_536)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            digest.update(buffer, 0, count)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
}
