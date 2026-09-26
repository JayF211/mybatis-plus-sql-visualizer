# T-ToolSiteBizFrame 执行点在业务工具类跳转上顶-开发计划

- **日期**：2026-09-26 18:57
- **工作树**：`F:/code/Java/Gitee/SongRenShuo/mybatis-plus-sql-visualizer-toolsite`（分支 `toolsite-bizframe`，基线 `edeb9a6`）
- **上游依据**：demo 仓库 PR !1（已合并，`29d64ad`）——「SQL 执行点在业务工具类」语料 E28~E34 + JAR 态子工程 `selfdev-shared-lib`，其开发计划 `20260923-1520-执行点在业务工具类跳转上顶场景-开发计划.md` §7.3/§7.5 用桩边界真实栈逐字复刻 `StackLocator` v2026.9.23 规则实测，钉死两个**未修机制**。
- **用户现场**（脱敏）：公司自研框架场景下部分 SQL 捕获位置不识别、跳转不精准——面板/跳转落在上层 Service 里「调用工具类」的那一行，而不是工具类内的 SQL 行。

## 一、问题定性（来自 PR 实测表，非推断）

| 机制 | 现状 | 落点形态 |
|---|---|---|
| **A：L1 来源过滤把 JAR 帧一律当第三方** | 未修 | 业务工具类以依赖 JAR 运行时，主位置上顶到第一个目录帧（调用方 Service）；登记其 JAR 包名进「帧过滤前缀」只会让它从候选池消失，落点不变（E29 P0/P1/P2 三档同落点实证） |
| **B′：判定缓存「身份在场」校验粒度不足** | 未修，**最贴合用户现场** | 同一 `entryKey`（共享引擎帧）下，先直发 SQL 的方法把「主位置身份＝本方法」写进判定缓存；该方法后续经工具类发 SQL 时是**祖先帧、必然在场** ⇒ 校验通过 ⇒ 主位置钉在浅帧，行号现算＝「调用工具类」那一行（E34 P0 实测；真执行点一直在候选列） |
| B：同身份行号互相冻结 | v2026.9.23 T-AgentSourceLocateStale T-01 已修 | E30→E31、E32→E33 为回归护栏，本轮不得破坏 |

## 二、修法（对齐 PR §7.5 修法线索 ②④）

### 修 1（B′，`StackLocator` 判定缓存命中路径）

缓存命中且身份在场后，**不再直接采用**：在当前 pool 的 `[0..at-1]`（比缓存身份**更深的调用链段**）里找首个「业务来源帧」（= L1 同判据：目录来源 **或** 命中反向名单）；找到即改用该帧并刷新判定（via=L1）。找不到才沿用缓存身份。

- 语义依据：全量路径 L1 本来就从 pool[0] 起取首个业务来源帧——缓存命中路径的契约是「与全量路径同结果、更便宜」，B′ 缺陷正是命中路径与全量路径**结果分叉**。
- 快路径零开销：`at == 0`（健康形态：直发 SQL 的方法自己是 pool[0]）时无更深层段，直接沿用，**零反射**；仅 `at > 0` 时逐帧探测，E34 形态下 pool[0] 即真执行点、一次 `Class.forName` 封顶（probe 注入口下为零成本）。
- 深帧 file 为 null（无调试信息）时与全量路径对齐：照常改用并刷新（`buildFrame` 返回 null → 本次不产出位置、不刷新缓存），不悄悄退回浅帧。

### 修 2（A，JAR 业务帧反向名单，端到端）

现「源码捕获帧过滤前缀」只有**跳过**语义；新增语义相反的登记项——**登记的包名前缀，其帧即使来自 JAR 也按业务帧对待**（L1 不再上顶）。

| 层 | 改动 |
|---|---|
| `StackLocator`（agent） | 新增 `EXTRA_BIZ_PREFIXES`（volatile 整组替换，与 `EXTRA_SKIP_PREFIXES` 同型）+ `setExtraBizJarPrefixes`；L1 与修 1 的探针共用同一业务来源判据 `isBusinessOrigin`（`isExtraBizFrame(cn) || !loadedFromJar`） |
| `SenderLoop`（agent） | CONFIG 入站新增 `"bizJarPrefixes"` 键提取（复用 `extractSkipPrefixes` 同款扫描器）；缺键=清空（与 skipPrefixes 语义一致）；ACK body 追加 `"bizJarPrefixes":<条数>` |
| `AgentProtocol`（IDE） | `configBody` 增带 `bizJarPrefixes` 的重载（空列表省略键，旧 agent contains 解析天然忽略）；`ConfigAck`/`CONFIG_ACK_REGEX`/`configAckBody` 追加 `bizJarPrefixes` 段 |
| `AgentManager`（IDE） | `sendConfig` 读新设置项随 CONFIG 下发；ACK 日志带出生效条数 |
| 设置面（IDE） | `PluginSettings` 新字段 `sourceFrameBizJarPrefixes`；`PluginSettingsPanel` 仿 `frameSkipPrefixesField` 加行（isModified/apply/reset 三处）；`PluginSettingsFolderConfigurable` 加 SearchableField；bundle 中英各三条（label/tip/placeholder） |
| 文档 | 设置项 tip 明确「skip=跳过框架帧 / biz=提升为业务帧」二分；E34 实证「须把整条引擎链包名登记全」写进 tip |

**IDE 侧 `CallerSourceLocator` 不动**：它是无 L1 来源判定的前缀名单降级主路径，JAR 工具类帧在其规则下本就会被选为最深业务帧，机制 A 是 agent 侧独有。

## 三、复现（先红后绿，反向验证纪律）

agent 单测（同包缝：`jarOriginProbe` + `clearLocatorCachesForTest`，合成栈形状照 PR §7.2 取证口径）：

| 用例 | 复刻 | 修前 | 修后 |
|---|---|---|---|
| `E34同形_共享引擎帧_先直发后经工具类` | SQL① 直发（pool=[svc:129]）→ SQL② 经工具类（pool=[tool:96, svc:132]），共享 entryKey=内核帧 | SQL② 落 `svc:132`（**红**） | 落 `tool:96` |
| `E29同形_JAR态工具类_反向名单救回` | pool=[tool(JAR):38, svc(dir):66]，entryKey=内核帧 | 落 `svc:66`（**红**） | 登记反向名单后落 `tool:38`；不登记仍 `svc:66`（负例共测） |
| 回归护栏×3 | T-01 五用例（行号自愈/身份不在场/漂移）；E30→E31 同身份两调用点；「业务帧来自 JAR 且未登记」安全网不劣化 | 绿 | 保持绿 |

协议层：`SenderLoopFrameTest` 增 bizJarPrefixes 提取/缺键清场/ACK 计数用例；`ConfigApplyEndToEndTest` 或同型端到端增一条「下发→生效→ACK」；IDE 侧 `AgentProtocol` 解析与设置面改动由既有同步测试家族（`AgentProtocolSyncTest`/`FrameSkipPrefixesFieldVisibilityTest` 同型）覆盖，随改随补。

## 四、待验证前提（实施者先证伪再动手）

1. **PR 实测表可信**：E29/E34 落点结论来自 PR 作者桩边界复刻，本轮**不重复真机取证**，以 agent 单测（同判据复刻）先红后绿作为本轮复现证据；真机 runIde 走查不在本轮范围（留给有 MySQL 的走查轮）。
2. **`at == 0` 快路径无更深层段**：pool[0] 是栈序最深业务帧，`[0..at-1]` 为空即无更深——由 `locate` 的 pool 构建顺序（`firstNonFramework` 起栈序追加）保证，测试覆盖。
3. **反向名单只在 L1/探针判据处消费**，不进 `isFrameworkFrame`——否则等价于「跳过」，候选池会被抽空（PR P2 档已证）。实施后 grep 确认无第二消费点误接。
4. **ACK 扩段的兼容边界**：agent 与插件同制品分发、版本恒同对；唯一错配窗口=插件更新后应用仍挂旧 agent——旧 agent 的 ACK 缺 `bizJarPrefixes` 键会被新 IDE 正则记畸形（与 P1-3 引入 skipPrefixes 时同边界，接受并留痕）。
5. **`SenderLoop` 入站解析对追加键顺序不敏感**：`contains` + 键名定位提取，与 skipPrefixes 同机制，前提由帧格式测试锁定。

## 五、实施步骤

| 步 | 内容 | 交付 |
|---|---|---|
| M1 | agent 侧复现测试（E34/E29 同形两用例，先红留读数） | 提交 1 |
| M2 | 修 1：判定缓存命中深度比选 + 快路径；回归护栏跑绿 | 提交 2 |
| M3 | 修 2 agent 侧：EXTRA_BIZ_PREFIXES + isBusinessOrigin + SenderLoop 提取/ACK；M1 用例转绿 | 提交 3 |
| M4 | IDE 侧：AgentProtocol/AgentManager/设置面/i18n；同步测试补断言 | 提交 4 |
| M5 | 定向测试收口（`:sql-visualizer-agent:test` 定向类 + IDE 侧定向类）＋计划文档实施记录回填 | 提交 5 |

**验证口径**：只跑定向测试（agent 子项目改动 ⇒ `:sql-visualizer-agent:test` 定向类；IDE 侧改动 ⇒ 对应定向类）；不跑全量、不跑构建校验、不 `clean`、不 `--stop`；沙盒 `MPSV-ToolSite` 已登记（并行任务 T-FullRegression 活跃，构建并发让位）。

## 六、实施记录

（回填处）
