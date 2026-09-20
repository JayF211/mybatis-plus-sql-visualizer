# T-PlaceholderSpliceGate 占位符拼接串感知化与基数对账 — 开发计划

- 日期：2026-09-20 11:44
- 任务编号：T-PlaceholderSpliceGate（第九轮报告 §9.3 **N-33**，P1）
- 来源：第九轮报告 §7.2 **Z-24**——zMyBatis 的可证拓扑替换：替换 `?` 前先证明 SQL 不含引号/注释符/`$`，任一命中即整体拒绝，再要求 `?` 计数与绑定数全等（`Materialization.kt:279-299`，`:167-168` 注释自述「拒绝半吊子 lexer」）
- 性质：**输出侧正确性**——不改输入侧闸门、不改渲染算法；把「朴素逐字符替换」换成既有的串感知实现并加对账
- 实施分支：自 master 拉任务分支 t-placeholder-splice-gate
- 验证基线：master `22d7c49`（testShards 5383/0/0/6 + testDatabase 10/0/0/0 + agent 58/0/0/0）

---

## 一、背景与依据（现状核实，含三处口径修正）

| # | 报告口径 | 核实事实 | 影响 |
|---|---------|---------|------|
| 1 | 「最终拼装只有一条路径」 | **不成立（重大口径修正）**：我方 `?` → 字面量的拼接有**三条互不相同的实现**：**面板回填**（朴素逐字符）、**复制/share**（朴素，同一函数）、**日志还原**（**串感知，安全**） | 落点从「新造闸门」改为「**让朴素的两条复用既有的串感知实现**」，成本大幅下降 |
| 2 | 「我方没有任何输出侧闸门」 | **成立**：既有三处安全机制全是**输入侧/解析期事实 + 确认（非拒绝）**——`hasDollarRawSegments`（解析期 `${` 收集 → 执行前确认框 → 复制时头部标记）、`SqlCommentStripper.strip`（复制路径清理，**非拒绝**，且面板回填路径不经过它）、`McpSelectGuard.stripLiterals`（执行侧 SELECT 守卫，与拼接链无关） | 报告所述「整体拒绝」与我方既有「确认」档不同；本任务按我方档位落地（见 §二 非目标） |
| 3 | 「`?` 计数对账缺失」 | **部分成立**：日志识别链**已有**严格等值门（`log/LogEntryPreviewRecognizer.kt:43-44`：`placeholderCount > 0 && placeholderCount == params.size`）、`LiteralParamRecovery` 有 fail-closed 双保险（`log/LiteralParamRecovery.kt:204`）+ 往返逐字符校验（`:220-237`）；XML/Wrapper 链是**结构性等值但无断言**；**复制路径的 `countReplaced` 只喂 toast 文案**（`CopyExecutableSqlAction.kt:69/80-84`），无等值校验 | 对账的缺口在**复制链与面板链**，不在日志链 |

### 1.1 三条路径的实测落点

| 路径 | 实现 | 是否串感知 | 证据 |
|---|---|---|---|
| `#{}` → `?` | 参数映射解析 | — | `xml/XmlSqlRenderer.kt:80-87`（`"?"` 字面量在 `:84`） |
| `${}` → mock 值 | token 替换 | — | `xml/TextSqlNode.kt:34-62`（`:55`） |
| **面板回填** | **朴素逐字符 `c == '?'`** | **否** | `ui/SqlPreviewPanel.kt:3625-3643`（`backfillParamsBySource`）+ `:3645-3672`（`renderParamValue`）+ `:3686-3696`（`renderSingleValue`） |
| **复制可执行 SQL / share** | **朴素**（同一函数，share 链复用） | **否** | `sql/ParameterReplacer.kt:67-90`（`buildExecutableSql`）+ `:96-107`（`renderValue`）；`share/format/ExecutableSqlFormatter.kt:28` |
| **日志还原** | **串感知** | **是** | `log/LogSqlRestorer.kt:19-34`（`restore`）+ `:97-128`（`placeholderPositions`：单引号字符串 / `--` / `/* */` 内的 `?` **不计**） |

**可复用的现成实现**：`log/LogEntryPreviewRecognizer.kt:100` `internal fun countPlaceholders(sql) = placeholderPositions(sql).size`（KDoc `:96-99` 明确「字符串字面量与注释内的 `?` 不计数」）。

**代码内已有的自认**：`log/LiteralParamRecovery.kt:202-203` 注释**明确承认面板回填是朴素替换、会错位**，并因此自行规避（`:204` 计数门 + `:220-237` 往返校验）——即「朴素回填会错位」在本仓已被认识到，只是没修到根上。

## 二、目标与非目标

**目标**：① 面板回填与复制/share 两条链改用串感知替换（引号/注释内的 `?` 不再被替换）；② 复制链与面板执行前补基数对账断言；③ 把「SQL 内 `?` 位点数 == 参数条数」这条自洽关系钉进测试。

**非目标**：不引入「含引号/注释即整体拒绝」的档位——我方既有档位是**输入侧确认**（`${}` 闸门）而非输出侧拒绝，本任务维持该姿态，只把替换做对；不改 `${}` 闸门与 `hasDollarRawSegments`；不改日志链（它已经是串感知的，是本次的**参照物**而非改动对象）；不改渲染算法（`XmlSqlRenderer`/`TextSqlNode` 不动）。

## 三、方案

**T-PSG-01（串感知替换的能力上提）**：`log/LogSqlRestorer.kt:97-128` 的 `placeholderPositions` 当前是 `log` 包 `internal`。**上提到 `sql` 包**（与 `sql/ParameterReplacer.kt` 同址，落点建议 `sql/PlaceholderPositions.kt` 或并入 `ParameterReplacer`），原处改为引用，保持行为逐字节不变。**理由**：三个消费方（面板 / 复制 / 日志）跨包，`internal` 跨包不可达；放 `sql` 包比放 `log` 包语义更准（它是 SQL 文本处理，不是日志处理）。

**T-PSG-02（复制链改串感知）**：`sql/ParameterReplacer.kt:77-88` 的 `buildExecutableSql` 改用 `placeholderPositions` 定位替换点（替换语义与顺序不变，只改「哪些 `?` 是占位符」的判定）。share 链经 `share/format/ExecutableSqlFormatter.kt:28` 自动受益。

**T-PSG-03（面板回填改串感知）**：`ui/SqlPreviewPanel.kt:3625-3643` 的 `backfillParamsBySource` 同改（逐字符循环 → 位点替换）。**注意**：`renderParamValue`/`renderSingleValue`（`:3645-3672`/`:3686-3696`）的**值渲染语义不变**，只改位点来源。

**T-PSG-04（基数对账）**：
- 复制链：`CopyExecutableSqlAction.kt:69` 的 `countReplaced` 现在只喂 toast，补一条对账——替换位点数 == 参数条数，不等时在 toast 或产物头部如实标注（**不阻断**，与我方确认档一致；具体形态见「待验证前提 2」）。
- 面板链：在既有两道确认门旁（`ui/SqlPreviewPanel.kt:1057-1061` 附近）补同一对账。
- 自洽关系独立成钉：新增用例断言「SQL 内串感知 `?` 位点数 == `rendered.mappings.size`」（`xml/XmlSqlRenderer.kt:38-49` 的 `RenderedXmlSql.mappings`）。

**T-PSG-05（测试与文档）**：定向用例——①含引号字面量内 `?` 的 SQL：面板回填与复制链**不再替换**该 `?`（改前红、改后绿）；②含 `--` 与 `/* */` 注释内 `?` 的同款；③正常多占位符 SQL：替换结果与改前**逐字节一致**（防回退，这是本任务的核心不变式）；④对账不等时如实标注。文档：CHANGELOG `[Unreleased]` + 变更记录行 + 第九轮报告 §7.2 Z-24 落地备注。

## 四、验收标准

1. **正常路径逐字节一致**：不含引号/注释内 `?` 的 SQL，替换结果与改前完全相同（用例 ③ 用硬编码字面量钉住）。
2. **串感知生效**：引号字面量内、`--` 注释内、`/* */` 注释内的 `?` 在面板回填与复制链**不被替换**（先红后绿）。
3. 三处消费方（面板 / 复制 / 日志）共用同一份位点实现（grep 断言：`placeholderPositions` 或新落点只有一个实现文件）。
4. 对账：替换位点数与参数条数不等时产物如实标注（用例 ④）。
5. 既有测试全绿：`ParameterReplacerTest`、`ExecutableSqlFormatterTest`、`CopyExecutableSqlActionTest`、`InjectionGateTest`、`SqlPreviewPanelParamKindTest`、`LogSqlRestorerTest`；全量 testShards + testDatabase + agent 绿（跑前按并行铁律登记）。

## 五、真机走查清单（留人工）

1. 真机预览一条含 `WHERE name = 'a?b'` 的 mapper：面板参数回填后引号内的 `?` 保持原样。
2. 真机对同一条 SQL 点「复制可执行 SQL」：粘贴出的 SQL 中引号内 `?` 未被参数值替换。
3. 真机含 `--` 行注释内 `?` 的 SQL：同上两条行为一致。
4. 真机正常多参数 SQL（无引号/注释内 `?`）：回填与复制结果与改前一致（回归确认）。

## 六、风险

| 风险 | 说明 | 缓解 |
|---|---|---|
| **行为面扩大** | 串感知后，「引号内的 `?`」不再被替换——若某用户依赖旧的（错位）行为，观感会变 | 验收 1 钉住「正常路径逐字节一致」；变更登记里如实写明「引号/注释内 `?` 不再被替换（修正向）」 |
| 上提 `placeholderPositions` 的可见性 | 跨包引用需改可见性或搬迁，可能触 `BannedApiSourceScanTest` 类平台 API 规则（风险低） | 优先**搬迁**（`sql` 包新落点）而非放宽可见性；搬迁后 `log` 包原处改为引用，行为不变 |
| 替换位点与值渲染耦合 | 面板的值渲染（`renderParamValue`）含数字启发式，若连带改动会引入第二处行为变更 | 明确只改「位点来源」，值渲染三函数**一行不动** |
| 对账形态未定 | 「不等时如实标注」的具体落点（toast / 产物头部注释）未定 | 列为「待验证前提 2」，实施时按既有 `CopyExecutableSqlAction.kt:65` 头部标记形态定 |

## 七、实施顺序

```
T-PSG-01（位点实现上提到 sql 包，行为不变）→ T-PSG-02（复制链）→ T-PSG-03（面板链）
  → T-PSG-04（基数对账 + 自洽关系成钉）→ T-PSG-05（测试与文档）→ 真机走查 §五
```

## 八、实施记录（实施后回填）

| 任务 | 提交 | 说明 / 偏离 |
|------|------|------|
| T-PSG-01 | （实施后填） | 新落点文件与 `log` 包原处引用形态须回填 |
| T-PSG-02 | （实施后填） | share 链是否自动受益须回填（验证一次） |
| T-PSG-03 | （实施后填） | 面板链改动的行区间与值渲染未动须回填 |
| T-PSG-04 | （实施后填） | 对账形态定案与落点须回填 |
| T-PSG-05 | （实施后填） | 先红后绿两次读数、门禁读数须回填 |

**待验证前提（实施者先证伪再动手）**：

1. 「三条链的朴素替换会错位」是本计划的核心前提，来自 `LiteralParamRecovery.kt:202-203` 的**注释自认**——实施第一步应**先写一条引号内 `?` 的用例跑改前代码**，确认它确实错位（若实际不错位，本任务降级为「统一实现」而非「修缺陷」）。
2. 「对账不等时不阻断」的口径与落点未定——我方既有档位是确认/标注而非拒绝（`${}` 闸门、`CopyExecutableSqlAction.kt:65` 头部标记），实施时须选一处既有形态照抄，不新造档位。
3. `placeholderPositions` 的实现是否**完整覆盖** SQL 串面（除单引号/`--`/`/* */` 外，双引号标识符、`#` 注释、`$$` 定界符内的 `?` 是否该计）——本计划只确认了它处理前三种；若目标方言含双引号标识符（PG/Oracle）内的 `?`，须实施时确认口径（**可能是有意的口径，勿擅改**）。
