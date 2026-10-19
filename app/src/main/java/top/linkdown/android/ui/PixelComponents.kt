package top.linkdown.android.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog

/* ==================== 像素图标（8×8 字符画 grid，Canvas 逐格绘制；设计禁 emoji） ==================== */

/** '#'=主色，'o'=点缀色，'.'=空。全部 8 行 × 8 列。 */
object Sprites {
    val BOX = listOf(        // 箱子（新货入库 / 今日出货）
        "........",
        ".######.",
        ".######.",
        ".#....#.",
        ".#.oo.#.",
        ".#.oo.#.",
        ".######.",
        "........",
    )
    val TARGET = listOf(     // 靶心（要买什么）
        "..####..",
        ".#....#.",
        "#..oo..#",
        "#.o..o.#",
        "#.o..o.#",
        "#..oo..#",
        ".#....#.",
        "..####..",
    )
    val CAMERA = listOf(     // 相机（视频）
        "........",
        ".######.",
        ".#....#.",
        ".#.oo.#.",
        ".#....#.",
        ".######.",
        "....##..",
        "........",
    )
    val NOTE = listOf(       // 音符（音频）
        "....#...",
        "....##..",
        "....#.#.",
        "....#...",
        "....#...",
        "..###...",
        ".####...",
        "..##....",
    )
    val IMAGE = listOf(      // 图片（封面）
        "........",
        ".######.",
        ".#oo..#.",
        ".#....#.",
        ".#.#..#.",
        ".#..##..",
        ".######.",
        "........",
    )
    val SHELF = listOf(      // 货架
        "#......#",
        "########",
        "#.#.o#.#",
        "########",
        "#..#.#.#",
        "########",
        "#.o#..##",
        "#......#",
    )
    val PLAY = listOf(       // 播放三角（6 宽占位 8×8）
        "#.......",
        "##......",
        "###.....",
        "####....",
        "###.....",
        "##......",
        "#.......",
        "........",
    )
    val TRASH = listOf(      // 垃圾桶
        "..####..",
        ".######.",
        ".#....#.",
        "..#..#..",
        "..#..#..",
        "..#..#..",
        "..####..",
        "........",
    )
    val CHECK = listOf(      // 勾（弹窗勾选方块 / 开始按钮）
        "........",
        "......##",
        ".....###",
        "....##..",
        "#..##...",
        "####....",
        "##......",
        "........",
    )
    val CLIPBOARD = listOf(  // 剪贴板（剪贴板 chip）
        "..####..",
        ".######.",
        "..####..",
        ".######.",
        ".#....#.",
        ".#.oo.#.",
        ".#.oo.#.",
        ".######.",
    )
    val KEY = listOf(        // 钥匙（B站登录自动抓 Cookie 入口）
        ".###....",
        "#...#...",
        "#...#...",
        ".###....",
        "...#....",
        "...#.##.",
        "...#.##.",
        "...#....",
    )
    val SETTINGS = listOf(   // 滑块（设置页三条轨）
        "........",
        "###o####",
        "........",
        "#o######",
        "........",
        "#####o##",
        "........",
        "........",
    )
}

@Composable
fun PixelIcon(
    grid: List<String>,
    color: Color,
    modifier: Modifier = Modifier,
    iconSize: Dp = 20.dp,
    accent: Color? = null,
) {
    Canvas(modifier = modifier.size(iconSize)) {
        val cell = size.width / 8f
        val acc = accent ?: color
        grid.forEachIndexed { y, row ->
            row.forEachIndexed { x, ch ->
                when (ch) {
                    '#' -> drawRect(color, topLeft = Offset(x * cell, y * cell), size = Size(cell, cell))
                    'o' -> drawRect(acc, topLeft = Offset(x * cell, y * cell), size = Size(cell, cell))
                }
            }
        }
    }
}

/* ==================== 面板（设计：圆角 10dp / #FFFBEA / 3dp 框 / 4dp 偏移投影 #C9B88C） ==================== */

@Composable
fun PixelPanel(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val c = PixelTheme.colors
    val shape = RoundedCornerShape(10.dp)
    Box(
        modifier = modifier
            .fillMaxWidth()
            .drawBehind {
                drawRoundRect(c.panelShadow, topLeft = Offset(4.dp.toPx(), 4.dp.toPx()), cornerRadius = CornerRadius(10.dp.toPx()))
            }
            .background(c.surface, shape)
            .border(3.dp, c.outline, shape)
            .drawBehind {
 // A 式内衬 #E8DBB8：框内一圈 2dp 细线
                val g = 3.dp.toPx()
                drawRoundRect(
                    c.inset, topLeft = Offset(g, g),
                    size = Size(size.width - g * 2, size.height - g * 2),
                    cornerRadius = CornerRadius(7.dp.toPx()),
                    style = Stroke(2.dp.toPx()),
                )
            }
            .padding(12.dp),
    ) {
 // Box 会把多个子级叠在一起，面板内容纵向排列
        Column { content() }
    }
}

/** 面板小标：像素图标 + 弱字（C 式「📮 交给我想要的链接：」的像素化） */
@Composable
fun PixelPanelLabel(icon: List<String>, text: String) {
    val c = PixelTheme.colors
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 6.dp)) {
        PixelIcon(icon, c.darkBrown, accent = c.gold, iconSize = 14.dp)
        Spacer(Modifier.width(6.dp))
        Text(text, color = c.textSecondary, fontSize = 13.sp)
    }
}

/* ==================== 主按钮（设计：圆角 10dp / 橙底白字 / 3dp 棕框 / 4dp 底投影，按下沉入） ==================== */

enum class PixelButtonStyle { PRIMARY, SUCCESS, ERROR }

@Composable
fun PixelButton(
    text: String,
    enabled: Boolean = true,
    style: PixelButtonStyle = PixelButtonStyle.PRIMARY,
    modifier: Modifier = Modifier,
    icon: List<String>? = null,
    fontSize: TextUnit = 18.sp,
    onClick: () -> Unit,
) {
    val c = PixelTheme.colors
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val sunk = pressed && enabled
    val back = when (style) {
        PixelButtonStyle.PRIMARY -> c.primary
        PixelButtonStyle.SUCCESS -> c.success
        PixelButtonStyle.ERROR -> c.error
    }
    val shape = RoundedCornerShape(10.dp)
    Box(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 54.dp)
            .offset(y = if (sunk) 4.dp else 0.dp)
            .drawBehind {
 // 按下时底投影消失（按钮沉入手感）；禁用态灰化但投影保留
                if (!sunk) drawRoundRect(c.buttonShadow, topLeft = Offset(0f, 4.dp.toPx()), cornerRadius = CornerRadius(10.dp.toPx()))
            }
            .background(if (enabled) back else c.textSecondary, shape)
            .border(3.dp, c.outline, shape)
            .clickable(enabled = enabled, interactionSource = interactionSource, indication = null) { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (icon != null) {
                PixelIcon(icon, if (enabled) c.onPrimary else c.background, iconSize = 14.dp)
                Spacer(Modifier.width(6.dp))
            }
            Text(
                text,
                color = if (enabled) c.onPrimary else c.background,
                fontSize = fontSize,
                fontWeight = FontWeight.Bold,
                letterSpacing = 2.sp,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 13.dp),
            )
        }
    }
}

/* ==================== chip（设计剪贴板绿 chip 等） ==================== */

@Composable
fun PixelChip(
    text: String,
    bg: Color,
    contentColor: Color,
    modifier: Modifier = Modifier,
    icon: List<String>? = null,
    iconAccent: Color? = null,
    onClick: (() -> Unit)? = null,
) {
    val c = PixelTheme.colors
    val shape = RoundedCornerShape(6.dp)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .background(bg, shape)
            .border(2.dp, c.outline, shape)
            .clickable(remember { MutableInteractionSource() }, enabled = onClick != null, indication = null) { onClick?.invoke() }
            .padding(horizontal = 8.dp, vertical = 5.dp),
    ) {
        if (icon != null) {
            PixelIcon(icon, contentColor, accent = iconAccent ?: contentColor, iconSize = 11.dp)
            Spacer(Modifier.width(5.dp))
        }
        Text(text, color = contentColor, fontSize = 12.sp)
    }
}

/* ==================== 弹窗勾选行（设计：选中橙方块勾+浅黄底，未选白底空方块，右侧灰字注记） ==================== */

@Composable
fun PixelCheckRow(
    label: String,
    icon: List<String>,
    note: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val c = PixelTheme.colors
    val shape = RoundedCornerShape(8.dp)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .background(if (selected) c.selectedBg else Color.White, shape)
            .border(2.dp, c.outline, shape)
            .clickable(remember { MutableInteractionSource() }, indication = null) { onClick() }
            .padding(horizontal = 10.dp, vertical = 11.dp),
    ) {
        val boxShape = RoundedCornerShape(4.dp)
        Box(
            Modifier
                .size(18.dp)
                .background(if (selected) c.primary else Color.White, boxShape)
                .border(2.dp, c.outline, boxShape),
            contentAlignment = Alignment.Center,
        ) {
            if (selected) PixelIcon(Sprites.CHECK, Color.White, iconSize = 12.dp)
        }
        Spacer(Modifier.width(9.dp))
        PixelIcon(icon, c.darkBrown, accent = c.gold, iconSize = 16.dp)
        Spacer(Modifier.width(7.dp))
        Text(label, color = c.textPrimary, fontSize = 15.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.weight(1f))
        Text(note, color = c.textSecondary, fontSize = 12.sp)
    }
}

/* ==================== 分段式像素进度条 ==================== */

@Composable
fun PixelProgressBar(fraction: Float, modifier: Modifier = Modifier) {
    val c = PixelTheme.colors
    val f = fraction.coerceIn(0f, 1f)
    Canvas(modifier = modifier.fillMaxWidth().height(18.dp)) {
        val segments = 20; val gap = 2.dp.toPx()
        val segW = (size.width - gap * (segments - 1)) / segments
        for (i in 0 until segments) {
            val x = i * (segW + gap)
            drawRect(c.inset, topLeft = Offset(x, 0f), size = Size(segW, size.height))
            drawRect(c.outline, topLeft = Offset(x, 0f), size = Size(segW, size.height), style = Stroke(2.dp.toPx()))
            if (f * segments > i + 0.5f) drawRect(c.primary, topLeft = Offset(x, 0f), size = Size(segW, size.height))
        }
    }
}

/* ==================== 输入框（C 式白底圆角） ==================== */

@Composable
fun PixelTextField(
    value: String,
    onValueChange: (String) -> Unit,
    hint: String,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
    singleLine: Boolean = true,
    testTag: String? = null,
) {
    val c = PixelTheme.colors
    val shape = RoundedCornerShape(8.dp)
    var focused by remember { mutableStateOf(false) }
    Box(
        modifier = modifier
            .fillMaxWidth()
            .drawBehind {
                drawRoundRect(c.panelShadow, topLeft = Offset(3.dp.toPx(), 3.dp.toPx()), cornerRadius = CornerRadius(8.dp.toPx()))
            }
            .background(if (enabled) Color.White else c.inset, shape)
            .border(2.dp, if (focused) c.primary else c.outline, shape)
            .padding(horizontal = 12.dp, vertical = 13.dp)
            .heightIn(min = 28.dp),
    ) {
        BasicTextField(
            value = value, onValueChange = onValueChange, singleLine = singleLine, enabled = enabled,
            textStyle = TextStyle(color = c.textPrimary, fontSize = 15.sp, fontFamily = FusionPixel),
            cursorBrush = SolidColor(c.primary),
            modifier = Modifier.fillMaxWidth().onFocusChanged { focused = it.isFocused }
                .semantics { if (testTag != null) this.testTag = testTag },
        )
        if (value.isEmpty()) Text(hint, color = c.textSecondary, fontSize = 15.sp)
    }
}

/* ==================== 顶栏：条纹遮阳棚 + 木招牌 + 金色货架徽标（设计/§2/§10） ==================== */

@Composable
fun ShopHeader(shelfCount: Int, onShelfClick: () -> Unit) {
    val c = PixelTheme.colors
    Column(Modifier.fillMaxWidth()) {
 // 遮阳棚：橙 #EF8F2E / 奶油 #FFF6E3 竖条纹 高 14dp，底缘 4dp 描边棕
        Box(
            Modifier
                .fillMaxWidth()
                .height(18.dp)
                .drawBehind {
                    val h = size.height - 4.dp.toPx()
                    val stripe = 18.dp.toPx()
                    var x = 0f; var i = 0
                    while (x < size.width) {
                        drawRect(if (i % 2 == 0) c.primary else c.awningCream, topLeft = Offset(x, 0f), size = Size(stripe, h))
                        x += stripe; i++
                    }
                    drawRect(c.outline, topLeft = Offset(0f, h), size = Size(size.width, 4.dp.toPx()))
                },
        )
        Spacer(Modifier.height(8.dp))
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
 // 木招牌：深棕圆角牌匾 + 奶油字 + 3dp 框 + 4dp 底投影
            val signShape = RoundedCornerShape(8.dp)
            Box(
                Modifier
                    .weight(1f)
                    .offset(y = 4.dp)
                    .drawBehind {
                        drawRoundRect(c.buttonShadow, topLeft = Offset(0f, 4.dp.toPx()), cornerRadius = CornerRadius(8.dp.toPx()))
                    }
                    .background(c.darkBrown, signShape)
                    .border(3.dp, c.outline, signShape)
                    .padding(vertical = 9.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "链 接 小 卖 部",
                    color = c.onDarkBrown,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 2.sp,
                )
            }
            Spacer(Modifier.width(8.dp))
 // 金色小徽标「货架 N 件」→ 货架页
            val badgeShape = RoundedCornerShape(6.dp)
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .offset(y = 4.dp)
                    .background(c.gold, badgeShape)
                    .border(2.dp, c.outline, badgeShape)
                    .clickable(remember { MutableInteractionSource() }, indication = null) { onShelfClick() }
                    .padding(horizontal = 7.dp, vertical = 6.dp),
            ) {
                PixelIcon(Sprites.SHELF, c.outline, accent = c.primary, iconSize = 13.dp)
                Spacer(Modifier.width(4.dp))
                Text("$shelfCount 件", color = c.outline, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }
        }
        Spacer(Modifier.height(10.dp))
    }
}

/* ==================== 日志行 ==================== */

@Composable
fun PixelLogLine(text: String, color: Color? = null, modifier: Modifier = Modifier) {
    val c = PixelTheme.colors
    Text(text, color = color ?: c.onDarkBrown, fontSize = 12.sp, lineHeight = 17.sp, modifier = modifier)
}

/* ==================== 确认弹窗（删除等） ==================== */

@Composable
fun PixelConfirmDialog(
    title: String,
    message: String,
    confirmText: String = "确认",
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val c = PixelTheme.colors
    Dialog(onDismissRequest = onDismiss) {
        PixelPanel {
            Text(title, color = c.textPrimary, fontSize = 16.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(8.dp))
            Text(message, color = c.textSecondary, fontSize = 13.sp, lineHeight = 19.sp)
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PixelButton("取 消", style = PixelButtonStyle.SUCCESS, onClick = onDismiss, modifier = Modifier.weight(1f))
                PixelButton(confirmText, style = PixelButtonStyle.ERROR, onClick = onConfirm, modifier = Modifier.weight(1f))
            }
        }
    }
}
