package top.linkdown.android.queue

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import top.linkdown.android.engine.Mode
import top.linkdown.android.engine.internal.ChildRef
import top.linkdown.android.pipeline.DownloadModes
import top.linkdown.android.pipeline.SingleDownloadPipeline
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/**
 * 功能任务：库判重 → Exists 终态的队列级接线（设计：命中=终态 Exists 不算 Failed；
 * 批量子项计入 existsChildren 不重下；Exists 视同完成参与父组归约）。
 * 假引擎 probe 固定 title=测试标题；假查询按落架名单注入。
 */
class DownloadQueueDedupTest {

 // ---- 脚手架（同 DownloadQueueTest.Harness 裁剪） ----

    private fun tempRoot(): File =
        File(System.getProperty("java.io.tmpdir"), "dqd-${System.nanoTime()}").apply { mkdirs() }

    private class Harness(root: File, scheduler: TestCoroutineScheduler) {
        val network = FakeNetworkMonitor()
        val env = FakeEnv(root)
        val startLog = AtomicInteger(0)
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(scheduler))
        val persistence = QueuePersistence.create(File(root, "queue.preferences_pb"), scope)
        val pipeline = SingleDownloadPipeline(env)
        var tick = 1000L
        val queue = DownloadQueue(
            persistence = persistence,
            network = network,
            runner = { item, hooks -> pipeline.runItem(item, hooks) },
            scope = scope,
            newTaskDir = { File(root, "td-${System.nanoTime()}").apply { mkdirs() } },
            startService = { startLog.incrementAndGet() },
            cancelDispatch = {},
            now = { tick++ },
        )
    }

    private fun ytdlpUrl(n: Int) = "https://example.com/v/$n"

    private fun Harness.engine(url: String, block: FakeEngine.() -> Unit = {}): FakeEngine =
        FakeEngine().apply(block).also { env.register(url, it) }

    private fun QueueUiState.item(id: String): QueueItem =
        items.firstOrNull { it.id == id } ?: error("item $id not in state: $items")

    private suspend fun Harness.enqueue(url: String, modes: DownloadModes): String =
        (queue.enqueue(url, modes, null) as EnqueueResult.Enqueued).item.id

    @Test fun `判重全命中 单项终态 Exists 进度置1 引擎零下载`() = runTest {
        val root = tempRoot(); val h = Harness(root, testScheduler)
        try {
            val e = h.engine(ytdlpUrl(1))
            h.env.dedup = DedupCheck { listOf("测试标题.mp4") } // probe title=测试标题 → VIDEO 命中
            val id = h.enqueue(ytdlpUrl(1), DownloadModes(true, false, false))
            testScheduler.advanceUntilIdle()
            val item = h.queue.state.value.item(id)
            assertEquals("命中 → Exists 终态（非异常非 Failed）", QueueState.Exists, item.state)
            assertEquals("Exists 视同完成：进度置 1", 1f, item.progressFraction)
            assertEquals("引擎零下载（不重下）", emptyList<Mode>(), e.modesRequested)
            assertEquals("error 为空（不是失败）", null, item.error)
        } finally { h.scope.cancel(); root.deleteRecursively() }
    }

    @Test fun `判重部分命中 命中跳过余下照下 终态 Completed`() = runTest {
        val root = tempRoot(); val h = Harness(root, testScheduler)
        try {
            val e = h.engine(ytdlpUrl(1))
            h.env.dedup = DedupCheck { listOf("测试标题.mp4") } // 仅 VIDEO 命中
            val id = h.enqueue(ytdlpUrl(1), DownloadModes(true, true, false))
            testScheduler.advanceUntilIdle()
            assertEquals("部分命中照下余下 → Completed", QueueState.Completed, h.queue.state.value.item(id).state)
            assertEquals(listOf(Mode.AUDIO), e.modesRequested)
        } finally { h.scope.cancel(); root.deleteRecursively() }
    }

    @Test fun `批量子项判重命中 子项 Exists 父组 existsChildren 加计 归约 Completed`() = runTest {
        val root = tempRoot(); val h = Harness(root, testScheduler)
        try {
            val engine = FakeEngine() // probe 恒 title=测试标题（两子项共用同引擎实例亦可）
            h.env.register(ytdlpUrl(1), engine)
            h.env.register(ytdlpUrl(2), engine)
            h.env.dedup = DedupCheck { listOf("测试标题.mp4") }
            val r = h.queue.enqueueGroup(
                title = "歌单", url = "https://example.com/playlist/1",
                modes = DownloadModes(true, false, false),
                children = listOf(ChildRef(ytdlpUrl(1), "t1"), ChildRef(ytdlpUrl(2), "t2")),
                sessdataSnapshot = null,
            ) as EnqueueResult.Enqueued
            testScheduler.advanceUntilIdle()
            val parent = h.queue.state.value.item(r.item.id)
            val children = h.queue.state.value.items.filter { it.parentGroupId == r.item.id }
            assertEquals("两子项全命中 → Exists", listOf(QueueState.Exists, QueueState.Exists), children.map { it.state })
            assertEquals("existsChildren 加计", 2, parent.existsChildren)
            assertEquals("Exists 视同完成 → 父组归约 Completed", QueueState.Completed, parent.state)
            assertEquals("引擎零下载", emptyList<Mode>(), engine.modesRequested)
        } finally { h.scope.cancel(); root.deleteRecursively() }
    }
}
