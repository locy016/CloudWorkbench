package cn.xux.cloudworkbench

import android.content.SharedPreferences
import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

internal data class DraftRecord(
    val text: String = "",
    val images: List<DraftImage> = emptyList(),
    val browser: BrowserSubmission? = null,
    val attempt: MessageAttempt? = null,
    val updatedAt: Long = 0,
    val projectId: String? = null,
    val title: String? = null,
    val projectName: String? = null
) {
    val uploads get() = listOfNotNull(browser?.image) + images.map { it.upload }
    val isEmpty get() = text.isEmpty() && images.isEmpty() && browser == null && attempt == null

    fun messageText(): String? = text.trim().ifEmpty { if (uploads.isNotEmpty()) "请分析附图。" else return null }
}

internal class BrowserSubmission(val context: JSONObject, val image: MessageImage?)

internal data class MessageAttempt(
    val requestId: String,
    val text: String,
    val imageIds: List<UUID>,
    val draftAtSend: String,
    val projectId: String,
    val title: String
) {
    fun matches(text: String?, imageIds: List<UUID>): Boolean = this.text == text && this.imageIds == imageIds
}

internal class DraftStore(private val prefs: SharedPreferences, private val directory: File) {
    fun restore(): Map<String, DraftRecord> = prefs.all.mapNotNull { (key, value) ->
        if (!key.startsWith(PREFIX) || value !is String) return@mapNotNull null
        runCatching { key.removePrefix(PREFIX) to decode(JSONObject(value)) }.getOrNull()
    }.toMap()

    fun save(records: Map<String, DraftRecord>, receivedThread: String?) {
        val edit = prefs.edit().putString("last-received-thread", receivedThread)
        for ((key, record) in records) {
            if (record == DraftRecord()) edit.remove(PREFIX + key)
            else edit.putString(PREFIX + key, encode(record).toString())
        }
        edit.apply()
    }

    fun lastReceivedThread(): String? = prefs.getString("last-received-thread", null)

    // Save the current preferences generation without overwriting edits made during a send.
    fun flush(): Boolean = prefs.edit().commit()

    fun stageImage(mime: String, bytes: ByteArray): MessageImage {
        require(bytes.size in 8..MAX_IMAGE_BYTES) { "图片过大或无法读取，请重新添加" }
        val id = UUID.randomUUID()
        val image = MessageImage(mime, imageFile(id), id)
        val target = AtomicFile(image.file)
        val output = target.startWrite()
        try {
            output.write(bytes)
            output.fd.sync()
            target.finishWrite(output)
            check(image.file.length() == bytes.size.toLong()) { "无法保存草稿图片" }
        } catch (e: Exception) {
            target.failWrite(output)
            target.delete()
            throw e
        }
        return image
    }

    fun unusedFiles(records: Collection<DraftRecord>): List<File> {
        val retained = records.flatMap { it.uploads }.mapTo(hashSetOf()) { it.file }
        return directory.listFiles().orEmpty().filter { it !in retained }
    }

    private fun imageFile(id: UUID) = File(directory, "$id.img")

    private fun encode(record: DraftRecord): JSONObject = JSONObject().put("text", record.text)
        .put("updatedAt", record.updatedAt).put("projectId", record.projectId)
        .put("title", record.title).put("projectName", record.projectName)
        .put("images", JSONArray(record.images.map { encodeImage(it.upload).put("name", it.name) }))
        .put("browser", record.browser?.let { JSONObject().put("context", it.context).put("image", it.image?.let(::encodeImage)) })
        .put("attempt", record.attempt?.let {
            JSONObject().put("requestId", it.requestId).put("text", it.text)
                .put("imageIds", JSONArray(it.imageIds.map(UUID::toString))).put("draftAtSend", it.draftAtSend)
                .put("projectId", it.projectId).put("title", it.title)
        })

    private fun encodeImage(image: MessageImage): JSONObject = JSONObject().put("id", image.localId.toString())
        .put("mime", image.mime).put("uploadedId", image.uploadedId)

    private fun decode(record: JSONObject): DraftRecord = DraftRecord(
        text = record.optString("text"),
        images = record.optJSONArray("images")?.objects().orEmpty().map { DraftImage(it.getString("name"), decodeImage(it)) },
        browser = record.optJSONObject("browser")?.let {
            BrowserSubmission(it.getJSONObject("context"), it.optJSONObject("image")?.let(::decodeImage))
        },
        attempt = record.optJSONObject("attempt")?.let {
            val ids = it.getJSONArray("imageIds")
            MessageAttempt(it.getString("requestId"), it.getString("text"),
                List(ids.length()) { index -> UUID.fromString(ids.getString(index)) },
                it.getString("draftAtSend"), it.getString("projectId"), it.getString("title"))
        },
        updatedAt = record.optLong("updatedAt"),
        projectId = record.optString("projectId").ifBlank { null },
        title = record.optString("title").ifBlank { null },
        projectName = record.optString("projectName").ifBlank { null }
    )

    private fun decodeImage(value: JSONObject): MessageImage {
        val id = UUID.fromString(value.getString("id"))
        return MessageImage(value.getString("mime"), imageFile(id), id, value.optString("uploadedId").ifEmpty { null })
    }

    private companion object {
        const val PREFIX = "draft:"
    }
}
