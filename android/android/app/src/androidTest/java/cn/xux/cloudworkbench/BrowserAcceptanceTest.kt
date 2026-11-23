package cn.xux.cloudworkbench

import android.content.Intent
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.atomic.AtomicReference
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BrowserAcceptanceTest {
    @get:Rule val compose = createEmptyComposeRule()
    private lateinit var activity: MainActivity

    @Before fun launchActivity() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        activity = instrumentation.startActivitySync(
            Intent(instrumentation.targetContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ) as MainActivity
        compose.runOnUiThread {
            activity.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    @After fun finishActivity() {
        if (::activity.isInitialized) compose.runOnUiThread { activity.finish() }
    }
    private fun web(v: View): WebView? { if (v is WebView) return v; if (v is ViewGroup) for (i in 0 until v.childCount) web(v.getChildAt(i))?.let { return it }; return null }
    private fun browser() {
        activity.runOnUiThread { activity.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
        compose.waitUntil(45000) { runCatching { compose.onAllNodesWithText("已连接").fetchSemanticsNodes().isNotEmpty() }.getOrDefault(false) }
        compose.openWorkbenchTab("浏览")
        compose.onNodeWithTag("browserAddress").performTextReplacement("https://example.com")
        compose.waitUntil(20000) { compose.onAllNodes(hasTestTag("browserGo") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("browserGo").performClick()
        activity.runOnUiThread {
            (activity.getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager).hideSoftInputFromWindow(activity.window.decorView.windowToken, 0)
            activity.currentFocus?.clearFocus()
        }
        val body = AtomicReference("")
        compose.waitUntil(45000) {
            activity.runOnUiThread { web(activity.window.decorView)?.evaluateJavascript("document.body?.innerText?.length > 20 ? document.title : ''") { body.set(it) } }
            body.get().contains("Example Domain")
        }
    }
    @Test fun browserTextToServerCodex() {
        browser()
        compose.onNodeWithTag("browserText").performClick()
        compose.onNodeWithTag("browserInstruction").performTextReplacement("只分析提供的网页资料，不调用工具。请用中文概括用途，最后回复 BROWSER_TEXT_OK。")
        compose.onNodeWithTag("browserSend").performClick()
        compose.waitUntil(180000) { runCatching { compose.onAllNodes(hasTestTag("message-assistant") and hasAnyDescendant(hasText("BROWSER_TEXT_OK", substring = true)), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }.getOrDefault(false) }
    }

    @Test fun serverDataShowsEcbReferenceRatesWithoutAWebView() {
        awaitVisibleConnection()
        compose.openWorkbenchTab("浏览")
        compose.onNodeWithTag("openServerData").assertIsDisplayed().performClick()
        compose.onNodeWithTag("openEcbRates").performClick()
        compose.onNodeWithTag("serverDataPage").assertIsDisplayed()
        compose.onNodeWithTag("browserView").assertDoesNotExist()
        compose.runOnIdle { org.junit.Assert.assertNull(web(activity.window.decorView)) }
        compose.waitUntil(25000) { compose.onAllNodesWithText("USD").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("referenceRatesSource").assertTextEquals("来源：欧洲央行（ECB）")
        compose.onNodeWithTag("referenceRatesDate").assert(
            SemanticsMatcher("shows the source publication date") { node ->
                node.config[androidx.compose.ui.semantics.SemanticsProperties.Text].any {
                    it.text.matches(Regex("数据日期：\\d{4}-\\d{2}-\\d{2}"))
                }
            }
        )
        compose.waitUntil(25000) {
            compose.onAllNodes(hasTestTag("referenceRatesRefresh") and isEnabled()).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("referenceRatesRefresh").performClick()
        compose.waitUntil(25000) {
            compose.onAllNodes(hasTestTag("referenceRatesRefresh") and isEnabled()).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("referenceRatesStatus").assertDoesNotExist()
        compose.onNodeWithContentDescription("返回浏览").performClick()
        compose.onNodeWithTag("browserStart").assertIsDisplayed()
    }

    @Test fun serverDataFiltersWorldBankTradeByCountryAndYear() {
        awaitVisibleConnection()
        compose.openWorkbenchTab("浏览")
        compose.onNodeWithTag("openServerData").performClick()
        compose.onNodeWithTag("openWorldBankTrade").performClick()
        compose.onNodeWithTag("tradeSource").assertTextContains("世界银行", substring = true)
        compose.onNodeWithTag("browserView").assertDoesNotExist()
        compose.runOnIdle { org.junit.Assert.assertNull(web(activity.window.decorView)) }

        fun query(code: String, year: Int) {
            compose.waitUntil(30000) {
                compose.onAllNodes(hasTestTag("tradeCountry") and isEnabled()).fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithTag("tradeCountry").performClick()
            compose.onNodeWithTag("tradeCountrySearch").performTextReplacement(code)
            compose.onNodeWithTag("tradeOption-$code").performClick()
            compose.waitUntil(25000) {
                compose.onAllNodes(hasTestTag("tradeYear") and isEnabled()).fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithTag("tradeYear").performTextReplacement(year.toString())
            compose.runOnUiThread {
                val keyboard = activity.getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
                keyboard.hideSoftInputFromWindow(activity.window.decorView.windowToken, 0)
                activity.currentFocus?.clearFocus()
            }
            compose.onNodeWithTag("tradeQuery").performClick()
            compose.waitUntil(30000) {
                compose.onAllNodes(hasTestTag("tradeDataYear") and hasText("统计年度：$year 年"))
                    .fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithTag("tradeCountry").assertTextContains(code, substring = true)
            compose.onNodeWithTag("tradeUpdatedAt").assertTextContains("数据集更新：", substring = true)
        }
        query("CHN", 2023)
        compose.onNodeWithTag("tradePercent").assertTextContains("%", substring = true)
        compose.onNodeWithTag("tradeExports").assertTextContains("现价美元", substring = true)
        compose.onNodeWithTag("tradeImports").assertTextContains("现价美元", substring = true)
        query("USA", 2024)
        compose.onNodeWithTag("tradePercent").assertTextContains("%", substring = true)
        query("JPN", java.time.LocalDate.now().year)
        compose.onNodeWithTag("tradeNoData").assertIsDisplayed()
        compose.onNodeWithContentDescription("返回浏览").performClick()
        compose.onNodeWithTag("browserStart").assertIsDisplayed()
    }

    @Test fun downloadsAnOnDemandResourceByKeywordAndSavesTheActualResult() {
        awaitVisibleConnection()
        compose.openWorkbenchTab("浏览")
        compose.onNodeWithTag("openServerData").performClick()
        awaitResourceCatalog()
        compose.onNodeWithTag("resourceCategory-other").performClick()
        compose.onNodeWithTag("resourceInput-period").performClick()
        compose.onNodeWithText("过去 24 小时").performClick()
        compose.onNodeWithTag("fetchResource").performClick()
        compose.waitUntil(25000) { compose.onAllNodesWithTag("resourceResult").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("来源更新时间：", substring = true).assertExists()
        compose.onNodeWithTag("resourcePicker").performClick()
        compose.onNodeWithText("公开开源项目搜索").performClick()
        compose.onNodeWithTag("resourceInput-keyword").performTextReplacement("nodejs in:name")
        compose.onNodeWithTag("fetchResource").performClick()
        compose.runOnUiThread {
            val keyboard = activity.getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
            keyboard.hideSoftInputFromWindow(activity.window.decorView.windowToken, 0)
            activity.currentFocus?.clearFocus()
            org.junit.Assert.assertNull(web(activity.window.decorView))
        }
        compose.waitUntil(25000) { compose.onAllNodesWithTag("resourceResult").fetchSemanticsNodes().isNotEmpty() }
        saveResource("exportResource", "application/json", "resource-acceptance.json") { file ->
            val saved = org.json.JSONObject(file.readText())
            org.junit.Assert.assertEquals("nodejs in:name", saved.getJSONObject("filters").getString("keyword"))
            java.time.Instant.parse(saved.getString("fetchedAt"))
            org.junit.Assert.assertTrue(saved.getJSONArray("rows").length() > 0)
        }
    }

    @Test fun previewsOfficialFeedsAndExportsFilteredPortObservationsAsCsv() {
        awaitVisibleConnection()
        compose.openWorkbenchTab("浏览")
        compose.onNodeWithTag("openServerData").performClick()
        awaitResourceCatalog()
        fun source(name: String) {
            compose.onNodeWithTag("resourcePicker").performScrollTo().performClick()
            compose.onNodeWithText(name).performClick()
        }
        fun fetch() {
            compose.runOnUiThread {
                val keyboard = activity.getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
                keyboard.hideSoftInputFromWindow(activity.window.decorView.windowToken, 0)
                activity.currentFocus?.clearFocus()
            }
            compose.onNodeWithTag("fetchResource").performScrollTo().performClick()
            compose.waitUntil(25000) { compose.onAllNodesWithTag("resourceResult").fetchSemanticsNodes().isNotEmpty() }
        }
        compose.onNodeWithTag("resourceCategory-trade").performClick()
        source("WTO 国际贸易新闻")
        compose.onNodeWithTag("toggleResourceFilters").performClick()
        compose.onNodeWithTag("resourceKeyword").performTextReplacement("WTO")
        fetch()
        compose.onNodeWithTag("resourceCounts").assertTextContains("匹配", substring = true)
        compose.onNodeWithText("WTO news · RSS", substring = true).assertExists()
        compose.onNodeWithTag("resourceCategory-logistics").performClick()
        source("海运气象警报")
        fetch()
        compose.onNodeWithText("· ATOM", substring = true).assertExists()
        source("港口潮位实测（近 72 小时）")
        val today = java.time.LocalDate.now(java.time.ZoneOffset.UTC).toString()
        compose.onNodeWithTag("resourceFrom").performTextReplacement(today)
        compose.onNodeWithTag("resourceTo").performTextReplacement(today)
        fetch()
        compose.onNodeWithTag("resourceCounts").assertTextContains("匹配", substring = true)
        compose.runOnIdle { org.junit.Assert.assertNull(web(activity.window.decorView)) }
        saveResource("exportResourceCsv", "text/csv", "resource-acceptance.csv") { file ->
            val lines = file.readLines()
            org.junit.Assert.assertTrue(lines[0].contains("来源网址") && lines[0].contains("观测时间（UTC）"))
            org.junit.Assert.assertTrue(lines.size > 1)
            org.junit.Assert.assertTrue(lines.drop(1).all { line ->
                line.contains("NOAA CO-OPS") && line.split("\",\"").getOrNull(3)?.startsWith("${today}T") == true
            })
        }
    }

    @Test fun singleSourceDirectoryKeepsHeadlinesCurrentAffairsAndTradeEasyToFind() {
        awaitVisibleConnection()
        compose.openWorkbenchTab("浏览")
        compose.onNodeWithTag("openServerData").performClick()
        awaitResourceCatalog()
        compose.onNodeWithTag("openSingleSource").assertIsSelected()
        compose.onNodeWithTag("resourceCategory-headlines").assertIsSelected()
        compose.onNodeWithTag("resourcePicker").assertTextContains("联合国日内瓦要闻", substring = true)
        compose.waitUntil(25000) { compose.onAllNodesWithTag("resourceResult").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("onDemandResources").performScrollToNode(hasTestTag("resourceHeadline"))
        compose.onAllNodesWithTag("resourceHeadline").onFirst().assertIsDisplayed()
        compose.onNodeWithTag("onDemandResources").performScrollToNode(hasTestTag("resourceCategory-current"))
        compose.onNodeWithTag("resourceCategory-current").performClick()
        compose.onNodeWithTag("resourcePicker").assertTextContains("欧盟委员会时事通报", substring = true)
        compose.onNodeWithTag("fetchResource").performClick()
        compose.waitUntil(25000) { compose.onAllNodesWithTag("resourceResult").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("resourceCategory-trade").performClick()
        compose.onNodeWithTag("resourcePicker").assertTextContains("WTO 国际贸易新闻", substring = true)
        compose.onNodeWithTag("openEcbRates").assertIsNotSelected()
    }

    @Test fun opensAggregatedNewsAndFiltersBySourceAndKeyword() {
        awaitVisibleConnection()
        compose.openWorkbenchTab("浏览")
        compose.onNodeWithTag("openServerData").performClick()
        compose.onNodeWithTag("openServerNews").assertIsSelected()
        fun awaitSearch() {
            compose.waitUntil(30000) {
                compose.onAllNodes(hasTestTag("searchNews") and isEnabled()).fetchSemanticsNodes().isNotEmpty() &&
                    compose.onAllNodesWithTag("newsCount").fetchSemanticsNodes().isNotEmpty()
            }
        }
        awaitSearch()
        compose.onNodeWithTag("pickNewsSource").assertTextContains("全部来源")
        compose.runOnIdle { org.junit.Assert.assertNull(web(activity.window.decorView)) }
        compose.onNodeWithTag("aggregatedNews").performScrollToNode(hasTestTag("newsHeadline"))
        compose.onAllNodesWithTag("newsHeadline").onFirst().assertIsDisplayed()
        compose.onAllNodesWithTag("newsHeadlineMetadata").onFirst().assertTextContains("UTC", substring = true)
        compose.onAllNodesWithTag("newsHeadlineLink").onFirst().assertTextContains("复制原文地址")
        compose.onNodeWithTag("aggregatedNews").performScrollToNode(hasTestTag("pickNewsSource"))
        compose.onNodeWithTag("pickNewsSource").performClick()
        compose.onNodeWithTag("newsSource-wto-trade-news").performClick()
        awaitSearch()
        compose.onNodeWithTag("pickNewsSource").assertTextContains("WTO 国际贸易新闻")
        compose.onNodeWithTag("aggregatedNews").performScrollToNode(hasTestTag("newsHeadline"))
        val headline = compose.onAllNodesWithTag("newsHeadline").onFirst().fetchSemanticsNode()
            .config[androidx.compose.ui.semantics.SemanticsProperties.Text].single().text
        val keyword = checkNotNull(Regex("""[\p{L}\p{N}]{4,}""").find(headline)).value
        compose.onAllNodesWithTag("newsHeadlineMetadata").onFirst().assertTextContains("WTO news", substring = true)
        compose.onNodeWithTag("aggregatedNews").performScrollToNode(hasTestTag("newsKeyword"))
        compose.onNodeWithTag("newsKeyword").performTextReplacement(keyword)
        compose.onNodeWithTag("searchNews").performClick()
        awaitSearch()
        compose.onNodeWithTag("aggregatedNews").performScrollToNode(hasTestTag("newsHeadline"))
        compose.onAllNodesWithTag("newsHeadlineMetadata").onFirst().assertTextContains("WTO news", substring = true)
        compose.onNodeWithText(headline).assertExists()
    }

    @Test fun newsCategoriesAndPublicationRangeCombineWithSourceAndKeyword() {
        awaitVisibleConnection()
        compose.openWorkbenchTab("浏览")
        compose.onNodeWithTag("openServerData").performClick()
        val list = compose.onNodeWithTag("aggregatedNews")
        fun awaitNews() = compose.waitUntil(30000) {
            compose.onAllNodes(hasTestTag("searchNews") and isEnabled()).fetchSemanticsNodes().isNotEmpty() &&
                compose.onAllNodesWithTag("newsCount").fetchSemanticsNodes().isNotEmpty()
        }
        fun metadata() = compose.onAllNodesWithTag("newsHeadlineMetadata").onFirst().fetchSemanticsNode()
            .config[androidx.compose.ui.semantics.SemanticsProperties.Text].single().text

        awaitNews()
        for ((category, publisher) in listOf("headlines" to "UN Geneva", "current" to "European Commission")) {
            list.performScrollToNode(hasTestTag("newsCategory-$category"))
            compose.onNodeWithTag("newsCategory-$category").performClick().assertIsSelected()
            awaitNews()
            list.performScrollToNode(hasTestTag("newsHeadline"))
            org.junit.Assert.assertTrue(metadata().contains(publisher))
        }
        list.performScrollToNode(hasTestTag("newsCategory-trade"))
        compose.onNodeWithTag("newsCategory-trade").performClick().assertIsSelected()
        awaitNews()
        compose.onNodeWithTag("pickNewsSource").performClick()
        compose.onNodeWithTag("newsSource-un-geneva-headlines").assertDoesNotExist()
        compose.onNodeWithTag("newsSource-eu-trade-policy").assertExists()
        compose.onNodeWithTag("newsSource-uk-trade-remedies").assertExists()
        compose.onNodeWithTag("newsSource-ustr-trade-news").performClick()
        awaitNews()
        list.performScrollToNode(hasTestTag("newsHeadline"))
        val headline = compose.onAllNodesWithTag("newsHeadline").onFirst().fetchSemanticsNode()
            .config[androidx.compose.ui.semantics.SemanticsProperties.Text].single().text
        val keyword = checkNotNull(Regex("""[\p{L}\p{N}]{4,}""").find(headline)).value
        val date = checkNotNull(Regex("""发布 (\d{4}-\d{2}-\d{2}) """).find(metadata())).groupValues[1]

        list.performScrollToNode(hasTestTag("newsKeyword"))
        compose.onNodeWithTag("newsKeyword").performTextReplacement(keyword)
        compose.onNodeWithTag("pickNewsDates").performClick()
        compose.onNodeWithTag("newsFrom").performTextReplacement("2026-02-30")
        compose.onNodeWithTag("applyNewsDates").assertIsNotEnabled()
        compose.onNodeWithTag("newsFrom").performTextReplacement(date)
        compose.onNodeWithTag("newsTo").performTextReplacement(date)
        compose.onNodeWithTag("applyNewsDates").assertIsEnabled().performClick()
        awaitNews()
        compose.onNodeWithTag("newsDates").assertTextContains("$date 至 $date（UTC）")
        compose.onNodeWithTag("pickNewsSource").assertTextContains("美国贸易代表办公室动态")
        list.performScrollToNode(hasTestTag("newsHeadline"))
        org.junit.Assert.assertTrue(metadata().contains("USTR") && metadata().contains("发布 $date"))
        compose.onNodeWithText(headline).assertExists()

        list.performScrollToNode(hasTestTag("pickNewsDates"))
        compose.onNodeWithTag("pickNewsDates").performClick()
        compose.onNodeWithTag("newsFrom").performTextReplacement("2000-01-01")
        compose.onNodeWithTag("newsTo").performTextReplacement("2000-01-01")
        compose.onNodeWithTag("applyNewsDates").performClick()
        compose.waitUntil(30000) { compose.onAllNodesWithText("没有符合条件的资讯").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("pickNewsDates").performClick()
        compose.onNodeWithTag("clearNewsDates").performClick()
        awaitNews()
        compose.onNodeWithTag("newsDates").assertDoesNotExist()
        list.performScrollToNode(hasTestTag("newsHeadline"))
        compose.onNodeWithText(headline).assertExists()
    }

    @Test fun translatesNewsOnTheServerAndPreservesTheOriginal() {
        awaitVisibleConnection()
        compose.openWorkbenchTab("浏览")
        compose.onNodeWithTag("openServerData").performClick()
        compose.waitUntil(30000) { compose.onAllNodesWithTag("newsCount").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("aggregatedNews").performScrollToNode(hasTestTag("translateNewsArticle"))
        compose.onAllNodesWithTag("translateNewsArticle").onFirst().performClick()
        val title = compose.onNodeWithTag("newsTranslationTitle")
        val original = title.fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsProperties.Text].single().text
        fun translate() {
            compose.onNodeWithTag("requestNewsTranslation").performClick()
            compose.waitUntil(20000) {
                compose.onAllNodesWithTag("toggleNewsOriginal").fetchSemanticsNodes().isNotEmpty()
            }
        }
        compose.onNodeWithTag("newsLanguage").performClick()
        compose.onNodeWithTag("newsLanguage-zh").performClick()
        translate()
        val chinese = title.fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsProperties.Text].single().text
        org.junit.Assert.assertTrue(chinese != original && chinese.any { Character.UnicodeScript.of(it.code) == Character.UnicodeScript.HAN })
        compose.onNodeWithTag("toggleNewsOriginal").performClick()
        title.assertTextEquals(original)

        compose.onNodeWithTag("newsLanguage").performClick()
        compose.onNodeWithTag("newsLanguage-ja").performClick()
        translate()
        val japanese = title.fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsProperties.Text].single().text
        org.junit.Assert.assertTrue(japanese != original && japanese != chinese)
        compose.onNodeWithText("关闭").performClick()
        compose.onAllNodesWithText(original).onFirst().assertExists()
        compose.onAllNodesWithTag("newsHeadlineLink").onFirst().assertExists()
        compose.runOnIdle { org.junit.Assert.assertNull(web(activity.window.decorView)) }
    }

    private fun awaitResourceCatalog() {
        compose.onNodeWithTag("openSingleSource").performClick()
        compose.waitUntil(25000) {
            compose.onAllNodesWithTag("resourcePicker").fetchSemanticsNodes().isNotEmpty() &&
                compose.onAllNodes(hasTestTag("fetchResource") and isEnabled()).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun saveResource(tag: String, mime: String, filename: String, verify: (java.io.File) -> Unit) {
        val file = java.io.File(activity.filesDir, "app-updates/$filename")
        file.parentFile?.mkdirs()
        val uri = androidx.core.content.FileProvider.getUriForFile(activity, "${activity.packageName}.updates", file)
        val instrument = InstrumentationRegistry.getInstrumentation()
        val filter = android.content.IntentFilter(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            addDataType(mime)
        }
        val picker = instrument.addMonitor(filter,
            android.app.Instrumentation.ActivityResult(android.app.Activity.RESULT_OK, Intent().setData(uri)), true)
        try {
            compose.onNodeWithTag(tag).performScrollTo().assertIsEnabled().performClick()
            compose.waitUntil(10000) { file.isFile && file.length() > 0 }
            compose.onNodeWithText("已保存文件").assertExists()
            verify(file)
        } finally {
            instrument.removeMonitor(picker)
            file.delete()
        }
    }

    private fun awaitVisibleConnection() {
        compose.waitUntil(45000) {
            runCatching { compose.onAllNodesWithText("已连接").fetchSemanticsNodes().isNotEmpty() }.getOrDefault(false)
        }
    }
    @Test fun keyboardGoAndSelectedText() {
        browser()
        compose.onNodeWithTag("browserAddress").performTextReplacement("https://example.com/?workbench=selection")
        compose.onNodeWithTag("browserAddress").performImeAction()
        val selected = AtomicReference(false)
        compose.waitUntil(30000) {
            activity.runOnUiThread { web(activity.window.decorView)?.evaluateJavascript("(()=>{if(!location.search.includes('workbench=selection') || !document.querySelector('p'))return false;let r=document.createRange();r.selectNodeContents(document.querySelector('p'));let s=getSelection();s.removeAllRanges();s.addRange(r);return s.toString().length>10;})()") { selected.set(it == "true") } }
            selected.get()
        }
        compose.onNodeWithTag("browserAddress").assertIsNotFocused()
        compose.waitUntil(15000) {
            compose.onAllNodes(hasTestTag("browserText") and isEnabled()).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithContentDescription("更多浏览操作").performClick()
        compose.onNodeWithText("选中文本").assertIsEnabled().performClick()
        compose.waitUntil(10000) {
            compose.onAllNodesWithText("交给服务器 Codex").fetchSemanticsNodes().isNotEmpty() ||
                compose.onAllNodesWithText("请先长按网页选中文字").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("交给服务器 Codex").assertExists()
        compose.onNodeWithText("取消").performClick()
    }

    @Test fun defaultHomeAndNavigationHistory() {
        activity.runOnUiThread { activity.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
        compose.waitUntil(45000) { compose.onAllNodesWithText("已连接").fetchSemanticsNodes().isNotEmpty() }
        compose.openWorkbenchTab("浏览")
        compose.onNodeWithTag("browserStart").assertIsDisplayed()
        compose.onNodeWithTag("browserText").assertIsNotEnabled()
        compose.onNodeWithText("栖物里").performClick()
        compose.waitUntil(30000) {
            compose.onAllNodes(hasTestTag("browserText") and isEnabled()).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("browserAddress").assertTextContains("https://qiwuli.com/")
        val homeRendered = AtomicReference(false)
        compose.waitUntil(30000) {
            activity.runOnUiThread {
                web(activity.window.decorView)?.evaluateJavascript(
                    "Boolean(document.querySelector('#app')?.innerText?.trim())"
                ) { homeRendered.set(it == "true") }
            }
            homeRendered.get()
        }
        compose.onNodeWithTag("browserText").performClick()
        compose.waitUntil(10000) {
            compose.onAllNodesWithTag("browserInstruction", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("取消").performClick()

        compose.onNodeWithTag("browserAddress").performTextReplacement("example.com")
        compose.onNodeWithTag("browserAddress").performImeAction()
        compose.waitUntil(30000) {
            compose.onAllNodes(hasTestTag("browserAddress") and hasText("https://example.com/")).fetchSemanticsNodes().isNotEmpty() &&
                compose.onAllNodes(hasTestTag("browserText") and isEnabled()).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithContentDescription("后退").assertIsEnabled().performClick()
        compose.waitUntil(30000) {
            compose.onAllNodes(hasTestTag("browserAddress") and hasText("https://qiwuli.com/")).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithContentDescription("更多浏览操作").performClick()
        compose.onNodeWithText("前进").assertIsEnabled().performClick()
        compose.waitUntil(30000) {
            compose.onAllNodes(hasTestTag("browserAddress") and hasText("https://example.com/")).fetchSemanticsNodes().isNotEmpty()
        }
        compose.waitUntil(30000) {
            compose.onAllNodes(hasTestTag("browserCapture") and isEnabled()).fetchSemanticsNodes().isNotEmpty()
        }
        val document = AtomicReference("")
        compose.runOnIdle {
            web(activity.window.decorView)?.evaluateJavascript(
                "window.__workbenchReturnTest = 'same-document'"
            ) { document.set(it) }
        }
        compose.waitUntil(5000) { document.get().contains("same-document") }
        captureReviewScreen("browser")
        compose.openWorkbenchTab("设置")
        compose.onNodeWithTag("browserAddress").assertIsNotDisplayed()
        compose.openWorkbenchTab("浏览")
        compose.onNodeWithTag("browserCapture").assertIsEnabled()
        document.set("")
        compose.runOnIdle {
            web(activity.window.decorView)?.evaluateJavascript("window.__workbenchReturnTest") { document.set(it) }
        }
        compose.waitUntil(5000) { document.get().contains("same-document") }
        compose.onNodeWithTag("browserCapture").performClick()
        compose.onNodeWithTag("captureRegion").performTouchInput {
            swipe(Offset(width * .15f, height * .15f), Offset(width * .85f, height * .5f), 600)
        }
        compose.onNodeWithTag("captureSelected").assertIsEnabled().performClick()
        compose.onNodeWithContentDescription("待发送图片", useUnmergedTree = true).assertExists()
        compose.mainClock.advanceTimeBy(350)
        compose.waitForIdle()
        captureReviewScreen("browser-capture")
        compose.onNodeWithText("取消").performClick()
        compose.onNodeWithContentDescription("主页").performClick()
        compose.onNodeWithTag("browserStart").assertIsDisplayed()
        compose.onNodeWithTag("browserText").assertIsNotEnabled()
    }
    @Test fun currentPageCanBeStarredAndEdited() {
        browser()
        val fixtureUrl = "https://example.com/?workbench=bookmark-acceptance"
        compose.onNodeWithTag("browserAddress").performTextReplacement(fixtureUrl)
        compose.onNodeWithTag("browserAddress").performImeAction()
        compose.waitUntil(30000) {
            compose.onAllNodes(hasTestTag("bookmarkCurrentPage") and isEnabled()).fetchSemanticsNodes().isNotEmpty() &&
                compose.onAllNodes(hasTestTag("browserAddress") and hasText(fixtureUrl)).fetchSemanticsNodes().isNotEmpty()
        }
        val bookmarks = androidx.lifecycle.ViewModelProvider(activity)[WorkbenchModel::class.java].browserBookmarks
        try {
            compose.onNodeWithContentDescription("收藏当前网页").performClick()
            compose.onNodeWithContentDescription("编辑当前网页书签").assertExists().performClick()
            compose.onNodeWithTag("bookmarkUrl").assertTextContains(fixtureUrl)
            compose.onNodeWithTag("bookmarkName").performTextReplacement("书签验收")
            compose.onNodeWithText("保存").performClick()
            compose.runOnIdle { org.junit.Assert.assertEquals("书签验收", bookmarks.forUrl(fixtureUrl)?.title) }
            compose.onNodeWithContentDescription("书签").performClick()
            compose.onNodeWithTag("bookmarkSearch").performTextReplacement("书签验收")
            compose.onNode(hasText("书签验收") and hasAnyAncestor(hasTestTag("bookmarkList"))).assertIsDisplayed()
            compose.onNodeWithContentDescription("关闭书签").performClick()
            compose.onNodeWithTag("browserAddress").assertTextContains(fixtureUrl)
        } finally {
            compose.runOnIdle { bookmarks.forUrl(fixtureUrl)?.let(bookmarks::remove) }
        }
    }
    @Test fun sshReconnectKeepsTheOpenDocument() {
        browser()
        val model = androidx.lifecycle.ViewModelProvider(activity)[WorkbenchModel::class.java]
        val document = AtomicReference("")
        compose.runOnIdle {
            web(activity.window.decorView)?.evaluateJavascript("window.__workbenchReconnect = 'same-document'") {
                document.set(it)
            }
        }
        compose.waitUntil(5000) { document.get().contains("same-document") }
        compose.runOnIdle { model.disconnect() }
        compose.onNodeWithTag("browserReconnect").assertIsDisplayed()
        compose.onNodeWithTag("browserText").assertIsNotEnabled()
        compose.runOnIdle { model.connect() }
        compose.waitUntil(45000) {
            compose.onAllNodes(hasTestTag("browserText") and isEnabled()).fetchSemanticsNodes().isNotEmpty()
        }
        document.set("")
        compose.runOnIdle {
            web(activity.window.decorView)?.evaluateJavascript("window.__workbenchReconnect") { document.set(it) }
        }
        compose.waitUntil(5000) { document.get().contains("same-document") }
        val refreshed = AtomicReference("")
        compose.runOnIdle {
            web(activity.window.decorView)?.evaluateJavascript(
                "fetch('/?reconnect=' + Date.now(), {cache:'no-store'}).then(r => window.__reconnectStatus = r.status)"
            ) {}
        }
        compose.waitUntil(30000) {
            activity.runOnUiThread {
                web(activity.window.decorView)?.evaluateJavascript("window.__reconnectStatus") { refreshed.set(it) }
            }
            refreshed.get() == "200"
        }
    }
    @Test fun qiwuliLoadsDirectlyWithWorkbenchDisconnected() {
        activity.runOnUiThread { activity.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
        val model = androidx.lifecycle.ViewModelProvider(activity)[WorkbenchModel::class.java]
        val wasConnected = compose.runOnIdle { model.connected }
        val rendered = AtomicReference(false)
        fun openQiwuli() {
            rendered.set(false)
            val url = "https://qiwuli.com/?workbench=${System.nanoTime()}"
            compose.onNodeWithTag("browserAddress").performTextReplacement(url)
            compose.onNodeWithTag("browserAddress").performImeAction()
            compose.waitUntil(30000) {
                activity.runOnUiThread {
                    web(activity.window.decorView)?.evaluateJavascript(
                        "location.href === '$url' && !!document.querySelector('#app')?.innerText.includes('栖物里')"
                    ) { rendered.set(it == "true") }
                }
                rendered.get()
            }
        }
        try {
            compose.runOnIdle { model.disconnect() }
            compose.openWorkbenchTab("浏览")
            openQiwuli()
            compose.onNodeWithTag("browserModeIndicator").assertDoesNotExist()
        } finally {
            compose.runOnIdle {
                if (wasConnected && !model.connected && !model.connecting) model.connect()
            }
        }
    }

    @Test fun loadingAndPageFailureLeaveBrowserControlsUsable() {
        activity.runOnUiThread { activity.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
        compose.connectWorkbenchIfNeeded()
        compose.openWorkbenchTab("浏览")
        val missing = "https://qiwuli.com/assets/workbench-missing.js"
        val home = "https://qiwuli.com/"
        fun navigate(url: String) {
            compose.onNodeWithTag("browserAddress").performTextReplacement(url)
            compose.waitUntil(15000) { compose.onNode(hasTestTag("browserGo") and isEnabled()).isDisplayed() }
            compose.onNodeWithTag("browserGo").performTouchInput { click() }
        }
        fun waitForError() {
            compose.waitUntil(30000) { compose.onNodeWithTag("browserPageError").isDisplayed() }
            compose.onNodeWithTag("browserPageError").assertTextEquals("服务器返回 HTTP 404")
            compose.onNodeWithTag("browserAddress").assertIsDisplayed().assertIsEnabled()
            compose.runOnIdle {
                check(web(activity.window.decorView)?.visibility == View.INVISIBLE)
            }
        }
        navigate(home)
        compose.waitUntil(15000) { compose.onNodeWithTag("browserLoading").isDisplayed() }
        compose.onNodeWithTag("browserAddress").assertIsDisplayed()
        compose.onNodeWithContentDescription("书签").performTouchInput { click() }
        compose.onNodeWithTag("bookmarkManager").assertIsDisplayed()
        compose.onNodeWithContentDescription("关闭书签").performClick()
        navigate(missing)
        waitForError()
        compose.onNodeWithText("重试").performTouchInput { click() }
        waitForError()
        navigate(home)
        compose.waitUntil(30000) { compose.onNode(hasTestTag("browserText") and isEnabled()).isDisplayed() }
        compose.onNodeWithContentDescription("主页").performTouchInput { click() }
        compose.onNodeWithTag("browserStart").assertIsDisplayed()
    }
    @Test fun selectedRegionToVision() {
        browser()
        compose.onNodeWithTag("browserCapture").performClick()
        compose.onNodeWithTag("captureRegion").performTouchInput { swipe(Offset(width * .05f, height * .03f), Offset(width * .95f, height * .6f), 600) }
        compose.onNodeWithTag("captureSelected").assertIsEnabled().performClick()
        compose.onNodeWithTag("browserInstruction").performTextReplacement("请读取这张网页区域截图中的一段可见正文，并用中文说明它的用途；不调用工具。最后回复 BROWSER_IMAGE_OK。")
        compose.onNodeWithTag("browserSend").performClick()
        compose.waitUntil(180000) { runCatching { compose.onAllNodes(hasTestTag("message-assistant") and hasAnyDescendant(hasText("BROWSER_IMAGE_OK", substring = true)), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }.getOrDefault(false) }
        compose.onAllNodes(hasTestTag("message-assistant") and hasAnyDescendant(hasText("文档", substring = true)), useUnmergedTree = true).onLast().assertExists()
    }
    @Test fun originalImageCanBeSelected() {
        browser()
        activity.runOnUiThread { web(activity.window.decorView)?.loadUrl("https://httpbin.org/image/png") }
        val loaded = AtomicReference(false)
        compose.waitUntil(45000) {
            activity.runOnUiThread { web(activity.window.decorView)?.evaluateJavascript("!!document.images[0]?.complete && document.images[0]?.naturalWidth > 0") { loaded.set(it == "true") } }
            loaded.get()
        }
        val center = AtomicReference<Offset?>(null)
        activity.runOnUiThread { web(activity.window.decorView)?.let { w -> w.evaluateJavascript("(()=>{let r=document.images[0].getBoundingClientRect();return [r.x+r.width/2,r.y+r.height/2,innerWidth]})()") {
            val a = org.json.JSONArray(it); val scale = w.width / a.getDouble(2); center.set(Offset((a.getDouble(0)*scale).toFloat(), (a.getDouble(1)*scale).toFloat()))
        }
        } }
        compose.waitUntil(10000) { center.get() != null }
        val location = IntArray(2)
        compose.runOnIdle { web(activity.window.decorView)!!.getLocationOnScreen(location) }
        val point = center.get()!!
        val instrumentation = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
        val down = android.os.SystemClock.uptimeMillis()
        fun touch(action: Int) { android.view.MotionEvent.obtain(down, android.os.SystemClock.uptimeMillis(), action, location[0] + point.x, location[1] + point.y, 0).also { instrumentation.sendPointerSync(it); it.recycle() } }
        touch(android.view.MotionEvent.ACTION_DOWN)
        android.os.SystemClock.sleep(1200)
        touch(android.view.MotionEvent.ACTION_UP)
        try { compose.waitUntil(30000) { compose.onAllNodesWithTag("browserInstruction").fetchSemanticsNodes().isNotEmpty() } }
        catch (e: Exception) { println(compose.onRoot(useUnmergedTree = true).printToString()); throw e }
        compose.onNodeWithContentDescription("待发送图片").assertExists()
        compose.onNodeWithTag("browserInstruction").performTextReplacement("请描述这张原始网页图片中的动物和主要颜色，不调用工具，最后回复 ORIGINAL_IMAGE_OK。")
        compose.onNodeWithTag("browserSend").performClick()
        compose.waitUntil(180000) { runCatching { compose.onAllNodes(hasTestTag("message-assistant") and hasAnyDescendant(hasText("ORIGINAL_IMAGE_OK", substring = true)), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }.getOrDefault(false) }
        // Closing pooled HTTPS sockets must not affect a new connection.
        compose.openWorkbenchTab("设置")
        compose.onNodeWithText("断开连接").performScrollTo().performClick()
        compose.onNodeWithTag("connect").performScrollTo().performClick()
        compose.waitUntil(45000) { compose.onAllNodesWithText("已连接").fetchSemanticsNodes().isNotEmpty() }
        compose.openWorkbenchTab("会话")
        compose.onNodeWithTag("project-对话").performClick()
        compose.waitUntil(15000) { compose.onAllNodes(hasTestTag("message-assistant") and hasAnyDescendant(hasText("ORIGINAL_IMAGE_OK", substring = true)), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
    }

}
