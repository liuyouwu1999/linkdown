package top.linkdown.android.engine

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import top.linkdown.android.engine.internal.ChildRef
import top.linkdown.android.engine.internal.isBatchUrl
import top.linkdown.android.engine.internal.parseEnumerateJson

/**
 * 批量枚举解析纯函数 fixture 直测（不起 Chaquopy、不出网）。
 * 参考实现 linkdown/resolvers/batch_ytdlp.py：parseEnumerateJson ≙enumerate_batch 的 entries
 * 循环 + item_url；isBatchUrl ≙is_batch_url（platform 由 host 推导≙上位路由）。
 */
class BatchParseTest {

 /** fixture 资源装载（同 DouyinEngineTest.fixtureText 装载法，无 host 重写需求）。 */
    private fun fixtureRoot(name: String = "flat_dump.json"): JsonObject {
        val loader = requireNotNull(javaClass.classLoader) { "无 test classloader" }
        val raw = requireNotNull(loader.getResourceAsStream(name)) { "缺 fixture $name" }
            .readBytes().toString(Charsets.UTF_8)
        return Json.parseToJsonElement(raw) as JsonObject
    }

    private fun root(json: String) = Json.parseToJsonElement(json) as JsonObject

 // ------------------------------------------------------------------
 // parseEnumerateJson（fixture：B站相对 url 构造 + 跳过 + 直用）
 // ------------------------------------------------------------------

    @Test fun `flat_dump fixture 判型 B站相对url构造 无id与null entry跳过 http直用`() {
        val children = parseEnumerateJson(fixtureRoot())
 // 5 entries → 3 children（null entry、无 id entry 跳过）
        assertEquals(3, children.size)
 // 非数字 id（BV…）→ /video/
        assertEquals(
            ChildRef(url = "https://www.bilibili.com/video/BV1GJ411x7h7", title = "相对 url 走 id 基构造（非数字→video）"),
            children[0],
        )
 // 数字 id（番剧 flat entry 常给数字 ep id）→ /bangumi/play/ep
        assertEquals(
            ChildRef(url = "https://www.bilibili.com/bangumi/play/ep1008", title = "番剧 flat entry 数字 id→ep 构造"),
            children[1],
        )
 // raw url 已是 http → 原样直用（参考实现 item_url 第一分支）
        assertEquals(
            ChildRef(url = "https://www.bilibili.com/video/BV1zu411m7Yb?p=1", title = "绝对 http url 原样直用"),
            children[2],
        )
    }

    @Test fun `YouTube 相对url 构造 watch?v=`() {
        val root = root(
            """{"_type": "playlist",
                "webpage_url": "https://www.youtube.com/playlist?list=PLabc",
                "entries": [{"id": "dQw4w9WgXcQ", "title": "mv", "url": "dQw4w9WgXcQ"}]}""",
        )
        assertEquals(
            listOf(ChildRef(url = "https://www.youtube.com/watch?v=dQw4w9WgXcQ", title = "mv")),
            parseEnumerateJson(root),
        )
    }

    @Test fun `网易云 相对url 构造 song?id=`() {
        val root = root(
            """{"_type": "playlist",
                "webpage_url": "https://music.163.com/playlist?id=888",
                "entries": [{"id": 301, "title": ""}, {"id": "302"}]}""",
        )
 // 数字原语 id str() 化；title 空/缺失回退 mid（参考实现 `or mid`）
        assertEquals(
            listOf(
                ChildRef(url = "https://music.163.com/song?id=301", title = "301"),
                ChildRef(url = "https://music.163.com/song?id=302", title = "302"),
            ),
            parseEnumerateJson(root),
        )
    }

    @Test fun `单视频 dump（_type=video 或缺省）返回空列表`() {
        val video = root("""{"_type": "video", "id": "dQw4w9WgXcQ", "title": "单视频"}""")
        assertTrue(parseEnumerateJson(video).isEmpty())
        val bare = root("""{"id": "x"}""")
        assertTrue(parseEnumerateJson(bare).isEmpty())
    }

    @Test fun `entries==1 恒平铺 1 child（==1 回退单视频是管道职责 非解析层）`() {
        val root = root(
            """{"_type": "playlist",
                "webpage_url": "https://www.youtube.com/playlist?list=PLabc",
                "entries": [{"id": "only1", "title": "唯一一条"}]}""",
        )
        assertEquals(
            listOf(ChildRef(url = "https://www.youtube.com/watch?v=only1", title = "唯一一条")),
            parseEnumerateJson(root),
        )
    }

 // ------------------------------------------------------------------
 // isBatchUrl（前置门：防 generic 抽取器把普通网页链接误判为批量）
 // ------------------------------------------------------------------

    @Test fun `isBatchUrl youtube 仅纯列表过门 混合型 watch&list 不过`() {
        assertTrue(isBatchUrl("https://www.youtube.com/playlist?list=PLabc"))
        assertTrue(isBatchUrl("https://m.youtube.com/playlist?list=PLabc"))
 // 混合型 watch&list（设计：不过前置门=单视频路径+「仅下这一个」逃逸）
        assertFalse(isBatchUrl("https://www.youtube.com/watch?v=dQw4w9WgXcQ&list=PLabc"))
        assertFalse(isBatchUrl("https://www.youtube.com/playlist")) // 缺 list 参数
        assertFalse(isBatchUrl("https://www.youtube.com/playlist?list=")) // 空值≙parse_qs 丢弃
        assertFalse(isBatchUrl("https://youtu.be/dQw4w9WgXcQ"))
    }

    @Test fun `isBatchUrl netease 仅 playlist album 带 id 过门`() {
        assertTrue(isBatchUrl("https://music.163.com/playlist?id=888"))
        assertTrue(isBatchUrl("https://music.163.com/album?id=1001"))
        assertFalse(isBatchUrl("https://music.163.com/song?id=301"))
        assertFalse(isBatchUrl("https://music.163.com/playlist")) // 缺 id
        assertFalse(isBatchUrl("https://music.163.com/#/playlist?id=888")) // ≙urlparse path="/"（参考实现同判 false）
    }

    @Test fun `isBatchUrl bilibili space 系与合集番剧形状`() {
 // space 系：纯数字 uid 根 / _SPACE_EXTRA 七段二段过门
        assertTrue(isBatchUrl("https://space.bilibili.com/9469742"))
        assertTrue(isBatchUrl("https://space.bilibili.com/9469742/video"))
        assertTrue(isBatchUrl("https://space.bilibili.com/9469742/uploads"))
        assertTrue(isBatchUrl("https://space.bilibili.com/9469742/lists"))
        assertTrue(isBatchUrl("https://space.bilibili.com/9469742/index"))
        assertTrue(isBatchUrl("https://space.bilibili.com/9469742/audio"))
        assertTrue(isBatchUrl("https://space.bilibili.com/9469742/bangumi"))
 // space 系不过门形状
        assertFalse(isBatchUrl("https://space.bilibili.com/9469742/live"))
        assertFalse(isBatchUrl("https://space.bilibili.com/not-a-uid"))
        assertFalse(isBatchUrl("https://space.bilibili.com/"))
 // 番剧/合集系
        assertTrue(isBatchUrl("https://www.bilibili.com/ep1008"))
        assertTrue(isBatchUrl("https://www.bilibili.com/ss4567"))
        assertTrue(isBatchUrl("https://www.bilibili.com/bangumi/play/ep1008"))
 // 单视频/其他不过门
        assertFalse(isBatchUrl("https://www.bilibili.com/video/BV1GJ411x7h7"))
        assertFalse(isBatchUrl("https://www.bilibili.com/medialist/play/123"))
        assertFalse(isBatchUrl("https://b23.tv/BV1GJ411x7h7"))
    }

    @Test fun `isBatchUrl 非批量平台 host 一律不过门`() {
        assertFalse(isBatchUrl("https://v.douyin.com/abc123/"))
        assertFalse(isBatchUrl("https://www.douyin.com/video/123"))
        assertFalse(isBatchUrl("https://www.qishui.com/music/1"))
        assertFalse(isBatchUrl("not a url"))
    }
}
