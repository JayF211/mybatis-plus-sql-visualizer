# T-PreviewDocLock2：EDT 读权限断言全库排雷 + 原生控制台挂载免包装 + 预览临时文件降级 开发计划

> **创建日期**：2026-09-10
> **分支**：master（HEAD `96a1dcb`，工作树干净）
> **优先级**：P1（每发一版在 262 真机上持续刷 SEVERE「Plugin to blame: SQL Visualizer」并上报 FUS；平台一抬版跑测试即红）
> **预估工时**：F1 单点 0.5h + F2/F3 设计 1~1.5h + 全量回归
> **来源**：T-PreviewDocLock（1ef8bf6）交付后用户要求「分析整个项目是否存在类似问题，一起修复，含先前记录的后续项」
> **实施状态**：已实施（F1 单点 + F2/F3 设计落档），全量回归与 262 真机日志验收见 §八

---

## 变更记录

| 日期 | 变更 |
|------|------|
| 2026-09-10 | 立项并实施：全库读权限断言面排雷（§一 排雷表 + 262 反编译证据链 + 真机日志交叉验证），F1 修 `DataSourceSelector.prepareInConsole` EDT 裸调 `attachConsole`/`console.getDocument`，F2 落档三选案（暂不落地，待用户拍板），F3 落档预览临时文件降级设计；后续项 T-PreviewDocLock §七 收编至此 |

---

## 一、排雷结论（现象 → 逐点定性）

### 1.1 真机日志交叉验证：三个真正的「EDT 读权限断言」热点

用脚本把 `%APPDATA%\JetBrains\IntelliJIdea2026.2\log\` 下 `idea.log`~`idea.5.log` 里全部 `Read access is allowed from inside read-action only` 块的**业务帧（第一个 `cn.song.*` 帧）**归一化统计：

| 业务帧（方法，文件，行） | 累计命中 | 定性 |
|--------------------------|:---:|------|
| `createEditor(SqlPreviewPanel.kt:1538)` | 32 | **已修复**（T-PreviewDocLock 1ef8bf6） |
| `prepareInConsole(DataSourceSelector.kt:1109)` | 16 | **本次 F1 修复** |
| `prepareInConsole(DataSourceSelector.kt:1116)` | 4 | **本次 F1 修复**（同函数另一句） |
| 其余（`idea.2.log` 内 54 条 ReadAccessSoftAssert，业务帧为 INFO 级 `SqlCompletionContributor` 日志，非断言源） | — | 假阳性（见 1.3） |

其余断言类型（`write thread only` / `write action only` / `must not read action` / `EDT only`）全库日志 **零命中插件代码**——`idea.2.log` 里 33 条「EDT only」经脚本核实其栈内无任何 `cn.song.*` 帧（平台内部噪音，与插件无关）。

### 1.2 全库读权限断言面逐点排雷表（`getDocument`/`findFile`/`findClass`/`getStdFileType`/`getPsiFile` 一族）

对 62 处 PSI/Document/VFS/FileType 访问点逐一核对**线程上下文**，落到下表（「命中」= 真机/沙盒日志实际出现断言，或 262 反编译确认该链有运行期断言且调用方在无读权限的 EDT 上）：

| 调用点 | 上下文 | 262 断言 | 定性/处置 |
|--------|--------|:---:|-----------|
| `SqlPreviewPanel:1549` `getDocument(psi)` | EDT 鼠标事件 | softAssertReadAccess | ✅ 已修（1ef8bf6 ReadAction 包裹） |
| `DataSourceSelector:1109` `attachConsole`（平台 `LanguageConsoleImpl$Helper.getDocument` → `FileDocumentManager.getDocument`） | EDT（consumer 由 MyRunner.run 经 `ModalityUiUtil.invokeLaterIfNeeded` 回 ED） | softAssertReadAccess（2 条）+ EditorLockFreeTyping | ❌ **F1 修复** |
| `DataSourceSelector:1116` `console.getDocument` 反射（平台 `JdbcConsole.getDocument` → `FileDocumentManagerBase.getDocument:54`） | EDT（同上） | softAssertReadAccess | ❌ **F1 修复** |
| `DataSourceSelector:371/1169/2071/2809` `FileDocumentManager.getDocument` | `WriteAction.compute` 内（写权限隐含读） | 无（不触发） | ✅ 安全 |
| `DataSourceSelector:1278/1303/2119/2761/2892` `PsiManager.findFile` | `ReadAction.compute` 内 | 无（在 read action 内） | ✅ 安全 |
| `WrapperLocator:217/234/245` `getDocument(containingFile)` | gutter/action 链，调用方在 ReadAction | 无 | ✅ 安全 |
| `SqlToJavaPanel:491` `getPsiFile` | `pickClass` 的 `ReadAction.compute` 内 | 无 | ✅ 安全 |
| `SqlInputEditor:69` `getDocument(psi)`（light PSI） | EDT 工具窗构建 | 日志零命中（light 文件走 `PsiFileFactory` 预置引用 + 下方 `?: createDocument` 内存兜底） | ✅ 观察（不修未复现路径） |
| `ShareDialog:64` `FileDocumentManager.getFile` | EDT 弹窗 init | 零命中（getFile 族无运行期断言） | ✅ 安全 |
| `ProjectTypeDetector`（`findClass`/`findDirectory`/`findClass`/AnnotatedElementsSearch） | 设置面板 EDT 勾选回调 `triggerInterceptorInjection()` → `inject()` | `JavaPsiFacade.findClass` 262 有 `assertReadActionRequired`（参考文献） | ⚠️ **观察项**（见 1.4） |
| `SqlSourceInterceptorAutoInjector:128` `findDirectory` | EDT（`inject()` 同步，`WriteCommandAction` 仅包文件创建段） | `PsiManager.findDirectory` 需读权限 | ⚠️ **观察项**（同上，同一触发链） |
| `NativeDatabaseDiagramSupport:148` `DbPsiFacade.findElement` | `buildTabComponent` 的 `ReadAction.compute` 内 | 无 | ✅ 安全 |
| `DatabaseSchemaResolver:65` 及依赖 `ReadAction.compute` | 补全/渲染回调，包 ReadAction | 无 | ✅ 安全 |
| `getStdFileType` 系列（`LogToSqlPanel:324`/`SqlInputEditor:61`/`SqlPreviewPanel:1360/1527`） | EDT | `getStdFileType` 用 `withReadLock`（java ReadWriteLock，**非**平台 read-action），无 `softAssertReadAccess` | ✅ 安全（见 1.5） |
| `JavaPsiFacade.findClass` 系列（`WrapperSearchService`/`NamedQueryLocator`/`PagesXmlPreviewService`/`XmlEwResolver`/`XmlPreviewService`/`XmlParamTypeResolver`/`XmlSqlCompletionContextResolver`/`AnnotationSqlPreviewService:149 computeConstantExpression`） | 全部经 `SqlPreviewService.previewAsync`（pooled 线程 + `ReadAction.compute`）回调 | 无（在 read action 内） | ✅ 安全 |

### 1.3 「READ 断言 54 条」假阳性辨析

`idea.2.log` 里 `ReadAccessSoftAssert` 计数高达 54，但脚本按「业务帧」归一时，其栈内第一个 `cn.song.*` 帧是 **INFO 级 `SqlCompletionContributor.registerContext` 日志**（不是调用帧，是相邻行被 grep 误并）。真正产生断言的都是 1.1 表里的三处业务帧。结论：**其余 54−已归因 ≈ 0 条新增热点**。

### 1.4 观察项：设置页「源码捕获拦截器自动注入」EDT 上做 PSI 探测

`PluginSettingsPanel.triggerInterceptorInjection()`（`autoInjectInterceptorCheck` 复选框 `addActionListener` → EDT）→ `SqlSourceInterceptorAutoInjector.inject(project)` → `ProjectTypeDetector.detect`：
- `ModuleRootManager.getSourceRoots` → `JavaPsiFacade.findClass`（3 次：SpringBootApplication / SpringApplication / SqlSessionFactory）→ `AnnotatedElementsSearch.searchPsiClasses(...).findAll()` → `PsiManager.findDirectory` → 递归 `file.text` 读包名。
- 262 `JavaPsiFacadeImpl.findClass` 有 `assertReadActionRequired`（platform 文献），**但真机日志里这条链零命中**——原因：`inject()` 是用户勾选开关的一次性低频动作，且 `detect` 内部 `try/catch` 兜底（`catch (Exception)` 时落到 `hasSpringBootMarkers`/`inferPackageFromSourceRoot`），而 `findClass` 的读权限断言是 `assertReadAccess`（**硬抛**，非 soft），一旦命中会直接 `RuntimeException` 被 catch 吞掉、表现为「注入静默失败」。这解释了为什么日志零命中——它不是软断言刷屏，而是**潜在硬异常被吞**。
- **处置**：不修（未复现用户可见故障，且涉及 F-44-7 自动注入功能的线程模型，超出本计划「读权限断言刷屏」范围）；记入 §六 后续项，另行立项把 `detect` 的 PSI 探测移入 `ReadAction` / 后台线程。

### 1.5 `getStdFileType` 为什么安全

262 `FileTypeManagerImpl.getStdFileType(name)`（反编译 `intellij.platform.ide.impl.jar`）：
```java
public FileType getStdFileType(String name) {
  this.instantiatePendingFileTypeByName(name);
  StandardFileType std = withReadLock(() -> standardFileTypes.get(name));
  return std != null ? std.fileType : PlainTextFileType.INSTANCE;
}
private <T,E> T withReadLock(ThrowableComputable<T,E> c) {
  return ConcurrencyUtil.withLock(myPendingInitializationLock.readLock(), c);
}
```
用的是 **java 并发 `ReadWriteLock`**（`ConcurrencyUtil.withLock`），不是平台 read-action，`getStdFileType` 全程没有任何 `softAssertReadAccess`/`assertReadAccess`。所以 `SqlPreviewPanel.createEditor` / `SqlInputEditor` / `LogToSqlPanel` 等所有 `getStdFileType("SQL")` 调用（含 EDT 上的）在 262 都不会触发该断言——T-PreviewDocLock 修复时顺带包进去的 `getStdFileType` 之外的语句无需再动。

---

## 二、F1 修复：原生控制台挂载在 EDT 上裸调，包 ReadAction

**问题**：`DataSourceSelector.prepareInConsole`（`prepareDefaultConsole` 预览准备链，EDT）的两处：

```kotlin
// :1109
val console = attach.invoke(null, project, session, vf)   // 平台 DBN，内部 LanguageConsoleImpl.attach → Helper.getDocument → FileDocumentManager.getDocument → 断言
// :1116
val document = consoleClass.getMethod("getDocument").invoke(console)  // JdbcConsole.getDocument → FileDocumentManagerBase.getDocument:54 → 断言
```

**为什么在 EDT**：`Runner.run()` 建 session 后，DBN 的 `MessageBusSession.attach` → `ModalityUiUtil.invokeLaterIfNeeded` 把 `initView`（→ `getView` → `LanguageConsoleImpl` 构造）**切回 EDT**执行；`attachConsole` 直接返回的是尚未完成视图初始化的 `JdbcConsole`，真正踩断言的是它内部那段「在 EDT 上给 console 建文档」。日志栈实锤：`LanguageConsoleImpl$Helper.getDocument(LanguageConsoleImpl.kt:406)` ← `ConsoleExecutionEditor.<init>` ← `LanguageConsoleImpl.<init>` ← `DatabaseSessionViewServiceImpl...`，以及 `JdbcConsole.getDocument(JdbcConsole.java:371)` ← `Method.invoke` —— 两条都是弹窗打开这条 EDT 链上的平台内部 `FileDocumentManager.getDocument` 触点，业务入口是我们那句 reflection `attach.invoke`。

**根因**：`attachConsole` 在非读权限的 EDT 上下文被调用，其内部同步触发平台读模型访问。

**修复**：把控制台挂载这一段包进 `ReadAction.compute`（EDT 上同步语义不变，`invokeLaterIfNeeded` 内部再切 EDT 的懒装配与 read-action 不冲突）：

```kotlin
// :1109 起
val console = ReadAction.compute<Any?, Throwable> {
    attach.invoke(null, project, session, vf)
}
```

判断依据与 T-PreviewDocLock / T-M42 同源：这里做的是「触发平台读模型/PSI 构建」的**纯内存读**操作，没有同步 VFS refresh（refresh 在 `createTempPsiFile` 已由 `WriteIntentReadAction` 处理），故用 `ReadAction` 而非 `WriteIntentReadAction`；注释写明「勿把 VFS refresh 塞进 lambda」。（`:1116` 的 `console.getDocument` 已在挂载 read-action 之内，若实测仍单发可一并包入；详见 §六 验收注意。）

**注意**：`runCatching { ... }` 外层已捕获异常，`ReadAction.compute<Any?, Throwable>` 的泛型与受检异常形态与文件内既有 `ReadAction.compute` 一致（`@Suppress("DEPRECATION")` 同款）。

---

## 三、F2 设计（三选案，暂不落地）：`ProjectTypeDetector` 检测调用现有 ReadAction 语义的缺口

`SqlSourceInterceptorAutoInjector.inject()` 的 `ProjectTypeDetector.detect()` 在 EDT 上做 PSI 探测，`JavaPsiFacade.findClass` 的 `assertReadAccess` 若命中会是**硬抛**（被 catch 吞掉），属于「读权限协议违约但被异常掩埋」。

**方案 A（最小改）**：`inject()` 入口把 `detect()` 包 `ReadAction.compute`，`injectSpringBoot` 的 `WriteCommandAction` 段维持不变。
**方案 B（语义更准）**：仅 `detect()`（纯探测，只读）包 `ReadAction.compute`；`inject` 的文件创建段仍走 `WriteCommandAction`。
**方案 C（彻底）**：`triggerInterceptorInjection` 整个 `inject` 调 `previewAsync`-style 后台线程 + ReadAction，结果 `invokeLater` 回调 UI 弹窗（对齐 `SqlPreviewService.previewAsync` 既有范式）。

> 本计划不落地 F2——它是独立的功能正确性问题（静默失败 vs 刷屏），且牵涉 F-44-7 功能验收，另立项单修；此处仅落档三选案。

---

## 四、F3 后续项（原 T-PreviewDocLock §七，收编）：预览临时文件一次性产物的硬失败降级

**问题**：`SqlPreviewPanel.createEditor` 依赖 `awaitPrewarmedTempFile()`，产物一次性（取用即置 null），两类触发「预览打不开」的确定性失败：
1. 预热超时（`PREWARM_WAIT_MS=2000`）→ `IllegalStateException("无法创建 SQL 预览临时文件")`；
2. `releaseEditor` 后未经过 `clear()` 即二次 `createEditor`（`prewarmedTempFile`/`previewTempFile` 均为 null）→ 同一异常。

**设计**：把「临时文件→PSI→文档→编辑器」这条 PSI 支链做成**可降级**，失败退回到「纯内存文档 + 自研高亮 + 无 PSI 补全上下文」回退编辑器（代码里已有 `project == null` 分支的纯内存模板 `EditorFactory.createEditor(createDocument(sql))` 可复用）：

```kotlin
// 伪码
val doc: Document = runCatching {
    val (vFile, psi) = awaitPrewarmedTempFile() ?: throw IllegalStateException(...)
    previewTempFile = vFile; psiFile = psi
    @Suppress("DEPRECATION")
    ReadAction.compute { PsiDocumentManager.getInstance(proj).getDocument(psi) } ?: throw ...
    // ... 填充 + 返回 doc
}.getOrElse {
    LOG.warn("[M36] preview temp file unavailable, falling back to in-memory editor", it)
    psiFile = null
    EditorFactory.getInstance().createDocument(sql)  // 纯内存文档
}
```

- **代价**：降级态无 PSI → 无自建高亮器/无补全（回退为平台默认裸编辑）；比现行「抛异常 → 白屏/打不开」可接受（功能降级优于硬失败）。
- **不变**：`project != null` 的主路径行为逐字节不变；预热机制（后台 `createTempFile + refresh + findFile`）保留不动，只是消费端从「必得」变「尽力」。
- **范围**：仅 `createEditor` 拿到临时文件失败时的兜底；`awaitPrewarmedTempFile` / 预热线程 / `releaseEditor` 清理逻辑零改动。

> F3 是否本计划就落地，取决于是否要一并改 `SqlPreviewPanel.kt`（当前工作树干净、无并行会话占用）。见 §七 决策。

---

## 五、全库「护栏」约定（沉淀进代码规范，不产生新代码）

排雷确定了一个可复用的判断口诀，写成一条不落盘代码的约定（供后续所有 PR 自查）：

1. **`getStdFileType` / `getFileTypeByFileName` 一族永远安全**（java ReadWriteLock，非平台 read-action）；`FileDocumentManager.getFile` / `getPsiFile(document)` 一族无运行期读断言。
2. **凡 `FileDocumentManager.getDocument` / `PsiDocumentManager.getDocument(psi)` / `PsiManager.findFile` / `JavaPsiFacade.findClass` / `DbPsiFacade.findElement`，在 EDT 上要么已经在 `WriteAction`/`ReadAction`/`WriteIntentReadAction` 内，要么本调用点就是平台 DBN 这类第三方链的入口（须自己包 `ReadAction`）。**
3. **`JavaPsiFacade.findClass` 是 `assertReadAccess` 硬抛**——不是软断言，违约是 `RuntimeException` 而非日志，容易被 `catch(Exception)` 掩埋成「静默失败」，排查时优先看 PSI 探测链路。

---

## 六、验收标准

- 全量 `./gradlew test` 绿（243 沙盒；2503~3252 量级，与现有基线比对）。
- 262 真机日志：装新构建后走一遍「双击日志预览 + 打开 JOIN 图 + 触发执行」三链路，`grep -c "c.i.u.ThreadingAssertions" idea.log | tail -1` 相对修复前**零增量**（T-PreviewDocLock 修复前每次预览 +4、F1 修复前每条控制台挂载 +4）。
- F1 边界：若 `:1116` 的 `console.getDocument` 在 `:1109` 包 ReadAction 后仍单发断言（理论上已含，但平台内部可能在校验线程再取一次），则把 `:1116` 一并包 `ReadAction`（注释同步）。

## 七、风险与边界

| 风险 | 评估 | 处置 |
|------|------|------|
| F1 的 ReadAction 里三番切线程（`ModalityUiUtil.invokeLaterIfNeeded`）与 read-action 叠出死锁 | 低（read-action 不阻塞 EDT 泵；DBN 的正常挂载本身就在 EDT） | 注释说明；真机走查三链路确认控制台仍正常挂载 |
| F1 把 `attachConsole` 包进 ReadAction 后，DBN 内部若再做写操作 | 低（attachConsole 是读模型/建视图，写操作在 `WriteAction` 段独立） | 若真机出现 write-safety 断言再收紧边界 |
| F2 不落地 → `assertReadAccess` 硬抛继续被吞 | 中 | 记 §六 后续项，单独立项 |
| F3 降级态无补全/高亮，用户可见功能弱化 | 低（仅极端竞态/超时才会进降级态） | 日志 WARN 可定位；文档说明降级边界 |

## 八、后续项

1. ~~**F2 立项**~~：已实施（§九 第 2 行）。
2. ~~**F3 预览临时文件降级**~~：已实施（§九 第 2 行）。
3. 提炼全局 pitfall（候选）：`softAssert*` 生产不抛但强度=SEVERE+FUS 上报；`JavaPsiFacade.findClass` 是 `assertReadAccess` 硬抛易被 catch 吞成静默失败；`getStdFileType` 用 java ReadWriteLock 永远安全（反编译证据链已在本计划 §1.5）。

## 九、实施记录

| 项 | 结果 |
|----|------|
| 代码改动 | F1：`DataSourceSelector.prepareInConsole` 两处反射（`attachConsole` + `console.getDocument`）各包 `ReadAction.compute`，附注释记录 262 饱和栈证据链与「lambda 内勿塞同步 refresh」约束（fix 提交 3cba9f6）；F2：`ProjectTypeDetector.detect` 包 `ReadAction.compute`（探测主体拆 `detectSync` 私有方法，`inject` 的文件创建段仍 `WriteCommandAction` 与探测分离——方案 B，fix 提交）；F3：`SqlPreviewPanel.createEditor` 临时文件支链 `runCatching{...}.getOrElse{ 纯内存文档兜底 }` 降级（主路径不变、降级态无 PSI/高亮/补全，fix 提交） |
| 全量回归 | F1 轮：`./gradlew test` BUILD SUCCESSFUL in 3m56s，3252/0/0/6；F2+F3 轮：见本次补充（同基线零回归） |
| 证据留存 | 真机日志 `idea.log`~`idea.5.log` 业务帧归一化统计（createEditor 32 / prepareInConsole:1109 16 / :1116 4，其余断言类型插件帧零命中）；262 反编译 `FileTypeManagerImpl`（`getStdFileType` 用 java ReadWriteLock）/`FileDocumentManagerBase`/`IdeaLogger`/`Logger.shouldRethrow`/`ThreadingAssertions`，及真机栈 `LanguageConsoleImpl$Helper.getDocument(kt:406)`/`JdbcConsole.getDocument(Java:371)`；243 sources jar 对照 |