package me.rerere.rikkahub.ui.pages.memory

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 261 / O1：「立即做梦」的两条提示必须与**真实的 run 状态**对齐。
 *
 * 旧行为：文案只看 `scanDirtyScopes().scheduledScopes`，于是「扫描说排上了」就报「已开始做梦」，
 * 而 run 可能压根没创建成功——用户看到的就是「提示已开始、下面没有做梦进行中」。
 */
class DreamNowMessageTest {
    @Test
    fun `already running message names the active run count`() {
        val message = dreamAlreadyRunningMessage(activeRuns = 2)

        assertTrue(message.contains("已有一次做梦在进行"))
        assertTrue(message.contains("2"))
    }

    @Test
    fun `started message only claims started when a run really exists`() {
        val started = dreamNowStartedMessage(activeRuns = 1)
        val notStarted = dreamNowStartedMessage(activeRuns = 0)

        assertTrue(started.startsWith("已开始做梦"))
        // 数不到 run 就不能说「已开始」——这是本版要堵的那个假信号。
        assertTrue(!notStarted.contains("已开始做梦"))
        assertTrue(notStarted.contains("没有创建成功"))
    }
}
