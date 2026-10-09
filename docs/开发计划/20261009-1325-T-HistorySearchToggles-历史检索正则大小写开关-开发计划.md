# T-HistorySearchToggles：历史检索正则/大小写开关（与 IDEA 原生搜索对齐）

> **来源**：用户 2026-10-09 需求「历史正则检索 再新增一个正则和大小写开关是不是更合适，
> 和 idea 原生搜索对齐」。经分析合理（见 §二），立项。
> **生成日**：2026-10-09；写完停下等确认，未动手。
> **占号**：O-132/R78/D132~D134/V155~V157（引用前实测：O 最高 O-131 / R 最高 R77 / D 最高 D131 /
> V 最高 V154）。
> **分析来源**：子代理现状盘点报告（20261009），主控审定。

## 一、现状（子代理盘点，file:line 实证）

**检索链是三条独立实现：**

1. **历史面板**（本需求主对象）：`HistorySqlLogPanel.applySqlSearch`（:554-588）。`regex:` 前缀
   触发正则（:563-574），命中三维 `restoredSql`/`summary`/`sourceFile`；非法正则 fail-closed
   回退子串。正则编译 memo（F41，:540-552）**键只有源串不含大小写标志**（隐患，见 S3）。
   **大小写语义不统一**：正则 `Regex(source)` 默认敏感；子串路径恒 `ignoreCase = true`。
   搜索框 `SearchTextField`（:136）挂工具栏（:276）恒可见。
2. **日志面板**：`SqlLogPanel.filterBySearch`（:226-240 顶层纯函数）只有子串恒忽略大小写，
   无正则；搜索框默认隐藏 Ctrl+F 唤起（:826/:880-935）。
3. **MCP 落盘检索**：`HistorySearchService`（:153-157）regex 硬编码 IGNORE_CASE，独立链本需求不动。

无高亮/计数联动面（渲染器不感知检索词，已核实）——开关改动不牵连渲染层。
UI 先例：全仓无平台 ToggleAction；开关控件先例 `dedupeCheckBox`（SqlLogPanel.kt:392）。

## 二、需求评估：合理

`regex:` 前缀形态可用但不可发现（藏在 tip/文档里）、无大小写控制、与 IDEA 原生 Find 交互
不一致；用户点名对齐 IDEA（Regex/Aa 图标按压态）。实现面收敛（两面板搜索框组件改造 +
一条纯函数签名），且顺带修掉「正则/子串大小写语义分裂」和「memo 不含旗」两个存量隐患。

## 三、方案

### D132：历史面板双开关（Regex + MatchCase）

- 搜索框外包 BorderLayout 容器：CENTER `searchField` + EAST 两枚 `JToggleButton`
  （`AllIcons.Actions.Regex` / `AllIcons.Actions.MatchCase`，与 IDEA Find 同图标同语义），
  挂原 :276 位置；`regexEnabled`/`matchCase` 两字段，**默认均 OFF**（=现状子串+忽略大小写）。
- `applySqlSearch` 改造：正则生效条件 `regexEnabled || q.startsWith("regex:")`（**前缀保留，
  或语义**——前缀是已发行能力，验收 3.11 不回归，不做迁移）；前缀在场先剥；编译旗随 matchCase
  （OFF=IGNORE_CASE，ON=敏感）；子串路径 `ignoreCase = !matchCase`（SQL/摘要/时间/源文件
  目标统一）；非法正则回退子串保持 fail-closed；行号 `#N` 精确匹配不受开关影响。
- **memo 键扩为「源串+matchCase」二元组**（S3，最易埋的 bug 点：切开关复用错编译结果）。
- `applyFileSearch`：matchCase 生效，regex 不参与（文件名正则价值低，tooltip 写明两级语义差）。

### D133：日志面板单开关（仅 MatchCase）

同构 MatchCase 单开关随搜索条显隐（wrapper 挂 :826，showSearch/hideSearch :920-935 改作用于
容器）；`filterBySearch` 加 `matchCase: Boolean = false` 参数（默认值保既有 30 例测试零改动）。
Regex 不上（日志面板无正则基础，避免改动面翻倍；历史/日志图标差异困惑由 tooltip 消解）。

### D134（放弃）：MCP 链加 caseSensitive

`HistorySearchService` 加参数属 MCP 工具 schema 变更，牵 docs/mcp 指南与工具测试，超出本需求，
登记后续可选。

### 行为变更声明（有意，非回归）

正则路径从恒大小写敏感变为随开关（默认 OFF=忽略大小写）——与子串路径、MCP 链拉齐，
属放宽；验收 3.11 复测按新语义走查并在验收文档标注。

## 四、拍板点（20261009 用户已拍板：按推荐）

- **D 开关态持久化**：✅ 会话级（对齐 IDEA Find），不进 PluginSettings。
- **E matchCase 默认值**：✅ OFF=忽略大小写（三链语义统一）。
- **F 日志面板范围**：✅ 只加 MatchCase。

## 五、实施步骤

- **S1** 历史面板开关组件（容器+两枚 JToggleButton+双语 tooltip 键）+ 两字段（默认 false）。
- **S2** `applySqlSearch` 改造（生效条件/剥前缀/编译旗/子串旗/fail-closed 保持/`#N` 不受影响）。
- **S3** `resolveSearchPattern` memo 键扩 matchCase（非法串 memo 同步带旗）。
- **S4** `applyFileSearch` matchCase 生效。
- **S5** 日志面板 MatchCase 单开关 + `filterBySearch` 加参（默认值保既有测试）。
- **S6** 测试：`SqlLogPanelFilterTest` 加 matchCase 组例；历史检索抽纯函数测（正则/大小写/
  前缀兼容/非法回退/memo 换旗）——历史正则路径**现状零单测**，照 filterBySearch 先例建缝。
- **S7** 反向验证两刀：①删「开关或前缀」条件的前缀分支 → `regex:` 前缀场景必红；②memo 键退回
  单字段 → 换旗重编例必红。贴读数后还原。
- **S8** 计划 §八实施记录回填 + CHANGELOG 一行 + 验收文档 3.11 行为变更标注。

## 六、验证方案

定向：`--tests "cn.song.mybatisplus.visualizer.log.SqlLogPanelFilterTest"` + 新增历史检索
纯逻辑测试类；回归两类全绿（filterBySearch 既有 30 例零改动通过为 P4 验证项）。
真机走查（留验收）：默认态与现状一致（子串忽略大小写；正则旧行为已按声明变更）、开关 ON
无前缀整串按正则且敏感、非法正则回退不报错、Esc 收起不动开关态、460px 窄宽不产生不可用折行。

## 七、待验证前提（实施者先证伪再动手）

- **P1** `AllIcons.Actions.Regex` / `AllIcons.Actions.MatchCase` 在 243/262 目标平台存在且渲染
  正常（平台图标常裁剪；不可用则按仓内「IPGP 裁剪自建替代」惯例处理）。
- **P2** `RegexOption.IGNORE_CASE` 的 containsMatchIn 语义与子串 `ignoreCase=true` 用户感知一致
  （unicode 折叠边界不造例）。
- **P3** 历史面板工具栏行加两枚按钮后 460px 窄宽不产生不可用折行（现状搜索框 min 200 已有同
  约束，推断安全，真机确认）。
- **P4** `filterBySearch` 加默认参数后既有 30 例测试零改动通过。
- **P5** 日志面板搜索条显隐改造只动 wrapper 引用，Esc 两段式收起契约（SqlLogPanel.kt:889-898）
  行为不变。

## 八、实施记录

（空——实施启动后回填）
