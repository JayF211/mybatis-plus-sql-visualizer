# T-SessionIdentityGuard 会话身份 fail-closed 加固 — 开发计划

- 日期：2026-09-18 08:33
- 任务编号：T-SessionIdentityGuard（第七轮报告 §9.4 **O-33**，P2；原拟定名「会话恢复身份 fail-closed」——核实后改名收窄为「仍在猜的分支加固」，跨 IDE 重启恢复面定案不适用）
- 来源：第七轮报告 §7 zMyBatis 精华清单 **Z-6**——持久化以「稳定数据源 UUID + 显式 schema」为身份，恢复校验失败即弃而非猜测（ConsoleCacheService.kt:43-52）
- 性质：执行环正确性——共享会话复用与数据源记忆层仍有四处「静默猜」，与 T-ConsoleDbContext「URL 库为准」定案同方向收尾
- 实施分支：合流后自 master 拉任务分支 t-session-identity-guard（前置项 T-MergeDev2Master 策略决策）
- 验证基线：合流后 master 全量 testShards 绿（以合流时钉死计数为准）

---

## 一、背景与依据

zMyBatis 对会话身份的原则是「校验失败即弃，绝不猜」。本插件锚点核实（master 实测）的结论：**冷启动与执行代纪层面已是 fail-closed**（跨项目缓存拒绝、exec 代际计数作废挂起提交、`findScriptModel` null 明确报错、run 失败清记忆），跨 IDE 重启本就没有会话恢复逻辑（重启即冷选，无「恢复猜错」问题）——这两面不适用。真正的缺口集中在**复用与记忆层**的四个「静默猜」分支：

| # | 猜测点 | 现状行为 | 位置 |
|---|---|---|---|
| 1 | 共享控制台复用身份 | 只比 last-used **显示名** + `console.isValid()`——数据源删除/重建后旧 console 仍 valid 即照常复用，直到连接失败才暴露（attach 路径有 `probeConsoleValidity` 含 `dsInFacade` 探测，复用热路径**不调**） | `DataSourceSelector.kt:370,374-376`（对照 attach `:1306-1325`） |
| 2 | last-used 记忆命中 | 同名多条数据源时 `firstOrNull { displayName == last }` 静默取首条，无提示 | `DataSourceSelector.kt:307` |
| 3a | 库上下文对齐 | URL 无库时**静默跳过对齐**（保留 DBN 文件映射/历史记忆库——真机「钉错库」事故形态仍可复现） | `execute/ExecConsoleContext.kt:35` |
| 3b | schema 设置 | `switchSchema` 失败回退 `setSearchPath` **强设不校验目标库存在性**；`ObjectKind.DATABASE` 硬编码对 PG 等 schema 型方言是否正确未验证 | `ExecConsoleContext.kt:40,42-45` |
| 4 | attach 路径探测失败 | 仅 disposed 快速失败；`dsInFacade=false` 等失效重试 3×300ms 后 warn **继续** | `DataSourceSelector.kt:1311-1325` |

辅助事实：`DataSourceSettings`（项目级持久化 `defaultDataSourceId`=UUID + `getUniqueId()` 反射封装）已存在但**全仓零调用**——UUID 身份能力已备未接（M36 遗留）。

## 三、方案

**T-SI-01（复用身份接 UUID 探测）**：`reuseSharedConsole` 复用判定从「显示名相等 + isValid」升级为「显示名相等 + isValid + `probeConsoleValidity` 的 `dsInFacade` 分量」（复用 attach 路径既有探测，不重造）；`dsInFacade=false` 时弃共享会话、清 last-used 记忆、走重新选择（fail-closed 与 attach 同规）。探测成本可接受（复用是低频路径），必要时复用既有有界重试节奏。

**T-SI-02（记忆键升级 UUID）**：last-used 记忆从纯显示名升级为「UUID 优先 + 名字回退」双字段（`PropertiesComponent` 键新增一个，旧键读取保留兼容一个版本）；命中顺序：UUID 精确命中 → 名字命中且**唯一** → 否则弃记忆走弹窗（同名多条不再静默取首条）。`DataSourceSettings.getUniqueId()` 反射封装直接复用。

**T-SI-03（对齐 fail-open 两点收口）**：`ExecConsoleContext.alignToDataSourceDatabase`：①URL 无库时不再静默跳过——照常尝试对齐，失败则 `describeContext`/预览头标注「未能确定默认库，当前=X」（诊断可见化，不阻塞执行）；②`setSearchPath` 强设后读回校验（`select current_database()`/`current_schema()` 轻量读回），不一致时醒目告警并附实际库（是否阻断执行挂起：v1 只告警不阻断，避免破坏只读场景）。`ObjectKind.DATABASE` 硬编码的 PG/schema 型方言行为列为 T-SI-05 真机验证项，验证后决定是否分支。

**T-SI-04（测试与文档）**：`ExecConsoleContextTest`/`DataSourceSelector` 相关测试扩用例：复用探测拒绝（dsInFacade=false 弃会话）、UUID 命中/名字唯一命中/同名歧义弃记忆、URL 无库标注文案、读回不一致告警。CHANGELOG [Unreleased] + 变更记录行 + 第七轮报告 Z-6 落地备注回填。

**T-SI-05（真机验证项，不占开发量）**：PG/schema 型方言下 `ObjectKind.DATABASE` 对齐行为实录（正确/需分支两态记录）；数据源删除后复用路径的拒绝表现。

## 四、验收标准

1. 四个猜测点各有对应用例钉住新行为：复用拒绝、歧义弃记忆、无库标注、读回告警。
2. 唯一数据源静默直跑、DBN 正常对齐、正常复用三条主路径行为逐字节不变（防回归断言）。
3. 旧版 last-used 名字记忆兼容读取（升级用户无感），一版后废弃计划在 CHANGELOG 注明。
4. 全量 test 绿，跑前按铁律并行登记。

## 五、真机走查清单（留人工）

1. 预览执行后删除数据源 → 再次预览：不复用旧会话、重新弹选择（而非连接失败才报错）。
2. 建两条同名数据源 → 预览：弹选择而非静默取首条；选定后 UUID 记忆命中。
3. URL 无库数据源 + DBN 映射钉库场景：预览头/错误上下文显示「当前=X」标注。
4. PG 数据源 schema 对齐行为实录（T-SI-05）。

## 六、风险

| 风险 | 说明 | 缓解 |
|---|---|---|
| probe 成本进热路径 | 复用路径每次探测 facade | probe 是内存级查询（DbPsiFacade.find 反查），有界重试仅失效时触发；用例断言复用耗时无回退 |
| UUID 反射封装兼容 | `getUniqueId()` 反射在 243/253/262 行为差异 | 该封装 M36 期已做跨版本实测（遗留代码），接线前重跑三版本探测用例；拿不到 UUID 时回退名字逻辑（行为=现状） |
| 读回校验开销 | 每次对齐多一条 `select current_database()` | 仅在 `setSearchPath` 强设路径（switchSchema 失败回退分支）读回，正常对齐不增查询 |
| 告警疲劳 | URL 无库场景每次预览都标 | 标注只进 describeContext 与预览头一行元信息，不弹窗不加 `⚠` 级别 |

## 七、实施顺序

```
T-SI-01（复用接探测）→ T-SI-02（UUID 记忆）→ T-SI-03（对齐收口）→ T-SI-04（测试与文档）→ 真机走查 §五（含 T-SI-05）
```

## 八、实施记录（实施后回填）

（未实施）
