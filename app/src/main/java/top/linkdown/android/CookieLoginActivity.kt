package top.linkdown.android

import android.os.Bundle
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import top.linkdown.android.cookies.CookieStore
import top.linkdown.android.cookies.NetscapeCookieFile
import top.linkdown.android.ui.PixelButton
import top.linkdown.android.ui.PixelButtonStyle
import top.linkdown.android.ui.PixelTheme
import top.linkdown.android.ui.Sprites

/**
 * WebView 登录自动抓 Cookie（评审修复 像素化改造 + 双平台；评审修复 自动检测+自动关闭）：
 * 棚条 + 小木招牌「XX 登录」+ 指引 + WebView(weight 1f) + 底部抓取按钮，配色走 PixelTheme。
 */
class CookieLoginActivity : ComponentActivity() {
    private var finished = false   // 防 onPageFinished 多次回调重复抓取/Toast

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 系统条适配（同 MainActivity）：Android 15+ 强制 edge-to-edge，条区让位交给根布局 safeDrawingPadding
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        val platform = intent?.getStringExtra(EXTRA_PLATFORM) ?: PLATFORM_BILIBILI
        val cfg = loginCfgFor(platform)
        setContent { PixelTheme { LoginScreen(cfg, onGrab = { grabAndSave(cfg) }, onAutoGrab = { autoGrabIfCookieReady(cfg) }) } }
    }

 /** 语义保留：flush 后抓 CookieManager，缺标记 Toast 提示（可手动重试），成功写 Netscape 文件并 finish（回登录选择页） */
    private fun grabAndSave(cfg: LoginCfg) {
        if (finished) return
        finished = true
        CookieManager.getInstance().apply { flush() }   // 先落盘再取，防内存态未同步
        val header = CookieManager.getInstance().getCookie(cfg.cookieUrl).orEmpty()
 // 只打名，不打值——cookie 是敏感信息
        android.util.Log.i("CookieLogin", "platform=" + cfg.platform + " names=" + header.split(";").map { it.trim().substringBefore('=') })
        if (!header.contains(cfg.marker)) {
            finished = false   // 没抓到，允许手动按钮重试
            Toast.makeText(this, "未检测到 ${cfg.marker}——请先在页面内登录", Toast.LENGTH_LONG).show()
            return
        }
        CookieStore.write(cfg.platform, NetscapeCookieFile.fromPairs(header, cfg.domain), filesDir)
        Toast.makeText(this, "已保存 ${cfg.platform} cookie，可回主页下载", Toast.LENGTH_LONG).show()
        finish()
    }

 /**
 * fix round 3：自动检测+自动关闭——页面每次加载完成时查平台目标 cookie，
 * 检测到即自动抓取保存并回选择页；SPA 纯哈希跳转不触发时由底部手动按钮兜底。
 */
    internal fun autoGrabIfCookieReady(cfg: LoginCfg) {
        if (finished) return
        val header = CookieManager.getInstance().getCookie(cfg.cookieUrl).orEmpty()
        if (header.contains(cfg.marker)) grabAndSave(cfg)
    }

    companion object {
        const val EXTRA_PLATFORM = "platform"
        const val PLATFORM_BILIBILI = "bilibili"
        const val PLATFORM_NETEASE = "netease"
    }
}

/** 各平台登录差异（fix round 2：B站 + 网易云） */
internal data class LoginCfg(
    val platform: String,
    val signText: String,   // 招牌文案
    val loginUrl: String,   // WebView 首载登录页
    val cookieUrl: String,  // 从该 URL 的域抓 CookieManager
    val marker: String,     // 判定「已登录」的 Cookie 名
    val domain: String,     // Netscape 行 domain（yt-dlp 可识别）
)

internal fun loginCfgFor(platform: String): LoginCfg = when (platform) {
    CookieLoginActivity.PLATFORM_NETEASE -> LoginCfg(
        platform = CookieLoginActivity.PLATFORM_NETEASE,
        signText = "网易云登录",
        loginUrl = "https://music.163.com/#/login",
        cookieUrl = "https://music.163.com",
        marker = "MUSIC_U",
        domain = ".music.163.com",
    )
    else -> LoginCfg(
        platform = CookieLoginActivity.PLATFORM_BILIBILI,
        signText = "B站登录",
        loginUrl = "https://passport.bilibili.com/login",
        cookieUrl = "https://www.bilibili.com",
        marker = "SESSDATA",
        domain = ".bilibili.com",
    )
}

@Composable
private fun LoginScreen(cfg: LoginCfg, onGrab: () -> Unit, onAutoGrab: () -> Unit) {
    val c = PixelTheme.colors
    Column(Modifier.background(c.darkBrown).safeDrawingPadding().fillMaxSize().background(c.background)) {
 // 顶部棚条：橙白条纹 14dp + 4dp 棕底缘（同主页三段之一）
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
        Spacer(Modifier.height(10.dp))
 // 小号木招牌「XX 登录」
        Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp), horizontalArrangement = Arrangement.Center) {
            val signShape = RoundedCornerShape(8.dp)
            Box(
                Modifier
                    .offset(y = 4.dp)
                    .drawBehind {
                        drawRoundRect(c.buttonShadow, topLeft = Offset(0f, 4.dp.toPx()), cornerRadius = CornerRadius(8.dp.toPx()))
                    }
                    .background(c.darkBrown, signShape)
                    .border(3.dp, c.outline, signShape)
                    .padding(horizontal = 24.dp, vertical = 7.dp),
            ) {
                Text(cfg.signText, color = c.onDarkBrown, fontSize = 15.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
            }
        }
        Spacer(Modifier.height(9.dp))
 // 指引（像素字，弱色）
        Text(
            "① 在下方页面登录 → ② 登录成功后点底部按钮 → ③ 返回主页看状态行",
            color = c.textSecondary, fontSize = 12.sp, lineHeight = 17.sp,
            modifier = Modifier.padding(horizontal = 14.dp),
        )
        Spacer(Modifier.height(8.dp))
 // WebView 占中间（棕框圆角内嵌）；fix round 3：每次页面加载完成自动查目标 cookie，检测到即自动抓取关闭
        AndroidView(
            factory = { ctx ->
                WebView(ctx).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true   // 登录流程（密码/短信/滑块）依赖 localStorage
 // 不设 WebViewClient 时页面跳转默认甩给外部浏览器，登录态落不到本进程 CookieManager
                    webViewClient = object : WebViewClient() {
                        override fun onPageFinished(view: WebView, url: String) {
                            onAutoGrab()
                        }
                    }
                    loadUrl(cfg.loginUrl)
                }
            },
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 14.dp)
                .clip(RoundedCornerShape(8.dp))
                .border(2.dp, c.outline, RoundedCornerShape(8.dp)),
        )
        Spacer(Modifier.height(10.dp))
 // 登录动作在后：抓取按钮挪到底部
        PixelButton(
            "登录好了，抓取 Cookie",
            style = PixelButtonStyle.PRIMARY,
            icon = Sprites.CHECK,
            fontSize = 16.sp,
            onClick = onGrab,
            modifier = Modifier.padding(horizontal = 14.dp),
        )
        Spacer(Modifier.height(12.dp))
    }
}
