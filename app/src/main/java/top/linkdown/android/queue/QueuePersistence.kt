package top.linkdown.android.queue

import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import top.linkdown.android.engine.Mode
import top.linkdown.android.pipeline.DownloadModes
import top.linkdown.android.settings.SettingsValues
import java.io.File

/**
 * 队列持久化（设计）：DataStore Preferences，QueueItem 列表 JSON 序列化（kotlinx 树形 API，
 * 沿 probe 同款「无 schema/无编译插件」约定）。分两张表：活跃项（未完成，恢复回 Queued 重走两段式；
 * GROUP 父项原样——派生态由计数推导）+ 最近终态项（按类配额：非子项 ≤[MAX_TERMINAL]、
 * 子项 ≤[MAX_CHILD_TERMINAL]， 带 parentGroupId 子项豁免 50 淘汰防 200 条挤爆归约）；
 * 队列级 paused 标志跨重启保留。
 * 写入时机=入队即写、每次状态迁移/completedModes 变更即写（DataStore.edit 内部串行化=IO 顺序写）。
 */
class QueuePersistence(private val dataStore: DataStore<androidx.datastore.preferences.core.Preferences>) {

    data class Snapshot(val items: List<QueueItem>, val terminal: List<QueueItem>, val paused: Boolean)

    suspend fun save(items: List<QueueItem>, terminal: List<QueueItem>, paused: Boolean) {
 // 终态按类配额在存储层兜底（队列内存侧同款 trim，[trimTerminalToQuota] 单源）
        val terminalTrimmed = trimTerminalToQuota(terminal)
        dataStore.edit { p ->
            p[KEY_ITEMS] = QueueJson.encode(items)
            p[KEY_TERMINAL] = QueueJson.encode(terminalTrimmed)
            p[KEY_PAUSED] = paused
        }
    }

    suspend fun load(): Snapshot {
        val prefs = dataStore.data.first()
        return Snapshot(
            items = QueueJson.decode(prefs[KEY_ITEMS]),
            terminal = QueueJson.decode(prefs[KEY_TERMINAL]),
            paused = prefs[KEY_PAUSED] ?: false,
        )
    }

    companion object {
        private val KEY_ITEMS = stringPreferencesKey("queue_items")
        private val KEY_TERMINAL = stringPreferencesKey("queue_terminal")
        private val KEY_PAUSED = booleanPreferencesKey("paused")

 /** 建实例（文件归属 scope 与队列同生命周期；损坏文件回空快照不崩）。 */
        fun create(file: File, scope: CoroutineScope): QueuePersistence = QueuePersistence(
            PreferenceDataStoreFactory.create(
                scope = scope,
                corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
            ) { file },
        )
    }
}

/**
 * 终态列表按类配额截断（ 设计）：非子项 ≤[MAX_TERMINAL]、带 parentGroupId 子项
 * ≤[MAX_CHILD_TERMINAL]（独立配额豁免 50 淘汰），各类淘汰最旧、整体保序。
 * 队列内存侧与存储层 save 共用本函数（单源，QueuePersistenceTest 直测）。
 */
internal fun trimTerminalToQuota(terminal: List<QueueItem>): List<QueueItem> {
    val indexed = terminal.withIndex().toList()
    val singles = indexed.filter { it.value.parentGroupId == null }.takeLast(MAX_TERMINAL)
    val children = indexed.filter { it.value.parentGroupId != null }.takeLast(MAX_CHILD_TERMINAL)
    return (singles + children).sortedBy { it.index }.map { it.value }
}

/** QueueItem ⇆ JSON（kotlinx.serialization.json 树形 API）。防御式解码：坏元素跳过不崩。 */
private object QueueJson {

    fun encode(items: List<QueueItem>): String =
        JsonArray(items.map { it.toJson() }).toString()

    fun decode(json: String?): List<QueueItem> {
        val arr = json?.let { runCatching { Json.parseToJsonElement(it).jsonArray }.getOrNull() } ?: return emptyList()
        return arr.mapNotNull { el -> runCatching { el.jsonObject.toQueueItem() }.getOrNull() }
    }

    private fun QueueItem.toJson() = buildJsonObject {
        put("id", id)
        put("url", url)
        put("platform", platform)
        put("v", modes.video)
        put("a", modes.audio)
        put("c", modes.cover)
        put("state", state.name)
        parentGroupId?.let { put("parentGroupId", it) }
        if (kind == QueueItemKind.GROUP) { // M3.5 父组：种类+五聚合计数
            put("kind", kind.name)
            put("totalChildren", totalChildren)
            put("completedChildren", completedChildren)
            put("failedChildren", failedChildren)
            put("cancelledChildren", cancelledChildren)
            put("existsChildren", existsChildren)
        }
        if (execRunId.isNotEmpty()) put("execRunId", execRunId) // 执行 runId（重试复活后与 id 解耦）
        put("completedModes", JsonArray(completedModes.map { kotlinx.serialization.json.JsonPrimitive(it.name) }))
        sessdataSnapshot?.let { put("sessdataSnapshot", it) }
        put("audioFormatSnapshot", audioFormatSnapshot)
        put("title", title)
        coverUrl?.let { put("coverUrl", it) }
        probePlatform?.let { put("probePlatform", it) }
        durationSec?.let { put("durationSec", it) }
        put("taskDir", taskDir)
        error?.let { put("error", it) }
        put("createdAt", createdAt)
        put("cancelling", cancelling)
    }

    private fun JsonObject.toQueueItem(): QueueItem? {
        fun s(k: String): String? = this[k]?.jsonPrimitive?.contentOrNull
        fun b(k: String): Boolean? = this[k]?.jsonPrimitive?.booleanOrNull
        fun l(k: String): Long? = this[k]?.jsonPrimitive?.longOrNull
        fun i(k: String): Int? = this[k]?.jsonPrimitive?.intOrNull
        fun d(k: String): Double? = this[k]?.jsonPrimitive?.doubleOrNull
        val id = s("id") ?: return null
        return QueueItem(
            id = id,
            url = s("url") ?: "",
            platform = s("platform") ?: "YTDLP",
            modes = DownloadModes(video = b("v") == true, audio = b("a") == true, cover = b("c") == true),
            state = s("state")?.let { runCatching { QueueState.valueOf(it) }.getOrNull() } ?: QueueState.Queued,
            parentGroupId = s("parentGroupId"),
 // 防御式回退：旧快照无 kind 字段回 SINGLE、计数回 0
            kind = s("kind")?.let { runCatching { QueueItemKind.valueOf(it) }.getOrNull() } ?: QueueItemKind.SINGLE,
            totalChildren = i("totalChildren") ?: 0,
            completedChildren = i("completedChildren") ?: 0,
            failedChildren = i("failedChildren") ?: 0,
            cancelledChildren = i("cancelledChildren") ?: 0,
            existsChildren = i("existsChildren") ?: 0,
            execRunId = s("execRunId") ?: "",
            completedModes = (this["completedModes"] as? JsonArray)
                ?.mapNotNull { m -> m.jsonPrimitive.contentOrNull?.let { runCatching { Mode.valueOf(it) }.getOrNull() } }
                ?.toSet()
                ?: emptySet(),
            sessdataSnapshot = s("sessdataSnapshot"),
 // 防御式回退：缺失/非法快照回 mp3（与设置读侧同款值域过滤）
            audioFormatSnapshot = s("audioFormatSnapshot")
                ?.takeIf { it in SettingsValues.AUDIO_FORMATS } ?: SettingsValues.AUDIO_MP3,
            title = s("title") ?: "",
            coverUrl = s("coverUrl"),
            probePlatform = s("probePlatform"),
            durationSec = d("durationSec"),
            taskDir = s("taskDir") ?: "",
            error = s("error"),
            createdAt = l("createdAt") ?: 0L,
            cancelling = b("cancelling") == true,
        )
    }
}
