package cn.xux.cloudworkbench

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.net.InetAddress
import java.net.Proxy
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.util.Random
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

@RunWith(AndroidJUnit4::class)
class DownloadUpdateTest {
    @Test fun releasesDownloadConnectionsAfterSuccessAndCancellation() = runBlocking {
        for (cancel in listOf(false, true)) {
            UpdateFixture().use { fixture ->
                fixture.server { if (cancel) Reply(limit = 65_555, pause = true) else Reply() }.use { server ->
                    val closed = AtomicInteger()
                    val opened = AtomicInteger()
                    val originalClosed = CountDownLatch(1)
                    val primary = object : MobileGatewaySession {
                        override val origin = server.url.removeSuffix("/apk")
                        override fun bearer() = "fixture-original"
                        override fun close() { originalClosed.countDown() }
                        override suspend fun forkForDownload(): MobileGatewaySession {
                            opened.incrementAndGet()
                            return object : MobileGatewaySession {
                                override val origin = server.url.removeSuffix("/apk")
                                override fun bearer() = "fixture-download"
                                override fun close() { closed.incrementAndGet() }
                            }
                        }
                    }
                    val connection = Connection(openGateway = { primary })
                    connection.connect(Credentials("fixture"))
                    try {
                        val received = CompletableDeferred<Unit>()
                        val job = launch { connection.downloadUpdate(fixture.release, fixture.target) {
                            if (it > 0) received.complete(Unit)
                        } }
                        if (cancel) {
                            withTimeout(10_000) { received.await() }
                            job.cancelAndJoin()
                            assertTrue(fixture.target.savedUpdateBytes(fixture.release) > 0)
                        } else {
                            withTimeout(20_000) { job.join() }
                            fixture.assertComplete()
                        }
                        assertEquals(UPDATE_STREAMS - 1, opened.get())
                        assertEquals(opened.get(), closed.get())
                        assertEquals(1L, originalClosed.count)
                    } finally { connection.disconnect() }
                    assertTrue(originalClosed.await(3, TimeUnit.SECONDS))
                }
            }
        }
    }

    @Test fun cancellationWhileOpeningRoutesClosesAlreadyOpenedRoutes() = runBlocking {
        UpdateFixture().use { fixture ->
            fixture.server().use { server ->
                val opened = CompletableDeferred<Unit>()
                val never = CompletableDeferred<Unit>()
                val attempts = AtomicInteger()
                val closed = AtomicInteger()
                val primary = object : MobileGatewaySession {
                    override val origin = server.url.removeSuffix("/apk")
                    override fun bearer() = "fixture-original"
                    override fun close() {}
                    override suspend fun forkForDownload(): MobileGatewaySession {
                        if (attempts.incrementAndGet() != 1) never.await()
                        opened.complete(Unit)
                        return object : MobileGatewaySession {
                            override val origin = server.url.removeSuffix("/apk")
                            override fun bearer() = "fixture-download"
                            override fun close() { closed.incrementAndGet() }
                        }
                    }
                }
                val connection = Connection(openGateway = { primary })
                connection.connect(Credentials("fixture"))
                try {
                    val job = launch { connection.downloadUpdate(fixture.release, fixture.target) {} }
                    withTimeout(5000) { opened.await() }
                    job.cancelAndJoin()
                    assertEquals(1, closed.get())
                } finally { connection.disconnect() }
            }
        }
    }

    @Test fun resumesAPartiallyMergedApkWithoutNetwork() = runBlocking {
        UpdateFixture().use { fixture ->
            val span = (fixture.payload.size + 7) / 8
            val prefix = (MIB + 73).toInt()
            fixture.target.writeBytes(fixture.payload.copyOfRange(0, prefix))
            assertTrue(fixture.target.updateParts().mkdirs())
            for (segment in 0 until 8) {
                var first = segment * span
                val end = minOf((segment + 1) * span, fixture.payload.size)
                while (first < end) {
                    val last = minOf(first + MIB.toInt(), end)
                    if (last > prefix) File(fixture.target.updateParts(), "chunk-$first")
                        .writeBytes(fixture.payload.copyOfRange(first, last))
                    first = last
                }
            }
            assertEquals(fixture.release.size, fixture.target.savedUpdateBytes(fixture.release))
            fixture.server { error("A complete saved download must not make a network request") }.use { server ->
                withTimeout(10_000) { fixture.download(server) }
                assertTrue(server.requests.isEmpty())
                fixture.assertComplete()
            }
        }
    }

    @Test fun failedWorkerKeepsOtherCompletedChunksAndResumes() = runBlocking {
        UpdateFixture().use { fixture ->
            fixture.server { range -> if (range.first == 0L) Reply(status = 503) else Reply() }.use { server ->
                val failure = runCatching { withTimeout(20_000) { fixture.download(server) } }.exceptionOrNull()
                assertTrue(failure is GatewayException)
                assertEquals(4, server.requests.count { it.first == 0L })
            }
            val saved = fixture.target.savedUpdateBytes(fixture.release)
            assertEquals(fixture.release.size - MIB, saved)
            fixture.server().use { server ->
                fixture.download(server)
                assertEquals(MIB, server.requests.sumOf { it.last - it.first + 1 })
                fixture.assertComplete()
            }
        }
    }

    @Test fun retriesInterruptedBodyFromTheLastWrittenByte() = runBlocking {
        UpdateFixture().use { fixture ->
            val cut = 65_555
            fixture.server { range ->
                if (range.first == 0L) Reply(limit = cut) else Reply()
            }.use { server ->
                withTimeout(30_000) { fixture.download(server) }
                val firstChunk = server.requests.filter { it.last == MIB - 1 }
                assertEquals(listOf(0L..(MIB - 1), cut.toLong()..(MIB - 1)), firstChunk)
                assertTrue(server.requests.all { it.last - it.first + 1 <= MIB })
                fixture.assertComplete()
            }
        }
    }

    @Test fun resumesCancelledDownloadWithoutFetchingSavedBytesAgain() = runBlocking {
        UpdateFixture().use { fixture ->
            val hasBytes = CompletableDeferred<Unit>()
            fixture.server { Reply(limit = 65_555, pause = true) }.use { server ->
                val job = launch {
                    fixture.download(server) { if (it > 0) hasBytes.complete(Unit) }
                }
                try {
                    withTimeout(10_000) { hasBytes.await() }
                } finally {
                    job.cancelAndJoin()
                }
            }
            val saved = fixture.target.savedUpdateBytes(fixture.release)
            assertTrue("Cancellation must retain a partial APK", saved in 1L until fixture.release.size)
            fixture.server().use { server ->
                val progress = mutableListOf<Long>()
                withTimeout(30_000) { fixture.download(server) { progress.add(it) } }
                assertEquals(saved, progress.first())
                assertEquals(fixture.release.size, progress.last())
                assertEquals(fixture.release.size - saved, server.requests.sumOf { it.last - it.first + 1 })
                fixture.assertComplete()
            }
        }
    }

    @Test fun migratesAllEightLegacySegmentsAndTheMergedPrefix() = runBlocking {
        UpdateFixture().use { fixture ->
            val parts = fixture.target.updateParts()
            assertTrue(parts.mkdirs())
            val span = (fixture.payload.size + 7) / 8
            val lengths = List(8) { 1_100_007 + it * 103 }
            lengths.forEachIndexed { index, length ->
                File(parts, index.toString()).writeBytes(
                    fixture.payload.copyOfRange(index * span, index * span + length)
                )
            }
            fixture.target.writeBytes(fixture.payload.copyOfRange(0, 65_000))
            val saved = lengths.sum().toLong()
            assertEquals(saved, fixture.target.savedUpdateBytes(fixture.release))
            fixture.server().use { server ->
                withTimeout(30_000) { fixture.download(server) }
                assertEquals(fixture.release.size - saved, server.requests.sumOf { it.last - it.first + 1 })
                server.requests.forEach { range ->
                    val segment = (range.first / span).toInt()
                    assertTrue(range.first >= segment.toLong() * span + lengths[segment])
                }
                fixture.assertComplete()
            }
        }
    }

    @Test fun rejectsMismatchedRangeMetadataWithoutSavingBody() = runBlocking {
        listOf(Reply(etag = "\"wrong-etag\""), Reply(contentRange = "bytes 0-0/1")).forEach { badReply ->
            UpdateFixture().use { fixture ->
                fixture.server { badReply }.use { server ->
                    val failure = runCatching { withTimeout(10_000) { fixture.download(server) } }.exceptionOrNull()
                    assertNotNull("Mismatched metadata must fail the download", failure)
                    assertTrue("Expected metadata rejection, got ${failure?.javaClass?.simpleName}",
                        failure is IllegalStateException)
                    assertEquals(0L, fixture.target.savedUpdateBytes(fixture.release))
                    assertTrue(server.requests.isNotEmpty())
                }
            }
        }
    }
}

private const val MIB = 1024L * 1024

private data class Reply(
    val status: Int = 206,
    val limit: Int? = null,
    val pause: Boolean = false,
    val etag: String? = null,
    val contentRange: String? = null
)

private class UpdateFixture : Closeable {
    val payload = ByteArray((PARALLEL_UPDATE_MIN_SIZE + 57).toInt()).apply { Random(41).nextBytes(this) }
    val release = AppRelease(1, "fixture", updateTestSha256(payload), "0".repeat(64), payload.size.toLong(), "")
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val directory = File.createTempFile("update-ranges-", ".test", context.cacheDir).apply {
        check(delete() && mkdir())
    }
    val target = File(directory, "fixture.apk")
    private val client = OkHttpClient.Builder().proxy(Proxy.NO_PROXY).retryOnConnectionFailure(false)
        .readTimeout(15, TimeUnit.SECONDS).build()

    fun server(reply: (LongRange) -> Reply = { Reply() }) = UpdateRangeServer(payload, release.sha256, reply)

    suspend fun download(server: UpdateRangeServer, progress: (Long) -> Unit = {}) {
        ConnectionTransfers().track(TransferKind.AppUpdateDownload) { transfer ->
            downloadUpdateRanges(release, target, transfer, progress) { _, first, last ->
                val request = Request.Builder().url(server.url)
                    .header("Accept-Encoding", "identity")
                    .header("Range", "bytes=$first-$last")
                    .header("If-Range", "\"${release.sha256}\"")
                    .build()
                client.newCall(request)
            }
        }
    }

    fun assertComplete() {
        assertEquals(release.size, target.length())
        assertEquals(release.sha256, updateTestSha256(target.readBytes()))
        assertFalse(target.updateParts().exists())
    }

    override fun close() {
        client.dispatcher.cancelAll()
        client.connectionPool.evictAll()
        client.dispatcher.executorService.shutdown()
        directory.deleteRecursively()
    }
}

private class UpdateRangeServer(
    private val payload: ByteArray,
    private val sha256: String,
    private val reply: (LongRange) -> Reply
) : Closeable {
    private val listener = ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))
    private val pool = Executors.newCachedThreadPool()
    private val closeSignal = CountDownLatch(1)
    private val sockets = ConcurrentLinkedQueue<Socket>()
    val requests = ConcurrentLinkedQueue<LongRange>()
    val url = "http://127.0.0.1:${listener.localPort}/apk"

    private val acceptThread = thread(name = "update-range-fixture", isDaemon = true) {
        try {
            while (!listener.isClosed) {
                val socket = listener.accept()
                sockets.add(socket)
                pool.execute {
                    try {
                        socket.use(::serve)
                    } catch (_: IOException) {
                        // Canceled client calls can close the socket between headers and body.
                    } finally {
                        sockets.remove(socket)
                    }
                }
            }
        } catch (_: IOException) {
            // Closing the listener unblocks accept().
        }
    }

    private fun serve(socket: Socket) {
        socket.soTimeout = 5_000
        val reader = socket.getInputStream().bufferedReader(Charsets.US_ASCII)
        // Cancellation may close a newly accepted socket before sending any headers.
        val requestLine = reader.readLine() ?: return
        if (requestLine.startsWith("GET /v1/status ")) {
            socket.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: 2\r\nConnection: close\r\n\r\n{}".toByteArray())
            return
        }
        check(requestLine.startsWith("GET /apk ") || requestLine.startsWith("GET /v1/app-update/$sha256/apk "))
        val headers = mutableMapOf<String, String>()
        while (true) {
            val line = reader.readLine() ?: return
            if (line.isEmpty()) break
            headers[line.substringBefore(':').lowercase()] = line.substringAfter(':').trim()
        }
        val match = checkNotNull(Regex("bytes=(\\d+)-(\\d+)").matchEntire(headers["range"].orEmpty()))
        val first = match.groupValues[1].toLong()
        val last = match.groupValues[2].toLong()
        check(first in 0..last && last < payload.size)
        val etag = "\"$sha256\""
        check(headers["if-range"] == etag)
        val range = first..last
        requests.add(range)
        val response = reply(range)
        val length = (last - first + 1).toInt()
        val contentRange = response.contentRange ?: "bytes $first-$last/${payload.size}"
        val output = socket.getOutputStream()
        if (response.status != 206) {
            output.write(("HTTP/1.1 ${response.status} Failed\r\nContent-Length: 0\r\nConnection: close\r\n\r\n").toByteArray())
            return
        }
        output.write(("HTTP/1.1 206 Partial Content\r\n" +
            "Content-Type: $UPDATE_APK_TYPE\r\nContent-Length: $length\r\n" +
            "Content-Range: $contentRange\r\nETag: ${response.etag ?: etag}\r\n" +
            "Connection: close\r\n\r\n").toByteArray(Charsets.US_ASCII))
        output.write(payload, first.toInt(), minOf(length, response.limit ?: length))
        output.flush()
        if (response.pause) closeSignal.await(20, TimeUnit.SECONDS)
    }

    override fun close() {
        listener.close()
        acceptThread.join()
        closeSignal.countDown()
        sockets.forEach { it.close() }
        pool.shutdown()
        check(pool.awaitTermination(10, TimeUnit.SECONDS))
    }
}

private fun updateTestSha256(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 0xff) }
