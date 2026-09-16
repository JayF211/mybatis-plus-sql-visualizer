---
name: t-preview-exec-ds
description: T-PreviewExecDs
  预览补全缺失与执行跨项目数据源错乱——已实施交付（20260914），三根因（Temp 预览文件+预热静默
  失败/cachedTempPsiFile 跨项目泄漏/console 清扫 dispose 链）与 F1~F5+P1-1~4 修复
metadata:
  node_type: memory
  type: project
  originSessionId: sess_80f37d94-15a4-4d36-9901-2342363fe364
---

T-PreviewExecDs **已实施交付**（2026-09-14，计划 `docs/开发计划/20260914-1017-T-PreviewExecDs-预览补全缺失与执行跨项目数据源错乱-开发计划.md`，沙盒 IU-2024.3.7.1 双项目同开场景取证）。全量裸命令 `./gradlew test` **3978/0/0/6 绿**（320 类，基线 3974+4 新增测试）；verifyFull/buildPlugin 未跑（用户口径仅发版前/大功能）。

**三缺陷与根因（均已实锤）**：
- 01 预览无补全无注释（三层叠加）：预览临时文件 `File.createTempFile` 落系统 Temp（M36.5.12 exec 路径同教训未跟进 preview 路径）→ Database 插件不解析；预热线程失败**静默**（`prewarmPreviewTempFile` 的 `onFailure { prewarmFailed = true }` 零日志，09:34:40 起 8 连败）→ 回退裸 document 编辑器无 PSI → 自研/原生补全全灭；预览文件无方言映射 + 数据源模型加载晚（启动即 "No model loaded"、runners=0→4 迟至 09:37）。
- 02 数据源弹窗跨项目：`DataSourceSelector.cachedTempPsiFile` 是 object 静态 Pair，cache hit 只查 `vf.isValid` **不查项目归属** → A 项目缓存给 B 项目用 → 平台 `getAllDataSourcesForExec` 按 psiFile.getProject() 弹出 A 的数据源。
- 03 dispose 报错：session 项目=dataSource.getProject()（A）而 attachConsole 注册进当前项目（B）→ `JdbcConsole.isValid()` 的 isTargetValid=`facade.findDataSource(uniqueId)!=null` 为 false → `getScriptModel()` 内 PSI 语言替换经 `SqlLanguageSubstitutor→getActiveConsoles→getActiveClients` 把 !isValid() console **Disposer.dispose**（attach 后仅 7ms）→ `updateScriptModel` 的 `Disposer.register` 抛 IncorrectOperationException（用户见平台长串）。

**243 database-plugin.jar 反编译实锤**（排障期间核实）：`getAttachDataSourceRunners`→`getDataSourceRunners`（appropriate+all 双枚举，仅全命中才免 "all" 复合 runner）；`runInDataSource`: `DatabaseSessionManager.getSession(dataSource.getProject(), local, sql)`；console 列表存 Project userData `DATABASE_CONSOLE_LIST`；清扫条件 = `!isValid()`；isValid = !isDisposed && isViewValid && isTargetValid。

**已实施修复**：
- F1 `cachedTempPsiFile` 缓存项目绑定——命中判定抽 internal `isCacheUsable(project, vf)`（vf.isValid + vf.path.startsWith(project.basePath)，纯函数表驱动测试）+ `consumeCachedTempPsiFile(project)`（跨项目命中弃缓存重建，加 `cachedTempPsiProjectName` 归属名诊断）。
- F2 attach 后 `probeConsoleValidity` 三分量预检（disposed 快速失败；非 EDT 有界重查 300ms×3 等 facade 异步窗口；EDT 不 sleep）。
- F3 `IncorrectOperationException` 含 "already been disposed" → 清缓存（clearPrepared+dropSharedConsole）+ i18n 新键 `execute.console.disposed` 可读文案，不再透平台长串。
- F4 `runRunner` 显式传 project——修 `openProjects.firstOrNull()` 失败路径清错项目 last-used 的 bug。
- F5 多项目弹窗标题带当前项目名（i18n 新键 `execute.datasource.select.in`；**偏离初稿**：改为标题带项目名而非 runner 名，因反射拿不到 Runner 的项目归属）。
- P1-1 预览文件迁 `.idea/sql-visualizer/preview/`（VFS 安全三态写入防 File Cache Conflict）；P1-2 预热失败分级日志 + refresh 重试 1→3 次退避；P1-3 三级降级链（项目内文件→内存 PSI `createInMemoryPreviewPsi` 保补全载体→裸 document）；P1-4 方言映射（**偏离**：MySQL 硬编码匹配，因插件自定义 Dialect 与 Database SqlDialectMappings 无现成对应）。

**未实施**：P1-5 数据源就绪后注释/补全上下文刷新——DatabaseSchemaResolver 缓存预热/刷新时机需真机观察「数据源晚加载」窗口再定方案，留二期。

**环境**：双项目测试场——demo 项目 4 数据源（jpa_demo/mpj_demo/mp_demo/kotlin_demo@localhost）+ easy-code-demo 1 个（rbac@localhost）；沙盒日志 `.intellijPlatform/sandbox/mybatis-plus-sql-visualizer/IU-2024.3.7.1/log/idea.log`。

**Why:** 三根因都是跨项目/生命周期类，单项目回归测不出；console 清扫链是平台内部行为，不反编译字节码不可能定位。并行铁律：T-FeedbackDlg 占用默认沙盒 → 登记 T-PreviewExecDs + build.gradle.kts 临时 `-PparallelSandbox=mpsv-preview-exec-ds` 独立沙盒隔离（用完还原）。

**How to apply:** 再遇 "parent has already been disposed (JdbcConsole)" 或弹窗数据源串项目，先查 cachedTempPsiFile 项目绑定（isCacheUsable）与 isValid 三分量。核实 Database 插件 API 用 243 发行版 unzip+javap（见 [[idea-262-platform-jar-verify]]）；沙盒真机流程见 [[runide-demo-verify-workflow]]。注意：i18n 新键（execute.console.disposed / execute.datasource.select.in）被并行会话 T-FeedbackDlg 提交时 git add 顺带卷进其提交（8ccb0ac），内容完整但 git 归属在别处；代码/测试/文档改动留工作区未提交。
