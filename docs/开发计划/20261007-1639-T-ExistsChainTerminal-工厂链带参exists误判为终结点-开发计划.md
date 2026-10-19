# 开发计划：T-ExistsChainTerminal——工厂链裸实参下带参 `exists` 被误判为链终结点（EXISTS 条件丢失）

- 编号占用：O-120 / R66 / D93~D94 / V120（落库前先 git 实测是否被并行会话占用；D88 有意留空，避开 O-118/R64 撞号区）
- 日期：2026-10-07
- 状态：✅ **已实施**（2026-10-07，提交 `71e54b4`，分支 `t-exists-chain-terminal`；读数与偏差见 §九）
- 实施方式：master 切分支 + 独立工作树（用户令）；未动 master 上并行会话在途文件
- 来源：反馈池 **FR-0012**「Wrapper 预览希望支持 exists / notExists 子查询的 SQL 还原」＋ 2026-08-14 旧计划 `20260814-2234-exists条件误判为链式终结点-开发计划.md`（T-M25.2）
- 任务性质：**补历史欠账 + 修正确性缺陷**，非新功能开发

---

## 一、需求与来源

### 1.1 反馈原文（FR-0012，2026-10-07 经 `/api/v1/feedback/posts/FR-0012` 实测拉取）

| 字段 | 值 |
| --- | --- |
| 标题 | Wrapper 预览希望支持 exists / notExists 子查询的 SQL 还原 |
| 正文 | 项目里大量用 `LambdaQueryWrapper` 的 `exists("select 1 from order o where o.user_id = t.id")` 写子查询，预览面板里这段会显示成原始占位，看不到拼接后的完整 SQL；希望预览时把 exists 子查询拼进最终 SQL，方便直接复制到数据库工具跑 |
| 环境自述 | MyBatis-Plus 3.5.7 / IDEA 2024.2 / pluginVersion `2.3.1` |
| 评论 1 条 | 「同样的需求 +1，我这边是 notExists 配合 apply 用得多」 |
| 状态 | 待审（status=1），0 票 |

### 1.2 反馈数据本身的三处可疑（排期权重参考，不影响技术结论）

1. `pluginVersion: "2.3.1"` **对不上任何发行版**——版本选项接口 `/api/v1/feedback/version-options` 只给 `v2026.8.13`~`v2026.10.7`；本仓 CHANGELOG/tag 也从未有过 2.x 体系（最早 1.0.0 / 1.1.0，2026-08-12）。
2. 发帖 `17:22:55`、评论 `17:23:00`，**相隔 5 秒且作者不同**（老张 / 小李）。
3. 同池 5 条帖里另 4 条（FR-0011/0014/0016/0017）全部「已完成」，其中 FR-0017 提交人是仓库作者本人。

→ 更像早期造数/演示帖。**处置建议**：不作为真实反馈计入票数与排期权重；但内容恰好压在真实缺陷上，技术复核与修复照做。回访/回复时按「已能拼出 EXISTS，请确认版本」口径，不必按新需求立项叙事。

### 1.3 需求字面：当前版本已实现（勿重复建设）

- 解析器有专门实现：`QueryWrapperParser.parseExists`（`converter/QueryWrapperParser.kt:839`），分派在同类 `:164`/`:165`，输出 `EXISTS (...)` / `NOT EXISTS (...)`，支持布尔前缀 toggle 与 `{i}`/`{0}` 参数替换。
- 引入版本 = CHANGELOG「exists 三形态（T-M15-03，Q14）」落在 **2026.8.13**——即版本下拉里**最旧**的一档。**当前所有可安装版本都带这个能力。**
- 金样矩阵已有该形态契约：S18（`exists`）/ S22（`notExists`），期望 `WHERE EXISTS (SELECT 1 FROM station WHERE station.id = device.id)`。
- **实测**：`./gradlew test --tests SqlPreviewSpecMatrixTest` → **91 例 0 失败**（S18/S22 在内，2026-10-07 实测，HEAD `3a85467`）。

→ 本节结论：**不能把 FR-0012 当新需求做**。真正的活是下面 §二 那个历史欠账。

---

## 二、现状分析

### 2.1 缺陷实测（五形态探针，2026-10-07 已跑，探针文件已删）

探针方式：复刻各写法 → `SqlPreviewService.preview()` 端到端取 `result.sql`（非模型级断言）。

| # | 写法 | 实测预览 SQL | 判 |
| --- | --- | --- | --- |
| A | `list(Wrappers.<Device>lambdaQuery().eq(..).exists("SELECT 1 FROM station WHERE station.id = device.id"))` —— **工厂链裸作实参，链内无真终结点** | `SELECT EXISTS (SELECT id, station_name, status FROM device WHERE status = 1)` | ❌ **子查询条件整条丢失 + 外层被包成 `SELECT EXISTS`** |
| B | 工厂链赋给变量再消费 | `SELECT id, station_name, status FROM device WHERE status = 1 AND EXISTS (SELECT 1 FROM station WHERE station.id = device.id)` | ✅ |
| C | `new` 链赋给变量再消费 | 同 B | ✅ |
| D | 分步续链（`new` 后逐句 `w.exists(...)`，反馈原文写法） | 同 B | ✅ |
| E | A 形态但换 `notExists` | `... AND NOT EXISTS (SELECT 1 FROM station WHERE station.id = device.id)` | ✅ **不对称：`notExists` 不触发** |

A 形态的产物比「少一段」更糟：整条查询被包成 `SELECT EXISTS (...)`，用户复制出去执行语义完全不同。

### 2.2 根因（代码级，已定位）

| 位置 | 事实 |
| --- | --- |
| `psi/WrapperLocator.kt:600` | `val terminal = clearedCalls.lastOrNull { it.methodExpression.referenceName in chainTerminalMethodNames }` —— 终结点判定**只按方法名**，不看实参 |
| `psi/WrapperLocator.kt:1396` | `chainTerminalMethodNames` 名单里含 `"exists"`（T-M15-01 为 `service.lambdaQuery()...exists()` 无参终结点加的） |
| `psi/WrapperLocator.kt:601` | 命中即 `filterNot { it === terminal }` —— 该调用被从链里剔除 |
| `psi/WrapperLocator.kt:607` | `chainExistsConsumed = terminalName == EXISTS_TERMINAL` → true |
| `ui/SqlPreviewService.kt:273`/`:352` | `chain.existsConsumed && wrapperType.isQueryLike()` 分支 → 整条包 `SELECT EXISTS (...)` |

**语义撞名**：`exists` 在 MP 里同时是「条件方法」与「链终结点」。A 形态里它恰好落在链尾，**结构上无法区分**，只能按**实参个数**判：无参 = `service.lambdaQuery()...exists()` 终结点；带参 = 条件方法。`notExists` 不在名单里，所以 E 形态正常——不对称的来源即此。

### 2.3 这是旧账，方案早已写就但从未落地

- 档案：`docs/memory-archive/bug-fixes/project-exists-bugfix.md`（2026-08-14 用户实测，同一个 `service.page(page, Wrappers.<T>lambdaQuery()....exists(flag, "SELECT ... {0}", v)...)` 形态）。
- 计划：`docs/开发计划/20260814-2234-exists条件误判为链式终结点-开发计划.md`（T-M25.2，§3 给出修复代码片段 = 无参守卫，约 5 行）。
- **核实结论**：计划状态是「待确认后实施」，当前代码 `WrapperLocator.kt:600` **无该守卫**；`git log` 无对应落地提交；全仓测试**零覆盖 A 形态**（测试树内 `.exists("` 只有金样矩阵的 `new` 形态与 `ConsumerMethodsCoverageTest` 两例，均非裸工厂链）。
- 计划 §2 已预判的「同一根因两种表现」与本次实测一致：剔除（旧构建→纯 SELECT 缺条件）与包裹（现构建→`SELECT EXISTS`）。

### 2.4 同源的第二处（Kotlin 链，另一条代码路径）

`psi/KtWrapperDetector.kt`（文本扫描，`.kt` 文件走 `WrapperLocator:105 locateFromKotlin`）：

- `:27-31` 终结点跳过名单含 `"exists"` → `.exists(...)` 一律被跳过；
- `:35-42` 操作白名单**不含** `exists` / `notExists` / `apply` → 这些条件根本不进链；
- 但该路径整体是 MVP：`locateFromKotlin`（`:117`）不解析实体，`table` 恒 null，预览会走「无法推断实体表」降级。**单点修此处无用户可见收益**，登记不现修（见 D94）。

---

## 三、方案设计

### 3.1 主修复：终结点判定加「exists 无参才是终结点」守卫

`psi/WrapperLocator.kt` 终结点筛选（`:600`）改为：

```kotlin
val terminal = clearedCalls.lastOrNull { call ->
    call.methodExpression.referenceName in chainTerminalMethodNames &&
        !isExistsConditionCall(call)
}
```

新增单一来源判定（放 `WrapperLocator`，`:1395` 名单附近）：

```kotlin
/**
 * exists 双语义守卫：带参 exists(condition, sql, values) 是条件方法（Q14-①）；
 * 仅无参 exists() 是链式终结点（service.lambdaQuery()...exists()，Q14-③）。
 * 带参形态被当终结点会同时触发「条件剔除」与「整链 SELECT EXISTS 包裹」（裸工厂链实参场景）。
 */
private fun isExistsConditionCall(call: PsiMethodCallExpression): Boolean =
    call.methodExpression.referenceName == EXISTS_TERMINAL &&
        call.argumentList.expressions.isNotEmpty()
```

### 3.2 同名单的第二处连线核对（按 D93 选项 B 一并收口）

`initializerIsWrapperChainWithoutTerminal`（`:370`）同样按名单判终结点：

```kotlin
if (expr.methodExpression.referenceName in chainTerminalMethodNames) return false
```

`var w = Wrappers.<T>lambdaQuery()....exists(sql)` 会因 `exists` 被拒为 wrapper 变量。按 D93=B 走同一判定函数，避免两处判据漂移：

```kotlin
if (expr.methodExpression.referenceName in chainTerminalMethodNames && !isExistsConditionCall(expr)) return false
```

**未验证**：修 `:600` 后 `var` 形态是否经工厂路径自然恢复、是否仍需此处单独放行（见 §七 前提 2）。

### 3.3 不受影响路径（实施时勿动，作为回归闸门）

| 路径 | 为何不受影响 |
| --- | --- |
| `service.exists(wrapper)`（Q14-②） | 走外层消费方法判定 `EXISTS_CONSUMER`，不经终结点剔除 |
| 无参 `exists()` 终结点（Q14-③） | `isExistsConditionCall` 为 false → 仍被剔除并触发 `SELECT EXISTS` 包裹 |
| 其余终结点（one/list/count/page/update/remove/getObj…） | 无同名 wrapper 条件方法，无歧义 |
| `notExists` | 不在 `chainTerminalMethodNames`，现状即正确（E 形态实测为证） |

### 3.4 明确不做

- **不给 `SELECT EXISTS` 包裹加诊断提示**：修根因后该分支不再被误触发；真终结点场景的包裹是正确行为，加提示属噪音。
- **不重排 `chainTerminalMethodNames` 名单本体**：名单是消费语义的单一来源，移除 `exists` 会同时破坏 Q14-③。
- **不动 `KtWrapperDetector`**（D94=A），只登记。

---

## 四、改动清单

| # | 文件 | 改动 |
| --- | --- | --- |
| 1 | `src/main/kotlin/.../psi/WrapperLocator.kt` | 新增 `isExistsConditionCall`；终结点判定（`:600`）与 `initializerIsWrapperChainWithoutTerminal`（`:370`）改用之（D93 选项） |
| 2 | `src/test/kotlin/.../sql/spec/SqlPreviewSpecMatrixTest.kt` | 金样矩阵补 A 形态新用例（`Wrappers.<T>lambdaQuery()....exists(sql)` 裸作实参）＋ 反向用例（无参 `exists()` 终结点仍包裹） |
| 3 | `src/test/kotlin/.../ui/ConsumerMethodsCoverageTest.kt` | 补 A 形态的 `exists`/`notExists` 对照例（后者钉住不对称是有意为之） |
| 4 | `docs/memory-archive/bug-fixes/project-exists-bugfix.md` | 状态勘正：交付后改注「已修（`<commit>`）」 |
| 5 | `docs/开发计划/20260814-2234-exists条件误判为链式终结点-开发计划.md` | 头部状态改「已由 T-ExistsChainTerminal 承接」 |
| 6 | `CHANGELOG.md` + `docs/变更记录.md` | 随实施同步（[Unreleased] 追加 + 总册条目） |
| 7 | 本计划 §十 | 实施记录回写（读数 / 实施偏差） |

> 说明：**无 i18n 变更**（不新增/删除任何 bundle 键），故不设 §五 章节。

---

## 五、测试与验证

1. **定向（金样矩阵，主闸门）**：新增用例断言 A 形态产出普通 SELECT 且含 `AND EXISTS (SELECT 1 FROM station WHERE station.id = device.id)`；**期望 SQL 手写快照，禁用实际输出回填**（矩阵失去回归能力的老坑）。
2. **反向用例（钉住 Q14-③ 不回退）**：无参 `exists()` 终结点仍产出 `SELECT EXISTS (...)` 包裹；`service.exists(wrapper)` 消费形态不变。
3. **既有回归闸门**：`SqlPreviewSpecMatrixTest` 全量（现 91 例）＋ `ConsumerMethodsCoverageTest`（含 T-TestGuard-05 两例 exists 同名双语义回归）＋ `SqlPreviewServiceTest` 消费族。
4. **反向验证（守卫纪律，必做）**：临时把 `isExistsConditionCall` 改成恒 false（还原为现状名单判定）→ 新用例**必红**，贴两次读数；探针跑完 grep 复查零残留。
5. **不对称钉桩**：A 形态 + `notExists` 的产物断言保持为「含 `AND NOT EXISTS (...)`」，防后人顺手把 `notExists` 也塞进终结点名单。
6. **冒烟**：`testFast`（纯逻辑快桶）。
7. **门禁口径**：只动 `psi` 定位层 + 测试，**不触发** `verifyGate` / `verifyPlugin` / `buildPlugin`（无 EP、无依赖版本、无平台 API 面变化）。
8. **真机走查（V120）**：在 demo 工程写一条 A 形态裸工厂链（含 `exists` 带参 + 后续 `eq`/`orderByDesc`），确认预览面板显示普通 SELECT 且条件在位；另确认变量/分步两种写法无回归。

---

## 六、待验证前提（实施者先证伪再动手）

1. **「无参 = 终结点」判据的 API 权威性**：本地缓存只有 `mybatis-plus-core:3.5.17`，javap 实测 `AbstractWrapper` 侧**只有** `exists(boolean, String, Object...)` / `notExists(boolean, String, Object...)`（无单参重载）；无参 `exists()` 终结点应在 `mybatis-plus-extension` 的 `ChainQuery`（本地无该 jar）。**需补 `mybatis-plus-extension` 依赖或直取 jar 核实**：若某版本存在带参的链终结点 `exists(...)`，本守卫判据需改（改为「无参 ∧ 接收者为 Chain 类型」或按调用点上下文判）。
2. **`initializerIsWrapperChainWithoutTerminal`（`:370`）是否需同步改**：修 `:600` 后，`var w = Wrappers.<T>lambdaQuery()....exists(sql)` 是经工厂路径自然恢复，还是仍因该门被拒而落空——**未验证**（探针只覆盖了 `LambdaQueryWrapper<X> w = ...` 显式类型声明形态）。实施时先写窄例实测再决定改不改。
3. **A 形态是否即 FR-0012 用户的形态**：反馈只给一行示意、无上下文，**定不下来**；实际影响回复口径（若对方是变量/分步写法，则应答「已支持，请确认版本」）。看其截图或回访。
4. **单参 `exists(String)` 的出处**：FR-0012 与仓内金样矩阵 S18/S22 都写单参形态，但 MP classic 3.5.17 core 侧无此重载（见前提 1）。出处可能是 MPJ 或更高版 MP。**不影响本次修复**（解析器按实参形状处理），但影响「用户代码能否编译」的判断与对外文档表述。
5. **并行会话在途文件**：实施前先 `git status --porcelain` 核对。本轮评估期间已撞到一次「另一会话正在写 `SqlToJavaPanel.kt`」导致的半写编译失败（`Unresolved reference` 假报），复跑即绿——实施阶段同样先核对、只碰本任务文件。

---

## 七、决策记录（待拍板）

- **D93 守卫落点 = 推荐 B（单一来源判定函数，两处共用）**。
  - A（备选）：只在 `buildChainFromFactory`（`:600`）加内联条件，最小改动 5 行，但 `:370` 留同名单判据，属「同一语义两处各判一遍」，下次名单增改易再漂移。
  - **B（推荐）**：抽 `isExistsConditionCall(call)` 单一来源，`:600` 与 `:370` 共用。代价约 +8 行，收益=同类撞名风险收敛到一处（与档案 `bug-fixes/project-exists-bugfix.md` 记录的「方法名集合驱动的分派易误判」同属一类坑的治理）。
- **D94 Kotlin 侧（`KtWrapperDetector`）= 推荐 A（本次只登记不动）**。
  - **A（推荐）**：`.kt` 路径整体是 MVP（`table` 恒 null → 预览必然降级），单点修 `exists` 无用户可见收益；待「Kotlin 预览整体形态」立项时连名单一起重做。
  - B：本次把 `exists` 移出终结点名单 + 补进操作白名单（约 3 行）。风险=在 table 恒 null 的路径上引入新解析分支，测试只能断言模型层、无法断言用户可见产物，属「修了也验不出」的形态。
  - 附带上报（不属本任务）：README:79 宣称「Java 与 Kotlin 文件均支持」，与 Kotlin 路径的 MVP 现状存在口径差，建议单独登记。

---

## 八、实施步骤（确认后执行）

1. **占号与在途核对**：实测 O-120/R66/D93~D94/V120 未被占用（D88 有意留空，避开 O-118/R64 撞号区）；`git status --porcelain` 列在途文件，实施只碰本任务文件。
2. **前提 2 窄例实测**：先写 `var` 形态窄例确认 `:370` 是否需同步改；再按 D93 落 `isExistsConditionCall` + 两处接线。
3. **金样矩阵补例**（A 形态 + 无参终结点反向例 + `notExists` 钉桩例）。
4. **反向验证**：`isExistsConditionCall` 改恒 false → 新用例必红（贴读数）→ 还原 → grep 复查零残留。
5. **定向回归**：`SqlPreviewSpecMatrixTest` + `ConsumerMethodsCoverageTest` + `SqlPreviewServiceTest`。
6. **冒烟**：`testFast`（注意：若用户真机在跑 runIde 沙盒 + demo 应用 + agent，按项目记忆 R17 带降足迹参数 `-PtestHeapSize=384m -PtestTieredStop=true -PtestCiCount=2 -PtestGcThreads=2`）。
7. **达标即提交**：修复笔与测试笔可合一；push master；CHANGELOG + 变更记录 + 两处旧账状态勘正 + 本计划 §十回写。
8. **V120 真机走查清单**交用户（demo 工程 A 形态一条 + 变量/分步无回归）。

---

## 九、实施记录

**提交**：`71e54b4`（分支 `t-exists-chain-terminal`，自 master `99f4af2` 切出）——修复笔含生产码、
金样矩阵新例、CHANGELOG、变更记录总册、旧计划状态勘正、归档档状态勘正。

### 9.1 待验证前提的结论（两项已关掉，一项确认保留）

| 前提 | 结论 | 依据 |
| --- | --- | --- |
| §六-1「无参=终结点」的 API 权威性 | ✅ **成立** | javap 实证：`mybatis-plus-core` 3.5.7/3.5.15/3.5.17 的 `AbstractWrapper` 只有 `exists(boolean,String,Object...)`；`mybatis-plus-extension` 3.5.17 的 `ChainQuery` 为 `public boolean exists()`（无参）。判据可安全按实参个数 |
| §六-2 `initializerIsWrapperChainWithoutTerminal` 是否需同步改 | ✅ **需要**（实测证实） | `var w = Wrappers.<Device>lambdaQuery().eq(..).exists(sql)` 修复前产出 `SELECT EXISTS (SELECT ... WHERE status = 1)`——条件丢失，与 A 形态同签名 |
| §六-4 单参 `exists(String)` 的出处 | ✅ **存在**（首轮结论「不存在」系看漏继承，已勘正） | core 的 `Join` 接口声明 `exists(String, Object...)`（零值变参即单参调用；`AbstractWrapper implements Join`），MPJ 自有的 `Join` 接口同款。首轮只 javap 了 `AbstractWrapper` **自身声明**、未看父接口 → 误判为「不存在」。**修复不受影响**：判据仍是实参个数（core 侧无任何无参 `exists`，无参只在 extension 的 `ChainQuery`） |

### 9.2 读数（先红 → 后绿 → 反向验证）

| 轮次 | 范围 | 读数 |
| --- | --- | --- |
| 先红基线（守卫不存在） | 矩阵 95 例 | **4 失败**：S91/S93/S94 为条件丢失 + 整链误包 `SELECT EXISTS`（真缺陷）；S92 仅格式化换行差异（期望串写错，非缺陷） |
| 修后 | 矩阵 96 例 | **96/0/0** |
| 反向验证（`isExistsConditionCall` 改恒 false，带 `RED PROBE` 注记） | 矩阵 96 例 | **4 失败**，红点精确落 S91/S93/S94/S95；S92（notExists）保绿 → 判别力定向，非整片炸 |
| 还原后定向回归 | 七类 | **261/0**：矩阵 96、`WrapperAnchorDetectorTest` 41、`WrapperAnchorDetectorNegativeTest` 5、`WrapperLocatorTest` 31、`ConsumerMethodsCoverageTest` 33、`SqlPreviewServiceTest` 49、`XbatisConverterTest` 6 |
| testFast 冒烟 | 266 类 | **3406/0/6 跳过**（2m05s） |
| **归并 master 后权威全量**（`testShards`，树 = master `9043743` 同树 `3fe3aef`） | 5 片 + testExternal | **SHARD TOTAL 6257 / 0 失败 / 0 错误 / 9 跳过**（1295/1274/1272/1106/1270 + 40；parity 544 计划类全集均有结果 XML；3m44s。跑时 Docker 未运行，与既有 9 跳过基线一致） |

`RED PROBE` 注记已还原，`grep -rn "RED PROBE" src/` 零命中；两个临时探针文件（`TmpExistsProbeTest` /
`TmpVarProbeTest`）已删，`git status` 无未跟踪残留。

### 9.3 实施偏差与发现

1. **§四 清单第 3 项折入矩阵**：原计划在 `ConsumerMethodsCoverageTest` 另补 A 形态 exists/notExists 对照例，
   实际折入金样矩阵 S91/S92——同一形态两处维护不划算，且矩阵已是 wrapper→SQL 语义的权威家。
2. **首版期望串未计格式化换行**：顶层后续条件由 formatter 换行并缩进 4（首个条件随 `WHERE`），
   首版写成单行致 S92 假红。按 formatter 规则修正，**未用实际输出回填**（矩阵回归能力的红线）。
3. **D93 选项 B 的必要性被实测证实**：`var` 形态（前提 2）与 A 形态是同一判据的两个消费点，
   单点收口后两处同时转正；若只改 `:600`，`var` 形态会经工厂路径「碰巧」恢复，但 `:370` 仍留错误判据。
4. **顺带发现（不属本任务，已登记）**：①Kotlin 侧 `KtWrapperDetector` 同一名单问题按 D94=A 不现修；
   ②README 宣称「Java 与 Kotlin 文件均支持」与 Kotlin 路径 MVP 现状（table 恒 null）存在口径差，建议单独登记。
5. **自身勘误（javap 只看声明不看继承）**：首轮核实前提 4 时只 javap 了 `AbstractWrapper` 自身声明的方法，
   据此得出「单参 `exists(String)` 在 MP classic 与 MPJ 均不存在、FR-0012 那行代码编译不过」——**结论错误**。
   补查父接口后：core 的 `Join` 声明 `exists(String, Object...)`、MPJ 自有 `Join` 同款，单参形态**真实可编译**
   （demo 工程 `mybatis-plus-demo` 里早就有该写法且能编译，是最直接的旁证）。
   教训：`javap` 默认只列**本类声明**，判定「某 API 有无某重载」必须把继承链上的接口一并扫（或直接扫 jar 内全部类）。
   已勘正：本表 §9.1 前提 4 行、金样矩阵 S18/S94 注记、变更记录总册条目、归档档。**修复本身不受影响**。

### 9.4 遗留

- **V120 真机走查**（交用户）：demo 工程 `mybatis-plus-demo` 的
  `WrapperFactoryCoverageServiceImpl#listByBareFactoryChainExists`（端点
  `GET /config/factory/bareChainExists?username=admin`，demo 仓 `38e6361`）——四种写法应产出同一份 SQL，
  逐形态比对预览；变量 / 分步 / `new` 三种写法无回归。
- ~~分支归并~~ **已办**：`t-exists-chain-terminal` 以 `--no-ff` 并入 master（`9043743`，用户令），
  改动面与 master 侧 T-MapResultVo v5 三笔零重叠；分支、远端分支、工作树（`-existsct`）均已清除，
  合并后权威全量读数见 §9.2 末行。
- **本任务唯一未验证面**：真机（沙盒/用户 IDE）走查未做——headless 金样矩阵已覆盖四形态与反向验证，
  真机只差「预览面板实际渲染」这一层观感确认。
