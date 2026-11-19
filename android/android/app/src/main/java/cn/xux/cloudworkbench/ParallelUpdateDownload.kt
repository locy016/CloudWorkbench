package cn.xux.cloudworkbench

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Response
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.coroutineContext
import kotlin.coroutines.resumeWithException

internal const val PARALLEL_UPDATE_MIN_SIZE = 16L * 1024 * 1024
internal const val UPDATE_STREAMS = 4
private const val UPDATE_CHUNK_SIZE = 1024L * 1024
private const val LEGACY_STREAMS = 8

internal class UpdateRangesUnavailable : IOException()
internal fun File.updateParts() = File(parentFile, "$name.parts")

private data class UpdateRange(val first: Long, val last: Long, val file: File, val offset: Long = 0)

// Keep legacy range boundaries so every previously saved byte can be migrated without holes.
private fun updateRanges(target: File, release: AppRelease): List<UpdateRange> {
    val span = (release.size + LEGACY_STREAMS - 1) / LEGACY_STREAMS
    return (0 until LEGACY_STREAMS).flatMap { index ->
        val end = minOf((index + 1) * span, release.size)
        generateSequence(index * span) { it + UPDATE_CHUNK_SIZE }.takeWhile { it < end }.map { first ->
            UpdateRange(first, minOf(first + UPDATE_CHUNK_SIZE, end) - 1,
                File(target.updateParts(), "chunk-$first"))
        }.toList()
    }
}

internal fun File.savedUpdateBytes(release: AppRelease): Long {
    val span = (release.size + LEGACY_STREAMS - 1) / LEGACY_STREAMS
    val prefix = length().coerceIn(0, release.size)
    return updateRanges(this, release).sumOf { range ->
        val legacyStart = range.first / span * span
        val legacy = File(updateParts(), (range.first / span).toString()).length()
        maxOf(prefix - range.first, range.file.length(), legacyStart + legacy - range.first)
            .coerceIn(0, range.last - range.first + 1)
    }
}

internal suspend fun downloadUpdateRanges(
    release: AppRelease, target: File, transfer: ConnectionTransfers.Operation,
    progress: (Long) -> Unit, request: (Int, Long, Long) -> Call
) = withContext(Dispatchers.IO) {
    val directory = target.updateParts()
    check(directory.isDirectory || directory.mkdirs()) { "无法创建下载分片目录" }
    val originalSize = target.length()
    val span = (release.size + LEGACY_STREAMS - 1) / LEGACY_STREAMS
    val ranges = updateRanges(target, release).filter { it.last >= originalSize }
    progress(target.savedUpdateBytes(release))
    for (range in ranges) {
        coroutineContext.ensureActive()
        val length = range.last - range.first + 1
        if (range.file.length() > length) check(range.file.delete()) { "无法清理损坏的下载分片" }
        val legacyStart = range.first / span * span
        val legacy = File(directory, (range.first / span).toString())
        val available = (legacyStart + legacy.length() - range.first).coerceIn(0, length)
        val saved = maxOf(range.file.length(), originalSize - range.first, 0L)
        if (available > saved) {
            RandomAccessFile(legacy, "r").use { input ->
                RandomAccessFile(range.file, "rw").use { output ->
                    input.seek(range.first - legacyStart + saved)
                    output.seek(saved)
                    val buffer = ByteArray(65536)
                    var remaining = available - saved
                    while (remaining > 0) {
                        coroutineContext.ensureActive()
                        val count = input.read(buffer, 0, minOf(remaining, buffer.size.toLong()).toInt())
                        check(count > 0) { "旧版安装包分片不完整" }
                        output.write(buffer, 0, count)
                        remaining -= count
                    }
                }
            }
        }
        if (range.last + 1 == minOf(legacyStart + span, release.size)) legacy.delete()
    }
    // All valid old bytes now have a corresponding new chunk or merged prefix.
    (0 until LEGACY_STREAMS).forEach { File(directory, it.toString()).delete() }
    val lock = Any()
    var total = target.savedUpdateBytes(release)
    progress(total)
    val pending = AtomicInteger()
    coroutineScope {
        List(UPDATE_STREAMS) { worker ->
            async {
                while (true) {
                    val index = pending.getAndIncrement()
                    if (index >= ranges.size) break
                    val range = ranges[index]
                    var failures = 0
                    while (true) {
                        coroutineContext.ensureActive()
                        val offset = maxOf(range.file.length(), originalSize - range.first, 0L)
                        if (range.first + offset > range.last) break
                        try {
                            receiveUpdateRange(request(worker, range.first + offset, range.last), release,
                                range.copy(offset = offset), transfer) { count ->
                                synchronized(lock) {
                                    total += count
                                    progress(total)
                                }
                            }
                            break
                        } catch (error: Exception) {
                            if (error is CancellationException || error is UpdateRangesUnavailable) throw error
                            val retryable = error is IOException ||
                                (error is GatewayException && (error.status == 408 || error.status == 429 || error.status >= 500))
                            if (!retryable || failures >= 3) throw error
                            delay(1000L shl failures)
                            failures++
                        }
                    }
                }
            }
        }.awaitAll()
    }
    FileOutputStream(target, true).buffered().use { output ->
        val buffer = ByteArray(65536)
        for (range in ranges) {
            coroutineContext.ensureActive()
            RandomAccessFile(range.file, "r").use { part ->
                part.seek(maxOf(0L, originalSize - range.first))
                while (true) {
                    coroutineContext.ensureActive()
                    val count = part.read(buffer)
                    if (count == -1) break
                    output.write(buffer, 0, count)
                }
            }
            output.flush()
            range.file.delete()
        }
    }
    check(target.length() == release.size) { "安装包分片合并后大小不符" }
    directory.deleteRecursively()
    Unit
}

private suspend fun receiveUpdateRange(
    call: Call, release: AppRelease, range: UpdateRange, transfer: ConnectionTransfers.Operation,
    received: (Int) -> Unit
) {
    val finished = CompletableDeferred<Unit>()
    try {
        suspendCancellableCoroutine<Unit> { result ->
            result.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    finished.complete(Unit)
                    result.resumeWithException(e)
                }

                override fun onResponse(call: Call, response: Response) {
                    val outcome = runCatching {
                        response.use {
                            if (it.code == 200) throw UpdateRangesUnavailable()
                            if (!it.isSuccessful) throw GatewayException(it.code, "安装包下载失败（HTTP " + it.code + "）")
                            val first = range.first + range.offset
                            val remaining = range.last - first + 1
                            val etag = "\"${release.sha256}\""
                            val contentRange = "bytes $first-${range.last}/${release.size}"
                            val body = it.body ?: throw IOException("安装包内容为空")
                            check(it.code == 206 && it.header("ETag") == etag &&
                                it.header("Content-Range") == contentRange &&
                                body.contentType()?.toString() == UPDATE_APK_TYPE && body.contentLength() == remaining) {
                                "服务器返回的安装包分片与发布清单不符"
                            }
                            transfer.receiving()
                            RandomAccessFile(range.file, "rw").use { output ->
                                output.seek(range.offset)
                                transfer.measure(body).byteStream().use { input ->
                                    val buffer = ByteArray(65536)
                                    var left = remaining
                                    while (left > 0 && result.isActive) {
                                        val count = input.read(buffer, 0, minOf(left, buffer.size.toLong()).toInt())
                                        if (count == -1) break
                                        output.write(buffer, 0, count)
                                        received(count)
                                        left -= count
                                    }
                                    if (left != 0L) throw IOException("安装包分片下载不完整")
                                }
                            }
                        }
                    }
                    finished.complete(Unit)
                    result.resumeWith(outcome)
                }
            })
        }
    } finally {
        // Wait for the canceled OkHttp callback before the caller can remove the part files.
        withContext(NonCancellable) { finished.await() }
    }
}
