package cn.xux.cloudworkbench

import org.json.JSONObject

private const val PAGE_SIZE = 40

internal fun threadPagePath(before: String? = null): String =
    "/v1/threads?limit=$PAGE_SIZE" + (before?.let { "&before=$it" } ?: "")

internal fun historyPagePath(id: String, before: String): String =
    "/v1/threads/$id/history?limit=$PAGE_SIZE&before=$before"

internal fun updatesPath(id: String, cursor: String? = null, limit: Int = PAGE_SIZE, requestId: String? = null): String =
    "/v1/threads/$id/updates?limit=$limit" + (cursor?.let { "&cursor=$it" } ?: "") +
        (requestId?.let { "&requestId=$it" } ?: "")

internal data class SubmissionReceipt(val requestId: String, val state: String) {
    companion object {
        fun parse(body: JSONObject): SubmissionReceipt? = body.optJSONObject("receipt")?.let {
            SubmissionReceipt(it.getString("requestId"), it.getString("state"))
        }
    }
}

internal data class ConversationPage(
    val entries: List<JSONObject>,
    val nextBefore: String?
) {
    companion object {
        fun parse(body: JSONObject, key: String): ConversationPage = ConversationPage(
            body.getJSONArray(key).objects(),
            if (body.getBoolean("hasMore")) body.getString("nextBefore") else null
        )
    }
}

internal class ConversationUpdates(body: JSONObject) {
    val thread = body.getJSONObject("thread")
    val approvals = body.getJSONArray("approvals").objects()
    val messages = body.getJSONArray("messages").objects()
    val reset = body.getBoolean("reset")
    val cursor = body.getString("cursor")
    val hasMore = body.getBoolean("hasMore")
    val historyBefore = if (reset && !body.isNull("historyBefore")) body.getString("historyBefore") else null
    val receipt = SubmissionReceipt.parse(body)
}

internal fun mergeMessages(existing: List<JSONObject>, newer: List<JSONObject>): List<JSONObject> {
    if (newer.isEmpty()) return existing
    val byId = (existing + newer).associateBy { it.getString("id") }
    return byId.values.sortedBy { it.optLong("created") }
}
