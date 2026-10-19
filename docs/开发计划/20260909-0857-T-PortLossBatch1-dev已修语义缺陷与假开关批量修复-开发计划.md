# T-PortLossBatch1：dev 已修语义缺陷嫁接 + 假开关批量处置 开发计划

> **创建日期**：2026-09-09
> **分支**：master（当前 HEAD `6ede29d`）
> **优先级**：P1（转换核心 6 个语义缺陷直接影响生成代码正确性；4 个假开关损伤设置页可信度）
> **预估工时**：3~4h（嫁接与测试同步 2.5h + 全量回归 0.5h + 文档 0.5h）
> **来源**：branch-port-loss-audit 审计（2026-09-09）第二类「master 带病运行」+ 第三类「假开关」；用户指令「第二类，第三类一起修」

---

## 一、背景与范围

审计实锤 dev→master 人工移植断层导致 master 带着九个 dev 已修复的语义/体验缺陷运行，另有四个设置字段写穿读断。本批只修**缺陷与假开关**（审计第二、三类），功能整体缺失（M37.1/.2/.4、M39、M44、T-ErrReport、hbm、gutter 交互）留待 Batch2 按需排期。

**排除项**：`83bdbc4`（popup 焦点告警 doFinalStep）——其修复对象「生成测试入口」（M37.4）与「gutter 最近记录」（M37.1）在 master 均无宿主功能，无法独立移植，随 Batch2 功能移植一并处理。

## 二、A 组：M38 语义缺陷嫁接（6 缺陷，5 文件 9 块）

来源 dev `3c25cbb`（M38.2 两个语义偏差）+ `904152c`（M38.2/38.3 三个真缺陷 + 一个求值缺陷）。**手工逐块嫁接，禁止整文件覆盖**——已实证 dev 版五文件混有 M44（`XbatisConverter` 引用，master 无此类会编译失败）与 T-Perf 重构（`QWP_LIMIT_PATTERN` 提取）等非本批内容。

| # | 缺陷 | 修法 | 文件 |
|---|---|---|---|
| A1 | `ne` 渲染 `!=`（MP 实际 `<>`） | 3 处字面量替换 `simple(call, "!=")`→`"<>"`、`methodName == "ne"` 分支同改 | QueryWrapperParser（1）/ WrapperConverter（2）/ MpjLambdaConverter（2）= 5 处 |
| A2 | `or(Consumer)` 内外颠倒：`or(w->w.eq(2).eq(3))` 渲染 `AND (2 OR 3)` | or 分支改 `parseGroupFlagged(call)` 结果以 `orPending=true` 落位；组内 connector 恒 `Connector.AND` | QueryWrapperParser |
| A3 | `condition=false` 被当 true 渲染 | 新增 `isFalseFlag`（布尔前缀 false 字面量检测），命中 `return null` 整体不拼接 | LambdaUpdateConverter |
| A4 | 字符串版 `having("...")` 静默丢弃 | lambda 嵌套形态外补 ValueArg 字符串 → `Condition.Raw` 透传 | QueryWrapperParser |
| A5 | `last("limit 5 OFFSET 10")` 的 OFFSET 丢失 | limit 解析补 `(?i)offset\s+(\d+)` 回填 `LimitClause(offset=…)` | QueryWrapperParser |
| A6 | 负数字面量 `-1` 误判运行时占位符 | `evaluate` 补 `PsiPrefixExpression` 分支 + `evaluatePrefix`（操作数为数值字面量才静态求值） | ExpressionEvaluator |

### 测试同步（A 组关键成本）

master 既有测试按旧行为断言 `!=`，嫁接后会红：全量 grep `src/test` 中 `!=` 渲染断言与 or 组渲染断言逐一更新为 dev 语义。并为 A3~A6 各补 ≥1 针对性用例（condition=false 丢弃、having 字符串透传、OFFSET 回填、`-1` 静态求值、or 内外正确性），落在 master 既有测试类（QueryWrapperParserTest / WrapperConverterTest / LambdaUpdateConverterTest / ExpressionEvaluatorTest 同族）。

## 三、B 组：M38.1 gutter 误报守卫嫁接（2 守卫）

来源 dev `71ea4a4`。两守卫均为文本级 PSI 判定、零 dev 依赖，可独立嫁接：

- **B1** `WrapperAnchorDetector`：`qualifierIsNewExpression` 守卫——`new Helper().lambdaQuery()` 任意类实例撞名调用不标锚（dev 版该文件 diff 62 行混有 M39/M44，只取此 6 行块）；
- **B2** `JpaGutterLineMarkerProvider`：`isInRepositoryInterface` 守卫——普通类 `findByXxx` PartTree 形态不标锚（接口 + extends 含 "Repository" 文本判定）。master 版该文件（81 行，M39 前基础版）分叉大，实施时在 `getLineMarkerInfo` 派生查询判定点插入守卫 + 附 `isInRepositoryInterface` 私有函数。

### 测试

WrapperAnchorDetectorTest（或同族）补 2 个负例：`new Helper().lambdaQuery()` 链不产锚、非接口类 `findByXxx` 不产锚。

## 四、C 组：设置导入导出行内反馈（363591b + 6443089）

现状：导入/导出结果用 `Messages.show*` 模态弹窗反馈，parent 挂 rootPanel 叠在 Settings 对话框**之下**——必须关掉设置页才能看到结果。

- **C1** 嫁接 `363591b`（PluginSettingsPanel 92 行）：4 处模态改面板内 statusLabel 行内提示（成功默认色 / 失败 ErrorForeground 红字 + tooltip 全量详情）；IO 拆 `applyImportedText` / `writeExportedFile` 可测函数。该文件 master↔dev 分叉 199 行（含 M37.4/M39 设置项），只取导入导出区块手工嫁接；
- **C2** 嫁接 `6443089`（8 行）：反馈 `invokeLater` 显式 `ModalityState.any()`——模态 Settings 弹窗存续期回调照常执行（对齐 M36.5.13 DataSourceSelector 惯例）；
- **C3** 测试：`PluginSettingsPanelStatusFeedbackTest`（dev 版 57 行）原样移植。

## 五、D 组：假开关处置（4 项，处置方向已逐一定案）

| # | 字段 | 定案 | 理由与改动面 |
|---|---|---|---|
| D1 | `logStopOnToolWindowClose` | **下线清理** | 实现已随 T-M51（31b5b437）有意移除且 dev 不再恢复；删 PluginSettings 字段 + PluginSettingsPanel 5 处绑定 + bundle zh/en 各 1 行（SettingsIO 导入导出无此字段，无兼容问题） |
| D2 | `injectTenantOnInsert` | **接线生效** | 默认值 `true` 与现状行为兼容；INSERT 租户列注入参数链为 SqlPreviewService:308 → BatchOperationConverter.buildInsertBatch:191/405，仅 INSERT 分支消费该参数——改传参为 `injectTenantLine && injectTenantOnInsert`（UPDATE 的 SET 注入走主开关，不受影响）；补 false 用例 |
| D3 | `collapsedGroups` | **删除字段** | 设置页已改版为 section 导航无折叠 UI（「默认折叠」注释为改版前遗物），全仓零读取；删字段即可 |
| D4 | `autoInjectSqlSourceInterceptor` | **文案澄清** | dev 现状同 master（勾选瞬间注入一次，无后台自动路径——自动改用户源码有风险，dev 亦未做）；tooltip 改为「勾选时立即生成一次 SqlSourceInterceptor 和配置类……」（zh/en），消除「开启后持续自动」误导 |

## 六、实施顺序与验证

1. A 组嫁接 + 既有断言更新 + 新用例 → 定向跑转换测试族；
2. B 组守卫 + 负例 → 定向跑锚点/gutter 测试；
3. C 组嫁接 + 测试移植 → 定向跑面板测试；
4. D 组四项 → 假开关复扫（审计 A 脚本口径：除 autoInject 保留字段外零读点字段清零）+ 定向跑设置/租户测试；
5. 全量回归 `./gradlew test`（基线 3138/0/6，本批新增用例净增）；并行登记 `autoshow-master-20260909` 沙盒惯例沿用，跑前 check。
6. 提交拆两个：`fix: T-PortLossBatch1 A~C 组`（语义缺陷 + gutter 守卫 + 导入导出反馈）与 `fix: T-PortLossBatch1 D 组`（假开关处置），计划文档随首提交入库。

## 七、风险与回滚

- **测试断言面**：A1 影响所有 ne 渲染断言——嫁接前先 grep 盘点 `src/test` 中 `!=` 断言清单，逐个确认属 ne 语义后更新；全量回归兜底。
- **JpaGutter 嫁接点**：master 版为 M39 前基础版，守卫函数自包含，若判定流程差异过大则退化为仅 B1（WrapperAnchorDetector 守卫独立成立）。
- **面板嫁接冲突**：C 组区块独立（导入导出按钮区），与 M37.4/M39 设置项区块无交集；冲突则降级为先修模态反馈位置（C1 前半）。
- **回滚**：两组提交各自独立 revert；D1/D3 删字段为纯减法，持久化旧数据被平台忽略，无迁移。
