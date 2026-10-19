---
name: project-sqltogjava-classpicker-edt-psi-read
description: SQL→Java 类选择器（ClassPickerField）EDT 无 ReadAction 直读 PSI 硬抛——已修复（2026-09-03，提交 7264828，dev 分支）
metadata:
  node_type: memory
  type: project
  originSessionId: sess_90bb8d08-18f7-42d6-9553-2da553ad78f4
---

SQL→Java 面板类选择器（SqlToJavaPanel.kt 的 `ClassPickerField`）点击即抛 `RuntimeExceptionWithAttachments: Read access is allowed from inside read-action only`——**已修复**（2026-09-03，提交 `7264828`，dev 分支，dev worktree `../mybatis-plus-sql-visualizer-dev`）。**已合入 master**（合并提交 `0413d6a`，2026-09-03）。

## 堆栈与根因（EDT 事件链直读 PSI）

```
mouseClicked (SqlToJavaPanel.kt:700)        ← AWT-EventQueue-0（EDT）
└─ pickClass() (:465)
   └─ detectCurrentEditorClass() (:487)
      └─ psiFile.classes (:492)             ← PsiJavaFileBaseImpl.getClasses → withGreenStubOrAst
         └─ assertReadAccessAllowed ✗ 硬抛
```

`detectCurrentEditorClass()` 方法注释写明「须在 ReadAction 内调用（PSI 访问）」，但唯一调用方 `pickClass()` 没有包装。262 平台 EDT 回调读 PSI 硬抛的既有陷阱实例（见全局 [[pitfall-edt-psi-read-in-callbacks]]）。

## 修复内容（3 处 ReadAction 收口）

1. **`pickClass()`（:465）**：`callerModelConcrete.currentEditorClass = ReadAction.compute<PsiClass?, Throwable> { detectCurrentEditorClass() }`。
2. **`pickPsiClassForTest()`（:575）**：把「调用类校验 + name/qualifiedName/icon 读取」合并进**单次** ReadAction，返回 `Pair<拒绝原因?, Triple<name, qualifiedName, icon>>`（EDT 外零 PSI 访问）。原本 `validateCallerClass()` 在 ReadAction 之前直读 `isInterface/hasModifierProperty`。
3. **`buildSpecialTooltip()`（:506）**：编辑器类上下文读取（`superTypes`/泛型参数）包 ReadAction。

用 `@Suppress("DEPRECATION")` + `ReadAction.compute(ThrowableComputable)` 重载（262 标 deprecated，243 编译目标仅此重载可用）。T-Mxx 占位符统一落位 T-CallerPicker。

## 编译踩坑：内嵌类构造被 Kotlin 编译器禁止

初版想把 pickPsiClassForTest 的读取结果放进一个 `private data class CallerPickOutcome(...)`（作为 `ClassPickerField` 的嵌套类），编译器报 **`'Class' is prohibited here`**（SqlToJavaPanel.kt:558）——`ClassPickerField` 是 `internal inner class`，其内部不允许再声明 `class`/`data class`。

**解法**：改用 `Pair<String?, Triple<String, String?, Icon?>?>` 返回多层嵌套元组，绕过嵌套类声明。

**Why:** 262 平台 EDT 事件回调读 PSI（getClasses→stub 树）触发 assertReadAccessAllowed 硬抛；方法注释声明约束不算数，调用处必须包 ReadAction。
**How to apply:** 修 SqlToJavaPanel.kt 的 ClassPickerField 时对照上述三处；同类新增代码（弹窗回调内读 PSI 或调用标注"须在 ReadAction 内"的方法）一律先检查调用链是否有 ReadAction/compute 包装。需要在一个 `inner class` 内聚合多字段返回值时，用 Pair/Triple 嵌套而非声明嵌套类（会触 `'Class' is prohibited here`）。