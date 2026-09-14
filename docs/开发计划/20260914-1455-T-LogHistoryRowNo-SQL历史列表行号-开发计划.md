# T-LogHistoryRowNo：SQL 历史列表行号 开发计划

> **创建日期**：2026-09-14
> **分支**：master（当前 HEAD `651cbf2`）
> **优先级**：P2（用户明确要求的新功能；改动面小，纯增量渲染，无存量行为变更）
> **预估工时**：2h（读取层序号 + 渲染片段 1h + 测试 1h）
> **来源**：用户需求原话——「sql历史中的sql列表 也需要显示行号」（延续 T-LogRowNumber 主列表行号后的历史面板补齐）

---

## 一、需求分析

### 1.1 目标

「SQL 历史」tab（`HistorySqlLogPanel`）二级 SQL 列表（单个历史文件内的 SQL 条目列表）也显示行号，与主「SQL 日志」tab 列表的行号视觉一致（元信息行最前 `#N` 灰色片段）。

### 1.2 行号语义：文件内条目顺序号

历史面板与主面板有一个本质差异——**历史条目无会话内捕获序号**：

| 维度 | 主「SQL 日志」列表 | 「SQL 历史」二级列表 |
|---|---|---|
| 数据源 | 内存缓冲 `SqlLogEntryBuffer`，入队时分配 `sequenceNumber` | 磁盘文件 `SqlLogFileReader.readFromFile` 反解析 |
| sequenceNumber | 入队时 `copy(sequenceNumber = …)` 注入 | **始终 0**（读回未设置） |
| 清零语义 | 项目启动/手动清空归零 | 无此概念（静态历史） |
| 排序 | 有 DURATION_DESC 排序 | **无排序**，仅搜索过滤 |

因此历史列表的行号**不能复用「会话内捕获序号」语义**（历史文件跨项目多次运行累积，无「本次启动第几条」概念）。正确的语义是**文件内条目顺序号**：按条目在文件中的先后顺序 1..N 编号，随搜索过滤保持稳定（过滤后仍显示原始文件内序号，与主列表「过滤后行号跟随条目」的既有语义一致）。

### 1.3 已定决策

1. **行号 = 文件内条目顺序号（1 起）**：在读取层分配——`SqlLogFileReader.readFromFile`/`parseContent` 返回条目时按解析顺序注入 `sequenceNumber = 索引+1`。条目自带序号，渲染器直读，搜索过滤天然稳定（过滤不改变条目实例，序号跟随）。
2. **渲染复用主列表模式**：`HistoryCellRenderer` 元信息行最前追加 `#N ` 灰色片段（`HISTORY_META_GRAY`，与主列表 `LIST_META_GRAY` 同色值 #9E9E9E），仅 `sequenceNumber > 0` 时显示——与主列表 `LogEntryListCellRenderer` 的守卫语义一致。
3. **不持久化序号**：历史文件格式（`SqlLogAutoSaver.writeEntry` 的 `-- source | time | timestamp --` 头部）不动，序号是读取层派生的展示属性，旧文件同样获得行号（零迁移）。
4. **只覆盖二级 SQL 列表**：一级文件列表（文件名|大小|条数）不加行号——那是文件摘要不是 SQL 条目；用户诉求明确是「sql历史中的sql列表」。
5. **不加设置开关**：与主列表行号决策一致（低噪音元信息，默认显示）。
6. **顺序号基于文件解析顺序**（`parseContent` 产出顺序），不按时间戳重排——文件内条目本就按保存时间先后写入（`SqlLogAutoSaver` 追加写），解析顺序即时间顺序。

---

## 二、现状与接线点

### 2.1 数据链路

```
磁盘 .sql 文件
  → SqlLogFileReader.readFromFile / parseContent（HistorySqlLogPanel.kt:294/323/336 调用）
  → List<LogSqlEntry>（sequenceNumber = 0）
  → HistorySqlLogPanel.currentEntries → applySqlSearch 过滤 → sqlListModel
  → HistoryCellRenderer.getListCellRendererComponent（HistorySqlLogPanel.kt:453）
      元信息行：[$duration] [$timestamp] [$source]（均 HISTORY_META_GRAY）
```

### 2.2 关键点

- **读取层唯一入口**：`SqlLogFileReader.parseContent`（readFromFile/readAll 都经它）——在此处按解析顺序注入 `sequenceNumber = index + 1`，一处改动覆盖历史面板所有读取路径（含后台扫描 keepReload）。
- **渲染器独立**：`HistoryCellRenderer` 与主列表 `LogEntryListCellRenderer` 是两个独立类，需各自加片段（主列表已加，历史面板未加）。
- **搜索过滤**：`applySqlSearch` 对 `currentEntries` 做 `filter` 后 addElement——过滤只删不改条目实例，序号跟随条目稳定；无需在渲染器里做「按 index 重编号」的显示层派生（那会在过滤下漂移）。
- **固定行高**：`HistoryCellRenderer` 用固定行高（`HistorySqlLogPanel.kt:155-157`），行号片段同行追加不改变两行结构高度，O(1) 快路径不受影响（与主列表同结论）。

### 2.3 data class 兼容面

- `LogSqlEntry` 新增 `sequenceNumber: Long = 0L` 已存在（T-LogRowNumber），默认 0。
- 历史读取路径 `parseBlock` 构造 `LogSqlEntry` 时不传 sequenceNumber（默认 0）→ 读取层注入即可，构造点零改动。
- 历史面板 `fixedCellHeight` 样例条目（`HistorySqlLogPanel.kt:156`）无 sequenceNumber → 渲染时无行号片段 → 实测行高不含行号影响（同行追加，高度不变）。

---

## 三、改动清单

1. **`SqlLogFileReader.kt`**：`parseContent` 返回前对 entries 按索引注入序号——`entries.mapIndexed { i, e -> if (e.sequenceNumber == 0L) e.copy(sequenceNumber = (i + 1).toLong()) else e }`（防御：已有序号的条目不覆盖）。KDoc 注明「历史行号 = 文件内顺序号，派生展示属性不持久化」。
2. **`HistorySqlLogPanel.kt`**（`HistoryCellRenderer.getListCellRendererComponent`，约 :488-507）：元信息行最前追加行号片段——`entry.sequenceNumber > 0` 时 `metaLine.append("#${entry.sequenceNumber} ", HISTORY_META_GRAY)`；KDoc 补行号说明。
3. **测试**（详见 §四）。

---

## 四、实施步骤与测试

### 4.1 步骤

1. `SqlLogFileReader.parseContent` 注入序号 + 单测（先测后改）。
2. `HistoryCellRenderer` 渲染片段 + 渲染测试。
3. 定向 + 全量回归。
4. runIde 真机验证（§4.3）。
5. 文档同步（§五）。

### 4.2 单测清单

- **SqlLogFileReaderTest** 增补：
  - `readFromFile` 解析 N 条 → sequenceNumber = 1..N 连续；
  - 新旧格式混合文件 → 序号按文件解析顺序连续（不按格式分组）；
  - `readAll` 多文件 → 每文件内序号 1..N（各文件独立，不跨文件连续——历史面板按单文件展示）。
- **HistoryCellRendererTest** 增补：
  - sequenceNumber>0 → metaTextForTest 以 `#N` 开头、不混入 SQL；
  - sequenceNumber=0（旧文件/未注入）→ 不渲染行号片段（与主列表守卫一致）；
  - 现有断言（耗时/时间戳/来源/特殊字符/超长 SQL）不回归。
- **SqlLogHistoryRoundTripTest** 增补：写→读 round-trip 后条目带序号 1..N（round-trip 文件本身序号为读取层派生，不要求写端持久化）。

### 4.3 全量回归与真机

- 全量基线 3228/0/0/6（master `874f257`；当前 HEAD `651cbf2` 较其又有并行会话提交，实施时以实际基线为准），新增用例净增。
- runIde 真机：历史 tab 打开某历史文件 → 二级列表显示 `#1 #2 #3...`；搜索过滤 → 行号跟随条目不变；一级文件列表无行号；与主 SQL 日志列表行号视觉一致（灰色 `#N` 前缀）。

---

## 五、交付时同步项

- `docs/变更记录.md` 总册顶插（2026-09-14）。
- `docs/MEMORY.md` 变更记录行。
- `CHANGELOG.md` Unreleased Added 条目（用户视角一句：SQL 历史列表增加行号，按文件内条目顺序编号）。
- 本文件补 §实施记录（测试计数、真机复验结论）。

---

## 六、风险与边界

- **历史文件跨运行累积**：序号是文件内顺序号，不跨运行连续（无「第 N 条 SQL」全局引用语义）——这是历史面板的固有属性，非缺陷；真机验收时向用户说明「历史行号=文件内顺序」。
- **round-trip 断言**：写端不持久化序号，读端派生——round-trip 测试只断言读回序号连续，不断言写文件包含序号（文件格式零改动）。
- **固定行高**：行号片段同行追加，不触发行高重测；渲染热路径仅多一次整数比较与短字符串拼接。
- **历史面板后台刷新**（`refresh` 已后台化）：读取层注入序号在 `SqlLogFileReader` 内完成，后台线程执行无 EDT 问题；渲染仍走既有 `applySqlSearch`→addElement 链路。

---

## 实施记录

（实施后回填）
