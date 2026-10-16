# T-SqlInsight 实施与非 UI 验收台账

> 状态：实施中，不是验收通过报告。基线 dev@44ee9d11，集成分支 t-sql-insight；本专题不启动沙盒 UI，全部开发完成后才合回 dev。
> [开发计划](../开发计划/20261016-1716-T-SqlInsight-SQL洞察与关系流向分期建设-开发计划.md) / [调研报告](../调研报告/20261016-1716-SQL洞察与关系流向功能演进-调研报告.md)。

## 一、目标到产物

完成全部17项需求、31个工作包的生产接线、测试和非UI验收；没有调用方的模型/算法、只有探针、只有文档或被标“不支持”的范围内任务不能算交付。最终必须对最终dev合流树复核，不能凭累计测试数判全部完成。

| 任务 | 具体产物/判据 | 状态及证据 |
|---|---|---|
| W00 | 冻结dev、独立集成树、资产和授权台账 | 已建集成树，dev@44ee9d11；文档提交9d98dd92/d23f1dd0 |
| W01 | AST作用域、黄金语料、源码范围可证/未知 | 集成7df5c3f1/3f4623fa；21例及扩面47例绿、反向1红/2红；精确range未知 |
| W02 | 执行身份、质量、协议及捕获覆盖探针 | 31fbeaeb/68c07266；agent66例绿，Kotlin端待整体门 |
| W03 | 页签身份、独立移除、取消/释放 | 12c4d60b；39例绿/撤真实票据门1红/恢复39绿 |
| W10 | SELECT/JOIN/CTE/DML角色及基础字段来源 | 3f4623fa，47例语义定向覆盖；最终集成未验 |
| W11 | 解释结果的生产入口/视图 | f155ca22/48fd7d9b；更多“分析SQL”真实入口，57例非UI绿 |
| W12 | 图/树表、定位与复制 | 同上；纯JDK图树4例独立快桶绿，范围缺失选择全语句 |
| W13 | 编辑失效、无数据库/插件后备与安全 | 文档身份/stamp/generation/ticket门；实际监听摘除1红/恢复57绿 |
| W14 | 表达式、CTE透传、UNION列来源 | 48c1a58b/27c6ac81，已含于47例；支持边界仍明确 |
| W15 | XML渲染时源片段与条件映射 | 4d32c38b/3661478b，trace与实际标量/嵌套预算已测；场景UI已接。expanded节点路径与实际追加模板，不是原XML offset |
| W16 | 命名预设只读场景矩阵及分支状态 | 13da7b42/4d32c38b与场景入口，快照/参数/文档版本反向红闭环；当前模型场景不覆盖手工草稿 |
| W17 | 脱敏场景与语义期望回归导出 | 5a525b1d与后台导出入口，5例核心/场景消费者纳入最终67绿；预览后显式复制，本页期望可保存比较，不执行匿名包 |
| W20 | agent执行/run/sequence/线程/结果身份 | 已实现，可选字段兼容；agent66及后续108相关定向绿 |
| W21 | 质量计数、终态水位与有界存储 | nonce/独立控制质量已实现；run STOP不代表request complete |
| W22 | IDE去重前分析分流及权限停采 | 真实socket100事件/日志1行、nonce配置与可选追踪停采2例绿；Channel7/Store10绿，未知库按执行隔离不混统计 |
| W23 | 指纹/调用点/数据库画像 | RuntimeInsightView实际启用/快照/聚合/基线消费者已接；同代bundle与未知质量说明 |
| W24 | 预览与真实执行候选及对比 | 候选身份带入显式样本Diff，显示语句类型/占位数/结构变化；参数类型无证据保持未知，不自动认领同次执行 |
| W30 | 标签观测分组与未知请求边界 | RuntimeEvidencePanel显式tag分组已接，标签不作请求ID |
| W31 | javax/jakarta请求根及结束事件 | ed1be2bb/027d00eb；真实API双族及108相关定向绿，仅SERVLET_THREAD_ONLY |
| W32 | 请求SQL时间轴与归因树 | 请求SQL明细/区间并集与同run/request筛选条形图已接，允许合法负nanoTime；RequestTraceStore4及时间条组件验证 |
| W33 | 重复/N+1证据及降噪 | 请求内重复候选+显式临时忽略/恢复已接，不改事实计数或预算，不假称确定N+1 |
| W34 | 请求基线与三态预算 | RequestBaselineView显式capture/JSON复制加载/环境声明/结构预算已接；核心19例及消费者已纳统一67绿，不完整基线永不确定FAIL/PASS |
| W35 | 连接租借/事务提交回滚/保存点观测 | 2ff05cd9～42948877真实H2及108定向绿；事务视图已接，lease仅包装生命周期，close不等于commit |
| W40 | 星号/子查询/MERGE/有序临时表扩面 | 语义47绿后独立审计8真红修至63绿，metadata显式Schema绑定/清除生产入口与2例已验证；复杂未证语法仍未知 |
| W41 | 显式范围使用反查与影响定位 | 真实PSI8例、逻辑4例通过；当前文件/模块选择、PSI stamp导航生产入口已接 |
| W42 | Schema/类型/映射差异 | Schema6+Audit11绿，codec4绿；双快照显式比较/nullable方向/已收集usage affected导航已接 |
| W43 | 计划算子解释与前后联动 | 0e6771cb解释4例、既有JSON13/Diff10/ResultView14回归通过；R61/R90保持 |
| W44 | 写目标/试算边界与局部事务证据 | eb0ec7ac不可变WriteImpactContext与人工事务关联同屏已接；生产消费者1例+模型2例绿，COUNT未执行、不自动归属提交 |
| W45 | 有界SELECT结果比较及回归执行 | 75核心+Remote5+签名1（243/253/262）绿；显式目标/后台prepare/审批/取消/执行port UI已接，真实按钮消费者通过 |
| W46 | 有证据优化候选与安全负例 | 三规则4例绿；优化页证据/前提及stamp范围定位已接，仅候选不自动改写 |
| W47 | trace关联及版本化离线导入 | OTLP JSON v1投影8例绿；显式FileChooser+限量UTF8/粘贴+HTTPS用户链接已接，本地文件正反例通过 |

## 二、已验证证据

### H05：真实织入产物

- 原始生产代码先运行 SqlInsightWeaveProbeTest，5例：2通过、3失败。
- 已通过的原始能力：executeUpdate 的 IRETURN 行数捕获；显式向外抛异常保持原异常。
- 真失败：execute(boolean)统一装箱Integer导致DML行数遗漏；隐式异常没有事件；内部catch接住的ATHROW被提前标执行错误。
- 修复：Boolean独立装箱，方法外层逃逸异常处理器最后注册以保留驱动原处理优先级，不采用COMPUTE_FRAMES触发类加载。
- 修复后定向XML：SqlInsightWeaveProbeTest 5、StatementWeaveEndToEndTest 10、StatementWeaverFramePatchVerifyTest 3、StatementWeaverVendorCoverageTest 9、ZeroLoadClassWriterWeaveTest 5，合计32/0/0/0。
- runtime提交d75f85b6，集成收编6c1886c8。主控读取runtime树agent/build/test-results/test五个XML核对32/0/0/0。该读数不是所有驱动/JDK完整验收，也不是集成最终树全量结果。

### 2026-10-17 阶段验证记录（不同冻结点不可直接相加）

- 语义扩面：semantic@24523f21，四类47/0/0/0；撤metadata身份与条件更新旧来源两闸=47/2/0/0，恢复47绿。包含函数/CASE/聚合/UNION/star/MERGE/临时表与污染反例。
- 预览生产入口：preview@6dbcca04，七类57/0/0/0；撤真实DocumentListener.invalidate=1/1/0/0，恢复57绿。纯JDK视图另testFast4例通过，不重复计入57。
- 主控算法补测：preview固定48fd7d9b+夹具修复3e48f793，六类41/0/0/0。首轮from缺errors导致测试编译失败，修复后XML确实入场；不是NoSuchMethodError受限通过。
- 结果/授权/离线导入：preview固定685ebfd1，四类31/0/0/0，含真实H2采集7、结果比较10、授权6、离线导入8。
- 请求/事务agent：runtime@b6b335e2，同任务20类108/0/0/0。初轮真实H2在同测试loader提前加载导致ZeroLoad三例红；改独立驱动loader，保留原断言后同任务108全绿，不靠分任务隐藏冲突。
- 在途测试：使用服务/Schema/优化13纯逻辑+8真实PSI（BasePlatformTestCase，无GUI）正向已绿，反向及恢复仍在该车道进行，最终读数待收编。

上述全部是阶段定向证据，不等于最终全量、兼容、性能或GUI通过。阶段独立归档已保存为集成工作树 `.local/sql-insight-stage-evidence.tar.gz`（不入公开仓），SHA256 `ae9637050ca9bb4c82654e45c6ac9d10b6d55887ba7b9f5ad5bbc29dc377bf1d`；包含h03/m1-ui/consumer/comparison-ui/final-consumer各轮XML日志、三轮agent性能summary与逐进程JSON、sender红绿和model-metrics。原工作树build仍保留；归档是证据备份，不是最终全量通过凭证。

## 三、已证伪前提与后续动作

- H08有程序化结果读取基础：McpDbExecutor的反射通道已在项目中存在，无需硬读原生网格；但原collect将SQL NULL转为空字符串，不能直接作为回归比较输入。现已新增保留NULL的采集器、Remote适配、独立目标会话和单次授权入口，定向证据见W45；仍不得把这些证据扩大为GUI或用户业务数据库验收。
- 预览页签原有固定索引/整体拆容器问题已由稳定页签身份、取消票据和独立释放修复；实际DocumentListener失效链已做反向验证，最终全量仍需覆盖。
- W45曾只有基础模型而无生产入口，现已补齐真实消费者及其测试。该历史缺口已闭合，但最终全量、构建与兼容尚未通过，不能写成全部验收完成。

## 四、性能实测与当前阻断

### 首轮：模型与agent分别计量

模型冻结50f3c833：普通SQL暖态P95 1.609ms（250样本）、取消含解析P95 1.759ms（100）、超长拒绝0.018ms、500列分析27.983ms。1万事件入库26.720ms、快照P95 5.461ms、估算保留3,309,388字节；500请求关联1万SQL P95 10.819ms；图200节点/400边预算构建P95 3.904ms。测量窗口heap增长不能解释为常驻增量；产物在runtime树build/sql-insight-perf/model-metrics.json。

agent首轮3组各3个独立JVM，实际心跳为实验注入200ms，warm200请求，业务每轮500请求×20SQL且checksum一致。与仅SQL捕获相比，开启请求追踪P95约+10.28%、wall约+8.03%，且SQL事件丢约70%；请求起止1000帧到齐不补偿SQL丢失。因此不达标，不能凭延迟数据宣称可靠覆盖。

### 发送调度第一刀与默认节奏复测

真实socket原默认10秒心跳基线：256条SQL排空3246ms、256条控制排空3865ms，两条吞吐断言红；STOP时ACK约6ms、半帧断连正例通过。最小修复d2b86725让busy轮不阻塞等CONFIG：SQL排空232ms、控制221ms，STOP ACK约8ms，37条相关定向全绿。队列2048及隐私未改。

随后默认心跳10秒、warm2000、完整3×3独立JVM复测：tracing对SQL中位P95约+2.83%、wall+0.74%、CPU+1.18百分点，预热末两块9轮都在15%内；但tracing只收到4602/2802/2048条，仍丢5398/7198/7952条，全部采集完整性失败。无agent对照的P95与CPU增幅也不能隐藏。原因进一步指向空闲时1秒入站等待加200ms休眠，突发到来不能唤醒sender；busy优化不解决idle起始积压。

### 可唤醒发送第二刀与无损对照

真实idle→burst原先首SQL要1208ms，新单reader/有界mailbox4/合并wake信号/单writer后低于1ms；256SQL约23ms、256control约15ms，STOP ACK约2ms，半帧断连及心跳/配置保持。runtime65962c3a，集成e4499a53，11类61定向绿；没有放大SQL2048队列。

第三轮默认10秒心跳、warm2000、完整3×3独立JVM：6个agent进程每轮generated=10000且unique received=10000，SQL/control损失均0，tracing三轮500START+500END完整配对，captureCoverageComplete=true。中位none/sql/tracing分别：wall983.89/1402.53/1231.00ms；SQL P95 159.4/267.2/229.4µs；一核归一CPU100.04/236.28/178.97%；RSS135.05/180.22/153.77MiB。tracing相对SQL捕获P95为-14.15%，但第三tracing预热比0.600不稳，不能宣传负开销；相对无agentP95+43.91%、wall+25.12%、CPU+78.93百分点如实披露。无agent差异包含既有完整SQL捕获成本，不能全归因新增请求追踪。

**状态**：突发发送丢失瓶颈已有无损负载证据闭合，新增追踪5%初始成本目标尚未在稳定空机环境证明。三轮均标host-contention=observed-other-IDE-session，不宣称空机标准性能通过。失败目录agent-trials与agent-trials-default-warm2000保留；成功覆盖目录agent-trials-wakeup-default-warm2000/summary.json，产物agent SHA256 `8f2d1e020aa7c246f43250a193cdd753d8d6baf36dc7b77315d3f2f5cb299ad1`。后续复测必须新目录，不覆盖失败证据、不以丢弃事件换低耗时。

### warm10000补测：质量通过，资源预算未通过

默认10秒心跳、每进程预热10000请求、3轮×none/sql/tracing共9个独立JVM实际完成，exit0。业务每进程均10000条SQL、checksum12454200000；所有agent进程收到10000唯一SQL，tracing各500个请求起止完整配对，SQL/control loss均0，9/9预热末两块差异在15%内。质量条件通过，但退出码0仅说明实验运行成功，不代表资源预算通过。

tracing相对sql的中位P95为-5.16%、wall为-1.54%，逐轮P95增量却为+21.71%/-5.16%/-28.35%，仍明显波动。单核归一CPU中位从170.61%增至190.74%，增加20.13个百分点；包含排空窗口仍增加21.23个百分点，超过既定5个百分点初始预算。RSS中位增加约5.63MiB，未GC heap差约-0.95MiB，均不冒充常驻内存证明。相对none的总成本另列：P95+22.86%、wall+15.53%、CPU+91.83个百分点，不把既有捕获成本全归因新增追踪。

开跑前背景IDE约9%单核CPU，过程两次采样为0，Gradle daemon采样为0且无worker/verifier；这是明确记录背景负载的实验，不是认证空机。原始summary SHA256 `26688cf9181adacaff9515a2ecf7185f0136fb546c387593627a3724bc8bf6ff`，位于runtime树`build/MPSV-SqlInsight-Runtime-Perf/sql-insight-perf/agent-trials-wakeup-default-warm10000/summary.json`。结论：资源门不得记PASS，当前阻止最终合回dev；继续核计量分母、绝对CPU与线程热点，不放宽目标、不丢事件、不删除质量证据。任何后续生产优化都需新冻结重新验证，ab8b54b1的门禁只覆盖原冻结代码。

### CPU计量复核与有界JFR诊断

原summary绝对CPU中位：SQL模式1953.125ms、追踪模式2234.375ms，每500请求增加281.25ms（每请求0.5625ms，约+14.4%），并非仅CPU百分比的wall分母效应。但CPU计量步长15.625ms、业务窗约1.1秒，包含JIT/GC/sender/main，不足以把增量归因到特定生产热点。

另以两个独立JVM做诊断：预热10000请求并排空后才启动JFR，按businessStart/End裁剪业务窗，记录开关与采样扰动，不作预算复测。SQL/追踪均10000SQL零丢失，追踪1000起止事件完整；窗内ExecutionSample仅51/66，多为H2业务栈，JavaMonitorEnter超过1ms事件为0，不支持全局锁竞争是主因。Compilation有92/86个事件，C2编译耗时之和521/599ms，追踪侧SenderLoop.run重编译135ms；这些是可重叠的编译耗时，不能当进程CPU直接扣减。GC各15次、暂停22.0/24.9ms；ThreadCPULoad按12核归一，不能与单核100%口径混减。NativeSample里的Net.poll为阻塞采样，不能冒充CPU热点。

诊断尚未锁定根因，不据此贸然改锁或队列。后续仅对实际代码可证明的重复scope读取、请求元数据处理或编码工作做单项反证，保持协议内容、完整性、质量和隐私不变；必要时另列更长固定负载的稳态校准，不覆盖原短窗失败结果。JFR原始文件与window-analysis.json位于runtime树`build/MPSV-SqlInsight-Runtime-JFR`，生产代码及既有基准脚本未因本次诊断修改。

## 五、最终冻结与自动化门禁

冻结提交 `67533aca415bf227e891d697ad45cdf33563b4ff`，基于已同步dev `5d884a5e`。专用detached工作树 `mpsv-sql-insight-gate` 生产写集为空，配置/输出仅在其build下；主控期间仅提交文档，不让在途代码改变绿灯含义。

- 测试命令：`:testShards --no-configuration-cache -PmpvDockerTests=false -Pkotlin.compiler.execution.strategy=in-process`，默认5片，包含external与agent子项目。
- 打包与兼容：测试通过后分别执行`:buildPlugin --no-configuration-cache`、`:verifyGate --no-configuration-cache -PverifyOnly=true`，默认243/253/262离线检查，不跑GUI。
- Docker preflight实际exit124，容器类显式禁用，跳过发生在调用Docker前；不重启用户daemon，不拿容器未运行当真实数据库覆盖。
- preflight提交量53,412,392,960/73,750,339,584字节，余约18.94GiB；仅本次全量与既有IDE运行，没有其他testShards并行。此值是开跑时快照，不是整个任务内存峰值。
- 新增测试清单按实际类名核对：相对dev新增54个根项目测试、8个agent测试；必须核XML每类/每方法入场，不能只信文件清单或BUILD SUCCESSFUL。
- 首轮状态：全量11分33秒后失败，未执行打包/兼容。shard1=157类1777/0/0/1；shard2=152类1520/6/0/2；agent=38类213/0/0/0；external=6类27/0/0/1。shard3～5未启动，62新增类中32未入场，不构成完整覆盖。
- 首轮6红：后台PSI捕获守卫命中UsageNavigation块内重取站点；fatal守卫命中RemoteSqlComparisonBridge/PreviewTabManager；bundle守卫把动态拼接前缀当字面量；premain重挂两例的selfPID被新辅助JVM安全闸拒绝；织入源守卫仍要求旧无参WeaveGate形态。由主控/对应车道逐项核实修复，禁止删安全闸或造无消费者bundle父键。
- 首轮日志SHA256 `0112fedbe1a17961004f5159828e9fb4f5e46011cf4ba645f43c3c50ef7ccd5a`；审计报告SHA256 `af7090e79bdc6e641cdaf299af45be721243b15b211aec750fd2688f41f7df9d`，均在gate树build/final-gate。4项实际skip为Windows符号链接权限1、Database测试classpath不可用2、ACP impl制品缺席1；容器用例所在后续片尚未执行，不能提前算入skip。
- 修复使67533aca的全量读数过期，须另冻新SHA完整复跑，不把第一片绿与第二轮部分绿拼成全量。首轮归档 `.local/sql-insight-gate-round1-failed.tar.gz`，SHA256 `826fb6aacb296817000865103cfff4e8996ee8a223ad8bbcd61a6019c7682759`。
- 六红修复：Remote fatal资源关闭语义/重挂self PID夹具局部注入正对照/ExecutionScope真实嵌套守卫收编edc9a9c0，元守卫登记214adcb5；UI fatal优先透传与导航块内PSI重取精准说明988db4fe，真实去注释负例；W43动态键模板及9实际算子键双语断言43b1a351。生产attach安全闸未放宽、没有虚造bundle父键。后端定向平台32、Meta/Wiring15绿，UI守卫组合七类39/0/0/0，含Banned/Bundle/Meta全扫描及真实导航去注释1命中、fatal/普通异常行为测试。
- 第二轮正式冻结 `b23e890de8b46cea71f9ed5b8fb5b7df16443a0c`，已单向吸收dev `c15a63cc` 的非GUI日志几何探针。授权gate干净detached切换新SHA完整复跑5片/agent/external，本地init仅强制Test任务fresh，不clean；日志/审计进round2目录，不覆盖round1。第二轮在第四片失败，完整情况如下。六红修复定向独立归档 `.local/sql-insight-gatefix-evidence.tar.gz`，SHA256 `0266f92584d119dbd33802b601b92e98a30b124d2f9f9c8e462434971a20a4c7`。

### 第二轮完整结果与回交

13分7秒后`:testShard4`失败，片5未运行；root新增63类中7类仍未入场，不作全量通过。

| 桶 | 类 | tests/failures/errors/skipped |
|---|---:|---|
| shard1 | 160 | 1811/0/0/0 |
| shard2 | 148 | 1443/0/0/0 |
| shard3 | 155 | 1515/0/0/6 |
| shard4 | 153 | 1740/2/0/3 |
| external | 6 | 27/0/0/1 |
| agent | 38 | 213/0/0/0 |

两项为陈旧测试契约：AgentListenSyncTest期望旧JSON未含三个默认false追踪字段，原停采/脱敏值未变；JavaCodeGeneratorJoinTest期望MP核心Lambda.selectAs，本地MP3.5.17 javap证实无该API，dev UI005已改为QueryWrapper完整字符串投影，MPJ路径仍须selectAs。分别补精确字段启停闭环与真实编译/MPJ正对照，未放宽生产安全规则。修复提交3e5f31fd/142cc926，定向JavaCodeGeneratorJoinTest39、JavaCodeGeneratorTest45、AgentListenSyncTest3合计87/0/0/0已核XML。第三轮候选a9a3488b已单向吸收dev3393dc66的Sharding日志配对/徽章修复，完整复跑待短JDK功能验证结束。

本轮10skip：Docker明确禁用5、外部jdbcUrl未给1、Database测试classpath缺席2、符号链接权限1、ACP结构性1。round2日志SHA256 `eedb0ce810b22dc89c9855799c9f462c00981a58abe53b16e2f655288754178a`，audit `f0e9425cc6a7795162efc6bb300eed58a6958e76df94ef5c9a51b1e8a972681c`；封存 `.local/sql-insight-gate-round2-failed.tar.gz`，SHA256 `f4493623a5d9e107aa161f11037998e224aa29cff79e7b3736e0f32e6114c3a4`。

### 第三轮冻结

冻结 `a9a3488b64a86264c9ac3979ba8d2e8ca0fde77a`，已吸收dev `3393dc66` 日志配对与徽章修复；R2两红为测试契约更正，原功能保护保持。gate detached完整从片1复跑，所有Test强制fresh，Docker显式禁用，默认5片。05:44 preflight Commit约51.179/73.750GB、余约22.57GB；并行仅另一dev四类短定向，不是另一全量，使用独立沙盒。本轮片1实际失败：159类1505/3/0/0，agent38类213绿、external6类27/0/0/1；片2～5未运行，63新增类48未入场。三红均为AliasDualTrackDefenseTest同族旧MP核心Lambda/selectAs预期，与已落dev UI005整链字符串Wrapper回落不符；由runtime车道扩为整族reverse定向盘点，保留真实编译/别名/WHERE/ORDER及MPJ正对照，不放宽生产逻辑。日志SHA256 `1d1510ddfbd92242642fac373166a2a1075c2d6552297779ff3a6c4716b535fe`，audit `491bf9f7770c3f77f7d1dcceeb8c4150905fcfad7d82400984f382b95621f5ed`；归档`.local/sql-insight-gate-round3-failed.tar.gz` SHA256 `29c85b20aa6790427cda9cd78e6144c4c7f982eb881215704b12c1ffd7fda2a0`。未打包、未verify，仍无全量通过读数。

### 第三轮同族修复与第四轮冻结

R3三红修复已收编为638a5740（原babc87c5），仅修改AliasDualTrackDefenseTest与GeneratedProjectionCompiler两份测试文件，未改变生产行为。核心LAMBDA/WRAPPERS_LAMBDA生成结果实际编译并核完整投影、实名WHERE和ORDER；MPJ selectAs正对照、未知列限定名、错误别名不得反向纠偏的负例保留。主控读取全部实际XML复核：快桶10类275例、平台SqlToJavaConverterTest 23例，共11类298/0/0/0。该结果覆盖reverse整个测试包，不等于项目全量。XML独立归档`.local/sql-insight-reverse-r3-verified.tar.gz`，SHA256 `ca4a4b2cd43de09e0d07f9cd50bd0aeaa5a28154ba4dd80c8a14c891dd85dfe9`。

第四轮冻结 `ab8b54b15e5723003c0d3af1ab3799a057380b2d`，已单向吸收dev `bfbe5b4c` 的属性门控日志几何诊断及其测试。专用gate树从片1完整复跑默认五片、agent与external；强制Test新执行，沿用独立沙盒、Docker显式禁用及非UI边界。通过后才依次打包与三代离线兼容验证。前三轮失败独立保留，不拼接部分结果，不预填本轮通过。门禁期间主控只更新文档，同时独立只读复核31工作包和17项需求的实际消费者与证据边界。

### 第四轮完整测试结果

冻结ab8b54b1，`:testShards`实际exit0，用时15分26秒。主控核对`round4/test-audit.json`：769个计划根分片类全部有本轮XML；新增63类（根55、agent8）全部入场，324个声明测试方法均匹配，无缺类、无跨片同FQCN/方法重复、无陈旧XML混入。冻结后集成树仅两份文档变化。

| 桶 | 类数 | tests/failures/errors/skipped |
|---|---:|---|
| shard1 | 159 | 1611/0/0/2 |
| shard2 | 155 | 1561/0/0/6 |
| shard3 | 152 | 1448/0/0/1 |
| shard4 | 151 | 1641/0/0/0 |
| shard5 | 152 | 1783/0/0/0 |
| external | 6 | 27/0/0/1 |
| agent | 38 | 213/0/0/0 |
| 合计 | 813 | 8284/0/0/10 |

10项跳过分别为禁用Docker5、未配置外部数据库1、Database测试classpath缺席2、Windows符号链接权限1、ACP结构性1，不是这些环境验证通过。日志SHA256 `b1ea4db163fcb49e4de3b7a24ab5ff404cb28238356ce5fa723a8d4d72320695`；审计JSON SHA256 `70328bad987792c10b755bc404e14e2f110165d1e02a7120c2447c06a3053cb8`。这是首轮完整自动化测试通过结果，不拼接前三轮局部绿；打包、ZIP内容核验和三代兼容仍在后续步骤，不预填成功。

### 第四轮打包、制品与三代兼容

`buildPlugin`与`inspect-plugin-zip.py`均通过；同一ZIP完成三代离线`verifyGate -PverifyOnly=true`，实际verdict全部为Compatible：IU-243.28141.41、IU-253.33514.17、IU-262.10968.63。三代各有scheduled/deprecated/experimental/internal API提示，分别为5/35/96/1、6/45/94/1、8/117/94/1；Compatible不等于零API警告。ignored有效规则0，本轮offline下载0B，不代表Marketplace在线验证。

实际ZIP为10,203,980字节，SHA256 `e3aff87366e6aef4aa8632e5e2718198cca2517859aa097a5b3aa726a06ce632`；主JAR SHA256 `d30820c72662f5ea6f3b3de5996f8e95376527d326ad79c6bb1610773857e831`；内嵌agent SHA256 `6f007d0f79d0a4cc8a4e7fe8d9b673e73ef503be5c8e81fe38785f0815c97fe5`。包内容核验包含核心洞察实现、预览入口、两语言包各187个一致的insight键；agent关键类ServletWeaver/RequestHooks/TransactionTracker/SenderWakeup均major52，无javax/jakarta Servlet测试依赖类混入。

针对agent与H09原制品哈希不同，另做实物逐条比对：原jar仍在且哈希符合H09记录；两jar均373,261字节、147个文件条目（146个class及manifest），无增删、重复或顺序变化，全部解压字节相同。588个原始差异字节仅落在ZIP的DOS时间字段，归零时间字段后的整jar哈希均为`99396124aea0bb196e1b53a381f744dd0dc3fbd0f6636217d8ed7c110a2bb06d`。因此H09与性能使用的agent类字节和最终包一致；不把它描述为外层插件包也参加过原实验。

构建、自动化测试和离线兼容已通过，但计划G-final的资源预算仍须单列：稳定5%新增开销尚未证明，不能仅因这些门禁全绿便把资源门或最终合流预填通过。

本轮完整报告、测试XML、三代verifier报告和实际ZIP已封存至`.local/sql-insight-round4-passed.tar.gz`，SHA256 `8a3c310be2f78d253efd194a845b3107e911dac7e12ff1c679a8de13ba3e19b4`。门禁登记已注销且后台任务结束。随后在无其他构建的窗口启动warm10000、3轮×3模式独立JVM性能补测，使用新目录不覆盖前三轮；背景IDE采样约9%单核CPU已记录为非认证空机环境，结果尚未形成，不能提前记性能通过。

### Agent JVM 验证层级

第二轮fresh XML已经证明既有 `AttachGateTest.attach门禁_JDK8目标` 与 `AttachPoolWarmGateTest.模式B2_JDK8目标_Hikari池存量连接复用` 均实际执行、未skip；测试源码明确启动本机JDK8子进程。新增ServletRequestWeaveTest最初6例只在JDK17宿主隔离ClassLoader运行，major52仅是字节码证据，故随后补H09真实目标JVM验证。

H09同份fixture经`javac --release 8`后，实际java.version分别为1.8.0_181、17.0.19、21.0.11，三个独立进程均FUNCTIONAL_PASS/exit0。每个5请求START/END配对、4唯一功能SQL（3条请求内+独立查询）、真实commit/rollback与SQL transactionId/requestId关联，业务计数3、最终库行数1；异常后同线程无SQL请求不串身份，SQL/control全部loss0、controlGenerated=written=17。

限定javax Servlet4.0.1；JDK8配H2 1.4.200，17/21配H2 2.3.232；Jakarta仅既有JDK17真实API测试，不外推全JDK。冻结agent SHA256 `8f2d1e020aa7c246f43250a193cdd753d8d6baf36dc7b77315d3f2f5cb299ad1`。summary SHA256 `d77b6a34f784b058b603d353ed93948b8da8114b68bb1bf5ffe32086507e69aa`，独立归档`.local/sql-insight-h09-jdk8-17-21.tar.gz` SHA256 `e581c95160d6215d239bec318e8d07f0e4cf863721b50a3710455fe632c7a494`。这是功能兼容而非性能预算证据，也不由IDE三代verifier替代。

### 第四轮冻结的独立只读范围审计

审计以本开发计划为唯一需求基线，检查ab8b54b1的31工作包和17项SI需求的实际实现、生产消费者和证据边界。结论：未发现新增可确认的生产阻断或整包缺少消费者，当前不需为审计追加生产改动。核查涵盖预览版本门、场景后台和预设只读、导出、去重前agent分流与显式开关、同代际证据、请求三态和用户基线、模块使用查询与PSI版本导航、Schema关联、计划解释、静态COUNT上下文、Remote只读结果比较、离线文件与受控链接。审计写集为空，未修改代码、运行测试或GUI；本结论只证明范围及接线复核，没有替代第四轮完整测试、打包和兼容结果。

原始H09三JDK JSON核实为javax/H2功能通过；性能JSON的`warmStableAllTrials=false`明确说明零丢失不能推出稳定5%成本达标。以下边界继续保留：

- GUI未验、稳定新增开销≤5%未证、Docker用例跳过；第四轮全量和兼容尚待最终报告。
- AST精确源码范围未知时定位整语句；XML追踪提供展开节点路径，不是原文件精确offset。
- Servlet仅线程内观测，事务仅代理连接包装生命周期，不认证真实连接池租借、XA或跨线程传播。
- W24参数类型保持UNKNOWN，不宣称参数类型差异已测。
- W45两次顺序读取不构成原子数据库快照，用户业务数据库端到端尚未验证。

## 六、最终门禁清单

- [x] 31项范围内开发都有可达生产入口或明确内部生产消费者（冻结67533aca，逐包表及只读覆盖复核；W44最终断点已以eb0ec7ac闭合）。
- [x] 17项SI需求逐条对应用户行为和定向正反测试；最终统一门禁仍需验证所有类同时入场。
- [ ] 真实语义、请求身份/质量、事务状态与权限安全无阻断。
- [ ] 定向测试实际XML入场、必要反向红/恢复绿已留证。
- [ ] 性能/资源预算有对照，不只空载。
- [x] 冻结ab8b54b1完成权威全量与agent验证，8284/0/0/10；合流前如dev再有生产变化需另行核对，不能沿用到未测试代码。
- [x] 新平台面三代离线兼容验证及构建校验完成，同ZIP三代Compatible；不替代GUI或Marketplace在线验证。
- [ ] 独立复核闭环、未验UI与其他受限项逐条披露。
- [ ] 最后一次ff-only合回dev，落地树与已验树一致，未push/发布/回流master。

UI走查清单保留在开发计划§7.4，本轮全部未执行；不得用headless测试替代GUI通过。
