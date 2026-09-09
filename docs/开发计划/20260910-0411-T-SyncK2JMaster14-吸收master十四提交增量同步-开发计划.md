# T-SyncK2JMaster14 开发计划：master 29fbf55..299607e 十四提交增量同步

日期：2026-09-10 ｜ 分支：feature/kotlin-to-java（worktree `mybatis-plus-sql-visualizer-kotlin-to-java`）｜ 状态：实施中

## 1. 背景与范围

上次同步锚点 `.port-sync = 29fbf55`（T-SyncK2JMaster33，3138 绿）。master 其后落了 14 个提交（3263+/188-，59 文件），本次按既定方案 A 手工移植，绝不 git merge：

| 提交 | 内容 | 主要落点 |
|---|---|---|
| 3ff30bb | T-AutoShowMaster 发现SQL自动开窗（M37.3，logAutoOpenOnCapture 默认开） | PluginSettings、SqlLogManager |
| 5507b86 | T-PortLossBatch1 A~C（ne/or 语义、定位误报、导入导出反馈） | converter×4、psi×2、gutter、设置面板 |
| 066051e | T-PortLossBatch1 D（删 stopOnToolWindowClose/collapsedGroups 假开关、接线 injectTenantOnInsert） | 设置族、SqlPreviewService、bundle |
| 78cc7c2 | T-AutoShowRecover（stopOnToolWindowClose 转正恢复 + 启动清抑制 + ToolWindowVisibilityListener 回归） | 设置族、SqlLogManager、SqlLogToolWindowFactory |
| 4340ade / 067987b | 发行 v2026.9.9 + 追补 | gradle.properties、build.gradle.kts changeNotes、CHANGELOG、发行说明、preview.update.json |
| 7953aa2 | T-PreviewFlash（ConsoleReplayGuard 重放守卫 + EDT 慢操作消解） | ConsoleReplayGuard 新建、LogFilterProxyHandler、SqlLogConsoleBridge、SqlPreviewPanel |
| 3ba0708 | T-DepApi 三处弃用 API 替换 | SqlSourceInterceptorAutoInjector、HbmXmlEntityResolver、StartupUpdateCheck |
| 2467f94 | T-LogRowNumber 行号（sequenceNumber + processStarted 清零） | LogSqlModels、SqlLogEntryBuffer、SqlLogManager、SqlLogPanel、RunConfigurationProcessListener |
| 27799c3 | T-ExecCopyFix（执行失败可见化 + CopyFeedback 对号反馈） | DataSourceSelector、CopyFeedback 新建、SqlResultPanel、bundle |
| 707e76a | T-RefreshDbSteal（多弹窗僵尸 rehost 监听根治） | SqlPreviewDialog、SqlPreviewPanel、SqlPreviewService |
| 446b490 / 0ef05cf / 299607e | 文档与群二维码 | docs 树 |

`SqlLogToolWindowFactory.java`、`ToolWindowVisibilityListener.java`、`PluginSettingsPersistenceTest.kt` 在本增量内净零变更（066051e 删、78cc7c2 原样加回），不单独移植。

### 1.1 审计发现：k2j 分支存量缺口（本计划顺带根治）

对 master 全量生产类与 k2j 逐一比对（basename 口径）+ plugin.xml 引用类核查，发现：

- **SqlLogToolWindowFactory.java 与 ToolWindowVisibilityListener.java 在 k2j 缺失**。90e8d090（.kt→.java 转换轮）删掉了转换前旧版（80/42 行）却未补转换版，plugin.xml 第 227 行活引用自此悬空。静态链（compileJava/test/buildPlugin/verifyPlugin）都不校验 toolWindow factoryClass 存在性，故历轮全绿未暴露；runIde 真机才会炸。两文件 master HEAD 本就是 Java，直取即补齐（含 78cc7c2 的开关转正接线，与 SqlLogManager/PluginSettings 增量正好闭环）。
- `SqlHistoryToolWindowFactory`（master 有、k2j 无）：plugin.xml 引用在注释块内，双侧同为死代码，不补。
- `Ognl`（master Ognl.kt ↔ k2j OgnlExpr/OgnlEvaluator）：改名承载，非缺口。
- `UpdateDeleteStatement`：master 自身零引用死类，不补。
- 测试面：master 独有测试恰为本增量 6 个新测试，k2j 无独有测试，无存量缺口。

## 2. 移植清单

### 2.1 生产代码（26 改 + 2 新建翻译 + 2 直取补缺）

按净 diff `git diff 29fbf55..master -- <file>` 逐块翻译进对应 `.java`（方法名以本地 jar 反编译为权威，遵循既有互操作铁律）：

- config：PluginSettings（logAutoOpenOnCapture 默认 true、删 collapsedGroups）、PluginSettingsPanel（三提交叠加：状态反馈/injectTenantOnInsert 接线/stopOnToolWindowClose 转正）、PluginSettingsFolderConfigurable
- converter：LambdaUpdateConverter、MpjLambdaConverter、QueryWrapperParser、WrapperConverter（T-PortLossBatch1 A~C 语义修复）
- execute：DataSourceSelector（T-ExecCopyFix 153 行）
- log：LogSqlModels→**LogSqlEntry.java**（+sequenceNumber 字段；k2j 侧模型按类拆文件，落点以 grep 定位）、LogFilterProxyHandler、SqlLogConsoleBridge、SqlLogEntryBuffer（行号分配）、SqlLogManager（三提交叠加：自动开窗+行号清零+stopOnClose）、SqlLogPanel、RunConfigurationProcessListener、SqlSourceInterceptorAutoInjector（T-DepApi）
- psi：ExpressionEvaluator、WrapperAnchorDetector、jpa/HbmXmlEntityResolver（T-DepApi）
- ui：SqlPreviewDialog、SqlPreviewPanel（两提交叠加 250 行）、SqlPreviewService、SqlResultPanel、gutter/JpaGutterLineMarkerProvider
- update：StartupUpdateCheck（T-DepApi）
- 新建翻译：log/ConsoleReplayGuard.java、ui/CopyFeedback.java
- 直取补缺：log/SqlLogToolWindowFactory.java、log/ToolWindowVisibilityListener.java（master HEAD 原样）
- bundle ×2：基线已对齐锚点，整取 master HEAD

### 2.2 测试（9 改 + 6 新建）

- .kt 直取整替（5）：QueryWrapperParserTest（k2j 分叉为历史具名→位置实参机械改写，整替 master HEAD 顺带消除分叉）、LogFilterProxyHandlerTest、WrapperAnchorDetectorTest、SqlPreviewServiceBatchTest、JpaGutterLineMarkerProviderTest——后四者基线已证对齐锚点
- .java 增量翻译（4）：LogSqlModelsTest、SqlLogEntryBufferTest、SqlLogManagerBehaviorTest、SqlLogPanelRendererUiTest
- 新建（6）：ConsoleReplayGuardTest、CopyFeedbackUiTest、ExecTempFileFixTest、PluginSettingsPanelStatusFeedbackTest、RefreshDbStealTest、SqlLogManagerAutoShowTest——按上轮惯例 .kt 直取+互操作适配；撞 JUnit3 test 前缀×lambda 合成方法坑则抽非 test 前缀私有工厂

### 2.3 版本/构建/文档

- gradle.properties、update/preview.update.json：基线对齐，整取
- build.gradle.kts：手术式合并——只并入 changeNotes v2026.9.9 段，保留 k2j 专属块（sourceSets test java.srcDir、Lombok 注释块、-parameters 等编译参数）；期间 sandboxContainer 行按惯例临时改独立沙盒、终验后还原
- CHANGELOG.md、docs/MEMORY.md、docs/变更记录.md：`git merge-file --union` 行级并集（k2j 专属行保留 + master 增量行并入）
- 直取：docs/发行说明/20260909-1235-*.md（新）、Marketplace发布流程-说明文档.md、开发计划 7 件、docs/images/group-qr.png
- `.port-sync` 锚点 → 299607e（收官提交）

## 3. 波次与提交链

1. 立项：本计划文档
2. Wave1 生产代码：翻译 26 文件 + 新建 2 + 直取补缺 2 + bundle 2
3. Wave2 测试：直取 5 + 翻译 4 + 新建 6
4. Wave3 版本/构建/文档：changeNotes 并集、版本资源、文档 union
5. 收官：全量验证通过后还原 sandboxContainer、推进 .port-sync、交付记录

## 4. 验证链（gradle 前必跑 `python C:\Users\USER\.zcode\cli\parallel_tasks.py check`，登记 port-sync-master-14 / 沙盒 portsync14）

1. `compileJava compileKotlin` 生产编译
2. 全量 `test`（K2J 陈旧输出目录劫持风险：失败先查 build/classes 残留，必要时清理重跑；compileTestKotlin --rerun-tasks 防过期测试字节码）
3. `buildPlugin`
4. `verifyPlugin` 三目标（243/253/262）须全 Compatible
5. 终验后还原 build.gradle.kts 沙盒行再收官提交

基线口径：3138/0/0/6 绿 + 本增量新增用例（master 口径约 +47 方法），失败只允许来自移植笔误，逐个归因修复。

## 5. 风险与对策

- **多提交叠加文件翻译漏块**：SqlLogManager/PluginSettingsPanel/SqlPreviewPanel 各叠 2~3 提交，一律以净 diff 为唯一输入，逐 hunk 勾稽
- **LogSqlModels 拆分映射**：k2j 模型按类拆文件，sequenceNumber 落点先 grep 实证再动笔
- **.kt 新测试互操作**：Kotlin 调 Java 禁命名实参、静态成员需显式 import、Unit.INSTANCE；CopyFeedback/ConsoleReplayGuard 本轮按 Java 惯例新建（object→final class+INSTANCE）
- **verifyPlugin 不保 toolWindow 类存在性**：补齐两文件后以 grep 审计 plugin.xml 全部活引用类落盘，作为 verifyPlugin 之外的额外门禁
- 回滚：每波次独立提交，异常 `git reset --hard <波次前>` 即可

## 6. 实施记录（2026-09-10 收官回填）

提交链：2142a8f 立项 → 9ec8fdb 生产（32 文件 +1189/-163）→ 4718607 测试（16 文件 +1200）→ 16363b9 资源/构建/文档 → 本收官提交（锚点 + 交付行）。

- **验证结果**：全量 3195/0/0/6 绿（基线 3138 + 本轮 57 新用例）；buildPlugin 16s 绿；verifyPlugin 三目标（IU-243.28141.41 / IU-253.33514.17 / IU-262.9437.185）全 **Compatible**，弃用计数 243:4 / 253:7 / 262:39、experimental 6（留档对比）。plugin.xml 活引用类审计：仅 history.SqlHistoryToolWindowFactory 缺失（注释块死引用，双侧对称，不补）。
- **计划偏差 1（QueryWrapperParserTest 整替方案否决）**：master HEAD 整文件直取后 compileTestKotlin 爆约 40 处「Named arguments are prohibited for non-Kotlin functions」——master 测试全用命名实参调 Kotlin 生产类，而 k2j 生产侧同名类是 Java。回退整替，改增量翻译（2 个既有 or 语义测试断言改版 + 4 个 M38.2 新用例按 k2j 位置实参风格）。教训：该文件的「风格分叉」不是历史遗留，而是 k2j 测试适配 Java 生产侧的必然形态，后续同步该文件一律增量翻译，不再整替。
- **计划偏差 2（union merge 用法事故，已修复零残留）**：`git merge-file --union` 无 `-p` 时就地改第一个文件，误把 stdout 重定向当产物复制，三份共享文档（CHANGELOG/MEMORY/变更记录）工作副本被清空；git 对象未损，从 HEAD/29fbf55/master 三方重新合并，逐文件验证「对 master 零删减 + k2j 专属行保留」后落盘。
- **编译期三处移植笔误**：LogSqlEntry 的 SqlSource 重载构造器漏加 `, 0L`（replace_all 被字面量差异躲过）；直取的 SqlLogToolWindowFactory/ToolWindowVisibilityListener 调 Kotlin 属性访问器 `getCapturing()` 而 k2j SqlLogManager.java 只暴露 `isCapturing()`——补 `getCapturing()` 别名保持直取文件与 master 零分叉；SqlLogManager 字段初始化器 lambda 读未定值 final 字段 `project` 触发 javac DA 限制——改方法引用 `this::defaultAutoShowRequest`。
- **新测试互操作适配**：SqlLogManagerAutoShowTest 的 onAutoShowRequest lambda 赋值抽入非 test 前缀私有工厂 `managerWithCounter()`（JUnit3 前缀扫描撞 testXxx$lambda$N 合成方法假失败，历轮踩坑规避）；CopyFeedbackUiTest/RefreshDbStealTest 的 PreviewResult 具名实参改位置实参（20 参全量/7 参便捷构造各一）；RefreshDbStealTest 的 showResult 补第三参 null（Java 无默认参数）。

