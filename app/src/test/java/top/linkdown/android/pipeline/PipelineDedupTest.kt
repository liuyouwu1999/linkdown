package top.linkdown.android.pipeline

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import top.linkdown.android.engine.Mode
import top.linkdown.android.queue.DedupCheck
import top.linkdown.android.queue.FakeEngine
import top.linkdown.android.queue.FakeEnv
import top.linkdown.android.queue.QueueRunHooks
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/**
 * 功能任务：库判重管道接线（设计——probe 后、下载启动前；命中模式跳过不重下；
 * 全部所选模式命中 → onExists 信号（**非异常**，队列侧终态化 Exists））。
 * 假引擎 probe 固定 title=测试标题/platform=汽水；假查询按落架名单注入。
 */
class PipelineDedupTest {

    private fun tempRoot(): File =
        File(System.getProperty("java.io.tmpdir"), "pd-${System.nanoTime()}").apply { mkdirs() }

    private fun ytdlpUrl(n: Int) = "https://example.com/v/$n"

    private class Hooks : QueueRunHooks {
        val existsSignals = AtomicInteger(0)
        val completedModes = ArrayList<Mode>()
        val logs = ArrayList<String>()
        override fun cancelRequested() = false
        override fun shouldWaitOffline() = false
        override fun onLog(line: String) { logs.add(line) }
        override fun onProgress(modeLabel: String, fraction: Float) {}
        override fun onIngested(label: String, uri: android.net.Uri?) {}
        override suspend fun onProbeDone(title: String, coverUrl: String?, platform: String, durationSec: Double?) {}
        override suspend fun onModeCompleted(mode: Mode, files: List<File>) { completedModes.add(mode) }
        override suspend fun onExists() { existsSignals.incrementAndGet() }
    }

    private fun item(root: File, modes: DownloadModes) = top.linkdown.android.queue.QueueItem(
        id = "rec-1", url = ytdlpUrl(1), platform = "YTDLP", modes = modes,
        title = "占位", taskDir = File(root, "td").apply { mkdirs() }.absolutePath, createdAt = 0L,
    )

    @Test fun `判重全命中 不经引擎下载 onExists 恰一次 临时目录清理`() = runTest {
        val root = tempRoot()
        try {
            val env = FakeEnv(root)
            env.dedup = DedupCheck { listOf("测试标题.mp4", "测试标题.jpg") } // video+cover 全命中
            val e = FakeEngine()
            env.register(ytdlpUrl(1), e)
            val theItem = item(root, DownloadModes(true, false, true)) // 先取项（item() 会 mkdirs，勿后建复活断言）
            val hooks = Hooks()
            SingleDownloadPipeline(env).runItem(theItem, hooks)
            assertEquals("Exists 信号恰一次（非异常通道）", 1, hooks.existsSignals.get())
            assertEquals("引擎零下载（命中不重下）", emptyList<Mode>(), e.modesRequested)
            assertFalse("无产物 → 临时目录清理", File(theItem.taskDir).exists())
        } finally { root.deleteRecursively() }
    }

    @Test fun `判重部分命中 命中模式跳过 余下照下 不发 Exists`() = runTest {
        val root = tempRoot()
        try {
            val env = FakeEnv(root)
            env.dedup = DedupCheck { listOf("测试标题.mp4") } // 仅 VIDEO 命中（mp4 不在 AUDIO/COVER 集合）
            val e = FakeEngine()
            env.register(ytdlpUrl(1), e)
            val hooks = Hooks()
            SingleDownloadPipeline(env).runItem(item(root, DownloadModes(true, true, false)), hooks)
            assertEquals("仅未命中模式（AUDIO）下载", listOf(Mode.AUDIO), e.modesRequested)
            assertEquals("completedModes 只含实跑模式", listOf(Mode.AUDIO), hooks.completedModes)
            assertEquals("部分命中 ≠ Exists", 0, hooks.existsSignals.get())
            assertTrue("跳过模式有日志交代", hooks.logs.any { it.contains("货架") })
        } finally { root.deleteRecursively() }
    }

    @Test fun `判重零命中 全模式照下 不发 Exists`() = runTest {
        val root = tempRoot()
        try {
            val env = FakeEnv(root) // 默认假查询=空货架
            val e = FakeEngine()
            env.register(ytdlpUrl(1), e)
            val hooks = Hooks()
            SingleDownloadPipeline(env).runItem(item(root, DownloadModes(true, true, false)), hooks)
            assertEquals(listOf(Mode.VIDEO, Mode.AUDIO), e.modesRequested)
            assertEquals(0, hooks.existsSignals.get())
        } finally { root.deleteRecursively() }
    }
}
