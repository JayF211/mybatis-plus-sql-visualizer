# 字符串版 QueryWrapper 服务链 DELETE 边界修复 开发计划

- **任务ID**：T-StrWrapperDeleteWhere
- **立项时间**：2026-09-06 02:00
- **分支**：`dev`
- **前置**：M37M38 已知边界记录（`docs/架构设计/M37M38新增功能-说明文档.md` §五）+ 金样矩阵 M38.2 缺陷尾注（`SqlPreviewSpecMatrixTest.kt` 文件尾）；第二轮调研报告（20260906-0120）§5.2 O-8 立项

## 1. 问题

M37M38 记录的已知边界：字符串版 `QueryWrapper` 经 `service.remove(w)` 等服务链消费时「输出 DELETE 缺 WHERE 条件（消费分支按 Lambda 解析）」。立项前用探针对当前 dev 分支做了 14 个形态的实测（LightPlatform 端到端，`SqlPreviewService.preview` 输出），**实测结论修正了记录的预设**——字符串条件本身在消费分支并不丢失，真实缺陷是三处，且文档表述已过时：

| # | 形态 | 实测输出 | 判定 |
|---|------|---------|------|
| P1 | `QueryWrapper<Device> w = new QueryWrapper<Device>().eq("id", 3L); service.remove(w);` | `DELETE FROM device WHERE id = 3` | 正确——「DELETE 缺 WHERE」表述与现状不符 |
| P3/P6 | 同上，条件为 `apply("status = {0}", 1)` / `inSql(...)` | `DELETE ... WHERE status = 1` / `WHERE id IN (SELECT ...)` | 正确，apply/inSql 字符串条件在消费分支正常收集 |
| P2 | `var w = Wrappers.<Device>query().eq("id", 3L); service.remove(w);` | `SELECT id, ... FROM device WHERE id = 3` | **缺陷 A：删除意图渲染成查询**（消费语义整体丢失） |
| P10 | 同 P2，工厂换 `Wrappers.<Device>lambdaQuery()` | 同 P2 | 缺陷 A 与字符串列无关，`var` 形态通病 |
| P13/P14 | `service.query().eq("id", 3L).one()` / `.count()` | **预览为 null（什么都没有）** | **缺陷 B：字符串服务链完全无预览** |
| P4 | `QueryWrapper<Device> w = new QueryWrapper<Device>().eq("status", 1).last("LIMIT 5"); service.remove(w);` | `DELETE FROM device WHERE status = 1`（LIMIT 5 丢失） | **缺陷 C：DELETE 消费丢 `last()` 尾段** |
| P7 | 无条件 wrapper + remove | `DELETE FROM device` + 警示「无 WHERE 条件，将删除全表数据！」 | 安全门现状可用（见 2.4） |

缺陷定位（文件:行号证据）：

- **缺陷 A**：`var` 声明在 LightPlatform 下类型不可识别 → `psi/WrapperLocator.kt:314-319`（isWrapperVariable 仅认具体 Wrapper 类名或 Wrapper/AbstractWrapper 基类，T-M15-01 回归守卫）拒绝 → 走不到含 ReferencesSearch 消费检测的变量路径 `psi/WrapperLocator.kt:846-851`，落入工厂路径 `buildChainFromFactory`（`psi/WrapperLocator.kt:461-555`）——其消费识别只看工厂调用自身是否为消费方法直参（`psi/WrapperLocator.kt:533-535` consumingCall），变量后续引用上的 `service.remove(w)` 不可见 → deleteConsumed=false → 按查询渲染。显式类型变量同形正确（P9 实测），内联工厂直参正确（P8 实测）。
- **缺陷 B**：`service.query()` 链起点识别正常（`serviceChainMethodNames` 含 query，`psi/WrapperLocator.kt:1244`），败在实体解析：链上无方法引用可推断（`inferEntityFromChain` 仅认首参 `PsiMethodReferenceExpression`，`psi/WrapperLocator.kt:582-592`），LightPlatform 下 `QueryChainWrapper<T>` 类型形参也解析不出 → table=null → `ui/SqlPreviewService.kt:204-208` 静默 `return null`——零反馈。
- **缺陷 C**：deleteConsumed 分支只携带条件：`ui/SqlPreviewService.kt:246-249` `DeleteStatement(table, QueryWrapperParser(table).parse(located.chain).conditions)`，`last()` 解析出的 limit 落在 SelectStatement 独有字段，DELETE 渲染无承载（`sql/SqlGenerator.kt:262-284` generateDelete 无 limit 段）。

## 2. 方案

原则：**能解析的渲染进 SQL，解析不了的显式降级提示**——静默丢条件、静默无预览、删除意图渲染成查询，同属正确性事故级输出。三处缺陷各一个修复面，外加既有安全门用例锁定与文档勘误。

### 2.1 缺陷 A——`var` + 工厂形态消费识别

- `isWrapperVariable`（`psi/WrapperLocator.kt:314-319`）扩展：类型名不可识别（`var`/推断形态）时，若 initializer 链头经 `detectWrapperTypeFromInitializer` 判为 wrapper 构造/工厂 **且链上无终结消费方法**（initializer 末次调用不在 `chainTerminalMethodNames`）→ 接受为 wrapper 变量。
- 回归守卫（T-M15-01 原样保留语义）：`Device d = service.lambdaQuery().eq(...).one()` 的结果变量因链含终结点 `one` 仍被拒绝，不会把普通赋值误判成 wrapper（`buildChain` 内 `detectWrapperTypeFromInitializer` 兜底已有，`psi/WrapperLocator.kt:816-818`，无需改）。
- 修复后 `var` 形态走变量路径，ReferencesSearch 消费检测天然生效（deleteConsumed/countConsumed/updateEntityConsumed/pageLimit 全量恢复）。
- 边界说明：真实 IDE 中 `var` 会被推断为具体类型、变量路径本就可达，本修复的直接收益是 LightPlatform 矩阵回归（S75 场景回补）与推断失败场景的一致性；真机行为以 runIde 抽查为准，不阻塞合入。

### 2.2 缺陷 B——`service.query()` 字符串服务链实体解析与显式降级

- 实体解析补第三通道：工厂调用 qualifier（`service` 引用）resolve 到变量 → 其类型（`DeviceService`）→ 父接口泛型实参（`IService<Device>`）→ 实体 PsiClass，挂进 `buildChainFromFactory` 的实体解析链（`entityClassFromFactoryArgs`/`resolveEntityClass` 失败后、`inferEntityFromChain` 前后顺位以实施期确认为准）。真实 PSI 解析，LightPlatform 可测（fixture 内声明 `interface IService<T>` 与 `interface DeviceService extends IService<Device>` 桩即可，无需 MP classpath）。
- 兜底降级：三通道全失败时，`ui/SqlPreviewService.kt:204-208` 的 table=null 分支不再静默 `return null`，改为返回带显式警示的空结果（复用 `emptyResult` 形态，文案说明"字符串服务链无法推断实体表"）——面板有红字提示而非无响应。注意 gutter 图标判定走 `WrapperAnchorDetector` 独立链路，不受 preview 返回值变化影响（实施期回归确认）。

### 2.3 缺陷 C——DELETE 消费携带 `last()` 尾段

`deleteConsumed` 分支（`ui/SqlPreviewService.kt:246-249`）把 `parseLimit` 产物透传进 DELETE 渲染：`DeleteStatement` 模型补尾段承载（raw 后缀 `rawLast` 或复用 `LimitClause`，MP 语义 MySQL `DELETE ... LIMIT n`，跨方言由 SqlGenerator 按方言决定渲染与否——承载形态以实施期确认为准），`generateDelete`（`sql/SqlGenerator.kt:262-284`）末尾渲染。

### 2.4 安全门用例（复用既有警示机制，证据）

无 WHERE DELETE 警示机制已存在且实测可用：`ui/SqlPreviewService.kt:1512-1519`（buildWarning，UPDATE/DELETE 条件为空 → 「⚠ [BlockAttack] 无 WHERE 条件，将更新/删除全表数据！」），探针 P7 端到端实证。本计划不新建机制，新增矩阵用例**断言 warning 文本**（现有 `spec()` 只断言 SQL，需补一个带 warning 断言的 helper），把安全门锁进回归。

### 2.5 取舍

- **只修消费语义/预览可得性，不做字符串列名校验**：`eq("列名", v)` 的列名拼写正确性属运行时范畴，静态预览不校验（与现有字符串版行为一致）。
- **UPDATE 实体消费形态不顺带扩**：P11 实测 `service.update(entity, w)` 字符串版 WHERE 正确、SET 为实体全列占位（T-M21-05 文档化近似），不构成本边界。
- **补全侧 `map.`/字符串链候选精度不扩**：属 N-8/X9 已知边界，互不牵扯。
- **`var` 真机差异不阻塞**：light 矩阵回归为准，runIde 抽查列入交付清单。

### 2.6 具体改动

| 文件 | 改动 |
|------|------|
| `psi/WrapperLocator.kt` | ① `isWrapperVariable` 接受「initializer 链头为 wrapper 且链无终结消费方法」的不可识别类型变量（var，§2.1）；② `buildChainFromFactory` 无需另加变量引用检测——var 归位变量路径后 ReferencesSearch 消费检测自动生效（以实施期确认为准，若 var 仍落工厂路径则在此补 ReferencesSearch 通道）；③ 实体解析补 qualifier 服务类型泛型实参通道（§2.2） |
| `ui/SqlPreviewService.kt` | ① table=null 分支改显式降级警示（§2.2）；② deleteConsumed 分支透传 last 尾段（§2.3） |
| `model/SqlStatement.kt`（DeleteStatement）+ `sql/SqlGenerator.kt`（generateDelete） | DELETE 尾段承载与渲染（§2.3，形态以实施期确认为准） |
| `src/test/kotlin/cn/song/mybatisplus/visualizer/sql/spec/SqlPreviewSpecMatrixTest.kt` | 第九批场景 S83~S90（见 §2.7）+ warning 断言 helper；删除文件尾 M38.2 已知缺陷尾注（缺陷随本计划收敛） |
| `docs/架构设计/M37M38新增功能-说明文档.md` §五 | 「DELETE 缺 WHERE」表述勘误为实测边界（var 消费语义/service.query 无预览/DELETE 丢 last），标注修复版本 |

### 2.7 测试设计（矩阵第九批，每条一行预期语义）

| # | 场景 | 预期语义 |
|---|------|---------|
| S83 | `QueryWrapper<Device> w = new QueryWrapper<Device>().eq("id", 3L); service.remove(w);` | `DELETE FROM device WHERE id = 3`（当前已正确——M38.2 移除场景回补，防回潮） |
| S84 | 同形，条件 `apply("status = {0}", 1)` | `DELETE ... WHERE status = 1`（字符串条件消费收集锁定） |
| S85 | 同形，条件 `inSql("id", "SELECT id FROM old")` | `DELETE ... WHERE id IN (SELECT id FROM old)` |
| S86 | 同形 + `.last("LIMIT 5")` | `DELETE ... WHERE ... LIMIT 5`（缺陷 C 修复后） |
| S87 | 无条件 wrapper + `service.remove(w)` | SQL `DELETE FROM device` **且 warning 含「无 WHERE 条件」**（安全门，warning 断言 helper） |
| S88 | `var w = Wrappers.<Device>query().eq("id", 3L); service.remove(w);` | `DELETE FROM device WHERE id = 3`（缺陷 A 修复后；若 light 下仍不可达则本场景改显式类型形态，var 真机验证入 runIde 清单） |
| S89 | `service.query().eq("id", 3L).one()`（fixture 声明 `IService<T>`/`DeviceService` 桩） | `SELECT id, station_name, status, created_at FROM device WHERE id = 3`（缺陷 B 修复后） |
| S90 | `service.query().eq("id", 3L).one()`（无任何实体解析线索形态） | 预览返回显式降级警示文案，不再为 null（缺陷 B 降级路径） |

## 3. 实施步骤

1. 本计划提交立项（docs）。
2. 注册并行沙盒（`python C:\Users\USER\.zcode\cli\parallel_tasks.py check` → `register --name str-wrapper-delete-where --sandbox strwrapper-sandbox`）+ build.gradle.kts 临时沙盒隔离。
3. 先落矩阵第九批中「当前已正确」的锁定场景（S83~S85、S87 warning helper）确认全绿基线。
4. 实施 §2.1 var 识别扩展 → S88。
5. 实施 §2.2 实体解析第三通道 + 降级警示 → S89/S90。
6. 实施 §2.3 DELETE 尾段 → S86。
7. 编译 + 定向测试（矩阵类 + WrapperLocator/SqlPreviewService 相关既有测试）→ 全量测试。
8. runIde/真机抽查 var 与 `service.query()` 各一例（清单记录，不作为合入门禁）。
9. 文档三处同步：说明文档 §五 勘误、docs/变更记录.md 追加（日期倒序）、本计划 §变更记录回填。
10. 还原沙盒隔离、注销登记（`parallel_tasks.py done`）。

## 4. 验收标准

- [x] 字符串版 QueryWrapper 消费形态不再静默丢失语义：var+工厂渲染 DELETE（S88 绿），声明变量/内联形态回归锁定（S83~S85 全绿）
- [x] `service.query()` 字符串服务链可预览（S89 绿），无法解析实体时返回显式降级警示而非 null（S90 绿；降级收窄为仅服务链形态，工厂/Kotlin/构造形态维持 null 语义）
- [x] DELETE 消费不再丢 `last()` 尾段（S86 绿；方言渲染：MySQL 族输出 LIMIT，其他方言静默省略）
- [x] 无 WHERE DELETE 必须有警示：S87 断言 warning 文本，复用既有 buildWarning 机制
- [x] 新增用例全绿 + 全量回归绿（矩阵 91/0/0，定向家族 192/0/0，全量 3519/0/0/6）；M38.2 矩阵尾注删除、说明文档 §五 勘误完成（文档三处同步）
- [x] 全程未触碰 `plugin.xml`（无扩展点变更，跳过全量 verify 的约定不变）

## 5. 风险

- `isWrapperVariable` 放宽是敏感面（T-M15-01/T-M19.5 两次回归都在这条门上）：终结点黑名单守卫必须与 `chainTerminalMethodNames` 单一来源（`psi/WrapperLocator.kt:1256` 起），实施时先补「结果变量不被误判」的反向用例再放宽。
- preview() table=null 改降级返回会改变「返回 null = 非 wrapper 锚点」的隐含契约，调用方（Action/hover/缓存）需逐一核对——实施期以全量回归兜底，若有调用方依赖 null 语义则降级收窄为「仅字符串服务链形态」。
- DELETE 尾段的方言差异（MySQL `LIMIT` 合法、PG 不支持 DELETE LIMIT）：渲染默认仅 MySQL 系输出，其他方言静默省略并在说明文档标注——与既有分页方言策略一致。
- `var` 在真实 IDE 与 LightPlatform 行为可能不同（类型推断可达性），矩阵断言以 light 实测为准，真机差异记录进交付说明，避免为对齐真机在 light 里做过拟合。

## 变更记录

- 2026-09-06 T-StrWrapperDeleteWhere 立项：字符串版 QueryWrapper 服务链 DELETE 边界修复（探针实测修正预设——真实缺陷为 var+工厂消费语义丢失、service.query 无预览、DELETE 丢 last 三处，含无 WHERE 警示安全门用例锁定与 §五 文档勘误）。
- 2026-09-06 T-StrWrapperDeleteWhere 交付：三处缺陷修复合入 feature/r2-o8-strwrapper-delete（006377b 基线锁定场景先行：S83~S85/S87 + specWarning helper + var 结果变量 one/count 反向守卫，修复前实证绿；973054f 三处修复 + S86/S88~S90 + M38.2 尾注删除）。实施要点：① var 判定走 typeElement 文本（getType() 在 LightPlatform 对不可解析 var 返回 null 型，不可依赖），新增 wrapperTypeFromInitializerChain 修工厂链首识别——原 detectWrapperTypeFromInitializer 沿 qualifier 回溯终点是引用（`Wrappers.<Device>query()` 链头判定落空），工厂调用自身即链首；② 缺陷B 实体第三通道挂 buildChainFromFactory 解析链末位，降级收窄为仅服务链形态（LocatedWrapper.entityResolutionFailed，接收者非静态工厂类才置位）；③ DELETE 尾段承载取 LimitClause 复用（非 rawLast），方言渲染由 Dialect.supportsDeleteLimit（MySQL/MariaDB/DM true）决定。测试：矩阵 91/0/0、定向家族 192/0/0、全量 3519/0/0/6 绿。步骤 8（runIde 真机抽查 var 与 service.query 各一例）未执行——runIde 真机验证待补。
