package top.linkdown.android.engine

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * Telegram 公开帖引擎单测（MockWebServer + 预览页基址注入缝）。
 * fixture 手工最小化：邻居视频帖 + 目标视频帖 + 目标图片帖——专测「按 data-post 块裁剪」
 * （/s/ 页带前后帖、?single 不隔离，不裁剪必串台）与媒体提取（telesco.pe 收、emoji 剔）。
 * 下载为真实字节流（MockWebServer 本地回环，不出网）；音频流依赖 ffmpeg 不在 JVM 测。
 */
class TelegramEngineTest {
    private lateinit var server: MockWebServer
    private lateinit var engine: TelegramEngine
    private lateinit var taskDir: File

    companion object {
        private const val NEIGHBOR_VIDEO = "/file/neighbor.mp4"
        private const val TARGET_VIDEO = "/file/target.mp4"
        private const val TARGET_POSTER = "/file/poster.jpg"
        private const val TARGET_PHOTO = "/file/photo1.jpg"
        private const val TARGET_PHOTO2 = "/file/photo2.jpg"

        private fun widget(post: String, body: String) =
            """<div class="tgme_widget_message_wrap js-message_wrap"><div class="tgme_widget_message" data-post="$post">$body</div></div>"""

        /** 预览页：邻居视频帖(100) → 目标视频帖(101) → 目标图片帖(102，双图+表情干扰项)。 */
        private fun previewPage(base: String) =
            """<html><body><div class="tgme_widget_message_text">x</div>""" +
                widget(
                    "demochan/100",
                    """<time datetime="2026-01-01T00:00:00+00:00"></time>""" +
                        """<video class="link_preview_video" src="$base$NEIGHBOR_VIDEO"></video>""",
                ) +
                widget(
                    "demochan/101",
                    """<time datetime="2026-01-02T00:00:00+00:00"></time>""" +
                        """<i class="tgme_widget_message_video_thumb" style="background-image:url('$base$TARGET_POSTER')"></i>""" +
                        """<video src="$base$TARGET_VIDEO" class="tgme_widget_message_video"></video>""" +
                        """<div class="tgme_widget_message_text js-message_text" dir="auto">Spike <b>hello</b> 1920 test</div>""",
                ) +
                widget(
                    "demochan/102",
                    """<div class="tgme_widget_message_text" dir="auto">两张图</div>""" +
                        """<a class="tgme_widget_message_photo_wrap" style="background-image:url('https://telegram.org/img/emoji/40/F09F8C8D.png')">e</a>""" +
                        """<a class="tgme_widget_message_photo_wrap" style="background-image:url('$base$TARGET_PHOTO')">1</a>""" +
                        """<a class="tgme_widget_message_photo_wrap" style="background-image:url('$base$TARGET_PHOTO2')">2</a>""",
                ) + "</body></html>"
    }

    @Before
    fun setUp() {
        server = MockWebServer(); server.start()
        engine = TelegramEngine(server.url("/").toString().trimEnd('/'), ffmpegPath = "")
        taskDir = Files.createTempDirectory("tgtest").toFile()
    }

    @After
    fun tearDown() {
        server.shutdown()
        taskDir.deleteRecursively()
    }

    private fun enqueuePreview() {
        server.enqueue(MockResponse().setResponseCode(200).setBody(previewPage(server.url("/").toString().trimEnd('/'))))
    }

    // ------------------------------------------------------------------
    // parsePost：host/形态/数字 id 校验
    // ------------------------------------------------------------------

    @Test fun `帖子链接解析四形态与剥s前缀`() {
        assertEquals("durov" to "531", engine.parsePost("https://t.me/durov/531"))
        assertEquals("durov" to "531", engine.parsePost("https://t.me/s/durov/531"))
        assertEquals("durov" to "531", engine.parsePost("https://telegram.me/durov/531?single"))
        assertEquals("demo_chan" to "12", engine.parsePost("https://www.t.me/demo_chan/12"))
    }

    @Test fun `非帖子形态与坏id一律拒绝`() {
        assertNull(engine.parsePost("https://t.me/share/blog"))
        assertNull(engine.parsePost("https://t.me/joinchat/AbCdEf"))
        assertNull(engine.parsePost("https://t.me/+AbCdEf123"))
        assertNull(engine.parsePost("https://t.me/durov"))
        assertNull(engine.parsePost("https://t.me/durov/abc"))
        assertNull(engine.parsePost("https://bilibili.com/durov/531"))
        assertNull(engine.parsePost("这不是链接"))
    }

    // ------------------------------------------------------------------
    // probe：按 data-post 块裁剪（邻居帖不串台）+ 媒体提取
    // ------------------------------------------------------------------

    @Test fun `probe视频帖 锁定目标块 提取视频与海报与正文标题`() = runBlocking {
        enqueuePreview()
        val info = engine.probe("https://t.me/demochan/101", null)
        assertEquals("demochan_101", info.mediaId)
        assertEquals("Telegram", info.platform)
        assertEquals("demochan", info.author)
        assertEquals("Spike hello 1920 test", info.title)
        assertEquals(server.url(TARGET_VIDEO).toString(), info.playUrl)
        assertEquals(server.url(TARGET_POSTER).toString(), info.coverUrl)
        assertEquals(false, info.hasImages)
        assertTrue(info.sourceUrl == "https://t.me/demochan/101")
        Unit
    }

    @Test fun `probe同块链接预览视频被class过滤只留帖内视频`() = runBlocking {
        // 真实页形态：帖内正文带外链时预览页会插 link_preview_video，class 收窄必须只留 message_video
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                widget(
                    "demochan/101",
                    """<video class="link_preview_video" src="${server.url(NEIGHBOR_VIDEO)}"></video>""" +
                        """<video src="${server.url(TARGET_VIDEO)}" class="tgme_widget_message_video js-message_video"></video>""" +
                        """<i class="tgme_widget_message_video_thumb" style="background-image:url('${server.url(TARGET_POSTER)}')"></i>""",
                ),
            ),
        )
        val info = engine.probe("https://t.me/demochan/101", null)
        assertEquals(server.url(TARGET_VIDEO).toString(), info.playUrl)
        assertEquals(server.url(TARGET_POSTER).toString(), info.coverUrl)
        Unit
    }

    @Test fun `probe大小写归一 URL大写命中规范data-post且author回填规范名`() = runBlocking {
        enqueuePreview()
        val info = engine.probe("https://t.me/DemoChan/101", null)
        assertEquals("demochan", info.author)
        assertEquals("demochan_101", info.mediaId)
        assertEquals(server.url(TARGET_VIDEO).toString(), info.playUrl)
        Unit
    }

    @Test fun `probe图片帖 双图收全且表情贴纸被剔`() = runBlocking {
        enqueuePreview()
        val info = engine.probe("https://t.me/demochan/102", null)
        assertEquals(listOf(server.url(TARGET_PHOTO).toString(), server.url(TARGET_PHOTO2).toString()), info.playUrl?.split(";"))
        assertTrue(info.hasImages)
        assertEquals("两张图", info.title)
        Unit
    }

    @Test fun `probe无媒体帖给人话报错`() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(widget("demochan/103", "纯文字帖")),
        )
        try {
            engine.probe("https://t.me/demochan/103", null)
            fail("应抛 EngineException")
        } catch (e: EngineException) {
            assertTrue(e.message!!.contains("没有可下载"))
        }
        Unit
    }

    @Test fun `probe预览页404给人话报错`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(404))
        try {
            engine.probe("https://t.me/demochan/404", null)
            fail("应抛 EngineException")
        } catch (e: EngineException) {
            assertTrue(e.message!!.contains("预览页访问失败"))
        }
        Unit
    }

    // ------------------------------------------------------------------
    // download：视频落盘+伴生海报 / 图片逐张 / 封面
    // ------------------------------------------------------------------

    private fun serveMedia(vararg bodies: String) {
        bodies.forEach { server.enqueue(MockResponse().setResponseCode(200).setBody(it)) }
    }

    @Test fun `视频下载 成品mp4加伴生海报`() = runBlocking {
        enqueuePreview()
        val info = engine.probe("https://t.me/demochan/101", null)
        serveMedia("VIDEOBYTES", "POSTERBYTES")
        val out = engine.download(info, Mode.VIDEO, null, taskDir.toPath(), "mp3",
            {}, { _, _ -> }, cancelHandleOf { false })
        assertEquals(2, out.size)
        assertEquals("VIDEOBYTES", Files.readAllBytes(out[0]).decodeToString())
        assertTrue(out[0].fileName.endsWith("demochan - Spike hello 1920 test.mp4"))
        assertEquals("POSTERBYTES", Files.readAllBytes(out[1]).decodeToString())
        assertTrue(out[1].fileName.toString().contains("-cover.jpg"))
        Unit
    }

    @Test fun `图片帖视频模式 逐张落盘带序号`() = runBlocking {
        enqueuePreview()
        val info = engine.probe("https://t.me/demochan/102", null)
        serveMedia("PHOTO1", "PHOTO2")
        val out = engine.download(info, Mode.VIDEO, null, taskDir.toPath(), "mp3",
            {}, { _, _ -> }, cancelHandleOf { false })
        assertEquals(2, out.size)
        assertTrue(out[0].fileName.endsWith("demochan - 两张图-1.jpg"))
        assertEquals("PHOTO1", Files.readAllBytes(out[0]).decodeToString())
        assertEquals("PHOTO2", Files.readAllBytes(out[1]).decodeToString())
        Unit
    }

    @Test fun `封面模式 视频帖取海报`() = runBlocking {
        enqueuePreview()
        val info = engine.probe("https://t.me/demochan/101", null)
        serveMedia("POSTERBYTES")
        val out = engine.download(info, Mode.COVER, null, taskDir.toPath(), "mp3",
            {}, { _, _ -> }, cancelHandleOf { false })
        assertEquals(1, out.size)
        assertEquals("POSTERBYTES", Files.readAllBytes(out[0]).decodeToString())
        Unit
    }

    private fun fail(message: String) = org.junit.Assert.fail(message)

    private fun cancelHandleOf(cancelled: () -> Boolean): CancelHandle = object : CancelHandle {
        override fun isCancelled(): Boolean = cancelled()
        override fun throwIfCancelled() {
            if (cancelled()) throw EngineCancelledException("cancelled")
        }
    }
}
