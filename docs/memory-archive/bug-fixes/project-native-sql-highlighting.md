---
name: project-native-sql-highlighting
description: 预览面板原生 SQL 高亮 + 补全（M34）——完整根因链：no PSI file（in-memory 不追踪）/ EDT SlowOperations（SqlLanguageSubstitutor）/ language=SQL 注册（全语言不被调度）
metadata:
  node_type: memory
  type: project
  originSessionId: sess_0f51a5f9-dd81-4575-8c93-cc772c7c4cde
  lastUpdated: 2026-08-26
---

## 迁移背景（2026-08-26）

自研 `SqlPreviewLanguage`（8 token + 56 关键字 + SqlLexer）高亮效果简陋（无方言感知、无字符串转色、无子查询层级着色）。用户实测 IDEA 原生 Diff 视图的 SQL 高亮效果极佳，要求预览面板统一升级。

## 迁移结果

- **编辑器创建**：`createFileFromText("preview.mpsql", SqlPreviewLanguage, sql)` → 原生 SQL FileType
- **⚠️ 高亮器需手动设置**：`EditorFactory.createEditor(doc, project)` 对内存 PSI 文件**不会自动关联高亮器**，必须手动 `SyntaxHighlighterFactory.getSyntaxHighlighter(sqlFileType, project, null)` + `LexerEditorHighlighter` 设到 `EditorEx.highlighter`，否则全灰。
- **⚠️ 创建编辑器必须传入 sqlFileType**：`createEditor(doc, proj)` 2 参数版本默认 PlainText → 补全按 `editor.getFileType().language` 匹配 → 不调度 SQL 贡献者。必须 4 参数 `createEditor(doc, proj, sqlFileType, false)`。
- **三处统一**：SqlPreviewPanel、SqlInputEditor、LogToSqlPanel 均已切换
- **补全注册**：plugin.xml `<completion.contributor language="SQL">`（见根因 3，**不能**用无 language 全语言注册）

## 补全守卫机制（最终方案）

`WeakHashMap<Document, SqlCompletionContext>` 三级回退：
1. `parameters.editor.getUserData(SqlCompletionContext.KEY)` — 编辑器实例未变命中
2. `position.containingFile.getUserData(SqlCompletionContext.KEY)` — PSI 未重建命中
3. `documentContexts[position.containingFile.viewProvider.document]` — commitDocument 重建兜底（另加 `documentContexts[parameters.editor.document]` 直查）

**注册时机**（两处，缺一不可）：`createEditor` 首次写入 + `refreshCompletionContext` 新预览刷新（同一文档跨预览复用，旧上下文过期）。

## M34 补全修复最终根因链（2026-08-26 三轮排查确认，提交 c5e8e5e/74b32ce/535e8ed）

M34 后补全完全不触发，完整根因有 **3 层**，逐层修复：

**根因 1（no PSI file）— `PsiFileFactory.createFileFromText` 的 in-memory PSI 不被 `PsiDocumentManager` 追踪**：
- `CodeCompletionHandlerBase.invokeCompletion` 内部 `PsiDocumentManager.getPsiFile(document)` 返回 null → `AssertionError: no PSI file: null` → 补全处理器直接崩溃，contributor 从不被调度（连关键字都没有）
- 实证（诊断测试 round-trip）：`PsiFileFactory` 方式 `getDocument(psi)`=null、`getPsiFile(doc)`=null；`LightVirtualFile`/真实临时文件方式 round-trip=同文件 ✅
- 修复：真实临时文件（`File.createTempFile("mp-sql-preview-", ".sql")` + `LocalFileSystem.refreshAndFindFileByIoFile` + `PsiManager.findFile`），VFS 文件被 PsiDocumentManager 追踪

**根因 2（EDT SlowOperations 硬抛）— `PsiManager.findFile(.sql)` 触发 SQL 方言替换**：
- 用户日志 SEVERE："Slow operations are prohibited on EDT"，堆栈 `SqlLanguageSubstitutor.getLanguage → ProjectFileIndex.isInContent`，入口 `PsiManagerImpl.findFile`（在 `createEditor`，EDT showResult 流程）
- `SqlLanguageSubstitutor` 对 .sql 文件做方言映射 → 查 `ProjectFileIndex`（慢操作）→ 2024.3 平台禁止 EDT 执行 → 硬抛 SEVERE 递归堆栈
- 修复：临时文件创建 + `PsiManager.findFile` **整体包 `ReadAction.compute`**（内部允许 slow operations）——`createPreviewFileForTest`（createFileFromText 包 ReadAction）一直无此问题，对比印证

**根因 3（全语言注册不被调度）— `<completion.contributor>` 无 language 属性**：
- M34 把 `language="SQL"` 改成无 language（全语言）→ `CompletionContributor.forLanguage()` **不返回**无 language 贡献者 → `addCompletions` 从不被调用
- **实证**（System.err 诊断，unit test mode 可捕获；LOG.info 测试看得到但易漏）：弹窗 227 关键字全是**平台自带**（非插件），`addCompletions` 从未执行
- 权威证据：DatabaseTools 插件 jar 的 plugin.xml（`unzip -p database-plugin.jar META-INF/plugin.xml`）→ `<fileType name="SQL" extensions="..." language="SQL" implementationClass="com.intellij.sql.SqlFileType">`，SQL 语言 id = "SQL"（com.intellij.sql.SqlLanguage）
- 修复：恢复 `language="SQL"` 注册
- **注意**：早期误判的 H4（plugin.xml 注册断裂）结论正确但方向被测试夹具带偏过一轮，见下

**附带陷阱（本会话踩过）**：
- **Kotlin 默认参数不生成 JVM 单参重载**：类构造加参数后（如 `sections: Set = SECTION_ALL`），既有 `Class(单参)` 调用在**重编译后**仍引用旧单参构造 → `NoSuchMethodError`。加 `@JvmOverloads` 生成单参重载（设置页重构同样问题，javap 实证测试字节码引 `(LPluginSettings;)V`）
- **gradle `clean` 未真正清空 classes**：配置缓存（configuration cache）作用下 clean 可能 10s 秒过、classes 残留 → 主类/测试类二进制不一致 → 大面积 `NoSuchMethodError` **假象**（72 失败）。强制 `--rerun-tasks` 重编译（2m56s）后全绿。遇到 NoSuchMethodError 先看 classes 是否真重编译

## 测试夹具差异（BasePlatformTestCase，无 DatabaseTools 插件）

- `getStdFileType("SQL")` → **PLAIN_TEXT**（"SQL" 非标准名）→ 编辑器 PSI 语言 TEXT
- `findFileTypeByName("SQL")` → 返回名为 "SQL" 的 file type 但 **`language=null`**（非 LanguageFileType 或未初始化）
- `Language.findLanguageByID("SQL")` → **null**
- 结论：**测试环境无法验证 SQL 语言补全**。守卫应检查 `Language.findLanguageByID("SQL") == null → skip`（不能用 `findFileTypeByName("SQL")`，它在测试环境非 null 不 skip 导致真跑且 FAIL）。真实 SQL 补全断言需 runIde 沙盒验证

## 删除/保留组件

- 删除：`SqlPreviewLanguage`、`SqlPreviewFileType`、`SqlParserDefinition`、`SqlPsiFile`、`SqlSyntaxHighlighter`、`DiffHighlighter`
- 保留：`SqlLexer` / `SqlTokenTypes` / `SqlHighlightColors`（Formatter/HTML 渲染依赖）、`SqlFormatter`

## Community 版兜底

`getStdFileType("SQL")` 在 Community 版（无 Database 插件）返回 null → 回落 `PlainTextFileType.INSTANCE`，编辑器可创建但无高亮/补全。

## 词选择粘连缺陷（2026-09-03 发现并修复）

**现象**：预览编辑器中双击 `CASE` 关键字，选中范围包含 `CASE` 及后随字段名（如 `CASE use_status` 整体被选中），而非仅选中 `CASE`。

**真实根因（2026-09-03 二次排查确认）**：`SqlFormatter.format()` 和 `formatClauseLine()` 的"其余关键字"分支（处理 CASE/WHEN/THEN/ELSE/END/NOT/IS/NULL/IN/LIKE 等非 JOIN/NEW_LINE/AND-OR 关键字）**缺失 `atLineStart = false`**。`atLineStart` 初始为 `true`，`flushSpace()` 检查 `!atLineStart` 决定是否加空格。首个关键字处理后 `atLineStart` 仍为 `true` → 下一个标识符前不加空格 → 输出 `CASEuse_status`（无空格）→ 编辑器将整个字符串作为单个词选中。

**修复**（提交 8ad344c）：两处 `atLineStart = false`：
- `format()` 方法"其余关键字"分支 L122
- `formatClauseLine()` 方法"其余关键字"分支 L284

**附带改动**：新建 `SqlSyntaxHighlighter` 包装自研 `SqlLexer` 替换平台原生高亮器（提交 a5b707a），词选择边界与高亮 token 完全一致 + 不依赖 Database 插件。31 个单测覆盖所有关键字上下文。

**影响范围**：所有"其余关键字"开头的 SQL 均受益：`CASE use_status WHEN ...`、`WHEN 1 THEN ...`、`ELSE 'x'`、`END AS name`、`NOT NULL`、`IS NULL`、`IN (...)`、`LIKE '%x%'` 等。

## 调试日志（保留）

- `[SqlCompletion] registerContext / addCompletions triggered / resolveContext` — 补全链路诊断
- 排查流程：无 addCompletions 日志 → 贡献者未被调度（查 language 注册/PSI 追踪/慢操作）；有 addCompletions 但无字段 → resolveContext 三级回退结果（editorUserData/docMap）

**Why:** 原生 SQL 高亮质量远超自研（方言感知、层级着色、字符串转色），且减少维护成本。
**How to apply:** 预览编辑器 PSI 必须被 PsiDocumentManager 追踪（真实临时文件）、`PsiManager.findFile` 必须包 ReadAction（EDT 慢操作）、补全注册必须 language="SQL"、类构造加参数必须 @JvmOverloads（Kotlin 默认参数无 JVM 重载）。