# T-ListParamCollect JdbcTemplate 集合参数括号误入字面量 — 开发计划

- 日期：2026-09-15 10:35
- 任务编号：T-ListParamCollect
- 来源：用户真机实测（Spring NamedParameterJdbcTemplate 单行日志）
- 性质：日志还原正确性缺陷（还原 SQL 不可执行）
- 实施分支：基于 master 开任务分支 `t-listparamcollect`
- 验证基线：master 全量 `./gradlew testShards` 4103/0/0/6 绿（T-PerfSweep2 合入后）

---

## 一、问题现象

用户日志：

```
2026-09-15 10:20:04.176 [cache-preheat-1] TRACE com.AAA.BBB.CCC.orm.impl.dml.DDDObjectRepositoryJdbcImpl Line:260 - sql = SELECT COUNT(*) as count FROM tableA  WHERE ( ("deleted" = :param1) AND (("deleted" = :param2) AND ("id" IN (:param3))) ) , parameters = {param1=0, param2=0, param3=[6b31d693xxxxx7f4979b]}
```

当前还原结果：

```sql
SELECT COUNT(*) AS COUNT FROM tableA
WHERE (("deleted" = 0) AND (("deleted" = 0) AND ("id" IN ('[6b31d693xxxxx7f4979b]'))))
```

两个问题叠在同一条语句上：

1. **注入形态错误（本任务主目标）**：`IN` 只有一个占位符 `(:param3)`，还原后必须变成 `IN ('6b31d693xxxxx7f4979b')`（单元素集合），当前把 `[ ]` 当值的一部分渲染成 `IN ('[...]')`——字符串字面量里带着方括号，SQL 语义错误（查出来的永远是空集）。
2. **中括号形式错误（同一根因的下游表现）**：即便按字面量渲染，`in` 列表也应用圆括号 `IN ('6b31d...')`，而不是把 Java 集合的 `toString()` 方括号原样吐进 SQL。

## 二、现状核实（master 实测，出证到 文件:行）

| 面 | 现状 | 位置 |
|---|---|---|
| 命中解析器 | `JdbcTemplateLogParser` 分支 1（NamedParameterJdbcTemplate 单行格式），非 MyBatis 解析器 | `log/JdbcTemplateLogParser.kt:70-77`、`:162-181` |
| 参数 Map 取值 | `parseNamedParamsMap` 按 `, key=` 边界切分，值原样保留（含 `[` `]`） | 同文件 `:188-201` |
| 值渲染 | `renderUntyped`：数值/true/false 裸渲染，其余**整体**引号包裹 | 同文件 `:207-224` |
| 集合展开 | **完全没有**——无 `[` `]` 剥离、无逗号拆分、无 `IN` 列表识别 | 同文件（全文件 grep 无括号处理） |
| 还原 SQL 落地 | `restoredSql` | 同文件 `:167`；面板与列表摘要均消费它 |
| 其它渲染通道 | 预览弹窗走 `LogSqlRestorer`（`?` 路径）+ `ParameterReplacer`（`:name` 路径） | `log/LogSqlRestorer.kt:37-46`、`sql/ParameterReplacer.kt:130-154` |

**关键事实一（无多元素需求的证据）**：`NAMED_PARAM_PATTERN` 的 SQL 侧为 `sql\s*=\s*(.*?)\s*,\s*parameters\s*=\s*\{([^}]*)\}`（`log/JdbcTemplateLogParser.kt:383-386`）。NamedParameterJdbcTemplate 的 `sql = ...` 单行日志是在**参数已解析成 SQL 文本之后**打印的，`IN (:param3)` 这种「一个占位符对多值」的形态必然由调用方手写而成，运行时多值会被展开成多个 `?`，日志里就变成 `IN (:param3, :param4, ...)` —— 每个参数一个值。所以单行格式里**一个命名参数恒对应单值**，`[a]` 只可能是单元素集合的 `toString()`。

**关键事实二（同类先例）**：代码里已有 `IN` 多值识别能力 `detectInClauseParams`（扫描 `IN (` 后的 `?`，`ui/SqlPreviewPanel.kt:2693-2710`）+ `ParamHint.inList`（`sql/ParamHint.kt:12-19`），语义是「把逗号分隔的多值拆成独立字面量」。本任务沿用它表达同一件事：该参数处于 `IN` 位置且值是 List，日志里的单值应还原为一个元素字面量。

**关键事实三（渲染分叉）**：日志条目的还原 SQL 由解析器一次性写入 `LogSqlEntry.restoredSql`，预览弹窗的 `toPreviewResult` 三条分支中，第二条（占位符数 == 参数数 → 参数行预填）与第三条（回退现状）都直接展示 `restoredSql`（`log/LogEntryPreviewRecognizer.kt:83-138`）。当前 named 条目的 `params` 是**伪参数**（名字 `:paramN` 与值，不是 `?` 位；`JdbcTemplateLogParser.kt:169-174`），`countPlaceholders(rawSql)==0` 而 `params.size>0` → 识别失败 → 走第三条回退，展示的正是 `restoredSql`。

## 三、方案

### T-LPC-01（纯逻辑核心，主修复）

新增 `log/ListParamRenderer.kt`（object，纯函数零平台依赖），解析器与预览通道共用同一渲染口径：

```kotlin
/** 渲染命名参数值：IN 位置的集合值剥括号按元素渲染，其余沿用现值渲染。 */
fun renderParam(value: String?, inClause: Boolean, render: (String) -> String): String

/** 集合形态判定 + 元素拆分：[a] → [a]，[a, b] → [a, b]，[] → 空列表；非集合形态返回 null。 */
fun splitListElements(value: String): List<String>?

/** 扫描 SQL 中的 IN (...)，收集区间内的命名参数名（字符串/注释感知）。 */
fun inClauseParamNames(sql: String): Set<String>
```

规则（钉死，避免「猜」）：

- **集合识别**：值形如 `[x]`（首尾为 `[` `]`）时判定为 List 的 `toString()`，剥括号后按 `,` 拆分（**不做转义还原**——`toString()` 产物本身无法可靠逆解析，菜谱如实为「剥括号 + 逗号切分 + 逐元素 trim，空元素丢弃」）。
- **IN 位置**：`inClauseParamNames` 扫描 SQL 中的独立 `IN` 关键字（大小写不敏感、允许 `IN(` 与空格、词边界防 `index_col` 误伤）+ 可选空白 + `(`，字符串感知地匹配括号区间（单引号字符串内不计括号，`''` 转义，`--`/`/* */` 注释跳过），区间内的 `:name` 即 IN 位置参数。同位置区间内多个参数（`:p1, :p2`）各自独立取值。
- **渲染**：
  - IN 位置 + 集合形态（含单元素）→ 拆分后逐元素渲染（`renderUntyped` 口径：数值裸 / 其余引号 + `'` 转义），逗号 + 空格连接；
  - IN 位置 + 非集合单值（如 `:param3` 值为 `6b31...`）→ 单元素渲染（与拆分结果同一函数分支，保证一致性）；
  - IN 位置 + 空集合 `[]` → `NULL`（SQL 层无合法表达，呼出方括号）；
  - 非 IN 位置 → 沿用现有 `renderUntyped`，字节级零变化；null 字面量交回调渲染（解析器回调产出 `NULL`，面板回调走既有空值分支）。
- **不改 SQL 文本本身**：`IN (…)` 的括号是 SQL 里本来就有的，本任务只改「参数位置渲染成什么」，绝不新增/删除 SQL 字符。

**实施修订（签名）**：原拟 `isInClauseParam(sql, paramName)` 每次调用重扫 SQL，实际收口为「调用方先 `inClauseParamNames(sql)` 一次、对每个参数查集合」，`renderParam` 以 `inClause: Boolean` 传入——避免逐参数 O(N²)。

以用户样例校验：`"id" IN (:param3)` + `param3=[6b31d693xxxxx7f4979b]` → `"id" IN ('6b31d693xxxxx7f4979b')`。

### T-LPC-02（解析器接线）

`JdbcTemplateLogParser.replaceNamedParams`（`:207-214`）改为经 `ListParamRenderer` 渲染。参数值列表（`params`，`:169-174`）保持现状记原始日志值不变——它是历史文件 `-- pd:` 载荷与列表展示的数据来源，改了会污染落盘与旧文件对比。

**实施修订**：`inClauseParamNames` 在解析器按 SQL 调一次、对每个参数查集合即可，不与逐参数重扫耦合。

### T-LPC-03（预览通道同口径）

覆盖**同一条日志的其它两条展示路径**，避免「列表对了、双击弹窗还是错的」：

1. `LogSqlEntry.toRecognizedPreview`（`log/LogEntryPreviewRecognizer.kt:39-63`）的 `initialValues` 预填值：值处于 IN 位置且为集合形态时，同样剥括号拆分为逗号分隔文本，使参数行预填值经面板既有的 `inList` 逗号分支（`ui/SqlPreviewPanel.kt:2737-2741`）渲染出与 01 一致的 SQL；
2. 参数行 `paramHints`：IN 位置的参数补 `ParamHint(inList = true)`（新增 `LogSqlParam.toHint` 的 inList 形参，`log/LogSqlRestorer.kt:85-89` 无需改签名，改调用点）。

**实施修订（两处，均为负面结论）**：

- **修订一：第 1 条的实现位置改为「仅回退分支预填」**。原设想在识别成功分支也预填——实测走查后发现该分支根本到不了：命名参数条目的 `params` 是伪参数（名字 `:paramN` + 值，非 `?` 位），`countPlaceholders(rawSql) == 0` 而 `params.size > 0` ⇒ 恒 `recognized=false`，永远走第三条回退分支直接展示解析器写好的 `restoredSql`。故 `initialValues` 在回退分支补出「参数名 → 日志值（集合值转元素清单）」，供面板按名回填；
- **修订二：第 2 条（`paramHints` 补 `inList`）判定为无效做法，不做**。named 条目 `unresolvedParams` 为空，`paramHints` 与之并行、无参数行消费它；而 `:name` 的渲染在面板走 `applyNamedParams` → `ParameterReplacer.replaceNamedParams`（按名整体替换，不读 `inList`），补上也不产生效果。真正生效的路径是 `initialValues` 本身（面板 `paramValues` 按 source 初始化 → 用户改值后经 `ParameterReplacer` 按名回填）。
- 顺带加防御：`namedInitialValues` 在「SQL 中 `:name` 引用数 ≠ params 数」时整体返回空表，宁可空表也不把值错位配到别的参数名上。

### T-LPC-04（i18n + 测试）

**实施修订：i18n 零新增键**。原计划的 `log.preview.listParamExpanded` 提示取消——详见 §六「集合传参不声称支持」的定案：既然本任务只保证「方括号不落进 SQL」而不宣称「集合传参被正确还原」，再弹一条「已按元素还原，请核对是否与运行时一致」属于自我加戏，且与既有回退分支「零 `?` 无警告」的克制口径冲突。

测试（表驱动，全在 `log/JdbcTemplateLogParserTest.kt` 与 `log/LogEntryPreviewRecognizerTest.kt` 就地扩）：

- 正例：用户原始日志逐字复现 → 还原 SQL 精确等于期望串（含 `IN ('6b31...')`，且**断言不含 `[` 与 `]`**）；
- 边界：多元素 `[a, b]`（日志形态罕见但按下述口径处理）、空集合 `[]`、含引号元素 `[it's]`（转义 `''`）、`IN` 大小写与 `IN(` 无空格、`IN` 位置单值非集合、同名参数在 IN 与非 IN 位置各出现一次（按位置分别渲染）；
- 防回归：非 IN 位置的既有 6 条 named 用例（`:326-404`）断言逐字不变；`[`/`]` 出现在普通字符串值中（非 IN 位置）保持原样包裹。

**实施修订（测试清单落地差异）**：① 含引号元素 `[it's]` 的转义断言下沉到 `ListParamRendererTest` 的回调层（本层不做转义，交由回调），未进解析器用例；② 「同名参数在 IN 与非 IN 位置各出现一次」未单独成例——`NAMED_PARAM_PATTERN` 捕获组按名替换存在固有覆盖问题（`replace(":$name", …)` 是子串替换，同名多处共用一次渲染），属既有边界不在本任务范围，改为在 `ListParamRendererTest` 表驱动中覆盖「IN 位置 vs 非 IN 位置」的判定正交性。

### T-LPC-05（文档同步）

- CHANGELOG `[Unreleased] Fixed`：一句话用户口径（「JdbcTemplate 命名参数的集合值不再把方括号带进 SQL，还原为 IN 列表」）；
- `docs/变更记录.md` 总册行；`docs/MEMORY.md` 开发计划索引行；
- 文档站 `features/sql-log.md`（+ `en/` 镜像）：JdbcTemplate 段落补半句「命名参数为集合时按元素还原」；
- `README.md` 第 98 行日志特性清单补同款半句。

**实施修订**：仓库当前无 `[Unreleased]` 块（发布时改名，见 `docs/发行说明/版本号规范-说明文档.md`），条目直接追加到 `[2026.9.14]` 的 Fixed/Test 分区（承接 T-PerfSweep2 / T-SettingsOrder 的落法）。

## 四、验收标准

1. 用户原始日志经解析 → 还原 SQL 精确等于 `SELECT COUNT(*) as count FROM tableA WHERE ( ("deleted" = 0) AND (("deleted" = 0) AND ("id" IN ('6b31d693xxxxx7f4979b'))) )`（按实际格式化开关呈现），**且不含 `[` / `]`**。
2. 双击该条目预览弹窗展示的 SQL 与列表一致；改参数值后回填结果一致。
3. 非 IN 位置的一切既有行为逐字节不变（`JdbcTemplateLogParserTest` 既有 named 用例零改动通过）。
4. 全量 `./gradlew testShards` 绿（基线 4103/0/0/6 + 新增用例数）。

## 五、真机走查清单（留人工）

1. 真机粘上用户这段日志 →「日志 → SQL」结果区出 `IN ('6b31…')`；复制后可直接执行、返回行数与预期一致。
2. 控制台实时捕捉同款日志 → 列表条目摘要与单击预览同步正确。
3. 自动保存历史开启时，落盘文件正文为正确 SQL（`-- pd:` 行载荷内值仍为原始 `[6b31…]`，属预期）。

## 六、风险与边界

| 风险 | 说明 | 缓解 |
|---|---|---|
| 误判普通字符串值 | 非 IN 位置的值若恰好形如 `[a]`，不应被剥括号 | 集合识别**只在 IN 位置生效**（两条件同时满足才展开）；表驱动含「非 IN 位置 `[a]` 值原样」用例 |
| 多元素 `[a, b]` 语义 | 单行格式里一个命名参数恒单值（见 §二 关键事实一），多元素是「值本身含逗号」的极小概率场景 | 按元素拆分渲染，与 `IN` 语义自洽；文档与 CHANGELOG 不宣称支持「集合传参」，只宣称「方括号不再进 SQL」 |
| 元素内含逗号 / 嵌套集合 | `{{a,b}}`、`[a, b]` 无法可靠逆解析 | 明确文档化为已知边界（不抛错、不猜测），保留原值；**不加面板提示**（见下） |
| 与 `?` 路径口径分叉 | `LogSqlRestorer` 的 `?` 路径本不拆逗号（MyBatis 每元素一个 `?`），本任务不改它 | 只在新 `ListParamRenderer` 内实现，其它渲染器零改动 |

### 定案：不声称支持「集合传参」、不加提示文案

本任务只保证「方括号不落进 SQL」，**不宣称「一个命名参数传集合」被正确还原**（该形态在本日志格式里不存在，见 §二 关键事实一）。因此原计划的 `log.preview.listParamExpanded` 提示取消：既有的回退分支对零 `?` 条目本就不发警告，为一件不承诺支持的事弹提示属自我加戏；用户若真要传集合，正确地写 `IN (:p1, :p2, …)` 即可，那条路径逐参数还原本来就是对的。

## 七、实施顺序

```
T-LPC-01（纯函数 + 表驱动测试）→ T-LPC-02（解析器接线）
→ T-LPC-03（预览通道同口径）→ T-LPC-04（测试补齐）
→ T-LPC-05（文档同步）→ 真机走查 §五
```

门禁口径：全量 test 绿即门禁（当前基线 4103/0/0/6）；`verifyFull` / `buildPlugin` 本轮不跑（非发版前、非大功能，按既有口径）。跑任何 `./gradlew` 前先 `python C:\Users\USER\.zcode\cli\parallel_tasks.py check`，有活跃任务则登记独立沙盒、用后注销。

## 八、变更记录（单文件）

- 2026-09-15 立项：用户真机实测（NamedParameterJdbcTemplate 单行日志的集合参数还原错误），定位到 `JdbcTemplateLogParser.renderUntyped` 无 IN/集合语义，五任务分解。
- 2026-09-15 交付：五任务全部落地分支 `t-listparamcollect`。新增 `log/ListParamRenderer.kt`（纯函数）+ `ListParamRendererTest` 9 例，解析器接线与识别层回退分支预填，测试净增 20 例（解析器 6 + 识别层 2 + 纯函数 9 已含）；全量 `testShards` **4123/0/0/6 绿**（基线 4103 + 20）。三处实施修订见 §三 各任务「实施修订」（T-LPC-03 第 2 条判定无效不做、T-LPC-04 零新增 i18n 键并取消提示文案、CHANGELOG 无 `[Unreleased]` 块直接落 2026.9.14 分区）。**坑**：改一处 Kotlin 源后字节码长期不变（`iconst_2` / `iconst_3` 常数与空集合分支行为不符），一度按「K2 陈旧输出劫持」清理 `build/classes` 排查——该轮为 dummy 探针文件同名（`build/tmp_probe.class` 抄自构建产物）所致的自证循环，真正修复是重写 `isInKeyword` 去掉折行折出的中间变量 `val next = i + 2`（拆成显式 `if (i + 1 >= sql.length) return false` + `sql[i + 1]`，避免「位置变量名 next 与语义不符」的读写错位）。附带发现 `build/probe` 目录在 `clean` 后需重建。真机走查三项留人工（§五）。