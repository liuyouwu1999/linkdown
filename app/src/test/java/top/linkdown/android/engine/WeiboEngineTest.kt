package top.linkdown.android.engine

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import top.linkdown.android.engine.internal.safeFilename
import java.io.File
import java.nio.file.Files
import java.nio.file.Path

/**
 * WeiboEngine fixture 单测（MockWebServer + visitor/api 基址注入缝）。
 * 语义锚点 = 2026-10 真机 curl spike：genvisitor2 JSONP 回包 → incarnate 种 Cookie →
 * statuses/show（ok 门 + 首试失败走 incarnate 重试一次）→ media_info 两代结构 →
 * 直链下载（Referer 必带：Range 无 Referer 会 403）。mid→bid 用真机验证过的真实配对。
 */
class WeiboEngineTest {
    @get:Rule val tmp = TemporaryFolder()

    companion object {
 // 真实配对（spike 实测：statuses/show 返回的 mid 经 base62 转换 = bid）
        const val REAL_MID = "5356225973978859"
        const val REAL_BID = "RnAH3gH59"
        val VIDEO_BYTES = ByteArray(4000) { i -> "VIDEO"[i % 5].code.toByte() }
        val IMG_BYTES = byteArrayOf(9, 8, 7, 6)
    }

    private var server: MockWebServer? = null
    private val requestLog = mutableListOf<String>()
    private val cookieLog = mutableListOf<String>()

 // 可按用例改写的桩
    private var statusesResponses = ArrayDeque<MockResponse>()
    private var videoResponse: MockResponse = MockResponse().setResponseCode(200)
    private var posterResponse: MockResponse = MockResponse().setResponseCode(200)

    @After fun tearDown() {
        server?.shutdown()
    }

    private fun statusesFixture(bid: String, mediaHd: String, mediaSd: String): MockResponse {
        val json = """
            {"ok":1,"data":{
              "user":{"screen_name":"央视新闻"},
              "status_title":"#主播说联播# 智竞未来",
              "text":"<a href=\"x\">正文链接</a> 内容正文",
              "page_info":{"type":"video",
                "page_pic":{"url":"%POSTER%"},
                "media_info":{"stream_url_hd":"%HD%","stream_url":"%SD%","duration":"141.317"}}
            }}
        """.trimIndent()
            .replace("%POSTER%", "http://localhost:1/media/poster.jpg")
            .replace("%HD%", mediaHd)
            .replace("%SD%", mediaSd)
        return MockResponse().setResponseCode(200).setBody(json)
    }

    private fun startServer(
 // 惰性：statuses 桩在 server 起来后按 base 构造（默认=ok fixture）
        statusesQueue: ((base: String) -> List<MockResponse>)? = null,
    ): WeiboEngine {
        val s = MockWebServer()
 // dispatcher 动态读 statusesResponses；桩在 start 后按真实端口构造（url() 须在 start 后）
        s.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requestLog += request.method + " " + request.path
                request.getHeader("Cookie")?.let { cookieLog += it }
                return when {
                    request.path?.startsWith("/visitor/genvisitor2") == true -> MockResponse().setResponseCode(200).setBody(
                        """window.visitor_gray_callback && visitor_gray_callback(
                            {"retcode":20000000,"msg":"succ","data":{"sub":"SUBVAL","subp":"SUBPVAL",
                             "tid":"TIDVAL","new_tid":true}});"""
                    )
                    request.path?.startsWith("/visitor/visitor?a=incarnate") == true -> MockResponse()
                        .setResponseCode(200)
                        .addHeader("Set-Cookie", "SUB=SUB2; Path=/")
                        .addHeader("Set-Cookie", "SUBP=SUBP2; Path=/")
                        .setBody("cross_domain")
                    request.path?.startsWith("/statuses/show") == true ->
                        statusesResponses.removeFirstOrNull()
                            ?: MockResponse().setResponseCode(200).setBody("""{"ok":0}""")
                    request.path?.contains("video.mp4") == true -> videoResponse
                    request.path?.contains("poster.jpg") == true -> posterResponse
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        s.start()
        val base0 = s.url("/").toString().trimEnd('/')
        statusesResponses = ArrayDeque(statusesQueue?.invoke(base0) ?: listOf(statusesFixture(base0, "ok")))
        server = s
        return WeiboEngine(visitorBaseUrl = base0, apiBaseUrl = base0, ffmpegPath = "ffmpeg")
    }

 /** fixture：媒体地址运行时重写到 MockWebServer（下载流量不得出网）。 */
    private fun statusesFixture(base: String, tag: String): MockResponse {
        val json = """
            {"ok":1,"data":{
              "user":{"screen_name":"央视新闻"},
              "status_title":"#主播说联播# 智竞未来",
              "text":"<a href=\"x\">正文链接</a> 内容正文",
              "page_info":{"type":"video",
                "page_pic":{"url":"$base/media/poster.jpg?tag=$tag"},
                "media_info":{"stream_url_hd":"$base/media/video.mp4?tag=$tag",
                              "stream_url":"$base/media/video_sd.mp4?tag=$tag",
                              "duration":"141.317"}}
            }}
        """.trimIndent()
        return MockResponse().setResponseCode(200).setBody(json)
    }

 // ------------------------------------------------------------------
 // bid 提取 / mid→bid 转换（纯函数）
 // ------------------------------------------------------------------

    @Test fun extractBid_mWeiboStatus_keptAsIs() {
        val e = startServer()
        assertEquals(REAL_BID, e.extractBid("https://m.weibo.cn/status/${REAL_BID}"))
 // 数字 bid 原样（API 直接受）
        assertEquals("5125617794941186", e.extractBid("https://m.weibo.cn/status/5125617794941186"))
    }

    @Test fun extractBid_weiboCom_numericMid_converts() {
        val e = startServer()
        assertEquals(REAL_BID, e.extractBid("https://weibo.com/2656274875/$REAL_MID"))
 // tv/show 形态（可能带 1034: 前缀）
        assertEquals(REAL_BID, e.extractBid("https://weibo.com/tv/show/1034:$REAL_MID?from=feed"))
    }

    @Test fun extractBid_nonWeibo_returnsNull() {
        val e = startServer()
        assertNull(e.extractBid("https://www.bilibili.com/video/BV1xx411c7mD"))
        assertNull(e.extractBid("https://weibo.com/"))
        assertNull(e.extractBid("not a url"))
    }

    @Test fun midToBid_realPair_roundTrips() {
        val e = startServer()
        assertEquals(REAL_BID, e.midToBid(REAL_MID))
    }

 // ------------------------------------------------------------------
 // probe
 // ------------------------------------------------------------------

    @Test fun probe_parsesFixture_andUsesVisitorCookies() = runBlocking {
        val e = startServer()
        val info = e.probe("https://m.weibo.cn/status/${REAL_BID}", cookies = null)
        assertEquals("微博", info.platform)
        assertEquals(REAL_BID, info.mediaId)
        assertEquals("央视新闻", info.author)
        assertEquals("#主播说联播# 智竞未来", info.title)
 // hd 优先
        assertTrue(info.playUrl!!.contains("video.mp4"))
        assertEquals(141.317, info.durationSec!!, 0.001)
        assertTrue(info.coverUrl!!.contains("poster.jpg"))
 // 访客链：genvisitor2 → statuses/show（Cookie 头带 sub/subp）
        assertTrue(requestLog.any { it.startsWith("POST /visitor/genvisitor2") })
        assertTrue(cookieLog.any { it.contains("SUB=SUBVAL") && it.contains("SUBP=SUBPVAL") })
    }

    @Test fun probe_firstShowFails_retriesAfterIncarnate() = runBlocking {
        val fail = MockResponse().setResponseCode(200).setBody("""{"ok":0}""")
        val e = startServer { base -> listOf(fail, statusesFixture(base, "retry")) }
        val info = e.probe("https://m.weibo.cn/status/${REAL_BID}", cookies = null)
        assertEquals("央视新闻", info.author)
        assertTrue("incarnate 被走到", requestLog.any { it.startsWith("GET /visitor/visitor?a=incarnate") })
 // 重试后的请求应带 incarnate 收获的 SUB2
        assertTrue(cookieLog.any { it.contains("SUB=SUB2") })
    }

    @Test fun probe_videoPostWithoutMedia_throws() = runBlocking {
        val topic = MockResponse().setResponseCode(200).setBody(
            """{"ok":1,"data":{"user":{"screen_name":"某人"},"status_title":"超话帖",
                 "page_info":{"type":"topic","page_pic":{"url":"http://localhost:1/p.jpg"}}}}"""
        )
        val e = startServer { base -> listOf(topic) }
        try {
            e.probe("https://m.weibo.cn/status/${REAL_BID}", cookies = null)
            fail("应当抛 NO_PLAY")
        } catch (ex: EngineException) {
            assertTrue(ex.message!!.contains("视频"))
        }
    }

 // ------------------------------------------------------------------
 // download
 // ------------------------------------------------------------------

    @Test fun download_video_streamsMp4AndCompanionCover() = runBlocking {
        val video = MockResponse().setResponseCode(200).setBody(Buffer().write(VIDEO_BYTES))
        val poster = MockResponse().setResponseCode(200).setBody(Buffer().write(IMG_BYTES))
        videoResponse = video
        posterResponse = poster
        val e = startServer()
        val info = e.probe("https://m.weibo.cn/status/${REAL_BID}", cookies = null)
        val dir: Path = tmp.newFolder("task").toPath()
        val products = e.download(info, Mode.VIDEO, cookies = null, taskDir = dir,
            audioFormat = "mp3", onLog = {}, onProgress = { _, _ -> },
            cancelHandle = noCancel(), quality = "max")
        assertEquals(2, products.size)
        val mp4 = products[0]
        assertEquals("mp4", mp4.toString().substringAfterLast('.'))
        assertTrue(mp4.fileName.toString().startsWith("央视新闻 - "))
        Files.readAllBytes(mp4).let { assertEquals(VIDEO_BYTES.size, it.size) }
        val cover = products[1]
        assertTrue(cover.fileName.toString().endsWith("-cover.jpg"))
    }

    @Test fun download_cover_onlyWritesPoster() = runBlocking {
        val poster = MockResponse().setResponseCode(200).setBody(Buffer().write(IMG_BYTES))
        posterResponse = poster
        val e = startServer()
        val info = e.probe("https://m.weibo.cn/status/${REAL_BID}", cookies = null)
        val dir: Path = tmp.newFolder("task2").toPath()
        val products = e.download(info, Mode.COVER, cookies = null, taskDir = dir,
            audioFormat = "mp3", onLog = {}, onProgress = { _, _ -> },
            cancelHandle = noCancel(), quality = "max")
        assertEquals(1, products.size)
        assertTrue(products[0].fileName.toString().endsWith(".jpg"))
    }

    private fun noCancel(): CancelHandle = object : CancelHandle {
        override fun isCancelled(): Boolean = false
    }

    @Test fun safeFilename_stillTrimsSeparators() {
 // 引擎产物名走既有 safeFilename（防路径分隔符）——冒烟一行防回归
        assertTrue(!safeFilename("a/b\\c:d").contains('/') && !safeFilename("a/b\\c:d").contains('\\'))
    }
}