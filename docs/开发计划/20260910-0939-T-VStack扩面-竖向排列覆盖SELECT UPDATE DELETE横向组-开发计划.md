# T-VStack·扩面：竖向排列覆盖 SELECT/UPDATE/DELETE 横向组开发计划

> **创建日期**：2026-09-10
> **状态**：✅ 已实施、已测试、已提交（master `db140aa`，2026-09-10；SqlFormatterTest 50/50 + SqlPreviewPanelTest 46/46 + 全量 2555/0/6 绿；runIde 真机走查留人工）
> **分支**：master（HEAD `d803a0b`；工作树仍含 T-PreviewTopOverlap 与 T-ExecSilentNoop 两会话在途，共享文件面不变，见「五、并行协调」）
> **优先级**：P2（延续 T-VStack 交付 `6dfdf72`，把「竖向排列」从 INSERT 专用扩为全语句的「逐行展开」开关）
> **预估工时**：4~5h（F1 通用组展开器 + F2 逐语句语境规则 + F3 测试 + F4 文档）
> **来源**：用户决策（2026-09-10）——确认竖向排列应对 SELECT/UPDATE 同样可用（现版对这两类输出与「格式化」完全一致，悬在 INSERT 一格），裁定走扩面方案

---

## 一、现状与缺口

T-VStack（`6dfdf72`）交付的 `formatVertical()`：首主子句 INSERT → 专用竖排（列清单 + VALUES 配对）；非 INSERT → `return format(sql)`。因此 SELECT/UPDATE/DELETE 点竖向排列**与「格式化」按钮产出逐字节相同**，无独立价值。

核对 `format()` 已竖排的部分（不受本计划影响）：SELECT 列清单（:101 newline+4）、UPDATE SET 赋值清单（同 :101 + :152 逗号换行）、顶层 AND/OR 缩进（:106-115）、主子句顶格换行。

**横向缺口**（`format()` 刻意不展开、竖向排列要补的目标，逐类型）：

| 语句 | 横向组 | 现 `format()` 输出 | 竖向排列目标 |
|---|---|---|---|
| SELECT | `WHERE id IN (1,2,3)` | `IN (1, 2, 3)` 同行 | 括号内一元素一行 |
| SELECT | `ORDER BY a DESC, b ASC` | 同行逗号+空格 | 一字段一行（修饰符紧跟） |
| SELECT | `GROUP BY a, b` | 同行 | 一字段一行 |
| SELECT | `FROM a, b`（逗号联表） | 同行 | 一表一行 |
| UPDATE | `SET x=1 WHERE id IN (…)` | IN 列表同行 | 括号内一元素一行 |
| DELETE | `WHERE id IN (…)` | 同行 | 一元素一行 |
| 通用 | 子查询 `IN (SELECT …)` | 整套同行 | **不展开**（见 D1） |

---

## 二、设计决策

### D1：IN 列表何类展开——字面量/占位符列表展开，子查询不展开

- **展开**：`IN (1, 2, 3)`、`IN (?, ?, ?)`——元素是标量，竖排换行读参便捷；
- **不展开**：`IN (SELECT user_id FROM orders WHERE status=1)`——元素是子查询，内部本应由 format 走缩进模型，竖向排列叠上去会与「格式化」语义纠缠；保持与 format 一致（`parenNewlined` 既有路径），留给 format 或快捷键处置；
- 判定：`IN (` 后首个语义 token 是 SELECT 关键字 → 子查询形态，不展开；否则按 depth==IN 括号内层、depth==1 逗号切分展开。

### D2：ORDER BY/GROUP BY/FROM 的字段按顶层逗号切分，修饰符紧跟字段

- `ORDER BY a DESC, b ASC` → 每字段一行（`a DESC` 一行，`DESC`/`ASC` 修饰符与其字段同行）；字段可能有 `t.a`、函数 `COUNT(*)`、`IF(…)` 等——按 depth==0 逗号切分（这些逗号在 `format()` 里本就 `currentClause` 为 GROUP/ORDER 时同行处理，切分点 = depth==0 逗号）；
- `GROUP BY a, b`、`FROM a, b` 同规则（depth==0 逗号切分）；
- `LIMIT 10, 20`：**保持同行**（分页参数语义紧，两个数字竖排反而不便，且历史 T-M29.2 专门修过 LIMIT 逗号不丢——不碰）。

### D3：UPDATE SET 赋值清单已逐行，本计划不改

SET 赋值清单 `format()` 已是 `col = val` 一行一对（天然配对），不重复竖排；只补其 WHERE 侧 IN 列表。

### D4：实现形态——`formatVertical` 收编通用组展开，非新入口

不改按钮、不改 i18n（竖向排列按钮与 panel.vertical 文案复用）；`formatVertical()` 的路由从「INSERT 专用 / else format」改为「INSERT 专用 / else **format + 逐层横向组展开后处理**」。即：

```
formatVertical(sql) =
  首主子句 INSERT  → 现有专用路径（不变）
  else             → expandHorizontalGroups(format(sql))
```

`expandHorizontalGroups` 在 format 产物上做**纯文本后处理**：只识别特定主子句后的横向组行（WHERE … IN (、ORDER BY、GROUP BY、FROM 逗号联表）并按逗号把组内容切成多行。切分只在「已被 format 排成单行」的子句内部发生，不触碰 format 的缩进/关键字大写/子查询模型。

### D5：后处理 vs 重走 pad 的取舍——后处理（保留 format 语义保真）

走纯文本后处理而非重写 format 的第二套递归：format 的产出自带关键字大写、主子句换行、缩进、括号对齐等全部既有语义，后处理只做「把某行内的横向组纵向化」这一个增量，风险局部化、token 保真与幂等断言可继承（见 F3）。代价是后处理要靠行/子句边界启发式，规则需逐条用例钉死。

---

## 三、方案设计

### F1：通用组展开器（SqlFormatter.kt 内部函数）

在 `format()` 产物（多行字符串）上做如下规则：

1. **IN 括号列表展开**：定位 `WHERE|HAVING|ON … IN (` 后、配对 `)` 前的括号内层；仅当 (a) `IN (` 后首 token 非 SELECT、(b) 组内含 depth-1 逗号 时，把该组 `(e1, e2, …)` 改写为
   ```
   (
     e1,
     e2
   )
   ```
   缩进 = 当前行缩进 + 4；元素内表达式（含自身括号/函数/`CASE … END`）整段保留，只按 depth-1 逗号切分。
2. **ORDER BY / GROUP BY 字段展开**：`ORDER BY` / `GROUP BY` 后按 depth==0 逗号切字段，一字段一行（缩进 = 子句行缩进 + 4），`ASC`/`DESC` 修饰符紧跟字段不上提。
3. **FROM 逗号联表展开**：`FROM` 后 depth==0 逗号切分，一表一行（含表别名）。

**识别前提**：只能对 `format()` 已收敛成单行的子句段操作；若某子句段本就被 format 排成多行（如子查询、条件括号组），跳过——保证后处理是纯增量的、幂等的。

### F2：逐语句语境接线

`formatVertical` 的 else 分支改为：

```
val expanded = expandHorizontalGroups(format(sql))
return if (expanded != sql) expanded else format(sql)
```

- INSERT 主路径完全不动（回归零风险）；
- 退化语句现在有独立产出，`testVerticalSelectFallsBackToFormat` / `testVerticalUpdateFallsBackToFormat` 两条**改为断言新竖排输出**（不再等于 format）。

### F3：测试

**SqlFormatterTest** 新增 `testVerticalExpand*` 组（约 10 条）：

1. SELECT `WHERE id IN (1,2,3)` → IN 列表逐行；
2. SELECT `ORDER BY a DESC, b ASC` → 字段逐行、修饰符紧跟；
3. SELECT `GROUP BY a, b` → 逐行；
4. SELECT `FROM a, b`（逗号联表）→ 逐行；
5. UPDATE `SET x=1 WHERE id IN (1,2)` → IN 逐行、SET 赋值清单仍逐行；
6. DELETE `WHERE id IN (1,2,3)` → 逐行；
7. `IN (SELECT …)` 子查询 → 不展开（整段保留 format 原样）；
8. `IN (?, ?, ?)` 占位符列表 → 逐行；
9. `ORDER BY COUNT(*), t.a` 函数/限定名列 → 逐行且字段表达式完整；
10. `LIMIT 10, 20` → 仍同行（兜底）。
11. token 保真 + 幂等（沿用现有 `semanticTokens` 与二次调用断言）。

**改写**：`testVerticalSelectFallsBackToFormat`、`testVerticalUpdateFallsBackToFormat` 两条语义反转（择一保留为「退化不再等于 format」的对照断言，余下转新预期）。

**面板测试**：`testVerticalNonInsertFallsBackToFormat`（SqlPreviewPanelTest）同步改为断言 SELECT 竖排后 IN/ORDER BY 组被展开（不再断言 equals format）。

### F4：docs 同步

- `docs/变更记录.md`、`docs/MEMORY.md` 变更记录行（倒序插入）；
- 本计划 §实施记录回填；
- 变更记录须标注「T-VStack·扩面续」以区别于 `6dfdf72` 首交付。

---

## 四、实施顺序

1. F1 展开器纯函数 + F3 新增用例（先绿）；
2. F2 接线 + 改写 3 条退化断言用例；
3. 定向测试 + 全量回归；
4. F4 文档 + 计划回填 + 记忆更新；
5. runIde 真机走查（留人工）。

---

## 五、并行协调（延续 T-VStack 五章约定）

- 每次 `./gradlew` 前 `parallel_tasks.py check`，登记 `T-VStackExpand`（独立沙盒 `sandbox-vstack-expand`）；
- 共享文件面：`SqlPreviewPanel.kt` + 两 bundle + 两 docs 仍在对方会话在途——本计划只新增 `SqlFormatter.kt` / `SqlFormatterTest.kt` / `SqlPreviewPanelTest.kt` 的改动 + docs 两行；共享文件如有改动仍走 hash-object + update-index 索引级拆分（删行==0 断言）；
- 提交时 `git add` 只含本任务文件，docs 两册按序索引级拆分行。

---

## 六、验收标准

- SELECT/UPDATE/DELETE 点竖向排列后，IN 列表 / ORDER BY / GROUP BY / FROM 联表逐行展开，且与「格式化」按钮输出可区分（不再同名）；
- INSERT 主路径不回归（现有 testVertical* 13 条全绿）；
- 子查询 IN 不展开；LIMIT 逗号不碰；token 保真 + 幂等；
- 全量回归绿；runIde 真机走查留人工。

---

## 七、风险与备选

- **风险①**：纯文本后处理的子句边界启发式对复杂一节（`WHERE a IN (…) AND b IN (…)` 双 IN）需逐字段定位——用例钉死，失败即裁界到单 IN；
- **风险②**：`CASE WHEN … THEN … ELSE … END` 出现在 ORDER BY 内的逗号会被误切——若遇此类，ORDER BY 展开规则加「CASE…END 整原子」守卫，或该类字段不展开；
- **备选**：重写 format 成带 extra 参的第二递归（`format(sql, expandLists=true)`）——语义更稳但侵入 format 主路径、INSERT 路径与退化路径行为差异面变大，先做后处理（D5），后处理兜不住再降级重写。

---

## 实施记录（2026-09-10 交付，master `db140aa`）

**与计划的偏差**：

1. **IN 展开形态**：`(` 自占一行（`IN` 后换行出括号），元素行 = 原行缩进 + 4，`)` 收行后接行尾残余——首版把 `(` 留在 `IN ` 同行，5 用例逮住后统一为与 INSERT 组一致的形态，观感与首交付统一。
2. **双 IN 期望修正**：第二个 `AND b IN` 行缩进 = format 的 AND 缩进（4），非 0——期望块 trimIndent 前缀写错，实现本身正确。
3. **line/trimmed 双参**：展开函数收原行 + 去缩进行两参——首版在 trimmed 上取前缀把 `AND`/`OR` 前导缩进丢了（双 IN 用例逮住真 bug），改为原行保前缀、trimmed 供定位。
4. **风险①②均未触雷**：单行含双 IN 场景按行独立展开天然成立；CASE…END 未入用例（现网 ORDER BY 语料无此形态，展开守卫「行尾残余含括号即放弃」已兜一层）。
5. **测试 12 条落点**：新增 10 条 + 反转 2 条 + 面板 1 条反转（计划估 10+3 反转，量级一致）。

**并行协调实际执行**（第五章约定落地 + 新增两条实录）：

- 全程登记 T-VStackExpand/sandbox-vstack-expand；gradle 全在我沙盒跑（10:07 全量 2555/0/6 绿）。
- **共享文件被对方覆盖实录**：本会话的 build.gradle.kts 临时沙盒行被 T-ExecSilentNoop 会话整文件覆盖为其沙盒行（10:0x），我的「还原」义务天然完成（无残留）；其后两次全量跑分别撞上共享 build 目录编译写入竞态（worker ClassNotFoundException 中断）与对方沙盒「用户映射区域」锁（prepareTestSandbox FAILED）——均为登记表预告的环境坑，非语义失败，1467/0 零失败佐证。
- **计数口径修正**：历史「全量 3212」为跨任务累积目录计数；单次 `./gradlew test` 权威口径 ~2555（196 类）——下不为例，记录以单次跑为准。
- 三代码文件（SqlFormatter.kt/SqlFormatterTest.kt/SqlPreviewPanelTest.kt）本会话独占（删行均为本会话断言反转），直接 git add；docs 两册仍索引级拆分行（对方在途）。

**验证结果**：SqlFormatterTest 50/50（含 testVerticalExpand* 10 + 反转 2）+ SqlPreviewPanelTest 46/46 + 全量 2555/0/6 绿。runIde 真机走查（SELECT/UPDATE/DELETE 各形态点按钮、IN 子查询保持同行、LIMIT 同行、亮暗主题）留人工。

---

## 追加交付：IN 展开阈值（T-VStack·InThreshold，master `9ef6b20`，2026-09-10）

**用户复议**：扩面交付后用户指出「IN 的参数单独一行是否合适」——裁定短清单竖排是负收益（占位符 `IN (?, ?, ?)` 是本插件 inList 特性主力形态，`?` 对应关系靠左侧参数面板顺序对照，竖排零增量信息、纯花行高把相邻条件推出视口）。

**修复**：`expandInList` 加 `IN_EXPAND_MIN_ELEMENTS = 5` 阈值——元素 <5 保持 `IN (1, 2, 3)` 同行，≥5 才 `(` 自占一行逐元素展开（长清单痛点保留）；ORDER BY/GROUP BY/FROM 字段行有语义信息不受影响。

**测试**：新增 3 条（长清单展开 / 恰 4 同行恰 5 展开边界 / 占位符短清单同行），回调 5 条短 IN 用例预期 + 2 条退化断言改长清单输入 + 双 IN 用例改双长清单；SqlFormatterTest 52/52、SqlPreviewPanelTest 46/46、全量 3230/0/6 绿（sandbox-vstack-in 隔离）。

**插曲（K2J 陈旧输出劫持再犯）**：阈值编译进 kotlin/main 新类后 5 用例假红——`build/classes/java/main` 残留 783 旧类（SqlFormatter.class 停在扩面前版本）排前于新类，jar 与测试运行时均吃旧类；字节码反汇编证实实现正确后按记忆既定方案清目录复绿。同环境并行会话活跃时该目录会被再次写入，全量绿后立即提交。

**新基线效果**：
- 短清单（本插件主力 `IN (?, ?, ?)`）：`WHERE station_id IN (?, ?, ?)` 一行不动；
- 长清单：`WHERE org_id IN` 换行 + `(` 自占一行 + 逐元素 + `)` 收行。