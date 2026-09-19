# T-DgmFullCols JOIN 图整表列口径 开发计划

> 日期：2026-09-17 18:30
> 状态：已实施（2026-09-17，分支 t-dgmfullcols，基线 dev 60ea0cb）
> 来源：T-DbSchemaCompletion 收尾时向用户提出的口径问题——用户确认按"整表列"实施

## 一、需求

JOIN 预览页的图复用 IDEA Database「显示图」的观感，但两侧列表口径不一致：**主表画实体整表列，JOIN 表只画 SQL 参与列**（ON 条件与 SELECT 里引用到的列，通常就一两个 join key）。结果同一张图里主表节点 5 行、JOIN 表节点 1 行，观感与原生「显示图」（整表，每列带 `/* 注释 */` 与类型串）差得远。

用户定案：**统一成整表列口径**，并把数据源里有而实体没映射的列一并补上；暂不加工具栏开关。

## 二、现状实测（代码位）

| 关注点 | 位置 | 现状 |
|---|---|---|
| 主表列来源 | `sql/JoinDiagramGenerator.kt:99-107` `pickColumns` | 实体**整表列**；>50 列时只留主键列 |
| JOIN 表列来源 | 同文件 `extractJoinColumns` | **只有** selectColumns 引用列 + ON 列 + FillClause 列 |
| 列数上限 | 同文件 `MAX_COLUMNS = 50` | 私有常量，只有主表裁剪用它 |
| 数据源富化 | `ui/diagram/DiagramSchemaEnricher.kt` `applyTableMeta` | 只对**实体已有列**按名覆盖注释/类型；库里多出来的列直接丢弃 |
| 渲染 | `ui/diagram/nativefw/SqlJoinDiagramProvider.kt:204-220` | `joinItemNameText` 内联 `/* 注释 */`、`joinItemTypeText` 右对齐类型；262 框架 `DiagramNodeItemComponent` 调的正是这两个三参重载（常量池实证） |

## 三、方案

1. **JOIN 表列来源与主表同口径**：`extractJoinColumns` 第 0 步先铺 `join.tableInfo.columns` 整表列（按同一 `MAX_COLUMNS` 规则裁剪：>50 列只留键列），被引用列（selectColumns / ON / FillClause）**追加或合并**在后——SQL 原始列名可能不在实体里，必须保留。
2. **合并语义逐维度不降级**（有序 `LinkedHashMap` + `put` 助手）：
   - `type`：保住原优先级「`ColumnRef.type`（selectAs）> 实体字段推断」——selectAs 的显式类型可覆盖实体值，实体的空类型不得顶掉已知类型；
   - `comment`：已有优先（两侧都源自实体）；
   - `isPk`：任一为真即真（实体是唯一 PK 来源）。
3. **数据源独有列追加**：`DiagramSchemaEnricher.mergeColumns` 在实体列之后按库返回顺序追加 `meta.columns` 里实体没有的列（`type = typeSpec`、`comment = DB COMMENT`、`isPk = false`）；追加后总数 >50 则整体放弃追加（宁少不撑爆画布）。
4. **上限常量收口**：`MAX_COLUMNS` 上移为 `ui/diagram/DiagramModel.kt` 的 `internal const val DIAGRAM_MAX_COLUMNS = 50`，生成器裁剪与富化器追加共用一个口径（两处各自维护会让"实体 40 列 + 库 20 列"绕过限制）。

**顺序**：实体列在前（用户熟悉的字段顺序），库独有列在后。

## 四、明确不做

- **库独有列不标主键**：`ColumnMeta` 只有 notNull/类型/注释、没有键信息；用 notNull 近似会误导，宁可不标（要标需另取 `DasTableKey`）。
- **不加工具栏开关**：先看真机观感；若宽表噪声大，再把"库独有列"做成第 4 个分类（默认关）——`DiagramNodeContentManager` 已注册 3 类，扩展位现成。
- **不做"参与列高亮"**：整表列落地后 ON/选中列不再有视觉区分，是否需要标记（图标/加粗）留观感反馈再定。

## 五、验证与验收

1. 全量 `./gradlew testShards` 绿。
2. 新增用例：JOIN 表整表列（不只 ON 引用列）、引用列不在实体时仍保留、>50 列退化为"主键 + 引用列"、库独有列追加在后且带类型串、超上限时不追加。
3. 既有契约用例更新：`NativeJoinDiagramScenariosTest.s02`（JOIN 表列期望由"仅引用列"改为"主键 + 引用列"）。
4. 真机（待用户）：JOIN 图 JOIN 表节点显示整表字段行（含 `/* DB 注释 */` 与 `varchar(64)` 这类类型串），观感与原生「显示图」一致。
5. 文档三处同步（变更记录 / CHANGELOG；changeNotes 发版时补——本条属对外可见变化，发行说明应列）。

## 六、风险与陷阱

| 风险 | 说明与对策 |
|---|---|
| 宽表噪声 | 50 列上限兜底（超限只画键列 + 引用列）；真机不满意再上开关 |
| 类型优先级被破坏 | 已实证会回归（`s03_lambdaJoin` 的 `VARCHAR` 变 null）：`put` 助手用 `overrideType` 显式表达"引用侧类型可覆盖"，并有既有用例守着 |
| 库独有列跨表重名 | 富化按 `entity.tableName` 逐表取 meta，不存在跨表串列；列名比较用 lowercase 归一 |
| 库列顺序依赖遍历顺序 | `meta.columns` 是 `linkedMapOf`，顺序即 DasModel 遍历顺序（MySQL 为 ordinal 序）；非 ordinal 的方言下顺序可能不理想，不影响正确性 |
