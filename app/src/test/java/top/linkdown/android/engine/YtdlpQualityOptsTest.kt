package top.linkdown.android.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import java.nio.file.Path

/**
 * 功能任务 Step 2：YtdlpEngine 画质档 extraOpts 映射（top-level internal 纯函数直测，
 * 不起 Chaquopy——映射逻辑与引擎执行分离后的 JVM 可测形态）。
 * 追加：单视频路径恒带 --no-playlist（设计——混合型 watch&list 只下点名的那一个；
 * 子项/普通单页 no-op），三模式全量断言。
 */
class YtdlpQualityOptsTest {

    @get:Rule val tmp = TemporaryFolder()

    @Test fun `VIDEO 1080P 限档 注入 -f 格式链`() {
        assertEquals(
            listOf("-f" to YtdlpEngine.VIDEO_FORMAT_1080P, "--write-thumbnail" to null, "--no-playlist" to null),
            downloadOptsFor(Mode.VIDEO, "mp3", "1080p"),
        )
        assertEquals("bv*[height<=1080]+ba/b", YtdlpEngine.VIDEO_FORMAT_1080P)
    }

    @Test fun `VIDEO max 默认格式链不带 -f`() {
        assertEquals(
            listOf("--write-thumbnail" to null, "--no-playlist" to null),
            downloadOptsFor(Mode.VIDEO, "mp3", "max"),
        )
    }

    @Test fun `AUDIO 只带音频格式转换 画质档不消费`() {
        assertEquals(
            listOf("-x" to null, "--audio-format" to "m4a", "--write-thumbnail" to null, "--no-playlist" to null),
            downloadOptsFor(Mode.AUDIO, "m4a", "1080p"),
        )
    }

    @Test fun `COVER 只带缩略图旗标 画质档与音频格式均不带`() {
        assertEquals(
            listOf("--write-thumbnail" to null, "--skip-download" to null, "--no-playlist" to null),
            downloadOptsFor(Mode.COVER, "mp3", "1080p"),
        )
    }

    @Test fun `pairThumbnailCompanion 缩略图改名伴生并入产物`() {
        val dir = tmp.newFolder("pair").toPath()
        val media = Files.write(dir.resolve("视频.mp4"), ByteArray(3000))
        val thumb = Files.write(dir.resolve("视频.webp"), ByteArray(50))
        val out = pairThumbnailCompanion(listOf(thumb, media))
        assertEquals(2, out.size)
        assertTrue(out.any { it.fileName.toString() == "视频-cover.webp" })
        assertTrue("缩略图已原地改名", java.nio.file.Files.notExists(dir.resolve("视频.webp")))
    }

    @Test fun `pairThumbnailCompanion 无缩略图原样返回`() {
        val dir = tmp.newFolder("pair2").toPath()
        val media = Files.write(dir.resolve("视频.mp4"), ByteArray(3000))
        val out = pairThumbnailCompanion(listOf(media))
        assertEquals(listOf<Path>(media), out)
    }

    @Test fun `单视频路径恒带 no-playlist 三模式全量`() {
 // 设计「单视频路径恒带 --no-playlist」：混合型 URL 默认路径的判重保险
        listOf(Mode.VIDEO, Mode.AUDIO, Mode.COVER).forEach { mode ->
            val opts = downloadOptsFor(mode, "mp3", "max")
            assertTrue("mode=$mode 缺 --no-playlist", opts.any { it.first == "--no-playlist" && it.second == null })
        }
    }
}
