package top.linkdown.android.engine

import dev.ffmpegkit_maintained.ytdlp.YtDlpException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test
import java.io.IOException

/**
 * 功能任务 甄别单测（技术债②清）：fork yt-dlp 取消 = YtDlpException 且 message 含
 * DownloadCancelled → 翻译抛 EngineCancelledException（类型甄别载体，管道 Cancelled 判据）。
 * [linkdown] cancelled marker 走 python.stdout→logcat，不经 LogCallback，翻译层不影响 marker 链路。
 */
class YtdlpCancelTranslateTest {

    @Test fun `DownloadCancelled 签名翻译为 EngineCancelledException`() {
        val t = translateYtdlpCancellation(
            YtDlpException("ERROR: [download] DownloadCancelled: 已请求中断下载"))
        assertEquals(EngineCancelledException::class, t::class)
        assertEquals(CANCELLED_MESSAGE, t.message)
    }

    @Test fun `多行 stderr 里 DownloadCancelled 行中也翻译`() {
 // fork 抛出形态：ExecutionException.cause=YtDlpException(全量 stderr 拼接)
        val t = translateYtdlpCancellation(
            YtDlpException("[download] ERROR: got error: DownloadCancelled()\nERROR: -x"))
        assertEquals(EngineCancelledException::class, t::class)
    }

    @Test fun `普通 YtDlpException 原样透传`() {
        val t: Throwable = YtDlpException("ERROR: Unsupported URL")
        assertSame(t, translateYtdlpCancellation(t))
    }

    @Test fun `非 YtDlp 异常原样透传`() {
        val t: Throwable = IOException("getaddrinfo failed")
        assertSame(t, translateYtdlpCancellation(t))
    }
}
