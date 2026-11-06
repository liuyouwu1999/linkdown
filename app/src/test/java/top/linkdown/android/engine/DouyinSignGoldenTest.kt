package top.linkdown.android.engine.internal

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 抖音双签名 golden vectors（ 实况图有什么下什么 ）：与 TikTokDownloader 开源实现
 * （Apache-2.0）的 Python 版逐字节对拍——固定 MT 种子/固定时钟/固定 UA 下的确定性输出。
 * Python 端生成脚本：固定 random.Random(seed) 噪声 + get_value(now_ms=…)。
 * 签名通过与否由抖音服务端裁决（真机 E2E 已实证 clip_type=5 实况流可取）。
 */
class DouyinSignGoldenTest {

    companion object {
        // 与 Python golden.py 同参：UA/模板/detail query/种子/时钟
        private const val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/146.0.0.0 Safari/537.36"
        private const val UIFID = "4a0bcb7f043e61c4dcd146ed28d71691f4767211e2950c20a3017a405cf7758fadeaae590c66ff74b705aaa729c8577fff75d6ca1cae8e98f64d325cdd451ac8c730f7875f90274b8aef9981036769b75b40d45383e952e29c7abe392c3f7d5f5abd9c6734962f8043a52b8d28f4d41280d7b66fb1c74ce24935b75ff51d48a4b2f87c67bdbdee4b3bd8a92a0dc26569ddc4f582ea4adbab2b3d7919dc77d8f4"

        private val QUERIES = listOf(
            "aweme_id=7704639139442229113&device_platform=webapp&aid=6383&uifid=" + UIFID.take(64),
            "aweme_id=111&device_platform=webapp&aid=6383&uifid=short-id-1&msToken=abcDEF123-_",
            "aid=6383&aweme_id=7300000000000000000",
        )
        private val A_BOGUS_GOLDEN = listOf(
            "QJ0RhtyjEoRcPVFG8KJDHtplJ9nANPSystT/ScHPHxu6YZMOSbPOxPGYGoo74xfyzSBshe3HZxMAbdncBtU0ZHHkomkvSOhWBt5C98XLhqw6GlG/LrmTe0hFuwBC0QvNe5ClEAD5WsMNIVxRVr5plBQaS5zqQbjgWNpcp2b9tEWgfASki9-wOehpqgRZ",
            "O745gFUyQ25fOdKGYCJYH7-lboy/NsuyEzTxWLqTSOP7YheObbPdxOtYjooL4DjysSBshH37gxMMYDVcMGXkZ9nkLmkfSw4jMT5c906Lhqq6Tes/LqDze0DzqwBC0mkNaAClElkR6s0r2jnRVH5NldMay5zq5cjgSHBCpZS9CEWXDS8kwx-kOHggLg37",
            "m7sRDey7ddAcOdMS8KJmHS5UnI9MNTWygUTdScpT9PY6bZtPSSPQxOSOGoow4Djyzbpzh91H2xMAbdxcBGU0Z99pFmkfuh0jPG559g6oMqqgTUG/gHmwegkFKwBFUYiNl/CeE1DRWs0x2nVR9NVBldMGC5Fo55DgbqpIpMtyeEW6fAWkko-zOeDhLgVE",
        )
        private val WEB_SIGNATURES = listOf(
            "8f7adcc64f993babcccd3294041fd66c",
            "5ec1cde51e926ce049915df36c74bb84",
            "2155e08836591f81e43cbadfeec51627",
        )
    }

    @Test
    fun `MT19937 与 Python random 同种同序`() {
        val r = Mt19937(42)
        val expected = listOf(
            0.6394267984578837, 0.025010755222666936, 0.27502931836911926,
            0.22321073814882275, 0.7364712141640124, 0.6766994874229113,
        )
        expected.forEachIndexed { i, e -> assertEquals("sample#$i", e, r.random(), 0.0) }
        val r7 = Mt19937(7)
        val expected7 = listOf(0.32383276483316237, 0.15084917392450192, 0.6509344730398537, 0.07243628666754276)
        expected7.forEachIndexed { i, e -> assertEquals("seed7#$i", e, r7.random(), 0.0) }
    }

    @Test
    fun `SM3 标准测试向量`() {
        // GB/T 32905-2016 附录 A：SM3("abc") 标准摘要
        val digest = Sm3.hash("abc".toByteArray(Charsets.UTF_8))
        val hex = digest.joinToString("") { String.format("%02x", it) }
        assertEquals("66c7f0f462eeedd9d1f2d46bdc10e4e24167c4875cf2f7a2297da02b8f4ba8e0", hex)
    }

    @Test
    fun `a_bogus 三组向量 与 Python 逐字节一致`() {
        QUERIES.forEachIndexed { i, query ->
            val value = ABogus(UA, seed = (42 + i).toLong()).getValue(query, nowMs = 1_730_000_000_000L + i)
            assertEquals("vector#$i", A_BOGUS_GOLDEN[i], value)
        }
    }

    @Test
    fun `websign 三组向量 md5 签名一致`() {
        QUERIES.forEachIndexed { i, query ->
            val aBogusValue = ABogus(UA, seed = (42 + i).toLong()).getValue(query, nowMs = 1_730_000_000_000L + i)
            val signed = douyinWebSign(
                douyinNormalizeQuery(query + "&a_bogus=" + aBogusValue),
                UIFID,
                timestampSec = 1_700_000_000L + i,
            )
            assertEquals(
                "signature#$i",
                WEB_SIGNATURES[i],
                signed.substringAfter("&$DOUYIN_SIGNATURE_PARAM=").take(32),
            )
        }
    }
}
