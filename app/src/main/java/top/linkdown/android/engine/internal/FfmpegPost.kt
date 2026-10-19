package top.linkdown.android.engine.internal

import kotlinx.coroutines.delay
import top.linkdown.android.engine.CANCELLED_MESSAGE
import top.linkdown.android.engine.EngineCancelledException
import top.linkdown.android.engine.EngineException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/**
 * ffmpeg CLI 后处理（设计；参考管线 postprocess.py 逐字平移：run_ffmpeg L8-38、
 * extract_audio L51-57、tag_audio L60-76）。ProcessBuilder 调 自建 ffmpeg CLI，零新依赖。
 * - extractAudio 失败=抛 EngineException（阻断语义）；tagAudio 失败=记日志返回原文件（不阻断，设计明文）
 * - 取消=轮询 isCancelled（约 300ms）→ destroy()（SIGTERM，≙参考实现 terminate()）等退出后抛
 * EngineCancelledException（[CANCELLED_MESSAGE] 文案）——管道按类型甄别进 Cancelled（技术债②清，
 * 不再按 message 串匹配），不标失败
 * - 退出码非 0 且未取消 → EngineException("ffmpeg 失败: " + stderr 尾部 ≤300 字符)
 */
class FfmpegPost(private val ffmpegPath: String) {

 /** 参考实现 run_ffmpeg：-hide_banner -loglevel error -y 前缀；stdout=DEVNULL、stderr 等退出后收尾读。 */
    private suspend fun runFfmpeg(args: List<String>, isCancelled: () -> Boolean) {
        val proc = ProcessBuilder(listOf(ffmpegPath, "-hide_banner", "-loglevel", "error", "-y") + args)
            .start()
 // 参考实现 stdout=DEVNULL；Redirect.DISCARD 需 API 34（minSdk 29 不满足），守护线程排空防管道撑满
        Thread { proc.inputStream.readBytes() }.apply { isDaemon = true }.start()
        try {
            while (proc.isAlive) {
                if (isCancelled()) {
                    proc.destroy()  // ≙参考实现 proc.terminate()
                    proc.waitFor()  // ≙参考实现 await wait_task
                    throw EngineCancelledException(CANCELLED_MESSAGE)
                }
                delay(CANCEL_POLL_MS)  // ≙参考实现 CANCEL_CHECK_INTERVAL=0.3
            }
            if (isCancelled()) throw EngineCancelledException(CANCELLED_MESSAGE)  // 出口竞态补查（参考实现 L24-25）
            if (proc.exitValue() != 0) {
                val err = proc.errorStream.readBytes().toString(Charsets.UTF_8)
                throw EngineException("ffmpeg 失败: " + err.takeLast(MAX_ERR_CHARS))
            }
        } finally {
            if (proc.isAlive) proc.destroy()
        }
    }

 /**
 * 参考实现 extract_audio：两分支均带 -vn；mp3=libmp3lame -q:a 0；m4a=aac -b:a 192k；
 * 非 mp3/m4a 值回退 mp3。失败抛 EngineException（阻断）。
 */
    suspend fun extractAudio(src: Path, dst: Path, format: String, isCancelled: () -> Boolean): Path {
        val args = if (format == "m4a") {
            listOf("-i", src.toString(), "-vn", "-c:a", "aac", "-b:a", "192k", dst.toString())
        } else {
            listOf("-i", src.toString(), "-vn", "-c:a", "libmp3lame", "-q:a", "0", dst.toString())
        }
        runFfmpeg(args, isCancelled)
        return dst
    }

 /**
 * 参考实现 tag_audio。有封面=-i 音频 -i 封面 -map 0:a -map 1:v -c:v copy -disposition:v attached_pic；
 * 无封面=-c copy；共用 -id3v2_version 3 + title/artist/album 三条 metadata（album 空串照参考实现 ；
 * mp3 muxer 自动出 APIC、m4a 出 covr）。临时输出名必须带真实后缀（.tagged.mp3 而非 .mp3.tagged）——
 * 真 ffmpeg 靠扩展名选封装器（参考实现 L70-71 之坑）。
 * 失败=记日志返回原文件（不阻断）；取消按类型穿透（EngineCancelledException 不能被「失败不阻断」吞掉）。
 */
    suspend fun tagAudio(audio: Path, cover: Path?, title: String, artist: String, album: String,
                         isCancelled: () -> Boolean): Path {
        try {
            val args = mutableListOf("-i", audio.toString())
            if (cover != null && Files.exists(cover)) {
                args += listOf("-i", cover.toString(), "-map", "0:a", "-map", "1:v",
                    "-c:v", "copy", "-disposition:v", "attached_pic")
            } else {
                args += listOf("-c", "copy")
            }
            args += listOf("-id3v2_version", "3",
                "-metadata", "title=$title", "-metadata", "artist=$artist", "-metadata", "album=$album")
            val tmpOut = taggedSibling(audio)
            args += listOf(tmpOut.toString())  // 参考实现 L72：临时输出路径必须是最后一个参数
            runFfmpeg(args, isCancelled)
            Files.deleteIfExists(audio)
            Files.move(tmpOut, audio, StandardCopyOption.REPLACE_EXISTING)
            return audio
        } catch (e: EngineException) {
            if (e is EngineCancelledException) throw e  // 取消必须穿透：类型甄别（原 message 串比对废弃）
            println("FfmpegPost: 标签嵌入失败，保留原文件 $audio：${e.message}")  // 记日志不阻断（设计）
            return audio
        }
    }

 /** ≙参考实现 file.with_suffix(".tagged" + suffix)：a.mp3→a.tagged.mp3；无后缀 foo→foo.tagged。 */
    private fun taggedSibling(audio: Path): Path {
        val name = audio.fileName.toString()
        val dot = name.lastIndexOf('.')
        return if (dot > 0) audio.resolveSibling(name.substring(0, dot) + ".tagged" + name.substring(dot))
        else audio.resolveSibling("$name.tagged")
    }

    companion object {
        private const val CANCEL_POLL_MS = 300L  // 参考实现 CANCEL_CHECK_INTERVAL=0.3s
        private const val MAX_ERR_CHARS = 300    // stderr 尾部截断长度
 // CANCELLED_MESSAGE 已迁 top.linkdown.android.engine.CANCELLED_MESSAGE（ 类型甄别，文案不参与甄别）
    }
}
