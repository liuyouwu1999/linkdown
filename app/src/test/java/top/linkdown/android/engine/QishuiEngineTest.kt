package top.linkdown.android.engine

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import top.linkdown.android.engine.internal.qualityRank
import java.io.File
import java.io.IOException

/**
 * QishuiEngine fixture 单测（MockWebServer + luna baseUrl 注入缝）。
 * 语义移植 参考实现 tests/test_qishui.py + tests/fixtures/qishui_seo_track.json；
 * 另含 参考实现没有的自造变体：track_id 三正则、status_code null 豁免、四键名顺序、
 * 试听边界/大写 Duration、VIP 三标志、video 模式按音频、cover 模式（设计）。
 * fixture 中 player/封面/音频 host 运行时重写到 MockWebServer（下载流量不得出网）。
 */
class QishuiEngineTest {
    @get:Rule val tmp = TemporaryFolder()

    companion object {
        const val TRACK_ID = "7159634662997968910" // 参考实现 fixture 同款 track_id
 // 参考实现 qishui.py _UA 逐字（测试侧独立留底，防引擎常量漂移）
        const val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/134.0.0.0 Safari/537.36"
        const val REFERER = "https://music.douyin.com/"
 // ≙参考实现 test_download b"AUDIO" * 1000（Content-Length=5000 已知 → MB 进度分支）
        val AUDIO_BYTES = ByteArray(5000) { i -> "AUDIO"[i % 5].code.toByte() }
        val IMG_BYTES = byteArrayOf(9, 8, 7, 6)
    }

    private var server: MockWebServer? = null
    private val requestLog = mutableListOf<String>()

 // 可按用例改写的桩（默认值在 startServer() 启动后赋值；用例可再改写）
    private var trackPagePath = "/qishui/share/track?track_id=$TRACK_ID"
    private var trackPageResponse: MockResponse = MockResponse().setResponseCode(200)
    private var seoResponse: MockResponse = MockResponse().setResponseCode(200)
    private var playerResponse: MockResponse = MockResponse().setResponseCode(200)
    private var coverResponse: MockResponse = MockResponse().setResponseCode(200)

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
                    "Referer=${request.getHeader("Referer")}",
                ).joinToString(" ")
                return when {
                    path.startsWith("/s/") ->
                        MockResponse().setResponseCode(302).setHeader("Location", trackPagePath)
                    path.startsWith(trackPagePath.substringBefore('?')) -> trackPageResponse
                    path.startsWith("/luna/h5/seo_track") -> seoResponse
                    path.startsWith("/player/") -> playerResponse
                    path.startsWith("/audio/") ->
                        MockResponse().setResponseCode(200).setBody(Buffer().write(AUDIO_BYTES))
                    path.startsWith("/img/") -> coverResponse
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        s.start()
        server = s
 // 依赖 server 的默认桩在启动后赋值（fixture host 重写需要 base()）；用例可再改写
        trackPagePath = "/qishui/share/track?track_id=$TRACK_ID"
        trackPageResponse = MockResponse().setResponseCode(200).setBody("ok")
        seoResponse = MockResponse().setResponseCode(200).setBody(fixtureText())
        playerResponse = MockResponse().setResponseCode(200).setBody(playerInfoJson(listOf(fullNode())))
        coverResponse = MockResponse().setResponseCode(200).setBody(Buffer().write(IMG_BYTES))
        return s
    }

    private fun base() = server!!.url("/").toString().trimEnd('/')

    private fun shortUrl() = server!!.url("/s/iXXjp58L/").toString()

    private fun trackUrl() = server!!.url(trackPagePath).toString()

 /** 参考实现 同款 ffmpeg 桩：首个 -i 的源复制到末位目标（标签嵌入带封面时 src 仍=音频而非封面）。 */
    private fun engine(): QishuiEngine {
        val sh = tmp.newFile("ffmpeg-stub-${System.nanoTime()}.sh")
        sh.writeText(
            listOf(
                "#!/bin/sh",
                "src=\"\"; dst=\"\"; prev=\"\"",
                "for arg in \"\$@\"; do",
                "  if [ \"\$prev\" = \"-i\" ] && [ \"\$src\" = \"\" ]; then src=\"\$arg\"; fi",
                "  dst=\"\$arg\"; prev=\"\$arg\"",
                "done",
                "cp \"\$src\" \"\$dst\"",
            ).joinToString("\n"),
        )
        sh.setExecutable(true)
        return QishuiEngine(lunaBaseUrl = base(), ffmpegPath = sh.absolutePath)
    }

 /** fixture 资源装载 + player/封面 host 重写到 MockWebServer（下载测试不得出网）。 */
    private fun fixtureText(): String {
        val loader = requireNotNull(javaClass.classLoader) { "无 test classloader" }
        val raw = requireNotNull(loader.getResourceAsStream("qishui_seo_track.json")) { "缺 fixture qishui_seo_track.json" }
            .readBytes().toString(Charsets.UTF_8)
        return raw.replace("https://player.test", "${base()}/player")
            .replace("https://p3-luna.douyinpic.com", base())
    }

 /** fixture 变体：按 JSON 路径改写（value=null 表示删除该键，≙status_code 缺省豁免等用例）。 */
    private fun seo(vararg updates: Pair<List<String>, JsonElement?>): MockResponse {
        var root = Json.parseToJsonElement(fixtureText()).jsonObject
        for ((segs, value) in updates) root = patch(root, segs, value)
        return MockResponse().setResponseCode(200).setBody(root.toString())
    }

    private fun patch(root: JsonObject, segs: List<String>, value: JsonElement?): JsonObject {
        val key = segs.first()
        if (segs.size == 1) {
            val map = root.toMap().toMutableMap()
            if (value == null) map.remove(key) else map[key] = value
            return JsonObject(map)
        }
        val child = root[key] as? JsonObject ?: JsonObject(emptyMap())
        return JsonObject(root.toMap() + (key to patch(child, segs.drop(1), value)))
    }

    private fun playNode(vararg pairs: Pair<String, JsonElement>): JsonObject = JsonObject(pairs.toMap())

 // cancelHandle 形参桩（原 8 号位 isCancelled lambda：{ false }→noCancel、{ true }→cancelNow）
    private fun noCancel(): CancelHandle = object : CancelHandle {
        override fun isCancelled() = false
    }
    private fun cancelNow(): CancelHandle = object : CancelHandle {
        override fun isCancelled() = true
    }

    private fun jsonPath(vararg segs: String): List<String> = segs.toList()

    private fun u(name: String): JsonElement = JsonPrimitive("${base()}/audio/$name.m4a")

 /** ≙参考实现 PLAYER_INFO：完整版 195 秒、higher 质量。 */
    private fun fullNode(): JsonObject = playNode(
        "main_play_url" to u("full"),
        "backup_play_url" to JsonPrimitive(""),
        "format" to JsonPrimitive("mp4"),
        "bitrate" to JsonPrimitive(128000),
        "quality" to JsonPrimitive("higher"),
        "size" to JsonPrimitive(3200000),
        "duration" to JsonPrimitive(195),
    )

 /** ≙参考实现 test_preview_detected_by_duration_gap：流只有 dur 秒（完整 195 秒）→ 试听。 */
    private fun previewNode(dur: Int): JsonObject = playNode(
        "main_play_url" to u("preview"),
        "backup_play_url" to JsonPrimitive(""),
        "format" to JsonPrimitive("mp4"),
        "bitrate" to JsonPrimitive(64000),
        "quality" to JsonPrimitive("standard"),
        "size" to JsonPrimitive(1),
        "duration" to JsonPrimitive(dur),
    )

    private fun playerInfoJson(list: List<JsonObject>): String = buildJsonObject {
        put("Result", buildJsonObject {
            put("Data", buildJsonObject {
                put("PlayInfoList", JsonArray(list))
            })
        })
    }.toString()

 /** video_model 多层字符串嵌套（layers=1/2/3 在参考实现 3 层解包内，4 超深）。 */
    private fun layeredVideoModel(inner: JsonElement, layers: Int): JsonElement {
        var n: JsonElement = inner
        repeat(layers) { n = JsonPrimitive(n.toString()) }
        return n
    }

    private fun videoModelSeo(model: JsonElement): MockResponse =
        seo(jsonPath("track_player", "video_model") to model)

 // ------------------------------------------------------------------
 // probe 全字段（参考实现 test_probe 平移）
 // ------------------------------------------------------------------

    @Test fun `probe 短链302 track_id 全字段断言`() = runBlocking {
        startServer()
        val short = shortUrl()
        val info = engine().probe(short, null)
        assertEquals("汽水", info.platform) // ProbeInfo.platform=入库目录名（PlatformDirs 契约）
        assertEquals(TRACK_ID, info.mediaId)
        assertEquals("DISCO 迪斯科 的士高 复古BGM", info.title)
        assertEquals("测试歌手", info.author)
        assertEquals(195.0, info.durationSec!!, 1e-9) // 参考实现 duration ms→s
 // 封面拼装（参考实现 test_probe：base + uri + "~" + tpl + "-resize:960:960.png"；urls[0] 已重写到本机）
        assertEquals("${base()}/img/test-uri~test-tpl-resize:960:960.png", info.coverUrl)
        assertEquals("${base()}/audio/full.m4a", info.playUrl)
        assertEquals(short, info.sourceUrl)
        assertFalse(info.isVip)
        assertFalse(info.hasImages)
 // 全程 UA=PC + Referer music.douyin.com（参考实现 _headers() 挂在 client 上，页/接口/播放地址都带）
        val page = requestLog.first { it.startsWith("GET /qishui/share/track") }
        assertTrue("page=[$page]", page.contains("UA=$UA ") && page.contains("Referer=$REFERER"))
        val seoReq = requestLog.first { it.startsWith("GET /luna/h5/seo_track") }
        assertTrue("seo=[$seoReq]", seoReq.contains("track_id=$TRACK_ID") && seoReq.contains("device_platform=web"))
        assertTrue("seo=[$seoReq]", seoReq.contains("UA=$UA ") && seoReq.contains("Referer=$REFERER"))
        val player = requestLog.first { it.startsWith("GET /player/") }
        assertTrue("player=[$player]", player.contains("UA=$UA ") && player.contains("Referer=$REFERER"))
    }

 // ------------------------------------------------------------------
 // track_id 三正则（参考实现 _extract_track_id；URL 先页面后）
 // ------------------------------------------------------------------

    @Test fun `track_id 三正则 参数式 JSON式 track与song路径`() = runBlocking {
        startServer()
        val e = engine()
 // 正则1：(?:^|[?&])track_id=（默认 trackPagePath 即参数式）
        assertEquals(TRACK_ID, e.probe(trackUrl(), null).mediaId)
 // 正则3：(?:track|song)/(10 位以上数字)
        trackPagePath = "/track/$TRACK_ID"
        assertEquals(TRACK_ID, e.probe(trackUrl(), null).mediaId)
        trackPagePath = "/song/$TRACK_ID"
        assertEquals(TRACK_ID, e.probe(trackUrl(), null).mediaId)
 // 正则2："track_id":"…"（URL 无命中时查页面文本；含空白变体 \s*:\s*）
        trackPagePath = "/plain/page"
        trackPageResponse = MockResponse().setResponseCode(200)
            .setBody("""{"data": {"track_id" :  "$TRACK_ID"}}""")
        assertEquals(TRACK_ID, e.probe(trackUrl(), null).mediaId)
    }

    @Test fun `track_id 不足10位 人话找不到`() = runBlocking {
        startServer()
        trackPagePath = "/song/123456789" // \d{10,} 下界之外（9 位）
        try {
            engine().probe(trackUrl(), null)
            fail("应抛 EngineException")
        } catch (e: EngineException) {
            assertEquals("汽水链接里找不到 track_id，请确认是歌曲分享链接", e.message)
        }
    }

    @Test fun `URL先于页面文本 取URL侧track_id`() = runBlocking {
        startServer()
        trackPageResponse = MockResponse().setResponseCode(200)
            .setBody("""{"data":{"track_id":"9999999999"}}""")
        assertEquals(TRACK_ID, engine().probe(trackUrl(), null).mediaId)
    }

 // ------------------------------------------------------------------
 // status_code 非 (null, 0) 人话（null 缺省是合法成功）
 // ------------------------------------------------------------------

    @Test fun `status_code 非null非0 人话报错`() = runBlocking {
        startServer()
        seoResponse = seo(jsonPath("status_code") to JsonPrimitive(1))
        try {
            engine().probe(trackUrl(), null)
            fail("应抛 EngineException")
        } catch (e: EngineException) {
            assertEquals("汽水接口返回错误 status_code=1，接口可能改版", e.message)
        }
        seoResponse = seo(jsonPath("status_code") to JsonPrimitive(999))
        try {
            engine().probe(trackUrl(), null)
            fail("应抛 EngineException")
        } catch (e: EngineException) {
            assertEquals("汽水接口返回错误 status_code=999，接口可能改版", e.message)
        }
    }

    @Test fun `status_code 缺省键 null豁免 探测成功`() = runBlocking {
        startServer()
        seoResponse = seo(jsonPath("status_code") to null) // 顶层无 status_code（状态在 status_info）→ 合法
        val info = engine().probe(trackUrl(), null)
        assertEquals(TRACK_ID, info.mediaId)
    }

 // ------------------------------------------------------------------
 // video_model 解包与递归收集（参考实现 _json_unpack / _collect_play_urls）
 // ------------------------------------------------------------------

    @Test fun `video_model 3层字符串解包 递归收集到播放节点 不触发player接口`() = runBlocking {
        startServer()
        val inner = buildJsonObject {
            put("stuff", JsonArray(emptyList()))
            put("nested", buildJsonObject {
                put("play", playNode(
                    "main_url" to u("full"),
                    "format" to JsonPrimitive("mp4"),
                    "bitrate" to JsonPrimitive(128000),
                    "quality" to JsonPrimitive("higher"),
                    "duration" to JsonPrimitive(195),
                ))
            })
        }
        seoResponse = videoModelSeo(layeredVideoModel(inner, 3))
        val info = engine().probe(trackUrl(), null)
        assertEquals("${base()}/audio/full.m4a", info.playUrl)
        assertTrue("video_model 已取到播放地址，不应再打 url_player_info",
            requestLog.none { it.startsWith("GET /player/") })
    }

    @Test fun `video_model 4层超出解包深度 回退player_info`() = runBlocking {
        startServer()
        val inner = buildJsonObject {
            put("play", playNode("main_url" to u("deep4"), "duration" to JsonPrimitive(195)))
        }
        seoResponse = videoModelSeo(layeredVideoModel(inner, 4))
        val info = engine().probe(trackUrl(), null)
 // deep4 节点收不到 → 走 url_player_info 的 full
        assertEquals("${base()}/audio/full.m4a", info.playUrl)
        assertTrue(requestLog.any { it.startsWith("GET /player/") })
    }

 // ------------------------------------------------------------------
 // 四键名候选与顺序（参考实现 _MAIN_KEYS/_BACKUP_KEYS + _play_url）
 // ------------------------------------------------------------------

    @Test fun `四键名候选与顺序 空main串跳过`() = runBlocking {
        startServer()
        val e = engine()
        val cases = listOf(
 // 全键齐 → main_play_url 最优先
            playNode(
                "main_play_url" to u("k1"), "main_url" to u("k2"), "MainPlayUrl" to u("k3"),
                "MainUrl" to u("k4"), "backup_play_url" to u("k5"), "backup_url" to u("k6"),
                "BackupPlayUrl" to u("k7"), "BackupUrl" to u("k8"), "duration" to JsonPrimitive(195),
            ) to "k1",
            playNode("main_url" to u("k2"), "MainPlayUrl" to u("k3"), "duration" to JsonPrimitive(195)) to "k2",
            playNode("MainPlayUrl" to u("k3"), "MainUrl" to u("k4"), "duration" to JsonPrimitive(195)) to "k3",
            playNode("MainUrl" to u("k4"), "backup_play_url" to u("k5"), "duration" to JsonPrimitive(195)) to "k4",
            playNode("backup_play_url" to u("k5"), "backup_url" to u("k6"), "duration" to JsonPrimitive(195)) to "k5",
            playNode("backup_url" to u("k6"), "BackupUrl" to u("k8"), "duration" to JsonPrimitive(195)) to "k6",
            playNode("BackupPlayUrl" to u("k7"), "duration" to JsonPrimitive(195)) to "k7",
            playNode("BackupUrl" to u("k8"), "duration" to JsonPrimitive(195)) to "k8",
 // 空串 main_play_url 视为缺失 → 落到 main_url（参考实现 if play.get(k) 真值语义）
            playNode("main_play_url" to JsonPrimitive(""), "main_url" to u("k2"), "duration" to JsonPrimitive(195)) to "k2",
        )
        for ((node, expect) in cases) {
            seoResponse = videoModelSeo(node)
            assertEquals(expect, e.probe(trackUrl(), null).playUrl!!.substringAfterLast("/").removeSuffix(".m4a"))
        }
    }

 // ------------------------------------------------------------------
 // 播放地址三级回退（video_model → url_player_info → audio_info.play_info_list）
 // ------------------------------------------------------------------

    @Test fun `三级回退 video_model空 走url_player_info`() = runBlocking {
        startServer()
 // 默认 fixture 即 video_model="" + url_player_info 指向本机
        val info = engine().probe(trackUrl(), null)
        assertEquals("${base()}/audio/full.m4a", info.playUrl)
        assertTrue(requestLog.any { it.startsWith("GET /player/") })
    }

    @Test fun `三级回退 无url_player_info 兜底audio_info play_info_list`() = runBlocking {
        startServer()
        seoResponse = seo(
            jsonPath("track_player", "url_player_info") to null,
            jsonPath("seo_track", "track", "audio_info", "play_info_list") to JsonArray(listOf(fullNode())),
        )
        val info = engine().probe(trackUrl(), null)
        assertEquals("${base()}/audio/full.m4a", info.playUrl)
        assertTrue("无 url_player_info 则不应打 player 接口",
            requestLog.none { it.startsWith("GET /player/") })
    }

    @Test fun `三级回退 全空 人话未返回可用播放地址`() = runBlocking {
        startServer()
        seoResponse = seo(jsonPath("track_player", "url_player_info") to null)
        try {
            engine().probe(trackUrl(), null)
            fail("应抛 EngineException")
        } catch (e: EngineException) {
            assertEquals("汽水未返回可用的播放地址，歌曲可能仅 VIP 可听", e.message)
        }
    }

    @Test fun `video_model试听 player_info取到完整版 则成功替换`() = runBlocking {
        startServer()
        seoResponse = videoModelSeo(previewNode(30))
        val info = engine().probe(trackUrl(), null)
        assertEquals("${base()}/audio/full.m4a", info.playUrl)
        assertTrue(requestLog.any { it.startsWith("GET /player/") })
    }

 // ------------------------------------------------------------------
 // 试听判定（参考实现 _is_preview：dur+5<full 严格；duration 或大写 Duration；阻断为 有意偏离）
 // ------------------------------------------------------------------

    @Test fun `试听阻断 人话含秒数取整`() = runBlocking {
        startServer()
 // 参考实现 test_preview 同款：url_player_info 也只回试听流（否则三级回退会换成完整版，参考实现 语义即如此）
        seoResponse = videoModelSeo(previewNode(30))
        playerResponse = MockResponse().setResponseCode(200).setBody(playerInfoJson(listOf(previewNode(30))))
        try {
            engine().probe(trackUrl(), null)
            fail("应抛 EngineException")
        } catch (e: EngineException) {
            assertEquals("汽水只给到试听片段（约 30 秒），完整版需要 VIP", e.message)
        }
    }

    @Test fun `试听边界 dur加5等于full不算试听 小于才阻断`() = runBlocking {
        startServer()
 // full=195：dur=190 → 190+5<195 假 → 成功交付；dur=189 → 194<195 真 → 阻断（player_info 不存在，无回退源）
        seoResponse = seo(
            jsonPath("track_player", "url_player_info") to null,
            jsonPath("track_player", "video_model") to previewNode(190),
        )
        assertEquals("${base()}/audio/preview.m4a", engine().probe(trackUrl(), null).playUrl)
        seoResponse = seo(
            jsonPath("track_player", "url_player_info") to null,
            jsonPath("track_player", "video_model") to previewNode(189),
        )
        try {
            engine().probe(trackUrl(), null)
            fail("应抛 EngineException")
        } catch (e: EngineException) {
            assertEquals("汽水只给到试听片段（约 189 秒），完整版需要 VIP", e.message)
        }
    }

    @Test fun `试听 节点仅大写Duration键仍判定`() = runBlocking {
        startServer()
        seoResponse = seo(
            jsonPath("track_player", "url_player_info") to null,
            jsonPath("track_player", "video_model") to playNode(
                "MainPlayUrl" to u("preview"), "Duration" to JsonPrimitive(30),
            ),
        )
        try {
            engine().probe(trackUrl(), null)
            fail("应抛 EngineException")
        } catch (e: EngineException) {
            assertEquals("汽水只给到试听片段（约 30 秒），完整版需要 VIP", e.message)
        }
    }

    @Test fun `player_info也只给试听 仍阻断人话`() = runBlocking {
        startServer()
        seoResponse = videoModelSeo(previewNode(30))
        playerResponse = MockResponse().setResponseCode(200).setBody(playerInfoJson(emptyList()))
        try {
            engine().probe(trackUrl(), null)
            fail("应抛 EngineException")
        } catch (e: EngineException) {
            assertEquals("汽水只给到试听片段（约 30 秒），完整版需要 VIP", e.message)
        }
    }

 // ------------------------------------------------------------------
 // 质量排序（参考实现 _quality_rank L63-87 逐字；直测 internal qualityRank）
 // ------------------------------------------------------------------

    @Test fun `quality_rank 空节点兜底10 分值表各档`() {
        assertEquals(10, qualityRank(playNode()))
 // 分值表：110 hires+lossless / 100 lossless / 90 hires / 88 atmos|dolby / 70 hq|high|br≥320 / 65 / 50 / 10
        assertEquals(110, qualityRank(playNode("quality" to JsonPrimitive("hires"), "format" to JsonPrimitive("flac"))))
        assertEquals(110, qualityRank(playNode("quality" to JsonPrimitive("master"), "format" to JsonPrimitive("FLAC"))))
        assertEquals(100, qualityRank(playNode("quality" to JsonPrimitive("lossless"))))
        assertEquals(100, qualityRank(playNode("quality" to JsonPrimitive("sq"))))
        assertEquals(100, qualityRank(playNode("format" to JsonPrimitive("flac"))))
        assertEquals(90, qualityRank(playNode("quality" to JsonPrimitive("hires"))))
        assertEquals(90, qualityRank(playNode("quality" to JsonPrimitive("Master"))))
        assertEquals(88, qualityRank(playNode("quality" to JsonPrimitive("atmos"))))
        assertEquals(88, qualityRank(playNode("quality" to JsonPrimitive("Dolby"))))
        assertEquals(70, qualityRank(playNode("quality" to JsonPrimitive("hq"))))
        assertEquals(70, qualityRank(playNode("quality" to JsonPrimitive("HIGH"))))
        assertEquals(70, qualityRank(playNode("bitrate" to JsonPrimitive(320))))
        assertEquals(65, qualityRank(playNode("bitrate" to JsonPrimitive(256))))
        assertEquals(50, qualityRank(playNode("quality" to JsonPrimitive("standard"))))
        assertEquals(50, qualityRank(playNode("bitrate" to JsonPrimitive(128))))
    }

    @Test fun `quality_rank 节点优先 video_meta回退 format三源 vtype优先`() {
 // 节点空 quality → video_meta.quality
        assertEquals(100, qualityRank(playNode("video_meta" to playNode("quality" to JsonPrimitive("lossless")))))
 // format 三源：节点 format → meta.vtype → meta.format
        assertEquals(100, qualityRank(playNode("video_meta" to playNode("vtype" to JsonPrimitive("flac")))))
        assertEquals(100, qualityRank(playNode("video_meta" to playNode("format" to JsonPrimitive("flac")))))
 // 节点 format 优先于 meta.vtype
        assertEquals(10, qualityRank(playNode(
            "format" to JsonPrimitive("mp4"),
            "video_meta" to playNode("vtype" to JsonPrimitive("flac")),
        )))
 // meta.vtype 优先于 meta.format
        assertEquals(10, qualityRank(playNode("video_meta" to playNode(
            "vtype" to JsonPrimitive("mp4"), "format" to JsonPrimitive("flac"),
        ))))
 // 节点 quality 为空串视为缺失 → meta 回退；bitrate 0 同理（参考实现 or 真值链）
        assertEquals(100, qualityRank(playNode(
            "quality" to JsonPrimitive(""), "bitrate" to JsonPrimitive(0),
            "video_meta" to playNode("quality" to JsonPrimitive("sq"), "bitrate" to JsonPrimitive(900)),
        )))
    }

    @Test fun `quality_rank bps折算 与阈值边界`() {
 // bitrate>10000 视为 bps 先 ÷1000（参考实现 注释：实测 64k/251k/126k 为 bps，阈值按 kbps 设计）
        assertEquals(10, qualityRank(playNode("bitrate" to JsonPrimitive(20000)))) // → 20 kbps → 兜底
        assertEquals(100, qualityRank(playNode("bitrate" to JsonPrimitive(10000)))) // 10000 不折算（须 >10000）→ ≥900 无损
        assertEquals(10, qualityRank(playNode("bitrate" to JsonPrimitive(100000)))) // 折算 100 → <128 → 兜底
        assertEquals(70, qualityRank(playNode("bitrate" to JsonPrimitive(899)))) // 899<900 非无损，但 ≥320 → 70（与 900→100 构成边界）
        assertEquals(100, qualityRank(playNode("bitrate" to JsonPrimitive(900)))) // ≥900 无损
        assertEquals(70, qualityRank(playNode("bitrate" to JsonPrimitive(640000)))) // → 640 → ≥320
        assertEquals(10, qualityRank(playNode("bitrate" to JsonPrimitive(127)))) // 127<128 → 兜底
        assertEquals(50, qualityRank(playNode("bitrate" to JsonPrimitive("128000")))) // 字符串数字照参考实现 int() 兼容
    }

    @Test fun `pick_best 只在可播放节点中选质量`() = runBlocking {
        startServer()
        val hi = playNode("main_play_url" to u("hi"), "quality" to JsonPrimitive("higher"),
            "bitrate" to JsonPrimitive(128000), "duration" to JsonPrimitive(195))
        val lo = playNode("main_play_url" to u("lo"), "quality" to JsonPrimitive("standard"),
            "bitrate" to JsonPrimitive(64000), "duration" to JsonPrimitive(195))
        val ghost = playNode("quality" to JsonPrimitive("lossless"), "duration" to JsonPrimitive(195)) // 无播放地址
        seoResponse = videoModelSeo(JsonArray(listOf(lo, hi)))
        assertEquals("hi", engine().probe(trackUrl(), null).playUrl!!.substringAfterLast("/").removeSuffix(".m4a"))
 // ghost 分高但不可播放 → 跳过（参考实现 playable 过滤）
        seoResponse = videoModelSeo(JsonArray(listOf(ghost, lo)))
        assertEquals("lo", engine().probe(trackUrl(), null).playUrl!!.substringAfterLast("/").removeSuffix(".m4a"))
    }

 // ------------------------------------------------------------------
 // 封面拼装（参考实现 _cover_url：urls[0] 缺省基）
 // ------------------------------------------------------------------

    @Test fun `封面 urls缺省 回退基p3-luna`() = runBlocking {
        startServer()
        seoResponse = seo(
            jsonPath("seo_track", "track", "album", "url_cover") to playNode(
                "uri" to JsonPrimitive("test-uri"),
                "template_prefix" to JsonPrimitive("test-tpl"),
            ),
        )
        val info = engine().probe(trackUrl(), null)
 // 缺省基来自引擎常量（非 fixture 重写），纯字符串断言无网络流量
        assertEquals("https://p3-luna.douyinpic.com/img/test-uri~test-tpl-resize:960:960.png", info.coverUrl)
    }

    @Test fun `封面 无uri 为null`() = runBlocking {
        startServer()
        seoResponse = seo(
            jsonPath("seo_track", "track", "album", "url_cover") to playNode(
                "urls" to JsonArray(listOf(JsonPrimitive("https://x/img/"))),
                "template_prefix" to JsonPrimitive("tpl"),
            ),
        )
        assertNull(engine().probe(trackUrl(), null).coverUrl)
    }

 // ------------------------------------------------------------------
 // VIP 三标志（probe 标记 + download onLog 提示不阻断；设计）
 // ------------------------------------------------------------------

    @Test fun `VIP only_vip_download 提示不阻断 产物完整`() = runBlocking {
        startServer()
        seoResponse = seo(
            jsonPath("seo_track", "track", "label_info") to playNode(
                "only_vip_download" to JsonPrimitive(true),
            ),
        )
        val e = engine()
        val info = e.probe(trackUrl(), null)
        assertTrue(info.isVip)
        val logs = mutableListOf<String>()
        val out = e.download(info, Mode.AUDIO, null, tmp.newFolder("task-vip1").toPath(), "mp3",
            { s -> logs += s }, { _, _ -> }, noCancel())
        assertEquals(2, out.size) // mp3 + 封面
        assertTrue(logs.any { it.contains("VIP") })
    }

    @Test fun `VIP only_vip_playable 与 quality_map need_vip 标志`() = runBlocking {
        startServer()
        seoResponse = seo(
            jsonPath("seo_track", "track", "label_info") to playNode(
                "only_vip_playable" to JsonPrimitive(true),
            ),
        )
        assertTrue(engine().probe(trackUrl(), null).isVip)
        seoResponse = seo(
            jsonPath("seo_track", "track", "label_info") to playNode(
                "quality_map" to JsonObject(mapOf(
                    "128" to playNode("need_vip" to JsonPrimitive(true)),
                )),
            ),
        )
        assertTrue(engine().probe(trackUrl(), null).isVip)
        seoResponse = seo(
            jsonPath("seo_track", "track", "label_info") to playNode(
                "quality_map" to JsonObject(mapOf(
                    "64" to playNode("need_vip" to JsonPrimitive(false)),
                    "320" to playNode("need_vip" to JsonPrimitive(true)),
                )),
            ),
        )
        assertTrue(engine().probe(trackUrl(), null).isVip)
 // 全 false → 非 VIP
        seoResponse = seo(
            jsonPath("seo_track", "track", "label_info") to playNode(
                "only_vip_download" to JsonPrimitive(false),
                "only_vip_playable" to JsonPrimitive(false),
                "quality_map" to JsonObject(mapOf("128" to playNode("need_vip" to JsonPrimitive(false)))),
            ),
        )
        assertFalse(engine().probe(trackUrl(), null).isVip)
    }

    @Test fun `非VIP 无VIP日志`() = runBlocking {
        startServer()
        val e = engine()
        val info = e.probe(trackUrl(), null)
        assertFalse(info.isVip)
        val logs = mutableListOf<String>()
        e.download(info, Mode.AUDIO, null, tmp.newFolder("task-novip").toPath(), "mp3",
            { s -> logs += s }, { _, _ -> }, noCancel())
        assertTrue(logs.none { it.contains("VIP") })
    }

 // ------------------------------------------------------------------
 // download（参考实现 download L157-194：流式→抽取→封面→标签；全程 Referer music.douyin.com）
 // ------------------------------------------------------------------

    @Test fun `download audio 双产物命名 封面在抽取后 请求头与进度`() = runBlocking {
        startServer()
        val e = engine()
        val info = e.probe(trackUrl(), null)
        val dir = tmp.newFolder("task-audio")
        val progress = mutableListOf<Pair<Float, String>>()
        val out = e.download(info, Mode.AUDIO, null, dir.toPath(), "mp3",
            {}, { f, s -> progress += f to s }, noCancel())
 // 产物 `作者 - 标题.mp3` + `作者 - 标题-cover.jpg`
        val baseName = "测试歌手 - DISCO 迪斯科 的士高 复古BGM"
        assertEquals(listOf("$baseName.mp3", "$baseName-cover.jpg"), out.map { it.fileName.toString() })
        assertTrue("音频产物来自流式下载内容（ffmpeg 桩转码）",
            out[0].toFile().readBytes().contentEquals(AUDIO_BYTES))
        assertTrue(out[1].toFile().readBytes().contentEquals(IMG_BYTES))
 // 顺序：流式 → 抽取 → 封面下载（参考实现 L177-188：封面在 extract_audio 之后）
        val audioIdx = requestLog.indexOfFirst { it.startsWith("GET /audio/") }
        val coverIdx = requestLog.indexOfFirst { it.startsWith("GET /img/") }
        assertTrue(audioIdx in 0 until coverIdx)
 // 全程 Referer music.douyin.com + UA=PC
        assertTrue(requestLog[audioIdx].contains("UA=$UA ") && requestLog[audioIdx].contains("Referer=$REFERER"))
        assertTrue(requestLog[coverIdx].contains("UA=$UA ") && requestLog[coverIdx].contains("Referer=$REFERER"))
 // 收尾 progress(1.0, 完成)；中间 MB 进度
        assertEquals(1.0f to "完成", progress.last())
        assertTrue(progress.dropLast(1).isNotEmpty() && progress.dropLast(1).all { it.second == "0.0/0.0 MB" })
    }

    @Test fun `download 封面404 无封面产物 音频仍交付`() = runBlocking {
        startServer()
        coverResponse = MockResponse().setResponseCode(404)
        val e = engine()
        val info = e.probe(trackUrl(), null)
        val out = e.download(info, Mode.AUDIO, null, tmp.newFolder("task-noimg").toPath(), "mp3",
            {}, { _, _ -> }, noCancel())
        assertEquals(1, out.size) // 仅音频（封面下载失败不阻断）
        assertTrue(out[0].toFile().readBytes().contentEquals(AUDIO_BYTES))
        assertFalse(out[0].fileName.toString().contains("-cover.jpg"))
    }

    @Test fun `download video模式 按音频执行并日志已按音频交付`() = runBlocking {
        startServer()
        val e = engine()
        val info = e.probe(trackUrl(), null)
        val logs = mutableListOf<String>()
        val out = e.download(info, Mode.VIDEO, null, tmp.newFolder("task-video").toPath(), "mp3",
            { s -> logs += s }, { _, _ -> }, noCancel())
        assertTrue(logs.any { it.contains("汽水无视频流，已按音频交付") })
 // 产物与音频模式一致（mp3 + 伴生封面）
        assertEquals(2, out.size)
        assertTrue(out[0].fileName.toString().endsWith(".mp3"))
        assertTrue(out[1].fileName.toString().endsWith("-cover.jpg"))
    }

    @Test fun `download cover模式 仅封面jpg`() = runBlocking {
        startServer()
        val e = engine()
        val info = e.probe(trackUrl(), null)
        val out = e.download(info, Mode.COVER, null, tmp.newFolder("task-cover").toPath(), "mp3",
            {}, { _, _ -> }, noCancel())
        assertEquals(listOf("DISCO 迪斯科 的士高 复古BGM.jpg"), out.map { it.fileName.toString() })
        assertTrue(out[0].toFile().readBytes().contentEquals(IMG_BYTES))
    }

    @Test fun `download 无作者 命名不带作者横杠`() = runBlocking {
        startServer()
        seoResponse = seo(jsonPath("seo_track", "track", "artists") to JsonArray(emptyList()))
        val e = engine()
        val info = e.probe(trackUrl(), null)
        assertEquals("", info.author)
        val out = e.download(info, Mode.AUDIO, null, tmp.newFolder("task-noauthor").toPath(), "mp3",
            {}, { _, _ -> }, noCancel())
        assertEquals(listOf("DISCO 迪斯科 的士高 复古BGM.mp3", "DISCO 迪斯科 的士高 复古BGM-cover.jpg"),
            out.map { it.fileName.toString() })
    }

    @Test fun `download 取消 轮询中止抛已取消`() = runBlocking {
        startServer()
        val e = engine()
        val info = e.probe(trackUrl(), null)
        try {
            e.download(info, Mode.AUDIO, null, tmp.newFolder("task-cancel").toPath(), "mp3",
                {}, { _, _ -> }, cancelNow())
            fail("应抛已取消")
        } catch (e: EngineCancelledException) { // M3：类型甄别（原 EngineException+message 串比对）
            assertEquals("已取消", e.message)
        }
    }

 // ------------------------------------------------------------------
 // 常量登记
 // ------------------------------------------------------------------

    @Test fun `生产默认luna基址`() {
        assertEquals("https://beta-luna.douyin.com", QishuiEngine.PROD_LUNA_BASE_URL)
    }
}
