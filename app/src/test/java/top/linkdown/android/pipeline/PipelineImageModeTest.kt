package top.linkdown.android.pipeline

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import top.linkdown.android.engine.EngineException
import top.linkdown.android.engine.Mode
import top.linkdown.android.engine.ProbeInfo
import top.linkdown.android.queue.DedupCheck
import top.linkdown.android.queue.FakeEngine
import top.linkdown.android.queue.FakeEnv
import top.linkdown.android.queue.QueueRunHooks
import top.linkdown.android.queue.QueueItem
import java.io.File

/**
 * 图文/图集「有什么下什么」管道语义（ moov atom 修复）：probe hasImages 时——
 * 勾了的音频跳过（日志交代）不阻断其他模式；只勾音频 → 人话失败不静默空成功；
 * VIDEO 段交付多图，进度标签如实显示「图片」；视频帖（hasImages=false）全模式照旧。
 */
class PipelineImageModeTest {

    private fun tempRoot(): File =
        File(System.getProperty("java.io.tmpdir"), "pim-${System.nanoTime()}").apply { mkdirs() }

    private class Hooks : QueueRunHooks {
        val completedModes = ArrayList<Mode>()
        val logs = ArrayList<String>()
        val progressLabels = LinkedHashSet<String>()
        val existsSignals = java.util.concurrent.atomic.AtomicInteger(0)
        override fun cancelRequested() = false
        override fun shouldWaitOffline() = false
        override fun onLog(line: String) { logs.add(line) }
        override fun onProgress(modeLabel: String, fraction: Float) { progressLabels.add(modeLabel) }
        override fun onIngested(label: String, uri: android.net.Uri?) {}
        override suspend fun onProbeDone(title: String, coverUrl: String?, platform: String, durationSec: Double?) {}
        override suspend fun onModeCompleted(mode: Mode, files: List<File>) { completedModes.add(mode) }
        override suspend fun onExists() { existsSignals.incrementAndGet() }
    }

    private fun item(root: File, modes: DownloadModes) = QueueItem(
        id = "rec-1", url = douyinUrl, platform = "DOUYIN", modes = modes,
        title = "占位", taskDir = File(root, "td").apply { mkdirs() }.absolutePath, createdAt = 0L,
    )

    private fun imageEngine() = FakeEngine(
        probeInfo = ProbeInfo(
            platform = "抖音", mediaId = "m1", sourceUrl = "", title = "测试标题",
            author = "", coverUrl = null, durationSec = null, isVip = false,
            playUrl = "http://img/1.jpg;http://img/2.jpg", hasImages = true,
        ),
    )

    private val douyinUrl = "https://www.douyin.com/video/7300000000000000000"

    @Test fun `图文勾视频加音频 音频跳过日志交代 仅视频跑`() = runTest {
        val root = tempRoot()
        try {
            val env = FakeEnv(root)
            val e = imageEngine()
            env.register(douyinUrl, e)
            val hooks = Hooks()
            SingleDownloadPipeline(env).runItem(item(root, DownloadModes(true, true, false)), hooks)
            assertEquals("音频不进引擎（无 moov atom 形失败）", listOf(Mode.VIDEO), e.modesRequested)
            assertEquals(listOf(Mode.VIDEO), hooks.completedModes)
            assertTrue("跳过有日志交代", hooks.logs.any { it.contains("图文没有音频") })
            assertTrue("VIDEO 段交付的是图片，标签如实", "图片" in hooks.progressLabels)
            assertTrue("不误报视频标签", "视频" !in hooks.progressLabels)
        } finally { root.deleteRecursively() }
    }

    @Test fun `图文只勾音频 人话失败不静默空成功`() = runTest {
        val root = tempRoot()
        try {
            val env = FakeEnv(root)
            val e = imageEngine()
            env.register(douyinUrl, e)
            val hooks = Hooks()
            try {
                SingleDownloadPipeline(env).runItem(item(root, DownloadModes(false, true, false)), hooks)
                fail("应抛 EngineException")
            } catch (t: EngineException) {
                assertEquals("图文没有音频可提取", t.message)
            }
            assertEquals("音频不得进引擎", emptyList<Mode>(), e.modesRequested)
            assertTrue(hooks.completedModes.isEmpty())
        } finally { root.deleteRecursively() }
    }

    @Test fun `视频帖不受影响 视频加音频照跑`() = runTest {
        val root = tempRoot()
        try {
            val env = FakeEnv(root)
            val e = FakeEngine() // 默认 probeInfo hasImages=false
            env.register(douyinUrl, e)
            val hooks = Hooks()
            SingleDownloadPipeline(env).runItem(item(root, DownloadModes(true, true, false)), hooks)
            assertEquals(listOf(Mode.VIDEO, Mode.AUDIO), e.modesRequested)
            assertTrue("视频帖不出现图片/跳过文案", hooks.logs.none { it.contains("图文没有音频") })
            assertEquals(linkedSetOf("视频", "音频"), hooks.progressLabels)
        } finally { root.deleteRecursively() }
    }

    @Test fun `图文勾全套 视频封面跑音频跳过`() = runTest {
        val root = tempRoot()
        try {
            val env = FakeEnv(root)
            val e = imageEngine()
            env.register(douyinUrl, e)
            val hooks = Hooks()
            SingleDownloadPipeline(env).runItem(item(root, DownloadModes(true, true, true)), hooks)
            assertEquals(listOf(Mode.VIDEO, Mode.COVER), e.modesRequested)
            assertEquals(listOf(Mode.VIDEO, Mode.COVER), hooks.completedModes)
            assertTrue("图片" in hooks.progressLabels && "封面" in hooks.progressLabels)
        } finally { root.deleteRecursively() }
    }

    @Test fun `probeFor 预判型缝 走引擎probe 带回图文结果`() = runTest {
        val root = tempRoot()
        try {
            val env = FakeEnv(root)
            val e = imageEngine()
            env.register(douyinUrl, e)
            val info = SingleDownloadPipeline(env).probeFor(douyinUrl, null)
            assertTrue(info.hasImages)
            assertEquals(douyinUrl, e.probesRequested.single())
        } finally { root.deleteRecursively() }
    }

    @Test fun `图文封面判重命中加音频勾选 Exists 终态而非失败劣化`() = runTest {
        val root = tempRoot()
        try {
            val env = FakeEnv(root)
            env.dedup = DedupCheck { listOf("测试标题.jpg") } // 封面命中，音频不可跑 → 无可跑模式
            val e = imageEngine()
            env.register(douyinUrl, e)
            val hooks = Hooks()
            SingleDownloadPipeline(env).runItem(item(root, DownloadModes(false, true, true)), hooks)
            assertEquals("审核 P2：内容已在货架 → Exists 终态（非异常失败）", 1, hooks.existsSignals.get())
            assertEquals("引擎零下载", emptyList<Mode>(), e.modesRequested)
            assertTrue(hooks.logs.any { it.contains("图文没有音频") })
        } finally { root.deleteRecursively() }
    }

    @Test fun `图文视频已完成后恢复 只剩音频 Exists 非永久失败`() = runTest {
        val root = tempRoot()
        try {
            val env = FakeEnv(root)
            val e = imageEngine()
            env.register(douyinUrl, e)
            val hooks = Hooks()
            val resumed = QueueItem(
                id = "rec-2", url = douyinUrl, platform = "DOUYIN", modes = DownloadModes(true, true, false),
                title = "占位", taskDir = File(root, "td2").apply { mkdirs() }.absolutePath, createdAt = 0L,
                completedModes = setOf(Mode.VIDEO), // 前次图片已交付，恢复后只剩不可跑的音频
            )
            SingleDownloadPipeline(env).runItem(resumed, hooks)
            assertEquals("已交付过 → Exists 终态可自愈", 1, hooks.existsSignals.get())
            assertEquals(emptyList<Mode>(), e.modesRequested)
        } finally { root.deleteRecursively() }
    }
}
