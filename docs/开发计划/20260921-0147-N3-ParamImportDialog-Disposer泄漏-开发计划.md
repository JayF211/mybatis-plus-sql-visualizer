# N3 `ParamImportDialog` Disposer 泄漏 — 开发计划

> 来源：2026-09-20 真机验收发现、2026-09-21 复现（[优雅退出预置验证与 N3 复现](../验收文档/20260921-沙盒优雅退出预置验证与N3复现.md)）；2026-09-21 立项并实施。
> 相邻事项：沙盒优雅退出（本项复现前提）已由 `confirmExit=false` 预置解决，见 [AB 报告附录](../验收文档/20260920-IDE启动开销AB实测报告.md)。

## 一、现象与影响

- **现象**：优雅退出 IDE 时平台 `ObjectTree` 报
  `Memory leak detected: 'cn.song.mybatisplus.visualizer.ui.ParamImportDialog@…' … was registered in Disposer as a child of 'ROOT_DISPOSABLE' but wasn't disposed.`
- **量化**：打开该对话框 **3 次 → 退出时报 3 个实例泄漏**，实例数与打开次数严格相等。
- **影响**：退出时日志噪音 + 平台泄漏告警；对话框对象与其监听器在会话期间累积（不随关闭释放）。
- **性质**：既有缺陷，非本轮引入（blame 落在 08-20/21）。**长期不可见的原因**：只在优雅退出时暴露，而此前所有沙盒运行都是强杀（退出路径被「确认退出」弹窗卡死，见上）。

## 二、根因（平台源码级）

泄漏对象是**对话框自身**，注册父节点是 `ROOT_DISPOSABLE`，注册栈指向 `createCenterPanel` 里挂文档监听的那一行。

平台源码（243 sources jar，`com/intellij/openapi/ui/DialogWrapper.java`）：

- `myDisposable`（`:157`）是一个匿名 `Disposable`，其 `dispose()`（`:164`）回调 `DialogWrapper.this.dispose()`；
- `close(exitCode, isOk, …)`（`:495-498`）执行的正是 `Disposer.dispose(myDisposable)`；
- `getDisposable()`（`:2237`）返回它，`disposeIfNeeded()`（`:2245`）= `Disposer.dispose(getDisposable())`。

而原代码把监听器挂在 **`this`**（`DialogWrapper` 作为 `Disposable` 的**另一个**节点）上：

```kotlin
editor?.document?.addDocumentListener(listener, this)   // ← 父节点错
```

`this` 这个节点**从未被登记进 Disposer 树**，于是以 `ROOT_DISPOSABLE` 子节点身份留存；平台 `close()` 处置的是 `myDisposable`，管不到它 → 应用退出时被泄漏检查报出。

## 三、修法

父节点由 `this` 改为 **`disposable`**（= `DialogWrapper.getDisposable()`），即平台 `close()` 真正处置的那个节点：

```kotlin
document.addDocumentListener(listener, disposable)
```

同时把挂监听抽成 `internal fun attachPlaceholderListener(document, onChanged = ::updatePlaceholderVisibility)`——**为了可测**（见下）。

**取舍与边界**：只动监听器的父节点，不改监听逻辑与占位提示行为；不引入额外 dispose 调用（对话框的处置仍由平台 `close()` 负责）。

## 四、测试（含变异实验）

新增 `ParamImportDialogTest#test文档监听随对话框disposable处置_处置后不再响应`：

- **判据用外部文档 + 注入计数器**——直接观察对话框自己的占位标签**没有判别力**：`dispose()` 会把 `editor` 置 null，`isNullOrBlank()` 恒真，断言会恒真。
- 用例：挂监听 → 改文档（**正对照**：必须触发，防「压根没挂上」的空绿）→ `Disposer.dispose(dialog.disposable)`（模拟平台 `close()` 路径）→ 再改文档 → **断言不再触发**。
- **变异实验**：父节点改回 `this` → 该用例转红，报 `expected:<1> but was:<2>`（即处置后仍在响应）；随后按 sha256 逐字还原。
- 定向：`ParamImportDialogTest` 15 例全绿。

## 五、真机验收口径

独立沙盒（**预置 `confirmExit=false` 才能拿到优雅退出**）→ 打开 demo 工程 → 预览面板「更多 → 导入参数」开 **3 次**（每次 Esc 关闭）→ 优雅退出 → 在 idea.log 里搜 `Memory leak detected`：
- `ParamImportDialog` 实例数 **0**（修复前为 3，且与打开次数相等）；
- 顺带确认仍无 `Showing dialogs in PopupStep.onChosen`（N4 修复未回归）。

## 变更记录

| 日期 | 变更 |
|------|------|
| 2026-09-21 | 立项并实施：根因（监听挂 `this` 而非平台处置的 `disposable`）、修法、判别性单测 + 变异实验、真机验收口径落定 |
