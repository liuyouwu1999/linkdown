package top.linkdown.android.pipeline

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.withContext
import top.linkdown.android.cookies.CookieStore
import top.linkdown.android.engine.CANCELLED_MESSAGE
import top.linkdown.android.engine.CancelHandle
import top.linkdown.android.engine.EngineCancelledException
import top.linkdown.android.engine.EngineException
import top.linkdown.android.engine.MediaEngine
import top.linkdown.android.engine.Mode
import top.linkdown.android.engine.QishuiEngine
import top.linkdown.android.ingest.MediaStoreIngest
import top.linkdown.android.queue.DedupCheck
import top.linkdown.android.queue.EngineRegistry
import top.linkdown.android.queue.MediaStoreShelfQuery
import top.linkdown.android.queue.QueueItem
import top.linkdown.android.queue.QueueRunHooks
import top.linkdown.android.queue.executionRunId
import top.linkdown.android.settings.SettingsValues
import java.io.File
import java.util.UUID

data class DownloadModes(val video: Boolean, val audio: Boolean, val cover: Boolean)

/**
 * 执行环境缝：管道对 Android Context 的全部依赖收敛于此（任务目录/cookie/入库/引擎路由）。
 * JVM 单测注入假环境（假引擎+临时目录），队列状态机全矩阵经真实执行代码驱动；生产用
 * [AndroidPipelineEnv]。
 */
interface PipelineEnv {
 /** URL → 引擎（生产=EngineRouter 路由+实例化薄壳）。 */
    fun engineFor(url: String): MediaEngine

 /** 新建本 run 任务临时目录（compat run 用；队列项 taskDir 由 DownloadQueue 生成并持久化）。 */
    fun newTaskDir(): File

 /** 路由平台→cookie 文件（哔哩哔哩→bilibili.txt（sessdata 快照兜底），网易云→netease.txt，
 * 其他→null；生产=CookieStore.forUrl，主页登录态兜底）。 */
    fun cookieFile(url: String, sessdata: String?): File?

 /** 入库（生产=MediaStoreIngest 拷入 Downloads/链接下载/<platform>/；JVM 假环境返回 null=无框架 Uri）。 */
    fun ingest(src: File, platform: String, onProgress: (Float) -> Unit): Uri?

 /** 库判重（ 设计：probe 后查货架命中；生产=DedupCheck+MediaStore 前缀比对，JVM 假环境可控）。 */
    val dedup: DedupCheck
}

/** 生产环境实现。 */
class AndroidPipelineEnv(private val context: Context) : PipelineEnv {
    override fun engineFor(url: String): MediaEngine =
        EngineRouter.route(EngineRouter.routeFor(url), context)

    override fun newTaskDir(): File =
        File(context.cacheDir, "downloads/${java.lang.Long.toHexString(System.currentTimeMillis())}").apply { mkdirs() }

    override fun cookieFile(url: String, sessdata: String?): File? =
        CookieStore.forUrl(url, sessdata?.trim()?.takeIf { it.isNotEmpty() }, context.filesDir)

    override fun ingest(src: File, platform: String, onProgress: (Float) -> Unit): Uri =
        MediaStoreIngest.ingest(context, src, platform, onProgress) // 非空 Uri 协变满足 Uri?

    override val dedup = DedupCheck(MediaStoreShelfQuery(context))
}

/**
 * 引擎感知模式折叠（设计矩阵）按路由 kind 的纯函数形态：汽水无视频流——凡勾了视频（或音频）的
 * 组合一律折叠为一次音频下载，伴生封面由引擎音频流自带（显式封面随折叠省去）；仅封面 → 仅封面。
 * [VIDEO,COVER] 设计未定义 → 按「音频+伴生封面」同 [VIDEO,AUDIO] 语义执行（EngineRouterTest
 * 拍板断言）。抖音/Ytdlp 不折叠直通（douyin 视频+音频=两次独立引擎调用，probe 复用）。
 * 队列入队/恢复折叠与执行展开共用本函数（单源）。
 */
fun foldedModesFor(kind: EngineKind, modes: DownloadModes): List<Mode> {
    if (kind == EngineKind.QISHUI) {
        return if (modes.video || modes.audio) listOf(Mode.AUDIO)
        else listOfNotNull(Mode.COVER.takeIf { modes.cover })
    }
    return buildList {
        if (modes.video) add(Mode.VIDEO)
        if (modes.audio) add(Mode.AUDIO)
        if (modes.cover) add(Mode.COVER)
    }
}

/**
 * 引擎实例形态（ 兼容入口，EngineRouterTest 直测）：按引擎是否汽水折算 kind 后委托 [foldedModesFor]。
 * top-level internal：JVM 单测直测（管道类持 Context 不可在宿主直构）。
 */
internal fun foldedModes(modes: DownloadModes, engine: MediaEngine): List<Mode> =
    foldedModesFor(if (engine is QishuiEngine) EngineKind.QISHUI else EngineKind.DOUYIN, modes)

sealed interface PipelineState {
    data object Idle : PipelineState
    data class Running(val modeLabel: String, val fraction: Float) : PipelineState
    data class Done(val results: List<Pair<String, Uri>>, val platforms: List<String>) : PipelineState
    data class Failed(val humanMessage: String) : PipelineState
    data object Cancelled : PipelineState
}

/**
 * 下载执行单元（功能任务 改造）：[runItem]=队列执行形态（QueueItem+hooks 驱动，状态决策在
 * DownloadQueue，本类只抛异常）；[run]=兼容直跑入口（单任务语义原样：失败即 Failed、
 * 不经队列派发门——PipelineE2ETest 三处直调与离线用例的即时 Failed 断言依赖此语义；MainActivity
 * 迁队列入口后本入口随收口清理）。两形态共用同一两段式执行核心。
 * [qualityProvider]=画质档缝（LinkdownApplication 以设置流回写；run 起跑时现读，
 * 仅 YtdlpEngine VIDEO 消费）；audioFormat 取 [QueueItem.audioFormatSnapshot] 入队快照值。
 */
class SingleDownloadPipeline(
    private val env: PipelineEnv,
    private val qualityProvider: () -> String = { SettingsValues.QUALITY_MAX },
) :
    top.linkdown.android.queue.QueueItemRunner {

 /** Context 便捷构造（MainActivity/ 现状不动）。 */
    constructor(context: Context) : this(AndroidPipelineEnv(context))

    private val _state = MutableStateFlow<PipelineState>(PipelineState.Idle)
    val state: StateFlow<PipelineState> = _state

 // 引擎原样日志行：转发给 UI 追加展示；缓冲丢弃兜底防慢消费者拖 IO 线程
    private val _logs = MutableSharedFlow<String>(extraBufferCapacity = 256, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val logs: SharedFlow<String> = _logs.asSharedFlow()

    @Volatile var busy = false; private set

 // per-run 取消：旗标收敛到 EngineRegistry（cancel 双侧派发），本类只记住当前 run 的 runId
 // （compat 单任务期 busy 约束下至多一个活动 run；队列形态的多 run 取消走 DownloadQueue.cancel）
    @Volatile private var currentRunId: String? = null

    private fun modeLabel(mode: Mode): String = when (mode) {
        Mode.VIDEO -> "视频"
        Mode.AUDIO -> "音频"
        Mode.COVER -> "封面"
    }

    fun requestCancel() {
 // compat 单任务形态（ 兼容）：取消当前 run；MainActivity 迁 queue.cancel 后随收口清理
        currentRunId?.let { EngineRegistry.cancel(it) }
    }

 /**
 * 入口批量枚举（ 设计弹窗二段确认缝；入口恰一次——判型/列表标题/子项引用，
 * ProbeInfo 不传入队列，队列照旧重 probe）。sessdata 快照 → cookie 文件同执行通道；
 * 枚举抛错原样上抛（弹窗人话，**不入队任何条目**）。取消语义：flat 抽取无 progress 事件
 * requestCancel 杀不掉——弹窗关闭=丢弃本协程结果，Future 自然结束不入队（勿造死等/强杀）。
 */
    suspend fun enumerate(url: String, sessdata: String?): top.linkdown.android.engine.BatchInfo =
        withContext(Dispatchers.IO) { // YtDlp.executeDebug().get() 阻塞：UI scope 调入必须离主线程
            val engine = env.engineFor(url)
            engine.enumerate(url, env.cookieFile(url, sessdata), runId = UUID.randomUUID().toString())
        }

    override suspend fun run(item: QueueItem, hooks: QueueRunHooks) = runItem(item, hooks)

 /**
 * 两段式执行核心（probe 每任务恰一次 → 模式循环 download，probe 产物复用）：
 * - completedModes 跳过（恢复/重试不重跑已完成模式，防重复入库）
 * - 库判重（设计）：probe 后、下载启动前逐模式查货架（[PipelineEnv.dedup]）——
 * 命中模式跳过不重下；全部所选模式命中 → [QueueRunHooks.onExists]（Exists 终态非异常）
 * - 每模式 ingest 成功 → [QueueRunHooks.onModeCompleted]（completedModes 写侧：持久化+删临时产物）
 * - sessdataSnapshot → cookieFile（主页登录态兜底）
 * - probe 摘要回写 → [QueueRunHooks.onProbeDone]
 * 失败/取消一律异常上抛（EngineCancelledException/CancellationException/EngineException/其余），
 * 由上位甄别：compat run 折 PipelineState；DownloadQueue 折六态（WaitingNetwork 两向判定）。
 */
    suspend fun runItem(item: QueueItem, hooks: QueueRunHooks) {
 // 执行键（ 评审修复 Important）：全程=item.executionRunId()——重试复活子项（execRunId
 // 再生 ≠ 记录 id）的 EngineRegistry/fork 注册表与队列取消派发（cancelDispatch(execRunId)）同键；
 // 单项退化=id， 零影响。键漏改则 YtDlp.requestCancel 打在未注册键上=引擎侧真取消静默失效。
        val runId = item.executionRunId()
        EngineRegistry.register(runId) // run 起点登记（清残留旗标，≙Python 入口 _reset_cancel）
        try {
            val engine = env.engineFor(item.url)
            val cookieFile = env.cookieFile(item.url, item.sessdataSnapshot)
            val taskDir = File(item.taskDir).apply { mkdirs() } // 恢复/重试复用吃 .part
            val handle = object : CancelHandle { // 引擎轮询取消的唯一入口（真源=hooks.cancelRequested）
                override fun isCancelled(): Boolean = hooks.cancelRequested()
            }
 // —— 第一段：probe 每任务恰一次（失败 → 异常上抛，taskDir 保留）——
            val info = engine.probe(item.url, cookieFile, runId)
            hooks.onProbeDone(info.title, info.coverUrl, info.platform, info.durationSec)
 // probe 窗口取消守卫（fix round 1， 改读 hooks 真源）：simulate 模式无 progress 事件、
 // Python 取消钩子不触发、probe 正常返回；不拦截则旗标被 download 入口 _reset_cancel 清掉
            if (handle.isCancelled()) throw EngineCancelledException(CANCELLED_MESSAGE)
 // —— 第二段：模式循环 download（入队折叠展开 + completedModes 跳过 + 库判重跳过）——
            val quality = qualityProvider() // run 起跑现读画质档（运行中改设置不影响本 run 已起跑的模式段）
            val selected = foldedModesFor(EngineKind.valueOf(item.platform), item.modes)
                .filterNot { item.completedModes.contains(it) }
 // 库判重（设计）：probe 后、下载启动前——货架已有产物的模式跳过不重下
 // （逐模式扩展名集合+预测基名前缀比对，判据在 DedupCheck）；跳过模式记日志交代。
 // 判重用 probe 平台（=入库 RELATIVE_PATH 平台目录，与 ingest 同源）。
            val hits = selected.filter { env.dedup.exists(info.platform, info.title, it, info.author) }
            val modesToRun = selected.filterNot { it in hits }
            hits.forEach { hooks.onLog("已在货架上，跳过：${modeLabel(it)}") }
            if (selected.isNotEmpty() && modesToRun.isEmpty()) {
 // 全部所选（未完成）模式命中 → Exists 终态（**非异常信号**，设计）：
 // 队列侧 hooksFor.onExists 终态化 Exists（终态表/父组 existsChildren/持久化/续派发）；
 // 信号返回后本管道正常收尾，dispatch 兜底 Completed 因项已出 active 表成 no-op。
 // compat 直跑（hooks 默认空实现）忽略信号照常 Done。
                hooks.onExists()
                taskDir.deleteRecursively() // 无产物下载，临时目录直接清
                return
            }
            modesToRun.forEachIndexed { index, mode ->
 // probe→download / 模式间间隙取消的同款守卫（评审修复 保留）
                if (handle.isCancelled()) throw EngineCancelledException(CANCELLED_MESSAGE)
                val label = modeLabel(mode)
 // 设计：汽水 video 勾选按音频交付——Running 的 modeLabel 即「音频」；「汽水无视频流，
 // 已按音频交付」日志行折叠后引擎收 AUDIO 不再自报，由管道补发
                if (engine is QishuiEngine && item.modes.video && mode == Mode.AUDIO) {
                    hooks.onLog(QishuiEngine.VIDEO_AS_AUDIO_LOG)
                }
                var modeFrac = 0f
 // 模式段内：下载占前 80%，入库拷贝占后 20%（设计）；段内取 max——B站 DASH 视频流
 // 完了音频流重开时引擎百分比回零，进度条不许倒退
                fun setMode(dlFrac: Float, copyFrac: Float = 0f) {
                    modeFrac = maxOf(modeFrac, (index + 0.8f * dlFrac + 0.2f * copyFrac) / modesToRun.size)
                    hooks.onProgress(label, modeFrac)
                }
                setMode(0f)
                val files = try {
 // audioFormat=入队快照值（防跨重启续传前后产物格式不一致）；quality=当前画质档
                    engine.download(info, mode, cookieFile, taskDir.toPath(), item.audioFormatSnapshot,
                        onLog = { line ->
 // 进度刻度行已由进度条表达，原样行转发其余（防刷屏）
                            if (ProgressParser.percent(line) == null) hooks.onLog(line)
                        },
                        onProgress = { frac, _ -> setMode(frac) },
                        cancelHandle = handle,
                        runId = runId, // 执行键（重试复活后与记录 id 解耦，fix round 1 Important）
                        quality = quality)
                } catch (t: Throwable) {
                    throw t // 失败/取消：上抛由上位甄别；taskDir 保留（上位负责清理判定），不清理
                }
                val newFiles = files.map { it.toFile() }.sortedByDescending { it.length() }
                for ((i, f) in newFiles.withIndex()) {
                    hooks.onIngested(label, env.ingest(f, info.platform) { cp ->
                        setMode(1f, (i + cp) / newFiles.size)
                    })
                }
                setMode(1f)
                hooks.onModeCompleted(mode, newFiles) // P0 写侧：completedModes+=mode、持久化、删临时产物
            }
            taskDir.deleteRecursively() // 全模式成功→清临时目录
        } finally {
            EngineRegistry.unregister(runId) // per-run 注册表生命周期：run 终点注销（键=执行 runId）
        }
    }

 /**
 * 兼容直跑入口（PipelineE2ETest 三处直调不受影响——收口清理项）：内部转 [runItem]，
 * hooks 折叠回 PipelineState/logs 语义（离线即时 Failed、Done 带 results/platforms）。
 */
    suspend fun run(url: String, modes: DownloadModes, sessdata: String?,
                    runId: String = UUID.randomUUID().toString()) = withContext(Dispatchers.IO) {
        check(!busy) { "已有任务在运行" }
        busy = true
        currentRunId = runId
        val results = mutableListOf<Pair<String, Uri>>()
        val platforms = linkedSetOf<String>()
        val hooks = object : QueueRunHooks {
            override fun cancelRequested(): Boolean = EngineRegistry.isCancelled(runId)
            override fun shouldWaitOffline(): Boolean = false // 兼容语义：离线异常即时 Failed（不入队等待）
            override fun onLog(line: String) { _logs.tryEmit(line) }
            override fun onProgress(label: String, fraction: Float) {
                _state.value = PipelineState.Running(label, fraction)
            }
            override fun onIngested(label: String, uri: Uri?) { if (uri != null) results.add(label to uri) }
            override suspend fun onProbeDone(title: String, coverUrl: String?, platform: String, durationSec: Double?) {
                platforms.add(platform)
            }
            override suspend fun onModeCompleted(mode: Mode, files: List<File>) {} // 直跑无队列持久化
        }
        try {
            runItem(
                QueueItem(
                    id = runId,
                    url = url,
                    platform = EngineRouter.routeFor(url).name,
                    modes = modes,
                    sessdataSnapshot = sessdata?.trim()?.takeIf { it.isNotEmpty() },
                    title = url,
                    taskDir = env.newTaskDir().absolutePath,
                    createdAt = System.currentTimeMillis(),
                ),
                hooks,
            )
            _state.value = PipelineState.Done(results.toList(), platforms.toList())
        } catch (ce: CancellationException) {
 // 协程取消穿透（技术债③）：模式段 rethrow 到这里同样不能吞，否则仍会被折成 Failed
            throw ce
        } catch (t: Throwable) {
            _state.value = classifyDownloadFailure(t)
        } finally {
            currentRunId = null
            busy = false
        }
    }
}

/**
 * 模式段失败甄别（类型制，技术债②③清）：
 * - EngineCancelledException（yt-dlp DownloadCancelled 翻译产物 / 原生引擎与 FfmpegPost 取消载体）→ Cancelled
 * - kotlinx CancellationException → 原样 rethrow（协程取消不吞）
 * - EngineException → Failed(人话直达，不过 ErrorMapper)
 * - 其余 → ErrorMapper.humanize
 * top-level internal：JVM 单测直测（管道类持 Context 不可在宿主直构）。
 */
internal fun classifyDownloadFailure(t: Throwable): PipelineState = when (t) {
    is EngineCancelledException -> PipelineState.Cancelled
    is CancellationException -> throw t
    is EngineException -> PipelineState.Failed(t.message ?: "下载失败")
    else -> PipelineState.Failed(ErrorMapper.humanize(t))
}
