# T-AutoSaverSOE：自动保存历史 SQL StackOverflowError 修复 开发计划

> **创建日期**：2026-09-04
> **分支**：`feature/autosaver-stackoverflow-fix`（从 master 切出）
> **优先级**：P1（生产崩溃级缺陷：跨天后首次写入必现）
> **预估工时**：2~4h
> **来源**：用户真机 IDEA 报错堆栈（2026-09-04 00:09 触发），经与当前 HEAD 代码比对确认问题未修复

---

## 一、问题与根因

### 1.1 现象

IDEA 中插件抛出异常（两份堆栈，同一根因）：

```
com.intellij.execution.filters.CompositeFilter$ApplyFilterException:
    Error while applying MyBatisLogConsoleFilter to '... ==> Preparing: SELECT COUNT( * ) ...'
Caused by: java.lang.StackOverflowError
    at cn.song.mybatisplus.visualizer.log.SqlLogAutoSaver.resolveFileName$lambda$0(SqlLogAutoSaver.kt:160)
    at kotlin.text.Regex.replace(Regex.kt:196)
    at cn.song.mybatisplus.visualizer.log.SqlLogAutoSaver.resolveFileName(SqlLogAutoSaver.kt:157)
    at cn.song.mybatisplus.visualizer.log.SqlLogAutoSaver.write(SqlLogAutoSaver.kt:71)
    at cn.song.mybatisplus.visualizer.log.SqlLogAutoSaver.write(SqlLogAutoSaver.kt:74)   ← 重复上千帧
    ...
```

### 1.2 未修复的判定依据

堆栈中四个行号与当前 master HEAD 的 `SqlLogAutoSaver.kt` 逐行对齐：

| 堆栈行号 | 当前代码（HEAD） |
|---------|-----------------|
| L160 | `val formatted = SimpleDateFormat(formatStr, Locale.ENGLISH).format(Date(now))` |
| L157 | `return fileNamePattern.replace(PLACEHOLDER_REGEX) { mr ->` |
| L71 | `val resolved = resolveFileName()` |
| L74 | `write(entry)` |

该文件自 2969b0f 后无改动（`git log` + `git diff HEAD` 均无变化），即"旧版本"与最新代码在此文件上完全一致，缺陷仍在。

### 1.3 根因：write() 的文件切换递归永无出口

当前实现（`SqlLogAutoSaver.kt:67-78`）：

```kotlin
fun write(entry: LogSqlEntry) {
    val w = ensureWriter() ?: return
    val resolved = resolveFileName()
    if (resolved != currentResolvedName || (currentFile?.length() ?: 0) >= rollThresholdBytes) {
        // 切换后重新写入（递归一次）      ← 注释的前提不成立
        write(entry)
        return
    }
    writeEntry(w, entry)
}
```

递归前**没有任何代码更新 `currentResolvedName`**；递归进入后 `ensureWriter()` 因 `writer != null` 直接短路返回（它只在首次打开时赋值 `currentResolvedName`），于是 `resolved != currentResolvedName` 恒为真，条件永远成立，无限递归直到栈耗尽。SOE 恰好抛在最内层 `resolveFileName()` 新建 `SimpleDateFormat` 处（L160），只是"压垮骆驼的最后一根稻草"，不是原因。

### 1.4 三个触发场景（全部必现）

| 场景 | 触发条件 | 频率 |
|------|---------|------|
| 跨天 | 默认模板 `sql-history-$yyyy-MM-dd$.sql`，跨天后第一次写入，新日期名 != 旧日期名 | 每天启用自动保存的用户**必现一次**；用户报错发生在 00:09:11，正是跨天后第一条 SQL |
| 细粒度时间模板 | 模板含 `$HH$`/`$mm$`/`$ss$`/`$SSS$` | 每次写入必现，第一个条目即崩 |
| 单文件滚动 | 文件达 100MB 阈值，条件第二支恒真 | 大量写入后必现 |

### 1.5 触发链路（为什么炸在控制台过滤器里）

```
AsyncFilterRunner 后台 read 线程（控制台超链接过滤）
  → MyBatisLogConsoleFilter.applyFilter
  → SqlLogConsoleBridge.processConsoleLine（SqlLogConsoleBridge.kt:319）
  → SqlLogEntryBuffer.addEntry → 容量淘汰
  → buffer.onEvicted（SqlLogManager.kt:262）
  → SqlLogAutoSaver.write   ← 同步执行，SOE 直接炸穿过滤器
```

SOE 被 `CompositeFilter` 包装成 `ApplyFilterException` 记入日志。运行在后台线程，不冻结 UI，但被淘汰条目丢失、异常持续刷屏。

### 1.6 同文件附带缺陷（顺带修复）

1. **线程安全**：`write`/`configure`/`close` 操作 `writer`/`currentFile`/`currentResolvedName`/`currentIndex` 四个可变字段无任何同步，而 `write` 来自控制台过滤器的任意后台线程，存在竞态（双开 writer、状态错乱）。
2. `resolveFileName()` 每次调用新建 `SimpleDateFormat`，每条日志两次（次要，修完 1.3 后顺手缓存）。

---

## 二、技术方案

### 2.1 递归改单趟迭代（核心修复）

删除递归与 `ensureWriter()` 的懒打开语义，改为一次遍历内完成"切换判断 → 打开 → 写入"。切换分支显式更新状态，条件自然收敛：

```kotlin
fun write(entry: LogSqlEntry) {
    val resolved = resolveFileName()
    if (resolved != currentResolvedName) {
        // 文件名变化（跨天/模板变更）：重开文件，滚动序号复位
        close()
        currentResolvedName = resolved
        currentIndex = 1
        openWriterForCurrent()
    } else if ((currentFile?.length() ?: 0L) >= rollThresholdBytes) {
        // 单文件超限：滚动序号递增（xxx.sql → xxx.2.sql）
        close()
        currentIndex += 1
        openWriterForCurrent()
    }
    val w = writer ?: return   // 打开失败（无效路径）已 LOG.warn，静默跳过
    writeEntry(w, entry)
}
```

首次写入天然覆盖：`configure()` 已将 `currentResolvedName` 置空串，`resolved != ""` 成立即走打开分支，原 `ensureWriter()` 可整体删除。

### 2.2 注入时钟，让跨天可测

`resolveFileName()` 直读 `System.currentTimeMillis()`，跨天路径无法在单测中稳定触达。构造函数注入时钟：

```kotlin
class SqlLogAutoSaver(private val clock: () -> Long = System::currentTimeMillis) {
    ...
    fun resolveFileName(): String = resolveFileName(clock())
    internal fun resolveFileName(now: Long): String { ... 原逻辑，now 替换 System.currentTimeMillis() ... }
}
```

生产路径零变化，测试可精确模拟跨天。

### 2.3 线程安全

`write`/`configure`/`close` 加 `@Synchronized`（同一把对象锁即可，无高频竞争，开销可忽略）。`writeEntry` 内已有 `synchronized(w)` 可保留。

### 2.4 滚动阈值可注入（测试需要）

```kotlin
class SqlLogAutoSaver(
    private val clock: () -> Long = System::currentTimeMillis,
    internal val rollThresholdBytes: Long = 100L * 1024 * 1024,
)
```

单测传小阈值即可验证 100MB 滚动路径，不必真写 100MB。

---

## 三、实施步骤

| # | 任务 | 说明 |
|---|------|------|
| 1 | 建分支 | `git checkout -b feature/autosaver-stackoverflow-fix`。注意当前工作区有 JOIN 图相关未提交改动（build.gradle.kts / ui/diagram 等），与本修复无关，切分支前先 stash 或让用户确认 |
| 2 | 重构 `SqlLogAutoSaver` | 按 2.1~2.4 改造；删除 `ensureWriter()`；`resolveFileName` 拆出 `now` 参数重载 |
| 3 | 补回归测试 | 见第四节用例清单，全部纯 JUnit（该测试类无平台依赖，直接跑） |
| 4 | 跑测试 | 跑 gradlew 前先 `python C:\Users\USER\.zcode\cli\parallel_tasks.py check`；`gradlew test --tests "cn.song.mybatisplus.visualizer.log.SqlLogAutoSaverTest"` + log 包全量 |
| 5 | 沙盒验证 | prepareSandbox + runIde（沙盒 jar 锁定铁律：先删沙盒插件目录重建，grep jar 内 class 验证）。自动保存模板临时设为含 `$ss$` 的模板，产生日志观察：文件按秒切换、无异常、无 SOE——等效覆盖"名字变化切换"路径（真实跨天无法等待） |
| 6 | 收尾 | 提交信息：`fix: T-AutoSaverSOE 自动保存历史SQL文件切换无限递归致StackOverflowError`；CHANGELOG/变更记录同步；文档三处同步约定 |

---

## 四、测试计划（SqlLogAutoSaverTest 新增用例）

| 用例 | 做法 | 断言 |
|------|------|------|
| 跨天切换不再死递归（**核心回归**） | fake clock 固定 T0，配置 `$yyyy-MM-dd$` 模板，write 一条；clock 拨到 +25h 再 write | 无 SOE；生成两个文件，新文件名为次日日期；旧文件 1 条、新文件 1 条 |
| 秒级模板连续写入 | `$HH-mm-ss$` 模板，clock 每次拨 2s，连续 write 3 条 | 无 SOE；3 个文件各 1 条 |
| 单文件滚动 | `rollThresholdBytes` 设 100，同一天内 write 到超限 | 滚动出 `.2.sql`，序号递增正确；无 SOE |
| 跨天后滚动序号复位 | 先触发滚动到 `.2.sql`，再拨时钟跨天 | 新一天文件无序号后缀（从 `.sql` 重新起算） |
| 时钟注入不改生产行为 | 默认构造，现有 10 个用例全部保持通过 | 现有用例零修改通过 |

现有测试恰好全用天级模板且同天写入，绕开了缺陷——这正是它漏网的原因，新用例补上这三个死区。

---

## 五、风险与回滚

- **风险低**：改动收敛在单文件约 30 行；落盘格式（`-- source: ... --` 头 + SQL）完全不变，Reader 兼容性不受影响。
- **行为差异**：原实现文件名切换时不清空滚动序号（但因为递归 bug 从未真正切换成功过，无历史行为可破坏）；新实现跨天复位序号、超限递增序号，语义更正确。
- **回滚**：单 commit revert 即可。
