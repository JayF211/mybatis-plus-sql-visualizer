---
name: pitfall-agent-locator-cache-stale-line
description: agent 源码位置串用的根因——StackLocator 的 L1 整帧缓存把行号冻结，缓存键取「首个非框架帧 class#method」，而 MPJ 拦截器帧是全应用共享帧且不在跳过前缀表里，于是首个查询的行号被钉死给后续所有查询；**20260922 已修复（L1 与 SAFETY 合并为判定缓存）并合入集成分支**
metadata:
  node_type: memory
  type: project
  originSessionId: sess_bef52d81-b9f2-456b-95bc-3e62d26f3e58
---

2026-09-22 用户报障「捕获的源码位置也不对」：面板条目显示 `[DeviceInfoServiceImpl.java:42]`，
而应用控制台同一次执行的标记行是 `SQLVIS-SOURCE|…/DeviceInfoServiceImpl.java|63`。
真值 63＝`joinExtAndSubTable()` 里的 mapper 调用行，42＝**同一个类里另一个方法** `joinRightAlias()` 的 mapper 调用行。
复现：同一应用 JVM 内先请求 `/mpj/device/rightAlias`（真值 42，此时面板正确）再请求 `/mpj/device/extSub`（真值 63，面板仍显示 42）。

**根因＝`sub-projects/sql-visualizer-agent/.../util/StackLocator.java` 的 L1 整帧缓存冻结行号。**
入口键取「栈上**首个非框架帧**的 `class#method`」，命中即返回**整帧**（行号与候选列一起冻结），
写入是 `putIfAbsent`（首个 L1 结果永久服务该 key 的后续所有调用者）。
而 MPJ 1.5.8 经 starter 自动配置注册了 `com.github.yulichang.interceptor.MPJInterceptor`
（`MybatisPlusJoinAutoConfiguration` 常量池引用实测；类 `implements org.apache.ibatis.plugin.Interceptor`），
它对全应用所有查询都是**同一个栈帧**，且 `com.github.yulichang.` **不在跳过前缀表里**
（表里已有 `com.github.pagehelper.` / `com.github.benmanes.` 两个同族漏项）→ 键被库帧占据 → 首个查询把 42 钉死。

**同一份文件里已有自证**：姊妹缓存 `SAFETY_DECISIONS` 的 KDoc 写着「只存判定不存结果帧——整帧入缓存会冻结行号与候选列
（P0-3 缺陷形态，禁）；命中须经『身份在场』校验」，SAFETY 路径照此改造了，**L1 路径没照做**；
`writeCaches` 的 KDoc 还把这个后果记成「已知残面：先到的 L1 结果同样服务后续调用者」。

**Why:** 错法是「钉死」而非随机——对 MPJ（本插件主力支持框架之一）用户，一个应用进程里**所有条目显示同一个位置**，
跳转落到同类里另一个方法；`sourceCandidates` 同样被冻结，选帧弹层与往上追踪的候选过期。
凡「在栈上、不在跳过表里、对多次调用是同一帧」的库都会造成同样后果（自研框架、其他 ORM 增强库）。

**How to apply:**
- 判「位置不对」先比两个答案：面板 meta 的 `[文件:行]` 与**控制台标记行** `SQLVIS-SOURCE|相对路径|行号`
  （标记行由注入的 `SqlSourceInterceptor` → IDE 侧 `CallerSourceLocator` 产出，是独立路径，可当对照）。
  若「同一进程内后面每次请求都显示同一个位置」→ 就是这个缓存冻结。
- 修法（已立项 **T-AgentSourceLocateStale / O-55**，计划 `docs/开发计划/20260922-0917-…`）：
  L1 改为与 SAFETY 同构的**只缓存判定** + 命中时按当前 pool 做「身份在场」校验并现算行号（根治，键是否精确不再决定正确性）；
  补 `com.github.yulichang.` 等缺失前缀（收敛面，不单独成立）；两侧跳过规则建单一事实源或登记差异；合成栈测试 + 「回退必红」反向验证。
- **第二层原因（同族，修缓存后仍会露头）＝「两侧跳过规则形态」（即计划里的定案项 D3）**：同一件事「沿栈跳过框架帧取首个业务帧」
  有**两份独立实现**——agent 侧 `StackLocator.FRAME_SKIP_PREFIXES`（应用 JVM 内 agent 用，产出面板那个位置）与
  IDE 侧 `CallerSourceLocator.isFrameworkFrame`（注入拦截器模板用，产出控制台标记行 `SQLVIS-SOURCE|文件|行号`）；
  之所以是两份＝agent 是独立 Java 制品、**不能引 IDE 的类**。两份已漂移：agent 那份丰富得多
  （驱动族 `dm.jdbc.`/`oracle.jdbc.`/`com.mysql.`…、连接池 `com.alibaba.druid.`/`com.mchange.`…、AOP `net.sf.cglib.`/`org.aspectj.`…，
  外加补收 `com.github.pagehelper.`/`com.alibaba.`/`org.apache.`/`com.google.`/`com.fasterxml.`/`io.netty.`/`org.slf4j.` 等共 30 余项），
  IDE 侧只多一条 `$Proxy` → 栈里有 druid/cglib 类帧时 agent 跳过、IDE 侧不跳，标记行可能指到框架帧。
  **已落地的保守方案**：两份仍分开 + 两侧 KDoc 改事实描述（原来那句「与 IDE 侧对齐」是假的）+ 新增跨侧同步守卫
  `LocatorSkipPrefixSyncTest`（差异清单登记在测试里，任一侧增删一条即转红，防静默漂移）。
  **另一条路（共享常量）不划算**：agent 不能引 IDE 类，只能「生成式常量 + 同一扫描守卫」；且它**只统一跳过集合、统一不了结论**——
  两个定位器**取栈时机不同**（拦截器在 SQL 生成期、agent 在 JDBC 执行期），同一执行仍可能给不同位置。
  最小替代动作（若更在意「标记行与面板位置同源」）＝把 IDE 侧那份补齐到与 agent 侧同集，代价是标记行行号可能变化、既有走查基线要重取。
  **✅ D3 已定案（2026-09-22 用户）＝保持两份**（不抽共享常量）。理由链（可复用）：①两份*列表*维护成本极低，
  真正贵的是「防静默漂移」，而该成本已由 `LocatorSkipPrefixSyncTest` 付掉；改共享常量要引入构建期生成（新链路 + 新失败面），总成本更高。
  ②**关键约束（用户点明）**：IDE 侧那份是**降级主路径**——agent 未授权/未注入/attach 失败/版本旧时全靠它产出标记行，
  因此**绝不可在运行期或编译期依赖 agent 侧代码**；即便日后做共享常量，也只能是「构建期生成一份数据」。
  ③R1（补齐 IDE 侧）已作为可选后续登记在 T-AgentResiduals（O-56）里，实施前提＝接受标记行行号变化。
- **排障前提**：`diagnose()` 只在 `logLevel=info` 输出，而 premain 注入固定 `logLevel=warn`（沙盒日志实测 `=port=…;logLevel=warn`）
  → 真机拿不到 `entryKey`/`pool`，只能靠行为反推（已立 T-05 让入口键可取证）。

**✅ 已合入 master（2026-09-22）**：随三车道经集成分支 `t-agent-config-locate` 合入（合并提交 `0eafa67`，集成分支已删、0 笔未合并）；
最终态门禁 `testShards` 三片全绿（5644 用例）/ `verifyGate` 三目标 Compatible / agent 83 例 0 失败。

**✅ 收口（2026-09-22）**：真机沙盒走查第 5 步通过——面板 `#1 … [DM (达梦)] [DeviceInfoServiceImpl.java:42]`（rightAlias）与
`#2 … [DM (达梦)] [DeviceInfoServiceImpl.java:63]`（extSub），与控制台标记行 `SQLVIS-SOURCE|…|63` **逐条一致**（修复前 #2 会错成 42）。
用户在自己的工程里也确认了同一形态：同一 service 的两个调用点各自显示自己的位置（`:73` 与 `:105`），即「位置被首个查询钉死」已消除。

相关：[[pitfall-agent-config-channel-never-applied]]（同轮报障的「开关不真」）、[[frame-skip-prefix-demo-verification]]
（前缀机制的验证载体与两条实测口径）、[[reference-real-ide-debug-entry]]。
