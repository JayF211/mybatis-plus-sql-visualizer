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
|------|------|------|
| T-XDH-01 | （实施后填） | 共享工厂落点须回填 |
| T-XDH-02 | （实施后填） | 三项 feature 在实施环境是否全部支持须回填 |
| T-XDH-03 | （实施后填） | 上限取值与 billion laughs 用例实测须回填 |
| D1 | —（决策已定，无代码提交） | **已定案 2026-09-19（用户认可本会话推荐）：选 A**——维持内联子集放行 + 加膨胀防护。理由：放行是有意设计且有测试钉住，真缺口是无上限展开；B/C 保留并标记弃用 |
| T-XDH-04 | 不实施 | D1=A，本项不实施；B/C 留档规格见 §三 |
| T-XDH-05 | （实施后填） | 用例数与防回退守卫形态须回填 |
| T-XDH-06 | （实施后填） | |
