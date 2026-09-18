# T-HistoryFileRetention 历史文件保留上限（防磁盘爆炸）— 开发计划

- 日期：2026-09-17 22:07
- 任务编号：T-HistoryFileRetention
- 来源：用户需求——SQL 历史「全量化」后磁盘占用上台阶，需「超过 N 个文件自动删除最早文件」的可配置保留策略，可设默认值，也可全量保留，防用户磁盘空间爆炸
- 定性：**功能新增（配置 + 写入侧裁剪）**，与 T-SqlHistoryFull（全量化）同一演进方向；性能敏感点主要在删除触发的时机与量（不得在写入热路径同步删）

---

## 一、需求

在「SQL 历史全量记录」基础上，增加历史文件（`.idea/sql-visualizer/sql-history/` 下滚动文件，如 `sql-history-2026-09-17.sql`、`sql-history-2026-09-17.2.sql`）的保留条数上限配置：

- 超过上限的文件数时，自动删除**最旧**的历史文件（含滚动序号文件），防止磁盘空间持续膨胀；
- 配置可关闭（= 全量保留，`0` 或「不限制」），默认给一个合理值（如保留最近 `30` 个文件），保证开箱即有兜底又不误删；
- 删除发生在**写入侧/后台**，不得阻塞、不得在捕获热路径同步删。

现状边界（读码实测）：

1. 历史文件由 `SqlLogAutoSaver` 管理：单文件 ≥100MB 滚动（`SqlLogAutoSaver.kt:31,154`）→ `sql-history-*.2.sql/.3.sql`；按日期模板跨天新建。目前文件**只增不减**，全量化后长期开启必然磁盘持续增长。
2. 用户可见的是「多个文件」（历史面板一级列表按文件展示每条 `HistoryFileEntry`，`HistorySqlLogPanel.kt:66`、`SqlLogFileReader` 按 `.sql` 后缀扫描目录 `HistorySqlLogPanel.kt:383`）。文件上限的语义 = 「保留最近 N 个文件」，符合用户心智（一个个历史文件）。

## 二、方案设计

### 配置项（PluginSettings.State）

新增设置项（放在 F-43 自动保存区）：

```kotlin
/**
 * 历史 SQL 文件保留上限（T-HistoryFileRetention）：超过该数量的历史文件时，
 * 后台自动删除最旧文件。0 或负数 = 不限制（全量保留，默认关）。
 * 默认 HISTORY_FILE_RETAIN_LIMIT_DEFAULT = 30（合理兜底防磁盘膨胀）。
 */
var logAutoSaveMaxFiles: Int = DEFAULT_LOG_AUTO_SAVE_MAX_FILES,
```

常量 `DEFAULT_LOG_AUTO_SAVE_MAX_FILES = 30`（companion，与现有 `DEFAULT_LOG_AUTO_SAVE_FILE_NAME_PATTERN` 同区，`PluginSettings.kt:479` 附近）。

### 删除策略与触发点（读码定位）

删除逻辑落在 `SqlLogAutoSaver`（它持有保存目录 `saveDir` 与滚动文件状态，删除最自然）。

触发时机（性能关键）：**不能在逐条 / bulk 写入热路径同步删**。放在**写批请求（一次 `drainAndWrite`）之后**做一次「非滚动文件数超限 → 删最旧」检查即可——写批已是在专用后台线程（`drainAndWrite` 由 `executor` 执行），删文件也在该线程做，天然不上 EDT、不阻塞控制台输出热路径（控制台注入走 `submit` 无锁队列，`SqlLogAutoSaver.kt:58`）。

具体实现：

```kotlin
/**
 * 保留上限裁剪（T-HistoryFileRetention）：目录下 .sql 文件（非滚动 .2/.3 的同名块按 base 名归并？
 * ——见下「归并口径」）超过上限时删除最旧。仅限 autoSave 主导目录（默认目录或自定义目录）。
 * 在 [drainAndWrite] 批量写之后调用；删除与写共用同一写线程，串行无竞态、不阻塞捕获。
 */
@Synchronized
fun enrichPruneRetained() {
    if (maxFiles <= 0) return // 0/负 = 全量保留
    val dir = ... // saveDir（自定义路径）；默认目录解析同 resolveDir
    val files = dir.listFiles { f -> f.extension == "sql" } ?: return
    if (files.size <= maxFiles) return
    // 按「文件基名」（去滚动序号）归并统计「保留文件块数」，
    // 超限后按最旧的时间戳序删除多余文件
    ...
}
```

**归并口径**（关键定案）：`sql-history-2026-09-17.sql` 与 `sql-history-2026-09-17.2.sql` 属于同一个「逻辑历史文件」（同一天同模板的滚动块），上限应计为**1 个文件**而不是 2 个。否则一天滚动出多个块时会误删。归并方式：取文件名的滚动序号前段 `base`（`xxx.sql` → `xxx`，`xxx.N.sql` → `xxx`），按 `base` 分组；「文件数」= 组数；超出上限时按组内文件时间戳的**最旧组先后**整组删除（同组多块一并删，或保守先删组内最旧块——采用**整组删**，因为一个逻辑文件删一半无意义，且滚动块本身是连续分片）。

> 简化取舍：若觉得按 base 归并复杂，退化为「按文件修改/创建时间排序，保留最近 N 个**文件**（不归并滚动块）」亦可，但会因单文件自行滚动 N 个块而提前删掉跨天文件。**推荐归并口径**（更贴合「保留最近 N 天/组」的用户直觉），实现成本低（一项 `groupingBy`）。

### 设置面板（PluginSettingsPanel）

在 F-43 自动保存区（`PluginSettingsPanel.kt:645` 起 `sectionGroup("settings.log.tab.autoSave")`）加一行数字输入框：

- 字段 `logAutoSaveMaxFilesField`（`JBTextField`，数字），label「历史文件上限」；
- 复用勾选联动：开启自动保存才 `isEnabled`；
- 空值/非法 = 回退默认 30；输入 `0` = 不限制（全量保留）；
- tooltip 说明：超上限自动删除最旧文件；`0` 表示不限制全量保留。

### 迁移/兼容

- 原有无该配置的业务读取点：仅 `PluginSettings` State 序列化 + `SqlLogAutoSaver.configure(...)` 传入 `maxFiles`（`SqlLogManager.applySettings:445-450` 顺手传参）。
- `SqlLogManager` 把 `state.logAutoSaveMaxFiles` 透传给 `autoSaver.configure`，将新的裁剪检查挂到配置后即可（写批路径自动生效）。

## 三、改动落点

- `src/main/kotlin/cn/song/mybatisplus/visualizer/config/PluginSettings.kt`：新增 `logAutoSaveMaxFiles` 设置项 + 常量。
- `src/main/kotlin/cn/song/mybatisplus/visualizer/log/SqlLogAutoSaver.kt`：新增 `maxFiles` 字段、`configure` 传参、`drainAndWrite` 后调用裁剪检查；新增「按 base 归并 + 删最旧组」私有方法（可独立单测）。
- `src/main/kotlin/cn/song/mybatisplus/visualizer/log/SqlLogManager.kt`：`applySettings` 传 `logAutoSaveMaxFiles` 给 `configure`。
- `src/main/kotlin/cn/song/mybatisplus/visualizer/config/PluginSettingsPanel.kt`：自动保存区加数字输入行 + 勾选联动。
- `src/main/resources/messages/MpSqlVisualizerBundle_zh.properties` 及英文 bundle：新增 `settings.log.autoSave.maxFiles` / `.maxFiles.tip` 键（项目 i18n 双语规范）。

## 四、测试计划

- `SqlLogAutoSaverTest`：
  - `maxFiles>0` 时目录超过上限 → 删去最旧组，保留数量 == 上限；
  - `maxFiles<=0`（0/负）→ 不删（全量保留）；
  - 滚动块归并：同一天 `.2.sql` 与 `.sql` 同 base 计 1 组，超限按组删；
  - 自定义目录同样生效；
  - 删除在写批后台完成，不抛异常、不影响写入。
- 手动/集成：历史面板一级文件列表在裁剪后条目正确（`countInFile` 扫描的目录文件随之减少）。

## 五、已知边界 / 取舍

- 「文件数」与「磁盘占用」非严格等价（各文件大小不等），按文件数裁剪是**防空盘爆炸的粗粒度兜底**；若要精确按总字节上限，可后续在 `maxFiles` 之外再补 `maxTotalBytes` 配置（本任务不做，避免配置面膨胀）。
- 默认 30 个文件：按默认日模板≈保留近一个月（酌情，用户可调）；`0` 关闭即全量保留——两者皆满足用户「默认合理 + 可全量」的诉求。
- 删除时机在专用写线程的写批后，天然不阻塞捕获/EDT；无需额外 Timer/监听。
- 不引入目录内非 SQL 文件参与裁剪（只扫 `.sql` 后缀，与历史面板 `scanHistoryFiles` 口径一致）。