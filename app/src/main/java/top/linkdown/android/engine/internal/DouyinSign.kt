package top.linkdown.android.engine.internal

import java.security.MessageDigest
import kotlin.math.max

/**
 * 抖音网页 detail 接口双签名（ 实况图有什么下什么 ）：
 * - a_bogus 参数：bdms.js (v1.0.1.19-fix.01) 逆向算法的 Kotlin 移植
 * - x-secsdk-web-signature 参数：secsdk webSignUrl 的 MD5 签名
 * 参照 TikTokDownloader (Apache-2.0) / Evil0ctal Douyin_TikTok_Download_API 的开源实现移植，
 * 语义逐字对拍（golden vectors 见 DouyinSignGoldenTest——Python 固定种子/固定时钟逐字节对齐）。
 * 噪声源 = 移植的 MT19937（Python random.Random 同种同序，含参数求值顺序）：
 * 服务器不校验噪声内容但校验签名整体，每次请求换种子防同字节重放。
 */

// ============================================================
// MT19937：Python random.Random(int) 的同种同序移植（噪声源）
// ============================================================

internal class Mt19937(seed: Long) {
    private val mt = IntArray(624)
    private var mti = 625

    init {
        // Python random.seed(int)：绝对值转 32 位小端字数组 → init_by_array
        var s = if (seed < 0) {
            // Python 取绝对值：-2^63 特例恒为自身绝对值溢出，此处入参不会出现
            seed.takeIf { it != Long.MIN_VALUE }?.let { -it } ?: Long.MIN_VALUE
        } else seed
        val key = ArrayList<Int>()
        var v = s
        while (v != 0L) {
            key.add((v and 0xffffffffL).toInt())
            v = v shr 32 and 0xffffffffL
        }
        if (key.isEmpty()) key.add(0)
        initByArray(key.toIntArray())
    }

    private fun initGenrand(s: Int) {
        mt[0] = s
        for (i in 1 until 624) {
            val prev = mt[i - 1]
            mt[i] = (1812433253L * ((prev xor (prev ushr 30)).toLong() and 0xffffffffL) + i).toInt()
        }
        mti = 624
    }

    private fun initByArray(key: IntArray) {
        initGenrand(19650218)
        var i = 1
        var j = 0
        var k = max(624, key.size)
        while (k > 0) {
            // mt 值恒为 32 位无符号位型：乘法操作数先转无符号 Long（防符号扩展）
            val prev = (mt[i - 1].toLong() and 0xffffffffL)
            val x = (prev xor (prev ushr 30)) * 1664525L and 0xffffffffL
            mt[i] = ((mt[i].toLong() and 0xffffffffL) xor x).plus(key[j].toLong() and 0xffffffffL).plus(j.toLong()).toInt()
            i++; j++; k--
            if (i >= 624) { mt[0] = mt[623]; i = 1 }
            if (j >= key.size) j = 0
        }
        for (k in 623 downTo 1) {
            val prev = (mt[i - 1].toLong() and 0xffffffffL)
            val x = (prev xor (prev ushr 30)) * 1566083941L and 0xffffffffL
            mt[i] = ((mt[i].toLong() and 0xffffffffL) xor x).minus(i.toLong()).toInt()
            i++
            if (i >= 624) { mt[0] = mt[623]; i = 1 }
        }
        mt[0] = 0x80000000.toInt()
    }

    private fun genrandInt32(): Int {
        if (mti >= 624) {
            for (i in 0 until 624) {
                val y = (mt[i] and 0x80000000.toInt()) or (mt[(i + 1) % 624] and 0x7fffffff)
                var n = mt[(i + 397) % 624] xor (y ushr 1)
                // ⚠️0x9908b0df 非 MT19937 标准 0x9907b0df——CPython _randommodule.c 的历史偏差，
                // golden vectors 以 CPython random.Random 的输出为基准，必须与其一致
                if (y and 1 != 0) n = n xor 0x9908b0df.toInt()
                mt[i] = n
            }
            mti = 0
        }
        var y = mt[mti++]
        y = y xor (y ushr 11)
        y = y xor ((y shl 7) and 0x9d2c5680.toInt())
        y = y xor ((y shl 15) and 0xefc60000.toInt())
        y = y xor (y ushr 18)
        return y
    }


    /** ≙Python random.random()：两个 27/26 位样本拼 53 位尾数 */
    fun random(): Double {
        val a = genrandInt32() ushr 5
        val b = genrandInt32() ushr 6
        return (a * 67108864.0 + b) / 9007199254740992.0
    }
}

// ============================================================
// SM3 国密哈希（GB/T 32905-2016）
// ============================================================

internal object Sm3 {
    private val IV = intArrayOf(
        0x7380166F, 0x4914B2B9, 0x172442D7, 0xDA8A0600.toInt(),
        0xA96F30BC.toInt(), 0x163138AA, 0xE38DEE4D.toInt(), 0xB0FB0E4E.toInt(),
    )
    private const val T0 = 0x79CC4519
    private const val T1 = 0x7A879D8A
    private const val MASK = 0xFFFFFFFFL

    private fun rotl(v: Int, bits: Int): Int {
        val b = bits % 32
        return if (b == 0) v else ((v shl b) or (v ushr (32 - b)))
    }

    private fun expand(block: ByteArray): Pair<IntArray, IntArray> {
        val w = IntArray(68)
        for (i in 0 until 16) {
            w[i] = (((block[i * 4].toLong() and 0xffL) shl 24) or
                ((block[i * 4 + 1].toLong() and 0xffL) shl 16) or
                ((block[i * 4 + 2].toLong() and 0xffL) shl 8) or
                (block[i * 4 + 3].toLong() and 0xffL)).toInt()
        }
        for (i in 16 until 68) {
            var x = w[i - 16] xor w[i - 9] xor rotl(w[i - 3], 15)
            x = x xor rotl(x, 15) xor rotl(x, 23)
            w[i] = (x xor rotl(w[i - 13], 7) xor w[i - 6]).toInt()
        }
        val w1 = IntArray(64)
        for (i in 0 until 64) w1[i] = w[i] xor w[i + 4]
        return w to w1
    }

    private fun compress(state: IntArray, block: ByteArray): IntArray {
        val (w, w1) = expand(block)
        var a = state[0]; var b = state[1]; var c = state[2]; var d = state[3]
        var e = state[4]; var f = state[5]; var g = state[6]; var h = state[7]
        for (j in 0 until 64) {
            val t = if (j < 16) T0 else T1
            val ss1 = rotl((rotl(a, 12).toLong() + (e.toLong() and MASK) + rotl(t, j).toLong()).toInt(), 7)
            val ss2 = ss1 xor rotl(a, 12)
            val tt1 = ((ff(j, a, b, c).toLong() + (d.toLong() and MASK) + (ss2.toLong() and MASK) + (w1[j].toLong() and MASK)) and MASK).toInt()
            val tt2 = ((gg(j, e, f, g).toLong() + (h.toLong() and MASK) + (ss1.toLong() and MASK) + (w[j].toLong() and MASK)) and MASK).toInt()
            d = c
            c = rotl(b, 9)
            b = a
            a = tt1
            h = g
            g = rotl(f, 19)
            f = e
            e = tt2 xor rotl(tt2, 9) xor rotl(tt2, 17)
        }
        return intArrayOf(
            a xor state[0], b xor state[1], c xor state[2], d xor state[3],
            e xor state[4], f xor state[5], g xor state[6], h xor state[7],
        )
    }

    private fun ff(index: Int, x: Int, y: Int, z: Int): Int =
        if (index < 16) x xor y xor z else (x and y) or (x and z) or (y and z)

    private fun gg(index: Int, x: Int, y: Int, z: Int): Int =
        if (index < 16) x xor y xor z else (x and y) or (x.inv() and z)

    private fun pad(message: ByteArray): ByteArray {
        val bitLength = message.size * 8L
        var padded = message + byteArrayOf(0x80.toByte())
        while (padded.size % 64 != 56) padded += 0
        val lenBytes = ByteArray(8)
        for (i in 0 until 8) lenBytes[i] = (bitLength ushr ((7 - i) * 8)).toByte()
        return padded + lenBytes
    }

    /** 32 字节 SM3 摘要 */
    fun hash(message: ByteArray): ByteArray {
        var state = IV
        val padded = pad(message)
        for (offset in padded.indices step 64) {
            state = compress(state, padded.copyOfRange(offset, offset + 64))
        }
        val out = ByteArray(32)
        state.forEachIndexed { i, word ->
            out[i * 4] = (word ushr 24).toByte()
            out[i * 4 + 1] = (word ushr 16).toByte()
            out[i * 4 + 2] = (word ushr 8).toByte()
            out[i * 4 + 3] = word.toByte()
        }
        return out
    }

    /** 32 个整数的摘要列表（≙Python sm3_to_array：摘要字节逐个 0..255） */
    fun toIntArray(data: ByteArray): IntArray = hash(data).map { it.toInt() and 0xFF }.toIntArray()
}

// ============================================================
// ABogus
// ============================================================

/** 抖音 ABogus 双字母表：s4 编码最终签名，s3 编码进入第三摘要链的 User-Agent */
private val ALPHABET_S3 = "ckdp1h4ZKsUB80/Mfvw36XIgR25+WQAlEi7NLboqYTOPuzmFjJnryx9HVGDaStCe"
private val ALPHABET_S4 = "Dkdpgh2ZmsQB80/MfvV36XI1R45-WUAlEixNLwoqYTOPuzKFjJnry79HbGcaStCe"

/** 哈希前附加在 query 末尾的盐值 */
private const val AB_SALT = "dhzx"
private const val PAYLOAD_KEY = 0xD3
private const val FORTNIGHT_EPOCH_MS = 1_721_836_800_000L
private const val PAGE_ID = 6241
private const val AID = 6383
private val HEADER_MAGIC = intArrayOf(3, 82)
private val SDK_VERSION = intArrayOf(1, 0, 1, 0)
private val NOISE_MASKS = intArrayOf(0x91, 0x42, 0x2C)
private val DATA_MASKS = intArrayOf(0x6E, 0xBD, 0xD3)
private val FIELD_ORDER = arrayOf(
    "L34", "L44", "L56", "L61", "L73", "L29", "L70", "L45", "L35", "L49",
    "L38", "L66", "L51", "L68", "L28", "L48", "L64", "L47", "L30", "L71",
    "L26", "L55", "L31", "L69", "L59", "L40", "L62", "L63", "L27", "L72",
    "L41", "L74", "L57", "L52", "L42", "L39", "L33", "L67", "L53", "L43",
    "L65", "L46", "L36", "L24", "L60", "L32", "L79", "L80", "L84", "L85",
)
private val CANARIES = arrayOf(intArrayOf(3, 11, 12), intArrayOf(4, 8, 9), intArrayOf(5, 12, 13))
private val DIGEST_CHAIN_SLOTS = mapOf(
    "query" to arrayOf("L48", "L49", "L51"),
    "body" to arrayOf("L52", "L53", "L55"),
    "user_agent" to arrayOf("L56", "L57", "L59"),
)
private val DIGEST_CHAIN_INDICES = mapOf(
    "query" to intArrayOf(9, 18),
    "body" to intArrayOf(10, 19),
    "user_agent" to intArrayOf(11, 21),
)
private const val ENV_FLAGS = 1
private const val DETECT_FLAGS = 14
private const val TRIPWIRE_LOCKED = 3
private const val CALL_BUCKET = 6
private const val DEFAULT_BROWSER_INFO = "1536|742|1536|864|1536|864|1536|864|MacIntel"
private val HEADER_NOISE_BANDS = mapOf("chrome" to 0, "firefox" to 40, "safari" to 81, "edge" to 125, "huawei" to 170, "other" to 210)
private const val TRIPWIRE_SET = 0xB2
private const val TRIPWIRE_FREE = 0x4D

/** 按 SDK charCodeAt 规则转字节：>U+00FF 先出高字节再出低字节，其余单字节 */
private fun jsBytes(text: String): ByteArray {
    val units = text.toByteArray(Charsets.UTF_16LE)
    val out = ArrayList<Byte>(units.size / 2 + 1)
    var i = 0
    while (i + 1 < units.size) {
        val code = (units[i].toInt() and 0xFF) or ((units[i + 1].toInt() and 0xFF) shl 8)
        if (code and 0xFF00 != 0) out.add(((code shr 8) and 0xFF).toByte())
        out.add((code and 0xFF).toByte())
        i += 2
    }
    return out.toByteArray()
}

private fun leBytes(value: Long, count: Int): IntArray = IntArray(count) { ((value shr (8 * it)) and 0xFF).toInt() }

/** SDK 的 RC4 变体：S 盒降序初始化、密钥调度用乘法（非标准 RC4） */
private fun rc4(key: ByteArray, data: ByteArray): ByteArray {
    val box = IntArray(256)
    for (i in 0 until 256) box[255 - i] = i
    var j = 0
    for (i in 0 until 256) {
        j = ((j.toLong() * box[i].toLong() + j.toLong() + (key[i % key.size].toLong() and 0xffL)) and 0xffL).toInt()
        val t = box[i]; box[i] = box[j]; box[j] = t
    }
    val out = ByteArray(data.size)
    var i = 0
    j = 0
    for (index in data.indices) {
        i = (i + 1) and 0xFF
        j = (j + box[i]) and 0xFF
        val t = box[i]; box[i] = box[j]; box[j] = t
        out[index] = (data[index].toInt() xor box[(box[i] + box[j]) and 0xFF]).toByte()
    }
    return out
}

/** SDK 字母表的 base64（"=" 填充） */
private fun encodeBase64(data: ByteArray, alphabet: String): String {
    val out = StringBuilder()
    var offset = 0
    while (offset < data.size) {
        val remain = data.size - offset
        val chunk = data.copyOfRange(offset, offset + minOf(3, remain))
        var block = 0L
        for (b in chunk) block = (block shl 8) or (b.toLong() and 0xffL)
        block = block shl (8 * (3 - chunk.size))
        val digits = intArrayOf(
            ((block shr 18) and 0x3F).toInt(),
            ((block shr 12) and 0x3F).toInt(),
            ((block shr 6) and 0x3F).toInt(),
            (block and 0x3F).toInt(),
        )
        for (d in 0..chunk.size) out.append(alphabet[digits[d]])
        offset += 3
    }
    val padLen = (4 - out.length % 4) % 4
    repeat(padLen) { out.append('=') }
    return out.toString()
}

private fun familyOf(userAgent: String): String {
    val ua = userAgent.lowercase()
    for (name in listOf("edg", "huawei", "firefox", "chrome", "safari")) {
        if (name in ua) return if (name == "edg") "edge" else name
    }
    return "other"
}

/** 一次性封装：SM3(SM3(text+SALT))（双链摘要，≙Python digest_of） */
private fun doubleDigest(text: String): IntArray {
    val salted = text.toByteArray(Charsets.UTF_8) + AB_SALT.toByteArray(Charsets.UTF_8)
    val first = Sm3.hash(salted)
    return Sm3.toIntArray(first)
}

private fun userAgentDigest(userAgent: String): IntArray {
    val key = byteArrayOf(
        ((ENV_FLAGS shr 8) and 0xFF).toByte(),
        (ENV_FLAGS and 0xFF).toByte(),
        (DETECT_FLAGS and 0xFF).toByte(),
    )
    val sealed = rc4(key, jsBytes(userAgent.trim()))
    return Sm3.toIntArray(encodeBase64(sealed, ALPHABET_S3).toByteArray(Charsets.UTF_8))
}

private fun canaryOf(digest: IntArray, offset: Int, sentinel: Int, fallback: Int): Int {
    for (i in offset until digest.size) {
        if (digest[i] != sentinel) return digest[i]
    }
    return fallback
}

/** _mask_pair：两数据字节摊四载体字节；噪声内部抽取，env 可覆盖 low/high（低/高字节） */
private fun maskPair(b0: Int, b1: Int, rng: Mt19937, overrideLow: Int? = null, overrideHigh: Int? = null): IntArray {
    val noise = (rng.random() * 65535).toInt()
    val low = (overrideLow ?: (noise and 0xFF)) and 0xFF
    val high = (overrideHigh ?: ((noise shr 8) and 0xFF)) and 0xFF
    return intArrayOf(
        (low and 0xAA) or (b0 and 0x55),
        (low and 0x55) or (b0 and 0xAA),
        (high and 0xAA) or (b1 and 0x55),
        (high and 0x55) or (b1 and 0xAA),
    )
}

private fun expandNoise(body: ByteArray, rng: Mt19937): ByteArray {
    val out = ArrayList<Byte>(body.size * 4 / 3 + 3)
    var offset = 0
    while (offset < body.size) {
        val remain = body.size - offset
        if (remain < 3) {
            out.add(body[offset])
            if (remain > 1 && body[offset + 1].toInt() != 0) out.add(body[offset + 1])
            break
        }
        val noise = ((rng.random() * 1000).toInt() and 0xFF)
        for (k in 0 until 3) {
            out.add(((noise and NOISE_MASKS[k]) or (body[offset + k].toInt() and DATA_MASKS[k])).toByte())
        }
        out.add(
            ((body[offset].toInt() and NOISE_MASKS[0]) or
                (body[offset + 1].toInt() and NOISE_MASKS[1]) or
                (body[offset + 2].toInt() and NOISE_MASKS[2])).toByte(),
        )
        offset += 3
    }
    return out.toByteArray()
}

/**
 * ABogus 签名器（≙Python ABogus 类）：除配置外无状态，每次请求新建。
 * [userAgent] 必须与实际发送请求的 UA 完全一致（签名摘要链绑定 UA）；
 * [seed] 驱动噪声字节（服务器不校验噪声，仅测试需固定种子对拍）。
 */
internal class ABogus(private val userAgent: String, private val seed: Long) {
    private val browserInfo = DEFAULT_BROWSER_INFO

    fun getValue(query: String, nowMs: Long): String {
        val rng = Mt19937(seed)
        val digests = mapOf(
            "query" to doubleDigest(query),
            "body" to doubleDigest(""),
            "user_agent" to userAgentDigest(userAgent),
        )
        val ink = nowMs - 1
        val fortnights = (nowMs - FORTNIGHT_EPOCH_MS) / (1000L * 60 * 60 * 24 * 14)
        val infoBytes = jsBytes(browserInfo)
        val tailBytes = jsBytes("${(nowMs + 3) and 0xFF},")
        val fields = HashMap<String, Int>(64)
        fields["L24"] = 41
        fields["L26"] = fortnights.toInt()
        fields["L27"] = CALL_BUCKET
        fields["L28"] = 3
        fields["L35"] = ENV_FLAGS and 0xFF
        fields["L36"] = (ENV_FLAGS shr 8) and 0xFF
        fields["L38"] = 0x21 and 0xFF
        fields["L39"] = (0x21 shr 8) and 0xFF
        fields["L66"] = TRIPWIRE_LOCKED
        fields["L79"] = infoBytes.size and 0xFF
        fields["L80"] = (infoBytes.size shr 8) and 0xFF
        fields["L84"] = tailBytes.size and 0xFF
        fields["L85"] = (tailBytes.size shr 8) and 0xFF
        leBytes(nowMs, 6).forEachIndexed { index, byte -> fields["L${29 + index}"] = byte }
        leBytes(DETECT_FLAGS.toLong(), 4).forEachIndexed { index, byte -> fields["L${44 + index}"] = byte }
        intArrayOf(0, 0, 0, 0).forEachIndexed { index, byte -> fields["L${40 + index}"] = byte }
        leBytes(ink, 6).forEachIndexed { index, byte -> fields["L${60 + index}"] = byte }
        leBytes(PAGE_ID.toLong(), 4).forEachIndexed { index, byte -> fields["L${67 + index}"] = byte }
        leBytes(AID.toLong(), 4).forEachIndexed { index, byte -> fields["L${71 + index}"] = byte }
        for ((name, slots) in DIGEST_CHAIN_SLOTS) {
            val digest = digests.getValue(name)
            val indices = DIGEST_CHAIN_INDICES.getValue(name)
            val canaryTriple = when (name) {
                "query" -> CANARIES[0]
                "body" -> CANARIES[1]
                else -> CANARIES[2]
            }
            val written = intArrayOf(
                digest[indices[0]],
                digest[indices[1]],
                canaryOf(digest, canaryTriple[0], canaryTriple[1], canaryTriple[2]),
            )
            slots.forEachIndexed { slot, field -> fields[field] = written[slot] }
        }

        // 版本块：Python 参数求值顺序决定噪声抽取顺序——
        // ① 第一对（内部噪声）→ ② 探针噪声 → ③ 绊线噪声 → ④ 第二对（内部噪声，low/high 被覆盖）
        val versionA = maskPair(SDK_VERSION[0], SDK_VERSION[1], rng)
        val probeRaw = (rng.random() * 240).toInt()
        val probeNoise = if (probeRaw > 109) probeRaw + probeRaw % 2 + 1 else probeRaw
        val tripwireNoise = ((rng.random() * 255).toInt() and TRIPWIRE_FREE) or TRIPWIRE_SET
        val versionB = maskPair(SDK_VERSION[2], SDK_VERSION[3], rng, overrideLow = probeNoise, overrideHigh = tripwireNoise)
        val version = versionA + versionB

        var checksum = 0
        for (b in version) checksum = checksum xor b
        for (name in FIELD_ORDER) checksum = checksum xor (fields[name] ?: 0)

        var bodyBytes = ByteArray(FIELD_ORDER.size)
        FIELD_ORDER.forEachIndexed { i, name -> bodyBytes[i] = (fields[name] ?: 0).toByte() }
        bodyBytes += jsBytes(browserInfo)
        bodyBytes += jsBytes("${(nowMs + 3) and 0xFF},")
        bodyBytes += byteArrayOf(checksum.toByte())

        // 签名头：Python 求值顺序——家族噪声（_header_noise）先抽 → 再 _mask_pair 内部噪声
        val familyBase = HEADER_NOISE_BANDS[familyOf(userAgent)] ?: 210
        val headerNoise = ((familyBase + (rng.random() * 40).toInt()) and 0xFF)
        val header = maskPair(HEADER_MAGIC[0], HEADER_MAGIC[1], rng, overrideHigh = headerNoise)

        val frame = expandNoise(bodyBytes, rng)
        val sealed = rc4(byteArrayOf(PAYLOAD_KEY.toByte()), byteArrayOf(*version.map { it.toByte() }.toByteArray()) + frame)
        return encodeBase64(byteArrayOf(*header.map { it.toByte() }.toByteArray()) + sealed, ALPHABET_S4)
    }
}

// ============================================================
// WebSign（x-secsdk-web-signature）
// ============================================================

private const val WEBSIGN_SALT = "A96D855A08C0A9707F8BEF0D9A527E4E"
const val DOUYIN_SIGNATURE_PARAM = "x-secsdk-web-signature"
const val DOUYIN_UIFID_PARAM = "uifid"
const val DOUYIN_TIMESTAMP_PARAM = "timestamp"

/** Python unquote 语义：只解 %XX（UTF-8），保留字面 '+' 与不完整转义 */
private fun percentDecode(s: String): String {
    if ('%' !in s) return s
    val out = java.io.ByteArrayOutputStream(s.length)
    var i = 0
    while (i < s.length) {
        val c = s[i]
        val hi = s.getOrNull(i + 1)
        val lo = s.getOrNull(i + 2)
        if (c == '%' && hi != null && lo != null && hi.isHexDigit() && lo.isHexDigit()) {
            out.write(((Character.digit(hi, 16) shl 4) or Character.digit(lo, 16)))
            i += 3
        } else {
            out.write(c.code)
            i++
        }
    }
    return out.toString("UTF-8")
}

private fun Char.isHexDigit(): Boolean = isDigit() || lowercase() in "abcdef"

/** WebSign 规范化：unquote 后按 SDK 百分号编码规则（safe='*-._'）重编码每个键值 */
internal fun douyinNormalizeQuery(query: String): String {
    val pairs = query.split("&").filter { it.isNotEmpty() }.map { part ->
        val idx = part.indexOf('=')
        if (idx < 0) percentDecode(part) to "" else percentDecode(part.substring(0, idx)) to percentDecode(part.substring(idx + 1))
    }
    return pairs.joinToString("&") { (name, value) ->
        "${percentEncode(name)}=${percentEncode(value)}"
    }
}

private fun percentEncode(s: String): String {
    val safe = "*-._"
    val sb = StringBuilder()
    for (b in s.toByteArray(Charsets.UTF_8)) {
        val c = b.toInt() and 0xFF
        if (c in 0x41..0x5A || c in 0x61..0x7A || c in 0x30..0x39 || safe.contains(c.toChar())) {
            sb.append(c.toChar())
        } else {
            sb.append('%')
            sb.append(String.format("%02X", c))
        }
    }
    return sb.toString()
}

/**
 * detail 接口完整签名 query 一步组装（ 实况图通道，=TikTokDownloader sign_url 流程）：
 * 规范化 → a_bogus（百分号编码后追加）→ websign 追加 uifid/timestamp/签名。
 * [nowMs] 毫秒时钟（签名与 websign 时间戳同源）；[seed] 驱动噪声。
 */
internal fun douyinSignDetailQuery(
    query: String,
    uifid: String,
    userAgent: String,
    seed: Long,
    nowMs: Long,
): String {
    val normalized = douyinNormalizeQuery(query)
    val aBogus = ABogus(userAgent, seed).getValue(normalized, nowMs)
    val withABogus = "$normalized&a_bogus=${percentEncode(aBogus)}"
    return douyinWebSign(withABogus, uifid, timestampSec = nowMs / 1000)
}

/**
 * WebSign：query 追加 uifid（缺省时）与 timestamp，签名覆盖两者、不含签名自身。
 * 每对先 unquote 再重编码（≙Python _query_pairs+_encode_pairs）。返回以
 * `&x-secsdk-web-signature={sig}` 结尾的最终 query。
 */
internal fun douyinWebSign(query: String, uifid: String, timestampSec: Long): String {
    val stamp = timestampSec.toString()
    val pairs = query.split("&").filter { it.isNotEmpty() }.map { part ->
        val idx = part.indexOf('=')
        if (idx < 0) percentDecode(part) to "" else percentDecode(part.substring(0, idx)) to percentDecode(part.substring(idx + 1))
    }.toMutableList()
    if (pairs.none { it.first == DOUYIN_UIFID_PARAM }) pairs.add(DOUYIN_UIFID_PARAM to uifid)
    pairs.add(DOUYIN_TIMESTAMP_PARAM to stamp)
    val hashed = pairs.joinToString("&") { (name, value) ->
        "${percentEncode(name)}=${percentEncode(value)}"
    }
    val preimage = "${uifid}_${stamp}_${WEBSIGN_SALT}_$hashed"
    val digest = MessageDigest.getInstance("MD5").digest(preimage.toByteArray(Charsets.UTF_8))
    val signature = digest.joinToString("") { String.format("%02x", it) }
    return "$hashed&$DOUYIN_SIGNATURE_PARAM=$signature"
}
