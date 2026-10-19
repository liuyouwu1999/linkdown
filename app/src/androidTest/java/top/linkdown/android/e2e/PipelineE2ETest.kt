package top.linkdown.android.e2e

import android.content.Context
import android.provider.MediaStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import top.linkdown.android.engine.QishuiEngine
import top.linkdown.android.engine.YtdlpEngine
import top.linkdown.android.pipeline.DownloadModes
import top.linkdown.android.pipeline.PipelineState
import top.linkdown.android.pipeline.SingleDownloadPipeline

// 注：androidTest 源集方法名禁用空格（D8 dexing 限制），设计文档中的反引号名改下划线
@RunWith(AndroidJUnit4::class)
class PipelineE2ETest {
    private val ctx: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val device: UiDevice = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())

    private fun cleanupMedia() {
        val doomed = mutableListOf<android.net.Uri>()
        ctx.contentResolver.query(MediaStore.Downloads.EXTERNAL_CONTENT_URI, null, null, null, null)?.use { c ->
            while (c.moveToNext()) {
                if (c.getString(c.getColumnIndexOrThrow(MediaStore.MediaColumns.RELATIVE_PATH)).startsWith("Download/链接下载/"))
                    doomed.add(android.content.ContentUris.withAppendedId(MediaStore.Downloads.EXTERNAL_CONTENT_URI, c.getLong(c.getColumnIndexOrThrow(MediaStore.MediaColumns._ID))))
            }
        }
        doomed.forEach { ctx.contentResolver.delete(it, null, null) }
    }

    private fun runAndWait(url: String, modes: DownloadModes): PipelineState {
        YtdlpEngine.initOnce(ctx)
        val p = SingleDownloadPipeline(ctx)
        runBlocking { p.run(url, modes, null) }
        return p.state.value
    }

    @Test fun netease_audio_end_to_end() {
        cleanupMedia()
        val st = runAndWait("https://music.163.com/song?id=436514312", DownloadModes(video = false, audio = true, cover = false))
        assertTrue("got $st", st is PipelineState.Done)
        val uri = (st as PipelineState.Done).results.first().second
        ctx.contentResolver.query(uri, null, null, null, null)!!.use { c ->
            c.moveToFirst()
            assertTrue(c.getString(c.getColumnIndexOrThrow(MediaStore.MediaColumns.RELATIVE_PATH)).startsWith("Download/链接下载/网易云/"))
        }
        ctx.contentResolver.delete(uri, null, null)
    }

    @Test fun bilibili_video_dash_end_to_end() {
        cleanupMedia()
        val st = runAndWait("https://www.bilibili.com/video/BV1GJ411x7h7", DownloadModes(video = true, audio = false, cover = false))
        assertTrue("got $st", st is PipelineState.Done)
        val uri = (st as PipelineState.Done).results.first().second
        ctx.contentResolver.query(uri, null, null, null, null)!!.use { c ->
            c.moveToFirst()
            assertTrue(c.getString(c.getColumnIndexOrThrow(MediaStore.MediaColumns.RELATIVE_PATH)).startsWith("Download/链接下载/哔哩哔哩/"))
        }
        ctx.contentResolver.delete(uri, null, null)
    }

    @Test fun bilibili_cover_keeps_source_format() {
        cleanupMedia()
        val st = runAndWait("https://www.bilibili.com/video/BV1GJ411x7h7", DownloadModes(video = false, audio = false, cover = true))
        assertTrue("got $st", st is PipelineState.Done)
        val name = (st as PipelineState.Done).results.let { results ->
            ctx.contentResolver.query(results.first().second, null, null, null, null)!!.use { c ->
                c.moveToFirst(); c.getString(c.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME))
            }
        }
        assertTrue("cover should keep source format, got: $name", listOf("jpg", "webp", "png").any { name.endsWith(".$it") })
        cleanupMedia()
    }

    @Test fun qishui_video_audio_folds_to_single_download() {
 // 功能任务 去重守门（设计）：视频+音频两勾 → 整体=一次音频下载（音频+伴生封面=2 条入库；
 // 回归为直通两模式则是 2 次下载=4 条）。不调 YtdlpEngine.initOnce：误路由 yt-dlp 会当场炸红。
        cleanupMedia()
        val lines = java.util.concurrent.CopyOnWriteArrayList<String>()
        val runningLabels = java.util.concurrent.CopyOnWriteArrayList<String>()
        val p = SingleDownloadPipeline(ctx)
        runBlocking {
 // 订阅门：onSubscription 在 collect 订阅建立时（先于任何发射）触发——
 // logs 是 replay=0 SharedFlow，若在首条发射里置门会死锁（发射方是下面的 run， await 等日志、日志等 run）
            val logSubscribed = CompletableDeferred<Unit>()
            val stateSubscribed = CompletableDeferred<Unit>()
            val logJob = launch(Dispatchers.IO) {
                p.logs.onSubscription { logSubscribed.complete(Unit) }.collect { lines.add(it) }
            }
            val stateJob = launch(Dispatchers.IO) {
                p.state.onSubscription { stateSubscribed.complete(Unit) }
                    .collect { if (it is PipelineState.Running) runningLabels.add(it.modeLabel) }
            }
            logSubscribed.await(); stateSubscribed.await() // 两个订阅都建立后才放行 run，Running→Done 早切换不丢
            p.run("https://qishui.douyin.com/s/iXGLcaeq/", DownloadModes(video = true, audio = true, cover = false), null)
            logJob.cancel(); stateJob.cancel()
            val st = p.state.value
            assertTrue("got $st", st is PipelineState.Done)
            val results = (st as PipelineState.Done).results
            assertEquals("results=${results.map { it.first }}", 2, results.size)
            assertTrue("labels=${results.map { it.first }}", results.all { it.first == "音频" })
// runningLabels/VIDEO_AS_AUDIO_LOG 断言在 StateFlow 合流下可能闪跳（Running→Done 秒级完成），终态断言已充分覆盖折叠语义
 // 入库目录=汽水（PlatformDirs 路由 qishui.douyin.com → 汽水）
            ctx.contentResolver.query(results[0].second, null, null, null, null)!!.use { c ->
                c.moveToFirst()
                assertTrue(c.getString(c.getColumnIndexOrThrow(MediaStore.MediaColumns.RELATIVE_PATH))
                    .startsWith("Download/链接下载/汽水/"))
            }
            results.forEach { ctx.contentResolver.delete(it.second, null, null) }
        }
    }

    @Test fun offline_fails_with_human_message_and_keeps_temp_dir() {
        cleanupMedia()
        device.executeShellCommand("svc wifi disable")
        device.executeShellCommand("svc data disable")
        try {
            val url = "https://music.163.com/song?id=436514312"
            YtdlpEngine.initOnce(ctx)
            val p = SingleDownloadPipeline(ctx)
            runBlocking { p.run(url, DownloadModes(video = false, audio = true, cover = false), null) }
            val st = p.state.value
            assertTrue("got $st", st is PipelineState.Failed)
            assertEquals("网络不给力：请检查网络后重试", (st as PipelineState.Failed).humanMessage)
 // 若上断言实际得到「下载失败：getaddrinfo failed」等：把该真实 DNS 错误签名补进
 // ErrorMapper 网络类（ErrorMapperTest 同步加用例）再重跑——错误映射表对真实环境的校准回路
            val dirs = ctx.cacheDir.resolve("downloads").listFiles().orEmpty()
 // 失败发生在元数据抓取阶段（DNS 不通），taskDir 为空目录属预期；断言的是「保留」本身
            assertTrue("temp dir should be kept on failure", dirs.any { it.isDirectory })
        } finally {
            device.executeShellCommand("svc wifi enable")
            device.executeShellCommand("svc data enable")
            Thread.sleep(3000) // 等网络恢复，免污染后续用例
        }
    }
}
