package top.linkdown.android.queue

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import top.linkdown.android.engine.EngineException
import top.linkdown.android.engine.Mode
import top.linkdown.android.engine.internal.ChildRef
import top.linkdown.android.pipeline.DownloadModes
import top.linkdown.android.pipeline.SingleDownloadPipeline
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger

/**
 * 父子聚合全矩阵（设计/§2.2，假引擎驱动）。
 * 逐格：归约穷举表（含 Exists 视同 Completed）/ enqueueGroup 单次持久化+单次拉服务 /
 * 父项不占槽不被跑 / 聚合计数增量维护 / 级联取消两条产品拍板断言（父卡取消含 Running/Cancelling 全
 * Cancelled 且 cancelledChildren+=n；重试失败子项复活同记录 id 不变 failedChildren− 再终态进度≤100%）/
 * GROUP 机制三条钉死（派发过滤/恢复跳过重置/执行 runId 解耦）/ 子项断网父组保持 Running /
 * 超限截断 200 / 排位豁免。
 *
 * 调度纪律同 DownloadQueueTest：存在未放行 Hang 时只用 runCurrent/advanceTimeBy（虚拟时钟会永续自旋），
 * gate 放行后方可 advanceUntilIdle。
 */
class DownloadQueueGroupTest {

 // ---- 脚手架 ----

    private fun tempRoot(): File =
        File(System.getProperty("java.io.tmpdir"), "dqg-${System.nanoTime()}").apply { mkdirs() }

 /** FakeDataStore：updateData 计数（enqueueGroup 单次持久化断言）+ 内存 preferences。 */
    private class CountingDataStore : DataStore<Preferences> {
        var editCount = 0
            private set
        private val state = MutableStateFlow(emptyPreferences())

        override val data: Flow<Preferences> = state

        override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences {
            editCount++
            state.value = transform(state.value)
            return state.value
        }
    }

    private class Harness(root: File, scheduler: TestCoroutineScheduler, store: DataStore<Preferences>? = null) {
        val network = FakeNetworkMonitor()
        val env = FakeEnv(root)
        val cancelLog = ArrayList<String>()
        val startLog = AtomicInteger(0)
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(scheduler))
        val persistence =
            if (store != null) QueuePersistence(store)
            else QueuePersistence.create(File(root, "queue.preferences_pb"), scope)
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

    private fun ytdlpUrl(n: Int) = "https://example.com/v/$n"
    private fun listUrl() = "https://example.com/playlist/42"
    private fun childRef(n: Int) = ChildRef(url = ytdlpUrl(n), title = "子项$n")

    private fun Harness.engine(url: String, block: FakeEngine.() -> Unit = {}): FakeEngine =
        FakeEngine().apply(block).also { env.register(url, it) }

    private fun QueueUiState.item(id: String): QueueItem =
        items.firstOrNull { it.id == id } ?: error("item $id not in state")

    private suspend fun Harness.enqueueGroup(
        vararg children: ChildRef,
        title: String = "测试列表",
    ): EnqueueResult.Enqueued =
        queue.enqueueGroup(title, listUrl(), DownloadModes(false, true, false), children.toList(), null)
            as EnqueueResult.Enqueued

 /** 纯函数表驱动用 GROUP 项构造。 */
    private fun group(
        total: Int,
        completed: Int = 0,
        failed: Int = 0,
        cancelled: Int = 0,
        exists: Int = 0,
    ) = QueueItem(
        id = "g", url = listUrl(), platform = "YTDLP",
        kind = QueueItemKind.GROUP,
        modes = DownloadModes(false, true, false),
        state = QueueState.Running,
        totalChildren = total, completedChildren = completed, failedChildren = failed,
        cancelledChildren = cancelled, existsChildren = exists,
        title = "列表", taskDir = "", createdAt = 0L,
    )

 // ---- 归约穷举表（纯函数逐格） ----

    @Test fun `归约穷举 全成与 Exists 视同完成 Partial Cancelled Running`() {
        assertEquals(QueueState.Completed, group(total = 2, completed = 2).deriveGroupState())
 // Exists 视同 Completed（设计）
        assertEquals(QueueState.Completed, group(total = 2, completed = 1, exists = 1).deriveGroupState())
        assertEquals(QueueState.Completed, group(total = 1, exists = 1).deriveGroupState())
 // failed>0 → Failed（Partial f/m），failed 优先于 cancelled
        assertEquals(QueueState.Failed, group(total = 2, completed = 1, failed = 1).deriveGroupState())
        assertEquals(QueueState.Failed, group(total = 3, exists = 1, failed = 1, cancelled = 1).deriveGroupState())
 // 无失败含 cancelled → Cancelled
        assertEquals(QueueState.Cancelled, group(total = 2, completed = 1, cancelled = 1).deriveGroupState())
        assertEquals(QueueState.Cancelled, group(total = 1, cancelled = 1).deriveGroupState())
 // 存在非终态子项 → Running（其余无论什么组合）
        assertEquals(QueueState.Running, group(total = 2, completed = 1).deriveGroupState())
        assertEquals(QueueState.Running, group(total = 4, completed = 1, failed = 1, cancelled = 1).deriveGroupState())
        assertEquals(QueueState.Running, group(total = 1).deriveGroupState())
 // 聚合进度：Exists 计入分子、total=0 兜底
        assertEquals(0.5f, group(total = 4, completed = 1, exists = 1).groupProgressFraction(), 1e-6f)
        assertEquals(0f, group(total = 0).groupProgressFraction(), 1e-6f)
    }

    @Test fun `子项终态计数增量纯函数 四终态各加对应计数`() {
        val g = group(total = 4)
        assertEquals(1, g.withChildTerminal(QueueState.Completed).completedChildren)
        assertEquals(1, g.withChildTerminal(QueueState.Exists).existsChildren)
        assertEquals(1, g.withChildTerminal(QueueState.Failed).failedChildren)
        assertEquals(1, g.withChildTerminal(QueueState.Cancelled).cancelledChildren)
        assertEquals("非终态不加计数", 4, g.withChildTerminal(QueueState.Running).totalChildren)
    }

 // ---- enqueueGroup：批量入队 / 单次持久化 / 单次拉服务 / 截断 ----

    @Test fun `enqueueGroup 父组子项一次入队 单次持久化单次拉服务 子项带父组标记`() = runTest {
        val root = tempRoot()
        val store = CountingDataStore()
        val h = Harness(root, testScheduler, store)
        try {
            h.network.goOffline() // 冻结派发：隔离「入队批写」本身（派发迁移写另计）
            testScheduler.runCurrent() // restore 完成
            val r = h.enqueueGroup(childRef(1), childRef(2), childRef(3))
            val parent = r.item
            assertEquals(QueueItemKind.GROUP, parent.kind)
            assertEquals("测试列表", parent.title)
            assertEquals(3, parent.totalChildren)
            assertEquals(QueueState.Running, parent.state)
 // 子项：带 parentGroupId、title 用枚举产物
            val children = h.queue.state.value.items.filter { it.parentGroupId == parent.id }
            assertEquals(3, children.size)
            assertEquals(listOf("子项1", "子项2", "子项3"), children.map { it.title })
            assertEquals(listOf(ytdlpUrl(1), ytdlpUrl(2), ytdlpUrl(3)), children.map { it.url })
 // 单次持久化：入队批写恰 1 次（快照已含父+子）
            assertEquals("enqueueGroup 入队批写必须恰 1 次（got ${store.editCount}）", 1, store.editCount)
            val snap = h.persistence.load()
            assertEquals(4, snap.items.size)
            assertNotNull(snap.items.first { it.kind == QueueItemKind.GROUP })
 // 单次拉服务：enqueue 唯一 FGS 触发点整批恰 1 次
            assertEquals(1, h.startLog.get())
        } finally { h.scope.cancel(); root.deleteRecursively() }
    }

    @Test fun `enqueueGroup 超限截断 200 父组 N 口径为实际子项数`() = runTest {
        val root = tempRoot(); val h = Harness(root, testScheduler)
        try {
            h.network.goOffline()
            testScheduler.runCurrent()
            val many = (1..250).map { childRef(it) }
            val r = h.enqueueGroup(*many.toTypedArray())
            assertEquals("父组一切 N 口径=实际子项数（≤200）", MAX_GROUP_CHILDREN, r.item.totalChildren)
            assertEquals(200, h.queue.state.value.items.count { it.parentGroupId == r.item.id })
        } finally { h.scope.cancel(); root.deleteRecursively() }
    }

    @Test fun `enqueueGroup 空子项回退单项入队`() = runTest {
        val root = tempRoot(); val h = Harness(root, testScheduler)
        try {
            val r = h.queue.enqueueGroup("空列表", listUrl(), DownloadModes(false, true, false), emptyList(), null)
            val item = (r as EnqueueResult.Enqueued).item
            assertEquals(QueueItemKind.SINGLE, item.kind)
        } finally { h.scope.cancel(); root.deleteRecursively() }
    }

 // ---- 机制①：父项不占槽不被当任务跑 ----

    @Test fun `父项不占槽不被当任务跑 子项跑完父组归约 Completed`() = runTest {
        val root = tempRoot(); val h = Harness(root, testScheduler)
        try {
            repeat(3) { n -> h.engine(ytdlpUrl(n + 1)) } // 只注册子项引擎：父项被误派发会在此抛错
            val r = h.enqueueGroup(childRef(1), childRef(2), childRef(3))
            val groupId = r.item.id
            testScheduler.advanceUntilIdle()
 // 父项 URL 不经引擎（不占槽不被跑）
            assertFalse("父项不得被派发", h.env.runOrder.contains(listUrl()))
            assertEquals(3, h.env.runOrder.size)
 // 子项全成 → 父组派生 Completed 进终态表
            val parent = h.queue.state.value.item(groupId)
            assertEquals(QueueState.Completed, parent.state)
            assertEquals(3, parent.completedChildren)
            assertEquals(3, parent.totalChildren)
            assertTrue(h.persistence.load().terminal.any { it.id == groupId && it.state == QueueState.Completed })
        } finally { h.scope.cancel(); root.deleteRecursively() }
    }

 // ---- 聚合计数增量维护 ----

    @Test fun `聚合计数增量 子项逐个终态 父组随派生态搬运`() = runTest {
        val root = tempRoot(); val h = Harness(root, testScheduler)
        try {
            val gate = CompletableDeferred<Unit>()
            h.engine(ytdlpUrl(1)) // 立即完成
            h.engine(ytdlpUrl(2)) { behavior[Mode.AUDIO] = FakeBehavior.Hang(gate) }
            val r = h.enqueueGroup(childRef(1), childRef(2))
            val groupId = r.item.id
            testScheduler.advanceTimeBy(5) // 子项1 完成、子项2 挂住
            var parent = h.queue.state.value.item(groupId)
            assertEquals("子项1 终态 → completedChildren 增量+1", 1, parent.completedChildren)
            assertEquals("仍有非终态子项 → Running（active 表）", QueueState.Running, parent.state)
            assertEquals(0.5f, parent.groupProgressFraction(), 1e-6f)
            gate.complete(Unit)
            testScheduler.advanceUntilIdle()
            parent = h.queue.state.value.item(groupId)
            assertEquals("全成 → Completed", QueueState.Completed, parent.state)
            assertEquals(2, parent.completedChildren)
        } finally { h.scope.cancel(); root.deleteRecursively() }
    }

    @Test fun `子项断网 WaitingNetwork 父组保持 Running 恢复后续传归约 Completed`() = runTest {
        val root = tempRoot(); val h = Harness(root, testScheduler)
        try {
            val gate = CompletableDeferred<Unit>()
            h.engine(ytdlpUrl(1)) { behavior[Mode.AUDIO] = FakeBehavior.Hang(gate, errAfterGate = IOException("offline boom")) }
            h.engine(ytdlpUrl(2))
            val r = h.enqueueGroup(childRef(1), childRef(2))
            val groupId = r.item.id
            testScheduler.runCurrent() // 两子项派发（子项1 挂住）
            h.network.goOffline() // 失败发生时断网
            gate.complete(Unit)
            testScheduler.advanceUntilIdle() // gate 已放行：安全
            assertEquals("引擎异常×断网 → 子项 WaitingNetwork", QueueState.WaitingNetwork, h.queue.state.value.items.first { it.url == ytdlpUrl(1) }.state)
            assertEquals("等待中子项非终态 → 父组不得 Partial/终态", QueueState.Running, h.queue.state.value.item(groupId).state)
            h.engine(ytdlpUrl(1)) // 修复
            h.network.goOnline()
            testScheduler.advanceUntilIdle()
            val parent = h.queue.state.value.item(groupId)
            assertEquals(QueueState.Completed, parent.state)
            assertEquals(2, parent.completedChildren)
        } finally { h.scope.cancel(); root.deleteRecursively() }
    }

 // ---- 拍板①：级联取消（父卡取消→未完成子项含 Running/Cancelling 全 Cancelled 且 cancelledChildren+=n） ----

    @Test fun `级联取消 父卡取消 Running 经 Cancelling 与 Queued 全 Cancelled cancelledChildren 加 n`() = runTest {
        val root = tempRoot(); val h = Harness(root, testScheduler)
        try {
            val gateA = CompletableDeferred<Unit>(); val gateB = CompletableDeferred<Unit>()
            h.engine(ytdlpUrl(1)) { behavior[Mode.AUDIO] = FakeBehavior.Hang(gateA) }
            h.engine(ytdlpUrl(2)) { behavior[Mode.AUDIO] = FakeBehavior.Hang(gateB) }
            h.engine(ytdlpUrl(3)) // 占第 3 位排队（2 槽满）
            val r = h.enqueueGroup(childRef(1), childRef(2), childRef(3))
            val groupId = r.item.id
            testScheduler.runCurrent()
            val children = h.queue.state.value.items.filter { it.parentGroupId == groupId }
            assertEquals(QueueState.Running, children[0].state)
            assertEquals(QueueState.Running, children[1].state)
            assertEquals(QueueState.Queued, children[2].state)

            h.queue.cancel(groupId) // 父卡取消 = 级联
            h.queue.cancel(groupId) // 防连点
            testScheduler.runCurrent()
            assertEquals("级联取消只派发一轮", 2, h.cancelLog.size)
            val after = h.queue.state.value.items.filter { it.parentGroupId == groupId }
            assertEquals("Running 子项 → Cancelling 子态（真取消）", listOf(true, true), after.take(2).map { it.cancelling })
            assertEquals("Queued 子项直达 Cancelled", QueueState.Cancelled, after[2].state)
            assertEquals("级联取消不入引擎（Queued 直达）", 2, h.env.runOrder.size)

            gateA.complete(Unit); gateB.complete(Unit)
            testScheduler.advanceTimeBy(5)
            testScheduler.advanceUntilIdle()
            val done = h.queue.state.value.items.filter { it.parentGroupId == groupId }
            assertEquals("未完成子项含 Running/Cancelling 全 Cancelled",
                listOf(QueueState.Cancelled, QueueState.Cancelled, QueueState.Cancelled), done.map { it.state })
            val parent = h.queue.state.value.item(groupId)
            assertEquals("cancelledChildren += 3", 3, parent.cancelledChildren)
            assertEquals("归约：无失败含 cancelled → Cancelled", QueueState.Cancelled, parent.state)
            assertTrue("父组终态化进终态表", h.persistence.load().terminal.any { it.id == groupId && it.state == QueueState.Cancelled })
        } finally { h.scope.cancel(); root.deleteRecursively() }
    }

    @Test fun `级联取消与单项取消 键同步 用执行 runId（重试复活后与 id 解耦）`() = runTest {
        val root = tempRoot(); val h = Harness(root, testScheduler)
        try {
 // 子项先失败（有网）→ 重试复活（execRunId 重新生成）→ 挂住 → 级联取消键=新执行 runId
            h.engine(ytdlpUrl(1)) { behavior[Mode.AUDIO] = FakeBehavior.Fail(EngineException("流断了")) }
            h.engine(ytdlpUrl(2))
            val r = h.enqueueGroup(childRef(1), childRef(2))
            testScheduler.advanceUntilIdle()
            assertEquals(QueueState.Failed, h.queue.state.value.item(r.item.id).state)
            val gate = CompletableDeferred<Unit>()
            h.engine(ytdlpUrl(1)) { behavior[Mode.AUDIO] = FakeBehavior.Hang(gate) }
            val revived = h.queue.retry(r.item.id)!!
            assertEquals(QueueItemKind.GROUP, revived.kind)
            testScheduler.runCurrent()
            val child = h.queue.state.value.items.first { it.url == ytdlpUrl(1) }
            assertEquals(QueueState.Running, child.state)
            val execId = child.execRunId
            assertTrue("复活子项带再生执行 runId", execId.isNotEmpty() && execId != child.id)
            h.cancelLog.clear()
            h.queue.cancel(revived.id) // 级联取消
            testScheduler.runCurrent()
            assertEquals("取消双侧派发键=执行 runId（EngineRegistry 键同步）", listOf(execId), h.cancelLog)
            assertTrue("取消旗标键=执行 runId", h.queue.state.value.item(revived.id).cancelling)
            gate.complete(Unit)
            testScheduler.advanceTimeBy(5)
            testScheduler.advanceUntilIdle()
            val parent = h.queue.state.value.item(r.item.id)
            assertEquals(QueueState.Cancelled, parent.state)
            assertEquals(1, parent.cancelledChildren)
        } finally { h.scope.cancel(); root.deleteRecursively() }
    }

 // ---- 终态记录删除：父组级联清台账（父+子全出终态表；已入库产物不动） ----

    @Test fun `删除父组 父与终态子项级联出账 已入库产物不动`() = runTest {
        val root = tempRoot(); val h = Harness(root, testScheduler)
        try {
            h.engine(ytdlpUrl(1)) { behavior[Mode.AUDIO] = FakeBehavior.Fail(EngineException("音频流断了")) }
            h.engine(ytdlpUrl(2))
            val groupId = h.enqueueGroup(childRef(1), childRef(2)).item.id
            testScheduler.advanceUntilIdle()
            assertEquals("failed>0 → Partial（派生 Failed）", QueueState.Failed, h.queue.state.value.item(groupId).state)

            h.queue.removeTerminal(groupId)
            assertTrue("父组出账", h.queue.state.value.items.none { it.id == groupId })
            assertTrue("终态子项级联出账",
                h.queue.state.value.items.none { it.parentGroupId == groupId })
            val snap = h.persistence.load()
            assertTrue("持久化同步清账（活跃表）",
                snap.items.none { it.id == groupId || it.parentGroupId == groupId })
            assertTrue("持久化同步清账（终态表）",
                snap.terminal.none { it.id == groupId || it.parentGroupId == groupId })
            assertEquals("已入库子项产物不动（ingest 不回滚）", 1, h.env.ingested.size)
        } finally { h.scope.cancel(); root.deleteRecursively() }
    }

    @Test fun `批量清账 单卡与父组混选含不存在id 一次移除`() = runTest {
        val root = tempRoot(); val h = Harness(root, testScheduler)
        try {
 // 父组（1 败 1 成 → Partial）+ 一个独立失败单卡
            h.engine(ytdlpUrl(1)) { behavior[Mode.AUDIO] = FakeBehavior.Fail(EngineException("音频流断了")) }
            h.engine(ytdlpUrl(2))
            val groupId = h.enqueueGroup(childRef(1), childRef(2)).item.id
            h.engine(ytdlpUrl(5)) { behavior[Mode.AUDIO] = FakeBehavior.Fail(EngineException("boom")) }
            val singleId = (h.queue.enqueue(ytdlpUrl(5), DownloadModes(false, true, false), null)
                as EnqueueResult.Enqueued).item.id
            testScheduler.advanceUntilIdle()
            assertEquals(QueueState.Failed, h.queue.state.value.item(groupId).state)
            assertEquals(QueueState.Failed, h.queue.state.value.item(singleId).state)

            h.queue.removeTerminals(listOf(singleId, groupId, "不存在的id"))
            assertEquals("单卡+父组+级联子项一次出账",
                0, h.queue.state.value.items.count { it.id == singleId || it.id == groupId || it.parentGroupId == groupId })
            val snap = h.persistence.load()
            assertTrue("活跃表清空", snap.items.isEmpty())
            assertTrue("终态表清空", snap.terminal.isEmpty())
        } finally { h.scope.cancel(); root.deleteRecursively() }
    }

 // ---- 拍板②：重试失败子项（复活同记录 id 不变回 Queued、failedChildren−、再终态+后进度≤100%） ----

    @Test fun `重试失败子项 复活同记录 failedChildren 减 再失败进度不超100 修复后归约 Completed`() = runTest {
        val root = tempRoot(); val h = Harness(root, testScheduler)
        try {
            h.engine(ytdlpUrl(1)) { behavior[Mode.AUDIO] = FakeBehavior.Fail(EngineException("音频流断了")) }
            h.engine(ytdlpUrl(2))
            val r = h.enqueueGroup(childRef(1), childRef(2))
            val groupId = r.item.id
            testScheduler.advanceUntilIdle()
            val parent = h.queue.state.value.item(groupId)
            assertEquals("failed>0 → Partial（派生 Failed）", QueueState.Failed, parent.state)
            assertEquals(1, parent.failedChildren)
            assertEquals(1, parent.completedChildren)
            val failedTaskDir = h.queue.state.value.items.first { it.url == ytdlpUrl(1) }.taskDir

 // 重试（离线冻结派发）：仅失败子项复活同记录回 Queued
            h.network.goOffline()
            val revived = h.queue.retry(groupId)!!
            assertEquals(groupId, revived.id) // 返回父组同记录
            val state = h.queue.state.value
            val child = state.items.first { it.url == ytdlpUrl(1) }
            assertEquals("复活同记录：子项 id 不变回 Queued", QueueState.Queued, child.state)
            assertEquals("taskDir 复用吃 .part", failedTaskDir, child.taskDir)
            assertEquals("failedChildren 增量反向 -n", 0, state.item(groupId).failedChildren)
            assertEquals("父组回 active 表 Running", QueueState.Running, state.item(groupId).state)
            assertTrue("父组不被执行（无执行键）", state.item(groupId).execRunId.isEmpty())
            assertTrue("复活子项带再生执行 runId（与 id 解耦）", child.execRunId.isNotEmpty() && child.execRunId != child.id)

 // 再失败：计数回加，归约与进度永不超 100%
            h.engine(ytdlpUrl(1)) { behavior[Mode.AUDIO] = FakeBehavior.Fail(EngineException("还是断")) }
            h.network.goOnline()
            testScheduler.advanceUntilIdle()
            val partial = h.queue.state.value.item(groupId)
            assertEquals(QueueState.Failed, partial.state)
            assertEquals("再终态 +1 后 failedChildren 回到 1", 1, partial.failedChildren)
            assertEquals("进度 ≤100%（不因复活翻倍）", 0.5f, partial.groupProgressFraction(), 1e-6f)
            assertTrue(
                "计数不变式：各计数和 ≤ total",
                partial.completedChildren + partial.existsChildren + partial.failedChildren + partial.cancelledChildren <= partial.totalChildren,
            )

 // 修复后重试 → Completed
            h.engine(ytdlpUrl(1))
            h.queue.retry(groupId)
            testScheduler.advanceUntilIdle()
            val done = h.queue.state.value.item(groupId)
            assertEquals(QueueState.Completed, done.state)
            assertEquals(2, done.completedChildren)
            assertEquals(0, done.failedChildren)
        } finally { h.scope.cancel(); root.deleteRecursively() }
    }

    @Test fun `重试仅限 Partial 父组 Running 或无失败子项返回 null`() = runTest {
        val root = tempRoot(); val h = Harness(root, testScheduler)
        try {
            val gate = CompletableDeferred<Unit>()
            h.engine(ytdlpUrl(1)) { behavior[Mode.AUDIO] = FakeBehavior.Hang(gate) }
            h.engine(ytdlpUrl(2))
            val r = h.enqueueGroup(childRef(1), childRef(2))
            testScheduler.runCurrent()
            assertNull("Running 父组不可重试", h.queue.retry(r.item.id))
            assertNull(h.queue.retry("no-such-group"))
            gate.complete(Unit)
            testScheduler.advanceUntilIdle()
            assertNull("Completed 父组不可重试", h.queue.retry(r.item.id))
        } finally { h.scope.cancel(); root.deleteRecursively() }
    }

 // ---- 机制②：恢复 GROUP 跳过 Queued 重置（派生态由计数推导）+ 子项续传 ----

    @Test fun `杀进程恢复 父组计数读回 子项 Queued 续传 跑完归约 Completed`() = runTest {
        val root = tempRoot()
        val h1 = Harness(root, testScheduler)
        val groupId: String
        val gate = CompletableDeferred<Unit>()
        try {
            h1.engine(ytdlpUrl(1)) // 立即完成
            h1.engine(ytdlpUrl(2)) { behavior[Mode.AUDIO] = FakeBehavior.Hang(gate) }
            groupId = h1.enqueueGroup(childRef(1), childRef(2)).item.id
            testScheduler.advanceTimeBy(5)
            assertEquals(1, h1.queue.state.value.item(groupId).completedChildren)
            assertEquals("Running 已持久化", QueueState.Running, h1.persistence.load().items.first { it.id == groupId }.state)
        } finally { h1.scope.cancel() } // = 杀进程

        val h2 = Harness(root, testScheduler)
        try {
            h2.network.goOffline() // 冻结派发：观察恢复中间态
            testScheduler.runCurrent() // restore 完成
            val restored = h2.queue.state.value.item(groupId)
            assertEquals("父组跳过 Queued 重置：派生态由计数推导", QueueState.Running, restored.state)
            assertEquals("父组计数从持久化读回（不靠子项明细归约）", 1, restored.completedChildren)
            assertEquals(2, restored.totalChildren)
            assertEquals("子项回 Queued 续传", QueueState.Queued, h2.queue.state.value.items.first { it.url == ytdlpUrl(2) }.state)
            val e2 = h2.engine(ytdlpUrl(2)) // 新进程引擎就绪（成功）
            h2.network.goOnline()
            testScheduler.advanceUntilIdle()
            val done = h2.queue.state.value.item(groupId)
            assertEquals(QueueState.Completed, done.state)
            assertEquals(2, done.completedChildren)
            assertEquals("子项续传重走两段式", 1, e2.probesRequested.size)
        } finally { h2.scope.cancel(); root.deleteRecursively() }
    }

    @Test fun `杀进程恢复 Partial 父组终态原样读回 可继续重试失败子项`() = runTest {
        val root = tempRoot()
        val h1 = Harness(root, testScheduler)
        val groupId: String
        try {
            h1.engine(ytdlpUrl(1)) { behavior[Mode.AUDIO] = FakeBehavior.Fail(EngineException("断流")) }
            h1.engine(ytdlpUrl(2))
            groupId = h1.enqueueGroup(childRef(1), childRef(2)).item.id
            testScheduler.advanceUntilIdle()
            assertEquals(QueueState.Failed, h1.queue.state.value.item(groupId).state)
        } finally { h1.scope.cancel() }

        val h2 = Harness(root, testScheduler)
        try {
            testScheduler.runCurrent()
            val restored = h2.queue.state.value.item(groupId)
            assertEquals("Partial 父组终态原样读回", QueueState.Failed, restored.state)
            assertEquals(1, restored.failedChildren)
            h2.engine(ytdlpUrl(1)) // 修复
            h2.queue.retry(groupId)
            testScheduler.advanceUntilIdle()
            assertEquals(QueueState.Completed, h2.queue.state.value.item(groupId).state)
        } finally { h2.scope.cancel(); root.deleteRecursively() }
    }

 // ---- 排位豁免 ----

    @Test fun `排位豁免 子项与父组不参与单项排位`() {
        val parent = group(total = 2).copy(id = "parent", state = QueueState.Running)
        val c1 = parent.copy(id = "c1", kind = QueueItemKind.SINGLE, parentGroupId = "parent", totalChildren = 0, state = QueueState.Running)
        val c2 = c1.copy(id = "c2", state = QueueState.Queued)
        val single = QueueItem(
            id = "s", url = ytdlpUrl(9), platform = "YTDLP",
            modes = DownloadModes(false, true, false), state = QueueState.Queued,
            title = "单", taskDir = "", createdAt = 9L,
        )
        val state = QueueUiState(listOf(parent, c1, c2, single), paused = false)
        assertEquals("子项不参与排位：单项排第 1 位（非第 4）", 1, state.activePositionOf("s"))
        assertEquals("父组查无排位 → 兜底 1", 1, state.activePositionOf("parent"))
    }

 // ---- 评审修复 Important：执行 runId 解耦贯穿引擎侧（runItem 全链路同键） ----

    @Test fun `复活子项取消 EngineRegistry 注册与注销键均为执行 runId 引擎收到同键`() = runTest {
        val root = tempRoot()
        val execId = "exec-uuid-1"
        try {
            val env = FakeEnv(root)
            val gate = CompletableDeferred<Unit>()
            val e = FakeEngine().apply { behavior[Mode.AUDIO] = FakeBehavior.Hang(gate) }
            env.register(ytdlpUrl(1), e)
            val pipeline = SingleDownloadPipeline(env)
            val item = QueueItem(
                id = "rec-1", url = ytdlpUrl(1), platform = "YTDLP",
                modes = DownloadModes(false, true, false), execRunId = execId,
                title = "子项", taskDir = File(root, "td").apply { mkdirs() }.absolutePath, createdAt = 0L,
            )
            val hooks = object : QueueRunHooks {
                override fun cancelRequested() = EngineRegistry.isCancelled(execId) // 真源同队列 hooks 键
                override fun shouldWaitOffline() = false
                override fun onLog(line: String) {}
                override fun onProgress(modeLabel: String, fraction: Float) {}
                override fun onIngested(label: String, uri: android.net.Uri?) {}
                override suspend fun onProbeDone(title: String, coverUrl: String?, platform: String, durationSec: Double?) {}
                override suspend fun onModeCompleted(mode: Mode, files: List<File>) {}
            }
            EngineRegistry.flagCancelledJvm(execId) // 模拟级联取消残留旗标（生产=cancel 双侧派发，JVM 缝仅旗标）
            val job = launch { runCatching { pipeline.runItem(item, hooks) } }
            testScheduler.runCurrent()
            assertTrue(
                "register 键=execRunId 清起跑前残留（挂在 item.id 键则旗标残留立即自取消）", job.isActive,
            )
            assertEquals("probe/download 收到执行 runId（fork 注册表同键）",
                listOf(execId, execId), e.runIdsSeen)
            EngineRegistry.flagCancelledJvm(execId) // 运行中取消（级联：生产侧 YtDlp.requestCancel 同打执行键）
            testScheduler.advanceUntilIdle() // Hang 轮询见旗标退出，run 终点注销
            assertTrue(job.isCompleted)
            assertFalse("注销键=execRunId（仍挂 item.id 键则旗标泄漏此处为 true）", EngineRegistry.isCancelled(execId))
            assertFalse("item.id 键全程未被注册表触碰", EngineRegistry.isCancelled("rec-1"))
        } finally {
            EngineRegistry.unregister(execId)
            EngineRegistry.unregister("rec-1")
            root.deleteRecursively()
        }
    }
}
