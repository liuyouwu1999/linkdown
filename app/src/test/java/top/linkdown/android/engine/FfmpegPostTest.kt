package top.linkdown.android.engine

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import top.linkdown.android.engine.internal.FfmpegPost
import java.io.File
import java.nio.file.Files
import java.nio.file.Path

/**
 * 宿主真 ffmpeg 单测（brew ffmpeg/ffprobe；API 参考实现 =参考管线 postprocess.py 逐字平移）。
 * 依赖 /opt/homebrew/bin/ffmpeg 与 /opt/homebrew/bin/ffprobe（FfmpegPost 构造收 ffmpegPath）。
 */
class FfmpegPostTest {
    @get:Rule val tmp = TemporaryFolder()

    private val ffmpegBin = "/opt/homebrew/bin/ffmpeg"
    private val ffprobeBin = "/opt/homebrew/bin/ffprobe"
    private val post = FfmpegPost(ffmpegBin)

    private fun run(vararg cmd: String): String {
        val p = ProcessBuilder(*cmd).start()
        val out = p.inputStream.readBytes().toString(Charsets.UTF_8)
        val err = p.errorStream.readBytes().toString(Charsets.UTF_8)
        assertEquals("${cmd.joinToString(" ")} 失败: $err", 0, p.waitFor())
        return out
    }

 /** 参考实现 同款合成源：-f lavfi -i sine 出 1s m4a。 */
    private fun makeM4a(dst: File) = run(
        ffmpegBin, "-hide_banner", "-loglevel", "error", "-y",
        "-f", "lavfi", "-i", "sine=frequency=440:duration=1", dst.absolutePath,
    )

    private fun makeCover(dst: File) = run(
        ffmpegBin, "-hide_banner", "-loglevel", "error", "-y",
        "-f", "lavfi", "-i", "color=c=red:s=64x64", "-frames:v", "1", dst.absolutePath,
    )

    private fun probe(path: Path): JsonObject = Json.parseToJsonElement(
        run(ffprobeBin, "-v", "quiet", "-print_format", "json", "-show_format", "-show_streams", path.toString()),
    ).jsonObject

    private fun JsonObject.formatTags() = get("format")!!.jsonObject["tags"]!!.jsonObject
    private fun JsonObject.streamList() = get("streams")!!.jsonArray.map { it.jsonObject }

 /** 假 ffmpeg：先记录收到的全部参数再转调真 ffmpeg——断言参数逐字=参考管线 postprocess.py。 */
    private fun writeCaptureWrapper(capture: File): String {
        val sh = tmp.newFile("ffmpeg-capture.sh")
        sh.writeText("#!/bin/sh\nprintf '%s\\n' \"\$@\" > \"${capture.absolutePath}\"\nexec $ffmpegBin \"\$@\"\n")
        ProcessBuilder("chmod", "+x", sh.absolutePath).start().waitFor()
        return sh.absolutePath
    }

 // —— extractAudio（失败阻断语义；参考实现 postprocess.py L51-57）——

    @Test fun `extractAudio mp3 分支出 mp3 音频`() = runBlocking {
        val src = tmp.newFile("src.m4a"); makeM4a(src)
        val dst = tmp.root.toPath().resolve("out.mp3")
        assertEquals(dst, post.extractAudio(src.toPath(), dst, "mp3") { false })
        val json = probe(dst)
        val streams = json.streamList()
        assertEquals("应只有一条音频流（-vn）", 1, streams.size)
        assertEquals("mp3", streams[0]["codec_name"]!!.jsonPrimitive.content)
        val duration = json["format"]!!.jsonObject["duration"]!!.jsonPrimitive.content.toDouble()
        assertTrue("duration=$duration 应约 1s", duration in 0.7..1.5)
    }

    @Test fun `extractAudio m4a 分支出 aac 音频`() = runBlocking {
        val src = tmp.newFile("src.m4a"); makeM4a(src)
        val dst = tmp.root.toPath().resolve("out.m4a")
        post.extractAudio(src.toPath(), dst, "m4a") { false }
        val streams = probe(dst).streamList()
        assertEquals(1, streams.size)
        assertEquals("aac", streams[0]["codec_name"]!!.jsonPrimitive.content)
    }

    @Test fun `extractAudio 非 mp3 m4a 值回退 mp3`() = runBlocking {
        val src = tmp.newFile("src.m4a"); makeM4a(src)
        val dst = tmp.root.toPath().resolve("out.mp3")
        post.extractAudio(src.toPath(), dst, "flac") { false }
        assertEquals("mp3", probe(dst).streamList()[0]["codec_name"]!!.jsonPrimitive.content)
    }

    @Test fun `extractAudio 参数逐字对照 参考管线 postprocess mp3 分支`() = runBlocking {
        val capture = tmp.newFile("args.txt")
        val src = tmp.newFile("src.m4a"); makeM4a(src)
        val dst = tmp.root.toPath().resolve("out.mp3")
        FfmpegPost(writeCaptureWrapper(capture)).extractAudio(src.toPath(), dst, "mp3") { false }
        assertEquals(
            listOf("-hide_banner", "-loglevel", "error", "-y",
                "-i", src.absolutePath, "-vn", "-c:a", "libmp3lame", "-q:a", "0", dst.toString()),
            capture.readLines(),
        )
    }

 // —— tagAudio（失败不阻断语义；参考实现 postprocess.py L60-76）——

    @Test fun `tagAudio 有封面 title artist 写入且封面为 attached_pic 视频流`() = runBlocking {
        val src = tmp.newFile("src.m4a"); makeM4a(src)
        val cover = tmp.newFile("cover.jpg"); makeCover(cover)
        val mp3 = tmp.root.toPath().resolve("a.mp3")
        post.extractAudio(src.toPath(), mp3, "mp3") { false }
        assertEquals(mp3, post.tagAudio(mp3, cover.toPath(), "测试标题", "作者A", "") { false })
        val json = probe(mp3)
        assertEquals("mp3", json["format"]!!.jsonObject["format_name"]!!.jsonPrimitive.content)
        assertEquals("测试标题", json.formatTags()["title"]!!.jsonPrimitive.content)
        assertEquals("作者A", json.formatTags()["artist"]!!.jsonPrimitive.content)
        val pic = json.streamList().single { it["codec_type"]!!.jsonPrimitive.content == "video" }
        assertEquals("封面流须标 attached_pic", "1", pic["disposition"]!!.jsonObject["attached_pic"]!!.jsonPrimitive.content)
        assertTrue("tagged 临时文件应已改名回原名", Files.exists(mp3))
    }

    @Test fun `tagAudio 无封面 c copy 不新增视频流且写入 metadata`() = runBlocking {
        val src = tmp.newFile("src.m4a"); makeM4a(src)
        val mp3 = tmp.root.toPath().resolve("a.mp3")
        post.extractAudio(src.toPath(), mp3, "mp3") { false }
        post.tagAudio(mp3, null, "标题2", "作者B", "") { false }
        val json = probe(mp3)
        val streams = json.streamList()
        assertEquals("仅原音频流（-c copy）", 1, streams.size)
        assertEquals("audio", streams[0]["codec_type"]!!.jsonPrimitive.content)
        assertEquals("标题2", json.formatTags()["title"]!!.jsonPrimitive.content)
        assertEquals("作者B", json.formatTags()["artist"]!!.jsonPrimitive.content)
    }

    @Test fun `tagAudio 参数逐字对照 参考管线 postprocess 有封面分支`() = runBlocking {
        val capture = tmp.newFile("args.txt")
        val src = tmp.newFile("src.m4a"); makeM4a(src)
        val cover = tmp.newFile("cover.jpg"); makeCover(cover)
        val mp3 = tmp.root.toPath().resolve("a.mp3")
        post.extractAudio(src.toPath(), mp3, "mp3") { false }
        FfmpegPost(writeCaptureWrapper(capture)).tagAudio(mp3, cover.toPath(), "测试标题", "作者A", "") { false }
        assertEquals(
            listOf("-hide_banner", "-loglevel", "error", "-y",
                "-i", mp3.toString(),
                "-i", cover.absolutePath,
                "-map", "0:a", "-map", "1:v",
                "-c:v", "copy", "-disposition:v", "attached_pic",
                "-id3v2_version", "3",
                "-metadata", "title=测试标题",
                "-metadata", "artist=作者A",
                "-metadata", "album=",
                tmp.root.resolve("a.tagged.mp3").absolutePath),
            capture.readLines(),
        )
    }

    @Test fun `tagAudio 参数逐字对照 参考管线 postprocess 无封面分支`() = runBlocking {
        val capture = tmp.newFile("args.txt")
        val src = tmp.newFile("src.m4a"); makeM4a(src)
        val mp3 = tmp.root.toPath().resolve("a.mp3")
        post.extractAudio(src.toPath(), mp3, "mp3") { false }
        FfmpegPost(writeCaptureWrapper(capture)).tagAudio(mp3, null, "t", "a", "") { false }
        assertEquals(
            listOf("-hide_banner", "-loglevel", "error", "-y",
                "-i", mp3.toString(),
                "-c", "copy",
                "-id3v2_version", "3",
                "-metadata", "title=t",
                "-metadata", "artist=a",
                "-metadata", "album=",
                tmp.root.resolve("a.tagged.mp3").absolutePath),
            capture.readLines(),
        )
    }

    @Test fun `tagAudio 失败不阻断记日志返回原文件`() = runBlocking {
        val missing = tmp.root.toPath().resolve("missing.mp3")
        assertEquals(missing, post.tagAudio(missing, null, "t", "a", "") { false })
        assertFalse("不应留下 tagged 残件", Files.exists(tmp.root.toPath().resolve("missing.tagged.mp3")))
    }

 // —— 取消语义（参考实现 run_ffmpeg L15-25：轮询取消 → terminate → CancelledError）——

    @Test fun `取消后 destroy 并抛 EngineException 已取消且 tagAudio 不吞`() = runBlocking {
        val src = tmp.newFile("src.m4a"); makeM4a(src)
        val dst = tmp.root.toPath().resolve("out.mp3")
        try {
            post.extractAudio(src.toPath(), dst, "mp3") { true }
            fail("extractAudio 应抛已取消")
        } catch (e: EngineException) {
            assertEquals("已取消", e.message)
        }
        assertFalse("取消后不应有产物", Files.exists(dst))
        try {
            post.tagAudio(src.toPath(), null, "t", "a", "") { true }
            fail("tagAudio 的取消必须穿透（不能被失败不阻断吞掉）")
        } catch (e: EngineException) {
            assertEquals("已取消", e.message)
        }
    }
}
