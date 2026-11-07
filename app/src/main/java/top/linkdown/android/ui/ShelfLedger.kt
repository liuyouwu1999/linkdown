package top.linkdown.android.ui

import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.Json
import java.io.File
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import top.linkdown.android.pipeline.FileRef
import top.linkdown.android.pipeline.LedgerEntry
import top.linkdown.android.pipeline.LedgerMerge

/** 文件名自然序比较（数值段按数值，前导零等价）：标题-2 < 标题-10。 */
fun compareNatural(a: String, b: String): Int {
    var i = 0
    var j = 0
    while (i < a.length && j < b.length) {
        val ca = a[i]
        val cb = b[j]
        if (ca.isDigit() && cb.isDigit()) {
            var ei = i
            while (ei < a.length && a[ei].isDigit()) ei++
            var ej = j
            while (ej < b.length && b[ej].isDigit()) ej++
            val na = a.substring(i, ei).trimStart('0').ifEmpty { "0" }
            val nb = b.substring(j, ej).trimStart('0').ifEmpty { "0" }
            val cmp = na.length.compareTo(nb.length).takeIf { it != 0 } ?: na.compareTo(nb)
            if (cmp != 0) return cmp
            i = ei
            j = ej
        } else {
            if (ca != cb) return ca.compareTo(cb)
            i++
            j++
        }
    }
    return (a.length - i).compareTo(b.length - j)
}

/**
 * 台账 JSON 编解码 + 增量合并（纯函数，JVM 直测；DataStore 壳在同类文件 Task 2 补）。
 * 防御式解码：坏元素/坏 JSON 跳过不崩（与 QueueJson 同风格）。
 */
object ShelfLedgerCodec {

    fun encode(entries: List<LedgerEntry>): String = JsonArray(entries.map { e ->
        buildJsonObject {
            put("workKey", e.workKey)
            put("platform", e.platform)
            put("title", e.title)
            put("updatedAt", e.updatedAt)
            put("files", JsonArray(e.files.map { f ->
                buildJsonObject {
                    put("path", f.path)
                    put("name", f.name)
                }
            }))
        }
    }).toString()

    fun decode(json: String?): List<LedgerEntry> {
        val arr = json?.let { runCatching { Json.parseToJsonElement(it).jsonArray }.getOrNull() } ?: return emptyList()
        return arr.mapNotNull { el ->
            runCatching {
                val o = el.jsonObject
                val files = o["files"]?.jsonArray?.mapNotNull { f ->
                    runCatching {
                        val fo = f.jsonObject
                        FileRef(fo["path"]!!.jsonPrimitive.content, fo["name"]!!.jsonPrimitive.content)
                    }.getOrNull()
                } ?: emptyList()
                LedgerEntry(
                    workKey = o["workKey"]!!.jsonPrimitive.content,
                    platform = o["platform"]?.jsonPrimitive?.content ?: "",
                    title = o["title"]?.jsonPrimitive?.content ?: "",
                    files = files,
                    updatedAt = o["updatedAt"]?.jsonPrimitive?.longOrNull ?: 0L,
                )
            }.getOrNull()
        }
    }

    /** 增量合并：workKey 已存在 → 按 (path,name) 去重追加 files、覆盖 title/platform/updatedAt；不存在 → 追加。files 落盘前按自然序排。 */
    fun mergeEntries(existing: List<LedgerEntry>, input: LedgerMerge, now: Long): List<LedgerEntry> {
        val natural = java.util.Comparator<FileRef> { x, y -> compareNatural(x.name, y.name) }
        val incoming = input.files.sortedWith(natural)
        val idx = existing.indexOfFirst { it.workKey == input.workKey }
        if (idx < 0) {
            return existing + LedgerEntry(input.workKey, input.platform, input.title, incoming, now)
        }
        val old = existing[idx]
        val merged = (old.files + incoming).distinctBy { it.path to it.name }.sortedWith(natural)
        return existing.toMutableList().also {
            it[idx] = old.copy(title = input.title, platform = input.platform, files = merged, updatedAt = now)
        }
    }
}

/**
 * 书架台账壳（QueuePersistence 同款形态）：单键 shelf_groups 存 JSON 数组。
 * 每次 merge/removeKeys 都在单个 dataStore.edit 内读-改-写（原子，防 pipeline 与对账清理并发丢更新）。
 */
class ShelfLedger(private val dataStore: DataStore<Preferences>) {

    suspend fun load(): List<LedgerEntry> =
        dataStore.data.first()[KEY]?.let { ShelfLedgerCodec.decode(it) } ?: emptyList()

    suspend fun merge(input: LedgerMerge) {
        dataStore.edit { p ->
            val now = System.currentTimeMillis()
            p[KEY] = ShelfLedgerCodec.encode(
                ShelfLedgerCodec.mergeEntries(ShelfLedgerCodec.decode(p[KEY]), input, now),
            )
        }
    }

    suspend fun removeKeys(keys: Set<String>) {
        if (keys.isEmpty()) return
        dataStore.edit { p ->
            val current = ShelfLedgerCodec.decode(p[KEY])
            if (current.isNotEmpty()) p[KEY] = ShelfLedgerCodec.encode(current.filterNot { it.workKey in keys })
        }
    }

    companion object {
        private val KEY = stringPreferencesKey("shelf_groups")

        /** 建实例（同文件绝不建第二个活跃实例；损坏文件回空偏好不崩）。 */
        fun create(file: File, scope: CoroutineScope): ShelfLedger = ShelfLedger(
            PreferenceDataStoreFactory.create(
                scope = scope,
                corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
            ) { file },
        )
    }
}
