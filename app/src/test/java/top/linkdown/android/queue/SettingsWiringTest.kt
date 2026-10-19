package top.linkdown.android.queue

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import top.linkdown.android.engine.Mode
import top.linkdown.android.pipeline.DownloadModes
import top.linkdown.android.pipeline.SingleDownloadPipeline
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/**
 * 功能任务 Step 2：设置三接线透传断言（假引擎驱动）。
 * - audioFormat：DownloadQueue.audioFormatProvider 入队快照固化到 QueueItem.audioFormatSnapshot
 * （持久化保留，防跨重启续传前后产物格式不一致）→ 管道透传引擎（替代 "mp3" 字面量）；
 * - quality：SingleDownloadPipeline.qualityProvider 透传引擎（仅 YtdlpEngine VIDEO 消费，假引擎只记录）；
 * - maxConcurrency：构造注入 () -> Int，运行中变更只影响后续派发。
 *
 * 调度纪律同 DownloadQueueTest：存在未放行 Hang 时只用 runCurrent。
 */
class SettingsWiringTest {

    private fun tempRoot(): File =
        File(System.getProperty("java.io.tmpdir"), "sw-${System.nanoTime()}").apply { mkdirs() }

    private class Harness(
        root: File,
        scheduler: TestCoroutineScheduler,
        val maxConcurrency: () -> Int = { 2 },
        val audioFormat: suspend () -> String = { "mp3" },
        val quality: () -> String = { "max" },
    ) {
        val network = FakeNetworkMonitor()
        val env = FakeEnv(root)
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(scheduler))
        val persistence = QueuePersistence.create(File(root, "queue.preferences_pb"), scope)
        val pipeline = SingleDownloadPipeline(env, qualityProvider = quality)
        val queue = DownloadQueue(
            persistence = persistence,
            network = network,
            runner = { item, hooks -> pipeline.runItem(item, hooks) },
            scope = scope,
            newTaskDir = { File(root, "td-${System.nanoTime()}").apply { mkdirs() } },
            startService = {},
            cancelDispatch = {},
            now = { 1000L },
            maxConcurrencyProvider = maxConcurrency,
            audioFormatProvider = audioFormat,
        )
    }

    private fun ytdlpUrl(n: Int) = "https://example.com/v/$n"

    private fun Harness.engine(url: String, block: FakeEngine.() -> Unit = {}): FakeEngine =
        FakeEngine().apply(block).also { env.register(url, it) }

    private suspend fun Harness.enqueue(url: String, modes: DownloadModes): String =
        (queue.enqueue(url, modes, null) as EnqueueResult.Enqueued).item.id

 // ---- audioFormat 接线 ----

    @Test fun `audioFormat 入队快照固化 透传引擎 持久化保留`() = runTest {
        val root = tempRoot(); val h = Harness(root, testScheduler, audioFormat = { "m4a" })
        try {
            val url = ytdlpUrl(1); val e = h.engine(url)
            val id = h.enqueue(url, DownloadModes(false, true, false))
            testScheduler.advanceUntilIdle()
            assertEquals("快照值透传引擎（替代 mp3 字面量）", listOf("m4a"), e.audioFormatsSeen)
            assertEquals("快照随项持久化（跨重启续传格式一致）",
                "m4a", h.persistence.load().terminal.first { it.id == id }.audioFormatSnapshot)
        } finally { h.scope.cancel(); root.deleteRecursively() }
    }

    @Test fun `audioFormat 默认提供者 mp3`() = runTest {
        val root = tempRoot(); val h = Harness(root, testScheduler)
        try {
            val url = ytdlpUrl(1); val e = h.engine(url)
            h.enqueue(url, DownloadModes(false, true, false))
            testScheduler.advanceUntilIdle()
            assertEquals(listOf("mp3"), e.audioFormatsSeen)
        } finally { h.scope.cancel(); root.deleteRecursively() }
    }

 // ---- quality 接线 ----

    @Test fun `qualityProvider 透传引擎 三模式均收到 引擎自择消费`() = runTest {
        val root = tempRoot(); val h = Harness(root, testScheduler, quality = { "1080p" })
        try {
            val url = ytdlpUrl(1); val e = h.engine(url)
            h.enqueue(url, DownloadModes(video = true, audio = true, cover = true))
            testScheduler.advanceUntilIdle()
            assertEquals(listOf(Mode.VIDEO, Mode.AUDIO, Mode.COVER), e.modesRequested)
            assertEquals("每次 download 均带当前画质档（是否消费由引擎决定）",
                listOf("1080p", "1080p", "1080p"), e.qualitySeen)
        } finally { h.scope.cancel(); root.deleteRecursively() }
    }

 // ---- maxConcurrency 接线 ----

    @Test fun `maxConcurrency 注入 1 槽 运行中变更只影响后续派发`() = runTest {
        val root = tempRoot()
        val slots = AtomicInteger(1)
        val h = Harness(root, testScheduler, maxConcurrency = { slots.get() })
        try {
            val gateA = CompletableDeferred<Unit>(); val gateB = CompletableDeferred<Unit>()
            val urlA = ytdlpUrl(1); val urlB = ytdlpUrl(2); val urlC = ytdlpUrl(3)
            h.engine(urlA) { behavior[Mode.AUDIO] = FakeBehavior.Hang(gateA) }
            h.engine(urlB) { behavior[Mode.AUDIO] = FakeBehavior.Hang(gateB) }
            h.engine(urlC)
            h.enqueue(urlA, DownloadModes(false, true, false))
            h.enqueue(urlB, DownloadModes(false, true, false))
            h.enqueue(urlC, DownloadModes(false, true, false))
            testScheduler.runCurrent() // 槽=1：仅 a 起跑，b/c 排队
            assertEquals(listOf(urlA), h.env.runOrder)
            assertEquals(QueueState.Running, h.queue.state.value.items.first { it.url == urlA }.state)
            slots.set(2) // 运行中变更：不影响已运行项，只改变后续派发门
            gateA.complete(Unit)
            testScheduler.advanceTimeBy(5) // a 完成 → 补位派发按新槽位连派 b、c
            val order = h.env.runOrder
            assertEquals("b、c 依新槽位派发（槽=1 时只派 b）", listOf(urlA, urlB, urlC), order)
            assertEquals(QueueState.Running, h.queue.state.value.items.first { it.url == urlB }.state)
            assertEquals(QueueState.Completed, h.queue.state.value.items.first { it.url == urlC }.state)
            gateB.complete(Unit)
            testScheduler.advanceTimeBy(5)
            assertEquals(QueueState.Completed, h.queue.state.value.items.first { it.url == urlB }.state)
        } finally { h.scope.cancel(); root.deleteRecursively() }
    }

    @Test fun `maxConcurrency 注入 3 槽 三项同跑`() = runTest {
        val root = tempRoot()
        val h = Harness(root, testScheduler, maxConcurrency = { 3 })
        try {
            val gate = CompletableDeferred<Unit>()
            val urls = (1..3).map { ytdlpUrl(it) }
            urls.forEach { u -> h.engine(u) { behavior[Mode.AUDIO] = FakeBehavior.Hang(gate) } }
            urls.forEach { u -> h.enqueue(u, DownloadModes(false, true, false)) }
            testScheduler.runCurrent()
            assertEquals("三槽同跑", urls, h.env.runOrder)
            gate.complete(Unit)
            testScheduler.advanceUntilIdle()
            urls.forEach { u ->
                assertEquals(QueueState.Completed, h.queue.state.value.items.first { it.url == u }.state)
            }
        } finally { h.scope.cancel(); root.deleteRecursively() }
    }
}
