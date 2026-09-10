# T-SyncK2JMaster32 开发计划——master 299607e..2543203 三十二提交增量同步

> 立项日期：2026-09-11　分支：feature/kotlin-to-java（worktree `mybatis-plus-sql-visualizer-kotlin-to-java`）
> 同步源：**本地 master 2543203**（注意：origin/master 停在 c13b508，本地领先——master 仓库最近提交尚未 push，本轮以本地 master HEAD 为准，收官后锚点记 2543203）
> 增量范围：`git diff 299607e..2543203`，32 提交，55 文件 +4963/−187

## 1. 背景与目标

上一轮 T-SyncK2JMaster14 收官于 299607e（2026-09-10 全量 3195/0/0/6 绿 + verifyPlugin 三目标 Compatible）。此后 master 又落了三十二个提交（一整个 v2026.9.10 发行日的工作量）。此前远程已有一份 T-SyncK2JDev 计划（440467a/38f8a5c，拟以 dev aebb0ce 为基线一轮吃下 dev+master 增量）；本轮按用户明确指示**只同步主分支（master）**，dev 基线方案保留为后续轮次（届时其净增量清单需按本轮收官后的新锚点重算）。

惯例不变：手工移植、绝不 git merge（merge 会带回 .kt 污染与历史失真）；生产代码保持 100% Java；测试源集 Kotlin/Java 混合按既有形态增量翻译。

## 2. master 增量功能线（32 提交归组）

| 功能线 | 提交 | 内容概要 |
|---|---|---|
| T-VStack（三笔） | a86c615/b14173f/dc308fc | 预览面板「竖向排列」按钮：INSERT 列/值逐行竖排 + 行尾 `-- 列名` 配对标注；扩面 IN 列表/ORDER BY/GROUP BY/FROM 纵向化；IN 阈值 <5 同行 ≥5 逐行 |
| T-VPairAnno | 770313a | 竖排 VALUES 行尾列名标注配置项 `verticalPairAnnotation`（默认开），四处接线 |
| T-PreviewDisplayMode | 7bf110e | 预览显示模式状态化：编辑器=模型派生视图，按钮直改文档必被重建抹掉；applyDisplayMode 接入 showSql 唯一收敛点，压缩短路最前，刷新复位 |
| T-HibDualCapture | 90f082a/7f31fc0/3368f0f | Hibernate 双份 SQL 参数分裂根治（零参影子隔离槽 + string-aware 占位符口径）+ 参数框 16 列/光标钉首/tooltip 带值 |
| T-PreviewTopOverlap | 739588c | 预览顶部元信息压按钮行：WEST→CENTER + 低频按钮折叠「更多」JBPopup + 宽度预算截断 |
| T-ExecSilentNoop | 0254169 | 首次执行静默不执行：freshModel 回落 demote 强制走 executeQueries 兜底 + 提示三分流（notSubmitted/timeout/indexing）去重 |
| T-PreviewDocLock（1/2） | e8b9d1d/7bf5860/b934323 | EDT 读权限断言排雷：PsiDocumentManager.getDocument 包 ReadAction；ProjectTypeDetector 检测体包 ReadAction；预览临时文件失败降级纯内存编辑器 |
| T-PreviewSlow | dd938ce | 预热等待 2000→60ms + attachConsole 去 ReadAction（WIRA 锁嵌套根因） |
| T-VersionUtil | ea66ea7 | 版本比较委托 IDEA 内置 VersionComparatorUtil（识别 `-beta` 预发布段） |
| T-LogRowNumber 续修 | 4c1940f/874f257 | 行号清零仅主配置启动（ModuleBasedConfiguration 反射判定）；dedupeExisting 去时间窗口 |
| v2026.9.10 发行 | 0283717/c13b508/2543203 | 版本号 2026.9.10 + CHANGELOG + 变更记录 + 发行说明 + preview 清单 |
| 文档/分析 | 73303d6/211311b/其余 docs | IDEA 性能分析复查、临时截图移出 docs/images、各任务计划与变更记录 |

## 3. 文件级移植矩阵

### 3.1 生产代码（15 个 .kt diff → Java 翻译）

| master 文件 | k2j 对应 | diff 规模 | 移植要点 |
|---|---|---|---|
| config/PluginSettings.kt | PluginSettings.java | +8 | State 加 `verticalPairAnnotation` 默认 true |
| config/PluginSettingsPanel.kt | PluginSettingsPanel.java | +7 | 复选框 + addRow/isModified/apply/reset 四接线（**复选框总数台账**：PersistenceTest 计数若同步 +1） |
| config/SettingsIO.kt | SettingsIO.java | +2 | export/import 各一行 |
| execute/DataSourceSelector.kt | DataSourceSelector.java | +130 | BUILD_STAMP→M36.8；`executionHintReported` volatile；`execSilentProbe`；demoted→强制 executeQueries 兜底；afterSubmitted/startResultTabRetry 增 `dsName/demotedOverride` 参；8s 与 40 拍提示共用去重、三分流；attachConsole/getDocument 去 ReadAction（T-PreviewSlow F2） |
| log/HibernateLogParser.kt | HibernateLogParser.java | +38/−1 | `quarantined` 零参隔离槽：closeEntry 合并/让位 + takeEntries 仅 `!collecting` 冲刷 |
| log/LogEntryPreviewRecognizer.kt | LogEntryPreviewRecognizerKt.java | +8/−1 | `countPlaceholders` + 识别口径改用它 |
| log/LogSqlRestorer.kt | LogSqlRestorer.java + LogSqlRestorerKt.java | +56/−9 | restore 改 positions 重放；顶层函数 `placeholderPositions` 落 Kt.java（internal→同包 public） |
| log/LogToSqlPanel.kt | LogToSqlPanel.java | +2/−1 | unresolved 计数改 countPlaceholders |
| log/ProjectTypeDetector.kt | ProjectTypeDetector.java | +13 | detect 包 ReadAction.compute，主体拆 detectSync |
| log/RunConfigurationProcessListener.kt | RunConfigurationProcessListener.java | +54/−5 | `isModuleBasedLaunch/isModuleBasedProfile`（ModuleBasedConfiguration 反射判定，失败保守按主进程）；processStarted 行号清零加 gating |
| log/SqlLogEntryBuffer.kt | SqlLogEntryBuffer.java | +15/−6 | dedupeExisting 去时间窗口（纯指纹判重） |
| log/SqlLogPanel.kt | SqlLogPanel.java | +9/−2 | 警示文案分流：零参→unresolvedNoBinding，有参不齐→unresolvedWarning |
| ui/SqlPreviewPanel.kt | SqlPreviewPanel.java | +407/−60 | 本轮主战场，见 §5 专项 |
| ui/highlight/SqlFormatter.kt | SqlFormatter.java | +456 | formatVertical(sql, annotatePairs)（默认参→双重载）+ collectInsertColumns + expandHorizontalGroups 家族；**局部类/局部函数→静态嵌套渲染器**，见 §5 专项 |
| update/VersionComparator.kt | VersionComparator.java | +39/−24 | 委托 `VersionComparatorUtil.compare`，try/catch 降级 0；null 语义保留包装层 |

### 3.2 测试（16 文件）

| master 文件 | k2j 形态 | 处置 |
|---|---|---|
| config/PluginSettingsPersistenceTest.kt | .kt（有） | 增量编辑（+verticalPairAnnotation 字段断言/复选框计数） |
| config/SettingsIOTest.kt | .kt（有） | 增量编辑 |
| execute/ExecSilentNoopHintTest.kt | **新建** | 新建 .kt + 互操作适配（对 Java 生产类禁命名实参等） |
| log/DualParserIntegrationTest.kt | .java（有） | 增量翻译进 .java |
| log/HibernateLogParserDualCopyTest.kt | **新建** | 新建 .kt + 互操作适配（隔离槽逐行 takeEntries 断言） |
| log/HibernateLogParserTest.kt | .java（有） | 增量翻译 |
| log/LogEntryPreviewRecognizerTest.kt | .java（有） | 增量翻译 |
| log/SqlLogEntryBufferTest.kt | .java（有） | 增量翻译（dedupeExisting 跨窗口断言） |
| log/SqlLogManagerBehaviorTest.kt | .java（有） | 增量翻译（行号 gating） |
| ui/SqlPreviewPanelImportTest.kt | .kt（有） | 增量编辑 |
| ui/SqlPreviewPanelParamFieldTest.kt | **新建** | 新建 .kt + 互操作适配（16 列/光标钉首/tooltip） |
| ui/SqlPreviewPanelTest.kt | .java（有） | 增量翻译（+237 行：显示模式保持性/更多菜单/顶部布局等） |
| ui/SqlPreviewTopRowLayoutTest.kt | **新建** | 新建 .kt + 互操作适配（布局断言） |
| ui/XmlPreviewPanelTest.kt | .kt（有） | 增量编辑（tooltip 前缀匹配改版） |
| ui/highlight/SqlFormatterTest.kt | .kt（有） | 增量追加 +484 行（竖排/扩面/阈值/标注开关全用例；禁命名实参机械转换） |
| update/VersionComparatorTest.kt | .java（有） | 增量翻译（beta 预发布比较） |

### 3.3 资源与版本（直取对齐 2543203）

- `src/main/resources/messages/MpSqlVisualizerBundle*.properties`（en/zh 各 +11）：panel.vertical(+tooltip)/panel.more/execute.notSubmitted.hint/execute.timeout.hint/settings.verticalPairAnnotation/log.preview.unresolvedNoBinding
- `gradle.properties`：version=2026.9.10
- `build.gradle.kts`：版本号与 changeNotes 对齐 master HEAD（k2j 自有 sandboxContainer 临时行保留，终验后还原）
- `update/preview.update.json`：2026.9.10 清单直取（注意 master 侧文件**无尾随换行**，保持一致）
- plugin.xml：本增量**无改动**（核对过 name-status），但按惯例先做活引用审计（上轮教训：toolWindow factoryClass 悬空静态链不报）

### 3.4 文档（直取 15 件 + 三册 union）

- 直取：docs/开发计划/ 12 件新计划（T-PerfBatch/T-RollbackSql/T-PreviewTopOverlap/T-VStack×2/T-ExecSilentNoop/T-PreviewDisplayMode/T-VPairAnno/T-HibDualCapture/T-PreviewDocLock×2/T-PreviewSlow）、docs/发行说明/20260910-2240 新增 + 版本号规范修改、docs/性能分析/20260910-0345、docs/架构设计/02-核心流程详解修改
- 行级并集（git merge-file --union，k2j 专属行保留）：CHANGELOG.md、docs/MEMORY.md、docs/变更记录.md

## 4. 波次与提交拆分

1. **W1 计划**：本文件提交。
2. **W2 生产**：§3.1 十五文件 Java 翻译，一次提交（随编译通过验证）。
3. **W3 测试**：§3.2 十六文件，一次提交。
4. **W4 资源/文档**：bundle + 版本四件 + 文档直取/union，一次提交。
5. **W5 收官**：全量测试 + verifyPlugin 三目标通过后——.port-sync 锚点→2543203、计划文档回填实施记录、MEMORY/变更记录交付行。

## 5. 转换专项（本批特有风险）

- **SqlFormatter.formatVertical 局部类/局部函数**：Kotlin `class Line` + 7 个捕获可变局部变量的局部函数（flushSpace/newline/renderElem/closeLine/renderGroup）无法直译为 Java 局部类（捕获非 effectively-final）→ 收敛为 `private static final class VerticalRenderer` 实例状态机（持 sb/depth/pendingSpace/atLineStart/seenValues/groupValues/elemTokens/elemHadComma/elemTrailing/elemCounter/groupLines/columnNames），`formatVertical` 组装后调 `run()`。行为逐行对照 Kotlin 原文，不改语义。
- **默认参数→重载**：`formatVertical(sql, annotatePairs=true)`、`afterSubmitted/startResultTabRetry`（私有，改全部调用点即可）。`SqlPreviewPanel` 内新私有方法无需重载。
- **Kotlin 局部 lambda `fallbackDoc` + runCatching**：转 try/catch + 私有方法 `createFallbackDocument`（warn 日志 + psiFile=null + createDocument(sql)）。
- **ReadAction.compute 泛型**：Java 写法 `ReadAction.compute(() -> ...)`，243 编译目标用 ThrowableComputable 重载（与 ProjectTypeDetector 同型）。
- **MetaPerfSegment**：master `private data class` → Java `private record MetaPerfSegment(String text, String color)`（访问器 `text()`/`color()`）。
- **PreviewDisplayMode 枚举**：master 嵌套 enum → Java 嵌套 `public enum`；测试访问 `displayModeForTest()` 返回类型同步。
- **Char.isLetterOrDigit / indexOf(ignoreCase=true)**：SqlFormatter 扩面家族里 `findKeywordIndex` 的忽略大小写 indexOf 无 JDK 直配 → 私有静态辅助（regionMatches ignoreCase）。
- **互操作铁律**（历轮沉淀，逐条自查）：record 不 extends；.kt 新测试对 Java 生产类禁命名实参；Java 静态成员 Kotlin 侧须显式 import；非 get/is 前缀方法不合成属性；Light service Java 侧显式 final；heredoc 反斜杠坑——Java 字符串转义用 Write/Edit 直落；编译验证禁 grep 过滤。
- **复选框总数台账**：PluginSettingsPanel 新增一复选框 → PersistenceTest 若断言复选框总数须同步 +1（T-VPairAnno 教训 22→23）。
- **JBTextField caretPosition=0**：需在 text 设置后调用；`paramFields` 列表类型对齐既有 Java 字段。

## 6. 验证链与门禁

1. gradle 前必跑 `python C:\Users\USER\.zcode\cli\parallel_tasks.py check`，登记 `--name port-sync-master32 --sandbox k2jsync32`，跑完 `done`。
2. 验证链：`compileJava compileKotlin` → `compileTestKotlin --rerun-tasks`（防过期测试字节码）→ `test`（init 脚本沙盒重定向）→ `buildPlugin verifyPlugin`。
3. 全量绿 + verifyPlugin 三目标（243/253/262）BUILD SUCCESSFUL 且 Compatible；弃用计数与上版基线 **243:4 / 253:7 / 262:39 + experimental 6** 对比，新增须归因。
4. 终验后还原 build.gradle.kts 的 sandboxContainer 行再提交收官。
5. push 留用户 GUI（headless GCM 挂死勿重试）。

## 7. 验收标准

- 生产代码 100% Java，无 .kt 混入；`git diff 299607e..2543203` 的生产/测试/资源/文档四类全部落地（或明确记录豁免）。
- 全量测试 0 失败 0 错误（预期总数 ≥3230，新增用例数以实际为准）；verifyPlugin 三目标 Compatible。
- .port-sync 锚点 = 2543203 全 SHA；三册 union 后 k2j 专属行无损。

## 8. 实施记录（2026-09-11 收官回填）

按计划 W1~W5 全部落地，提交链：33d97d8 计划 → 29a1110 生产 → 225f2b0 测试 → 5a7d5bb 资源/文档 → 本收官提交。

- **生产（W2，16 文件）**：§3.1 矩阵 15 文件全落地（LogSqlRestorer 拆 `LogSqlRestorer.java`（restore 按位替换）+ `LogSqlRestorerKt.java`（placeholderPositions）；SqlFormatter 局部类/局部函数按 §5 收敛为 `VerticalState`/`VerticalLine` 静态嵌套状态机；SqlPreviewPanel 嵌套 enum + MetaPerfSegment record + 更多菜单 BaseListPopupStep）。compileJava 绿后提交。
- **测试（W3，16 文件）**：§3.2 矩阵全落地——9 修改（6 .kt 增量编辑 + DualParser/HibParser/LogEntryPreviewRecognizer/SqlLogEntryBuffer/SqlLogManagerBehavior/VersionComparator 六 .java 增量翻译）+ SqlPreviewPanelTest.java 追加 9 方法（显示模式保持性/压缩优先级/竖排按钮等）+ SqlFormatterTest.kt 追加 31 用例（`annotatePairs = false` 命名实参机械化转位置实参）+ 4 新建 .kt（ExecSilentNoopHint 纯逻辑直取 / HibDualCopy record 访问器方法调用化 / ParamField + TopRowLayout PreviewResult 20 参位置实参适配）。
- **资源/文档（W4，24 文件）**：bundle 六键 + gradle.properties 2026.9.10 + build.gradle.kts changeNotes 9.10 段（Lombok 注释/test sourceSets/compilerArgs 等自有块保留，sandboxContainer 临时行用后已还原）+ preview.update.json 直取（无尾随换行保持一致）+ docs 16 件直取 + 三册 union。
- **CHANGELOG union 特例（计划外）**：master 在 v2026.9.10 发行时把锚点期 Unreleased 的 T-PreviewFlash/T-DepApi/T-ExecCopyFix/T-RefreshDbSteal 四条**直接删除未搬入 9.10 段**，merge-file --union 的「删除侧胜出」吞掉 k2j 保留行（双向校验捕获 5 行丢失）——改手工方案：k2j 现有内容原样保留 + master 的 [2026.9.10] 段插入 [2026.9.9] 之前，双向零丢失复核通过。
- **编译期三修**：LogSqlRestorerKt 缺 java.util 导入；SqlLogManagerBehaviorTest/SqlPreviewPanelTest 新增方法里 `project` 是 Kotlin 属性名，Java 侧须 `getProject()`；MpSqlVisualizerBundle 在 SqlPreviewPanelTest 无 import 改全限定名。
- **全量（W5 门禁）**：`./gradlew test`（独立沙盒 k2jsync32）**3261/0/0/6 绿，263 套件**（较 T-SyncK2JMaster14 基线 3195 +66，套件数与 master 同为 263）。
- **verifyPlugin 三目标**：buildPlugin + verifyPlugin（243/253/262）BUILD SUCCESSFUL，三报告均 **Compatible**，Dynamic Plugin Eligibility 通过。弃用计数 **243:4 / 253:7 / 262:41**（262 较上轮基线 39 **+2**，归因本轮新增两处 `ReadAction.compute(ThrowableComputable)` 站点：ProjectTypeDetector.detect / SqlPreviewPanel.createEditor——与 master 本轮完全同源，报告逐条核实；243/253 不变、experimental 6 不变），无 compatibility problem。
- **锚点**：.port-sync synced_master → 254320340ecfd38443e589b6c35daba0dc859c0f（2543203）。push 留用户 GUI。
