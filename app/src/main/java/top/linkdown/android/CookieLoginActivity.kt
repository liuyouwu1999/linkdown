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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.viewinterop.AndroidView
import top.linkdown.android.cookies.CookieStore
import top.linkdown.android.engine.DouyinEngine
import top.linkdown.android.cookies.NetscapeCookieFile
import top.linkdown.android.ui.PixelButton
import top.linkdown.android.ui.PixelButtonStyle
import top.linkdown.android.ui.PixelTheme
import top.linkdown.android.ui.Sprites
import top.linkdown.android.ui.pixelMinTouchTarget

/**
 * WebView 登录自动抓 Cookie（评审修复 像素化改造 + 双平台；评审修复 自动检测+自动关闭）：
 * 棚条 + 小木招牌「XX 登录」+ 指引 + WebView(weight 1f) + 底部抓取按钮，配色走 PixelTheme。
 */
class CookieLoginActivity : ComponentActivity() {
    private var finished = false   // 防 onPageFinished 多次回调重复抓取/Toast
    private lateinit var cfg: LoginCfg
    private val uiHandler = android.os.Handler(android.os.Looper.getMainLooper())

    /**
     * 抖音「登录」按钮的视口分数坐标（fx/fy ∈ 0..1，JS getBoundingClientRect/innerWidth 归一）。
     * 合成 click 是否能唤起弹窗取决于抖音风控（真机实测时开时不开），不可靠；此坐标用于画
     * 「点这里登录」穿透箭头，让用户真手指点（可信触摸百分百有效）。null=按钮不可见（已登录/未渲染）。
     */
    internal var loginButtonHint by mutableStateOf<List<Float>?>(null)

    // 登录态轮询（fix round 4）：SPA 登录成功（验证码/密码登录）未必触发页面重载，
    // onPageFinished 挂空的场景兜住——前台每 3s 查一次目标 cookie，查到即自动保存收尾
    private val cookiePoll = object : Runnable {
        override fun run() {
            if (finished || isFinishing) return
            autoGrabIfCookieReady(cfg)
            uiHandler.postDelayed(this, 3000L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 系统条适配（同 MainActivity）：Android 15+ 强制 edge-to-edge，条区让位交给根布局 safeDrawingPadding
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        cfg = loginCfgFor(intent?.getStringExtra(EXTRA_PLATFORM) ?: PLATFORM_BILIBILI)
        setContent {
            PixelTheme {
                LoginScreen(
                    cfg,
                    onBack = { finish() },
                    onGrab = { grabAndSave(cfg) },
                    onAutoGrab = { autoGrabIfCookieReady(cfg) },
                    onAutoClick = { v, c, u -> autoClickDouyinLogin(v, c, u) },
                    buttonHint = loginButtonHint,
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        uiHandler.post(cookiePoll)
    }

    override fun onPause() {
        uiHandler.removeCallbacks(cookiePoll)
        super.onPause()
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

 /**
* 抖音页面（feed/登录门户）登录入口在总览缩放下太小找不到（真机反馈）：页面加载完成后 JS
* 自动点「登录」唤起扫码面板——文本精确匹配+可见过滤（只点全文恰为「登录」的首个可见元素），
* 已登录态页面无该按钮 → 空操作；重试 3 次覆盖 SPA 水合时机；手动路径（用户自己点）不受影响。
* 只回传 clicked/not-found 字面量，无敏感信息进日志。
*/
    internal fun autoClickDouyinLogin(view: android.webkit.WebView, cfg: LoginCfg, url: String) {
        if (cfg.platform != CookieLoginActivity.PLATFORM_DOUYIN) return
        if (url == lastArmedUrl) return   // 同一页面的重复 onPageFinished 不重排
        lastArmedUrl = url
        loginBtnLocated = false
        autoClickGen++
        val gen = autoClickGen
        // 探测梯队（只读不点！2026-11-07 真机教训：合成 click 会把抖音弹窗开/关状态拨乱，
        // 用户真手指再点就无反应——点击动作必须 100% 归还给用户）：慢网下按钮水合可迟到分钟级，
        // 找到按钮坐标即停（画「点这里登录」箭头），没找到才落下一档。整页重载后 URL 变化重新武装。
        listOf(2500L, 12000L, 30000L, 60000L, 100000L).forEach { delayMs ->
            uiHandler.postDelayed({
                if (finished || isFinishing || loginBtnLocated || gen != autoClickGen) return@postDelayed
                try {
                    view.evaluateJavascript(AUTO_CLICK_JS) { r ->
                        android.util.Log.i("CookieLogin", "login-btn probe @$delayMs result=[$r]")
                        val s = r ?: ""
                        when {
                            s.contains("rect:fx=") -> {
                                loginBtnLocated = true
                                runCatching {
                                    val fx = s.substringAfter("fx=").substringBefore(',').toFloat()
                                    val fy = s.substringAfter("fy=").substringBefore(',').toFloat()
                                    loginButtonHint = listOf(fx, fy)   // 喂给穿透箭头
                                }
                            }
                            s.contains("not-found") -> loginButtonHint = null
                        }
                    }
                } catch (e: Exception) {
                    android.util.Log.w("CookieLogin", "login-btn probe error: " + e.message)
                }
            }, delayMs)
        }
    }

    private var lastArmedUrl: String? = null
    private var loginBtnLocated = false
    private var autoClickGen = 0

    companion object {
        const val EXTRA_PLATFORM = "platform"
        const val PLATFORM_BILIBILI = "bilibili"
        const val PLATFORM_NETEASE = "netease"
        const val PLATFORM_DOUYIN = "douyin"

        /**
         * 只读探测（不点击！）：深度优先穿 Shadow DOM 找全文恰为「登录」的最内层可见元素，
         * 回报其视口分数坐标（fx/fy）供「点这里登录」箭头定位；顺带把抖音自钉的窄视口 meta
         * （device-width+initial-scale=0.5）改写为 1280 触发重排——桌面版式登录按钮清晰可点。
         * 真机教训（2026-11-07）：合成 click 会拨乱弹窗开/关状态，点击必须交还给用户真手指。
         */
        private val AUTO_CLICK_JS = "(function(){" +
            "var m=document.querySelector('meta[name=\"viewport\"]');" +
            "if(m){m.setAttribute('content','width=1280');}" +
            "else{m=document.createElement('meta');m.name='viewport';m.setAttribute('content','width=1280');document.head.appendChild(m);}" +
            "var found=null;" +
            "function scan(root){" +
            "  var els=root.querySelectorAll('button,a,div,span');" +
            "  for(var i=0;i<els.length;i++){var e=els[i];" +
            "    var t=(e.textContent||'').trim();" +
            "    if(t==='登录'&&e.getBoundingClientRect().width>0&&e.getBoundingClientRect().height>0){" +
            "      if(!found||e.getBoundingClientRect().width<=found.getBoundingClientRect().width)found=e;" +
            "    }" +
            "    if(e.shadowRoot)scan(e.shadowRoot);" +
            "  }" +
            "}" +
            "scan(document);" +
            "if(!found){return 'not-found docW='+window.innerWidth;}" +
            "var r=found.getBoundingClientRect();" +
            "var fx=(r.x+r.width/2)/window.innerWidth,fy=(r.y+r.height/2)/window.innerHeight;" +
            "return 'rect:fx='+fx.toFixed(4)+',fy='+fy.toFixed(4)+',docW='+window.innerWidth;" +
            "})()"
    }
}

/** 各平台登录差异（fix round 2：B站 + 网易云； 实况图：抖音） */
internal data class LoginCfg(
    val platform: String,
    val signText: String,   // 招牌文案
    val loginUrl: String,   // WebView 首载登录页
    val cookieUrl: String,  // 从该 URL 的域抓 CookieManager
    val marker: String,     // 判定「已登录」的 Cookie 名
    val domain: String,     // Netscape 行 domain（yt-dlp 可识别）
    val desktopUa: Boolean = false, // 抖音桌面页在 WebView 手机 UA 下白屏——桌面 UA 渲染
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
    CookieLoginActivity.PLATFORM_DOUYIN -> LoginCfg(
        platform = CookieLoginActivity.PLATFORM_DOUYIN,
        signText = "抖音登录",
        // 实测结论（2026-11-07 探针）：sso.douyin.com/get_qrcode 与 login.douyin.com 在 WebView 里
        // 同样渲染成门户变体、无二维码面板（SSO 当 API 调也被反爬墙挡）；www.douyin.com feed 是
        // v1.0.0.7 真机 E2E 全通路径，登录弹窗=验证码/密码（抖音风控对内嵌 WebView 摘除扫码）。
        loginUrl = "https://www.douyin.com/?recommend=1",
        cookieUrl = "https://www.douyin.com",
        marker = "sessionid",   // HttpOnly——CookieManager.getCookie 可读（ 实况签名接口登录态 ）
        domain = ".douyin.com",
        desktopUa = true,
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
private fun LoginScreen(
    cfg: LoginCfg,
    onBack: () -> Unit,
    onGrab: () -> Unit,
    onAutoGrab: () -> Unit,
    onAutoClick: (android.webkit.WebView, LoginCfg, String) -> Unit,
    buttonHint: List<Float>?,
) {
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
 // 小号木招牌「XX 登录」（左端返回钮热区≥48dp，spec §3.3；招牌保持居中不受挤占）
        Box(Modifier.fillMaxWidth()) {
            Text(
                "← 返回",
                color = c.awningCream, fontSize = 14.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .pixelMinTouchTarget()
                    .clickable(remember { MutableInteractionSource() }, indication = null) { onBack() }
                    .padding(horizontal = 6.dp),
            )
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
        }
        Spacer(Modifier.height(9.dp))
 // 指引（像素字，弱色）；抖音页面加载后自动弹登录框（手机验证码/密码登录），登录后自动保存
        Text(
            if (cfg.desktopUa)
                "① 页面加载后自动弹出登录框（没弹出就点右上角红色「登录」）→ ② 手机验证码或密码登录 → ③ 登录后自动保存回主页"
            else "① 在下方页面登录 → ② 登录成功后点底部按钮 → ③ 返回主页看状态行",
            color = c.textSecondary, fontSize = 12.sp, lineHeight = 17.sp,
            modifier = Modifier.padding(horizontal = 14.dp),
        )
        Spacer(Modifier.height(8.dp))
 // WebView 占中间（棕框圆角内嵌）；fix round 3：每次页面加载完成自动查目标 cookie，检测到即自动抓取关闭
        Box(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 14.dp)
                .clip(RoundedCornerShape(8.dp))
                .border(2.dp, c.outline, RoundedCornerShape(8.dp)),
        ) {
            AndroidView(
                factory = { ctx ->
                    WebView(ctx).apply {
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true   // 登录流程（密码/短信/滑块）依赖 localStorage
                        if (cfg.desktopUa) {
                            // 抖音桌面页在 WebView 默认手机 UA 下白屏——按桌面 Chrome UA 渲染（桌面页含扫码/验证码登录）
                            settings.userAgentString = DouyinEngine.UA_SIGN
                            // 桌面版式比手机屏宽：总览缩放让整页适配屏宽，登录按钮可点；支持双指放大看二维码
                            settings.useWideViewPort = true
                            settings.loadWithOverviewMode = true
                            settings.setSupportZoom(true)
                            settings.builtInZoomControls = true
                            settings.displayZoomControls = false
                        }
 // 不设 WebViewClient 时页面跳转默认甩给外部浏览器，登录态落不到本进程 CookieManager
                        webViewClient = object : WebViewClient() {
                            override fun onPageFinished(view: WebView, url: String) {
                                android.util.Log.i("CookieLogin", "onPageFinished url=$url")
                                onAutoGrab()
                                onAutoClick(view, cfg, url)   // 按 URL 重排单击梯队（整页重载后弹窗已没，重新武装）
                            }

                            override fun onReceivedError(view: WebView, req: android.webkit.WebResourceRequest, err: android.webkit.WebResourceError) {
                                android.util.Log.w("CookieLogin", "onReceivedError ${req.url} code=${err.errorCode} ${err.description}")
                            }

                            override fun onReceivedHttpError(view: WebView, req: android.webkit.WebResourceRequest, resp: android.webkit.WebResourceResponse) {
                                android.util.Log.w("CookieLogin", "onReceivedHttpError ${req.url} code=${resp.statusCode}")
                            }
                        }
                        webChromeClient = object : android.webkit.WebChromeClient() {
                            override fun onConsoleMessage(message: android.webkit.ConsoleMessage): Boolean {
                                android.util.Log.i("CookieLogin", "console[${message.messageLevel()}] ${message.message().take(200)}")
                                return true
                            }
                        }
                        loadUrl(cfg.loginUrl)
                    }
                },
                modifier = Modifier.fillMaxSize(),
            )
 // 穿透式「点这里登录」箭头：合成 click 唤弹窗受抖音风控摆布（时开时不开），真手指百分百开；
 // 无 clickable 修饰 → 触摸全部穿透到 WebView，只做视觉指引不拦截
            buttonHint?.let { (fx, fy) ->
                var boxPx by remember { mutableStateOf(IntSize.Zero) }
                Box(Modifier.matchParentSize().onSizeChanged { boxPx = it }) {
                    if (boxPx != IntSize.Zero) {
                        val d = androidx.compose.ui.platform.LocalDensity.current
                        val anchorX = (boxPx.width * fx).toInt()
                        val anchorY = (boxPx.height * fy).toInt()
                        // 登录按钮恒在右上角：chip 居按钮左侧、▶ 水平指向按钮中心（groupY 对齐按钮行）
                        val groupX = (anchorX - d.run { 112.dp.toPx() }).toInt()
                            .coerceIn(d.run { 6.dp.toPx() }.toInt(), (boxPx.width - d.run { 122.dp.toPx() }.toInt()).coerceAtLeast(0))
                        val groupY = (anchorY - d.run { 16.dp.toPx() }).toInt().coerceAtLeast(d.run { 6.dp.toPx() }.toInt())
                        Row(
                            Modifier.offset { IntOffset(groupX, groupY) },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                "点这里登录",
                                color = c.onPrimary, fontSize = 12.sp, fontWeight = FontWeight.Bold,
                                modifier = Modifier
                                    .background(c.primary, RoundedCornerShape(5.dp))
                                    .border(2.dp, c.outline, RoundedCornerShape(5.dp))
                                    .padding(horizontal = 10.dp, vertical = 5.dp),
                            )
                            Text("▶", color = c.primary, fontSize = 13.sp)
                        }
                    }
                }
            }
        }
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
