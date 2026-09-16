# T-InParamRec IN 字面量 SQL 预览参数未识别——开发计划

> 立项时间：2026-09-16（用户反馈）
> 分支：`master`；实施走独立 git 工作树分支 `t-inparamrec`
> 需求来源（用户原话）：「in的参数在sql日志中 没有正常识别出参数」+ 实录日志与预览截图（左栏「无未解析参数」）

## 一、问题与根因

用户实录（Spring JdbcTemplate 链路，SkyWalking TID 前缀）：

```
DEBUG org.springframework.jdbc.core.JdbcTemplate - Executing prepared SQL statement [SELECT
            paramA
            FROM
            tableA
            WHERE deleted = 0
                            AND paramB in
                (''
                         ,'7a6bf5087f314683a49e363713b19f29'
                )
]
DEBUG org.springframework.jdbc.core.JdbcTemplate - Executing prepared SQL update
```

**关键事实：这条 SQL 里根本没有 `?` 占位符**——`deleted = 0`、IN 列表的值全部由上游代码内联进 SQL 文本（非 PreparedStatement 绑定参数），因此控制台不会有任何 `Setting SQL statement parameter value` 参数行，`JdbcTemplateLogParser` 产出 `params=[]`、`rawSql` 零 `?` 的条目。这不是解析器并发错挂（T-JdbcThreadBucket/T-LogParseConc 已修的形态），而是**「零占位符 + 零绑定行」的字面量 SQL**。

项目对这类 SQL 本有既定方案：`LiteralParamRecovery`（T-HistParam F4，`recoverLiteralParams`）把字面量原位改写为 `?` 复用参数管线，设置 `literalParamRecovery` 默认开启。但预览入口的接线不齐：

| 预览入口 | 是否接字面量恢复 | 现状 |
|---|---|---|
| 日志 tab 双击（`SqlLogPanel.openPreview`） | ✅ `toPreviewResult(literalRecovery=state)` | 正常 |
| 历史 tab（`HistorySqlLogPanel.buildPreviewResult`） | ✅ 同上 | 正常 |
| **源码跳转/角标反查（`LogEntryPreviewOpener`，EntrySourceMatcher.kt）** | ❌ **未接**——自带一套 `toRecognizedPreview` + 回退旧逻辑的独立实现 | **本 bug 命中路径**：识别失败（零 `?`）→ 回退分支 `unresolvedParams=[]`、无 `?` 无警告 → 预览左栏「无未解析参数」，SQL 原样展示，用户无法改值 |

`LogEntryPreviewOpener` 是 M37.1 时代与 `SqlLogPanel.openPreview` 并行发展的独立实现（当时注释「SqlLogPanel.openPreview 保持不动避免回归」），T-HistParam F3 统一识别通道时只收编了面板与历史两个入口，漏掉了它。截图弹窗形态（无警告 + 无参数行）与该路径回退分支行为完全吻合。

**次要观察**（同轮核实，不扩scope）：
- `recoverLiteralParams` 对空白字符串 `''` 刻意跳过（防空值输入渲染 `?` 无法往返）——本例 IN 列表首元素 `''` 保持字面量、其余元素成为参数行，属既有定案行为，不改。
- `toPreviewResult` 本身对该 SQL 的恢复逻辑经推演可正确工作（`0` 在比较语境、`'7a6b…'` 字符串、数量 < MAX_RECOVERED_PARAMS、往返校验通过），无需动 `LiteralParamRecovery` 核心。

## 二、方案

### S1 预览入口统一收编（根修）

`LogEntryPreviewOpener.open` 删除自带的识别/回退双分支，改为直接调用 `entry.toPreviewResult(formatSql = state.logFormatSql, literalRecovery = state.literalParamRecovery)`——与 `SqlLogPanel.openPreview` / `HistorySqlLogPanel` 三口归一。此后识别通道的任何演进（如本轮之后的改进）只改一处。

改动后该入口自然获得：字面量恢复（本 bug 修复）、零参数/数量不匹配的分流警告文案（含 JDBC/Hibernate 分源引导）、paramHints/initialParamValues 回填。

### S2 回归守卫用例

新增测试（纯逻辑，不依赖平台类）：
1. 用用户实录 SQL（多行、SkyWalking 前缀、内联 IN 值）走 `JdbcTemplateLogParser.feed` → `closePending` → `toPreviewResult(literalRecovery=true)`，断言：`unresolvedParams` 非空、`initialValues` 含 `'7a6bf508…'`、SQL 含 `?`、无警告；
2. `LogEntryPreviewOpener` 路径同口径断言（该类是 object，测 `toPreviewResult` 供其调用的同参行为即可——若直测需平台 Project 则以 S1 的「单一调用点」由代码审查保证 + 用例 1 覆盖语义）;
3. `literalRecovery=false` 时维持现状回退（零参数、无警告），保证开关语义不变。

## 三、任务分解

| ID | 任务 | 涉及 | 验收 |
|---|---|---|---|
| T-IPR-01 | `LogEntryPreviewOpener` 收编 `toPreviewResult` | `EntrySourceMatcher.kt` | 该文件不再有独立的识别/回退分支；三个预览入口调用同源 |
| T-IPR-02 | 回归守卫用例 | 新增 `src/test/kotlin/.../log/InLiteralRecoveryRegressionTest.kt` | 上节三条断言；既有 `ParamEdgeCaseRegressionTest` 等全绿 |
| T-IPR-03 | 文档三处同步 | `CHANGELOG.md`、`docs/变更记录.md`、本计划 §实施记录 | 条目落盘 |

## 四、测试与门禁

- 定向：`./gradlew test --tests "*InLiteralRecoveryRegressionTest*" --tests "*ParamEdgeCaseRegressionTest*" --tests "*JdbcTemplateLogParserTest*"`；
- 全量 testShards 绿为交付标准（小修全量 test 绿即门禁口径）；
- 并行协调：跑 gradle 前 `python parallel_tasks.py check` / register / done。

## 五、实施记录

> 实施分支 `t-inparamrec`（worktree `mpsv-inparam`），完成后回填。
