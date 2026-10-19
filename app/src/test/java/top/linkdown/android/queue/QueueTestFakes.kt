package top.linkdown.android.queue

import android.net.Uri
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import top.linkdown.android.engine.BatchInfo
import top.linkdown.android.engine.CANCELLED_MESSAGE
import top.linkdown.android.engine.CancelHandle
import top.linkdown.android.engine.EngineCancelledException
import top.linkdown.android.engine.EngineException
import top.linkdown.android.engine.MediaEngine
import top.linkdown.android.engine.Mode
import top.linkdown.android.engine.ProbeInfo
import top.linkdown.android.pipeline.PipelineEnv
import java.io.File
import java.nio.file.Path

/** 假引擎每模式行为脚本。 */
sealed class FakeBehavior {
 /** 写文件即成功返回。 */
    data object Complete : FakeBehavior()

 /** 抛指定异常（有网 Failed / 断网 WaitingNetwork 两向判定用）。 */
    class Fail(val err: Throwable) : FakeBehavior()

 /** 挂起等待放行 gate / 取消（Running 取消三态用）；errAfterGate 非空=放行后抛错（WaitingNetwork 两向判定用）。 */
    class Hang(
        val gate: CompletableDeferred<Unit> = CompletableDeferred(),
        val errAfterGate: Throwable? = null,
    ) : FakeBehavior()
}

/**
 * 假 MediaEngine：probe 返回固定摘要（或抛 probeError）；download 按 behavior 脚本执行，
 * 向 taskDir 写真实文件（断言 completedModes 写侧的临时产物删除）。记录收到的
 * modes/cookies 供折叠跳过与 cookie 接线断言。
 */
class FakeEngine(
    val probeInfo: ProbeInfo = ProbeInfo(
        platform = "汽水", mediaId = "m1", sourceUrl = "", title = "测试标题",
 // author 空：汽水判重基名拼 `作者 - ` 前缀），接线测试不涉该语义
        author = "",
        coverUrl = "http://cover/1.jpg", durationSec = 183.5, isVip = false, playUrl = null,
        hasImages = false,
    ),
    var probeError: Throwable? = null,
) : MediaEngine {

    val behavior = mutableMapOf<Mode, FakeBehavior>()
    val modesRequested = ArrayList<Mode>()
    val cookiesSeen = ArrayList<String?>() // cookieFile 内容（SESSDATA=...）或 null
    val audioFormatsSeen = ArrayList<String>() // M4：管道透传的 audioFormat 快照值
    val qualitySeen = ArrayList<String>() // M4：管道透传的画质档
    val probesRequested = ArrayList<String>()
    var probeDelayGate: CompletableDeferred<Unit>? = null // probe 窗口取消用
 // 批量：入口枚举缝（pipeline.enumerate 透传断言）
    val enumerateCalls = ArrayList<String>()
    var enumerateResult: BatchInfo? = null
    var enumerateError: Throwable? = null
    val runIdsSeen = ArrayList<String>() // probe/download 收到的执行 runId（执行键同步断言，fix round 1）

    override suspend fun enumerate(url: String, cookies: File?, runId: String): BatchInfo {
        enumerateCalls += url
        cookiesSeen += cookies?.readText()
        enumerateError?.let { throw it }
        return enumerateResult ?: BatchInfo(title = "", children = emptyList(), entriesTotal = 0)
    }

    override suspend fun probe(url: String, cookies: File?, runId: String): ProbeInfo {
        runIdsSeen += runId
        probesRequested += url
        cookiesSeen += cookies?.readText()
        probeDelayGate?.let { it.await() }
        probeError?.let { throw it }
        return probeInfo.copy(sourceUrl = url)
    }

    override suspend fun download(
        info: ProbeInfo, mode: Mode, cookies: File?, taskDir: Path,
        audioFormat: String, onLog: (String) -> Unit,
        onProgress: (Float, String) -> Unit,
        cancelHandle: CancelHandle, runId: String, quality: String,
    ): List<Path> {
        runIdsSeen += runId
        modesRequested += mode
        cookiesSeen += cookies?.readText()
        audioFormatsSeen += audioFormat
        qualitySeen += quality
        val f = File(taskDir.toFile(), "out-${mode.name.lowercase()}.bin")
        f.writeText("data-$mode")
        when (val b = behavior[mode]) {
            is FakeBehavior.Fail -> throw b.err
            is FakeBehavior.Hang -> {
 // 模拟长时间下载：轮询取消句柄（真引擎同款语义），放行 gate 或取消即退出
                while (!cancelHandle.isCancelled() && !b.gate.isCompleted) delay(1)
                if (cancelHandle.isCancelled()) throw EngineCancelledException(CANCELLED_MESSAGE)
                b.errAfterGate?.let { throw it }
            }
            else -> {}
        }
        onProgress(1f, "")
        onLog("[download] $mode done")
        return listOf(f.toPath())
    }
}

/** 假 NetworkMonitor：测试手动拨 online/offline，恢复沿真实路径触发 onRestored 回调。 */
class FakeNetworkMonitor : NetworkMonitor {
    var online = true
        private set
    var onRestored: (() -> Unit)? = null
        private set
    var started = false
        private set

    override fun isOnline(): Boolean = online
    override fun start(onRestored: () -> Unit) { this.onRestored = onRestored; started = true }
    override fun stop() { started = false }

    fun goOffline() { online = false }
    fun goOnline() {
        val was = online
        online = true
        if (!was) onRestored?.invoke() // 仅「恢复」沿真实回调路径
    }
}

/** 假环境：任务目录=临时 root 下递增目录；ingest 记录拷贝；引擎按 URL 精确注册。 */
class FakeEnv(private val root: File) : PipelineEnv {
    val engines = LinkedHashMap<String, FakeEngine>()
    val ingested = ArrayList<Pair<String, String>>() // (platform, fileName)
    val cookieRequests = ArrayList<String?>()
    val taskDirs = ArrayList<File>()
    val runOrder = ArrayList<String>() // runItem 起跑顺序（engineFor 调用序=派发顺序）
    var taskDirCounter = 0

 /** 库判重缝：默认空货架（零命中，既有用例零影响）；判重用例注入假落架名单。 */
    override var dedup: DedupCheck = DedupCheck { emptyList() }

    fun register(url: String, engine: FakeEngine) { engines[url] = engine }
    override fun engineFor(url: String): MediaEngine {
        runOrder.add(url)
        return engines[url] ?: error("FakeEnv: no engine registered for $url")
    }

    override fun newTaskDir(): File =
        File(root, "td-${taskDirCounter++}").apply { mkdirs() }.also { taskDirs.add(it) }

    override fun cookieFile(url: String, sessdata: String?): File? {
        cookieRequests.add(sessdata)
        return sessdata?.let {
            File(root, "cookie.txt").apply { writeText("SESSDATA=$it") }
        }
    }

    override fun ingest(src: File, platform: String, onProgress: (Float) -> Unit): Uri? {
        ingested.add(platform to src.name)
        onProgress(1f)
        return null // JVM 无 Android 框架：Uri 仅签名占位，队列层不消费其值
    }
}
