package top.linkdown.android.ingest

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import java.io.File

object MediaStoreIngest {
    fun ingest(context: Context, src: File, platformDir: String, onProgress: (Float) -> Unit = {}): Uri {
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
            return uri
        } catch (t: Throwable) {
            resolver.delete(uri, null, null)
            throw t
        }
    }
}
