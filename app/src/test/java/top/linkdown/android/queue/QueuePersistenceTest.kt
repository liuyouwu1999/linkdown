package top.linkdown.android.queue

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.emptyPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import top.linkdown.android.engine.Mode
import top.linkdown.android.pipeline.DownloadModes
import java.io.File

/**
 * QueueItem + QueuePersistence 单测（设计）。
 * round-trip 字段全量（含 parentGroupId/sessdataSnapshot/completedModes）、paused 跨实例保留、
 * 最近终态项 FIFO≤50 淘汰、杀进程模拟=scope cancel 后同文件重建实例恢复全队列。
 */
class QueuePersistenceTest {

    private fun newItem(
        id: String = "rid-1",
        state: QueueState = QueueState.Queued,
        parentGroupId: String? = null,
        completedModes: Set<Mode> = emptySet(),
        error: String? = null,
        createdAt: Long = 1000L,
    ) = QueueItem(
        id = id,
        url = "https://qishui.douyin.com/s/iXXjp58L/",
        platform = "QISHUI",
        modes = DownloadModes(video = true, audio = true, cover = false),
        state = state,
        parentGroupId = parentGroupId,
        completedModes = completedModes,
        sessdataSnapshot = "SESSDATA=abc123",
        title = "https://qishui.douyin.com/s/iXXjp58L/", // probe 前占位=链接本身
        coverUrl = null,
        taskDir = "/tmp/td-$id",
        error = error,
        createdAt = createdAt,
    )

    private fun tempRoot(): File =
        File(System.getProperty("java.io.tmpdir"), "qp-${System.nanoTime()}").apply { mkdirs() }

 /** 建持久化实例（独立 scope，杀进程模拟时 cancel）。 */
    private fun create(root: File, scope: CoroutineScope): QueuePersistence =
        QueuePersistence.create(File(root, "queue.preferences_pb"), scope)

    @Test fun `字段全量 round trip 含 parentGroupId sessdataSnapshot completedModes`() = runTest {
        val root = tempRoot()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val p = create(root, scope)
            val item = newItem(
                id = "rid-full",
                state = QueueState.Running,
                parentGroupId = "group-42",
                completedModes = setOf(Mode.VIDEO, Mode.AUDIO),
                error = "下载失败：x",
                createdAt = 1729051200000L,
            ).copy(
                coverUrl = "http://cover/1.jpg",
                probePlatform = "汽水",
                durationSec = 183.5,
                audioFormatSnapshot = "m4a", // M4：入队快照字段持久化 round-trip
                cancelling = true,
            )
            p.save(listOf(item), emptyList(), paused = false)
            val snap = p.load()
            assertEquals(listOf(item), snap.items)
            assertEquals(emptyList<QueueItem>(), snap.terminal)
        } finally { scope.cancel(); root.deleteRecursively() }
    }

    @Test fun `内存态进度字段不持久化（读回复位默认）`() = runTest {
        val root = tempRoot()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val p = create(root, scope)
            p.save(listOf(newItem(id = "r").copy(progressFraction = 0.42f, modeLabel = "音频")), emptyList(), false)
            val read = p.load().items.single()
            assertEquals(0f, read.progressFraction)
            assertEquals("", read.modeLabel)
        } finally { scope.cancel(); root.deleteRecursively() }
    }

    @Test fun `paused 跨实例保留`() = runTest {
        val root = tempRoot()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            create(root, scope).save(emptyList(), emptyList(), paused = true)
 // 杀进程模拟：scope cancel + 同文件重建
            scope.cancel()
            val scope2 = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            try {
                val snap = create(root, scope2).load()
                assertTrue(snap.paused)
            } finally { scope2.cancel() }
        } finally { root.deleteRecursively() }
    }

    @Test fun `终态项 FIFO 上限 50 淘汰最旧`() = runTest {
        val root = tempRoot()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val p = create(root, scope)
            val terminal = (1..60).map {
                newItem(id = "t$it", state = QueueState.Completed, createdAt = it.toLong())
            }
            p.save(emptyList(), terminal, false)
            val read = p.load().terminal
            assertEquals(50, read.size)
            assertEquals("t11", read.first().id) // 最旧 10 条被淘出头
            assertEquals("t60", read.last().id)
        } finally { scope.cancel(); root.deleteRecursively() }
    }

    @Test fun `终态分配额截断 非子项50 子项300 淘汰最旧保序`() = runTest {
        val root = tempRoot()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val p = create(root, scope)
 // 块状布局：60 条非子项 + 320 条子项（子项带 parentGroupId）
            val terminal = (1..60).map { newItem(id = "s$it", state = QueueState.Completed, createdAt = it.toLong()) } +
                (1..320).map {
                    newItem(id = "c$it", state = QueueState.Completed, parentGroupId = "g", createdAt = 100L + it)
                }
            p.save(emptyList(), terminal, false)
            val read = p.load().terminal
            assertEquals("非子项 ≤50 + 子项 ≤300（独立配额豁免）", 350, read.size)
            assertEquals(50, read.count { it.parentGroupId == null })
            assertEquals(300, read.count { it.parentGroupId != null })
 // 淘汰最旧且两类相对顺序都保留：s11..s60 + c21..c320
            assertEquals("s11", read.first().id)
            assertEquals("s60", read[49].id)
            assertEquals("c21", read[50].id)
            assertEquals("c320", read.last().id)
            assertEquals((11..60).map { "s$it" }, read.take(50).map { it.id })
            assertEquals((21..320).map { "c$it" }, read.drop(50).map { it.id })
        } finally { scope.cancel(); root.deleteRecursively() }
    }

    @Test fun `GROUP 字段 round trip kind 五计数 执行 runId`() = runTest {
        val root = tempRoot()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val p = create(root, scope)
            val parent = newItem(id = "g1", state = QueueState.Running).copy(
                kind = QueueItemKind.GROUP,
                totalChildren = 200,
                completedChildren = 120,
                failedChildren = 3,
                cancelledChildren = 4,
                existsChildren = 5,
            )
            val child = newItem(id = "c1", state = QueueState.Running, parentGroupId = "g1")
                .copy(execRunId = "exec-uuid-1")
            p.save(listOf(parent, child), emptyList(), paused = false)
            val snap = p.load()
            val readParent = snap.items[0]
            assertEquals(QueueItemKind.GROUP, readParent.kind)
            assertEquals(200, readParent.totalChildren)
            assertEquals(120, readParent.completedChildren)
            assertEquals(3, readParent.failedChildren)
            assertEquals(4, readParent.cancelledChildren)
            assertEquals(5, readParent.existsChildren)
            assertEquals("exec-uuid-1", snap.items[1].execRunId)
 // 旧快照缺省防御：无 kind 字段回 SINGLE、计数回 0
            p.save(listOf(newItem(id = "legacy")), emptyList(), false)
            val legacy = p.load().items.single()
            assertEquals(QueueItemKind.SINGLE, legacy.kind)
            assertEquals(0, legacy.totalChildren)
            assertEquals("", legacy.execRunId)
        } finally { scope.cancel(); root.deleteRecursively() }
    }

    @Test fun `杀进程模拟 重建实例恢复全队列 终态原样活跃项待队列回 Queued`() = runTest {
        val root = tempRoot()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val p = create(root, scope)
            val running = newItem(id = "r1", state = QueueState.Running)
                .copy(cancelling = true, progressFraction = 0.7f, modeLabel = "音频")
            val waiting = newItem(id = "r2", state = QueueState.WaitingNetwork, error = "网络不给力")
            val failed = newItem(id = "f1", state = QueueState.Failed, error = "不支持")
            val done = newItem(id = "c1", state = QueueState.Completed, completedModes = setOf(Mode.AUDIO))
            p.save(listOf(running, waiting), listOf(failed, done), paused = false)

            scope.cancel() // = 进程死亡
            val scope2 = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            try {
                val snap = create(root, scope2).load()
 // 原样读回（Running/WaitingNetwork→Queued 的重置语义在 DownloadQueue.restore，队列级测试覆盖）
                assertEquals(listOf("r1", "r2"), snap.items.map { it.id })
                assertEquals(QueueState.Running, snap.items[0].state)
                assertEquals(QueueState.WaitingNetwork, snap.items[1].state)
                assertEquals(listOf("f1", "c1"), snap.terminal.map { it.id })
                assertEquals(QueueState.Failed, snap.terminal.first().state)
                assertEquals(setOf(Mode.AUDIO), snap.terminal.last().completedModes)
            } finally { scope2.cancel() }
        } finally { root.deleteRecursively() }
    }

    @Test fun `空存储读回空快照`() = runTest {
        val root = tempRoot()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val snap = create(root, scope).load()
            assertTrue(snap.items.isEmpty() && snap.terminal.isEmpty())
            assertFalse(snap.paused)
        } finally { scope.cancel(); root.deleteRecursively() }
    }

    @Test fun `损坏文件读回空快照不崩`() = runTest {
        val root = tempRoot()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val file = File(root, "queue.preferences_pb")
            file.writeText("not-a-datastore-file-garbage!!!")
            val snap = create(root, scope).load()
            assertNull(snap.items.firstOrNull())
            assertFalse(snap.paused)
        } finally { scope.cancel(); root.deleteRecursively() }
    }
}
