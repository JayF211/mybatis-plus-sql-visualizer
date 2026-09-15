# T-SelToggle：「全选/全不选」双按钮合并为单个状态切换按钮（SQL 预览 + 日志类型弹窗）开发计划

> **创建日期**：2026-09-12
> **分支**：master（工作树有本需求前置改动未提交——清除按钮移至分区按钮组末位，`SqlPreviewPanel.kt` 两处 `sectionHeaderRow` 按钮顺序调整；实施前作为独立小提交先行入库，见 §四 步骤 0）
> **优先级**：P2（纯 UI 精简，无渲染语义变更；预览面板左栏是高频交互区，按钮组瘦身直接影响观感）
> **预估工时**：4.5h（预览侧主改动 1h + 弹窗侧改动 0.5h + 测试改造与新增 2.5h + 文档 0.5h）
> **来源**：用户需求原话——「主分支有一个待提交的文件，是调整了清除按钮位置，我现在需要将全选/全不选，两个按钮合并成一个按钮」；范围追加（2026-09-12）——「SqlLogPanel也加入开发计划」，SQL 日志页类型过滤弹窗的同款双按钮并入本任务

---

## 一、需求分析

### 1.1 现状盘点

预览面板左栏有**两个分区**各挂一组「全选 / 全不选」紧凑按钮（`SqlPreviewPanel.kt`）：

| 分区 | 构建方法 | 按钮字段 | 动作 |
|---|---|---|---|
| 批量「参数列」标题行 | `batchSectionHeader()`（:1903） | `batchSelectAllButton` / `batchSelectNoneButton` | `setAllBatchColumns(true/false)`（:1917，操作 `batchCheckBoxes` + `enabledIds`） |
| 「动态条件」标题行 | `conditionSectionHeader()`（:1930） | `selectAllButton` / `selectNoneButton` | `setAllConditions(true/false)`（:1951，操作 `conditionCheckBoxes`，Wrapper 条件与 XML 条件共用） |

两组均经 `sectionHeaderRow(title, buttons)`（:1887）回挂分区标题行右侧（T-M26.3 形态），与「清除」按钮同组；工作树待提交改动已把清除按钮调到按钮组末位。i18n 键两分区共用：`panel.condition.selectAll`（全选 / Select all）、`panel.condition.selectNone`（全不选 / Select none）。

按钮生命周期：`rebuildLeftPanel()` 每次重建先置空六个引用（:1423-1426、:1761-1766），仅对应场景出现时重建；测试经四个 `*ForTest()` 访问器驱动（`SqlPreviewPanelTest.kt` 16 处 + `XmlPreviewPanelTest.kt` 3 处）。

### 1.2 「合并成一个按钮」的语义定死

两个按钮互为反义、目标相同（一键设定整组勾选态），合并后必须是**一个按钮承担两个动作**——即按当前勾选态决定行为与文案：

| 当前态 | 按钮文案 | 点击动作 |
|---|---|---|
| 全部已勾选 | 全不选 | 整组取消勾选 |
| 否则（含空集、部分勾选） | 全选 | 整组勾选 |

文案跟随状态刷新是本方案的核心难点：勾选态有三个变更入口——①合并按钮自身点击；②单个复选框点击（共三处 listener：批量列行 :1857、动态条件行 :1967、XML 条件行 :2045）；③面板重建后的初始态。三处入口**全部收敛于 `renderSql()`**（三个 listener 与各 setAll 方法末尾均调用），因此文案同步单点挂在 `renderSql()` 即可全覆盖，不需要给每个 listener 加钩子。

以复选框 UI 态（而非 `enabledIds`）为判定基准：`enabledIds` 可能残留当前场景外的 id（跨场景共享集合），而复选框列表正是用户所见、也是 setAll 作用的对象，二者以 UI 态对齐才不会出现「按钮写着全不选但点下去没反应」的错位。

### 1.3 SqlLogPanel 类型过滤弹窗现状（范围追加）

SQL 日志页「SQL类型」多选按钮弹出 `JPopupMenu`（`SqlLogPanel.showTypeFilterPopup` :579），弹窗底部一对「全选/全不选」按钮（:604-615，i18n 键 `log.filter.selectAll`/`log.filter.selectNone`，与预览面板的 `panel.condition.*` 是**两套独立键、同文案**）：

- 动作收敛于 `applyAllTypes(selected)`（:622）：清空/填满 `activeFilter`，回写弹窗复选框、外层按钮文案、列表；
- 判定基准与预览面板不同——勾选态的真源是 **`activeFilter` 集合**（弹窗复选框只是镜像）；
- 变更入口两条：合并按钮自身 + 弹窗内单类型复选框 listener（:591-595，直接改 `activeFilter` 后 `updateTypeFilterButton()+refreshList()`）；
- **弹窗每次打开整体重建**（`typePopupCheckBoxes` 字段 :572 仅为打开期间同步服务），无持久面板级按钮——与预览面板「常驻标题行按钮 + renderSql 收口」形态本质不同，文案同步不能照搬 F1 的收口点。

### 1.4 范围边界

- 覆盖**三处**全选/全不选：预览面板批量参数列区、预览面板动态条件区（含 XML 共用）、SQL 日志页类型过滤弹窗（用户 2026-09-12 追加确认）。
- 「清除」按钮不动（其工作树位置调整正是本需求的前置，一并入库）。
- 不改 `setAllConditions` / `setAllBatchColumns` / `applyAllTypes` 的行为语义（含「全不选 → 空列清单」文档化边界），只改触发入口的呈现。

---

## 二、设计决策（含备选否决）

### D1：状态切换单按钮，文案取现有两键之一（推荐）

按钮文案在 `panel.condition.selectAll` / `panel.condition.selectNone` 两现有键间切换，**零新增 i18n 键**、双语天然齐备。判定式：

```
全不选 ⟺ checkboxes.isNotEmpty() && checkboxes.all { it.isSelected }
全选   ⟺ 其余一切情形
```

- **备选 B（文案恒为「全选/全不选」不变的单按钮）——否决**：点击结果不可预期，用户无法从按钮预判行为，属可用性倒退。
- **备选 C（下拉菜单：点开选「全选」或「全不选」）——否决**：两步交互换一步收益，且分区标题行右侧按钮组本就是直显风格（T-M23.2 用户明确要求参数可选场景**必须有**一键全选/全不选）。
- **备选 D（图标按钮 All/None 两态图标）——否决**：无现成平台图标语义直白可辨，且文案切换已足够，不引入图标资源成本。

### D2：文案同步挂在 `renderSql()` 末尾，单点收口

`renderSql()` 是所有勾选态变更的公共下游（见 1.2），在此调一个私有 `syncToggleSelectLabels()`：分别对两个按钮（可空，判空即场景守卫）按对应复选框列表重算文案。成本为两次 `setText`（文本未变化时 Swing 自身短路？——不短路，故比较后仅在**文案变化时** `setText`，避免每次重渲染触发标题行 FlowLayout 无谓重排）。

- **备选（三处 listener 各自同步）——否决**：三个入口 × 两个分区共六处钩子，漏一处即状态不同步（XML 条件行 :2050 就藏在另一条构建链上，正属易漏点）；单点收口与 T-PickMpjErr `generateCodeOrReport` 的收口哲学一致。

### D3：字段与测试访问器改名对齐「切换」语义

字段 `selectAllButton`/`selectNoneButton` → `toggleSelectButton`，`batchSelectAllButton`/`batchSelectNoneButton` → `batchToggleSelectButton`；对应 `*ForTest()` 访问器同步（旧四访问器删除，防止测试误用陈旧 API）。按钮点击后文案断言并入既有用例，不新增测试类。

### D4：清除按钮顺序前置改动随本任务入库

工作树两处顺序调整（清除移至末位）与本改动同方法同函数，拆开提交反而制造两次冲突面；作为本任务提交的组成部分，提交信息一并说明（chore 语义并入 feat 描述）。

### D5：弹窗侧同样状态切换，但判定基准取 `activeFilter`、同步点两处显式调用

弹窗内合并按钮沿用 1.2 的「全勾 → 显示全不选，否则显示全选」规则，文案仍取现有 `log.filter.selectAll` / `log.filter.selectNone`（不新造键、不动预览侧键，两套同文案各归各的语义域）。差异两点必须按弹窗实态处理：

- **判定基准 = `activeFilter.containsAll(FILTERABLE_TYPES)`**，不遍历弹窗复选框——`activeFilter` 是勾选态唯一真源（复选框是它的镜像，`applyAllTypes` 即按它回写 :625），且 `OTHER` 类型不在 `FILTERABLE_TYPES` 内、不参与该判定，与外层「全部」文案判定（`updateTypeFilterButton` :565）口径完全一致；空集 → 显示「全选」（点击填满），与预览侧「空集显示全选」一致。
- **同步点无法单点收口**：弹窗无 `renderSql()` 这类公共下游（`updateTypeFilterButton()`/`refreshList()` 还被搜索、清除等无关链路调用，挂进去等于给不相干路径加钩子）。故抽 `private fun syncTypePopupToggleLabel()`，在三个位置显式调用：弹窗构建末尾（初始文案）、`applyAllTypes()` 末尾（合并按钮点击）、单类型复选框 listener 内（:591-595 那条，勾选即时生效链路）。三处即 1.3 列出的全部勾选态变更入口，逐条对得上，不留隐式期望。

- **备选（把同步塞进 `updateTypeFilterButton()`）——否决**：该函数语义是「外层筛选按钮文案」，被搜索清空/筛选恢复等非弹窗路径调用，往里塞弹窗内控件更新属职责混淆，且弹窗关闭后字段为陈旧引用（见 F2 生命周期处理），判空虽安全但每次无关刷新白跑一遍。

### D6：弹窗按钮可测性用可空字段 + `*ForTest` 访问器

`showTypeFilterPopup()` 内按钮与 `JPopupMenu` 均为局部变量，现状无从断言。新增 `private var typePopupToggleButton: JButton? = null`（弹窗构建时赋值，与既有 `typePopupCheckBoxes` :572 同生命周期、同「仅打开期间有效」约定），配 `internal fun typePopupToggleButtonForTest(): JButton?`——与 `SqlLogPanel` 现有 `typeFilterButtonForTest` 等访问器风格一致，不抽方法、不改弹窗结构。陈旧引用可接受：唯一消费点是弹窗内 listener，弹窗收起后无可触发路径。

---

## 三、方案设计

### F1：主改动（SqlPreviewPanel.kt）

1. **字段收敛**（:161-167）：四字段 → `toggleSelectButton: JButton?` + `batchToggleSelectButton: JButton?`；:1423-1426、:1761-1766 两处置空同步收敛。
2. **按钮构建**：`batchSectionHeader()` / `conditionSectionHeader()` 各建一个按钮，初始文案按当前复选框态调 `toggleLabel(checkboxes)` 计算（重建时复选框尚未构建，初始恒「全选」，与首帧 UI 态——按模型 checked 标志勾选——若全勾则 `renderSql()` 首渲染即纠正为「全不选」；`showResult` 链路渲染后必过 `renderSql()`，无空窗）。点击动作 = `setAllBatchColumns(!allSelected())` / `setAllConditions(!allSelected())`。
3. **辅助函数**：
   - `private fun toggleLabel(boxes: List<JCheckBox>): String`——1.2 判定式，返回两键之一的 message；
   - `private fun syncToggleSelectLabels()`——分别对两按钮（判空 + 对应复选框列表）算新文案，`text != new` 才 `setText`；
   - 挂在 `renderSql()` 末尾（`renderSql` 内部提前 return 的分支不经过则核对：文案只随勾选态变，未渲染成功时保持旧文案不致错位）。
4. **KDoc 更新**：`batchSectionHeader` / `conditionSectionHeader` / `setAllBatchColumns` / `setAllConditions` 注释里「全选/全不选按钮」措辞改「单个全选/全不选切换按钮」，并注明 T-SelToggle。
5. **测试访问器**（:2760-2766）：四合一二——`toggleSelectButtonForTest()` / `batchToggleSelectButtonForTest()`；清除按钮两访问器不动。

### F2：SqlLogPanel 类型过滤弹窗改动（SqlLogPanel.kt）

1. **双按钮合一**（:604-615）：底部行改建单个 `JButton`，初始文案 = `typeToggleLabel()`（`activeFilter.containsAll(FILTERABLE_TYPES)` → `log.filter.selectNone`，否则 `log.filter.selectAll`；空集落「全选」分支）；点击动作 = `applyAllTypes(!activeFilter.containsAll(FILTERABLE_TYPES))`；`isFocusable = false` 保留（弹窗内连续操作焦点策略 :608/:612 同款）。
2. **同步函数**：`private fun syncTypePopupToggleLabel()`——按 D5 判定式重算文案，判空 + 变化才 `setText`；调用点三处（D5）：弹窗构建末尾、`applyAllTypes()` 末尾、弹窗复选框 listener（:591-595）内。
3. **可测性**（D6）：新增 `typePopupToggleButton` 可空字段 + `typePopupToggleButtonForTest()` 访问器（挨现有 `typeFilterButtonForTest` :346 排列）；弹窗收起不强制置 null（与 `typePopupCheckBoxes` 同约定），但 `showTypeFilterPopup()` 开头重建赋值天然无跨弹窗脏读。
4. **KDoc 更新**：`showTypeFilterPopup` / `applyAllTypes` 注释「底部全选/全不选一键设置」措辞改单按钮切换语义，注明 T-SelToggle。

### F3：测试改造（预览侧 19 处迁移 + 弹窗侧新增）

预览侧（`SqlPreviewPanelTest` / `XmlPreviewPanelTest`）：

- `SqlPreviewPanelTest`：
  - :439-452（动态条件全选/全不选流）：双按钮点击改单按钮连点两次（全选 → 断言 + 文案「全不选」→ 再点 → 断言 + 文案回到「全选」）；
  - :740-752（批量列全选/全不选流）：同上双次点击改造；
  - :866-873（按钮在视口内）：访问器改名，其余不动；
  - :1145-1175（场景互斥/无内容断言）：改断言新访问器；
  - **新增**：部分勾选态下按钮文案为「全选」、点击后变「全不选」（覆盖 1.2 判定式的中段态）；XML 条件与 Wrapper 条件共用按钮路径一条。
- `XmlPreviewPanelTest` :114/:128/:148：`selectAllButtonForTest()/selectNoneButtonForTest()` 触发改 `toggleSelectButtonForTest()`；注意 :114 原注释「全不选」实为借全选按钮反向操作——改造时以**当前文案驱动点击预期**（初始态非全勾 → 文案「全选」→ 点击全勾），断言逐条按实际语义重写，不保留原注释错位。

弹窗侧（新增，现状无既有用例可迁——仓库测试从未触发过该弹窗）：

- 新建或并入 `SqlLogPanel` 测试类：直接调 `showTypeFilterPopup()`（headless 下 `JPopupMenu.show` 仅入队 lightweight popup，组件树照常构建；断言只经访问器触达按钮与 `activeFilter` 投影 `typeFilterLabelForTest`，**不依赖 popup 真实显示**），断言四点：①初始全选态 → 文案「全不选」；②经复选框 listener 关掉一个类型 → 文案翻「全选」；③点击 → `activeFilter` 回满、外层按钮文案「全部」（`typeFilterLabelForTest` 既有口径）；④空集态 → 文案「全选」、点击填满。若 headless 下 `show` 出现不可控异常，退而抽 `buildTypeFilterContent(): JPanel` 纯构建方法（popup 只管 show），构建断言不受影响——该重构为纯提取，不改行为。
- `applyTypeFilterForTest`（:352）可用来构造任意 `activeFilter` 初始态，省去为每个态铺全套 fixture。

### F4：文档三处同步

变更记录总册 `docs/变更记录.md`、`docs/MEMORY.md` 变更记录、本计划回填实施记录；无设置项、无 bundle 改动（两侧各用现有两套键）、无插件描述「功能清单」粒度变化（按钮合并属细节），不动发行说明。

---

## 四、实施顺序

0. **前置**：`parallel_tasks.py check` + register；工作树清除按钮顺序改动并入本任务提交（D4），不单独成笔。
1. F1 预览侧主改动 + F2 弹窗侧改动（两文件互不牵连，可分可合，一次成型）；
2. F3 测试迁移 + 新增用例，定向跑 `SqlPreviewPanelTest` + `XmlPreviewPanelTest` + SqlLogPanel 测试组；
3. 全量回归（当前基线 3346/0/6——T-CrudSql JBPopup 交付后，实施前复核）；
4. F4 文档同步 + 计划回填；
5. runIde 真机走查（留人工，见 §五）。

## 五、验收标准与真机走查

**程序化验收**：

- 预览两分区各只剩一个勾选组按钮 + 一个清除按钮，按钮顺序 =〔切换按钮, 清除〕（含前置改动）；
- 空集/部分/全选三态下三处按钮文案与点击结果严格符合 1.2 表；全选点击后 SQL 与旧「全选按钮」产物逐字节一致（行为零变更）；
- 单复选框点选后按钮文案即时翻转正确（预览侧依赖 renderSql 收口、弹窗侧依赖三处显式调用，均为重点回归项）；
- XML 动态条件场景按钮共用路径无回归；`clear()` / 场景切换后无陈旧按钮引用（null 断言）；
- 弹窗侧：`applyAllTypes` 对外层「全部/无/类型组合」文案与列表过滤链路零变更；弹窗收起重开文案按当下 `activeFilter` 正确初始化；
- 全量回归绿。

**runIde 走查（留人工）**：亮暗主题下按钮文案切换无闪烁/无标题行跳动（文本长度「全选」↔「全不选」不同宽，FlowLayout 重排观感）；批量 INSERT 大列清单反复点击；清除与切换按钮相邻后的误触观感；类型过滤弹窗保持打开连续勾选/取消，底部按钮文案逐次翻转且弹窗不关闭。

## 六、风险

| # | 风险 | 对策 |
|---|---|---|
| 1 | `renderSql()` 高频路径加同步逻辑拖慢渲染 | 逻辑为两列表 `all{}` + 条件 `setText`，O(列数) 常数级；文案未变不触发重排 |
| 2 | `renderSql()` 存在提前 return 分支导致文案未随勾选更新 | 实施时通读 `renderSql` 分支，若有勾选态已变而提前 return 的路径，则该路径 return 前补调 `syncToggleSelectLabels()`（预计无此路径：三个 listener 均在自身方法内先改态再调 renderSql） |
| 3 | 文案中文两字/三字宽度差致标题行内容左右微移 | 观感项，走查确认；确有困扰再给按钮设 `preferredSize` 宽度恒取两文案较大值 |
| 4 | `XmlPreviewPanelTest` :114 借「全选」按钮做「全不选」的历史错位语义 | F3 明确按当前文案驱动重写断言，不机械替换方法名 |
| 5 | headless 测试环境 `JPopupMenu.show` 行为不稳（无显示设备时 lightweight popup 队列/焦点异常） | F3 断言设计不依赖 popup 真实显示；若 `show` 本身抛错则按预案抽 `buildTypeFilterContent()` 纯构建方法（D6 已备） |
| 6 | 弹窗 `typePopupToggleButton` 陈旧引用在弹窗收起后被误触发 | 唯一触发源是弹窗内 listener，弹窗收起即无可达路径；与 `typePopupCheckBoxes` 既有陈旧引用约定同级，不额外加防护 |

## 七、实施记录

2026-09-12 交付（master 直接实施，沙盒 t-seltoggle 隔离）：

- F1 预览侧：`SqlPreviewPanel` 四字段（selectAll/selectNone + batch 双份）收敛为 `toggleSelectButton`/`batchToggleSelectButton` 两字段；两分区标题行构建单切换按钮（lambda 内 `setAllXxx(!boxes.all{isSelected})` 现算现切）；新增 `toggleLabel(boxes)` + `syncToggleSelectLabels()`（文案未变不 setText），挂在 `renderSql()` 末尾单点收口——通读确认唯一提前 return 是 `current ?: return`（此时按钮本为 null），风险 2 的补调路径不需要。测试访问器收敛为 `toggleSelectButtonForTest()`/`batchToggleSelectButtonForTest()`。
- F2 弹窗侧：`showTypeFilterPopup` 拆出纯构建 `buildTypeFilterContent()`（headless 测试不依赖 popup 显示，风险 5 预案直接采用主方案而非 fallback）；底部双按钮合一，判定 `activeFilter.containsAll(FILTERABLE_TYPES)`；三处显式 `syncTypePopupToggleLabel()`（构建末尾/applyAllTypes 末尾/逐类型 listener）。
- F3 测试：SqlPreviewPanelTest 动态条件/批量两既有流程用例迁移单按钮驱动 + 新增 `testToggleSelectLabelFollowsSingleCheckboxClick`；新建 SqlLogPanelTypePopupToggleTest 6 例；XmlPreviewPanelTest 三处历史错位按钮用法统一 `deselectAllConditions` 帮助函数（内含默认「全不选」文案断言，XML 条件 `!= null` 默认启用全勾已核实）。
- F4 文档：docs/变更记录.md 总册 + docs/MEMORY.md 两表（开发计划索引 + 变更记录）本笔同步。
- 结果：clean 全量 3353/0/6 绿（基线 3346 + 7 新用例）；同笔提交含前置「清除」按钮末位调整（D4）。runIde 真机走查（§五清单）留人工。
