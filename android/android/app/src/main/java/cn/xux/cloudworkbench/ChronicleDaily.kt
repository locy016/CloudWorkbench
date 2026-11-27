package cn.xux.cloudworkbench

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.util.Xml
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import java.time.Instant
import java.time.LocalDate
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit

internal enum class DailyTopic(val label: String) { World("地缘局势"), People("关键人物"), Models("大模型") }
internal enum class DailySource(val label: String, val topic: DailyTopic, val url: String) {
    Peace("联合国中文新闻", DailyTopic.World, "https://news.un.org/feed/subscribe/zh/news/topic/peace-and-security/feed/rss.xml"),
    Diplomacy("中国外交部", DailyTopic.People, "https://www.mfa.gov.cn/web/wjdt_674879/gjldrhd_674881/"),
    Downing("英国首相府", DailyTopic.People, "https://www.gov.uk/government/organisations/prime-ministers-office-10-downing-street.atom"),
    GoogleAI("Google AI 官方博客", DailyTopic.Models, "https://blog.google/technology/ai/rss/"),
    Qwen("Qwen", DailyTopic.Models, modelUrl("Qwen")),
    DeepSeek("DeepSeek", DailyTopic.Models, modelUrl("deepseek-ai")),
    Mistral("Mistral AI", DailyTopic.Models, modelUrl("mistralai"))
}

private fun modelUrl(org: String) =
    "https://huggingface.co/api/models?author=$org&sort=createdAt&direction=-1&limit=50&expand=createdAt&expand=pipeline_tag&expand=library_name&expand=tags"

internal data class DailyItem(val title: String, val date: String, val url: String, val summary: String = "")
internal data class DailyBatch(val checked: Long, val items: List<DailyItem>, val bundled: Boolean = false)

internal object ChronicleDaily {
    private const val JOB = 64379
    private const val DAY = 86_400_000L
    private val http = OkHttpClient.Builder().callTimeout(12, TimeUnit.SECONDS).build()
    private val lock = Mutex()
    val batches = MutableStateFlow<Map<DailySource, DailyBatch>>(emptyMap())
    val failures = MutableStateFlow<Set<DailySource>>(emptySet())
    val loading = MutableStateFlow(false)
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("chronicle_daily", Context.MODE_PRIVATE)

    @Synchronized fun load(ctx: Context) {
        if (batches.value.isNotEmpty()) return
        val seed = JSONObject(ctx.assets.open("chronicle-daily.json").bufferedReader().use { it.readText() })
        val prefs = prefs(ctx)
        batches.value = DailySource.entries.associateWith { source ->
            val saved = prefs.getString(source.name, null)?.let { runCatching { decode(JSONObject(it), false) }.getOrNull() }
            saved ?: decode(seed.getJSONObject(source.name), true)
        }
    }

    private fun decode(row: JSONObject, bundled: Boolean): DailyBatch {
        val rows = row.getJSONArray("items")
        val items = (0 until rows.length()).map {
            val item = rows.getJSONObject(it)
            DailyItem(item.getString("title"), item.getString("date"), item.getString("url"), item.optString("summary"))
        }
        return DailyBatch(row.getLong("checked"), items, bundled)
    }

    fun schedule(ctx: Context) {
        val scheduler = ctx.getSystemService(JobScheduler::class.java)
        if (scheduler.getPendingJob(JOB) == null) {
            scheduler.schedule(JobInfo.Builder(JOB, ComponentName(ctx, ChronicleDailyJob::class.java))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY).setPeriodic(DAY).setPersisted(true).build())
        }
    }

    suspend fun refresh(ctx: Context, force: Boolean = false) = lock.withLock {
        load(ctx)
        val today = LocalDate.now()
        val zone = java.time.ZoneId.systemDefault()
        val nowMillis = System.currentTimeMillis()
        val due = DailySource.entries.filter { source ->
            val batch = batches.value.getValue(source)
            force || batch.bundled || nowMillis - batch.checked >= 3_600_000L ||
                Instant.ofEpochMilli(batch.checked).atZone(zone).toLocalDate() != today
        }
        if (due.isEmpty()) return@withLock
        loading.value = true
        try {
            coroutineScope {
                val jobs = due.associateWith { source -> async(Dispatchers.IO) {
                    try { fetch(source) }
                    catch (e: CancellationException) { throw e }
                    catch (_: Exception) { null }
                } }
                for ((source, job) in jobs) {
                    val items = job.await()
                    if (items == null) {
                        failures.value += source
                        continue
                    }
                    val now = System.currentTimeMillis()
                    val rows = JSONArray()
                    items.forEach { rows.put(JSONObject().put("title", it.title).put("date", it.date).put("url", it.url).put("summary", it.summary)) }
                    prefs(ctx).edit().putString(source.name, JSONObject().put("checked", now).put("items", rows).toString()).apply()
                    batches.value += source to DailyBatch(now, items)
                    failures.value -= source
                }
            }
        } finally {
            loading.value = false
        }
    }

    private fun readUrl(url: String): String {
        val request = Request.Builder().url(url).header("User-Agent", "CloudWorkbench-Chronicle/1.0").build()
        return http.newCall(request).execute().use { response ->
            check(response.isSuccessful)
            val stream = checkNotNull(response.body).source()
            check(!stream.request(2_000_001L))
            stream.readUtf8()
        }
    }

    private val modelTasks = setOf("text-generation", "image-text-to-text", "any-to-any", "text-chat", "chat")
    private val modelId = Regex("[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+")

    private fun modelscope(org: String): List<DailyItem> {
        val body = readUrl("https://modelscope.cn/openapi/v1/models?owner=$org&page_size=50&sort=last_modified")
        val rows = JSONObject(body).getJSONObject("data").getJSONArray("models")
        return (0 until rows.length()).mapNotNull { i ->
            val row = rows.getJSONObject(i)
            val id = row.getString("id")
            val tasks = row.optJSONArray("tasks") ?: JSONArray()
            val kind = (0 until tasks.length()).map { tasks.getString(it) }.firstOrNull { it in modelTasks }
            // Some Mistral repositories have no task tag. Retain its named language-model families.
            val namedMistral = org == "mistralai" && id.substringAfter('/').startsWith("Mistral-", ignoreCase = true)
            if (!modelId.matches(id) || id.substringBefore('/') != org || (kind == null && !namedMistral)) return@mapNotNull null
            val metadata = listOfNotNull(
                when (kind) { "image-text-to-text" -> "图文输入"; "any-to-any" -> "多模态"; null -> null; else -> "文本生成" },
                row.optString("license").takeIf { it.isNotBlank() }?.let { "许可 $it" },
                row.optString("created_at").takeIf { it.length >= 10 }?.let { "建库于 " + it.take(10) }
            )
            DailyItem(id, Instant.parse(row.getString("last_modified")).toString().take(10),
                "https://modelscope.cn/models/$id", metadata.joinToString(" · "))
        }.distinctBy { it.url }.sortedByDescending { it.date }.take(8).also { check(it.isNotEmpty()) }
    }

    private fun fetch(source: DailySource): List<DailyItem> {
        val org = source.url.toHttpUrl().queryParameter("author")
        if (org != null) {
            try { return modelscope(org) } catch (_: Exception) { /* Try the same publisher on Hugging Face. */ }
        }
        val body = readUrl(source.url)
        val items = when {
            org != null -> {
                val rows = JSONArray(body)
                (0 until rows.length()).mapNotNull { i ->
                    val row = rows.getJSONObject(i)
                    val id = row.getString("id")
                    val kind = row.optString("pipeline_tag")
                    if (kind !in modelTasks || id.substringBefore('/') != org || !modelId.matches(id)) null
                    else {
                        val tags = row.optJSONArray("tags") ?: JSONArray()
                        val labels = (0 until tags.length()).map { tags.getString(it) }
                        val metadata = listOfNotNull(
                            when (kind) { "image-text-to-text" -> "图文输入"; "any-to-any" -> "多模态"; else -> "文本生成" },
                            row.optString("library_name").takeIf { it.isNotBlank() },
                            labels.firstOrNull { it.startsWith("license:") }?.let { "许可 " + it.substringAfter(':') }
                        )
                        DailyItem(id, Instant.parse(row.getString("createdAt")).toString().take(10), "https://huggingface.co/$id", metadata.joinToString(" · "))
                    }
                }
            }
            source == DailySource.Diplomacy -> diplomacy.findAll(body).map { match ->
                DailyItem(android.text.Html.fromHtml(match.groupValues[2], 0).toString().trim(), match.groupValues[3],
                    source.url.toHttpUrl().resolve(match.groupValues[1]).toString())
            }.toList()
            source == DailySource.GoogleAI -> syndication(body).filter { modelNames.containsMatchIn(it.title) }
            else -> syndication(body)
        }
        return items.filter { it.title.isNotBlank() && it.url.toHttpUrlOrNull()?.isHttps == true }
            .distinctBy { it.url }.sortedByDescending { it.date }.take(8).also { check(it.isNotEmpty()) }
    }

    private val diplomacy = Regex("""<a\s+href="(\./\d{6}/t\d{8}_\d+\.shtml)"[^>]*>([^<]+?)[（(](\d{4}-\d{2}-\d{2})[）)]\s*</a>""")
    private val modelNames = Regex("Gemini|Gemma|Veo|Imagen|language model", RegexOption.IGNORE_CASE)

    private fun syndication(body: String): List<DailyItem> {
        val xml = Xml.newPullParser()
        xml.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
        xml.setInput(body.reader())
        val rows = mutableListOf<DailyItem>()
        var depth = -1
        var title = ""
        var url = ""
        var date = ""
        var summary = ""
        while (xml.next() != XmlPullParser.END_DOCUMENT) {
            if (xml.eventType == XmlPullParser.START_TAG) {
                if (depth < 0 && xml.name in setOf("item", "entry")) {
                    depth = xml.depth
                    title = ""; url = ""; date = ""; summary = ""
                } else if (xml.depth == depth + 1 && depth > 0) {
                    when (xml.name) {
                        "title" -> title = xml.nextText().trim()
                        "description", "summary" -> summary = android.text.Html.fromHtml(xml.nextText(), 0)
                            .toString().replace("\uFFFC", "").replace(Regex("\\s+"), " ").trim().take(240)
                        "pubDate", "updated", "published" -> {
                            val value = xml.nextText().trim()
                            date = runCatching { Instant.parse(value) }.recoverCatching {
                                ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant()
                            }.getOrNull()?.toString()?.take(10) ?: date
                        }
                        "link" -> {
                            val href = xml.getAttributeValue(null, "href")
                            if (href == null) url = xml.nextText().trim()
                            else if (xml.getAttributeValue(null, "rel") in listOf(null, "alternate")) url = href
                        }
                    }
                }
            } else if (xml.eventType == XmlPullParser.END_TAG && xml.depth == depth) {
                if (date.isNotBlank() && title.isNotBlank() && url.isNotBlank()) rows += DailyItem(title, date, url, summary)
                depth = -1
            }
        }
        return rows
    }
}

class ChronicleDailyJob : JobService() {
    private var task: Job? = null
    override fun onStartJob(params: JobParameters): Boolean {
        task = CoroutineScope(Dispatchers.Main).launch {
            ChronicleDaily.refresh(applicationContext)
            jobFinished(params, false)
        }
        return true
    }
    override fun onStopJob(params: JobParameters): Boolean { task?.cancel(); return true }
}
