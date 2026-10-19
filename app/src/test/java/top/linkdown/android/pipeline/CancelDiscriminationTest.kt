package top.linkdown.android.pipeline

import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test
import top.linkdown.android.engine.CANCELLED_MESSAGE
import top.linkdown.android.engine.EngineCancelledException
import top.linkdown.android.engine.EngineException
import java.io.IOException

/**
 * 功能任务 甄别单测（技术债②③清）：模式段失败按【类型】甄别——
 * EngineCancelledException → Cancelled；kotlinx CancellationException → 原样 rethrow（不吞）；
 * EngineException → Failed(人话直达)；其余 → ErrorMapper。
 * 旧实现按 message 串（"DownloadCancelled"）甄别，FfmpegPost 载体改类型后此测试钉死新甄别制。
 */
class CancelDiscriminationTest {

    @Test fun `EngineCancelledException 进 Cancelled`() {
        assertEquals(PipelineState.Cancelled,
            classifyDownloadFailure(EngineCancelledException(CANCELLED_MESSAGE)))
    }

    @Test fun `EngineCancelledException 优先于 EngineException 分支（子类不被 Failed 吞）`() {
 // 防回归：若把 is EngineException 分支提到首位，取消会被折成 Failed 而非 Cancelled
        assertEquals(PipelineState.Cancelled,
            classifyDownloadFailure(EngineCancelledException(CANCELLED_MESSAGE)))
    }

    @Test fun `CancellationException 必须 rethrow 不吞（技术债已清）`() {
        try {
            classifyDownloadFailure(CancellationException("Job was cancelled"))
            fail("CancellationException 必须穿透（吞掉会让协程取消静默变 Failed）")
        } catch (e: CancellationException) {
            assertEquals("Job was cancelled", e.message)
        }
    }

    @Test fun `EngineException 进 Failed 人话直达不过 ErrorMapper`() {
        assertEquals(PipelineState.Failed("抖音图集暂不支持"),
            classifyDownloadFailure(EngineException("抖音图集暂不支持")))
    }

    @Test fun `其余异常走 ErrorMapper 人话`() {
        assertEquals(PipelineState.Failed("网络不给力：请检查网络后重试"),
            classifyDownloadFailure(IOException("Unable to download JSON metadata: no route")))
    }
}
