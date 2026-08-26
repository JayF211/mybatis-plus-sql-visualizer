---
name: m30-m33-features
description: M30-M33 全量开发已完成（2026-08-23 合并）——11 功能模块：SQL 诊断/复杂度评分/JOIN
  可视化/Wrapper 补全/弹窗 UX/日志差异化/拦截器模拟/Kotlin/历史记录/团队分享/设置导入导出
metadata:
  node_type: memory
  type: project
  originSessionId: sess_0f51a5f9-dd81-4575-8c93-cc772c7c4cde
---

# M30-M33 全量优化与新功能（2026-08-23 合并，commit 235be2b）

基于竞品分析（`20260822-1500-竞品分析与优化建议.md`）与五项并行调研，3 个优化项 + 9 个新功能按四个里程碑实施。**298 测试增量**，总计 11 功能模块。

## M30：SQL 诊断平台核心（P0）

- **F-2 SQL 性能分析建议**（`SqlPerfAnalyzer`）：纯静态分析，20 条规则分三批——Batch1 6 条正则规则（无 WHERE 的 UPDATE/DELETE、前导通配符 LIKE、SELECT * 多表 JOIN、ORDER BY 无 LIMIT、ORDER BY RAND()、UNION 改 UNION ALL）、Batch2 8 条 JSqlParser AST 规则（JOIN 无 ON、子查询嵌套、IN 超 100 项、函数包裹列、OR 过多、DISTINCT+JOIN、GROUP BY 无聚合、无 LIMIT 大结果集）、Batch3 6 条高级规则（关联子查询、隐式类型转换、BETWEEN 日期、SELECT 内嵌子查询、OFFSET 分页、多重 GROUP BY/ORDER BY）。LRU 缓存 200 条，最多 10 条警告，< 50ms。
- **F-3 复杂度评分**（`ComplexityScorer`）：0-100 分五级（SIMPLE/MEDIUM/COMPLEX/VERY_COMPLEX/EXTREME），维度：表数量、JOIN 数量、子查询深度、SELECT *、前导通配符、无 WHERE、函数包裹列、ORDER BY RAND()、DISTINCT、GROUP BY、HAVING、UNION、CTE、OR。
- **F-1 JOIN 可视化**（`JoinDiagramGenerator` + `DiagramPanel`）：PlantUML entity 图渲染，主表绿色、JOIN 表蓝色，JOIN 类型对应连线样式，ON 条件为连线标签。支持 PNG/SVG 导出、滚轮缩放。

## M31：智能补全与体验增强（P1）

- **F-5 Wrapper 方法链智能补全**（`WrapperCompletionContributor`）：Java 文件中 Wrapper 链上下文感知——方法名补全（链首/条件后/select 后/orderBy 后）+ 实体字段补全（`.eq(|)` 首参位置）。O(1) 纯文本判定无 resolve，DumbAware。
- **O-4 预览弹窗 UX 增强**：差异高亮（本次 vs 上次预览）、SQL Diff（LCS 算法行级对比）、一键复制可执行 SQL（参数替换）。
- **O-1 日志窗口差异化**：执行耗时解析（前缀 `Time:` 可自定义）、慢查询标记（阈值默认 1000ms 可配置）、源码跳转。

## M32：拦截器模拟与 Kotlin（P1）

- **F-6 拦截器模拟增强**：TenantLine UPDATE SET 追加租户列 + INSERT 列清单追加（独立开关 `injectTenantOnInsert`）；动态表名正则替换（`$1`/`$2` 捕获组 + `$tableName` 变量）；数据权限占位提示 + 手动模拟条件输入。
- **F-4 Kotlin KtWrapper 支持**（`KtWrapperDetector`）：`ktQuery()`/`ktUpdate()` 扩展函数 + `LambdaQueryWrapper<User>()` 直接构造，gutter 图标预览。MVP 按行扫描源码文本正则匹配。

## M33：团队生态与设置完善（P2）

- **F-7 SQL 历史记录**（`SqlHistoryManager` + `SqlHistoryToolWindowFactory`）：自动记录每次预览（上限 200 可配置），收藏/标签/搜索，跨会话持久化（`PersistentStateComponent`）。
- **F-8 团队协作**（`SqlShareManager`）：Markdown 代码块/表格、HTML 报告、可执行 SQL 一键复制，关联源码位置。
- **O-5 设置导入导出**（`SettingsIO`）：JSON 格式（仅可覆盖项，不含全局项如语言/快捷键），按项目覆盖全局设置（`ProjectPluginSettingsConfigurable`）。

## 关键约束与经验

- **JOIN 图标签页显示条件**：`statement is SelectStatement && statement.joins.isNotEmpty() && previewModel != null`。即必须同时满足：SELECT 语句 + 有 JOIN + 有动态条件（views 非空）。UPDATE/DELETE/单表/XML/JPA 预览不显示。
- **previewModel 为 null 的场景**：无动态条件的 Wrapper 链（`views.isEmpty()`）、批量操作（走 batchModel）、XML 预览（走 xmlModel）、JPA 预览（可能为 null）。
- **JOIN 图不显示"无JOIN子句可可视化"排查（2026-08-23 用户反馈）**：① Service 未继承 `MPJBaseService`（或 Mapper 未继承 `MPJBaseMapper`）→ 插件识别不到 MPJ 能力；② 用了 `LambdaQueryWrapper` 而非 `MPJLambdaWrapper`（单表 Wrapper 不支持联表）；③ `leftJoin(Entity.class, ...)` 实体类不在项目 PSI 内无法解析 TableInfo。**判断方法**：看预览弹窗顶部类型行——`MPJ_LAMBDA` = MPJ 能力已识别，`LAMBDA` = 被当单表查询。
- **ORDER BY without LIMIT 警告（2026-08-23 用户咨询）**：Batch2 性能规则，ORDER BY 全量排序无 LIMIT 兜底有风险（全量 sort + 大结果集传输）。修复：`.last("LIMIT 1000")` 或 `.page(new Page<>(1, 20))`。
- 新增外部依赖：JSqlParser 5.3（+1.26MB）、PlantUML 8059（+5MB）
- 子计划文档：`docs/开发计划/20260822-1601 ~ 20260822-1611`（总纲 + 11 个子计划）

## 用户实际使用踩坑（2026-08-25~26）

- **Wrapper 智能补全"感受不到"**：① 字段补全仅在实体类与 Wrapper链**同文件**时生效（`FieldLookupFactory.resolveEntityTable` 用 PSI 子树遍历不做 resolve，跨文件无法解析实体 → 降级为仅方法名补全）；② 方法名补全需链首被识别（`new LambdaQueryWrapper<>()` / `Wrappers.lambdaQuery()` / `service.lambdaQuery()` 等）；③ 仅 Java 文件触发（plugin.xml `language="JAVA"`），Kotlin 不触发。
- **差异对比（Compare）使用方式**：点击工具栏 `差异对比` 按钮 → 弹出 IDEA 原生 Diff 窗口（左侧=上次预览 SQL，右侧=当前 SQL，两侧均可编辑 + SQL 语法高亮）。需至少两次预览才有"上次 SQL"可对比，否则 toast 提示。无 Diff 行内高亮按钮（已移除）。
- **SQL 定位到源码入口**：在**日志面板右键菜单**中（非预览面板）——右键单击日志条目 → `Jump to Source` → 提取表名 → 后台搜索 Wrapper → 单匹配直接跳转 / 多匹配弹选择框 / 未找到提示。完整右键菜单：Copy / Delete / Send to SQL→Java / Send to Log to SQL / Jump to Source。
- **预览弹窗工具栏完整按钮序列**（从左到右，2026-08-26 更新）：CopyError(条件异常时显示) / 单多行切换 / Format / KeywordWrap / ImportParams / CopyExecutableSql / **差异对比(Compare)** / Share / Refresh。**Diff 差异高亮按钮已移除**。

## Bug 修复（2026-08-26）

- **差异高亮整个移除（已修复）**：原 `DiffHighlighter` 用 `EffectType.BOXED` 画的是行内边框非背景填充且 alpha=40 太淡，用户反馈无效果。最终决策：**整个差异高亮功能移除**——删除 `DiffHighlighter.kt`、工具栏 `Diff` 按钮、`toggleDiffHighlight()`/`applyDiffHighlight()` 方法全部移除。保留 `SqlDiffer.kt`（有对应单元测试）。
- **Compare 改用 IDEA 原生 Diff + 可编辑（已修复）**：原来自研 `SqlDiffDialog`（双 `JBTextArea` 只读文本区）功能简陋。改为接入 `DiffContentFactory.createEditable()` + `SimpleDiffRequest` + `DiffManager.getInstance().showDiff()`，两侧均可编辑 + SQL 语法高亮 + IDEA 标准 diff 全部能力（行级高亮、同步滚动、折叠相同行、F7 跳转）。已删除 `SqlDiffDialog.kt` 整个文件。
- **Compare 按钮重命名**：`并排对比` → `差异对比`（中英文版 bundle 同步更新）。
- **DiffContentFactory API 陷阱（已修复）**：① `create()` 第三参期望 `FileType?`，传 `"text/sql"` 字符串编译报错；② 传裸 `null` 报 overload resolution ambiguity（四个重载都接受 null）；③ 正确写法：显式类型注解 `val leftContent: DocumentContent = diffContentFactory.createEditable(proj, sql, sqlFileType)`。**How to apply：** 对接 `DiffContentFactory` API 时第三参不要传 MIME 字符串，用 `createEditable()` 获取可编辑内容。

关联：[[project-overview]] [[project-architecture]] [[project-models]]