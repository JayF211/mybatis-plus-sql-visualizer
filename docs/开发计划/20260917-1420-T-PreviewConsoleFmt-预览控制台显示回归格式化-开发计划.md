# T-PreviewConsoleFmt 预览控制台显示回归格式化 — 开发计划

- 日期：2026-09-17 14:20
- 任务编号：T-PreviewConsoleFmt
- 来源：用户反馈（T-ExecDocSync3 真机复验通过后提出）——「预览 SQL 怎么没有默认格式化了，现在都是默认单行显示」
- 取证：真实 IDE 2024.3 的 `idea.log` + 现场代码走查（本次先取证再动手：前三轮 File Cache Conflict 的教训是别按代码推演）

---

## 一、根因（日志 + 代码双证，非推理）

三个事实拼起来就是全部：

1. **日志实证控制台文档 = exec 文件文档**：`editor=EditorImpl[file://F:/.../exec/mpsql-exec.sql]`
   —— `attachConsole` 挂在这个文件上，控制台显示的就是这个文件的内容。
2. **该文件被「预览原文」改写**：每次预览的时序（13:58 窗口重复三次，模式固定）

   ```
   T+0.00  showSql branch=createEditor: len=274     ← 面板自建编辑器拿到格式化文本（16 行）
   T+2.00  exec prewarm: cache hit, content refreshed only
   T+2.04  exec file updated via document+save       ← 预热把 PreviewResult.sql 原文写进文件（1 行 / 231）
   T+2.12  attachConsole                             ← 控制台读该文件 = 裸单行 SQL
   ```

   预热写「预览原文」是 T-ExecSlowOps（2026-09-14）的既定设计：缓存命中路径刷新文件内容，
   让 attachConsole 的初始 document 对上本条预览 SQL。
3. **挂载后没有任何重渲染**：`showSql` 已把格式化文本渲染进面板自建编辑器，但
   `onNativeEditorReady` 只做 `releaseEditor()` + 切换显示到控制台编辑器，不触发渲染；
   `showResult` 里的渲染在挂载之前完成。于是用户眼前的控制台保持裸单行，直到下一次
   参数输入/刷新才被 `showSql branch=nativeDoc` 覆盖成格式化文本（日志 13:58:05 可见该分支）。

面板侧格式化器本身无问题：以用户磁盘上那条 SQL 实测 `SqlFormatter.format` = 231 字符 → 279 字符 / 16 行。

## 二、方案

| # | 项 | 改动 |
|---|----|------|
| F1 | 挂载后回填渲染 | `prepareDefaultConsole` 的 `onConsole` 回调（`embedConsole` 之后）用 `invokeLater` 触发一次 `renderSql()`——此时 `consoleAttachedToMe()` 已成立，`showSql` 走 nativeDoc 分支把格式化文本写回控制台文档 |
| F2 | 预热不再越权改写 | `prewarmExecTempFile` 缓存命中分支：若共享控制台已挂在该 vf 上（`isConsoleAttachedToVf`）则跳过内容刷新——该文件文档就是控制台文档，预热写原文会把面板渲染的格式化文本盖掉 |

触发点为何不在 `onNativeEditorReady`：`showSql` 的 nativeDoc 分支前置条件是
`consoleAttachedToMe()`（= `embeddedConsole` 已进 `consoleHost`），而该回调在 `embedConsole`
之前，此刻不成立（首版即栽在这里，被新增用例当场抓住）。

## 三、测试

`SqlPreviewPanelTest` 新增 `testRenderRepublishesFormattedTextIntoNativeConsoleDoc`：
模拟控制台已嵌入（`embedConsoleForTest`）+ 控制台文档持裸单行原文 → 渲染后断言该文档被回填为
格式化文本。面板级 61 例全绿。

## 四、验收标准

1. 全量 `test` 绿。
2. **真机复验（用户侧）**：打开预览，控制台 SQL 区应显示格式化多行（与面板渲染一致）；
   参数改动后仍保持；执行内容不受影响（执行取编辑器所见）。

## 五、风险

| 风险 | 说明 | 缓解 |
|---|---|---|
| 回填与 DBN 自身刷新竞争 | 控制台内容由 DBN 在 attach 时写入 | 回填排在 `embedConsole` 之后（invokeLater），晚于 DBN 的初始写入 |
| 预热跳过刷新导致内容陈旧 | 跳过仅发生在控制台已挂载时 | 面板渲染链随后写回该文档；执行前 `executeOnConsole` 仍会同步编辑器文本 |
| 与 File Cache Conflict 链交互 | 回填是 document 写（非裸写） | T-ExecDocSync3 后无裸写路径；回填走 `showSql` 既有 document 写入 |

## 六、实施顺序

```
分支 t-previewconsolefmt → F1/F2 代码 → 面板守护用例 → 全量 test 门禁 → 提交合入 → 真机复验
```
