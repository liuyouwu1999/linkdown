package top.linkdown.android.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.LocalTextStyle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import top.linkdown.android.R

// 融合像素字体（：真像素字是本 UI 的骨架，全局部落）
val FusionPixel = FontFamily(Font(R.font.fusion_pixel, FontWeight.Normal))

/**
 * 混搭色板——全部取 mockup 方向 A「开罗本铺」（绑定权威，spec 混搭规范）：
 * 底 #F8EFD8 / 面板 #FFFBEA / 框与描边 #4A3B2A / 内衬 #E8DBB8 / 投影 #C9B88C /
 * 主橙 #EF8F2E / 辅绿 #67B26F / 红 #D9534F / 正文 #3E3428 / 弱字 #8A7A5C /
 * 选中底 #F5E9C8 / 金 #F5C542 / 深棕底 #6B4E2E(字 #FFF3D6)
 */
data class PixelColors(
    val background: Color,
    val surface: Color,          // 面板底 #FFFBEA
    val outline: Color,          // 框与描边 #4A3B2A
    val inset: Color,            // 内衬 #E8DBB8
    val panelShadow: Color,      // 面板偏移投影 #C9B88C
    val textPrimary: Color,      // 正文 #3E3428
    val textSecondary: Color,    // 弱字 #8A7A5C
    val primary: Color,          // 主橙 #EF8F2E
    val onPrimary: Color,        // 橙上白字
    val success: Color,          // 辅绿 #67B26F
    val error: Color,            // 红 #D9534F
    val selectedBg: Color,       // 选中底 #F5E9C8
    val gold: Color,             // 金 #F5C542
    val darkBrown: Color,        // 深棕底 #6B4E2E（招牌/出货区内衬）
    val onDarkBrown: Color,      // 深棕上奶油字 #FFF3D6
    val awningCream: Color,      // 遮阳棚奶油条 #FFF6E3
    val buttonShadow: Color,     // 按钮底投影（=描边棕）
)

private val LightColors = PixelColors(
    background = Color(0xFFF8EFD8),
    surface = Color(0xFFFFFBEA),
    outline = Color(0xFF4A3B2A),
    inset = Color(0xFFE8DBB8),
    panelShadow = Color(0xFFC9B88C),
    textPrimary = Color(0xFF3E3428),
    textSecondary = Color(0xFF8A7A5C),
    primary = Color(0xFFEF8F2E),
    onPrimary = Color(0xFFFFFFFF),
    success = Color(0xFF67B26F),
    error = Color(0xFFD9534F),
    selectedBg = Color(0xFFF5E9C8),
    gold = Color(0xFFF5C542),
    darkBrown = Color(0xFF6B4E2E),
    onDarkBrown = Color(0xFFFFF3D6),
    awningCream = Color(0xFFFFF6E3),
    buttonShadow = Color(0xFF4A3B2A),
)

/** 深色变体：底/面板/框/文字简单衍生（spec：底 #2B2620 / 面板 #3A322A / 框 #1F1B16 / 文字 #F8EFD8 系），强调色不变 */
private val DarkColors = PixelColors(
    background = Color(0xFF2B2620),
    surface = Color(0xFF3A322A),
    outline = Color(0xFF1F1B16),
    inset = Color(0xFF4A4034),
    panelShadow = Color(0xFF16120E),
    textPrimary = Color(0xFFF8EFD8),
    textSecondary = Color(0xFFB3A482),
    primary = Color(0xFFEF8F2E),
    onPrimary = Color(0xFF2B2620),
    success = Color(0xFF67B26F),
    error = Color(0xFFD9534F),
    selectedBg = Color(0xFF4A4034),
    gold = Color(0xFFF5C542),
    darkBrown = Color(0xFF6B4E2E),
    onDarkBrown = Color(0xFFFFF3D6),
    awningCream = Color(0xFFE8DBB8),
    buttonShadow = Color(0xFF1F1B16),
)

private val LocalPixelColors = staticCompositionLocalOf { LightColors }

object PixelTheme {
    val colors: PixelColors @Composable get() = LocalPixelColors.current
}

@Composable
fun PixelTheme(content: @Composable () -> Unit) {
    val colors = if (isSystemInDarkTheme()) DarkColors else LightColors
 // 全局像素字体：material3.Text 落 LocalTextStyle，这里整棵树统一接管
    val base = LocalTextStyle.current
    CompositionLocalProvider(
        LocalPixelColors provides colors,
        LocalTextStyle provides base.copy(fontFamily = FusionPixel),
        content = content,
    )
}
