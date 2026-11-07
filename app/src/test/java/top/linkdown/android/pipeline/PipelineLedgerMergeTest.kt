package top.linkdown.android.pipeline

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import top.linkdown.android.engine.Mode
import top.linkdown.android.queue.DedupCheck
import top.linkdown.android.queue.FakeEngine
import top.linkdown.android.queue.FakeEnv
import top.linkdown.android.queue.QueueRunHooks
import java.io.File

/** 台账缝接线（设计 §5：每模式增量 merge、半程合并、失败不影响下载）。 */
class PipelineLedgerMergeTest {

    private fun tempRoot(): File =
        File(System.getProperty("java.io.tmpdir"), "plm-${System.nanoTime()}").apply { mkdirs() }

    private class Hooks : QueueRunHooks {
        val completedModes = ArrayList<Mode>()
        override fun cancelRequested() = false
        override fun shouldWaitOffline() = false
        override fun onLog(line: String) {}
        override fun onProgress(modeLabel: String, fraction: Float) {}
        override fun onIngested(label: String, uri: android.net.Uri?) {}
        override suspend fun onProbeDone(title: String, coverUrl: String?, platform: String, durationSec: Double?) {}
        override suspend fun onModeCompleted(mode: Mode, files: List<File>) { completedModes.add(mode) }
        override suspend fun onExists() {}
    }

    private fun item(root: File, modes: DownloadModes) = top.linkdown.android.queue.QueueItem(
        id = "rec-1", url = "https://example.com/v/1", platform = "YTDLP", modes = modes,
        title = "占位", taskDir = File(root, "td").apply { mkdirs() }.absolutePath, createdAt = 0L,
    )

    @Test fun `每模式增量 merge workKey 取 probe mediaId`() = runTest {
        val root = tempRoot()
        try {
            val env = FakeEnv(root)
            val e = FakeEngine()
            env.register("https://example.com/v/1", e)
            val hooks = Hooks()
            SingleDownloadPipeline(env).runItem(item(root, DownloadModes(true, false, true)), hooks)
            assertEquals(2, env.ledgerMerges.size)
            assertEquals("汽水:m1", env.ledgerMerges[0].workKey)
            assertEquals("汽水", env.ledgerMerges[0].platform)
            assertEquals("测试标题", env.ledgerMerges[0].title)
            assertEquals(listOf("out-video.bin"), env.ledgerMerges[0].files.map { it.name })
            assertEquals(listOf("out-cover.bin"), env.ledgerMerges[1].files.map { it.name })
            assertEquals("Download/链接下载/汽水/", env.ledgerMerges[0].files[0].path)
        } finally { root.deleteRecursively() }
    }

    @Test fun `全模式判重命中 不写台账`() = runTest {
        val root = tempRoot()
        try {
            val env = FakeEnv(root)
            env.dedup = DedupCheck { listOf("测试标题.mp4", "测试标题.jpg") }
            env.register("https://example.com/v/1", FakeEngine())
            SingleDownloadPipeline(env).runItem(item(root, DownloadModes(true, false, true)), Hooks())
            assertEquals(0, env.ledgerMerges.size)
        } finally { root.deleteRecursively() }
    }

    @Test fun `模式中途失败 半程合并已入库部分 异常照常上抛`() = runTest {
        val root = tempRoot()
        try {
            val env = FakeEnv(root)
            val e = FakeEngine().apply { filesPerMode = 2 }
            env.register("https://example.com/v/1", e)
            env.ingestFailOn = "out-video-2.bin" // 第 2 张入库失败
            val hooks = Hooks()
            val err = runCatching {
                SingleDownloadPipeline(env).runItem(item(root, DownloadModes(true, false, false)), hooks)
            }.exceptionOrNull()
            assertTrue("失败如实上抛", err != null)
            assertEquals(1, env.ledgerMerges.size)
            assertEquals(listOf("out-video-1.bin"), env.ledgerMerges[0].files.map { it.name })
        } finally { root.deleteRecursively() }
    }

    @Test fun `台账缝自身失败 下载不受影响`() = runTest {
        val root = tempRoot()
        try {
            val env = FakeEnv(root)
            env.ledgerMerge = { throw RuntimeException("台账炸了") }
            env.register("https://example.com/v/1", FakeEngine())
            val hooks = Hooks()
            SingleDownloadPipeline(env).runItem(item(root, DownloadModes(true, false, true)), hooks)
            assertEquals("两模式照常完成", listOf(Mode.VIDEO, Mode.COVER), hooks.completedModes)
        } finally { root.deleteRecursively() }
    }
}
