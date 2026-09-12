# T-PickMpjErr：SQL转Java 选类重渲染 MPJ 业务异常逃逸 EDT 修复计划

> **创建日期**：2026-09-12
> **状态**：🟢 已立项、当日实施（master）
> **分支**：master（HEAD `a0702f6`）
> **优先级**：P1（真机 red exclamation——预期业务报错以 `UnhandledException` 形式炸到平台顶层）
> **预估工时**：0.5h（单点收口 + 测试）
> **来源**：用户真机 idea.log（2026-09-12 贴出）——SQL→Java 面板经调用类选择器选定无 MPJ 能力的 `DeviceInfoMapper` 后转换多表 JOIN SQL，`SqlReverseException` 从 EDT 鼠标回调一路逃逸成 `com.intellij.openapi.diagnostic.UnhandledException`

---

## 一、问题定性

### 1.1 抛出链（真机堆栈实证）

```
ChooseByNameBase$7.onClick（mouseReleased，EDT）
→ SqlToJavaPanel$ClassPickerField$pickClass$2.elementChosen（:476）
→ ClassPickerField.setPicked（:699/704）
→ regenerateWithNewFormat（:1050）
→ render（:1104）
→ generateCode（:1170）→ SqlToJavaConverter.generateCode（:345）
→ JavaCodeGenerator.generateSelect（:113）抛 SqlReverseException(KEY_UNSUPPORTED)
→ IdeEventQueue.processException → StartupErrorReporter 红叹号
```

异常本身是**设计内的业务拒绝**（多表 JOIN 需 MPJ 依赖、调用类无 MPJ 能力，`JavaCodeGenerator.kt:113` 主动抛出），错在面板层没接住：全文件唯一的 `catch (SqlReverseException)` 在 `convert()` 的 `parse` 阶段（`SqlToJavaPanel.kt:790`），只覆盖按钮入口的解析期错误；**生成期**（选类/切形态触发的 `regenerateWithNewFormat → render`）路径零捕获。

### 1.2 波及面

`render` 三个分支共四处裸调 `generateCode`，全部在 EDT 上执行：

| # | 分支 | 位置 |
|---|---|---|
| ① | 0 候选回落 QueryWrapper | `render` when 第 0 支 |
| ② | 1 候选直接生成 | when 第 1 支 |
| ③ | 多候选、手动选择关闭取首个 | else 支前段 |
| ④ | 多候选、SqlSelectionDialog 选定回调 | else 支后段 |

同一异常还能从「形态切换/输入变化」的 `regenerateWithNewFormat`（`callerField` lambda :142/:154/:162）触发——修复收口在 `render` 内即可全覆盖（①~④ 都经 `render`）。

### 1.3 非目标

- 不改 `JavaCodeGenerator` 的抛错语义（无 MPJ 能力拒生成是正确行为）；
- 不动 T-ReverseParenWira 已收口的 attachConsole WIRA 包裹（用户日志里那 5 条 read access 硬抛来自**旧构建**，master dab3d3b 已修，装新构建即可验证，与本任务无关）。

## 二、方案

### F1 `render` 内单点收口

新增私有辅助（`generateCode` 同参 + 返回 `String?`）：

```kotlin
/** T-PickMpjErr：生成期业务拒绝（SqlReverseException，如多表 JOIN 无 MPJ 能力）转状态栏
 *  错误提示，结果区保留上次内容；返回 null = 已报错。generateCode 在 EDT 鼠标回调内同步执行，
 *  不接住会逃逸成平台 UnhandledException 红叹号（真机实证）。 */
private fun generateCodeOrReport(...): String?
```

catch 块对齐 `convert()` :790 既有口径：`status(MpSqlVisualizerBundle.message(ex.key, ex.detail), error = true)`（不带行号——`errorLine` 需要原始输入文本，`render` 无此参；解析期错误已在 :790 带行号提示，生成期错误均为结构性拒绝，无行号不损体验）。

四处调用点改为：`generateCodeOrReport` 返回非 null 才 `setResultText` + 成功状态；返回 null 时错误状态已由辅助内置，调用点直接 return（分支②③）或不做后续成功提示（分支①④）。

### F2 测试

`SqlToJavaPanelTest`（或既有 SQL转Java 面板测试类）补一用例：构造多表 JOIN 解析结果 + 无 MPJ 能力调用类，走 `render`/`regenerateWithNewFormat` 入口，断言：

1. 不抛异常（修复前此用例直接复现 UnhandledException 路径）；
2. `statusForTest()` 含 `reverse.error.unsupported` 本地化文案；
3. 结果区文本未被错误分支改写（保留上一次内容或空）。

## 三、验收

- [ ] F1 落地，`grep -n "generateCode(result" SqlToJavaPanel.kt` 零裸调残留；
- [ ] F2 新用例绿，面板既有用例零回归；
- [ ] 真机复验（人工）：同一 SQL + 无 MPJ 调用类，选类后面板状态栏出现友好错误、无红叹号。
