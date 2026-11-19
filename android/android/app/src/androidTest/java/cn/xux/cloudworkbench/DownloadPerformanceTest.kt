package cn.xux.cloudworkbench

import android.os.SystemClock
import android.content.Intent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.net.Proxy
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Controlled real-device comparison: same immutable APK prefix, disk writes, and authentication. */
@RunWith(AndroidJUnit4::class)
class DownloadPerformanceTest {
    @Test fun compareRangeSchedulingAndIndependentTransports() = runBlocking {
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        val key = File(app.filesDir, "download-ssh-fixture.key").readText()
        val sessions = mutableListOf<MobileGatewaySession>()
        val clients = mutableListOf<OkHttpClient>()
        val evidence = JSONObject().put("bytesPerCase", 24L * 1024 * 1024)
        val results = JSONArray()
        evidence.put("results", results)
        val report = File(app.filesDir, "download-performance.json")
        val directory = File(app.cacheDir, "download-performance").apply { mkdirs() }
        val instrument = InstrumentationRegistry.getInstrumentation()
        val activity = instrument.startActivitySync(Intent(app, ComponentActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as ComponentActivity
        instrument.runOnMainSync { activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
        try {
            val connecting = SystemClock.elapsedRealtime()
            sessions += openMobileSshGateway(key)
            fun client() = OkHttpClient.Builder().proxy(Proxy.NO_PROXY)
                .connectTimeout(20, TimeUnit.SECONDS).readTimeout(65, TimeUnit.SECONDS)
                .retryOnConnectionFailure(true).build().also { clients += it }
            val shared = client()
            fun request(session: MobileGatewaySession, path: String) = Request.Builder()
                .url(session.origin + path).header("Authorization", "Bearer " + session.bearer())
                .header("Accept-Encoding", "identity")
            val manifest = withContext(Dispatchers.IO) {
                shared.newCall(request(sessions[0], "/v1/app-update").build()).execute().use {
                    check(it.isSuccessful); JSONObject(it.body!!.string()).getJSONObject("update")
                }
            }
            evidence.put("version", manifest.getString("versionName"))
                .put("sha256", manifest.getString("sha256"))
                .put("connectAndManifestMs", SystemClock.elapsedRealtime() - connecting)
            val path = "/v1/app-update/" + manifest.getString("sha256") + "/apk"
            val total = 24L * 1024 * 1024
            suspend fun measure(name: String, chunk: Long, workers: Int, isolated: Boolean = false) {
                val cursor = AtomicInteger()
                val requests = (total / chunk).toInt()
                val start = SystemClock.elapsedRealtime()
                coroutineScope {
                    List(workers) { worker -> async(Dispatchers.IO) {
                        val active = sessions[if (isolated) worker % sessions.size else 0]
                        val http = clients[if (isolated) worker % sessions.size else 0]
                        while (true) {
                            val index = cursor.getAndIncrement()
                            if (index >= requests) break
                            val first = index * chunk
                            val call = request(active, path).header("Range", "bytes=$first-${first + chunk - 1}")
                                .header("If-Range", "\"${manifest.getString("sha256")}\"").build()
                            http.newCall(call).execute().use { response ->
                                assertEquals(206, response.code)
                                assertEquals(chunk, response.body!!.contentLength())
                                File(directory, "$worker.part").outputStream().buffered().use { out ->
                                    assertEquals(chunk, response.body!!.byteStream().use { it.copyTo(out, 65536) })
                                }
                            }
                        }
                    }}.awaitAll()
                }
                val elapsed = SystemClock.elapsedRealtime() - start
                results.put(JSONObject().put("name", name).put("requestCount", requests)
                    .put("workers", workers).put("sshConnections", if (isolated) sessions.size else 1)
                    .put("elapsedMs", elapsed).put("bytesPerSecond", total * 1000 / elapsed))
                report.writeText(evidence.toString(2))
            }
            if (InstrumentationRegistry.getArguments().getString("mode") != "transport") {
                measure("small-1MiB-4-workers", 1024L * 1024, 4)
                measure("long-range-8-workers", 3L * 1024 * 1024, 8)
                measure("small-1MiB-8-workers", 1024L * 1024, 8)
            }
            val opening = SystemClock.elapsedRealtime()
            sessions += coroutineScope { List(3) { async { openMobileSshGateway(key) } }.awaitAll() }
            repeat(3) { client() }
            evidence.put("additionalTransportConnectMs", SystemClock.elapsedRealtime() - opening)
            measure("long-range-8-workers-4-ssh", 3L * 1024 * 1024, 8, isolated = true)
            measure("long-range-8-workers-repeat", 3L * 1024 * 1024, 8)
        } finally {
            sessions.forEach { it.close() }
            clients.forEach { it.connectionPool.evictAll(); it.dispatcher.executorService.shutdown() }
            directory.deleteRecursively()
            instrument.runOnMainSync { activity.finish() }
        }
    }
}
