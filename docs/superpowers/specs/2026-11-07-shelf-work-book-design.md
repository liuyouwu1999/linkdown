# 书架「一个作品=一本书」设计规格（v2.1）

日期：2026-11-07
状态：v1 评审（P0×2/P1×6/P2×4）→ v2 全面修订 → 复审 **PASS**（余 P1×2/P2×7）→ v2.1 补入复审建议；v2.2 修正 §6 matched==1 语义内在矛盾（不认领——matched==1 不产生组无重复渲染，文件按 Single 展示，与 §11「剩 1 页按 Single」对齐）；v2.3 注记 pagesSummary 纯视频页不打『含视频』标为已裁决实现口径（§7 措辞按此理解）。
v1→v2 修订摘要：matched==1 保留条目不再清理（修 P0-1 自相矛盾）；groupShelf 改 Uri 无关投影（修 P0-2 JVM 不可测）；台账取数改为 ingest 回查 MediaStore 真实名（P1-1）；ShelfLedger 改类实例 + PipelineEnv 缝（P1-2）；merge 改每模式增量（P1-3）；输出序写死（P1-4）；补 MainScreen 改动面（P1-5）；页序按文件名自然序（P1-6）；P2 四项全部采纳（摘要按文件名识别实况/类别键与 Single 统一 ordinal、同标题判重局限入边界表、列表行 sizeBytes 与头部计数口径、URL 兜底退化标注）。

## 1. 背景与问题

书架（`ui/ShelfStore.kt`）是对 `MediaStore.Downloads` 的实时查询：**一个文件 = 一本书**。
下载引擎侧同一作品的多个文件之间没有任何落盘的共享标识（awemeId 只存在于内存
`ProbeInfo`，不进文件名/不入库），因此：

- 图文 9 图 → 9 本书；
- 视频+音频+封面三种模式 → 3 本书。

这与队列页「一条链接一张卡」的单位不一致，书架被同作品的零散文件占满。

## 2. 目标 / 非目标

**目标**

- 书架以「作品」为单位展示：同一作品的全部文件合成一本书，角标 ×N，点开翻页浏览。
- 下架以作品为粒度：一次删掉该书全部文件。
- 已下载的旧文件（无台账）保持现状，逐本显示，行为不劣化。

**非目标**

- 不改下载文件名、目录、相册可见性（分享体验完全不变）。
- 不改下载判重（`DedupCheck` 逻辑不动；其「同标题前缀互撞」已知局限随之保留，见 §11）。
- 不改队列页。
- 不做重命名、云端同步、跨设备迁移。

## 3. 已拍板决策

1. 书架展示单位：**一个作品 = 一本书**（角标 ×N，点击打开阅读器翻页）。
2. 绑定方式：**App 内部台账**（DataStore 记录 作品键→文件清单），文件名/目录不动；
   书架查询时与 MediaStore 实存对账，外部删除的文件自动从书里少一页，全删整书消失。
3. 代价已知情：重装/清 App 数据后台账丢失，旧书退回逐本显示（与今天一致，不会更糟）。

## 4. 架构总览

```
下载引擎 → SingleDownloadPipeline
              │  每模式逐文件 ingest（现状不变）
              ├─► MediaStore.Downloads（文件落盘，现状不变）
              │        └─ ingest 回查该 Uri 的 DISPLAY_NAME/RELATIVE_PATH（新）
              └─► env.ledgerMerge(...)（新：每模式增量 merge 台账）

MainActivity.refreshShelf()
  ├─ ShelfStore.query()          // 现状不变（投影补带出 RELATIVE_PATH）
  ├─ ledger.load()               // 新：台账
  ├─ groupShelf(keys, ledger)    // 新：Uri 无关纯函数合书 + 对账
  └─ shelf: List<ShelfEntry>     // Single（=现 ShelfItem 单页）/ Bound（作品组）
```

## 5. ShelfLedger（台账）

新文件 `app/src/main/java/top/linkdown/android/ui/ShelfLedger.kt`（与 ShelfStore 同包）。

- **形态**：类实例 `ShelfLedger.create(file: File, scope: CoroutineScope)`（与
  `QueuePersistence.create` 同款；同一文件绝不允许第二个活跃 DataStore 实例）。
  `LinkdownApplication` 持单例；pipeline 经缝使用（见下）。
- **存储**：DataStore Preferences，独立文件 `filesDir/datastore/shelf_ledger.preferences_pb`，
  单键 `shelf_groups` = JSON 数组字符串。序列化手写 `kotlinx.serialization.json`
  JsonObject（与 `QueuePersistence` 同风格），编解码抽成纯对象 `ShelfLedgerCodec`
  供 JVM 直测。
- **数据结构**：

  ```kotlin
  data class FileRef(val path: String, val name: String)   // path=RELATIVE_PATH（含尾斜杠）
  data class LedgerMerge(val workKey: String, val platform: String,
                         val title: String, val files: List<FileRef>)
  data class LedgerEntry(val workKey: String, val platform: String, val title: String,
                         val files: List<FileRef>, val updatedAt: Long)
  ```

  类型归属：`LedgerMerge/FileRef/LedgerEntry` 定义在 **pipeline 包**（与 `PipelineEnv`
  同处，避免 pipeline→ui 反向依赖）；`ShelfLedger.kt`(ui) 引用之。

- **workKey**：`ProbeInfo.mediaId` 非空白 → `"<platform>:<mediaId>"`（抖音=awemeId）；
  否则 `"<platform>:url:<sourceUrl 去 query/fragment/尾斜杠>"`。
  已知退化：汽水 mediaId 可空、yt-dlp 以 URL 充当 mediaId——短链/带参变体可能
  同作品不同键，可接受（§11 边界表）。
- **取数来源（关键）**：MediaStore 对同名 DISPLAY_NAME 会自动追加「 (1)」类后缀
  （Android 10+ scoped storage 行为），真实名 ≠ 本地临时文件名。**台账一律以 ingest
  成功后按返回 Uri 回查的 DISPLAY_NAME/RELATIVE_PATH 为准**（见 §5a），不用本地文件名。
- **API（实例方法）**：
  - `suspend fun load(): List<LedgerEntry>`（键缺失/JSON 损坏 → 空表，不抛）
  - `suspend fun merge(input: LedgerMerge)`：**单次 `dataStore.edit{}` 内读-改-写**
    （原子，防 pipeline merge 与对账清理两个并发写者丢更新）；workKey 已存在 →
    按 (path,name) 去重追加 files、覆盖 title/updatedAt；不存在 → 追加。
    写入前 files 按文件名自然序（数值感知）排序持久化。
    合并逻辑抽成纯函数 `mergeEntries(existing, input): List<LedgerEntry>`（JVM 直测），
    edit lambda 只做「读旧值 → mergeEntries → 写回」。
  - `suspend fun removeKeys(keys: Set<String>)`：同样单 edit 原子读改写。
- **写入时机**：**每模式 ingest 完成后增量 merge**（files=该模式实际入库成功的文件；
  0 文件不写）。`runCatching` 包裹，失败仅记日志，**不影响下载结果**（台账是增强
  信息）。主流程任一模式失败上抛时，已入库模式已各自 merge 过——部分失败场景天然覆盖。
- **pipeline 注入缝**：`PipelineEnv` 新增挂起函数属性
  `var ledgerMerge: suspend (LedgerMerge) -> Unit`（仿 `dedup` 的 override var 模式）；
  主码默认实现调 Application 单例；测试注入记录器断言参数。

## 5a. MediaStoreIngest 改动

- `ingest` 返回值从 `Uri` 扩为
  `data class IngestedFile(val uri: Uri?, val displayName: String, val relPath: String)`
  （定义在 ingest 包）：拷贝完成、`IS_PENDING=0` 后按 insert 返回的 Uri 回查
  DISPLAY_NAME/RELATIVE_PATH 一次。**uri 可空**：生产路径恒非空（compat run 与
  hooks.onIngested 消费 Uri），JVM 假件返回 `IngestedFile(null, src.name, "Download/链接下载/$platform/")`。
  回查万一查空（理论不应发生）：回退 `displayName=src.name` + 构造 relPath
  （`Download/链接下载/<platformDir>/`），记日志，不按 ingest 失败处理。
  拷贝失败回滚（delete uri）路径不变。

## 6. 合书：`groupShelf`（Uri 无关纯函数）与展示模型

**ShelfItem**：增加 `relPath: String` 字段（query 投影本就取了 RELATIVE_PATH，补放进
data class）；匹配键与台账 `FileRef.path` 同形（含尾斜杠）。

**Uri 无关投影**（JVM 直测形态，与 `ShelfSortTest` 规避 android.Uri 的既有手法一致）：

```kotlin
data class ShelfKey(val id: String, val relPath: String, val name: String,
                    val kind: ShelfKind, val dateAdded: Long)
sealed interface Group { val id: String; val dateAdded: Long }
data class SingleGroup(val key: ShelfKey) : Group
data class BoundGroup(val workKey: String, val title: String, val platform: String,
                      val keys: List<ShelfKey>) : Group   // 页序=台账序（已自然序）
data class GroupingResult(val groups: List<Group>, val staleKeys: Set<String>)
```

`groupShelf(keys: List<ShelfKey>, ledger: List<LedgerEntry>): GroupingResult` 算法：

1. 索引 `Map<(relPath, name), ShelfKey>`。
2. 逐台账条目按 files 顺序查索引（已被先前条目认领的文件先到先得，不再认领）：
   - matched ≥ 2 → **BoundGroup**（页序=台账序）；matched 全部标记认领。
   - matched == 1 → **条目保留、不清理、不认领**（matched==1 不产生任何组，无重复渲染
     问题；该文件经第 3 步按 Single 展示。之后补下其他模式时 merge 按 workKey 找到
     条目追加并组）。
   - matched == 0 → workKey 记入 staleKeys。
3. 未认领的 ShelfKey → 各自 SingleGroup（旧文件/台账丢失，行为同今天）。
4. **输出序**：按 dateAdded DESC（Bound=max(keys.dateAdded)），同值按输入原序稳定
   排序——查询本身 DATE_ADDED DESC，TIME 排序的恒等映射语义由此保住。

**不变量**：stale 只含 matched==0 的条目；matched==1 条目保留（这是 v1 的 P0 修复：
v1 把 matched==1 清掉，导致「单模式首购→下轮查询被清→补下永远无法并组」的自毁循环）。

**UI 装配**（ShelfStore 内，非纯函数层）：key→ShelfItem 映射回 Uri，产出展示模型：

```kotlin
sealed interface ShelfEntry {
    val id: String          // Single=uri.toString()；Bound=workKey
    val title: String       // Bound=台账 title（空回退首页文件名）
    val platform: String
    val dateAdded: Long     // Bound=组内最新文件
    val sizeBytes: Long     // Bound=Σ页（列表行汇总大小用）
    val pages: List<ShelfItem>  // Single 恒 1 页
}
```

**封面**：Bound 按页序取第一个 kind==VIDEO 的页（复用 `loadCover` 视频掐帧路径）；
无视频取第一个 kind==IMAGE 的页并**直接 BitmapFactory 解码该页**（`loadCover` 对
IMAGE 只查伴生封面文件，图文 Bound 会得占位块——故 Bound 封面分支不走 loadCover
的 IMAGE 路径）；再退首页。

**命名冲突安全**：两个同标题作品的文件经 MediaStore 自动改名后 DISPLAY_NAME 不同，
按 (path,name) 精确匹配不会误并；且台账以回查名为准，不依赖改名时机。

## 7. 书架 UI（ShelfPage.kt）

- **BookCell（书架模式）**：Bound 书脊标题=作品标题，封面规则见 §6；
  信息浮条加「×N」页数角标；浮条摘要 = 纯函数
  `pagesSummary(names: List<String>, kinds: List<ShelfKind>)`（Uri 无关投影，
  UI 层以 pages 调用）：「N 页」+ 按序拼接「含实况 / 含视频 / 含音频 / N 图」
  （纯视频页不打『含视频』标——已裁决口径）。
  **实况按文件名识别**（任一页名以 `.MP.jpg` 结尾——kindOf 把它判成 IMAGE，
  kind 集合推不出实况）；音频页计入摘要；**实况页计入 N 图**（按 kind 计数，
  实况只是附加标签）。
- **列表模式**（shelfBoardMode=false）：Bound 渲染为一行「作品标题 (N 个文件)」，
  大小列=Σ页 sizeBytes（走现有 formatSize）；单击同样打开阅读器。
- **头部「货架 · 全部 N 件」**：N=分组后条目数（书数，非文件数）。
- 单击 Bound → 打开阅读器；长按 → 进入多选（Bound 作为一格参与，现有
  `onEnterSelection` 流程不变）。排序头、空柜搁板、翻页交互不动。

## 8. BookReader 阅读器（新文件 `ui/BookReader.kt`）

- 全屏覆盖层（MainActivity 级持有状态，书架页与主页出货区共用），顶栏=作品标题+关闭，
  底部页码「第 n/N 页」。
- 主体横向 Pager 翻页；`HorizontalPager`（Compose Foundation，项目已有 Compose 依赖）。
- 页类型行为：
  - 图片页（含 `.MP.jpg` 实况合成图，按静图显示——动态效果归相册/播放器管）：
    BitmapFactory 按屏幕尺寸降采样解码（复用/对齐 loadCover 的解码风格）。
  - 视频/音频页：显示「▶ 播放」占位，点击走现有 `ShelfStore.play(context, item)`
    Resolver 路径，阅读器保持打开。
  - 其他类型页（OTHER）：文件名占位，点击同样走 play。
- 打开锚点：单击 Bound 固定从第 1 页进入。
- 初始实现不含双指缩放、不含竖向翻页。

## 9. 下架

- Bound 单删确认文案：「「标题」将从下载目录删除 **N 个文件**，不可恢复。」
  确认后循环 `ShelfStore.delete(item)` 删全部页（现有 API，逐文件删），重查书架。
  循环中某页返回 false：失败页名并入现有「下架失败：name」日志口径，成功页不回滚；
  剩余页下轮对账自然收敛（≥2 仍成组、1 页按 Single、0 页清条目）。
  **台账不同步删**：全页删光后下轮对账 matched==0 → stale → `removeKeys` 自动清理。
- 多选批量：Bound 算一个选择项，删除其全部文件；结果日志沿用现有
  「批量下架完成：N 件」口径（一件=一本书）。
- 队列页「删记录」口径不变（不动货架产物）。

## 10. 排序（ShelfPage.kt 纯函数扩展，Uri 无关键序列投影，UI 层以 entries 调用）

- **时间**：恒等映射（groupShelf 输出序已按 dateAdded DESC，Bound=组内最新文件）。
- **名称**：title 小写序。
- **类别**：组内最高 kindOf ordinal 主键（VIDEO > AUDIO > IMAGE > OTHER，**与 Single
  现行 ordinal 规则一致**——音频+封面书归音频类），title 次键。

单文件的 Single 语义与现状完全一致。

## 11. 错误处理与边界

| 场景 | 行为 |
|---|---|
| 台账 JSON 损坏/键缺失 | load 返回空表，书架全按 Single 显示（不劣化） |
| 外部删了部分文件 | 对账少页；剩 1 页按 Single 展示、**条目保留**（补下可恢复成组） |
| 外部删光 | 整书消失；条目 matched==0 → 对账清理 |
| 重装/清数据 | 台账丢失，全部退化为 Single（用户已知情） |
| 同标题不同作品 | (path,name) 回查名匹配不误并；⚠️ 判重前缀互撞可能把第二个作品**整本拦截为 Exists**（DedupCheck 已知局限，非本规格引入，验收勿误判为合书 bug） |
| 模式 0 文件失败 | 该模式不写台账；下次补下按 workKey 并组 |
| 模式中途失败（第 k/N 张） | try/finally 半程合并：已入库部分纳入台账（少页书）；补下受判重前缀拦截可能补不齐缺页（DedupCheck 已知局限，如实接受） |
| 台账写失败 | runCatching 记日志，下载不受影响 |
| 同名冲突 | MediaStore 自动改名；台账以回查名为准，不依赖改名时机 |
| 汽水/yt-dlp 无稳定 mediaId | URL 兜底；短链/带参变体可能同作品不同键（已知退化，可接受） |
| 并发写台账 | merge/removeKeys 均单 dataStore.edit 原子读改写 |

## 12. 测试计划（JVM，全部 Uri 无关）

1. `ShelfLedgerCodecTest`：encode/decode 往返、损坏 JSON → 空表、`mergeEntries`
   纯函数（去重追加+覆盖 title/updatedAt）、自然序排页（`标题-2` < `标题-10` 数值序）。
2. `GroupShelfTest`（ShelfKey 投影输入）：≥2 成组且页序=台账序；1 页保留条目按
   Single 展示；0 页 stale；未认领文件成 Single；两组同 title 不同 workKey 不误并；
   (path,name) 跨目录不串组；输出序 dateAdded DESC + 同值输入序稳定。
3. 排序扩展（现有 ShelfSort 测试文件内补）：Bound 的时间/名称/类别键（音频+封面
   归音频类）。
4. `pagesSummary`：kind+文件名联合文案（`.MP.jpg` 识别实况、音频计入、N 图计数）。
5. Pipeline 台账缝：假 PipelineEnv 注入记录器（ingest 假件返回
   `IngestedFile(null, …)`），断言 workKey/文件清单/每模式增量调用/半程合并/
   台账失败不影响主流程。
6. ingest 回查逻辑薄，JVM 以接口缝假件覆盖（真机验收实际改名场景）。

真机 UI（书架角标、阅读器翻页、下架文案、列表模式、出货区）由用户验收。

## 13. 涉及文件

| 文件 | 动作 |
|---|---|
| `ui/ShelfLedger.kt` | 新增（codec/mergeEntries 纯函数 + DataStore 类实例） |
| `ui/BookReader.kt` | 新增（阅读器） |
| `ui/ShelfStore.kt` | 修改（ShelfItem+relPath、ShelfKey/Group 投影、groupShelf、ShelfEntry 装配） |
| `ui/ShelfPage.kt` | 修改（BookCell 角标/摘要、列表行、头部计数、排序键、阅读器入口、删除文案） |
| `ui/MainScreen.kt` | 修改（shelf 计数/今日出货/`ShipmentArea` 改以条目为单位；出货区点击 Bound=开阅读器） |
| `MainActivity.kt` | 修改（分组数据接线、stale 清理、阅读器状态供书架+主页共用、下架删组） |
| `pipeline/SingleDownloadPipeline.kt` | 修改（每模式增量 `env.ledgerMerge`） |
| PipelineEnv 定义处 | 修改（`ledgerMerge` 缝 + `LedgerMerge`/`FileRef`/`LedgerEntry` 类型定义，仿 dedup override var） |
| `ingest/MediaStoreIngest.kt` | 修改（返回 `IngestedFile` 回查真实名） |
| `LinkdownApplication.kt` | 修改（台账单例，沿用队列持久化模式） |
| `app/src/test/...` | 新增/扩展上述测试 |

版本纪律：随下一个四段版本发版（本规格不含版本号，实现计划里定 bump）。
