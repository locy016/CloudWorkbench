package cn.xux.cloudworkbench

import android.os.SystemClock
import com.jcraft.jsch.ChannelExec
import com.jcraft.jsch.JSchException
import com.jcraft.jsch.Session
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException

internal interface MobileGatewaySession {
    val origin: String
    fun bearer(): String
    fun invalidate(token: String) {}
    suspend fun forkForDownload(): MobileGatewaySession? = null
    fun close()
}

internal suspend fun openMobileSshGateway(privateKey: String): MobileGatewaySession {
    var opened: MobileGatewaySession? = null
    try {
        return withContext(Dispatchers.IO) {
            val (jsch, session) = workbenchSsh(privateKey)
            try {
                session.setServerAliveInterval(15_000)
                session.setServerAliveCountMax(3)
                session.connect(20_000)
                val port = session.setPortForwardingL("127.0.0.1", 0, "127.0.0.1", 8766)
                MobileSshGateway(session, port, privateKey) { jsch.removeAllIdentity() }.also { opened = it }
            } catch (error: Throwable) {
                try { session.disconnect() } finally { jsch.removeAllIdentity() }
                throw error
            }
        }
    } catch (error: Throwable) {
        opened?.close()
        throw error
    }
}

private class MobileSshGateway(
    private val session: Session,
    private val port: Int,
    private var privateKey: String,
    private val clearIdentities: () -> Unit
) : MobileGatewaySession {
    override val origin = "http://127.0.0.1:$port"
    private var token = ""
    private var expiresAt = 0L

    override suspend fun forkForDownload(): MobileGatewaySession {
        val key = synchronized(this) {
            if (!session.isConnected || privateKey.isBlank()) throw IOException("SSH 连接已断开")
            privateKey
        }
        return openMobileSshGateway(key)
    }

    @Synchronized override fun bearer(): String {
        if (!session.isConnected) throw IOException("SSH 连接已断开")
        if (expiresAt - System.currentTimeMillis() > 5_000L) return token
        val issued = try {
            fetchBearer()
        } catch (error: JSchException) {
            throw IOException("无法验证服务器 SSH 会话", error)
        }
        token = issued.first
        expiresAt = issued.second
        return token
    }

    @Synchronized override fun invalidate(token: String) {
        if (this.token == token) expiresAt = 0L
    }

    private fun fetchBearer(): Pair<String, Long> {
        val channel = session.openChannel("exec") as ChannelExec
        try {
            channel.setCommand("workbench-ssh-session-v1")
            val output = object : ByteArrayOutputStream() {
                @Synchronized override fun write(bytes: ByteArray, offset: Int, count: Int) {
                    if (size() + count > 4096) throw IOException("SSH 会话响应无效")
                    super.write(bytes, offset, count)
                }
            }
            channel.setOutputStream(output)
            channel.connect(8_000)
            val deadline = SystemClock.elapsedRealtime() + 8_000
            while (!channel.isClosed) {
                if (SystemClock.elapsedRealtime() >= deadline) throw IOException("SSH 会话验证超时")
                try {
                    Thread.sleep(20)
                } catch (error: InterruptedException) {
                    Thread.currentThread().interrupt()
                    throw IOException("SSH 会话验证已中断", error)
                }
            }
            if (channel.exitStatus != 0) throw IOException("服务器未能签发 SSH 会话")
            val json = try {
                JSONObject(output.toString(Charsets.UTF_8.name()))
            } catch (_: Exception) {
                throw IOException("SSH 会话响应无效")
            }
            val issued = json.optString("token")
            val expiry = json.optLong("expiresAt")
            if (!issued.startsWith("sshm1.") || issued.length !in 7..2048 ||
                issued.any { it !in '!'..'~' } || expiry <= System.currentTimeMillis()) {
                throw IOException("SSH 会话响应无效")
            }
            return issued to expiry
        } finally {
            channel.disconnect()
        }
    }

    override fun close() {
        synchronized(this) { privateKey = "" }
        try { session.disconnect() } finally {
            synchronized(this) {
                token = ""
                expiresAt = 0L
                clearIdentities()
            }
        }
    }
}
