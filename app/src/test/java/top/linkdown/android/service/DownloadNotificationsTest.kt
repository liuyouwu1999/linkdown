package top.linkdown.android.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import top.linkdown.android.pipeline.DownloadModes
import top.linkdown.android.queue.QueueItem
import top.linkdown.android.queue.QueueState
import top.linkdown.android.queue.QueueUiState

/**
 * DownloadService 可 JVM 化部分（功能任务）：通知文案常量、notificationId 映射、存活谓词
 * （设计逐字：Running/Queued/WaitingNetwork 任一存活，终态不保活）、整体进度、
 * 结构签名+节流门（≤1 次/秒，结构变化立即）。Android 框架接线（NotificationManager/FGS）留真机。
 */
class DownloadNotificationsTest {

    private fun item(
        id: String,
        state: QueueState,
        progress: Float = 0f,
        title: String = "https://example.com/$id",
        cancelling: Boolean = false,
    ) = QueueItem(
        id = id,
        url = title,
        platform = "DOUYIN",
        modes = DownloadModes(false, true, false),
        state = state,
        title = title,
        taskDir = "/tmp/$id",
        createdAt = 0L,
        progressFraction = progress,
        cancelling = cancelling,
    )

 // ---- 文案 ----

    @Test
    fun aggregateTitle_carriesActiveCount() {
        assertEquals("链接小卖部 · 下载中 0", DownloadNotifications.aggregateTitle(0))
        assertEquals("链接小卖部 · 下载中 3", DownloadNotifications.aggregateTitle(3))
    }

    @Test
    fun fixedTexts_matchSpec() {
        assertEquals("等待网络…", DownloadNotifications.waitingNetworkText())
        assertEquals("今日后台时长用尽，回 App 继续", DownloadNotifications.timeoutText())
    }

 // ---- notificationId 映射 ----

    @Test
    fun taskNotificationId_isRunIdHash_byDefault() {
 // 保留段之上（≥3）的 hash 原样透传（本串 hash=990325984）
        val runId = "aaaaaaaa-bbbb-cccc"
        assertTrue(runId.hashCode() >= 3)
        assertEquals(runId.hashCode(), DownloadNotifications.taskNotificationId(runId))
    }

    @Test
    fun taskNotificationId_shiftsAwayFromReservedIds() {
 // 注入保留 id 与其下任意值（含负/极值）：结果必须为非保留 id——hash≤2 一律抬到 3，
 // 绝不错位进另一个保留 id（旧实现 1→2==TIMEOUT 的 bug）
        listOf(
            Int.MIN_VALUE, -7, 0, 1, 2,
            DownloadNotifications.AGGREGATE_NOTIFICATION_ID,
            DownloadNotifications.TIMEOUT_NOTIFICATION_ID,
        ).forEach { h ->
            val nid = DownloadNotifications.resolveTaskId(h)
            assertNotEquals(DownloadNotifications.AGGREGATE_NOTIFICATION_ID, nid)
            assertNotEquals(DownloadNotifications.TIMEOUT_NOTIFICATION_ID, nid)
        }
        assertEquals(3, DownloadNotifications.resolveTaskId(DownloadNotifications.AGGREGATE_NOTIFICATION_ID))
        assertEquals(3, DownloadNotifications.resolveTaskId(DownloadNotifications.TIMEOUT_NOTIFICATION_ID))
    }

 // ---- 存活谓词（设计逐字） ----

    @Test
    fun alivePredicate_runningQueuedWaitingNetwork_true_terminal_false() {
        assertTrue(DownloadNotifications.isAliveState(QueueState.Running))
        assertTrue(DownloadNotifications.isAliveState(QueueState.Queued))
        assertTrue(DownloadNotifications.isAliveState(QueueState.WaitingNetwork))
        assertFalse(DownloadNotifications.isAliveState(QueueState.Completed))
        assertFalse(DownloadNotifications.isAliveState(QueueState.Failed))
        assertFalse(DownloadNotifications.isAliveState(QueueState.Cancelled))
    }

    @Test
    fun aliveItems_filtersTerminals_only() {
        val state = QueueUiState(
            items = listOf(
                item("run", QueueState.Running, progress = 0.5f),
                item("done", QueueState.Completed),
                item("queue", QueueState.Queued),
                item("fail", QueueState.Failed),
            ),
            paused = false,
        )
        assertEquals(listOf("run", "queue"), DownloadNotifications.aliveItems(state.items).map { it.id })
        assertFalse(DownloadNotifications.hasAliveItems(state.items.filter { it.state == QueueState.Completed }))
    }

 // ---- 整体进度 ----

    @Test
    fun overallProgress_emptyOrAllTerminal_isZero() {
        assertEquals(0f, DownloadNotifications.overallProgressFraction(emptyList()), 1e-6f)
        assertEquals(
            0f,
            DownloadNotifications.overallProgressFraction(listOf(item("d", QueueState.Completed, 1f))),
            1e-6f,
        )
    }

    @Test
    fun overallProgress_isMeanOverAliveItems_terminalsExcluded() {
        val items = listOf(
            item("a", QueueState.Running, 0.6f),
            item("b", QueueState.Queued, 0f), // 排队贡献 0
            item("c", QueueState.WaitingNetwork, 0.3f),
            item("d", QueueState.Completed, 1f), // 终态剔除
        )
        assertEquals((0.6f + 0f + 0.3f) / 3f, DownloadNotifications.overallProgressFraction(items), 1e-6f)
    }

 // ---- 结构签名 + 节流门 ----

    @Test
    fun signature_progressOnlyChange_keepsSameSignature() {
        val before = QueueUiState(listOf(item("a", QueueState.Running, 0.1f)), paused = false)
        val after = QueueUiState(listOf(item("a", QueueState.Running, 0.9f)), paused = false)
        assertEquals(DownloadNotifications.stateSignature(before), DownloadNotifications.stateSignature(after))
    }

    @Test
    fun signature_structuralChanges_flipSignature() {
        val base = QueueUiState(listOf(item("a", QueueState.Running, 0.5f)), paused = false)
 // 数量变化
        val more = QueueUiState(base.items + item("b", QueueState.Queued), paused = false)
 // 状态迁移
        val cancelled = QueueUiState(listOf(item("a", QueueState.Cancelled)), paused = false)
 // cancelling 子态
        val cancelling = QueueUiState(listOf(item("a", QueueState.Running, 0.5f, cancelling = true)), paused = false)
 // 暂停翻转
        val paused = QueueUiState(base.items, paused = true)
 // probe 摘要回写（title 变）
        val renamed = QueueUiState(listOf(item("a", QueueState.Running, 0.5f, title = "某视频")), paused = false)
        listOf(more, cancelled, cancelling, paused, renamed).forEach { variant ->
            assertNotEquals(
                "结构变化必须翻转签名：$variant",
                DownloadNotifications.stateSignature(base),
                DownloadNotifications.stateSignature(variant),
            )
        }
    }

    @Test
    fun throttle_structuralAlwaysRenders_progressGatedAtOneSecond() {
 // 结构变化无视间隔立即渲染
        assertTrue(DownloadNotifications.shouldRender(signatureChanged = true, lastRenderAtMs = 9_500L, nowMs = 9_600L))
 // 仅进度：1 秒内节流
        assertFalse(DownloadNotifications.shouldRender(signatureChanged = false, lastRenderAtMs = 9_500L, nowMs = 9_999L))
 // 仅进度：满 1 秒放行
        assertTrue(DownloadNotifications.shouldRender(signatureChanged = false, lastRenderAtMs = 9_500L, nowMs = 10_500L))
 // 首次渲染（无历史）立即放行
        assertTrue(DownloadNotifications.shouldRender(signatureChanged = false, lastRenderAtMs = 0L, nowMs = 5L))
    }

 // ---- 通知收敛：子项不各自发通知 / 父组进度读聚合计数 ----

    private fun group(
        id: String,
        state: QueueState,
        total: Int,
        completed: Int = 0,
        exists: Int = 0,
        failed: Int = 0,
        cancelled: Int = 0,
    ) = QueueItem(
        id = id,
        url = "https://example.com/playlist",
        platform = "YTDLP",
        modes = DownloadModes(false, true, false),
        kind = top.linkdown.android.queue.QueueItemKind.GROUP,
        state = state,
        totalChildren = total,
        completedChildren = completed,
        existsChildren = exists,
        failedChildren = failed,
        cancelledChildren = cancelled,
        title = "歌单",
        taskDir = "",
        createdAt = 0L,
    )

    private fun child(id: String, parent: String, state: QueueState, progress: Float = 0f) =
        item(id, state, progress).copy(parentGroupId = parent)

    @Test
    fun childrenWithParentGroup_neverGetIndividualNotification() {
        assertTrue("父项/单项渲染独立通知", DownloadNotifications.shouldRenderTaskNotification(item("p", QueueState.Running)))
        assertFalse("带 parentGroupId 子项不渲染独立通知（收敛父组）",
            DownloadNotifications.shouldRenderTaskNotification(child("c", "p", QueueState.Running)))
    }

    @Test
    fun groupProgressText_readsAggregateCounts_existsCountsAsDone() {
        assertEquals("已完成 3/10", DownloadNotifications.groupProgressText(group("g", QueueState.Running, total = 10, completed = 2, exists = 1)))
        assertEquals("已完成 0/4", DownloadNotifications.groupProgressText(group("g", QueueState.Running, total = 4)))
    }

    @Test
    fun groupProgressFraction_countsExists_totalZeroFallback() {
        assertEquals(0.5f, DownloadNotifications.groupProgressFraction(group("g", QueueState.Running, total = 4, completed = 1, exists = 1)), 1e-6f)
        assertEquals(0f, DownloadNotifications.groupProgressFraction(group("g", QueueState.Running, total = 0)), 1e-6f)
    }

    @Test
    fun overallProgress_childrenExcluded_groupContributesAggregate() {
 // 父组（2/4 完成 → 0.5）+ 单项 0.8 → 均值 0.65；子项不计（收敛进父组）
        val items = listOf(
            group("g", QueueState.Running, total = 4, completed = 2),
            child("c1", "g", QueueState.Running, progress = 0.9f),
            child("c2", "g", QueueState.Queued),
            item("s", QueueState.Running, progress = 0.8f),
        )
        assertEquals((0.5f + 0.8f) / 2f, DownloadNotifications.overallProgressFraction(items), 1e-6f)
    }

    @Test
    fun aggregateActiveCount_groupCountsOne_childrenNotCounted() {
        val items = listOf(
            group("g", QueueState.Running, total = 3),
            child("c1", "g", QueueState.Running),
            child("c2", "g", QueueState.Queued),
            item("s", QueueState.Queued),
        )
        assertEquals("父组记 1 单、子项不计", 2, DownloadNotifications.aggregateActiveCount(items))
    }

    @Test
    fun groupParent_aliveness_byDerivedState_terminalNotKeptAlive() {
 // GROUP 父项存活按派生终态判定：派生 Running 保活；Partial/终态不保活
        assertTrue(DownloadNotifications.isAliveState(QueueState.Running))
        val partial = group("g", QueueState.Failed, total = 2, completed = 1, failed = 1)
        val done = group("g", QueueState.Completed, total = 2, completed = 2)
        assertFalse(DownloadNotifications.hasAliveItems(listOf(partial)))
        assertFalse(DownloadNotifications.hasAliveItems(listOf(done)))
    }
}
