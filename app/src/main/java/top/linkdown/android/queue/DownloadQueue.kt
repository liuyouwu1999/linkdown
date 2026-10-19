package top.linkdown.android.queue

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import top.linkdown.android.engine.EngineCancelledException
import top.linkdown.android.engine.EngineException
import top.linkdown.android.engine.internal.ChildRef
import top.linkdown.android.pipeline.DownloadModes
import top.linkdown.android.pipeline.EngineRouter
import top.linkdown.android.pipeline.ErrorMapper
import top.linkdown.android.pipeline.PlatformDirs
import top.linkdown.android.settings.SettingsValues
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * 下载队列（按设计：两向判定 + 父子聚合）：状态流 + 调度器 + DataStore 持久化 + per-run 取消。
 * Application 进程单例（LinkdownApplication.queue），Activity 重建/回收仅观察 [state]（技术债已清）。
 *
 * 派发门四条件（同时满足才派）：!paused && 有空槽（并发 [maxConcurrencyProvider] 现读， 设置接线）&&
 * 网络可用 && 存在 Queued 且 kind != GROUP（GROUP 父项不占槽不被当任务跑）。
 * **暂停优先于断网恢复**（暂停下网络恢复不派发）；**暂停不禁取消**（Queued/WaitingNetwork 均可取消）。
 *
 * 取消三态：Queued/WaitingNetwork → 直接 Cancelled（入终态列表非物理移除）；
 * Running → Cancelling（Running 的 UI 子态，cancel 双侧派发缝 [cancelDispatch]）→ Cancelled（.part 保留）。
 * **父卡取消 = 级联**：未完成子项全部取消——含 Running（→Cancelling 真取消，有意优于上位
 * 「仅未开始」）；Queued/WaitingNetwork 直达 Cancelled。单次持锁批量置态+单次持久化（防 200 子项风暴）。
 *
 * WaitingNetwork 判定（两向）：引擎异常发生时 [NetworkMonitor] 断网 → WaitingNetwork（等待无上限，
 * 网络恢复回队列重跑未完成模式）；有网 → Failed（终态，人话）。
 *
 * 失败重试 [retry]：单项=新 runId 重新入队（completedModes 保留、taskDir 复用吃 .part）；
 * GROUP 父组=级联重试——失败子项**复活同记录**（id 不变回 Queued、failedChildren 增量反向 -n、
 * 执行 [QueueItem.executionRunId] 每次重跑重新生成与记录 id 解耦），归约与进度永不超 100%。
 *
 * 父组聚合计数：子项每次终态化增量更新父计数+即时持久化（[withChildTerminal]）；
 * 父组派生态由计数推导（[QueueItem.deriveGroupState] 穷举），父项随派生态在 active/terminal 间搬运。
 *
 * 进程重启恢复：init 读回持久化快照 → 未完成项一律回 Queued（Running/WaitingNetwork/Cancelling 重置；
 * **GROUP 父项跳过重置**——派生态由计数推导），paused 保留，随后走正常派发门自动续传。
 *
 * JVM 单测注入缝：[startService]（enqueue 唯一 FGS 触发点）、
 * [cancelDispatch]（真实现=EngineRegistry.cancel，其 YtDlp.requestCancel 依赖 Chaquopy 不可在 JVM 调）、
 * [now]（FIFO 时钟）、[maxConcurrencyProvider]/[audioFormatProvider]（ 设置三接线：并发槽运行时注入，
 * LinkdownApplication 以设置流回写——**运行中变更只影响后续派发**）、[runner]/persistence/network（假引擎/假监视器）。
 */
class DownloadQueue(
    private val persistence: QueuePersistence,
    private val network: NetworkMonitor,
    private val runner: QueueItemRunner,
    private val scope: CoroutineScope,
    private val newTaskDir: () -> File,
    private val startService: () -> Unit = {},
    private val cancelDispatch: (String) -> Unit = EngineRegistry::cancel,
    private val now: () -> Long = System::currentTimeMillis,
    private val maxConcurrencyProvider: () -> Int = { MAX_CONCURRENCY },
    private val audioFormatProvider: suspend () -> String = { SettingsValues.AUDIO_MP3 },
) {

    private val mutex = Mutex()
    private val active = LinkedHashMap<String, QueueItem>() // id→item，入队序
    private val terminal = ArrayList<QueueItem>() // 终态化序，按类配额（非子项≤50、子项≤300）
    private var paused = false
    private var activeRuns = 0

 /** restore 完成门（评审修复 Important②）：enqueue/retry 抢在 restore 前落盘会用内存空表覆盖
 * 上一进程快照（设计恢复保证缺口）。公开变更入口一律先 await——**必须在锁外**：若在锁内
 * 且 restore 尚未拿到锁，await 等 complete、restore 等锁，互等死锁；complete 在 restore 持锁
 * 末尾调用，await 返回即保证 restore 变更已全部落定且锁已按序交接。 */
    private val restored = CompletableDeferred<Unit>()

 /** 本队列请求取消的执行 runId 集（cancel 缝旗标；与 EngineRegistry 旗标并集为取消真源；
 * 键=[QueueItem.executionRunId]，与 hooks/EngineRegistry 同键）。 */
    private val cancelRequested = ConcurrentHashMap.newKeySet<String>()

    private val _state = MutableStateFlow(QueueUiState(items = emptyList(), paused = false))
    val state: StateFlow<QueueUiState> = _state.asStateFlow()

    init {
        network.start { scope.launch { onNetworkRestored() } }
        scope.launch {
            mutex.withLock {
                restoreLocked()
                restored.complete(Unit)
            }
        }
    }

 // ---- 入队 / 重试 ----

 /**
 * 入队（主页单视频路径/分享单项共用）：内部模式折叠（设计 矩阵，按路由 kind；执行时展开）+ 去重
 * （URL 字面精确+modes 三布尔一致，只对活跃项）。入队即写；成功即触发 FGS 唯一触发点
 * [startService]。命中去重返回 [EnqueueResult.Duplicate]（UI 层弹「已在队列中」）。
 */
    suspend fun enqueue(url: String, modes: DownloadModes, sessdataSnapshot: String?): EnqueueResult {
        restored.await() // 启动竞态防护（锁外，见 [restored]）
        return mutex.withLock {
            if (active.values.any { it.url == url && it.modes == modes }) return@withLock EnqueueResult.Duplicate
            val kind = EngineRouter.routeFor(url) // 路由识别，入队即可得
            val item = QueueItem(
                id = UUID.randomUUID().toString(),
                url = url,
                platform = kind.name,
                modes = modes,
                sessdataSnapshot = sessdataSnapshot?.trim()?.takeIf { it.isNotEmpty() },
                audioFormatSnapshot = audioFormatProvider(), // 入队读设置固化（M4：防续传前后产物格式不一致）
                title = url, // probe 前占位=链接本身
                taskDir = newTaskDir().absolutePath,
                createdAt = now(),
            )
            active[item.id] = item
            startService()
            publishLocked()
            persistLocked()
            maybeDispatchLocked()
            EnqueueResult.Enqueued(item)
        }
    }

 /**
 * 批量入队（按设计）：父 GROUP 项+子项**单次持锁批量入队+单次持久化+单次拉服务**
 * （防 200 子项×200 次快照写）。子项超 [MAX_GROUP_CHILDREN] 截断（弹窗人话「共 N 条，
 * 将截取前 200」由调用方提示一次）；子项 title 用枚举产物（无需占位）。空 children 回退 [enqueue]。
 * 去重：父 URL+modes 命中活跃项返回 [EnqueueResult.Duplicate]。
 */
    suspend fun enqueueGroup(
        title: String,
        url: String,
        modes: DownloadModes,
        children: List<ChildRef>,
        sessdataSnapshot: String?,
    ): EnqueueResult {
        restored.await() // 启动竞态防护（锁外）
        if (children.isEmpty()) return enqueue(url, modes, sessdataSnapshot)
        return mutex.withLock {
            if (active.values.any { it.url == url && it.modes == modes }) return@withLock EnqueueResult.Duplicate
            val taken = children.take(MAX_GROUP_CHILDREN)
            val groupId = UUID.randomUUID().toString()
            val sess = sessdataSnapshot?.trim()?.takeIf { it.isNotEmpty() }
            val audio = audioFormatProvider() // 整批读一次设置固化（同组同快照）
            val parent = QueueItem(
                id = groupId,
                url = url,
                platform = EngineRouter.routeFor(url).name,
                kind = QueueItemKind.GROUP,
                modes = modes,
                state = QueueState.Running, // 派生态初值（存在子项即非终态；此后随计数推导迁移）
                totalChildren = taken.size,
                sessdataSnapshot = sess,
                audioFormatSnapshot = audio,
                title = title, // 列表标题（枚举产物）
                taskDir = "", // 父项不执行（无临时目录）
                createdAt = now(),
            )
            active[groupId] = parent
            taken.forEach { child ->
                val item = QueueItem(
                    id = UUID.randomUUID().toString(),
                    url = child.url,
                    platform = EngineRouter.routeFor(child.url).name,
                    modes = modes,
                    parentGroupId = groupId,
                    sessdataSnapshot = sess,
                    audioFormatSnapshot = audio,
                    title = child.title, // 枚举产物标题
                    taskDir = newTaskDir().absolutePath,
                    createdAt = now(),
                )
                active[item.id] = item
            }
            startService() // 单次拉服务
            publishLocked()
            persistLocked() // 单次持久化（整批一次快照写）
            maybeDispatchLocked()
            EnqueueResult.Enqueued(parent)
        }
    }

 /**
 * 失败重试：单项=仅 Failed 项可重试，新 runId 重新入队（completedModes 保留=执行跳过已完成模式、
 * taskDir 复用吃 .part；probe 重走两段式）；GROUP=级联重试失败子项（[retryGroupChildrenLocked]）。
 * 返回新/复活 [QueueItem]，不可重试返回 null。
 */
    suspend fun retry(runId: String): QueueItem? {
        restored.await() // 启动竞态防护（锁外）
        return mutex.withLock {
            val src = terminal.lastOrNull { it.id == runId } ?: return@withLock null
            when (src.kind) {
                QueueItemKind.GROUP -> retryGroupChildrenLocked(src)
                QueueItemKind.SINGLE -> retrySingleLocked(src)
            }
        }
    }

    private suspend fun retrySingleLocked(src: QueueItem): QueueItem? {
        if (src.state != QueueState.Failed) return null
        val item = src.copy(
            id = UUID.randomUUID().toString(),
            state = QueueState.Queued,
            error = null,
            cancelling = false,
            progressFraction = 0f,
            modeLabel = "",
            createdAt = now(),
 // taskDir/completedModes/sessdataSnapshot/probe 摘要随 copy 保留（设计重试语义）
        )
        active[item.id] = item
        startService()
        publishLocked()
        persistLocked()
        maybeDispatchLocked()
        return item
    }

 /**
 * 级联重试（按设计）：失败子项**复活同记录**——id 不变回 Queued（completedModes/taskDir
 * 复用），执行 runId 重新生成与 item id 解耦（[QueueItem.executionRunId]，EngineRegistry/通知
 * notificationId 键随其同步）；failedChildren 增量反向 -n（归约与进度永不超 100%）。
 * 仅派生 Failed（Partial）父组可重试；无失败子项返回 null。
 */
    private suspend fun retryGroupChildrenLocked(src: QueueItem): QueueItem? {
        if (src.state != QueueState.Failed) return null
        val failed = terminal.filter { it.parentGroupId == src.id && it.state == QueueState.Failed }
        if (failed.isEmpty()) return null
        failed.forEach { f ->
            terminal.remove(f)
            active[f.id] = f.copy(
                state = QueueState.Queued,
                error = null,
                cancelling = false,
                progressFraction = 0f,
                modeLabel = "",
                execRunId = UUID.randomUUID().toString(), // 执行 runId 重新生成（与记录 id 解耦）
                createdAt = now(),
            )
        }
        val revived = src.copy(
            state = src.copy(failedChildren = src.failedChildren - failed.size).deriveGroupState(),
            failedChildren = src.failedChildren - failed.size,
            cancelling = false,
        )
        terminal.remove(src)
        active[revived.id] = revived // 父组回 active 表（派生 Running）
        startService()
        publishLocked()
        persistLocked()
        maybeDispatchLocked()
        return revived
    }

 // ---- 取消 / 暂停 ----

 /**
 * 取消（暂停不禁取消）：单项=Queued/WaitingNetwork 直达 Cancelled、Running→Cancelling 子态+双侧派发
 * （键=[QueueItem.executionRunId]）；GROUP=级联取消（[cancelGroupLocked]）。
 */
    suspend fun cancel(runId: String) {
        restored.await() // 启动竞态防护（锁外）
        mutex.withLock {
            val item = active[runId] ?: return@withLock
            when {
                item.kind == QueueItemKind.GROUP -> cancelGroupLocked(runId)
                else -> when (item.state) {
                    QueueState.Queued, QueueState.WaitingNetwork ->
                        finishRunLocked(runId, QueueState.Cancelled, error = null)
                    QueueState.Running -> if (!item.cancelling) { // 防连点
                        updateLocked(runId) { it.copy(cancelling = true) }
                        val execId = item.executionRunId()
                        cancelRequested.add(execId)
                        cancelDispatch(execId) // 双侧派发（EngineRegistry.cancel：Kotlin 旗标+YtDlp.requestCancel）
                        persistLocked()
                    }
                    else -> {}
                }
            }
        }
    }

 /**
 * 终态记录删除（后厨清账）：从终态表移除该记录；GROUP 父项级联移除其终态子项（派生 Failed/Exists
 * 父项的子项必已全部终态化——[QueueItem.deriveGroupState] 穷举保证）。**只删台账不删货**：
 * 货架产物/taskDir/.part 一概不动（文件归货架下架管）。非终态 id 无操作。
 */
    suspend fun removeTerminal(id: String) = removeTerminals(listOf(id))

 /** 批量清账（后厨多选）：单锁一次移除+一次持久化，规则同 [removeTerminal]（父组级联）。 */
    suspend fun removeTerminals(ids: Collection<String>) {
        restored.await() // 启动竞态防护（锁外）
        mutex.withLock {
            val set = ids.toSet()
            if (set.isEmpty() || terminal.none { it.id in set }) return@withLock
            terminal.removeAll { it.id in set || it.parentGroupId in set }
            publishLocked()
            persistLocked()
        }
    }

 /**
 * 级联取消（按设计）：未完成子项全部取消——含 Running（→Cancelling 真取消，有意优于上位
 * 「仅未开始」）；Queued/WaitingNetwork 直达 Cancelled。**单次持锁批量置态+单次持久化**
 * （防 200 子项取消风暴）。.part 保留（Cancelled 语义）。
 */
    private suspend fun cancelGroupLocked(groupId: String) {
        val parent = active[groupId] ?: return
        if (parent.cancelling) return // 防连点
        updateLocked(groupId) { it.copy(cancelling = true) }
        val children = active.values.filter { it.parentGroupId == groupId }
        children.forEach { child ->
            when (child.state) {
                QueueState.Queued, QueueState.WaitingNetwork -> {
                    active.remove(child.id)
                    cancelRequested.remove(child.executionRunId())
                    terminal.add(child.copy(state = QueueState.Cancelled, error = null, cancelling = false))
                    refreshGroupLocked(groupId, QueueState.Cancelled) // 计数增量+派生态迁移（单锁内）
                }
                QueueState.Running -> if (!child.cancelling) {
                    active[child.id] = child.copy(cancelling = true)
                    val execId = child.executionRunId()
                    cancelRequested.add(execId)
                    cancelDispatch(execId)
                }
                else -> {}
            }
        }
        trimTerminalLocked()
        publishLocked()
        persistLocked() // 单次持久化
    }

 /** 队列级暂停：停派新任务、运行中跑完；跨重启保留（持久化）。立即发布 [QueueUiState]（暂停按钮
 * 状态不依赖入队/派发事件——空队列/只有 Running 时同样可见，评审修复 Important①）。 */
    suspend fun setPaused(value: Boolean) {
        restored.await() // 启动竞态防护（锁外）
        mutex.withLock {
            if (paused != value) {
                paused = value
                if (!value) resumeWaitingLocked()
                publishLocked()
                persistLocked()
                maybeDispatchLocked()
            }
        }
    }

 // ---- 内部：恢复 / 派发 ----

 /** 进程重启恢复（init 派发）：未完成项一律回 Queued，paused 保留，终态表读回。
 * **GROUP 父项跳过 Queued 重置**——派生态由计数推导（cancel 残留一并复位）。
 * 合并跳过活跃表已有 id——restore 与并发入队同锁交错时不得用旧快照覆盖活跃项状态
 * （否则 Running 被翻回 Queued 造成同项双派发）。完成时置 [restored] 门（持锁内）。 */
    private suspend fun restoreLocked() {
        val snap = persistence.load()
        paused = snap.paused
        snap.items.forEach {
            if (active.containsKey(it.id)) return@forEach
            active[it.id] =
                if (it.kind == QueueItemKind.GROUP) {
                    it.copy(state = it.deriveGroupState(), cancelling = false)
                } else {
                    it.copy(
                        state = QueueState.Queued,
                        error = null,
                        cancelling = false,
                        progressFraction = 0f,
                        modeLabel = "",
                    )
                }
        }
        snap.terminal.forEach { terminalItem ->
            if (active.containsKey(terminalItem.id) || terminal.any { it.id == terminalItem.id }) return@forEach
            terminal.add(terminalItem)
        }
        publishLocked()
        maybeDispatchLocked()
    }

    private suspend fun onNetworkRestored() {
        restored.await() // 启动竞态防护（锁外）
        mutex.withLock {
            if (paused) return@withLock // 暂停优先于断网恢复
            resumeWaitingLocked()
            maybeDispatchLocked()
        }
    }

 /** 网络恢复侧：WaitingNetwork → Queued（仅 !paused && 有网；回队列重跑未完成模式）。 */
    private suspend fun resumeWaitingLocked() {
        if (paused || !network.isOnline()) return
        val waiting = active.values.filter { it.state == QueueState.WaitingNetwork }
        waiting.forEach { updateLocked(it.id) { it.copy(state = QueueState.Queued, error = null) } }
        if (waiting.isNotEmpty()) persistLocked()
    }

 /** 派发门四条件：!paused && 有空槽 && 网络可用 && 存在 Queued（FIFO=createdAt 序）。
 * 槽位数每次派发检查现读 [maxConcurrencyProvider]（运行中改设置只影响后续派发）。
 * 批量：kind != GROUP 过滤——GROUP 父项不占槽不被当任务跑。 */
    private suspend fun maybeDispatchLocked() {
        if (paused || !network.isOnline()) return
        while (activeRuns < maxConcurrencyProvider()) {
            val next = active.values
                .filter { it.state == QueueState.Queued && it.kind != QueueItemKind.GROUP }
                .minByOrNull { it.createdAt } ?: break
            dispatchLocked(next)
        }
    }

    private suspend fun dispatchLocked(item: QueueItem) {
        activeRuns++
        updateLocked(item.id) { it.copy(state = QueueState.Running, cancelling = false, progressFraction = 0f) }
        persistLocked()
        val execId = item.executionRunId() // 执行键（重试复活后与记录 id 解耦）
        val hooks = hooksFor(item.id, execId)
        scope.launch {
            try {
                runner.run(item, hooks)
                finish(item.id, QueueState.Completed, error = null)
            } catch (e: EngineCancelledException) {
                finish(item.id, QueueState.Cancelled, error = null)
            } catch (ce: CancellationException) {
                throw ce // 协程取消穿透不吞（技术债已清）
            } catch (e: EngineException) {
                failOrWait(item.id, hooks, e.message ?: "下载失败")
            } catch (t: Throwable) {
 // 登录墙人话的 XX=平台名：传路由平台名（PlatformDirs.dirFor，与 probePlatform/入库目录
 // 同源）；item.platform 是 EngineKind.name（YTDLP 等）非平台名，直接透传会渲染「登录 YTDLP」
 // 「其他」（无法路由识别的链接）不留名，ErrorMapper 兜底「B站」
                failOrWait(item.id, hooks, ErrorMapper.humanize(t, PlatformDirs.dirFor(item.url).takeIf { it != "其他" }))
            } finally {
                mutex.withLock {
                    activeRuns--
                    maybeDispatchLocked()
                }
            }
        }
    }

 /**
 * 引擎异常甄别（设计两向；判定时机=异常处理时刻，经 [QueueRunHooks.shouldWaitOffline]
 * 缝——队列实现=断网判定，compat 直跑恒 false=Failed）：
 * 断网 → WaitingNetwork（留活跃表，等待无上限可取消）；有网 → Failed。
 */
    private suspend fun failOrWait(runId: String, hooks: QueueRunHooks, humanMessage: String) {
        if (!hooks.shouldWaitOffline()) {
            finish(runId, QueueState.Failed, error = humanMessage)
        } else {
            mutex.withLock {
                cancelRequested.remove(active[runId]?.executionRunId() ?: runId)
                updateLocked(runId) { it.copy(state = QueueState.WaitingNetwork, error = humanMessage, cancelling = false) }
                persistLocked()
                maybeDispatchLocked() // 该 run 已释放槽位，其余排队项可继续
            }
        }
    }

 /** 终态化：移入终态表（按类配额淘汰）、清取消旗标（执行键）、子项终态→父计数增量+派生态迁移、
 * 持久化、续派发。 */
    private suspend fun finish(runId: String, state: QueueState, error: String?) = mutex.withLock {
        finishRunLocked(runId, state, error)
    }

    private suspend fun finishRunLocked(runId: String, state: QueueState, error: String?) {
        val item = active.remove(runId) ?: return
        cancelRequested.remove(item.executionRunId())
        terminal.add(
            item.copy(
                state = state,
                error = error,
                cancelling = false,
                progressFraction = if (state == QueueState.Completed || state == QueueState.Exists) 1f else item.progressFraction,
                modeLabel = if (state == QueueState.Completed || state == QueueState.Exists) "" else item.modeLabel,
            ),
        )
        trimTerminalLocked()
        item.parentGroupId?.let { refreshGroupLocked(it, state) } // M3.5：子项终态 → 父计数增量+派生态迁移
        publishLocked()
        persistLocked()
        maybeDispatchLocked()
    }

 /**
 * 父组聚合计数增量+派生态迁移（M3.5；单锁内调用，发布/持久化由调用方按迁移时机执行）：
 * 子项终态 → [withChildTerminal] 增量；state=计数推导派生态；父项随派生态在 active/terminal 间
 * 搬运（派生终态化进按类配额终态表）。
 */
    private fun refreshGroupLocked(groupId: String, childTerminalState: QueueState? = null) {
        val inActive = active.containsKey(groupId)
        var parent = (if (inActive) active[groupId] else terminal.firstOrNull { it.id == groupId }) ?: return
        if (childTerminalState != null) parent = parent.withChildTerminal(childTerminalState)
        val derived = parent.deriveGroupState()
        parent = parent.copy(
            state = derived,
            cancelling = if (derived == QueueState.Running) parent.cancelling else false, // 终态化清 Cancelling 子态
        )
        if (derived == QueueState.Running) {
            if (!inActive) terminal.removeAll { it.id == groupId } // 复活：终态表回 active
            active[groupId] = parent
        } else {
            if (inActive) {
                active.remove(groupId)
                terminal.add(parent)
                trimTerminalLocked()
            } else {
                terminal[terminal.indexOfFirst { it.id == groupId }] = parent
            }
        }
    }

 /** 终态表按类配额淘汰最旧（非子项≤[MAX_TERMINAL]、子项≤[MAX_CHILD_TERMINAL]；[trimTerminalToQuota] 单源）。 */
    private fun trimTerminalLocked() {
        val trimmed = trimTerminalToQuota(terminal)
        terminal.clear()
        terminal.addAll(trimmed)
    }

 // ---- 内部：执行回调（QueueRunHooks） ----

 /** hooks 双键：mapKey=记录 id（active 表读写）、execKey=执行 runId（取消旗标/EngineRegistry）。 */
    private fun hooksFor(mapKey: String, execKey: String) = object : QueueRunHooks {
        override fun cancelRequested(): Boolean =
            cancelRequested.contains(execKey) || EngineRegistry.isCancelled(execKey)

        override fun shouldWaitOffline(): Boolean = !network.isOnline()

        override fun onLog(line: String) {} // 队列卡不展示引擎日志（UI 再议）

        override fun onProgress(modeLabel: String, fraction: Float) {
 // 进度不持久化（逐块高频）；仅内存态发布，StateFlow conflation 兜底
            scope.launch {
                mutex.withLock {
                    active[mapKey]?.let { active[mapKey] = it.copy(progressFraction = fraction, modeLabel = modeLabel) }
                    publishLocked()
                }
            }
        }

        override fun onIngested(label: String, uri: android.net.Uri?) {} // 队列层不消费入库 Uri（compat run 用）

        override suspend fun onProbeDone(title: String, coverUrl: String?, platform: String, durationSec: Double?) =
            mutex.withLock {
                updateLocked(mapKey) {
                    it.copy(title = title, coverUrl = coverUrl, probePlatform = platform, durationSec = durationSec)
                }
                persistLocked()
            }

 /** P0 写侧：每模式 ingest 成功 → completedModes += mode → 立即持久化 → 删该模式临时产物。 */
        override suspend fun onModeCompleted(mode: top.linkdown.android.engine.Mode, files: List<File>) =
            mutex.withLock {
                updateLocked(mapKey) { it.copy(completedModes = it.completedModes + mode) }
                persistLocked()
                files.forEach { it.delete() } // 防 yt-dlp 重跑报 "already been downloaded"
            }

 /** 库判重全命中 → Exists 终态（非异常信号；设计）：终态化入终态表+父组
 * existsChildren 计数/归约+持久化+续派发。信号返回后管道正常收尾，dispatch 兜底
 * Completed 因项已出 active 表（finishRunLocked 先 remove）成 no-op。 */
        override suspend fun onExists() = mutex.withLock {
            finishRunLocked(mapKey, QueueState.Exists, error = null)
        }
    }

 // ---- 内部：状态发布 / 持久化 ----

 /** UI 列表=活跃项（入队序）+ 终态项（终态化序）。 */
    private fun publishLocked() {
        _state.value = QueueUiState(items = active.values.toList() + terminal.toList(), paused = paused)
    }

 /** 纯内存更新+发布（持久化由调用方按迁移时机决定；进度走本函数不写盘）。 */
    private fun updateLocked(runId: String, f: (QueueItem) -> QueueItem) {
        val cur = active[runId] ?: return
        active[runId] = f(cur)
        publishLocked()
    }

    private suspend fun persistLocked() {
        persistence.save(active.values.toList(), terminal.toList(), paused)
    }
}
