package top.linkdown.android.queue

import android.content.Context
import android.provider.MediaStore
import top.linkdown.android.engine.Mode
import top.linkdown.android.engine.internal.PredictMediaName
import top.linkdown.android.engine.internal.safeFilename
import top.linkdown.android.pipeline.PlatformDirs

/**
 * 货架查询缝（ 库判重 设计）：按平台目录返回已入库 DISPLAY_NAME 集。
 * 生产=[MediaStoreShelfQuery]（ContentResolver 查询）；JVM 单测注入假查询（lambda）。
 */
fun interface ShelfQuery {
    fun displayNames(platform: String): List<String>
}

/**
 * 库判重（按设计）：probe 后、下载启动前，预测落盘名与货架 DISPLAY_NAME 前缀比对。
 * 判据两段：
 * - 扩展名匹配：DISPLAY_NAME 扩展名 ∈ 该模式扩展名集合——VIDEO=mp4|mkv|webm；AUDIO=mp3|m4a；
 * COVER=jpg|webp（集合外不命中，防封面顶替视频误判）；
 * - 前缀方向=「预测基名（无扩展名）是 DISPLAY_NAME 去扩展名后的前缀」
 * ——顺带命中 MediaStore 冲突自动改名 `xxx (1).mp4`；
 * - 预测基名归一器**与产物命名同源**（真机实证两处 miss）：yt-dlp 平台落盘名=yt-dlp sanitize
 * （[PredictMediaName.base] 复刻）；抖音/汽水原生落盘名=[safeFilename]——删 `|《》` 等字符并字节
 * 截断，归一不一致时含特殊字符/超长标题判重恒 miss；汽水产物名还带 `作者 - ` 前缀（参考实现 
 * f"{author} - {title}"），故 [author] 仅汽水拼入。
 * 局限：前缀族互撞（「…Sequel Part 2」命中「…Sequel」的判重）为参考实现 语义有意取舍；yt-dlp
 * 改名产物（格式合并 mkv/webm 漂移）已被集合覆盖，其余极端漏判见 设计注记。
 */
class DedupCheck(private val query: ShelfQuery) {

    fun exists(platform: String, title: String, mode: Mode, author: String? = null): Boolean {
        val base = when (platform) {
            PlatformDirs.DOUYIN -> safeFilename(title)
            PlatformDirs.QISHUI ->
                safeFilename(if (author.isNullOrEmpty()) title else "$author - $title")
            else -> PredictMediaName.base(title)
        }
        val exts = extsFor(mode)
        return query.displayNames(platform).any { name ->
            val dot = name.lastIndexOf('.')
            val ext = (if (dot < 0) "" else name.substring(dot + 1)).lowercase()
            ext in exts && name.substring(0, if (dot < 0) name.length else dot).startsWith(base)
        }
    }

    companion object {
 /** 各模式产物扩展名集合（设计逐字；yt-dlp 实际落盘格式域）。 */
        fun extsFor(mode: Mode): Set<String> = when (mode) {
            Mode.VIDEO -> setOf("mp4", "mkv", "webm")
            Mode.AUDIO -> setOf("mp3", "m4a")
            Mode.COVER -> setOf("jpg", "webp")
        }
    }
}

/**
 * 生产查询：MediaStore.Downloads owner（本应用）+ RELATIVE_PATH 前缀过滤——与入库
 * （MediaStoreIngest `Download/链接下载/<platform>`）和货架页（ShelfStore.query）同子树。
 */
class MediaStoreShelfQuery(context: Context) : ShelfQuery {
    private val resolver = context.contentResolver
    private val packageName = context.packageName

    override fun displayNames(platform: String): List<String> {
        val proj = arrayOf(MediaStore.MediaColumns.DISPLAY_NAME)
        val sel = "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ? AND " +
            "(${MediaStore.MediaColumns.OWNER_PACKAGE_NAME} IS NULL OR " +
            "${MediaStore.MediaColumns.OWNER_PACKAGE_NAME} = ?)"
        val out = mutableListOf<String>()
        resolver.query(
            MediaStore.Downloads.EXTERNAL_CONTENT_URI,
            proj,
            sel,
            arrayOf("Download/链接下载/$platform/%", packageName),
            null,
        )?.use { cur ->
            while (cur.moveToNext()) cur.getString(0)?.let { out.add(it) }
        }
        return out
    }
}
