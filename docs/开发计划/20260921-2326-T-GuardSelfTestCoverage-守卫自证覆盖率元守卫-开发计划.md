# T-GuardSelfTestCoverage 守卫自证覆盖率元守卫 — 开发计划

- 日期：2026-09-21 23:26
- 任务编号：T-GuardSelfTestCoverage（第十轮报告 §9.4 **O-50**，**P2**）
- 来源：第十轮报告 §7.2 **Z-30**——zMyBatis 的校验器**自带红绿语料、先自证再执法**：`.github/workflows/failure-triage.yml:142-205` 内嵌 13 例 `expect_ok`/`expect_fail` 成对语料（4 个必须放行的描述性正例 + 7 个必须拒绝的反例），任一自测漂移即 `SystemExit`，**在真正的正文校验之前执行**
- 性质：**测试基建**——不改产品代码；把「守卫改动必做反向验证」从人工仪式变成结构性约束
- 实施分支：自 master 拉任务分支 t-guard-selftest-coverage
- 验证基线：master `28a1efa`（最近可核读数 491 类/5604 用例 0 失败 与 5577 例 0 失败，属不同批次树；实施时以当次实测为准）

---

## 一、背景与依据（现状核实，含一处重要口径修正）

| # | 报告口径 | 核实事实 | 影响 |
|---|---------|---------|------|
| 1 | 「我方守卫缺自证语料，应照 zMyBatis 补」 | **实质已具备（口径修正，重要）**：我方**已有**五个自证用例 + 判定函数与测试分离 + 空扫描面自证。差的是**约束力**，不是能力 | 任务从「补自证」改为「**加覆盖率约束**」 |

### 1.1 我方已有的自证资产（逐项实测）

| 形态 | 落点 | 内容 |
|---|---|---|
| 自证用例 | `convention/BannedApiSourceScanTest.kt:703` | `尾随lambda写文档守卫自证_合成样本必红`（三态：违规必报 / 过闸放行 / 带理由标注放行） |
| 自证用例 | 同文件 `:784` | `TFS06自证_显式模态实参写法必报且配平不越界` |
| 自证用例 | 同文件 `:1197` | `TFP03自证_无判定必红_有判定与豁免放行` |
| 自证用例 | 同文件 `:1456` | `TPCP04自证_池化块读PSI无校验必红_三种放行形态` |
| 自证用例 | 同文件 `:1968` | `TST02d自证_初始化入口写共享服务不复位必红_边界态放行` |
| 自证用例 | `convention/DialogWrapperInitOrderGuardTest.kt:363` | `test_扫描器灵敏度与豁免自证()` |
| 自证用例 | `convention/EnumReachabilityGuardTest.kt:267` | `test_扫描器灵敏度自证_KDoc词与注释交叉引用不误报()` |
| 自证用例 | `generator/DmlAliasGuardTest.kt:121` | 「守卫有效性自证：注入别名形态必红」 |
| 自证用例 | `sql/verify/DockerComposeLabConsistencyTest.kt:93/105/123` | 漂移自证三例 |
| **空扫描面自证** | `BannedApiSourceScanTest.kt:2317` | 「路径解析失败 = 空绿，主用例的非空断言必须能拦住」 |
| 判定函数与测试分离 | `BannedApiSourceScanTest.kt:613/631/640/976/1128` | `invokeLaterWriteOffenders` / `anyModalityWriteOffenders` / `defaultModalityWriteOffenders` / `throwableCatchSites` / `fatalPassthroughOffenders` |
| 生产代码测试缝 | `mcp/McpSchemaContextService.kt:61/69/157/202` | `internal data class ModelTable` / `modelTablesForTest` / `collectEntries` / `indexModelTables` |

**设计意图原文**（`BannedApiSourceScanTest.kt:610`）：

> 单独成函数（而非内联进 @Test）是为了让规则本身可自证：合成源码样本能直接喂进来，否则「规则看不见某形态」这种空绿只能靠人工改线上代码才验得出来。

**纪律原文**（`docs/架构设计/03-开发与测试指南.md:140`）：

> **守卫写法教训**（2026-09-19，从严收录）：守卫必须用真实站点做「删掉修复行→必红」反向验证——合成样本只证「规则看得见我造的形态」，「仓内 0 处违规」在写命中为 0 时是空绿（三次实证：…）

守卫清单权威口径：`docs/架构设计/03-开发与测试指南.md:96`（「文件名含 Guard 16 个 + 同性质源码扫描 3 个」，2026-09-20 对账）。

### 1.2 与 zMyBatis 的形态差别（口径修正的具体内容）

| 面 | zMyBatis | 我方现状 | 差距 |
|---|---|---|---|
| 自证语料 | 有（13 例成对） | **有**（10 处自证用例） | 无 |
| 判定与测试分离 | 有 | **有** | 无 |
| **执行顺序** | 自测**在正文校验之前**，漂移即整体失败 | 自证是**并列 @Test** | 有（顺序） |
| **覆盖率约束** | 自测覆盖由作者保证（单文件内） | **无任何机制**：新加守卫规则不写自证，没有任何测试会红 | **有（关键）** |

**结论**：我方缺的不是「自证」，而是**「新规则必须配自证」这条约束**——这与仓内已三次实证的失败形态（守卫绿灯下失效）直接对应。

## 二、目标与非目标

**目标**：让「守卫规则没有自证语料」这件事**能被自动化发现**——新增元守卫，断言每个守卫判定函数至少被一个自证用例引用。

**非目标**：不重写既有守卫；不要求所有守卫都改成「前置短路」（那只是 zMyBatis 的实现细节，我方并列 @Test 已覆盖实质）；不改产品代码；不追求覆盖率数字（不设百分比阈值，只要求「有 vs 无」）。

## 三、方案

**T-GSC-01（自证覆盖率元守卫）**：新增守卫测试，扫描守卫类源码，提取**判定函数名**（约定：`private fun *Offenders` / `*Sites` / `*Functions` 等返回违规点集合的函数），断言每个名字**至少在一个自证用例的函数体内被引用**（自证用例识别：函数名含 `自证` / `selftest` / `自检`，或 KDoc 标注）。不一致即红，报文点名缺自证的函数。

**T-GSC-02（可选：自证前置化）**：把五个自证用例的断言抽成 `private fun selfTest()`，在对应主用例开头调用一次（漂移即抛、不进入正文扫描）。**默认不做**——收益是「失败更早」，但并列 @Test 已能发现漂移；除非 T-GSC-01 实施中发现顺序确实重要。

**T-GSC-03（纪律成文）**：在 `docs/架构设计/03-开发与测试指南.md` 的守卫章节补一条**可执行约定**：新增守卫判定函数必须同时提供自证语料，否则 T-GSC-01 会红（把 `:140` 的教训从「写法教训」升级为「有守卫的约定」）。

**T-GSC-04（测试与文档）**：T-GSC-01 自身**必须先做反向验证**（本仓铁律：守卫必须用真实站点做「删掉修复行→必红」）——临时删掉一个既有自证用例 → 元守卫必须点名该函数；两次读数留档。文档：CHANGELOG（测试基建类，按惯例视口径决定是否入）+ 变更记录行 + 第十轮报告 §7.2 Z-30 落地备注。

## 四、验收标准

1. 元守卫在**当前 master 上先跑一次**，产出「判定函数 → 是否有自证」的实测清单（**这是本任务的第一步，也是判据的基线**）；清单中若已存在缺自证的函数，如实登记（不当作本任务的失败，而是它的第一个产出）。
2. **反向验证**：临时删掉一个既有自证用例 → 元守卫必红并点名；还原后绿（两次读数留档）。
3. 元守卫自身**不做宽泛匹配**：识别规则须在 KDoc 写明漏报/过报边界（照 `psiCapture` 守卫的既有做法，`T-PsiCaptureProtocol` 验收文档 §一-7 有「误报边界两个方向都登记」的先例）。
4. `docs/架构设计/03-开发与测试指南.md` 补入可执行约定。
5. 全量 testShards + testDatabase + agent 绿；新增测试类须同步该指南 §3.2 的类数/用例数分布表（文档级）。

## 五、真机走查清单

**无真机项**（纯测试基建，headless 全覆盖）。

## 六、风险

| 风险 | 说明 | 缓解 |
|---|---|---|
| **元守卫自身的假绿**（最大风险） | 扫描自己的仓库源码，若识别规则写窄（只认某一种命名）会「全部通过」而毫无判别力——这正是仓内已三次实证的形态 | 验收 2 的**反向验证是硬要求**；验收 3 要求登记漏报/过报边界；识别规则须能覆盖 `BannedApiSourceScanTest` 全部 5 个判定函数（否则立刻暴露） |
| 命名约定未成文 | 判定函数命名若无约定，元守卫识别不到新函数（漏报） | T-GSC-03 把命名约定与「必须配自证」一并写进指南；元守卫对**未识别的守卫类**也报一条（防「新守卫类整体脱离扫描面」） |
| 过度约束 | 强制每个判定函数配自证会拖慢正常开发 | 只要求「有 vs 无」，不设覆盖率阈值；允许在元守卫里登记**豁免表**（函数名 + 理由），与既有守卫豁免表同规 |
| 改守卫类本身有风险 | 若为通过元守卫而改动既有守卫 | 本任务**不改既有守卫逻辑**（只加元守卫 + 文档）；既有自证用例保持原样 |

## 七、实施顺序

```
T-GSC-01 的基线扫描（先产出「判定函数 → 有无自证」实测清单）→ 元守卫实现
  → 反向验证（验收 2，硬要求）→ T-GSC-03（纪律成文）→ T-GSC-04（测试与文档）→ [T-GSC-02 可选]
```

## 八、实施记录（实施后回填）

| 任务 | 提交 | 说明 / 偏离 |
|------|------|------|
| 基线扫描 | 未提交（一次性扫描脚本落在仓库外临时目录，不入库） | **实测清单见本节末「基线扫描清单」**。前提 1/2/3 全部被实测证伪（详见文末「前提证伪」），故按计划 §八 前提 1 自己预告的退路实施：**显式登记表为主识别面 + 命名兜底面为辅**。实测 21 个判定函数：13 有自证、8 无（其中 3 个是**真缺口**、5 个是站点提取器/断言助手）。缺自证项按验收 ① 如实登记，未在本任务修复（非目标）。另：验收 ③ 写的「`BannedApiSourceScanTest` 全部 5 个判定函数」实为 13 个，照原判据会在漏看一半的情况下判通过。 |
| T-GSC-01 | （本任务改动） | 新增 `src/test/kotlin/cn/song/mybatisplus/visualizer/convention/GuardSelfTestCoverageMetaGuardTest.kt`（6 用例，零平台依赖→进 testFast 快桶）。识别规则 = 登记表 `GUARD_CLASSES`（23 条）+ 三条闭合：① 文件名含 `Guard` 或以 `SourceScanTest` 结尾的文件必须登记；② 任何测试文件出现类级（4 空格缩进）`*Offenders`/`*Sites`/`*Violations`/`*Hits`/`*Functions` 函数而文件未登记即红；③ 已登记类里出现未逐条登记的判定形状函数即红。豁免表 = `DecisionFn.exempt`（理由非空，且该函数一旦获得自证语料即红要求删除，防豁免腐烂）。漏报/过报边界两个方向写在类头 KDoc。**实施中实跑抓到自身一处缺陷并修掉**：Java 正则 `\w` 只认 ASCII，函数名匹配写成 `[A-Za-z_]\w*` 会让 `fun test_扫描器灵敏度与豁免自证()`（无反引号中文名，仓内两处）整条声明漏识别，把 `DialogWrapperInitOrderGuardTest.scan`/`EnumReachabilityGuardTest.scan` 误报成缺自证；改 `[\p{L}_][\p{L}\p{N}_]*` 并把该形态加进元守卫灵敏度自证用例。 |
| 反向验证 | （本任务改动） | 读数见本节末「反向验证读数」。**偏离**：计划举例的删除 `BannedApiSourceScanTest.kt:703` **不会变红**——实测其唯一被它覆盖的判定函数 `defaultModalityWriteOffenders` 同时被 `:784` 引用（`:807/:809/:823/:825/:840/:842/…` 全在 `:784` 体内），删 `:703` 后覆盖率不降；改用 `:784`（`anyModalityWriteOffenders` 的唯一自证来源）。 |
| T-GSC-02 | 未实施 | 依据：计划 §三 明写「默认不做」；实施中未发现顺序必要性——自证与主用例并列 `@Test`，漂移即红，无需前置短路。 |
| T-GSC-03 | （本任务改动） | `docs/架构设计/03-开发与测试指南.md` §3.1 守卫族末条新增「**新增守卫判定函数的可执行约定**」三步（登记进 `GUARD_CLASSES` / 配自证语料（函数名或 `@Test` 上方紧邻注释）/ 无自证时登记 `exempt` 理由）；§3.1 守卫族全清单按元守卫登记表机器对账改写为 23 条，补入此前漏登记的 `i18n/DegradedBannerWordingGuardTest`、`convention/BundlePlaceholderArityTest`、`sql/verify/DockerComposeLabConsistencyTest`、`convention/GuardSelfTestCoverageMetaGuardTest`；§3.3「守卫写法教训」升级为指向该元守卫的「有守卫的约定」。约定原文即该条 bullet 正文。 |
| T-GSC-04 | （本任务改动） | 门禁读数见本节末；§3.2 分布表按磁盘实测重核类数（482→495，逐包增量写在表头注），运行口径改用 `build/test-results/testShard{1,2,3}` 最近一次全量产物读数（2026-09-21 21:05–21:08）。CHANGELOG / 变更记录 / 第十轮报告 Z-30 备注按任务说明交给 T-Round10-08 统一写（不在本任务写集内）。 |

### 基线扫描清单（2026-09-22，master 树实测；命令见本节末）

**判定内核类（有可注入合成样本的判定函数）——21 个判定函数**

| 守卫类 | 判定函数 | 行 | 类别 | 自证语料 |
|---|---|---|---|---|
| `convention/BannedApiSourceScanTest.kt` | `anyModalityWriteOffenders` | :631 | 违规点 | ✅ `TFS06自证`(:784) |
| 同上 | `defaultModalityWriteOffenders` | :640 | 违规点 | ✅ `尾随lambda写文档守卫自证`(:703)、`TFS06自证`(:784) |
| 同上 | `fatalPassthroughOffenders` | :1128 | 违规点 | ✅ `TFP03自证`(:1197) |
| 同上 | `pooledThreadSites` | :1332 | 站点提取器 | ✅ `TPCP04自证`(:1456，:1574) |
| 同上 | `psiCaptureOffenders` | :1406 | 违规点 | ✅ `TPCP04自证`(:1456) |
| 同上 | `initEntrySites` | :1685 | 站点提取器 | ✅ `TST02d自证`(:1968，:2335/:2339) |
| 同上 | `projectServiceStateOffenders` | :1916 | 违规点 | ✅ `TST02d自证`(:1968) |
| 同上 | `invokeLaterWriteOffenders` | :613 | 违规点（共享内核） | ➖ 豁免：被 :631/:640 在 :632/:641 调用，自证经转发 |
| 同上 | `invokeLaterSites` | :537 | 站点提取器 | ➖ 豁免：被 :613 在 :619 调用 |
| 同上 | `throwableCatchSites` | :976 | 站点提取器 | ➖ 豁免：被 :1128 在 :1132 调用 |
| 同上 | `serviceWriteHits` | :1802 | 站点提取器 | ➖ 豁免：被 :1916 在 :1931 调用 |
| 同上 | `assertNoHits` | :77 | 断言助手 | ➖ 豁免：非内核，命名偶然命中 `*Hits` |
| 同上 | `edtReadContractOffenders` | :1606 | 违规点 | ❌ **真缺口**：仅主用例 :1614 在 :1618 引用，无合成样本自证 |
| `convention/DialogWrapperInitOrderGuardTest.kt` | `scan` | :178 | 违规点 | ✅ `test_扫描器灵敏度与豁免自证`(:363) |
| `convention/EnumReachabilityGuardTest.kt` | `scan` | :206 | 违规点 | ✅ `test_扫描器灵敏度自证…`(:267) |
| `generator/DmlAliasGuardTest.kt` | `dmlTargetsHaveNoAlias` | :42 | 违规点 | ✅ `guardDetectsInjectedAliasForms`(:125，靠 :121 行注释标注) |
| `sql/verify/DockerComposeLabConsistencyTest.kt` | `checkImagePinning` | :147 | 违规点 | ✅ `漂移自证_latest镜像被抓`(:93) |
| 同上 | `checkPorts` | :159 | 违规点 | ✅ `漂移自证_端口冲突被抓`(:105)、`漂移自证_低段端口被抓`(:123) |
| 同上 | `checkRequiredEnv` | :182 | 违规点 | ❌ **真缺口**：三个漂移自证只覆盖镜像与端口两检查 |
| 同上 | `checkHealthcheck` | :203 | 违规点 | ❌ **真缺口**：同上 |
| `convention/BundlePlaceholderArityTest.kt` | `warningSites` | :99 | 站点提取器 | ✅ `扫描自检_站点数非零`(:129) |

**无判定内核的守卫类（16 个，文件名闭包内）**：`acp/AcpTransportScopeSourceScanTest`、`config/PluginXmlStructureGuardTest`、`config/SettingsRealityGuardTest`、`execute/ExecutionTimeoutGuardTest`、`execute/SessionIdentityGuardTest`、`i18n/BundleParityGuardTest`、`i18n/DegradedBannerWordingGuardTest`、`log/ConsoleReplayGuardTest`、`log/LogRouterHotPathRegexGuardTest`、`log/ReplayGuardLine0ReadTest`、`log/SqlLogAutoSaverPruneGuardTest`、`log/SqlLogListFreezeGuardTest`、`log/agent/AgentUrlDecoupleGuardTest`、`mcp/McpScopeSourceScanTest`、`mcp/McpSelectGuardTest`、`update/UpdateZipGuardTest`——断言直接内联在 `@Test`（或经 `assertHolds`/`assertDenied` 等助手），无可注入合成样本的判定内核；元守卫按「无判定内核」登记并写明依据。

**总计**：21 个判定函数 = 13 有自证 + 8 无（3 真缺口 + 5 豁免）。

### 反向验证读数（三段，2026-09-22 实跑）

前置：并行会话活跃（`parallel_tasks.py check` 报 8 条），共享 `build/` 被互踩，故全程用两个**仓库外** init script 做只读隔离——报告落点重定向（`gsc-reports.gradle`，只改 `Test` 任务的 junitXml/html/binary 输出位置）+ 目标类过滤 `--tests`；两者都不改被测行为。

| 段 | 命令 | 读数 |
|---|---|---|
| ① 基线（未改任何文件） | `gradlew.bat testFast --tests "cn.song.mybatisplus.visualizer.convention.GuardSelfTestCoverageMetaGuardTest" --init-script <报告重定向> -Pkotlin.compiler.execution.strategy=in-process` | `BUILD SUCCESSFUL in 13m 23s`；XML `<testsuite ... tests="6" skipped="0" failures="0" errors="0" time="1.912">`——6 个用例全绿（含覆盖率检查、登记表/豁免腐烂、两条闭合、灵敏度自证） |
| ② 临时删自证用例 | 同上命令；前置 `cp` 备份 + `sed -i '783,958d' src/test/kotlin/cn/song/mybatisplus/visualizer/convention/BannedApiSourceScanTest.kt`（删掉 `@Test`(:783) + `` `TFS06自证_显式模态实参写法必报且配平不越界` ``(:784–:957)） | `BUILD FAILED in 7m 10s`；XML `tests="6" failures="1"`，报文**逐字点名**：`守卫判定函数缺自证语料…: - convention/BannedApiSourceScanTest.kt :: anyModalityWriteOffenders（VIOLATIONS）` |
| ③ 还原 | `cp` 回备份 | 还原后 `md5sum` 与备份**逐字节一致**（`09904cbce15b95ab7fad2cf6cf732e96`）、`git diff --stat` 对该文件**空**；随后全量 `gradlew.bat testFast` 一跑，`TEST-…GuardSelfTestCoverageMetaGuardTest.xml` 读数 `tests="6" failures="0" errors="0"`（绿） |

**为什么删 `:784` 而不是计划举例的 `:703`**：实测 `:703` 的自证体内只引用 `defaultModalityWriteOffenders`（`:719/:738/:740/:756/:758`），而该函数**同时**被 `:784` 的体内引用（`:807/:809/:823/:825/:840/:842/:861/:863/:880/:882/:899/:901/:929/:935/:937`）——删 `:703` 后覆盖率不降、元守卫仍绿，读不出判别力。`:784` 是 `anyModalityWriteOffenders` 的**唯一**自证来源，删除后精确点名它，才是有效反向验证。

### 门禁读数（2026-09-22）

- `gradlew.bat testFast`（全量快桶，含本任务新增类与 4 个既有守卫类）：`3023 tests completed, 4 failed, 6 skipped`。4 个失败**全部落在并行会话在途改动**上、无一在本任务写集内：`DialectExplainTest`（`:43`、`:210` 两例）与 `ExplainIndexAdvisorTest`（`:113`、`:142` 两例）——`git status` 显示这 4 个文件对应的 `src/main/.../sql/Dialect.kt`、`src/main/.../util/ExplainIndexAdvisor.kt`、`src/test/.../sql/DialectExplainTest.kt`、`src/test/.../util/ExplainIndexAdvisorTest.kt` 均为**他人未提交的在途改动**。本任务写集内 5 个测试类读数：`GuardSelfTestCoverageMetaGuardTest` 6/0/0、`DialogWrapperInitOrderGuardTest` 4/0/0、`EnumReachabilityGuardTest` 3/0/0、`DmlAliasGuardTest` 4/0/0、`DockerComposeLabConsistencyTest` 9/0/0（tests/failures/errors）。
- `gradlew.bat testShards`（全量）：**未跑**。依据：项目红线「有活跃并行任务时禁止直接跑默认沙盒全量测试」（当时 `parallel_tasks.py check` 有 8 条活跃登记），且本仓交付约定「全量由主流程统一跑」。运行口径的最近一次全量读数（2026-09-21 21:05–21:08 产物）已按验收 ④ 回填指南 §3.2。

**待验证前提（实施者先证伪再动手）**：

1. **「判定函数」是否可被稳定识别**——本计划只确认了 `BannedApiSourceScanTest` 的五个函数名形态（`:613/631/640/976/1128`），**未穷举**其余 19 个守卫类的函数命名。第一步的基线扫描就是为证伪这条：若命名过于发散导致识别规则无法覆盖，则改为「按 KDoc 标注登记」而非按命名识别。
2. 「自证用例」的识别判据（函数名含 `自证`/`selftest`/`自检`）——既有 10 处自证用例的命名已核（含 `test_…自证`、`…自证_…` 两种形态），但**是否全部覆盖**须在基线扫描中确认。
3. 元守卫是否应跨类（扫描全部守卫类而非仅 `convention/` 包）——取决于基线扫描结果；若跨类导致识别规则复杂化，可先只覆盖 `convention/` 三个大守卫类（`:2343` 行 / `:469` 行 / `:352` 行），其余登记为后续扩展。

**前提证伪（2026-09-22 基线扫描实测）**：

1. **证伪**：文件名闭包内 20 个守卫类里，只有 4 个暴露「返回违规点/站点集合」的判定内核（`BannedApiSourceScanTest`、`DialogWrapperInitOrderGuardTest`、`EnumReachabilityGuardTest`、`DmlAliasGuardTest`）；其余 16 个断言直接内联在 `@Test`。且判定函数命名发散——`DialogWrapperInitOrderGuardTest:178` 与 `EnumReachabilityGuardTest:206` 都叫 `scan()`、`DmlAliasGuardTest:42` 叫 `dmlTargetsHaveNoAlias`、`DockerComposeLabConsistencyTest:147/:159` 是 `internal fun checkXxx`。按命名识别等于对这三个类零识别。**处置**：改走「显式登记表为主识别面」。
2. **证伪**：两个方向都错。(a) 漏——`generator/DmlAliasGuardTest.kt:125` 的 `guardDetectsInjectedAliasForms()` 名字不含任何关键词，标注是 `:121` 的 `//` 行注释（非 KDoc）且与 `@Test` 间隔着 `:122` `// ----` 与 `:123` 空行；(b) 过——按名匹配全树命中 ≥5 个自证函数，含 `ui/SqlPreviewPanelRollbackParseShapeTest.kt:103 探针自证_…` 这类**非守卫文件**，若按全树取会出现「非守卫文件跨文件假覆盖」。**处置**：自证识别限定在**被登记守卫类内部**，并接受紧邻注释（允许夹 `//` 分隔线与空行），且只认测试函数（避免把判定函数自身 KDoc 里的「可自证」当语料，`BannedApiSourceScanTest.kt:610` 即此形态）。
3. **证伪**：`convention/` 实为 4 个 `*Test.kt`（多出 `BundlePlaceholderArityTest.kt`，其 `:99 warningSites()` 恰命中 `*Sites` 约定），而三个大守卫类里只有 `BannedApiSourceScanTest` 的函数名命中命名约定——「先只覆盖 convention/ 三个大守卫类」的兜底范围实际只覆盖 1 个类的函数命名，与目标不匹配。**处置**：元守卫按「文件名闭包（全仓）+ 判定形状兜底面」双向闭合，覆盖全部守卫类。

**扫描脚本（仓库外一次性脚本，不入库）**：以 `src/test/kotlin` 全量 `*.kt` 为输入，① 取文件名含 `Guard`/以 `SourceScanTest` 结尾者；② 按 4 空格缩进的 `fun` 声明抽函数名与行号；③ 取「测试函数（`@Test` 或 `test` 前缀）+ 名字或紧邻注释含 `自证`/`自检`/`selftest`」为自证语料；④ 用函数体文本做词边界引用匹配。该脚本的判定语义随后被元守卫的 `元守卫灵敏度自证_…` 用例逐条钉住。
