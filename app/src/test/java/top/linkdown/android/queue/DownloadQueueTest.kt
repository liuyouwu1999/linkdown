package top.linkdown.android.queue

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import top.linkdown.android.engine.EngineException
import top.linkdown.android.engine.Mode
import top.linkdown.android.pipeline.DownloadModes
import top.linkdown.android.pipeline.SingleDownloadPipeline
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger

/**
 *路由/折叠与调度状态机单测（假引擎驱动）。。
 * 逐格：FIFO / 并发 2 槽位 / 派发门四条件 / 暂停×断网叠加 / 去重 / 取消三态（Cancelling UI 子态、
 * 防连点、双侧派发缝）/ WaitingNetwork 两向+恢复重跑 / 重试跳过 completedModes（写侧：持久化+删临时产物）/
 * 入队折叠 Qishui[VIDEO,AUDIO]→AUDIO / probe 摘要占位与回写 / cookie 快照接线 / 杀进程恢复自动重派发。
 *
 * 调度纪律：FakeBehavior.Hang 的等待轮询用 delay(1) 自重排——存在未放行 Hang 时禁止
 * advanceUntilIdle（虚拟时钟会永续自旋），只用 runCurrent；gate 放行且轮询必退出后方可 advanceUntilIdle。
 */
class DownloadQueueTest {

 // ---- 脚手架 ----

    private fun tempRoot(): File =
        File(System.getProperty("java.io.tmpdir"), "dq-${System.nanoTime()}").apply { mkdirs() }

    private class Harness(root: File, scheduler: TestCoroutineScheduler) {
        val network = FakeNetworkMonitor()
        val env = FakeEnv(root)
        val cancelLog = ArrayList<String>() // cancelDispatch 缝记录（真实现=EngineRegistry.cancel 双侧派发）
        val startLog = AtomicInteger(0) // startService 缝记录（enqueue 唯一 FGS 触发点）
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
            cancelDispatch = { id -> cancelLog.add(id) },
            now = { tick++ },
        )
    }

    private fun qishuiUrl(n: Int) = "https://qishui.douyin.com/s/abc$n"
    private fun ytdlpUrl(n: Int) = "https://example.com/v/$n"

 /** 注册引擎并返回（默认全模式成功）。 */
    private fun Harness.engine(url: String, block: FakeEngine.() -> Unit = {}): FakeEngine =
        FakeEngine().apply(block).also { env.register(url, it) }

    private fun QueueUiState.item(id: String): QueueItem =
        items.firstOrNull { it.id == id } ?: error("item $id not in state: $items")

    private suspend fun Harness.enqueue(url: String, modes: DownloadModes, sessdata: String? = null): String =
        (queue.enqueue(url, modes, sessdata) as EnqueueResult.Enqueued).item.id

 // ---- 派发门与槽位 ----

    @Test fun `FIFO 按 createdAt 顺序派发`() = runTest {
        val root = tempRoot(); val h = Harness(root, testScheduler)
        try {
            val urls = listOf("a", "b", "c", "d").map { ytdlpUrl(it[0].code) }
            urls.forEach { u -> h.engine(u) }
            val ids = urls.map { h.enqueue(it, DownloadModes(false, true, false)) }
            testScheduler.advanceUntilIdle()
            assertEquals(urls, h.env.runOrder) // 起跑顺序=入队顺序
            h.queue.state.value.items.filter { it.id in ids }.forEach { assertEquals(QueueState.Completed, it.state) }
        } finally { h.scope.cancel(); root.deleteRecursively() }
    }

    @Test fun `并发 2 槽位：第三个排队 完成一个即补位`() = runTest {
        val root = tempRoot(); val h = Harness(root, testScheduler)
        try {
            val gateA = CompletableDeferred<Unit>(); val gateB = CompletableDeferred<Unit>()
            val urlA = ytdlpUrl(1); val urlB = ytdlpUrl(2); val urlC = ytdlpUrl(3)
            h.engine(urlA) { behavior[Mode.AUDIO] = FakeBehavior.Hang(gateA) }
            h.engine(urlB) { behavior[Mode.AUDIO] = FakeBehavior.Hang(gateB) }
            h.engine(urlC)
            val idA = h.enqueue(urlA, DownloadModes(false, true, false))
            val idB = h.enqueue(urlB, DownloadModes(false, true, false))
            val idC = h.enqueue(urlC, DownloadModes(false, true, false))
            testScheduler.runCurrent() // a/b 占满 2 槽，c 不得起跑
            assertEquals(QueueState.Running, h.queue.state.value.item(idA).state)
            assertEquals(QueueState.Running, h.queue.state.value.item(idB).state)
            assertEquals(QueueState.Queued, h.queue.state.value.item(idC).state)
            assertEquals(listOf(urlA, urlB), h.env.runOrder)
            gateA.complete(Unit)
            testScheduler.advanceTimeBy(5) // Hang 轮询在 delay(1) 上自重排：需虚拟时钟推进一格才观察到放行
            assertEquals(QueueState.Completed, h.queue.state.value.item(idA).state)
            assertEquals(QueueState.Completed, h.queue.state.value.item(idC).state)
            assertEquals(QueueState.Running, h.queue.state.value.item(idB).state)
            assertEquals(listOf(urlA, urlB, urlC), h.env.runOrder)
            gateB.complete(Unit)
            testScheduler.advanceTimeBy(5)
            assertEquals(QueueState.Completed, h.queue.state.value.item(idB).state)
        } finally { h.scope.cancel(); root.deleteRecursively() }
    }

    @Test fun `派发门-暂停：不派发 解除后派发`() = runTest {
        val root = tempRoot(); val h = Harness(root, testScheduler)
        try {
            val url = ytdlpUrl(1); h.engine(url)
            h.queue.setPaused(true)
            val id = h.enqueue(url, DownloadModes(false, true, false))
            testScheduler.runCurrent()
            assertEquals(QueueState.Queued, h.queue.state.value.item(id).state)
            assertTrue(h.queue.state.value.paused)
            h.queue.setPaused(false)
            testScheduler.advanceUntilIdle()
            assertEquals(QueueState.Completed, h.queue.state.value.item(id).state)
        } finally { h.scope.cancel(); root.deleteRecursively() }
    }

    @Test fun `派发门-断网：不派发 网络恢复自动派发`() = runTest {
        val root = tempRoot(); val h = Harness(root, testScheduler)
        try {
            h.network.goOffline()
            val url = ytdlpUrl(1); h.engine(url)
            val id = h.enqueue(url, DownloadModes(false, true, false))
            testScheduler.runCurrent()
            assertEquals(QueueState.Queued, h.queue.state.value.item(id).state)
            h.network.goOnline() // 沿真实 onRestored 回调路径
            testScheduler.advanceUntilIdle()
            assertEquals(QueueState.Completed, h.queue.state.value.item(id).state)
        } finally { h.scope.cancel(); root.deleteRecursively() }
    }

    @Test fun `暂停优先于断网恢复：暂停下网络恢复不派发`() = runTest {
        val root = tempRoot(); val h = Harness(root, testScheduler)
        try {
            h.queue.setPaused(true)
            h.network.goOffline()
            val url = ytdlpUrl(1); h.engine(url)
            val id = h.enqueue(url, DownloadModes(false, true, false))
            testScheduler.runCurrent()
            assertEquals(QueueState.Queued, h.queue.state.value.item(id).state)
            h.network.goOnline()
            testScheduler.runCurrent()
            assertEquals("暂停下网络恢复不得派发", QueueState.Queued, h.queue.state.value.item(id).state)
            h.queue.setPaused(false)
            testScheduler.advanceUntilIdle()
            assertEquals(QueueState.Completed, h.queue.state.value.item(id).state)
        } finally { h.scope.cancel(); root.deleteRecursively() }
    }

    @Test fun `暂停不禁取消：paused 下 Queued 可取消`() = runTest {
        val root = tempRoot(); val h = Harness(root, testScheduler)
        try {
            h.queue.setPaused(true)
            val url = ytdlpUrl(1); h.engine(url)
            val id = h.enqueue(url, DownloadModes(false, true, false))
            testScheduler.runCurrent()
            h.queue.cancel(id)
            testScheduler.runCurrent()
            assertEquals(QueueState.Cancelled, h.queue.state.value.item(id).state)
            h.queue.setPaused(false)
            testScheduler.advanceUntilIdle()
            assertEquals("已取消项不得被派发", QueueState.Cancelled, h.queue.state.value.item(id).state)
            assertTrue("取消非物理移除（仍在列表）", h.queue.state.value.items.any { it.id == id })
            assertTrue("取消项不经引擎", h.env.runOrder.isEmpty())
        } finally { h.scope.cancel(); root.deleteRecursively() }
    }

 // ---- 去重与 FGS 触发缝 ----

    @Test fun `去重 URL 字面加 modes 一致拒绝 不同 modes 放行 终态后可再入队`() = runTest {
        val root = tempRoot(); val h = Harness(root, testScheduler)
        try {
            val url = ytdlpUrl(1)
            h.engine(url); h.engine(ytdlpUrl(2))
            val modes = DownloadModes(video = true, audio = false, cover = false)
            assertTrue(h.queue.enqueue(url, modes, null) is EnqueueResult.Enqueued)
            assertEquals("重复入队拒绝", EnqueueResult.Duplicate, h.queue.enqueue(url, modes, null))
            assertTrue("同 URL 不同 modes 放行", h.queue.enqueue(url, DownloadModes(false, true, false), null) is EnqueueResult.Enqueued)
            assertTrue("不同 URL 同 modes 放行", h.queue.enqueue(ytdlpUrl(2), modes, null) is EnqueueResult.Enqueued)
 // FGS 唯一触发点：仅成功入队触发，Duplicate 不触发
            assertEquals(3, h.startLog.get())
            testScheduler.advanceUntilIdle()
 // 全部终态后同 URL 同 modes 可再次入队（去重只对活跃项）
            assertTrue(h.queue.enqueue(url, modes, null) is EnqueueResult.Enqueued)
            assertEquals(4, h.startLog.get())
        } finally { h.scope.cancel(); root.deleteRecursively() }
    }

 // ---- 取消三态 ----

    @Test fun `取消三态 Queued 直达 Cancelled 且不派发`() = runTest {
        val root = tempRoot(); val h = Harness(root, testScheduler)
        try {
            val gateA = CompletableDeferred<Unit>(); val gateC = CompletableDeferred<Unit>()
            val urlA = ytdlpUrl(1); val urlB = ytdlpUrl(2); val urlC = ytdlpUrl(3)
            h.engine(urlA) { behavior[Mode.AUDIO] = FakeBehavior.Hang(gateA) }
            h.engine(urlB)
            h.engine(urlC) { behavior[Mode.AUDIO] = FakeBehavior.Hang(gateC) }
            val idA = h.enqueue(urlA, DownloadModes(false, true, false))
            val idC = h.enqueue(urlC, DownloadModes(false, true, false))
            val idB = h.enqueue(urlB, DownloadModes(false, true, false))
            testScheduler.runCurrent() // a/c 占满 2 槽，b 排队
            assertEquals(QueueState.Running, h.queue.state.value.item(idA).state)
            assertEquals(QueueState.Queued, h.queue.state.value.item(idB).state)
            h.queue.cancel(idB) // b 仍在排队
            testScheduler.runCurrent()
            assertEquals(QueueState.Cancelled, h.queue.state.value.item(idB).state)
            assertFalse("Queued 取消不经引擎", h.env.runOrder.contains(urlB))
            gateA.complete(Unit); gateC.complete(Unit)
            testScheduler.advanceUntilIdle()
            assertEquals("已取消项不被补位派发", QueueState.Cancelled, h.queue.state.value.item(idB).state)
            assertEquals(QueueState.Completed, h.queue.state.value.item(idA).state)
            assertEquals(QueueState.Completed, h.queue.state.value.item(idC).state)
        } finally { h.scope.cancel(); root.deleteRecursively() }
    }

    @Test fun `取消三态 Running 经 Cancelling 子态 双侧派发防连点 part 保留`() = runTest {
        val root = tempRoot(); val h = Harness(root, testScheduler)
        try {
            val gate = CompletableDeferred<Unit>()
            val url = ytdlpUrl(1)
            h.engine(url) { behavior[Mode.AUDIO] = FakeBehavior.Hang(gate) }
            val id = h.enqueue(url, DownloadModes(false, true, false))
            testScheduler.runCurrent()
            assertEquals(QueueState.Running, h.queue.state.value.item(id).state)
            h.queue.cancel(id)
            testScheduler.runCurrent()
            val it = h.queue.state.value.item(id)
            assertEquals("Running→Cancelling 为 Running 的 UI 子态", QueueState.Running, it.state)
            assertTrue(it.cancelling)
            assertEquals("cancel 双侧派发缝被调（真实现=EngineRegistry.cancel）", listOf(id), h.cancelLog)
            h.queue.cancel(id) // 防连点：不重复派发
            assertEquals(1, h.cancelLog.size)
            gate.complete(Unit)
            testScheduler.advanceUntilIdle()
            val done = h.queue.state.value.item(id)
            assertEquals(QueueState.Cancelled, done.state)
            assertFalse(done.cancelling)
 // .part 保留：引擎已写出的临时产物不删（spec：Cancelled 保留 taskDir）
            assertTrue(File(done.taskDir).listFiles().orEmpty().isNotEmpty())
        } finally { h.scope.cancel(); root.deleteRecursively() }
    }

 // ---- WaitingNetwork 两向判定 + 恢复重跑 ----

    @Test fun `断网期间异常进 WaitingNetwork 恢复后回队列重跑未完成模式`() = runTest {
        val root = tempRoot(); val h = Harness(root, testScheduler)
        try {
            val gate = CompletableDeferred<Unit>()
            val url = ytdlpUrl(1)
            val e1 = h.engine(url) {
                behavior[Mode.AUDIO] = FakeBehavior.Hang(gate, errAfterGate = IOException("boom while offline"))
            }
            val id = h.enqueue(url, DownloadModes(false, true, false))
            testScheduler.runCurrent()
            assertEquals(QueueState.Running, h.queue.state.value.item(id).state)
            h.network.goOffline() // 失败发生时断网
            gate.complete(Unit)
            testScheduler.advanceUntilIdle()
            val waiting = h.queue.state.value.item(id)
            assertEquals("引擎异常×断网 → WaitingNetwork", QueueState.WaitingNetwork, waiting.state)
            assertEquals("错误人话随等待项保留（ErrorMapper 兜底文案）", "下载失败：boom while offline", waiting.error)
            assertTrue("等待项持久化为活跃项（非终态）",
                h.persistence.load().items.any { it.id == id && it.state == QueueState.WaitingNetwork })
 // 恢复：回队列重跑（新引擎行为成功）→ 重走两段式 → Completed
            val e2 = h.engine(url)
            h.network.goOnline()
            testScheduler.advanceUntilIdle()
            assertEquals(QueueState.Completed, h.queue.state.value.item(id).state)
            assertEquals("重跑=重新 probe（两段式重走）", 1, e2.probesRequested.size)
            assertEquals(listOf(Mode.AUDIO), e2.modesRequested)
            assertEquals(listOf(url, url), h.env.runOrder)
            assertTrue(h.persistence.load().terminal.any { it.id == id && it.state == QueueState.Completed })
            assertFalse(e1.probesRequested.isEmpty())
        } finally { h.scope.cancel(); root.deleteRecursively() }
    }

    @Test fun `有网异常直接 Failed 终态且 taskDir 保留`() = runTest {
        val root = tempRoot(); val h = Harness(root, testScheduler)
        try {
            val url = ytdlpUrl(1)
            h.engine(url) {
                behavior[Mode.AUDIO] = FakeBehavior.Fail(EngineException("抖音图集暂不支持"))
            }
            val id = h.enqueue(url, DownloadModes(false, true, false))
            testScheduler.advanceUntilIdle()
            val failed = h.queue.state.value.item(id)
            assertEquals("引擎异常×有网 → Failed", QueueState.Failed, failed.state)
            assertEquals("抖音图集暂不支持", failed.error)
            assertTrue("失败保留 taskDir（重试复用）", File(failed.taskDir).listFiles().orEmpty().isNotEmpty())
            assertTrue(h.persistence.load().terminal.any { it.id == id && it.state == QueueState.Failed })
        } finally { h.scope.cancel(); root.deleteRecursively() }
    }

 // ---- 终态记录删除（后厨清账）：removeTerminal 只删台账不删货 ----

    @Test fun `删除终态记录 Failed 从终态表移除且持久化 taskDir 不动`() = runTest {
        val root = tempRoot(); val h = Harness(root, testScheduler)
        try {
            val url = ytdlpUrl(1)
            h.engine(url) {
                behavior[Mode.AUDIO] = FakeBehavior.Fail(EngineException("抖音图集暂不支持"))
            }
            val id = h.enqueue(url, DownloadModes(false, true, false))
            testScheduler.advanceUntilIdle()
            assertEquals(QueueState.Failed, h.queue.state.value.item(id).state)
            val taskDir = File(h.queue.state.value.item(id).taskDir)

            h.queue.removeTerminal(id)
            assertTrue("删除后不出现在状态流", h.queue.state.value.items.none { it.id == id })
            val snap = h.persistence.load()
            assertTrue("删除持久化（活跃表无）", snap.items.none { it.id == id })
            assertTrue("删除持久化（终态表无）", snap.terminal.none { it.id == id })
            assertTrue("只删台账不删货：taskDir 保留", taskDir.exists() && taskDir.listFiles().orEmpty().isNotEmpty())
        } finally { h.scope.cancel(); root.deleteRecursively() }
    }

    @Test fun `删除非终态或不存在 id 无操作`() = runTest {
        val root = tempRoot(); val h = Harness(root, testScheduler)
        try {
            val gate = CompletableDeferred<Unit>()
            val url = ytdlpUrl(1)
            h.engine(url) { behavior[Mode.AUDIO] = FakeBehavior.Hang(gate) }
            val id = h.enqueue(url, DownloadModes(false, true, false))
            testScheduler.runCurrent() // Running 占槽（Hang 未放行禁 advanceUntilIdle）
            h.queue.removeTerminal(id) // 活跃项不在台账，不得被删
            assertEquals(QueueState.Running, h.queue.state.value.item(id).state)
            h.queue.removeTerminal("不存在") // 无操作不抛
            gate.complete(Unit)
            testScheduler.advanceUntilIdle()
            assertEquals(QueueState.Completed, h.queue.state.value.item(id).state)
        } finally { h.scope.cancel(); root.deleteRecursively() }
    }

 // ---- 失败重试：completedModes 保留=跳过已完成模式（P0 写侧） ----

    @Test fun `重试新 runId 跳过已完成模式 不重跑不重复入库 复用 taskDir`() = runTest {
        val root = tempRoot(); val h = Harness(root, testScheduler)
        try {
            val url = ytdlpUrl(1)
            val e = h.engine(url) {
                behavior[Mode.VIDEO] = FakeBehavior.Complete
                behavior[Mode.AUDIO] = FakeBehavior.Fail(EngineException("音频流断了"))
            }
            val id1 = h.enqueue(url, DownloadModes(video = true, audio = true, cover = false))
            testScheduler.advanceUntilIdle()
            assertEquals(listOf(Mode.VIDEO, Mode.AUDIO), e.modesRequested)
            val failed = h.queue.state.value.item(id1)
            assertEquals(QueueState.Failed, failed.state)
 // P0 写侧：VIDEO ingest 成功 → completedModes 即时持久化 + 该模式临时产物已删
            assertEquals(setOf(Mode.VIDEO), failed.completedModes)
            assertTrue(h.persistence.load().terminal.first { it.id == id1 }.completedModes.contains(Mode.VIDEO))
            assertFalse("已完成模式临时产物须删（防 already been downloaded）", File(failed.taskDir, "out-video.bin").exists())
            assertTrue(File(failed.taskDir, "out-audio.bin").exists())
            assertEquals("video 仅入库一次", 1, h.env.ingested.count { it.second == "out-video.bin" })

 // 重试：新 runId、completedModes 保留、taskDir 复用（重试前修复音频流故障=第二轮可成功）
            e.behavior[Mode.AUDIO] = FakeBehavior.Complete
            val retried = h.queue.retry(id1)!!
            val id2 = retried.id
            assertNotEquals(id1, id2)
            val it2 = h.queue.state.value.item(id2)
            assertEquals(setOf(Mode.VIDEO), it2.completedModes)
            assertEquals(failed.taskDir, it2.taskDir)
            testScheduler.advanceUntilIdle()
            assertEquals("重试只补未完成模式（VIDEO 不重跑=不报 already downloaded）",
                listOf(Mode.VIDEO, Mode.AUDIO, Mode.AUDIO), e.modesRequested)
            val done = h.queue.state.value.item(id2)
            assertEquals(QueueState.Completed, done.state)
            assertEquals(setOf(Mode.VIDEO, Mode.AUDIO), done.completedModes)
            assertEquals("video 全程仅入库一次", 1, h.env.ingested.count { it.second == "out-video.bin" })
            assertEquals("旧 Failed 项留终态列表", QueueState.Failed, h.queue.state.value.item(id1).state)
        } finally { h.scope.cancel(); root.deleteRecursively() }
    }

    @Test fun `重试仅限 Failed 且不存在的 runId 返回 null`() = runTest {
        val root = tempRoot(); val h = Harness(root, testScheduler)
        try {
            val url = ytdlpUrl(1); h.engine(url)
            val id = h.enqueue(url, DownloadModes(false, true, false))
            testScheduler.advanceUntilIdle()
            assertNull("Completed 不可重试", h.queue.retry(id))
            assertNull(h.queue.retry("no-such-run"))
        } finally { h.scope.cancel(); root.deleteRecursively() }
    }

 // ---- 入队折叠（设计 矩阵） ----

    @Test fun `入队折叠 Qishui VIDEO AUDIO 折为单次 AUDIO 仅封面折 COVER`() = runTest {
        val root = tempRoot(); val h = Harness(root, testScheduler)
        try {
            val u1 = qishuiUrl(1); val e1 = h.engine(u1)
            val id1 = h.enqueue(u1, DownloadModes(video = true, audio = true, cover = false))
            val u2 = qishuiUrl(2); val e2 = h.engine(u2)
            val id2 = h.enqueue(u2, DownloadModes(video = false, audio = false, cover = true))
            testScheduler.advanceUntilIdle()
            assertEquals(listOf(Mode.AUDIO), e1.modesRequested) // [VIDEO,AUDIO]→AUDIO
            assertEquals(setOf(Mode.AUDIO), h.queue.state.value.item(id1).completedModes)
            assertEquals(listOf(Mode.COVER), e2.modesRequested) // 仅封面 → 仅封面
            assertEquals(setOf(Mode.COVER), h.queue.state.value.item(id2).completedModes)
        } finally { h.scope.cancel(); root.deleteRecursively() }
    }

 // ---- probe 摘要 / cookie 快照 / 成功清理 / 写时机 ----

    @Test fun `probe 前 title 占位为链接 完成后回写摘要`() = runTest {
        val root = tempRoot(); val h = Harness(root, testScheduler)
        try {
            val url = ytdlpUrl(1); h.engine(url)
            val r = h.queue.enqueue(url, DownloadModes(false, true, false), null) as EnqueueResult.Enqueued
            assertEquals("probe 前占位=链接本身", url, r.item.title)
            assertNull(r.item.coverUrl); assertNull(r.item.probePlatform); assertNull(r.item.durationSec)
            testScheduler.advanceUntilIdle()
            val done = h.queue.state.value.item(r.item.id)
            assertEquals("测试标题", done.title)
            assertEquals("http://cover/1.jpg", done.coverUrl)
            assertEquals("汽水", done.probePlatform)
            assertEquals(183.5, done.durationSec!!, 0.0)
            assertEquals("测试标题", h.persistence.load().terminal.first { it.id == done.id }.title)
        } finally { h.scope.cancel(); root.deleteRecursively() }
    }

    @Test fun `sessdataSnapshot 接线 cookieFile 引擎收到快照`() = runTest {
        val root = tempRoot(); val h = Harness(root, testScheduler)
        try {
            val url = ytdlpUrl(1); val e = h.engine(url)
            h.enqueue(url, DownloadModes(false, true, false), "abc123")
            val url2 = ytdlpUrl(2); val e2 = h.engine(url2)
            h.enqueue(url2, DownloadModes(false, true, false), "  ") // 空白折叠为 null
            testScheduler.advanceUntilIdle()
            assertTrue(e.cookiesSeen.all { it == "SESSDATA=abc123" })
            assertTrue(e2.cookiesSeen.all { it == null })
        } finally { h.scope.cancel(); root.deleteRecursively() }
    }

    @Test fun `入队即写 派发迁移即写 全模式成功后清 taskDir`() = runTest {
        val root = tempRoot(); val h = Harness(root, testScheduler)
        try {
            val url = ytdlpUrl(1)
            val gate = CompletableDeferred<Unit>()
            h.engine(url) { behavior[Mode.AUDIO] = FakeBehavior.Hang(gate) }
            val id = h.enqueue(url, DownloadModes(false, true, false))
 // 入队即写（Hang 引擎兜底：该项不可能提前终态；派发与入队同锁交错时快照可为 Queued/Running）
            val justEnqueued = h.persistence.load().items.first { it.id == id }.state
            assertTrue("入队即写（got $justEnqueued）", justEnqueued == QueueState.Queued || justEnqueued == QueueState.Running)
            testScheduler.runCurrent()
            assertEquals("派发迁移即写（Running 可从存储读回）",
                QueueState.Running, h.persistence.load().items.first { it.id == id }.state)
            gate.complete(Unit)
            testScheduler.advanceUntilIdle()
            val done = h.queue.state.value.item(id)
            assertEquals(QueueState.Completed, done.state)
            assertFalse("成功清临时目录", File(done.taskDir).exists())
            assertTrue(h.persistence.load().terminal.any { it.id == id && it.state == QueueState.Completed })
        } finally { h.scope.cancel(); root.deleteRecursively() }
    }

 // ---- 杀进程恢复（队列级） ----

    @Test fun `杀进程模拟 重建队列 未完成项回 Queued 自动重派发重走两段式`() = runTest {
        val root = tempRoot()
        val h1 = Harness(root, testScheduler)
        val idKill: String
        val urlDone = ytdlpUrl(1)
        val urlKill = ytdlpUrl(2)
        try {
            h1.engine(urlDone)
            val gate = CompletableDeferred<Unit>()
            h1.engine(urlKill) { behavior[Mode.AUDIO] = FakeBehavior.Hang(gate) }
            h1.enqueue(urlDone, DownloadModes(false, true, false))
            idKill = h1.enqueue(urlKill, DownloadModes(false, true, false), "sess-1")
            testScheduler.runCurrent() // done 跑完；kill 挂在下载（Running 已持久化）
            assertEquals(QueueState.Completed, h1.queue.state.value.items.first { it.url == urlDone }.state)
            assertEquals(QueueState.Running, h1.persistence.load().items.first { it.id == idKill }.state)
        } finally { h1.scope.cancel() } // = 杀进程（DataStore 随 scope 关闭）

        val h2 = Harness(root, testScheduler) // 新进程：同文件重建实例
        try {
            val e2 = h2.engine(urlKill) // 新引擎全成功
            testScheduler.advanceUntilIdle() // restore → 回 Queued → 自动重派发
            val item = h2.queue.state.value.item(idKill)
            assertEquals("未完成项恢复后自动续传完成", QueueState.Completed, item.state)
            assertEquals("重走两段式：probe 重跑", 1, e2.probesRequested.size)
            assertEquals("已完成项不重跑", listOf(urlKill), h2.env.runOrder)
            assertTrue("sessdataSnapshot 恢复保留", item.sessdataSnapshot == "sess-1")
            assertTrue(h2.persistence.load().terminal.any { it.id == idKill && it.state == QueueState.Completed })
        } finally { h2.scope.cancel(); root.deleteRecursively() }
    }

    @Test fun `setPaused 立即发布 QueueUiState 不经 enqueue`() = runTest {
        val root = tempRoot(); val h = Harness(root, testScheduler)
        try {
            testScheduler.runCurrent() // restore 完成
            assertFalse(h.queue.state.value.paused)
            h.queue.setPaused(true) // 空队列：暂停状态必须立即可见（fix round 1 Important① 回归）
            assertTrue("空队列 setPaused 后 state.paused 滞留", h.queue.state.value.paused)
            h.queue.setPaused(false)
            assertFalse(h.queue.state.value.paused)
 // 只有 Running 时解除暂停同样立即可见（不经任何终态/派发发布兜底）
            val gate = CompletableDeferred<Unit>()
            val url = ytdlpUrl(1)
            h.engine(url) { behavior[Mode.AUDIO] = FakeBehavior.Hang(gate) }
            h.enqueue(url, DownloadModes(false, true, false))
            testScheduler.runCurrent()
            h.queue.setPaused(true)
            assertTrue("Running 存续时 setPaused 后 state.paused 滞留", h.queue.state.value.paused)
            gate.complete(Unit)
            testScheduler.advanceTimeBy(5)
            h.queue.setPaused(false)
            assertFalse(h.queue.state.value.paused)
        } finally { h.scope.cancel(); root.deleteRecursively() }
    }

    @Test fun `启动竞态 构造后立即 enqueue 上一进程快照不被空表覆盖`() = runTest {
        val root = tempRoot()
        val h1 = Harness(root, testScheduler)
        val urlOld = ytdlpUrl(1)
        try {
            h1.queue.setPaused(true) // 让 old 项持久化为 Queued 且不被派发
            testScheduler.runCurrent()
            h1.engine(urlOld)
            h1.enqueue(urlOld, DownloadModes(false, true, false), "keep-1")
            testScheduler.runCurrent()
            assertEquals(QueueState.Queued, h1.persistence.load().items.single().state)
        } finally { h1.scope.cancel() } // = 上一进程死亡（快照：Queued 项 + paused=true）

        val h2 = Harness(root, testScheduler) // 新进程：**不 advance，立即 enqueue**（抢在 restore 前）
        try {
            h2.engine(urlOld)
            val urlNew = ytdlpUrl(2)
            h2.engine(urlNew)
            val idNew = h2.enqueue(urlNew, DownloadModes(false, true, false), null)
            testScheduler.advanceUntilIdle() // restore 先于 enqueue 落盘（restored 门）
            val items = h2.queue.state.value.items
            assertTrue("上一进程快照项被空表覆盖丢失（Important② 回归）", items.any { it.url == urlOld })
            assertTrue("新入队项存活", items.any { it.url == urlNew })
            h2.queue.setPaused(false) // paused 随快照恢复，解除后两项续传
            testScheduler.advanceUntilIdle()
            val snap = h2.persistence.load()
            assertTrue("存储含新旧两项（未被覆盖）",
                snap.terminal.any { it.url == urlOld && it.sessdataSnapshot == "keep-1" } &&
                    snap.terminal.any { it.id == idNew })
        } finally { h2.scope.cancel(); root.deleteRecursively() }
    }

    @Test fun `杀进程模拟且 paused 保留 恢复后不自动派发`() = runTest {
        val root = tempRoot()
        val h1 = Harness(root, testScheduler)
        try {
            h1.queue.setPaused(true)
            val url = ytdlpUrl(9); h1.engine(url)
            h1.enqueue(url, DownloadModes(false, true, false))
            testScheduler.runCurrent()
        } finally { h1.scope.cancel() }
        val h2 = Harness(root, testScheduler)
        try {
            testScheduler.runCurrent() // restore
            assertTrue(h2.queue.state.value.paused)
            h2.engine(ytdlpUrl(9)) // 新进程引擎就绪
            testScheduler.runCurrent()
            assertEquals("paused 恢复后仍不派发", QueueState.Queued, h2.queue.state.value.items.single().state)
            h2.queue.setPaused(false)
            testScheduler.advanceUntilIdle()
            assertEquals(QueueState.Completed, h2.queue.state.value.items.single().state)
        } finally { h2.scope.cancel(); root.deleteRecursively() }
    }
}
