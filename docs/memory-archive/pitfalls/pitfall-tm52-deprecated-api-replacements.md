---
name: pitfall-tm52-deprecated-api-replacements
description: T-M52 废弃API替代方案全量实施——三版本字节码实证与跨版本陷阱
metadata:
  node_type: memory
  type: project
  originSessionId: sess_f46b40e3-ab2b-4662-bb21-3552516b7c42
---

**T-M52 废弃 API 替代方案全量实施（2026-08-19）**：verifyPlugin 三版本 deprecated 从 9/11/27 降至 3/4/20。

**已消除的 deprecated（有跨版本非废弃替代）**：
1. `LineMarkerInfo` 六参 → 七参构造（加 `Supplier<@Nls String> accessibleNameProvider`）— 三文件 + 3 bundle 键
2. `JavaAnnotationIndex.get()` → `getAnnotations()` — 一行改动
3. `ChooseByNameModel.getCheckBoxMnemonic()` — 删除覆写
4. `Document.addDocumentListener(单参)` → 双参 + `Disposable` 管理生命周期

**保留 Suppress 的 deprecated（无跨版本非废弃替代）**：
- `JBHtmlEditorKit()` — 三版本均废弃但均可用（boolean 版 262 已移除！）
- `TextAttributesKey.createTextAttributesKey(String, TextAttributes)` — 三版本均废弃，无等价 API
- `Notification.setListener()` — 243/262 废弃，4 参数构造器 262 也废弃
- `DaemonCodeAnalyzer.restart(PsiFile)` — 253/262 废弃，243 安全
- `ReadAction.compute(ThrowableComputable)` — 仅 262 废弃，17 处，低版本安全

**2026-08-21 T0050 补充——ReadAction.compute 正确修复方式**：
- **错误做法**：移除 `<T, Throwable>` 类型参数让编译器选 `Computable` 重载 → 编译报 `Cannot infer type for type parameter 'E'`，因为 lambda 内抛受检异常时编译器仍选 `ThrowableComputable`
- **根因**：243 平台只有 `compute(ThrowableComputable<T, E>)` 一个重载（无 `Computable` 单参版），262 才加了 `Computable` 重载并标旧版 deprecated
- **正确做法**：保持 `<T, Throwable>` 显式类型参数 + 在调用方法上加 `@Suppress("DEPRECATION")` + 注释说明仅 262 废弃低版本安全

**关键字节码实证（三版本 jar 交叉核实）**：
1. `JBHtmlEditorKit(boolean)` 在 262 **已移除**（verifyPlugin 报 unresolved constructor），243/253 标 deprecated
2. `DaemonCodeAnalyzer.restart(PsiFile, Object)` 仅存在于 253+，243 无此重载
3. `NotificationGroup.createNotification(4参数)` 243 非废弃，262 废弃 → 无法同时满足三版本
4. `ReadAction.compute(ThrowableComputable)` 仅 262 废弃，243/253 安全

**教训**：verifyPlugin 报 deprecated ≠ 必须修复。当 API 在所有版本的非废弃替代方案互相矛盾时（A 版本 X 废弃 Y 不废弃，B 版本 Y 废弃 X 不废弃），选择最广泛可用的 API + `@Suppress("DEPRECATION")` 是正确策略。
