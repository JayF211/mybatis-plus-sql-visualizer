---
name: project-idea-diagram-framework
description: JOIN 图原生框架全历程——T-Dgm 接入反射桥 / T-NativeUI 渲染对齐 Database / T-Embed B 路径内嵌真实
  DbTable+虚拟外键 JOIN 线 + 字段/注释真机已验 + 连线=VIRTUAL_FOREIGN_KEYS 分类 / T-Embed-09 表头注释 DB 优先 / T-Embed-10 真机一~五轮（getDasObject 不存在、refreshDataModel 节点生成、setCategoryEnabled 开连线）
metadata:
  node_type: memory
  type: project
  originSessionId: sess_b705387c-fae3-459d-8063-cc245b9527d7
---

JOIN 图换用 IDEA 原生图框架（[[project-f42-join-diagram-render]] 的替代渲染层）——**2026-09-03 已实施**，分支 `feature/join-diagram-native-framework`（worktree `../mybatis-plus-sql-visualizer-joinfw`），计划文档 `docs/开发计划/20260903-0100-JOIN图换用IDEA原生图框架-开发计划.md`。

**调研结论（反编译 ideaIU-2024.3.7.1，字节码三重核实）**：
- 渲染内核（yFiles fork，`com.intellij.openapi.graph.*`）在平台核心 `lib/app.jar`，全 IDE 可用。
- 图框架 API（`com.intellij.diagram.*`）在捆绑插件 `com.intellij.diagram`（"Diagrams"，`plugins/uml/lib/uml-support.jar`），Ultimate 捆绑；EP：`com.intellij.diagram.Provider`。
- 接入模式参照 DatabaseTools 的 `DbDiagramProvider`（表关系图）与 `QueryPlanProvider`（EXPLAIN 计划图，纯数据模型最接近我们场景）。
- 官方立场：无正式 SDK 文档，JetBrains 员工论坛确认第三方可用（DiagramProvider Javadoc + jb.gg/ipe 范例）。

**实施要点（编译期实证的 API 事实）**：
- `bundledPlugin("com.intellij.diagram")` + `plugin.xml` 可选依赖 `diagram-optional.xml`（与 database-optional.xml 同模式）。
- **`UmlEditorProvider` 是 Kotlin internal**（跨模块编译期不可直引，字节码层面 public）→ `Class.forName("com.intellij.uml.UmlEditorProvider")` 实例化后转 `FileEditorProvider` 接口调 `createEditor(project, file)`（feedback-settings-editor-internal-dep 同款反射桥）。
- `DiagramRelationshipInfoAdapter.getName()` 也是 internal——跨模块只能读 `getLabel()`（3 参 ctor 的 label 落 upperCenterLabel）。
- 平台常量名：`SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES / GRAYED_ATTRIBUTES`（无 BOLD_/GRAY_）；图标 `AllIcons.Nodes.DataTable / DataColumn`（无 Table/Column/PrimaryKey）。nullability：`DiagramElementManager` 回调参数多为平台 @NotNull；节点覆写用 `fun getIdentifyingElement()` 而非 val。
- 打开链路：`UmlVirtualFileSystem().findFileByPath("<providerId>/<fqn>")`（分隔符 `/`=47）→ `setProject/setInitialized/setPresentableName` → embed 走 createEditor、独立标签页走 `FileEditorManager.openFile`。
- 可用性判定：`DiagramProvider.findByID(ID) != null`（EP 已挂载 = Diagrams 存在且启用），**不要用 PluginManagerCore.getPlugin（@Internal）+ isEnabled（deprecated）**，verifyPlugin 会报。
- 快照模式：打开前把 `DiagramModel` 登记进 `JoinDiagramSnapshotRegistry`（id 即 FQN，禁止含 `.` 防误判 .uml 格式），Provider/DataModel 凭 id 回查。

**关键修复（2026-09-03 真机反复踩坑最终根因，两条铁律）**：
1. **createBuilder project 丢失**：`DiagramBuilderFactory.createBuilder` 内部经 `VirtualFileManager` 重查文件，必须与创建时用**同一个注册单例 UmlVirtualFileSystem**（共享 cache）。用 `new UmlVirtualFileSystem().findFileByPath(...)` 新建独立实例 → 与框架重查所得非同一对象 → project 丢失 → `Cannot create DiagramBuilder: Can't find project by file: LightVirtualFile`。**正解**：`VirtualFileManager.getInstance().findFileByUrl("uml://<providerId>/<fqn>")` + `DiagramDataKeys.ORIGINAL_ELEMENT` userData，对齐 ShowDiagramBase.initializeDiagramVirtualFile。
2. **字段列表不显示（T-NativeUI-02 最终根因与正解）**：任何 `DiagramNodeContentManager`（含 DiagramCategory）会被框架按持久化状态（DiagramContentSettings/DiagramState）过滤节点内容项，新图默认空状态把列全过滤 → 节点卡只有标题无字段。根因字节码实证：`AbstractDiagramNodeContentManager.myEnabledCategories`（TreeSet）初始为空、`setDataModel` 不自动启用；`DiagramCategory` 3 参 `(Supplier, Icon, boolean)` 的布尔即 `enabledByDefault`（7 参构造第 3 位）。**阶段性正解（已用 NO_CATEGORIES 落地）→ 最终正解（T-NativeUI-02）**：直接实现 `DiagramNodeContentManager` **接口**（不继承 Abstract），默认全启用（`enabled` 集合含全部内容分类），规避持久化空状态过滤；对齐 Database 的 `DbDiagramProvider$1`（继承 Abstract 但在分类 ctor 传 enabledByDefault=true）。`createNodeContentManager()` 返回该管理器，`getNodeItems` 直返 `ColumnEntry[]`，getItemName/getItemType（3 参）渲染 列名+类型+字段注释。

**验证状态**：编译 + verifyPlugin（IU-243/253/262）通过；适配层 13 + 场景 13 纯单测 + Heavy 真实链路 3 用例全绿；已合并 master（merge 1277117 + 后续修复 115a478）。T-Dgm-05 视觉验收按用户指示改为测试覆盖真实场景：HeavyPlatformTestCase（真实 Project + Diagrams 插件加载）无头跑通全链；场景单测以演示项目真实 Wrapper 写法为蓝本。真实契约澄清：MAX_COLUMNS 裁剪仅作用主表，JOIN 表列=被引用列。

**遗留**：① `NativeJoinDiagramSupport.openInEditorTab`（独立标签页）API 就绪未挂 UI。② 画布填充为异步 runAsync，Heavy 测试无法同步等待画布像素（EDT 死锁），画布真实可见性需 runIde 最终确认。

**已知限制**：画布填充依赖框架异步 `queryUpdate().runAsync()`（OLD_EDT_BASED 线程模型），
Heavy 测试运行在 EDT 上无法同步等待该 future（会死锁），故 Heavy 测试只断言数据模型节点数；
画布真实填充在 runIde 真机环境完成。若后续需断言画布像素级内容，须用 runIde + 截图。

**Why:** 接入方式与 internal 边界是踩出来的编译期事实，262 平台升级回归时直接对照。
**How to apply:** 后续轮次先做 runIde 视觉验收，再走合并/发行流程。

## T-NativeUI 渲染对齐 Database 最终方案（2026-09-03）

在原生框架基础上把渲染全对齐 Database「显示图」，插件只控「字段展示」+「连接线数据」：
- **表注释链路**：`EntityNode`/`NativeEntity` 增 `tableComment`，`JoinDiagramGenerator` 从 `stmt.table.comment`（主表）+ `join.tableInfo?.comment`（JOIN 表）透传，快照 `put` 同步（T-NativeUI-01）。
- **表头**：覆写 `getPresentableElementTitle(e, builder)` 返回 `SimpleColoredText` = 显示名(加粗) + 表注释(灰斜体 GRAYED_ITALIC)；`getElementTitle` 仍返 String（T-NativeUI-03）。
- **连接线**：`DiagramRelationshipInfoAdapter.Builder()` 全参 `setLineType/setWidth/setSourceArrow/setTargetArrow/setUpperCenterLabel`。箭头形状用 `DiagramRelationshipInfo.DELTA/DELTA_SMALL/WHITE_DELTA/NONE`；INNER→终点 DELTA、LEFT→DELTA_SMALL、RIGHT→起点 DELTA、FULL→双向 WHITE_DELTA+DASHED+width2，ON 条件走 `setUpperCenterLabel`（`getLabel()` 读它）。适配器覆写 `getStartArrow/getEndArrow`＝source/target 箭头（`getSourceArrow`委托它）（T-NativeUI-04）。
- **分类**：`JoinDiagramNodeContentManager`（object 直接实现接口）含 COLUMNS/KEY_COLUMNS/COMMENTS，`enabled` 集合默认全启用 + `setCategoryEnabled` 追踪（工具栏可真实显隐字段）；COMMENTS 不对列行过滤（`isInCategory` 返回 false，与 Database 一致注释内联到列行）。

**⚠️ isInCategory 参数位陷阱（真机踩坑，2026-09-03 复现）**：框架 `DiagramNodeContentManager.getNodeItemsAccordingToCurrentContentSettings` 当 `getContentCategories()` 非空时逐 enabled 分类 `isInCategory(node, item, category, builder)` 过滤元素；字段项可能出现在两个 Object 参数的任一槽（不同版本框架传递顺序不稳，Database D$1 用第 2 参做 `instanceof DbColumn`，勿依赖单一槽位）。**正解**：`isInCategory` 内两个参数都 `filterIsInstance<ColumnEntry>()` 取首命中，消除参数序依赖。**断言计数陷阱**：分类分组在组间插 null 分隔符（`[id, null, name]`），按列名断言而非硬计数。这也是前两次分类尝试失败（字段消失）的根因——`item` 槽拿到节点对象导致 `is ColumnEntry` 恒 false。

**Why:** 前两次分类尝试（继承 Abstract / 直接接口）字段消失，根因是持久化空状态过滤；最终正解=直接接口+默认全启用。
**How to apply:** 改字段展示/连线样式只动 `SqlJoinDiagramProvider.kt` 一个文件（Manager+ContentManager+joinRelationshipInfo）；新增线型/箭头常量先 javap `DiagramRelationshipInfo` 确认存在。

## T-Embed 立项（2026-09-04，待实施）：直接内嵌 Database 原生「显示图」组件

T-NativeUI 交付后（8d1e370）真机仍与原生 6 维度差距（同库同表截图对比实证）：
- 注释样式：原生 `字段名 /* 注释 */ 类型`（注释 `/* */` 斜体紧跟名字、类型右对齐独立列）；我们注释挂 getItemType 尾部。
- 类型：原生 DB 真实类型 `int(11)`（数据源 schema）；我们 Java 映射 `INT`。
- PK 分区：原生在**底部独立分区**（分隔线+金钥匙）——机制=框架按 enabled 分类顺序逐组 append+组间 null 分隔符，**KEY_COLUMNS 排最后即 PK 沉底**；我们 KEY_COLUMNS 在前故 PK 顶置。
- 工具栏：原生有分类切换按钮（钥匙/气泡），我们 provider 没有。
- JOIN 表字段空缺陷：真机 station_info (t1) 节点无字段行（extractJoinColumns 别名匹配疑似缺口）。

方案 B 主攻：SQL 表名→数据源 DbTable→原生 Database 图链路打开（复用反射桥），JOIN 线首选**虚拟外键**（DbDiagramProvider$1 已实证 VIRTUAL_FOREIGN_KEYS 分类）注入；方案 A 兜底=自实现 Provider 渲染细节对齐（无数据源降级路径，独立交付价值）。计划文档 `docs/开发计划/20260904-0843-JOIN图直接内嵌Database原生显示图组件-开发计划.md`。

## T-Embed 实施状态（2026-09-04，A 侧交付 / B 侧待真机轮）

**A 侧已交付**（6 维度闭环 5 个）：
- 数据源富化：`DatabaseSchemaResolver.columnMeta`（类型=`DasTypedObject.getDataType().getSpecification()` 反射，即原生 `int(11)` 同源串）+ `DiagramSchemaEnricher`（`SqlPreviewPanel.updateDiagramTab` 挂接，无数据源静默回落）。
- 原生三段式字段行：`/* 注释 */` 灰斜体内联名字侧 + 类型右对齐独立列；渲染抽顶层纯函数 `joinTitleText/joinItemNameText/joinItemTypeText`（SqlJoinDiagramProvider.kt）。
- PK 沉底：分类数组 COLUMNS→KEY_COLUMNS 顺序（框架逐分类 append+组间分隔符）。
- JOIN 表字段空修复：`extractJoinColumns` 双别名匹配（MPJ 转换器原始别名=类名小驼峰，显示别名=t1；ON 标签走 aliasMap 显示正常而列提取落空——更具迷惑性）。
- 工具栏维度澄清：嵌入组件=同一 UmlFileEditorImpl，分类切换原生也不在工具栏（设置/右键内）。

**B 侧调研实证（可直接开工）**：
- `ShowDiagramBase.createSeed(project, provider, element, additionalElements)` provider 无关；`DiagramSeed` 四元组；子类化可暴露 protected `show(seed, point, indicator)`。
- Database 图 provider id = **`DATABASE`**；`DbDiagramDataModel` 建边源=`ModelRelationManager.getForeignKeys(project, DasTable)` + `DasUtil.getForeignKeys`。
- **虚拟外键正式通道 = `ModelRelationManager.ModelRelationProvider` EP + `ExtraRelation(DasTable, DasTable)` 抽象基类**（实现 getProviderId + getColumnsRef/getRefColumns 即注入原生连线）。
- 遗留风险：`ShowDiagramBase.show` 异步扩展的表集合边界、EP 注册全局副作用——须带数据源 runIde/testDatabase 验证，无头测试无法覆盖。

**T-Embed-09 表头注释优先数据库（2026-09-04，76fbe37）**：用户真机截图反馈表头注释仍取实体类——`DiagramSchemaEnricher` 原先只富化列级、漏了 `EntityNode.tableComment`。修法：`DatabaseSchemaResolver.tableMeta(project, tableName)` 一次遍历同取表 COMMENT（`DasTable.getComment()` 反射）+ 列元数据（`columnMeta` 委托 `tableMeta().columns` 兼容）；富化抽顶层纯函数 `applyTableMeta`（metaFor 可注入供单测打正向路径——无 database 插件的测试 classpath 打不到正向，注入是唯一通道）。语义=DB COMMENT 优先、没写回落实体注释、表匹配不到整表保留。

**B 侧真机进展（2026-09-04）**：用户自行在沙盒 IDE 验证 B 路径截图（原生 Database 显示图 + yFiles + JOIN 线）——但截图注释仍为实体类注释且字段为「wrapper 涉及字段」形态，判断实际走 A 路径（B 降级或装的是发行版 jar），T-Embed-09 修复对 A 路径生效；B 路径生效时注释天然来自真实 DbTable 无需此修。

**EP 全名确认（2026-09-04，发行文档核实）**：虚拟外键 EP 全名 = `com.intellij.database.modelRelationProvider`（database-optional.xml 注册 `JoinVirtualFkProvider`，反射/编译期直调 `DasDataSource.getModel()`）；`JoinFkRegistry`（会话级注册表随图释放回收）→ B 路径接管成功打 INFO 日志（tables 清单，c882d6d 真机自证）。**T-Embed 全链（03/04/09/10）随 v2026.9.4 发行（2026-09-04）**。

**T-Embed-10 真机二轮两个根因（2026-09-04，1e478ff）**：
1. **enrich 真机全空**（用户截图类型仍是 Java 映射大写 INT=富化从未生效）：javap 实证 243 平台 `RawDataSource`/`DbDataSource` **无 `getDasObject()`**（`getModel()` 声明在父接口 `DasDataSource`）——M35 反射链第 3 步 NoSuchMethodException 被 runCatching 吞且仅 LOG.debug（idea.log 默认 INFO 看不到），躺了两个里程碑。修=反射直接 `getMethod("getModel")`（B 路径 locateDbTables 直调 ds.model 同源所以它一直成功——**同一链路直调成功≠反射可用的对照实验**）；失败日志必须升 WARN。
2. **B 路径画布空白**（方法1 纯 join 空白、方法2 含子查询走 A 路径正常——B 接管但 DbDiagramDataModel 无节点）：javap `ShowDiagramBase`（uml-support.jar com.intellij.uml.core.actions）实证 `fillDataModel(dataModel, elements)` = assertWriteAccess + 循环 `addElement`——`ORIGINAL_ELEMENT` 只是文件 userData，**不会被 DATABASE provider 转成节点**；queryUpdate 只刷新已有节点不添加。修=WriteAction 内 createBuilder 后逐表 `b.dataModel.addElement`（getDataModel() 返回 star projection，Kotlin 需 `as DiagramDataModel<Any>` 窄化）。A 路径同链能显示恰因自实现 Provider 的 createDataModel 从快照返回全部节点。

**共享工作树并行编译互锁**：兄弟会话新文件（PreviewReceiverResolver.kt 未跟踪+中间态）阻塞全量 compileKotlin——隔离验证法=临时 mv 双方文件到 /tmp→编译→立即 diff 还原（测试 task 同法）；不可长期占位，不可动其内容。

**T-Embed-10.2 三轮实证（2026-09-04，75bd1d2）**：沙盒日志（15:54）两条硬证据——① `collectFromDataSource failed: IllegalAccessException`：`ds.javaClass.getMethod("getModel")` 拿到的 Method 声明类是实现类（LocalDataSource 等，database-plugin **非导出内部包**），跨插件 classloader invoke 被拒；**铁律=跨插件反射 Method 一律从声明接口 `Class.forName(接口).getMethod` 获取**（getManagers 从接口拿所以 M35 起一直成功——同文件两种风格并存是最迷惑样本）。已全链接口化：DasDataSource.getModel / DasModel.traverser / JBIterable.toList / DasTypedObject.getDataType / DataType.getSpecification / DataSourceManager.getDataSources。② B 路径接管成功（INFO taking over）但画布仍空且**零异常**——字节码已排除：refreshDataModel 重建 = getAllTables（myTablesAddedByUser+myTableSets，isValid 过滤，addElement 登记的表幸存）→ calculateElementToNodeMap；afterDiagramIsShown 与我们 queryUpdate 链完全一致；原生额外步骤=UmlGraphBuilder.setPopupMode+registerActions+异步 initializeDataModelAsync（fillDataModel 只填 additionalElements）。已埋节点数探针（addElement 后 + queryUpdate 完成后），等下轮日志定位。注：原生 builder=DiagramBuilderFactory.create→UmlGraphBuilder（非 createBuilder），但 A 路径 createBuilder 可渲染，暂非嫌疑。

**T-Embed-10.4 四轮闭环（2026-09-04，8832f19）**：探针日志 `fillDataModel done: elements=2, nodes=0` + `after queryUpdate: nodes=0`——字节码最终实证：`DbDiagramDataModel.addElement` 只登记 `myTablesAddedByUser`（DbTable 分支 createDbNode **只返回不写 myNodes**），`myNodes` 只在 `refreshDataModel()` 重建（calculateElementToNodeMap←getAllTables）；原生链 `initializeDataModelAsync` = **fillDataModel 后必调 `refreshDataModelAsync(indicator)`**。我们缺此调用 → nodes 恒 0。修=addElement 后显式 `dataModel.refreshDataModel()`（EDT WriteAction 内，表少毫秒级；`queryUpdate().withDataReload()` 不等于它）。同轮第二修复：`DasModel.traverser()` 返回 JBTreeTraverser（extends FilteredTraverserBase **implements java.lang.Iterable**，非 JBIterable 子类）——对 JBIterable 取 toList invoke 抛 `IllegalArgumentException: object is not an instance of declaring class`；修=Iterable.iterator 反射 + cast java.util.Iterator 直调。**方法论沉淀**：探针日志（读运行时状态）比字节码推理快一轮定位；`LOG.warn(msg, e)` 必须带 Throwable 才能定位炸点（仅 `${e}` 消息无法区分链上哪步）。

**T-Embed-10.5 五轮（2026-09-04，f33480c）**：连线缺失根因（javap calculateEdges 实证）——建边前 `isCategoryEnabled(VIRTUAL_FOREIGN_KEYS)`，嵌入场景 nodeContentManager 新实例该分类默认 false → 直接 return（因此 nodes=2 正常但零边）。修=反射读 `DbDiagramProvider$Holder.VIRTUAL_FOREIGN_KEYS`（私有静态，package-private holder 类）+ `dataModel.nodeContentManager.setCategoryEnabled(true)`（DiagramDataModel public API）于 refreshDataModel 前。**B 路径字段全量展示=原生无列级过滤 API**（DbDiagramDataModel 只登记表，列展示由 provider/DbTable 决定），已约定不做字段过滤、优先连线（用户 2026-09-04 明确「如果无法进行字段过滤就算了 优先保证现有的功能和join连接信息」）。A 路径 not found 探针已埋（resolver scan: tablesScanned/hit/foundColumns）——下轮日志分辨三因（elements空/DasTable筛不出/名称不匹配）。

**T-Embed-10.5 真机验证状态（2026-09-04）**：首例方法 B 路径**已真机验证**——字段+表注释+`int(11)` 类型全部 Database 真实值（nodes=2 两表定位），T-Embed-10.4 的 refreshDataModel 修复与 10.5 的 setCategoryEnabled 连线修复均生效；截图确认注释/类型/表头全原生。**唯一遗留**：含子查询的第二种方法走 A 路径，富化链仍 `not found in 2 data sources`（resolver 遍历空）——已埋扫描探针待下轮日志定位（B 路径「显示多了」=原生无列级过滤 API，确定为预期行为）。

**编译/测试陷阱复证**：测试编译会从 build/libs 旧插件 jar 解析同包符号（新 internal 函数报 Unresolved 而老符号正常）——删 `build/libs/*.jar` 恢复；Kotlin 非空参数反射传 null 触发 checkNotNullParameter 内在检查，渲染逻辑须抽纯函数直测。

## dev 分支差异（2026-09-03 实证 → **2026-09-13 已失效**）

**「dev 分支没有原生 JOIN 图框架」已过时**——该差异只存在于 2026-09-03~09-10 窗口。dev 经 master→dev 合并（T-MergeMasterIntoDev/2，至 merge 613eae6）已带上完整原生框架，2026-09-13 实证 dev HEAD（adaa2f4）：

- `ui/diagram/nativefw/`：SqlJoinDiagramProvider（diagram-optional.xml 注册 `diagram.Provider` EP）/ NativeJoinDiagramSupport（降级门面）/ NativeJoinDiagramOpener / JoinDiagramSnapshotRegistry；
- `ui/diagram/nativefw/db/`：JoinVirtualFkProvider（database-optional.xml 注册 `modelRelationProvider` EP）/ JoinFkRegistry / NativeDatabaseDiagramSupport；
- 自绘降级保留（`ui/diagram/DiagramPanel` 等）；设置键 `joinDiagramNative` 默认 true；
- dev 侧测试：NativeJoinDiagramAdapterTest / NativeJoinDiagramScenariosTest / JoinFkRegistryTest（纯 JUnit）。

两端实现已归一，原「modify/delete 冲突无法 cherry-pick」的顾虑消除；dev 架构文档 02 篇 §7 已按此收录（[[t-archdocs-sync-dev]]）。
