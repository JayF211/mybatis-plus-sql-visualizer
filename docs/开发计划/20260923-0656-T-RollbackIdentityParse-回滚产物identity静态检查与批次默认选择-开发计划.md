# T-RollbackIdentityParse 回滚产物 identity 静态检查与批次默认选择 — 开发计划

- 日期：2026-09-23 06:56
- 任务编号：**O-64**（第三轮全景审计 §8.1；即 `docs/验收文档/20260920-待分析问题清单.md` §P19 的立项收口）
- 来源：P19（真机走查第八轮登记；该清单现挂在 origin/master，本地 t-round10 尚无此节——实施前先让 O-62 §三.1 快进本地 master）
- 性质：正确性（用户可见误报 + 产物执行不完整）
- 影响面：仅 SQL Server / DM8 的 identity 包装产物（O-52 + O-59 链路）；不会静默成功（失败可见），但会让「回滚」这个核心安全网在关键数据库上体验残缺
- 实施分支：自 master 拉任务分支
- 验证基线：origin/master `133b4a2`

---

## 一、缺陷形态（P19 两半）

1. **静态检查误报**：含 `SET IDENTITY_INSERT [user] ON; INSERT ...; SET IDENTITY_INSERT [user] OFF;` 的回滚产物走无界写静态检查报「无法解析」——JSqlParser 对 T-SQL `SET` 语句的解析能力不足，P19 原文只记「无法解析」，报错点与异常形态未钉。
2. **Statements 默认选择**：执行对话框的语句选择默认只勾第一条（= 第一条 `SET IDENTITY_INSERT ... ON`），用户不逐项勾选直接执行时只切了开关、INSERT 不跑——O-59 真机验通走的是人工全选路径，默认路径是坏的。

## 二、待验证前提（实施前先证伪）

1. **JSqlParser 对 `SET IDENTITY_INSERT` 的实际行为**：抛错位置与异常形态需先复现钉住（是 parse error 还是 AST 缺节点）——它直接决定 §三.1 选剥离还是选豁免。
2. **静态检查面的输入源**：该检查是否只服务插件自产产物，还是同时服务用户手写输入——决定剥离逻辑的误伤半径（自产形态封闭，剥离安全；用户输入则要收窄判据）。
3. **Statements 默认选择的代码位置与现态**：`ui/RollbackSqlDialog.kt` 执行链（O-59 后带 identityColumns 消费逻辑），选择框初始化逻辑需锚点定位后定改法。
4. **DM8 侧同形确认**：DmDialect 与 SqlServerDialect 共用 `identityInsertWrapper`（`f543ae1`），同病推定成立——测试覆盖面核对，确认修一处双方言受益。
5. **真机环境**：O-59 曾用真 SQL Server 2022 容器验通，但 docker daemon 此前一度未运行——实施时复测容器可用性，不可用则真机条目按「环境受限」登记。

## 三、方向（两半各一）

1. **静态检查**：优先「检查前剥离 `SET IDENTITY_INSERT` 形态再解析」——对齐 O-47 的 DELIMITER 感知先例（产物我方自产、形态封闭，剥离安全）；「无法解析」降级为提示是备选（D1）。
2. **Statements 默认**：插件自产多语句产物默认全选。范围 D2：仅 identity 包装产物（倾向）还是一切多语句产物——倾向前者，普通多语句保持现态避免行为突变。

## 四、验证与反向验证

- 单测：identity 产物过静态检查不再报「无法解析」（正）；真写语句仍被拦（反向：摘掉剥离分支 → 必红，贴两次读数）。
- Statements 默认全选后的逐条执行读数（沿用对话框层既有测试夹具形态）。
- 双方言单测各一（SQL Server + DM8 形态产物）。
- 真机口径：容器可用则沿 O-59 流程复验「两行原值恢复」全流程（默认路径、不再人工全选）；不可用则登记环境受限，并在验收文档显式写为在册阻塞而非已通过。

## 五、决策点

| # | 决策 | 选项 | 倾向 |
|---|------|------|------|
| D1 | 剥离 vs 豁免 | A 检查前剥离（形态封闭自产）/ B 「无法解析」降级为提示（误报仍在，只是变软） | A |
| D2 | 默认全选范围 | A 仅 identity 包装产物 / B 一切插件自产多语句产物 | A |

## 六、风险

- 剥离逻辑误伤用户手写同类语句：前提 §二.2 核实输入源；若检查面同时服务用户输入，判据收窄为「仅 `SET IDENTITY_INSERT` 精确前缀形态」。
- 与 P18（成功 DML 被误标执行失败）同链不同案：本计划不碰 P18，避免两案互相污染读数；P18 取证走走查车道（第三轮报告 §8.2 候选池）。
- 触达执行链：定向测试按触达面加该链定向类，不跑全量（三档分层）。

## 七、实施记录

实施：2026-09-23，工作树 mpsv-rb-identity（分支 t-rollback-identity），提交 `c718e3d`（D1）+ `8eb2c0a`（D2）。

### 前提证伪结论（逐条）

1. **JSqlParser 异常形态＝解析期抛错，非 AST 缺节点**。离线复现（JSqlParser 5.3 与仓内同版本，JBR 21 运行）：`SET IDENTITY_INSERT <表> ON|OFF` 抛 `JSQLParserException` 包 `ParseException: Encountered unexpected token: "ON" "ON"`（ON 位即败，`SqlAstParser` 已开的 `allowSquareBracketQuotation` 救不了该尾部）；方括号/裸名/双引号/DM8 产物/单独 `SET … ON` 五形态全部同败，`SET @x = 1` 正常解析为 SetStatement。**剥离（D1=A）为唯一可行路线，豁免/AST 修补不适用**。
2. **静态检查面同时服务用户手写输入**。`UnboundedWriteDetector.detect` 的消费面：三处确认流调用点（`LogToSqlPanel:268`、`SqlPreviewPanel:1077 executeAdhocSql`、`:1288 executeSql`）+ MCP `SqlVisualizerCheckSqlTool`。按预案判据收窄为「`SET IDENTITY_INSERT` + 单表引用 + `ON|OFF` + 可选分号 + 干净边界（文本首/`;`/换行后）」精确形态；剥不到＝维持改前 fail-closed，失败方向安全。
3. **Statements 选择框不在插件 UI，是原生弹层**。执行链 `executeAdhocSql → SqlExecutor → DataSourceSelector.executeOnConsole` 经反射走原生 `JdbcConsoleProvider.findScriptModel(psi, psi, editor, execOption)`；多语句行为由 `DatabaseSettings$ExecOption.execInside/execOutside` 决定，默认 `EXECUTE_INSIDE_SHOW_CHOOSER`（javap 实测值=1）即 P19 弹层的来源。修法＝执行时传「整批直跑」ExecOption **副本**（`execInside=EXECUTE_INSIDE_WHOLE_SCRIPT=6`、`execOutside=EXECUTE_OUTSIDE_WHOLE_SCRIPT=2`），不弹层整批全执行；`copyFrom` 字节码确认四字段全拷贝，缓存与用户全局设置活实例零突变。
4. **双方言共用 `identityInsertWrapper` 成立**（`Dialect.kt:501`，SqlServer/DM 两处 `renderInsertWrapper` 均委托它，形态逐字相同仅表引用符随方言）。修一处双方言受益；测试双方言形态各一（SQL Server `[user]` 形态 + DM8 裸名形态，后者与 RollbackSqlGeneratorTest `rt_del` 真机产物同款）。
5. **docker daemon 活性正常**（`timeout 10 docker info` exit 0），环境不构成阻塞；真机端到端复验（容器 SQL Server 全流程）属 IDE 交互走查，本子代理车道只跑定向测试，交回主控走查车道执行。

### 实施（两半）

- **D1 静态检查**（c718e3d）：新增 `sql/IdentityInsertStatements`（`stripForParse`/`containsStatement` 纯函数）；无痕迹返回**原引用**，T-PS5-02 AST 单槽 `===` 快路径不受影响（剥离只对 identity 文本换解析键，后果=该文本多一次解析，语义不变）。`UnboundedWriteDetector.detect` 解析输入换剥离文本；开关语句本不产生发现，真写语句发现原样浮出（剥离不构成洗白，有测试钉住）。
- **D2 默认整批**（8eb2c0a）：`DataSourceSelector.wholeScriptExecOption(base)` 副本工厂（反射不可用原样返回=维持现态）；`wholeScriptExec` 旗标沿 executePrepared / selectAndExecute→chooseAndPrepare→reuseSharedConsole / prepareInConsole / prepareAfterEditorResolved→executeOnConsole 三路径贯通（`effectiveExecOption` 五处使用点收口）；`SqlExecutor.execute/executePrepared` 透传（默认 false，既有调用点零变化）；`SqlPreviewPanel.executeAdhocSql` 按 `containsStatement` 计算（生产入口仅回滚弹窗链；executeSql 用户编辑器链不接旗标，D2=A 普通多语句产物保持现态）。

### 定向读数

- 快桶（`./gradlew testFast`）：修复后 **3206 tests / 0 failures**（含新增 IdentityInsertStatementsTest 9 例、UnboundedWriteDetectorTest identity 三例、ExecuteConfirmMessagesTest identity 一例、IdentityWholeScriptWiringGuardTest 四例）。
- **反向验证**：临时摘掉剥离分支（RED PROBE）→ `testFast` **3202 tests / 4 failed**，恰好为四例 identity 用例（UnboundedWriteDetectorTest 三例 + ExecuteConfirmMessagesTest 一例），其余 3198 例不受影响；还原后同桶全绿。
- 平台桶定向：`ExecWholeScriptOptionTest` 3 例 0 失败；其中副本覆写/基础实例零突变两例被 assume 跳过（**默认 test classpath 不含 DatabaseTools jar，环境受限未验证**；其语义由 javap 离线证据钉住——常量值/默认值/copyFrom 全拷贝均为 class 文件直读取数；classpath 变更后用例自动激活），兜底例（反射不可用原样返回）实跑通过。
- 对话框层与解析共享契约：`ConfirmRollbackParseShareTest`/`SqlPreviewPanelRollbackParseShapeTest` 为纯逻辑、快桶已绿；`RollbackSqlDialogTest`/`RollbackSqlPanelTest`/`DatabaseSchemaResolverAutoGeneratedTest`/`LogToSqlPanelExecuteTest` 平台桶定向跑 BUILD SUCCESSFUL。
- 接线守卫：`IdentityWholeScriptWiringGuardTest`（ErrorPositionWiringGuardTest 同款源码扫描）钉「executeAdhocSql 体内每个 SqlExecutor 调用必传 wholeScriptExec」+「executeOnConsole 每个调用必传」+「旗标必须由 containsStatement 计算」，已配合成样本自证语料并登记 `GUARD_CLASSES`（登记过程中踩「带描述无 via＝豁免条目」的登记语义，callsWithin 改裸条目直证）。

### 真机口径

容器环境可用（daemon exit 0），但「两行原值恢复」全流程复验需 IDE 内交互（O-59 流程），本车道未执行——**真机条目为「环境可用、待走查复验」而非已通过**，交主控走查车道；预期行为：identity 产物确认后不再弹 Statements 选择层、整批直跑（三语句全执行），确认框文案回既有形态（不再含「无法解析语句结构」）。

### 未做项与边界

- P18（成功 DML 被误标执行失败）同链不同案，按计划不碰。
- `MetadataInvalidator.mayChangeMetadata` 对 identity 产物仍解析失败 fail-closed→ 过度失效缓存（仅性能面、非正确性），P19 未涉及，未改。
- 多行字符串字面量内恰似 `SET IDENTITY_INSERT` 的文本会被剥掉（被剥对象本身不产生发现，不构成漏检面），已在剥离器 KDoc 显式登记。
- `executeSql`（用户编辑器执行链）与 MCP CheckSqlTool 的选择层行为未接旗标（D2=A 最小面）；用户在控制台手贴 identity 产物执行仍走原生选择层现态。
- build.gradle.kts 的 sandboxContainer 临时隔离行已还原（未入库）。
