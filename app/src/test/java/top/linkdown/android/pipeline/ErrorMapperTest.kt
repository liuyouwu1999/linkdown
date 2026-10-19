package top.linkdown.android.pipeline

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.IOException

class ErrorMapperTest {
    private fun e(msg: String) = RuntimeException(msg)
    @Test fun `maps known signatures`() {
        assertEquals("已取消", ErrorMapper.humanize(e("DownloadCancelled: The download was cancelled")))
        assertEquals("网络不给力：请检查网络后重试", ErrorMapper.humanize(e("Unable to download JSON metadata: boom")))
        assertEquals("网络不给力：请检查网络后重试", ErrorMapper.humanize(e("caused by [Errno 7] No address associated with hostname")))
        assertEquals("网络不给力：请检查网络后重试", ErrorMapper.humanize(IOException("connect timed out")))
        assertEquals("暂不支持这个链接（支持平台见说明）", ErrorMapper.humanize(e("Unsupported URL: abc")))
        assertEquals("暂不支持这个链接（支持平台见说明）", ErrorMapper.humanize(e("No video formats found（手动重试仍失败）")))
        assertEquals("内部错误 C1（cookie 文件损坏，删 App 数据可解）", ErrorMapper.humanize(e("x does not look like a Netscape format cookies file")))
    }
    @Test fun `login wall signatures map to login prompt with platform passthrough`() {
 // 登录墙三签名（大小写不敏感——yt-dlp 实际输出「Login required」首字母大写）→ 逐字人话 + 平台名透传
        assertEquals("需要登录：请在主页「登录账号」里登录 哔哩哔哩",
            ErrorMapper.humanize(e("ERROR: [bilibili] 123: Login required to view this video"), "哔哩哔哩"))
        assertEquals("需要登录：请在主页「登录账号」里登录 YouTube",
            ErrorMapper.humanize(e("ERROR: [youtube] abc: Sign in to confirm your age"), "YouTube"))
        assertEquals("需要登录：请在主页「登录账号」里登录 网易云",
            ErrorMapper.humanize(e("ERROR: [netease] 456: members on this channel only"), "网易云"))
    }
    @Test fun `login wall without platform falls back to default name`() {
        assertEquals("需要登录：请在主页「登录账号」里登录 B站",
            ErrorMapper.humanize(e("ERROR: [bilibili] 123: Login required")))
        assertEquals("需要登录：请在主页「登录账号」里登录 B站",
            ErrorMapper.humanize(e("Login required"), " "))
    }
    @Test fun `login wall takes precedence over fallback`() {
 // 多行日志首行是无关噪声时仍按签名命中（不落 fallback 首行截断）
        assertEquals("需要登录：请在主页「登录账号」里登录 哔哩哔哩",
            ErrorMapper.humanize(e("line one noise\nlogin required: please log in"), "哔哩哔哩"))
    }
    @Test fun `fallback keeps first line`() {
        assertEquals("下载失败：line one", ErrorMapper.humanize(e("line one\nline two")))
    }
    @Test fun `null and empty message fallback`() {
        assertEquals("下载失败：未知错误", ErrorMapper.humanize(RuntimeException()))
        assertEquals("下载失败：未知错误", ErrorMapper.humanize(RuntimeException("")))
    }
}
