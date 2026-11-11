package top.linkdown.android

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleResumeEffect
import top.linkdown.android.cookies.CookieStore
import top.linkdown.android.ui.PageTopBar
import top.linkdown.android.ui.PixelConfirmDialog
import top.linkdown.android.ui.PixelIcon
import top.linkdown.android.ui.PixelTheme
import top.linkdown.android.ui.Sprites
import top.linkdown.android.ui.pixelMinTouchTarget
import top.linkdown.android.ui.pixelNeutralContainer
import top.linkdown.android.ui.pixelNeutralContent

/**
 * 登录选择页（评审修复 产品拍板）：细棚条 + 招牌「登录账号」+ 平台行卡
 * （色块图标 + 平台名 + 状态「有 ✓/无」+ 右侧「登录 ▸」→ CookieLoginActivity），
 * 状态每次 onResume 刷新（CookieStore.existing）。
 */
class LoginHubActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 系统条适配（同 MainActivity）：Android 15+ 强制 edge-to-edge，条区让位交给根布局 safeDrawingPadding
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        setContent {
            PixelTheme {
                LoginHubScreen(
                    onBack = { finish() },
                    onOpenPlatform = { openPlatform(it) },
                    onClear = { platform, filesDir -> clearPlatform(platform, filesDir) },
                )
            }
        }
    }

    private fun openPlatform(platform: String) {
        startActivity(Intent(this, CookieLoginActivity::class.java).putExtra(CookieLoginActivity.EXTRA_PLATFORM, platform))
    }

 /**
* 登录中心「清除」（ 登出 ）：WebView 域内 cookie 逐名过期（host-only 与 domain 两种属性
* 各发一遍 Max-Age=0，防残留任一形态）+ 删平台 cookie 文件（引擎侧登出）。返回文件是否确有删除。
*/
    private fun clearPlatform(platform: String, filesDir: java.io.File): Boolean {
        val cfg = loginCfgFor(platform)
        val cm = android.webkit.CookieManager.getInstance()
        val names = cm.getCookie(cfg.cookieUrl).orEmpty()
            .split(";").map { it.trim().substringBefore('=') }.filter { it.isNotEmpty() }
        names.forEach { name ->
            cm.setCookie(cfg.cookieUrl, "$name=; Path=/; Max-Age=0")
            cm.setCookie(cfg.cookieUrl, "$name=; Domain=${cfg.domain}; Path=/; Max-Age=0")
        }
        cm.flush()
        return CookieStore.delete(platform, filesDir)
    }
}

/** 折叠区/选择页共用的平台清单（顺序即展示顺序； 实况图：抖音入列） */
internal val LOGIN_PLATFORMS = listOf(
    CookieLoginActivity.PLATFORM_BILIBILI,
    CookieLoginActivity.PLATFORM_NETEASE,
    CookieLoginActivity.PLATFORM_DOUYIN,
)

internal fun platformDisplayName(platform: String): String = when (platform) {
    CookieLoginActivity.PLATFORM_NETEASE -> "网易云"
    CookieLoginActivity.PLATFORM_DOUYIN -> "抖音"
    else -> "B站"
}

@Composable
private fun LoginHubScreen(
    onBack: () -> Unit,
    onOpenPlatform: (String) -> Unit,
    onClear: (String, java.io.File) -> Boolean,
) {
    val c = PixelTheme.colors
    val context = androidx.compose.ui.platform.LocalContext.current
    var status by remember { mutableStateOf<Map<String, Boolean>>(emptyMap()) }
    var pendingClear by remember { mutableStateOf<String?>(null) }
    LifecycleResumeEffect(Unit) {
 // 每次回前台刷新（CookieLoginActivity 保存后 finish 回到本页也会触发）
        status = LOGIN_PLATFORMS.associateWith { CookieStore.existing(it, context.filesDir) != null }
        onPauseOrDispose { }
    }
    Column(Modifier.background(c.darkBrown).safeDrawingPadding().fillMaxSize().background(c.background)) {
 // 细棚条（同货架页：10dp 含 3dp 棕底缘）
        Box(
            Modifier
                .fillMaxWidth()
                .height(10.dp)
                .drawBehind {
                    val h = size.height - 3.dp.toPx()
                    val stripe = 18.dp.toPx()
                    var x = 0f; var i = 0
                    while (x < size.width) {
                        drawRect(if (i % 2 == 0) c.primary else c.awningCream, topLeft = Offset(x, 0f), size = Size(stripe, h))
                        x += stripe; i++
                    }
                    drawRect(c.outline, topLeft = Offset(0f, h), size = Size(size.width, 3.dp.toPx()))
                },
        )
 // 顶栏「登录账号」（PageTopBar 统一二级页顶栏，spec §3；页内原招牌删除）
        PageTopBar(title = "登录账号", onBack = onBack)
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            LOGIN_PLATFORMS.forEach { platform ->
                LoginPlatformRow(
                    platform = platform,
                    hasCookie = status[platform] == true,
                    onClick = { onOpenPlatform(platform) },
                    onClear = { pendingClear = platform },
                )
            }
        }
 // 底部弱字说明
        Text(
            "更多平台的登录支持陆续添加",
            color = c.textSecondary, fontSize = 11.sp,
            modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
    }
 // 清除确认（PixelConfirmDialog 同款像素风）
    pendingClear?.let { platform ->
        PixelConfirmDialog(
            title = "清除${platformDisplayName(platform)}登录？",
            message = "清除后下载不再携带${platformDisplayName(platform)}登录态（实况图等登录功能需重新登录才生效）。",
            confirmText = "清 除",
            onConfirm = {
                val removed = onClear(platform, context.filesDir)
                status = LOGIN_PLATFORMS.associateWith { CookieStore.existing(it, context.filesDir) != null }
                android.widget.Toast.makeText(
                    context,
                    if (removed) "已清除${platformDisplayName(platform)}登录" else "${platformDisplayName(platform)}本就没有登录信息",
                    android.widget.Toast.LENGTH_SHORT,
                ).show()
                pendingClear = null
            },
            onDismiss = { pendingClear = null },
        )
    }
}

@Composable
private fun LoginPlatformRow(
    platform: String,
    hasCookie: Boolean,
    onClick: () -> Unit,
    onClear: () -> Unit,
) {
    val c = PixelTheme.colors
    val shape = RoundedCornerShape(8.dp)
    val isNetease = platform == CookieLoginActivity.PLATFORM_NETEASE
    val blockColor = if (isNetease) c.success else c.primary
    val blockIcon = if (isNetease) Sprites.NOTE else Sprites.CAMERA
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .background(c.surface, shape)
            .border(2.dp, c.outline, shape)
            .clickable(remember { MutableInteractionSource() }, indication = null) { onClick() }
            .padding(8.dp),
    ) {
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
            Text(platformDisplayName(platform), color = c.textPrimary, fontSize = 14.sp, fontWeight = FontWeight.Bold)
            Text(
                if (hasCookie) "已存 cookie：有 ✓" else "已存 cookie：无",
                color = if (hasCookie) c.success else c.textSecondary,
                fontSize = 11.sp,
            )
        }
        Spacer(Modifier.width(8.dp))
        val btnShape = RoundedCornerShape(5.dp)
 // 「清除」只在有 cookie 时出现（NEUTRAL 三色=次要动作，不抢登录主按钮；热区≥48dp）
        if (hasCookie) {
            Box(
                Modifier
                    .pixelMinTouchTarget()
                    .background(pixelNeutralContainer(), btnShape)
                    .border(2.dp, c.outline, btnShape)
                    .clickable(remember { MutableInteractionSource() }, indication = null) { onClear() }
                    .padding(horizontal = 9.dp, vertical = 6.dp),
            ) {
                Text("清除", color = pixelNeutralContent(), fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.width(8.dp))
        }
        Box(
            Modifier
                .pixelMinTouchTarget()
                .background(c.primary, btnShape)
                .border(2.dp, c.outline, btnShape)
                .clickable(remember { MutableInteractionSource() }, indication = null) { onClick() }
                .padding(horizontal = 9.dp, vertical = 6.dp),
        ) {
            Text("登录 ▸", color = c.onPrimary, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        }
    }
}
