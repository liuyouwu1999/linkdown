package top.linkdown.android.queue

import android.net.Uri
import top.linkdown.android.engine.Mode
import top.linkdown.android.pipeline.DownloadModes
import top.linkdown.android.settings.SettingsValues
import java.io.File

/** 队列态（设计； +Exists 终态：库判重命中「已在货架上」，接线产出——Exists 视同完成）；
 * Cancelling 为 [QueueItem.cancelling]=true 的 Running UI 子态（取消异步耗时防连点）。 */
enum class QueueState { Queued, Running, WaitingNetwork, Completed, Failed, Cancelled, Exists }

/** 队列默认并发（设计常量， 起可经设置运行时覆盖——编译期常量退化为默认提供者值）。 */
const val MAX_CONCURRENCY = 2

/** 最近终态项持久化 FIFO 上限（设计；仅非子项消费，子项走 [MAX_CHILD_TERMINAL] 独立配额）。 */
const val MAX_TERMINAL = 50

/** 子项（带 parentGroupId）终态持久化 FIFO 独立配额（ 设计：豁免 50 淘汰，200 上限+余量）。 */
const val MAX_CHILD_TERMINAL = 300

/** 父组子项入队上限（ 设计：超限截断，弹窗人话「共 N 条，将截取前 200」提示一次）。 */
const val MAX_GROUP_CHILDREN = 200

/** 队列项种类：SINGLE=普通单项；GROUP=父组聚合项（不执行/不占并发槽/不出排位，态由子项计数推导）。 */
enum class QueueItemKind { SINGLE, GROUP }

/**
 * 队列项（设计字段逐字）：id=记录 id（UUID 字符串；通知/取消注册表键=[executionRunId]——
 * 默认与 id 同键，子项重试复活后每次重跑重新生成、与记录 id 解耦，设计）、
 * url、platform（路由识别 EngineKind.name，入队即可得）、modes（ DownloadModes 三布尔）、state、
 * parentGroupId（批量：父组子项标记）、kind（SINGLE/GROUP——GROUP 父项携带五计数）、
 * totalChildren/completedChildren/failedChildren/cancelledChildren/existsChildren（ 父组聚合计数，
 * 子项终态化/复活时增量维护+持久化，GROUP 归约由此推导）、execRunId（执行 runId，空=同 id）、
 * completedModes（已入库成功模式集合，P0）、sessdataSnapshot（入队时粘贴框快照）、
 * probe 摘要 title/coverUrl/probePlatform/durationSec（probe 前占位 title=链接本身，完成回写）、
 * taskDir（绝对路径，恢复/重试复用吃 .part；GROUP 父项不执行恒空串）、error、createdAt。
 * cancelling/progressFraction/modeLabel 为内存态（不持久化——进度逐块高频，持久化仅随状态迁移写）。
 */
data class QueueItem(
    val id: String,
    val url: String,
    val platform: String,
    val modes: DownloadModes,
    val state: QueueState = QueueState.Queued,
    val parentGroupId: String? = null,
    val kind: QueueItemKind = QueueItemKind.SINGLE,
    val totalChildren: Int = 0,
    val completedChildren: Int = 0,
    val failedChildren: Int = 0,
    val cancelledChildren: Int = 0,
    val existsChildren: Int = 0,
    val execRunId: String = "",
    val completedModes: Set<Mode> = emptySet(),
    val sessdataSnapshot: String? = null,
    val audioFormatSnapshot: String = SettingsValues.AUDIO_MP3, // 入队固化（M4：防跨重启续传前后产物格式不一致）
    val title: String,
    val coverUrl: String? = null,
    val probePlatform: String? = null,
    val durationSec: Double? = null,
    val taskDir: String,
    val error: String? = null,
    val createdAt: Long,
    val cancelling: Boolean = false,
    val progressFraction: Float = 0f,
    val modeLabel: String = "",
)

/** 执行 runId（EngineRegistry/fork 取消注册表/notificationId 键）：默认=记录 id；
 * 子项重试复活每次重跑重新生成（与记录 id 解耦，设计）。 */
fun QueueItem.executionRunId(): String = execRunId.ifEmpty { id }

/** 父组已完成子项数（Exists 视同完成——「已在货架上」不算失败，设计）。 */
fun QueueItem.groupDoneChildren(): Int = completedChildren + existsChildren

/** 父组聚合进度（0..1；total=0 兜底 0）。 */
fun QueueItem.groupProgressFraction(): Float =
    if (totalChildren <= 0) 0f else groupDoneChildren().toFloat() / totalChildren

/**
 * GROUP 派生态（计数推导穷举，设计）：存在非终态子项 → Running(N/M)；
 * failedChildren>0 → Failed（Partial f/m）；否则 cancelledChildren>0 → Cancelled；
 * 否则（completed+exists==total）→ Completed。
 */
fun QueueItem.deriveGroupState(): QueueState = when {
    groupDoneChildren() + failedChildren + cancelledChildren < totalChildren -> QueueState.Running
    failedChildren > 0 -> QueueState.Failed
    cancelledChildren > 0 -> QueueState.Cancelled
    else -> QueueState.Completed
}

/** 子项进入终态 → 父聚合计数增量（纯函数；重试复活离开 Failed 的反向 -n 由队列层处理）。 */
internal fun QueueItem.withChildTerminal(state: QueueState): QueueItem = when (state) {
    QueueState.Completed -> copy(completedChildren = completedChildren + 1)
    QueueState.Exists -> copy(existsChildren = existsChildren + 1)
    QueueState.Failed -> copy(failedChildren = failedChildren + 1)
    QueueState.Cancelled -> copy(cancelledChildren = cancelledChildren + 1)
    else -> this
}

/** UI 观察态：items=活跃项（入队序）+ 终态项（终态化序），paused=队列级暂停标志。 */
data class QueueUiState(val items: List<QueueItem>, val paused: Boolean)

/** 活跃项（Queued/Running/WaitingNetwork）在队中的位次（1 起）：队列卡「第 N 位」与分享卡
 * 「已加入队列（第 N 位）」共用；子项与 GROUP 父项不参与排位（设计「不出独立排位」）。
 * 查无（已终态化/非排位项）返回 1 兜底。 */
fun QueueUiState.activePositionOf(runId: String): Int {
    val idx = items
        .filter {
            it.parentGroupId == null && it.kind == QueueItemKind.SINGLE &&
                (it.state == QueueState.Queued || it.state == QueueState.Running || it.state == QueueState.WaitingNetwork)
        }
        .indexOfFirst { it.id == runId }
    return if (idx < 0) 1 else idx + 1
}

/** 入队结果：Duplicate=URL 字面精确+modes 三布尔一致命中活跃项（UI 层弹「已在队列中」Toast）。 */
sealed interface EnqueueResult {
    data class Enqueued(val item: QueueItem) : EnqueueResult
    data object Duplicate : EnqueueResult
}

/**
 * 单项执行回调（队列→执行单元的观察缝；执行单元只抛异常，状态决策全在 DownloadQueue）：
 * - cancelRequested：取消轮询唯一真源（队列取消集 ∪ EngineRegistry 旗标），probe 窗口/模式间隙守卫与
 * 合成 CancelHandle 共用（真引擎路径经 queue.cancel→cancelDispatch→EngineRegistry.cancel 传导）。
 * - shouldWaitOffline：引擎异常甄别用——断网 true→WaitingNetwork，有网 false→Failed（设计两向判定）。
 * - onModeCompleted：P0 写侧——completedModes += mode + 立即持久化 + 删该模式临时产物（防 already downloaded）。
 */
interface QueueRunHooks {
    fun cancelRequested(): Boolean
    fun shouldWaitOffline(): Boolean
    fun onLog(line: String)
    fun onProgress(modeLabel: String, fraction: Float)
    fun onIngested(label: String, uri: Uri?)
    suspend fun onProbeDone(title: String, coverUrl: String?, platform: String, durationSec: Double?)
    suspend fun onModeCompleted(mode: Mode, files: List<File>)

 /**
 * 库判重全命中 → Exists 终态（ 设计：**非异常信号**——管道判重命中直接置 Exists；
 * 队列形态覆写=终态化 Exists+持久化+父组计数，compat 直跑/旧测试用默认空实现不受影响）。
 */
    suspend fun onExists() {}
}

/** 队列执行单元（SingleDownloadPipeline.runItem 实现）：QueueItem+hooks 驱动，失败/取消以异常上抛。 */
fun interface QueueItemRunner {
    suspend fun run(item: QueueItem, hooks: QueueRunHooks)
}
