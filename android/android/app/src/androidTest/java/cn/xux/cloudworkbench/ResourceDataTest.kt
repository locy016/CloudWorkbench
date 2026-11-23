package cn.xux.cloudworkbench

import android.content.Intent
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.core.content.FileProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ResourceDataTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun opensTheHeadlineGroupAndLoadsItsFirstSourceFromTheCatalog() {
        val instrument = InstrumentationRegistry.getInstrumentation()
        val context = instrument.targetContext
        val connection = object : WorkbenchConnection {
            override val connected = true
            override fun disconnect() = Unit
            override suspend fun connect(c: Credentials) = Unit
            override suspend fun request(path: String, body: JSONObject?, delete: Boolean): JSONObject {
                if (path == "/v1/server-data/resources") return JSONObject("""{"sources":[
                    {"id":"trade","title":"经贸动态","publisher":"贸易源","category":"trade","filters":[]},
                    {"id":"headline","title":"国际要闻","publisher":"要闻源","category":"headlines","format":"rss","filters":[]}]}""")
                assertEquals("/v1/server-data/resources/headline", path)
                return JSONObject("""{"id":"headline","fetchedAt":"2026-11-23T04:00:00Z","updatedAt":null,
                    "columns":[{"key":"headline","label":"标题"}],"rows":[{"headline":"新闻摘要验收"}]}""")
            }
        }
        val activity = instrument.startActivitySync(Intent(context, ComponentActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as ComponentActivity
        try {
            val data = ResourceData { connection }
            compose.runOnUiThread { activity.setContent { WorkbenchTheme { ResourceDataPage(data, connected = true, visible = true) } } }
            compose.waitUntil(10000) { runCatching {
                compose.onAllNodesWithTag("resourceResult").fetchSemanticsNodes().isNotEmpty()
            }.getOrDefault(false) }
            compose.onNodeWithTag("resourceCategory-headlines").assertIsSelected()
            compose.onNodeWithTag("resourcePicker").assertTextContains("国际要闻", substring = true)
            compose.onNodeWithTag("onDemandResources").performScrollToNode(hasTestTag("resourceHeadline"))
            compose.onNodeWithTag("resourceHeadline").assertTextEquals("新闻摘要验收")
        } finally {
            compose.runOnUiThread { activity.finish() }
        }
    }

    @Test fun exportQuotesCsvCellsAndNeutralizesFormulas() {
        val input = JSONObject("""{"publisher":"NOAA","sourceUrl":"https://source.example","fetchedAt":"2026-11-23T04:00:00Z",
          "columns":[{"key":"name","label":"名称"},{"key":"amount","label":"量值"}],
          "rows":[{"name":"=SUM(1,2)","amount":"-0.10"},{"name":"a\"b","amount":"@text"}]}""")
        val csv = resourceCsv(input)
        assertTrue(csv.startsWith("\uFEFF\"来源\""))
        assertTrue(csv.contains("\"'=SUM(1,2)\",\"-0.10\"\r\n"))
        assertTrue(csv.contains("\"a\"\"b\",\"'@text\"\r\n"))
    }

    @Test fun resourceCatalogRendersAKeywordFieldAndExportsTheRequestedRows() {
        val instrument = InstrumentationRegistry.getInstrumentation()
        val context = instrument.targetContext
        val keyword = "国际 贸易 & feed"
        val connection = object : WorkbenchConnection {
            override val connected = true
            override fun disconnect() = Unit
            override suspend fun connect(c: Credentials) = Unit
            override suspend fun request(path: String, body: JSONObject?, delete: Boolean): JSONObject {
                if (path == "/v1/server-data/resources") return JSONObject("""{"sources":[
                    {"id":"fixture","title":"测试资源","publisher":"测试站点","filters":[
                    {"key":"keyword","label":"任意关键词","type":"text","maxLength":80}]}]}""")
                val url = Uri.parse("https://localhost$path")
                assertEquals("/v1/server-data/resources/fixture", url.path)
                assertEquals(setOf("keyword"), url.queryParameterNames)
                assertEquals(keyword, url.getQueryParameter("keyword"))
                return JSONObject("""{"id":"fixture","fetchedAt":"2026-11-23T04:00:00Z","updatedAt":null,
                    "columns":[{"key":"title","label":"名称"}],"rows":[{"title":"查询结果"}],
                    "filters":{}}""").apply { getJSONObject("filters").put("keyword", keyword) }
            }
        }
        val data = ResourceData { connection }
        val activity = instrument.startActivitySync(Intent(context, ComponentActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as ComponentActivity
        val file = File(context.filesDir, "app-updates/resource-fixture-test.json")
        try {
            compose.runOnUiThread { activity.setContent { WorkbenchTheme { ResourceDataPage(data, connected = true, visible = true) } } }
            compose.waitUntil(10000) { runCatching {
                compose.onAllNodesWithTag("resourceInput-keyword").fetchSemanticsNodes().isNotEmpty()
            }.getOrDefault(false) }
            compose.onNodeWithTag("resourceInput-keyword").performTextReplacement(keyword)
            compose.onNodeWithTag("fetchResource").performClick()
            compose.waitUntil(10000) { compose.onAllNodesWithTag("resourceResult").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("exportResource").assertIsEnabled()
            compose.runOnIdle { data.prepareExport(checkNotNull(data.result)) }
            file.parentFile?.mkdirs()
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.updates", file)
            assertTrue(runBlocking { data.saveExport(context, uri) })
            val exported = JSONObject(file.readText())
            assertEquals(keyword, exported.getJSONObject("filters").getString("keyword"))
            assertEquals("查询结果", exported.getJSONArray("rows").getJSONObject(0).getString("title"))
        } finally {
            compose.runOnUiThread { activity.finish() }
            file.delete()
        }
    }
}
