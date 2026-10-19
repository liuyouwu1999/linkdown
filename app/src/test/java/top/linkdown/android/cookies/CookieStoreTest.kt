package top.linkdown.android.cookies

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File

/**
 * 功能任务：CookieStore netease 探测 + 路由平台→cookie 文件选择（设计「平台化 cookie 文件」）。
 * JVM 纯函数直测（java.io.File 临时目录；选择逻辑=PlatformDirs.dirFor 路由平台名的 when 纯函数）。
 */
class CookieStoreTest {

    private fun tempRoot(): File =
        File(System.getProperty("java.io.tmpdir"), "cs-${System.nanoTime()}").apply { mkdirs() }

    private fun seed(root: File, platform: String, content: String = "# Netscape HTTP Cookie File"): File {
        val dir = File(root, "cookies").apply { mkdirs() }
        return File(dir, "$platform.txt").apply { writeText(content) }
    }

    @Test fun `netease 探测既有 netease_txt 无则 null`() {
        val root = tempRoot()
        try {
            assertNull(CookieStore.netease(root))
            val f = seed(root, "netease")
            assertEquals(f, CookieStore.netease(root))
        } finally { root.deleteRecursively() }
    }

    @Test fun `网易云路由选 netease_txt`() {
        val root = tempRoot()
        try {
            val url = "https://music.163.com/song?id=22831636"
            assertNull("无 cookie 无快照 → null（匿名下载公开内容照常）", CookieStore.forUrl(url, null, root))
            val f = seed(root, "netease")
            assertEquals(f, CookieStore.forUrl(url, null, root))
 // 手输 sessdata 通道是 B站 专用：网易云忽略 sessdata、只认 netease.txt
            assertEquals(f, CookieStore.forUrl(url, "abc", root))
        } finally { root.deleteRecursively() }
    }

    @Test fun `B站路由维持现状 sessdata 写入与既有探测`() {
        val root = tempRoot()
        try {
            val url = "https://www.bilibili.com/video/BV1xx411c7mD"
            assertNull(CookieStore.forUrl(url, null, root))
            val existing = seed(root, "bilibili")
            assertEquals("既有 bilibili.txt 探测（无快照）", existing, CookieStore.forUrl(url, null, root))
            val written = CookieStore.forUrl("https://b23.tv/abc", "abc123", root)!! // b23.tv 短链同归哔哩哔哩
            assertEquals(existing, written)
            val entry = NetscapeCookieFile.parse(written.readText()).single()
            assertEquals("快照写入 SESSDATA 条目", "SESSDATA" to "abc123", entry.name to entry.value)
        } finally { root.deleteRecursively() }
    }

    @Test fun `其他平台一律 null`() {
        val root = tempRoot()
        try {
            seed(root, "bilibili"); seed(root, "netease") // cookie 文件存在也不串台
            assertNull(CookieStore.forUrl("https://www.youtube.com/watch?v=x", "abc", root))
            assertNull(CookieStore.forUrl("https://v.douyin.com/abcd/", null, root))
            assertNull(CookieStore.forUrl("https://www.qishui.com/s/123", null, root))
        } finally { root.deleteRecursively() }
    }
}
