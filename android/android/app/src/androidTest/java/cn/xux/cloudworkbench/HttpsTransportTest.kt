package cn.xux.cloudworkbench

import android.app.Application
import android.content.Context
import android.os.SystemClock
import android.util.Base64
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeNotNull
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.net.InetAddress
import java.net.Socket
import java.security.KeyStore
import java.security.cert.CertificateFactory
import java.util.UUID
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLException
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

@RunWith(AndroidJUnit4::class)
class HttpsTransportTest {
    @Test fun validatesTlsAndResumesEventCatchUpAfterBackgroundAndNetworkChange() {
        val encoded = InstrumentationRegistry.getArguments().getString("fixtureCertificate")
        assumeNotNull(encoded)
        val fixture = "https://localhost:18443"
        val binding = Credentials("fixture")
        fun tunnel(origin: String): suspend (String) -> MobileGatewaySession = {
            object : MobileGatewaySession {
                override val origin = origin
                override fun bearer() = "sshm1.android-usb-fixture"
                override fun close() {}
            }
        }
        val certificate = CertificateFactory.getInstance("X.509")
            .generateCertificate(ByteArrayInputStream(Base64.decode(encoded, Base64.DEFAULT)))
        val trustStore = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
            load(null)
            setCertificateEntry("fixture", certificate)
        }
        val trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(trustStore) }
            .trustManagers.single() as X509TrustManager
        val tls = SSLContext.getInstance("TLS").apply { init(null, arrayOf(trust), null) }
        val configure: OkHttpClient.Builder.() -> Unit = {
            sslSocketFactory(tls.socketFactory, trust)
            dns(object : Dns {
                override fun lookup(hostname: String) = listOf(InetAddress.getByName("127.0.0.1"))
            })
        }
        fun assertRejected(connection: Connection) {
            try {
                val failure = runBlocking { runCatching { connection.connect(binding) }.exceptionOrNull() }
                assertTrue("TLS validation must reject the endpoint", failure is SSLException)
            } finally { connection.disconnect() }
        }
        assertRejected(Connection(tunnel(fixture)))
        assertRejected(Connection(tunnel("https://wrong.test:18443"), configureClient = configure))
        val browser = Connection(tunnel(fixture), configureClient = configure)
        try {
            runBlocking {
                browser.connect(binding)
                val image = browser.browserImage("http://fixture.test/fixture-image", "", "Workbench USB acceptance")
                assertEquals("WORKBENCH", image.toString(Charsets.US_ASCII))
                val expected = "workbench APK resume fixture"
                val release = AppRelease(33, "0.4.20", "b".repeat(64), "c".repeat(64), expected.length.toLong(), "")
                val context = InstrumentationRegistry.getInstrumentation().targetContext
                val partial = File.createTempFile("resume-fixture-", ".apk", context.cacheDir)
                try {
                    val cut = runCatching { browser.downloadUpdate(release, partial) {} }.exceptionOrNull()
                    assertTrue("The first response must end early", cut is IOException)
                    assertEquals(9L, partial.length())
                    browser.downloadUpdate(release, partial) {}
                    assertEquals(expected, partial.readText())
                } finally { partial.delete() }
                val complete = File.createTempFile("parallel-fixture-", ".apk", context.cacheDir)
                try {
                    // Mix bytes saved by the previous single-stream updater and a resumed range.
                    val expectedBytes = ByteArray(PARALLEL_UPDATE_MIN_SIZE.toInt()) { index ->
                        ((index ushr 16) xor (index and 255)).toByte()
                    }
                    complete.writeBytes(expectedBytes.copyOfRange(0, 65_555))
                    val parts = complete.updateParts()
                    check(parts.mkdir())
                    val middle = expectedBytes.size / 8 * 3
                    File(parts, "3").writeBytes(expectedBytes.copyOfRange(middle, middle + 12345))
                    val parallelRelease = release.copy(sha256 = "d".repeat(64), size = expectedBytes.size.toLong())
                    browser.downloadUpdate(parallelRelease, complete) {}
                    assertTrue("All ranges must be merged in order", expectedBytes.contentEquals(complete.readBytes()))
                    assertFalse(parts.exists())
                } finally {
                    complete.delete()
                    complete.updateParts().deleteRecursively()
                }
            }
        } finally { browser.disconnect() }
        val client = OkHttpClient.Builder().apply(configure).build()
        fun fixtureCall(path: String, post: Boolean = false): String {
            val request = Request.Builder().url(fixture + path).apply { if (post) post(ByteArray(0).toRequestBody()) }.build()
            return client.newCall(request).execute().use {
                assertEquals(200, it.code)
                it.body!!.string()
            }
        }
        fun activeFeeds() = JSONObject(fixtureCall("/fixture/stats")).getInt("activeFeeds")
        val tester = InstrumentationRegistry.getInstrumentation()
        val app = tester.targetContext.applicationContext as Application
        val prefs = app.getSharedPreferences("tls-fixture-" + UUID.randomUUID(), Context.MODE_PRIVATE)
        var model: WorkbenchModel? = null
        fun onMain(block: (WorkbenchModel) -> Unit) {
            var error: Throwable? = null
            tester.runOnMainSync { runCatching { block(checkNotNull(model)) }.onFailure { error = it } }
            error?.let { throw it }
        }
        fun await(description: String, condition: () -> Boolean) {
            val until = SystemClock.elapsedRealtime() + 12_000
            while (SystemClock.elapsedRealtime() < until) {
                if (condition()) return
                SystemClock.sleep(60)
            }
            assertTrue(description, condition())
        }
        fun online(): Boolean {
            var ready = false
            onMain { ready = it.hostOnline }
            return ready
        }
        fun hasThread(id: String): Boolean {
            var found = false
            onMain { found = it.threads.any { row -> row.optString("id") == id } }
            return found
        }
        val first = fixtureCall("/fixture/append", post = true)
        tester.runOnMainSync {
            model = WorkbenchModel(app, { Connection(tunnel(fixture), configureClient = configure) }, binding, prefs)
            model!!.foreground(true)
        }
        try {
            await("initial event stream and snapshot") { online() && activeFeeds() == 1 && hasThread("fixture-thread-$first") }
            val before = SystemClock.elapsedRealtime()
            val foreground = fixtureCall("/fixture/append", post = true)
            await("foreground notification refreshes threads") { hasThread("fixture-thread-$foreground") }
            val latency = SystemClock.elapsedRealtime() - before
            println("USB local fixture change-to-visible: ${latency}ms")
            onMain { it.foreground(false) }
            await("going to background closes the event feed") { activeFeeds() == 0 }
            val background = fixtureCall("/fixture/append", post = true)
            assertFalse(hasThread("fixture-thread-$background"))
            onMain { it.foreground(true) }
            await("foreground catch-up retrieves missed updates") { activeFeeds() == 1 && hasThread("fixture-thread-$background") }
            onMain {
                it.defaultNetworkChanged(100)
                it.defaultNetworkChanged(null)
            }
            await("losing the default network closes the event feed") { activeFeeds() == 0 }
            val switched = fixtureCall("/fixture/append", post = true)
            onMain { it.defaultNetworkChanged(101) }
            await("default network recovery catches up") { online() && activeFeeds() == 1 && hasThread("fixture-thread-$switched") }
            var proxyPort = 0
            onMain { proxyPort = it.browserPort }
            Socket("127.0.0.1", proxyPort).use { unauthenticated ->
                unauthenticated.soTimeout = 4000
                unauthenticated.getOutputStream().write("CONNECT example.com:443 HTTP/1.1\r\nHost: example.com\r\n\r\n".toByteArray())
                assertTrue(unauthenticated.getInputStream().bufferedReader().readLine().contains("407"))
                assertEquals(0, JSONObject(fixtureCall("/fixture/stats")).getInt("activeTunnels"))
            }
        } finally {
            onMain { it.foreground(false); it.disconnect() }
            client.dispatcher.cancelAll()
            client.connectionPool.evictAll()
        }
    }
}
