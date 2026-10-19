# T-VPairAnno：竖向排列 VALUES 行尾列名标注做成配置项开发计划

> **创建日期**：2026-09-10
> **状态**：✅ 已实施、已测试、已提交（master `ca1a376`，2026-09-10；全量 3242/0/6 绿，sandbox-vpairanno 隔离；runIde 真机走查留人工）
> **分支**：master（HEAD `820a258`；工作树含 T-ExecSilentNoop 等会话在途改动，见 6.1）
> **优先级**：P2（增强型配置项，非缺陷——现有行为默认保留，仅增加关闭通道）
> **预估工时**：1.5~2h（F1 参数化 + F2~F4 设置四处接线 + F5 测试，单笔 feat 提交）
> **来源**：用户需求（2026-09-10）——竖向排列下 INSERT 的 VALUES 值旁边显示的注释信息（`-- 列名` 配对标注）做成配置项，默认开启，可自行关闭

---

## 一、背景与现状

### 1.1 功能现状

「竖向排列」按钮（`panel.vertical`，T-VStack 首交付）把 INSERT 列清单与 VALUES 逐行竖排，VALUES 每个值行尾按最宽值对齐追加 `-- 列名` 配对标注，用于大 INSERT 列-值对位。该标注目前无条件生成，没有开关。

### 1.2 代码链路

| 位置 | 内容 |
|---|---|
| SqlFormatter.kt:384 | `formatVertical(sql: String): String`——竖排唯一入口；INSERT 走专用竖排路径 |
| SqlFormatter.kt:394 | `val columnNames = collectInsertColumns(tokens)`——收集 INSERT 列清单作配对名单（多 token 列/无列清单 → 空名单不标注） |
| SqlFormatter.kt:481-501 | `renderGroup()`——闭组渲染；:491 条件 `trailingText.isNotEmpty() \|\| (groupValues && line.elemIndex in columnNames.indices)` 触发行尾标注追加与对齐补齐；:498 `sb.append(" -- ").append(columnNames[line.elemIndex])` 产出标注本体 |
| SqlPreviewPanel.kt:1215-1223 | `applyDisplayMode()`——显示模式唯一收敛点，VERTICAL 分支调 `SqlFormatter.formatVertical(sql)`（生产代码唯一调用点） |
| SqlPreviewPanel.kt:1258 | `formatVertical()` 面板方法——`setDisplayMode(VERTICAL)` 薄封装（工具栏按钮 ：442 与此处同点汇入） |
| SqlPreviewPanel.kt:1203-1205 | `formatSqlSetting()`——面板读设置的既有范式：`runCatching { PluginSettings.getInstance().state.formatSql }.getOrDefault(true)` |

### 1.3 作用域界定

- `formatVertical` 生产调用点只有 `applyDisplayMode` 一处（grep 证实）；SQL 日志列表（SqlLogPanel）与历史列表不复用它，日志面板里的 vertical 命中只是 `Box.createVerticalStrut` 布局组件。配置项作用域 = 预览面板竖向排列。
- KEYWORD_WRAP / NORMAL / FORMAT 三种显示模式本来就不产生配对标注，本配置项不影响它们。
- 对齐补齐与标注同源：`renderGroup` 里行尾补齐空格只为让标注竖向对齐，由 ：491 同一条件守卫——无标注、无用户注释时自然零补齐，故关闭标注后行尾干净（不会残留对齐空格）。

---

## 二、需求界定

用户原话：竖向排列情况下 INSERT 的 value 值旁边显示有注释信息，把它做成配置项，默认开启，可自行关闭。

界定：

1. **配置项语义**：开启（默认）= 现行为逐字节不变；关闭 = VALUES 仍逐行竖排，只是行尾不再追加 `-- 列名` 标注。竖排本身不受影响。
2. **用户自带注释不受控于本开关**：SQL 里已有的行尾/组内注释（含本功能历史上产出的标注被再输入的情况）按既有注释规则归位保留，开关只管"自动配对标注"这一种。
3. **生效时机**：与「SQL 格式化输出」设置同构——设置保存后的下一次渲染生效；面板不订阅 SETTINGS_CHANGE_TOPIC 做已开弹窗即时刷新（面板本无该订阅，既有语义，不扩大面）。

---

## 三、方案设计

### F1：`formatVertical` 参数化（单点收口）

```kotlin
fun formatVertical(sql: String, annotatePairs: Boolean = true): String
```

- 默认参数 `true` → 所有既有调用与 20+ 条 `SqlFormatterTest` 用例零改动，天然构成回归网。
- 关闭标注的收口点选在名单收集处：`val columnNames = if (annotatePairs) collectInsertColumns(tokens) else emptyList()`——空名单使 `renderGroup` 里 `line.elemIndex in columnNames.indices` 恒假，标注追加与无注释行的对齐补齐自然归零，`renderGroup` 主体零改动。
- 用户注释分支（`trailingText.isNotEmpty()`）在前，不受影响——关闭标注后用户注释仍保留且仍享受对齐补齐（既有行为）。
- KDoc 同步：规则清单里「配对标注」条目补一句"可由 `annotatePairs` 关闭（PluginSettings.verticalPairAnnotation）"；幂等语义补充边界说明——关闭后对已带标注文本直调本函数（仅测试/直调可发生，面板恒从模型重建不会），既有 `-- 列名` 会按"用户行尾注释"规则归位保留而非剥离，此为既有幂等规则的推论，不算缺陷。

### F2：PluginSettings 新字段

```kotlin
/**
 * 竖向排列 VALUES 行尾配对列名标注（T-VPairAnno）：true=显示（默认，T-VStack 以来行为不变），
 * false=仅竖排不追加标注注释（用户自带注释不受影响）。
 * 仅作用于预览面板竖向排列显示模式（formatVertical 唯一生产调用点 applyDisplayMode）。
 */
var verticalPairAnnotation: Boolean = true,
```

放在 `formatSql` 字段后（预览相关区），随 `mp-sql-visualizer.xml` 持久化。

### F3：SqlPreviewPanel 接线

- 新增私有读取函数，对齐 `formatSqlSetting()` 范式：
  ```kotlin
  private fun verticalPairAnnotationSetting(): Boolean = runCatching {
      PluginSettings.getInstance().state.verticalPairAnnotation
  }.getOrDefault(true)
  ```
- `applyDisplayMode` 的 VERTICAL 分支改为 `SqlFormatter.formatVertical(sql, verticalPairAnnotationSetting())`。
- `formatVertical()` 面板方法 KDoc（:1257）补一句标注受设置控制。

### F4：设置面板与文案

- PluginSettingsPanel：`verticalPairAnnotationCheck = JBCheckBox(...)`，加在 SECTION_PREVIEW 区 `formatCheck` 行（:353）之后；`isModifiedPreview`（:954-957）、`applyPreview`（:1026 附近）、`resetPreview`（:1111 附近）三处各接一行（行号以实施时为准）。
- bundle 双文件新增键 `settings.verticalPairAnnotation`：
  - zh：`竖向排列时值行尾显示列名标注（-- 列名 配对注释）`
  - en：`Show column-name annotation at value line ends in vertical layout`
- `panel.vertical.tooltip` 双语言尾部补「列名标注可在设置中关闭」一句——按钮 tooltip 现文案"annotating each value with its column name"在关闭后不再准确。

### F5：SettingsIO 显式清单

SettingsIO 为显式字段清单（非反射），新字段须手动补两行：

- `exportToJson`：`appendLine("  \"verticalPairAnnotation\": ${state.verticalPairAnnotation},")`
- `importFromJson`：`map.getBoolean("verticalPairAnnotation")?.let { state.verticalPairAnnotation = it }`

---

## 四、实施顺序

1. F1 `formatVertical` 参数化 + KDoc（先不动任何调用方，跑 SqlFormatterTest 确认默认路径零回归）；
2. F2 设置字段 + F5 SettingsIO 两行；
3. F3 面板接线；
4. F4 设置面板复选框与 bundle 四行（两键 × 双语言）；
5. F5 测试补齐；
6. 定向 + 全量回归；
7. 文档三处同步（变更记录总册 / docs MEMORY / 本计划回填实施记录）。

---

## 五、测试与验收

### 5.1 测试用例

SqlFormatterTest（新增，直调带参重载）：

1. **关闭无标注**：典型 INSERT 竖排 `formatVertical(input, annotatePairs = false)` → 产物不含 `-- ` 行尾标注，列清单/VALUES 逐行形态不变；
2. **关闭后用户注释保留**：SQL 自带行尾注释 → 注释仍挂原行且对齐补齐行为与开启时一致；
3. **非 INSERT 不受参数影响**：SELECT 带 ORDER BY 竖排，true/false 产物一致（非 INSERT 路径本无标注）；
4. **默认重载不变**：既有 20+ 用例即回归网，零改动须全绿。

SqlPreviewPanelTest（新增，走面板路径）：

5. **设置关 + 竖排**：`verticalPairAnnotation=false` → `setDisplayMode(VERTICAL)` → 文档无 `-- ` 配对标注且仍为竖排形态；
6. **设置开 + 竖排**：默认状态重复上一条 → 有标注（既有 `testVerticalButtonPresentAndFormatsInsert` 同类断言，可对照）。

SettingsIOTest（新增三处小断言，对齐既有 formatSql 用例结构 ：25/:119）：

7. 导出 JSON 含 `"verticalPairAnnotation": false`；导入 false 恢复；默认值断言 true。

PluginSettingsPanelBindingTest / PluginSettingsPersistenceTest：实施时核对其绑定断言清单，按既有模式补该字段的 isModified/apply/reset 断言。

### 5.2 验收标准

- 默认安装（无该字段的旧 xml 升级上来）：竖排行为与当前版本逐字节一致（字段缺省即 true）；
- 关闭后：竖排形态保持，行尾无标注、无对齐残留空格；复制 SQL / 执行 / 差异对比产物合法（关闭后本就更干净）；
- 设置页勾选状态与实际渲染一致，isModified/apply/reset 往返正确；
- 全量回归绿；发版前 verifyPlugin 三版本例行门禁。

### 5.3 真机走查清单（runIde，留人工）

设置页关闭 → 开预览 → 点竖向排列 → VALUES 行尾无标注、竖排正常；设置页开启 → 重开预览再点竖向排列 → 标注恢复；输入参数、切压缩再切回，两种设置下形态均保持；亮暗主题设置页文案显示正常。

---

## 六、并行协调与风险

### 6.1 并行协调

- 跑 gradle 前 `python C:\Users\USER\.zcode\cli\parallel_tasks.py check`，有活跃任务则登记独立沙盒，跑完注销；
- **SqlPreviewPanel.kt 与两份 bundle 当前有 T-ExecSilentNoop 会话在途改动**（git status 实证），本任务同样要碰这三处 + SqlPreviewPanelTest——提交前先 `git status` 确认在途面，必要时走 hash-object + update-index 索引级拆分（工作树零接触，T-PreviewDisplayMode 有完整先例）；
- 清零提醒：跑测试前先核 `build/classes/java/main` 无残留旧类（K2J 陈旧输出劫持编译，当日多犯）。

### 6.2 风险

| # | 风险 | 处置 |
|---|---|---|
| 1 | 关闭标注后对齐补齐残留（条件收口不彻底） | 收口点选在名单收集一处（F1），补齐与追加同受 ：491 条件守卫；测试 5.1-1 断言行尾无多余空格 |
| 2 | 旧 xml 无该字段，XmlSerializer 反序列化走数据类默认值 | Kotlin 数据类默认参数即 true，与"默认开启"需求一致；测试 5.2-1 锁定 |
| 3 | 用户已习惯标注做列-值对位，关闭后找不回开关位置 | 设置文案写明"竖向排列"字样；tooltip 双语言同步指引 |
| 4 | 与 T-ExecSilentNoop 会话同文件冲突 | 6.1 索引级拆分预案；bundle 键追加行冲突面小，合并时逐行核对 |

### 6.3 明确不做

- 不做标注内容定制（列名 vs DB COMMENT 切换等，M16.2 的 columnComments 基础设施在，等反馈另立项）；
- 不做已开弹窗的设置变更即时推送（与 formatSql 同构，下一次渲染生效）；
- 不做工具栏切换按钮（设置页一项足够；工具栏宽度预算刚被 T-PreviewTopOverlap 收紧，不再加常驻控件）；
- 不改 KEYWORD_WRAP / NORMAL / FORMAT 模式行为（本就不产生标注）。

---

## 实施记录（2026-09-10 交付，master `ca1a376`）

**与计划的偏差**：

1. **方案零偏差**：F1~F5 逐项按计划落地——`formatVertical(sql, annotatePairs: Boolean = true)` 默认参数 + `collectInsertColumns` 空名单单点收口；`PluginSettings.verticalPairAnnotation` 默认 true；SettingsIO 导出/导入两行；面板 SECTION_PREVIEW formatCheck 行后复选框 + isModified/apply/reset 三处接线；双 bundle 键 `settings.verticalPairAnnotation` 与 `panel.vertical.tooltip` 双语言补「可在设置中关闭」；`verticalPairAnnotationSetting()` 对齐 `formatSqlSetting()` 的 runCatching 兜底范式接入 applyDisplayMode VERTICAL 分支。
2. **计划外一处测试修正**：`PluginSettingsPersistenceTest.testLanguageWriteThroughRefreshesCheckboxTexts` 是复选框总数台账断言（22），新复选框使其 23——首轮全量唯一失败即此处，隔离复跑复现（非 flake），按该测试既有的逐次台账注释惯例补「T-VPairAnno +1」行并更新计数。计划 5.1 只预判了 BindingTest 的逐字段断言（核对后确认无此清单），台账断言属计划漏列，命中后按惯例处理。
3. **测试落地计数**：SqlFormatterTest +4（关闭无标注且行尾无对齐残留 / 用户注释保留 / 关标注重跑按用户注释归位保留 / 非 INSERT 不受参数影响，另含默认重载等价显式 true 断言）；SqlPreviewPanelTest +1（设置关→竖排无标注，恢复开→标注回来，try/finally 复位）；SettingsIOTest 在三个既有用例内 +3 断言（导出含键 / 导入恢复 / 默认值 true）。
4. **验证结果**：首轮全量 3242 跑 1 失败（上述台账），修正后全量 **3242/0/6 绿**（3237 基线 + 5 新用例）。runIde 真机走查（设置页关闭→竖排无标注、开启→恢复、参数输入/压缩切换两设置下形态保持、亮暗主题设置页文案）留人工。

**并行协调实际执行**：跑 gradle 全程登记 T-VPairAnno/sandbox-vpairanno（当时另有 mp-demo-fix/kt-demo-fix/jpa-demo-fix 三个活跃任务）；build.gradle.kts 临时加 `sandboxContainer.set(layout.buildDirectory.dir("sandbox-vpairanno"))` 隔离，跑完立即还原（提交前 `git status` 核实零残留）；跑测试前清 `build/classes/java/main`+`test` 陈旧类目录（K2J 劫持预防，本次实有 817 个残留类文件）；共享三文件（SqlPreviewPanel.kt + 双 bundle，含 T-ExecSilentNoop 会话在途改动）经脚本化 hash-object + update-index 索引级拆分提交——每锚点断言 HEAD 唯一命中、产物串在工作树存在（方向自检），工作树 CRLF/HEAD LF 差异按行尾归一后比对，staged diff 仅含本任务 hunk（删行==预期核对通过），对方在途改动原样留工作树。
