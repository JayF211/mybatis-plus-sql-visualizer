# T-AgentResiduals 交付在册残留收口 — 开发计划

> 来源：2026-09-22 交付 **T-AgentConfigChannel（O-54）+ T-AgentSourceLocateStale（O-55）** 收口后，
> 两份独立只读复核 + 真机级 e2e + 本机门禁实测累计登记 **7 项残留**（均不阻塞该交付；交付本体已合入并推送
> master `c54f4f2`，门禁 testShards 495 套件 / 5645 用例 / 0 失败 + verifyGate 三目标 Compatible）。
> 用户 2026-09-22 决定：**现在收**。
> 相邻事项：两个**真缺陷** `T-ExplainChainHardening (O-48)` / `T-ErrorLocateUsability (O-49)` 另有既有计划
> （`docs/开发计划/20260921-2322-…` / `20260921-2324-…`，均 P1），用户要求**先做那两个**；本计划写集与它们
> 不相交（那两个都改 `ui/SqlPreviewPanel.kt` 须彼此串行，与本文各项无交集），**可并行**。
> 编号：占用 **O-56**（O-53 留空，O-54/O-55 已用）。

## 一、残留清单与修法

| # | 残留 | 现状与依据（文件:行） | 修法 | 成本 |
|---|---|---|---|---|
| R1 | **IDE 侧跳过表未与 agent 侧同集** | `src/main/kotlin/.../log/CallerSourceLocator.kt#isFrameworkFrame` 仅 15 项；agent 侧 `sub-projects/.../util/StackLocator.java#FRAME_SKIP_PREFIXES` 多出驱动族（`dm.jdbc.`/`oracle.jdbc.`/`com.mysql.`…）、连接池（`com.alibaba.druid.`/`com.mchange.`/`org.apache.commons.dbcp2.`/`org.apache.tomcat.`）、AOP（`net.sf.cglib.`/`org.aspectj.`/`org.objectweb.asm.`/`org.jboss.`）与一批「补收」（`com.github.pagehelper.`/`org.mybatis.`/`net.sf.jsqlparser.`/`com.alibaba.`/`org.apache.`/`com.google.`/`com.fasterxml.`/`io.netty.`/`io.lettuce.`/`redis.clients.`/`io.micrometer.`/`io.seata.`/`org.slf4j.`/`ch.qos.`/`org.ehcache.`/`net.sf.ehcache.`/`com.github.benmanes.`/`org.quartz.`/`com.netflix.`/`org.elasticsearch.`/`com.mongodb.`）共 30+ 项 → **同一执行两侧可能给出不同位置**（标记行指到框架帧） | 把 IDE 侧那份**补齐到与 agent 侧同集**（纯数据同步，**不引任何依赖**——IDE 侧是降级主路径，绝不可依赖 agent 代码，见 O-55 计划 §八 D1 定案）；同步更新 `LocatorSkipPrefixSyncTest` 的差异登记 | ~30 分钟 + 门禁 |
| R2 | **shard3 flake 的最后一个来源：测试侧 `@After` 取服务** | `testutil/SqlLogManagerProjectState` 的调用方在 `@After` 里经 `logManager()`（`AlreadyDisposedDiagnostics.projectService`）取服务做复原，而 `@After` 正是 temporarily-disposed 窗口高发时段；服务容器一次创建失败即永久毒化（T-ST-02b2 已证） | 让快照**持有 manager 引用**、`@After` 只 `restore(snapshot)` 不再取服务；**同步改姊妹守卫** `convention/BannedApiSourceScanTest`（它认的是双参 `restore(承接名, snapshot)` 形态）；涉及 5~7 个使用该工具的测试类 | ~1 小时 + 门禁 |
| R3 | **`HeartbeatRhythmTest` 容差 1.5× 偏紧** | `sub-projects/sql-visualizer-agent/src/test/java/.../transport/HeartbeatRhythmTest.java` 的 `TOLERANCE`；本机门禁实测命中一次（472ms > 450ms，同代码复跑 5645 例全绿）——属 `T-PerfSweep6` 发现 14 的登记处置 | 容差放宽到 **2×**（并注明依据：并行构建下调度抖动可达百毫秒级） | 几分钟 |
| R4 | **IDE 侧复核的 3 条低风险** | ① `AgentManager` 的 ACK 超时 WARN 不判会话健康——socket 半死时把「连接已断」误叙成「应用侧未生效」（复核 F3）；② `INTAKE_DROP_WARN_STEP` / `CONFIG_ACK_TIMEOUT_MS` 无字面值断言（F7）；③ 旧 agent（无 ACK）混合版本下桥永久放行 → 双通道共存（F2） | ① 超时 WARN 补判会话健康并分文案；② 补两条字面值断言；③ 仅在 KDoc/CHANGELOG 记边界（不改行为，靠去重折叠） | ~40 分钟 |
| R5 | **T-08 的语句级边界** | `sub-projects/.../hook/ConnectionHandler.java` 的逐调用 `isDisabled()` 短路在 `prepareStatement` 处即直通 → 停采期间 prepare 的语句在恢复后仍不包装（MyBatis 默认 `SimpleExecutor` 每条查询现 prepare，**实际不可达**；仅 `ReuseExecutor` 一类会命中） | 二选一（**待定案 D1**）：A 保持现状 + KDoc 记边界；B 改为「只在执行级短路」（`StatementHandler`），让停采期间 prepare 的语句也被包装 | ~30 分钟 |
| R6 | **`testDatabase` 2 例在册环境失败** | `execute/DbDriverPipelineTest` 的 `testDriverArtifactResolvesToJarsOnClasspath`（本地驱动版本集不含 ConnectorJ 9.7.0）与 `testSeedFilesLandWhereDbLooksForThem`（新工作树沙盒路径未播种） | 先判定是否纯环境性（干净环境复现），再决定「修判据」或「登记为环境前置」；**依赖本机 Database 插件/驱动环境** | 依赖环境 |
| R7 | **真机级探针未纳门禁** | `dev-support/e2e-fake-ide`（假 IDE + `-javaagent` 注入真实 Spring Boot/MPJ 应用，本轮靠它抓到 T-08）现为手动运行 | 包一个 gradle 任务（如 `e2eFakeIde`）挂到收口清单，作为**发布前置**的可脚本化验收（不挂默认 `testShards`，避免拖慢门禁） | ~1 小时 |

## 二、待验证前提（实施前先证伪，勿直接照抄）

| # | 前提 | 验证方式 |
|---|---|---|
| P-1 | R1 补齐后标记行行号**只变准不变错** | 在 demo（join，dm profile）上跑 `rightAlias`/`extSub`，对照面板位置与 `SQLVIS-SOURCE` 标记行；改动前后各取一次读数 |
| P-2 | R2 的「快照持有引用」不会让 `capture/restore` 语义退化（服务未创建时应为 no-op 而非报错） | 先写一条用例：服务未创建时 `capture` 返回可空快照、`restore` 静默跳过；再改实现 |
| P-3 | R3 放宽到 2× 不会掩盖真实节奏退化 | 改前后各跑一轮 `HeartbeatRhythmTest`，并保留「退回累加计时形态必红」的反向验证读数 |
| P-4 | R5 若选 B，「停采期间 prepare、恢复后执行」在 `ReuseExecutor` 下确实恢复产出 | 构造用例（`ConfigApplyEndToEndTest` 同款真 socket + 真 `SenderLoop`）钉住 |
| P-5 | R6 两例确为环境性（非判据错） | 干净环境/基线提交复跑对照；若基线也红则按环境前置登记，不动判据 |
| P-6 | R7 的探针可稳定复跑（不依赖特定端口/时序） | 连续跑 2 轮，两轮读数一致才纳门禁 |

## 三、执行顺序与写集

```
① 两个真缺陷（用户要求先做）：T-ExplainChainHardening (O-48) → T-ErrorLocateUsability (O-49)
   —— 两者都改 ui/SqlPreviewPanel.kt，须串行
② 本计划 R1 + R2（写集互不相交：CallerSourceLocator+同步守卫 / testutil+姊妹守卫+5~7 测试类）
   —— 与 ① 也不相交，可并行
③ R3（agent 子项目测试）+ R4（AgentManager 等）
④ R5（待 D1 定案）、R6（依赖环境）、R7（构建脚本 + dev-support）
```

**写集冲突提示**：R1 与 R2 都碰测试目录但**文件不相交**；R2 与 O-55 计划的收口测试（`LocatorSkipPrefixSyncTest`）不相交（R1 才碰它）；R7 碰 `build.gradle.kts`（与并行会话的临时 sandboxContainer 改动同文件，实施前先 `git status` 确认）。

## 四、不做的事

- 不引入「两侧共享常量」（D3 已定案保持两份，见 O-55 计划 §八）——R1 只是**数据同步**，不建依赖。
- 不改 MCP 审计的既有门（D1 已定案：只受 MCP 工具开关，不受捕获开关；本轮已落地 `addAuditEntry`）。
- 不动 `testShards` 的分片权重/分片规则（flake 的正解是消除毒化来源，不是重排分片）。

## 五、待用户定案

- **D1（本计划）**：R5 选 A（保持现状 + 记边界）还是 B（改为执行级短路，让停采期间 prepare 的语句也包装）？
  倾向 A——默认 `SimpleExecutor` 下不可达，改造要动 agent 包装层且需新用例兜住；B 的价值只在 `ReuseExecutor` 一类。

## 六、验收判据

- 每项：定向测试绿 + **反向验证**（摘掉修复分支必红）读数；R1/R2/R4 另需门禁（`testShards` + `verifyGate`）重跑。
- 全计划收口：`testShards` 三片 0 失败 + `verifyGate` 三目标 Compatible + agent 子项目 0 失败；
  R1 需附「面板位置 vs 标记行」改动前后对照读数；R6 若判环境性需附基线对照读数。
