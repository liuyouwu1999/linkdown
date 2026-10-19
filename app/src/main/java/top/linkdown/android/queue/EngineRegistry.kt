package top.linkdown.android.queue

import dev.ffmpegkit_maintained.ytdlp.YtDlp
import top.linkdown.android.engine.CANCELLED_MESSAGE
import top.linkdown.android.engine.CancelHandle
import top.linkdown.android.engine.EngineCancelledException
import java.util.concurrent.ConcurrentHashMap

/**
 * per-run 取消注册表（按设计）：runId → Kotlin 侧取消旗标，probe/download 全程有效。
 * Python 侧注册表（fork ytdlp_runner.py `_cancel_registry`）仅 download 段生效——progress 钩子
 * 只挂下载段，probe=simulate 无 progress 事件；probe 窗口/模式间隙的取消由 Kotlin 旗标兜住
 * （ probe 窗口守卫的 形态）。
 *
 * ⚠️ requestCancel 仅对已起跑的 run 有意义：Python 侧 execute/execute_debug 入口
 * `_reset_cancel(run_id)` 会抹掉起跑前的注册表条目，未起跑 run 的 Python 侧取消请求起跑即被清零
 * （Kotlin 旗标不受此影响；本类 register 同法清残留）。
 *
 * cancel(runId) **双侧派发**：置 Kotlin 旗标 + 无条件 `YtDlp.requestCancel(runId)`——
 * 漏调后者则 yt-dlp 真取消静默失效（下载不停，只被 Kotlin 守卫在模式间隙截断）。
 */
object EngineRegistry {
    private val cancelled = ConcurrentHashMap.newKeySet<String>()

 /** run 起点登记：清残留旗标（幂等，≙Python 侧入口 _reset_cancel(run_id) 语义）。 */
    fun register(runId: String) {
        cancelled.remove(runId)
    }

 /** run 终点注销（管道 finally 调）。 */
    fun unregister(runId: String) {
        cancelled.remove(runId)
    }

 /** 取消 = 双侧派发：Kotlin 旗标（probe 窗口/模式间隙守卫与原生引擎轮询读）+ Python 注册表。 */
    fun cancel(runId: String) {
        cancelled.add(runId)
        YtDlp.requestCancel(runId)
    }

 /** 仅 JVM 测试缝：置 Kotlin 旗标、不打 Python 注册表（YtDlp.requestCancel 依赖 Chaquopy
 * 不可在 JVM 调—— 起队列测试经 cancelDispatch 缝绕开；管道级键同步测试同款约束）。
 * 生产路径恒经 [cancel] 双侧派发，本函数不在任何生产调用点。 */
    internal fun flagCancelledJvm(runId: String) {
        cancelled.add(runId)
    }

    fun isCancelled(runId: String): Boolean = cancelled.contains(runId)

 /** 管道构造绑定本 run 的 [CancelHandle]（引擎轮询入口，引擎不感知 runId）。 */
    fun handle(runId: String): CancelHandle = object : CancelHandle {
        override fun isCancelled(): Boolean = this@EngineRegistry.isCancelled(runId)
        override fun throwIfCancelled() {
            if (isCancelled()) throw EngineCancelledException(CANCELLED_MESSAGE)
        }
    }
}
