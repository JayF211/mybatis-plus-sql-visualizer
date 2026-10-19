# T-FirstUseFreshInstallConsent：出厂未确认态首启静默与补扫空转修复（R3）——开发计划

> 2026-10-05 用户在 master 实测反馈（截图+沙盒日志）：「首次启动项目还是没有任何提示、没有增强捕获弹框，第二次启动项目才出现弹框，但 SQL 没有成功捕获」。本计划基于 12:39~12:46 沙盒会话日志归因。**与 [T-StatementWeave 计划](20261005-1148-T-StatementWeave-agent语句级织入-开发计划.md)（已确认实施）合并为同一分支交付**——本计划解决「弹框时机」，StatementWeave 解决「同意后零捕获」。写完停等确认，未获确认不实施。

## 一、日志实证（12:39~12:46 会话，master 含 R1+R2）

| 时刻 | 日志 | 判读 |
|---|---|---|
| 12:41:42.166 | `premain 提前确认跳过：headless 或非 EDT 线程` + `premain 注入跳过：enhanced-unconfirmed` | 首轮 Run：出厂态 confirmed=false；updateJavaParameters 在后台线程，R1 前置确认被 EDT 闸门跳过（R2 已知回落） |
| （12:41:4x） | ——无 processStarted/onProcessAppeared 主配置日志—— | 首启主进程出现，但**事件被错过** |
| 12:41:59.824 | `已订阅 EXECUTION_TOPIC` | EXECUTION_TOPIC 订阅落在首启进程出现**之后约 17 秒**——SqlLogManager 懒创建，事件早于订阅（R2 已知竞态） |
| 12:41:59.8xx | ——无任何 `backfill:` 日志—— | R2 补扫在订阅时执行，进程存活却 0 命中且零诊断（见 §二.2 实锤） |
| 12:42:27 / 12:43:15 | `非主配置进程启动……静默跳过` | 用户打 HTTP 请求（正常路径） |
| 12:44:17.854 | `onProcessAppeared: 增强已开启但未确认注入——请求确认后挂载` | 二轮 Run：attach 链弹确认框（用户截图 2 的弹框） |
| 12:44:31.697 | `用户同意注入——继续挂载` | 用户点「同意并启用」（截图 2） |
| 12:44:34.850 | `notifyAttachFallbackOnce: attach 兜底挂载成功` | attach 成功，但池化连接建于 attach 前 → **0 条 SQL**（截图 1「产出 0 条」）——StatementWeave 缺口 |

## 二、根因（四段，前两段是本计划新修）

### 1. 出厂态结构性地绕过 R2「开启时刻确认」（主因）

R2 方案 C 把确认挂在 SETTINGS_CHANGE **开启上升沿**。但出厂态是 `enhancedCaptureEnabled=true`（默认）+ `enhancedCaptureConfirmed=false`——**从出生就是开着的，永远不产生上升沿**。R2 只覆盖「用户主动开启」，没覆盖「出厂即开但未确认」这个最普遍的首次使用形态。首启静默于是回落到 attach 链，而 attach 链又撞上根因 2。

### 2. R2 补扫结构性空转：`RunContentDescriptor.getRunProfile()` 在 243 不存在（javap 实锤）

补扫实现按 `descriptor.javaClass.getMethod("getRunProfile")` 探测主配置。243 `com.intellij.execution.ui.RunContentDescriptor` 全方法列表**没有 `getRunProfile`**（构造器可传入 RunProfile，但无 getter）→ 每个描述符 `NoSuchMethodException → getOrNull → null → continue` **静默跳过**。12:41:59.8 订阅时首启进程存活，补扫却 0 命中、零日志——不可观测的假绿。

### 3. 订阅晚于首启（R2 已知竞态，本计划不改结构、以根因 1 的修复旁路）

SqlLogManager 懒创建（12:41:59.8，晚于首启进程 ~17s）→ EXECUTION_TOPIC 订阅必晚。根因 1 修复后，同意发生在项目打开期、先于任何 Run，premain 注入不依赖该订阅——竞态对「捕获完整性」的影响被旁路；对控制台桥（二通道）仍是既有行为，登记观察。

### 4. 同意后 attach 零捕获（已立项）

池化连接建于 attach 前、不可回溯包装——T-StatementWeave（0e3d3ee）语句级织入正解，随本分支实施。

## 三、方案设计

### S1（主修）：出厂未确认态「项目打开即确认」

- ProjectActivity（挂 `LogStartupCapture.execute` 尾部，或独立 activity）：条件 = EDT 可弹闸门（复用 `premainConsentGate`）+ `enabled && !confirmed`（即 `needsReauthorization()`）+ 非冷却 → BG 线程 `invokeLater` EDT 走 `requestConsentOnce()`。
- 效果链：项目打开 → 弹一次确认（截图 2 同款三态框）→ 同意持久化 confirmed → **首轮 Run 的 updateJavaParameters 即产出 premain**（不再依赖 attach 链、不受订阅竞态影响）→ 连接从建池起包装、首启捕获完整。
- 三态语义全部沿用现单点：同意=confirmed；取消=24h 冷却（期间不再弹，状态行如实显示）；关闭增强=enabled=false。`requestConsentOnce` 去重窗天然防「启动即弹 + 用户立刻去设置页开启上升沿」双弹。
- EDT 前提不成立（headless 测试等）静默跳过，回落既有 attach 链——零劣化不变。

### S2：补扫修复与诊断性

- 主配置判定替换：**去掉不存在的 `getRunProfile` 探测**，改为「JVM 进程 handler」判据（handler 为 `BaseOSProcessHandler` 系实例——Java 应用/测试 fork 均是；HTTP 客户端 handler 天然排除）；`ExecutionEnvironment` 关联若可从 handler userData 取得则作增强判定（待验证前提 1，取不到不阻断）。
- **逐描述符 skip 原因 debug 日志 + 补扫汇总 INFO**（发现 N 个描述符 / 命中 M 个补发 / 各跳过原因计数）——消灭「结构性空转零诊断」这一形态（「加了防护」≠防护有效，必须可观测）。
- 残余风险登记：测试 fork JVM 在首启窗口会被补扫视为候选（attach 链无 T-GradleTaskInjectScope 范围闸门）——弹一次确认有三态+冷却兜底，可接受；如走查实测困扰再收窄。

### S3：文案口径随 StatementWeave S6 一并修订（不独立做）

### S4：测试与反向验证

- 出厂态启动即弹：enabled=true+confirmed=false → 触发 activity 逻辑 → 弹一次（gate 注入）；confirmed 态不弹；冷却态不弹；与上升沿/引导链并发不双弹（去重窗既有测试扩展）。
- 补扫：源码守卫断言新判据在场 + skip 日志调用在场；摘 S2 判据块必红。
- 全包回归 agent+config；`testFast` 归口。
- runIde 沙盒走查（随分支实施后的下一批）：出厂态新项目打开即见弹框（进程启动前）→ 同意 → 首轮 Run 启动期 SQL 即捕获。

## 四、待验证前提（实施者先证伪再动手）

1. `ProcessHandler.getUserData(ExecutionEnvironment.KEY)`（或等价 DataKey）在 243 是否挂着 env——是则补扫可精确判定主配置；否则用 S2 的 JVM 进程判据宽口径。
2. `BaseOSProcessHandler` 系判据对 HTTP 客户端 handler 的排除是否完备（`HttpClientRequestProcessHandler` 的继承链 javap 确认不在 BaseOSProcessHandler 系）。
3. S1 弹框时机在项目打开 EDT 空闲期的用户感知（模态打断 vs 首启静默的取舍——当前口径：一次性的知情同意，打断可接受；真机走查可复核）。
4. 出厂态「项目打开即弹」与 O-66 引导框（缺位置引导）同屏叠加的场景——O-66 编排自带授权确认，去重窗吸收；真机走查确认。

## 五、验收标准

1. 出厂态（新装/新 confirmed=false）打开项目：**Run 之前**即见增强捕获确认框；同意后首轮 Run 日志含 `premain 注入完成`，启动期 SQL 即被捕获（不再出现「第二次才弹、弹了也零捕获」）。
2. 首启进程先于订阅的场景：补扫按新判据命中并补发（日志可见「命中 M 个」），或如实记录跳过原因——不再静默空转。
3. 拒绝/关闭/冷却三态行为与现语义一致。
4. 反向验证两次读数留档；agent+config 全包回归绿。

## 六、实施记录（2026-10-05 当轮回填，master 直做）

### 实施内容

- **S1**：`AgentManager.maybeAskConsentAtStartup()`——`needsReauthorization()` 前置短路（已确认/未开启同步返回）→ `invokeLater` EDT → `premainConsentGate` 复核（headless/非EDT 静默回落）→ `requestConsentOnce()`（三态落盘+去重窗单点）。接线在 `LogStartupCapture.execute` **最顶部**（任何 early return 之前——自动监听开关与确认无关），`runCatching` + fatal 透传。
- **S2**：`backfillRunningProcesses` 判据替换——删除不存在的 `getRunProfile` 探测，改 `BaseOSProcessHandler` 系实例判定（反射 Class.forName，与文件既有口径一致）；诊断性=逐描述符 skip 原因 debug + **无命中也输出**的汇总 INFO（描述符数/补发数/无handler/已结束/非JVM 分类计数）。
- **S4 测试**（`AgentPremainConsentTest` 新增 7 例）：启动即弹五态（同意持久化/拒绝冷却/已确认不弹/冷却期不弹/EDT守卫false不弹）+ S1 接线源码守卫（调用在场且在 `shouldStartCapture` 早退**之前**）+ S2 判据与诊断源码守卫（`BaseOSProcessHandler` 在场 / backfill 函数体内无 `getRunProfile` / 汇总日志在场）。

### 过程坑（留档）

- **源码守卫扫描面**：首版「全文件禁 `getRunProfile`」误伤 `isModuleBasedLaunch`——`ExecutionEnvironment.getRunProfile()` 在 243 **存在且合法**（事件链用），禁令只针对 descriptor 探测。守卫收窄为 backfill 函数体切片判定。
- 无其他坑；`requestConsentOnce` 去重窗、`premainConsentGate` 均直接复用 R2 资产。

### 反向验证两次读数

- **红**：摘 LogStartupCapture 接线 → `testR3启动即弹接线源码守卫` 红；摘 BaseOSProcessHandler 判据 → `testR3补扫判据与诊断源码守卫` 红（同跑双红）。
- **绿**：恢复接线 → agent+config+LogStartupCapture 回归 **44 类 335/0 绿**。

### 遗留与收口状态

- **runIde 沙盒走查留下轮批**（真机段验收 1：出厂态打开项目即弹框→同意→首轮 Run `premain 注入完成` 日志+启动期 SQL 捕获；补扫汇总日志读数）。
- 「同意后 attach 零捕获」由 T-StatementWeave 计划解决，随 `t-statementweave` 车道实施（在途）。
- 全量门禁按三档分层另派只读收口代理。
