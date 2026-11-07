package top.linkdown.android.ingest

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import java.io.File

/** 入库产物：uri=MediaStore 条目（JVM 假件为 null）；displayName/relPath=入库后回查真名（同名冲突自动改名场景）。 */
data class IngestedFile(val uri: Uri?, val displayName: String, val relPath: String)

object MediaStoreIngest {
    fun ingest(context: Context, src: File, platformDir: String, onProgress: (Float) -> Unit = {}): IngestedFile {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, src.name)
            put(MediaStore.MediaColumns.RELATIVE_PATH, "Download/链接下载/$platformDir")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: error("MediaStore 插入失败")
        try {
            val total = src.length().coerceAtLeast(1)
            resolver.openOutputStream(uri)?.use { out ->
                src.inputStream().use { input ->
                    val buf = ByteArray(8192); var copied = 0L; var n = input.read(buf)
                    while (n >= 0) {
                        out.write(buf, 0, n); copied += n
                        onProgress((copied.toDouble() / total).toFloat())
                        n = input.read(buf)
                    }
                }
            } ?: error("MediaStore 输出流打开失败")
            values.clear()
            values.put(MediaStore.MediaColumns.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            // 回查真实名（IS_PENDING 清零后查；查空回退本地名+构造路径，记日志不按失败处理）
            var displayName = src.name
            var relPath = "Download/链接下载/$platformDir/"
            runCatching {
                resolver.query(
                    uri,
                    arrayOf(MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.RELATIVE_PATH),
                    null, null, null,
                )?.use { cur ->
                    if (cur.moveToFirst()) {
                        cur.getString(0)?.takeIf { it.isNotEmpty() }?.let { displayName = it }
                        cur.getString(1)?.takeIf { it.isNotEmpty() }?.let { relPath = it }
                    }
                }
            }
            return IngestedFile(uri, displayName, relPath)
        } catch (t: Throwable) {
            resolver.delete(uri, null, null)
            throw t
        }
    }
}
