package top.linkdown.android

import org.junit.Assert.assertEquals
import org.junit.Test
import top.linkdown.android.pipeline.PlatformDirs


/** 各平台分享文案形态矩阵（提取 + 平台路由联合验证）。 */
class ShareTextMatrixTest {
    private val cases = mapOf(
        "《电气工程师去老家出差时》 #电气专业就业 #钓鱼竿 https://v.douyin.com/EIm16yopJc4/ 复制此链接，打开【抖音】，直接观看视频！"
            to "https://v.douyin.com/EIm16yopJc4/",
        "【官方 MV】Never Gonna Give You Up - Rick Astley https://www.bilibili.com/video/BV1GJ411x7h7?share_source=copy_link&share_medium=iphone 复制此链接,打开【哔哩哔哩】App,观看视频!"
            to "https://www.bilibili.com/video/BV1GJ411x7h7?share_source=copy_link&share_medium=iphone",
        "【官方 MV】Never Gonna Give You Up https://b23.tv/BV1GJ411x7h7 复制此链接,打开【哔哩哔哩】App"
            to "https://b23.tv/BV1GJ411x7h7",
        "《电气工程师去老家出差时》 https://www.xiaohongshu.com/explore/65f1a2b3000000001200abcd?xsec_token=ABC123&xsec_source=xhs_share ✂️复制此链接，打开【小红书】App查看精彩内容！"
            to "https://www.xiaohongshu.com/explore/65f1a2b3000000001200abcd?xsec_token=ABC123&xsec_source=xhs_share",
        "笔记分享 https://xhslink.com/abcdEF 对你有亿点点启发哦～"
            to "https://xhslink.com/abcdEF",
        "【转发】超赞的视频 https://weibo.com/tv/show/1034:1234567 来自@微博视频号"
            to "https://weibo.com/tv/show/1034:1234567",
        "Never Gonna Give You Up https://youtu.be/dQw4w9WgXcQ via @YouTube"
            to "https://youtu.be/dQw4w9WgXcQ",
        "chill mix https://qishui.douyin.com/share/video/7312345678901234 原声很好听"
            to "https://qishui.douyin.com/share/video/7312345678901234",
    )

    @Test fun `各平台完整分享文案均可提取首个链接`() {
        cases.forEach { (text, expected) ->
            assertEquals("文本: " + text.take(24), listOf(expected), extractShareUrls(text))
        }
    }

    @Test fun `提取的链接全部被平台路由识别为已知平台`() {
        cases.values.forEach { url ->
            assertEquals("url: " + url, true, top.linkdown.android.pipeline.PlatformDirs.dirFor(url) != "其他")
        }
    }
}
