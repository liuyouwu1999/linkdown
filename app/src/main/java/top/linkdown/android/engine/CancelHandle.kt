package top.linkdown.android.engine

/**
 * 取消共享文案（原 FfmpegPost.CANCELLED_MESSAGE 迁此）：所有取消载体异常统一携带，供人话展示。
 * 甄别一律按类型（[EngineCancelledException]），文案串不参与甄别（技术债②清）。
 */
const val CANCELLED_MESSAGE = "已取消"

/**
 * 取消句柄（ per-run 取消）：引擎侧轮询取消的唯一入口——引擎不感知 runId/注册表，
 * 管道注入绑定到本 run 的实现（EngineRegistry.handle(runId)）。
 * yt-dlp 取消走 fork per-run 注册表（requestCancel(runId) → DownloadCancelled），此句柄为其
 * 接口统一形态；原生引擎（抖音/汽水）与 FfmpegPost 逐块轮询 isCancelled()。
 */
interface CancelHandle {
    fun isCancelled(): Boolean

 /** 已取消则抛 [EngineCancelledException]（管道按类型甄别进 Cancelled）。 */
    fun throwIfCancelled() {
        if (isCancelled()) throw EngineCancelledException(CANCELLED_MESSAGE)
    }
}

/** 引擎取消语义异常：取消甄别的唯一类型判据（message 串甄别废弃——技术债②清）。 */
class EngineCancelledException(message: String) : EngineException(message)
