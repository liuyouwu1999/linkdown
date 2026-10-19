package top.linkdown.android.engine

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import top.linkdown.android.engine.internal.safeFilename
import java.io.File
import java.io.IOException

/**
 * DouyinEngine fixture 单测（MockWebServer + 双 host baseUrl 注入缝；设计）。
 * 语义移植 参考实现 tests/test_douyin.py + tests/fixtures/douyin_router_data.json，
 * 另含 参考实现没有的两个自造变体：图集（atlas）、空 play_addr.url_list（no_play_url）。
 * fixture 中 play/cover host 运行时重写到 MockWebServer（下载流量不得出网）。
 */
class DouyinEngineTest {
    @get:Rule val tmp = TemporaryFolder()

    companion object {
        const val VIDEO_ID = "7300000000000000000"
        const val VIDEO_ID_15 = "730000000000000" // \d{15,} 下界形态
 // 参考实现 douyin.py _UA_PC / _UA_MOBILE 逐字（测试侧独立留底，防引擎常量漂移）
        const val UA_PC = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/134.0.0.0 Safari/537.36"
        const val UA_MOBILE = "Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.0 Mobile/15E148 Safari/604.1"
        val VIDEO_BYTES = ByteArray(300) { i -> "VID"[i % 3].code.toByte() } // ≙参考实现 b"VID" * 100
        val COVER_BYTES = byteArrayOf(1, 2, 3, 4, 5)
    }

    private var server: MockWebServer? = null
    private val requestLog = mutableListOf<String>()

 /** 可按用例改写的两处桩：ttwid 响应 / share 页响应（按请求 Cookie 决定）。 */
    private var ttwidResponse: MockResponse = MockResponse().setResponseCode(200)
        .addHeader("Set-Cookie", "ttwid=mock-ttwid-token; Path=/")
    private var shareResponse: (cookie: String?) -> MockResponse = {
        MockResponse().setResponseCode(200).setBody(sharePage(fixtureText()))
    }

    @After fun tearDown() {
        try {
            server?.shutdown()
        } catch (_: IOException) {
        }
    }

 // ------------------------------------------------------------------
 // 测试基建：MockWebServer 调度 + 请求留痕 + fixture 装配
 // ------------------------------------------------------------------

    private fun startServer(): MockWebServer {
        val s = MockWebServer()
        s.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty()
                requestLog += listOf(
                    "${request.method} $path",
                    "UA=${request.getHeader("User-Agent")}",
                    "Cookie=${request.getHeader("Cookie")}",
                    "Referer=${request.getHeader("Referer")}",
                    "BODY=${request.body.readUtf8()}",
                ).joinToString(" ")
                return when {
                    path.startsWith("/ttwid/") -> ttwidResponse
                    path == "/iABC12/" ->
                        MockResponse().setResponseCode(302).setHeader("Location", "/video/$VIDEO_ID")
                    path == "/video/$VIDEO_ID" -> MockResponse().setResponseCode(200).setBody("ok")
                    path.startsWith("/share/video/") -> shareResponse(request.getHeader("Cookie"))
                    path.startsWith("/aweme/v1/play/") ->
                        MockResponse().setResponseCode(200).setBody(Buffer().write(VIDEO_BYTES))
                    path == "/test-cover.jpeg" ->
                        MockResponse().setResponseCode(200).setBody(Buffer().write(COVER_BYTES))
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        s.start()
        server = s
        return s
    }

    private fun shortUrl() = server!!.url("/iABC12/").toString()

    private fun base() = server!!.url("/").toString().trimEnd('/')

 /** 参考实现 douyin.py UA=PC → share UA=移动 iPhone 串（参考实现 _UA_MOBILE）。 */
    private fun engine(): DouyinEngine {
        val sh = tmp.newFile("ffmpeg-stub-${System.nanoTime()}.sh")
        sh.writeText(
            listOf(
                "#!/bin/sh",
                "src=\"\"; dst=\"\"; prev=\"\"",
                "for arg in \"\$@\"; do",
                "  if [ \"\$prev\" = \"-i\" ]; then src=\"\$arg\"; fi",
                "  dst=\"\$arg\"; prev=\"\$arg\"",
                "done",
                "cp \"\$src\" \"\$dst\"",
            ).joinToString("\n"),
        )
        sh.setExecutable(true)
        return DouyinEngine(
            ttwidBaseUrl = server!!.url("/ttwid/union/register/").toString(),
            shareBaseUrl = base(),
            ffmpegPath = sh.absolutePath,
        )
    }

 /** fixture 资源装载 + play/cover host 重写到 MockWebServer（下载测试不得出网）。 */
    private fun fixtureText(name: String = "douyin_router_data.json"): String {
        val loader = requireNotNull(javaClass.classLoader) { "无 test classloader" }
        val raw = requireNotNull(loader.getResourceAsStream(name)) { "缺 fixture $name" }
            .readBytes().toString(Charsets.UTF_8)
        return raw.replace("https://www.douyin.com", base())
            .replace("https://p3-sign.douyinpic.com", base())
    }

 /** ≙参考实现 mock_api："<script>window._ROUTER_DATA=JSON</script>"；尾部再补一个含 } 的脚本块守卫非贪婪。 */
    private fun sharePage(fixture: String) =
        "<html><body><script>window._ROUTER_DATA=" + fixture +
            "</script><script>var cfg={\"a\":1};</script></body></html>"

    private fun shareForFixture(name: String) {
        shareResponse = { MockResponse().setResponseCode(200).setBody(sharePage(fixtureText(name))) }
    }

 // cancelHandle 形参桩（原 8 号位 isCancelled lambda：{ false }→noCancel、{ true }→cancelNow）
    private fun noCancel(): CancelHandle = object : CancelHandle {
        override fun isCancelled() = false
    }
    private fun cancelNow(): CancelHandle = object : CancelHandle {
        override fun isCancelled() = true
    }

 // ------------------------------------------------------------------
 // probe（参考实现 test_probe_no_watermark / test_probe_falls_back_without_ttwid 平移 + 自造变体）
 // ------------------------------------------------------------------

    @Test fun `probe 短链302取ID 无水印playwm替换 全字段`() = runBlocking {
        startServer()
        val short = shortUrl()
        val info = engine().probe(short, null)
        assertEquals("抖音", info.platform) // ProbeInfo.platform=入库目录名（PlatformDirs 契约）
        assertEquals(VIDEO_ID, info.mediaId)
        assertEquals("测试视频标题", info.title)
        assertEquals("测试博主", info.author)
 // playwm 已替换为 play → 无水印（参考实现 NOWM 语义，host 重写后前缀=MockWebServer）
        assertEquals("${base()}/aweme/v1/play/?video_id=v0d00fg10000test&ratio=720p&line=0&play=1", info.playUrl)
        assertFalse(info.playUrl!!.contains("playwm"))
        assertEquals("${base()}/test-cover.jpeg", info.coverUrl)
        assertEquals(12.345, info.durationSec!!, 1e-9) // 参考实现 duration ms→s
        assertEquals(short, info.sourceUrl)
        assertFalse(info.isVip)
        assertFalse(info.hasImages)
 // 首跳 UA=PC；share 页 UA=移动 + Cookie=ttwid（参考实现 mock_api 的 ttwid 链路语义）
        assertTrue(requestLog.first().startsWith("GET /iABC12/ UA=$UA_PC "))
        val share = requestLog.first { it.startsWith("GET /share/video/") }
        assertTrue(share.contains("UA=$UA_MOBILE "))
        assertTrue(share.contains("Cookie=ttwid=mock-ttwid-token "))
    }

    @Test fun `aweme_id 双正则 video note slides modal_id 与15位下界`() = runBlocking {
        startServer()
        val e = engine()
        assertEquals(VIDEO_ID, e.probe(server!!.url("/note/$VIDEO_ID").toString(), null).mediaId)
        assertEquals(VIDEO_ID_15, e.probe(server!!.url("/slides/$VIDEO_ID_15").toString(), null).mediaId)
        assertEquals(VIDEO_ID, e.probe(server!!.url("/search/?modal_id=$VIDEO_ID").toString(), null).mediaId)
    }

    @Test fun `无视频ID 人话报错`() = runBlocking {
        startServer()
        try {
            engine().probe(server!!.url("/no-id-here").toString(), null)
            fail("应抛 EngineException")
        } catch (e: EngineException) {
            assertEquals("抖音链接里找不到视频 ID", e.message)
        }
    }

    @Test fun `ttwid 在第2个Set-Cookie头也能扫到 多头扫描补强`() = runBlocking {
        ttwidResponse = MockResponse().setResponseCode(200)
            .addHeader("Set-Cookie", "other=abc; Path=/")
            .addHeader("Set-Cookie", "ttwid=token-from-second-header; Path=/")
        startServer()
        assertEquals(VIDEO_ID, engine().probe(shortUrl(), null).mediaId)
        val share = requestLog.first { it.startsWith("GET /share/video/") }
        assertTrue(share.contains("Cookie=ttwid=token-from-second-header "))
    }

    @Test fun `ttwid注册失败500 null降级 不带cookie再试成功`() = runBlocking {
        ttwidResponse = MockResponse().setResponseCode(500)
        startServer()
 // 参考实现 test_probe_falls_back_without_ttwid：ttwid 挂了仍可解析
        assertEquals(VIDEO_ID, engine().probe(shortUrl(), null).mediaId)
        val shares = requestLog.filter { it.startsWith("GET /share/video/") }
        assertEquals("ttwid 失败 → 仅一次无 cookie 尝试且成功，不再重试", 1, shares.size)
        assertTrue(shares[0].contains("Cookie=null "))
    }

    @Test fun `带cookie取不到 无cookie再试成功`() = runBlocking {
        startServer()
        shareResponse = { cookie ->
            if (cookie != null) MockResponse().setResponseCode(200).setBody("<html>没有数据</html>")
            else MockResponse().setResponseCode(200).setBody(sharePage(fixtureText()))
        }
        assertEquals(VIDEO_ID, engine().probe(shortUrl(), null).mediaId)
        val shares = requestLog.filter { it.startsWith("GET /share/video/") }
        assertEquals(2, shares.size)
        assertTrue(shares[0].contains("Cookie=ttwid=mock-ttwid-token "))
        assertTrue(shares[1].contains("Cookie=null "))
    }

    @Test fun `share页无_ROUTER_DATA 改版人话`() = runBlocking {
        startServer()
        shareResponse = { MockResponse().setResponseCode(200).setBody("<html>改版了</html>") }
        try {
            engine().probe(shortUrl(), null)
            fail("应抛 EngineException")
        } catch (e: EngineException) {
            assertEquals("抖音分享页结构可能已改版，尝试更新工具", e.message)
        }
        assertEquals("带 cookie 与无 cookie 各试一次", 2, requestLog.count { it.startsWith("GET /share/video/") })
    }

    @Test fun `_ROUTER_DATA JSON损坏 同样改版人话`() = runBlocking {
        startServer()
        shareResponse = {
            MockResponse().setResponseCode(200).setBody("<script>window._ROUTER_DATA={broken}</script>")
        }
        try {
            engine().probe(shortUrl(), null)
            fail("应抛 EngineException")
        } catch (e: EngineException) {
            assertEquals("抖音分享页结构可能已改版，尝试更新工具", e.message)
        }
    }

    @Test fun `loaderData陷阱项 video_layout=null 禁取首个走遍历`() = runBlocking {
        startServer()
 // fixture 首项 video_layout=null、真数据在第二项——取首个即失败，走通即证明按 videoInfoRes 键遍历
        val info = engine().probe(shortUrl(), null)
        assertEquals("测试视频标题", info.title)
    }

    @Test fun `图集 有images无play_addr 人话暂不支持`() = runBlocking {
        startServer()
        shareForFixture("douyin_router_data_atlas.json")
        try {
            engine().probe(shortUrl(), null)
            fail("应抛 EngineException")
        } catch (e: EngineException) {
            assertEquals("抖音图集暂不支持", e.message)
        }
    }

    @Test fun `play_addr url_list为空 未取到播放地址人话`() = runBlocking {
        startServer()
        shareForFixture("douyin_router_data_no_play_url.json")
        try {
            engine().probe(shortUrl(), null)
            fail("应抛 EngineException")
        } catch (e: EngineException) {
            assertEquals("未取到播放地址，视频可能已删除或仅登录可见", e.message)
        }
    }

    @Test fun `ttwid注册请求体与UA语义照参考实现 `() = runBlocking {
        startServer()
        engine().probe(shortUrl(), null)
        val req = requestLog.first { it.startsWith("POST /ttwid/") }
        val body = Json.parseToJsonElement(req.substringAfter("BODY=")).jsonObject
 // 参考实现 _TTWID_BODY 逐字段（region/aid/needFid/service/migrate_info{ticket,source}/cbUrlProtocol/union）
        assertEquals("cn", body["region"]!!.jsonPrimitive.content)
        assertEquals(1768, body["aid"]!!.jsonPrimitive.int)
        assertFalse(body["needFid"]!!.jsonPrimitive.boolean)
        assertEquals("www.ixigua.com", body["service"]!!.jsonPrimitive.content)
        assertEquals("", body["migrate_info"]!!.jsonObject["ticket"]!!.jsonPrimitive.content)
        assertEquals("node", body["migrate_info"]!!.jsonObject["source"]!!.jsonPrimitive.content)
        assertEquals("https", body["cbUrlProtocol"]!!.jsonPrimitive.content)
        assertTrue(body["union"]!!.jsonPrimitive.boolean)
 // 参考实现 UA 不设（httpx 默认 UA → okhttp 默认 UA），不得带上 PC/移动 UA
        assertFalse(req.contains(UA_PC))
        assertFalse(req.contains(UA_MOBILE))
    }

    @Test fun `生产默认双host常量登记`() {
        assertEquals("https://ttwid.bytedance.com/ttwid/union/register/", DouyinEngine.PROD_TTWID_URL)
        assertEquals("https://www.iesdouyin.com", DouyinEngine.PROD_SHARE_BASE_URL)
    }

 // ------------------------------------------------------------------
 // download（参考实现 test_download_video_and_audio_modes 平移 + cover/取消/safe_filename 变体）
 // ------------------------------------------------------------------

    @Test fun `download video模式 产物名 内容 MB进度与完成回调`() = runBlocking {
        startServer()
        val e = engine()
        val info = e.probe(shortUrl(), null)
        val dir = tmp.newFolder("task-video")
        val progress = mutableListOf<Pair<Float, String>>()
        val out = e.download(info, Mode.VIDEO, null, dir.toPath(), "mp3",
            {}, { f, s -> progress += f to s }, noCancel())
        assertEquals(listOf("测试视频标题.mp4"), out.map { it.fileName.toString() })
        assertTrue("分块流式内容逐字节一致", out[0].toFile().readBytes().contentEquals(VIDEO_BYTES))
        assertEquals("收尾 progress(1.0, 下载完成)", 1.0f to "下载完成", progress.last())
        assertTrue("中间进度为 MB 标签（蓝本 f\"{done/1e6:.1f}/{total/1e6:.1f} MB\"）",
            progress.dropLast(1).isNotEmpty() && progress.dropLast(1).all { it.second == "0.0/0.0 MB" })
 // 下载请求 UA=PC + Referer www.douyin.com（参考实现 下载客户端头）
        val dl = requestLog.first { it.startsWith("GET /aweme/v1/play/") }
        assertTrue(dl.contains("UA=$UA_PC "))
        assertTrue(dl.contains("Referer=https://www.douyin.com/ "))
    }

    @Test fun `download audio模式 经ffmpeg抽取 out mp3 并按标题命名`() = runBlocking {
        startServer()
        val e = engine()
        val info = e.probe(shortUrl(), null)
        val dir = tmp.newFolder("task-audio")
        val out = e.download(info, Mode.AUDIO, null, dir.toPath(), "mp3", {}, { _, _ -> }, noCancel())
        assertEquals(listOf("测试视频标题.mp3"), out.map { it.fileName.toString() })
        assertTrue("ffmpeg 桩把视频源复制为音频产物", out[0].toFile().readBytes().contentEquals(VIDEO_BYTES))
    }

    @Test fun `download cover模式 下载cover_url为jpg`() = runBlocking {
        startServer()
        val e = engine()
        val info = e.probe(shortUrl(), null)
        val dir = tmp.newFolder("task-cover")
        val out = e.download(info, Mode.COVER, null, dir.toPath(), "mp3", {}, { _, _ -> }, noCancel())
        assertEquals(listOf("测试视频标题.jpg"), out.map { it.fileName.toString() })
        assertTrue(out[0].toFile().readBytes().contentEquals(COVER_BYTES))
        val dl = requestLog.first { it.startsWith("GET /test-cover.jpeg") }
        assertTrue("封面下载同走 PC UA + Referer", dl.contains("UA=$UA_PC ") && dl.contains("Referer=https://www.douyin.com/ "))
    }

    @Test fun `download 取消 轮询中止抛已取消`() = runBlocking {
        startServer()
        val e = engine()
        val info = e.probe(shortUrl(), null)
        val dir = tmp.newFolder("task-cancel")
        try {
            e.download(info, Mode.VIDEO, null, dir.toPath(), "mp3", {}, { _, _ -> }, cancelNow())
            fail("应抛已取消")
        } catch (e: EngineCancelledException) { // M3：类型甄别（原 EngineException+message 串比对）
            assertEquals("已取消", e.message)
        }
    }

    @Test fun `标题非法字符经safe_filename清洗落盘`() = runBlocking {
        startServer()
        val dirty = "测/试:视*频?标<题>" // 含 \\/:*?<>| 非法字符（不掺引号，避免破坏 fixture JSON 文本）
        shareResponse = {
            MockResponse().setResponseCode(200).setBody(sharePage(fixtureText().replace("测试视频标题", dirty)))
        }
        val e = engine()
        val info = e.probe(shortUrl(), null)
        assertEquals("probe 阶段 desc 原样（清洗只在落盘命名）", dirty, info.title)
        val dir = tmp.newFolder("task-sanitize")
        val out = e.download(info, Mode.VIDEO, null, dir.toPath(), "mp3", {}, { _, _ -> }, noCancel())
        assertEquals(listOf("测试视频标题.mp4"), out.map { it.fileName.toString() })
    }

    @Test fun `safe_filename 平移断言`() {
 // 参考实现 storage.py：re.sub(r'[\\/:*?"<>|\x00-\x1f]', "") + strip() + [:150] or fallback
        assertEquals("abcdefghij", safeFilename("a/b\\c:d*e?f\"g<h>i|j"))
        assertEquals("标题", safeFilename("  标题  "))
        assertEquals("untitled", safeFilename(""))
        assertEquals("untitled", safeFilename("///"))
 // 有意偏离（真机两轮实证）：150 字符后再按 UTF-8 字节截 ≤200——
 // ASCII 150 字符=150 字节不受影响（参考实现 语义保持）；CJK 66 字=198 字节为截断点
        assertEquals(150, safeFilename("a".repeat(200)).length)
        assertEquals(150, safeFilename("a".repeat(200)).toByteArray(Charsets.UTF_8).size) // ASCII 150 字符=150 字节，不触字节截断
        assertEquals(66, safeFilename("标".repeat(200)).length)
 // 不劈半个字符：59 汉字(177 字节)+3 个 4 字节 emoji——第 6 个 emoji 会超 200，截断后仍为合法 UTF-8
        val mixed = "标".repeat(59) + "\uD83D\uDE00".repeat(10)
        val out = safeFilename(mixed)
        assertTrue("bytes=${out.toByteArray(Charsets.UTF_8).size}", out.toByteArray(Charsets.UTF_8).size <= 200)
        assertEquals(out, String(out.toByteArray(Charsets.UTF_8), Charsets.UTF_8)) // 不劈半个字符
    }
}
