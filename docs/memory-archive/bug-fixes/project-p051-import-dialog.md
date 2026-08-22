---
name: project-p051-import-dialog
description: P-05.1 参数导入弹窗+驼峰/下划线匹配+JSON 高亮+默认回显当前参数已交付（2026-08-20）
metadata:
  node_type: memory
  type: project
  originSessionId: sess_1de4ef77-ee04-4b58-bdb1-60884269f820
---

**P-05.1 参数导入增强**已交付（2026-08-20，全量测试全绿无回归）。计划见 `docs/开发计划/20260820-1922-导入失败弹框与驼峰匹配-开发计划.md`。

### 功能清单

1. **按钮文案**：「导入」→「导入参数」（bundle 键 `panel.param.import`）
2. **始终弹框确认**：点击导入参数始终弹框（不读取剪贴板），用户手动粘贴/编辑后导入
3. **默认回显当前参数**：弹框打开时将 `paramValues` 序列化为 JSON 预填（用户可在此基础上修改），无参数时弹空框+占位示例
4. **驼峰/下划线自动匹配**：`resolveSource` 三级 fallback（精确 → 去`#` → `camelToSnake`/`snakeToCamel` 互转）
5. **JSON 语法高亮**：自建 `JsonSyntaxHighlighter`（不依赖 JSON 插件，Community 可用），`ParamImportDialog` 使用 IntelliJ `Editor` + `LexerEditorHighlighter`
6. **弹框工具栏**：格式化按钮（JSON 美化/文本归一）+ 清除按钮（清空输入框），图标风格参考 SqlToJavaPanel

### 新增文件

- `src/main/kotlin/.../util/CaseFormatUtil.kt` — `camelToSnake` / `snakeToCamel` 互转（纯函数）
- `src/main/kotlin/.../ui/ParamImportDialog.kt` — 弹窗（顶部工具栏 + Editor JSON 高亮 + `preFill` 可选）
- `src/main/kotlin/.../ui/highlight/JsonSyntaxHighlighter.kt` — 轻量 JSON 词法扫描器 + 高亮器
- `src/test/kotlin/.../util/CaseFormatUtilTest.kt` — 8 用例
- `src/test/kotlin/.../ui/ParamImportDialogTest.kt` — 12 用例

### 修改文件

- `src/main/kotlin/.../ui/SqlPreviewPanel.kt` — `resolveSource` 三级匹配 + `importParamsFromClipboard` 默认回显 JSON + `serializeParamsToJson()` 方法
- `src/main/resources/messages/MpSqlVisualizerBundle.properties` + `_zh.properties` — 按钮文案 + 占位示例 + `format`/`clear` tooltip
- `src/test/kotlin/.../ui/SqlPreviewPanelImportTest.kt` — +8 用例（驼峰匹配/弹框态/默认回显/序列化 JSON/引号转义）

### 关键设计决策

- **Editor 替代 JBTextArea**：`ParamImportDialog` 使用 IntelliJ `Editor`（`EditorFactory.createEditor`）+ `LexerEditorHighlighter` 实现 JSON 高亮，需要 `dispose()` 释放 Editor
- **WriteAction 包裹文档修改**：`formatInput`/`clearInput`/`setTextForTest` 中所有 `document.setText()` 必须用 `WriteCommandAction.runWriteCommandAction(project)` 包裹
- **格式化引号策略**：所有值统一加引号（参数值本质都是字符串，不做数值/布尔类型推断，避免 `123456` → 123456 引号丢失）
- **默认回显**：`serializeParamsToJson()` 过滤空值参数，空参数时传 null 显示占位示例
- **测试释放**：所有创建 `ParamImportDialog` 的测试必须在 `finally` 中调用 `dialog.dispose()` 释放 Editor

### 已废弃

- `ParamImportParser.looksLikeParamFormat()` — 随剪贴板自动导入一起移除（已无生产代码调用）

**Why:** 用户反馈导入交互割裂 + 驼峰/下划线风格差异漏匹配 + 弹框应回显当前参数 + JSON 格式化引号丢失 + 需要语法高亮。
**How to apply:** 后续新增导入格式只需扩展 `ParamImportParser`；`resolveSource` 匹配策略集中在一处；弹窗使用 Editor 必须 dispose。