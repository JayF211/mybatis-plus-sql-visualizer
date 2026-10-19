---
name: project-m56-agent-bytecode
description: M56 agent 字节码增强——**2026-09-16 已整体合入 master（merge 68469dd，30 分支提交并入，master 全量 test 绿）**，此后可正常在 master 改；同日真机排障闭环（resolvePid 三步回退 / Jsons 首逗号 / 传输加固 / T-AgentListenSync / T-AgentSourceJump / 排序方式）+ 历史页元信息统一 + 构建缺口修复（RelocateAsmMain 被 .gitignore build/ 规则漏纳）
metadata:
  node_type: memory
  type: project
  originSessionId: sess_b60633b3-135d-4438-89b9-0654d88a6741
---

# M56 agent 字节码增强捕获通道

**实施版计划（唯一实施依据）2026-09-12 立项**：`docs/开发计划/20260912-0817-M56-SQL日志增强捕获agent字节码-实施开发计划.md`（T-M56-00~09，整合三竞品调研；0710 版转为决策与调研整合记录）。方案级定夺：插桩主路径「Driver.connect 织入 + JDK 动态代理包装」（逐方法 hook 降对照组）；三层提取=setXxx 显式枚举→驱动自带 asSql/toString→反射驱动内部字段（分库策略表沿 DebugTools 组织）；**agentmain 必须 addTransformer+retransform（DebugTools agentmain 只起 server 不挂 SQL transformer 的半成品陷阱，PoC 硬验收）**；ASM 首选（**Tree API 已定稿，门禁内完成编织不切 ByteBuddy**）；协议=长度前缀+单字节命令裸 Socket（RMI/Netty/JVMTI/groovy/hotswap 全不吸收）；回调类 system classloader 兜底+HiddenAgentClassLoader 式单测。

## 落地状态（2026-09-16 已合入 master——合并债清零）

**2026-09-16 整体合入 master（`merge 68469dd`）**：feature/t-m56-01-agent-base 的 30 个提交并入（master 侧同时有 16 提交，真合并非快进），三处文档冲突解决（CHANGELOG 双 `[Unreleased]` 合一、docs/MEMORY 台账并集、变更记录并集按新在上排序）；master 全量 test + agent 子项目 test 绿（5m51s）。**此前「不能在 master 改 M56」的约束随之解除**。分支 `feature/t-m56-01-agent-base` 仍保留（含同一批修复，另有 master 独有的文档收口提交 9a50f07 未回分支）。

**2026-09-16 晚分支彻底收口删除**：master 又经 T-AgentLogTime(17eb380)/T-LogParseConc(6626365)/T-HibConc(e7787ca) 推进后，分支唯一独占提交是 83bc395（RelocateAsmMain 构建修复），但该修复与 master 388a56b **文件级逐字节一致**（`git diff` 零差异——同一修复两条线各提交一次，非漏合）。在 mpsv-m56 工作树做几何收口合并 master 生成 3266268（tree 与 master 完全一致）后，**本地分支已删除、worktree 移除并清残留目录**（Windows 长路径 `Filename too long` 时用 `rm -rf` 补清）；master HEAD 已由发行工作树推进到 74cfabf（v2026.9.18-beta.3）。`remotes/origin/feature/t-m56-01-agent-base` 仍挂远程，待确认后 `git push origin --delete` 清。**经验：判「分支是否可删」要做成分判断——合并后 `git diff <分支> master --stat` 若只剩发行类差异（版本号/变更记录），即分支已落后主分支可删。**

**合流后暴露并修复的构建缺口（重要踩坑）**：`src/reloc` 源集的 `RelocateAsmMain.java`（agent fat jar 的 ASM 自 relocate 打包工具）**从未被 git 跟踪**——其包名路径含 `build` 段（`.../sqlvis/agent/build/RelocateAsmMain.java`），被 `.gitignore` 通用规则 `build/` 整目录忽略；mpsv-m56 工作区因文件残留在本地磁盘而构建一直看似正常，干净检出/其他工作区必报 `ClassNotFoundException: RelocateAsmMain`。修复=补 `!**/src/reloc/**/build/` 例外（同 `!**/src/main/**/build/` 既有风格）+ 纳入源文件（master 388a56b / 分支 83bc395）。**通用教训：源码路径含 `build/`、`target/` 等构建目录名时，必须 `git check-ignore -v` 验一次是否真被跟踪。**

**2026-09-17 agent 已入 dev + 架构文档全链入册（dev 759102b）**：dev 经 beta.3 合并链（merge 05080f6）已含 agent 全部代码（dev worktree 实测 `sub-projects/sql-visualizer-agent` 在场）——**「agent 未入 dev」的旧状态全部作废**。IDE 侧落点=log/agent/{AgentManager（AGENT_RESOURCE=/agent/mps-sql-agent.jar；解压落盘→ServerSocket(0) 端口前置协商→VirtualMachine.attach+loadAgent→socket 会话/心跳/在线态） / AgentEventAdapter（dedupeKey 与桥通道同口径） / AgentProtocol / JdbcUrlDialects（jdbc url→目标库角标）}；`SqlCaptureGate` 定义在 SqlLogConsoleBridge.kt 内；consoleFilterProvider EP 主捕获通道（SqlConsoleBridgeFilterProvider，`epActive` 会话内粘滞，EP 生效后桥反射轮询停转防双路解析，T-M56-09）。**requestTag=agent 反射读 slf4j MDC key `sqlvisRequestTag`**（MdcAccessor，T-M56-07 一期兜底：无 slf4j/无标签静默 null；Web 过滤器 hook 织入留二期）。LogTimeCorrelator 接管期用控制台日志原始时间回填条目（键=logTimeKey 占位符版 rawSql 指纹——dedupeKey（还原后指纹）口径会因布尔参数/注释内 ?/参数个数三类分叉静默失配，T-AgentLogTime 改键实证）。设置页「源码位置捕获」分组三行：enhancedCaptureEnabled（默认 true）/enhancedCaptureConfirmed（首挂每进程确认一次）/enhancedCaptureDesensitize。架构文档三篇已同步该链路：01 §5.5 设计决策、02 新增 §6.5 链路章、03 §4.9 agent 改动约定（DriverRegistry 登记/双端事件字段联动配格式守卫/JDK8 纯 Java 兼容）。

### 历史落地状态（2026-09-15，合并债记录）

一期 T-M56-00~09 已实施交付（提交 `bc6351c` agent 基座 + `ae0c549` IDE 集成 + `e3f62b9`/`e2e20a1` 文档与 JDK8 门禁），但 **master 与 dev 都没有 agent 代码**：`bc6351c` 只在 `feature/t-m56-01-agent-base`（companion `feature/t-m56-00-consumer`），`git merge-base --is-ancestor` 实测不在 master，dev 树 `grep sql-visualizer-agent` 计数 0（dev 头 5f80b6c 四计划批次不含 M56）。交付记录归档 [[t-m56-agent-delivery]]。**任何「基于 M56 改造」的任务开工前必须先决策分支合并**，不能在 master 上直接改。

**2026-09-15 master→分支方向已清账**：master 全量合入 `feature/t-m56-01-agent-base`（worktree mpsv-m56），三次合并 `227eaec`（248 提交首环，11 文件 12 冲突块）+ `ad9fb60`（T-Intranet/v2026.9.18-beta.1）+ `ea758e2`（T-ReleasePerf/日志原始时间/JdbcTemplate 并发修复/日志列表搜索行号时间）——分支现含 master 全部功能（`merge-base --is-ancestor` 通过），全量 4200/0/0/6 + agent 21/0 + verifyGate 三目标 Compatible，台账见 docs/开发计划/20260915-1250-M56-MasterMerge 交接文档；**未推送远程**，M56→master 反向合并仍待决策。合并踩坑三沉淀：① PS5.1 `Set-Content -Encoding utf8` 写 bundle 带 BOM→Properties 解析出 `?#` 幽灵键（必须 .NET 无 BOM 写回）；② 目录迁移（cb4436f agent/→sub-projects/）后必须全局 grep 旧路径（AgentProtocolSyncTest 漏改潜伏到全量才爆）；③ 合并给 data class 加字段后首跑必清 build/classes+build/kotlin（本次 327 例 NoSuchMethodError 劫持实录，[[pitfall-regression-false-failure-flakes]] 已收录该形态）。

- 子项目形态：`sub-projects/sql-visualizer-agent/`（`cb4436f` git mv 自 `agent/`，沿用 Cool Request 的 sub-projects 约定）；settings.gradle.kts 里原名 `include(":agent")`、迁移后任务路径 `:agent:` → `:sql-visualizer-agent:`；根 build.gradle.kts `evaluationDependsOn` + `embedAgentJar`(Copy → `resources/agent/mps-sql-agent.jar`) + `processResources.dependsOn`，打包含内嵌 agent jar（6.43MB）。
- agent 实现：Java 8 字节码 + `--release 8`，**无 shadow 插件**——自写 RelocateAsmMain 用 ASM 重写 agent 类自身的 ASM 引用（漏掉即运行期 NCDFE）。
- 现有入口：`AgentMain` 提供 **premain 与 agentmain 双入口**；`AgentArgs` 已是反射自省 `k=v` 逗号分隔（加字段零协议改动）。
- IDE 侧已落地件：AgentManager（惰性 3s 去抖挂载/幂等表/在线态/回落）、SqlCaptureGate 接管闸门、AgentEventAdapter（dedupeKey 与桥一致）、设置页开关+确认框（JEP 451 话术）、consoleFilterProvider EP 双轨（epActive 首行标志粘性）。

## 2026-09-16 真机启用排障与设置归位

- **T-AgentCudSource 增删改源码位置缺失修复（20260916 提交 d89c334，master 直接做）**：用户反馈增强捕获下只有 SELECT 捕获到源码位置、INSERT/UPDATE/DELETE 捕获不到。子代理静态复核确认采集链路对 SELECT 与 CUD **完全同构、无按语句类型分支**（`EventBuilder.isExecuteFamily` 把 execute/executeQuery/executeUpdate 一并纳入同一 `executeWithCapture`→`emitEvent`→`StackLocator.locate`，IDE 侧 `AgentEventAdapter` 纯直通无 CUD 置 null）——不存在「executeUpdate 漏采集」的结构性缺陷。**真凶=StackLocator 调用方缓存自毒化**：缓存键=首个非框架帧 `class#method`，主位置无调试信息（file 为 null）时候选首位顶上且可能仍为 null，随后整帧（含 null）入 `CACHE.putIfAbsent`；某调用方一旦解析出无调试信息的中间帧（CUD 常走的 MP 默认方法/`$Proxy` 派发帧即命中），该 `class#method` 名下所有后续语句（含 CUD）恒无源码位置且不再自愈——正对应「查询有、增删改无」。**修复=仅缓存有效位置**：主位置 null 时候选循环挑非 null 顶上（候选构建已滤 null-file 项）、候选缺席 return null 不缓存（下次同调用方重试）、`putIfAbsent` 前加 `primaryFile != null` 守卫双保险；正常路径仍走缓存热路径零回归。**测试缺口收口**：agent 侧此前无任何「真实 CUD 执行→断言事件 sourceFile 非空」端到端用例（ConnectWeaveEndToEndTest 只断言 executeUpdate 原语义/行数），新增 `StatementSourceLocationTest` 2 例经包外 `UserCaller` 用户形态调用帧触发真实 executeUpdate/execute 断言事件 sourceFile 精确指向用户栈帧。**踩坑**：定位断言须精确到首个用户帧——夹具触发帧若放 agent 前缀包 `cn.song.mybatisplus.sqlvis.` 会被 `FRAME_SKIP_PREFIXES` 自滤掉、误选 JUnit 的 FrameworkMethod（断言失真），改放包外 `cn.song.mybatisplus.sqlvistest.` 即精确命中。**方言增强能力矩阵（副任务，一并盘清）**：采集面是 JDBC 接口级、与数据库类型无关，对全部 9 方言 + GBASE/HSQL/openGauss 一视同仁、不走按方言分支；**唯一耦合点=DriverRegistry 驱动类清单**（16 个精确类名，DM 仅登记 `dm.jdbc.driver.DmDriver` 无模糊匹配）——若目标应用驱动类名不同/加载晚于 `agentmain.retransformLoadedDrivers`/驱动 loader 隐藏 agent 类（`ConnectWeaver.weave` 织入期可见性判定不通过整类不织入）则零织入；**「在线但零捕获」是静默的**（`updateOnline`→`SqlCaptureGate.paused=true` 连控制台解析也短路，无在线零捕获告警，增强角标已移除）→ DM 捕不到需目标应用实际驱动类名 + IDE 日志 `AgentManager` 在线态切换 WARN 定案，属运行期证据问题非代码缺陷。

- **设置页开关位置修正（两步）**：T-M56-08 的三行（增强捕获/脱敏/状态）原误置「SQL 预览」区组，先归位「SQL 日志」区组（提交 `711370a`）；用户再反馈「位置应该在增强捕获状态中而不是参数前缀中」→ **三行再挪到 SQL 日志区组末尾**（`settings.log.tab.mybatis` 的 showDuration 之后）做独立收尾小节（**已改未提交**，与 AgentManager 诊断日志同批）。**用户报「没看到开关」先想是不是在别的区组**。
- **搬区组必须同步三处读写判定（711370a 教训，未同步导致真机勾选保存失败）**：`711370a` 只搬了**显示布局**（addRow），但 `apply()`/`reset()`/`isModified()` 里增强捕获字段仍挂在 `if (SECTION_PREVIEW in sections)` 分支。用户打开 SQL 日志子页（sections 只含 `SECTION_SQL_LOG`）勾选时，`isModifiedSqlLog()` 无增强捕获判断 → 平台判定「无改动」→ **不调 apply()** → `enhancedCaptureEnabled` 不写入 → 重开未勾选。修复=`25f0a77` 把增强捕获读写判定整体从 preview 分支移到 sqlLog 分支（isModifiedSqlLog/apply/reset 三处），**已提交**，config 包 + 全量 4200+ 绿。此类「假开关」仓库已有同型先例（reverseWrapperFormat 漏挂 isModified），搬区组后必查 apply/reset/isModified 三分区一致。
- **启用持久化语义（confirmed 与 enabled 两套时机）**：`enhancedCaptureConfirmed` 在勾选监听里**立即**写 state（确认框同意即置 true）；`enhancedCaptureEnabled` 只在设置页**点 OK/Apply** 且 isModified 命中时才写入。用户「勾选了却没开」两种可能：① 勾选+同意确认框但没点保存；② 点保存但分区归属错（上述 bug）。排障第一步：读生产 `options/mp-sql-visualizer.xml` 查 `name="enhancedCaptureEnabled" value="true"` 是否存在；只有 confirmed=true 无 enabled = enabled 没写入。
- **判定「增强是否生效」的证据链**：① 安装包含 agent（zipfile 查 `log/agent/AgentManager` 类 + 内嵌 `mps-sql-agent.jar` 资源）——2026-09-16 实测生产装的 v2026.9.18-beta.1.jar 与 runIde 沙盒 jar 两样都有=分支构建；② 生产 `idea.log` 出现 AgentManager/attachToPid/SqlCaptureGate 痕迹；③ 设置 XML 有 enabled=true。三者缺一即未启用/未挂载。
- **2026-09-16 链路全通（提交链 711370a/25f0a77/33f96fb/4f69780/fb408b9）**：诊断日志逐层定位三根因全修（resolvePid 三步回退 / Jsons 首逗号 / 传输脆弱点），用户真机确认「可以正常捕获 SQL 了」。
- **T-AgentListenSync 已交付（fb408b9，计划 docs/开发计划/20260916-1200）**：监听开关联动应用内采集——停止监听下发 CONFIG `disabled=true` 真停采（此前 agent 持续采集传输、到 IDE 侧被 `buffer.paused` 丢弃=白采集白传输），开启即恢复。三边界：B1 attach 成功后按当前监听状态下发初始 CONFIG（新 agent 默认 `disabled=false`，监听关着启动项目曾白采集）、B2 `pushDesensitize` 不写死 `disabled=false`（否则监听关闭时改设置会误重置为采集）、B3 开启监听 ≤1s 轮询窗口内首条 SQL 可能丢（接受不修，与现有控制台桥 attachFilter 轮询行为一致）。**量化结论**：应用侧节省≈一条栈快照（5~20μs/SQL），日常场景无感、高频 1000+/s 约 1~3% 单核——定位为「语义正确性修正」而非性能优化。agent 侧零改动（CONFIG 通道 + `isDisabled()` 短路现成）；`AgentListenSyncTest` 3 例。
- **增强捕获源码跳转定位（T-AgentSourceJump 已交付 a474447，计划 docs/开发计划/20260916-1300）**：实测「跳转报源码文件未找到：com/github/pagehelper/PageInterceptor.java、列表每行位置误显该插件类」→ 根因=源码定位靠**硬编码框架前缀黑名单**，漏收 PageHelper（插件帧在 JDBC 执行栈框架层，被误判为用户帧）。三层修复：**①L1 来源过滤（主修复）**=开发态用户业务类来自目录（target/classes）、第三方依赖恒来自 JAR，按 `ProtectionDomain.codeSource` 判帧来源，JAR 帧一律跳过——**与类名无关，根治黑名单打地鼠（任何未收录第三方插件不再误选）**；②L2 补齐 pagehelper/mybatis/jsqlparser/apache/alibaba/redis 等前缀（**刻意不收 cn.hutool. 等通用工具库**——用户业务代码可能直接调用，误收即跳错）；③L3 候选帧（协议新键 `cands`，≤3 条栈序）+ IDE 侧 `resolveCandidateJump` 回退（只有 IDE 知道工程内有哪些文件）。**安全网**=L1 过滤后无候选（fat jar 运行等）回退首个非框架帧=既有行为，杜绝「过滤到空→跳转能力消失」。agent 侧 5 例 + IDE 侧 6 例测试。
- **同轮三项收口（e0acf1d，用户实测反馈驱动）**：①**「(-1 rows)」根因=JDBC 语义写反**——`execute()` 返 true 表示首结果为 ResultSet（此时 `getUpdateCount()` 恒 -1 无意义）、返 false 才是更新计数；采集侧条件误写 `Boolean.TRUE` 致每条 SELECT 上报 -1。修正为按 true/false 正确语义取值 + 负值一律不上报（agent 不发、IDE 不渲染双端守卫）。②**移除「[⚡增强]」来源角标**（用户明确要求；增强已是默认通道，逐行标来源属噪音，勿再回加）——目标库角标/耗时/影响行数保留。③**耗时排序按钮在增强下不可见且排序失效**=按钮可见性与排序取值原先只看「日志文本解析出的耗时」（p6spy/druid 行），而增强通道耗时是结构化 `executionTimeMs`、文本内无 → 按钮恒隐藏、排序取值全空；改结构化优先 + 文本兜底（`durationOf()` 统一口径）。④agent 事件 JSON 还承载 `cands`/`url` 等结构化字段，**任何按「文本形态」判定的逻辑在增强通道下都需改用结构化字段**（本类问题已两犯，见 [[pitfall-test-fixture-shape-mismatch]]）。
- **诊断方法论沉淀**：agent 链路的每个静默返回点都必须有日志（本次靠补日志三轮定位）；跨进程协议两端必须有往返（round-trip）格式守卫测试；**给 data class 加字段后全量首选必先清 build/classes+build/kotlin**（本轮 248 例 NoSuchMethodError 陈旧产物劫持实录，清后复跑即绿）。
- **mpsv-m56 沙盒路径（本次 runIde）**：build.gradle.kts 顶层 `intellijPlatform {}` 有临时 `sandboxContainer = layout.buildDirectory.dir("test-sandbox-listparam")`（T-ListParamCollect 并行隔离）→ 沙盒日志在 `build/test-sandbox-listparam/mybatis-plus-sql-visualizer/IU-2024.3.7.1/log/idea.log`，**不是** `.intellijPlatform/sandbox`（后者 9-12 后未再更新）。用户真机验证的 demo=easy-code-demo（Maven、包 cn.song.dasycodedemo、JDK8，`F:/code/Java/Gitee/SongRenShuo/easy-code-demo`；首次跑报「找不到主类」= target/classes 空未编译，需先 mvn compile）。
- **用户环境路径（日常 IDEA 2026.2 生产实例）**：日志 `%LOCALAPPDATA%/JetBrains/IntelliJIdea2026.2/log/idea.log`（滚动 idea.1~3.log）；插件安装 `%APPDATA%/JetBrains/IntelliJIdea2026.2/plugins/mybatis-plus-sql-visualizer/lib/`；设置 `%APPDATA%/JetBrains/IntelliJIdea2026.2/options/mp-sql-visualizer.xml`（@State 名 MpSqlVisualizerSettings）。用户装分支构建走 buildPlugin zip 解压进 lib/。
- **行为对照（诊断锚点）**：agent 通道条目 executionTimeMs 恒有值（durationNanos/1e6，最差 0ms）+ 目标库角标（JdbcUrlDialects）+ 源码位置实为用户业务类；控制台桥通道耗时 null 时渲染灰「—」（formatDurationFragment）、无目标库角标。**来源角标（原 `[⚡增强]`）已于 e0acf1d 按用户要求移除，勿再回加**——增强已是默认捕获通道。用户报「耗时没显示/控制台没日志就没信息」= 走的仍是控制台桥，agent 未接管。
- **遗留缺口**：`enhancedStatusLabel` 已由死代码改为无标签状态行（92bcf6f 删「增强捕获状态：」标签按用户要求，状态值仍显示）——但仍是静态文本，无 AgentManager.online 联动刷新；待补在线态轮询。增强捕获三行已归位「源码位置捕获」分组（settings.log.tab.sourceCapture，与拦截器注入同组）。
- **分支推送**：2026-09-16 09:26 用户已从生产 IDE push `feature/t-m56-01-agent-base` 至 Gitee（此前「未推送远程」已过时）。**M56 仍未反向合回 master/dev，反向合并前不能在 master 改，改动全在 mpsv-m56 worktree 分支上。**

## 免注入拦截器类：文件通道方案（2026-09-15 分析产出，未立项）

**问题**：当前轻量捕获要往用户项目写两个类（`SqlSourceInterceptor.java` + `SqlVisualizerConfig.java`，模板见 `log/SourceInterceptorTemplates.kt`，注入器 `log/SqlSourceInterceptorAutoInjector.kt`），污染用户代码。**根因**是插件（IDE JVM）跨不过进程边界，唯一共享通道是应用输出——现状是标记行打 stdout 再由 `SqlLogConsoleBridge.attachFilter` 挂平台 `Filter` 抓回。

**方案**：不改应用代码——agent 织 `java.io.PrintStream.println`（JDK 内部类，premain 也能 retransform），回调里过滤 `SQLVIS-SOURCE|` 前缀的标记行写文件；插件读该文件写入条目 `sourceFile/sourceLine`。SQL 文本与参数仍走 stdout + 现有三解析链，只有 file:line 换通道 → 增量最小。hook 目标选 PrintStream 而非 `Driver.connect` 的理由：Framework 无关（不需用户项目有 MyBatis/JDBC），且与「M56 生效即禁双写」的红线不冲突。

- 挂载形态定夺：**`-javaagent` 是长期形态**（JEP 451 之后 attach/动态加载收紧，启动加载不受影响），attach 降为「不想重启就立刻看」的快捷档；两者共用同一 agent、只差入口。用户项目侧零类、零代码，代价是 Run Configuration VM options 多一行参数。
- **2026-09-16 链路全通收口（33f96fb→4f69780 五提交）**：诊断日志逐层剥出三根因并全修——① resolvePid 两步全失败：243 平台 KillableProcessHandler **无 getNativePid**（反编译 util.jar 证实，「243 已有」记忆有误）+ `getProcess().pid()` 反射 invoke 抛 IllegalAccessException（**ProcessImpl 是 JDK package-private 类**，测试匿名子类可访问故绿、真机即挂）→ 改直接接口调用虚分派 + `OSProcessUtil.getProcessID(Process)`（util.jar 稳定 API）兜底；② **真凶=agent Jsons.writeEvent 给首成员也补前导逗号**，产出 `{,"sql":...}` 非法 JSON，IDE 侧 parse 全量失败静默丢弃（SQL/参数/耗时其实已全部采集到位）→ 统一首字段标志重写 + 新增 JsonsEventFormatTest 格式守卫 4 例（堵「两端各自测试皆绿、合流即断」的往返缺口）；③ 传输加固：超限帧丢弃继续不杀会话、agent 单事件编码失败丢事件不断连接、会话读循环退出补异常栈日志。agent 挂载/在线/事件链路已实测走通（11:13 沙盒：pid 解析→loadAgent(0) 容错→在线 true→事件到达仅因 JSON 坏被丢）。`Connection reset` 掉线=用户停应用，正常生命周期。
- 插件侧复用面已很足：`RunContentDescriptor.getProcessHandler().getNativePid()`（**仅 262+**，243 无此方法）；`attachFilter` 已反射取得 `console.getEditor()` + `docStampReader`/`line0Reader`；`.idea/sql-visualizer/*.log` 路径算法在 `SqlLogAutoSaver.configure`；`AgentArgs` 参数下发已支持 `k=v`。
- **路由必须按 pid**（如 `file=<项目>/.idea/sql-visualizer/agent-<pid>.marker`），禁用「扫目录取最新文件」——多运行配置/多模块会串台。
- `-javaagent` 自动注入路线：**`JavaProgramPatcher`（`com.intellij.execution.runners`，注册在 `com.intellij.java.programPatcher` EP）在 262 实测仍在**——DebugTools 生产用它、2026-09-15 本机核实类在 `plugins/java/lib/modules/intellij.java.execution.jar`，`JavaParameters` 同 jar、`ParametersList`（add/addAll/prepend/replaceOrPrepend…）在 `lib/util.jar`。运行时按运行配置 JDK 版本决定加哪些参数，比 `RunConfigurationExtension` 更具体，是首选路线。注意 DebugTools 的 IDE 侧模块 `debug-tools-idea` 在 2026-02-03「项目结构」提交（8608070a，删 20k+ 行）已从开源仓剥离 → **此后 IDE 侧实现闭源，只能看 2026-02 之前的形态**。
- 与 M56 增强版不冲突：同一 agent、两套 hook 目标，增强开启时桥通道整体旁路（现成设计）。

## JEP 451 真实语义（2026-09-15 纠正，勿再照抄旧说法）

**「JEP 451 之后动态挂载会被禁」是错的**。JBR 25 实际行为是**两条截然不同的路径**：①**启动加载**（`-javaagent`，或 JAR manifest 带 `Launcher-Agent-Class`）→ JVM 认定 agent「预期内」，后续 attach 该 agent **不告警**、不受限；②**从未经 `-javaagent` 加载过的 agent 被 attach** → 才告警，且 `-XX:-EnableDynamicAgentLoading` 时可硬拒。

结论：**走 `-javaagent`+premain 的路线完全不需要 `-XX:+EnableDynamicAgentLoading`，JEP 451 全程不参与**——这是免注入方案比 attach 优先的根本理由（不是「attach 快没了」）。attach 只有当 agent 从未启动加载时才需要该逃生参数。

DebugTools 的两路互补形态可照搬（思路可抄，**代码是 GPL 3.0 不能抄**）：

- **路径一 premain（主力，SQL 字节码增强只在这里）**：`DebugToolsJavaProgramPatcher extends JavaProgramPatcher`，无条件给运行配置加 `-XX:+EnableDynamicAgentLoading`（JDK 17/21；25 再加 `--enable-native-access=ALL-UNNAMED`），再拼 `-javaagent:<path>=<args>`；JDK8 不支持时只写标志文件不挂。落地提交 `4bd35743`（2025-06-18，标题即「打印 sql 支持 jdk21」），同提交把 ByteBuddy 换内嵌 Javassist。
- **路径二 agentmain（兜底，即时体验）**：`DebugToolsExecutionListener.processStarted` 取 `KillableColoredProcessHandler.Silent.getProcess().pid()` → 后台线程轮询 agent 侧写的「就绪标志文件」（`~/.debug-tools/auto_attach.txt`，2026-07-22 `14c82267` 补 `auto_attach_<pid>.txt` 做多进程隔离）→ 标志为 1 才 `VirtualMachine.attach` →`loadAgent`。**「就绪标志文件 + pid 轮询」是比定时 sleep 靠谱的挂载时序，值得照搬**。
- 但它的 agentmain 只 `createExtensionJar()`+`startServer()`，**不挂 SQL transformer**（=我们计划记的半成品陷阱）→ 它的 SQL 增强只在 premain，官方文档也明写「修改打印格式后需重启应用才能完成驱动字节码增强」。即：**它不受 JEP 451 影响（功能没丢，靠显式加宽参数压告警），但形态正是被盯上的那一类**——而参数代价落在它自己身上，不在用户代码。

## 本机关键环境（2026-09-15 实测）

**IDEA 2026.2 自带 JBR = 25.0.4**（`jbr/release` 的 JAVA_VERSION）——即 IDE JVM 已在 JEP 451 生效区间。

## 技术基座（2026-09-07 本机实测，已写死到计划）

- **attach 端到端跑通**：JBR25/JDK17/JDK8 交叉 attach+loadAgent 成功，retransformSupported=true；Windows 命名管道机制；**JDK8 目标首次 loadAgent 即抛 AgentLoadException: 0**（非仅二次）——幂等必做。
- **选型**：裸 ASM 9.10.1（~400KB）首选（Arthas ByteKit 同路线）；agent jar 独立自包含落盘 + deleteOnExit；ASM 须 shade/relocate（平台已 relocate `org.jetbrains.org.objectweb.asm`）；agent 用 Java 写免 Kotlin stdlib。
- **拦截层级**：JDBC 层主体（prepare/setXxx/execute 三族）+ MyBatis 层补 ms.getId()（Executor 首参）。**agent 生效即跳过模板注入**（双写冗余）。

## 架构与定位

AgentManager（project 级）→ 惰性 attach → agent 采集队列（不阻塞）→ loopback Socket → AgentEventAdapter（纯函数）→ **SqlLogEntryBuffer.addEntry 唯一汇聚点**（`log/SqlLogEntryBuffer.kt:67`，桥在 `log/SqlLogConsoleBridge.kt:337,341`）。双通道并存、增强接管失败回落。对外定位「**增强捕获**」，对用户不出现 agent 字样。

**默认值定案（2026-09-16 用户定案，此前「默认关」已作废）**：增强捕获**默认开**（`enhancedCaptureEnabled=true`）、脱敏**默认关**（`enhancedCaptureDesensitize=false`，即默认采集真实参数值）。安全四件套随之收窄为**三件**（注入确认 / 脱敏可开 / 禁用不卸载），"默认关"不再保留。

**注入确认时机（默认开启后的必经改造，否则死开关）**：确认原仅由设置页手动勾选触发（`addActionListener`），默认开启后用户从不勾选 → `enhancedCaptureConfirmed` 永不达成 → `isEnhancedActive()` 恒 false → agent 永不 attach（表现为「默认开了却没捕获」）。改为**首次挂载（首次运行项目）时**在 EDT 弹一次确认框：`onProcessAppeared` 拆 `isEnhancedEnabled`/`isEnhancedConfirmed` 两级判定，未确认走 `requestConsentThenAttach`（`consentProvider` seam 供测试驱动，headless 默认拒绝）→ 同意置 confirmed 持久化并继续挂载，拒绝本次不注入、下次运行再询问，两种情况控制台桥照常回落。设置页手动勾选确认路径保留。

## 关键关联

- 消费端模型：`LogSqlEntry`（unified 后的 `log/LogSqlModels.kt`）在 master **已有** `mapperStatement`/`errorText`/`requestTag`/`updateCount` 与 `SqlSource.AGENT`、`SqlSourceMarker` 3~5 段宽松化——「master 不存在 M56-00 字段」的旧结论已过时（消费端先导已合 master）。
- 免注入方案所替代的两个类：`SourceInterceptorTemplates.kt`（模板）+ `SqlSourceInterceptorAutoInjector.kt`（注入）；跳转失败引导弹框在 `log/SourceCaptureGuide.kt`，设置页开关 `autoInjectInterceptorCheck`。
- 三竞品调研报告 1617(CoolRequest)/1700(DebugTools) 已提交；consoleFilterProvider EP 双轨设计沿用。
- 真机验证走 [[runide-demo-verify-workflow]]；平台 jar 核实走 [[idea-262-platform-jar-verify]]。