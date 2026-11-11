package top.linkdown.android.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BlurMaskFilter
import android.graphics.Paint as AndroidPaint
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.Instant
import java.time.ZoneId
import kotlin.math.min
import kotlin.random.Random
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/*
 * 作品阅读器——「软木板+图钉便签」（用户参考图逐元素适配，2026-11-08）：
 * 木框里一块软木板，每个文件是一张钉着的便签纸（顶部彩色图钉、左上类型徽章、
 * 缩略图+文件名+「大小 · 日期」）。图片纸点开全屏大图，视频/音频纸点击直接调起
 * 播放（复用 ShelfStore.play）。缩略图全部小尺寸降采样 IO 解码并按 uri 记忆
 * （治理 v1.0.0.11 真机「关闭点不动」的首开主线程堵塞），关闭恒为即时置空。
 */

/** 图钉六色循环（对齐参考图：红蓝绿黄青紫）。 */
private val PIN_COLORS = listOf(
    Color(0xFFE06055), Color(0xFF5B8FD9), Color(0xFF63B96F),
    Color(0xFFE8B54C), Color(0xFF4FB8A8), Color(0xFFA97BD6),
)

internal fun pinColorIndex(position: Int): Int = position.mod(PIN_COLORS.size)

/** 每张纸的微旋转角（-2°..2°，index 确定；轴心=上沿中点，不顶歪图钉对位）。 */
internal fun paperRotationDegrees(index: Int): Float =
    Random(index * 7919 + 29).nextFloat() * 4f - 2f

/** 便签缩略图来源决策核（JVM 直测，吃字段投影）：图片页=自身；视频/音频页=同 stem 图片页（封面）→ 首个图片页 → null。 */
internal fun coverSheetNameFor(pageName: String, pageKind: ShelfKind, candidates: List<Pair<String, ShelfKind>>): String? {
    if (pageKind == ShelfKind.IMAGE) return pageName
    val stem = pageName.substringBeforeLast('.')
    candidates.firstOrNull { (name, kind) -> kind == ShelfKind.IMAGE && name.substringBeforeLast('.') == stem }
        ?.let { return it.first }
    return candidates.firstOrNull { (_, kind) -> kind == ShelfKind.IMAGE }?.first
}

/** 便签缩略图来源：图片页=自身；视频/音频页=封面页（无则视频掐帧/音频占位）。 */
internal fun coverSheetFor(page: ShelfItem, pages: List<ShelfItem>): ShelfItem? {
    val target = coverSheetNameFor(page.name, page.kind, pages.map { it.name to it.kind }) ?: return null
    return pages.first { it.name == target }
}

/** 纸底日期（yyyy-MM-dd，设备时区）。 */
internal fun boardDate(dateAddedSec: Long): String =
    Instant.ofEpochSecond(dateAddedSec).atZone(ZoneId.systemDefault()).toLocalDate().toString()

@Composable
fun BookReader(entry: ShelfEntry, onDismiss: () -> Unit, onPlayPage: (ShelfItem) -> Unit) {
    val thumbCache = remember { HashMap<String, Bitmap>() }
    var viewerPage by remember { mutableStateOf<ShelfItem?>(null) }
    Box(
        Modifier
            .fillMaxSize()
            .background(Color(0xFFE8D9B8)),
    ) {
        Column(
            Modifier
                .fillMaxSize()
                // 系统条让位（v1.0.0.11 E2E 修复沿用）：覆盖层是根级兄弟，不走 screenModifier；
                // 让位只作用于板面，全屏查看器在 padding 外不受限
                .safeDrawingPadding()
                .padding(horizontal = 10.dp, vertical = 8.dp),
        ) {
            // 顶栏：木条 ← 返回 + 标题 + 共 N 项
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(Brush.verticalGradient(listOf(Color(0xFF7A5230), Color(0xFF5E3C1E))))
                    .padding(horizontal = 10.dp, vertical = 9.dp),
            ) {
                Text(
                    "←",
                    color = Color(0xFFFFF6E3), fontSize = 18.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier
                        .pixelMinTouchTarget()
                        .clickable(MutableInteractionSource(), indication = null) { onDismiss() }
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                )
                Spacer(Modifier.width(4.dp))
                Text(
                    entry.title,
                    color = Color(0xFFFFF6E3), fontSize = 14.sp, fontWeight = FontWeight.Bold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    "共 ${entry.pages.size} 项",
                    color = Color(0xFFF3D9A4), fontSize = 12.sp, fontWeight = FontWeight.Bold,
                )
            }
            Spacer(Modifier.height(8.dp))
            // 软木板：整圈木框内钉便签
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .background(Brush.verticalGradient(listOf(Color(0xFF7A5230), Color(0xFF5E3C1E))), RoundedCornerShape(16.dp))
                    .padding(5.dp),
            ) {
                Box(Modifier.fillMaxSize().clip(RoundedCornerShape(11.dp))) {
                    CorkSurface(Modifier.fillMaxSize())
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(3),
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 24.dp, bottom = 18.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalArrangement = Arrangement.spacedBy(20.dp),
                    ) {
                        itemsIndexed(entry.pages, key = { _, it -> it.uri.toString() }) { index, page ->
                            NoteCard(
                                page = page,
                                index = index,
                                pages = entry.pages,
                                cache = thumbCache,
                                onOpen = {
                                    when (page.kind) {
                                        ShelfKind.IMAGE -> viewerPage = page
                                        else -> onPlayPage(page)
                                    }
                                },
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            // 底部木搁板（无摆件，用户拍板）
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(14.dp)
                    .clip(RoundedCornerShape(7.dp))
                    .background(Brush.verticalGradient(listOf(Color(0xFF9A6B3C), Color(0xFF6E4A2A)))),
            )
        }
        // 图片纸全屏查看（点任意处关闭）
        viewerPage?.let { page -> ImageViewer(page = page, onDismiss = { viewerPage = null }) }
    }
}

/** 软木板面：暖棕底色渐变+大块晕斑+中斑+密布细砂颗粒（均匀细腻、无黑点针孔）+上缘受光+内框线（固定种子，不随重组闪变）。 */
@Composable
private fun CorkSurface(modifier: Modifier = Modifier) {
    val blotches = remember {
        val rnd = Random(0xC0FB)
        List(16) { listOf(rnd.nextFloat(), rnd.nextFloat(), 0.10f + rnd.nextFloat() * 0.10f, 0.5f + rnd.nextFloat()) }
    }
    val speckles = remember {
        val rnd = Random(0xC0FC)
        List(200) { listOf(rnd.nextFloat(), rnd.nextFloat(), rnd.nextFloat(), rnd.nextFloat()) }
    }
    // 软木砂粒噪声瓦片（一次性生成像素级颗粒图，平铺整个板面——密实明显、无稀疏黑点）
    val grainTile = remember {
        val s = 220
        val bmp = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888)
        val px = IntArray(s * s)
        val rnd = Random(0xC0FF)
        for (i in px.indices) {
            val v = rnd.nextFloat()
            val j = (rnd.nextFloat() * 30).toInt()
            px[i] = when {
                v < 0.26f -> (0x52 shl 24) or (0x6B shl 16) or ((0x45 + j) shl 8) or (0x26 + j)
                v > 0.76f -> (0x4A shl 24) or (0xE8 shl 16) or ((0xC9 - j) shl 8) or (0x95 - j)
                else -> 0
            }
        }
        bmp.setPixels(px, 0, s, 0, 0, s, s)
        bmp.asImageBitmap()
    }
    Canvas(modifier) {
        drawRect(Brush.verticalGradient(listOf(Color(0xFFCEA06A), Color(0xFFB58453))), size = size)
        // 大块晕斑（径向渐变淡出，无环纹）
        blotches.forEach { b ->
            val (fx, fy, alpha, scale) = b
            val r = size.minDimension * (0.06f + scale * 0.07f)
            drawCircle(
                brush = Brush.radialGradient(
                    listOf(Color(0x8A5A31).copy(alpha = 0.30f * alpha), Color(0x8A5A31).copy(alpha = 0f)),
                    center = Offset(fx * size.width, fy * size.height),
                    radius = r,
                ),
                radius = r,
                center = Offset(fx * size.width, fy * size.height),
            )
        }
        // 中斑（柔和深浅斑块，融入底色）
        speckles.forEach { s ->
            val (fx, fy, fr, fl) = s
            drawCircle(
                color = if (fl > 0.5f) Color(0x268A5A31) else Color(0x26D9B077),
                radius = 1.5f + fr * 3f,
                center = Offset(fx * size.width, fy * size.height),
            )
        }
        // 软木砂粒层（噪声瓦片 1.3× 平铺全板：颗粒更细更密、深浅对比更明显）
        val ts = (grainTile.width * 1.3f).toInt()
        var ty = 0
        while (ty < size.height.toInt()) {
            var tx = 0
            while (tx < size.width.toInt()) {
                drawImage(
                    image = grainTile,
                    srcOffset = IntOffset.Zero,
                    srcSize = IntSize(grainTile.width, grainTile.height),
                    dstOffset = IntOffset(tx, ty),
                    dstSize = IntSize(ts, ts),
                )
                tx += ts
            }
            ty += ts
        }
        // 上缘受光
        drawRect(
            Brush.verticalGradient(listOf(Color(0x26FFFFFF), Color.Transparent)),
            size = Size(size.width, size.height * 0.16f),
        )
        // 内框：亮线+暗线（与木框衔接）
        drawRoundRect(
            color = Color(0x33FFFFFF), cornerRadius = CornerRadius(11.dp.toPx()),
            topLeft = Offset(1.5.dp.toPx(), 1.5.dp.toPx()),
            size = Size(size.width - 3.dp.toPx(), size.height - 3.dp.toPx()),
            style = Stroke(width = 1.dp.toPx()),
        )
        drawRoundRect(
            color = Color(0x2E3A2A1A), cornerRadius = CornerRadius(11.dp.toPx()),
            topLeft = Offset(3.dp.toPx(), 3.dp.toPx()),
            size = Size(size.width - 6.dp.toPx(), size.height - 6.dp.toPx()),
            style = Stroke(width = 1.dp.toPx()),
        )
    }
}

/** 一张便签纸：图钉 + 类型徽章 + 缩略图 + 文件名/元信息 + 右下卷角。 */
@Composable
private fun NoteCard(
    page: ShelfItem,
    index: Int,
    pages: List<ShelfItem>,
    cache: MutableMap<String, Bitmap>,
    onOpen: () -> Unit,
) {
    val context = LocalContext.current
    // 缩略图来源：图片页=自身；视频/音频页=封面页（书内同 stem 图片页，无则视频掐帧/音频占位）。
    // 缓存键=来源 uri：多条视频/音频纸命中同一封面页时共享一次解码
    val source = remember(page.uri) { coverSheetFor(page, pages) }
    val cacheKey = (source?.uri ?: page.uri).toString()
    var thumb by remember(cacheKey) { mutableStateOf(cache[cacheKey]) }
    LaunchedEffect(cacheKey) {
        if (cache.containsKey(cacheKey)) {
            thumb = cache[cacheKey]
            return@LaunchedEffect
        }
        val decoded = when {
            source != null -> decodeSampled(context, source.uri, 360, 360)
            page.kind == ShelfKind.VIDEO -> videoThumb(context, page)
            else -> null
        }
        if (decoded != null) {
            cache[cacheKey] = decoded
            thumb = decoded
        }
    }
    Box(
        Modifier
            .fillMaxWidth()
            .clickable(MutableInteractionSource(), indication = null, onClick = onOpen),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .offset(y = 10.dp) // 给图钉让位
                .graphicsLayer {
                    rotationZ = paperRotationDegrees(index) // 每张纸 ±2° 微旋转（轴心=上沿中点）
                    transformOrigin = TransformOrigin(0.5f, 0f)
                }
                .drawBehind { drawPaperCard(index) }
                .padding(start = 7.dp, end = 7.dp, top = 13.dp, bottom = 8.dp), // 顶部让出钉帽压边区
        ) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f)
                    .clip(RoundedCornerShape(8.dp))
                    .background(if (page.kind == ShelfKind.AUDIO) Color(0xFFFDEBD9) else Color(0xFFEFE6CF)),
                contentAlignment = Alignment.Center,
            ) {
                val bmp = thumb
                when {
                    bmp != null -> Image(
                        bitmap = bmp.asImageBitmap(),
                        contentDescription = page.name,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                    page.kind == ShelfKind.AUDIO -> AudioFallback()
                    else -> Text("…", color = Color(0xFFB8A88A), fontSize = 18.sp, fontWeight = FontWeight.Bold)
                }
                if (page.kind == ShelfKind.VIDEO) PlayOverlay()
            }
            Spacer(Modifier.height(5.dp))
            Text(
                page.name,
                color = Color(0xFF3A2A1A), fontSize = 11.sp, fontWeight = FontWeight.Bold,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            Text(
                "${formatSize(page.sizeBytes)} · ${boardDate(page.dateAdded)}",
                color = Color(0xFF8A7A5A), fontSize = 9.sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            // 右下卷角（纸角折起的质感）
            Box(
                Modifier
                    .align(Alignment.End)
                    .offset(x = 3.dp, y = 3.dp)
                    .size(14.dp)
                    .drawBehind { drawFoldCorner() },
            )
        }
        // 类型徽章（左上，压在缩略图上）
        val (label, chipColor) = when (page.kind) {
            ShelfKind.IMAGE -> "图片" to Color(0xFF3D7EDB)
            ShelfKind.VIDEO -> "视频" to Color(0xFF5A63D8)
            ShelfKind.AUDIO -> "音频" to Color(0xFFF09A4E)
            ShelfKind.OTHER -> "文件" to Color(0xFF8A7A5A)
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .offset(x = 6.dp, y = 19.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(chipColor)
                .padding(horizontal = 5.dp, vertical = 2.dp),
        ) {
            PixelIcon(
                when (page.kind) {
                    ShelfKind.VIDEO -> Sprites.CAMERA
                    ShelfKind.AUDIO -> Sprites.NOTE
                    ShelfKind.OTHER -> Sprites.BOX
                    else -> Sprites.IMAGE
                },
                Color(0xFFFFFFFF), accent = Color(0xFFFFFFFF), iconSize = 9.dp,
            )
            Spacer(Modifier.width(3.dp))
            Text(label, color = Color(0xFFFFFFFF), fontSize = 10.sp, fontWeight = FontWeight.Bold)
        }
        // 图钉（顶部中央，钉帽压住纸张上沿：钉帽中心对齐撕边沿，下半重叠纸沿）
        PushPin(
            color = PIN_COLORS[pinColorIndex(index)],
            modifier = Modifier
                .align(Alignment.TopCenter)
                .offset(y = 3.5.dp),
        )
    }
}

/**
 * 便签纸体（平整暖白卡纸，对齐用户参考图）：
 * 边缘平直、四角微圆、每边 ≤0.6dp 平滑微起伏（非锯齿/邮票/撕纸），BlurMaskFilter 柔和投影，
 * 低对比纸纹斑点+短纤维丝，暖白纸色。右下卷角由 drawFoldCorner 负责。
 * index 决定微起伏与纸纹——每张纸各不相同。
 */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawPaperCard(index: Int) {
    val w = size.width
    val h = size.height
    val r = 3.dp.toPx()
    val rnd = Random(index * 7919 + 13)
    fun bow(): Float = (rnd.nextFloat() - 0.5f) * 1.2.dp.toPx()
    val bTop = bow()
    val bEnd = bow()
    val bBottom = bow()
    val bStart = bow()
    fun paperPath(): Path = Path().apply {
        moveTo(r, 0f)
        quadraticBezierTo(w / 2, bTop, w - r, 0f)
        quadraticBezierTo(w, 0f, w, r)
        quadraticBezierTo(w + bEnd, h / 2, w, h - r)
        quadraticBezierTo(w, h, w - r, h)
        quadraticBezierTo(w / 2, h + bBottom, r, h)
        quadraticBezierTo(0f, h, 0f, h - r)
        quadraticBezierTo(bStart, h / 2, 0f, r)
        quadraticBezierTo(0f, 0f, r, 0f)
        close()
    }
    // 柔和投影（BlurMaskFilter 模糊软晕双层，光源左上→影落右下）
    fun softShadow(dx: Float, dy: Float, argb: Int, blur: Float) {
        drawIntoCanvas { c ->
            val paint = AndroidPaint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                color = argb
                maskFilter = BlurMaskFilter(blur, BlurMaskFilter.Blur.NORMAL)
            }
            c.save()
            c.translate(dx, dy)
            c.nativeCanvas.drawPath(paperPath().asAndroidPath(), paint)
            c.restore()
        }
    }
    softShadow(2.5f.dp.toPx(), 4f.dp.toPx(), 0x332B1A0E, 3f.dp.toPx())
    softShadow(5f.dp.toPx(), 8f.dp.toPx(), 0x1F2B1A0E, 5.5f.dp.toPx())
    // 纸体（暖白：上近白下米白渐变）+ 淡描边
    drawPath(
        paperPath(),
        brush = Brush.verticalGradient(listOf(Color(0xFFFFFEF8), Color(0xFFFBF1DB))),
    )
    drawPath(paperPath(), color = Color(0xFFE4D5B4), style = Stroke(width = 1.dp.toPx()))
    // 细腻纸纹（低对比斑点+短纤维丝，藏在缩略图/文字层下）
    repeat(26) {
        drawCircle(
            Color(0x122B1A0E),
            radius = (0.4f + rnd.nextFloat() * 0.7f).dp.toPx(),
            center = Offset(rnd.nextFloat() * w, rnd.nextFloat() * h),
        )
    }
    repeat(7) {
        val x = rnd.nextFloat() * w
        val y = rnd.nextFloat() * h
        drawLine(
            Color(0x0E2B1A0E),
            Offset(x, y),
            Offset(x + (rnd.nextFloat() - 0.5f) * 14.dp.toPx(), y + (rnd.nextFloat() - 0.5f) * 4.dp.toPx()),
            strokeWidth = 0.8.dp.toPx(),
        )
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawFoldCorner() {
    val w = size.width
    // 卷角投影（渐隐）
    drawPath(
        Path().apply { moveTo(w, 0f); lineTo(0f, w); lineTo(w, w); close() },
        brush = Brush.radialGradient(
            listOf(Color(0x472B1A0E), Color(0x002B1A0E)),
            center = Offset(w, w), radius = w,
        ),
    )
    // 折起的纸角（上亮下暗两面）
    drawPath(
        Path().apply { moveTo(w * 0.10f, 0f); lineTo(w, w * 0.90f); lineTo(w, 0f); close() },
        brush = Brush.linearGradient(listOf(Color(0xFFFCF6E6), Color(0xFFE4D5B4))),
    )
    // 折缘
    drawPath(
        Path().apply { moveTo(w * 0.10f, 0f); lineTo(w, w * 0.90f) },
        color = Color(0xFFC9B88C),
        style = Stroke(width = 1.dp.toPx()),
    )
}

/** 播放圆钮（视频纸中央）。 */
@Composable
private fun PlayOverlay() {
    Box(
        Modifier
            .size(34.dp)
            .clip(CircleShape)
            .background(Color(0x992B1A0E)),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(14.dp)) {
            val w = size.width
            val h = size.height
            drawPath(
                Path().apply {
                    moveTo(w * 0.22f, 0f); lineTo(w, h * 0.5f); lineTo(w * 0.22f, h); close()
                },
                color = Color(0xFFFFF6E3),
            )
        }
    }
}

/** 音频占位插画：桃色底+音符+波形条（无封面文件时）。 */
@Composable
private fun AudioFallback() {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text("♪", color = Color(0xFFF09A4E), fontSize = 26.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(4.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            val bars = remember { listOf(0.4f, 0.7f, 1f, 0.55f, 0.85f, 0.5f, 0.75f, 0.35f) }
            bars.forEach { h ->
                Box(
                    Modifier
                        .padding(horizontal = 1.5.dp)
                        .width(3.dp)
                        .height((6 + h * 16).dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(Color(0xFFF09A4E).copy(alpha = 0.5f)),
                )
            }
        }
    }
}

/**
 * 工字钉（立体四段式，对齐真实工字钉照片）：宽圆盘钉帽（带厚度底缘+顶亮底暗+高光斑）+ 短粗筒身 + 圆鼓底肩 + 细金属针没入针孔；
 * 钉帽压住纸张上沿（下半重叠纸沿），针尖扎进纸面不悬空，投影随纸张光源落右下。
 * 层级：软木板 < 纸张 < 工字钉（图钉整体画在纸前面）。
 */
@Composable
private fun PushPin(color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier.size(width = 26.dp, height = 24.dp)) {
        val cx = size.width / 2
        // 钉体投在纸面上的柔和影子（光源左上→影子偏右下，与纸张投影方向一致）
        drawOval(
            brush = Brush.radialGradient(listOf(Color(0x3D2B1A0E), Color(0x002B1A0E))),
            topLeft = Offset(cx - 6.5.dp.toPx(), 10.5.dp.toPx()),
            size = Size(14.dp.toPx(), 5.5.dp.toPx()),
        )
        // 金属针（上粗下略细，尖端没入针孔，针杆不悬空）
        drawPath(
            Path().apply {
                moveTo(cx - 0.85.dp.toPx(), 16.dp.toPx())
                lineTo(cx + 0.85.dp.toPx(), 16.dp.toPx())
                lineTo(cx + 0.55.dp.toPx(), 19.5.dp.toPx())
                lineTo(cx - 0.55.dp.toPx(), 19.5.dp.toPx())
                close()
            },
            brush = Brush.horizontalGradient(listOf(Color(0xFFE9EBEF), Color(0xFF8F939C))),
        )
        // 短筒身（钉帽与底肩之间的粗短圆柱，左亮右暗塑立体）
        drawPath(
            Path().apply {
                moveTo(cx - 4.dp.toPx(), 7.5.dp.toPx())
                lineTo(cx + 4.dp.toPx(), 7.5.dp.toPx())
                lineTo(cx + 3.5.dp.toPx(), 12.5.dp.toPx())
                lineTo(cx - 3.5.dp.toPx(), 12.5.dp.toPx())
                close()
            },
            brush = Brush.horizontalGradient(listOf(lighten(color, 0.2f), color, darken(color, 0.42f))),
        )
        // 圆鼓底肩（针座，比筒身宽的扁圆鼓，底部暗）
        drawOval(
            brush = Brush.verticalGradient(listOf(lighten(color, 0.25f), color, darken(color, 0.45f))),
            topLeft = Offset(cx - 5.5.dp.toPx(), 11.5.dp.toPx()),
            size = Size(11.dp.toPx(), 5.5.dp.toPx()),
        )
        drawOval(
            brush = Brush.radialGradient(
                listOf(Color(0x66FFFFFF), Color(0x00FFFFFF)),
                center = Offset(cx - 2.2.dp.toPx(), 13.dp.toPx()),
                radius = 3.5.dp.toPx(),
            ),
            topLeft = Offset(cx - 5.5.dp.toPx(), 11.5.dp.toPx()),
            size = Size(11.dp.toPx(), 5.5.dp.toPx()),
        )
        // 针孔（针尖没入纸面的小凹点）
        drawOval(
            color = Color(0x4D2B1A0E),
            topLeft = Offset(cx - 2.2.dp.toPx(), 18.2.dp.toPx()),
            size = Size(4.4.dp.toPx(), 2.dp.toPx()),
        )
        drawOval(
            color = Color(0x732B1A0E),
            topLeft = Offset(cx - 1.3.dp.toPx(), 18.5.dp.toPx()),
            size = Size(2.6.dp.toPx(), 1.3.dp.toPx()),
        )
        // 钉帽底缘厚度（暗色椭圆下移 1.3dp，露出一弯月牙=盘厚）
        drawOval(
            color = darken(color, 0.42f),
            topLeft = Offset(cx - 9.dp.toPx(), 1.3.dp.toPx()),
            size = Size(18.dp.toPx(), 10.dp.toPx()),
        )
        // 钉帽盘面（饱满圆盘：顶亮底暗+左上高光带+亮核+缘线）
        drawOval(
            brush = Brush.verticalGradient(listOf(lighten(color, 0.5f), color, darken(color, 0.25f))),
            topLeft = Offset(cx - 9.dp.toPx(), 0f),
            size = Size(18.dp.toPx(), 10.dp.toPx()),
        )
        drawOval(
            brush = Brush.radialGradient(
                listOf(Color(0x8CFFFFFF), Color(0x00FFFFFF)),
                center = Offset(cx - 3.5.dp.toPx(), 2.8.dp.toPx()),
                radius = 5.dp.toPx(),
            ),
            topLeft = Offset(cx - 9.dp.toPx(), 0f),
            size = Size(18.dp.toPx(), 10.dp.toPx()),
        )
        drawOval(
            brush = Brush.radialGradient(
                listOf(Color(0xB3FFFFFF), Color(0x00FFFFFF)),
                center = Offset(cx - 3.dp.toPx(), 2.2.dp.toPx()),
                radius = 2.2.dp.toPx(),
            ),
            topLeft = Offset(cx - 9.dp.toPx(), 0f),
            size = Size(18.dp.toPx(), 10.dp.toPx()),
        )
        drawOval(
            color = darken(color, 0.45f),
            topLeft = Offset(cx - 9.dp.toPx(), 0f),
            size = Size(18.dp.toPx(), 10.dp.toPx()),
            style = Stroke(width = 1.dp.toPx()),
        )
    }
}

private fun lighten(c: Color, f: Float): Color = c.copy(alpha = f).compositeOver(Color(0xFFFFFFFF))

private fun darken(c: Color, f: Float): Color = c.copy(alpha = f).compositeOver(Color(0xFF000000))

/** 全屏大图（图片纸点开；点任意处关闭）。 */
@Composable
private fun ImageViewer(page: ShelfItem, onDismiss: () -> Unit) {
    val context = LocalContext.current
    var bitmap by remember(page.uri) { mutableStateOf<Bitmap?>(null) }
    var failed by remember(page.uri) { mutableStateOf(false) }
    LaunchedEffect(page.uri) {
        val metrics = context.resources.displayMetrics
        bitmap = decodeSampled(context, page.uri, metrics.widthPixels, metrics.heightPixels)
        if (bitmap == null) failed = true
    }
    Box(
        Modifier
            .fillMaxSize()
            .background(Color(0xF2100A04))
            .clickable(MutableInteractionSource(), indication = null) { onDismiss() },
        contentAlignment = Alignment.Center,
    ) {
        val bmp = bitmap
        when {
            bmp != null -> Image(
                bitmap = bmp.asImageBitmap(),
                contentDescription = page.name,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize(),
            )
            failed -> Text("加载失败", color = Color(0xFFC9B88C), fontSize = 13.sp)
            else -> Text("加载中…", color = Color(0xFFC9B88C), fontSize = 13.sp)
        }
        // 可见关闭钮（spec §6.1）：48×48 热区；「点任意处关闭」保留——内层 clickable 消费事件不冒泡
        Box(
            Modifier
                .align(Alignment.TopEnd)
                .statusBarsPadding()
                .padding(top = 8.dp, end = 8.dp)
                .background(Color(0x66FFF6E3), RoundedCornerShape(8.dp))
                .border(1.dp, Color(0x591E0F06), RoundedCornerShape(8.dp))
                .pixelMinTouchTarget()
                .clickable(MutableInteractionSource(), indication = null) { onDismiss() }
                .semantics { contentDescription = "关闭" },
            contentAlignment = Alignment.Center,
        ) {
            Text("×", color = Color(0xE61E0F06), fontSize = 18.sp, fontWeight = FontWeight.Bold)
        }
    }
}

/** 目标尺寸降采样解码（缩略图 360px/查看器全屏，均 IO 线程，失败 null）。 */
internal suspend fun decodeSampled(context: Context, uri: Uri, reqW: Int, reqH: Int): Bitmap? =
    withContext(Dispatchers.IO) {
        runCatching {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@runCatching null
            var sample = 1
            while (bounds.outWidth / (sample * 2) >= reqW && bounds.outHeight / (sample * 2) >= reqH) sample *= 2
            val opts = BitmapFactory.Options().apply { inSampleSize = sample }
            context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
                ?.let { scaledDown(it, maxOf(reqW, reqH)) }
        }.getOrNull()
    }

/** 视频掐帧缩略图（30% 处，缩到 360px，IO 线程，失败 null）。 */
private suspend fun videoThumb(context: Context, page: ShelfItem): Bitmap? =
    withContext(Dispatchers.IO) {
        runCatching {
            val r = MediaMetadataRetriever()
            try {
                r.setDataSource(context, page.uri)
                val durMs = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
                val frame = if (durMs > 0) {
                    r.getFrameAtTime(durMs * 1000L * 30 / 100, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                } else {
                    null
                }
                frame?.let { scaledDown(it, 360) }
            } finally {
                r.release()
            }
        }.getOrNull()
    }

private fun scaledDown(src: Bitmap, maxPx: Int): Bitmap {
    val longest = maxOf(src.width, src.height)
    if (longest <= maxPx) return src
    val s = maxPx.toFloat() / longest
    return Bitmap.createScaledBitmap(src, (src.width * s).toInt().coerceAtLeast(1), (src.height * s).toInt().coerceAtLeast(1), true)
}
