# JOIN 图全原生 UI 对齐 IDEA Database「显示图」开发计划

> 源自 2026-09-03 用户反馈：JOIN 图当前字段/表头/toolbar 与 IDEA Database 原生「显示图」差距大，要求**整个 UI 直接用原生能力**，只控制「字段是否展示」和「JOIN 连接线」。
> 目标基线：ideaIU-2024.3.7.1，已注册 `com.intellij.diagram.Provider`（diagram-optional.xml 可选依赖），图框架已能原生渲染节点/边（yFiles）。本计划聚焦把渲染完全对齐 Database 原生，不引入任何自绘节点。

## 背景与现状

| 项 | 现状 | Database 原生 |
|----|------|---------------|
| 字段行 | 名 + 类型（注释缺失） | 名 + 类型 + 注释（PK 加粗 + 分区） |
| 表头 | 表名(别名) | 图标 + 表名 + 表注释 |
| 连接线 | 直线 + ON 标签 | 箭头/颜色按关系类型 |
| 工具类/工具栏 | 框架默认 | 分类切换、导出、图例、右键菜单 |
| 内容类别 | NO_CATEGORIES（分类启用会过滤字段） | COLUMNS / KEY_COLUMNS / COMMENTS 可切换 |

**已实证的字节码关键点**：
- 节点项渲染走 `DiagramNodeItemComponent` 的 **3 参** `getItemName/getItemType(identifyingElement, item, builder)`，2 参落空默认（已修复）。
- `DiagramCategory` 启用受 `DiagramContentSettings`/`DiagramState` 持久化状态过滤，新图默认空状态会把列全过滤——这是字段消失的根因，当前用 `NO_CATEGORIES` 规避。
- `createBuilder` 必须用 `VirtualFileManager.findFileByUrl`（注册单例 VFS），否则 `Can't find project by file`。

## 目标

JOIN 图渲染完全复用 IDEA Database 原生图的展示体系：字段显示「PK 图标 + 名 + 类型 + 注释」、表头「图标 + 表名 + 表注释」、连接线带 JOIN 类型语义、工具栏原生分类切换 + 导出 + 图例。插件只负责**控制哪些字段展示**（分类开关）和**JOIN 连接线数据**（终点 + 样式），渲染全交框架。

## 任务

### T-NativeUI-01 字段注释与表注释数据链路贯通

- 现状：`ColumnEntry.comment` 已有；`EntityNode/NativeEntity` **缺表注释字段**。
- 给 `EntityNode`/`NativeEntity` 增加 `tableComment: String?`；`JoinDiagramGenerator` 从 `TableInfo.comment` + `stmt.table.comment` 透传；快照适配层同步。
- 排查字段注释为何 getItemType 已返回仍不显示：验证 `DbDiagramElementManager.getItemName/getItemType` 的**确切渲染尺寸/分隔符**（javap 实证），对齐到注释文本真正出画。
- 断言单测：主表/JOIN 表列注释、表注释经快照往返保留。

### T-NativeUI-02 分类体系正确启用（替代 NO_CATEGORIES）

- 根因是 `DiagramContentSettings` 持久化状态默认空。找出 Database 的 `DbDiagramNodeContentManager` 如何初始化默认启用（javap `getEnabledCategories`/`setDataModel`/`DiagramContentSettings` 读写）。
- 方案：实现 `DiagramNodeContentManager`，`getContentCategories()` 返回 COLUMNS/KEY_COLUMNS/COMMENTS；**正确设置默认启用态**（覆盖 `isCategoryEnabled` 初值或初始化 `setCategoryEnabled` 全 true），既保留框架工具栏分类切换、又不致新图字段被过滤。
- 验证：分类切换勾选后字段实时增减（Heavy 测试注断言）。

### T-NativeUI-03 表头显示表注释（对齐 Database 标题栏）

- 覆写 `getPresentableElementTitle`/`getElementTitle`：标题 = 表名 + 括号别名；表注释以 Database 样式的灰色斜体/次级文字展示（参考 `DbDiagramElementManager.getPresentableElementTitle` 字节码）。
- 图标：表节点图标沿用 DataTables，PK 用 FieldPK，普通列 DataColumn（已对接）。

### T-NativeUI-04 JOIN 连接线语义化

- `DiagramRelationshipInfoAdapter` 全参构造：JOIN 类型 → 起点/终点箭头（`DiagramRelationshipInfo.DELTA`/`CROWS_FOOT` 等）、线型（LINE 实线 / FULL 虚线）、线宽、标签（ON 条件）。
- INNER/LEFT/RIGHT/FULL 用不同箭头+颜色区分，对齐 Database 关系线观感。
- 单测：各 JOIN 类型渲染参数（lineType/arrows/label）断言。

### T-NativeUI-05 工具栏与交互验收

- 确认框架原生工具栏已带：缩放适配、导出（PNG/SVG/Mermaid 等 export 包）、图例、右键菜单、分类切换。
- 需要插件补的（若有）：隐藏 yFiles 水印？导出名称默认值；表注释 tooltip。
- 真机输出屏幕截图置档（铁律：真实 IDE 截图验证视觉，单测通过≠视觉达标）。

### T-NativeUI-06 清理与研究性复核

- 复核 `JoinDiagramNodeContentManager.kt` 删除后无残留引用；`diagram-optional.xml` EP 注册保持。
- `plugin.xml description`/CHANGELOG 同步；`project-idea-diagram-framework.md` 记忆补最终渲染方案。
- verifyPlugin（IU-243/253/262）+ 全量 shard 回归。

## 风险与对策

| 风险 | 对策 |
|------|------|
| DiagramCategory 持久化状态默认过滤字段 | T-NativeUI-02 找 Database 初始化方式，Heavy 测试兜底 |
| 注释/表注释数据在某些场景缺失 | 数据链路 T-NativeUI-01 贯通，缺失则空字串降级 |
| 连接线样式 API（箭头/颜色）探索成本 | T-NativeUI-04 javap 实证 `DiagramRelationshipInfoAdapter` 全参 |
| 原生工具栏能力超出插件控制 | T-NativeUI-05 真机截图核对，仅补缺口 |

## 并行隔离（铁律）

- 跑任何 `./gradlew` 前 `python C:\Users\USER\.zcode\cli\parallel_tasks.py check`；有活跃任务则 register 独立沙盒。
- 提交只含本任务文件。

## 变更记录

| 日期 | 变更 |
|------|------|
| 2026-09-03 | 实施 T-NativeUI-01~04 + 全量测试：表/字段注释链路贯通（EntityNode/NativeEntity 增 tableComment）；弃 NO_CATEGORIES，直接实现 `DiagramNodeContentManager` 默认全启用（根因实证 `AbstractDiagramNodeContentManager.myEnabledCategories` 初始空 + `DiagramCategory` 3 参构造布尔即 enabledByDefault，对齐 Database 的 DbDiagramProvider$1）；`getPresentableElementTitle` 表头表注释灰斜体；`DiagramRelationshipInfoAdapter.Builder` 全参连线（INNER/LEFT/RIGHT/FULL 各箭头+线型+宽度+ON 标签）。adapter 23 + generator 19 + Heavy 分类断言，全量回归 exit 0 |
| 2026-09-03 | 立项：当前字段缺注释、表头缺表注释、连线无语义、分类未启用（NO_CATEGORIES 规避状态过滤）。规划 T-NativeUI-01~06 |