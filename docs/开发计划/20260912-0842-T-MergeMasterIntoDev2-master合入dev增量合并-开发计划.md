# master 合入 dev 增量合并开发计划（第二轮）

- 日期：2026-09-12 08:42
- 分支方向：`git merge master`（在 dev worktree `F:/code/Java/Gitee/SongRenShuo/mybatis-plus-sql-visualizer-dev` 执行，master 合入 dev）
- merge-base：`06d742a`（v2026.9.10 发行提交）
- 分叉规模：master 独有 29 提交（含 1 个 merge 771b5f2）/ dev 独有 114 提交
- 前置侦察：`git merge-tree --write-tree master dev` 预演完成，**5 文件 5 冲突块**逐块过目定性（§四）
- 关联：上轮同类合并 `docs/开发计划/20260910-1211-T-MergeMasterIntoDev-master合入dev增量合并-开发计划.md`（merge e6a42ff）；dev→master 丢功能审计 `docs/memory-archive/`（branch-port-loss-audit）

## 一、背景与两侧增量分布

上轮合并（e6a42ff）后两侧再次分叉约两天。master 走完了 v2026.9.11 发行与三条修复线；dev 走完了 GIF 录制管线治理与性能第二批。

**master 独有（29 提交）**：

| 线 | 提交 | 落点域 |
|---|---|---|
| T-DynUpdate 自更新免重启动态安装 | 34055dd 主路径 + DynamicInstallSupport/InstallResult 三分支 + bundle 三键；7b4b906 计划；771b5f2 合并 feature/t-dynupdate（带入 T-HistParam） | update 包、PlatformPluginInstaller |
| T-HistParam 历史参数持久化与识别统一 | bceeee9 v2 格式 `-- pd:` 注释行 + SqlLogParamCodec + 双面板识别通道统一；3d72025 二轮数值谓词扩面（比较/IN/BETWEEN/VALUES 语境限定） | log 包（AutoSaver/Reader/HistoryPanel）、bundle |
| T-DynUpdate 热替换本地验证套件 | 50748d1/11407b7/7e1d3e0/1a1faac/5083859/e0b1522 六笔：本地真包服务脚本 + runIdeHotSwap 生命周期 + manifest 执行期转发 + `.run` 双配置 | build.gradle.kts、.run、dev-support |
| 262 平台适配与护栏 | 9c2788f unloadPlugin 首参弹性匹配（PluginMainDescriptor 漂移）；9f8821d PlatformSignatureCrossVersionTest 三平台签名护栏；171b7a3 更新通知 Gitee 入口去重 | update 包、测试 |
| T-ReverseParenWira | a24a30e MPJ 括号条件反向退化 + **attachConsole 包 WIRA / getDocument 包 ReadAction**（262 读权限硬抛根治） | DataSourceSelector、SqlReverseParser |
| T-PickMpjErr | ce62959 SqlToJavaPanel render 单点 generateCodeOrReport 收口 SqlReverseException | SqlToJavaPanel |
| v2026.9.11 发行 | 0cb95eb 版本号 + CHANGELOG + preview.update.json；e1a9807 marketplace 页单源化；e80d3ea README 致谢；200b187 gitignore hprof；7531cfb/2a65d6b/f949d8c/4c3ed7c/aa49c76/a6bdfc7/4c59d91/f955d85 文档与测试基建 | 发行物、docs |

**dev 独有（114 提交）**：T-GifRecordSwitch（`-PgifRecord` 属性开关替代常驻 262 录制态 + buildPlugin 误开闸门）、GIF 录制脚本管线与经验总结修订、T-PerfBatch 第二轮（SqlLogEntryBuffer 三出口拆分 + 列表通知节流）、T-PerfSweep 四项热点清扫、上轮 T-MergeMasterIntoDev 合并交付、docs 同步。

**相撞面**：master 的 T-HistParam 与 T-ReverseParenWira 正面落进 dev 同期大改的 log 包（SqlLogAutoSaver 被 T-Perf-12 异步批量写整体重写、HistorySqlLogPanel 被 T-ListMode/T-TwoLine/T-PerfBatch 重构、DataSourceSelector 被 T-Perf-13/16 改造）——这是本次冲突的全部来源。

目标：**双侧功能全保全**、全量测试 0 失败、verifyPlugin 三目标（243/253/262）Compatible、buildPlugin 正常出产物。

## 二、冲突预判（merge-tree 实测）

内容冲突 5 文件各 1 块，共 5 块；无 add/add、无 modify/delete：

| 文件 | 块位置 | 性质 |
|---|---|---|
| docs/MEMORY.md | 235 行附近 | 双侧顶插变更记录表格行，按时序并集 |
| docs/变更记录.md | 11 行附近 | 同上（master 9 行 2026-09-11 vs dev 4 行 09-10~09-11，无重复行） |
| execute/DataSourceSelector.kt | 1148~1213 | master 的 getDocument ReadAction 包裹（T-ReverseParenWira）vs dev 的 T-Perf-13 异步编辑器等待链——**同段代码两代形态，需语义融合** |
| log/HistorySqlLogPanel.kt | 3~6 | 仅 1 行 import 差异（master 增 `model.PreviewResult`），纯并集 |
| log/SqlLogAutoSaver.kt | 212~244 | master 的 `-- pd:` 参数持久化行（写点在旧同步 writeEntry 内）vs dev 的 T-Perf-12 批量写重写——**union：dev 结构 + master 两行语义** |

上轮的 CHANGELOG.md 整文件 CRLF 伪冲突本轮不再出现（dev 侧已归一 LF，merge-tree 实测 Auto-merging 通过）。

## 三、解决原则（承上轮定案）

1. 架构基准按「谁更新」判：取时间上更晚、且已被该分支全量测试锁定的版本为实现基准，另一侧特征补进去。
2. dev 的性能红线不可回退：T-Perf-12 异步批量写、T-Perf-13 异步编辑器等待链、T-PerfBatch 通知节流是 dev 核心资产，master 侧语义一律融入 dev 结构，不搬运旧结构。
3. master 的修复以 master 版为准：当 dev 侧是「修复前」形态时（attachConsole/getDocument 的读权限处理，dev 注释已预先授权该修法）。
4. 纯新增取并集；注释差异取信息更全一侧。
5. **严禁按文件整取一边**（`git checkout --ours/--theirs`）——上轮实证会抹掉另一侧自动合并区成果（releaseForPanel 悬空）。正解：以 `git merge` 自动合并结果为底，逐块替换冲突标记区。

## 四、逐块解决方案

### 4.1 execute/DataSourceSelector.kt —— master 的 ReadAction 包裹 + dev 的异步等待链（语义融合）

冲突块两侧是 `prepareInConsole` 中「取 console 文档 → 找编辑器实例」同段代码的两代形态：

- master 侧（T-ReverseParenWira）：`getDocument` 包 `ReadAction.compute`（262 `ConsoleExecutionEditor.<init>:54` 读权限断言实证修复，dev 侧 T-PreviewSlow F2 注释「若真机仍刷 Read access SEVERE 再单独为这一句包 ReadAction」正是对它的预先授权）；但其后的编辑器查找仍是基点旧态的 EDT `repeat(20){Thread.sleep(20)}` 忙等。
- dev 侧（T-Perf-13）：`findEditor()` 局部函数 + 首查即中短路径 + EDT 未命中转 pooled 线程轮询回 EDT 续链（`prepareAfterEditorResolved`）；但 `getDocument` 是裸调（修复前形态）。

**定案（union）**：`document` 取 master 的 `ReadAction.compute` 版（含 `@Suppress("DEPRECATION")` 与三轮踩坑注释）；编辑器等待链取 dev 的 T-Perf-13 异步结构。冲突块上方的 attachConsole 包 WIRA 段已在自动合并区落入，不需处理。融合后核对：块后缀闭括号配平、`ReadAction` import 已存在（dev 的 findFile 段在用）、`ApplicationManager` import 已存在（dev 结构在用）。

### 4.2 log/SqlLogAutoSaver.kt —— dev 的 T-Perf-12 批量写结构 + master 的 `-- pd:` 行（union）

- dev 侧已把 writeEntry 重写为无锁化批量写（DateTimeFormatter 常量、flush 上移批末、无 `synchronized(w)`）。
- master 侧的 T-HistParam F1 在旧结构头部下插了两行：`-- pd: ${SqlLogParamCodec.encode(entry.rawSql, entry.params)} --`（条件 `entry.params.isNotEmpty() || countPlaceholders(entry.rawSql) > 0`）。

**定案**：保留 dev 的 writeEntry 全部结构，把 master 的条件 pd 行插到头部注释行之后、正文之前（原相对位置不变）。实施时核对三件事：① `countPlaceholders` 符号在合并树可达（master 对本文件仅 1 个 hunk，该函数应定义在 log 包其他文件——预计 SqlLogParamCodec.kt 或 Reader，编译验证）；② `SqlLogParamCodec` 同包免 import；③ 合并树现存 import 里 master 侧 `java.text.SimpleDateFormat`/`java.util.Date` 若因本块取 dev 而失去全部读者则清掉（dev 的 TIMESTAMP_FORMATTER 不用它们），以编译 warning/无读者为准。

### 4.3 log/HistorySqlLogPanel.kt —— import 行并集

唯一冲突是文件头 1 行：master 增 `import cn.song.mybatisplus.visualizer.model.PreviewResult`（合并树 424 行 `buildPreviewResult` 的返回类型需要它）。**取 master 行保留**即完成。同时复核自动合并区：master 的 `buildPreviewResult`/`toPreviewResult` 统一识别通道是否完整落在 dev 的 T-ListMode 渲染结构上（416 行 `SqlPreviewService.showResult(project, buildPreviewResult(entry))` 调用点在位）。

### 4.4 docs/变更记录.md —— 表格行时序并集

master 侧 9 行（2026-09-11：T-ReverseParenWira、hprof 收口、热替换缓解+Gitee 去重、跨版本签名护栏、262 unloadPlugin 漂移、T-DynUpdate 实测、771b5f2 合并行、T-HistParam 两行、T-DynUpdate 主行）+ dev 侧 4 行（2026-09-11：录制配置治理、T-PerfBatch 第二轮；2026-09-10：上轮合并交付、T-VStack·InThreshold）。交叉核对无同内容重复行，按日期倒序合并（同日按 master 交付时序），表头分隔行只留一份。

### 4.5 docs/MEMORY.md —— 变更记录行并集

同 4.4 模式：master 3 行（2026-09-12 调研报告迁移/记忆第六次精简、2026-09-11 T-ReverseParenWira）+ dev 4 行（2026-09-11 录制配置治理/T-PerfBatch 第二轮、2026-09-10 上轮合并交付/T-VStack·InThreshold），按日期倒序并集。

## 五、自动合并区语义复核清单

自动合并成功 ≠ 语义正确，以下文件双侧都改过，合并后须逐项过目：

| 文件 | 复核点 |
|---|---|
| build.gradle.kts | master +152 行（runIdeHotSwap 任务/本地服务生命周期/`.run` 相关）与 dev +42 行（`-PgifRecord` 开关 + buildPlugin 误开闸门）共存；确认闸门的 `startParameter.taskNames` 读取不被 master 新任务名绕开（gifRecord=true 时 master 新任务应同样被拦或明确放行） |
| gradle.properties | 版本号收敛到 master 的 2026.9.11 |
| CHANGELOG.md | Unreleased 段双侧条目并集无丢失；确认全文件 LF |
| PluginSettings.kt / PluginSettingsPanel.kt | master T-DynUpdate/T-HistParam 新增字段与 dev T-ListMode 相关字段共存，无同名冲突残留 |
| UpdateSection.kt / StartupUpdateCheck.kt | master T-DynUpdate 的 InstalledLive 态/通知分叉完整，dev ±6/±19 行改动未被挤掉 |
| SqlLogFileReader.kt | master 的 pd 行探测解码（±42）与 dev 改动（±15）共存——v2 条目参数恢复链路完整 |
| SqlLogPanel.kt | master ±67（T-HistParam 识别通道统一等）落进 dev ±186（T-ListMode 片段管线/T-PerfBatch 节流）之上，渲染管线无断裂 |
| SqlToJavaPanel.kt | master ±31（T-PickMpjErr 的 generateCodeOrReport 收口）与 dev ±44 共存——异常收口分支不被 dev 结构挤掉 |
| bundle en/zh | 双侧新增键并集后 zh/en 键集合仍对称（跑 bundle 对称测试兜底）；已知 master 既有重复键 `gutter.pages.accessible.name` 随合并进入，非本合并引入，登记不处置 |
| SqlLogHistoryRoundTripTest.kt | master 的 v2 round-trip 用例（±32）依赖 dev 保留的同步 `write()` API（T-Perf-12 特意为测试保留），确认用例语义仍成立 |
| README.md / .gitignore / marketplace-custom-page.html | 小改动并集过目 |
| update/preview.update.json | 取 master 的 v2026.9.11 发行元数据 |
| 新增文件 | master 的 PlatformSignatureCrossVersionTest、DynamicInstallSupport、SqlLogParamCodec、热替换脚本/dev-support、`.run`、docs/调研报告/* 干净落入 |

**预期用例规模**：dev 基线 3733（录制配置治理行实测）+ master 侧新增（签名护栏/动态安装/历史参数/反向括号/选类收口等）≈ 3800 上下，0 失败 6 跳过量级（签名护栏测试在发行版缺失时按 Assume 跳过，本机 transforms 齐全应实跑）。

## 六、测试与验收

1. 前置：跑任何 `./gradlew` 前先 `python C:/Users/USER/.zcode/cli/parallel_tasks.py check`，有活跃任务则登记独立沙盒再跑。
2. 编译冒烟：`compileKotlin compileTestKotlin`（警惕 K2J 陈旧输出劫持——失败先清 `build/classes`+`build/kotlin` 再证伪，见 global 踩坑）。
3. 全量：`./gradlew test`，0 失败；对失败项先证伪（陈旧类/clean 首跑崩溃两类假失败）再排逻辑。
4. 构建校验：`./gradlew verifyPlugin` 三目标 Compatible（warning 级允许，error 级零容忍）；`./gradlew buildPlugin` 出正常产物。
5. 行尾抽验：合并后 CHANGELOG.md 与两个冲突文档 crlf=0。

## 七、回滚预案

- 合并前在 dev 打备份锚点：`git tag dev-backup-pre-master-merge-20260912`（对齐上轮 dev-backup-pre-master-merge-20260908 惯例）。
- 冲突解决中途废弃：`git merge --abort`。
- 合并已提交后发现方向性错误：`git reset --hard dev-backup-pre-master-merge-20260912`（未推送前）。
- 上轮的教训继续有效：任何块拿不准时先 `git show master:<file>` / `git show dev:<file>` 对照读完整函数，不以行数多寡定取边。

## 八、实施步骤

1. 立项：本计划文档提交 dev（docs: T-MergeMasterIntoDev2 立项）。
2. 备份锚点 tag → `git merge master` → 逐块解决 5 冲突（§四）→ 自动合并区复核（§五）。
3. 编译冒烟 → 全量测试 → 修复（§六）。
4. `verifyPlugin` + `buildPlugin`。
5. 交付三处同步：本计划 §九 实施记录回填、docs/变更记录.md 顶插行、docs/MEMORY.md 顶插行；merge 提交信息按惯例列明合入的任务线；交付行注明全量计数与 verifyPlugin 结果。

## 九、实施记录

- 2026-09-12 09:15 全项完成，merge 提交 `28765fa`，冲突预演与实际零偏差（5 文件 5 块）。
- **逐块落地与 §四 定案一致**：
  - DataSourceSelector：master 的 `getDocument` ReadAction 包裹 + dev 的 T-Perf-13 异步等待链融合；`prepareAfterEditorResolved`（dev 独有，master 0 引用）已承载 master 内联续链全部语义（M36.5.16/M36.5.24/refreshConsoleModel），attachConsole WIRA 包裹在自动合并区落位，编译即过。
  - SqlLogAutoSaver：dev 的 T-Perf-12 批量写结构 + master 的条件 pd 行（`-- pd:`）插入头部行后；`countPlaceholders` 系 `LogEntryPreviewRecognizer.kt` 顶层 internal 函数（同包免 import），`SqlLogParamCodec.kt` 同包新文件干净落入；`SimpleDateFormat/Date/Locale` 三个 import 经查仍有文件名模板解析读者，保留。
  - HistorySqlLogPanel：仅 import 行并集（`model.PreviewResult`）；master 的 `buildPreviewResult`（T-HistParam F3 统一识别通道）自动合并落在 dev T-ListMode 结构上，调用点 `openPreview` 在位。
  - docs/MEMORY.md 与 docs/变更记录.md：时序并集去重（master 09-11 十行 + dev 09-11 两行 + dev 09-10 两行；MEMORY 侧 master 09-12 两行 + 双侧 09-11/09-10 行），交叉核对无重复行；脚本重组时踩 CRLF 匹配坑一次（`=======` 带 `\r` 致精确等值失配，rstrip 处理），首次运行在写盘前失败文件无损。
- **自动合并区复核（§五）结果**：gradle.properties 收敛 2026.9.11；build.gradle.kts 的 `-PgifRecord` 闸门（读 startParameter.taskNames）与 master 新增 runIdeHotSwap/本地清单任务共存无冲突（闸门只拦 buildPlugin/publishPlugin/publish，runIde 族任务语义上应放行）；CHANGELOG 暂存 blob crlf=0（上轮行尾归一红利兑现，本轮无伪冲突）；bundle en/zh 各 557 键、键集合双向差集为空（master 既有重复键 `gutter.pages.accessible.name` 已在其后续提交消除，未随合并带入）；`InstalledLive` 三态在 UpdateSection/StartupUpdateCheck 落位；SqlLogPanel 中 T-HistParam F3 `toPreviewResult`（1258 行）与 dev 片段管线 `lineFragments`（1508 行）共存；SqlLogHistoryRoundTripTest 走 dev 保留的同步 `write()` API；SqlLogFileReader pd 探测解码（PD_PREFIX/v2 头部判据排除）完整。
- **验收**：全量 `./gradlew test` BUILD SUCCESSFUL 4m32s——**295 类 / 3788 用例 / 0 失败 / 0 错误 / 6 跳过**（dev 基线 3733 + master 侧 55，一次通过零修复）；`verifyPlugin` 三目标（IU-243.28141.41 / IU-253.33514.17 / IU-262.9437.185）BUILD SUCCESSFUL 且均 **Compatible**（deprecated 3/6/47、scheduled-for-removal 1、experimental 12，均为 warning 级；较上轮 3/6/44+10 的增量来自 master v2026.9.11 已过发行门禁的 update 域代码，随合并带入非本次引入）；`buildPlugin` 出产物 `mybatis-plus-sql-visualizer-2026.9.11.zip`（6.6MB）。
- 备份锚点 `dev-backup-pre-master-merge-20260912`（tag，指向 5d66df0 立项提交；合并废弃时 `git merge --abort`，已提交后回退 `git reset --hard dev-backup-pre-master-merge-20260912`）。
