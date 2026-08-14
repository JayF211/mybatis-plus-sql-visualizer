# gutter 双预览图标与分步 selectAll 锚点——开发计划（T-M23.11）

> 本文档是 **MyBatis-Plus SQL Visualizer** 的 T-M23.11 增量计划：2026-08-14 用户实测——
> `selectJoinOne(VO.class, new MPJLambdaWrapper<>().selectAll(Entity.class)...)` 出现**两个预览 SQL 图标**
> （new 行 + selectAll 行）；分步形态（`w.selectAll(...)`）同样双图标。根因：`selectAll` 同时收录于
> 批量操作注册表（T-M22-04 ActiveRecord `selectAll()`）与 MPJ 链方法（`.selectAll(Class)`）。
> **T-M23.12（追加）**：`wrapper.and(o -> ...)` 嵌套条件 lambda 参数被场景 B 误标为锚点（真实 IDE
> 类型推断为 LambdaQueryWrapper，用户代码出现三图标）——见 §2.3。
>
> 当前基线：T-M23.10 已交付（503 测试全绿）。任务编号源：本文档（T-M23.11/T-M23.12，延续 M23 系列增量）。
>
> 交付节奏：① 内联链形态修复已交付（506 测试全绿，见 docs/变更记录.md T-M23.11）；② 分步形态增强已交付
> （506 测试全绿，T-M23.11 分步增强）；③ **lambda 参数锚点误标（T-M23.12，§2.3）已交付（507 测试全绿）**。

---

## 1. 问题与动机（用户反馈，证据驱动）

**用户复现（内联形态）**：

```java
CarStationPriceTaskRecordVO vo = taskRecordService.selectJoinOne(
        CarStationPriceTaskRecordVO.class,
        new MPJLambdaWrapper<CarStationPriceTaskRecordEntity>()
                .selectAll(CarStationPriceTaskRecordEntity.class)   // ← 双图标之一
                ...);
```

**根因（代码证据）**：`WrapperAnchorDetector`（`psi/WrapperAnchorDetector.kt`）——
- new 关键字命中场景 A（wrapper 构造锚点）→ 图标 1（正确）；
- `.selectAll(Class)` 方法名命中场景 D（批量操作锚点：`referenceName in batchOperationMethodNames` + 实参非空），
  场景 D **未排除 wrapper 链上的同名调用** → selectAll 行被误标 → 图标 2（误判）；
- 撞名来源：`BatchOperationConverter.BATCH_OPERATIONS` 收录 `selectAll`（T-M22-04 ActiveRecord 形态），
  与 MPJ 链方法 `selectAll(Class)` 同名；此前无「wrapper 链上的批量同名方法」测试覆盖。

**影响**：两个图标点击预览同一 SQL（`WrapperLocator` 定位会回溯链首），纯视觉冗余，无功能错误。

**约束（性能红线，M10 §2.4 硬性）**：锚点判定器只允许 O(1) 纯语法判断——仅访问 `element.parent` 与
`type.canonicalText` / `referenceName` 文本匹配，**禁止 PSI resolve、PsiTreeUtil 遍历、文件级扫描**；
负例（非 Wrapper 元素）必须零成本返回 null。**修复不得造成任何既有锚点消失**（用户明确要求）。

---

## 2. 设计方案（T-M23.11）

### 2.1 内联链形态（已交付，506 测试全绿）

场景 D 加 **wrapper 链守卫**：`initializerHeadIsWrapper(call)`（复用场景 A/B 的 O(1) 语法级链首回溯——
链首是 wrapper 构造 / Wrappers 工厂 → 该调用属于 wrapper 链，不标批量锚点，链锚点由 new/变量/工厂承载）。
批量形态（`Db.selectAll(Class)` / `service.updateBatchById(list)` 链首非 wrapper 构造）不受影响。

### 2.2 分步形态增强（本次实施）

分步写法 `MPJLambdaWrapper w = new ...; w.selectAll(...)`：qualifier 是变量引用，`initializerHeadIsWrapper`
回溯不到 new（变量引用非方法调用）→ 需判定变量是否 wrapper。

- **撞名白名单**：批量注册表 ∩ 链方法名的交集（目前仅 `selectAll`；将来新增撞名时扩展）——
  只对白名单方法做变量判定，其余批量方法零侵入；
- **判定**：qualifier `resolve()` → `PsiVariable` → `WrapperLocator.isWrapperVariable(variable)`
  （M19.5 起 internal 复用，O(1) 纯语法判定——类型文本 + initializer 链首回溯，无二次 resolve）；
  wrapper 变量上的调用 → 不标批量锚点（链锚点已由场景 B 变量行承载）；
- **性能账**（用户质询）：resolve 仅发生在「白名单方法名调用点」（每文件 0~1 个），且仅 qualifier
  为引用时；局部变量 resolve 为作用域内查找（微秒级）；普通标识符负例仍零 resolve（红线语义不变）；
  跨文件字段引用（罕见）首次触发 PSI 文件加载一次，有缓存；
- **不误杀保证**：`Db.selectAll(Class)` resolve 到 PsiClass（非 PsiVariable）→ 不拦截仍标；
  `service.updateBatchById` 不在白名单 → 不 resolve 仍标；分步非 wrapper 变量上不出现 `selectAll`
  语法（方法不存在）→ 无新误判面。

### 2.3 lambda 参数锚点误标（T-M23.12，已交付，507 测试全绿）

**用户复现**：`wrapper.and(o -> ...).and(o -> ...)` 出现**三个图标**（wrapper 变量行 + 两个 lambda 参数行）。

**根因**：`PsiParameter` 是 `PsiVariable` 子类，场景 B 对「类型是 wrapper」的变量名标锚点；MP 嵌套条件
`and(Consumer<...>)` 的 **lambda 参数类型即 `LambdaQueryWrapper<实体>`**——真实 IDE 类型推断使每个
lambda 参数 o 命中场景 B。测试环境 LightPlatform 无法推断 lambda 参数类型（`o.type` 为空）→ 该缺陷
为**测试盲区**（环境类型解析差异）。

**修复**：场景 B 加 O(1) 语法级守卫——`variable is PsiParameter && (variable.parent?.parent) is PsiLambdaExpression`
→ 返回 null（lambda 参数不是独立 wrapper 变量）；方法参数（父链 PsiMethod）不受影响。

**测试**：显式类型 lambda 参数（`wrapper.and((LambdaQueryWrapper<X> o) -> ...)`，LightPlatform 可解析
类型文本）→ 断言不标锚点 + wrapper 变量行锚点保留（图标不丢）。`clean test` 507 全绿。

---

## 3. 实施步骤与验收

1. `WrapperAnchorDetector` 场景 D：白名单常量 `BATCH_CHAIN_COLLISION_NAMES`（= setOf("selectAll")）+ 变量判定守卫（§2.2）；
2. 测试更新/新增：
   - `testStepByStepSelectAllOnWrapperVariableNotBatchAnchor`（原「保持批量锚点」断言反转）：
     分步 `w.selectAll(Class)` → null，且**变量行场景 B 仍为锚点**（图标不丢）；
   - `testBatchSelectAllCallStillAnchor`（已交付，保留）：`Db.selectAll(Class)` / `service.updateBatchById` 仍标；
   - `testInlineMpjSelectAllOnWrapperChainNotBatchAnchor`（已交付，保留）：内联形态不标 + new 仍标；
3. `clean test` 全量绿（506 → 507）；
4. 同步 docs/变更记录.md 总册 + docs/MEMORY.md 索引。
