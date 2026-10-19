package top.linkdown.android.cookies

import java.io.File

/** Netscape cookies.txt 读写。HttpOnly 行带 #HttpOnly_ 前缀（yt-dlp 认该前缀）。 */
object NetscapeCookieFile {

    data class Entry(
        val domain: String, val path: String, val secure: Boolean,
        val expires: Long, val name: String, val value: String, val httpOnly: Boolean,
    )

    fun parse(text: String): List<Entry> = text.lineSequence()
        .map { it.trim() }
        .filter { it.isNotEmpty() && !it.startsWith("# ") }
        .map { line ->
            val httpOnly = line.startsWith("#HttpOnly_")
            val raw = if (httpOnly) line.removePrefix("#HttpOnly_") else line
            val f = raw.split("\t")
            Entry(f[0], f[2], f[3] == "TRUE", f[4].toLong(), f[5], f.drop(6).joinToString("\t"), httpOnly)
        }.toList()

 /** 首行 magic 为 yt-dlp/Python cookiejar 的硬校验——缺行整文件拒收（parse 靠 "# " 前缀跳过它）。 */
    const val MAGIC_HEADER = "# Netscape HTTP Cookie File"

    fun serialize(entries: List<Entry>): String =
        entries.joinToString("\n", prefix = "$MAGIC_HEADER\n") { e ->
            val prefix = if (e.httpOnly) "#HttpOnly_" else ""
            "$prefix${e.domain}\tTRUE\t${e.path}\t${if (e.secure) "TRUE" else "FALSE"}\t${e.expires}\t${e.name}\t${e.value}"
        } + "\n"

 /** WebView CookieManager 返回 "k=v; k2=v2"（HttpOnly 已包含、属性被剥）——统一按 HttpOnly 落盘。 */
    fun fromPairs(header: String, domain: String, expiresEpoch: Long = 2145916800L): List<Entry> =
        header.split(";").mapNotNull {
            val kv = it.trim().split("=", limit = 2)
            if (kv.size != 2) null else Entry(domain, "/", false, expiresEpoch, kv[0], kv[1], httpOnly = true)
        }

    fun write(entries: List<Entry>, file: File) {
        file.parentFile?.mkdirs()
        file.writeText(serialize(entries))
    }
}
