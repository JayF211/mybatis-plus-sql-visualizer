# T-DialectXcFamily 信创方言补全族（OceanBase + openGauss） — 开发计划

- 日期：2026-09-23 06:54
- 任务编号：**O-63**（第三轮全景审计 §8.1）
- 来源：UX 锚点核实——方言注册表 9 方言（`sql/Dialect.kt:689-699`），OceanBase / openGauss / GaussDB 全文 grep 零命中；信创双备（DM8 真机 + KingBase）已有交付与验证先例
- 性质：功能补全（既有方言矩阵扩容，非新环；竞品侧零家支持信创方言，r11 §5 矩阵——补齐后「信创四备」叙事成立）
- 实施分支：自 master 拉任务分支（实施前先让 O-62 §三.1 把本地 master 快进到 origin/master）
- 验证基线：origin/master `133b4a2`

---

## 一、背景与缺口

现役 9 方言（`Dialect.kt`）：MySQL `:361` / PostgreSQL `:387` / Oracle `:420` / SQL Server `:453` / H2 `:518` / SQLite `:556` / MariaDB `:582` / DM 达梦 `:613` / KingBase 人大金仓 `:657`。信创装机量最大的两个缺口：**OceanBase**（MySQL 模式为主流）与 **openGauss/GaussDB**（PG 系）。两者都不在任何注册表、URL 推断表与 EXPLAIN 支持面表里。

每个方言的接入面（参照 KingBase=PG 系先例）：

1. `Dialect` object 注册（`:689-699` 清单）+ 引号规则 + 折叠方向族（O-51 形态 `IdentifierFolding`，`Dialect.kt:101/:267`）+ `renderLimit` + 关键字换行格式化词表
2. EXPLAIN 支持面表 `sql/ExplainSupport.kt`（`:96` entries、`:111` render，O-48 后为显式登记驱动）
3. agent URL 推断：`DialectInference` 的 URL 前缀匹配面（`jdbc:oceanbase://`、`jdbc:opengauss://` 等，前缀清单见 §二.4）
4. CRUD / 回滚 / 格式化链共用面自动继承

**明确出圈（不做）**：OceanBase Oracle 模式（D1）；identity 包装（DM8 教训——先推定不需要，真机暴露再按 O-52 先例增量，`f543ae1` 先例在册）；GBase / TiDB 等（登记观察，§8.3 以族为单位控制重演半径）。

## 二、待验证前提（实施前先证伪）

1. **OceanBase MySQL 模式与 MySQL 方言兼容面**：backtick 引号、LIMIT 语法、EXPLAIN 形态推定高兼容（MySQL 5.7/8.0 语法族）——需真库或官方文档证实。dbx MCP 在册连接无 oceanbase 类型；若环境不可得，按「文档证实 + 真机留账」口径交付，禁冒充「验证通过」。
2. **openGauss 内核基线**：openGauss 系 PG 9.2 演化但语法有分叉；EXPLAIN 支持面推定与 PG 同形（TEXT/JSON）。**GaussDB（华为云版）与 openGauss 社区版的 JDBC URL 前缀差异**（`jdbc:opengauss://` vs `jdbc:gaussdb://`）需查证后写入 D2 的定案。
3. **折叠方向**：openGauss 推定 LOWER（PG 系，KingBase 同款先例）；OceanBase MySQL 模式推定 NONE。两者均属推定——真机打脸按 H2 先例处理（`df7d177` 订正 + 反向验证），不预设为已验。
4. **URL 推断注册点**：`DialectInference` 的 URL 匹配面位置与既有前缀表形态（agent 侧 URL 全链透传 → IDE 侧推断）——实施前先锚点定位，确认新前缀要在几处登记。
5. **ExplainSupport 显式表登记格式**：按引擎文档登记 OB / openGauss 的 EXPLAIN 输出格式支持位，不臆测格式名。
6. **Dialect 契约测试冻结面**：O-51/O-52 的契约断言族（折叠方向、identity 包装）对新增 object 的约束范围——实施前核对契约测试的扫描面，确认新方言要过哪几条。

## 三、实施步骤（草案）

1. 单测先行：双方言的格式化 / 折叠 / limit / 引号规则语料（对照 MySQL、PG 既有语料模板）；含反向验证夹具（摘掉注册 → 推断/格式化回落失败路径必红）。
2. `Dialect` 双 object 注册 + 折叠方向 + renderLimit + 换行词表。
3. `ExplainSupport` 表登记 + `ExplainGate` 词法核对（EXPLAIN 关键字两侧预期无差异，核对即可）。
4. `DialectInference` URL 前缀登记（前缀清单按 §二.2/§二.4 查证结果）。
5. 设置页 / i18n 文案（方言显示名，双 bundle 同步）。
6. 定向测试（`--tests` Dialect 族 + ExplainSupport 族；纯逻辑可走 `testFast`）。不跑全量、不跑构建校验、不 clean——三档分层口径。

## 四、验证

- 定向测试全绿 + 反向验证读数（§三.1 夹具）。
- dbx MCP 真库探询（用户定例：需真库验证先问 dbx MCP）；在册无 OB / openGauss 连接则如实登记「环境受限未验证」，交付说明按「引擎级已验 / 文档级已验 / 环境受限」三态写。
- 不跑 verifyGate（核对四判据：不动 build.gradle.kts 与依赖、不动 plugin.xml 的 EP 与 since-build、不引入新平台 API 面或反射入口、不加 MCP 工具——预期全不触发；若实施中打破任一条，升级为大改动并复跑）。

## 五、决策点

| # | 决策 | 选项 | 倾向 |
|---|------|------|------|
| D1 | OceanBase Oracle 模式 | A 不做（MySQL 模式覆盖主流装机）/ B 同任务做（体量翻倍 + 需 Oracle 形态真机） | A |
| D2 | openGauss 与 GaussDB | A 一个 object 两 URL 前缀 / B 只做 openGauss | A（以前缀查证为前提） |
| D3 | TiDB 顺手加不加 | A 登记观察 / B 顺手注册（MySQL 兼容推定） | A——推定不白拿，TiDB 分布语法有差异，真机验证跟上再说 |

## 六、风险

- 推定被打脸（折叠方向 / EXPLAIN 格式 / 兼容面）：H2 先例处理——真机订正 + 反向验证 + 契约断言同步。
- 宣传口径先于验证：对外「信创四备」叙事在引擎级验证前只说「已适配」，不说「已验证」。
- `Dialect.kt` 与 O-51/O-52 契约测试的冻结面：新增 object 必须过契约族，防「断言把缺口冻成期望」（㉘ 教训）。
- 与在途任务撞 `Dialect.kt` 写集：开工前按登记表 check（O-51 已交付，预期无冲突；如有在途，串行）。

## 七、实施记录

（待实施后回填）
