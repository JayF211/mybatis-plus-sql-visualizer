---
name: native-diagram-relation-provider-null-ref
description: Database 原生图虚拟外键 EP 的 refTable=null 契约——两参重载恒定传 null 表示「不过滤、返回该表全部
  FK」，误读成「无关系」会让 B 路径连线全丢（243/253/262.1/262.3 四版本字节码同构）；附排障要点与 jar 定位
metadata:
  node_type: memory
  type: project
  originSessionId: sess_b62a2f61-8ba8-4845-9c32-1c01fd2ae8be
---

**平台契约（四版本字节码实证，2026-09-21）**：`com.intellij.database.model.ModelRelationManager` 的 **两参重载** `getForeignKeys(Project, DasTable)` 会把 `refTable` 传成 **`null`** 调 `ModelRelationProvider.getForeignKeys(project, table, refTable)`（`lambda$getForeignKeys$1` 偏移 3 = `aconst_null`，243.28141.41 / 253.5 / 262.1 / 262.3 四份字节码逐字节相同）。平台自己的内置实现 `ModelRelationManager$DasForeignKeys` 给出正确语义：`refTable == null ? DasUtil.getForeignKeys(table) : 过滤(isReferenceTo(fk, refTable))`，即 **null = 不做引用表过滤、返回该表全部 FK**。

**为什么是致命点**：`DbDiagramDataModel.calculateEdges`（唯一的建边入口，`refreshDataModel` 内调）在 `VIRTUAL_FOREIGN_KEYS` 分类启用后走的正是**两参重载**（`isCategoryEnabled` 为真 → 两参；为假 → `DasUtil.getForeignKeys`），拿到 FK 后以 `nodeMap.get(asDbElement(fk.getRefTable()))` 查引用表节点，查不到就丢边。我方 `JoinVirtualFkProvider` 曾把 `refTable == null` 当成「无关系」直接返回空 → B 路径（Database 原生显示图）节点/注释/类型全正常、**连线恒空**。症状极具迷惑性：配数据源后「有注释没线」，不配数据源走 A 路径「有线没注释」，两条路径缺口互补。

**修复要点（N6，计划 `docs/开发计划/20260921-1637-N6-JOIN图原生路径连线缺失-开发计划.md`）**：空 refTable 分支要返回「该表作为 FK child 的全部登记」，且 `getRefTable()` 必须是**图里那个 DbTable 实例**（`asDbElement` 对已是 `DbTable` 的对象 `tryCast` 原样返回；`addElementInner` 字节码实证 `myTablesAddedByUser.put(name, (DbTable) element)` 存的就是传入实例，`calculateElementToNodeMap` 直接拿它当 nodeMap 键）。实例来源=登记时手上的 `located: Map<String, DbElement>`，但**不要塞进 `JoinFkRegistry.Entry`**——该注册表是「纯 JVM 零平台依赖」，`JoinFkRegistryTest` 在无 database jar 的 `test` 任务下跑，塞平台类型会污染类加载边界。

**实施状态（2026-09-21，分支 `n6-join-fk-nullref` 提交 `f16017e`，工作树 `../mybatis-plus-sql-visualizer-n6`）**：已实施并验证——语义下沉到 `JoinFkRegistry.selectForCall`（纯函数，provider 内不再有 null 判断）、新增 `JoinFkRefTables` 持有实例、`registerJoinForeignKeys` 成对填充 + 表名小写归一、`LocateCounts` 降级诊断出口。定向 14 例绿；反向验证两路各自命中（还原 `selectForCall` → 2 例红；还原 provider 早退行 → 源码级守卫红）；全量 `test` 491 类/5588 例 0 失败；`testDatabase` 2 例失败已用 HEAD 基线证为既有环境性失败（`DbDriverPipelineTest` 驱动制品索引）。**未闭环：本环境数据源模型为空（`roots=0`）故 B 路径仍未被真跑，画布级须真机走查；分支未 push。**

**排障要点（本轮最大障因）**：日志打在 null 守卫之后，导致 idea.log 里只有三参调用（来自 `getExportedKeys`→`computeExported` 的成对回问）显示 `hits=1`，看起来一片健康，而建边链一次都没留痕。**判断 B 路径连线问题必须先确认日志覆盖了建边入口**；`hits=1` 只反证「登记是好的」，不能证明边已建。

**教训（与 [[pitfall-guard-covers-audited-targets]]、[[project-test-conventions]] 同族）**：`HeavyNativeDatabaseDiagramJoinChainTest` 写了 `edges.isNotEmpty()` 断言却三级软返回（introspect 失败 / `component == null` / 编辑器未挂接 → `println + return` 记绿），XML `system-out` 实证本环境走 `component == null` 分支，**五组断言从未执行**——「9 例 JUnit 绿」与「B 路径已验证」是两件事，类注释已自陈但没人当门禁用。修 B 路径问题先看该用例的 XML 有没有 SKIP 行。

**jar 定位（2026-09-21 实测）**：`ModelRelationManager` 在 `DatabaseTools/lib/modules/intellij.database.core.impl.jar`（253/262）；`DbDiagramDataModel` 在 `DatabaseTools/lib/database-plugin.jar`（243/262 均在）。**Git Bash 的 `unzip -l` 在这些大 jar 上会静默漏报**（同一路径 Python `zipfile` 能查到、`unzip | grep` 查不到），扫 jar 一律用 Python zipfile，见 [[reference-platform-artifact-keymap-lookup]]。

**Why:** 「null = 无关系」是直觉误读，代价是整条 B 路径的连线能力从未生效；四版本字节码同构说明该契约不会随版本漂走。
**How to apply:** 动 `JoinVirtualFkProvider` 或任何 `ModelRelationProvider` 实现前，先确认两参重载的 null 语义；跨版本结论用 Python zipfile + javap 复跑一次再落笔。
