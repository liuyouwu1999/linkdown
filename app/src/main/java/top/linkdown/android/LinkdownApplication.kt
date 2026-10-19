package top.linkdown.android

import android.app.Application
import android.app.ForegroundServiceStartNotAllowedException
import android.content.Intent
import kotlinx.coroutines.flow.MutableStateFlow
import top.linkdown.android.pipeline.AndroidPipelineEnv
import top.linkdown.android.pipeline.SingleDownloadPipeline
import top.linkdown.android.queue.AndroidNetworkMonitor
import top.linkdown.android.queue.DownloadQueue
import top.linkdown.android.queue.QueuePersistence
import top.linkdown.android.service.DownloadService
import top.linkdown.android.settings.AppSettings
import top.linkdown.android.settings.SettingsRepository
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Application 进程单例（设计架构总览）：DownloadQueue 活在这里，Activity 旋转/重建/回收仅观察
 * 其状态流（技术债已清）。manifest `android:name` 注册。
 * [settingsRepository] 三键设置（音频格式/画质档/并发数）；onCreate 起设置流回写 [settingsCache]，
 * queue/pipeline 的非挂起 provider 现读此快照——并发变更只影响后续派发，画质档 run 起跑时现读，
 * 音频格式在入队时经 [DownloadQueue] 的 audioFormatProvider 固化进项快照。
 */
class LinkdownApplication : Application() {

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

 /** 设置仓库（DataStore settings 表，损坏回默认不崩）。 */
    val settingsRepository: SettingsRepository by lazy {
        SettingsRepository.create(File(filesDir, "datastore/settings.preferences_pb"), appScope)
    }

 /** 设置内存快照：设置流回写（provider 缝为非挂起 lambda，现读此值；默认值=空存储首读一致）。 */
    private val settingsCache = MutableStateFlow(AppSettings())

 /** 下载管道（队列 runner 与 入口枚举缝共用同一 env/画质档装配）。 */
    val pipeline: SingleDownloadPipeline by lazy {
        SingleDownloadPipeline(
            AndroidPipelineEnv(this),
            qualityProvider = { settingsCache.value.quality },
        )
    }

 /** 队列与进程同生命周期；scope 取消即进程死亡（持久化保证恢复）。 */
    val queue: DownloadQueue by lazy {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        DownloadQueue(
            persistence = QueuePersistence.create(File(filesDir, "datastore/queue.preferences_pb"), scope),
            network = AndroidNetworkMonitor(this),
            runner = { item, hooks -> pipeline.runItem(item, hooks) },
            scope = scope,
            newTaskDir = {
 // 与 compat run 的 taskId 同目录约定；附 UUID 短尾防同毫秒碰撞（队列项持久化后复用）
                File(cacheDir, "downloads/${java.lang.Long.toHexString(System.currentTimeMillis())}-${UUID.randomUUID().toString().take(8)}")
                    .apply { mkdirs() }
            },
            startService = {
 // enqueue 唯一 FGS 触发点（接系统服务）；后台启动被拒 → 降级仅前台队列
 // （无前台服务/通知照跑），不崩。UI 观察队列流不受影响。
                try {
                    startForegroundService(Intent(this, DownloadService::class.java))
                } catch (e: ForegroundServiceStartNotAllowedException) {
 // API31+ 后台启动限制：忽略
                }
            },
            maxConcurrencyProvider = { settingsCache.value.maxConcurrency },
            audioFormatProvider = { settingsCache.value.audioFormat },
        )
    }

    override fun onCreate() {
        super.onCreate()
 // 设置流回写内存快照（Application.onCreate 先于任何 Activity，首个 UI 读前必已就绪；
 // DataStore 读期间 provider 暂返回默认值，与空存储首读语义一致）
        appScope.launch {
            settingsRepository.settings.collect { settingsCache.value = it }
        }
    }
}
