# T-RealEngineCardinality 真引擎绑定基数交叉对账 — 开发计划

- 日期：2026-09-20 11:46
- 任务编号：T-RealEngineCardinality（第九轮报告 §9.4 **O-40**，P1）
- 来源：第九轮报告 §7.2 **Z-25**——zMyBatis 把引擎真实生成的 `?` 数量与 PSI 侧契约的 Placeholder 证据计数做**全等对账**，两条独立推导链互证、漂移即红（`XmlMapperStaticBoundInputPreparationTest.kt:91`）
- 性质：**测试基建加断言**——不改产品代码、不改 O-34 既有协议；把「基数」这一维度从无断言变为有断言
- 实施分支：自 master 拉任务分支 t-real-engine-cardinality
- 验证基线：master `22d7c49`（testShards 5383/0/0/6 + testDatabase 10/0/0/0 + agent 58/0/0/0）

---

## 一、背景与依据（现状核实，含两处口径修正）

| # | 报告口径 | 核实事实 | 影响 |
|---|---------|---------|------|
| 1 | 「搭 O-34 已建成的对照基建，补占位符计数对账」 | **成立**：O-34 产物 = `xml/fidelity/RealEngineDriver.kt`（234 行，T-FS-01 基建）+ `xml/fidelity/RealEngineFidelityTest.kt`（464 行，25 条一致面 + 6 条已知差异）+ 差异清单文档 `docs/调研报告/20260919-T-FidelityEval-自研与真引擎已知差异清单.md`（273 行）；两侧的「条数」都拿得到 | 任务可行，改动面小（加用例） |
| 2 | 「参数列表长度」可拿 | **部分不成立（口径修正）**：**参数值列表在我方模型层不存在**——`xml/XmlPreviewModel.kt` 只暴露条件/foreach/bind 视图与参数名序列；参数值只在面板私有 map（`ui/SqlPreviewPanel.kt:347 private val paramValues`，读口 `:4005 paramValuesSnapshot()`）。模型层 `model/PreviewResult.kt:52 initialParamValues` 是初始预填值，不是渲染时用的值 | 三角对账的可用量改为「**真引擎 parameterProperties.size / 我方 mappings.size / 我方 SQL 内串感知 `?` 个数**」，不涉及参数值 |
| 3 | 「用 `?` 个数」 | **须用串感知计数**：裸 `sql.count { it == '?' }` 会把字面量内的 `?` 误计——本仓已有 `log/LogEntryPreviewRecognizer.kt:100` `internal fun countPlaceholders(sql) = placeholderPositions(sql).size`（KDoc `:96-99` 明确串感知） | 复用 `countPlaceholders`，不自造计数 |

### 1.1 O-34 现有断言面（逐字段实测）

| 面 | 断言字段 | 证据 |
|---|---|---|
| 一致面 25 条 | ① `compact(realBound.sql) == compact(mine.sql)`（空白归一）；② `realBound.parameterProperties == mine.mappings.map { it.property }`；③ `mine.errors.isEmpty()` | `RealEngineFidelityTest.kt:58-86`（`:71` / `:74-77` / `:78-80`；`compact` 在 `:414`） |
| 已知差异 6 条 | 真引擎异常类全名（`RealEngineException.causeClassName`）+ 自研 SQL 文本 + 自研参数队列 + `errors` 条数与关键词 | `:88-172`（`:95-99`/`:121-125`/`:139-143` 等） |
| 版本钉住 | jar 文件名 + 隔离 loader 载入类的 `package.implementationVersion`，期望 `3.5.19` | `:206-228`；`EXPECTED_ENGINE_VERSION = "3.5.19"` 在 `:442` |
| **基数** | **无独立断言**（一致面 ② 是「属性名序列相等」，隐含条数相等，但没有「`?` 位点数 == 条数」这条自洽关系的断言） | 同左 |

**结论**：O-40 属**加断言**，不改 O-34 协议与差异清单 6 条的口径。

### 1.2 两侧可用量

| 量 | 取值点 | 证据 |
|---|---|---|
| 真引擎参数条数 | `realBound.parameterProperties.size` | `RealEngineDriver.kt:96-98`（`getParameterMappings()` → 逐条 `getProperty()`）；`RealBoundSql` `:18-21` |
| 我方参数条数 | `mine.mappings.size` | `xml/XmlSqlRenderer.kt:38-49`（`RenderedXmlSql.mappings: List<ParameterMapping>` 在 `:40`） |
| 我方 SQL 内 `?` 位点数（串感知） | `countPlaceholders(mine.sql)` | `log/LogEntryPreviewRecognizer.kt:100` |
| 先例（直接报条数） | `append("    #{} parameters: ").append(rendered.mappings.size)` | `mcp/SqlVisualizerMapperCheckTool.kt:103` |

**驱动未暴露的面**：`RealEngineDriver` 返回的是**属性名列表**而非 `ParameterMapping` 对象本身，也未取参数值——若日后要对「值」对账需新开驱动口（本任务**不做**，登记）。

**用例命名风格（照抄）**：规则陈述式——`` `一致面 25 条：SQL 文本与参数队列逐字节相等` ``（`:59`）、`` `已知差异 6 条：按登记形态断言（形态变化即红，强制同步差异清单）` ``（`:89`）、`` `P20 归因：求值器属性访问的载体面不含 POJO` ``（`:182`）。

## 二、目标与非目标

**目标**：对 25 条一致面逐条断言**三方基数相等**（真引擎参数条数 == 我方 mappings 条数 == 我方 SQL 内串感知 `?` 位点数），并把「SQL 内 `?` 位点数 == 参数条数」这条**自洽关系**独立成一钉——它不依赖真引擎，可在无 jar 环境（如 CI 无 ml-llm 类环境）继续守护。

**非目标**：不改 O-34 协议（`compact` 归一、属性名序列相等两条不动）；不改差异清单 6 条的口径；不新增真引擎参数值通道（驱动口不动）；不做「值级」对账；不改产品代码（本任务零 `src/main` 改动）。

## 三、方案

**T-REC-01（自洽关系成钉，不依赖真引擎）**：新增用例断言「我方渲染产物满足 `countPlaceholders(rendered.sql) == rendered.mappings.size`」。覆盖既有 XML/注解/Wrapper 三条渲染链的代表性场景（含 `<if>`/`<foreach>`/`<bind>`/`${}` 混用场景）。**价值**：这条关系在无真引擎的环境（CI、他机）也能守护，是真引擎对照的**降级替身**。

**T-REC-02（三方基数对账，挂 25 条一致面）**：在 `xml/fidelity/RealEngineFidelityTest.kt` 内新增一个用例（照 `:206-228` 版本钉住形态），或新建同包 `RealEngineCardinalityTest.kt`（二选一，见「待验证前提 3」）：
- 对 25 条一致面逐条断言 `realBound.parameterProperties.size == mine.mappings.size == countPlaceholders(mine.sql)`；
- 失败报文须含场景 id（照既有场景 id 陈述式命名，如 `"P1 if Integer 0 / Map param"` `:237`），便于定位是哪条场景漂移。

**T-REC-03（6 条已知差异的基数口径）**：6 条已知差异按登记形态断言（真引擎抛异常或语义不等），**不参与基数对账**；但须在用例 KDoc 中写明「为何排除」，避免后人误以为漏做。

**T-REC-04（文档）**：`docs/调研报告/20260919-T-FidelityEval-自研与真引擎已知差异清单.md` 补一节「基数对账」说明（断言了哪三个量、为何排除参数值、6 条差异为何不参与）；CHANGELOG 按惯例登记（测试基建类，`[Unreleased]` 视口径决定是否入——若按既有惯例测试类改动不入 CHANGELOG 则跳过并说明）。

## 四、验收标准

1. 自洽关系用例绿：代表性场景下 `countPlaceholders(sql) == mappings.size`（T-REC-01）。
2. 25 条一致面全部通过三方基数对账（T-REC-02）；失败时报文含场景 id。
3. 6 条已知差异不参与基数对账且有用例 KDoc 说明（T-REC-03）。
4. **先红后绿实证**：至少对一条场景构造「基数不等」的形态（如临时改一条断言口径或注入错位参数），确认该用例会红——避免出现「断言写错方向永远绿」的假绿（本仓守卫类已三次绿灯下失效，见 `docs/MEMORY.md`）。
5. 全量 testShards + testDatabase + agent 绿；若新建测试类，`docs/架构设计/03-开发与测试指南.md` §3.2 的类数/用例数分布表须同步（**仅文档级，无自动断言**）。

## 五、真机走查清单（留人工）

本任务为 headless 测试基建，**无真机走查项**（真引擎 jar 在测试依赖图内，`build.gradle.kts:93-96`）。唯一环境面：**若本机缺 mybatis jar，`RealEngineFidelityTest.kt:44-48` 会硬失败**（不是跳过）——故实施前须先跑一次 `testFast` 确认 jar 定位（`RealEngineDriver.kt:210-232` 的三路定位：宿主 classpath 资源 → `java.class.path` → gradle 缓存）。

## 六、风险

| 风险 | 说明 | 缓解 |
|---|---|---|
| **假绿**（本任务最大风险） | 「基数相等」若因两侧同源（都从同一 `mappings` 派生）而恒等，断言就没有判别力 | 三方里的真引擎侧**独立于**我方推导（隔离 loader + 真引擎 `getBoundSql`），这是判别力来源；再叠加验收 4 的先红后绿实证 |
| `getParameterMappings()` 条数与 SQL 内 `?` 个数**不恒等** | 已知可能不等的场景：`${}` 直替出 `?`、`#{}` 出现在被引擎特殊处理的片段、动态 SQL 分支展开差异 | **列为待验证前提 1**——实施第一步用真引擎实测确认；若确有不等的**合法**场景，则该场景改用「真引擎 == 我方 mappings」两方对账，并在 KDoc 写明豁免理由 |
| 新用例使既有场景表膨胀 | 25 条一致面若逐条内联断言会让测试类变长 | 复用既有场景表结构（`:235-365`），循环内断言，不复制场景 |
| 测试类数变化影响分片 | 新增测试类会改分片归属，可能触发顺序依赖假失败 | 按既有三步证伪（隔离复跑 → 分片单独复跑 → 静默机重跑）；**合并/分支切换后首跑先 `rm -rf build/classes build/kotlin`**（陈旧产物劫持已知形态） |

## 七、实施顺序

```
T-REC-01（自洽关系成钉，零真引擎依赖）→ T-REC-02（三方对账）→ T-REC-03（6 条差异的口径说明）
  → T-REC-04（文档）→ 先红后绿实证（验收 4）
```

## 八、实施记录（2026-09-20 回填）

| 任务 | 提交 | 说明 / 偏离 |
|------|------|------|
| T-REC-01 | `00a732b` | **落点=新建独立类** `src/test/kotlin/cn/song/mybatisplus/visualizer/xml/fidelity/RealEngineCardinalityTest.kt`（266 行，零真引擎依赖）。三条渲染链均从**生产入口**驱动、非旁路构造：XML mapper 链 5 条读数（X1 `if` 真值 / X1' 同 mapper 分支关闭 / X2 `foreach` 3 次迭代 / X3 `bind`+`${}` 直替+`#{}` 混用 / X4 字面量与注释内 `?` 不计）、注解 SQL 链 2 条（A1 纯文本含字面量内 `?` / A2 `<script>` 动态标签）、MP wrapper 占位链 2 条（W1 `${ew.customSqlSegment}` 片段 + 尾随 `#{}` / W2 片段内带引号 `?`）。`countPlaceholders` **直接复用产品侧 `log/LogEntryPreviewRecognizer.kt` 的 `internal` 实现**（测试与主代码同模块可达）——**未放宽任何产品代码可见性**，也未自造计数。失败报文含场景 id 与两侧读数，一次报出全部偏差（不首条即断）；每条读数另断言 `errors.isEmpty()`（自洽关系的前提） |
| T-REC-02 | `00a732b` | **落点=加用例于既有类** `RealEngineFidelityTest.kt`（+133 行，复用既有场景表，不复制场景）。逐条断言 `realBound.parameterProperties.size == mine.mappings.size == countPlaceholders(mine.sql)`，报文含场景 id（照既有陈述式命名）且一次报出全部偏差。**实测 28 条一致面全部落在恒等域内**（非推断）；用例内另加**场景集不相交断言**（一致面与已知差异场景集取交集，误搬即红）+ 两条计数硬断言（一致面 28 / 已知差异 6）。**偏离**：计划 §一/§三/§四 通篇写「25 条一致面」，实测为 **28 条**——T-FCA（`81ddc08`）已追加 P31~P33 三条 foreach 集合别名场景，本计划立项时的条数口径已过时，按实测 28 条落地 |
| T-REC-03 | `00a732b` | 6 条已知差异（P0 / P14 / P15 / P16 / P20 / P30）**不参与基数对账**，理由写入两处 KDoc（新类类头 + T-REC-02 用例 KDoc）——它们是保真差异场景（真引擎硬抛或两侧语义分叉），其中 **P20 两侧基数本就不同**（真引擎 1 条 / 自研 0 条），纳入「三方相等」等于要求它不成立。排除的兜底不是靠注释而是靠 T-REC-02 用例内的场景集不相交断言 |
| T-REC-04 | `2840b86` | 差异清单 `docs/调研报告/20260919-T-FidelityEval-自研与真引擎已知差异清单.md` 补 **§9「基数对账」**（+88 行）：对账的三个量、前提 1 实测不恒等与两种合法形态的逐字节读数。**CHANGELOG 处置**：本任务属测试基建（src/main 零改动），但按本仓既有惯例 `[Unreleased]` 的 `### Test` 段确实收录测试基建类改动，故由文档收口路统一写入一条（`6cdce63`），口径含「前提 1 被证伪」与「src/main 零改动」 |
| 先红后绿 | `00a732b` | 两次读数（均已还原，`src/main` 零改动）：①**P11 临时改直替注入形态 → 基数对账红，且同改动下原「一致面 28 条」用例仍绿**——证明本用例抓的是原断言面（SQL 文本相等 + 属性名序列相等）看不见的维度；②**XML 链临时加字面量内 `#{}` → 自洽钉红**（`countPlaceholders` 不认字面量内 `?`，`mappings` 照样多一条） |

**待验证前提（实施后逐条结清）**：

1. **「mybatis 3.5.19 下 `getParameterMappings()` 条数 == SQL 内 `?` 个数」是否恒等** → **证伪（本任务第一步即实测，非推断）**。两种**合法**形态下真引擎自身就不等：`${}` 直替出的值自身含 `?`（`props=0` / `?`=1，`?` 由文本注入而非 `#{}` 产生）、`#{}` 落在字符串字面量或 `--` 行注释内（`props=2` / `?`=1，引擎的 `#{}` 识别**非串感知**，产出的 `?` 落进字面量/注释里）。两形态下**我方与真引擎完全一致**（文本与参数条数都相同）——是「串感知计数」与「引擎非串感知 `#{}` 识别」两个口径的固有分歧，**不是保真差异、不是待修缺陷**。故对账口径定案为**场景域断言**（28 条一致面实测全在恒等域内），两种非恒等形态由**边界钉**按登记形态正向钉住（我方侧在 `RealEngineCardinalityTest`、真引擎侧在 `RealEngineFidelityTest`，两处都是「口径变化即红」的登记式断言），并明确禁止后人把本条关系误当普适恒等式去「修」串感知计数或 `#{}` 识别
2. 「`countPlaceholders` 可从 xml 包（测试侧）引用」→ **成立**：`log/LogEntryPreviewRecognizer.kt` 的 `internal fun`，测试与主代码同模块可达，直接复用、未放宽产品代码可见性
3. 「加用例 vs 新建类」→ **两者都用**：自洽钉（T-REC-01）新建独立类（需独立分片归属与「零真引擎依赖」的类级语义），三方对账（T-REC-02）加在既有类（复用既有场景表与真引擎驱动，避免重复场景与重复 loader 成本）

## 九、实施小结

**已交付**：T-REC-01/02/03（`00a732b`，新增 1 个测试类 266 行 + 既有类 +133 行）+ T-REC-04（`2840b86`，差异清单 §9 +88 行）。**src/main 零改动**（与计划非目标一致：不改 O-34 协议、不改差异清单 6 条口径、不新增真引擎参数值通道、不做值级对账）。

**偏离**：

1. **计划口径「25 条一致面」→ 实测 28 条**（T-FCA 已追加 P31~P33）。计划 §一 现状核实表与 §四 验收 2 的条数按实测落地，未按原数字写断言（否则新用例会被迫在 25 与 28 之间二选一）。
2. **对账口径由「普适恒等式」降级为「场景域断言」**——前提 1 证伪后的必然结果，已在两处 KDoc 与差异清单 §9 三处写明适用域（不含两种非恒等形态的渲染产物）。
3. **自洽钉与三方对账分落两类**（新建类 vs 既有类），非计划 §三 的「二选一」措辞；理由是两类对真引擎 jar 的依赖语义不同（自洽钉刻意零依赖，供 CI/他机守护）。
4. 计划 §四 验收 5 要求「若新建测试类，`docs/架构设计/03-开发与测试指南.md` §3.2 的类数/用例数分布表须同步」——**本轮未做**（该文件不在文档收口路写集），见下方未验证项。

**阻塞**：无。

**未验证项（如实登记，勿当已验）**：

1. **`docs/架构设计/03-开发与测试指南.md` §3.2 分布表未同步**：新建 `xml/fidelity/RealEngineCardinalityTest` 后该表 `xml/fidelity`（1）应为（2）、基线「472 类 / 5383 用例」亦已过时（T-FCA 25→28 条、T-PSG/T-PWT/T-ICI 各自的用例增量未计）；该文件不在本路写集，登记为**待同步项**（计划 §四 验收 5 因此**未满足**）。
2. **未跑任何门禁**：本任务为纯测试基建，收口阶段未统一复跑 `testShards` / `testDatabase` / `agent`，故「全量绿」无本任务自己的读数（先红后绿两处是本任务范围内唯一实测读数）。
3. **真引擎侧读数只出自 mybatis 3.5.19 单一版本**（`build.gradle.kts:93-96` 的测试依赖图）；版本漂移后边界钉的两侧读数需重取（版本钉住断言 `EXPECTED_ENGINE_VERSION` 会让漂移先红，不会静默）。
4. **边界钉只覆盖三种实测形态**（`${}` 值含 `?`、`#{}` 在字符串字面量内、`#{}` 在行注释内）；计划 §六 风险表另列的「`#{}` 出现在被引擎特殊处理的片段、动态 SQL 分支展开差异」两类**未构造**，故本钉不宣称穷尽非恒等形态。
