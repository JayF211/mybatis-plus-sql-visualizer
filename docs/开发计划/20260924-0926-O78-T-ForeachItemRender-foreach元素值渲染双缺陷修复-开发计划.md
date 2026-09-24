# O-78 T-ForeachItemRender foreach 元素值渲染双缺陷修复 — 开发计划

- **编号**：O-78（占号前实测：`git log master --oneline` 顶=`324b29f`，`docs/开发计划/` 当日无 0926 后新建文件，台账快照「下一号 O-78」）
- **任务名**：T-ForeachItemRender
- **日期**：2026-09-24
- **状态**：待实施（用户同批点名「分析→计划→实施」，本计划随实施回填 §九）
- **来源**：用户问题反馈——demo `selectOrdersByCondition` 的 `statusList`（`List<Integer>`）在 foreach 集合框输入 `1,2,4,5`，预览渲染出 `IN ('1', '2', '4', ?)`：数值被加引号成字符串 + 输入 4 值只渲染 3 个、尾巴留裸 `?`
- **基线**：master=`324b29f`（20260924 实测）

## 一、问题与复现

demo 仓 `OrderMapper.xml` 的 `selectOrdersByCondition`（foreach `collection="statusList" item="st"`，`List<Integer>`）：在预览面板 foreach 集合框输入 `1,2,4,5` → 渲染 `AND status IN ('1', '2', '4', ?)`。两个缺陷同屏：

1. **数值误加引号**：`List<Integer>` 的元素按字符串渲染（`'1'`），MySQL 数值列隐式转换虽可执行，但语义失真（字符串列数值列不可辨、字符串比较/索引语义错）；
2. **尾巴丢值**：输入 4 值渲染 3 值 + 裸 `?`，且丢的是**最后一个**。

## 二、根因分析

### 缺陷 1（引号）：集合元素类型在 T-PT-01 归类时被剥掉，item 占位键无类型条目

- foreach 展开后每个 `#{st}` 被重写为占位键 `__frch_st_<n>`（`ForeachSqlNode.kt:100/157`），面板回填引号取 `xml.quoteFor(source) ?: true`（`SqlPreviewPanel.kt:2361`）；
- `quoteFor`/`paramTypeFor`（`XmlPreviewModel.kt:97-109`）查 `paramTypes`——该表由 `XmlParamTypeResolver.resolveTypes` 按方法签名生成，**只有参数名键**（`statusList` → COLLECTION），元素类型 `Integer` 在 `kindOfTypeText` 剥泛型时丢弃（`XmlParamTypeResolver.kt:228`），且 `__frch_*` 键从不在表内；
- 未命中 → `quoteFor` 返回 null → 面板兜底 `true` 恒引号。`ParamTypeInfo`（`ParamKind.kt:47`）只有 kind+nullable，无元素类别字段——信息在源头就丢了。

### 缺陷 2（尾巴丢值）：值播种依赖「渲染后同步读映射清单」，生产 write-unsafe 上下文下该刷新是异步的

- `applyXmlForEachValues`（`SqlPreviewPanel.kt:3690-3705`）：设 counts → `renderSql()` → **同步读 `xmlMappings` 过滤 `__frch_st_` 前缀得键清单** → 按下标配值 → 再 `renderSql()` 回填。注释（:3679-3680）明言依赖「先渲染一次刷新键清单」；
- 但 `renderSql()` 首行有写安全闸（`SqlPreviewPanel.kt:2327`）：`DocWriteContext.isWriteAllowedNow()` 为 false 时**重投递 nonModal 并立即返回**，`xmlMappings` 不刷新。生产里 apply 由防抖 Timer 触发（`scheduleRender` → `renderDebounceTimer`），Timer 事件非用户活动 → `TransactionGuard.isWritingAllowed()` 为 false（`DocWriteContext.kt:49-56`）→ **renderSql 必被异步化** → `keys` 读到的是上一拍的旧键清单 → 值按旧键播种，新键（多出的下标）无值；
- 错位量=旧/新键数差：逐字输入节奏下（如先输 `1,2,4` 停顿再补 `,5`）恰是「3 个旧键有值 + 第 4 个新键裸 `?`」，与截图逐字吻合；
- **单测环境 `isWriteAllowedNow` 恒 true（`DocWriteContext.kt:52`）→ 双渲染同步成立 → 既有测试全绿**——环境差异假绿，真机必现。同族隐患：任何「renderSql 之后同步读 xmlMappings/依赖其刷新」的调用点。

## 三、影响面

- 缺陷 1：所有带签名类型信息的 foreach 场景——`List<Integer>`/`List<Long>` 等数值元素全被加引号（T-PT-01 落地即存在，发布版 2026.9.14 受影响）；无类型信息（Map 参数/解析不到接口）保持引号兜底不变。
- 缺陷 2：面板 foreach 值输入路径（gutter/预览弹窗共用 `SqlPreviewPanel`），丢值量随输入节奏浮动，非确定性复现是本缺陷的隐蔽点。
- MCP render 工具不经过面板回填层，不受缺陷 2 影响；缺陷 1 属面板回填层，同样不波及 MCP。

## 四、修复方案

### 缺陷 1：元素类别传导（paramTypes 源头补信息 + 模型查找兜底）

1. `ParamTypeInfo` 增尾置默认字段 `elementKind: ParamKind? = null`（集合参数的元素类别；非集合/数组/无泛型实参为 null——宁缺勿错）；
2. `XmlParamTypeResolver.typeInfoOf`：kind==COLLECTION 且 `PsiType is PsiClassType` 且恰一个泛型实参时，以 `kindOf(实参)` 填 `elementKind`（`List<Integer>`→NUMBER、`List<String>`→TEXT、`List<DTO>`→OBJECT；裸 `List`/数组→null）。DTO 字段递归与 `collection`/`list`/`paramN` 别名同走 `typeInfoOf`，自动继承；
3. `XmlPreviewModel.paramTypeFor` 前缀兜底之后新增 foreach item 键解析：`^__frch_(.+)_\d+$`（仅裸键，含 `.` 子路径不接）→ item 名 → `forEachViews` 反查 collectionExpr → `paramTypes[collectionExpr]?.elementKind` 合成 `ParamTypeInfo(kind=元素类别)`；`quoteFor` 末尾加 `paramTypeFor(source)?.kind?.quoteRequired` 兜底（paramTypes 空的旧布尔通道不受影响——paramTypeFor 恒 null）。

### 缺陷 2：值播种收进渲染管线（状态与渲染解耦，消灭双渲染读旧键）

1. 面板新增状态 `xmlForEachValues: Map<collectionExpr, List<String>>`（用户输入的权威状态）；`applyXmlForEachValues` 退化为「存状态 + 设 counts + `renderSql()` 一次」（防抖后由渲染管线按最新状态出图，deferred 与否都正确）；
2. `renderSql()` XML 分支在 `xmlMappings = rendered.mappings` 之后调 `syncForEachItemValues`：按 collectionExpr→view→item 链取前缀过滤**当前**映射清单播种 `paramValues[__frch_*]`（值映射、多出键清除——语义与原实现一致，但键清单恒新鲜）；映射刷新与播种同帧完成，write-unsafe 重投递时二者一起延迟，不再错位；
3. 状态复位点（结果切换 init、清除）与 `xmlForEachCounts` 同步清。

**否决项**：按 `__frch_<item>_<下标>` 推算键名免渲染——键的数字段是 DynamicContext 全局唯一编号而非循环下标，同文件多 foreach 时不从 0 起，推算必错；保持「读真实映射清单」只把读取时机挪进渲染帧。

## 五、待验证前提（实施者先证伪再动手）

- **P1**：Light 夹具 `writeAllowedProbe={false}` + `UIUtil.dispatchAllInvocationEvents()` 泵送可确定性复现丢值（机制与生产 Timer 上下文同构；生产端确切交错未逐步取证，以「键清单滞后一拍」推演与截图 3+1 形态吻合为间接证据）；
- **P2**：`quoteFor` 新兜底对 paramTypes 空的旧布尔通道返回 null 不变——单测钉住；
- **P3**：元素抽取对 `List<DTO>`→OBJECT、`List<String>`→TEXT、裸 `List`/数组→null 的边界——解析器测试钉住；
- **P4**：多 foreach 并存/同 item 名形态下 sync 的键归属（collectionExpr→view→item 链）——至少以双 foreach 单测覆盖一例。

## 六、实施步骤

1. **测试先行（先红）**：`ParamTypeInfo.elementKind` 先落（默认 null，惰性字段）；随后三组用例——模型单测（item 键 `quoteFor`/`kindFor`/旧通道不变）、解析器平台测试（`List<Integer>`→elementKind=NUMBER 等 P3 边界）、面板平台测试（probe=false 下输入 `1,2,4,5` 泵送后断言 `IN ( 1, 2, 4, 5 )`——引号+丢值一测双覆盖）；
2. 生产代码按 §四实施；
3. **反向验证**：临时摘掉模型兜底与 sync 播种 → 三组用例复红；恢复 → 全绿；
4. 定向回归：`XmlPreviewModelTest`、`SqlPreviewPanelParamKindTest`、`XmlEwExpanderTest`、`XmlEwResolverTest`、`XmlPreviewServiceTest`、`DocWriteContextTest`；
5. 验证口径：纯逻辑+面板改动，定向 + `testFast`；不跑全量、不跑构建校验；gradle 前照例 `parallel_tasks.py check`；
6. 交付纪律：主控统一写变更记录/CHANGELOG；每完成一件可提交的立即提交。

## 七、验收标准（用户可见判据）

1. demo `selectOrdersByCondition` foreach 框输入 `1,2,4,5` → `AND status IN ( 1, 2, 4, 5 )`——数值裸渲染且四值齐全；
2. `List<String>` 集合元素仍带引号（引号语义不误伤）；
3. 任意输入节奏（逐字/粘贴/分批补输）不出现尾巴 `?`；
4. 无类型信息场景（Map 参数等）维持引号兜底与 `?` 兜底现状。

## 八、风险与回滚

- 缺陷 1 改动=惰性默认字段+两处查找兜底，paramTypes 空路径零影响；缺陷 2 改动收拢在面板 foreach 值路径，其余回填路径零触碰；
- 主要风险=P1 的生产交错与夹具不同构（夹具必现而生产浮动）——夹具红不能证明生产全形态修复，验收以 §七第 3 条真机走查兜底；
- 回滚：单提交 revert。

## 九、实施记录

（实施中回填。）
