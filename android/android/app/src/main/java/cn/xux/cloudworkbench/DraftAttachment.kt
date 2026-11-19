package cn.xux.cloudworkbench

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.util.UUID
import kotlin.math.roundToInt

internal const val MAX_MESSAGE_IMAGES = 4
private const val MAX_IMAGE_EDGE = 2048
private const val MAX_IMAGE_BYTES = 5 * 1024 * 1024

internal class MessageImage(val mime: String, val bytes: ByteArray) {
    val localId = UUID.randomUUID()
    var uploadedId: String? = null
}

internal class DraftImage(val name: String, val thumbnail: Bitmap, val upload: MessageImage)

internal fun ContentResolver.readDraftImage(uri: Uri, name: String? = null): DraftImage {
    val bitmap = decodeDraftImage(uri)
    try {
        val output = ByteArrayOutputStream()
        // Keep screenshots sharp when their lossless representation fits the gateway limit.
        val png = getType(uri) == "image/png" || name == null && uri.displayName(this).endsWith(".png", true)
        if (png) check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
        val mime = if (png && output.size() <= MAX_IMAGE_BYTES) "image/png" else {
            output.reset()
            val opaque = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
            try {
                Canvas(opaque).apply { drawColor(Color.WHITE); drawBitmap(bitmap, 0f, 0f, null) }
                check(opaque.compress(Bitmap.CompressFormat.JPEG, 90, output))
            } finally {
                opaque.recycle()
            }
            "image/jpeg"
        }
        require(output.size() in 8..MAX_IMAGE_BYTES) { "图片过大，请选择较小的图片" }
        val scale = (320f / maxOf(bitmap.width, bitmap.height)).coerceAtMost(1f)
        val preview = Bitmap.createScaledBitmap(
            bitmap, (bitmap.width * scale).roundToInt().coerceAtLeast(1),
            (bitmap.height * scale).roundToInt().coerceAtLeast(1), true
        )
        val thumbnail = if (preview === bitmap) checkNotNull(bitmap.copy(Bitmap.Config.ARGB_8888, false)) else preview
        return DraftImage(name ?: uri.displayName(this).ifBlank { "图片" }, thumbnail, MessageImage(mime, output.toByteArray()))
    } finally {
        bitmap.recycle()
    }
}

private fun ContentResolver.decodeDraftImage(uri: Uri): Bitmap {
    if (Build.VERSION.SDK_INT >= 28) {
        return ImageDecoder.decodeBitmap(ImageDecoder.createSource(this, uri)) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            val scale = (MAX_IMAGE_EDGE.toFloat() / maxOf(info.size.width, info.size.height)).coerceAtMost(1f)
            decoder.setTargetSize(
                (info.size.width * scale).roundToInt().coerceAtLeast(1),
                (info.size.height * scale).roundToInt().coerceAtLeast(1)
            )
        }
    }
    val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
    require(options.outWidth > 0 && options.outHeight > 0) { "无法读取图片" }
    options.inJustDecodeBounds = false
    options.inSampleSize = 1
    while (maxOf(options.outWidth, options.outHeight) / options.inSampleSize > MAX_IMAGE_EDGE) options.inSampleSize *= 2
    val bitmap = openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) } ?: error("无法读取图片")
    val orientation = try {
        openInputStream(uri)?.use { ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, 1) }
    } catch (_: java.io.IOException) { null }
    val transform = Matrix().apply {
        when (orientation) {
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> setScale(-1f, 1f)
            ExifInterface.ORIENTATION_ROTATE_180 -> setRotate(180f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> setScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> { setRotate(90f); postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_ROTATE_90 -> setRotate(90f)
            ExifInterface.ORIENTATION_TRANSVERSE -> { setRotate(270f); postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_ROTATE_270 -> setRotate(270f)
        }
    }
    return if (transform.isIdentity) bitmap else Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, transform, true)
        .also { if (it !== bitmap) bitmap.recycle() }
}

internal fun ContentResolver.readDraftText(uri: Uri): String {
    val data = openInputStream(uri)?.use { input ->
        val limit = MAX_DRAFT_LENGTH * 4 + 3
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(4096)
        while (output.size() <= limit) {
            val count = input.read(buffer)
            if (count == -1) break
            output.write(buffer, 0, count)
        }
        require(output.size() <= limit) { "文本文件过长，请截取需要的部分" }
        output.toByteArray()
    } ?: error("无法读取文本文件")
    val text = try {
        Charsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(data)).toString().removePrefix("\uFEFF")
    } catch (_: java.nio.charset.CharacterCodingException) {
        throw IllegalArgumentException("请选择 UTF-8 文本文件")
    }
    require(text.none { it.isISOControl() && it !in "\n\r\t" }) { "请选择 UTF-8 文本文件" }
    require(text.isNotBlank()) { "文本文件为空" }
    return text
}

private fun Uri.displayName(resolver: ContentResolver): String = runCatching {
    resolver.query(this, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
        if (cursor.moveToFirst()) cursor.getString(0) else null
    }
}.getOrNull().orEmpty().take(120)
