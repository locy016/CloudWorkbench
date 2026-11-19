package cn.xux.cloudworkbench

import okhttp3.OkHttpClient
import okhttp3.Credentials
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit

private val connectLine = Regex("CONNECT ((?:[a-zA-Z0-9_.-]+|\\[[0-9a-fA-F:]+\\]):[0-9]{1,5}) HTTP/1\\.[01]")

/** Gives WebView its normal HTTP proxy socket while carrying the bytes over authenticated WSS. */
internal class BrowserTunnel(
    private val client: OkHttpClient,
    private val request: () -> Request
) {
    private val server = ServerSocket(0, 24, InetAddress.getByName("127.0.0.1"))
    val port: Int get() = server.localPort
    val realm = UUID.randomUUID().toString()
    val password = UUID.randomUUID().toString()
    val proxyAuthorization: String = Credentials.basic("workbench", password)
    private val peers = mutableSetOf<Peer>()
    private val slots = Semaphore(24)
    private val workers = Executors.newCachedThreadPool { job ->
        Thread(job, "browser-tunnel").apply { isDaemon = true }
    }
    private var closed = false
    private var paused = false

    init {
        workers.execute {
            try {
                while (true) {
                    val socket = server.accept()
                    synchronized(this) {
                        if (closed || paused || !slots.tryAcquire()) {
                            socket.close()
                        } else {
                            val peer = Peer(socket)
                            peers.add(peer)
                            workers.execute { peer.start() }
                        }
                    }
                }
            } catch (_: java.io.IOException) {
                close()
            }
        }
    }

    @Synchronized fun pause(value: Boolean) {
        paused = value
        if (paused) peers.toList().forEach { it.close() }
    }

    @Synchronized fun close() {
        if (closed) return
        closed = true
        runCatching { server.close() }
        peers.toList().forEach { it.close() }
        workers.shutdownNow()
    }

    private inner class Peer(private val socket: Socket) : WebSocketListener() {
        private var webSocket: WebSocket? = null
        private val upgradeReady = CountDownLatch(1)
        private var openingRequest: ByteString? = null
        @Volatile private var ended = false

        fun start() {
            var closingNormally = false
            try {
                socket.tcpNoDelay = true
                val input = socket.getInputStream().buffered()
                val bytes = ByteArray(16 * 1024)
                // Chromium makes speculative TCP connections. Open WSS only after data arrives.
                socket.soTimeout = 15_000
                val headers = readHeaders(input) ?: return
                val fields = headers.toString(Charsets.ISO_8859_1).split("\r\n")
                val credentials = fields.drop(1).filter { it.substringBefore(':').equals("Proxy-Authorization", true) }
                val supplied = credentials.singleOrNull()?.substringAfter(':')?.trim().orEmpty()
                if (!MessageDigest.isEqual(supplied.toByteArray(), proxyAuthorization.toByteArray())) {
                    socket.getOutputStream().write(
                        ("HTTP/1.1 407 Proxy Authentication Required\r\n" +
                            "Proxy-Authenticate: Basic realm=\"$realm\"\r\n" +
                            "Connection: close\r\nContent-Length: 0\r\n\r\n").toByteArray()
                    )
                    return
                }
                socket.soTimeout = 0
                val clean = fields.filterNot { it.substringBefore(':').equals("Proxy-Authorization", true) }
                val opening = clean.joinToString("\r\n").toByteArray(Charsets.ISO_8859_1).toByteString()
                val earlyTarget = connectLine.matchEntire(fields.first())?.groupValues?.get(1)?.takeIf { it.length <= 270 }
                val upgrade = request().let { base ->
                    if (earlyTarget == null) base else base.newBuilder().header("X-Workbench-Connect", earlyTarget).build()
                }
                val ws = synchronized(this@BrowserTunnel) {
                    if (ended) return
                    if (earlyTarget != null) openingRequest = opening
                    client.newWebSocket(upgrade, this).also { webSocket = it }
                }
                if (earlyTarget == null && !ws.send(opening)) return
                if (earlyTarget != null && (!upgradeReady.await(20, TimeUnit.SECONDS) || ended)) return
                var count = input.read(bytes)
                while (count > 0 && !ended) {
                    var wait = 0
                    while (ws.queueSize() >= 128 * 1024L && !ended) {
                        // Keep OkHttp's send queue bounded when the server or link stalls.
                        if (wait++ >= 3000) return
                        Thread.sleep(10)
                    }
                    if (!ws.send(bytes.toByteString(0, count))) return
                    count = input.read(bytes)
                }
                closingNormally = ws.close(1000, null)
            } catch (_: Exception) {
            } finally {
                if (!closingNormally) close()
            }
        }

        override fun onOpen(webSocket: WebSocket, response: Response) {
            val opening = synchronized(this@BrowserTunnel) { openingRequest.also { openingRequest = null } }
            if (opening != null && response.header("X-Workbench-Early-Connect") != "1" && !webSocket.send(opening)) close()
            upgradeReady.countDown()
        }

        private fun readHeaders(input: java.io.InputStream): ByteArray? {
            val bytes = java.io.ByteArrayOutputStream()
            var tail = 0
            repeat(32 * 1024) {
                val next = input.read()
                if (next == -1) return null
                bytes.write(next)
                tail = (tail shl 8) or next
                if (tail == 0x0d0a0d0a) return bytes.toByteArray()
            }
            return null
        }

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
            try {
                socket.getOutputStream().write(bytes.toByteArray())
            } catch (_: Exception) {
                close()
            }
        }

        override fun onMessage(webSocket: WebSocket, text: String) { close() }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(1000, null)
            close(cancel = false)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { close() }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            response?.close()
            close()
        }

        fun close(cancel: Boolean = true) = synchronized(this@BrowserTunnel) {
            if (ended) return@synchronized
            ended = true
            upgradeReady.countDown()
            if (cancel) webSocket?.cancel()
            runCatching { socket.close() }
            peers.remove(this)
            slots.release()
        }
    }
}
