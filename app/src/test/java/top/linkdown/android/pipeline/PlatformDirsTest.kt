package top.linkdown.android.pipeline

import org.junit.Assert.assertEquals
import org.junit.Test

class PlatformDirsTest {
    @Test fun `maps all spec hosts`() {
        assertEquals("哔哩哔哩", PlatformDirs.dirFor("https://www.bilibili.com/video/BV1x"))
        assertEquals("哔哩哔哩", PlatformDirs.dirFor("https://b23.tv/abc"))
        assertEquals("网易云", PlatformDirs.dirFor("https://music.163.com/song?id=1"))
        assertEquals("YouTube", PlatformDirs.dirFor("https://www.youtube.com/watch?v=1"))
        assertEquals("YouTube", PlatformDirs.dirFor("https://youtu.be/1"))
        assertEquals("X", PlatformDirs.dirFor("https://x.com/a/status/1"))
        assertEquals("X", PlatformDirs.dirFor("https://twitter.com/a/status/1"))
        assertEquals("Instagram", PlatformDirs.dirFor("https://www.instagram.com/p/abc/"))
        assertEquals("TikTok", PlatformDirs.dirFor("https://www.tiktok.com/@a/video/1"))
        assertEquals("SoundCloud", PlatformDirs.dirFor("https://soundcloud.com/a/b"))
    }
    @Test fun `qishui wins over douyin by longest suffix`() {
        assertEquals("汽水", PlatformDirs.dirFor("https://qishui.douyin.com/s/abc"))
        assertEquals("抖音", PlatformDirs.dirFor("https://www.douyin.com/video/1"))
    }
    @Test fun `iesdouyin hosts map to douyin dir`() {
        assertEquals("抖音", PlatformDirs.dirFor("https://www.iesdouyin.com/share/video/7300000000000000000"))
        assertEquals("抖音", PlatformDirs.dirFor("https://iesdouyin.com/share/video/7300000000000000000"))
    }
    @Test fun `unknown and garbage fall back`() {
        assertEquals("其他", PlatformDirs.dirFor("https://example.com/x"))
        assertEquals("其他", PlatformDirs.dirFor("不是链接"))
        assertEquals("其他", PlatformDirs.dirFor(""))
    }
}
