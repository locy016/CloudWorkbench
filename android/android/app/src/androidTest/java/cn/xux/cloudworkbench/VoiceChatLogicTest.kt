package cn.xux.cloudworkbench

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceChatLogicTest {
    @Test fun startsTheNextTurnAfterThePreviousReplyFinishes() = runBlocking {
        val turns = ArrayDeque(listOf("第一轮", "第二轮"))
        val calls = mutableListOf<String>()
        val firstPlayback = CompletableDeferred<Unit>()
        val finishFirstPlayback = CompletableDeferred<Unit>()
        val loop = launch {
            runVoiceTurns(
                capture = {
                    calls.add("collect")
                    turns.removeFirstOrNull()
                },
                exchange = { text -> calls.add("send $text"); "$text 回复" },
                speak = { text ->
                    calls.add("read $text")
                    if (text.startsWith("第一轮")) {
                        firstPlayback.complete(Unit)
                        finishFirstPlayback.await()
                    }
                },
                onStage = { _, _ -> }
            )
        }
        firstPlayback.await()
        assertEquals(listOf("collect", "send 第一轮", "read 第一轮 回复"), calls)
        finishFirstPlayback.complete(Unit)
        loop.join()
        assertEquals(listOf("collect", "send 第一轮", "read 第一轮 回复",
            "collect", "send 第二轮", "read 第二轮 回复", "collect"), calls)
    }

    @Test fun closingDuringCaptureLeavesWithoutSending() = runBlocking {
        val listening = CompletableDeferred<Unit>()
        var sent = false
        val loop = launch {
            runVoiceTurns(
                capture = { listening.complete(Unit); awaitCancellation() },
                exchange = { sent = true; "reply" },
                speak = {},
                onStage = { _, _ -> }
            )
        }
        listening.await()
        loop.cancelAndJoin()
        assertFalse(sent)
    }

    @Test fun readingKeepsLinkLabelsAndOmitsCodeAndRawAddresses() {
        val text = "## 状态\n**通过**，详见 [说明](https://example.test/help)。\n```bash\nls -al\n```"
        val spoken = voiceReadingText(text)
        assertTrue(spoken.contains("通过") && spoken.contains("说明"))
        assertFalse(spoken.contains("https") || spoken.contains("ls -al"))
    }
}
