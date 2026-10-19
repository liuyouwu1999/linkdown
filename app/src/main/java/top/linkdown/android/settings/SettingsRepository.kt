package top.linkdown.android.settings

import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.io.File

/** 设置项合法取值（功能任务：音频格式 mp3/m4a、画质档 最高/1080P、并发数 2/3/4，设计并发常量接线）。 */
object SettingsValues {
    const val AUDIO_MP3 = "mp3"
    const val AUDIO_M4A = "m4a"
    val AUDIO_FORMATS = listOf(AUDIO_MP3, AUDIO_M4A)

    const val QUALITY_MAX = "max"
    const val QUALITY_1080P = "1080p"
    val QUALITIES = listOf(QUALITY_MAX, QUALITY_1080P)

    val CONCURRENCIES = listOf(2, 3, 4)
}

/**
 * 应用设置（功能任务）：DataStore Preferences 三键（音频格式/画质档/并发数），读侧防御——
 * 非法值（旧版本残留/手改存储）一律回退默认 mp3/max/2，不崩。UI 设置页写，消费方：
 * - maxConcurrency → LinkdownApplication 设置流回写 → DownloadQueue.maxConcurrencyProvider（派发门）
 * - audioFormat → DownloadQueue.enqueue 入队快照固化到 [top.linkdown.android.queue.QueueItem.audioFormatSnapshot]
 * - quality → LinkdownApplication 设置流回写 → SingleDownloadPipeline.qualityProvider → YtdlpEngine VIDEO extraOpts
 */
data class AppSettings(
    val audioFormat: String = SettingsValues.AUDIO_MP3,
    val quality: String = SettingsValues.QUALITY_MAX,
    val maxConcurrency: Int = 2,
    val updateMirror: String = "",
    val autoCheckUpdate: Boolean = true,
)

/** DataStore 键（测试直写非法值用，internal 收敛）。 */
object SettingsRepositoryKeys {
    val AUDIO_FORMAT = stringPreferencesKey("audio_format")
    val QUALITY = stringPreferencesKey("quality")
    val MAX_CONCURRENCY = intPreferencesKey("max_concurrency")
    val BATTERY_PROMPT_DONE = booleanPreferencesKey("battery_prompt_done")
    val EULA_ACCEPTED = booleanPreferencesKey("eula_accepted")
    val SHELF_BOARD_MODE = booleanPreferencesKey("shelf_board_mode")
    val UPDATE_MIRROR = stringPreferencesKey("update_mirror")
    val AUTO_CHECK_UPDATE = booleanPreferencesKey("auto_check_update")
    val LAST_UPDATE_CHECK_AT = longPreferencesKey("last_update_check_at")
}

class SettingsRepository(internal val dataStore: DataStore<Preferences>) {

 /** 设置流：每次读均按合法值域过滤，非法回退默认（默认值=空存储首读同样成立）。 */
    val settings: Flow<AppSettings> = dataStore.data.map { p ->
        AppSettings(
            audioFormat = p[SettingsRepositoryKeys.AUDIO_FORMAT]
                ?.takeIf { it in SettingsValues.AUDIO_FORMATS } ?: SettingsValues.AUDIO_MP3,
            quality = p[SettingsRepositoryKeys.QUALITY]
                ?.takeIf { it in SettingsValues.QUALITIES } ?: SettingsValues.QUALITY_MAX,
            maxConcurrency = p[SettingsRepositoryKeys.MAX_CONCURRENCY]
                ?.takeIf { it in SettingsValues.CONCURRENCIES } ?: 2,
            updateMirror = p[SettingsRepositoryKeys.UPDATE_MIRROR] ?: "",
            autoCheckUpdate = p[SettingsRepositoryKeys.AUTO_CHECK_UPDATE] ?: true,
        )
    }

 /** 三个 setter（UI 设置页联动写；合法值域由读侧兜底，写侧不重复校验）。 */
    suspend fun setAudioFormat(value: String) {
        dataStore.edit { it[SettingsRepositoryKeys.AUDIO_FORMAT] = value }
    }

    suspend fun setQuality(value: String) {
        dataStore.edit { it[SettingsRepositoryKeys.QUALITY] = value }
    }

    suspend fun setMaxConcurrency(value: Int) {
        dataStore.edit { it[SettingsRepositoryKeys.MAX_CONCURRENCY] = value }
    }

 /**
 * 电池引导旗标（ 设计）：一次性直读/直写——不走 [settings] 流，防冷启动默认值快照
 * （流首帧前 AppSettings() 旗标恒 false）把已引导过的用户再弹一次。同意/拒绝均置位，之后不再弹。
 */
    suspend fun isBatteryPromptDone(): Boolean =
        dataStore.data.first()[SettingsRepositoryKeys.BATTERY_PROMPT_DONE] ?: false

    suspend fun setBatteryPromptDone() {
        dataStore.edit { it[SettingsRepositoryKeys.BATTERY_PROMPT_DONE] = true }
    }

 /**
 * 首启协议旗标（开源合规）：一次性直读/直写（同电池引导模式，防默认值快照重复弹）。
 * 必须显式同意才置位（不同意=退出应用），同意后永不再弹；先于电池引导执行。
 */
    suspend fun isEulaAccepted(): Boolean =
        dataStore.data.first()[SettingsRepositoryKeys.EULA_ACCEPTED] ?: false

    suspend fun setEulaAccepted() {
        dataStore.edit { it[SettingsRepositoryKeys.EULA_ACCEPTED] = true }
    }

 /** 货架展示模式（true=书架视图 false=列表视图，默认书架）：一次性直读/直写同电池引导模式。 */
    suspend fun isShelfBoardMode(): Boolean =
        dataStore.data.first()[SettingsRepositoryKeys.SHELF_BOARD_MODE] ?: true

    suspend fun setShelfBoardMode(value: Boolean) {
        dataStore.edit { it[SettingsRepositoryKeys.SHELF_BOARD_MODE] = value }
    }

 /** 更新下载加速镜像（GitHub 前缀代理，""=直连；值域=AppUpdater.MIRRORS 首元素集合）。 */
    suspend fun updateMirror(): String =
        dataStore.data.first()[SettingsRepositoryKeys.UPDATE_MIRROR] ?: ""

    suspend fun setUpdateMirror(value: String) {
        dataStore.edit { it[SettingsRepositoryKeys.UPDATE_MIRROR] = value }
    }

 /** 启动时自动检查更新（默认开；24h 节流由 [lastUpdateCheckAt] 承载）。 */
    suspend fun isAutoCheckUpdate(): Boolean =
        dataStore.data.first()[SettingsRepositoryKeys.AUTO_CHECK_UPDATE] ?: true

    suspend fun setAutoCheckUpdate(value: Boolean) {
        dataStore.edit { it[SettingsRepositoryKeys.AUTO_CHECK_UPDATE] = value }
    }

    suspend fun lastUpdateCheckAt(): Long =
        dataStore.data.first()[SettingsRepositoryKeys.LAST_UPDATE_CHECK_AT] ?: 0L

    suspend fun setLastUpdateCheckAt(value: Long) {
        dataStore.edit { it[SettingsRepositoryKeys.LAST_UPDATE_CHECK_AT] = value }
    }

    companion object {
 /** 建实例（文件归属 scope 与进程同生命周期；损坏文件回空偏好=全默认设置不崩）。 */
        fun create(file: File, scope: CoroutineScope): SettingsRepository = SettingsRepository(
            PreferenceDataStoreFactory.create(
                scope = scope,
                corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
            ) { file },
        )
    }
}
