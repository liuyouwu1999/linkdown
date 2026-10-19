package top.linkdown.android.engine.internal

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * 汽水播放节点质量评分（参考实现 qishui.py `_quality_rank` L63-87 逐字平移，设计）。
 * quality/bitrate 节点优先、`video_meta` 子字典回退；format 走 节点 format → meta.vtype → meta.format；
 * 全部子串判定先 lower()；bitrate>10000 视为 bps 先 ÷1000（参考实现 注释：实测 64k/251k/126k 为 bps，
 * 阈值按 kbps 设计）。分值表：hires+lossless=110 / lossless=100 / hires=90 / atmos|dolby=88 /
 * hq|high|br≥320=70 / br≥256=65 / standard|br≥128=50 / 其余 10。
 */
internal fun qualityRank(info: JsonObject): Int {
    val meta = info["video_meta"] as? JsonObject ?: JsonObject(emptyMap()) // 参考实现 video_meta 子字典
    val q = firstTruthyStr(info["quality"], meta["quality"]).lowercase()
    val f = firstTruthyStr(info["format"], meta["vtype"], meta["format"]).lowercase()
    var br = firstTruthyLong(info["bitrate"], meta["bitrate"])
    if (br > 10000) br /= 1000
    val lossless = "flac" in f || "lossless" in q || "sq" in q || br >= 900
    val hires = "hires" in q || "master" in q
    return when {
        hires && lossless -> 110
        lossless -> 100
        hires -> 90
        "atmos" in q || "dolby" in q -> 88
        "hq" in q || "high" in q || br >= 320 -> 70
        br >= 256 -> 65
        "standard" in q || br >= 128 -> 50
        else -> 10
    }
}

/** ≙参考实现 `a or b or ""` 真值链后 str()：首个真值（缺失/null/空串/0 均视为假）。 */
private fun firstTruthyStr(vararg elems: JsonElement?): String =
    elems.firstOrNull { truthy(it) }
        ?.let { (it as? JsonPrimitive)?.takeIf { p -> p !is JsonNull }?.content } ?: ""

/** ≙参考实现 int(a or b or 0)：真值链后 int()（数字或数字字符串均可；浮点截断）。 */
private fun firstTruthyLong(vararg elems: JsonElement?): Long =
    elems.firstOrNull { truthy(it) }
        ?.let { e ->
            val c = (e as? JsonPrimitive)?.takeIf { p -> p !is JsonNull }?.content
            c?.toLongOrNull() ?: c?.toDoubleOrNull()?.toLong()
        } ?: 0L

/** Python 真值语义：null/缺失/空串/0/false 假；非空容器真。 */
internal fun truthy(e: JsonElement?): Boolean = when (e) {
    null, is JsonNull -> false
    is JsonObject -> !e.isEmpty()
    is JsonArray -> !e.isEmpty()
    is JsonPrimitive -> when {
        e.isString -> e.content.isNotEmpty()
        e.content == "true" -> true
        e.content == "false" -> false
        else -> (e.content.toDoubleOrNull() ?: 0.0) != 0.0
    }
}
