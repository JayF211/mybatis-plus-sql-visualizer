# T-VStack·InAll IN 列表全量竖向排列——开发计划

> 立项时间：2026-09-16（用户反馈）
> 分支：`master`；实施走独立 git 工作树分支 `t-vstackinall`
> 需求来源（用户原话）：「sql预览窗口有一个【竖向排列】功能，当时是为了防止in出现很多行，把多行时的in放在同一行了，现在需要改成 全部竖向排列」

## 一、需求分析与背景

预览窗口「竖向排列」按钮（`SqlFormatter.formatVertical`）对非 INSERT 语句走 `format` 全展开 + `expandHorizontalGroups` 后处理，其中 IN 列表的展开受 2026-09-10 引入的阈值约束（T-VStack·InThreshold，master `dc308fc`）：`IN_EXPAND_MIN_ELEMENTS = 5`——元素 < 5 保持 `IN (1, 2, 3)` 同行，≥ 5 才 `(` 自占一行逐元素展开。当时裁定依据：占位符 `IN (?, ?, ?)` 等短清单竖排零增量信息（参数对应关系靠左侧参数面板顺序对照），纯花行高把相邻 WHERE 条件推出视口。

用户现在复议该裁定：短清单同行反而要逐个数元素对位，要求**IN 列表不再分级，全部逐行竖排**。

**方案即回退阈值为扩面原始守卫**：阈值引入前（`dc308fc~1`）`expandInList` 的守卫本就是 `elements.size < 2`——单元素（`IN (1)` / `IN (?)`，无顶层逗号）不展开，**含逗号即 ≥ 2 元素一律逐行**。本任务恢复该守卫并删除 `IN_EXPAND_MIN_ELEMENTS` 常量。这不是新行为设计，是回到有先例的形态，风险面最小。

### 边界定案

| IN 列表形态 | 改前 | 改后 |
|---|---|---|
| `IN (1)` / `IN (?)` 单元素 | 同行 | 同行（无逗号无清单，竖排纯花行高——沿用扩面原始守卫） |
| `IN (1, 2)` ~ `IN (1, 2, 3, 4)` | 同行 | **逐行展开**（本次变更主体） |
| ≥ 5 元素 | 逐行展开 | 逐行展开（不变） |
| 子查询 `IN (SELECT …)` | 不展开 | 不展开（不变） |
| LIMIT 分页参数、ORDER BY / GROUP BY / FROM 展开 | 不变 | 不变 |

**范围限定**：只动 `expandInList` 的元素数守卫一处。INSERT 专用竖排路径（VALUES 本来就全竖排）、T-VPairAnno 标注开关、子查询/LIMIT 豁免一概不碰。不加设置项——原阈值也未配置化，用户要求的是行为直接反转。

### 影响面核实（已逐点确认）

- `formatVertical` 唯一 UI 调用点 `SqlPreviewPanel.kt:1413`，无其他消费方依赖阈值行为；
- 面板级测试 `testVerticalNonInsertFallsBackToFormat` 用 5 元素输入，断言 `contains("IN\n")` 改后仍成立，只需同步注释；
- token 保真不变式（剔注释重分词与原文一致）只动空白，阈值调整天然满足；
- 幂等性：展开产物再入时 `IN` 与 `(` 已分处两行，`expandInList` 在无 `(` 同行时返回 null，不会二次展开——需补短清单幂等用例固化。

## 二、任务分解

| ID | 任务 | 涉及 | 验收 |
|---|---|---|---|
| T-VIA-01 | 阈值回退：删除 `IN_EXPAND_MIN_ELEMENTS` 常量，`expandInList` 恢复 `elements.size < 2` 守卫，同步方法/常量处注释（`SqlFormatter.kt:31-32`、`693-724` 一带） | `SqlFormatter.kt` | 2~4 元素 IN 全部逐行；单元素/子查询/LIMIT 行为不变 |
| T-VIA-02 | 测试反转与新增：`SqlFormatterTest` 改 4 条 + 新增 2~3 条；`SqlPreviewPanelTest` 改注释 + 补 2 元素断言 | 两个测试文件 | 见下节清单；全量 test 绿 |
| T-VIA-03 | 文档三处同步：`CHANGELOG.md`、`docs/变更记录.md`、本计划 §实施记录 | 三处 | 条目落盘（注明对 `dc308fc` 裁定的反转与用户复议背景） |

## 三、测试改动清单（T-VIA-02 展开）

`SqlFormatterTest.kt`（现有阈值守卫用例全部反转）：

1. `testVerticalDeleteExpandsInList`（~610 行）：3 元素 IN 由「保持同行」断言改为逐行展开断言，注释同步；
2. `testVerticalInPlaceholderShortStaysInline`（~629 行）：`IN (?, ?, ?)` 由同行断言改为展开断言，改名去 StaysInline（如 `testVerticalInPlaceholderExpanded`）；
3. `testVerticalInThresholdBoundary`（~656 行）：边界从「4 同行 / 5 展开」改为「1 同行 / 2 展开」，改名去 Threshold（如 `testVerticalInMinBoundary`）；
4. `testVerticalTwoInListsBothExpanded`（~688 行）：注释「≥5 元素才展开」已失效，输入可改用 3 元素双清单直接实证新行为；
5. 新增：短清单（2 元素）幂等用例——展开产物再走一次 `formatVertical` 输出稳定；
6. 新增：单元素 `IN (1)` 与 `IN (?)` 保持同行的守卫用例（防未来误伤）；
7. `testVerticalExpandTokenFidelity`（~724 行）：输入矩阵补一条 2 元素 IN（token 流保真复核）。

`SqlPreviewPanelTest.kt`：

8. `testVerticalNonInsertFallsBackToFormat`（~957 行）：断言不变，注释去掉阈值描述，可补 2 元素输入的 `contains("IN\n")` 断言。

## 四、测试与门禁

- 定向：`./gradlew test --tests "*SqlFormatterTest*" --tests "*SqlPreviewPanelTest*"`；
- 全量 testShards 绿为交付标准（小修全量 test 绿即门禁口径，无需 buildPlugin）；
- 并行协调：跑 gradle 前 `python parallel_tasks.py check` → register → done；
- 已知假红预案：本仓 K2J 陈旧输出劫持再犯史（上轮 InThreshold 即中招，`build/classes/java/main` 残留旧类排前）——遇断言与实现不符先清 `build/classes` 复跑，勿先改实现。

## 五、实施记录

> 实施分支 `t-vstackinall`（独立工作树），完成后回填。
>
> **2026-09-16 已交付**：T-VIA-01 阈值回退（删 `IN_EXPAND_MIN_ELEMENTS` 常量，`expandInList` 恢复 `elements.size < 2` 守卫，注释换 InAll 语义）；T-VIA-02 `SqlFormatterTest` 反转 6 例（SELECT 组合 / UPDATE / DELETE 短清单、占位符列表、阈值边界、双 IN 清单）+ 新增 2 例（`testVerticalInSingleElementStaysInline`、短清单幂等并入 `testVerticalExpandIdempotent`）+ token 保真矩阵补 2 元素 IN，`SqlPreviewPanelTest` 注释同步并补 2 元素面板断言；T-VIA-03 三处同步落盘。**门禁**：定向 SqlFormatterTest 57 / SqlPreviewPanelTest 60 绿，全量 test **4279/0/0/6 绿**（4m11s）。插曲：本机代理 127.0.0.1:7890 进程在、上游握手断（curl 经代理 000、直连 400），工作树首次配置拉产品清单即 SSLHandshakeException——gradle 命令加 `-Dhttp.nonProxyHosts="*"` 直连绕过；build.gradle.kts 沙盒行临时指向 `test-sandbox-vstackinall`，提交前已还原。测试改动与计划 §三 的偏差：计划估「改 4 条」，实施时逐条盘出阈值守卫用例共 6 条（另有 `testVerticalSelectExpandsHorizontalGroups`、`testVerticalUpdateExpandsInList` 两条同族断言在 4 条之外），全部反转。
