# 书架「一个作品=一本书」实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use subagent-driven-development (recommended) or executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 书架从「一个文件=一本书」改为「一个作品=一本书」：App 内部台账绑定同作品文件、合书展示 ×N、阅读器翻页、作品粒度下架。

**Architecture:** 下载管道每模式 ingest 后增量写 DataStore 台账（作品键→文件清单，文件名用 MediaStore 回查真名）；书架查询后用 Uri 无关纯函数 `groupShelf` 对账合书（外部删除自动少页/退化/清理）；UI 层 `ShelfEntry` 模型渲染，Bound 单击进 BookReader 横向翻页，删除整组循环删文件后靠对账清台账。

**Tech Stack:** Kotlin + Jetpack Compose（现有）、DataStore Preferences（现有模式）、kotlinx.serialization.json 树形 API（现有风格）、JVM JUnit4（现有）。

**Spec:** `docs/superpowers/specs/2026-11-07-shelf-work-book-design.md`（v2.2，复审 PASS；v2.2 修正 §6 matched==1 语义=保留、不认领）

**发版不在本计划内**：全绿 + 模拟器验收后，按用户流程走子代理终审/真机测试/四段版本 bump。

## Global Constraints

- 开发仓：`/Users/liuyouwu/Desktop/个人研究/linkdown-m1-handoff/linkdown-android`；JAVA_HOME=openjdk@17（`export JAVA_HOME=$(/usr/libexec/java_home -v 17)`）。
- AGP 9.1.1 增量编译会吞主码改动：**每次构建前 `./gradlew clean`**，或核对 class 时间戳。
- JVM 测试禁触碰 android 桩（`android.util.Log`/`Uri` 构造会抛 "not mocked"）：新纯函数一律 Uri 无关投影（`ShelfKey`/names/kinds），与 `shelfSortedIndices` 同款手法。
- 台账写路径 `runCatching` 包裹，任何失败仅记日志（`android.util.Log` 调用自身套 `runCatching`），**不影响下载结果**。
- merge/removeKeys 每次调用 = **单次 `dataStore.edit{}`**（原子读改写）。
- 不改：DedupCheck 判重逻辑、下载文件命名、队列页、全局 PixelButton、`ShelfStore.play`/`delete` 现有行为。
- 提交信息中文 `feat:/test:/docs:` 风格；每个任务收尾独立 commit；**本计划不 push GitHub**。
- 平台常量事实：`ShelfStore.ROOT = "Download/链接下载"`；`ShelfKind` ordinal 序 VIDEO(0) < AUDIO(1) < IMAGE(2) < OTHER(3)；实况合成图扩展名 `.MP.jpg`（`DouyinEngine` 写死）。

## Review Focus

规格隐含但任务测试难以全覆盖、最易咬人的五类（每类已钉到拥有它的任务）：

1. **台账并发写丢更新**（pipeline merge 与书架对账 removeKeys 双写者）→ 期望：任一调用序列后台账无丢失条目。Task 2 实现侧钉死（单 edit），执行评审员核代码；JVM 不构造竞态用例。
2. **MediaStore 同名自动改名后台账错配**（真实 DISPLAY_NAME ≠ 本地临时文件名）→ 期望：台账记回查名，书架仍成组。Task 4 实现回查+回退；模拟器 E2E（Task 9）走真名链路。
3. **外部文件管理器删文件后书架状态** → 期望：少页、剩 1 页按 Single 且条目保留、全删整书消失。Task 3 测试钉死三态。
4. **同标题不同作品 / 同名文件跨平台目录** → 期望：按 (relPath,name)+workKey 不误并。Task 3 测试钉死。
5. **模式中途失败**（第 k/N 张入库后断）→ 期望：已入库部分仍入台账（少页书），下载失败如实上抛。Task 5 测试钉死。

---

### Task 0: 基线确认

**Files:** 无（只读验证）

**Interfaces:**
- Consumes: 无
- Produces: 干净基线（全测试绿 + git 树净），后续任务失败时可归因

- [ ] **Step 1: 确认工作树干净**

```bash
cd /Users/liuyouwu/Desktop/个人研究/linkdown-m1-handoff/linkdown-android && git status --short
```

Expected: 无未提交改动（规格文档已随 `ea69b1d` 提交）

- [ ] **Step 2: 跑全套 JVM 测试基线**

```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 17) && ./gradlew clean testDebugUnitTest
```

Expected: BUILD SUCCESSFUL，全绿（基线约 265+ 用例）。有红先停下报告，不在红基线上开工。

---

### Task 1: 台账类型 + codec 纯函数

**Files:**
- Create: `app/src/main/java/top/linkdown/android/pipeline/ShelfLedgerTypes.kt`
- Create: `app/src/main/java/top/linkdown/android/ui/ShelfLedger.kt`（本任务只含 codec 部分；Task 2 加 DataStore 类）
- Test: `app/src/test/java/top/linkdown/android/ui/ShelfLedgerCodecTest.kt`

**Interfaces:**
- Consumes: 无
- Produces: `pipeline.FileRef(path: String, name: String)`、`pipeline.LedgerMerge(workKey: String, platform: String, title: String, files: List<FileRef>)`、`pipeline.LedgerEntry(workKey: String, platform: String, title: String, files: List<FileRef>, updatedAt: Long)`、`ui.ShelfLedgerCodec.encode(List<LedgerEntry>): String`、`ui.ShelfLedgerCodec.decode(String?): List<LedgerEntry>`、`ui.ShelfLedgerCodec.mergeEntries(existing: List<LedgerEntry>, input: LedgerMerge, now: Long): List<LedgerEntry>`、`ui.compareNatural(a: String, b: String): Int`

- [ ] **Step 1: 写失败测试**

`app/src/test/java/top/linkdown/android/ui/ShelfLedgerCodecTest.kt`：

```kotlin
package top.linkdown.android.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import top.linkdown.android.pipeline.FileRef
import top.linkdown.android.pipeline.LedgerEntry
import top.linkdown.android.pipeline.LedgerMerge

/** 台账编解码/合并纯函数（JVM 直测；Uri 无关）。 */
class ShelfLedgerCodecTest {

    private val e1 = LedgerEntry("抖音:111", "抖音", "猫片", listOf(FileRef("Download/链接下载/抖音/", "猫片-1.jpg")), 100L)

    @Test fun `encode decode 往返保真`() {
        val restored = ShelfLedgerCodec.decode(ShelfLedgerCodec.encode(listOf(e1)))
        assertEquals(listOf(e1), restored)
    }

    @Test fun `decode 损坏或空输入回空表`() {
        assertTrue(ShelfLedgerCodec.decode(null).isEmpty())
        assertTrue(ShelfLedgerCodec.decode("not json{").isEmpty())
        assertTrue(ShelfLedgerCodec.decode("[]").isEmpty())
    }

    @Test fun `mergeEntries 新键追加`() {
        val input = LedgerMerge("抖音:222", "抖音", "狗片", listOf(FileRef("Download/链接下载/抖音/", "狗片.mp4")))
        val out = ShelfLedgerCodec.mergeEntries(listOf(e1), input, now = 200L)
        assertEquals(2, out.size)
        assertEquals("狗片", out[1].title)
        assertEquals(200L, out[1].updatedAt)
    }

    @Test fun `mergeEntries 既有键去重追加并覆盖标题`() {
        val input = LedgerMerge("抖音:111", "抖音", "猫片", listOf(
            FileRef("Download/链接下载/抖音/", "猫片-1.jpg"),   // 已有 → 不重复
            FileRef("Download/链接下载/抖音/", "猫片.mp4"),     // 新 → 追加
        ))
        val out = ShelfLedgerCodec.mergeEntries(listOf(e1), input, now = 300L)
        assertEquals(1, out.size)
        assertEquals(listOf("猫片-1.jpg", "猫片.mp4"), out[0].files.map { it.name })
        assertEquals(300L, out[0].updatedAt)
    }

    @Test fun `mergeEntries 文件名自然序 数值感知`() {
        val input = LedgerMerge("抖音:1", "抖音", "t", listOf(
            FileRef("p/", "标题-10.jpg"), FileRef("p/", "标题-2.jpg")))
        val out = ShelfLedgerCodec.mergeEntries(emptyList(), input, now = 0L)
        assertEquals(listOf("标题-2.jpg", "标题-10.jpg"), out[0].files.map { it.name })
    }

    @Test fun `compareNatural 数字段按数值比较`() {
        assertTrue(compareNatural("a-2.jpg", "a-10.jpg") < 0)
        assertTrue(compareNatural("a-002.jpg", "a-2.jpg") == 0)
        assertTrue(compareNatural("b.jpg", "a.jpg") > 0)
    }
}
```

注意：`mergeEntries 既有键去重追加并覆盖标题` 的 files 顺序断言以自然序实现为准（`猫片-1.jpg` 与 `猫片.mp4` 在 '-'(0x2d) 与 '.'(0x2e) 处逐字符比较，`-` < `.` → `猫片-1.jpg` 在前）。

- [ ] **Step 2: 跑测试确认失败**

```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 17) && ./gradlew testDebugUnitTest --tests "top.linkdown.android.ui.ShelfLedgerCodecTest"
```

Expected: 编译失败（`ShelfLedgerCodec`/`FileRef` 等不存在）

- [ ] **Step 3: 实现**

`app/src/main/java/top/linkdown/android/pipeline/ShelfLedgerTypes.kt`：

```kotlin
package top.linkdown.android.pipeline

/**
 * 书架台账类型（设计：作品键→文件清单，pipeline 定义避免 pipeline→ui 反向依赖）。
 * FileRef.path=RELATIVE_PATH（含尾斜杠）、name=MediaStore 回查 DISPLAY_NAME。
 */
data class FileRef(val path: String, val name: String)

/** 一次增量合并输入（每模式 ingest 后由 pipeline 构造）。 */
data class LedgerMerge(val workKey: String, val platform: String, val title: String, val files: List<FileRef>)

data class LedgerEntry(
    val workKey: String,
    val platform: String,
    val title: String,
    val files: List<FileRef>,
    val updatedAt: Long,
)
```

`app/src/main/java/top/linkdown/android/ui/ShelfLedger.kt`：

```kotlin
package top.linkdown.android.ui

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import top.linkdown.android.pipeline.FileRef
import top.linkdown.android.pipeline.LedgerEntry
import top.linkdown.android.pipeline.LedgerMerge

/** 文件名自然序比较（数值段按数值，前导零等价）：标题-2 < 标题-10。 */
fun compareNatural(a: String, b: String): Int {
    var i = 0
    var j = 0
    while (i < a.length && j < b.length) {
        val ca = a[i]
        val cb = b[j]
        if (ca.isDigit() && cb.isDigit()) {
            var ei = i
            while (ei < a.length && a[ei].isDigit()) ei++
            var ej = j
            while (ej < b.length && b[ej].isDigit()) ej++
            val na = a.substring(i, ei).trimStart('0').ifEmpty { "0" }
            val nb = b.substring(j, ej).trimStart('0').ifEmpty { "0" }
            val cmp = na.length.compareTo(nb.length).takeIf { it != 0 } ?: na.compareTo(nb)
            if (cmp != 0) return cmp
            i = ei
            j = ej
        } else {
            if (ca != cb) return ca.compareTo(cb)
            i++
            j++
        }
    }
    return (a.length - i).compareTo(b.length - j)
}

/**
 * 台账 JSON 编解码 + 增量合并（纯函数，JVM 直测；DataStore 壳在同类文件 Task 2 补）。
 * 防御式解码：坏元素/坏 JSON 跳过不崩（与 QueueJson 同风格）。
 */
object ShelfLedgerCodec {

    fun encode(entries: List<LedgerEntry>): String = JsonArray(entries.map { e ->
        buildJsonObject {
            put("workKey", e.workKey)
            put("platform", e.platform)
            put("title", e.title)
            put("updatedAt", e.updatedAt)
            put("files", JsonArray(e.files.map { f ->
                buildJsonObject {
                    put("path", f.path)
                    put("name", f.name)
                }
            }))
        }
    }).toString()

    fun decode(json: String?): List<LedgerEntry> {
        val arr = json?.let { runCatching { Json.parseToJsonElement(it).jsonArray }.getOrNull() } ?: return emptyList()
        return arr.mapNotNull { el ->
            runCatching {
                val o = el.jsonObject
                val files = o["files"]?.jsonArray?.mapNotNull { f ->
                    runCatching {
                        val fo = f.jsonObject
                        FileRef(fo["path"]!!.jsonPrimitive.content, fo["name"]!!.jsonPrimitive.content)
                    }.getOrNull()
                } ?: emptyList()
                LedgerEntry(
                    workKey = o["workKey"]!!.jsonPrimitive.content,
                    platform = o["platform"]?.jsonPrimitive?.content ?: "",
                    title = o["title"]?.jsonPrimitive?.content ?: "",
                    files = files,
                    updatedAt = o["updatedAt"]?.jsonPrimitive?.longOrNull ?: 0L,
                )
            }.getOrNull()
        }
    }

    /** 增量合并：workKey 已存在 → 按 (path,name) 去重追加 files、覆盖 title/platform/updatedAt；不存在 → 追加。files 落盘前按自然序排。 */
    fun mergeEntries(existing: List<LedgerEntry>, input: LedgerMerge, now: Long): List<LedgerEntry> {
        val natural = java.util.Comparator<FileRef> { x, y -> compareNatural(x.name, y.name) }
        val incoming = input.files.sortedWith(natural)
        val idx = existing.indexOfFirst { it.workKey == input.workKey }
        if (idx < 0) {
            return existing + LedgerEntry(input.workKey, input.platform, input.title, incoming, now)
        }
        val old = existing[idx]
        val merged = (old.files + incoming).distinctBy { it.path to it.name }.sortedWith(natural)
        return existing.toMutableList().also {
            it[idx] = old.copy(title = input.title, platform = input.platform, files = merged, updatedAt = now)
        }
    }
}
```

- [ ] **Step 4: 跑测试确认通过**

```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 17) && ./gradlew testDebugUnitTest --tests "top.linkdown.android.ui.ShelfLedgerCodecTest"
```

Expected: PASS（6 用例）

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/top/linkdown/android/pipeline/ShelfLedgerTypes.kt app/src/main/java/top/linkdown/android/ui/ShelfLedger.kt app/src/test/java/top/linkdown/android/ui/ShelfLedgerCodecTest.kt
git commit -m "feat(shelf): 台账类型与编解码纯函数——FileRef/LedgerMerge/LedgerEntry、JSON 防御式编解码、mergeEntries 去重追加+自然序排页"
```

---

### Task 2: ShelfLedger DataStore 实例

**Files:**
- Modify: `app/src/main/java/top/linkdown/android/ui/ShelfLedger.kt`（追加类）
- Test: `app/src/test/java/top/linkdown/android/ui/ShelfLedgerTest.kt`

**Interfaces:**
- Consumes: Task 1 的 codec 与类型
- Produces: `ui.ShelfLedger(dataStore)`：`suspend load(): List<LedgerEntry>`、`suspend merge(input: LedgerMerge)`、`suspend removeKeys(keys: Set<String>)`；`ShelfLedger.create(file: java.io.File, scope: kotlinx.coroutines.CoroutineScope): ShelfLedger`

- [ ] **Step 1: 写失败测试**

`app/src/test/java/top/linkdown/android/ui/ShelfLedgerTest.kt`：

```kotlin
package top.linkdown.android.ui

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import top.linkdown.android.pipeline.FileRef
import top.linkdown.android.pipeline.LedgerEntry
import top.linkdown.android.pipeline.LedgerMerge
import java.io.File

/** 台账 DataStore 壳（JVM 真 DataStore + 临时文件；每用例独立目录防同文件双实例）。 */
class ShelfLedgerTest {

    private fun tempRoot(): File =
        File(System.getProperty("java.io.tmpdir"), "ledger-${System.nanoTime()}").apply { mkdirs() }

    private class Box : AutoCloseable {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        lateinit var ledger: ShelfLedger
        lateinit var dir: File
        override fun close() { scope.cancel(); dir.deleteRecursively() }
    }

    private fun newLedger(): Box = Box().apply {
        dir = tempRoot()
        ledger = ShelfLedger.create(File(dir, "shelf_ledger.preferences_pb"), scope)
    }

    private val m1 = LedgerMerge("抖音:111", "抖音", "猫片", listOf(FileRef("Download/链接下载/抖音/", "猫片-1.jpg")))

    @Test fun `merge 后 load 往返`() = runBlocking {
        newLedger().use { b ->
            b.ledger.merge(m1)
            assertEquals(listOf(LedgerEntry(m1.workKey, m1.platform, m1.title, m1.files, b.ledger.load()[0].updatedAt)), b.ledger.load())
        }
        Unit
    }

    @Test fun `merge 幂等去重 同文件不重复`() = runBlocking {
        newLedger().use { b ->
            b.ledger.merge(m1)
            b.ledger.merge(m1)
            assertEquals(1, b.ledger.load().size)
            assertEquals(1, b.ledger.load()[0].files.size)
        }
        Unit
    }

    @Test fun `removeKeys 删指定键`() = runBlocking {
        newLedger().use { b ->
            b.ledger.merge(m1)
            b.ledger.merge(LedgerMerge("抖音:222", "抖音", "狗片", listOf(FileRef("p/", "g.mp4"))))
            b.ledger.removeKeys(setOf("抖音:111"))
            assertEquals(listOf("抖音:222"), b.ledger.load().map { it.workKey })
        }
        Unit
    }

    @Test fun `removeKeys 空集不写 快速返回`() = runBlocking {
        newLedger().use { b ->
            b.ledger.merge(m1)
            b.ledger.removeKeys(emptySet())
            assertEquals(1, b.ledger.load().size)
        }
        Unit
    }

    @Test fun `损坏文件 回空表不崩`() {
        // 先写坏文件再建唯一实例：DataStore 对同文件第二活跃实例会抛 IllegalStateException
        val b = Box()
        try {
            b.dir = tempRoot()
            val f = File(b.dir, "shelf_ledger.preferences_pb")
            f.writeBytes(byteArrayOf(1, 2, 3, 9, 9))
            b.ledger = ShelfLedger.create(f, b.scope)
            runBlocking { assertTrue(b.ledger.load().isEmpty()) }
        } finally { b.close() }
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 17) && ./gradlew testDebugUnitTest --tests "top.linkdown.android.ui.ShelfLedgerTest"
```

Expected: 编译失败（`ShelfLedger` 类不存在）

- [ ] **Step 3: 实现（追加到 ShelfLedger.kt）**

```kotlin
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import java.io.File

/**
 * 书架台账壳（QueuePersistence 同款形态）：单键 shelf_groups 存 JSON 数组。
 * 每次 merge/removeKeys 都在单个 dataStore.edit 内读-改-写（原子，防 pipeline 与对账清理并发丢更新）。
 */
class ShelfLedger(private val dataStore: DataStore<Preferences>) {

    suspend fun load(): List<LedgerEntry> =
        dataStore.data.first()[KEY]?.let { ShelfLedgerCodec.decode(it) } ?: emptyList()

    suspend fun merge(input: LedgerMerge) {
        dataStore.edit { p ->
            val now = System.currentTimeMillis()
            p[KEY] = ShelfLedgerCodec.encode(
                ShelfLedgerCodec.mergeEntries(ShelfLedgerCodec.decode(p[KEY]), input, now),
            )
        }
    }

    suspend fun removeKeys(keys: Set<String>) {
        if (keys.isEmpty()) return
        dataStore.edit { p ->
            val current = ShelfLedgerCodec.decode(p[KEY])
            if (current.isNotEmpty()) p[KEY] = ShelfLedgerCodec.encode(current.filterNot { it.workKey in keys })
        }
    }

    companion object {
        private val KEY = stringPreferencesKey("shelf_groups")

        /** 建实例（同文件绝不建第二个活跃实例；损坏文件回空偏好不崩）。 */
        fun create(file: File, scope: CoroutineScope): ShelfLedger = ShelfLedger(
            PreferenceDataStoreFactory.create(
                scope = scope,
                corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
            ) { file },
        )
    }
}
```

- [ ] **Step 4: 跑测试确认通过 + 全套不回归**

```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 17) && ./gradlew testDebugUnitTest --tests "top.linkdown.android.ui.ShelfLedgerTest" --tests "top.linkdown.android.ui.ShelfLedgerCodecTest"
```

Expected: PASS（11 用例）

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/top/linkdown/android/ui/ShelfLedger.kt app/src/test/java/top/linkdown/android/ui/ShelfLedgerTest.kt
git commit -m "feat(shelf): 台账 DataStore 壳——单键 shelf_groups、单 edit 原子读改写、损坏回空表"
```

---

### Task 3: groupShelf 合书纯函数 + ShelfItem.relPath

**Files:**
- Modify: `app/src/main/java/top/linkdown/android/ui/ShelfStore.kt`（ShelfItem +relPath 字段、query 投影落值、groupShelf/ShelfKey/Group/GroupingResult 纯函数）
- Test: `app/src/test/java/top/linkdown/android/ui/GroupShelfTest.kt`

**Interfaces:**
- Consumes: `pipeline.LedgerEntry`（Task 1）
- Produces: `ShelfItem.relPath: String`（既有字段 uri/name/platform/sizeBytes/kind/dateAdded 不变）；`ShelfStore.ShelfKey(id, relPath, name, kind, dateAdded)`；`ShelfStore.Group`（sealed：`SingleGroup(key)` / `BoundGroup(workKey, title, platform, keys)`，均暴露 `id: String` 与 `dateAdded: Long`）；`ShelfStore.GroupingResult(groups: List<Group>, staleKeys: Set<String>)`；`ShelfStore.groupShelf(keys: List<ShelfKey>, ledger: List<LedgerEntry>): GroupingResult`；`ShelfStore.toKeys(items: List<ShelfItem>): List<ShelfKey>`

- [ ] **Step 1: 写失败测试**

`app/src/test/java/top/linkdown/android/ui/GroupShelfTest.kt`：

```kotlin
package top.linkdown.android.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import top.linkdown.android.pipeline.FileRef
import top.linkdown.android.pipeline.LedgerEntry
import top.linkdown.android.ui.ShelfStore.ShelfKey

/** 合书对账纯函数（Uri 无关投影，JVM 直测）。 */
class GroupShelfTest {

    private fun key(id: String, name: String, relPath: String = "Download/链接下载/抖音/", date: Long = 10L) =
        ShelfKey(id = id, relPath = relPath, name = name,
            kind = ShelfStore.kindOf(name), dateAdded = date)

    private val ledger = listOf(
        LedgerEntry("抖音:111", "抖音", "猫片", listOf(
            FileRef("Download/链接下载/抖音/", "猫片-1.jpg"),
            FileRef("Download/链接下载/抖音/", "猫片-2.jpg"),
        ), 0L),
    )

    @Test fun `成组 页序等于台账序`() {
        val keys = listOf(key("u2", "猫片-2.jpg", date = 9), key("u1", "猫片-1.jpg", date = 11))
        val r = ShelfStore.groupShelf(keys, ledger)
        val bound = r.groups.filterIsInstance<ShelfStore.BoundGroup>().single()
        assertEquals(listOf("u1", "u2"), bound.keys.map { it.id }) // 台账序，非输入序
        assertEquals("猫片", bound.title)
        assertEquals("抖音:111", bound.workKey)
        assertTrue(r.staleKeys.isEmpty())
    }

    @Test fun `剩1页 条目保留 文件按Single且不重复渲染`() {
        val keys = listOf(key("u1", "猫片-1.jpg"))
        val r = ShelfStore.groupShelf(keys, ledger)
        assertEquals(1, r.groups.size)
        assertTrue(r.groups[0] is ShelfStore.SingleGroup)
        assertTrue(r.staleKeys.isEmpty()) // 条目保留，不清理
    }

    @Test fun `全删 stale 收录整组键`() {
        val r = ShelfStore.groupShelf(emptyList(), ledger)
        assertTrue(r.groups.isEmpty())
        assertEquals(setOf("抖音:111"), r.staleKeys)
    }

    @Test fun `未认领文件成Single 旧文件行为不变`() {
        val keys = listOf(key("u9", "旧图.jpg"), key("u1", "猫片-1.jpg", date = 11), key("u2", "猫片-2.jpg"))
        val r = ShelfStore.groupShelf(keys, ledger)
        val bound = r.groups.filterIsInstance<ShelfStore.BoundGroup>().single()
        assertEquals(listOf("u1", "u2"), bound.keys.map { it.id })
        assertEquals("u9", (r.groups.filterIsInstance<ShelfStore.SingleGroup>().single()).key.id)
    }

    @Test fun `同title不同workKey不误并 匹配键含relPath`() {
        val keys = listOf(
            key("a1", "猫片-1.jpg", relPath = "Download/链接下载/抖音/"),
            key("a2", "猫片-1.jpg", relPath = "Download/链接下载/小红书/"),
            key("a3", "猫片-2.jpg", relPath = "Download/链接下载/抖音/"),
        )
        val r = ShelfStore.groupShelf(keys, ledger) // 台账指向抖音目录：a1+a3 成组
        assertEquals(1, r.groups.filterIsInstance<ShelfStore.BoundGroup>().size)
        assertEquals(1, r.groups.filterIsInstance<ShelfStore.SingleGroup>().size) // 小红书同名文件不认领
    }

    @Test fun `输出序 dateAdded DESC 同值输入序稳定`() {
        val keys = listOf(
            key("old", "旧.jpg", date = 5), key("n1", "猫片-1.jpg", date = 11), key("n2", "猫片-2.jpg", date = 9),
        )
        val r = ShelfStore.groupShelf(keys, ledger)
        assertEquals(listOf("抖音:111", "old"), r.groups.map { it.id }) // Bound(max=11) 在前
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 17) && ./gradlew testDebugUnitTest --tests "top.linkdown.android.ui.GroupShelfTest"
```

Expected: 编译失败（`ShelfKey`/`groupShelf` 不存在）

- [ ] **Step 3: 实现**

`ShelfStore.kt` 三处改动：

1. `ShelfItem` 加字段（第 12-19 行 data class）：

```kotlin
data class ShelfItem(
    val uri: Uri,
    val name: String,
    val relPath: String,
    val platform: String,
    val sizeBytes: Long,
    val kind: ShelfKind,
    val dateAdded: Long,
)
```

2. `query()` 落值（现 `rel` 变量已存在，第 65-75 行）：构造处加 `relPath = rel`。

3. `object ShelfStore` 内追加（类型与纯函数都嵌在 object 里——测试 import `ShelfStore.ShelfKey` 依赖嵌套；文件头 import 区加 `import top.linkdown.android.pipeline.LedgerEntry`）：

```kotlin
    /** 合书投影键（Uri 无关，JVM 直测）：id 用于回射 ShelfItem。 */
    data class ShelfKey(
        val id: String,
        val relPath: String,
        val name: String,
        val kind: ShelfKind,
        val dateAdded: Long,
    )

    /** 合书结果组：Single=现单文件行为；Bound=同作品成书。 */
    sealed interface Group {
        val id: String
        val dateAdded: Long
    }

    data class SingleGroup(val key: ShelfKey) : Group {
        override val id get() = key.id
        override val dateAdded get() = key.dateAdded
    }

    data class BoundGroup(
        val workKey: String,
        val title: String,
        val platform: String,
        val keys: List<ShelfKey>,
    ) : Group {
        override val id get() = workKey
        override val dateAdded get() = keys.maxOf { it.dateAdded }
    }

    data class GroupingResult(val groups: List<Group>, val staleKeys: Set<String>)
```

同在 `object ShelfStore` 内追加 groupShelf/toKeys：

```kotlin
    fun toKeys(items: List<ShelfItem>): List<ShelfKey> =
        items.map { ShelfKey(it.uri.toString(), it.relPath, it.name, it.kind, it.dateAdded) }

    /**
     * 合书对账（纯函数，JVM 直测；设计 §6）：
     * - matched ≥2 → BoundGroup（页序=台账序，台账已按自然序写入），matched 标记认领
     * - matched ==1 → 条目保留（补下可并组），**不认领**——matched==1 不产生组，无重复渲染；
     *   该文件落入下方未认领分支按 SingleGroup 展示（设计 §11「剩 1 页按 Single」）
     * - matched ==0 → workKey 入 staleKeys（对账清理）
     * - 未认领键 → 各自 SingleGroup（旧文件行为不变）
     * - 输出序：dateAdded DESC，同值稳定保序（TIME 排序恒等语义依赖此序）
     */
    fun groupShelf(keys: List<ShelfKey>, ledger: List<LedgerEntry>): GroupingResult {
        val index = keys.associateBy { it.relPath to it.name }
        val claimed = mutableSetOf<String>()
        val stale = mutableSetOf<String>()
        val bounds = mutableListOf<BoundGroup>()
        for (entry in ledger) {
            val matched = entry.files.mapNotNull { index[it.path to it.name] }.filter { it.id !in claimed }
            when {
                matched.size >= 2 -> {
                    bounds += BoundGroup(entry.workKey, entry.title, entry.platform, matched)
                    claimed += matched.map { it.id }
                }
                matched.size == 1 -> {} // 条目保留、不认领：文件经未认领分支按 Single 展示
                else -> stale += entry.workKey
            }
        }
        val singles = keys.filter { it.id !in claimed }.map { SingleGroup(it) }
        return GroupingResult((singles + bounds).sortedByDescending { it.dateAdded }, stale)
    }
```

- [ ] **Step 4: 跑测试确认通过 + 全套不回归（ShelfStoreCoverTest 等既有用例不受 relPath 新字段影响？——构造 ShelfItem 的测试文件若用具名参数需补 relPath，报错即修）**

```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 17) && ./gradlew testDebugUnitTest
```

Expected: 全绿。若既有测试因 `ShelfItem` 新字段编译失败，给其构造点补 `relPath = "Download/链接下载/${platform}/"`（只改测试构造，不改断言语义）。

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/top/linkdown/android/ui/ShelfStore.kt app/src/test/java/top/linkdown/android/ui/GroupShelfTest.kt
git commit -m "feat(shelf): groupShelf 合书对账纯函数——ShelfItem 带 relPath、三态对账（成组/保留/清理）、dateAdded DESC 稳定输出序"
```

---

### Task 4: ingest 返回 IngestedFile（回查真名）

**Files:**
- Modify: `app/src/main/java/top/linkdown/android/ingest/MediaStoreIngest.kt`
- Modify: `app/src/main/java/top/linkdown/android/pipeline/SingleDownloadPipeline.kt`（`PipelineEnv.ingest` 签名 + `AndroidPipelineEnv.ingest` + runItem 调用点传 `inf?.uri` 给 onIngested）
- Modify: `app/src/test/java/top/linkdown/android/queue/QueueTestFakes.kt`（FakeEnv.ingest 返回 IngestedFile）
- Test: 全套既有 pipeline/queue 测试回归

**Interfaces:**
- Consumes: 无（android 层，不 JVM 新测）
- Produces: `ingest.IngestedFile(uri: android.net.Uri?, displayName: String, relPath: String)`（uri 生产恒非空、JVM 假件 null）；`PipelineEnv.ingest(src, platform, onProgress): IngestedFile?`（新签名）；`hooks.onIngested(label, uri)` 调用点语义不变（null=无框架 Uri）

- [ ] **Step 1: 实现 MediaStoreIngest**

`MediaStoreIngest.kt` 全文替换为：

```kotlin
package top.linkdown.android.ingest

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import java.io.File

/** 入库产物：uri=MediaStore 条目（JVM 假件为 null）；displayName/relPath=入库后回查真名（同名冲突自动改名场景）。 */
data class IngestedFile(val uri: Uri?, val displayName: String, val relPath: String)

object MediaStoreIngest {
    fun ingest(context: Context, src: File, platformDir: String, onProgress: (Float) -> Unit = {}): IngestedFile {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, src.name)
            put(MediaStore.MediaColumns.RELATIVE_PATH, "Download/链接下载/$platformDir")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: error("MediaStore 插入失败")
        try {
            val total = src.length().coerceAtLeast(1)
            resolver.openOutputStream(uri)?.use { out ->
                src.inputStream().use { input ->
                    val buf = ByteArray(8192); var copied = 0L; var n = input.read(buf)
                    while (n >= 0) {
                        out.write(buf, 0, n); copied += n
                        onProgress((copied.toDouble() / total).toFloat())
                        n = input.read(buf)
                    }
                }
            } ?: error("MediaStore 输出流打开失败")
            values.clear()
            values.put(MediaStore.MediaColumns.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            // 回查真实名（IS_PENDING 清零后查；查空回退本地名+构造路径，记日志不按失败处理）
            var displayName = src.name
            var relPath = "Download/链接下载/$platformDir/"
            runCatching {
                resolver.query(
                    uri,
                    arrayOf(MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.RELATIVE_PATH),
                    null, null, null,
                )?.use { cur ->
                    if (cur.moveToFirst()) {
                        cur.getString(0)?.takeIf { it.isNotEmpty() }?.let { displayName = it }
                        cur.getString(1)?.takeIf { it.isNotEmpty() }?.let { relPath = it }
                    }
                }
            }
            return IngestedFile(uri, displayName, relPath)
        } catch (t: Throwable) {
            resolver.delete(uri, null, null)
            throw t
        }
    }
}
```

- [ ] **Step 2: 改 PipelineEnv/AndroidPipelineEnv/runItem/FakeEnv**

`SingleDownloadPipeline.kt`：

1. import 区加 `import top.linkdown.android.ingest.IngestedFile`。
2. `PipelineEnv.ingest`（第 52-53 行）签名换：

```kotlin
    /** 入库（生产=MediaStoreIngest 拷入 Downloads/链接下载/<platform>/；JVM 假环境 uri=null）。 */
    fun ingest(src: File, platform: String, onProgress: (Float) -> Unit): IngestedFile?
```

3. `AndroidPipelineEnv.ingest`（第 70-71 行）：

```kotlin
    override fun ingest(src: File, platform: String, onProgress: (Float) -> Unit): IngestedFile =
        MediaStoreIngest.ingest(context, src, platform, onProgress)
```

4. runItem 入库循环（第 268-272 行）——本任务只改调用点形态（台账合并是 Task 5）：

```kotlin
                for ((i, f) in newFiles.withIndex()) {
                    val inf = env.ingest(f, info.platform) { cp ->
                        setMode(1f, (i + cp) / newFiles.size)
                    }
                    hooks.onIngested(label, inf?.uri)
                }
```

`QueueTestFakes.kt` 的 `FakeEnv.ingest`（第 156-160 行）：

```kotlin
    override fun ingest(src: File, platform: String, onProgress: (Float) -> Unit): IngestedFile? {
        ingested.add(platform to src.name)
        onProgress(1f)
        return IngestedFile(null, src.name, "Download/链接下载/$platform/") // JVM 无框架 Uri
    }
```

（import 区加 `import top.linkdown.android.ingest.IngestedFile`。）

- [ ] **Step 3: 全套回归**

```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 17) && ./gradlew clean testDebugUnitTest
```

Expected: 全绿（既有用例对 ingest 返回值只经 `?.uri` 消费，行为不变）。

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/top/linkdown/android/ingest/MediaStoreIngest.kt app/src/main/java/top/linkdown/android/pipeline/SingleDownloadPipeline.kt app/src/test/java/top/linkdown/android/queue/QueueTestFakes.kt
git commit -m "feat(shelf): ingest 返回 IngestedFile 回查 MediaStore 真名——同名冲突自动改名后台账以真名为准，查空回退本地名"
```

---

### Task 5: ledgerMerge 缝 + runItem 每模式增量合并（半程合并）

**Files:**
- Modify: `app/src/main/java/top/linkdown/android/pipeline/SingleDownloadPipeline.kt`（PipelineEnv +workKeyOf +runItem 合并）
- Modify: `app/src/test/java/top/linkdown/android/queue/QueueTestFakes.kt`（FakeEnv.ledgerMerge 记录器 + ingestFailOn + FakeEngine.filesPerMode）
- Test: `app/src/test/java/top/linkdown/android/pipeline/PipelineLedgerMergeTest.kt`

**Interfaces:**
- Consumes: Task 1 类型、Task 4 IngestedFile
- Produces: `PipelineEnv.ledgerMerge: suspend (LedgerMerge) -> Unit`（var 缝，仿 dedup override var）；`internal fun workKeyOf(info: ProbeInfo): String`（mediaId 非空→`"<platform>:<mediaId>"`；否则 `"<platform>:url:<sourceUrl 去 query/fragment/尾斜杠>"`）

- [ ] **Step 1: 写失败测试**

`app/src/test/java/top/linkdown/android/pipeline/PipelineLedgerMergeTest.kt`：

```kotlin
package top.linkdown.android.pipeline

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import top.linkdown.android.engine.Mode
import top.linkdown.android.queue.DedupCheck
import top.linkdown.android.queue.FakeEngine
import top.linkdown.android.queue.FakeEnv
import top.linkdown.android.queue.QueueRunHooks
import java.io.File

/** 台账缝接线（设计 §5：每模式增量 merge、半程合并、失败不影响下载）。 */
class PipelineLedgerMergeTest {

    private fun tempRoot(): File =
        File(System.getProperty("java.io.tmpdir"), "plm-${System.nanoTime()}").apply { mkdirs() }

    private class Hooks : QueueRunHooks {
        val completedModes = ArrayList<Mode>()
        override fun cancelRequested() = false
        override fun shouldWaitOffline() = false
        override fun onLog(line: String) {}
        override fun onProgress(modeLabel: String, fraction: Float) {}
        override fun onIngested(label: String, uri: android.net.Uri?) {}
        override suspend fun onProbeDone(title: String, coverUrl: String?, platform: String, durationSec: Double?) {}
        override suspend fun onModeCompleted(mode: Mode, files: List<File>) { completedModes.add(mode) }
        override suspend fun onExists() {}
    }

    private fun item(root: File, modes: DownloadModes) = top.linkdown.android.queue.QueueItem(
        id = "rec-1", url = "https://example.com/v/1", platform = "YTDLP", modes = modes,
        title = "占位", taskDir = File(root, "td").apply { mkdirs() }.absolutePath, createdAt = 0L,
    )

    @Test fun `每模式增量 merge workKey 取 probe mediaId`() = runTest {
        val root = tempRoot()
        try {
            val env = FakeEnv(root)
            val e = FakeEngine()
            env.register("https://example.com/v/1", e)
            val hooks = Hooks()
            SingleDownloadPipeline(env).runItem(item(root, DownloadModes(true, false, true)), hooks)
            assertEquals(2, env.ledgerMerges.size)
            assertEquals("汽水:m1", env.ledgerMerges[0].workKey)
            assertEquals("汽水", env.ledgerMerges[0].platform)
            assertEquals("测试标题", env.ledgerMerges[0].title)
            assertEquals(listOf("out-video.bin"), env.ledgerMerges[0].files.map { it.name })
            assertEquals(listOf("out-cover.bin"), env.ledgerMerges[1].files.map { it.name })
            assertEquals("Download/链接下载/汽水/", env.ledgerMerges[0].files[0].path)
        } finally { root.deleteRecursively() }
    }

    @Test fun `全模式判重命中 不写台账`() = runTest {
        val root = tempRoot()
        try {
            val env = FakeEnv(root)
            env.dedup = DedupCheck { listOf("测试标题.mp4", "测试标题.jpg") }
            env.register("https://example.com/v/1", FakeEngine())
            SingleDownloadPipeline(env).runItem(item(root, DownloadModes(true, false, true)), Hooks())
            assertEquals(0, env.ledgerMerges.size)
        } finally { root.deleteRecursively() }
    }

    @Test fun `模式中途失败 半程合并已入库部分 异常照常上抛`() = runTest {
        val root = tempRoot()
        try {
            val env = FakeEnv(root)
            val e = FakeEngine().apply { filesPerMode = 2 }
            env.register("https://example.com/v/1", e)
            env.ingestFailOn = "out-video-2.bin" // 第 2 张入库失败
            val hooks = Hooks()
            val err = runCatching {
                SingleDownloadPipeline(env).runItem(item(root, DownloadModes(true, false, false)), hooks)
            }.exceptionOrNull()
            assertTrue("失败如实上抛", err != null)
            assertEquals(1, env.ledgerMerges.size)
            assertEquals(listOf("out-video-1.bin"), env.ledgerMerges[0].files.map { it.name })
        } finally { root.deleteRecursively() }
    }

    @Test fun `台账缝自身失败 下载不受影响`() = runTest {
        val root = tempRoot()
        try {
            val env = FakeEnv(root)
            env.ledgerMerge = { throw RuntimeException("台账炸了") }
            env.register("https://example.com/v/1", FakeEngine())
            val hooks = Hooks()
            SingleDownloadPipeline(env).runItem(item(root, DownloadModes(true, false, true)), hooks)
            assertEquals("两模式照常完成", listOf(Mode.VIDEO, Mode.COVER), hooks.completedModes)
        } finally { root.deleteRecursively() }
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 17) && ./gradlew testDebugUnitTest --tests "top.linkdown.android.pipeline.PipelineLedgerMergeTest"
```

Expected: 编译失败（`ledgerMerges`/`ledgerMerge` 不存在）

- [ ] **Step 3: 实现**

1. `SingleDownloadPipeline.kt` `PipelineEnv` 接口（`dedup` 后）加：

```kotlin
    /** 台账增量合并缝（设计：每模式 ingest 后调用；生产=Application 单例，JVM 假环境记录器）。 */
    var ledgerMerge: suspend (LedgerMerge) -> Unit
```

2. `AndroidPipelineEnv` 加（无参默认 no-op，由 Application 接线，见 Task 7）：

```kotlin
    override var ledgerMerge: suspend (LedgerMerge) -> Unit = {}
```

3. `SingleDownloadPipeline.kt` 顶层加（`classifyDownloadFailure` 附近）：

```kotlin
/**
 * 作品键（设计 §5）：mediaId 非空 → 平台:mediaId；否则 平台:url:<sourceUrl 去 query/fragment/尾斜杠>。
 * 已知退化：无稳定 mediaId 的引擎短链/带参变体可能同作品不同键（可接受）。
 */
internal fun workKeyOf(info: ProbeInfo): String = when {
    info.mediaId.isNotBlank() -> "${info.platform}:${info.mediaId}"
    else -> "${info.platform}:url:" + info.sourceUrl.substringBefore('?').substringBefore('#').trimEnd('/')
}
```

4. runItem 入库循环（Task 4 改后的形态）换为半程合并版：

```kotlin
                val ingested = mutableListOf<IngestedFile>()
                try {
                    for ((i, f) in newFiles.withIndex()) {
                        val inf = env.ingest(f, info.platform) { cp ->
                            setMode(1f, (i + cp) / newFiles.size)
                        }
                        if (inf != null) ingested += inf
                        hooks.onIngested(label, inf?.uri)
                    }
                } finally {
                    // 半程合并：模式中途失败/取消，已入库部分照记台账（少页书）；台账失败不影响下载
                    if (ingested.isNotEmpty()) {
                        runCatching {
                            withContext(kotlinx.coroutines.NonCancellable) {
                                env.ledgerMerge(
                                    LedgerMerge(workKeyOf(info), info.platform, info.title,
                                        ingested.map { FileRef(it.relPath, it.displayName) }),
                                )
                            }
                        }
                    }
                }
                setMode(1f)
                hooks.onModeCompleted(mode, newFiles)
```

（`FileRef`/`LedgerMerge` 同包，无需 import；`withContext`/`NonCancellable` 用全限定或加 import。）

5. `QueueTestFakes.kt` `FakeEnv` 加（`ingested` 字段之后；import 区加 `import top.linkdown.android.pipeline.LedgerMerge`）：

```kotlin
    val ledgerMerges = ArrayList<LedgerMerge>()
    override var ledgerMerge: suspend (LedgerMerge) -> Unit = { ledgerMerges.add(it) }
    var ingestFailOn: String? = null
```

`ingest` 改为（在 Task 4 基础上加失败注入）：

```kotlin
    override fun ingest(src: File, platform: String, onProgress: (Float) -> Unit): IngestedFile? {
        if (src.name == ingestFailOn) error("模拟入库失败")
        ingested.add(platform to src.name)
        onProgress(1f)
        return IngestedFile(null, src.name, "Download/链接下载/$platform/") // JVM 无框架 Uri
    }
```

`FakeEngine` 加字段并把 download 落盘改为多文件能力（默认 1 保持既有断言）：

```kotlin
    var filesPerMode = 1 // 台账半程合并测试用；默认 1 保持既有 ingested 名单
```

download 内落盘段（第 89-90 行）替换：

```kotlin
        val paths = (1..filesPerMode).map { idx ->
            val f = File(
                taskDir.toFile(),
                if (filesPerMode == 1) "out-${mode.name.lowercase()}.bin" else "out-${mode.name.lowercase()}-$idx.bin",
            )
            f.writeText("data-$mode-$idx")
            f.toPath()
        }
```

（return 语句相应 `return paths`。）

- [ ] **Step 4: 跑测试确认通过 + 全套回归**

```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 17) && ./gradlew clean testDebugUnitTest
```

Expected: 全绿（含 PipelineLedgerMergeTest 4 用例）。

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/top/linkdown/android/pipeline/SingleDownloadPipeline.kt app/src/test/java/top/linkdown/android/queue/QueueTestFakes.kt app/src/test/java/top/linkdown/android/pipeline/PipelineLedgerMergeTest.kt
git commit -m "feat(shelf): 管道台账缝——每模式增量 merge、半程合并（模式中途失败已入库部分照记）、workKeyOf（mediaId/URL 兜底）、台账失败不影响下载"
```

---

### Task 6: ShelfEntry 模型 + 装配 + 排序/摘要纯函数

**Files:**
- Modify: `app/src/main/java/top/linkdown/android/ui/ShelfStore.kt`（ShelfEntry sealed + SingleEntry/BoundEntry + assemble + loadEntryCover）
- Modify: `app/src/main/java/top/linkdown/android/ui/ShelfPage.kt`（pagesSummary + shelfEntrySortedIndices + sortShelfEntries，放在 shelfSortedIndices 旁）
- Test: `app/src/test/java/top/linkdown/android/ui/ShelfEntryTest.kt`；`ShelfSortTest.kt` 追加 entries 用例

**Interfaces:**
- Consumes: Task 3 的 Group/GroupingResult/toKeys
- Produces: `ui.ShelfEntry`（sealed：`id/title/platform/dateAdded/sizeBytes/pages: List<ShelfItem>`；`SingleEntry(item)`、`BoundEntry(workKey, boundTitle, platform, pages)`——Bound.title 空回退首页文件名去扩展名、dateAdded=max、sizeBytes=Σ）；`ShelfStore.assemble(items: List<ShelfItem>, result: GroupingResult): List<ShelfEntry>`；`ShelfStore.loadEntryCover(context: Context, entry: ShelfEntry, all: List<ShelfItem>): Bitmap?`（all=全货架文件列表，伴生封面查找与现状同域）；`ui.pagesSummary(names: List<String>, kinds: List<ShelfKind>): String`；`ui.shelfEntrySortedIndices(titles: List<String>, kindOrdinals: List<Int>, dates: List<Long>, mode: ShelfSort): List<Int>`；`ui.sortShelfEntries(entries: List<ShelfEntry>, mode: ShelfSort): List<ShelfEntry>`（UI 层装配，三键提取规则见实现）

- [ ] **Step 1: 写失败测试**

`app/src/test/java/top/linkdown/android/ui/ShelfEntryTest.kt`：

```kotlin
package top.linkdown.android.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/** ShelfEntry 派生纯函数（Uri 无关投影；Bound 派生字段经 pages 键验证）。 */
class ShelfEntryTest {

    @Test fun `pagesSummary 实况按文件名识别且计入N图`() {
        val names = listOf("猫片-1.MP.jpg", "猫片-2.MP.jpg")
        val kinds = names.map { ShelfStore.kindOf(it) }
        assertEquals("2 页 · 含实况 · 2 图", pagesSummary(names, kinds))
    }

    @Test fun `pagesSummary 视频+音频+封面混合`() {
        val names = listOf("a.mp4", "a.mp3", "a-cover.jpg")
        val kinds = names.map { ShelfStore.kindOf(it) }
        assertEquals("3 页 · 含视频 · 含音频 · 1 图", pagesSummary(names, kinds))
    }

    @Test fun `pagesSummary 纯视频无标签`() {
        assertEquals("1 页", pagesSummary(listOf("a.mp4"), listOf(ShelfKind.VIDEO)))
    }

    @Test fun `pagesSummary 实况与视频并存 标签齐全`() {
        val names = listOf("a.mp4", "a-1.MP.jpg")
        assertEquals("2 页 · 含实况 · 含视频 · 1 图", pagesSummary(names, names.map { ShelfStore.kindOf(it) }))
    }
}
```

`ShelfSortTest.kt` 追加（**注意**：`ShelfEntry` 含 `List<ShelfItem>`（Uri），JVM 不可构造，排序纯函数吃**键序列**：`ui.shelfEntrySortedIndices(titles: List<String>, kindOrdinals: List<Int>, dates: List<Long>, mode: ShelfSort): List<Int>`——与 `shelfSortedIndices(names, kinds, mode)` 同款键投影，UI 层以 entries 提取三键。用以下测试：

```kotlin
    @Test fun `entries 排序 TIME 恒等 NAME 按 title KIND 按 min ordinal`() {
        // titles/kindOrdinals/dates 三键与 ShelfEntry 派生规则一致（Bound.dateAdded=max、Bound.kindOrdinal=min）
        val titles = listOf("b", "a", "c")
        val kindOrdinals = listOf(0, 2, 1) // VIDEO, IMAGE, AUDIO
        val dates = listOf(3L, 1L, 2L)
        assertEquals(listOf(0, 1, 2), shelfEntrySortedIndices(titles, kindOrdinals, dates, ShelfSort.TIME))
        assertEquals(listOf(1, 0, 2), shelfEntrySortedIndices(titles, kindOrdinals, dates, ShelfSort.NAME))
        assertEquals(listOf(0, 2, 1), shelfEntrySortedIndices(titles, kindOrdinals, dates, ShelfSort.KIND))
    }
```

（追加到既有 `ShelfSortTest` 类内；import 按该文件现状。）

- [ ] **Step 2: 跑测试确认失败**

```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 17) && ./gradlew testDebugUnitTest --tests "top.linkdown.android.ui.ShelfEntryTest" --tests "top.linkdown.android.ui.ShelfSortTest"
```

Expected: 编译失败（`pagesSummary`/`shelfEntrySortedIndices` 不存在）

- [ ] **Step 3: 实现**

1. `ShelfPage.kt` 在 `sortShelfItems`（第 290-292 行）后追加：

```kotlin
/**
 * 分组排序核心（JVM 直测，键序列投影——Bound.dateAdded=max(pages)、Bound 类别键=min(kind.ordinal)，
 * 与 shelfSortedIndices 同款 Uri 无关手法）。
 */
internal fun shelfEntrySortedIndices(titles: List<String>, kindOrdinals: List<Int>, dates: List<Long>, mode: ShelfSort): List<Int> =
    when (mode) {
        ShelfSort.TIME -> titles.indices.toList() // groupShelf 输出已 dateAdded DESC
        ShelfSort.NAME -> titles.indices.sortedBy { titles[it].lowercase() }
        ShelfSort.KIND -> titles.indices.sortedWith(compareBy({ kindOrdinals[it] }, { titles[it].lowercase() }))
    }

/** 信息条摘要（JVM 直测；实况按文件名 .MP.jpg 识别——kindOf 判 IMAGE 推不出；实况计入 N 图）。 */
internal fun pagesSummary(names: List<String>, kinds: List<ShelfKind>): String {
    val tags = buildList {
        if (names.any { it.endsWith(".MP.jpg", ignoreCase = true) }) add("含实况")
        if (kinds.any { it == ShelfKind.VIDEO }) add("含视频")
        if (kinds.any { it == ShelfKind.AUDIO }) add("含音频")
        val images = kinds.count { it == ShelfKind.IMAGE }
        if (images > 0) add("$images 图")
    }
    return (listOf("${names.size} 页") + tags).joinToString(" · ")
}

/**
 * UI 层装配排序（entries 含 Uri 不可 JVM 构造，纯逻辑在 shelfEntrySortedIndices）。
 * 三键提取约定（实现者照抄，勿自创）：title=entry.title；
 * 类别键=组内最高优先级 kind 的 ordinal（min——VIDEO(0) 最优，音频+封面书归音频类，规格 §10）；
 * dateAdded=entry.dateAdded（Bound=max(pages)，模型已派生）。
 */
fun sortShelfEntries(entries: List<ShelfEntry>, mode: ShelfSort): List<ShelfEntry> =
    shelfEntrySortedIndices(
        entries.map { it.title },
        entries.map { e -> e.pages.minOf { it.kind.ordinal } },
        entries.map { it.dateAdded },
        mode,
    ).map { entries[it] }
```

2. `ShelfStore.kt` 文件尾追加（`loadCover` 后）：

```kotlin
/** 书架条目：Single=现单文件书；Bound=同作品成书（台账 workKey 绑定）。 */
sealed interface ShelfEntry {
    val id: String
    val title: String
    val platform: String
    val dateAdded: Long
    val sizeBytes: Long
    val pages: List<ShelfItem>
}

data class SingleEntry(val item: ShelfItem) : ShelfEntry {
    override val id get() = item.uri.toString()
    override val title get() = item.name
    override val platform get() = item.platform
    override val dateAdded get() = item.dateAdded
    override val sizeBytes get() = item.sizeBytes
    override val pages get() = listOf(item)
}

data class BoundEntry(
    val workKey: String,
    val boundTitle: String,
    override val platform: String,
    override val pages: List<ShelfItem>,
) : ShelfEntry {
    override val id get() = workKey
    override val title get() =
        boundTitle.ifBlank { pages.firstOrNull()?.name?.substringBeforeLast('.')?.ifBlank { null } ?: "未命名内容" }
    override val dateAdded get() = pages.maxOf { it.dateAdded }
    override val sizeBytes get() = pages.sumOf { it.sizeBytes }
}
```

`ShelfStore` object 内追加：

```kotlin
/** 合书装配（android 层，Uri 回射）：GroupingResult → 展示条目。 */
fun assemble(items: List<ShelfItem>, result: GroupingResult): List<ShelfEntry> {
    val byId = items.associateBy { it.uri.toString() }
    return result.groups.map { g ->
        when (g) {
            is SingleGroup -> SingleEntry(byId.getValue(g.key.id))
            is BoundGroup -> BoundEntry(g.workKey, g.title, g.platform, g.keys.map { byId.getValue(it.id) })
        }
    }
}

/**
 * 条目封面（Bound 特例：无视频取首页 IMAGE 直接解码——loadCover 对 IMAGE 只查伴生封面文件）。
 * [all]=全货架文件列表：SingleEntry 走 loadCover 时伴生封面查找域与现状一致（旧文件封面不劣化）。
 */
fun loadEntryCover(context: Context, entry: ShelfEntry, all: List<ShelfItem>): Bitmap? {
    val coverItem = when (entry) {
        is SingleEntry -> entry.item
        is BoundEntry -> entry.pages.firstOrNull { it.kind == ShelfKind.VIDEO }
            ?: entry.pages.firstOrNull { it.kind == ShelfKind.IMAGE }
            ?: entry.pages.first()
    }
    if (entry is BoundEntry && coverItem.kind == ShelfKind.IMAGE) {
        return runCatching {
            context.contentResolver.openInputStream(coverItem.uri)?.use { BitmapFactory.decodeStream(it) }
        }.getOrNull()
    }
    return loadCover(context, coverItem, all)
}
```

- [ ] **Step 4: 跑测试确认通过 + 全套回归**

```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 17) && ./gradlew testDebugUnitTest
```

Expected: 全绿。

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/top/linkdown/android/ui/ShelfStore.kt app/src/main/java/top/linkdown/android/ui/ShelfPage.kt app/src/test/java/top/linkdown/android/ui/ShelfEntryTest.kt app/src/test/java/top/linkdown/android/ui/ShelfSortTest.kt
git commit -m "feat(shelf): ShelfEntry 模型与装配——Single/Bound 派生字段、条目封面（图文直解码）、entries 排序键序列纯函数、信息条摘要（实况文件名识别）"
```

---

### Task 7: 全链路换型接线（MainActivity + ShelfPage + MainScreen + Application）

**Files:**
- Modify: `app/src/main/java/top/linkdown/android/LinkdownApplication.kt`（ledger 单例 + pipeline env 接线）
- Modify: `app/src/main/java/top/linkdown/android/MainActivity.kt`（shelf 状态 List<ShelfEntry>、refreshShelf 分组+stale 清理、下架 handler entry 粒度、点击分发）
- Modify: `app/src/main/java/top/linkdown/android/ui/ShelfPage.kt`（ShelfScreen/ShelfRow/BookCell 吃 ShelfEntry、×N 角标、信息条摘要、列表行、确认文案、头部计数）
- Modify: `app/src/main/java/top/linkdown/android/ui/MainScreen.kt`（shelf: List<ShelfEntry>、shelfCount/今日出货=条目数、ShipmentArea 吃 entries）
- Test: 全套回归（UI 无 JVM 用例；本任务靠编译 + 既有测试不回归 + Task 9 模拟器验收）

**Interfaces:**
- Consumes: Task 2 ShelfLedger、Task 5 ledgerMerge 缝、Task 6 ShelfEntry/assemble/sortShelfEntries/pagesSummary/loadEntryCover
- Produces: `LinkdownApplication.ledger: ShelfLedger`（lazy）；`MainActivity` 点击语义：Single→`ShelfStore.play`（现状）、Bound→Task 8 阅读器（本任务先临时 play 首页，Task 8 替换）；`ShelfScreen(entries: List<ShelfEntry>, onOpen: (ShelfEntry) -> Unit, onDelete: (ShelfEntry) -> Unit, onBatchDelete: (List<ShelfEntry>) -> Unit, ...)`

- [ ] **Step 1: LinkdownApplication**

import 区加 `import top.linkdown.android.ui.ShelfLedger`；`pipeline` 与 `queue` 声明之间加：

```kotlin
    /** 书架台账（作品键→文件清单；书架合书数据源，与队列持久化同款 DataStore 形态）。 */
    val ledger: ShelfLedger by lazy {
        ShelfLedger.create(File(filesDir, "datastore/shelf_ledger.preferences_pb"), appScope)
    }
```

`pipeline` 声明改为接线台账缝：

```kotlin
    /** 下载管道（队列 runner 与 入口枚举缝共用同一 env/画质档装配）。 */
    val pipeline: SingleDownloadPipeline by lazy {
        val env = AndroidPipelineEnv(this)
        env.ledgerMerge = { merge ->
            runCatching { ledger.merge(merge) }
                .onFailure { runCatching { android.util.Log.w("ShelfLedger", "台账写入失败: ${it.message}") } }
        }
        SingleDownloadPipeline(env, qualityProvider = { settingsCache.value.quality })
    }
```

- [ ] **Step 2: MainActivity**

1. 第 138 行 shelf 状态换型：

```kotlin
        var shelf by remember { mutableStateOf<List<ShelfEntry>>(emptyList()) }
```

（import 加 `top.linkdown.android.ui.ShelfEntry`。）

2. 第 130 行附近取 ledger（与 queue/settingsRepo 并列）：

```kotlin
        val ledger = (application as LinkdownApplication).ledger
```

3. `refreshShelf()`（第 181-183 行）替换：

```kotlin
        fun refreshShelf() {
            scope.launch(Dispatchers.IO) {
                val items = ShelfStore.query(applicationContext)
                val result = ShelfStore.groupShelf(ShelfStore.toKeys(items), ledger.load())
                runCatching { ledger.removeKeys(result.staleKeys) }
                shelf = ShelfStore.assemble(items, result)
            }
        }
```

4. `Screen.SHELF` 接线块（第 358-396 行）：`ShelfScreen(items = shelf, ...)` 改参数名 `entries = shelf`；`onPlay = { item -> ShelfStore.play(...) }` 换为点击分发（Task 8 会替换 Bound 分支为阅读器，本任务临时播放首页）：

```kotlin
                onOpen = { entry ->
                    when (entry) {
                        is BoundEntry -> entry.pages.firstOrNull()?.let { ShelfStore.play(applicationContext, it) }
                        is SingleEntry -> ShelfStore.play(applicationContext, entry.item)
                    }
                },
                onDelete = { entry ->
                    scope.launch(Dispatchers.IO) {
                        var ok = 0
                        val failedNames = mutableListOf<String>()
                        entry.pages.forEach { p ->
                            if (ShelfStore.delete(applicationContext, p)) ok++ else failedNames += p.name
                        }
                        refreshShelf()
                        log(
                            when {
                                failedNames.isEmpty() -> "已下架：${entry.title}（${entry.pages.size} 个文件）"
                                else -> "下架失败：${failedNames.joinToString("、")}"
                            },
                        )
                    }
                },
                onBatchDelete = { entries ->
                    scope.launch(Dispatchers.IO) {
                        var ok = 0
                        val failedNames = mutableListOf<String>()
                        entries.forEach { entry ->
                            entry.pages.forEach { p ->
                                if (ShelfStore.delete(applicationContext, p)) ok++ else failedNames += p.name
                            }
                        }
                        refreshShelf()
                        log(
                            when {
                                failedNames.isEmpty() -> "批量下架完成：${entries.size} 件"
                                else -> "批量下架：${entries.size} 件中失败：${failedNames.joinToString("、")}"
                            },
                        )
                    }
                },
```

（import 加 `top.linkdown.android.ui.BoundEntry`、`top.linkdown.android.ui.SingleEntry`。）

- [ ] **Step 3: ShelfPage 换型**

1. `ShelfScreen` 签名（第 79-88 行）：

```kotlin
fun ShelfScreen(
    entries: List<ShelfEntry>,
    onOpen: (ShelfEntry) -> Unit,
    onDelete: (ShelfEntry) -> Unit,
    onBatchDelete: (List<ShelfEntry>) -> Unit,
    // 其余参数（onToggleShelfBoardMode/onBack/modifier/shelfBoardMode）保持现状
```

2. 函数体内（实现者通读 ShelfScreen 后做机械替换，锚点如下）：
   - `var selecting/selected/confirmBatch` 不变；`val selectedItems = sortedEntries.filter { it.id in selected }`。
   - 排序：`var sortMode` 不变；`val sortedEntries = sortShelfEntries(entries, sortMode)`（Task 6 产物）。
   - 头部计数：`Text("货架 · 全部 ${entries.size} 件", ...)`。
   - 列表模式分支（`if (shelfBoardMode)`）与书架网格分支：每个 `ShelfRow(...)`/`BookCell(...)` 调用点从 item 换 entry。
   - 确认弹窗（第 244-266 行）：

```kotlin
    pendingDelete?.let { entry ->
        PixelConfirmDialog(
            title = "下架这件货？",
            message = when (entry) {
                is BoundEntry -> "「${entry.title}」将从下载目录删除 ${entry.pages.size} 个文件，不可恢复。"
                is SingleEntry -> "「${entry.item.name}」将从下载目录删除，不可恢复。"
            },
            confirmText = "删 除",
            onConfirm = { onDelete(entry); pendingDelete = null },
            onDismiss = { pendingDelete = null },
        )
    }
```

   批量确认框文案不变（件=书）。

3. `ShelfRow` 换型（第 352-420 行附近）：参数 `item: ShelfItem` → `entry: ShelfEntry`；`onPlay: (ShelfItem) -> Unit` → `onOpen: (ShelfEntry) -> Unit`；行首色块/图标类别键用**组内最高优先级页**：`entry.pages.minBy { it.kind.ordinal }.kind`（音频+封面书呈音频色）；标题行：

```kotlin
            Text(
                when (entry) {
                    is BoundEntry -> "${entry.title} (${entry.pages.size} 个文件)"
                    is SingleEntry -> entry.item.name
                },
                color = c.textPrimary, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            Text("${entry.platform} · ${formatSize(entry.sizeBytes)}", color = c.textSecondary, fontSize = 11.sp)
```

   `pointerInput(item.uri, selecting)` 的 key 换 `entry.id`；onTap 分发 `onOpen(entry)`。

4. `BookCell` 换型（第 810-940 行附近，实现者通读后做）：参数 `item: ShelfItem` → `entry: ShelfEntry`，**保留 `all: List<ShelfItem>` 参数**（调用点传 `entries.flatMap { it.pages }` 全货架文件列表，伴生封面查找域与现状一致）；`onPlay: (ShelfItem) -> Unit` → `onOpen: (ShelfEntry) -> Unit`；封面加载：

```kotlin
    var cover by remember(entry.id) { mutableStateOf<android.graphics.Bitmap?>(null) }
    LaunchedEffect(entry.id) {
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            cover = loadEntryCover(context, entry, all)?.let { pixelateCover(it, 88) }
        }
    }
```

   书脊标题：`val title = when (entry) { is BoundEntry -> entry.title; is SingleEntry -> entry.item.name.substringBeforeLast('.').replace(Regex("[【】\\[\\]()（）]"), " ").trim().ifBlank { "未命名内容" } }`。
   信息浮条（BookCell 内现有浮条是一行 `kindLabelZh·platform` 样式文本，高约 21dp/单行省略）：**Single 保持现状不动**；Bound 把该行换成 `platform · ${pagesSummary(...)}` 单行，并在浮条角落叠「×N」角标文本：

```kotlin
    if (entry is BoundEntry) {
        val summary = pagesSummary(entry.pages.map { it.name }, entry.pages.map { it.kind })
        // 浮条主行文本 = "${entry.platform} · $summary"；浮条一隅加 Text("×${entry.pages.size}") 角标
    }
```

   视觉跟随现有浮条样式（同色系 Text、fontSize 11.sp 内、单行省略勿撑爆 21dp 条），不新造组件；摘要过宽被省略可接受（Task 9 Step 6 断言依赖 ×N 文本存在，优先保角标）。
   `remember(item.uri)`/`pointerInput(item.uri, ...)` 的 key 一律换 `entry.id`；onTap 分发 `onOpen(entry)`。
   选择态接线（`ShelfBoard`/`ShelfPlankRow` 等 isSelected/onToggle lambda）：键从 `it.uri.toString()` 换 `it.id`（`selected` 已是 `Set<String>`，天然兼容）。

5. 文件内其余 `items.size`/`items[it]` 等残余引用按编译器指示机械替换为 entries 形态；**不改**排序按钮交互、空柜搁板、翻页逻辑。

- [ ] **Step 4: MainScreen 换型**

1. 第 102 行参数 `shelf: List<ShelfItem>` → `shelf: List<ShelfEntry>`（import 加 `ShelfEntry`）。
2. 第 132 行 `ShopHeader(shelfCount = shelf.size, ...)` 不动（N=条目数=书数，语义已变对）。
3. 第 217 行 `Text("今日出货 ${shelf.size} 件", ...)` 不动（同上）。
4. 第 120 行 `onPlayShelfItem: (ShelfItem) -> Unit` → `onOpenShelfEntry: (ShelfEntry) -> Unit`；第 221 行 `ShipmentArea(shelf.take(3), onOpenShelfEntry)`。
5. `ShipmentArea`（第 589-627 行）换型：`recent: List<ShelfEntry>`、`onOpen: (ShelfEntry) -> Unit`；循环变量 `item` 统一改名 `entry: ShelfEntry`，行内标题：

```kotlin
                        Text(
                            when (entry) {
                                is BoundEntry -> entry.title
                                is SingleEntry -> entry.item.name
                            },
                            color = c.textPrimary, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false),
                        )
```

   （`formatSize(entry.sizeBytes)`、`entry.platform` 同步换。）
6. MainActivity 调用 `MainScreen(...)` 处第 120 行实参同步改名。

- [ ] **Step 5: androidTest 适配（MainScreenTest 等构造点会因 ShelfItem+relPath / MainScreen+ShelfScreen 签名换型编译失败）**

```bash
grep -rn "ShelfItem(\|onPlayShelfItem\|MainScreen(" app/src/androidTest/ | head -20
```

对命中的 androidTest 构造点机械适配：`ShelfItem(...)` 补 `relPath = "Download/链接下载/<platform>/"`（位置参数改具名或按序插入，MainScreenTest.kt:279 是 6 参位置构造）；MainScreen/ShelfScreen 调用实参换 `onOpenShelfEntry`/`onOpen` 与 entries 形态（fixture 构造 `listOf(SingleEntry(item))` 包一层，import `SingleEntry`）。断言语义不变，只适配签名。

- [ ] **Step 6: 全量编译 + 回归（含 androidTest 编译验证）**

```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 17) && ./gradlew clean testDebugUnitTest assembleDebug :app:compileDebugAndroidTestKotlin
```

Expected: 全绿 + APK 产物正常 + androidTest 编译通过。UI 行为（角标/文案/翻页）由 Task 9 模拟器验收。

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/top/linkdown/android/LinkdownApplication.kt app/src/main/java/top/linkdown/android/MainActivity.kt app/src/main/java/top/linkdown/android/ui/ShelfPage.kt app/src/main/java/top/linkdown/android/ui/MainScreen.kt app/src/androidTest/
git commit -m "feat(shelf): 全链路换型——书架/主页以作品条目为单位（×N 角标+信息条摘要+作品粒度下架文案+台账 stale 清理+出货区条目化）"
```

---

### Task 8: BookReader 阅读器

**Files:**
- Create: `app/src/main/java/top/linkdown/android/ui/BookReader.kt`
- Modify: `app/src/main/java/top/linkdown/android/MainActivity.kt`（readerEntry 状态 + BackHandler + 覆盖层）
- Modify: `app/src/main/java/top/linkdown/android/ui/ShelfPage.kt`（onOpen 分发 Bound→阅读器，Single→play 保持现状——分发在 MainActivity，ShelfPage 只透传）
- Test: 全套回归（Compose UI 无 JVM 用例；Task 9 模拟器验收）

**Interfaces:**
- Consumes: Task 6 ShelfEntry/pages；`ShelfStore.play`
- Produces: `ui.BookReader(entry: ShelfEntry, onDismiss: () -> Unit, onPlayPage: (ShelfItem) -> Unit)`（全屏覆盖层 Composable）

- [ ] **Step 1: 实现 BookReader.kt**

```kotlin
package top.linkdown.android.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 作品阅读器（设计 §8）：全屏覆盖层，横向翻页。
 * 图片页（含 .MP.jpg 实况合成图，按静图显示）降采样解码；视频/音频/其他页「▶ 播放」占位，
 * 点击复用 ShelfStore.play Resolver 路径，阅读器保持打开。
 */
@Composable
fun BookReader(entry: ShelfEntry, onDismiss: () -> Unit, onPlayPage: (ShelfItem) -> Unit) {
    val pagerState = rememberPagerState { entry.pages.size }
    Box(
        Modifier
            .fillMaxSize()
            .background(androidx.compose.ui.graphics.Color(0xEE120A04)),
    ) {
        Column(Modifier.fillMaxSize()) {
            // 顶栏：标题 + 关闭
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(androidx.compose.ui.graphics.Color(0xFF4A2E18))
                    .padding(horizontal = 12.dp, vertical = 10.dp),
            ) {
                Text(
                    entry.title,
                    color = androidx.compose.ui.graphics.Color(0xFFFFF6E3),
                    fontSize = 15.sp, fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    "关 闭",
                    color = androidx.compose.ui.graphics.Color(0xFFFFF6E3),
                    fontSize = 13.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier
                        .clickable(MutableInteractionSource(), indication = null) { onDismiss() }
                        .padding(6.dp),
                )
            }
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.weight(1f),
            ) { pageIdx ->
                val page = entry.pages[pageIdx]
                when (page.kind) {
                    ShelfKind.IMAGE -> ReaderImagePage(page)
                    else -> ReaderPlayPage(page, onPlayPage)
                }
            }
            // 底部页码
            Text(
                "第 ${pagerState.currentPage + 1}/${entry.pages.size} 页",
                color = androidx.compose.ui.graphics.Color(0xFFFFF6E3),
                fontSize = 12.sp,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(androidx.compose.ui.graphics.Color(0xFF2A1A0E))
                    .padding(vertical = 8.dp),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
        }
    }
}

@Composable
private fun ReaderImagePage(page: ShelfItem) {
    val context = LocalContext.current
    var bitmap by remember(page.uri) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(page.uri) {
        withContext(Dispatchers.IO) { bitmap = decodeSampled(context, page.uri) }
    }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        val bmp = bitmap
        if (bmp != null) {
            Image(
                bitmap = bmp.asImageBitmap(),
                contentDescription = page.name,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Text("加载中…", color = androidx.compose.ui.graphics.Color(0xFFC9B88C), fontSize = 13.sp)
        }
    }
}

@Composable
private fun ReaderPlayPage(page: ShelfItem, onPlayPage: (ShelfItem) -> Unit) {
    Box(
        Modifier
            .fillMaxSize()
            .clickable(MutableInteractionSource(), indication = null) { onPlayPage(page) },
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("▶", color = androidx.compose.ui.graphics.Color(0xFFFFF6E3), fontSize = 42.sp, fontWeight = FontWeight.Bold)
            Text(page.name, color = androidx.compose.ui.graphics.Color(0xFFC9B88C), fontSize = 12.sp, maxLines = 2)
            Text("点击播放", color = androidx.compose.ui.graphics.Color(0xFF8A7A5A), fontSize = 11.sp)
        }
    }
}

/** 屏幕尺寸降采样解码（设计 §8：对齐 loadCover 的防御风格，失败 null）。 */
private suspend fun decodeSampled(context: android.content.Context, uri: Uri): Bitmap? =
    withContext(Dispatchers.IO) {
        runCatching {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@runCatching null
            val metrics = context.resources.displayMetrics
            val reqW = metrics.widthPixels.coerceAtLeast(1)
            val reqH = metrics.heightPixels.coerceAtLeast(1)
            var sample = 1
            while (bounds.outWidth / (sample * 2) >= reqW && bounds.outHeight / (sample * 2) >= reqH) sample *= 2
            val opts = BitmapFactory.Options().apply { inSampleSize = sample }
            context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
        }.getOrNull()
    }
```

注：若项目 Compose 版本无 `androidx.compose.foundation.pager.HorizontalPager`（编译报错），回退实现为 `LazyRow` + `LazyListState` 逐页 snap（保持同一对外签名与页码逻辑），并在提交信息注明。

- [ ] **Step 2: MainActivity 接线**

1. import 加 `top.linkdown.android.ui.BookReader`。
2. shelf 状态旁加：

```kotlin
        var readerEntry by remember { mutableStateOf<ShelfEntry?>(null) }
```

3. `BackHandler` 区（第 185-186 行）加：

```kotlin
        BackHandler(enabled = readerEntry != null) { readerEntry = null }
```

4. `Screen.SHELF` 接线块的 `onOpen` 分发改（Task 7 的临时播放替换）：

```kotlin
                onOpen = { entry ->
                    when (entry) {
                        is BoundEntry -> readerEntry = entry
                        is SingleEntry -> ShelfStore.play(applicationContext, entry.item)
                    }
                },
```

5. 覆盖层落位（**z 序关键**）：`when (screen) {...}` 块**之后**、首启用户协议（EulaPromptDialog）块**之前**——Compose 根级兄弟节点后画者在上，放前面会被主界面盖住（不可见）：

```kotlin
        readerEntry?.let { entry ->
            BookReader(
                entry = entry,
                onDismiss = { readerEntry = null },
                onPlayPage = { page -> ShelfStore.play(applicationContext, page) },
            )
        }
```

6. 主页出货区点击（MainActivity 调 `MainScreen(...)` 的 `onOpenShelfEntry` 实参）同步：

```kotlin
                onOpenShelfEntry = { entry ->
                    when (entry) {
                        is BoundEntry -> readerEntry = entry
                        is SingleEntry -> ShelfStore.play(applicationContext, entry.item)
                    }
                },
```

- [ ] **Step 3: 全量编译 + 回归**

```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 17) && ./gradlew clean testDebugUnitTest assembleDebug
```

Expected: 全绿 + APK 正常。

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/top/linkdown/android/ui/BookReader.kt app/src/main/java/top/linkdown/android/MainActivity.kt app/src/main/java/top/linkdown/android/ui/ShelfPage.kt
git commit -m "feat(shelf): BookReader 作品阅读器——横向翻页+页码+降采样图片页（实况按静图）+视频/音频页复用播放 Resolver；Bound 单击进阅读器（书架+主页出货区）"
```

---

### Task 9: 全套 JVM + 模拟器 E2E 验收

**Files:** 无新码（验证任务；发现问题回上游任务修）

**Interfaces:**
- Consumes: 全部前序任务产物（debug APK）
- Produces: 验收记录（覆盖范围如实报告：图文多图需抖音登录，留真机验收）

- [ ] **Step 1: 全套 JVM**

```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 17) && cd /Users/liuyouwu/Desktop/个人研究/linkdown-m1-handoff/linkdown-android && ./gradlew clean testDebugUnitTest
```

Expected: 全绿。红 → 回对应任务修，重跑本任务。

- [ ] **Step 2: 编 debug APK（clean 必做，AGP 增量编译坑）**

```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 17) && ./gradlew clean assembleDebug && ls -la app/build/outputs/apk/debug/
```

Expected: `app-debug.apk` 产出（宿主 arm64 → 默认产物含 arm64-v8a，与模拟器镜像匹配）。

- [ ] **Step 3: 起模拟器（arm64 镜像；无则先建）**

```bash
EMULATOR=~/Library/Android/sdk/emulator/emulator
$EMULATOR -list-avds
```

- 有可用 AVD：`$EMULATOR -avd <名字> -http-proxy http://127.0.0.1:7890 -no-snapshot-save &`（走 clash 代理保证 Telegram 可达；等待 `adb wait-for-device` + `adb shell getprop sys.boot_completed` 为 `1`）。
- 无可用 AVD：`~/Library/Android/sdk/cmdline-tools/latest/bin/avdmanager create avd -n linkdown-test -k "system-images;android-35;google_apis;arm64-v8a" -d pixel_5`（镜像缺则先 `~/Library/Android/sdk/cmdline-tools/latest/bin/sdkmanager "system-images;android-35;google_apis;arm64-v8a"`），再按上行启动。
- 代理端口以本机 clash 实际监听为准（`http_proxy` 环境变量或 7890 常规端口；不可达则 Telegram 下载必败，先修网络再继续）。

- [ ] **Step 4: 安装 + 冷启动**

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n top.linkdown.android/.MainActivity
```

Expected: 安装成功、协议弹窗出现（首次）→ adb 点「同意」坐标（uiautomator dump 定位文本 bounds；Compose 控件 bounds 偶发漂移时用像素扫描兜底，见 AGENTS.md 真机注意）。

- [ ] **Step 5: 下载一个多文件作品（Telegram 公开频道视频帖，无需登录）**

1. 挑一个稳定的公开频道视频帖 URL（执行时用 `curl -x http://127.0.0.1:7890` 预检 `https://t.me/s/<channel>/<id>` 返回 200 且含 `tgme_widget_message_video`；当前不可用就换频道/帖，**不写死具体帖号**）。
2. UI 流：点输入框 → `adb shell input text "<URL>"` → 勾选「视频」+「封面」→ 点下载 → 等队列完成（轮询 `adb shell logcat -d | grep -i "已保存\|完成"` 或 uiautomator 查队列卡状态，超时 120s 报失败）。
3. 落盘断言（两个文件、同目录）：

```bash
adb shell content query --uri content://media/external/downloads --projection _display_name,relative_path --where "relative_path LIKE 'Download/链接下载/%'"
```

Expected: 该作品 ≥2 个文件（mp4 + 封面 jpg）。

- [ ] **Step 6: 书架合书断言**

1. 进书架（底部/侧边入口，uiautomator 定位）。
2. `adb shell uiautomator dump /sdcard/ui.xml && adb shell cat /sdcard/ui.xml | grep -o 'content-desc="[^"]*"' | head -50`（或拉到本地搜文本）。
3. 断言：同一作品的两个文件只出现 **一本书**（书脊/信息条含作品标题，且「×2」或「2 页」文本存在）；头部「货架 · 全部 N 件」N=书数。

- [ ] **Step 7: 阅读器断言**

1. 单击该书 → dump 断言「第 1/2 页」与作品标题顶栏。
2. `adb shell input swipe 800 1200 200 1200 300`（右→左翻页）→ dump 断言「第 2/2 页」。
3. 点「关 闭」→ 回书架。

- [ ] **Step 8: 作品粒度下架断言**

1. 长按该书 → 确认弹窗文案含「2 个文件」→ 点「删 除」。
2. 复查 Step 5 的 content query：该作品文件归零；书架回空/少一书。

- [ ] **Step 9: 回归冒烟（单文件行为不变）**

下载任意单文件作品（如 Telegram 图片帖单图）→ 书架显示为一本书（无 ×N）→ 单击直接走 ACTION_VIEW 播放/查看（不进阅读器）→ 长按下架文案为单文件版。

- [ ] **Step 10: 验收记录**

如实记录（不留空话）：覆盖了什么、没覆盖什么（**图文 9 图成书、实况 .MP.jpg 页、多模式混合摘要**需抖音登录/真机实况格式，留真机验收）、模拟器上发现的缺陷与回修。记录写入本计划文件末尾「验收记录」节并 commit：

```bash
git add docs/superpowers/plans/2026-11-07-shelf-work-book.md
git commit -m "docs(plan): 书架成书模拟器 E2E 验收记录"
```

---

## Self-Review 记录

1. **Spec 覆盖**：§5 台账（T1/T2）✓ §5a 回查（T4）✓ §6 合书（T3）✓ §6 封面（T6 loadEntryCover）✓ §7 UI（T7）✓ §8 阅读器（T8）✓ §9 下架（T7 文案+循环删+失败口径）✓ §10 排序（T6）✓ §11 边界（T2 损坏/T3 三态/T4 回退/T5 半程）✓ §12 测试（T1-T6）✓ 模拟器（T9）✓；MainScreen（T7）✓。
2. **占位符扫描**：无 TBD/TODO；UI 换型步骤给了锚点+代码片段+「实现者通读后机械替换」的明确边界（Compose 像素代码不做整文件重写是刻意决定，非占位）。
3. **类型一致性**：`IngestedFile(uri?, displayName, relPath)`（T4 定义，T5 消费）✓；`LedgerMerge/FileRef` pipeline 包（T1 定义，T5 消费）✓；`shelfEntrySortedIndices(titles, kindOrdinals, dates, mode)` 键序列签名（T6 自洽）✓；`onOpen: (ShelfEntry) -> Unit`（T7 签名，T8 分发）✓。
4. **Review Focus 回填**：并发单 edit（T2 实现+评审核）；回查真名（T4 实现+T9 真名链路）；外部删三态（T3 测试）；同名不误并（T3 测试）；半程合并（T5 测试）；TIME 恒等（T3/T6 测试）。

## 评审修订记录

- **R1（计划评审 round 1：P0×4/P1×6/P2×6 全采纳）**：matched==1 改「保留、不认领」（修实现/测试/规格三方矛盾，规格 §6 同步 v2.2）；同title测试补 a3 键；ShelfKey/Group 五类型钉死嵌套 `object ShelfStore`；损坏文件测试改「先写坏再建唯一实例」；补 DedupCheck/LedgerMerge import；删 Task 1 自相矛盾断言；补 sortShelfEntries 实现+三键提取约定；loadEntryCover 加 all 参数（伴生封面查找域不劣化）；BookReader 覆盖层落位钉死 when(screen) 之后；补 androidTest 适配步骤+compileDebugAndroidTestKotlin 验证；浮条描述按真实实现修正（kindLabelZh·platform）；ShipmentArea 片段统一 entry；ledgerMerge 失败补日志；删未用 import。

---

## 验收记录（Task 9：全套 JVM + 模拟器 E2E，2026-11-08）

**环境**：本机无现成 AVD，新装 `emulator` + `system-images;android-35;google_apis;arm64-v8a`（约 2.2GB），`avdmanager create avd -n linkdown-test -d pixel_5`（1080×2340@440dpi，arm64）；无窗口启动 `-no-window -no-audio -gpu swiftshader_indirect -http-proxy http://127.0.0.1:7890`。UI 驱动=uiautomator dump 文本定位 + 截图像素扫描（🗑 勾选框等 canvas 图元无语义节点）兜底。

**结果**：JVM 403 用例 0 失败 0 跳过（缺陷修复 clean 重建后重跑仍 403/0）；E2E Step 5–9 全过；发现 1 个缺陷并已修复（commit 23e0b4a）。

### 分步结果
- **Step 1/2**：`clean testDebugUnitTest` 全绿；`clean assembleDebug` 出 app-debug.apk（75MB，arm64-v8a）。
- **Step 4**：安装 Success；首启通知权限（拒）/用户协议（同意）/电池引导（暂不）均 uiautomator 点过，主界面正常。
- **Step 5 多文件**：t.me/telegram/447（@telegram 频道 15s 视频帖，执行时 curl 预检 200+`tgme_widget_message_video`）勾「视频 mp4」+「封面」→ `Download/链接下载/Telegram/` 落 3 文件：`telegram - Rich Text Formatting for Bots. Telegram.mp4`（3.6MB）+ 同名 `-cover.jpg`（伴生海报）+ `Rich Text Formatting for Bots. Telegram.jpg`（仅封面产物）——≥2 同目录达标。
- **Step 6 合书**：书架「货架 · 全部 1 件」；唯一一书=作品标题封面 + 角标 **×3** + 信息条「Telegram · 3 页 · 含视频 · 2 图」——同作品 3 文件只成 1 书。
- **Step 7 阅读器**：单击进阅读器，顶栏标题+「关 闭」+「第 1/3 页」→ 右→左滑「第 2/3 页」（图片页解码渲染）→「第 3/3 页」（视频页 ▶/点击播放占位）→「关 闭」回书架。
- **Step 8 下架**：列表行「Rich Text Formatting for Bots. Telegram  (3 个文件)」→ 🗑 → 弹窗「『…』将从下载目录删除 **3 个文件**，不可恢复。」→ 删 除 → MediaStore 该作品归零、货架「全部 0 件」空态。（计划写「长按」——实现为长按进多选、单作品删除走行内 🗑，文案断言等价达成。）
- **Step 9 单文件回归**：t.me/telegram/458（单图帖）弹窗自适应「图片 · 1 张/封面（首图）」，只勾图片 → 落盘 1 个 jpg；列表行=原始文件名（无「(N 个文件)」），书柜模式 0 个 ×N；单击行直接 ACTION_VIEW 拉起系统相册（Share/Edit/Lens）未进阅读器；🗑 弹窗=单文件版（无「个文件」字样）。测试文件已删净归零。

### 缺陷与修复（Task 9 发现，commit 23e0b4a）
- **BookReader 顶栏「关 闭」/底栏页码不可点**：阅读器覆盖层是根级兄弟节点，未走主界面 screenModifier 的 `safeDrawingPadding()`，「关 闭」可点击区 [3,135] 整段落在本模拟器 136px 高的状态栏窗口内被系统拦截。修复=覆盖层根 Box 补 `.safeDrawingPadding()`；修复后按钮 bounds 移至 [139,271]，点击正常关闭。修复后 JVM 重跑 403/0。

### 环境补丁（模拟器网络，非应用缺陷）
- QEMU `-http-proxy` 只代理 TCP 不代理 DNS，模拟器内 t.me 被 DNS 污染到 127.0.0.1 → `settings put global http_proxy 10.0.2.2:7890` 走系统代理（域名交 clash 解析）后可达。
- 验证探针经代理不稳定 → 网络被判 PARTIAL_CONNECTIVITY（无 VALIDATED），队列派发门按设计卡「排队中」→ `settings put global captive_portal_mode 0` 跳过探针后正常派发。

### 未覆盖（留真机验收）
- 图文 9 图成书、实况 .MP.jpg 页、多模式混合摘要（含音频）：需抖音登录/真机实况格式。
- 阅读器视频/音频页「▶ 播放」外跳 Resolver 实播：未点击（页占位渲染已验证）。
- 软渲染下书柜翻页帧率、大图降采样耗时：模拟器数值无参考价值。
- 范围外观察：失败单「重 试」按既有设计新建记录（retrySingleLocked copy 新 UUID），旧 Failed 卡留队列区，本次 2 败 1 成共 3 条同 URL 记录；失败记录 completedModes=[] 未入台账，货架无脏数据。
