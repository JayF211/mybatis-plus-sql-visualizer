# T-SourceJumpFunnel 自研框架汇聚入口跳源码上溯适配 —— 验收文档

> **2026-09-20 脚本化验收**：30 条真机走查项改为「配置预置 + 文件系统/日志 + 单元测试」脚本化核验，结论逐条回填在下方判定卡。全量 `testShards` 5383/0/0/6 绿；agent 子项目 `:sql-visualizer-agent:test` 58/0/0 绿（该子项目不进 testShards，本轮单独补跑）。

> **验收对象**：分支 `t-sourcejumpfunnel` 交付（提交 `b117d8c` 主功能 + `870affc` 占位提示，基点 master `f723c0e`，未并入 master、未推送）。
> **面向读者**：功能验收人（含用户本人真机走查）。背景与设计见 [T-SourceJumpFunnel 开发计划](../开发计划/20260917-1726-T-SourceJumpFunnel-自研框架汇聚入口跳源码上溯适配-开发计划.md)。
> **回归基线**：agent 单测 49/0；IDE 全量 testShards **4319/0/0/6 绿**；编译 0 错；设置面板/bundle 对齐定向绿。

---

## 一、背景与验收前置

本任务解决用户公司自研数据访问框架（不经 MyBatis）下「所有 SQL 跳源码都落到框架门面方法 `DataUpdateTool.findByFilter`」的问题。原理：agent 在应用 JVM 执行 SQL 时取调用栈，此前三层判定在「全 SQL 汇聚同一门面」形态下失效；本次收敛判定并新增 IDE 侧统一解析与人工纠偏面。

**环境**：装发行包（`./gradlew buildPlugin`）或 `runIde` 沙盒均可。需一个使用自研框架（SchemaCode + DataUpdateTool.findByFilter 形态，见用户截图示意）的演示工程，配好数据源。

## 二、改动总览（按验收入口分组）

| # | 改动 | 入口 / 位置 | 来源提交 |
|---|------|------------|---------|
| 1 | 源码捕获帧过滤前缀（自研框架包名登记后跳转自动落业务帧） | 设置 → SQL Visualizer → SQL 日志分组「源码捕获帧过滤前缀」 | b117d8c |
| 2 | 实时/历史两面板跳转统一解析，历史面板补候选回退 | SQL 日志 / SQL 历史右键「跳转到源码」 | b117d8c |
| 3 | 调用栈快照（往上追踪）| 预览弹窗顶部提示行（悬停 tooltip 全文） | b117d8c |
| 4 | 帧过滤前缀输入框空态灰色占位提示 | 同上设置输入框 | 870affc |

其余为支撑改动（不直接验收，但真机可间接确认）：agent 候选增厚四列、缓存防合流、上顶收敛、诊断打点（`logLevel=info`）、`cands/sd` 双端协议列尾追加、历史持久化载荷扩展。

## 三、分项验收步骤（真机走查，留人工）

### 3.1 帧过滤前缀热下发（核心入口）

| 步骤 | 操作 | 预期 |
|------|------|------|
| 1 | 打开设置 → SQL 日志分组 | 「源码捕获帧过滤前缀」为多行输入框；**空态时框内有灰色占位文字**「示例：com.corp.engine.（一行一个包名前缀）」 |
| 2 | 悬停该框 | tooltip 显示配置说明（一行一个前缀、保存后即时生效） |
| 3 | 填入一至多行前缀，如 `com.corp.engine.` + 另一框架层包名 | 输入后占位文字消失；多行均可填 |
| 4 | 填完点 OK/Apply | 无需重启应用，立即生效（CONFIG 热下发 agent） |
| 5 | 跑一条 `DataUpdateTool.findByFilter` SQL | **跳转源码落在真实业务方法**（如截图 `getXxxId`），不再落到 `DataUpdateTool.findByFilter` |
| 6 | 清空该框再 Apply | 恢复默认行为（回退候选栈序） |
| 7 | 只填首行、留多余空行 | 空行被忽略，无空白前缀进入生效列表 |

### 3.2 统一跳转解析 + 历史面板候选回退

| 步骤 | 操作 | 预期 |
|------|------|------|
| 1 | 未登记前缀时，捕获一条自研框架 SQL，实时面板跳源码 | 主位置为汇聚门面（非工程内）→ 按候选栈序回退，能落到首个工程内业务帧则跳转成功；全落空则轻通知「not found」并带明细 |
| 2 | 「SQL 历史」页打开同一条 SQL，右键跳源码 | 与实时面板**同一解析结果**（历史面板此前无候选回退，本次补齐，是最直接的对照点） |
| 3 | 历史页条目含候选/栈快照 | 历史读回后候选与栈快照仍可用（pd 载荷扩展键） |

### 3.3 调用栈快照（往上追踪）

| 步骤 | 操作 | 预期 |
|------|------|------|
| 1 | 双击一条自研框架 SQL 打开预览 | 顶部提示行追加「调用栈: `业务方法#行 ← …`（栈顶在左，截断展示）」 |
| 2 | 悬停该 meta 行 | tooltip 显示完整调用链（`类#方法:行 ← …`，可逐帧看到业务 → 框架门面全链） |

### 3.4 诊断打点（定谳失效机制，供排障）

| 步骤 | 操作 | 预期 |
|------|------|------|
| 1 | agent `logLevel=info` 跑一条 SQL | 应用控制台输出 `[T-SourceJumpFunnel] primary=... via=L1/SAFETY entry=... pool=...`，含前 8 帧类名与各自 jar 判定——可确认主位置判定路径 |
| 2 | 默认 `logLevel=warn` | 无该打点（零噪音）；不填前缀时跳转行为与旧版一致（不劣化） |

## 四、自动化已验证项（无需人工）

| 项 | 结果 |
|----|------|
| agent 全量单测 | 49/0（新增 `SourceJumpFunnelTest` 5 例 + `CaptureRulesTest` 扩展：候选四列/前缀热效力/栈快照截断） |
| IDE 定向新用例 | `EntryJumpResolverTest` 6 例 + `AgentProtocolSkipPrefixesTest` 3 例全绿 |
| 既有跳转回归 | `SqlLogPanelLiveJumpWithSourceTest` / `HistorySqlLogPanelJumpToSourceTest` / `AgentEventAdapterTest` / `SqlLogParamCodec` 全绿 |
| 全量门禁 | `./gradlew testShards` → **4319/0/0/6**（相对上线基线 4319，无回归） |
| 双端协议兼容 | `cands/sd` 列尾追加：旧 IDE 按下标读前两列（`AgentEvent.parseCandidates` 已按 `getOrNull` 演进容错）；新 IDE 读旧 agent 事件缺列降级；`AgentProtocolSyncTest` 仍绿 |

## 五、已知边界

- 帧过滤前缀仅作用于 **agent 侧主位置帧判定**；IDE 侧候选回退仍以「工程内能否解析」为准。若跳转仍落工具类帧（业务直接调工具类再调框架），需在设置中追加该工具类包名前缀。
- P1-1 选帧弹层 / P1-2 选择记忆 / P2-1 汇聚入口自适应检测**未实施**（本次交付 P0 全部 + P1-3 + P2-2）。当前「填前缀 + 候选回退 + 栈快照人工上溯」为兜底方案。
- MyBatis/MP 拦截器链路（`CallerSourceLocator`）不受影响——自研框架下本就没有拦截点，统一解析器已留其后续接入位。
- 引擎内部表结构/元数据解析（schemaCode 语义）不在本任务范围，只修「跳转落点」。

## 六、真机复验判定卡

勾选通过即判定闭环：

- [x] 设置页输入框空态有灰色占位文字（§3.1-1） — ✅ 脚本化通过：FrameSkipPrefixesFieldVisibilityTest 1 例（输入框在组件树且不塌缩）+ SettingsRealityGuardTest 4 例（bundle 键引用守卫）+ bundle:285 赋 emptyText，均 0 失败
- [x] 填入公司引擎包名前缀 Apply 后，跳转落真实业务帧、无需重启（§3.1-5） — ✅ 脚本化通过：AgentProtocolSkipPrefixesTest 3 例（带前缀追加 skipPrefixes 键）+ EntryJumpResolverTest 6 例，均 0 失败；热生效见 CaptureRulesTest 15 例
- [x] 历史页跳转与实时页同结果（§3.2-2，本任务最直接的改进观察点） — ✅ 脚本化通过：HistorySqlLogPanelJumpToSourceTest 6 + SqlLogPanelLiveJumpWithSourceTest 10 + EntryJumpResolverTest 6（两侧同调 EntryJumpResolver.resolve）0 失败
- [ ] 预览 meta 行显示调用栈、悬停见完整链（§3.3） — ⏳ 需真机：截断展示与悬停全文为纯视觉判断（载荷往返有 EntryJumpResolverTest 覆盖）
- [ ] `logLevel=info` 打点确认判定路径，与复验前对照（§3.4） — ⏳ 静态核验：StackLocator.diagnose 实现已在（primary/via/entry/pool + 前 8 帧），但 logLevel 由插件硬编码 warn 传入（AgentProtocol.kt:28），端到端须手改启动参数，无自动化路径
- [x] 清空前缀 / warn 档行为不劣化（§3.1-6 / §3.4-2） — ✅ 脚本化通过：AgentProtocolSkipPrefixesTest#空前缀列表_退化为既有两键body 0 失败；agent 侧 CaptureRulesTest 15 例含热生效与清空
