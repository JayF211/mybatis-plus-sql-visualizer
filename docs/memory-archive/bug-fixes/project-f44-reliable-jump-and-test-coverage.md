---
name: project-f44-reliable-jump-and-test-coverage
description: F-44 实时日志可靠跳转源码方案（InnerInterceptor 标记行）+ 全插件 UI/集成测试覆盖（50+ 文件 200+ 用例，v2026.9.2 发行）
metadata:
  node_type: memory
  type: project
  updated: 2026-09-02
  originSessionId: sess_release_2026_9_2
---

F-44 系列（2026-08-31~09-01 实施，v2026.9.2 发行，合并原计划 8.22 未发行的更新机制改进）：

## 1. 实时日志「精确跳转源码」可靠方案（F-44-6）

**根因**：旧 `SqlLogConsoleBridge.extractSourceLocation` 在 **IDE 控制台线程**取调用栈，栈帧只有 JDK/IntelliJ/插件帧，永远取不到应用调用点 → `sourceFile/sourceLine` 恒为 null/0 → `jumpToSource` 退化为 `WrapperSearchService` 表名猜实体（不可靠、常落空）。

**新方案**：在**应用 JVM** 用 MyBatis-Plus `InnerInterceptor` 于 SQL 执行线程捕获真实调用点，向 stdout 打印标记行 `SQLVIS-SOURCE|<相对路径>|<行号>`；`SqlLogConsoleBridge.processConsoleLine` 解析写入 `LogSqlEntry.sourceFile/sourceLine`，实时日志复用与历史面板一致的精确定位跳转。无标记时零影响、被动回退原表名搜索。

**关键组件**：
- `SqlSourceMarker`：标记格式/解析（纯函数）
- `CallerSourceLocator`：调用栈→用户代码帧纯函数（VFS 路径统一正斜杠）
- `docs/instrumentation/SqlSourceInterceptor.kt`：运行期拦截器自包含示例（Windows 反斜杠拼接坑已修复为正斜杠）
- `SqlLogPanel` 抽 `internal sealed class LogJumpTarget`（Precise/NoSource/NotFound/SearchByTable/NoTable）+ `resolveEntryJump` 统一解析层

**Why**：IDE 控制台线程永远取不到应用调用点（架构性限制），必须在应用线程 InnerInterceptor 捕获。

**How to apply**：用户项目接入只需 copy `docs/instrumentation/SqlSourceInterceptor.kt` 并注册为 MP InnerInterceptor；标记协议与插件解析一致，无标记时零影响。

## 2. F-44-7：SqlSourceInterceptor 自动注入（已完成 2026-09-02）

**动机**：F-44-6 的 `SqlSourceInterceptor` 需用户手动复制到项目并注册，门槛高。F-44-7 做成设置开关，用户只需开启即可自动注入。

**方案**：方案 A 自动生成配置类（⭐⭐⭐ 从 4 种方案中选出）。
- `ProjectTypeDetector`：检测项目类型（Spring Boot via @SpringBootApplication / MyBatis native via SqlSessionFactory classpath）+ 定位源码目录和主包名
- `SqlSourceInterceptorAutoInjector`：幂等注入（检查文件已存在则跳过）+ PSI 文件创建 + CodeStyleManager 格式化
- `SourceInterceptorTemplates`：拦截器类 + Spring Boot @Configuration 类代码模板（标记格式与 SqlSourceMarker 一致）
- 设置项 `autoInjectSqlSourceInterceptor: Boolean = false` + 设置页 UI 复选框 + `triggerInterceptorInjection()` 方法

**核心限制**：IDE JVM 与应用 JVM 隔离，无法运行时注入；必须在应用启动前生成配置文件。

**相关记忆**：[[project-log-jump-no-wrapper-rootcause]]

## 3. SQL 日志面板四项修复（F-44-2）
- **列表 SQL 词法高亮恢复**：M46 重构移除 HTML 通道后回滚，SQL 摘要走 `SqlListHtmlRenderer`
- **耗时字段接入控制台链路**：`ExecutionTimeParser` 已有 p6spy/MP TimeCount/自定义前缀实现，bridge `FilterProxyHandler` 从未调用→本次接通
- **去重已存在条目同步去重**：`SqlLogEntryBuffer.dedupeExisting()` 勾选后立即按窗口裁剪
- **去重计数即时刷新**：去重分支也调用 `notifyChanged()`，`⨉N` 徽章与动作一帧同步

## 4. SQL 历史面板两级导航（F-44-3）
- 一级（文件列表：文件名+大小+条数）→ 二级（双击进文件 SQL 列表：高亮+耗时+源码）→ 双击 SQL 预览
- 搜索框按文件名/SQL 内容过滤；刷新保持浏览位置；旧目录数据不读取（F-44-4）

## 5. 插件更名 + 数据目录（F-44-4）
- 插件显示名「MyBatis-Plus SQL Visualizer」→「SQL Visualizer」（plugin.xml `<name>` + 全部用户可见文案中英双语同步更名）
- 插件 ID / 构建产物 zip 顶层短名 / 仓库与 Marketplace 页地址不变
- 数据目录 `.idea/mybatis-plus-sql-visualizer` → `.idea/sql-visualizer`（**不考虑向前兼容**）
- 新增统一常量 `SqlLogAutoSaver.DATA_DIR_NAME`

## 6. 全插件测试覆盖（50+ 文件 200+ 用例）
- **UI/交互**：SqlLogPanel/HistorySqlLogPanel/PluginSettingsPanel/LogToSqlPanel/SqlToJavaPanel/Help/SqlPreviewPanel/SqlResultPanel/SqlSelectionDialog/ShareDialog/ConvertToSqlAction/ConvertSqlToJavaAction/SqlInputEditor + 3 个 gutter 测试
- **真实场景端到端**：SqlLogHistoryRoundTripTest(5)/SqlLogCaptureRealtimeScenarioTest(9)/HistorySqlLogPanelNavigationTest(7)/SqlLogPanelDedupeToggleTest(5)
- **可靠跳转链路**：SqlSourceMarkerTest(6)/CallerSourceLocatorTest(5)/SqlLogConsoleBridgeMarkerTest(5)/SqlLogPanelLiveJumpWithSourceTest(6)
- **集成骨架**：DataSourceSelectorIntegrationTest(4，经 `testDatabase` 任务)
- **生产可测化重构（零行为变更）**：日志列表渲染器抽 `internal LogEntryListCellRenderer`；历史/实时面板抽出 `internal` 解析层；`*ForTest` 测试缝

**相关记忆**：[[project-m365-console-embed-plan-a]]（M36 原生控制台嵌入） / [[project-m36-direct-execute]]（M36 执行链路） / [[feedback-m36-native-console-misalignment]]（控制台嵌入成败） / [[project-log-jump-no-wrapper-rootcause]]（F-44-7 自动注入）