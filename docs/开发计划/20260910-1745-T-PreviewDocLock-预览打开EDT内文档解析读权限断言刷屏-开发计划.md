# T-PreviewDocLock：预览打开时 EDT 内文档解析踩 262 读权限硬断言刷屏 开发计划

> **创建日期**：2026-09-10
> **分支**：master
> **优先级**：P1（功能未断但每次打开预览向 idea.log 刷 4 条 SEVERE 内部错误，且被平台归因「Plugin to blame: SQL Visualizer」并上报 FUS；新版平台跑测试会直接变红）
> **预估工时**：0.5h 代码 + 全量回归
> **来源**：用户真机报错堆栈（IDE 2026.2.2 / IU-262.10315.125，插件 2026.9.9）——`RuntimeExceptionWithAttachments: Read access is allowed from inside read-action only`，栈顶落 `SqlPreviewPanel.createEditor(SqlPreviewPanel.kt:1538)`
> **实施状态**：已实施（单点修复 + 文档同步），全量 3252/0/0/6 绿；262 真机日志验收见 §三 第 3 步

---

## 变更记录

| 日期 | 变更 |
|------|------|
| 2026-09-10 | 立项并实施：`SqlPreviewPanel.createEditor` 的 `PsiDocumentManager.getDocument(psi)` 包 `ReadAction.compute`（同 T-M42 处置范式），消除 262 平台新增的读权限硬断言；§1.3 全库排雷表落档；全量 3252/0/0/6 绿 |

---

## 一、问题与根因

### 1.1 现象

用户在 2026.2.2 真机（IU-262.10315.125）双击 SQL 日志条目打开预览，IDE 记录内部错误：

```
com.intellij.openapi.diagnostic.RuntimeExceptionWithAttachments:
Read access is allowed from inside read-action only (see Application.runReadAction());
If you access or modify model on EDT consider wrapping your code in WriteIntentReadAction or ReadAction
  at ThreadingAssertions.softAssertReadAccess
  at FileDocumentManagerBase.getDocument
  at FileDocumentManager.getDocument
  at PsiDocumentManagerBase.getDocument
  at SqlPreviewPanel.createEditor(SqlPreviewPanel.kt:1538)   ← 业务帧
  at showSql(1496) → renderSql(1178) → showResult(893)
  at SqlPreviewDialog.<init>(SqlPreviewDialog.kt:33)
  at SqlPreviewService.showResult → SqlLogPanel.openPreview → mouseClicked（EDT 鼠标事件）
```

本地 `%APPDATA%\JetBrains\IntelliJIdea2026.2\log\idea.log` 与 `idea.1.log` 实证：**每一次打开预览（日志双击）固定刷 4 条**，每条都带 `Plugin to blame: SQL Visualizer version: 2026.9.9`。四条栈都源自同一个调用点（`createEditor:1538` 的一次 `PsiDocumentManager.getDocument(psi)`），只是踩的断言位置不同：

| # | 栈中平台帧（原文行号） | 触发的断言 |
|---|----------------------|-----------|
| 1 | `PsiDocumentManagerBase.getDocument(298)` → `FileDocumentManager.getDocument(63)` | `EditorLockFreeTyping.assertReadAccess(file)` |
| 2 | `PsiDocumentManagerBase.getDocument(298)` → `FileDocumentManager.getDocument(65)` → `FileDocumentManagerBase.getDocument(54)` | `ThreadingAssertions.softAssertReadAccess()` |
| 3 | `PsiDocumentManagerBase.getDocument(301)` → `PsiFileImpl.getTextLength(418)` → `AbstractFileViewProvider.getContents(164)` → `VirtualFileContent.getText(471)` → `AbstractFileViewProvider.getDocument(183)` → `FileDocumentManager.getDocument(63)` | 同上（EditorLockFreeTyping 路径） |
| 4 | 同 #3 但末帧为 `FileDocumentManagerBase.getDocument(54)` | `softAssertReadAccess` |

即「vf→Document 解析」与「Document/PSI 文本长度一致性校验」两条内部路径各贡献两条。

### 1.2 根因：262 平台给 FileDocumentManager 一族加了运行期读权限硬断言，243 只有注解

三重交叉核实（官方 sources jar + 本地 jar 反编译 + 真机日志）：

1. **243（编译基准 `ideaIU-2024.3.7.1`）**：`FileDocumentManagerBase.getDocument(VirtualFile)` 只有 `@RequiresReadLock` **注解**，无运行期检查（自官方 `ideaIU-2024.3.7.1-sources.jar` 提取原文确认）；`EditorLockFreeTyping` 这个类在 243 源码里根本不存在。
2. **262（本机 IU-262.10315.125）**：`FileDocumentManagerBase.getDocument` 首行 `ThreadingAssertions.softAssertReadAccess()`（fernflower 反编译 `intellij.platform.core.impl.jar` 确认）；`FileDocumentManager.getDocument(vf, project)` 顶部还多一层 `EditorLockFreeTyping.assertReadAccess(file)`（反编译 `intellij.platform.core.jar` 确认，`@RequiresReadLock(generateAssertion = false)` 即由它显式断言）。而 `PsiDocumentManagerBase.getDocument(psiFile)` → `fileDocumentManager.getDocument(vf, project)`，所以**任何**「PSI 文件 → 文档」解析都被这条链覆盖。
3. **断言的强度**：`ThreadingAssertions.softAssertReadAccess`（反编译）→ `Logger.error(exception)` → `IdeaLogger.error`。`IdeaLogger` 记 SEVERE + 版本/JDK/OS + `Plugin to blame` 头 + `reportToFus(t)`，随后 `if (shouldRethrow(t)) rethrow`。`Logger.shouldRethrow` 仅对 `ControlFlowException`/`CancellationException` 成立，故**生产模式不抛**——功能没断（真机日志显示 `createEditor` 后续 `registerContext`、控制台重建照常执行），代价是每次打开刷 4 条内部错误并上报；而 `Logger` 的 Javadoc 明示 UsefulTestCase 模式下 error 直接抛 `AssertionError`——**平台版本一旦抬到 253/262，这套测试会直接变红**。
4. **为什么现在才暴露**：`createEditor` 这条路径自 T-PreviewFlash（7953aa2，2026-09-09）起改为「EDT 消费后台预热产物」——预热线程只产出 `(VirtualFile, PsiFile)`，**文档解析留在 EDT**（1537-1538 行）。改造前该位置在 EDT 的 `WriteIntentReadAction.compute` 之后紧接着执行，产物与锁序不同（预热竞态下 `PsiManager.findFile` 曾在 EDT 的 write-intent 区间内先建好文档缓存，后续裸 `getDocument` 命中缓存分支，绕过了 FileDocumentManager）。判定为**平台加固暴露的既有契约违约**，不是本轮新写错的逻辑。

一句话根因：**在 EDT 鼠标事件里（无读权限）裸调 `PsiDocumentManager.getDocument`，243 平台只注解不检查，262 平台改成了记 ERROR 的内部错误断言。**

### 1.3 全库排雷：同类调用点仅此一处踩线

`getDocument` 一族全库调用点及其锁上下文：

| 调用点 | 上下文 | 262 是否踩线 |
|--------|--------|:---:|
| `SqlPreviewPanel.kt:1538` `PsiDocumentManager.getDocument(psi)` | EDT 鼠标事件，无读权限 | **是（本次修复点）**，真机日志 4 条/次 |
| `DataSourceSelector.kt:371/1169/2071/2809` `FileDocumentManager.getDocument(consoleVf)` | 均在 `WriteAction.compute` 内（写权限隐含读权限） | 否 |
| `WrapperLocator.kt:217/234/245` `PsiDocumentManager.getDocument(containingFile)` | gutter/action 链，调用方在 ReadAction 内（文件内 KDoc 已声明该契约） | 否（真机日志零命中） |
| `SqlToJavaPanel.kt:491` `getPsiFile(document)` | ReadAction 内（调用方保证） | 否（262 该 API 本身无断言） |
| `SqlInputEditor.kt:69` `PsiDocumentManager.getDocument(psi)`（非物理 light PSI，`SqlToJavaPanel` 构造链） | EDT 工具窗构建 | 否（真机日志零命中；light 文件走 `PsiFileFactory` 预置引用，且下方本就有 `?: createDocument` 内存兜底） |
| `SqlPreviewPanel.kt:1544/1654` `commitDocument` | 前者 `WriteCommandAction` 内、后者 invokeLater 事件链 | 否（`commitDocument` 只断言 write-safe environment，不断言读权限） |

---

## 二、技术方案

单点修复：把 `SqlPreviewPanel.createEditor` 里的文档解析包进 `ReadAction.compute`。

```kotlin
@Suppress("DEPRECATION") // T-M52：ReadAction.compute(ThrowableComputable) 在 262 标 deprecated；243 编译目标仅此重载可用
val doc = ReadAction.compute<Document, Throwable> {
    com.intellij.psi.PsiDocumentManager.getInstance(proj).getDocument(psi)
} ?: throw IllegalStateException("临时 SQL 文件无关联文档")
```

设计取舍：

- **为什么是 `ReadAction` 而不是 `WriteIntentReadAction`**（T-PreviewWiLock 用的是后者）：T-PreviewWiLock 踩线的是同步 VFS refresh，必须在 write-safe context；此处是纯内存模型读（vf→文档解析 + 文本长度校验），不触发 refresh，无 write-safe 要求。EDT 上 `ReadAction.compute` 会把 write-intent lock check out，只要 lambda 内不做 write-safe 操作就无害——与 T-PreviewWiLock 的结论并不冲突，反而正是它记录的那条边界（「EDT 上 ReadAction 里别放同步 refresh」）。代码注释里把这层约束写明，防后人往里塞 refresh。
- **一致性优先**：同文件 `createPreviewFileForTest`（T-M42）就是用 `ReadAction.compute` 消 `PsiFileFactory.createFileFromText` 在 EDT 上的 `softAssertReadAccess` 硬抛，同一类问题同一处置；`@Suppress("DEPRECATION")` 的写法与该文件既有 20 余处一致。
- **不动预热结构**：`awaitPrewarmedTempFile` 的分工（后台建文件/refresh/findFile，EDT 消费）保持不变，本次不把文档解析也挪进预热线程——纯包装可让行为逐字节等价，改动面最小、回归风险最低。

---

## 三、实施步骤

| # | 任务 | 说明 |
|---|------|------|
| 1 | 改 `SqlPreviewPanel.createEditor` | 1537-1538 行文档解析包 `ReadAction.compute`，注释记录 262 断言证据链与「勿塞同步 refresh」约束 |
| 2 | 全量回归 | gradlew 前 `python parallel_tasks.py check`；`./gradlew test --no-build-cache --no-configuration-cache`，与基线比对 |
| 3 | 262 日志验收（人工跟进） | 真机/262 沙盒装新构建后打开预览，`grep -c "c.i.u.c.ThreadingAssertions" idea.log` 增量必须为 0（修复前每次打开 +4）。沙盒走 `dev-support` 的 262 IDE 或临时把 build.gradle.kts:64 平台换 2026.2.1 |
| 4 | 文档同步 | 本计划 §变更记录/§实施记录 + `docs/变更记录.md` 总册 + `docs/MEMORY.md` 变更记录 |

---

## 四、验收标准

- 单元测试全量绿（243 沙盒）。
- 262 真机日志：打开预览（日志双击 / gutter 图标 / 动作入口三条链都会汇到 `createEditor`）后 `ThreadingAssertions` 计数零增量，`Plugin to blame: SQL Visualizer` 零增量。
- 预览功能与修复前一致：编辑器带行号、PSI 补全可用、参数改动同步 SQL。

## 五、风险与边界

| 风险 | 评估 | 处置 |
|------|------|------|
| EDT 上多一层 ReadAction 引入卡顿 | 极低：lambda 内只解析刚创建的临时文件（KB 级），无 IO 往返 | 保持 |
| 后续有人在 lambda 内加同步 VFS refresh → 复现 T-PreviewWiLock 崩溃 | 中（同文件已有历史） | 注释显式写明约束 |
| 243 平台无此断言，单测覆盖不到 | **确定**（最低兼容版本编译/测试策略的固有盲区） | 验收靠 262 真机日志；本条已写入 §六 经验沉淀 |

## 六、经验沉淀

- **平台版本加固类缺陷是测试盲区**：测试沙盒 = 编译基准 243，而 243 只有 `@RequiresReadLock` 注解、262 才把 `FileDocumentManager.getDocument` 一族改成 ERROR 级断言。凡「EDT 上访问 PSI/Document/VFS」的代码，243 跑绿不代表 262 安全——判断依据只能是 262 反编译 + 真机日志。
- **`softAssert*` 名字会骗人**：262 的 `softAssertReadAccess` 走 `Logger.error`，虽因 `shouldRethrow` 只认控制流异常而生产不抛，但已是 SEVERE + FUS 上报 + UsefulTestCase 抛 AssertionError 的强度，不能当噪音放过。
- 关联：[[pitfall-vfs-refresh-write-intent-lock]]（EDT 上 ReadAction 与 write-intent lock 的边界）、T-M42（同文件同族先例）。

## 七、后续项（不在本次范围）

`awaitPrewarmedTempFile` 的产物是一次性的，超时（2s）即 `IllegalStateException("无法创建 SQL 预览临时文件")`，且 `createEditor` 若在未重新预热的情况下被二次调用（`releaseEditor` 后未经 `clear()`）同样会走这条失败路径 → 表现为「预览打不开」。建议后续改成 `Future` + 降级（拿不到临时文件时退回纯内存文档编辑器并记 WARN），把硬失败变成可用性降级。本次不动，仅记录。

## 八、实施记录

| 项 | 结果 |
|----|------|
| 代码改动 | `SqlPreviewPanel.kt` 一处（fix 提交 `e8b9d1d`）：1537-1538 行文档解析包 `ReadAction.compute<Document, Throwable>`，附注释记录 262 断言证据链与「lambda 内勿塞同步 VFS refresh」约束；上一行插入 `@Suppress("DEPRECATION")`（同文件既有写法） |
| 编译 | `./gradlew compileKotlin` 通过 |
| 全量回归 | `./gradlew test --no-build-cache --no-configuration-cache` BUILD SUCCESSFUL in 3m40s；解析 `build/test-results/test/*.xml` 共 263 套件、**3252/0/0/6**（用例/失败/错误/跳过），与 T-HibDualCapture 基线一致，零回归 |
| 未做 | 262 真机日志验收（§三 第 3 步）与 `SqlInputEditor.kt:69` 的防御性包装——后者真机日志零命中，按「不修未复现的路径」留档观察 |
| 证据留存 | 真机日志 `idea.log`/`idea.1.log`（IDE 2026.2.2 IU-262.10315.125）四条块栈；243 sources jar 与 262 反编译对照（`FileDocumentManagerBase`、`FileDocumentManager`、`EditorLockFreeTyping`、`ThreadingAssertions`、`IdeaLogger`、`Logger.shouldRethrow`） |
