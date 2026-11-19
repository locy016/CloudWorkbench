package cn.xux.cloudworkbench

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BookmarkAcceptanceTest {
    @get:Rule val compose = createComposeRule()

    @Test fun existingBookmarksAndFoldersCanBeManagedAndRestored() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val prefs = context.getSharedPreferences("bookmark-acceptance", 0)
        prefs.edit().clear().putString("browserBookmarks", """
            [{"id":"original","title":"栖物里","url":"https://qiwuli.com/","shortcut":true},
            {"id":"example","title":"Example","url":"https://example.com/","shortcut":false}]
        """.trimIndent()).commit()
        val bookmarks = BrowserBookmarks(prefs)
        var opened: String? = null
        try {
            compose.setContent {
                WorkbenchTheme {
                    BrowserBookmarkManager(bookmarks, onOpen = { opened = it.url; true }, onClose = {})
                }
            }
            compose.onNodeWithText("栖物里").assertIsDisplayed()
            compose.onNodeWithContentDescription("新建文件夹").performClick()
            compose.onNodeWithTag("bookmarkFolderName").performTextReplacement("资料")
            compose.onNodeWithText("保存").performClick()
            val folderId = compose.runOnIdle { bookmarks.entries.single { it.isFolder }.id }
            compose.onNodeWithContentDescription("Example的更多操作").performClick()
            compose.onNodeWithText("移动到…").performClick()
            compose.onNodeWithTag("bookmarkDestination-$folderId").performClick()
            compose.onNodeWithText("移到这里").performClick()
            compose.onNodeWithText("Example").assertDoesNotExist()
            compose.onNodeWithTag("bookmarkSearch").performTextReplacement("example.com")
            compose.onNodeWithText("Example").assertIsDisplayed()
            compose.onNodeWithContentDescription("Example的更多操作").performClick()
            compose.onNodeWithText("编辑").performClick()
            compose.onNodeWithTag("bookmarkName").performTextReplacement("参考示例")
            compose.onNodeWithText("保存").performClick()
            compose.onNodeWithContentDescription("清空搜索").performClick()
            compose.onNodeWithText("资料").performClick()
            compose.onNodeWithText("参考示例").performClick()
            compose.runOnIdle {
                assertEquals("https://example.com/", opened)
                val reloaded = BrowserBookmarks(prefs)
                assertEquals(folderId, reloaded.find("example")?.parentId)
                assertTrue(reloaded.find("original")?.shortcut == true)
                assertEquals(folderId, reloaded.lastFolder)
                assertFalse(reloaded.canMove(reloaded.find(folderId)!!, folderId))
            }
            compose.onNodeWithContentDescription("排序和视图选项").performClick()
            compose.onNodeWithText("最近打开").performClick()
            compose.onNodeWithContentDescription("排序和视图选项").performClick()
            compose.onNodeWithText("切换到紧凑视图").performClick()
            compose.runOnIdle {
                val reloaded = BrowserBookmarks(prefs)
                assertEquals(BookmarkOrder.Recent, reloaded.order)
                assertTrue(reloaded.compact)
            }
            compose.onNodeWithContentDescription("返回").performClick()
            compose.onNodeWithContentDescription("资料的更多操作").performClick()
            compose.onNodeWithText("删除").performClick()
            compose.onNodeWithText("删除文件夹？").assertIsDisplayed()
            compose.onNodeWithText("删除").performClick()
            compose.runOnIdle {
                assertNull(bookmarks.find("example"))
                assertNotNull(bookmarks.find("original"))
            }
        } finally {
            prefs.edit().clear().commit()
        }
    }
}
