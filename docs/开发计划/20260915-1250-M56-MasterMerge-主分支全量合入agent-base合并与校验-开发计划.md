# M56 主分支全量合入 feature/t-m56-01-agent-base 开发计划

| 项 | 值 |
|----|----|
| 计划编号 | 20260915-1250-M56-MasterMerge |
| 任务 | master → feature/t-m56-01-agent-base 合并、冲突解决、全量测试、插件构建校验 |
| 工作树 | `F:\code\Java\Gitee\SongRenShuo\mpsv-m56`（分支 feature/t-m56-01-agent-base） |
| merge-base | `a274e30` |
| master HEAD | `155e0cf`（docs: T-ListParamCollect 合入 master） |
| feature HEAD | `59e8510`（fix: 恢复变更记录总册） |
| 差异规模 | master 领先 248 提交；feature 自身 9 提交（M56 agent 字节码通道一期） |
| 状态 | 实施中——首环完成（§五 1~6 全落地），master 并行两次推进，正按 §六做增量收口 |

---

## 一、合并策略

1. **方向与方式**：在 mpsv-m56 工作树（已确认 clean、HEAD=feature/t-m56-01-agent-base）执行 `git merge master --no-ff -m "merge master ..."`，合并提交保留双亲，不做 rebase（保留 M56 分支语义与历史）。
2. **合并前保护**：创建备份分支 `backup-m56-pre-master-merge-20260915` 指向当前 HEAD，任何时刻可 `git reset --hard backup-m56-pre-master-merge-20260915` 回滚。
3. **合入范围**：master 全部 248 个提交内容（T-ListParamCollect、T-TestPerf2、T-SettingsOrder、T-PerfSweep2、T-MarketInternal、T-ExplainFirstNative、T-FeedbackErr 系列、T-FeedbackBatch、T-ListPreviewCopy、T-ReportL10n 系列、v2026.9.14 发布链等）。
4. **语义原则**：master 新功能完整吸收；M56 agent 增强语义完整保留；两者交集处做"融合"而非"二选一"。
5. **本地性**：合并结果只留在本地，不推送远程（推送需用户再次确认）。

## 二、冲突预判与处理原则（merge-tree 预演实证）

预演命令：`git merge-tree --write-tree master feature/t-m56-01-agent-base`。结果：**11 个内容冲突文件，12 个冲突块**，其余 12 个重叠文件可自动合并。

| # | 文件 | 冲突类型 | 解决策略 |
|---|------|---------|---------|
| 1 | `build.gradle.kts` | 尾部新增块：master 测试并行策略注释 vs feature agent jar 内嵌（embedAgentJar/evaluationDependsOn） | **双保留 union**：两块互不相关，先后排列 |
| 2 | `settings.gradle.kts` | master buildCache 关闭（T-TestPerf2） vs feature `:sql-visualizer-agent` include | **双保留 union**（buildCache 关闭必须保留——历史三次 NoSuchMethodError 假失败的根因） |
| 3 | `docs/MEMORY.md` | 表格行追加型：master 09-13~09-15 行 vs feature M56 行 | **union**：两组日期行全部保留 |
| 4 | `docs/变更记录.md` | 同上，表格行追加 | **union** |
| 5 | `config/PluginSettings.kt` | data class State 尾部：master 加 reportSubmitterName/Email（T-ErrReport） vs feature 加 enhancedCaptureEnabled/Confirmed/Desensitize（M56） | **union**：6 个字段全保留，master 2 个在前 |
| 6 | `log/SqlLogAutoSaver.kt` | **语义融合**：master 用 DateTimeFormatter 常量+flush 移批量（T-Perf-12） vs feature synchronized(w)+encodeEntry 扩展字段（M56 T-M56-00） | **融合**：保留 feature 的 `synchronized(w)` 并发保护与 `encodeEntry`（M56 结构化字段不丢失），内部时间格式化采用 master 的 `TIMESTAMP_FORMATTER`（线程安全+零新建），逐条 flush 遵循 master 移除（writeBatch 统一 flush） |
| 7 | `log/SqlLogConsoleBridge.kt` | 文件头：master MARKER_DEFENSE_LOG 常量 vs feature SqlCaptureGate object | **双保留 union**（两个独立新增代码块） |
| 8 | `log/SqlLogPanel.kt` 块1 | master T-Perf-03 注释头 vs feature FocusFilter/filterByFocus（M56 T-M56-00/06） | **双保留 union** |
| 9 | `log/SqlLogPanel.kt` 块2 | **语义融合**：master `formatSourceFragment(entry, useSelectionFill)`（T-CrudColor 选中态） vs feature `formatSourceFragment(entry, isSelected)` + 3 个 agent badge 片段调用（T-M56-02/05） | **融合**：源码片段用 `useSelectionFill`（跟随 master 渲染语义）；agent badge/updateCount/error 三调用保留 feature 实现（其函数签名以合并后实际定义为准对齐参数） |
| 10 | `messages/MpSqlVisualizerBundle.properties` | 整文件级：master 606 键大改版 vs feature 基于旧 fork 的 516 键+M56 新增 | **master 为底 + M56 增量**：以 master 版为基准，追加 M56 独有键（`settings.enhancedCapture*` 6 键、`log.panel.copyExecutableSql` 等核实后的 M56 新键）。M56 侧相对 merge-base 无既有键值修改（已实证：8 个"差异键"全部是换行解析噪音，值实际相同） |
| 11 | `messages/MpSqlVisualizerBundle_zh.properties` | 同上 | 同上策略（中文侧） |
| 12 | `test/.../SqlLogConsoleBridgeMarkerTest.kt` | 测试块追加：master T-IntrUpg 自位污染防线 2 用例 vs feature M56 标记第 4/5 段 2 用例 | **union**：4 个测试全部保留 |

**通用处理纪律**：
- 每解决一个文件，立即在冲突台账（交接文档）登记"文件 / 冲突类型 / 保留策略 / 取舍原因"。
- bundle 融合后必须通过 `BundleParityGuardTest`（en/zh 守卫）。
- 全部解决后全文搜索 `<<<<<<<`/`>>>>>>>`/`=======`（限冲突标记语境）确认零残留。
- M56 侧 sub-projects/sql-visualizer-agent 目录为纯新增（master 无此目录），无冲突；master 侧对 log/ 下其他文件（LogSqlModels、SourceInterceptorTemplates、SqlLogFileReader、SqlLogManager、SqlSourceMarker、plugin.xml、PluginSettingsPanel、LogSqlModels 等）自动合并结果需人工复核 M56 语义未被冲掉。

## 三、测试修复与构建校验步骤

1. **环境**：JAVA_HOME 指向 `D:\RuanJian\JetBrains\IntelliJ IDEA 2\jbr`（JBR 21.0.7，满足 Gradle 9.7 + Kotlin 2.3.20 + intellij-platform 2.18.1 要求；系统默认 java 1.8 不可用）。
2. **编译门禁**：`.\gradlew.bat compileKotlin compileTestKotlin`（含 agent 子项目 evaluationDependsOn 链，先确认 embedAgentJar 任务链正常）。
3. **全量测试**：`.\gradlew.bat testShards`（并行 3 片，master 侧 T-TestPerf2 推荐路径；串行 `test` 作对照备用）。含 master 新增全部测试（预期约 4100+ 用例）与 M56 agent 测试（AttachGateTest 等真实 attach 门禁）。
4. **失败修复循环**：失败用例逐条定位 → 区分「合并语义冲突」「flake（clipboard/冷启动类，历史已知 CopyFeedbackUiTest 首跑假失败）」「环境缺依赖（Docker 类自动 skip）」→ 修复后复跑至 0 失败。flake 判定标准：同用例独立沙箱重跑通过且与合并改动无逻辑关联。
5. **插件构建**：`.\gradlew.bat buildPlugin` → 产出 `build/distributions/*.zip`，核对 zip 内 `agent/mps-sql-agent.jar` 已内嵌（embedAgentJar 链）。
6. **插件校验**：`.\gradlew.bat verifyPlugin` → 三目标 IDE（243/253/262）无 Internal/scheduled-for-removal 错误（历史基线：Compatible，deprecated 若无新增即达标）。
7. **收尾自检**：`git status` 干净、合并提交存在、冲突标记零残留、台账完整。

## 四、风险与回滚方案

| 风险 | 概率 | 缓解/回滚 |
|------|------|----------|
| bundle 整文件融合漏键/漂移 | 中 | 键集合 diff 三方核对（base/master/feature）；BundleParityGuardTest 守卫；M56 键清单化追加 |
| SqlLogAutoSaver 融合破坏 pd 行兼容 | 中 | SqlLogHistoryRoundTripTest + SqlLogFileReader 探测链测试兜底；必要时保留 feature 侧 flush 保守语义 |
| build cache 陈旧产物假失败（历史三连坑） | 中 | master 侧 settings 已关 buildCache，合并时必须保留该块；仍异常则 `--no-build-cache` + 清 build/classes |
| 剪贴板/冷启动 flake | 中 | 已知清单（CopyFeedbackUiTest 等）单类重跑甄别，不计入真实失败 |
| Gradle daemon 锁（journal-1.lock/javaCompile.lock） | 低 | 单会话串行执行；卡锁先 `.\gradlew.bat --stop` |
| agent 子项目 JDK8 目标编译与 JBR21 工具链冲突 | 低 | agent toolchain 17 由 Gradle 自动解析（foojay resolver），attach 门禁测试 AttachGateTest 实证 |
| 合并后行为级回归（渲染/选中态） | 低 | 渲染器冻结守卫 + UI 测试覆盖；真机走查项列入交接文档遗留 |
| 整体回滚 | — | `git reset --hard backup-m56-pre-master-merge-20260915`（合并提交未推送前任意时刻可执行） |

## 五、交付物清单

1. 本开发计划（先行产出）✅
2. 合并提交（master 248 提交全量合入、工作树 clean、无冲突标记）
3. 冲突解决台账（并入交接文档）
4. 全量测试通过记录（用例数/失败数/耗时）
5. buildPlugin 产物 + verifyPlugin 报告结论
6. 交接文档（`docs/开发计划/` 同级：冲突清单、变更摘要、测试与校验结果、遗留事项、回滚方式）

## 六、master 并行推进的增量收口（二次/三次合并）

master 在本任务执行期间被并行会话两次推进，「master 功能全部合入」目标要求随之做增量合并。本节在动代码前登记增量计划。

### 二次合并（已完成，提交 `da37ad5`）

| 项 | 值 |
|----|----|
| master 合入点 | `d366731`（T-Intranet 内网离线可用性 + v2026.9.18-beta.1 发行链 + T-TestPerf2 沉淀） |
| 冲突 | 1 文件（`docs/变更记录.md` 表格行追加型）→ union（master T-Intranet 两行 + M56 台账行归位表首） |
| 测试 | testShards **4162/0/0/6**（1570+1344+1248，跳过=6 Docker）+ agent 21 用例绿 |
| 中断点 | 最终 buildPlugin + verifyPlugin 未跑完（会话中断），由三次合并后统一收口 |

### 三次合并（本节计划，master 合入点 `5eeb884`）

增量内容 8 提交、28 文件：T-ReleasePerf 收尾（`c44dbfd`/`e8f0224`：build.gradle.kts verifyGate 离线化、`dev-support/verifier-ignored-problems.txt` 豁免清单、发行说明/开发测试指南文档）、日志原始时间记录与显示切换（`51eb01a`/`a2436f1`：LogTimestampParser + 各 parser + 设置项）、JdbcTemplate 并发修复（`7d23c1d`）、日志列表搜索行号/时间 + 右键复制时间戳（`5eeb884`/`71bffa1`：SqlLogPanel/SqlHistoryPanel）。

- **预演**：`git merge-tree --write-tree HEAD master` → exit=0 **零文本冲突**（28 文件全部自动合并）；`docs/MEMORY.md`、`docs/变更记录.md`、M56 实施计划文档 master 侧在 `da37ad5..5eeb884` 区间未改动（差异纯来自 feature 侧），合并保留 feature 版。
- **自动合并语义复核点**（零冲突 ≠ 语义正确，逐项过守卫）：
  1. `build.gradle.kts`：T-ReleasePerf verifyGate/releaseGate 改动与 feature embedAgentJar 尾块共存性；
  2. 两个 bundle：master 新增 timestamp/search 键自动并入后，en/zh 键集合仍须全等（BundleParityGuardTest 守卫）；
  3. `SqlLogPanel.kt`：master 搜索行号/时间与右键复制时间戳 vs feature agent badge（非重叠区自动合并，渲染冻结守卫复核）；
  4. `PluginSettings.kt`：master 日志时间新字段与 feature enhancedCapture 字段并列完整。
- **门禁**（合并后一次性收口，含二次合并遗留）：testShards 全量 + `:sql-visualizer-agent:test` + buildPlugin（核对 agent jar 内嵌）+ verifyPlugin 三目标 Compatible。
