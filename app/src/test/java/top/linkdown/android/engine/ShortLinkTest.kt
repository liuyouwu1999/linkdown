package top.linkdown.android.engine

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import top.linkdown.android.engine.internal.canonicalBilibiliOffline
import top.linkdown.android.engine.internal.expandShortlink
import top.linkdown.android.engine.internal.isShortlinkUrl
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * 短链展开单测（参考实现 shortlink.py 的 Android 平移：白名单 + GET 手动跟 5 跳重定向）。
 * 展开走 MockWebServer（下载流量不得出网）；白名单为纯函数直测。
 * 背景：yt-dlp 提取器不认 b23.tv 等短域，裸短链落 generic 提取器报错——与参考实现一致在调引擎前展开。
 */
class ShortLinkTest {
    private lateinit var server: MockWebServer
    private val client = OkHttpClient.Builder()
        .followRedirects(false)
        .connectTimeout(5, TimeUnit.SECONDS).readTimeout(5, TimeUnit.SECONDS).build()

    @Before fun setUp() { server = MockWebServer(); server.start() }
    @After fun tearDown() { server.shutdown() }

    // ------------------------------------------------------------------
    // 白名单纯函数（参考实现 EXPAND_HOSTS：v.douyin.com/b23.tv/qishui.douyin.com/163cn.tv）
    // ------------------------------------------------------------------

    @Test fun `白名单四域名命中大小写不敏感`() {
        assertTrue(isShortlinkUrl("https://b23.tv/ep84798"))
        assertTrue(isShortlinkUrl("https://B23.TV/ep84798"))
        assertTrue(isShortlinkUrl("https://v.douyin.com/abc123/"))
        assertTrue(isShortlinkUrl("https://qishui.douyin.com/song/xyz"))
        assertTrue(isShortlinkUrl("https://163cn.tv/abc"))
    }

    @Test fun `非白名单与畸形输入原样放行`() {
        assertFalse(isShortlinkUrl("https://www.bilibili.com/bangumi/play/ep84798"))
        assertFalse(isShortlinkUrl("https://bilibili.com/video/BVxx"))
        assertFalse(isShortlinkUrl("https://evil-b23.tv/ep1"))   // 后缀伪装不命中
        assertFalse(isShortlinkUrl("https://youtube.com/watch?v=1"))
        assertFalse(isShortlinkUrl("not a url"))
        assertFalse(isShortlinkUrl(""))
    }

    // ------------------------------------------------------------------
    // b23.tv 纯 id 形态离线直拼（短链域 DNS 可被污染，网络展开不可依赖——真机实测）
    // ------------------------------------------------------------------

    @Test fun `b23纯id形态离线拼canonical不出网`() {
        assertEquals("https://www.bilibili.com/bangumi/play/ep84798",
            canonicalBilibiliOffline("https://b23.tv/ep84798"))
        assertEquals("https://www.bilibili.com/bangumi/play/ss2851",
            canonicalBilibiliOffline("https://B23.TV/ss2851/"))
        assertEquals("https://www.bilibili.com/video/BV1xx411c7mD",
            canonicalBilibiliOffline("https://b23.tv/BV1xx411c7mD"))
        assertEquals("https://www.bilibili.com/video/av170001",
            canonicalBilibiliOffline("https://b23.tv/av170001"))
    }

    @Test fun `b23非id形态与非b23域名不做离线拼接`() {
        assertNull("随机短码形态必须走网络展开", canonicalBilibiliOffline("https://b23.tv/aB3xY9"))
        assertNull(canonicalBilibiliOffline("https://www.bilibili.com/bangumi/play/ep84798"))
        assertNull(canonicalBilibiliOffline("https://evil-b23.tv/ep84798"))
        assertNull(canonicalBilibiliOffline("not a url"))
    }

    // ------------------------------------------------------------------
    // expandShortlink（参考实现 expand：GET+手动跟跳，Location 缺失即停）
    // ------------------------------------------------------------------

    private fun redirect(location: String, code: Int = 302) = MockResponse()
        .setResponseCode(code).setHeader("Location", location)

    @Test fun `单跳302绝对Location展开为目标`() = runBlocking {
        server.enqueue(redirect("${server.url("/final?x=1")}"))
        server.enqueue(MockResponse().setResponseCode(200))
        val out = expandShortlink("${server.url("/short")}", client)
        assertEquals(server.url("/final?x=1").toString(), out)
    }

    @Test fun `链式重定向含相对Location逐跳拼接`() = runBlocking {
        server.enqueue(redirect("/step2"))
        server.enqueue(redirect("${server.url("/landing")}"))
        server.enqueue(MockResponse().setResponseCode(200))
        val out = expandShortlink("${server.url("/a")}", client)
        assertEquals(server.url("/landing").toString(), out)
    }

    @Test fun `301_303_307_308均跟随`() = runBlocking {
        listOf(301, 303, 307, 308).forEach { code ->
            server.enqueue(redirect("/tail", code))
            server.enqueue(MockResponse().setResponseCode(200))   // 跟随后对 /tail 的收尾 GET
            assertEquals("跟随 $code", server.url("/tail").toString(),
                expandShortlink("${server.url("/s$code")}", client))
        }
    }

    @Test fun `非重定向响应原样返回当前链接`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody("ok"))
        val url = "${server.url("/plain")}"
        assertEquals(url, expandShortlink(url, client))
    }

    @Test fun `重定向无Location头部即停`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(302))
        val url = "${server.url("/noloc")}"
        assertEquals(url, expandShortlink(url, client))
    }

    @Test fun `超过跳数上限停止不再死循环`() = runBlocking {
        val n = AtomicInteger()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(r: RecordedRequest): MockResponse =
                redirect("/next-${n.incrementAndGet()}")
        }
        val out = expandShortlink("${server.url("/loop")}", client, maxHops = 5)
        assertEquals("第 5 跳后停在最新 Location", server.url("/next-5").toString(), out)
        assertEquals("恰好发起 5 次请求", 5, server.requestCount)
    }

    @Test fun `网络失败异常向外抛由调用方兜底`() {
        val dead = OkHttpClient.Builder()
            .followRedirects(false)
            .connectTimeout(1, TimeUnit.SECONDS).readTimeout(1, TimeUnit.SECONDS).build()
        val url = server.url("/gone").toString()
        server.shutdown()
        try {
            runBlocking { expandShortlink(url, dead) }
            fail("应抛出异常")
        } catch (e: IOException) { /* 预期 */ }
    }
}
