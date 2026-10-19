package top.linkdown.android.engine

import android.content.Context
import java.io.File

object FfmpegLocator {
 /** yt-dlp 文件形式 --ffmpeg-location；ffprobe 由 yt-dlp 按 ffmpeg→ffprobe 字符串替换派生。 */
    fun ffmpegPath(context: Context): String =
        File(context.applicationInfo.nativeLibraryDir, "libffmpegexec.so").absolutePath
}
