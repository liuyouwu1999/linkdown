package top.linkdown.android.engine.internal

import android.media.ExifInterface
import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.Build
import android.util.Log
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.CancellationException

/**
 * 实况图合成器（设备自适应动态照片）—— 平移参考实现 NEORUAA/XHS_Downloader_Android
 * LivePhotoCreator，按本项目场景裁剪：
 * - 不做 Bitmap 解码重编码/方向归一（抖音源图已是 JPEG，原字节直通零画质损失；SOI 校验
 *   在 MotionPhotoContainer.write 内，非 JPEG 抛 IOException → 调用方回退两文件交付）；
 * - 不注入 Context/scanFile（入库走 MediaStoreIngest，MediaStore 按 .jpg 扩展名登记 image/jpeg）；
 * - 不带 VIVO_LEGACY 双文件模式（AUTO 设备映射不会产生该值，write() 亦拒绝）。
 * 失败一律返回 false（CancellationException 穿透），调用方回退「静图+实况视频」两文件交付。
 */
object LivePhotoCreator {
    private const val TAG = "LivePhotoCreator"

    // 最近一次合成失败原因（人类可读；CancellationException 静默重抛路径也记——回退诊断/用户日志用）
    @Volatile
    internal var lastFailure: String? = null; private set

    fun resolveFormat(): LivePhotoFormat = LivePhotoFormat.AUTO.resolve(Build.MANUFACTURER, Build.BRAND)

    /** image=实况静帧 JPEG、video=实况流 mp4、output=合成产物（须与两者不同文件）。 */
    fun createLivePhoto(
        imageFile: File,
        videoFile: File,
        outputFile: File,
        checkActive: () -> Unit = ::checkThread,
    ): Boolean {
        require(outputFile.canonicalFile !in listOf(imageFile.canonicalFile, videoFile.canonicalFile))
        var jpeg: File? = null
        var staged: File? = null
        try {
            checkActive()
            val resolved = resolveFormat()
            val mime = MotionPhotoContainer.videoMime(videoFile, checkActive)
            if (resolved in setOf(LivePhotoFormat.OPLUS, LivePhotoFormat.HUAWEI) && mime != "video/mp4") {
                throw IOException("Selected live photo format requires MP4")
            }
            val countFrames = resolved == LivePhotoFormat.HUAWEI
            val sourceTracks = inspectTracks(videoFile, 0, videoFile.length(), countFrames, checkActive)
            // EXIF 厂商标记写进临时副本，原下载文件保持不动（参考实现 同语义：转换副本上写 EXIF）
            jpeg = File.createTempFile("live_cover_", ".jpg", outputFile.absoluteFile.parentFile)
            FileInputStream(imageFile).use { input -> FileOutputStream(jpeg).use { input.copyTo(it) } }
            checkActive()
            addVendorExif(jpeg, resolved)
            staged = File.createTempFile("live_output_", ".jpg", outputFile.absoluteFile.parentFile)
            val layout = MotionPhotoContainer.write(jpeg, videoFile, staged, resolved, mime, sourceTracks.frameCount, checkActive)
            val embeddedTracks = inspectTracks(staged, layout.videoOffset, layout.videoLength, countFrames, checkActive)
            if (sourceTracks != embeddedTracks) throw IOException("Embedded media tracks changed")
            checkActive()
            if (!staged.renameTo(outputFile)) throw IOException("Unable to publish live photo")
            Log.d(TAG, "Created $resolved motion photo with ${sourceTracks.mimeTypes}")
            return true
        } catch (cancelled: CancellationException) {
            lastFailure = "cancelled(interrupted=${Thread.currentThread().isInterrupted})"
            Log.w(TAG, "Live photo creation cancelled", cancelled)
            throw cancelled
        } catch (error: Exception) {
            lastFailure = "${error.javaClass.simpleName}: ${error.message}"
            Log.w(TAG, "Unable to create live photo", error)
            return false
        } finally {
            jpeg?.delete()
            staged?.delete()
        }
    }

    private data class Tracks(val mimeTypes: List<String>, val durationMs: Long, val frameCount: Int)

    private fun inspectTracks(
        file: File, offset: Long, length: Long, countFrames: Boolean = false, checkActive: () -> Unit = ::checkThread,
    ): Tracks {
        val extractor = MediaExtractor()
        try {
            FileInputStream(file).use { extractor.setDataSource(it.fd, offset, length) }
            val types = mutableListOf<String>()
            var durationUs = 0L
            var hasVideo = false
            var frameCount = 0
            for (index in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(index)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
                if (!mime.startsWith("video/") && !mime.startsWith("audio/")) continue
                types += mime
                if (mime.startsWith("video/")) {
                    hasVideo = true
                    if (format.getInteger(MediaFormat.KEY_WIDTH) <= 0 || format.getInteger(MediaFormat.KEY_HEIGHT) <= 0) {
                        throw IOException("Invalid video dimensions")
                    }
                    if (format.containsKey(MediaFormat.KEY_DURATION)) durationUs = format.getLong(MediaFormat.KEY_DURATION)
                }
                extractor.selectTrack(index)
                extractor.seekTo(0, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
                // AAC priming samples can have negative presentation times; only a missing track is EOS.
                if (extractor.sampleTrackIndex != index) throw IOException("Empty media track")
                if (countFrames && mime.startsWith("video/")) {
                    do {
                        checkActive()
                        if (frameCount == Int.MAX_VALUE) throw IOException("Too many video frames")
                        frameCount++
                    } while (extractor.advance())
                }
                extractor.unselectTrack(index)
            }
            if (!hasVideo) throw IOException("No readable video track")
            return Tracks(types, durationUs / 1000, frameCount)
        } finally {
            extractor.release()
        }
    }

    private fun addVendorExif(jpeg: File, format: LivePhotoFormat) {
        val marker = when (format) {
            LivePhotoFormat.OPLUS -> "Oplus_8388608"
            LivePhotoFormat.VIVO -> vivoUserComment()
            else -> return
        }
        ExifInterface(jpeg.absolutePath).apply {
            setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL.toString())
            setAttribute(ExifInterface.TAG_USER_COMMENT, marker)
            saveAttributes()
        }
    }

    internal fun vivoUserComment(date: String = SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.ROOT).format(Date())): String {
        // Observed X300 capture-state structure. Neutral fields are not original capture metadata.
        return "filter: 2237; fileterIntensity: 0.000000; filterMask: 0; captureOrientation: 90;\n" +
            "niceRunStatus: 1002; hdrForward: 7; shaking: 0.000000; highlight: 1; motionR: 0; algolist: 0;\n" +
            "multi-frame: 1;\nbrp_mask: 0;\nbrp_del_th: 0.0000,0.0000;\nbrp_del_sen: 0.0000,0.0000;\n" +
            "delta:1;\nbokeh:1;\nispap:1;\npapproctime: $date;\n" +
            "module: photo;hw-remosaic: false;touch: (-1.0, -1.0);sceneMode: 13107200;cct_value: 0;" +
            "AI_Scene: (-1, -1);aec_lux: 130.098;aec_lux_index: 0;albedo:  ;confidence:  ;motionLevel: -1;" +
            "weatherinfo: weather: cloudy,icon:1,weatherInfo:100;temperature: 37;zeissColor: bright;"
    }

    private fun checkThread() {
        if (Thread.currentThread().isInterrupted) throw CancellationException("Live photo creation cancelled")
    }
}
