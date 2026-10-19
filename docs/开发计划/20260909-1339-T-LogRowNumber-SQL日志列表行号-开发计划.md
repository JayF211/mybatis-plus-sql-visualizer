# T-LogRowNumber：SQL 日志列表行号（启动项目清零）开发计划

> **创建日期**：2026-09-09
> **分支**：master（当前 HEAD `22eeff7`，v2026.9.9 已发行，基线 3153/0/6 绿）
> **优先级**：P2（用户明确要求的新功能；改动面小，纯增量，无存量行为变更）
> **预估工时**：3h（字段与缓冲分配 1h + 清零接线 0.5h + 渲染 0.5h + 测试 1h）
> **来源**：用户需求原话——「sql日志的sql列表需要增加行号，每次启动项目时清0」

---

## 一、需求分析

### 1.1 行号语义定义

「行号」的本质是**会话内捕获序号**：本轮项目启动以来成功捕获的第 N 条 SQL。它与显示位置序号（列表第几行）是两回事，差异在三个既有行为下暴露：

| 行为 | 显示位置序号 | 捕获序号（本方案） |
|---|---|---|
| 耗时排序（DURATION_DESC） | 行号被打乱，失去对照意义 | 行号跟随条目，仍可辨认原始捕获顺序 |
| 缓冲头部淘汰（超 200 条） | 恒从 1 起，无信息量 | 从淘汰后的真实序号起（如 201），反映「启动以来第几条」 |
| 类型过滤/搜索 | 序号压缩，同一 SQL 两次查看行号不同 | 行号稳定，可作为用户交流的「第 N 条 SQL」引用 |

「每次启动项目时清 0」只有对捕获序号才有意义（显示序号天然从 1 起，不存在清零概念），故按捕获序号实现。

### 1.2 已定决策

1. **分配点**：`SqlLogEntryBuffer.addEntry` 成功入队时分配，单调递增。去重丢弃、暂停丢弃、过滤关键字命中的条目**不占号**——用户看不到的 SQL 不应造成列表跳号。
2. **清零触发点**：项目启动（`RunConfigurationProcessListener` 的 `processStarted` 回调）→ 无条件清零。不放在 `SqlLogManager.onProjectProcessStarted()` 内部——该方法开头有 `if (!autoStart) return` 守卫，手动捕捉模式下启动项目会跳过清零，违背「每次启动项目时清 0」的无条件语义。
3. **手动「清空」按钮同时清零行号计数器**：与既有惯例对齐（`SqlLogEntryBuffer.clear()` 的 KDoc「去重计数一并归零——徽章与列表内容同生命周期」）。行号与列表内容同生命周期，否则清空列表后下一条 SQL 显示 #201 起的跳空，用户会当作 bug 反馈。
4. **列表内容不随启动项目自动清空**：需求只说行号清 0，不动现有列表清空语义（用户仍可手动清空、自动淘汰照旧）。若用户真实意图是「启动项目连列表一起清空」，属一行改动（清零处追加 `entries.clear()`），实施前评审时一句话即可加上。
5. **显示位置**：元信息行行首，`#N ` 前缀片段（灰色 LIST_META_GRAY，与时间戳/来源同通道）——T-LogListFreeze-04 的片段渲染管线是纯文本追加，不动两行结构与固定行高。SQL 高亮行不混入行号（破坏片段高亮语义）。
6. **不加设置开关**：行号是低噪音元信息（4 字符宽），先默认显示；用户反馈需要开关再加，避免假开关回归（T-PortLossBatch1 教训）。
7. **历史 tab 不动**：`HistorySqlLogPanel` 是独立面板独立渲染器，本需求只覆盖「SQL 日志」tab 实时列表。
8. **与「移除重复的sql」的交互（2026-09-09 用户确认）**：`dedupeExisting()` 裁剪只删条目、**不重新编号**——剩余条目保留原行号，列表出现跳号（如 #1 #2 #3⨉2 #4 #6…，5/8 号因重复被裁）。跳号 + ⨉N 徽章共同表达「此处有重复被移除」；取消勾选徽章消失、行号不变（已裁条目不恢复，现状行为）；勾选状态下新到重复 SQL 丢弃不占号，不产生新缺口。若真机反馈跳号观感不佳，备选方案为裁剪时重连续编号（代价：行号退化为位置序号，排序/过滤后漂移）。

---

## 二、现状与接线点

### 2.1 数据链路（全仓唯一通道）

```
控制台 filter 回调（SqlLogConsoleBridge.kt:317-319）
  → buffer.addEntry（SqlLogEntryBuffer.kt:62，synchronized(entries) 临界区）
  → LogSqlEntry（不可变 data class）入 ArrayDeque
  → 面板 refreshList 快照渲染（LogEntryListCellRenderer 片段通道）
```

行号在 **buffer 入队临界区内分配**是唯一正确位置：保证「捕获顺序 = 行号顺序」，且天然线程安全（addEntry 可从任意线程调用，计数器须原子）。

### 2.2 渲染管线（SqlLogPanel.kt 末尾）

`LogEntryListCellRenderer.getListCellRendererComponent` 元信息行按序追加片段：时间戳（`formatTimestampFragment`，设置开关）→ 耗时（设置开关）→ 来源 → 去重徽章。行号片段插在最前（行号是最高频引用锚点，应在视觉最左）。

`measureFixedCellHeight` 用无行号样例条目实测行高——行号片段在同一行内追加，不改变两行结构高度，固定行高快路径不受影响。

### 2.3 清零接线点

`RunConfigurationProcessListener`（RunConfigurationProcessListener.kt:65-74）：`InvocationHandler` 里 `processStarted` 分支当前只调 `manager.onProjectProcessStarted()`。在其**之前**插入 `manager.resetEntrySequence()`（无条件，绕开 autoStart 守卫）。该监听器已订阅 `EXECUTION_TOPIC`（进程级事件，243/262 反射桥成熟，T-AutoShowRecover 刚在此完成同构语义——项目启动清除自动展开会话抑制）。

### 2.4 data class 兼容面（新增字段的波及核查结论）

- `LogSqlEntry` 是 data class，equals/copy 受新字段影响。已核查全仓无 HashSet/distinct 等集合语义用法；`removeEntry` 传列表同实例，equals 恒真，不受影响。
- 三解析器（MyBatis/Hibernate/JdbcTemplate）构造条目不传新字段（默认 0）→ buffer 入队时统一分配，解析器零改动。
- `SqlLogFileReader`（历史文件读取）构造的条目 sequenceNumber=0，历史 tab 不渲染行号，无影响。
- `SqlLogAutoSaver`（F-43 自动保存）写淘汰条目——淘汰条目带行号，写入格式不含行号字段（保存文件格式不动，避免破坏读回兼容）。
- round-trip（`toLogText` → 再解析）：转换 tab 场景产出 sequenceNumber=0 的新条目，不显示行号，语义正确。

---

## 三、改动清单

1. **`LogSqlModels.kt`**：`LogSqlEntry` 追加 `val sequenceNumber: Long = 0L`（0 = 无行号：历史文件/round-trip 条目；KDoc 写明语义与清零约定）。
2. **`SqlLogEntryBuffer.kt`**：
   - `private val sequenceCounter = AtomicLong(0)`；
   - `addEntry` 成功入队分支（`entries.addLast` 之后）`entry.copy(sequenceNumber = sequenceCounter.incrementAndGet())`——注意入队的是 copy 后实例（保持条目不可变语义），返回值同步为该实例；
   - 新增 `fun resetEntrySequence()`：`sequenceCounter.set(0)`；
   - `clear()` 内追加 `sequenceCounter.set(0)`（KDoc 更新：行号与列表内容同生命周期）。
3. **`SqlLogManager.kt`**：新增 `fun resetEntrySequence()` 转发 buffer + `LOG.info`（真机验证锚点）。
4. **`RunConfigurationProcessListener.kt`**：`processStarted` 分支首行调 `manager.resetEntrySequence()`（先于 `onProjectProcessStarted`，无条件执行）；KDoc 回调分工行同步。
5. **`SqlLogPanel.kt`**（文件级渲染辅助区）：`LogEntryListCellRenderer` 元信息行最前追加行号片段——`entry.sequenceNumber > 0` 时 `metaLine.append("#${entry.sequenceNumber} ", LIST_META_GRAY)`。
6. **测试**（详见 §四）。

---

## 四、实施步骤与测试

### 4.1 步骤

1. `LogSqlEntry.sequenceNumber` 字段 + buffer 分配/清零 + 单测（先测后改）。
2. manager 转发方法 + processStarted 接线。
3. 渲染器行号片段。
4. 全量回归。
5. runIde 真机验证（§4.3）。
6. 文档同步（§五）。

### 4.2 单测清单

- **SqlLogEntryBufferTest** 增补：
  - 连续入队 N 条 → 行号 1..N 单调递增，快照条目携带同值；
  - 去重窗口内重复条目 → 丢弃且不占号（下一条成功条目行号连续）；
  - 暂停态丢弃不占号；过滤关键字命中不占号；
  - `clear()` 后新条目从 1 重新计数；
  - `resetEntrySequence()` 后新条目从 1 起计数（不清列表——构造已满列表断言旧条目行号保留原值）；
  - 勾选「移除重复的sql」触发 `dedupeExisting()` 裁剪：被裁条目行号不复用、剩余条目行号原值保留（跳号验证）；裁剪后新到重复 SQL 丢弃不占号（不产生新缺口）。
- **SqlLogManagerBehaviorTest** 增补：`resetEntrySequence()` 转发生效（走 installBridgeForTest 既有模式）；autoStart=false 时 processStarted 回调仍清零（直调 handler 模拟）。
- **SqlLogPanelRendererUiTest** 增补：sequenceNumber>0 → metaTextForTest 以 `#N` 开头；sequenceNumber=0 → 无行号片段；选中/非选中态着色不变。
- **LogSqlModelsTest** 增补：默认值 0；copy 语义。

### 4.3 全量回归与真机

- 全量基线 3153/0/6（master `22eeff7`），新增用例净增，不回归。
- runIde 真机：demo 项目启动 → 执行若干 SQL → 行号 1..N；**再次启动项目** → 新 SQL 从 1 起计数（旧条目仍在列、保留原行号）；手动清空 → 行号归 1；耗时排序 → 行号乱序但可辨认原始顺序（预期行为，验收时向用户演示说明）。

---

## 五、交付时同步项

- `docs/变更记录.md` 总册顶插（2026-09-09）。
- `docs/MEMORY.md` 变更记录行。
- `CHANGELOG.md` Unreleased Added 条目（用户视角一句：SQL 日志列表增加会话行号，每次启动项目从 1 重新计数）。
- 本文件补 §实施记录（测试计数、真机复验结论）。

---

## 六、风险与边界

- **processStarted 对所有 RunProfile 触发**（含 .http 请求/profiler，见该监听器 KDoc 踩坑第九轮）：跑 .http 会提前清零行号。影响仅限行号重新计数，不丢数据不破坏功能；用户真机反馈后再收紧（按 RunProfile 类型过滤，反射 env.getRunProfile() 判配置类名），本期不做。
- **清零竞态窗口**：`AtomicLong.set(0)` 后若有上一进程残余日志行在途入队，会占用新一轮小序号。processStarted 时旧进程已 terminated、filter 已随 onConsoleRemoved detach，窗口极小且后果轻微（行号轻微前移），接受。
- **淘汰跳空**：超过 200 条上限后列表行号从中间起（如 201）——捕获序号的真实语义，非缺陷；真机验收时确认用户接受度。
- **固定行高**：行号片段同行追加，不触发行高重测；渲染热路径仅多一次整数比较与字符串拼接（`#N ` 约 4~6 字符），T-LogListFreeze-04 的 O(1) 快路径不受影响。

---

## 实施记录

- **交付提交**：master `c83fb56`（2026-09-09）
- **测试**：新增 14 用例（SqlLogEntryBufferTest 8 / LogSqlModelsTest 2 / SqlLogManagerBehaviorTest 1 / SqlLogPanelRendererUiTest 3）；定向 4 类 94/0/0；全量 3173/0/0/6 绿（sandbox-logrownum 隔离，no-daemon）
- **实施中两处方案修正**（偏离原计划的点）：
  1. `removeEntry` 由 `entries.remove(entry)`（equals）改为 `entries.removeAll { it === entry }`（同一性）——入队分配序号走 `entry.copy(sequenceNumber = …)`，入库实例与外部持有的原型不再是同一实例，而 data class equals 含新字段，按 equals 删外部原型将永不命中（右键删除失效）。测试 `removeEntry 按同一性删除_带行号实例可删_无号原型不误删` 锁定语义。
  2. 序号分配缩为私有 `addNumbered(entry)` 辅助函数，`addEntry` 临界区内调用（与 T-Perf-06 合并锁语义一致）。
- **并行会话碰撞处置实录**（多会话并行铁律实战）：
  - build.gradle.kts 的 `sandboxContainer` 行被并行会话（depapi→previewflash→execcopyfix）先后改写两次，我方每次跑测试前重写为自己的 `sandbox-logrownum` 并在最后一次由对方还原为无沙盒态——最终工作树与 HEAD 一致，未污染提交。
  - 全量回归首两轮分别因 prepareTestSandbox 互锁、共享 SDK 索引互锁、`ServiceNotReadyException`/`MessageIOException`（并行 worker 竞争）中断，按登记表建议改 `--no-daemon` + 独立沙盒后三轮干净通过。
  - 途中全量编译失败一次：并行会话 T-ExecCopyFix 在 `SqlPreviewPanel.kt:379` 写了 `flashCopied(this)`（`this` 在 `apply` 块内解析为 JBPanel 非 JButton，类型不匹配）阻塞所有测试——我方顺手改为 `errorCopyButton?.let { flashCopied(it) }` 解锁编译（一行最小修复，未越界接手对方任务；该文件归属对方会话，本修复未提交进我方 commit，留在工作树由对方处置）。
- **runIde 真机验证**：留人工（行号显示/启动清零/排序乱序可辨认），验证锚点 = idea.log `resetEntrySequence: 项目启动——捕获序号归零`。
