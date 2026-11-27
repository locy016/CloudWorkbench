package cn.xux.cloudworkbench

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ChronicleAcceptanceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

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
        compose.onNodeWithText("选择区域").performClick()
        compose.onNodeWithText("日本列岛").performScrollTo().performClick()
        compose.onNodeWithText("全部维度").performClick()
        compose.onNode(hasText("科学技术") and hasAnyAncestor(isPopup())).performClick()
        compose.onNodeWithText("东海道新干线开通").assertIsDisplayed()
        compose.openWorkbenchTab("设置")
        compose.openWorkbenchTab("史时")
        compose.onNodeWithText("东海道新干线开通").assertIsDisplayed()
        compose.onNodeWithText("东海道新干线开通").performClick()
        compose.onNodeWithText("对照这个阶段的其他区域").performClick()
        compose.onNodeWithText("战后与当代世界").assertIsDisplayed()

        compose.onNodeWithText("选择阶段").performClick()
        capture("periods")
        compose.onNodeWithText("聚落与生计").performScrollTo().performClick()
        compose.onNodeWithText("全部维度").performClick()
        compose.onNode(hasText("政权制度") and hasAnyAncestor(isPopup())).performClick()
        compose.onNodeWithText("当前选材没有这一维度的记录。").assertIsDisplayed()
        compose.onNodeWithText("查看全部维度").performClick()
        compose.onNodeWithText("绳文聚落依水而居").performScrollTo().assertIsDisplayed()
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
}
