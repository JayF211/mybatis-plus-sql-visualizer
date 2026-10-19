---
name: project-f42-join-diagram-render
description: F-xx JOIN 关系图自绘 Swing 引擎（替代 PlantUML + Batik），架构/数据模型/渲染管线/踩坑/F-1.1 UI优化
metadata:
  node_type: memory
  type: project
  originSessionId: sess_c11a0580-0cd2-472a-9e30-872350aee1d8
---

# F-xx JOIN 关系图自绘 Swing 引擎

## 变更背景（2026-08-26）

F-42 修复 PlantUML 8059 硬依赖 Graphviz 后，引入 PlantUML 1.2024.8 (21.9MB) + Batik (~8MB)，插件 zip 从 ~10MB 膨胀至 42MB。决定移除全部外部依赖，改用纯 Java Graphics2D 自绘 ER 关系图。

## 架构

```
JoinDiagramGenerator.buildDiagramModel(SelectStatement) → DiagramModel
DiagramModel → DiagramRenderer → DiagramPanel (UI)
```

**数据模型** (`ui/diagram/DiagramModel.kt`):
- `DiagramModel(entities: List<EntityNode>, relationships: List<RelationshipLine>)`
- `EntityNode(alias, tableName, columns: List<ColumnEntry>, isMain, joinType: JoinType?)`
- `ColumnEntry(name, type: String?, isPk)` — type 现为 SQL 类型（INT/VARCHAR/BIGINT）
- `RelationshipLine(fromAlias, toAlias, joinType, label)`

**生成器** (`sql/JoinDiagramGenerator.kt`):
- `buildDiagramModel(stmt)` — 主表绿色、JOIN 蓝色
- 列来源：`selectColumns`（含 selectAs）+ `ON 条件` + `FillClause`
- 类型解析：`javaTypeToSqlType()` 将 Java 全限定名映射为 SQL 类型（Integer→INT, String→VARCHAR 等）
- Alias 解析：`resolveJoinAlias()` — 实体类名（含大写驼峰如 `stationInfoEntity`）fallback 为默认别名 `t{N}`，纯小写 SQL 别名（如 `tt`）保留
- 子查询处理：`resolveDisplayTableName()` — tableName 含 SELECT 或以 `(` 开头 → 显示为 "Subquery"

**渲染器** (`ui/diagram/DiagramRenderer.kt`):
- `measurePreferredSize(model)` — 计算图表自然尺寸
- `render(model)` — 渲染为 BufferedImage（PNG 导出）
- `renderToSvg(model)` — 渲染为 SVG 字符串
- `drawCards/drawLabels/prepareLinesFor` — 供 DiagramPanel 直接调用

**面板** (`ui/diagram/DiagramPanel.kt`):
- `DiagramCanvas` (JComponent) + `JBScrollPane`
- `paintComponent` 内直接 `Graphics2D` 变换绘制，无中间位图
- HiDPI 由 `Graphics2D.transform` 自动适配

## F-1.1 UI 优化（2026-08-26）

**布局重构**：左右列排列 → 上下居中对称（梳状拓扑）
- 主表居中上方，JOIN 表水平排列在下方
- `computeLayout()` 算法：mainX = MARGIN + (totalJoinWidth - mainWidth) / 2

**连线正交路由**：直线 → T 型/梳状折线（`computeOrthoPath`）
- 主表底部中心 → 垂直到分叉高度 → 水平分叉到各子表列 → 垂直到子表顶部
- `drawOrthoLines` 绘制 polyline + 三角箭头

**标签位置与重叠修复**：
- 初版将 ON 条件标签居中在水平段上方，但用户实测多个 JOIN 时仍重叠
- 当前改进：ON 文本统一截断到 48 字符、最大宽度 300px，并按关系序号向上分层（`LABEL_LINE_GAP`）排列；布局同时根据实际标签宽度动态扩大 JOIN 卡片间距
- 基数标记（1/0..*）在实体端居中
- 重要验证：只通过单元测试不足以证明视觉不重叠，必须在 runIde/真实弹窗截图中检查；用户在 2026-08-26 两次反馈截图仍有标签重叠，说明纯静态边界框策略不可靠，后续应优先采用每条关系独立标签轨道或改为卡片内/边缘显示，而不是依赖同一水平分叉段

**已提交修复**：`e50c1cf fix: prevent JOIN relation labels overlapping`；此前 F-1.1 主实现提交为 `0347405`。

## F-1.2 后续布局调整（2026-08-26）

用户在真实 IDE 截图中连续反馈标签仍重叠，说明 F-1.1 的“动态间距 + 静态边界框 + 关系序号错层”方案不可靠。随后尝试了以下方向：

- 提交 `054407d`：改为主表左侧、JOIN 表右侧纵向分行；连线改为主表右边缘到 JOIN 表左边缘的独立正交路径；Canvas 补画关系线并改为单次 zoom。
- 提交 `71dbf94`：扩大标签列边界并把 ON 标签移到连线侧方。
- 提交 `f10d374`：为关系标签增加独立标签行/标签列并纳入画布宽度计算。

**当前仍需真实截图验收**：用户反馈最新截图仍有标签/关系视觉重叠。后续不能继续叠加固定偏移量；应建立统一 Layout Snapshot/Scene（节点矩形、边路径、ON 标签矩形、基数矩形、content bounds），让 PNG、SVG、Canvas 共用同一快照，并增加矩形碰撞测试。鼠标平移、节点/关系悬停高亮、点击选中已写入开发计划，暂缓实现。

**最新状态（2026-08-26）**：布局经过 `054407d`、`71dbf94`、`f10d374` 多轮调整后基本稳定为主表左侧、JOIN 表右侧纵向分行；ON 标签进入独立标签行/列，且画布尺寸包含标签区域。随后在 `31e06bb` 修复了中间 ON 条件别名：图节点使用归一化 alias（如 `stationInfoEntity` fallback 为 `t1`）时，关系标签同步显示 `t.id = t1.user_id`，不再混用原始实体类名。相关 `JoinDiagramGeneratorTest` 已通过。

**卡片样式**：JOIN 表标题右侧标注 JOIN 类型（如 `[LEFT]`）

**Java→SQL 类型映射**：`javaTypeToSqlType()` — Integer→INT, Long→BIGINT, String→VARCHAR, LocalDateTime→DATETIME 等

## 渲染样式

- 卡片：白底 + 1px 边框 + 微阴影，圆角 6px
- 标题栏：主表绿色 `#DCE8DC`，JOIN 蓝色 `#DCE5ED`
- JOIN 类型标注：标题右侧灰色 badge（如 `[LEFT]`）
- 列间分隔线：浅灰 0.8px，行交替底色
- 关系线：深灰 1.5px 正交折线 + 实心三角箭头
- Cardinality 标签：`1` / `0..*`，实体端居中
- ON 条件标签：白色圆角紧贴水平连线段

## selectAs 字段归属

`selectAs(StationInfoEntity::getStationName, DeviceInfoEntity::getDeviceName)` 的字段归属：
1. `ArgResolver.resolve` 提取方法引用返回类型 → `AliasArg.returnType`
2. `parseSelectAs` 读取 `AliasArg.returnType` → `ColumnRef.type`
3. `parseSelectAs` 使用 `classToAlias` 映射（entityClass → alias）设置正确 `tableAlias`
4. `JoinDiagramGenerator.extractJoinColumns` 按 `tableAlias` 匹配 + 类型解析

## JoinClause.tableInfo

`JoinClause` 新增 `tableInfo: TableInfo?` 字段，由 `MpjLambdaConverter.parseJoin` 填充（仅 EntityRefArg 有值）。用于：
- 图表渲染时解析 JOIN 表字段的 Java 类型
- `extractJoinColumns` 中 `typeFromTable(colName)` 辅助函数

## 关键踩坑

1. **Write 工具静默失败**：大文件写入时 Write 工具报告成功但内容未实际更新。对策：用 Bash heredoc 或 Python 脚本直接写磁盘。
2. **2x DPI 超采样方案废弃**：渲染 2x → 缩放 1x 仍模糊，改为直接 Graphics2D 变换绘制。
3. **min/max 3 参数重载**：`kotlin.math.min/max` 不支持 3 参数，改用 `minOf/maxOf`。
4. **中间位图方案废弃**：`JLabel` + `ImageIcon` + `BufferedImage` 双重缩放导致模糊，改为 `paintComponent` 直接绘制。

## 体积收益

- 移除：`plantuml:1.2024.8` (21.9MB) + `batik-transcoder:1.17` + `batik-codec:1.17` (~8MB)
- 移除：`xml-apis` 排除规则 + `--add-opens` 参数
- 插件 zip：42MB → 5.3MB（-87%）