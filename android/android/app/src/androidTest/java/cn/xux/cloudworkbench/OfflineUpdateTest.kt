package cn.xux.cloudworkbench

import android.content.pm.PackageManager
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest

/** Run prepare and restore in separate instrumentation processes. No installer is launched. */
@RunWith(AndroidJUnit4::class)
class OfflineUpdateTest {
    private val instrument = InstrumentationRegistry.getInstrumentation()
    private val app = instrument.targetContext
    private fun digest(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it) }
    private fun await(condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 15000
        while (SystemClock.elapsedRealtime() < deadline) {
            var done = false
            instrument.runOnMainSync { done = condition() }
            if (done) return
            SystemClock.sleep(30)
        }
        fail("Update state did not settle")
    }

    @Test fun prepareVerifiedDownload() {
        val fixture = File(app.filesDir, "offline-fixture.apk")
        check(fixture.isFile) { "Inject the signed future-version acceptance APK first" }
        val info = checkNotNull(app.packageManager.getPackageArchiveInfo(fixture.path, PackageManager.GET_SIGNING_CERTIFICATES))
        val manifest = JSONObject().put("packageId", app.packageName).put("versionCode", info.longVersionCode)
            .put("versionName", info.versionName).put("size", fixture.length()).put("notes", "Offline fixture")
            .put("sha256", digest(fixture.readBytes())).put("certificate", digest(info.signingInfo!!.apkContentsSigners.single().toByteArray()))
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        lateinit var updates: AppUpdates
        val connection = object : WorkbenchConnection {
            override val connected = true
            override suspend fun connect(c: Credentials) {}
            override fun disconnect() {}
            override suspend fun request(path: String, body: JSONObject?, delete: Boolean) = JSONObject().put("update", manifest)
            override suspend fun downloadUpdate(release: AppRelease, file: File, progress: (Long) -> Unit) {
                fixture.copyTo(file, overwrite = true)
                progress(file.length())
            }
        }
        try {
            instrument.runOnMainSync { updates = AppUpdates(app, scope) { connection }; updates.check() }
            await { !updates.checking && updates.release != null }
            instrument.runOnMainSync { updates.download() }
            await { !updates.downloading && updates.ready }
            assertEquals(fixture.length(), updates.progress.value)
        } finally { scope.cancel() }
    }

    @Test fun restoreVerifiedDownloadWithNoConnection() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        lateinit var updates: AppUpdates
        try {
            instrument.runOnMainSync {
                updates = AppUpdates(app, scope) { error("Offline restore must not access a connection") }
            }
            await { !updates.checking && !updates.downloading && updates.ready }
            assertEquals(40L, updates.release?.versionCode)
            assertEquals("安装包校验通过", updates.status)
        } finally { scope.cancel() }
    }

    @Test fun rejectsCorruptedSavedApkOffline() {
        val directory = File(app.filesDir, "app-updates")
        val apk = checkNotNull(directory.listFiles()?.singleOrNull { it.extension == "apk" })
        java.io.RandomAccessFile(apk, "rw").use { it.write(0) }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        lateinit var updates: AppUpdates
        try {
            instrument.runOnMainSync { updates = AppUpdates(app, scope) { error("Must stay offline") } }
            await { !updates.checking && !updates.downloading && updates.status != null }
            assertFalse(updates.ready)
            assertFalse(apk.exists())
            assertTrue(updates.status.orEmpty().contains("SHA-256"))
        } finally { scope.cancel() }
    }
}
