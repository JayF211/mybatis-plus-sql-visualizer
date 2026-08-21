# verifyPlugin 废弃 API 修复开发计划

> 创建日期：2026-08-21
> 状态：**计划中**
> 优先级：**P1**（Marketplace 审核可能阻断，scheduled-for-removal 必须修复）

## verifyPlugin 结果摘要

| 版本 | 结果 | Scheduled-removal | Deprecated | Experimental |
|------|------|-------------------|------------|--------------|
| 243 (2024.3) | Compatible | 1 | 3 | 8 |
| 262 (2026.2) | Compatible | 1 | 23 | 8 |

Experimental（RestartDialogImpl）暂不修复——功能正常且替换 API 不稳定。

---

## P0：Scheduled for Removal（必须修复，否则未来版本不兼容）

### 1. `PsiType.VOID` → `PsiTypes.voidType()`

| 项目 | 内容 |
|------|------|
| 文件 | `psi/jpa/DerivedMethodLocator.kt` |
| 位置 | `resolveEntityFromReturnType(PsiMethod)` |
| 当前 | `PsiType.VOID` |
| 替换 | `PsiTypes.voidType()`（com.intellij.psi.PsiTypes 工具类） |
| 影响 | 1 处 |

---

## P1：Deprecated API（262 新增 19 处，批量修复）

### 2. `ReadAction.compute(ThrowableComputable)` → `ReadAction.compute(Computable)`（16 处）

**根因**：Kotlin lambda 中调用受检异常方法时，编译器自动选择 `ThrowableComputable` 重载。修复方式：用 `try-catch` 包裹受检异常，或改用 `ReadAction.run { }` + 内部 try-catch。

| # | 文件 | 方法 |
|---|------|------|
| 1 | `actions/ConvertSqlToJavaAction.kt` | `actionPerformed` |
| 2 | `actions/ConvertToSqlAction.kt` | `actionPerformed` |
| 3 | `actions/ConvertToSqlAction.kt` | `showAnnotationCandidates` |
| 4 | `actions/ConvertToSqlAction.kt` | `previewXmlStatement` |
| 5 | `actions/ConvertToSqlAction.kt` | `previewXmlSelection` |
| 6 | `actions/ConvertToSqlAction.kt` | `showAnnotationSelectionCandidates` |
| 7 | `log/SqlToJavaPanel.kt` | `CallerClassByNameModel.getFullName` |
| 8 | `log/SqlToJavaPanel.kt` | `CallerClassByNameModel.getElementName` |
| 9 | `log/SqlToJavaPanel.kt` | `convert` |
| 10 | `log/SqlToJavaPanel.kt` | `render` |
| 11 | `log/SqlToJavaPanel.kt` | `resolveSubEntities` |
| 12 | `log/SqlToJavaPanel.kt` | `pickPsiClassForTest` |
| 13 | `log/SqlToJavaPanel.kt` | `convertBatch` |
| 14 | `ui/EntitySelectionDialog.kt` | `refreshRow` |
| 15 | `ui/SqlPreviewPanel.kt` | `createEditor` |
| 16 | `ui/JavaCodeFormatter.kt` | `format` |
| 17 | `ui/SqlPreviewService.kt` | `previewAsync` |

**修复模式**（Kotlin）：

```kotlin
// ❌ 旧（ThrowableComputable，deprecated）
ReadAction.compute {
    someThrowingCode() // 抛出 IOException 等
}

// ✅ 新（Computable，非 deprecated）
ReadAction.compute {
    try {
        someThrowingCode()
    } catch (e: IOException) {
        throw RuntimeException(e)
    }
}

// 或者如果不需要返回值
ReadAction.run {
    try {
        someThrowingCode()
    } catch (e: IOException) {
        throw RuntimeException(e)
    }
}
```

### 3. `DaemonCodeAnalyzer.restart(PsiFile)` → 替代方案（1 处）

| 项目 | 内容 |
|------|------|
| 文件 | `config/PluginSettingsConfigurable.kt` |
| 位置 | `refreshGutterIcons()` |
| 当前 | `DaemonCodeAnalyzer.getInstance(project).restart(psiFile)` |
| 替代 | 需 javap 实证：`DaemonCodeAnalyzer.restart(PsiFile)` 在新版可能移除文件参数或改签名 |

### 4. `JBHtmlEditorKit.<init>()` → 替代构造（1 处）

| 项目 | 内容 |
|------|------|
| 文件 | `log/HelpDialog.kt` |
| 位置 | `HelpDialog.<init>(Project)` |
| 当前 | `JBHtmlEditorKit()` |
| 替代 | 需 javap 实证：可能有 `JBHtmlEditorKit(false)` 参数或工厂方法 |

### 5. `TextAttributesKey.createTextAttributesKey(String, TextAttributes)` → 替代（1 处）

| 项目 | 内容 |
|------|------|
| 文件 | `ui/highlight/SqlHighlightColors.kt` |
| 位置 | `key(String, Color, Color, boolean)` |
| 当前 | `TextAttributesKey.createTextAttributesKey(name, TextAttributes(...))` |
| 替代 | 可能只需 `TextAttributesKey.createTextAttributesKey(name)` + 全局属性注册 |

### 6. `Notification.setListener(NotificationListener)` → 替代（1 处）

| 项目 | 内容 |
|------|------|
| 文件 | `update/StartupUpdateCheck.kt` |
| 位置 | `showAvailable(Project, UpdateManifest)` |
| 当前 | `notification.setListener(listener)` |
| 替代 | 可能用 `NotificationBalloon` 或 `Balloon` 构建器替代 |

### 7. `Document.addDocumentListener(DocumentListener)` → `addDocumentListener(listener, Disposable)`（1 处）

| 项目 | 内容 |
|------|------|
| 文件 | `ui/ParamImportDialog.kt` |
| 位置 | `createCenterPanel()` |
| 当前 | `document.addDocumentListener(listener)` |
| 替代 | `document.addDocumentListener(listener, disposable)` — 自动随 Disposable 释放 |

---

## 文件清单

### 修改文件（13 个）

| 文件 | 改动数 | 内容 |
|------|--------|------|
| `psi/jpa/DerivedMethodLocator.kt` | 1 | P0: PsiType.VOID → PsiTypes.voidType() |
| `actions/ConvertSqlToJavaAction.kt` | 1 | P1: ReadAction.compute 修复 |
| `actions/ConvertToSqlAction.kt` | 4 | P1: ReadAction.compute 修复 |
| `log/SqlToJavaPanel.kt` | 7 | P1: ReadAction.compute 修复 |
| `ui/EntitySelectionDialog.kt` | 1 | P1: ReadAction.compute 修复 |
| `ui/SqlPreviewPanel.kt` | 1 | P1: ReadAction.compute 修复 |
| `ui/JavaCodeFormatter.kt` | 1 | P1: ReadAction.compute 修复 |
| `ui/SqlPreviewService.kt` | 1 | P1: ReadAction.compute 修复 |
| `config/PluginSettingsConfigurable.kt` | 1 | P1: DaemonCodeAnalyzer.restart 替代 |
| `log/HelpDialog.kt` | 1 | P1: JBHtmlEditorKit 替代 |
| `ui/highlight/SqlHighlightColors.kt` | 1 | P1: TextAttributesKey.createTextAttributesKey 替代 |
| `update/StartupUpdateCheck.kt` | 1 | P1: Notification.setListener 替代 |
| `ui/ParamImportDialog.kt` | 1 | P1: Document.addDocumentListener 替代 |

### 不修改（观察）

| 文件 | 原因 |
|------|------|
| `config/UpdateSection.kt` | Experimental API (RestartDialogImpl)，功能正常 |
| `update/StartupUpdateCheck.kt` | Experimental API (RestartDialogImpl)，同上 |

---

## 修复策略

### 自动修复（模式明确，可批量）

**ReadAction.compute(ThrowableComputable) → Computable**（17 处）：
- 识别 `ReadAction.compute { ... }` 中 lambda 内抛出受检异常的调用
- 用 `try-catch` 包裹受检异常，转为 `RuntimeException` 或就地处理
- 如果 lambda 不抛异常但编译器仍选错，显式声明返回类型

**Document.addDocumentListener → addDocumentListener(listener, disposable)**（1 处）：
- 找到 ParamImportDialog 的 disposable 属性传入即可

### 需要 javap 实证（替代 API 不确定）

| 项 | 实证方法 |
|----|---------|
| DaemonCodeAnalyzer.restart | javap 262 jar 看是否有无参/无文件参数版本 |
| JBHtmlEditorKit | javap 262 jar 看构造函数签名和工厂方法 |
| TextAttributesKey.createTextAttributesKey | javap 262 jar 看可用重载 |
| Notification.setListener | javap 262 jar 看替代方法 |

---

## 工作量估算

| 改动 | 预估 |
|------|------|
| P0: PsiType.VOID | 0.5h |
| ReadAction.compute 17 处 | 3h |
| 其他 deprecated 5 处（需 javap 实证） | 3h |
| 编译 + 测试验证 | 1h |
| verifyPlugin 复测 | 6min |
| **合计** | **~8h** |

---

## 验收标准

- [ ] P0：`PsiType.VOID` → `PsiTypes.voidType()`，编译通过
- [ ] P1：`ReadAction.compute(ThrowableComputable)` 17 处全部替换，编译通过
- [ ] P1：`DaemonCodeAnalyzer.restart` 替代完成
- [ ] P1：`JBHtmlEditorKit` 替代完成
- [ ] P1：`TextAttributesKey.createTextAttributesKey` 替代完成
- [ ] P1：`Notification.setListener` 替代完成
- [ ] P1：`Document.addDocumentListener` 替代完成
- [ ] 全量测试 `clean test` 通过
- [ ] `verifyPlugin -PverifyIdes=minimal` 结果：0 scheduled-removal, 0 deprecated（或仅剩无法替换的 experimental）
