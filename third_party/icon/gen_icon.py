#!/usr/bin/env python3
"""链接小卖部 启动图标生成器 v9——光泽小卖部门面（对齐用户参考效果图重构）。

v9（2026-10-23）：按用户提供的参考图重做构图与质感——
①顶棚为全图主导：梯形外扩（顶宽 48→底摆 62），7 条等宽橙白条纹呈扇形展开，
  每条纵向渐变出布面光泽，棚底连续浅扇贝弧，棚下投柔和阴影；
②双橱窗放大（14×22 竖长窗）藏于招牌后侧（左侧露 L 形、右同），深棕内嵌窗框 +
  金/绿玻璃纵向渐变 + 斜向高光条；
③中央奶油下载招牌（30×21，渐变+落影），箭头+接件托盘为唯一视觉焦点；
④底部基座比墙体宽（50 vs 46，圆角地台），对齐参考图「底部比中间长一点」；
⑤全部主形体收在常见蒙版圆 r36 内（最远点 34.9=棚底外角；r33 安全圆外仅棚角
  与基座角 1~2dp，任何常见蒙版下不裁切，异形蒙版最多修掉角上 1dp）。
无描边：层与层靠色彩/渐变/阴影分离（参考图为软质 3D 风）。
渐变用 aapt:attr inline gradient（API 24+，minSdk 29 覆盖）；
pathData 仍全 M/L/Z（圆弧/圆角统一采样折线）；矢量 XML 与 PNG 渲染共用同一采样几何。
发射：drawable 三件套（含 monochrome 镂空层）+ anydpi XML×2 + 各密度 PNG。

v9.1（子代理审核修复）：①monochrome 剪影/镂空合并为单 pathData（nonZero 绕向只在单条
path 内生效，拆多个 <path> 反向环绕不成洞）；②矢量/PNG 棚下阴影统一画在墙体层之后，
消层级分歧；③扇贝加深帽加越界防护；④条纹/棚轮廓/加深帽弧采样统一 n=16；
⑤mono 墙体跟随前景圆角；⑥PNG 渲染 paste→alpha_composite 真混合（paste 是像素替换）。
"""
import os
from PIL import Image, ImageChops, ImageDraw

RES = os.path.join(os.path.dirname(__file__), "..", "..", "app", "src", "main", "res")
PREVIEW_DIR = "/tmp/linkdown_icon_preview"

# ---- 配色（对齐参考效果图） ----
BG       = "#F7E7C4"            # 背景奶油（比棚白条纹略深，保小尺寸分离）
ORANGE_0 = "#F99C3E"            # 棚橙条渐变亮端
ORANGE_1 = "#ED7C14"            # 棚橙条渐变深端
IVORY_0  = "#FFFAF0"            # 棚白条渐变亮端
IVORY_1  = "#F3DFB4"            # 棚白条渐变深端
BODY_0   = "#8A5731"            # 墙面渐变亮端
BODY_1   = "#6E421E"            # 墙面渐变深端
BASE_0   = "#63401F"            # 基座渐变亮端
BASE_1   = "#452A12"            # 基座渐变深端
FRAME    = "#4A2B11"            # 窗框/箭头/托盘深棕
SIGN_0   = "#FFFAEE"            # 招牌渐变亮端
SIGN_1   = "#F2E0B6"            # 招牌渐变深端
GLASS_L0, GLASS_L1 = "#F4D07E", "#D8A64A"   # 左窗金玻璃
GLASS_R0, GLASS_R1 = "#45B78C", "#1E8A65"   # 右窗绿玻璃
SHADOW_C = "#402B11"            # 暖棕阴影色（配 alpha 用）
# 顶棚立体感叠层（半透明，不改变轮廓）
FOLD_F = 0.20                   # 折面阴影带宽 / 条纹宽
CAP_CUT = 0.40                  # 扇贝弧加深起始（弧深比例，越越小帽越大）
CAP_ALPHA = "#26000000"         # 扇贝底加深（15% 黑）
FOLD_C0, FOLD_C1 = "#0F000000", "#26000000"   # 折面阴影上→下
HL_C0, HL_C1 = "#30FFFFFF", "#00FFFFFF"       # 棚顶高光上→下

# ---- 几何（108dp 视口，中心 54,54） ----
# 顶棚：梯形外扩 + 扇贝底摆
CT_Y0, CT_Y1 = 30.5, 38.0       # 棚顶缘 / 棚檐直线段底
CT_TOP_W, CT_BOT_W = 48.0, 62.0 # 顶宽 / 底摆宽（梯形外扩）
PANELS = 7                      # 条纹数：橙白橙白橙白橙（对称起止均橙）
SCALLOP_K = 0.38                # 扇贝弧深 = 弦长×K（浅弧布檐）
# 墙体 / 基座
BX0, BX1 = 28.0, 80.0           # 墙体（52 宽）
BY0, BY1 = 37.5, 68.5           # 墙顶藏于棚后
BSE_X0, BSE_X1 = 27.0, 81.0     # 基座（54 宽 > 墙体，「底部比中间长一点」）
BSE_Y0, BSE_Y1 = 68.5, 77.5
BASE_R = 4.0
BODY_R = 2.0
# 招牌 + 下载符号
SX0, SX1 = 40.0, 68.0           # 招牌 28×20（压在双窗上）
SY0, SY1 = 43.5, 63.5
SIGN_R = 4.0
SHAFX0, SHAFX1 = 51.2, 56.8     # 箭头杆
SHAFY0, SHAFY1 = 45.5, 50.0
HEAD = [(45.5, 50.0), (62.5, 50.0), (54.0, 57.0)]   # 箭头尖
TRAY = [(45.0, 58.0), (47.2, 58.0), (47.2, 59.6), (60.8, 59.6),
        (60.8, 58.0), (63.0, 58.0), (63.0, 61.5), (45.0, 61.5)]  # 接件托盘（U 形）
# 双橱窗（竖长，藏于招牌后，左右各露宽侧条+底条）
WY0, WY1 = 45.5, 67.0
WLX0, WLX1 = 30.0, 46.0
WRX0, WRX1 = 62.0, 78.0
WIN_R = 3.0
WIN_INSET = 2.2                 # 窗框厚

def fmt(v):
    s = f"{v:.2f}".rstrip("0").rstrip(".")
    return s if s else "0"

def q_sample(p0, c, p1, n=6):
    """二次贝塞尔采样 n 段（不含起点，含终点）——M/L/Z 约定下近似圆弧/圆角。"""
    out = []
    for i in range(1, n + 1):
        t = i / n
        u = 1 - t
        out.append((u * u * p0[0] + 2 * u * t * c[0] + t * t * p1[0],
                    u * u * p0[1] + 2 * u * t * c[1] + t * t * p1[1]))
    return out

def rounded_rect_pts(x0, y0, x1, y1, r, n=6):
    """顺时针（屏坐标）圆角矩形采样点列。"""
    pts = [(x0 + r, y0), (x1 - r, y0)]
    pts += q_sample((x1 - r, y0), (x1, y0), (x1, y0 + r), n)
    pts += [(x1, y1 - r)]
    pts += q_sample((x1, y1 - r), (x1, y1), (x1 - r, y1), n)
    pts += [(x0 + r, y1)]
    pts += q_sample((x0 + r, y1), (x0, y1), (x0, y1 - r), n)
    pts += [(x0, y0 + r)]
    pts += q_sample((x0, y0 + r), (x0, y0), (x0 + r, y0), n)
    return pts

def lerp(a, b, t):
    return a + (b - a) * t

def edge_x(t, top=True):
    """棚檐横向位置参数 t∈[0,1] → 梯形边上的 x（顶缘/底摆线性插值）。"""
    half_top, half_bot = CT_TOP_W / 2, CT_BOT_W / 2
    return (54 - (half_top if top else half_bot)) + (CT_TOP_W if top else CT_BOT_W) * t

def stripe_arc_pts(k, n=16):
    """第 k 条条纹的底摆扇贝弧采样点列（右→左）。"""
    t0, t1 = k / PANELS, (k + 1) / PANELS
    xlb, xrb = edge_x(t0, False), edge_x(t1, False)
    chord = xrb - xlb
    return q_sample((xrb, CT_Y1), ((xlb + xrb) / 2, CT_Y1 + chord * SCALLOP_K * 2),
                    (xlb, CT_Y1), n)

def stripe_pts(k):
    """第 k 条条纹：梯形四角 + 底摆扇贝弧（顺时针，弧与加深帽同一采样）。"""
    t0, t1 = k / PANELS, (k + 1) / PANELS
    xlt, xrt = edge_x(t0, True), edge_x(t1, True)
    xrb = edge_x(t1, False)
    return [(xlt, CT_Y0), (xrt, CT_Y0), (xrb, CT_Y1)] + STRIPE_ARCS[k]

STRIPE_ARCS = [stripe_arc_pts(k) for k in range(PANELS)]

def fold_pts(k):
    """第 k 条条纹右缘折面阴影带（沿梯形斜边的平行四边形）。"""
    t0, t1 = k / PANELS, (k + 1) / PANELS
    xrt, xrb = edge_x(t1, True), edge_x(t1, False)
    w_top = (edge_x(t1, True) - edge_x(t0, True)) * FOLD_F
    w_bot = (edge_x(t1, False) - edge_x(t0, False)) * FOLD_F
    return [(xrt, CT_Y0), (xrt - w_top, CT_Y0), (xrb - w_bot, CT_Y1), (xrb, CT_Y1)]

def scallop_cap_pts(k):
    """第 k 条扇贝弧底部加深帽：弧上 y≥割线 的段 + 割线回连。"""
    arc = STRIPE_ARCS[k]
    y_cut = CT_Y1 + (CT_BOT_W / PANELS) * SCALLOP_K * CAP_CUT
    below = [i for i, p in enumerate(arc) if p[1] >= y_cut]
    if not below or below[0] == 0 or below[-1] == len(arc) - 1:
        return []  # 割线不在弧 y 域内（常量被改出界）时不出帽

    def cross(a, b):
        t = (y_cut - a[1]) / (b[1] - a[1])
        return (a[0] + (b[0] - a[0]) * t, y_cut)

    i0, i1 = below[0], below[-1]
    return [cross(arc[i0 - 1], arc[i0])] + arc[i0:i1 + 1] + [cross(arc[i1], arc[i1 + 1])]

def top_hl_pts():
    """棚顶受光高光带（沿梯形顶缘的窄梯形）。"""
    y1 = CT_Y0 + 2.8
    f = (y1 - CT_Y0) / (CT_Y1 - CT_Y0)
    return [(edge_x(0, True), CT_Y0), (edge_x(1, True), CT_Y0),
            (lerp(edge_x(1, True), edge_x(1, False), f), y1),
            (lerp(edge_x(0, True), edge_x(0, False), f), y1)]

FOLD_PTS = [fold_pts(k) for k in range(PANELS)]
CAP_PTS = [scallop_cap_pts(k) for k in range(PANELS)]
TOP_HL = top_hl_pts()

def canopy_pts():
    """顶棚整轮廓（顺时针）：顶缘→右缘→底摆→扇贝弧链（右→左，复用条纹弧采样）→闭合。"""
    xlt, xrt = edge_x(0, True), edge_x(1, True)
    xrb = edge_x(1, False)
    chain = []
    for k in reversed(range(PANELS)):
        chain += STRIPE_ARCS[k]
    return [(xlt, CT_Y0), (xrt, CT_Y0), (xrb, CT_Y1)] + chain

STRIPE_PTS = [stripe_pts(k) for k in range(PANELS)]
CANOPY_PTS = canopy_pts()
BODY_PTS = rounded_rect_pts(BX0, BY0, BX1, BY1, BODY_R)
BASE_PTS = rounded_rect_pts(BSE_X0, BSE_Y0, BSE_X1, BSE_Y1, BASE_R)
SIGN_PTS = rounded_rect_pts(SX0, SY0, SX1, SY1, SIGN_R)
SIGN_SHADOW_PTS = rounded_rect_pts(SX0, SY0 + 1.3, SX1, SY1 + 1.3, SIGN_R)
WL_FRAME = rounded_rect_pts(WLX0, WY0, WLX1, WY1, WIN_R)
WR_FRAME = rounded_rect_pts(WRX0, WY0, WRX1, WY1, WIN_R)
WL_GLASS = rounded_rect_pts(WLX0 + WIN_INSET, WY0 + WIN_INSET,
                            WLX1 - WIN_INSET, WY1 - WIN_INSET, WIN_R - 1.2)
WR_GLASS = rounded_rect_pts(WRX0 + WIN_INSET, WY0 + WIN_INSET,
                            WRX1 - WIN_INSET, WY1 - WIN_INSET, WIN_R - 1.2)
# 斜向高光条（45° 白色半透明，收在玻璃内）
WL_STREAK = [(33.2, 64.5), (36.4, 64.5), (41.4, 48.0), (38.2, 48.0)]
WR_STREAK = [(65.2, 64.5), (68.4, 64.5), (73.4, 48.0), (70.2, 48.0)]
# 棚下柔和阴影（落在墙面/招牌顶部，轻）
CANOPY_SHADOW = [(32.0, CT_Y1), (76.0, CT_Y1), (76.0, 44.0), (32.0, 44.0)]

def to_path(pts):
    return "M" + " L".join(f"{fmt(x)},{fmt(y)}" for x, y in pts) + " Z"

def hexc(h, a=255):
    return (int(h[1:3], 16), int(h[3:5], 16), int(h[5:7], 16), a)

# ---- XML 发射 ----
def path_el(pts, grad=None, solid=None):
    """grad=(y0,y1,color0,color1)（aapt inline gradient）或 solid=(color,)；点列转 M/L/Z path。"""
    d = to_path(pts)
    if grad:
        y0, y1, ca, cb = grad
        return (f'    <path android:pathData="{d}">\n'
                f'        <aapt:attr name="android:fillColor">\n'
                f'            <gradient android:type="linear" android:startX="{fmt(pts[0][0])}"'
                f' android:startY="{fmt(y0)}" android:endX="{fmt(pts[0][0])}" android:endY="{fmt(y1)}"\n'
                f'                android:startColor="{ca}" android:endColor="{cb}" />\n'
                f'        </aapt:attr>\n'
                f'    </path>')
    return f'    <path android:fillColor="{solid[0]}" android:pathData="{d}" />'

def fg_xml():
    out = []
    out.append(path_el(BODY_PTS, grad=(BY0, BY1, BODY_0, BODY_1)))
    out.append(path_el(CANOPY_SHADOW,
                       grad=(CT_Y1, max(p[1] for p in CANOPY_SHADOW),
                             "#28402B11", "#00000000")))
    for k in range(PANELS):
        c0, c1 = (ORANGE_0, ORANGE_1) if k % 2 == 0 else (IVORY_0, IVORY_1)
        out.append(path_el(STRIPE_PTS[k],
                           grad=(CT_Y0, CT_Y1 + (CT_BOT_W / PANELS) * SCALLOP_K, c0, c1)))
    for pts in FOLD_PTS:
        out.append(path_el(pts, grad=(CT_Y0, CT_Y1, FOLD_C0, FOLD_C1)))
    for pts in CAP_PTS:
        if pts:
            out.append(path_el(pts, solid=(CAP_ALPHA,)))
    out.append(path_el(TOP_HL, grad=(CT_Y0, CT_Y0 + 2.8, HL_C0, HL_C1)))
    out.append(path_el(WL_FRAME, solid=(FRAME,)))
    out.append(path_el(WR_FRAME, solid=(FRAME,)))
    out.append(path_el(WL_GLASS, grad=(WY0, WY1, GLASS_L0, GLASS_L1)))
    out.append(path_el(WR_GLASS, grad=(WY0, WY1, GLASS_R0, GLASS_R1)))
    out.append(path_el(WL_STREAK, solid=("#38FFFFFF",)))
    out.append(path_el(WR_STREAK, solid=("#38FFFFFF",)))
    out.append(path_el(SIGN_SHADOW_PTS, solid=("#40" + SHADOW_C[1:],)))
    out.append(path_el(SIGN_PTS, grad=(SY0, SY1, SIGN_0, SIGN_1)))
    out.append(path_el([(SHAFX0, SHAFY0), (SHAFX1, SHAFY0), (SHAFX1, SHAFY1), (SHAFX0, SHAFY1)],
                       solid=(FRAME,)))
    out.append(path_el(HEAD, solid=(FRAME,)))
    out.append(path_el(TRAY, solid=(FRAME,)))
    out.append(path_el(BASE_PTS, grad=(BSE_Y0, BSE_Y1, BASE_0, BASE_1)))
    return "\n".join(out)

XML_FG = f'''<?xml version="1.0" encoding="utf-8"?>
<!-- 启动图标前景层：光泽小卖部门面（梯形橙白棚+双橱窗+下载招牌+宽基座），收在常见蒙版圆内。 -->
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:aapt="http://schemas.android.com/aapt"
    android:width="108dp"
    android:height="108dp"
    android:viewportWidth="108"
    android:viewportHeight="108">
{fg_xml()}
</vector>
'''

XML_BG = f'''<?xml version="1.0" encoding="utf-8"?>
<!-- 启动图标背景层：奶油纯色（蒙版外同色延伸无割裂） -->
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="108dp"
    android:height="108dp"
    android:viewportWidth="108"
    android:viewportHeight="108">
    <path android:fillColor="{BG}" android:pathData="M0,0 L108,0 L108,108 L0,108 Z" />
</vector>
'''

# 单色层：剪影（顺时针）+镂空（逆时针）必须合入同一条 pathData——nonZero 绕向只在
# 单条 path 内生效，拆成多个 <path> 各自独立绘制、反向环绕不产生洞（审核 P0 修复）
MONO_ARROW = [(SHAFX0, SHAFY0), (SHAFX1, SHAFY0), (SHAFX1, SHAFY1),
              HEAD[1], HEAD[2], HEAD[0], (SHAFX0, SHAFY1)]
MONO_BODY = rounded_rect_pts(BX0, BY0, BX1, BY1, BODY_R)
MONO_HOLES = [list(reversed(MONO_ARROW)), list(reversed(TRAY)),
              [(WLX0, WY0), (WLX0, WY1), (WLX1, WY1), (WLX1, WY0)],
              [(WRX0, WY0), (WRX0, WY1), (WRX1, WY1), (WRX1, WY0)]]
MONO_D = " ".join(to_path(pts) for pts in [CANOPY_PTS, MONO_BODY, BASE_PTS] + MONO_HOLES)

XML_MONO = f'''<?xml version="1.0" encoding="utf-8"?>
<!-- 主题图标单色层：棚+墙+基座剪影与箭头/托盘/橱窗镂空合入单 pathData（nonZero 绕向成洞，
     启动器自行着色） -->
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="108dp"
    android:height="108dp"
    android:viewportWidth="108"
    android:viewportHeight="108">
    <path android:fillType="nonZero" android:fillColor="#000000" android:pathData="{MONO_D}" />
</vector>
'''

XML_ADAPTIVE = '''<?xml version="1.0" encoding="utf-8"?>
<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
    <background android:drawable="@drawable/ic_launcher_background" />
    <foreground android:drawable="@drawable/ic_launcher_foreground" />
    <monochrome android:drawable="@drawable/ic_launcher_monochrome" />
</adaptive-icon>
'''

def emit():
    drawable = os.path.join(RES, "drawable")
    anydpi = os.path.join(RES, "mipmap-anydpi-v26")
    os.makedirs(drawable, exist_ok=True)
    os.makedirs(anydpi, exist_ok=True)
    open(os.path.join(drawable, "ic_launcher_background.xml"), "w").write(XML_BG)
    open(os.path.join(drawable, "ic_launcher_foreground.xml"), "w").write(XML_FG)
    open(os.path.join(drawable, "ic_launcher_monochrome.xml"), "w").write(XML_MONO)
    for name in ("ic_launcher.xml", "ic_launcher_round.xml"):
        open(os.path.join(anydpi, name), "w").write(XML_ADAPTIVE)
    print("矢量 XML 已发射：background/foreground/monochrome + anydpi x2")

# ---- PNG：与矢量同一几何，864 超采样 + 渐变合成 ----
SS = 864
K = SS / 108

def vgrad_layer(y0, y1, c0, c1):
    """整幅竖向线性渐变层（1×H 生成后拉伸），y0..y1 之间插值、之外钳制。"""
    row = bytearray()
    for j in range(SS):
        y = (j + 0.5) / K
        t = max(0.0, min(1.0, (y - y0) / max(1e-6, y1 - y0)))
        row += bytes(round(lerp(c0[i], c1[i], t)) for i in range(4))
    strip = Image.frombytes("RGBA", (1, SS), bytes(row))
    return strip.resize((SS, SS), Image.NEAREST)

def solid_layer(c):
    return Image.new("RGBA", (SS, SS), c)

def composite(base, layer, pts=None, rrect=None, radius=0):
    """形状内 alpha 混合：layer 自身 alpha × 形状 mask，正叠到 base 上。"""
    mask = Image.new("L", (SS, SS), 0)
    dm = ImageDraw.Draw(mask)
    if pts:
        dm.polygon([(x * K, y * K) for x, y in pts], fill=255)
    else:
        dm.rounded_rectangle(rrect, radius=radius * K, fill=255)
    layer.putalpha(ImageChops.multiply(layer.getchannel("A"), mask))
    base.alpha_composite(layer)

def render_canvas():
    im = Image.new("RGBA", (SS, SS), hexc(BG))
    composite(im, vgrad_layer(BY0, BY1, hexc(BODY_0), hexc(BODY_1)),
              rrect=[BX0 * K, BY0 * K, BX1 * K, BY1 * K], radius=BODY_R)
    sh_xs = [p[0] for p in CANOPY_SHADOW]
    sh_ys = [p[1] for p in CANOPY_SHADOW]
    composite(im, vgrad_layer(min(sh_ys), max(sh_ys), hexc(SHADOW_C, 0x28), (0, 0, 0, 0)),
              rrect=[min(sh_xs) * K, min(sh_ys) * K, max(sh_xs) * K, max(sh_ys) * K])
    for pts in (WL_FRAME, WR_FRAME):
        composite(im, solid_layer(hexc(FRAME)), pts=pts)
    composite(im, vgrad_layer(WY0, WY1, hexc(GLASS_L0), hexc(GLASS_L1)), pts=WL_GLASS)
    composite(im, vgrad_layer(WY0, WY1, hexc(GLASS_R0), hexc(GLASS_R1)), pts=WR_GLASS)
    composite(im, solid_layer((255, 255, 255, 0x38)), pts=WL_STREAK)
    composite(im, solid_layer((255, 255, 255, 0x38)), pts=WR_STREAK)
    composite(im, solid_layer(hexc(SHADOW_C, 0x40)),
              rrect=[SX0 * K, (SY0 + 1.3) * K, SX1 * K, (SY1 + 1.3) * K], radius=SIGN_R)
    composite(im, vgrad_layer(SY0, SY1, hexc(SIGN_0), hexc(SIGN_1)),
              rrect=[SX0 * K, SY0 * K, SX1 * K, SY1 * K], radius=SIGN_R)
    composite(im, solid_layer(hexc(FRAME)),
              rrect=[SHAFX0 * K, SHAFY0 * K, SHAFX1 * K, SHAFY1 * K])
    composite(im, solid_layer(hexc(FRAME)), pts=HEAD)
    composite(im, solid_layer(hexc(FRAME)), pts=TRAY)
    for k in range(PANELS):
        if k % 2 == 0:
            continue
        chord = CT_BOT_W / PANELS
        composite(im, vgrad_layer(CT_Y0, CT_Y1 + chord * SCALLOP_K,
                                  hexc(IVORY_0), hexc(IVORY_1)), pts=STRIPE_PTS[k])
    for k in range(PANELS):
        if k % 2 == 1:
            continue
        chord = CT_BOT_W / PANELS
        composite(im, vgrad_layer(CT_Y0, CT_Y1 + chord * SCALLOP_K,
                                  hexc(ORANGE_0), hexc(ORANGE_1)), pts=STRIPE_PTS[k])
    for pts in FOLD_PTS:
        composite(im, vgrad_layer(CT_Y0, CT_Y1, (0, 0, 0, 0x0F), (0, 0, 0, 0x26)), pts=pts)
    for pts in CAP_PTS:
        if pts:
            composite(im, solid_layer((0, 0, 0, 0x26)), pts=pts)
    composite(im, vgrad_layer(CT_Y0, CT_Y0 + 2.8, (255, 255, 255, 0x30), (255, 255, 255, 0)),
              pts=TOP_HL)
    composite(im, vgrad_layer(BSE_Y0, BSE_Y1, hexc(BASE_0), hexc(BASE_1)),
              rrect=[BSE_X0 * K, BSE_Y0 * K, BSE_X1 * K, BSE_Y1 * K], radius=BASE_R)
    return im

def render_mono():
    im = Image.new("RGBA", (SS, SS), hexc("#E7E3DA"))
    dark = hexc("#4A4A4A")
    composite(im, solid_layer(dark), pts=CANOPY_PTS)
    composite(im, solid_layer(dark), pts=MONO_BODY)
    composite(im, solid_layer(dark),
              rrect=[BSE_X0 * K, BSE_Y0 * K, BSE_X1 * K, BSE_Y1 * K], radius=BASE_R)
    for hole in (list(reversed(MONO_ARROW)), list(reversed(TRAY)),
                 [(WLX0, WY0), (WLX0, WY1), (WLX1, WY1), (WLX1, WY0)],
                 [(WRX0, WY0), (WRX0, WY1), (WRX1, WY1), (WRX1, WY0)]):
        composite(im, solid_layer(hexc("#E7E3DA")), pts=hole)
    return im.convert("RGB")

def masked(im, shape, size):
    im2 = im.convert("RGBA").resize((size, size), Image.LANCZOS)
    m = Image.new("L", (size, size), 0)
    dm = ImageDraw.Draw(m)
    if shape == "circle":
        dm.ellipse([0, 0, size, size], fill=255)
    elif shape == "rounded":
        dm.rounded_rectangle([0, 0, size, size], radius=int(size * 0.225), fill=255)
    else:  # squircle：超椭圆 n=5 采样
        import math
        pts = []
        c = size / 2
        n = 5.0
        for i in range(360):
            a = i * math.pi / 180
            ct, st = math.cos(a), math.sin(a)
            r = (abs(ct) ** n + abs(st) ** n) ** (-1 / n)
            pts.append((c + c * r * ct, c + c * r * st))
        dm.polygon(pts, fill=255)
    im2.putalpha(m)
    return im2

def emit_previews(im, mono_im):
    os.makedirs(PREVIEW_DIR, exist_ok=True)
    im.resize((432, 432), Image.LANCZOS).save(f"{PREVIEW_DIR}/design_432.png")
    mono_im.resize((192, 192), Image.LANCZOS).save(f"{PREVIEW_DIR}/monochrome_192.png")
    sizes = [192, 108, 72, 48]
    for s in sizes:
        for shape in ("square", "circle", "rounded", "squircle"):
            if shape == "square":
                tile = im.convert("RGBA").resize((s, s), Image.LANCZOS)
            else:
                tile = masked(im, shape, s)
            tile.save(f"{PREVIEW_DIR}/launcher_{s}_{shape}.png")
    # 对照表：深浅两种桌面底 × 各尺寸（圆/圆角方/超椭圆/方形）+ 单色层（尺寸自适应不溢出）
    margin, inner_gap, group_gap = 40, 16, 36
    widths = [4 * s + 3 * inner_gap for s in sizes]
    W = 2 * margin + sum(widths) + (len(sizes) - 1) * group_gap
    sheet = Image.new("RGB", (W, 850), c2hex("#777777"))
    for row, bgc in enumerate(("#F2F2F2", "#2B2B2B")):
        d = ImageDraw.Draw(sheet)
        d.rectangle([16, 16 + row * 320, W - 16, 316 + row * 320], fill=c2hex(bgc))
        x = margin
        for s in sizes:
            y = 166 + row * 320 - s // 2
            for shape in ("circle", "rounded", "squircle", "square"):
                if shape == "square":
                    t = im.convert("RGBA").resize((s, s), Image.LANCZOS)
                else:
                    t = masked(im, shape, s)
                sheet.paste(t, (x, y), t)
                x += s + inner_gap
            x += group_gap - inner_gap
    mt = mono_im.resize((192, 192), Image.LANCZOS)
    sheet.paste(mt, (margin, 650))
    sheet.save(f"{PREVIEW_DIR}/preview_sheet.png")
    print(f"预览已输出：{PREVIEW_DIR}/")

def c2hex(h):
    return tuple(int(h[i:i + 2], 16) for i in (1, 3, 5))

def render_pngs(im):
    im = im.convert("RGB")
    for name, px in [("mdpi", 48), ("hdpi", 72), ("xhdpi", 96), ("xxhdpi", 144), ("xxxhdpi", 192)]:
        out = os.path.join(RES, f"mipmap-{name}")
        os.makedirs(out, exist_ok=True)
        small = im.resize((px, px), Image.LANCZOS)
        small.save(os.path.join(out, "ic_launcher.png"))
        small.save(os.path.join(out, "ic_launcher_round.png"))
    print("PNG 兜底已渲染（48/72/96/144/192）")

if __name__ == "__main__":
    emit()
    canvas = render_canvas()
    mono = render_mono()
    render_pngs(canvas)
    emit_previews(canvas, mono)
