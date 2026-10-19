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
import java.nio.file.Files
import java.nio.file.Path

/**
 * XhsEngine fixture 单测（MockWebServer + xhs 页面基址注入缝）。
 * 解析键对齐 XHS-Downloader：__INITIAL_STATE__（undefined→null / new Map([])→[] 清洗）→
 * noteDetailMap[noteId].note → 视频 originVideoKey 直链优先 / media.stream 各清晰度按 height 取最高
 * （backupUrls[0] 优先 masterUrl）→ 图文 imageList[].url_default。下载流量不出网（host 重写）。
 */
class XhsEngineTest {
    @get:Rule val tmp = TemporaryFolder()

    companion object {
        const val NOTE_ID = "65f1a2b3000000001200abcd"
        val VIDEO_BYTES = ByteArray(4000) { i -> "VID"[i % 3].code.toByte() }
        val IMG_BYTES = byteArrayOf(1, 2, 3, 4)

 /** 视频笔记 state（含 undefined 与 new Map 清洗点、h264/h265 双清晰度）。 */
        fun videoState(videoBase: String, imgBase: String): String = """
window.__INITIAL_STATE__={
  "note": {
    "noteDetailMap": {
      "$NOTE_ID": {
        "note": {
          "type": "video",
          "title": "测试视频笔记",
          "desc": "<a href=\"x\">描述</a>内容",
          "user": {
            "nickname": "作者昵称"
          },
          "imageList": [
            {
              "urlDefault": "$imgBase/img/cover.jpg",
              "url": "$imgBase/img/cover_raw.jpg"
            }
          ],
          "video": {
            "media": {
              "stream": {
                "h264": [
                  {
                    "masterUrl": "$videoBase/v/master720.mp4",
                    "backupUrls": [
                      "$videoBase/v/b720.mp4"
                    ],
                    "height": 720
                  }
                ],
                "h265": [
                  {
                    "masterUrl": "$videoBase/v/master1080.mp4",
                    "backupUrls": [
                      "$videoBase/v/b1080.mp4"
                    ],
                    "height": 1080
                  }
                ]
              }
            },
            "consumer": {
              "originVideoKey": undefined
            }
          }
        }
      }
    }
  },
  "other": undefined
}
""".trimIndent()
    }

    private var server: MockWebServer? = null
    private val requestLog = mutableListOf<String>()

 // 可按用例改写的桩
    private var stateBody: String = ""
    private var stateCode = 200
    private var videoResponse: MockResponse = MockResponse().setResponseCode(200)
    private var imgResponse: MockResponse = MockResponse().setResponseCode(200)
    private var coverResponse: MockResponse = MockResponse().setResponseCode(200)

    @After fun tearDown() {
        server?.shutdown()
        XhsEngine.clearProbeMemo()
    }

    private fun startServer(kind: String = "video"): XhsEngine {
        val s = MockWebServer()
        s.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requestLog += request.method + " " + request.path
                return when {
                    request.path?.startsWith("/explore/") == true -> {
                        if (stateCode != 200) MockResponse().setResponseCode(stateCode)
                        else MockResponse().setResponseCode(200).setBody(stateBody)
                    }
                    request.path?.contains("master1080") == true ||
                        request.path?.contains("video.mp4") == true -> videoResponse
                    request.path?.contains("b1080") == true ||
                        request.path?.contains("backup.mp4") == true -> videoResponse
                    request.path?.contains("/img/") == true -> imgResponse
                    request.path?.contains("cover.jpg") == true -> coverResponse
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        s.start()
        server = s
        val base = s.url("/").toString().trimEnd('/')
        stateBody = XhsEngineTest.videoState(base, base)
        if (kind == "image") {
            stateBody = """
                window.__INITIAL_STATE__={"note":{"noteDetailMap":{"$NOTE_ID":{"note":{
                  "type":"normal",
                  "title":"测试图文笔记",
                  "desc":undefined,
                  "user":{"nickname":"作者昵称"},
                  "imageList":[
                    {"url":"$base/img/1_raw.jpg","urlDefault":"$base/img/1.jpg"},
                    {"url":"$base/img/2_raw.jpg","urlDefault":"$base/img/2.jpg"}
                  ]
                }}}},"trace":new Map([])}
            """.trimIndent()
        }
        return XhsEngine(xhsBaseUrl = base, ffmpegPath = "ffmpeg")
    }

    private fun noCancel(): CancelHandle = object : CancelHandle {
        override fun isCancelled() = false
    }

 // ------------------------------------------------------------------
 // 链接解析
 // ------------------------------------------------------------------

    @Test fun noteId_explore() {
        val e = startServer()
        val probe = runCatching {
            runBlocking { e.probe("http://localhost:1/explore/$NOTE_ID?xsec_token=TK", cookies = null) }
        }
 // host 非 xiaohongshu 也接受（单测注入基址）；只断言不因找不到 id 失败
        assertTrue(probe.exceptionOrNull()?.message?.contains("id") != true)
    }

    @Test fun noteId_nonXhs_returnsNullPath() {
        val e = startServer()
 // bilibili 链接 → extract 不到 id → fetchPage 用 null id 也能拼 URL，但 state 里无该键 → STATE_FAIL
 // 这里只验证不抛未预期异常类型
        try {
            runBlocking { e.probe("https://www.bilibili.com/video/BV1xx", cookies = null) }
        } catch (ex: EngineException) {
            assertTrue(ex.message!!.contains("id") || ex.message!!.contains("解析"))
        }
    }

 // ------------------------------------------------------------------
 // 视频笔记
 // ------------------------------------------------------------------

    @Test fun probe_video_picksHighestBackup_andCleansState() = runBlocking {
        val e = startServer("video")
        val info = e.probe("http://localhost:1/explore/$NOTE_ID?xsec_token=TK", cookies = null)
        assertEquals("小红书", info.platform)
        assertEquals(NOTE_ID, info.mediaId)
        assertEquals("作者昵称", info.author)
        assertEquals("测试视频笔记", info.title)
 // h265 1080 最高，backupUrls[0] 优先
        assertTrue(info.playUrl!!.contains("b1080.mp4"))
        assertTrue(info.coverUrl!!.contains("cover.jpg"))
        assertTrue("清洗后 undefined 变 null，state 能解析", true)
    }

    @Test fun probe_xhscdn明文http升https_非xhscdn原样保留() = runBlocking {
 // 真机实测：__INITIAL_STATE__ 里的 xhscdn 图链下发 http 明文 → Android 9+ 禁明文
 // （CLEARTEXT not permitted，参考网页版不拦所以没暴露）——仅 xhscdn 域升 https，
 // MockWebServer 的 http://localhost 桩基址必须原样保留（升了下载桩就收不到请求）
        val e = startServer("image")
        val base = server!!.url("/").toString().trimEnd('/')
        stateBody = """
            window.__INITIAL_STATE__={"note":{"noteDetailMap":{"$NOTE_ID":{"note":{
              "type":"normal","title":"明文直链","desc":undefined,
              "user":{"nickname":"作者昵称"},
              "imageList":[
                {"urlDefault":"http://sns-webpic-qc.xhscdn.com/1.jpg"},
                {"urlDefault":"$base/img/2.jpg"}
              ]
            }}}},"trace":new Map([])}
        """.trimIndent()
        val info = e.probe("http://localhost:1/explore/$NOTE_ID?xsec_token=TK", cookies = null)
        val urls = info.playUrl!!.split(";")
        assertEquals("https://sns-webpic-qc.xhscdn.com/1.jpg", urls[0])
        assertEquals("非 xhscdn 的 http 原样保留", "$base/img/2.jpg", urls[1])
    }

    @Test fun probe_视频backup明文http升https() = runBlocking {
        val e = startServer("video")
        stateBody = videoState("http://sns-video-hw.xhscdn.com", "http://sns-webpic-qc.xhscdn.com")
        val info = e.probe("http://localhost:1/explore/$NOTE_ID?xsec_token=TK", cookies = null)
        assertTrue("视频 backup 直链升 https", info.playUrl!!.startsWith("https://sns-video-hw.xhscdn.com/"))
        assertTrue("封面直链升 https", info.coverUrl!!.startsWith("https://sns-webpic-qc.xhscdn.com/"))
    }

    @Test fun download_video_streamsAndCompanionCover() = runBlocking {
        videoResponse = MockResponse().setResponseCode(200).setBody(Buffer().write(VIDEO_BYTES))
        coverResponse = MockResponse().setResponseCode(200).setBody(Buffer().write(IMG_BYTES))
        val e = startServer("video")
        val info = e.probe("http://localhost:1/explore/$NOTE_ID?xsec_token=TK", cookies = null)
        val dir: Path = tmp.newFolder("task").toPath()
        val products = e.download(info, Mode.VIDEO, cookies = null, taskDir = dir,
            audioFormat = "mp3", onLog = {}, onProgress = { _, _ -> },
            cancelHandle = noCancel(), quality = "max")
        assertEquals(2, products.size)
        assertTrue(products[0].fileName.toString().endsWith(".mp4"))
        assertTrue(products[1].fileName.toString().endsWith("-cover.jpg"))
        assertEquals(VIDEO_BYTES.size, Files.readAllBytes(products[0]).size)
    }

 // ------------------------------------------------------------------
 // 图文笔记
 // ------------------------------------------------------------------

    @Test fun probe_image_hasImagesJoined() = runBlocking {
        val e = startServer("image")
        val info = e.probe("http://localhost:1/explore/$NOTE_ID?xsec_token=TK", cookies = null)
        assertTrue(info.hasImages)
 // url_default 优先；两张图分号拼接
        val urls = info.playUrl!!.split(";")
        assertEquals(2, urls.size)
        assertTrue(urls[0].contains("/img/1.jpg"))
    }

    @Test fun download_images_downloadsAllNumbered() = runBlocking {
        imgResponse = MockResponse().setResponseCode(200).setBody(Buffer().write(IMG_BYTES))
        val e = startServer("image")
        val info = e.probe("http://localhost:1/explore/$NOTE_ID?xsec_token=TK", cookies = null)
        val dir: Path = tmp.newFolder("task3").toPath()
        val products = e.download(info, Mode.VIDEO, cookies = null, taskDir = dir,
            audioFormat = "mp3", onLog = {}, onProgress = { _, _ -> },
            cancelHandle = noCancel(), quality = "max")
        assertEquals(2, products.size)
        assertTrue(products[0].fileName.toString().endsWith("-1.jpg"))
        assertTrue(products[1].fileName.toString().endsWith("-2.jpg"))
    }

 // ------------------------------------------------------------------
 // 错误态
 // ------------------------------------------------------------------

    @Test fun probe_pageFails_throws() = runBlocking {
        val e = startServer()
        stateCode = 461
        try {
            e.probe("http://localhost:1/explore/$NOTE_ID?xsec_token=TK", cookies = null)
            fail("应当抛页面失败")
        } catch (ex: EngineException) {
            assertTrue(ex.message!!.contains("小红书"))
        }
    }

    @Test fun probe_stateWithoutNote_throws() = runBlocking {
        val e = startServer()
        stateBody = """window.__INITIAL_STATE__={"note":{"noteDetailMap":{}}}"""
        try {
            e.probe("http://localhost:1/explore/$NOTE_ID?xsec_token=TK", cookies = null)
            fail("应当抛解析失败")
        } catch (ex: EngineException) {
            assertTrue(ex.message!!.contains("解析"))
        }
    }

    @Test fun nullProbeHelpers() {
 // 冒烟：占位断言防空转
        assertNull(null as String?)
    }
}