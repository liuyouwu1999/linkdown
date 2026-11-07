package top.linkdown.android.ui

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.MediaStore
import top.linkdown.android.pipeline.LedgerEntry

/** 货架条目：MediaStore.Downloads 中 Download/链接下载/ 子树内的入库产物 */
data class ShelfItem(
    val uri: Uri,
    val name: String,
    val relPath: String,
    val platform: String,
    val sizeBytes: Long,
    val kind: ShelfKind,
    val dateAdded: Long,
)

enum class ShelfKind { VIDEO, AUDIO, IMAGE, OTHER }

/** 已有 PlatformDirs（入库时定 RELATIVE_PATH），货架按同一路径反推平台 */
object ShelfStore {
    const val ROOT = "Download/链接下载"

    fun kindOf(name: String): ShelfKind {
        val ext = name.substringAfterLast('.', "").lowercase()
        return when (ext) {
            "mp4", "mkv", "webm", "mov", "avi", "flv" -> ShelfKind.VIDEO
            "mp3", "m4a", "opus", "ogg", "wav", "flac", "aac" -> ShelfKind.AUDIO
            "jpg", "jpeg", "png", "webp", "gif" -> ShelfKind.IMAGE
            else -> ShelfKind.OTHER
        }
    }

    fun platformFromRelPath(relPath: String): String =
        relPath.removePrefix("$ROOT/").substringBefore('/').ifEmpty { "其他" }

    fun mimeOf(kind: ShelfKind): String = when (kind) {
        ShelfKind.VIDEO -> "video/*"
        ShelfKind.AUDIO -> "audio/*"
        ShelfKind.IMAGE -> "image/*"
        ShelfKind.OTHER -> "*/*"
    }

 /** owner 过滤 + 日期倒序（spec：MediaStore 查询 Download/链接下载/ 子树，owner 过滤） */
    fun query(context: Context): List<ShelfItem> {
        val proj = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.RELATIVE_PATH,
            MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.DATE_ADDED,
        )
        val sel = "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ? AND " +
            "(${MediaStore.MediaColumns.OWNER_PACKAGE_NAME} IS NULL OR ${MediaStore.MediaColumns.OWNER_PACKAGE_NAME} = ?)"
        val args = arrayOf("$ROOT/%", context.packageName)
        val out = mutableListOf<ShelfItem>()
        context.contentResolver.query(MediaStore.Downloads.EXTERNAL_CONTENT_URI, proj, sel, args, "${MediaStore.MediaColumns.DATE_ADDED} DESC")
            ?.use { cur ->
                while (cur.moveToNext()) {
                    val id = cur.getLong(0)
                    val name = cur.getString(1) ?: continue
                    val rel = cur.getString(2) ?: ""
                    val size = cur.getLong(3)
                    val date = cur.getLong(4)
                    out += ShelfItem(
                        uri = Uri.withAppendedPath(MediaStore.Downloads.EXTERNAL_CONTENT_URI, id.toString()),
                        name = name,
                        relPath = rel,
                        platform = platformFromRelPath(rel),
                        sizeBytes = size,
                        kind = kindOf(name),
                        dateAdded = date,
                    )
                }
            }
        return out
    }

 /** 删除：确认后 contentResolver.delete（设计），成功与否返回给 UI 日志 */
    fun delete(context: Context, item: ShelfItem): Boolean =
        context.contentResolver.delete(item.uri, null, null) > 0

 /** 播放：ACTION_VIEW + READ 授权（设计点击行=ACTION_VIEW 播放） */
    fun play(context: Context, item: ShelfItem) {
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(item.uri, mimeOf(item.kind))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching { context.startActivity(intent) }
    }

    /** 合书投影键（Uri 无关，JVM 直测）：id 用于回射 ShelfItem。 */
    data class ShelfKey(
        val id: String,
        val relPath: String,
        val name: String,
        val kind: ShelfKind,
        val dateAdded: Long,
    )

    /** 合书结果组：Single=现单文件行为；Bound=同作品成书。 */
    sealed interface Group {
        val id: String
        val dateAdded: Long
    }

    data class SingleGroup(val key: ShelfKey) : Group {
        override val id get() = key.id
        override val dateAdded get() = key.dateAdded
    }

    data class BoundGroup(
        val workKey: String,
        val title: String,
        val platform: String,
        val keys: List<ShelfKey>,
    ) : Group {
        override val id get() = workKey
        override val dateAdded get() = keys.maxOf { it.dateAdded }
    }

    data class GroupingResult(val groups: List<Group>, val staleKeys: Set<String>)

    fun toKeys(items: List<ShelfItem>): List<ShelfKey> =
        items.map { ShelfKey(it.uri.toString(), it.relPath, it.name, it.kind, it.dateAdded) }

    /**
     * 合书对账（纯函数，JVM 直测；设计 §6）：
     * - matched ≥2 → BoundGroup（页序=台账序，台账已按自然序写入），matched 标记认领
     * - matched ==1 → 条目保留（补下可并组），**不认领**——matched==1 不产生组，无重复渲染；
     *   该文件落入下方未认领分支按 SingleGroup 展示（设计 §11「剩 1 页按 Single」）
     * - matched ==0 → workKey 入 staleKeys（对账清理）
     * - 未认领键 → 各自 SingleGroup（旧文件行为不变）
     * - 输出序：dateAdded DESC，同值稳定保序（TIME 排序恒等语义依赖此序）
     */
    fun groupShelf(keys: List<ShelfKey>, ledger: List<LedgerEntry>): GroupingResult {
        val index = keys.associateBy { it.relPath to it.name }
        val claimed = mutableSetOf<String>()
        val stale = mutableSetOf<String>()
        val bounds = mutableListOf<BoundGroup>()
        for (entry in ledger) {
            val matched = entry.files.mapNotNull { index[it.path to it.name] }.filter { it.id !in claimed }
            when {
                matched.size >= 2 -> {
                    bounds += BoundGroup(entry.workKey, entry.title, entry.platform, matched)
                    claimed += matched.map { it.id }
                }
                matched.size == 1 -> {} // 条目保留、不认领：文件经未认领分支按 Single 展示
                else -> stale += entry.workKey
            }
        }
        val singles = keys.filter { it.id !in claimed }.map { SingleGroup(it) }
        return GroupingResult((singles + bounds).sortedByDescending { it.dateAdded }, stale)
    }

    /** 合书装配（android 层，Uri 回射）：GroupingResult → 展示条目。 */
    fun assemble(items: List<ShelfItem>, result: GroupingResult): List<ShelfEntry> {
        val byId = items.associateBy { it.uri.toString() }
        return result.groups.map { g ->
            when (g) {
                is SingleGroup -> SingleEntry(byId.getValue(g.key.id))
                is BoundGroup -> BoundEntry(g.workKey, g.title, g.platform, g.keys.map { byId.getValue(it.id) })
            }
        }
    }

    /**
     * 条目封面（Bound 特例：无视频取首页 IMAGE 直接解码——loadCover 对 IMAGE 只查伴生封面文件）。
     * [all]=全货架文件列表：SingleEntry 走 loadCover 时伴生封面查找域与现状一致（旧文件封面不劣化）。
     */
    fun loadEntryCover(context: Context, entry: ShelfEntry, all: List<ShelfItem>): Bitmap? {
        val coverItem = when (entry) {
            is SingleEntry -> entry.item
            is BoundEntry -> entry.pages.firstOrNull { it.kind == ShelfKind.VIDEO }
                ?: entry.pages.firstOrNull { it.kind == ShelfKind.IMAGE }
                ?: entry.pages.first()
        }
        if (entry is BoundEntry && coverItem.kind == ShelfKind.IMAGE) {
            return runCatching {
                context.contentResolver.openInputStream(coverItem.uri)?.use { BitmapFactory.decodeStream(it) }
            }.getOrNull()
        }
        return loadCover(context, coverItem, all)
    }
}

/**
 * 书架模式：伴生封面匹配（纯函数，JVM 直测）。主文件「X.mp4/m4a」↔ 伴生封面「X-cover.<图扩展>」
 * （B 站/汽水引擎落盘规律）；封面文件自身（-cover 结尾）返回 null——封面不给自己找封面。
 */
fun companionCoverOf(name: String, allNames: List<String>): String? {
    val suffix = name.substringAfterLast('.', "").let { ext -> name.removeSuffix(".$ext") }.substringAfterLast('-')
    if (suffix.equals("cover", ignoreCase = true)) return null // 封面文件自身不找封面
    val stem = name.substringBeforeLast('.')
    for (ext in COVER_EXTS) {
        val cand = "$stem-cover.$ext"
        if (cand in allNames) return cand
    }
    return null
}

private val COVER_EXTS = listOf("jpg", "jpeg", "png", "webp")

/**
 * 书架模式：加载条目封面（ImageBitmap，失败返回 null）。
 * 优先级：伴生封面文件（同 query 子树）→ 视频帧（MediaMetadataRetriever）→ 音频内嵌图 → null。
 */
fun loadCover(context: Context, item: ShelfItem, all: List<ShelfItem>): Bitmap? {
    val names = all.map { it.name }
    companionCoverOf(item.name, names)?.let { coverName ->
        all.firstOrNull { it.name == coverName }?.let { cover ->
            runCatching {
                context.contentResolver.openInputStream(cover.uri)?.use { BitmapFactory.decodeStream(it) }
            }.getOrNull()?.let { return it }
        }
    }
 // 视频：固定掐 30% 处的帧（系统缩略图偏开头，常见黑场/字幕）；掐帧失败回退系统缩略图。
 // 音频：内嵌封面图。
    if (item.kind == ShelfKind.VIDEO) {
        runCatching {
            val r = MediaMetadataRetriever()
            r.setDataSource(context, item.uri)
            val frame = try {
                val durMs = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                    ?.toLongOrNull() ?: 0L
                if (durMs > 0) {
                    r.getFrameAtTime(
                        durMs * 1000L * 30 / 100, // µs：视频 30% 处
                        MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                    )
                } else {
                    null
                }
            } finally {
                r.release()
            }
            frame
        }.getOrNull()?.let { return it }
        runCatching {
            context.contentResolver.loadThumbnail(item.uri, android.util.Size(512, 512), null)
        }.getOrNull()?.let { return it }
    }
    if (item.kind == ShelfKind.AUDIO) {
        runCatching {
            val r = MediaMetadataRetriever()
            r.setDataSource(context, item.uri)
            val bmp = r.embeddedPicture?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
            r.release()
            bmp
        }.getOrNull()?.let { return it }
    }
    return null
}

fun formatSize(bytes: Long): String = when {
    bytes >= 1L shl 30 -> "%.1fGB".format(bytes.toDouble() / (1L shl 30))
    bytes >= 1L shl 20 -> "%.0fMB".format(bytes.toDouble() / (1L shl 20))
    bytes >= 1L shl 10 -> "%.0fKB".format(bytes.toDouble() / (1L shl 10))
    else -> "${bytes}B"
}

/** 书架条目：Single=现单文件书；Bound=同作品成书（台账 workKey 绑定）。 */
sealed interface ShelfEntry {
    val id: String
    val title: String
    val platform: String
    val dateAdded: Long
    val sizeBytes: Long
    val pages: List<ShelfItem>
}

data class SingleEntry(val item: ShelfItem) : ShelfEntry {
    override val id get() = item.uri.toString()
    override val title get() = item.name
    override val platform get() = item.platform
    override val dateAdded get() = item.dateAdded
    override val sizeBytes get() = item.sizeBytes
    override val pages get() = listOf(item)
}

data class BoundEntry(
    val workKey: String,
    val boundTitle: String,
    override val platform: String,
    override val pages: List<ShelfItem>,
) : ShelfEntry {
    override val id get() = workKey
    override val title get() =
        boundTitle.ifBlank { pages.firstOrNull()?.name?.substringBeforeLast('.')?.ifBlank { null } ?: "未命名内容" }
    override val dateAdded get() = pages.maxOf { it.dateAdded }
    override val sizeBytes get() = pages.sumOf { it.sizeBytes }
}
