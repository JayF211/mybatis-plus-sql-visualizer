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

- H08有程序化结果读取基础：McpDbExecutor的反射通道已在项目中存在，无需硬读原生网格；但collect将SQL NULL转为空字符串，不能直接作为回归比较输入。新增纯JDBC采集器保留NULL，平台适配和安全执行入口仍待实施验证。
- 预览页签存在固定索引/整体拆容器问题，子车道先修真实生命周期，不能只加新tab。
- 当前主控结果比较实现只有基础，没有运行生产入口，必须保持W45未完成。

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

## 五、最终门禁清单

- [x] 31项范围内开发都有可达生产入口或明确内部生产消费者（冻结67533aca，逐包表及只读覆盖复核；W44最终断点已以eb0ec7ac闭合）。
- [x] 17项SI需求逐条对应用户行为和定向正反测试；最终统一门禁仍需验证所有类同时入场。
- [ ] 真实语义、请求身份/质量、事务状态与权限安全无阻断。
- [ ] 定向测试实际XML入场、必要反向红/恢复绿已留证。
- [ ] 性能/资源预算有对照，不只空载。
- [ ] 最终吸收dev后的冻结SHA完成权威全量与agent验证。
- [ ] 新平台面三代兼容验证及构建校验完成。
- [ ] 独立复核闭环、未验UI与其他受限项逐条披露。
- [ ] 最后一次ff-only合回dev，落地树与已验树一致，未push/发布/回流master。

UI走查清单保留在开发计划§7.4，本轮全部未执行；不得用headless测试替代GUI通过。
