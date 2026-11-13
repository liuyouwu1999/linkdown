package top.linkdown.android.engine

import top.linkdown.android.cookies.CookieStore
import top.linkdown.android.cookies.NetscapeCookieFile
import top.linkdown.android.engine.internal.ProbeReason
import top.linkdown.android.engine.internal.VideoScopeProbeResult
import top.linkdown.android.engine.internal.parseVideoScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

internal interface VideoScopeProbe { suspend fun probe(url: String): VideoScopeProbeResult }

internal class HttpVideoScopeProbe(private val filesDir: File) : VideoScopeProbe {
    override suspend fun probe(url: String): VideoScopeProbeResult = withContext(Dispatchers.IO) {
        val bvid = Regex("/video/(BV[0-9A-Za-z]+|av\\d+)").find(url)?.groupValues?.get(1)
            ?: return@withContext VideoScopeProbeResult(null, ProbeReason.DEGRADED)
        val q = if (bvid.startsWith("av")) "aid=${bvid.removePrefix("av")}" else "bvid=$bvid"
        val conn = (URL("https://api.bilibili.com/x/web-interface/view?$q").openConnection()
                as HttpURLConnection).apply {
            connectTimeout = 8000; readTimeout = 8000
            setRequestProperty("User-Agent",
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/134.0.0.0 Safari/537.36")
            setRequestProperty("Referer", "https://www.bilibili.com/")
            CookieStore.existing("bilibili", filesDir)?.readText()
                ?.let { text -> NetscapeCookieFile.parse(text).firstOrNull { it.name == "SESSDATA" }?.value }
                ?.let { sessdata -> setRequestProperty("Cookie", "SESSDATA=$sessdata") }
        }
        try {
            val text = conn.inputStream.bufferedReader().use { it.readText() }
            parseVideoScope(text)
        } catch (_: Exception) {
            VideoScopeProbeResult(null, ProbeReason.DEGRADED)
        } finally {
            conn.disconnect()
        }
    }
}
