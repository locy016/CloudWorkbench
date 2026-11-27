package cn.xux.cloudworkbench

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Before
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ChronicleAcceptanceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Before fun keepScreenOnDuringReview() {
        compose.runOnUiThread {
            compose.activity.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    @After fun finishWorkbench() {
        // On the tablet, ActivityScenario cannot launch its cross-package teardown activity.
        val activity = compose.activity
        compose.runOnUiThread { activity.finish() }
        compose.waitUntil(5000) { activity.isDestroyed }
    }

    private fun capture(name: String) {
        compose.waitForIdle()
        android.os.SystemClock.sleep(450)
        captureReviewScreen("chronicle-$name")
    }

    @Test fun navigateCompareTraceAndFilter() {
        compose.openWorkbenchTab("史时")
        compose.onNodeWithText("4 个区域 · 12 条记录").assertIsDisplayed()
        capture("overview")
        compose.onNodeWithContentDescription("上一阶段").performClick()
        compose.onNodeWithContentDescription("上一阶段").assertIsNotEnabled()
        compose.onNodeWithContentDescription("下一阶段").performClick()
        compose.onNodeWithTag("menuToggle").performClick()
        val browse = compose.onNodeWithTag("nav-浏览").fetchSemanticsNode().boundsInWindow
        val history = compose.onNodeWithTag("nav-史时").fetchSemanticsNode().boundsInWindow
        assertTrue(browse.bottom <= history.top)
        capture("menu")
        compose.onNodeWithTag("menuClose").performClick()

        compose.onNodeWithText("良渚以稻作支撑古城").performScrollTo().performClick()
        compose.onNodeWithText("联合国教科文组织 · 良渚古城遗址").assertIsDisplayed()
        capture("detail")
        compose.onNodeWithText("追踪中国的历史").performClick()
        compose.onNodeWithText("历时脉络").assertIsDisplayed()
        capture("region")
        val firstChina = regionalChronologies.getValue(HistoryRegion.China).first().summary
        compose.onNodeWithText(firstChina).performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("more-neolithic").performScrollTo().performClick()
        compose.onNodeWithText("良渚以稻作支撑古城").performScrollTo()
        capture("region-timeline")
        compose.onNodeWithText(regionalChronologies.getValue(HistoryRegion.China).last().summary).performScrollTo().assertIsDisplayed()
        compose.onNodeWithContentDescription("选择区域").performScrollTo()
        compose.onNodeWithContentDescription("选择区域").performClick()
        capture("region-picker")
        compose.onNodeWithText("日本列岛").performScrollTo().performClick()
        compose.onNodeWithText("全部维度").performClick()
        compose.onNode(hasText("科学技术") and hasAnyAncestor(isPopup())).performClick()
        compose.onNodeWithText(regionalChronologies.getValue(HistoryRegion.Japan).first { it.id == "showa" }.summary).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("东海道新干线开通").performScrollTo().assertIsDisplayed()
        capture("region-filtered")
        compose.openWorkbenchTab("设置")
        compose.openWorkbenchTab("史时")
        compose.onNodeWithText("东海道新干线开通").assertIsDisplayed()
        compose.onNodeWithText("东海道新干线开通").performClick()
        compose.onNodeWithText("对照这个阶段的其他区域").performClick()
        compose.onNodeWithContentDescription("选择阶段").assert(hasText(postwarPeriods[1].title))

        compose.onNodeWithContentDescription("选择阶段").performClick()
        capture("periods")
        compose.onNodeWithTag("eraTimeline").performScrollToIndex(0)
        compose.onNode(hasText("聚落与生计") and hasAnyAncestor(hasTestTag("eraTimeline"))).performClick()
        compose.onNodeWithText("进入本阶段").performClick()
        compose.onNodeWithText("全部维度").performClick()
        compose.onNode(hasText("政权制度") and hasAnyAncestor(isPopup())).performClick()
        compose.onNodeWithText("当前选材没有这一维度的记录。").assertIsDisplayed()
        compose.onNodeWithText("查看全部维度").performClick()
        compose.onNodeWithText("绳文聚落依水而居").performScrollTo().assertIsDisplayed()
    }

    @Test fun readingWindowsAndDraggableScrollbars() {
        compose.openWorkbenchTab("史时")
        compose.onNodeWithContentDescription("分期与选材说明").performClick()
        val infoTop = compose.onNodeWithText("分期与选材").fetchSemanticsNode().boundsInWindow.top
        compose.onNodeWithText("查阅世界史分期标准").performScrollTo().assertIsDisplayed()
        assertTrue(compose.onNodeWithText("分期与选材").fetchSemanticsNode().boundsInWindow.top == infoTop)
        capture("guide")
        compose.onNodeWithContentDescription("关闭").performClick()
        compose.onNodeWithContentDescription("选择阶段").performClick()
        val pane = compose.onNodeWithTag("eraTimeline")
        pane.performScrollToIndex(0)
        fun position() = pane.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value()
        val before = position()
        val header = compose.onNodeWithText("选择历史阶段").fetchSemanticsNode().boundsInWindow
        pane.performTouchInput {
            swipe(Offset(width - 5f, height * .25f), Offset(width - 5f, height * .65f), 450)
        }
        compose.waitForIdle()
        assertTrue("Right-edge drag must advance the stage list", position() > before)
        assertTrue(compose.onNodeWithText("选择历史阶段").fetchSemanticsNode().boundsInWindow == header)
        compose.onNodeWithText("进入本阶段").assertIsDisplayed()
        capture("timeline-scroll")
        compose.onNodeWithContentDescription("关闭").performClick()
    }

    @Test fun compareEarlyCitiesWithSources() {
        compose.openWorkbenchTab("史时")
        fun compare(lens: String, count: Int, name: String) {
            compose.onNodeWithContentDescription("筛选维度").performClick()
            compose.onNode(hasText(lens) and hasAnyAncestor(isPopup())).performClick()
            compose.onNodeWithText("同期区域对照").assertIsDisplayed()
            compose.onNodeWithText("4 个区域 · $count 条记录").assertIsDisplayed()
            capture(name)
        }
        compare("文化思想", 5, "culture")
        compose.onNodeWithText("乌尔琴饰描绘动物奏乐").performScrollTo().performClick()
        compose.onNodeWithText("史料出处").assertIsDisplayed()
        compose.onNodeWithText("宾夕法尼亚大学博物馆 · 乌尔里拉琴饰板").assertIsDisplayed()
        compose.onNodeWithContentDescription("关闭").performClick()
        compose.onNodeWithContentDescription("筛选维度").performScrollTo()
        compare("社会生活", 4, "society")
        compose.onNodeWithText("王墓工匠一家几代做同一门手艺").performScrollTo().performClick()
        compose.onNodeWithText("大都会艺术博物馆 · 新王国埃及的工匠墓").assertIsDisplayed()
    }

    @Test fun timelinePreviewsRegionalSummariesBeforeEntering() {
        historyEras.forEach { era ->
            assertTrue(era.events.map { it.region }.toSet() == era.regionalSummaries.keys)
            assertTrue(era.regionalSummaries.values.all { it.isNotBlank() })
        }
        compose.openWorkbenchTab("史时")
        compose.onNodeWithContentDescription("选择阶段").performClick()
        val timeline = compose.onNodeWithTag("eraTimeline")
        fun header(index: Int) = compose.onNode(hasText(historyEras[index].name) and hasAnyAncestor(hasTestTag("eraTimeline")))
        fun preview(index: Int) {
            timeline.performScrollToIndex(index)
            header(index).performClick().assert(hasStateDescription("已展开"))
            val heading = header(index).fetchSemanticsNode().boundsInWindow
            val details = compose.onNodeWithTag("eraSummary").fetchSemanticsNode().boundsInWindow
            val axis = timeline.fetchSemanticsNode().boundsInWindow
            assertTrue("阶段卡左右边距应相等", kotlin.math.abs((heading.left - axis.left) - (axis.right - heading.right)) <= 2)
            assertTrue("地区摘要应位于阶段概况正下方", heading.bottom <= details.top + 1 && details.width >= heading.width * .95f)
        }
        compose.onNodeWithText("区域摘要").assertIsDisplayed()
        capture("timeline-early")
        header(1).performClick().assert(hasStateDescription("已收起"))
        compose.onNodeWithTag("eraSummary").assertDoesNotExist()
        header(1).assert(hasText(historyEras[1].summary))
        capture("timeline-collapsed")
        preview(1)
        preview(historyEras.lastIndex)
        compose.onAllNodesWithTag("eraSummary").assertCountEquals(1)
        compose.onNodeWithText(historyEras.last().regionalSummaries.getValue(HistoryRegion.Brazil)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("取消").performClick()
        compose.onNodeWithText("城市与早期文明").assertIsDisplayed()
        compose.onNodeWithContentDescription("选择阶段").performClick()
        preview(3)
        capture("timeline-exchange")
        compose.onNodeWithText("进入本阶段").performClick()
        compose.onNodeWithText("区域交流的扩展").assertIsDisplayed()
    }

    @Test fun regionalHistoriesFromWarToToday() {
        val records = historyEras.flatMap { it.events }
        for (region in listOf(HistoryRegion.China, HistoryRegion.America, HistoryRegion.Russia)) {
            val modern = records.filter { it.region == region && it.inModernRegionalHistory() }
            assertTrue(modern.map { it.lens }.toSet() == HistoryLens.entries.toSet())
            val decades = modern.map { it.date.take(4).toInt() / 10 }.toSet()
            assertTrue((194..202).all { it in decades })
            assertTrue(modern.any { it.date.startsWith("2026") })
        }
        compose.openWorkbenchTab("史时")
        compose.onNodeWithText("区域追踪").performClick()
        compose.onNodeWithText("1949年至今").performClick()
        compose.onNodeWithText("中华人民共和国成立").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("工业利润月报记录前十个月的经营变化").performScrollTo().performClick()
        compose.onNodeWithText("史料出处").assertIsDisplayed()
        val body = compose.onNodeWithTag("historyDetailBody").fetchSemanticsNode().boundsInWindow
        val source = compose.onNodeWithText("史料出处").fetchSemanticsNode().boundsInWindow
        assertTrue("正文应通栏，出处应位于正文下方", body.bottom <= source.top)
        capture("china-current")
        compose.onNodeWithContentDescription("关闭").performClick()
        compose.onNodeWithContentDescription("选择区域").performScrollTo().performClick()
        compose.onNodeWithText(HistoryRegion.Russia.label).performScrollTo().performClick()
        compose.onNodeWithText("二战至今").performClick()
        compose.onNodeWithText("斯大林格勒战役成为东线转折").performScrollTo().assertIsDisplayed()
        capture("soviet-wartime")
        compose.onNodeWithText("德苏条约秘密议定书与波兰被瓜分").assertDoesNotExist()
        compose.onNodeWithTag("more-union-war").performScrollTo().performClick()
        compose.onNodeWithText("德苏条约秘密议定书与波兰被瓜分").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("more-union-war").performScrollTo().performClick()
        compose.onNodeWithText("俄罗斯金融危机波及卢布与银行储户").performScrollTo().performClick()
        compose.onNodeWithText("史料出处").assertIsDisplayed()
        capture("russia-transition")
        compose.onNodeWithContentDescription("关闭").performClick()
        compose.onNodeWithContentDescription("选择区域").performScrollTo().performClick()
        compose.onNodeWithText(HistoryRegion.America.label).performScrollTo().performClick()
        compose.onNodeWithText("二战至今").performClick()
        compose.onNodeWithText("肯尼迪与赫鲁晓夫应对古巴导弹危机").performScrollTo().performClick()
        compose.onNodeWithText("查看完整事件轨迹").performScrollTo().performClick()
        compose.onNodeWithText("古巴危机走上谈判桌").performScrollTo().performClick().assert(hasStateDescription("已展开"))
        compose.onNodeWithText("各共和国走向独立").performScrollTo().assert(hasStateDescription("已收起"))
        capture("us-soviet-trail")
    }

    @Test fun regionalPeriodizationAndMergedSelectors() {
        assertTrue(regionalChronologies.keys == HistoryRegion.entries.toSet())
        historyEras.flatMap { it.events }.forEach { e ->
            assertTrue(e.localPeriodIds.all { id -> regionalChronologies.getValue(e.region).count { it.id == id } == 1 })
        }
        assertTrue(americanPeriods.count { it.id.matches(Regex("p[0-9]+")) } == 47)
        compose.openWorkbenchTab("史时")
        compose.onNodeWithText("城市与早期文明").assertHasClickAction()
        compose.onNodeWithText("选择阶段").assertDoesNotExist()
        capture("merged-era")
        compose.onNodeWithText("区域追踪").performClick()
        compose.onNodeWithContentDescription("选择区域").assert(hasText("中国")).performClick()
        compose.onNodeWithText("北美·美国").performScrollTo().performClick()
        compose.onNodeWithContentDescription("选择区域").assert(hasText("北美·美国"))
        compose.onNodeWithText("选择区域").assertDoesNotExist()
        compose.onNodeWithContentDescription("选择本地分期").performClick()
        compose.onNodeWithText("按名称或年代查找").performTextInput("尼克松")
        compose.onNode(hasText("第37任 · 理查德·尼克松") and hasAnyAncestor(isDialog())).performScrollTo().performClick()
        compose.onNodeWithText("阿波罗11号载人登月").performScrollTo().assertIsDisplayed()
        capture("us-presidency")
        compose.onNodeWithText("美国推行新政").assertDoesNotExist()
        compose.onNodeWithText("阿波罗11号载人登月").performClick()
        compose.onNodeWithText("对照这个阶段的其他区域").performScrollTo().performClick()
        compose.onNodeWithContentDescription("选择阶段").assert(hasText(postwarPeriods[1].title))
    }

    @Test fun comparePostwarCultureWithinOnePeriod() {
        val all = historyEras.last().events
        assertTrue(all.all { event -> postwarPeriods.count { it.contains(event) } == 1 })
        postwarPeriods.forEach { period ->
            val records = all.filter(period::contains)
            assertTrue(records.any { it.lens == HistoryLens.Ideas })
            assertTrue(records.any { it.lens == HistoryLens.Society })
            assertTrue(records.map { it.region }.distinct().size >= 3)
        }
        compose.openWorkbenchTab("史时")
        compose.onNodeWithContentDescription("选择阶段").performClick()
        val list = compose.onNodeWithTag("eraTimeline")
        list.performScrollToIndex(historyEras.lastIndex)
        compose.onNode(hasText(historyEras.last().name) and hasAnyAncestor(hasTestTag("eraTimeline"))).performClick()
        compose.onNodeWithText(postwarPeriods[2].title).performScrollTo().performClick()
        compose.onNodeWithText("文化与日常生活").performScrollTo().assertIsDisplayed()
        capture("postwar-period")
        compose.onNodeWithContentDescription("筛选维度").performScrollTo().performClick()
        compose.onNode(hasText("文化思想") and hasAnyAncestor(isPopup())).performClick()
        val cairo = "马哈福兹笔下的开罗进入世界文学视野"
        compose.onNodeWithText(cairo).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("柏林墙的一段成为露天画廊").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("昆曲传承进入国际口头与非物质遗产记录").assertDoesNotExist()
        capture("postwar-compare")
        compose.onNodeWithText(cairo).performScrollTo().performClick()
        compose.onNodeWithText("诺贝尔奖 · 马哈福兹生平与作品").performScrollTo().assertIsDisplayed()
        compose.onNodeWithContentDescription("关闭").performClick()
        compose.onNodeWithText(postwarPeriods[3].dates).performScrollTo().performClick()
        compose.onNodeWithText("昆曲传承进入国际口头与非物质遗产记录").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(cairo).assertDoesNotExist()
        compose.onNodeWithText("返回六期总览").performScrollTo().performClick()
        compose.onNodeWithText(postwarPeriods.last().title).performScrollTo().assertIsDisplayed()
    }

    @Test fun readModernTrailsAndCurrentAffairs() {
        val trails = historyEras.flatMap { it.events }.mapNotNull { it.trail }.distinct()
        assertTrue(HistoryTrails.coldWar in trails && trails.size == 14 && trails.all { it.steps.size >= 4 })
        assertTrue(trails.flatMap { it.steps }.all { it.source.url.startsWith("https://") && it.source.label.isNotBlank() })
        compose.openWorkbenchTab("史时")
        fun enter(index: Int) {
            compose.onNodeWithContentDescription("选择阶段").performScrollTo().performClick()
            compose.onNodeWithTag("eraTimeline").performScrollToIndex(index)
            compose.onNode(hasText(historyEras[index].name) and hasAnyAncestor(hasTestTag("eraTimeline"))).performClick()
            compose.onNodeWithText("进入本阶段").performClick()
        }
        enter(7)
        compose.onNodeWithText("辛亥革命与清帝退位").performScrollTo().performClick()
        compose.onNodeWithText("查看完整事件轨迹").performScrollTo().performClick()
        compose.onNodeWithText("同盟会在东京成立").assertIsDisplayed()
        capture("modern-person")
        compose.onNodeWithText("孙中山卸任").performScrollTo().assertIsDisplayed()
        compose.onNodeWithContentDescription("关闭").performClick()
        compose.onNodeWithText("史料出处").performScrollTo().assertIsDisplayed()
        compose.onNodeWithContentDescription("关闭").performClick()
        enter(historyEras.lastIndex)
        compose.onNodeWithText("战后科技发展").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("AI 与大模型").performScrollTo().performClick()
        capture("technology-overview")
        compose.onNodeWithContentDescription("查看科技轨迹").performScrollTo().performClick()
        val reading = compose.onNodeWithTag("historyReadingPane")
        reading.performTouchInput {
            swipe(Offset(width - 5f, height * .25f), Offset(width - 5f, height * .55f), 350)
        }
        val range = reading.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange]
        assertTrue(range.maxValue() == 0f || range.value() > 0f)
        compose.onNodeWithText("同一模型接收文字与图像").performScrollTo().performClick()
        capture("tech-ai")
        compose.onNodeWithContentDescription("关闭").performClick()
        compose.onNodeWithText("每日动态").performScrollTo().assertIsDisplayed()
        compose.waitUntil(30_000) { !ChronicleDaily.loading.value }
        compose.onNodeWithText("刷新动态").performScrollTo().performClick()
        compose.waitUntil(30_000) { !ChronicleDaily.loading.value }
        assertTrue(DailySource.entries.all { ChronicleDaily.batches.value[it]?.items?.isNotEmpty() == true })
        for (topic in listOf("关键人物", "大模型", "地缘局势")) {
            compose.onNodeWithText(topic).performScrollTo().performClick()
            compose.onNodeWithText("查看全部 ", substring = true).performScrollTo().assertIsDisplayed()
            if (topic == "大模型") {
                compose.onAllNodes(hasText("仓库", substring = true))[2].performScrollTo().assertIsDisplayed()
            }
            capture("daily-" + when (topic) { "大模型" -> "models"; "关键人物" -> "people"; else -> "world" })
        }
        compose.onNodeWithText("时事专题").performScrollTo().assertIsDisplayed()
        capture("recent-index")
        compose.onNodeWithText(HistoryTrails.climate.title).performScrollTo().performClick()
        compose.onNodeWithText("协定在巴黎通过").assertIsDisplayed()
        compose.onNode(hasText(HistoryTrails.climate.steps.last().title) and hasAnyAncestor(isDialog())).performScrollTo().performClick()
        compose.onNodeWithText(HistoryTrails.climate.steps.last().source.label).performScrollTo().assertIsDisplayed()
        capture("recent-progress")
        compose.onNodeWithContentDescription("关闭").performClick()
        compose.onNodeWithText(HistoryTrails.worldCup.title).performScrollTo().performClick()
        compose.onNode(hasText("西班牙在加时赛夺冠") and hasAnyAncestor(isDialog())).performScrollTo().assertIsDisplayed()
    }
}
