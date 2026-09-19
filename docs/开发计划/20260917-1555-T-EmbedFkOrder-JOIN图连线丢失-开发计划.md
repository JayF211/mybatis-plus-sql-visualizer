# T-EmbedFkOrder JOIN 图连线丢失（虚拟外键登记被旧图清理抹掉）— 开发计划

- 日期：2026-09-17 15:55
- 任务编号：T-EmbedFkOrder
- 来源：用户报告（日常 IDE 2026.2 真机）——同样两条 SQL：第二条 JOIN 图有连线，第一条没有
- 定性：**代码级根因**（读码即定），非竞态、非环境

---

## 一、现象与分流

| SQL | 图内容 | 走的渲染路径 | 连线 |
|---|---|---|---|
| ① `joinRightAlias`（`leftJoin(Class,"st",左列,"t",右列)`） | `car_device_info` / `station_info` 真实表（DB 注释、`int(11)` 类型） | **Database 原生图（B 路径）** | **无**（真机截图实证） |
| ② `joinExtAndSubTable`（含子表子查询 `Subquery (tt)`） | 带别名标题的节点（`station_info (t1)` / `Subquery (tt)`） | JOIN wrapper 路径（A 路径，因 B 路径前置不满足而回落） | 有 |

分流判据是既有的 B 路径前置条件：**模型里所有表都能在数据源中定位**。②含子查询伪表 → 不满足 → 回落 A 路径；
①两张都是真实表 → B 路径接手。

两条路径的连线来源**不同**：
- A 路径：连线直接来自 `DiagramModel.relationships`（含 `columnPairs`）；
- B 路径：连线来自 **`JoinFkRegistry` 虚拟外键**——由 `registerJoinForeignKeys(model, located)` 登记，
  再由 `JoinVirtualFkProvider`（`com.intellij.database.modelRelationProvider` EP）在建边时回查。

⇒ ①无连线 = B 路径的虚拟外键登记**没有存活到建边时刻**。

## 二、根因（代码级）

`SqlPreviewPanel.showJoinDiagram` 的语句顺序：

```kotlin
val native = if (nativeEnabled) {
    (db.buildTabComponent(...) ?: join.buildTabComponent(...))   // ① 建新图：内部 registerJoinForeignKeys 登记 FK
} else null
clearNativeDiagram()                                            // ② 清理旧图：dispose → JoinFkRegistry.clear()
```

而 `NativeDatabaseDiagramSupport.disposeNativeEditor` 在 `finally` 里无条件 `JoinFkRegistry.clear()`。
于是**②把①刚登记的虚拟外键整批抹掉** → Database 原生图只剩节点，没有连线。

为什么①在首次预览（面板无旧图）时正常、②"另一张"始终正常：
- 首次预览 `currentNativeDiagram == null` → ②空转 → FK 存活 → 有连线；
- 面板复用（换 SQL 再预览）→ ②真的清理 → 新登记被抹 → 无连线（用户看到的正是这个）；
- 而走 A 路径的图不读注册表 → 不受影响（用户另一条 SQL 恰是这种）。

## 三、方案

| # | 项 | 改动 |
|---|----|------|
| F1 | 顺序修正 | `showJoinDiagram` 把 `clearNativeDiagram()` **前移到建新图之前**——旧图清理先发生，新登记后发生，互不干扰 |
| F2 | 职责修正 | `NativeDatabaseDiagramSupport.disposeNativeEditor` **不再清空注册表**（dispose 只负责释放编辑器；清空会误伤当前图的登记）；改为在明确的语义点清理：`registerJoinForeignKeys` 登记前已自带 clear（新图恰好只留自己的 FK）、无 JOIN 移除标签页与面板 `clear()` 时显式 `clearRegisteredFk()` |
| F3 | 守卫 | `testDatabase` 的 `HeavyNativeDatabaseDiagramJoinChainTest`：①改「dispose 后再显式清理才为空」；②新增回归断言——建图登记完成后，清理一个**无关旧组件**不得抹掉当前登记（正是本次故障形态）。`JoinFkRegistryTest`（纯 JVM）覆盖注册表语义不变 |

## 四、验收标准

1. 同一预览面板**连续**预览两条 SQL（先②后①或反之）→ ①（Database 原生图）**都有连线**，标签显示 ON 条件列对。
2. 关闭预览/切到无 JOIN 的语句 → 注册表清空（无跨图残留，`disposeNativeEditor` 语义变更不引入泄漏）。
3. 全量 `test` 绿 + `testDatabase` 绿（DB 链双跑口径）。

## 五、真机走查清单（留人工）

1. 打开预览 → JOIN 图（第一条 SQL）：应见 `station_info → car_device_info` 连线（虚拟外键名 `SQLV_JOIN_*`）。
2. 在**同一面板**里先预览第二条 SQL（含子查询）再预览第一条：第一条仍要有连线（本次故障的判定场景）。
3. 切到无 JOIN 的语句：JOIN 图标签页消失；再切回 JOIN 语句：连线仍正常。

## 六、风险

| 风险 | 说明 | 缓解 |
|---|---|---|
| 注册表残留 | F2 移除 dispose 内的清理后，若无人显式清理可能跨图残留 | 登记前 clear（既有）+ 无 JOIN/面板 clear 两处显式清理；走查项 3 覆盖 |
| A 路径误伤 | A 路径不读注册表，清理只影响 B 路径 | 显式清理点均为「整图卸载」时机，A/B 通用 |
| 顺序修正引入闪烁 | 先清旧图再建新图，短暂空白 | 二者同帧内完成（同一 EDT 任务），真机走查确认无可见闪烁 |
