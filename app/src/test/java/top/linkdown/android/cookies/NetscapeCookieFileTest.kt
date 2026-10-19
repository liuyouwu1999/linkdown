package top.linkdown.android.cookies

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NetscapeCookieFileTest {
    private val e = NetscapeCookieFile.Entry(".bilibili.com", "/", true, 2145916800L, "SESSDATA", "abc", httpOnly = true)

    @Test fun `round trip preserves httponly prefix`() {
        val back = NetscapeCookieFile.parse(NetscapeCookieFile.serialize(listOf(e)))
        assertEquals(listOf(e), back)
    }
    @Test fun `serialize starts with netscape magic header for ytdlp`() {
 // yt-dlp（Python cookiejar）按首行 magic 校验，缺行整文件拒收：
 // "does not look like a Netscape format cookies file"（真机实测踩坑）
        val text = NetscapeCookieFile.serialize(listOf(e))
        assertTrue(text.startsWith("# Netscape HTTP Cookie File\n"))
        val back = NetscapeCookieFile.parse(text)
        assertEquals(listOf(e), back)
    }
    @Test fun `parse skips comments and blank lines`() {
        val text = "# Netscape HTTP Cookie File\n\n.bilibili.com\tTRUE\t/\tTRUE\t0\tbuvid\tX\n"
        assertEquals(1, NetscapeCookieFile.parse(text).size)
    }
    @Test fun `fromPairs marks all httponly with given domain`() {
        val es = NetscapeCookieFile.fromPairs("SESSDATA=abc; buvid=X", ".bilibili.com")
        assertEquals(2, es.size)
        assertTrue(es.all { it.httpOnly && it.domain == ".bilibili.com" })
        assertEquals("SESSDATA", es[0].name)
    }
}
