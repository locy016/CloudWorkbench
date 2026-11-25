package cn.xux.cloudworkbench

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.Closeable
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.concurrent.thread

@RunWith(AndroidJUnit4::class)
class NewsTransportTest {
    @Test fun chunksLongReaderParagraphsWithoutLosingTextOrSplittingSurrogates() {
        val words = List(95) { "paragraph$it" }.joinToString(" ")
        val chunks = newsParagraphChunks(words)
        assertEquals(words, chunks.joinToString(" "))
        assertTrue(chunks.size > 1 && chunks.all { it.length <= 360 })

        val unbroken = "a".repeat(359) + "🔖" + "b".repeat(50)
        val unbrokenChunks = newsParagraphChunks(unbroken)
        assertEquals(unbroken, unbrokenChunks.joinToString(""))
        assertTrue(unbrokenChunks.none { it.last().isHighSurrogate() || it.first().isLowSurrogate() })
    }

    @Test fun automaticTranslationWaitsForAPreviousBatchAndCachesTheResult() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val prefs = context.getSharedPreferences("news-transport-fixture", Context.MODE_PRIVATE)
        var attempts = 0
        val connection = object : WorkbenchConnection {
            override val connected = true
            override fun disconnect() = Unit
            override suspend fun connect(c: Credentials) = Unit
            override suspend fun request(path: String, body: JSONObject?, delete: Boolean): JSONObject {
                assertEquals("/v1/server-data/news/translate-batch", path)
                if (++attempts <= 5) throw GatewayException(429, "Translation worker busy")
                return JSONObject("""{"target":"zh","articles":[{"title":"标题","summary":"摘要"}]}""")
            }
        }
        val translations = NewsTranslations(prefs) { connection }
        val article = NewsArticle("Headline", "Fixture", null, null, "Summary", null, "en", null)
        translations.translate(listOf(article), "zh")
        assertEquals(NewsTranslation("标题", "摘要"), translations.get(article, "zh"))
        translations.translate(listOf(article), "zh")
        assertEquals(6, attempts)
    }

    @Test fun busyAndExpiredArticleReferencesRecoverWithoutChangingFilters() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val prefs = context.getSharedPreferences("news-transport-fixture", Context.MODE_PRIVATE)
        val id = "a".repeat(64)
        val query = NewsQuery(sourceId = "fixture", category = NewsCategory.Trade)
        val requests = mutableListOf<String>()
        var reads = 0
        val connection = object : WorkbenchConnection {
            override val connected = true
            override fun disconnect() = Unit
            override suspend fun connect(c: Credentials) = Unit
            override suspend fun request(path: String, body: JSONObject?, delete: Boolean): JSONObject {
                requests.add(path)
                if (path == query.path()) return JSONObject("""{"sources":[],"unavailable":[],"matchedCount":1,
                    "articles":[{"title":"Title","publisher":"Fixture","sourceId":"fixture","readerId":"$id"}]}""")
                assertEquals("/v1/server-data/news/article?id=$id", path)
                return when (++reads) {
                    1 -> throw GatewayException(429, "Busy")
                    2 -> throw GatewayException(404, "Expired")
                    else -> JSONObject("""{"id":"$id","blocks":[{"kind":"paragraph","text":"Final paragraph"}],
                        "truncated":false,"language":"en"}""")
                }
            }
        }
        val feed = NewsFeed(prefs) { connection }
        feed.refresh(query)
        val prior = feed.snapshot
        val article = feed.readArticle(id)
        assertEquals("Final paragraph", article.blocks.single().text)
        assertEquals(prior, feed.snapshot)
        assertEquals(2, requests.count { it == query.path() })
        assertEquals(article, feed.readArticle(id))
        assertEquals(3, reads)
    }

    @Test fun slowUpstreamAndTransientFailureDoNotDiscardTheNewsFeed() = runBlocking {
        NewsGatewayFixture().use { gateway ->
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val prefs = context.getSharedPreferences("news-transport-fixture", Context.MODE_PRIVATE)
            val connection = Connection(openGateway = {
                object : MobileGatewaySession {
                    override val origin = gateway.origin
                    override fun bearer() = "sshm1.news-test-fixture"
                    override fun close() = Unit
                }
            })
            try {
                connection.connect(Credentials("fixture"))
                val feed = NewsFeed(prefs) { connection }
                val slow = NewsQuery(keyword = "slow")
                feed.refresh(slow)
                assertNull("A valid response after the old eight-second deadline must be accepted", feed.refreshError)
                assertEquals(slow, feed.snapshot?.query)
                assertEquals("Published article", feed.snapshot?.articles?.single()?.title)

                val recovered = NewsQuery(keyword = "retry")
                feed.refresh(recovered)
                assertNull(feed.refreshError)
                assertEquals(recovered, feed.snapshot?.query)
                assertEquals(2, gateway.requests.count { it.endsWith("?q=retry") })

                feed.refresh(recovered)
                assertEquals("服务器返回的资讯格式异常", feed.refreshError)
                assertEquals(recovered, feed.snapshot?.query)
                assertEquals(3, gateway.requests.count { it.endsWith("?q=retry") })
            } finally {
                connection.disconnect()
            }
        }
    }
}

private class NewsGatewayFixture : Closeable {
    private val listener = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
    val origin = "http://127.0.0.1:${listener.localPort}"
    val requests = ConcurrentLinkedQueue<String>()
    private var retries = 0
    private val serveThread = thread(name = "news-gateway-fixture", isDaemon = true) {
        while (!listener.isClosed) {
            try {
                listener.accept().use(::serve)
            } catch (_: IOException) {
                // A timeout can close the accepted socket before the delayed response is written.
            }
        }
    }

    private fun serve(socket: Socket) {
        socket.soTimeout = 4_000
        val input = socket.getInputStream().bufferedReader(Charsets.US_ASCII)
        val path = input.readLine()?.split(' ')?.getOrNull(1) ?: return
        while (input.readLine()?.isNotEmpty() == true) Unit
        requests.add(path)
        val feed = """{"sources":[],"unavailable":[],"matchedCount":1,
            "articles":[{"title":"Published article","publisher":"Fixture","sourceId":"fixture","language":"en"}]}"""
        val (status, body) = when (path) {
            "/v1/projects" -> "200 OK" to "{}"
            "/v1/server-data/news?q=slow" -> {
                Thread.sleep(8_800)
                "200 OK" to feed
            }
            "/v1/server-data/news?q=retry" -> when (++retries) {
                1 -> "503 Service Unavailable" to """{"error":"Upstream temporarily unavailable"}"""
                2 -> "200 OK" to feed
                else -> "200 OK" to "{"
            }
            else -> "404 Not Found" to "{}"
        }
        val bytes = body.toByteArray(Charsets.UTF_8)
        val output = socket.getOutputStream()
        output.write(("HTTP/1.1 $status\r\nContent-Type: application/json\r\n" +
            "Content-Length: ${bytes.size}\r\nConnection: close\r\n\r\n").toByteArray(Charsets.US_ASCII))
        output.write(bytes)
        output.flush()
    }

    override fun close() {
        listener.close()
        serveThread.join(10_000)
    }
}
