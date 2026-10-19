package top.linkdown.android.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.graphics.drawable.IconCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import top.linkdown.android.LinkdownApplication
import top.linkdown.android.MainActivity
import top.linkdown.android.queue.DownloadQueue
import top.linkdown.android.queue.QueueItem
import top.linkdown.android.queue.QueueState
import top.linkdown.android.queue.QueueUiState
import java.util.concurrent.ConcurrentHashMap

/**
 * 下载前台服务（ 设计）：只承载「进程存活+通知」，状态真源在 [DownloadQueue]
 * （Application 单例，enqueue 是唯一 FGS 触发点——本类被动被拉起，不自我启动）。
 *
 * - foregroundServiceType=dataSync + START_NOT_STICKY（恢复靠持久化，不靠 sticky 重启）。
 * - 存活谓词逐字：Running/Queued/WaitingNetwork 任一 → 保持前台；三者皆无 → stopSelf
 * （终态不保活）。onStartCommand 必先 startForeground（startForegroundService 5 秒契约），
 * 随后谓词决定去留。
 * - 通知：聚合通知（「链接小卖部 · 下载中 N」+ 整体进度 + 暂停/继续 action + 点回主页）+
 * 每任务独立通知（notificationId=runId.hashCode()，probe 摘要为标题、进度条、取消 action）。
 * 更新节流 ≤1 次/秒（进度逐块高频）；结构变化（状态迁移/暂停/增删）立即渲染；
 * 任务终态 → 撤该任务通知。文案与节流门在 [DownloadNotifications]（JVM 单测覆盖）。
 * - onTimeout()（仅 API≥35 dataSync 6 小时配额触发）：全量取消（经 queue.cancel 驱动状态
 * 迁移与持久化，队列自身落盘）→ 撤任务通知 → 「今日后台时长用尽」通知（独立 id，服务停止后
 * 仍可清除）→ stopSelf。
 */
class DownloadService : Service() {

    private val queue: DownloadQueue by lazy { (application as LinkdownApplication).queue }
    private val notificationManager by lazy { getSystemService(NotificationManager::class.java) }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private var observed = false
    private var stopping = false
    private var lastSignature: String? = null

 /** notificationId → 上次渲染时刻（节流门；0/缺失=从未渲染，立即放行）。 */
    private val lastRenderAt = ConcurrentHashMap<Int, Long>()

 /** 当前展示中的每任务通知 runId 集（终态清理的依据）。 */
    private val shownTaskIds = ConcurrentHashMap.newKeySet<String>()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ensureChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
 // 同实例 stopSelf→onDestroy 毫秒窗口内新 start intent：复位降级/停渲旗标，服务可复活
        stopping = false
        enterForeground()
        when (intent?.action) {
            ACTION_PAUSE -> scope.launch { runCatching { queue.setPaused(true) } }
            ACTION_RESUME -> scope.launch { runCatching { queue.setPaused(false) } }
            ACTION_CANCEL_RUN -> intent.getStringExtra(EXTRA_RUN_ID)?.let { runId ->
                scope.launch { runCatching { queue.cancel(runId) } }
            }
        }
        observeQueue()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

 /** API≥35 dataSync 配额用尽（API≤34 无此回调）。全量取消→持久化由队列自身状态迁移驱动。 */
    override fun onTimeout(startId: Int) {
 // 先封渲染：cancel 风暴立刻产生状态发射，不封会把刚撤的每任务通知贴回、
 // 在 stopForeground 后重新 startForeground 聚合牌（通知「复活」竞态）
        stopping = true
 // 独立 scope：服务 stopSelf 销毁后，取消仍要在队列侧走完（Running→Cancelling 落盘由
 // 队列 cancel 即写；runner 收尾的 Cancelled 迁移走队列自身 scope）
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            queue.state.value.items
                .filter { DownloadNotifications.isAliveState(it.state) }
                .forEach { runCatching { queue.cancel(it.id) } }
        }
        shownTaskIds.forEach { notificationManager.cancel(DownloadNotifications.taskNotificationId(it)) }
        shownTaskIds.clear()
        notificationManager.notify(DownloadNotifications.TIMEOUT_NOTIFICATION_ID, timeoutNotification())
        stopForeground(STOP_FOREGROUND_REMOVE) // 撤聚合 FGS 通知；超时通知独立 id 不受影响
        stopSelf()
    }

 // ---- 存活谓词 + 状态观察 ----

 /** startForegroundService 5 秒契约：任何启动路径先立 FGS 牌（空队列也先立后撤）。 */
    private fun enterForeground() {
        try {
            val state = queue.state.value
            startForeground(
                DownloadNotifications.AGGREGATE_NOTIFICATION_ID,
                aggregateNotification(state, DownloadNotifications.aliveItems(state.items)),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        } catch (e: android.app.ForegroundServiceStartNotAllowedException) {
 // 后台启动被拒（API31+）：降级为仅前台队列，无通知，不崩。密闭降级路径——
 // 封渲染防 render 内 startForeground 再次被拒炸进程；新 start intent 复位 stopping 可复活
            stopping = true
            stopSelf()
        }
    }

    private fun observeQueue() {
        if (observed) return
        observed = true
        scope.launch { queue.state.collect { render(it) } }
    }

 /** 状态流驱动渲染：终态清理立即；进度走节流门 ≤1/s；结构变化立即；无存活项 → stopSelf。
 * 批量：带 parentGroupId 子项不渲染独立通知（进度收敛父组通知）；GROUP 父项存活按
 * 派生终态判定（state=队列维护的计数推导态，isAliveState 同谓词）。 */
    private fun render(state: QueueUiState) {
        if (stopping) return
        val signature = DownloadNotifications.stateSignature(state)
        val signatureChanged = signature != lastSignature
        lastSignature = signature
        val now = SystemClock.elapsedRealtime() // 单调节流时钟：防系统时间回拨致节流门停摆

        val alive = DownloadNotifications.aliveItems(state.items)

 // 任务终态 → 撤该任务通知（清理/撤除不受节流约束）
        shownTaskIds.retainAll { runId ->
            val stillAlive = alive.any { it.id == runId }
            if (!stillAlive) {
                val nid = DownloadNotifications.taskNotificationId(runId)
                notificationManager.cancel(nid)
                lastRenderAt.remove(nid)
            }
            stillAlive
        }

        alive.forEach { item ->
            if (!DownloadNotifications.shouldRenderTaskNotification(item)) return@forEach // 子项收敛父组
            val nid = DownloadNotifications.taskNotificationId(item.id)
            if (DownloadNotifications.shouldRender(signatureChanged, lastRenderAt[nid] ?: 0L, now)) {
                notificationManager.notify(nid, taskNotification(item))
                lastRenderAt[nid] = now
                shownTaskIds.add(item.id)
            }
        }

        if (alive.isEmpty()) {
            stopping = true
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }

        if (DownloadNotifications.shouldRender(signatureChanged, lastRenderAt[DownloadNotifications.AGGREGATE_NOTIFICATION_ID] ?: 0L, now)) {
            startForeground(DownloadNotifications.AGGREGATE_NOTIFICATION_ID, aggregateNotification(state, alive))
            lastRenderAt[DownloadNotifications.AGGREGATE_NOTIFICATION_ID] = now
        }
    }

 // ---- 通知构造 ----

    private fun ensureChannel() {
        if (notificationManager.getNotificationChannel(DownloadNotifications.CHANNEL_ID) == null) {
            notificationManager.createNotificationChannel(
                NotificationChannel(
                    DownloadNotifications.CHANNEL_ID,
                    "下载进度",
                    NotificationManager.IMPORTANCE_LOW, // 常驻进度不发声不弹横幅
                ),
            )
        }
    }

    private fun mainContentIntent(): PendingIntent =
        PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )

    private fun pauseResumeAction(paused: Boolean): NotificationCompat.Action {
        val label = if (paused) "继续" else "暂停"
        val action = if (paused) ACTION_RESUME else ACTION_PAUSE
        val pi = PendingIntent.getService(
            this, REQUEST_CODE_PAUSE_RESUME,
            Intent(this, DownloadService::class.java).setAction(action),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Action.Builder(actionIcon(), label, pi).build()
    }

    private fun cancelRunAction(runId: String, requestCode: Int): NotificationCompat.Action {
        val pi = PendingIntent.getService(
            this, requestCode,
            Intent(this, DownloadService::class.java).setAction(ACTION_CANCEL_RUN).putExtra(EXTRA_RUN_ID, runId),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Action.Builder(actionIcon(), "取消", pi).build()
    }

    private fun actionIcon(): IconCompat = IconCompat.createWithResource(this, NOTIFICATION_ICON)

    private fun aggregateNotification(state: QueueUiState, alive: List<QueueItem>): Notification {
        val progress = DownloadNotifications.overallProgressFraction(state.items)
        return NotificationCompat.Builder(this, DownloadNotifications.CHANNEL_ID)
            .setSmallIcon(NOTIFICATION_ICON)
 // 批量：父组记 1 单、子项不计（子项进度收敛父组通知）
            .setContentTitle(DownloadNotifications.aggregateTitle(DownloadNotifications.aggregateActiveCount(state.items)))
            .setContentIntent(mainContentIntent())
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .addAction(pauseResumeAction(state.paused))
            .apply {
                if (state.paused) {
                    setContentText("已暂停")
                } else {
                    val anyProgress = alive.any { it.state == QueueState.Running && it.progressFraction > 0f }
                    if (anyProgress) {
                        setContentText("${(progress * 100).toInt()}%")
                        setProgress(100, (progress * 100).toInt(), false)
                    } else {
                        setContentText("准备中…") // probe/排队阶段无进度值
                        setProgress(0, 0, true)
                    }
                }
            }
            .build()
    }

    private fun taskNotification(item: QueueItem): Notification =
        NotificationCompat.Builder(this, DownloadNotifications.CHANNEL_ID)
            .setSmallIcon(NOTIFICATION_ICON)
            .setContentTitle(item.title) // probe 前占位=链接本身，probe 完成回写摘要
            .setContentIntent(mainContentIntent())
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .addAction(cancelRunAction(item.id, DownloadNotifications.taskNotificationId(item.id)))
            .apply {
                if (item.kind == top.linkdown.android.queue.QueueItemKind.GROUP) {
 // 父组通知：进度读聚合计数（子项不各自发通知）；取消 action=级联取消整组
                    setContentText(
                        when (item.state) {
                            QueueState.Running -> if (item.cancelling) "取消中…" else DownloadNotifications.groupProgressText(item)
                            QueueState.Queued -> "排队中…"
                            QueueState.WaitingNetwork -> DownloadNotifications.waitingNetworkText()
                            else -> DownloadNotifications.groupProgressText(item)
                        },
                    )
                    if (!item.cancelling) {
                        val pct = (DownloadNotifications.groupProgressFraction(item) * 100).toInt()
                        setProgress(100, pct, false)
                    }
                } else when (item.state) {
                    QueueState.Running -> {
                        setContentText(
                            when {
                                item.cancelling -> "取消中…"
                                item.modeLabel.isNotBlank() -> item.modeLabel
                                else -> "下载中…"
                            },
                        )
                        if (!item.cancelling) {
                            if (item.progressFraction > 0f) {
                                setProgress(100, (item.progressFraction * 100).toInt(), false)
                            } else {
                                setProgress(0, 0, true) // probe 窗口
                            }
                        }
                    }
                    QueueState.Queued -> setContentText("排队中…")
                    QueueState.WaitingNetwork -> setContentText(DownloadNotifications.waitingNetworkText())
                    else -> {}
                }
            }
            .build()

    private fun timeoutNotification(): Notification =
        NotificationCompat.Builder(this, DownloadNotifications.CHANNEL_ID)
            .setSmallIcon(NOTIFICATION_ICON)
            .setContentTitle("链接小卖部")
            .setContentText(DownloadNotifications.timeoutText())
            .setContentIntent(mainContentIntent())
            .setAutoCancel(true)
            .build()

    companion object {
        private const val ACTION_PAUSE = "top.linkdown.android.action.PAUSE"
        private const val ACTION_RESUME = "top.linkdown.android.action.RESUME"
        private const val ACTION_CANCEL_RUN = "top.linkdown.android.action.CANCEL_RUN"
        private const val EXTRA_RUN_ID = "runId"
        private const val REQUEST_CODE_PAUSE_RESUME = 100
        private const val NOTIFICATION_ICON = android.R.drawable.stat_sys_download
    }
}
