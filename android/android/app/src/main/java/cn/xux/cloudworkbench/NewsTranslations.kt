package cn.xux.cloudworkbench

import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONObject

internal const val NEWS_PREFERRED_LANGUAGE = "newsPreferredLanguage"
internal val newsLanguages = linkedMapOf("zh" to "简体中文", "ja" to "日本語", "ko" to "한국어",
    "fr" to "Français", "de" to "Deutsch", "es" to "Español")
internal data class NewsTranslation(val title: String, val summary: String)

private data class TranslationKey(val target: String, val title: String, val summary: String)

internal class NewsTranslations(private val prefs: SharedPreferences, private val connection: () -> WorkbenchConnection?) {
    private val cache = mutableStateMapOf<TranslationKey, NewsTranslation>()
    private val order = ArrayDeque<TranslationKey>()
    var preferredLanguage by mutableStateOf(prefs.getString(NEWS_PREFERRED_LANGUAGE, null)
        ?.takeIf { it in newsLanguages } ?: "zh")
        private set

    fun setLanguage(target: String) {
        require(target in newsLanguages)
        preferredLanguage = target
        prefs.edit().putString(NEWS_PREFERRED_LANGUAGE, target).apply()
    }

    private fun NewsArticle.translationKey(target: String) =
        TranslationKey(target, title, summary.orEmpty())

    fun get(article: NewsArticle, target: String): NewsTranslation? =
        if (article.language == "en") cache[article.translationKey(target)] else null

    suspend fun translate(articles: List<NewsArticle>, target: String) {
        val transport = connection() ?: return
        val missing = articles.asSequence().filter { it.language == "en" }.map { it.translationKey(target) }
            .distinct().filterNot(cache::containsKey).toList()
        for (batch in missing.chunked(3)) {
            if (transport !== connection()) return
            val text = JSONArray(batch.map { JSONObject().put("title", it.title).put("summary", it.summary) })
            val body = JSONObject().put("source", "en").put("target", target).put("articles", text)
            val response = requestBatch(transport, body)
            if (transport !== connection()) return
            check(response.getString("target") == target)
            val translated = response.getJSONArray("articles").objects().map { item ->
                NewsTranslation(item.getString("title"), item.getString("summary")).also {
                    require(it.title.isNotBlank() && it.title.length <= 2048 && it.summary.length <= 2048)
                }
            }
            check(translated.size == batch.size)
            for ((key, value) in batch.zip(translated)) {
                if (key !in cache) order.addLast(key)
                cache[key] = value
                if (order.size > 384) cache.remove(order.removeFirst())
            }
        }
    }

    private suspend fun requestBatch(transport: WorkbenchConnection, body: JSONObject): JSONObject {
        // A batch canceled by a source/language change can still be finishing on the server.
        val pause = listOf(500L, 1_000L, 2_000L, 3_500L, 5_000L, 6_500L)
        for (wait in pause) {
            try {
                return transport.request("/v1/server-data/news/translate-batch", body)
            } catch (e: GatewayException) {
                if (e.status != 429) throw e
                delay(wait)
            }
        }
        return transport.request("/v1/server-data/news/translate-batch", body)
    }
}
