package cn.xux.cloudworkbench

import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.util.UUID

internal data class BrowserBookmark(
    val id: String,
    val title: String,
    val url: String?,
    val shortcut: Boolean = false,
    val parentId: String? = null,
    val created: Long = 0,
    val opened: Long = 0
) {
    val isFolder get() = url == null
    val host get() = url?.let { runCatching { URI(it).host }.getOrNull() }.orEmpty()
}

internal enum class BookmarkOrder(val label: String) {
    Manual("手动顺序"), Newest("最新添加"), Oldest("最早添加"),
    Recent("最近打开"), Name("名称 A–Z"), ReverseName("名称 Z–A")
}

internal class BrowserBookmarks(private val prefs: SharedPreferences) {
    var entries by mutableStateOf(
        prefs.getString("browserBookmarks", null)?.let { saved ->
            runCatching {
                JSONArray(saved).objects().map {
                    BrowserBookmark(it.getString("id"), it.getString("title"),
                        it.optString("url").takeUnless { url -> url.isEmpty() || url == "null" },
                        it.optBoolean("shortcut"), it.optString("parentId").ifBlank { null },
                        it.optLong("created"), it.optLong("opened"))
                }
            }.getOrDefault(emptyList())
        } ?: listOf(BrowserBookmark("qiwuli", "栖物里", "https://qiwuli.com/", true))
    )
        private set
    var order by mutableStateOf(BookmarkOrder.entries.firstOrNull { it.name == prefs.getString("bookmarkOrder", null) } ?: BookmarkOrder.Manual)
        private set
    var compact by mutableStateOf(prefs.getBoolean("bookmarkCompact", false))
        private set
    var barVisible by mutableStateOf(prefs.getBoolean("bookmarkBarVisible", false))
        private set
    var lastFolder by mutableStateOf(prefs.getString("bookmarkLastFolder", null))
        private set

    fun rememberFolder(id: String?) {
        lastFolder = id
        prefs.edit().putString("bookmarkLastFolder", id).apply()
    }

    fun changeOrder(value: BookmarkOrder) {
        order = value
        prefs.edit().putString("bookmarkOrder", value.name).apply()
    }

    fun toggleCompact() {
        compact = !compact
        prefs.edit().putBoolean("bookmarkCompact", compact).apply()
    }

    fun toggleBar() {
        barVisible = !barVisible
        prefs.edit().putBoolean("bookmarkBarVisible", barVisible).apply()
    }

    fun find(id: String?) = entries.find { it.id == id }
    fun forUrl(url: String): BrowserBookmark? {
        val identity = urlIdentity(url) ?: return null
        return entries.find { it.url?.let(::urlIdentity) == identity }
    }

    private fun urlIdentity(url: String): URI? = runCatching {
        val parsed = URI(url)
        if (parsed.rawPath.isNullOrEmpty()) {
            val authority = parsed.rawAuthority ?: return null
            val endOfAuthority = parsed.scheme.length + 3 + authority.length
            URI(url.substring(0, endOfAuthority) + "/" + url.substring(endOfAuthority))
        } else parsed
    }.getOrNull()

    fun children(parent: String?) = entries.filter { it.parentId == parent }

    fun visible(folder: String?, query: String): List<BrowserBookmark> {
        val matches = if (query.isBlank()) children(folder) else entries.filter {
            it.title.contains(query, ignoreCase = true) || it.url?.contains(query, ignoreCase = true) == true
        }
        val sorted = when (order) {
            BookmarkOrder.Manual -> matches
            BookmarkOrder.Newest -> matches.sortedByDescending { it.created }
            BookmarkOrder.Oldest -> matches.sortedBy { it.created }
            BookmarkOrder.Recent -> matches.sortedByDescending { it.opened }
            BookmarkOrder.Name -> matches.sortedBy { it.title.lowercase() }
            BookmarkOrder.ReverseName -> matches.sortedByDescending { it.title.lowercase() }
        }
        return sorted.sortedByDescending { it.isFolder }
    }

    fun save(original: BrowserBookmark?, title: String, rawUrl: String, shortcut: Boolean, parentId: String?): BrowserBookmark {
        val url = browserDestination(rawUrl, allowSearch = false)
        require(forUrl(url)?.let { it.id == original?.id } != false) { "这个网址已在书签中" }
        val entry = BrowserBookmark(original?.id ?: UUID.randomUUID().toString(),
            title.trim().ifEmpty { URI(url).host }, url, shortcut, parentId,
            original?.created ?: System.currentTimeMillis(), original?.opened ?: 0)
        put(entry)
        return entry
    }

    fun saveFolder(original: BrowserBookmark?, name: String, parentId: String?) {
        val title = name.trim()
        require(title.isNotBlank()) { "请输入文件夹名称" }
        put(original?.copy(title = title) ?: BrowserBookmark(UUID.randomUUID().toString(), title, null,
            parentId = parentId, created = System.currentTimeMillis()))
    }

    fun canMove(entry: BrowserBookmark, parentId: String?): Boolean {
        var parent = parentId
        val visited = mutableSetOf(entry.id)
        while (parent != null) {
            if (!visited.add(parent)) return false
            val folder = find(parent)?.takeIf { it.isFolder } ?: return false
            parent = folder.parentId
        }
        return true
    }

    fun move(entry: BrowserBookmark, parentId: String?) {
        require(canMove(entry, parentId)) { "请选择其他文件夹" }
        if (entry.parentId != parentId) update(entries.filterNot { it.id == entry.id } + entry.copy(parentId = parentId))
    }

    fun shift(entry: BrowserBookmark, direction: Int) {
        val siblings = children(entry.parentId).filter { it.isFolder == entry.isFolder }
        val target = siblings.getOrNull(siblings.indexOf(entry) + direction) ?: return
        update(entries.map { when (it.id) { entry.id -> target; target.id -> entry; else -> it } })
    }

    fun recordOpen(entry: BrowserBookmark) = put(entry.copy(opened = System.currentTimeMillis()))

    fun remove(entry: BrowserBookmark) {
        val ids = mutableSetOf(entry.id)
        val pending = ArrayDeque<String>().apply { add(entry.id) }
        while (pending.isNotEmpty()) {
            children(pending.removeFirst()).forEach { if (ids.add(it.id)) pending.add(it.id) }
        }
        if (lastFolder in ids) rememberFolder(entry.parentId)
        update(entries.filterNot { it.id in ids })
    }

    private fun put(entry: BrowserBookmark) {
        update(if (find(entry.id) == null) entries + entry else entries.map { if (it.id == entry.id) entry else it })
    }

    private fun update(next: List<BrowserBookmark>) {
        val json = JSONArray()
        next.forEach {
            json.put(JSONObject().put("id", it.id).put("title", it.title).put("url", it.url)
                .put("shortcut", it.shortcut).put("parentId", it.parentId)
                .put("created", it.created).put("opened", it.opened))
        }
        prefs.edit().putString("browserBookmarks", json.toString()).apply()
        entries = next
    }
}
