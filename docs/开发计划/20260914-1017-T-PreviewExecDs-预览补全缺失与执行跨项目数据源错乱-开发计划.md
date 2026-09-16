# T-PreviewExecDs：预览补全缺失与执行跨项目数据源错乱开发计划

- 立项日期：2026-09-14
- 任务性质：真机走查缺陷修复（沙盒 IU-2024.3.7.1，双项目同开场景）
- 反馈来源：用户 2026-09-14 反馈两问题；沙盒 `idea.log`（09:25~09:47）+ 平台 jar 反编译取证
- 目标：预览编辑器恢复补全与注释提示；执行链数据源弹窗严格本项目作用域；`JdbcConsole has already been disposed` 报错根除；全量测试绿 + verifyPlugin 三目标 Compatible + runIde 双项目场景真机复查

## 一、问题清单与优先级

| # | 任务号 | 现象 | 级别 | 根因状态 |
|---|--------|------|------|---------|
| 1 | T-PreviewExecDs-01 | SQL 预览：项目已配数据源，SQL 无代码补全、无注释提示 | P1 | **已实锤**（预热线程持续失败静默降级 + Temp 预览文件结构缺陷，三层叠加见 §2.1） |
| 2 | T-PreviewExecDs-02 | 点击执行 SQL：数据源弹窗显示的是另一打开项目的数据源 | P1 | **已实锤**（`cachedTempPsiFile` 跨项目泄漏，见 §2.2） |
| 3 | T-PreviewExecDs-03 | 执行 SQL：报错 `Sorry but parent: {} (class JdbcConsole) has already been disposed ... so the child: ScriptModel{...mpsql-exec.sql} will never be disposed` | P1 | **已实锤**（console 被 `getActiveConsoles` 清扫 dispose 的完整链路已还原，失效分量待一轮诊断确认，见 §2.2） |

## 二、根因分析

### 2.1 T-PreviewExecDs-01：预览无补全、无注释提示（三层叠加）

**直接层：预热线程持续失败 → 纯内存编辑器 → 补全全灭。**
- 日志实锤：09:34:40 起 8 次预览全部 `WARN [M36] preview temp file unavailable, fall back to in-memory editor`（09:35:21 / 09:37:21×3 / 09:38:31 / 09:38:44 / 09:46×2 / 09:47）。回退产物是 `EditorFactory.createDocument(sql)` 的裸文档编辑器——**无 VirtualFile、无 PsiFile**。
- 无 PSI 的直接后果：插件自研补全链（`SqlCompletionContributor`，plugin.xml 显式 language 注册）依赖 PsiFile 与 `SqlCompletionContext`（Editor userData + document→context 映射只是载体，补全动作本体仍要 PsiFile），平台 `CodeCompletionAction` 在无文件文档上直接不触发——实体/表/列补全与列注释 tailText（`SqlCompletionContext.columnComments`，M16）全部消失；Database 原生补全更是无门。
- 预热失败**零日志**：`prewarmPreviewTempFile`（SqlPreviewPanel.kt:387）`onFailure { prewarmFailed = true }`，三条失败路径（refreshAndFindFileByIoFile 两次 null / findFile null / 异常）全部静默，`awaitPrewarmedTempFile` 见 prewarmFailed 立即返回——09:34:40 后为何次次失败（两项目同开 VFS 忙？Temp 目录状态？）**无从归因**，这是本次排障最大的信息黑洞。

**结构层：预览临时文件落在系统 Temp，Database 插件天然不解析。**
- `prewarmPreviewTempFile` 用 `File.createTempFile("mp-sql-preview-", ".sql")`——系统 Temp 目录，不在任何项目内容范围。Database 插件的数据源关联、schema 补全、表/列注释提示只对项目内（或 console 挂载）文件生效。
- **同一教训 exec 路径已踩过并修过**：M36.5.12 注释明文「Temp 文件不在数据源关联范围，导致 IDE 报 Unable to resolve table……项目内文件可被 VFS 追踪、可被 Database 插件解析」，exec 文件因此迁到 `.idea/sql-visualizer/exec/`——preview 路径没有跟进。即使预热成功，用户要的「数据源驱动的补全 + 注释提示」也不可用。

**配置层：预览文件无方言映射 + 数据源模型加载晚。**
- exec 路径有 `mapDialectBeforePsi`（MySQL 预映射，M36.5.3），preview 文件没有任何方言映射 → PSI 按 Generic SQL 解析，即使文件迁进项目，原生补全也不触发。
- 数据源加载时机：09:25:09 启动即 `DataSourceModelStorageImpl - No model loaded for: jpa_demo@localhost, mp_demo@localhost, kotlin_demo@localhost`，09:28~09:34 执行全程 `runners=0`（facade 无 DbDataSource），~09:37 才出现 `runners=4`——`DatabaseSchemaResolver`（数据源列注释，M35 反射链 `DataSourceManager.getManagers` → DasModel 遍历）在数据源未加载窗口内空转，补全退化为纯实体注释。

**修复**：
- 预览临时文件迁 `<project>/.idea/sql-visualizer/preview/`，复用 `projectExecFile` 的 VFS 安全写入三态模式（document 驱动 setText+commit > vf.setBinaryContent > FileUtil 兜底），杜绝 File Cache Conflict 弹窗；文件按项目隔离，`releaseEditor` 清理路径沿用。
- 预热失败分级可观测：write / refresh null（两次）/ findPsi / 异常各阶段 warn 带上下文；refresh 重试 1→3 次带退避；`prewarmFailed` 快速降级语义保留但必落日志。
- 降级链改三级：① 项目内文件（主路径）；② `LightVirtualFile` + `PsiFileFactory.createFileFromText`（内存 PSI，补全保留——T-M42 `createPreviewFileForTest` 同机制先例，须包 ReadAction，262 平台实证先例在案）；③ 裸 document 编辑器（现兜底，仅二级也失败时）。
- 预览文件方言预映射：复用 `mapDialectBeforePsi` 骨架，dialect 从 `previewModel.dialect` 取（不硬编码 MySQL），`PsiFileFactory` 前或文件创建后 setMapping。

### 2.2 T-PreviewExecDs-02/03：跨项目数据源 + dispose 报错（同一条根因链）

**插件侧代码实锤（DataSourceSelector.kt:2842-2848）：**

```kotlin
cachedTempPsiFile?.let { (vf, psi) ->
    if (vf.isValid) {                       // ← 只校验 vf 有效，不校验 vf 归属项目
        LOG.info("[M36] exec psi cache hit (no VFS refresh): ${vf.name}")
        return vf to psi
    }
    cachedTempPsiFile = null
}
```

`cachedTempPsiFile` 是 object 单例静态 `Pair<VirtualFile, PsiFile>`，跨项目共享。跨项目时序还原（与日志逐条吻合）：

1. demo 项目先预览/执行 → 缓存 = demo 的 (vf, psi)。
2. 用户切到 easy-code-demo 预览、点执行 → `createTempPsiFile(easy-code-demo)` 写入自己的 exec 文件后，**缓存命中直接返回 demo 的 vf+psi**。
3. `getAttachDataSourceRunners(demo的psiFile, ...)` → 平台 `DbImplUtil.getAllDataSourcesForExec(psiFile)` 按 **psiFile.getProject()** 取 `DbPsiFacade.getInstance(demo).getDataSources()` → 弹窗列出 demo 项目的 4 个数据源（jpa_demo/mpj_demo/mp_demo/kotlin_demo）——用户看到的就是「另一个项目的数据源」（T-PreviewExecDs-02）。日志 `runners=4` 反复弹窗 7 次即用户反复重试。
4. 选中 `jpa_demo@localhost` → `runInDataSource`（反编译实锤）session 项目 = `dataSource.getProject()`（demo）；插件 `attachConsole.invoke(null, 当前项目, session, demo的vf)` → console 注册进**当前项目**的 console 列表。
5. `prepareAfterEditorResolved:1230` 反射 `console.getScriptModel()` → `JdbcConsole.updateScriptModel:274` → `getFile()` → PSI 解析 → `SqlLanguageSubstitutor.getSqlLanguageFromFile:85` → `JdbcConsoleProviderCore.getValidConsole` → `getActiveConsoles:511` → `ScriptingDatabaseSessionClientHolder.getActiveClients:776`：对本项目 console 列表里 `!isValid()` 的实例逐个 **`Disposer.dispose`**（反编译实锤，清扫条件 `lambda$getActiveClients$0 = !isValid()`）。
6. `isValid()` = `!isDisposed && isViewValid && isTargetValid`；`isTargetValid` = `DataSourceUtil.isDataSourceValid(DbPsiFacade.getInstance(console.project), console.getDataSource())` = **facade.findDataSource(uniqueId) != null**。当前项目 facade 只有 rbac，没有 jpa_demo → false → 新 console 在 attach 后 7ms 被清扫（日志 :508 attach → :515 FAILED）。
7. 回到 `updateScriptModel:275` 的 `Disposer.register(console, scriptModel)` → `IncorrectOperationException: parent has already been disposed` → `prepareInConsole FAILED`，平台异常长串直接透给用户（T-PreviewExecDs-03）。

**日志吻合点**：报错里 ScriptModel file 是 demo 的 `mpsql-exec.sql`（泄漏的缓存 vf）；attachConsole(:508,508)→失败(:515) 仅 7ms；同刻 easy-code-demo 的 SqlDialectPusher push-all（attachConsole 内 `updateSqlDialectMappingIfNeeded` 对外来 vf 在本项目做映射触发的 dumb 任务）。

**单项目场景的残余风险**：即使不做跨项目切换，`isValid()` 依赖 facade 状态（数据源模型异步加载/重建窗口），清扫路径同样可能命中——§2.2 的失效分量诊断（isDisposed / isViewValid / facade.findDataSource 三分量落日志）一轮真机后即可区分主次，但修复手段相同。

**修复**：
- **F1 缓存项目绑定**：cache hit 增加归属校验（`vf.path` 以当前 `project.basePath` 开头，或缓存改 project 键控 `MutableMap<Project, Pair<...>>` / 项目 userData），不匹配即弃缓存走冷路径重建；`dialectMappedVf` 静态字段同款绑定校验（同 vf 跨项目时 setMapping 打错项目的映射表）。
- **F2 isValid 预检 + 失效分量诊断**：attachConsole 后、getScriptModel 前反射 `console.isValid()`；false 时把三分量（isDisposed / isViewValid / facade.findDataSource 结果）落 warn 日志，并有界重试（如 300ms×5，等 facade 异步窗口）→ 仍 invalid 丢弃该 console 重建一次（新 session+attach）→ 再失败给可读错误。
- **F3 "already been disposed" 特判归类**：`prepareInConsole`/`prepareAfterEditorResolved` 捕获链识别 `IncorrectOperationException`（或消息含 "has already been disposed"）→ 走 F2 自愈路径，错误文案本地化（i18n 新键），不再透平台长串。
- **F4 runRunner 错项目 bug**：失败路径用 `ProjectManager.openProjects.firstOrNull()` 清 last-used/弹错误框（DataSourceSelector.kt:2741/2750）——应传当前 project 进来，多项目下清的是别人的记忆。
- **F5 弹窗来源标注**：多项目同开时 runner 显示名附项目名（`displayName` 追加 ` · <project>`，单项目不加，避免噪音），根治「哪来的数据源」困惑。
- **F6 static 单例防线**：`sharedConsole`/`prepared*`/`pendingOnConsole` 等 object 静态槽位最小改造 = 复用与消费点补项目归属校验；完整改造（迁 Project 级 Service 或按 project 键控容器 + 项目关闭清理挂 Disposable）工作量另计，视 F1~F5 修复后真机表现决定是否拆二期。

### 可测试性设计

- `createTempPsiFile` 的缓存命中判定抽纯函数 `internal fun isCacheUsable(project: Project, vf: VirtualFile): Boolean`（路径绑定 + isValid），表驱动单测零平台依赖。
- 预热降级链抽 `internal fun resolvePreviewDocumentHierarchy(): PreviewDocSource`（FILE / LIGHT_VIRTUAL_PSI / BARE_DOCUMENT 三态枚举），测试断言三级顺序与产物（二级产物必须 `psiFile != null`）。
- isValid 三分量诊断抽 `internal data class ConsoleValidity(val disposed: Boolean, val viewValid: Boolean, val dsInFacade: Boolean?)`，反射装配逻辑独立可测。

**测试**（扩展现有 `ExecTempFileFixTest` / `DataSourceSelectorIntegrationTest`，新建 `PreviewTempFileMigrationTest`）：
- 跨项目隔离：测试夹具双 project，projectA 先 createTempPsiFile → projectB 再调用，断言 projectB 拿到的 vf.path 以 projectB.basePath 开头（旧实现必炸，新实现必绿）。
- `isCacheUsable` 表驱动：同项目命中 / 跨项目拒绝 / vf 失效拒绝。
- 降级链：预热线程注入失败 → createEditor 产物含 PSI（二级回退）；预热成功 → 一级文件路径。
- bundle parity 守卫自动覆盖新增 i18n 键（zh/en 双 bundle）。

## 三、实施顺序与预估

1. **T-PreviewExecDs-02**：F1 缓存项目绑定 + F4 runRunner 错项目 + F5 弹窗来源标注（~1.5h）——先止血跨项目错乱
2. **T-PreviewExecDs-03**：F2 isValid 预检+三分量诊断+自愈，F3 异常特判与文案（~2h，含一轮 runIde 真机诊断确认失效分量）
3. **T-PreviewExecDs-01a**：预览文件迁项目内 + 预热失败分级日志与重试加强（~2h）
4. **T-PreviewExecDs-01b**：三级降级链 + 预览文件方言映射（~1.5h）
5. **T-PreviewExecDs-01c**：数据源就绪后注释/补全上下文刷新（P1-5，先诊断 DatabaseSchemaResolver 在数据源晚加载场景的行为再定方案，~1h）
6. 收尾：全量测试（`./gradlew test` 裸跑）+ verifyPlugin + runIde 双项目场景真机走查（下节清单）+ 变更记录/MEMORY 台账同步

## 四、真机走查清单（runIde 沙盒）

1. 双项目同开（demo + easy-code-demo）：A 项目预览并执行 → 切 B 项目预览并执行 → 弹窗只显示 B 项目自己的数据源（F1/F5 生效）。
2. B 项目选数据源执行：日志无 `has already been disposed`，控制台正常出结果（F2/F3 生效）。
3. A 项目预览：预热日志无静默失败；失败时有阶段 warn。
4. 预览编辑器 Ctrl+Space：实体/表/列补全出现，列注释 tailText 可见（先实体注释，数据源连接后 DB 注释可覆盖）。
5. 预览面板关闭重开、执行→预览→再执行往返：无 File Cache Conflict 弹窗、无结果残留串扰。
6. 单项目回归：唯一数据源静默直连、last-used 复用、▶ 按钮启用链（既有 M36.5 能力不回退）。

## 五、实施交付记录

- 交付日期：2026-09-14
- 实施范围：F1~F5（问题二）+ P1-1~P1-4（问题一）。**P1-5（数据源就绪后注释/补全上下文刷新）本期未实施**——DatabaseSchemaResolver 的缓存预热与刷新时机需真机观察「数据源晚加载」窗口下的实际行为再定方案，留二期。
- 测试：新增 4 例（ExecTempFileFixTest：isCacheUsable 表驱动 / consume 跨项目拒绝+清空 / 项目内命中复用；CrudSqlPreviewPanelRenderTest：内存 PSI 降级前提）。全量裸命令 **3978/0/0/6 绿**（320 类，基线 3974+4）。verifyFull/buildPlugin 按用户口径跳过。
- 并行：T-FeedbackDlg 占用默认沙盒 → 登记 T-PreviewExecDs + build.gradle.kts 临时 `-PparallelSandbox` 独立沙盒（用完已还原）。
- **偏离点（相对本计划初稿）**：
  1. F5 从「runner 显示名附项目名」改为「多项目时弹窗标题带项目名」——反射拿不到 Runner 的项目归属（PersistenceConsoleProvider$Runner 无 getProject，MyRunner 的 dataSource 是闭包局部变量）；且 F1 修复后弹窗数据源必然属于当前项目，标题注明当前项目即消除「哪来的数据源」困惑。
  2. P1-4 方言映射从「取 previewModel.dialect」改为「MySQL 硬编码匹配」——插件自定义 `Dialect`（sql.MySqlDialect）与 Database 插件 SqlDialectMappings 的方言体系无现成对应，exec 路径本就硬编码 MySQL，预览对齐之。
  3. F2 预检的有界重查仅在非 EDT 路径执行（EDT 不 sleep 阻塞）；disposed 一律快速失败走 F3。
