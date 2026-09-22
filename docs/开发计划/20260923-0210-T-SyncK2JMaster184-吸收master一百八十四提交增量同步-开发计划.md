# T-SyncK2JMaster184 开发计划——master 8f51b9e..c07b8e3 增量同步

> 立项：2026-09-23 02:10，用户指令「切换到 feature/kotlin-to-java，将主分支最近更新的内容同步过来并解决冲突、全量测试并修复问题、确保插件构建校验通过；同步功能不是合并分支，先出开发计划再实施」。
> 工作树：`F:/code/Java/Gitee/SongRenShuo/mybatis-plus-sql-visualizer-kotlin-to-java`（分支 feature/kotlin-to-java，起点 1c3dce0）。
> 锚点：`.port-sync` synced_master = **8f51b9e**（2026-09-21 T-SyncK2JMaster115 收官），本轮目标锚点 = **c07b8e3**（origin/master 当前 tip，与本地 master 一致）。

## 1. 背景与目标

k2j 分支把 master 的 Kotlin 生产代码手工翻译成 100% Java，坚持「同步功能，不合并分支」：master 的 .kt 绝不进生产树，共享文件（build.gradle.kts / plugin.xml / bundle / CHANGELOG / MEMORY / 变更记录 / 架构文档）走手工 union。增量范围 `8f51b9e..c07b8e3` 共 **184 提交**（含上一轮明确留下的 22 个在途提交，本轮一并吸收）。

本轮交付四件事：①生产/测试/资源/文档全量移植且编译绿；②全量测试修复到 0 失败（testShards + testDatabase + agent 子项目）；③构建校验通过（buildPlugin 出包 + verifyGate 三目标 Compatible）；④锚点推进与台账/计划回填写（C8 收官）。

## 2. master 增量功能线（184 提交归组）

| 功能线 | 大致提交 | 落点 |
|---|---|---|
| T-AgentHeartbeat/Reconnect/DiagWrap/ProxyCL 心跳批次 | 4990831..3cb9dac 约 22 | IDE 侧 log/agent 2 件 + agent 子项目 8 生产件 + 测试（心跳按真实流逝计/循环 accept 重连/诊断行折行/代理 loader 跟随） |
| T-AgentResiduals（O-56 R1b/R2/R3/R4/R5/R7） | 0abc1bb 等 | AgentManager/AgentProtocol/AgentState/ConnectionHandler/SqlHookCallbacks；停采改执行级短路、回执超时分会话、心跳容差 2x、真机级探针包 gradle 任务（9a2a0f5） |
| O-48 T-ExplainChainHardening | 83a7e18 | sql/ExplainGate+ExplainSupport（新建）、ExplainIndexAdvisor（修 PG 索引建议永不触发）、McpExplainFormatter、SqlVisualizerExplainTool |
| O-49 T-ErrorLocateUsability | 7eb519a | ExecutionErrorInfo、SqlResultPanel/SqlLogPanel（先解析后显隐 + Oracle 位置形态 + 越界钳制；复核㉕ 补 Oracle 驱动 `, Position : N`） |
| O-47 T-SqlSplitterPlsql | 2158a4d 等 | SqlStatementSplitter（PL/SQL 块深度；复核③ END IF/END LOOP 内层终结符） |
| O-51/O-52 方言与 identity | df7d177、f543ae1 | Dialect（H2 折叠族 UPPER）、rollback 两件（DM8 也需 identity 包装） |
| O-59 回滚链 identity 包装 | 32ff1f5、e1b5136 | RollbackSnapshotStore（补 dialect 实参）、RollbackSqlGenerator（解析器补自增标记 + 消费点按表名解析） |
| O-57/O-58 HQL 别名列映射 + 列名校正 | 4733ce6、60812a7 | HqlConverter、JpaAliasTableResolver（新建）、JpaFromEntityMapper、JpqlConverter、ColumnNameCalibrator（新建）、EntityResolver、JpaEntityResolver、JpaMetadataReader、DbTableSpecAdapter |
| T-TemplateKeyDrop 键位摘除 | aefc140、e6ce2c5 | actions/SqlTemplateGroup（新建）、SqlTemplateShortcutConflictCheck（删除）、config 7 件、plugin.xml、bundle、README |
| N6 JOIN 图原生路径连线 | f16017e | ui/diagram/nativefw/db 4 件（JoinFkRefTables 新建；两参建边契约 refTable=null） |
| T-DynUpdateWriteSafe | b7ac235、6e2a34d | update/DynamicInstallSupport |
| T-PerfSweep6 + 独立复核批次㉔~㉝ | 152c104..a720919 等 | 分散：T-PS6-05 端口放弃可观测、⑭ PSI 批量链自增列（BatchOperationConverter/CrudSqlGenerator）、⑯ ACP 绕行（AcpIdeaMcpTransportProviderImpl）、⑰ 元守卫、⑳ DM TEXT 档、㉘ 半包/H2、㉚ DedupErrorList（T-Round10-07）、㉝ checker 三项 |
| 走查修复 | 0e8c222 | config/SectionConfigurable（组文案改由 class 取 bundle） |
| 纯文档（计划/验收/报告/台账） | 其余约 60 | docs 42 件 + CHANGELOG/MEMORY/变更记录 union |

## 3. 文件级移植矩阵（生产 66：7 新建 + 1 删除 + 58 增量；测试 68：24 新建 + 1 删除 + 43 增量；agent 17；资源 3；构建 2；dev-support 11；docs 42）

### W1 低层/基础面（22 件，含 4 新建）
sql：Dialect、ExplainGate*、ExplainSupport*、rollback/RollbackSnapshotStore、rollback/RollbackSqlGenerator；util：ExplainIndexAdvisor、SqlStatementSplitter；xml：DynamicContext、SecureXml；model：BatchModel、TableInfo；converter/jpa：HqlConverter、JpaAliasTableResolver*、JpaFromEntityMapper、JpqlConverter；psi：BatchOperationConverter、ColumnNameCalibrator*、EntityResolver、jpa/JpaEntityResolver、jpa/JpaMetadataReader；generator：CrudSqlGenerator、CrudTableSpec；database：DbTableSpecAdapter。（*=新建翻译）

### W2 捕获链/配置/MCP 面（25 件，含 1 新建）
log：CallerSourceLocator、CaptureDiagnostics、LogStartupCapture、LogToSqlPanel、SourceInterceptorTemplates、SqlConsoleBridgeFilterProvider、SqlLogEntryBuffer、SqlLogManager、SqlLogManagerLookup*、SqlLogPanel、SqlSourceInterceptorAutoInjector；log/agent：AgentManager、AgentProtocol；config：PluginSettings、PluginSettingsFolderConfigurable、PluginSettingsPanel、ProjectPluginSettings、ProjectPluginSettingsConfigurable、SectionConfigurable、SettingsIO；acp：AcpIdeaMcpTransportProviderImpl；mcp：McpExplainFormatter、McpSchemaContextService、McpToolsetSupport、SqlVisualizerExplainTool。

### W3 高层 UI 面（15 件，含 1 新建 + 1 删除）
actions：SqlTemplateGroup*（新）、SqlTemplateShortcutConflictCheck（删）；ui：DegradationBanner、PrewarmAwait、RollbackSqlDialog、SqlPreviewPanel、SqlPreviewService、SqlResultPanel；ui/diagram/nativefw/db：JoinFkRefTables*（新）、JoinFkRegistry、JoinVirtualFkProvider、NativeDatabaseDiagramSupport；ui/highlight：DatabaseSchemaResolver；ui/inlay：InsertColumnInlayPlanner、PreviewInsertColumnInlayProvider；update：DynamicInstallSupport。

### W4 agent 子项目直取 + 测试移植
agent 子项目 17 件全 Java 直取（生产 8：AgentState/ConnectionHandler/ProxySupport/SqlHookCallbacks/StatementHandler/EventBus/SenderLoop/StackLocator；测试 9：4 新建 + 5 增量）。src/test 68 件：24 新建 + 1 删除 + 43 增量——.kt 测试可直取（测试树混放），但调 Java 生产类的调用点须过互操作适配（禁具名实参、`INSTANCE`、顶层函数走 `XxxKt`），守卫测试按惯例适配扫描根到 `src/main/java` + `.java`。

### W5 资源/构建/文档
plugin.xml（键位摘除后 action 声明面变化，核对）、bundle 两语言、build.gradle.kts 与 buildSrc/mpv-verification.gradle.kts union、dev-support/e2e-fake-ide 11 件直取、docs 42 件直取 + CHANGELOG/MEMORY/变更记录/架构文档 union（两侧行集合校验）。

## 4. 波次与提交链

- 并行登记：`register --name t-synck2j184 --sandbox test-sandbox-k2jsync184`；gradle 命令带独立沙盒与 `-Pkotlin.compiler.execution.strategy=in-process -Dorg.gradle.jvmargs="-Xmx3g -XX:MaxMetaspaceSize=1024m"`。
- 提交链八段：C1 立项计划（本文件）→ C2 构建链 union → C3 W1 → C4 W2 → C5 W3 → C6 agent+测试 → C7 资源/文档 → C8 收官（锚点推进 + 沙盒临时行还原 + 计划 §9 回填 + 变更记录台账行）。
- 波内并行 ≤3 个 general-purpose 子代理；子代理只跑定向测试、明令不跑全量/不 clean/不 --stop；「每完成一件可提交的就立即提交」不适用波内（按波统一提交，子代理只改自己写集）；回填写子代理工作树副本口径不适用（单工作树作业，子代理直接改 k2j 树并自行编译定向）。
- 每波收口做一次中央编译（compileJava + compileTestJava + compileTestKotlin），跨批依赖在编译门暴露，带 -parameters。
- 全量门禁：全部波次+移植完成后单派只读收口代理跑权威全量（testShards + testDatabase + :sql-visualizer-agent:test + buildPlugin + verifyGate 三目标），主控并行写文档收尾；收口期间不碰 src/main。testDatabase 前置：确认 runIde 沙盒 jdbc-drivers 种子源在位（记忆第 11 条），缺则从 master 工作树复制。

## 5. 移植规则（既定清单全量生效）

记忆 `project-kotlin-to-java-port-sync` 12 条系统性坑逐条生效，重点：①互操作族（禁具名实参/INSTANCE/XxxKt/record 无 get 前缀/copy 手工重建/泛型 LUB 显式标注）；②Kotlin 函数类型字段落 Function0/Function1；③受检异常透传用 sneakyThrow；④私有字段遮蔽改名 xxxState；⑤默认参落显式重载；⑥守卫测试扫描根适配 src/main/java + .java；⑦elvis 内层 null 合并不许漏；⑧改名全仓 grep 旧名；⑨新枚举产出点写 `EnumName.CONST` 限定形态；⑩测试红先看 XML 计数与时间戳（BUILD FAILED 三形态=没跑）；⑪testDatabase 种子链前置；⑫Companion 三件套 + `-parameters` 不可移除。结构性决策两处勿改：fatal 透传 `throw (Error) t`；fatal 守卫两段式基线（落地集 6 文件判定数精确相等 + 未判定站点总量基线 615——本轮 master 若有新增 catch(Throwable) 站点，按 T-SyncK2JMaster115 惯例重登记基线并留移植注）。

共享文件 union 后做两侧行集合校验（k2j 台账行 byte-exact 比对，不只看行数）；CHANGELOG `[Unreleased]` 与 `### Changed` 子标题每版本唯一。

## 6. 待验证前提（实施者先证伪再动手）

1. **agent 子项目 17 件为纯 Java 直取**——本轮 agent 改动全部在 .java，前提是 master 未在这些提交里给 agent 引入新 .kt；实施前 `git diff --name-only 8f51b9e..c07b8e3 -- sub-projects/sql-visualizer-agent | grep -c '\.kt$'` 应为 0。
2. **测试树 24 件新建可直取**——前提是它们引用的生产 API 在 k2j 侧同名同签名（翻译后按 Java 形态）；个别 .kt 测试可能因互操作编译不过，允许改写为 .java 等价（上轮先例：无，但 309 轮有 .java 增量合并先例）。
3. **锚点差恰为 184 提交且 c07b8e3=origin/master=本地 master**——已核实（git worktree list master=c07b8e3；fetch 无新）。作业期间 master 再前进则按惯例把 post-anchor 增量一并吸收或如实登记留下轮。
4. **fatal 未判定站点基线 615 会变**——本轮 log/agent、mcp、execute 等文件有 runCatching 增量，收口时以守卫读数重登记（上轮 597→615 先例），不许硬凑旧基线。
5. **verifyGate 的 for-removal/deprecated/internal 读数应与上轮逐项相同**（243:4+10+1、253:6+13+1、262:7+67+1）；experimental 因本轮 `codeInsight.hints` 之外的增量可能变化，逐条归因即可。
6. **plugin.xml 的 action 声明在 T-TemplateKeyDrop 后有删除**（三动作键位摘除 ≠ action 删除；冲突检测类删除后其 EP/引用面须全仓 grep 清理）——实施 W5 时以 master 侧为准逐行 union。
7. **master 侧 dev-support/e2e-fake-ide 为直取无关构建**——前提它不进 jar（不在 sourceSets 内）；W5 核对 build.gradle.kts 有无对其的新引用。

## 7. 风险清单

- **跨波编译依赖**：W1 的 Dialect/ExplainGate 被 W3 的 SqlPreviewPanel 与 W2 的 mcp 引用；C3~C5 每波中央编译不追求全绿（依赖方未到属预期），C6 后必须全绿。
- **并行会话竞争**：登记表 12 条活跃；共享 transform 缓存被清理的第五/六形态（记忆回归假失败清单）——探缓存后等待重试，勿删共享缓存；k2j 工作树沙盒 jar 被自己遗留孤儿 JVM 锁（第五形态）——按命令行含本工作树路径识别只杀自己的。
- **测试-生产语义错位**：锚点若在作业中前移，测试按新 HEAD 直取而生产未到 → 运行期红；对策=锚点差固定为 c07b8e3，post-anchor 增量按第 6 节前提 3 处置。
- **O-59/O-52 的 SQL Server/DM 语义**：identity 包装改动涉及方言默认值翻转（「DM 不包装」被真机证伪），翻译时以 master 侧实现为唯一权威，勿按 k2j 旧注释「修正」回去。
- **守卫类适配遗漏**：本轮新增守卫（BannedApiSourceScanTest 增族、元守卫收紧、探针 gradle 任务 9a2a0f5）扫描根若漏适配 = 恒真假绿；每件守卫测试按第 5 节第 ⑥ 条核对。

## 8. 验收标准

1. k2j 生产树 0 个 .kt 新增（`git diff --name-only 1c3dce0..HEAD -- src/main/kotlin` 为空）；`SqlTemplateShortcutConflictCheck` 的 Java 件删除且全仓无引用。
2. 中央编译三目标绿（compileJava/compileTestJava/compileTestKotlin）。
3. 全量 testShards 0 失败 0 错误（跳过数与基线口径一致）；testDatabase 0 失败（环境性在册项除外，须逐条归因）；`:sql-visualizer-agent:test` 全绿。
4. buildPlugin 出包（版本号随 master 侧 update.json/build 制品口径）；verifyGate 三目标 Compatible。
5. `.port-sync` 锚点推进 c07b8e3，note 重写为本轮记录；变更记录台账行 + MEMORY 索引行更新；本计划 §9 回填。
6. 反向验证两处：删除一件本轮翻译的修复分支须红（任选守卫可判的，如 ExplainGate 首关键字边界或 DM identity 包装）；「仓内 0 违规」不构成精度证据。

## 9. 实施记录

（作业中回填）
