package top.linkdown.android.service

import top.linkdown.android.queue.QueueItem
import top.linkdown.android.queue.QueueItemKind
import top.linkdown.android.queue.QueueState
import top.linkdown.android.queue.QueueUiState
import top.linkdown.android.queue.groupDoneChildren
import top.linkdown.android.queue.groupProgressFraction

/**
 * DownloadService 的纯函数层（ 设计；JVM 单测直接覆盖 [DownloadNotificationsTest]）：
 * 通知文案、notificationId 映射、存活谓词、整体进度、结构签名+节流门。
 * 父子聚合收敛：带 parentGroupId 子项不渲染独立通知；父组通知进度读聚合计数
 * （Exists 视同完成）；聚合牌进度=父组聚合进度+单项进度的均值；父组记 1 单。
 * Service 本体只做 Android 框架接线（NotificationCompat/FGS/PendingIntent）。
 */
object DownloadNotifications {

    const val CHANNEL_ID = "download"
    const val AGGREGATE_NOTIFICATION_ID = 1
    const val TIMEOUT_NOTIFICATION_ID = 2

 /** 设计：通知更新节流 ≤1 次/秒（onProgress 逐块高频）。 */
    const val MIN_RENDER_INTERVAL_MS = 1000L

    fun aggregateTitle(activeCount: Int): String = "链接小卖部 · 下载中 $activeCount"

    fun waitingNetworkText(): String = "等待网络…"

    fun timeoutText(): String = "今日后台时长用尽，回 App 继续"

 /** 每任务通知 id=runId.hashCode()（设计）；落在保留 id 段（聚合/超时）时抬出。 */
    fun taskNotificationId(runId: String): Int = resolveTaskId(runId.hashCode())

 /**
 * hash→notificationId 映射（可注入碰撞值直测）：max(hash, 3) 起步——hash 与保留 id
 * （聚合=1/超时=2）碰撞或落在其下（含负值，概率~2^-31/run）一律抬到首个非保留 id，
 * 绝不覆盖保留通知。
 */
    fun resolveTaskId(hash: Int): Int = maxOf(hash, TIMEOUT_NOTIFICATION_ID + 1)

 /** 存活谓词（设计逐字）：Running/Queued/WaitingNetwork 任一；终态不保活。
 * GROUP 父项同谓词——其 state=队列维护的计数推导派生态（派生终态不保活）。 */
    fun isAliveState(state: QueueState): Boolean = when (state) {
        QueueState.Running, QueueState.Queued, QueueState.WaitingNetwork -> true
        else -> false
    }

    fun aliveItems(items: List<QueueItem>): List<QueueItem> = items.filter { isAliveState(it.state) }

    fun hasAliveItems(items: List<QueueItem>): Boolean = items.any { isAliveState(it.state) }

 /** 批量：带 parentGroupId 子项不渲染独立通知（进度收敛父组通知）；父项/单项渲染。 */
    fun shouldRenderTaskNotification(item: QueueItem): Boolean = item.parentGroupId == null

 /** 父组通知进度文案（聚合计数口径；Exists 视同完成）。 */
    fun groupProgressText(item: QueueItem): String = "已完成 ${item.groupDoneChildren()}/${item.totalChildren}"

 /** 父组聚合进度（0..1；total=0 兜底 0）。 */
    fun groupProgressFraction(item: QueueItem): Float = item.groupProgressFraction()

 /**
 * 聚合牌活跃计数：父组记 1 单、子项不计（子项进度收敛父组）。
 */
    fun aggregateActiveCount(items: List<QueueItem>): Int =
        items.count { isAliveState(it.state) && it.parentGroupId == null }

 /**
 * 整体进度=存活「进度单元」均值（ 修订）：单视频项贡献 progressFraction；GROUP 父项贡献
 * 聚合计数进度（completedChildren+existsChildren)/totalChildren）；子项不计（收敛进父组）。
 */
    fun overallProgressFraction(items: List<QueueItem>): Float {
        val units = items
            .filter { isAliveState(it.state) && it.parentGroupId == null }
            .map { if (it.kind == QueueItemKind.GROUP) it.groupProgressFraction() else it.progressFraction }
        if (units.isEmpty()) return 0f
        return units.sumOf { it.toDouble() }.toFloat() / units.size
    }

 /**
 * 结构签名：数量/状态/cancelling 子态/paused/probe 摘要（title）任一变化 → 签名翻转；
 * 刻意排除 progressFraction（逐块高频）与 modeLabel（同频）——仅进度变化走节流门。
 */
    fun stateSignature(state: QueueUiState): String =
        state.items.joinToString(",") { "${it.id}:${it.state}:${it.cancelling}:${it.title}" } +
            "|paused=${state.paused}|count=${state.items.size}"

 /**
 * 节流门：结构变化（签名翻转）→ 立即渲染（终态清理/暂停翻转/增删项不滞后）；
 * 仅进度变化 → 距上次渲染满 [MIN_RENDER_INTERVAL_MS] 才放行。
 * lastRenderAtMs≤0 表示从未渲染（服务侧缺失条目）→ 立即放行。
 */
    fun shouldRender(signatureChanged: Boolean, lastRenderAtMs: Long, nowMs: Long): Boolean =
        signatureChanged || lastRenderAtMs <= 0L || nowMs - lastRenderAtMs >= MIN_RENDER_INTERVAL_MS
}
