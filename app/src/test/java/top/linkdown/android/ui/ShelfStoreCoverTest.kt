package top.linkdown.android.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 书架模式封面解析（货架书架视图）：伴生封面匹配纯函数直测。
 * 产物命名规律（下载引擎落盘）：主文件「X.mp4/m4a」+ 伴生封面「X-cover.jpg」（B 站/汽水引擎均有）。
 */
class ShelfStoreCoverTest {

    private val shelf = listOf(
        "我的视频.mp4",
        "我的视频-cover.jpg",
        "等天黑 - 枫.m4a",
        "等天黑 - 枫-cover.jpg",
        "无封面歌曲.mp3",
        "某封面图-cover.jpg",
        "纯图片.png",
    )

    @Test fun `视频匹配伴生封面`() {
        assertEquals("我的视频-cover.jpg", companionCoverOf("我的视频.mp4", shelf))
    }

    @Test fun `音频匹配伴生封面`() {
        assertEquals("等天黑 - 枫-cover.jpg", companionCoverOf("等天黑 - 枫.m4a", shelf))
    }

    @Test fun `无伴生返回null`() {
        assertNull(companionCoverOf("无封面歌曲.mp3", shelf))
    }

    @Test fun `封面文件自身不找封面`() {
        assertNull(companionCoverOf("某封面图-cover.jpg", shelf))
        assertNull(companionCoverOf("我的视频-cover.jpg", shelf))
    }

    @Test fun `同名不同扩展不算伴生`() {
 // 视频 X.mp4 的伴生必须精确为 X-cover.<图扩展>；X.png 不是
        assertNull(companionCoverOf("纯图片.png", shelf))
    }

    @Test fun `封面扩展支持jpeg与png`() {
        val names = listOf("a.mp4", "a-cover.jpeg", "b.mp4", "b-cover.png")
        assertEquals("a-cover.jpeg", companionCoverOf("a.mp4", names))
        assertEquals("b-cover.png", companionCoverOf("b.mp4", names))
    }

    @Test fun `集合为空不崩`() {
        assertNull(companionCoverOf("x.mp4", emptyList()))
    }
}
