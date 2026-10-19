# T-XmlDtdHardening XML 解析配置收敛与内联 DTD 收紧 — 开发计划

- 日期：2026-09-19 05:01
- 任务编号：T-XmlDtdHardening（第八轮报告 §9.4 **O-37**，P2~P3；原拟名「XML 入口 StAX 配置对齐 + 内联 DTD 拒绝」——核实后**入口形态与一半结论都要改**，见 §一）
- 来源：第八轮报告 §7.2 zMyBatis 精华 **Z-18**——`SUPPORT_DTD=true` + `IS_SUPPORTING_EXTERNAL_ENTITIES=false` + XMLResolver 返回空 reader + 内联 DTD 子集拒绝（`XmlStatementParameterContractFactory.kt:342-350`、`:173-181`）
- 性质：**安全面收敛 + 一处行为变更**——⚠️ 其中「内联 DTD 子集」当前是**明确放行且有测试钉住**的行为，收紧属语义变更，须明示并同步改测试
- 实施分支：自 master 拉任务分支 t-xml-dtd-hardening
- 验证基线：master（testShards 5153/0/0/6 + testDatabase 10/0/0/0 + agent 绿）

---

## 一、背景与依据（现状核实，含三处口径修正）

| # | 原建议口径 | 核实事实 | 影响 |
|---|-----------|---------|------|
| 1 | 「存量 XXE 防护配置一致性核查」（暗示入口多、可能不一致） | **入口只有 2 个工厂创建点、配置逐字相同**：`xml/XmlMapperParser.kt:205-224`（`newDocumentBuilder`，实际解析 `:185-187`）与 `xml/PagesXmlParser.kt:100-110`（实际解析 `:87-89`）。`XmlMapperParser.parseDocument` 是共享入口，被 8 处调用（`:60`/`:99`/`:114`、`AnnotationSqlParser.kt:43` 注解 `<script>`、`XmlIncludeExpander.kt:136` 跨命名空间片段、`ui/XmlPreviewService.kt:44`、`mcp/MapperCheckTool.kt:71`、`psi/XmlCrossNsFragmentResolver.kt:54,99`）。全仓**无** SAXParserFactory/XMLInputFactory/StAX/JDOM 入口 | 核查工作量远小于预判；真正价值是**消除重复配置防漂移** + 补缺失测试 |
| 2 | 「补内联 DTD 子集判据（官方 DOCTYPE 过、内联子集拒）」 | **口径修正（重要）**：内联 DTD 子集当前是**显式允许并展开**的，且有测试钉住——`XmlMapperParser.kt:220` `isExpandEntityReferences = true`；`xml/XmlSqlRendererTest.kt:311-318`（`内部实体定义可解析`）：`<!DOCTYPE mapper [<!ENTITY status "ACTIVE">]>` → 断言 `&status;` 展开为 `ACTIVE`。**本计划落地后该测试将由绿转红** | 这是**语义变更**，不是「补判据」；须同步改写既有测试 + 登记变更记录 |
| 3 | 「官方 DOCTYPE 当前是否放行」 | **已放行且有测试**：`XmlMapperParser.kt:216` `disallow-doctype-decl = false`（注释 `:207-210` 点名官方 DTD 场景）；`:221-224` EntityResolver 把外部 DTD 读取拦为空（不联网）；测试 `XmlSqlRendererTest.kt:296-308`（`官方DTD声明可解析`） | 写成「**保持不回退**」并补防回退守卫 |

**当前防护配置（两处逐字相同）**：

| 配置项 | 值 | 位置 |
|---|---|---|
| `FEATURE_SECURE_PROCESSING` | true | `XmlMapperParser.kt:206` / `PagesXmlParser.kt:101` |
| `disallow-doctype-decl` | false（允许 DOCTYPE） | `:216` / `:102` |
| `external-general-entities` | false | `:217` / `:103` |
| `external-parameter-entities` | false | `:218` / `:104` |
| `isXIncludeAware` | false | `:219` / `:105` |
| `isExpandEntityReferences` | **true** | `:220` / `:106` |
| `ACCESS_EXTERNAL_DTD` | `"all"` | `:221` / `:107` |
| `EntityResolver` | 恒返回空 `InputSource`（不联网） | `:222-224` / `:108-110` |

T-M25.1 的「三重防护」表述与实现相符（落点注释 `:207-215`）。**但**：`setFeature` 全包 `runCatching`（`:216-218`）——若解析器不支持某 feature 会**静默降级**，防护强度依赖 JDK 内置 Xerces，**无断言兜底**（这是本计划要补的第二个点）。

**测试覆盖现状**：已覆盖官方 PUBLIC DTD（`XmlSqlRendererTest.kt:296-308`）、内联子集 + 内部实体（`:311-318`）、内联子集 + 外部实体 SYSTEM 指向 `file:///etc/hosts`（`:322-331`，断言解析为空）。**缺失**：①无「内联子集应被拒绝」的任何判据（当前语义相反）；②**无实体递归/指数膨胀（billion laughs）用例**——`isExpandEntityReferences=true` 对内部实体无深度/总量上限；③内联子集中的参数实体（`<!ENTITY % p ...>`）无用例；④`PagesXmlParser` **零 XXE/DOCTYPE 测试**（配置虽同但无回归护栏）；⑤注解 `<script>` 路径（`AnnotationSqlParser.kt:43`）无 DOCTYPE 用例。

## 二、目标与非目标

**目标**：①消除两份重复的解析器配置（防漂移）；②对「内联 DTD 子集」做出**明示的安全决策**并落地（**已定案：维持放行 + 加膨胀防护**）；③补关键缺失用例（billion laughs / 参数实体 / `PagesXmlParser` / 注解路径 / 防回退守卫）。

**非目标**：不回退官方 DOCTYPE 放行（既有能力，有测试）；不动 `XmlMapperParser.parseDocument` 的共享形态与 8 处调用点；不动 XXE 之外的解析语义（标签/属性处理不变）。

## 三、决策点（D1 已定案）

**D1：内联 DTD 子集（`<!DOCTYPE mapper [ ... ]>`）如何处置**

| 选项 | 形态 | 利 | 弊 |
|---|---|---|---|
| **A（维持放行）— 已定案** | 保留内部实体展开（现状），但**加膨胀防护**（实体递归深度/展开总量上限）+ 补参数实体与 billion laughs 用例 | 零行为变更、零测试改写；内部实体在真实 mapper 中罕见但确有使用 | 保留了「用户 XML 可自定义实体」这一面；防护靠新加上限而非禁止 |
| ~~B（收紧为拒绝，对齐 zMyBatis）~~（**弃用**） | 内联子集直接拒（保留官方 PUBLIC DTD 放行）；同步改写 `XmlSqlRendererTest.kt:311-318` 并登记语义变更 | 与竞品的 fail-closed 姿态一致；攻击面最小 | **行为变更**：现有依赖内部实体的用户 XML 会从「能预览」变成「拒绝」；须 CHANGELOG 明示 |
| ~~C（分级）~~（**弃用**） | 无内部实体声明的内联子集放行，含 `<!ENTITY` 的拒 | 兼容「只声明 DTD 不定义实体」的写法 | 规则稍复杂，须用例覆盖两态 |

**D1 已定案（2026-09-19，用户认可本会话推荐）：选 A。** 备选 B/C 保留在表内并标记**弃用**（不删，留决策痕迹）。定案理由：放行内部实体是 T-M25.1 三重防护的**有意设计**（官方 PUBLIC DTD 放行、外部实体经 `external-general-entities=false` + EntityResolver 返空双重拦死、内部实体保留可用），且被 `XmlSqlRendererTest.kt:311-318` 钉住；真正的缺口是**展开无上限**（`isExpandEntityReferences=true` 无深度/总量限制，billion laughs 可打穿），A 恰好补在这里，零行为变更、零测试改写；fail-closed 姿态已由「外部实体全禁」那一半给足。

## 四、方案

**T-XDH-01（配置收敛，无争议）**：抽共享的 `DocumentBuilderFactory` 构造工厂（建议落 `xml/SecureXml.kt` 或 `XmlMapperParser` 内的 `internal fun newSecureDocumentBuilder()`），`XmlMapperParser`（`:205-224`）与 `PagesXmlParser`（`:100-110`）改为调用同一实现。**依据**：两份逐字相同的副本是漂移源（口径修正 1）。

**T-XDH-02（防护有效性断言，无争议）**：给 `setFeature` 的 `runCatching` 降级加**可观测性**——feature 设置失败时记一条 warn（含 feature 名），并在测试中断言关键三项（`external-general-entities`/`external-parameter-entities`/`FEATURE_SECURE_PROCESSING`）在当前 JDK 下**确实生效**（防止「以为设了其实静默降级」）。**依据**：现状无断言兜底（§一）。

**T-XDH-03（膨胀防护，无争议）**：对内部实体展开加**深度/总量上限**（实现方式二选一：① 展开前扫描 DTD 子集，对实体声明数量与引用嵌套深度设上限；② 在 EntityResolver/自定义 EntityHandler 处计数并超限抛出）。补 **billion laughs** 用例（构造指数膨胀的实体定义 → 断言被拒绝而非 OOM/超时）。

**T-XDH-04（D1 已定案 A，本项不实施；以下为 B/C 方案的留档规格）**：在共享工厂中按 D1 结论加内联子集判据（B：含内联子集即拒；C：含 `<!ENTITY` 声明即拒），**保留官方 PUBLIC DTD 放行**；同步改写 `XmlSqlRendererTest.kt:311-318`（由「内部实体可解析」改为「内联子集被拒绝」或按 C 分级），并在 CHANGELOG 的 **Changed** 段明示语义变化与影响面。

**T-XDH-05（缺失用例补齐）**：①`PagesXmlParser` 的 XXE/DOCTYPE 回归用例（当前零覆盖）；②内联子集 + **参数实体**（`<!ENTITY % p ...>`）；③注解 `<script>` 路径的 DOCTYPE 用例（`AnnotationSqlParser.kt:43` 走共享入口，应同规）；④**防回退守卫**——断言 `disallow-doctype-decl == false` 与 EntityResolver 非空（防有人误改回 true 导致官方 DTD 解析失败）。

**T-XDH-06（文档）**：CHANGELOG（D1=A 无行为变更，无需 Changed 段；若将来改判 B/C 才需明示）+ 变更记录 + 第八轮报告 Z-18 落地备注（含「我方 T-M25.1 三重防护已存在，本任务落点是配置收敛 + 膨胀防护 + D1 收紧」的口径说明）。

## 五、验收标准

1. 解析器配置只有一处实现，两处调用点共用（无第二份副本）。
2. 关键 feature 生效性有断言；`setFeature` 降级有 warn 可观测。
3. billion laughs 用例：构造的指数膨胀实体**被拒绝**（不 OOM、不超时挂死）。
4. D1=A（已定案）：内联子集**保持放行**，`XmlSqlRendererTest.kt:311-318` **保持绿**（语义不变）；膨胀防护生效（见验收 3）。
5. `PagesXmlParser` 与注解 `<script>` 路径各有 DOCTYPE 用例；防回退守卫在位。
6. 全量 testShards + testDatabase + agent 绿；跑前按并行铁律登记。

## 六、真机走查清单（留人工）

1. 真机打开带官方 MyBatis DOCTYPE 的 mapper → 预览正常（防回退）。
2. （D1=A）真机打开带内联 DTD 子集的 mapper → 行为与改前一致（内部实体仍可解析）；另用 billion laughs 构造的实体验证被拒绝且不 OOM。
3. 真机在含内部实体的 mapper 上预览 → 仍应正常（D1=A）。

## 七、风险

| 风险 | 说明 | 缓解 |
|---|---|---|
| ~~语义变更影响真实用户~~（已随 D1=A 弃用） | 内联实体用户 XML 从可用变拒绝（原 B/C 方案的风险） | D1 已定案 A，该风险不成立；若将来改判 B/C 须重新评估并在 CHANGELOG 明示 |
| 既有测试由绿转红 | `XmlSqlRendererTest.kt:311-318` 钉住的正是待改行为 | T-XDH-04 明确「同步改写并登记」，避免实施时出现「守卫红 vs 计划绿」冲突 |
| 膨胀防护误伤 | 合法多层实体被上限拦 | 上限取值给足余量并在用例中钉住边界（合法场景应通过） |
| feature 断言与 JDK 绑定 | 不同 JDK 的 Xerces 支持面可能不同 | 断言只覆盖三项关键 feature；若某环境不支持则该测试显式 skip 并留档（不静默通过） |
| 与 T-TailSweep2 交叠 | 后者 T-FS-* 在 `xml/DynamicContext`/`converter/`/`config/`/守卫文件 | `xml/` 同包不同文件；守卫文件若触及须串行（见 T-PsiCaptureProtocol T-PCP-04） |

## 八、实施顺序

```
T-XDH-01（配置收敛）→ T-XDH-02（生效性断言）→ T-XDH-03（膨胀防护）→ D1 已定案（A）→ T-XDH-04 不实施（留档）→ T-XDH-05（缺失用例）→ T-XDH-06（文档）→ 真机走查 §六
```

## 九、实施记录（实施后回填）

| 任务 | 提交 | 说明 / 偏离 |
|------|------|-------------|
| T-XDH-01 | d94b58a | 共享工厂落点：**新建 `xml/SecureXml.kt`**（object `SecureXml`，internal）；`XmlMapperParser`/`PagesXmlParser` 的私有 `newDocumentBuilder()` 双双删除，改调 `SecureXml.parse(fileText)`（预扫 + 安全解析器）。收敛判据落成源码扫描用例（src/main 含 feature URI/`ACCESS_EXTERNAL_DTD` 的文件数 == 1 且为 SecureXml.kt），改前 2 处命中 → 改后 1 处。 |
| T-XDH-02 | d94b58a | 三项关键 feature（`FEATURE_SECURE_PROCESSING`/`external-general-entities`/`external-parameter-entities`）在实施环境（JDK 17.0.19 + 内置 Xerces）**全部支持**：`SecureXmlTest.关键feature在当前JDK确实生效` 断言降级告警为空 + 三项取值正确 + 可重复设置成功；降级可观测由 `SecureXml.applyFeature(factory, name, value, onUnsupported)` 承担（失败回调 feature 名 + 原因，默认出口平台日志、惰性解析、失败退化 stderr），`feature设置失败_降级有warn可观测` 用注入收集器断言「恰一条告警且含 feature 名」。无 skip 分支（本环境三项全支持，未触发计划 §七「若某环境不支持则显式 skip」的备用路径）。 |
| T-XDH-03 | d94b58a | 采用**方案①（解析前静态预扫 DTD 内部子集）**——实测否掉了方案②：内部实体展开不经过 EntityResolver（resolver 只管外部实体/外部 DTD），故无法在 resolver 处计数。上限取值：声明数 ≤ **64**、引用嵌套深度 ≤ **16**、单实体展开估算 ≤ **65,536** 字符（`SecureXml.MAX_*` 常量）。取值依据：真实 mapper 内部实体是枚举/状态码级短值；官方 mybatis-3-mapper.dtd 的 `<!ENTITY` 声明数为 **0**（实测下载计数 ELEMENT 33 / ATTLIST 28 / ENTITY 0），「整段内联官方 DTD」这种真实写法不受影响。**实测读数**（JDK 17.0.19，探针 `build/tmpprobe/Probe2.java`，非提交物）：改前 4 层 × 10 引用 × 10 字符的 billion laughs **展开 100,016 字符且解析成功**（JDK `entityExpansionLimit=64000` 只数展开次数，本例 11,110 次，管不到）；40 层链式引用、100 个参数实体声明、1000 个声明同样解析成功；循环引用由 JDK 报「递归实体引用」。改后上述四类全部由预扫拒绝，billion laughs 拒绝耗时 < 5s（用例断言）。边界用例：单实体 65,536 字符放行 / 65,537 拒绝；声明数 64 放行 / 65 拒绝；3 层 × 10 引用（10,016 字符）放行。 |
| D1 | —（决策已定，无代码提交） | **已定案 2026-09-19（用户认可本会话推荐）：选 A**——维持内联子集放行 + 加膨胀防护。理由：放行是有意设计且有测试钉住，真缺口是无上限展开；B/C 保留并标记弃用 |
| T-XDH-04 | 不实施 | D1=A，本项不实施；B/C 留档规格见 §三。`XmlSqlRendererTest.kt:311-318`（内部实体定义可解析）**未改写**，实测仍绿（定向跑 XmlSqlRendererTest 23 例 0 失败） |
| T-XDH-05 | 2d02e7e | 用例数：`SecureXmlTest` 17（收敛 1 + 膨胀判据 4 + D1=A 钉住 3 + T-XDH-02 生效性 3 + 边界/误伤 6）+ `PagesXmlParserTest` +5 + `AnnotationSqlParserTest` +2。防回退守卫形态：①源码扫描（配置唯一实现）；②feature 取值断言（三项 + `disallow-doctype-decl == false`）；③EntityResolver 行为面断言——`DOCTYPE SYSTEM "http://127.0.0.1:1/x.dtd"` 仍能解析（实测摘掉 resolver 即抛 `ConnectException: Connection refused`，故该断言是「resolver 真挂在 builder 上」的判据；`DocumentBuilder` 无 `getEntityResolver()` 读不回，只能走行为面）。**实测发现**：实体引用只在普通文本节点展开，CDATA 是字面量——pages 文件惯用 CDATA 承载 SQL，故 D1=A 对真实 pages 文件无影响（已写进用例注释）。 |
| T-XDH-06 | 见下行说明 | CHANGELOG 未改（D1=A 无行为变更，按 §四 T-XDH-06 口径无需 Changed 段）；`docs/变更记录.md` 与盘点文档按派活方约束不动，变更口径由提交信息承载。 |
| 偏离说明 | — | ①**未改 `mcp/MapperCheckTool.kt` 与 `ui/XmlPreviewService.kt`**（写集允许但非必需）：预扫异常由两处 `parseDocument` 包装为既有异常类型（`XmlParseException`/`PagesXmlParseException`），两条既有 catch 链天然接住（MCP 转 INVALID_ARGUMENT 可读错误、UI 转 warning 红字），无需改调用方；②~~`SecureXml.checkEntityExpansion` 的深度判据是「路径深度」而非严格图深度（记忆化命中会跳过路径检查），字符数上限不受影响——已加注释说明，对真实攻击（指数膨胀）判据充分~~ ——**该句两处结论均为事实性错误，已由独立复核实测证伪，订正见下方「偏离说明②（订正）」行**。 |
| T-XDH-07（独立复核后加固） | c80a845 + 6deaa70（PI 口径）+ 2e707ee（快路径） | **背景**：独立复核实测 6 条「声明/引用对预扫不可见」的绕过——同一载荷（4 层 × 10 引用 × 4000 字符）不加诱饵时预扫正确拒绝（chars=65537），加 6 字节诱饵后预扫 PASS 且 JDK 实际展开 **40,000,016 字符**。**逐条处置**：①子集切分 `indexOf("]>")` → 引号/注释/处理指令/条件段状态机，结束符 = `]` + 可选空白 + `>`（诱饵形态：实体值 `<!ENTITY d "]>">`、注释 `<!-- ]> -->`、处理指令 `<?pi ]> ?>`，三者实测 JDK 均接受且不构成子集结束）；②`] >`（`]` 与 `>` 之间有空白，实测 JDK 接受）此前完全不认结束符 → 整段不扫；③`indexOf("<!DOCTYPE")` 被序言注释/处理指令里的假 DOCTYPE 抢占 → 改为只在注释/处理指令/CDATA 之外认；④参数实体走私（PE 字面量里的声明对预扫不可见，`%hide;` 被按 1 字符计）→ 新增 `visibleSubset` 把 `%name;` 迭代替换为字面量（轮数上限 32、展开预算 262,144 字符，超限即拒），使声明数/深度/字符数三项判据同时恢复可见；⑤非 ASCII 实体名（`<!ENTITY é "…">`）不被 `[A-Za-z_]` 起头的名字正则认下 → 声明与引用改走宽松 `isNameChar`（只排除空白与结构性字符，`#` 在内故字符引用仍不计）；⑥另修递归护栏 `visiting.size >= 16` → `>`：头先声明的 16 层链（合法，实测 JDK 接受）此前被误拒，深度判据不得依赖声明顺序。⑦子集内处理指令里的 `<!ENTITY …>` 文本此前被当声明（实测 JDK 口径相反：PI 里的声明不生效、`%p;` 也不展开）→ 抽出 `skipNonMarkup`（注释 + 处理指令）供声明扫描与 PE 替换共用，与 `internalSubset` 的 PI 跳过同口径，消除「PI 里 70,000 字符文本把合法文件误判为展开超限」的误报（先红后绿：临时去掉 PI 跳过 → 该用例红，消息「实体展开估算 70000 字符超过上限 65536（实体 `evil`）」）。另加 `indexOfDoctype` 快路径（全文无 `<!DOCTYPE` 时不做状态扫描）：片段解析与无 DOCTYPE 的 mapper 占多数，状态扫描成本只落在序言。**读数**：改前 6 条载荷预扫全 PASS、实际展开 40,000,016 / 10,000,016 / 1,000,016 字符（探针 `F:/tmp/xdh7/ProbeB.java`，复刻改前预扫逻辑 + 生产解析配置，非提交物）；改后 `SecureXmlTest` 28 例全绿（改前 26 例 8 失败，红全部落在新增用例上），诱饵与走私载荷均在 1~12ms 内由预扫拒绝（无展开发生）；`xml.*` 定向 223 例 0 失败。**用例 +10**：诱饵三形态（实体值/注释/处理指令）+ 序言假 DOCTYPE + `] >` 结束符 + PE 走私三判据（声明数 302 / 深度 20 / 展开量）+ 非 ASCII 实体名 + 深度边界顺序无关 + 菱形复用护栏 + 子集内处理指令/注释文本不算声明。**放行不回退**：官方 DTD 真文本（test classpath 上 mybatis-3.5.19 的 `mybatis-3-mapper.dtd`，实测 7,819 字节 / ELEMENT 33 / ATTLIST 28 / ENTITY 0 / 无 `%` 引用 / 无 `]>`，去掉首行 XML 声明后整段内联）、合法内部实体、参数实体、上限内多层、菱形复用、`&lt;` 类未声明内置引用全部仍放行（新增用例 `官方DTD整段内联不被误伤` 钉住真 DTD 文本，补上了此前只用合成子集钉住的缺口）。 |
| 偏离说明②（订正） | c80a845 | **订正 2026-09-19**（原句见上「偏离说明」行②，两处结论均不成立）：<br>（a）「字符数上限不受影响」不成立——三项判据只对**可见声明**有效，而可见性此前无任何保证：诱饵可让声明 0 命中（`declarations.isEmpty()` 早退）、PE 走私可让整批声明不可见，实测同一载荷加 6 字节诱饵即从「正确拒绝」变成「放行并展开 40,000,016 字符」（可见性收口见 T-XDH-07 行）。<br>（b）「对真实攻击判据充分」不成立——除上述可见性缺口外，深度判据当时还**依赖声明顺序**（头先声明的合法 16 层链被误拒，已修），字符数判据只是**单实体**展开估算、不含正文引用次数：实测 `<!ENTITY big "y"×65,536>` 在正文引用 100 次 → 展开 6,553,616 字符放行（183ms），兜底是 JDK `FEATURE_SECURE_PROCESSING` 的 `totalEntitySizeLimit`=50,000,000（实测引用 800 次即被 JDK 以 JAXP00010004 拒绝）。<br>**订正后口径**：三项判据管「单个可见字面量实体展开量 ≤ 65,536 字符、引用路径深度 ≤ 16、可见字面量声明数 ≤ 64」，**不管文档总展开量**（由 JDK 50M 兜底）。 |
| 剩余已知边界（如实登记） | — | ①外部子集/外部参数实体里的声明不参与预扫——`external-*-entities=false` + EntityResolver 恒返空，JDK 侧同样拿不到声明（实测 `<!ENTITY % p SYSTEM "…">%p;` 展开为空），非缺口；②内部子集里的 PE 引用落在标记声明内（实测 JDK 报「参数实体引用不能出现在内部子集的标记内」）、条件段 `<![INCLUDE[`（实测 JDK 报「标记声明必须格式正确」）、`<!ENTITY %p`（`%` 后无空白，实测 JDK 要求空格）——JDK 直接拒，预扫口径是「宁可见不漏认」的超集，非缺口；③未声明引用按 1 字符计（内置 `&lt;` 等）与字符引用 `&#38;` 不计——两者都不产生展开，非缺口；④预扫是**静态估算**而非展开次数/总量上限：正文引用次数与文档总展开量不在判据内（见偏离说明②订正），最坏可放行约 50MB 展开（JDK 兜底）；⑤未做随机/变异语料的模糊测试，可见性结论只有上述定向正反例背书。 |

**⚠️ 本任务是该批唯一没有全量门禁读数的任务（2026-09-19 验收文档撰写者核实）**：§九 只有定向读数（`SecureXmlTest` 28 例 / `xml.*` 223 例），未跑 `testShards`。其绿灯结论由并轨后的全量门禁（该批 5362/0/0/6）间接背书，**本任务自身无全量读数留档**。
