package top.linkdown.android

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 分享文本 URL 提取纯函数单测（设计：正则限定合法字符集 + 去尾部 CJK 标点 + 多链接取首个）。
 */
class ShareUrlExtractorTest {

    @Test fun extracts_plain_https_url() {
        assertEquals(listOf("https://v.douyin.com/abc123/"), extractShareUrls("https://v.douyin.com/abc123/"))
    }

    @Test fun extracts_http_url() {
        assertEquals(listOf("http://x.com/a"), extractShareUrls("http://x.com/a"))
    }

    @Test fun url_without_spaces_in_chinese_text() {
        assertEquals(
            listOf("https://b23.tv/xyz"),
            extractShareUrls("看看这个视频https://b23.tv/xyz很好看"),
        )
    }

    @Test fun trailing_cjk_punctuation_not_included() {
 // 正则字符集天然截断 CJK；尾部剥离集 。，、！？）】」为 设计逐字兜底
        assertEquals(listOf("https://x.com/a"), extractShareUrls("https://x.com/a。"))
        assertEquals(listOf("https://x.com/a"), extractShareUrls("（https://x.com/a）很好"))
    }

    @Test fun query_and_fragment_kept() {
        assertEquals(
            listOf("https://x.com/a?b=1&c=2#top"),
            extractShareUrls("https://x.com/a?b=1&c=2#top"),
        )
    }

    @Test fun multiple_urls_returns_all_first_is_target() {
        val urls = extractShareUrls("第一个https://x.com/1 第二个https://x.com/2")
        assertEquals(2, urls.size)
        assertEquals("https://x.com/1", urls.first())
    }

    @Test fun no_url_returns_empty() {
        assertEquals(emptyList<String>(), extractShareUrls("这是一段没有链接的文字"))
        assertEquals(emptyList<String>(), extractShareUrls(""))
    }

    @Test fun ftp_and_bare_domain_not_matched() {
        assertEquals(emptyList<String>(), extractShareUrls("ftp://x.com/a"))
        assertEquals(emptyList<String>(), extractShareUrls("去 www.bilibili.com 看看"))
    }

    @Test fun whitespace_and_cjk_stop_matching() {
 // URL 中的空白/CJK 属非法字符集 → 截断
        assertEquals(listOf("https://x.com/a"), extractShareUrls("https://x.com/a b"))
        assertEquals(listOf("https://x.com/a"), extractShareUrls("https://x.com/a路径"))
    }
}
