package top.linkdown.android.engine.internal

/**
 * 参考实现 linkdown/storage.py safe_filename 平移（storage 为两引擎共用模块，故置 engine/internal）：
 * 去掉 [\\/:*?"<>|] 与控制字符（\x00-\x1f）、首尾空白，截 150 字符，空则回退 fallback。
 * 有意偏离（真机两轮实证）：①Linux 单文件名上限 255 **字节**，参考实现 150 **字符**在 CJK
 * 下=450 字节必炸（抖音 desc 600+ 字触发）；②经 MediaStore IS_PENDING 两段式真实落盘时，
 * FUSE/MediaProvider 文件层对 >~240 字节名做**中段「...」省略化**（239+4=243 字节触发、
 * 233+4=237 免疫，产物名被改写导致判重 miss）——故字节上限取 [MAX_BYTES]=200，再留
 * 扩展名（调用点拼接）与 MediaStore 同名自动后缀「 (N)」裕量；不劈半个字符。
 */
private val UNSAFE_CHARS = Regex("[\\\\/:*?\"<>|\u0000-\u001F]")

/** 240 字节 FUSE 省略化实证界内再留扩展名/去重后缀裕量 */
private const val MAX_BYTES = 200

internal fun safeFilename(name: String, fallback: String = "untitled"): String {
    val cleaned = UNSAFE_CHARS.replace(name.trim(), "").take(150)
    if (cleaned.isEmpty()) return fallback
    var bytes = 0
    var i = 0
    while (i < cleaned.length) {
        val cp = cleaned.codePointAt(i)
        val w = when {
            cp < 0x80 -> 1
            cp < 0x800 -> 2
            cp < 0x10000 -> 3
            else -> 4
        }
        if (bytes + w > MAX_BYTES) break
        bytes += w
        i += Character.charCount(cp)
    }
    return if (i == 0) fallback else cleaned.substring(0, i)
}
