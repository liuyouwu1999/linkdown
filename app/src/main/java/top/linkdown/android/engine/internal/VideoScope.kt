package top.linkdown.android.engine.internal

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.net.URI

/** view API 判型结果 reason（spec §5.1；两端枚举一致）。 */
internal enum class ProbeReason { GONE, DEGRADED }

internal data class VideoScopeItem(
    val mediaId: String, val title: String, val url: String,
    val durationSec: Long?, val scope: String)

internal data class VideoScope(
    val bvid: String, val title: String, val pages: List<VideoScopeItem>,
    val seasonTitle: String?, val episodes: List<VideoScopeItem>,
    val epCount: Int, val truncated: Boolean,
) { fun hit(): Boolean = pages.size > 1 || episodes.isNotEmpty() }

internal data class VideoScopeProbeResult(val scope: VideoScope?, val reason: ProbeReason?)

private val VIDEO_PATH = Regex("^/video/(BV[0-9A-Za-z]+|av\\d+)/?$")

/** 双域放行（www./m.，router.py:15 先例）；空间页/番剧不放行。 */
internal fun isVideoUrl(url: String): Boolean = runCatching {
    val u = URI(url); val path = u.path ?: "/"
    (u.host?.lowercase() == "www.bilibili.com" || u.host?.lowercase() == "m.bilibili.com")
        && VIDEO_PATH.containsMatchIn(path)
}.getOrDefault(false)

/** 分P清单 title 唯一字面（spec §5.1；part 空则仅 P{n}）。 */
internal fun pageItemTitle(n: Int, part: String?): String {
    val p = part?.trim().orEmpty()
    return if (p.isEmpty()) "P$n" else "P$n $p"
}

/** 嵌套多P集 title 后缀（ep.pages>1 才加；spec §7）。常量供预查键去后缀用。 */
internal const val MULTI_P_SUFFIX = " (多P，仅下 P1)"

internal fun episodeItemTitle(arcTitle: String, epPages: Int): String =
    if (epPages > 1) arcTitle + MULTI_P_SUFFIX else arcTitle

/** 复合落盘名预测键（spec §6.2；extractor bilibili.py:784-786 逐字复刻 + PredictMediaName.base）。
 * 坑：part 空 → 尾随空格逐字保留；P≥100 三位自然增长（padStart 最小宽度语义）。 */
internal fun compositePrecheckKey(videoTitle: String, n: Int, part: String?): String =
    PredictMediaName.base("$videoTitle p${n.toString().padStart(2, '0')} ${part.orEmpty()}")

/** 季范围预查键=裸集标题（spec §6.2 v4 钉死；不带后缀）。 */
internal fun seasonPrecheckKey(epTitle: String): String = PredictMediaName.base(epTitle)

private fun jStr(o: JsonObject?, k: String): String? =
    (o?.get(k) as? JsonPrimitive)?.takeIf { it !is kotlinx.serialization.json.JsonNull }?.content

internal fun parseVideoScope(jsonText: String): VideoScopeProbeResult = runCatching {
    val root = Json.parseToJsonElement(jsonText).jsonObject
    val code = root["code"]?.jsonPrimitive?.content?.toIntOrNull()
        ?: return@runCatching VideoScopeProbeResult(null, ProbeReason.DEGRADED)
    if (code == -404) return@runCatching VideoScopeProbeResult(null, ProbeReason.GONE)
    if (code != 0) return@runCatching VideoScopeProbeResult(null, ProbeReason.DEGRADED)
    val d = root["data"]?.jsonObject ?: return@runCatching VideoScopeProbeResult(null, ProbeReason.DEGRADED)
    val bvid = jStr(d, "bvid") ?: return@runCatching VideoScopeProbeResult(null, ProbeReason.DEGRADED)
    val pages = (d["pages"]?.jsonArray ?: kotlinx.serialization.json.JsonArray(emptyList()))
        .mapIndexed { i, e ->
            val pg = e.jsonObject
            VideoScopeItem(
                mediaId = "${bvid}_p${i + 1}",
                title = pageItemTitle(i + 1, jStr(pg, "part")),
                url = "https://www.bilibili.com/video/$bvid?p=${i + 1}",
                durationSec = pg["duration"]?.jsonPrimitive?.longOrNull,
                scope = "page")
        }
    val season = d["ugc_season"]?.jsonObject
    val eps = season?.get("sections")?.jsonArray.orEmpty()
        .flatMap { it.jsonObject["episodes"]?.jsonArray.orEmpty() }
        .map { e ->
            val ep = e.jsonObject; val arc = ep["arc"]?.jsonObject
            val bvid2 = jStr(ep, "bvid") ?: ""
            VideoScopeItem(
                mediaId = bvid2,
                title = episodeItemTitle(
                    jStr(arc, "title") ?: jStr(ep, "title") ?: bvid2,
                    ep["pages"]?.jsonArray?.size ?: 0),
                url = "https://www.bilibili.com/video/$bvid2",
                durationSec = arc?.get("duration")?.jsonPrimitive?.longOrNull,
                scope = "season")
        }
    val epCount = season?.get("ep_count")?.jsonPrimitive?.content?.toIntOrNull() ?: eps.size
    val title = jStr(d, "title") ?: ""
    VideoScopeProbeResult(
        VideoScope(bvid = bvid, title = title, pages = pages,
            seasonTitle = season?.let { jStr(it, "title") }, episodes = eps,
            epCount = epCount, truncated = eps.size < epCount), null)
}.getOrDefault(VideoScopeProbeResult(null, ProbeReason.DEGRADED))

/** 范围→(清单标题, 子项)；which ∈ "page"|"season"。预查键纯函数预计算。
 * 季范围预查键必须去后缀（裸集标题，spec v4 §6.2:130；带后缀会预查恒 miss）。 */
internal fun scopeChildren(scope: VideoScope, which: String): Pair<String, List<ScopeChild>> =
    if (which == "season") {
        (scope.seasonTitle ?: scope.title) to scope.episodes.map {
            ScopeChild(it.url, it.title, seasonPrecheckKey(it.title.removeSuffix(MULTI_P_SUFFIX)))
        }
    } else {
        scope.title to scope.pages.mapIndexed { i, it ->
            val part = it.title.removePrefix("P${i + 1}").removePrefix(" ")
            ScopeChild(it.url, it.title, compositePrecheckKey(scope.title, i + 1, part))
        }
    }

/** startFlow 判型后下一步（纯决策，JVM 直测；spec §8.4 Android 降级落点）。 */
internal fun scopeFlowNext(res: VideoScopeProbeResult?): ScopeFlowAction = when {
    res == null -> ScopeFlowAction.SINGLE_PLAIN                 // 协程异常
    res.scope == null ->
        if (res.reason == ProbeReason.DEGRADED) ScopeFlowAction.SINGLE_TOAST
        else ScopeFlowAction.SINGLE_PLAIN                       // gone：不 toast 识别注
    res.scope.hit() -> ScopeFlowAction.SHOW_CHOICE
    else -> ScopeFlowAction.SINGLE_PLAIN                        // 命中定义未达 → 单视频
}

/** 范围子项（url + 展示 title + 预查落盘名预测键，Task 8 弹窗插位消费）。 */
internal data class ScopeChild(val url: String, val title: String, val precheckKey: String)

/** startFlow 判型后动作（Task 8 弹窗插位消费）。 */
internal enum class ScopeFlowAction { SHOW_CHOICE, SINGLE_PLAIN, SINGLE_TOAST }
