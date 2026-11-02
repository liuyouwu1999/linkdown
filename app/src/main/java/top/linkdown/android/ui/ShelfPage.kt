package top.linkdown.android.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.layout.ContentScale
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 货架页（设计）：细棚条 + 头部（返回｜标题居中｜排序+列表/书架切换右对齐）。
 * 列表模式=圆角行卡；书架模式=像素木质书柜分页展示（每页 3 层搁板 × 3 本）。
 * 行/书=播放，🗑/长按=确认后删除；**长按进多选**（批量下架，底部操作条）；排序循环 按时间→按名称→按类别。
 */
@Composable
fun ShelfScreen(
    items: List<ShelfItem>,
    onPlay: (ShelfItem) -> Unit,
    onDelete: (ShelfItem) -> Unit,
    onBatchDelete: (List<ShelfItem>) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    shelfBoardMode: Boolean = false,
    onToggleShelfBoardMode: () -> Unit = {},
) {
    val c = PixelTheme.colors
    var pendingDelete by remember { mutableStateOf<ShelfItem?>(null) }
    var sortMode by remember { mutableStateOf(ShelfSort.TIME) }
    val sortedItems = remember(items, sortMode) { sortShelfItems(items, sortMode) }
 // —— 多选批量下架（长按书/行进入；下架=删文件，故批量也有确认框）——
    var selecting by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf(emptySet<String>()) }
    var confirmBatch by remember { mutableStateOf(false) }
    val selectedItems = sortedItems.filter { it.uri.toString() in selected }
    Column(modifier = modifier.fillMaxSize().background(c.background)) {
 // 细棚条（同主页遮阳棚，矮版 10dp 含 3dp 底缘）
        Box(
            Modifier
                .fillMaxWidth()
                .height(10.dp)
                .drawBehind {
                    val h = size.height - 3.dp.toPx()
                    val stripe = 18.dp.toPx()
                    var x = 0f; var i = 0
                    while (x < size.width) {
                        drawRect(if (i % 2 == 0) c.primary else c.awningCream, topLeft = Offset(x, 0f), size = androidx.compose.ui.geometry.Size(stripe, h))
                        x += stripe; i++
                    }
                    drawRect(c.outline, topLeft = Offset(0f, h), size = androidx.compose.ui.geometry.Size(size.width, 3.dp.toPx()))
                },
        )
 // 头部：返回（左）+ 标题（剩余空间内居中）+ 排序/切换（右对齐）——三段 Row，标题不会压到按钮
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFF4A2E18))
                .drawBehind {
 // 柜顶横梁：下沿一圈受光木边 + 一道深缝，和下面的柜身接上
                    drawRect(
                        Color(0xFF8A5A31).copy(alpha = 0.75f),
                        topLeft = Offset(0f, size.height - 2.dp.toPx()),
                        size = Size(size.width, 1.dp.toPx()),
                    )
                    drawRect(
                        Color(0xFF1E0F06),
                        topLeft = Offset(0f, size.height - 1.dp.toPx()),
                        size = Size(size.width, 1.dp.toPx()),
                    )
                }
                .padding(horizontal = 10.dp, vertical = 9.dp),
        ) {
            Text(
                "← 返回",
                color = c.awningCream, fontSize = 14.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .clickable(remember { MutableInteractionSource() }, indication = null) { onBack() }
                    .padding(horizontal = 4.dp, vertical = 6.dp),
            )
            Spacer(Modifier.weight(1f))
            Text("货架 · 全部 ${items.size} 件", color = c.awningCream, fontSize = 14.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.weight(1f))
            if (selecting) {
 // 多选态：排序/切换退位给「取 消」（选择态下点书/行只做勾选）
                ShelfPixelButton(
                    "取 消", container = Color(0xFF2E8B62),
                    onClick = { selecting = false; selected = emptySet() },
                    modifier = Modifier.width(78.dp),
                )
            } else {
 // 排序：循环切换 时间→名称→类别
                ShelfPixelButton(
                    sortMode.label, container = Color(0xFFC9731E),
                    onClick = { sortMode = sortMode.next() },
                    modifier = Modifier.width(78.dp),
                )
                Spacer(Modifier.width(6.dp))
 // 展示模式切换：列表 ⇄ 书架
                ShelfPixelButton(
                    if (shelfBoardMode) "列表" else "书架", container = Color(0xFF2E8B62),
                    onClick = onToggleShelfBoardMode,
                    modifier = Modifier.width(78.dp),
                )
            }
        }
 // 内容区（weight 1f 给底部操作条让位）
        Column(Modifier.fillMaxWidth().weight(1f)) {
            if (shelfBoardMode) {
                ShelfBoard(
                    sortedItems, onPlay = onPlay,
                    selecting = selecting,
                    isSelected = { it.uri.toString() in selected },
                    onToggle = { item -> selected = if (item.uri.toString() in selected) selected - item.uri.toString() else selected + item.uri.toString() },
                    onEnterSelection = { item -> selecting = true; selected = selected + item.uri.toString() },
                )
            } else if (items.isEmpty()) {
 // 空态（列表模式；书架模式的空柜仍带整面架子，见 ShelfBoard）
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                    modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
                ) {
                    PixelIcon(Sprites.BOX, c.panelShadow, accent = c.inset, iconSize = 56.dp)
                    Spacer(Modifier.height(12.dp))
                    Text("货架空空如也，先去进点货", color = c.textSecondary, fontSize = 14.sp)
                }
            } else {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(top = 4.dp, bottom = 16.dp),
                ) {
                    items(sortedItems) { item ->
                        ShelfRow(
                            item, onPlay = onPlay, onDelete = { pendingDelete = item },
                            selecting = selecting,
                            selected = item.uri.toString() in selected,
                            onToggle = { selected = if (item.uri.toString() in selected) selected - item.uri.toString() else selected + item.uri.toString() },
                            onEnterSelection = { selecting = true; selected = selected + item.uri.toString() },
                        )
                    }
                }
            }
        }
 // 底部操作条（多选态）
        if (selecting) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF4A2E18))
                    .padding(horizontal = 10.dp, vertical = 8.dp),
            ) {
                Text(
                    "已选 ${selected.size} 件",
                    color = c.awningCream, fontSize = 12.sp, fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.weight(1f))
                ShelfPixelButton(
                    "全 选", container = Color(0xFFC9731E), fontSize = 12.sp, height = 30.dp,
                    onClick = { selected = sortedItems.map { it.uri.toString() }.toSet() },
                    modifier = Modifier.width(64.dp),
                )
                Spacer(Modifier.width(6.dp))
                ShelfPixelButton(
                    "下架所选", container = Color(0xFFB03240), fontSize = 12.sp, height = 30.dp,
                    enabled = selected.isNotEmpty(),
                    onClick = { confirmBatch = true },
                    modifier = Modifier.width(88.dp),
                )
                Spacer(Modifier.width(6.dp))
                ShelfPixelButton(
                    "完 成", container = Color(0xFF2E8B62), fontSize = 12.sp, height = 30.dp,
                    onClick = { selecting = false; selected = emptySet() },
                    modifier = Modifier.width(64.dp),
                )
            }
        }
    }
    pendingDelete?.let { item ->
        PixelConfirmDialog(
            title = "下架这件货？",
            message = "「${item.name}」将从下载目录删除，不可恢复。",
            confirmText = "删 除",
            onConfirm = { onDelete(item); pendingDelete = null },
            onDismiss = { pendingDelete = null },
        )
    }
    if (confirmBatch) {
        PixelConfirmDialog(
            title = "下架所选 ${selectedItems.size} 件？",
            message = "所选内容将从下载目录删除，不可恢复。",
            confirmText = "删 除",
            onConfirm = {
                onBatchDelete(selectedItems)
                selecting = false
                selected = emptySet()
                confirmBatch = false
            },
            onDismiss = { confirmBatch = false },
        )
    }
}

/** 货架排序方式：label 用于头部循环按钮（两字省宽）；TIME=入库原序（MediaStore 日期倒序） */
enum class ShelfSort(val label: String) {
    TIME("时间"),
    NAME("名称"),
    KIND("类别"),
    ;

    fun next(): ShelfSort = entries[(ordinal + 1) % entries.size]
}

/**
 * 排序核心纯函数（JVM 直测，只吃名称/类别键——绕开 JVM 测试无法构造 android Uri 的问题）：
 * 返回排序后的原下标序列。名称按小写比较；类别按 视频→音频→图片→文件 分组、组内按名称；同键稳定保序。
 */
internal fun shelfSortedIndices(names: List<String>, kinds: List<ShelfKind>, mode: ShelfSort): List<Int> =
    when (mode) {
        ShelfSort.TIME -> names.indices.toList()
        ShelfSort.NAME -> names.indices.sortedBy { names[it].lowercase() }
        ShelfSort.KIND -> names.indices.sortedWith(compareBy({ kinds[it].ordinal }, { names[it].lowercase() }))
    }

/** 排序纯函数：TIME=入库原序（MediaStore 日期倒序） */
fun sortShelfItems(items: List<ShelfItem>, mode: ShelfSort): List<ShelfItem> =
    shelfSortedIndices(items.map { it.name }, items.map { it.kind }, mode).map { items[it] }

/**
 * 书架页专用像素按钮（参考图工具栏风格）：小圆角 + 深描边 + 顶部内亮边 + 右下硬投影，按下下沉。
 * 仅书架页使用——不改全局 PixelButton，避免波及其他页面。
 */
@Composable
private fun ShelfPixelButton(
    text: String,
    container: Color,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    fontSize: TextUnit = 13.sp,
    height: Dp = 38.dp,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(6.dp)
    val outline = Color(0xFF4A3422)
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val sink = if (pressed && enabled) 2.dp else 0.dp
    Box(
        modifier = modifier
            .height(height)
            .drawBehind {
 // 右下硬投影（禁用态无投影，呈灰陷感）
                if (enabled) {
                    drawRect(Color(0x80382619), Offset(2.dp.toPx(), 3.dp.toPx()), Size(size.width, size.height))
                }
            }
            .offset(y = sink)
            .background(if (enabled) container else Color(0xFFC9B88C), shape)
            .border(2.dp, outline, shape)
            .clickable(interaction, indication = null, enabled = enabled) { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        if (enabled) {
 // 顶部内亮边
            Box(
                Modifier
                    .matchParentSize()
                    .drawBehind {
                        drawRect(
                            Color(0x59FFFFFF),
                            topLeft = Offset(3.dp.toPx(), 2.dp.toPx()),
                            size = Size(size.width - 6.dp.toPx(), 2.dp.toPx()),
                        )
                    },
            )
        }
        Text(
            text = text,
            color = if (enabled) Color(0xFFFFF6E3) else Color(0xFF6E5F48),
            fontSize = fontSize,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
        )
    }
}

@Composable
private fun ShelfRow(
    item: ShelfItem,
    onPlay: (ShelfItem) -> Unit,
    onDelete: (ShelfItem) -> Unit,
    selecting: Boolean,
    selected: Boolean,
    onToggle: () -> Unit,
    onEnterSelection: () -> Unit,
) {
    val c = PixelTheme.colors
    val shape = RoundedCornerShape(8.dp)
    val blockColor = when (item.kind) {
        ShelfKind.VIDEO -> c.primary
        ShelfKind.AUDIO -> c.success
        ShelfKind.IMAGE -> c.gold
        ShelfKind.OTHER -> c.inset
    }
    val blockIcon = when (item.kind) {
        ShelfKind.VIDEO -> Sprites.CAMERA
        ShelfKind.AUDIO -> Sprites.NOTE
        ShelfKind.IMAGE -> Sprites.IMAGE
        ShelfKind.OTHER -> Sprites.BOX
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .background(c.surface, shape)
            .border(if (selected) 3.dp else 2.dp, if (selected) c.primary else c.outline, shape)
            .pointerInput(item.uri, selecting) {
                detectTapGestures(
                    onTap = { if (selecting) onToggle() else onPlay(item) },
                    onLongPress = { if (!selecting) onEnterSelection() },
                )
            }
            .padding(8.dp),
    ) {
 // 多选态：行首勾选框
        if (selecting) {
            Box(
                Modifier
                    .size(22.dp)
                    .background(if (selected) c.success else c.surface, RoundedCornerShape(4.dp))
                    .border(2.dp, if (selected) c.success else c.outline, RoundedCornerShape(4.dp)),
                contentAlignment = Alignment.Center,
            ) {
                if (selected) Text("✓", color = c.onPrimary, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.width(9.dp))
        }
 // 色块缩略
        Box(
            Modifier
                .size(width = 34.dp, height = 26.dp)
                .background(blockColor, RoundedCornerShape(4.dp))
                .border(1.dp, c.outline, RoundedCornerShape(4.dp)),
            contentAlignment = Alignment.Center,
        ) {
            PixelIcon(blockIcon, c.background, accent = c.surface, iconSize = 16.dp)
        }
        Spacer(Modifier.width(9.dp))
        Column(Modifier.weight(1f)) {
            Text(item.name, color = c.textPrimary, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("${item.platform} · ${formatSize(item.sizeBytes)}", color = c.textSecondary, fontSize = 11.sp)
        }
        Spacer(Modifier.width(8.dp))
 // 多选态隐藏 ▶/🗑（点行即勾选；单条删除走批量流或退出多选后用 🗑）
        if (!selecting) {
 // 橙底 ▶ 圆角小钮
            val playShape = RoundedCornerShape(5.dp)
            Box(
                Modifier
                    .background(c.primary, playShape)
                    .border(2.dp, c.outline, playShape)
                    .clickable(remember { MutableInteractionSource() }, indication = null) { onPlay(item) }
                    .padding(horizontal = 9.dp, vertical = 6.dp),
            ) {
                PixelIcon(Sprites.PLAY, c.onPrimary, iconSize = 12.dp)
            }
            Spacer(Modifier.width(6.dp))
 // 🗑 小钮（像素垃圾桶）
            val trashShape = RoundedCornerShape(5.dp)
            Box(
                Modifier
                    .background(c.error, trashShape)
                    .border(2.dp, c.outline, trashShape)
                    .clickable(remember { MutableInteractionSource() }, indication = null) { onDelete(item) }
                    .padding(horizontal = 6.dp, vertical = 6.dp),
            ) {
                PixelIcon(Sprites.TRASH, c.onPrimary, iconSize = 12.dp)
            }
        }
    }
}

/* ==================== 书架模式：木质货柜（书盒按参考图重做） ==================== */

/** 书架每页层数（每层 3 本 → 9 本/页）；书多时翻到下一个书架 */
private const val SHELF_PAGE_ROWS = 3
private const val BOOKS_PER_ROW = 3

/** 搁板厚度 / 柜侧立柱宽 / 书左右留白 / 书间基础缝（360dp 基准下的固定木作尺寸） */
private val SHELF_BOARD_H = 20.dp
private val SHELF_POST_W = 10.dp
private val SHELF_SIDE_INSET = 14.dp
private val SHELF_GAP = 10.dp

/** 书距上限：富余宽度分进缝里超过此值就不再拉大（防书太少时两本吊在搁板两头）。 */
private val MAX_BOOK_GAP = 32.dp

/**
 * 一排书的书距（纯函数，JVM 直测）：把排内富余宽度均分进书间缝，整排撑满搁板（产品 2026-10-22
 * 拍板「右边还有空间往右放放」）；单本无缝可言返回基础缝；富余过大被 [MAX_BOOK_GAP] 封顶，
 * 剩余留在右侧——不会像无限均分那样把两本书推到搁板两端中间留大洞（产品评审已否）。
 */
internal fun bookRowGap(count: Int, rowWidth: Dp, bookWidth: Dp): Dp {
    if (count <= 1) return SHELF_GAP
    val leftover = rowWidth - bookWidth * count - SHELF_GAP * (count - 1)
    return (SHELF_GAP + leftover / (count - 1)).coerceIn(SHELF_GAP, MAX_BOOK_GAP)
}

/** 书盒比例（高 / 宽）：参考图磁带盒 1:1.6，比原来的 1:1.79 矮一点，更像货品 */
private const val BOOK_RATIO = 1.67f

/** 书顶书页取色：纸面 / 页纹线——书顶不是一块板，是一叠页子的边。 */
private val PAGE_PAPER = Color(0xFFF1E2BC)
private val PAGE_LINE = Color(0xFFCDB787)

/**
 * 书盒六套配色（参考图取色，整体压暗降饱和）：外描边 / 盒体 / 书脊 / 底部信息带 / 高光。
 * 按全局位置索引稳定轮换，同一本书不会因翻页换色。
 */
private data class ShelfBookPalette(
    val outline: Color,
    val main: Color,
    val spine: Color,
    val footer: Color,
    val highlight: Color,
)

private val shelfBookPalettes = listOf(
    ShelfBookPalette(Color(0xFF3D1C06), Color(0xFFD06A17), Color(0xFFA84A0D), Color(0xFFB2540F), Color(0xFFFFC072)), // 橙
    ShelfBookPalette(Color(0xFF0C2A1D), Color(0xFF1F7A5B), Color(0xFF14624A), Color(0xFF1A6C51), Color(0xFF8FE0BE)), // 绿
    ShelfBookPalette(Color(0xFF300C11), Color(0xFFB03240), Color(0xFF8E2531), Color(0xFF992A36), Color(0xFFFFA6A2)), // 暗红
    ShelfBookPalette(Color(0xFF0B1B2E), Color(0xFF2F61A6), Color(0xFF21488A), Color(0xFF27528F), Color(0xFFA6CDF5)), // 蓝
    ShelfBookPalette(Color(0xFF2F2004), Color(0xFFC98A20), Color(0xFFA56E12), Color(0xFFB67C18), Color(0xFFF8DA8A)), // 金
    ShelfBookPalette(Color(0xFF06252C), Color(0xFF2E7C8C), Color(0xFF1E5C69), Color(0xFF226874), Color(0xFF93DCE4)), // 青
)

/** 木质货柜取色（参考图：暖棕柜背 + 浅金搁板面 + 深棕投影） */
private object Cabinet {
    val outline = Color(0xFF2A1508)
    val frame = Color(0xFF4E3018)
    val frameHi = Color(0xFF8A5A31)
    val wallTop = Color(0xFF71441F)
    val wallMid = Color(0xFF62391B)
    val wallBottom = Color(0xFF4E2C13)
    val grainHi = Color(0xFF9A6636)
    val grainLo = Color(0xFF3E230E)
    val plankTop = Color(0xFFD89A52)
    val plankNosing = Color(0xFFF9CE92)
    val plankFrontHi = Color(0xFFC98D52)
    val plankFront = Color(0xFFA56D38)
    val plankFrontLo = Color(0xFF7A4E29)
    val plankUnder = Color(0xFF38200F)
    val cream = Color(0xFFFFF1D2)
}

/**
 * 书架视图：整面木柜（柜背木纹 + 左右立柱 + 三层厚搁板），书籍按 3 本一层紧排在搁板上。
 * 分页展示，横滑/按钮翻页；空层照画搁板，空柜提示照旧。
 * 点击=播放、长按=进多选（批量下架；多选态点书勾选）——pointerInput 手势：combinedClickable
 * 在此组合层级不响应，勿改回。
 */
@Composable
private fun ShelfBoard(
    shelfItems: List<ShelfItem>,
    onPlay: (ShelfItem) -> Unit,
    selecting: Boolean,
    isSelected: (ShelfItem) -> Boolean,
    onToggle: (ShelfItem) -> Unit,
    onEnterSelection: (ShelfItem) -> Unit,
) {
    val perPage = SHELF_PAGE_ROWS * BOOKS_PER_ROW
    val pages = remember(shelfItems) {
        if (shelfItems.isEmpty()) listOf(emptyList()) else shelfItems.chunked(perPage)
    }
    var page by remember { mutableStateOf(0) }
    LaunchedEffect(pages.size) { if (page >= pages.size) page = 0 }

    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .background(Cabinet.wallBottom),
    ) {
        val showPager = pages.size > 1
        val footerH = if (showPager) 34.dp else 0.dp
        val rowH = (maxHeight - footerH) / SHELF_PAGE_ROWS
 // 书宽按 3 本紧排算，书高受层高限制（窄屏自动缩书，不裁字）
        val slotW = (maxWidth - (SHELF_POST_W + SHELF_SIDE_INSET) * 2 - SHELF_GAP * 2) / BOOKS_PER_ROW
        val bookH = minOf(slotW * BOOK_RATIO, rowH - 24.dp) * 0.92f // 整体缩小一圈，拉开书距
        val bookW = bookH / BOOK_RATIO
        val rowUsableWidth = maxWidth - (SHELF_POST_W + SHELF_SIDE_INSET) * 2 // 书距铺排用的排内可用宽

        Box(
            Modifier
                .fillMaxSize()
                .drawBehind {
                    drawCabinet(
                        rowH = rowH.toPx(),
                        boardH = SHELF_BOARD_H.toPx(),
                        postW = SHELF_POST_W.toPx(),
                        rows = SHELF_PAGE_ROWS,
                        footerH = footerH.toPx(),
                    )
                }
                .pointerInput(pages) {
                    var acc = 0f
                    detectHorizontalDragGestures(
                        onDragEnd = {
                            if (acc < -140f && page < pages.size - 1) page++
                            else if (acc > 140f && page > 0) page--
                            acc = 0f
                        },
                    ) { _, drag -> acc += drag }
                },
        ) {
            AnimatedContent(
                targetState = page,
                transitionSpec = {
                    val dir = if (targetState > initialState) 1 else -1
                    (slideInHorizontally { it * dir } + fadeIn()) togetherWith
                        (slideOutHorizontally { -it * dir } + fadeOut())
                },
                modifier = Modifier.fillMaxSize(),
                label = "shelfPage",
            ) { p ->
                Column(
                    Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState()),
                ) {
                    val rows = pages[p].chunked(BOOKS_PER_ROW)
                    val filledRows = rows.size
                    repeat(SHELF_PAGE_ROWS) { r ->
                        val sign = when {
                            shelfItems.isEmpty() && r == 0 -> "书架还是空的" to "下载的内容会陈列在这里"
                            r == filledRows && rows.getOrElse(r) { emptyList() }.isEmpty() ->
                                "空 架" to "下载的内容会摆到这一层"
                            else -> null
                        }
                    ShelfPlankRow(
                        rowItems = rows.getOrElse(r) { emptyList() },
                        all = shelfItems,
                        colorStart = p * perPage + r * BOOKS_PER_ROW,
                        rowHeight = rowH,
                        rowWidth = rowUsableWidth,
                        bookWidth = bookW,
                        bookHeight = bookH,
                        emptySign = sign,
                        selecting = selecting,
                        isSelected = isSelected,
                        onToggle = onToggle,
                        onEnterSelection = onEnterSelection,
                        onPlay = onPlay,
                    )
                    }
                }
            }

 // 翻页条：只在多页时出现，压在柜底横档上（木色底条，不是浮空按钮）
            if (showPager) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .height(footerH),
                ) {
                    ShelfPixelButton(
                        "<", container = Color(0xFFC9731E), enabled = page > 0,
                        onClick = { if (page > 0) page-- },
                        modifier = Modifier.width(46.dp), fontSize = 12.sp, height = 24.dp,
                    )
                    Spacer(Modifier.width(12.dp))
                    Text(
                        "${page + 1} / ${pages.size}",
                        color = Cabinet.cream,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                    )
                    Spacer(Modifier.width(12.dp))
                    ShelfPixelButton(
                        ">", container = Color(0xFFC9731E), enabled = page < pages.size - 1,
                        onClick = { if (page < pages.size - 1) page++ },
                        modifier = Modifier.width(46.dp), fontSize = 12.sp, height = 24.dp,
                    )
                }
            }
        }
    }
}

/**
 * 一层货架：书距按 [bookRowGap] 把富余宽度分进缝里撑满搁板；底边坐在搁板顶面上。
 * 空层照画搁板（搁板由柜体统一绘制，这里只排书）。
 */
@Composable
private fun ShelfPlankRow(
    rowItems: List<ShelfItem>,
    all: List<ShelfItem>,
    colorStart: Int,
    rowHeight: Dp,
    rowWidth: Dp,
    bookWidth: Dp,
    bookHeight: Dp,
    emptySign: Pair<String, String>? = null,
    selecting: Boolean,
    isSelected: (ShelfItem) -> Boolean,
    onToggle: (ShelfItem) -> Unit,
    onEnterSelection: (ShelfItem) -> Unit,
    onPlay: (ShelfItem) -> Unit,
) {
    val spreadGap = bookRowGap(rowItems.size, rowWidth, bookWidth)
    Box(
        Modifier
            .fillMaxWidth()
            .height(rowHeight),
    ) {
        if (rowItems.isEmpty() && emptySign != null) {
            EmptyShelfSign(
                title = emptySign.first,
                subtitle = emptySign.second,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 14.dp),
            )
        }
        Row(
            Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
                .padding(
                    start = SHELF_POST_W + SHELF_SIDE_INSET,
                    end = SHELF_POST_W + SHELF_SIDE_INSET,
                    bottom = SHELF_BOARD_H,
                ),
 // 富余宽度均分进书间缝（上限封顶），书少时左挤不吊两头
            horizontalArrangement = Arrangement.spacedBy(spreadGap),
            verticalAlignment = Alignment.Bottom,
        ) {
            rowItems.forEachIndexed { i, item ->
                BookCell(
                    item = item,
                    all = all,
                    colorIndex = colorStart + i,
                    bookWidth = bookWidth,
                    bookHeight = bookHeight,
                    selecting = selecting,
                    selected = isSelected(item),
                    onToggle = { onToggle(item) },
                    onEnterSelection = { onEnterSelection(item) },
                    onPlay = onPlay,
                )
            }
        }
    }
}

/**
 * 空层挂牌：挂在上一层搁板下的木牌（两根吊绳 + 硬投影），把“空”变成一个设计过的状态，
 * 而不是一面空墙。只在第一层空架出现一次，不重复刷屏。
 */
@Composable
private fun EmptyShelfSign(
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier,
) {
    val boardW = 172.dp
    val boardH = 50.dp
    val ropeH = 16.dp
    Box(
        modifier
            .width(boardW)
            .height(boardH + ropeH)
            .drawBehind {
                val bw = size.width
                val bh = size.height - ropeH.toPx()
 // 吊绳
                drawRect(Color(0xFF2E1A0C), Offset(bw * 0.2f, 0f), Size(2.dp.toPx(), ropeH.toPx()))
                drawRect(Color(0xFF2E1A0C), Offset(bw * 0.8f, 0f), Size(2.dp.toPx(), ropeH.toPx()))
 // 硬投影
                drawRect(Color(0x7A0F0703), Offset(4.dp.toPx(), ropeH.toPx() + 4.dp.toPx()), Size(bw, bh))
 // 木牌
                drawRect(Color(0xFFC99552), Offset(0f, ropeH.toPx()), Size(bw, bh))
                drawRect(Color(0xFFE8B778), Offset(0f, ropeH.toPx()), Size(bw, 2.dp.toPx()))
                drawRect(Color(0xFF8A5A31), Offset(0f, size.height - 3.dp.toPx()), Size(bw, 3.dp.toPx()))
 // 绷布纹
                val rnd = kotlin.random.Random(512)
                repeat(10) {
                    drawRect(
                        Color(0xFF8A5A31).copy(alpha = 0.18f),
                        Offset(rnd.nextFloat() * bw, ropeH.toPx() + 4.dp.toPx()),
                        Size(1.dp.toPx(), bh - 10.dp.toPx()),
                    )
                }
 // 牌子描边（只包木板，不包吊绳）
                val o = Color(0xFF2A1508)
                val t = 2.dp.toPx()
                drawRect(o, Offset(0f, ropeH.toPx()), Size(bw, t))
                drawRect(o, Offset(0f, size.height - t), Size(bw, t))
                drawRect(o, Offset(0f, ropeH.toPx()), Size(t, bh))
                drawRect(o, Offset(bw - t, ropeH.toPx()), Size(t, bh))
            },
        contentAlignment = Alignment.BottomCenter,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(bottom = 6.dp),
        ) {
            Text(
                title,
                color = Color(0xFF3A2112),
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.sp,
            )
            Spacer(Modifier.height(2.dp))
            Text(subtitle, color = Color(0xFF6B4526), fontSize = 8.5.sp, letterSpacing = 0.3.sp)
        }
    }
}

fun kindLabelZh(item: ShelfItem): String = when (item.kind) {
    ShelfKind.VIDEO -> "视频"
    ShelfKind.AUDIO -> "音频"
    ShelfKind.IMAGE -> "图片"
    ShelfKind.OTHER -> "文件"
}

/** 封面调色：饱和度 +22%、亮度 +6%，压掉缩略图的灰雾，向参考图那种鲜艳封面靠。 */
private val coverVividFilter: ColorFilter = ColorFilter.colorMatrix(
    ColorMatrix().apply {
        setToSaturation(1.16f)
    },
)

/**
 * 一个真正“立方体”的书盒（照手绘图② 的斜投影）：正面矩形 + 左书脊斜面向后退出进深 +
 * 顶面书页（纸面+页纹横线+外缘封皮包边），整圈深描边勾出六边形轮廓；书脊底角悬空、正面底边坐在搁板上。
 * 进深 = 书宽 18%（≥14dp），抬高 = 进深一半（像素风斜角）；右下硬投影贴搁板。
 * 封面先降采样成像素画再 Fill bounds；点击=播放、长按=进多选（多选态点书勾选）
 * （pointerInput 手势：combinedClickable 在此组合层级不响应，勿改回）。
 */
@Composable
private fun BookCell(
    item: ShelfItem,
    all: List<ShelfItem>,
    colorIndex: Int,
    bookWidth: Dp,
    bookHeight: Dp,
    selecting: Boolean,
    selected: Boolean,
    onToggle: () -> Unit,
    onEnterSelection: () -> Unit,
    onPlay: (ShelfItem) -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var cover by remember(item.uri) { mutableStateOf<android.graphics.Bitmap?>(null) }
    LaunchedEffect(item.uri) {
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            cover = loadCover(context, item, all)?.let { pixelateCover(it, 88) }
        }
    }
    val playLatest by rememberUpdatedState(onPlay)
    val palette = shelfBookPalettes[colorIndex.mod(shelfBookPalettes.size)]
 // 斜投影进深：整盒向左上“退进去”d×rise（rise = d/2，像素风斜角）；竖边保持垂直，书站直
    val depthX = (bookWidth * 0.18f).coerceAtLeast(14.dp)
    val rise = depthX * 0.5f
    val frontW = bookWidth - depthX
    val frontH = bookHeight - rise
    val spineContentW = 14.dp
    val title = item.name
        .substringBeforeLast('.')
        .replace(Regex("[【】\\[\\]()（）]"), " ")
        .trim()
        .ifBlank { "未命名内容" }
    Box(
        Modifier
            .width(bookWidth)
            .height(bookHeight)
            .drawBehind {
                val w = size.width
                val h = size.height
                val d = depthX.toPx()
                val r = rise.toPx()
                val fw = frontW.toPx()
                val fh = frontH.toPx()
 // 六边形轮廓：背左上 → 背右上 → 前右上 → 前右下 → 前左下 → 背左下（竖边垂直，书站直）
                val box = Path().apply {
                    moveTo(0f, 0f)
                    lineTo(fw, 0f)
                    lineTo(w, r)
                    lineTo(w, h)
                    lineTo(d, h)
                    lineTo(0f, fh)
                    close()
                }
 // 硬投影：整个轮廓向右下平移，实心暗块先铺在搁板上
                translate(4.dp.toPx(), 5.dp.toPx()) {
                    drawPath(box, Color(0x59100703))
                }
 // 顶面=书页效果（不是一块实心板）：纸色面 + 页纹横线（页张沿厚度堆叠，
 // 平行于前缘）+ 外缘一条封皮包边（书皮包过顶，与书脊同色衔接）
                drawPath(
                    Path().apply {
                        moveTo(0f, 0f)
                        lineTo(fw, 0f)
                        lineTo(w, r)
                        lineTo(d, r)
                        close()
                    },
                    PAGE_PAPER,
                )
                listOf(0.36f, 0.6f, 0.82f).forEach { t ->
                    drawLine(
                        PAGE_LINE,
                        Offset(d * t, r * t),
                        Offset(fw + (w - fw) * t, r * t),
                        strokeWidth = 1.dp.toPx(),
                    )
                }
                drawPath(
                    Path().apply {
                        moveTo(0f, 0f)
                        lineTo(fw, 0f)
                        lineTo(fw + (w - fw) * 0.2f, r * 0.2f)
                        lineTo(d * 0.2f, r * 0.2f)
                        close()
                    },
                    palette.spine,
                )
 // 书脊斜面（背光，向左后退）
                drawPath(
                    Path().apply {
                        moveTo(0f, 0f)
                        lineTo(d, r)
                        lineTo(d, h)
                        lineTo(0f, fh)
                        close()
                    },
                    palette.spine,
                )
 // 正面（主色竖向渐变）——宽度 = 整段前缘，别少填右带
                drawRect(
                    Brush.verticalGradient(
                        listOf(palette.main, lerp(palette.main, palette.outline, 0.16f)),
                    ),
                    Offset(d, r),
                    Size(fw, h - r),
                )
 // 面-面棱线：正面顶边与左边各一条深线，把三个面切清楚
                drawLine(palette.outline, Offset(d, r), Offset(w, r), strokeWidth = 2.dp.toPx())
                drawLine(palette.outline, Offset(d, r), Offset(d, h), strokeWidth = 2.dp.toPx())
 // 顶面背缘一条最亮高光（受光最足处）
                drawLine(
                    Color.White.copy(alpha = 0.35f),
                    Offset(0f, 1.dp.toPx()),
                    Offset(fw, 1.dp.toPx()),
                    strokeWidth = 1.5.dp.toPx(),
                )
 // 多选态视觉：奶油内描边 + 轻罩染（勾选中的书）
                if (selected) {
                    drawRect(
                        Cabinet.cream.copy(alpha = 0.12f),
                    )
                    drawRect(
                        Cabinet.cream,
                        style = androidx.compose.ui.graphics.drawscope.Stroke(width = 4.dp.toPx()),
                    )
                }
 // 整圈轮廓描边（六边形，MITER 硬角）
                drawPath(
                    box,
                    palette.outline,
                    style = androidx.compose.ui.graphics.drawscope.Stroke(
                        width = 2.5.dp.toPx(),
                        join = androidx.compose.ui.graphics.StrokeJoin.Miter,
                    ),
                )
            }
            .pointerInput(item.uri, selecting) {
                detectTapGestures(
                    onTap = { if (selecting) onToggle() else playLatest(item) },
                    onLongPress = { if (!selecting) onEnterSelection() },
                )
            },
    ) {
 // 书脊内容：▶标签 + 竖排书名（写在斜面上，起点避开斜边；水平居中于进深带，随面前倾）
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .align(Alignment.TopStart)
                .offset(x = (depthX - spineContentW) / 2, y = rise + 4.dp)
                .width(spineContentW)
        ) {
            Box(
                Modifier
                    .size(12.dp)
                    .background(Cabinet.cream, RoundedCornerShape(2.dp)),
                contentAlignment = Alignment.Center,
            ) {
                PixelIcon(Sprites.PLAY, palette.spine, iconSize = 7.dp)
            }
            Spacer(Modifier.height(4.dp))
            title.replace(" ", "·").take(10).forEach { ch ->
                Text(
                    ch.toString(),
                    color = Cabinet.cream,
                    fontSize = 8.sp,
                    lineHeight = 9.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
        }

 // 正面内容：标题 / 封面窗 / 信息带（整体“退”到进深之后的正面矩形里）
        Column(
            modifier = Modifier
                .align(Alignment.TopStart)
                .offset(x = depthX, y = rise)
                .width(frontW)
                .height(frontH)
                .padding(horizontal = 3.dp, vertical = 2.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(35.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = title,
                    modifier = Modifier.fillMaxWidth(),
                    color = Cabinet.cream,
                    fontSize = if (title.length <= 14) 11.5.sp else 10.sp,
                    lineHeight = if (title.length <= 14) 12.5.sp else 11.sp,
                    letterSpacing = 0.4.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }

 // 封面窗：厚奶油衬框 + 深色内框，右下角斜纹（参考图的磁带盒装饰）
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .background(Cabinet.cream)
                    .padding(3.5.dp),
            ) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(Color(0xFF2B2018))
                        .border(1.5.dp, palette.outline.copy(alpha = 0.85f)),
                ) {
                    val bmp = cover
                    if (bmp != null) {
                        Image(
                            bitmap = bmp.asImageBitmap(),
                            contentDescription = item.name,
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.FillBounds,
                            filterQuality = FilterQuality.None,
                            colorFilter = coverVividFilter,
                        )
 // 轻微同色罩染，让封面和书盒颜色是一套
                        Box(
                            Modifier
                                .matchParentSize()
                                .background(palette.main.copy(alpha = 0.08f)),
                        )
                    } else {
                        PixelCoverPlaceholder(kind = item.kind, palette = palette)
                    }
                    Box(
                        Modifier
                            .matchParentSize()
                            .drawBehind {
                                val arm = 11.dp.toPx()
                                drawLine(
                                    palette.highlight.copy(alpha = 0.9f),
                                    Offset(size.width, size.height - arm),
                                    Offset(size.width - arm, size.height),
                                    strokeWidth = 3.dp.toPx(),
                                )
                                drawLine(
                                    palette.highlight.copy(alpha = 0.6f),
                                    Offset(size.width, size.height - arm * 2.2f),
                                    Offset(size.width - arm * 2.2f, size.height),
                                    strokeWidth = 1.5.dp.toPx(),
                                )
                            },
                    )
                }
            }

            Spacer(Modifier.height(3.dp))
 // 底部信息带：整条深色带 + 奶油细框胶囊（参考图）
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(21.dp)
                    .background(palette.footer)
                    .drawBehind {
                        drawRect(
                            palette.highlight.copy(alpha = 0.35f),
                            size = Size(size.width, 1.dp.toPx()),
                        )
                    },
                contentAlignment = Alignment.Center,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 2.dp)
                        .border(1.dp, Cabinet.cream.copy(alpha = 0.8f), RoundedCornerShape(3.dp))
                        .padding(horizontal = 3.dp, vertical = 1.dp),
                ) {
                    PixelIcon(Sprites.PLAY, Cabinet.cream, iconSize = 6.dp)
                    Spacer(Modifier.width(3.dp))
                    Text(
                        text = "${kindLabelZh(item)} · ${item.platform.ifBlank { "本地" }}",
                        color = Cabinet.cream,
                        fontSize = 7.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

/** 无封面占位：低对比像素块拼出的“设计过的封面”，中心用项目像素图标区分类型。 */
@Composable
private fun PixelCoverPlaceholder(
    kind: ShelfKind,
    palette: ShelfBookPalette,
) {
    val icon = when (kind) {
        ShelfKind.VIDEO -> Sprites.CAMERA
        ShelfKind.AUDIO -> Sprites.NOTE
        ShelfKind.IMAGE -> Sprites.IMAGE
        ShelfKind.OTHER -> Sprites.BOX
    }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF6B5339))
            .drawBehind {
                drawRect(
                    color = palette.highlight.copy(alpha = 0.18f),
                    topLeft = Offset(size.width * 0.12f, size.height * 0.12f),
                    size = Size(size.width * 0.76f, size.height * 0.10f),
                )
                drawRect(
                    color = palette.spine.copy(alpha = 0.7f),
                    topLeft = Offset(size.width * 0.16f, size.height * 0.72f),
                    size = Size(size.width * 0.68f, size.height * 0.08f),
                )
                drawRect(
                    color = palette.main.copy(alpha = 0.45f),
                    topLeft = Offset(size.width * 0.24f, size.height * 0.30f),
                    size = Size(size.width * 0.52f, size.height * 0.26f),
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        PixelIcon(icon, Cabinet.cream, accent = palette.main, iconSize = 24.dp)
    }
}

/**
 * 封面处理：先裁掉系统缩略图的信箱黑边（竖屏视频在方形缩略图里会左右带黑条），
 * 再按书窗比例中心裁切，降采样到 targetW 宽；绘制时用邻近插值放大成像素画。
 * 好处：缩略图的字幕/台标不再抢戏，整面书架统一成像素画风。
 */
private fun pixelateCover(src: android.graphics.Bitmap, targetW: Int): android.graphics.Bitmap {
    if (src.width <= 0 || src.height <= 0) return src
    val trimmed = trimLetterbox(src)
    val base = trimmed
    val ratio = 0.74f // 书窗宽 / 高
    val srcRatio = base.width.toFloat() / base.height
    val cropW: Int
    val cropH: Int
    if (srcRatio > ratio) {
        cropH = base.height
        cropW = (base.height * ratio).toInt().coerceAtLeast(1)
    } else {
        cropW = base.width
        cropH = (base.width / ratio).toInt().coerceAtLeast(1)
    }
    val x = (base.width - cropW) / 2
    val y = (base.height - cropH) / 2
    val targetH = (targetW / ratio).toInt().coerceAtLeast(1)
    return runCatching {
        val cropped = android.graphics.Bitmap.createBitmap(base, x, y, cropW, cropH)
        android.graphics.Bitmap.createScaledBitmap(cropped, targetW, targetH, true)
    }.getOrDefault(src)
}

/** 裁掉四边的纯黑信箱边（最多各边 30%，防止把夜景封面整张裁没）。 */
private fun trimLetterbox(src: android.graphics.Bitmap): android.graphics.Bitmap {
    val w = src.width
    val h = src.height
    if (w < 12 || h < 12) return src
    val px = IntArray(w * h)
    runCatching { src.getPixels(px, 0, w, 0, 0, w, h) }.getOrElse { return src }

    fun dark(c: Int): Boolean =
        ((c shr 16) and 0xFF) + ((c shr 8) and 0xFF) + (c and 0xFF) < 48

    fun rowDark(y: Int): Boolean {
        var d = 0
        var n = 0
        var x = 0
        while (x < w) {
            if (dark(px[y * w + x])) d++
            n++
            x += 3
        }
        return d >= n * 0.66f
    }

    fun colDark(x: Int): Boolean {
        var d = 0
        var n = 0
        var y = 0
        while (y < h) {
            if (dark(px[y * w + x])) d++
            n++
            y += 3
        }
        return d >= n * 0.66f
    }

    val maxY = (h * 0.3f).toInt()
    val maxX = (w * 0.3f).toInt()
    var top = 0
    while (top < maxY && rowDark(top)) top++
    var bottom = h - 1
    while (bottom > h - 1 - maxY && rowDark(bottom)) bottom--
    var left = 0
    while (left < maxX && colDark(left)) left++
    var right = w - 1
    while (right > w - 1 - maxX && colDark(right)) right--
    if (left == 0 && top == 0 && right == w - 1 && bottom == h - 1) return src
    if (right - left < w / 3 || bottom - top < h / 3) return src
    return runCatching {
        android.graphics.Bitmap.createBitmap(src, left, top, right - left + 1, bottom - top + 1)
    }.getOrDefault(src)
}

/** 整面木柜：柜背木纹 + 顶部内阴影 + 左右立柱 + 三层搁板（含板下投影）+ 柜底横档。 */
private fun DrawScope.drawCabinet(
    rowH: Float,
    boardH: Float,
    postW: Float,
    rows: Int,
    footerH: Float,
) {
    val px = 1.dp.toPx()

 // 柜背：上暖下暗的整面木板
    drawRect(
        Brush.verticalGradient(
            listOf(Cabinet.wallTop, Cabinet.wallMid, Cabinet.wallBottom),
            startY = 0f,
            endY = size.height,
        ),
    )
 // 细木纹：长短不一的竖向丝纹（固定种子，重绘不闪）
    val rnd = kotlin.random.Random(20261020)
    repeat(96) {
        val x = rnd.nextFloat() * size.width
        val w = if (rnd.nextInt(3) == 0) 2f * px else px
        val a = 0.06f + rnd.nextFloat() * 0.055f
        drawRect(
            color = if (rnd.nextBoolean()) Cabinet.grainHi.copy(alpha = a) else Cabinet.grainLo.copy(alpha = a),
            topLeft = Offset(x, 0f),
            size = Size(w, size.height),
        )
    }
 // 两个木结（低对比，避免每层重复）
    listOf(0.18f to 0.32f, 0.66f to 0.71f, 0.42f to 0.88f).forEach { (fx, fy) ->
        drawOval(
            color = Cabinet.grainLo.copy(alpha = 0.16f),
            topLeft = Offset(size.width * fx, size.height * fy),
            size = Size(6.dp.toPx(), 3.dp.toPx()),
        )
    }

 // 顶部内阴影（柜口压在柜背上的暗部）
    drawRect(
        Brush.verticalGradient(listOf(Color(0x8C000000), Color(0x00000000)), 0f, 34.dp.toPx()),
        topLeft = Offset(0f, 0f),
        size = Size(size.width, 34.dp.toPx()),
    )

 // 左右立柱（外暗边 + 内亮边），并把阴影投到柜背上
    drawRect(Cabinet.frame, topLeft = Offset(0f, 0f), size = Size(postW, size.height))
    drawRect(Cabinet.frame, topLeft = Offset(size.width - postW, 0f), size = Size(postW, size.height))
    drawRect(Cabinet.outline, topLeft = Offset(0f, 0f), size = Size(px, size.height))
    drawRect(
        Cabinet.outline,
        topLeft = Offset(size.width - px, 0f),
        size = Size(px, size.height),
    )
    drawRect(
        Cabinet.frameHi.copy(alpha = 0.6f),
        topLeft = Offset(postW - 2.dp.toPx(), 0f),
        size = Size(2.dp.toPx(), size.height),
    )
    drawRect(
        Cabinet.frameHi.copy(alpha = 0.6f),
        topLeft = Offset(size.width - postW, 0f),
        size = Size(2.dp.toPx(), size.height),
    )
    drawRect(
        Brush.horizontalGradient(
            listOf(Color(0x66000000), Color(0x00000000)),
            startX = postW,
            endX = postW + 16.dp.toPx(),
        ),
        topLeft = Offset(postW, 0f),
        size = Size(16.dp.toPx(), size.height),
    )
    drawRect(
        Brush.horizontalGradient(
            listOf(Color(0x00000000), Color(0x66000000)),
            startX = size.width - postW - 16.dp.toPx(),
            endX = size.width - postW,
        ),
        topLeft = Offset(size.width - postW - 16.dp.toPx(), 0f),
        size = Size(16.dp.toPx(), size.height),
    )

 // 搁板
    for (r in 0 until rows) {
        val top = (r + 1) * rowH - boardH
        drawShelfBoard(top = top, h = boardH)
    }

 // 柜底横档（翻页条落在上面）
    if (footerH > 0f) {
        val y = size.height - footerH
        drawRect(
            Brush.verticalGradient(listOf(Color(0x73000000), Color(0x00000000)), y - 12.dp.toPx(), y),
            topLeft = Offset(0f, y - 12.dp.toPx()),
            size = Size(size.width, 12.dp.toPx()),
        )
        drawRect(Cabinet.frame, topLeft = Offset(0f, y), size = Size(size.width, footerH))
        drawRect(
            Cabinet.frameHi.copy(alpha = 0.75f),
            topLeft = Offset(0f, y),
            size = Size(size.width, 1.dp.toPx()),
        )
        drawRect(
            Cabinet.outline,
            topLeft = Offset(0f, size.height - px),
            size = Size(size.width, px),
        )
    }
}

/** 一块厚木搁板：亮顶面 + 受光前唇 + 木色立面 + 板下投影（参考图的三段式木头）。 */
private fun DrawScope.drawShelfBoard(top: Float, h: Float) {
    val px = 1.dp.toPx()
    val w = size.width
 // 板下投影
    drawRect(
        Brush.verticalGradient(
            listOf(Color(0x8C1A0D05), Color(0x001A0D05)),
            startY = top + h,
            endY = top + h + 14.dp.toPx(),
        ),
        topLeft = Offset(0f, top + h),
        size = Size(w, 14.dp.toPx()),
    )
 // 立面
    drawRect(
        Brush.verticalGradient(
            listOf(Cabinet.plankFrontHi, Cabinet.plankFront, Cabinet.plankFrontLo),
            startY = top + 4.dp.toPx(),
            endY = top + h,
        ),
        topLeft = Offset(0f, top + 4.dp.toPx()),
        size = Size(w, h - 4.dp.toPx()),
    )
 // 顶面（受光）
    drawRect(Cabinet.plankTop, topLeft = Offset(0f, top), size = Size(w, 4.dp.toPx()))
 // 前唇高光
    drawRect(Cabinet.plankNosing, topLeft = Offset(0f, top + 3.dp.toPx()), size = Size(w, px))
 // 书底接触暗线
    drawRect(Color(0x33000000), topLeft = Offset(0f, top), size = Size(w, px))
 // 立面木纹（短竖丝）
    val rnd = kotlin.random.Random(top.toInt() xor 77)
    repeat(26) {
        val x = rnd.nextFloat() * w
        drawRect(
            Cabinet.plankFrontLo.copy(alpha = 0.16f),
            topLeft = Offset(x, top + 6.dp.toPx()),
            size = Size(px, (h - 10.dp.toPx()) * (0.4f + rnd.nextFloat() * 0.5f)),
        )
    }
 // 板底
    drawRect(Cabinet.plankUnder, topLeft = Offset(0f, top + h - 3.dp.toPx()), size = Size(w, 3.dp.toPx()))
}
