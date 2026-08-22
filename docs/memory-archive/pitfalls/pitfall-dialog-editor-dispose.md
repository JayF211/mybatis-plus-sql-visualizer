---
name: pitfall-dialog-editor-dispose
description: DialogWrapper 中使用 IntelliJ Editor 必须 dispose 释放，否则测试报 "Editor hasn't been released" 且生产环境内存泄漏
metadata:
  node_type: memory
  type: pitfall
  originSessionId: sess_1de4ef77-ee04-4b58-bdb1-60884269f820
---

**DialogWrapper 中使用 Editor 的生命周期管理**（2026-08-20，P-05.1 ParamImportDialog 实证）

### 问题

`DialogWrapper` 子类中使用 `EditorFactory.getInstance().createEditor()` 创建编辑器后：
- 测试环境：`BasePlatformTestCase` 的 `checkEditorsReleased` 检测到未释放的 Editor → 报 `CompoundRuntimeException: Editor EditorImpl[null] hasn't been released`
- 生产环境：Editor 未被释放 → 内存泄漏（Editor 持有 Document、Highlighter、PSI 引用）

### 根因

`EditorFactory.createEditor()` 创建的 Editor 不会自动释放，必须显式调用 `EditorFactory.getInstance().releaseEditor(editor)`。

### 修复

```kotlin
class ParamImportDialog(...) : DialogWrapper(...), Disposable {
    private var editor: Editor? = null

    override fun dispose() {
        editor?.let { EditorFactory.getInstance().releaseEditor(it) }
        editor = null
        super.dispose()
    }
}
```

### 测试中的释放

所有创建 Dialog 的测试必须在 `finally` 中调用 `dialog.dispose()`：

```kotlin
val dialog = ParamImportDialog(project) {}
try {
    // 测试逻辑
} finally {
    dialog.dispose()  // 必须！否则 Editor 泄漏
}
```

### 文档修改的 WriteAction

Editor 的 `document.setText()` 必须在 WriteAction 中执行：

```kotlin
// 错误：EDT 直接修改文档 → RuntimeExceptionWithAttachments: Write access is allowed inside write-action only
editor.document.setText(text)

// 正确：包裹 WriteCommandAction
WriteCommandAction.runWriteCommandAction(project) { doc.setText(text) }
```

**Why:** IntelliJ 平台线程模型要求文档修改必须在 WriteAction 中执行；Editor 是重量级对象必须显式释放。
**How to apply:** 任何 Dialog/ToolWindow 中使用 Editor 都必须实现 Disposable 并在 dispose 中 releaseEditor；测试中必须 dispose dialog。