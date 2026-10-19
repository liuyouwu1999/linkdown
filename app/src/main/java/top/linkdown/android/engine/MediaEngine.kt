package top.linkdown.android.engine

import java.io.File
import java.nio.file.Path
import top.linkdown.android.engine.internal.ChildRef

/**
 * 引擎抽象（ 设计，签名逐字；一处已批勘误：cookies 实际类型为 File?——
 * CookieStore 是无状态 object 工厂，管道实际持有 cookieFile: File?。
 * 追加两参数：runId 仅 YtdlpEngine 消费（fork execute* 必填 run_id 形参，probe/download/
 * requestCancel 共用同一 runId）；cancelHandle 为取消轮询唯一入口（原 isCancelled lambda，
 * 技术债②类型甄别的载体）。两者原生引擎均忽略，语义同 cookies。
 * 追加 quality：画质档（"max"/"1080p"），仅 YtdlpEngine VIDEO 消费（extraOpts 限档），
 * Douyin/Qishui 忽略（原生直链无档位概念）。
 * 两段式：probe 每任务恰一次；download 按 mode 循环、probe 产物复用。
 */
interface MediaEngine {
    suspend fun probe(url: String, cookies: File?, runId: String = ""): ProbeInfo
    suspend fun download(info: ProbeInfo, mode: Mode, cookies: File?, taskDir: Path,
                         audioFormat: String, onLog: (String) -> Unit,
                         onProgress: (Float, String) -> Unit,
                         cancelHandle: CancelHandle, runId: String = "",
                         quality: String = "max"): List<Path>

 /**
 * 批量枚举：默认 children 恒空实现——原生引擎非批量（「= emptyList<ChildRef>()
 * 默认空实现」，entriesTotal=0 → 调用方判非批量走单视频路径；YtdlpEngine override 出真枚举）。
 * ⚠️ 取消语义：flat 抽取（列表网络拉取）无 progress 事件，requestCancel(runId) 的
 * progress_hooks 取消钩子不会 fire、杀不掉进行中的抽取——取消=丢弃本 Future 结果不入队，
 * Future 自然结束（勿在此造死等/强杀）。
 */
    suspend fun enumerate(url: String, cookies: File?, runId: String = ""): BatchInfo =
        BatchInfo(title = "", children = emptyList(), entriesTotal = 0)
}

data class ProbeInfo(
    val platform: String,
    val mediaId: String,
    val sourceUrl: String,   // probe 原样带回的原始链接（YtdlpEngine.download 必需）
    val title: String,
    val author: String,
    val coverUrl: String?,
    val durationSec: Double?,
    val isVip: Boolean,
    val playUrl: String?,    // 仅原生直链引擎消费（yt-dlp 平台恒 null）
    val hasImages: Boolean,
)

enum class Mode { VIDEO, AUDIO, COVER }

/** 引擎语义失败（人话文案直达 UI，交管道 Failed(e.message)，不过 ErrorMapper）。
 * open：EngineCancelledException（取消甄别的类型载体）为其子类。 */
open class EngineException(message: String) : Exception(message)
