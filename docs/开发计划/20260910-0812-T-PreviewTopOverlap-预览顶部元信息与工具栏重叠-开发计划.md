# T-PreviewTopOverlap：SQL 预览顶部重叠修复 + 低频按钮折叠开发计划

> **创建日期**：2026-09-10（同日追加折叠需求，v2）
> **分支**：master（HEAD `299607e`；工作树仅其余会话在途的文档改动，无代码在途）
> **优先级**：P1（用户真机截图实证的样式缺陷，预览弹窗是插件主入口门面）
> **预估工时**：5~6h（F1 结构修复 + F2 工具栏折叠 + F3 截断兜底 + F4 回归测试，单笔提交）
> **来源**：用户真机截图（2026-09-10，IDEA 2026.2.1）——预览弹窗顶部两行元信息直接压进右侧按钮行；同日追加需求——把不常用、不重要的按钮折叠收纳

---

## 一、问题定位与根因

### 1.1 现象（截图取证）

窄弹窗下，顶部元信息两行文字与右侧工具栏按钮互相叠压：

- 第一行「类型: LAMBDA_QU…」在按钮左缘被截断；
- 第二行性能建议「⚠ ORDER BY 无 LIMIT —— 可能返回大量结果集，建议添加 LIMIT」横穿整个按钮行：截图里「—— 可能」一段被「压缩」按钮盖住，紧邻的「返回大量结果集」一段反而画在按钮上面——同一帧两种叠压方向并存，说明重叠区内双方按各自重绘时序互相覆盖，谁在上取决于哪块脏区先重画。

### 1.2 代码链路

| 位置 | 内容 |
|---|---|
| SqlPreviewPanel.kt:256-263 | `topRow`：BorderLayout，`metaLabel` 加 **WEST**（:258），`toolbar()` 加 **EAST**（:259） |
| SqlPreviewPanel.kt:381-407 | `toolbar()`：BoxLayout X_AXIS，7 个常驻文本小按钮（压缩/格式化/关键字换行/导入/差异对比/分享/刷新），警示时再常驻「复制错误」第 8 个（:384-390） |
| SqlPreviewPanel.kt:2295-2329 | `updateMeta()`：meta 单行 + 性能建议行拼成两行 HTML（:2313-2318 `<html>$meta$warningLine<br>$perfLine</html>`），tooltip 写全文（:2320） |
| SqlPreviewPanel.kt:2335-2349 | `buildPerfLine()`：性能建议含 `<font color>` 标签的单行 HTML |

### 1.3 根因三层

**① 结构性缺陷（直接机制）**：AWT BorderLayout 的契约是 WEST/EAST 各按自身 preferred 尺寸整块贴边、绝不收缩；可用宽度不足时只把 CENTER 夹成 0 甚至负宽，WEST 与 EAST **直接互相重叠**（`java.awt.BorderLayout.layoutContainer` 的既定行为，不是 bug）。z 序上先 add 的 metaLabel 是 index 0、画在最上层（`JComponent.paintChildren` 自尾向头遍历），所以整体倾向文字盖按钮；重叠区内哪一方先重画还会产出截图那种混合帧。不论谁在上，重叠本身就是缺陷。

**② 元信息宽度无上限**：metaLabel 的 preferred 宽 = HTML 最宽行的全文宽，没有任何截断约束。性能建议一行动辄 300~450px（如 `perf.order_by_without_limit`，MpSqlVisualizerBundle_zh.properties:94）；XML 预览的 statementId/parameterType 走全限定名（:2301-2302），数百 px 常见。对比之下左栏复选框文本在 T-M26.3 已按视口宽度截断（:1989-2007），元信息行漏了同款治理。

**③ 工具栏持续膨胀**：常驻按钮已涨到 7 个，preferred 宽约 450px；出现解析警示时「复制错误」第 8 个再挤入。

**触发面**：弹窗尺寸经 DimensionService 记忆（SqlPreviewDialog.kt:84 `DIMENSION_KEY`），用户拖窄一次即持久保持窄弹窗，下限 640（:87，经 :39 minimumSize 兜底）。640 宽下顶部行可用约 600px，而 420（元信息）+ 450（工具栏）≈ 870px——必然重叠；默认 900 宽叠加长 perf 行或 XML 长 statementId 同样可破。

**既有缓解为何无效**：:2311 注释「性能建议文案单独一行（HTML 换行，避免与右侧工具栏重叠）」——分行只降行高不降宽度，WEST 依旧按最宽行占位。两行 HTML（T-SqlPerf `14988db6` 引入）只把纵向撑高问题换成了今天的横向重叠。

**复现路径**：预览一条 ORDER BY 无 LIMIT 的 SELECT（触发性能建议行），弹窗拖窄一次（尺寸即被记忆）→ 必现。

---

## 二、方案设计

### 2.1 F1：metaLabel WEST → CENTER（结构修复，必做）

`topRow` 布局把 metaLabel 从 WEST 迁到 CENTER，工具栏保持 EAST。BorderLayout 的 CENTER 只拿剩余宽度，与 EAST **结构性零重叠**——一行改动消除重叠机制本身，两种叠压方向同时消失。

被压窄后 HTML 视图按组件实际宽度重排换行（BasicHTML 在 paint 时按组件 bounds setSize），行数变多、超出组件高度的部分被裁剪，不会越界推挤按钮；全文 tooltip 已有（:2320）。行高观感由 F3 兜底：截断后文本恒定两行内；宽度压力另由 F2 折叠进一步缓解（工具栏 450px → 约 250px）。

配套微调：metaLabel 垂直居中（改 CENTER 后与按钮行对齐更协调），WEST 边框语义（:256）随迁移并入 CENTER 侧。

### 2.2 F2：工具栏低频按钮折叠进「更多」菜单（追加需求）

**分组决策**（按使用频率与反馈语义一刀切，不做设置项）：

| 去向 | 按钮 | 理由 |
|---|---|---|
| 直显保留 | 压缩/多行（modeButton） | 模式切换是预览主交互，且文案随状态双向变化，藏进菜单后状态不可见 |
| 直显保留 | 格式化 | 高频整理动作，另有 Ctrl+Alt+L 快捷键同源（T-M26.6） |
| 直显保留 | 关键字换行（用户定版） | 格式化的高频变体，用户指定保留直显 |
| 直显保留 | 刷新 | 「回到默认状态」兜底语义（M19.3），界面看着不对劲时必须一键可达 |
| 直显保留（条件） | 复制错误 | 仅警示时可见，错误反馈链路一环；flashCopied 对号反馈依赖 JButton（:386-388），折叠降低恰当时机的可发现性——**永不折叠** |
| 折叠进菜单 | 导入参数 | P-05 偶发场景 |
| 折叠进菜单 | 差异对比 | O-4 偶发场景 |
| 折叠进菜单 | 分享 | F-8 偶发场景 |

折叠后常驻形态：`[复制错误(警示时)] [压缩] [格式化] [关键字换行] [刷新] [更多]`，工具栏 preferred 宽从约 450px 降到约 330px，窄弹窗下元信息预算增至约 240px 起步（F3 截断兜底极端场景）。

**实现路线：保留 JButton + JBPopup 列表菜单**（不迁 ActionToolbar）：

- 「更多」按钮复用 `smallButton()` 惯例（:763-766）的纯文本 JButton，点击弹 `JBPopupFactory.getInstance().createListPopup(BaseListPopupStep)`——该路线本项目已有两处真机实证（SqlClickPreview.kt:146、DataSourceSelector.kt:2577-2595），包路径与本机裁剪发行版的兼容性被现网验证过，零新增 API 风险；
- 菜单三项（导入参数/差异对比/分享）顺序与原工具栏一致，onChosen 直接委托面板既有方法（importParamsFromClipboard / showSqlDiff / showShareDialog），动作语义与原按钮一字不差；
- **不迁 ActionToolbar 的理由**：① 七按钮迁 AnAction 组要放弃 errorCopyButton 的 flashCopied JButton 语义；② 「按钮放不下收进 overflow popup」的行为 API（ActionToolbarImpl 一族）在 243/262 裁剪发行版的可用性须逐个 javap 核实，收益不成比例；③ 折叠分组已按频率一刀切定死，不需要 ActionToolbar 的动态 overflow 能力；
- i18n：新增 `panel.more`（更多 / More）键，两份 bundle 同步（`MpSqlVisualizerBundle.properties` panel.refresh :76 附近 + `_zh` :76 同位置）。

**明确不做**：按弹窗宽度自适应增减折叠档位（确定性分组可预期，自适应徒增状态面）；用户自定义按钮排序/显隐设置（可另立项）。

### 2.3 F3：元信息按可用宽度截断（兜底打磨）

F1+F2 落地后，640 窄弹窗下元信息预算约 350px，常规 perf 行已放得下；本项降级为极端场景（XML 长 statementId/parameterType 可达数百 px）的兜底，消除「HTML 压缩换行残行」观感：

- **宽度预算** = topRow 实际宽 − 工具栏 preferred 宽 − topRow 左右边距（:261，共 16）− metaLabel 自身边距（:256，共 16）。
- **截断范式复用 T-M26.3**：与左栏复选框标签截断（:1989-2007，字体度量 + 中段省略）同一套路——meta 段（纯文本）超预算时中段省略；perf 段含 `<font color>` 标签，超预算时在 HTML 标签外尾部加「…」，不在标签内部切割。tooltip 恒全文，与 :2320 现状一致。
- **触发时机**：topRow 挂 `ComponentListener`（componentResized / componentShown）——纯 Swing 路径，不涉 ContentManagerListener 失效、ActionToolbar 刷新不可靠等平台陷阱。
- **防抖**：记录上次预算宽度，未变化不重算；字体度量本身 O(文本长)，无性能面。

### 2.4 F4：布局与折叠回归测试

仿面板既有 UI 测试惯例（metaLabelForTest :2452、errorCopyButtonForTest :2454），新增 internal 访问器 `topRowForTest()` / `toolbarForTest()`，用例：

1. 窄宽（640）下 setBounds + doLayout 后，断言 metaLabel 右界 ≤ 工具栏左界（零像素重叠——本缺陷的核心回归断言）；
2. 超长 meta / perf 文本下 tooltip 仍为全文；
3. 警示在场时「复制错误」按钮可见且**不被折叠**（errorCopyButton 语义不回归）；
4. 截断后渲染文本宽度 ≤ 预算；
5. 折叠后直显按钮集合恰为 压缩/格式化/刷新/更多（+警示时复制错误），「更多」文案取自 `panel.more`；
6. 「更多」菜单恰三项、顺序与原工具栏一致，逐项断言委托到 importParamsFromClipboard / showSqlDiff / showShareDialog；关键字换行断言仍为直显按钮——BaseListPopupStep 可直接构造断言 items 与 onChosen，无需真实弹层。

### 2.5 备选与边界（明确不做）

- **备选 B（评估后不采纳）**：工具栏迁 `ActionToolbar` + 平台 overflow「更多」弹出——理由见 2.2（flashCopied 语义、裁剪 API 核实成本、分组既定无需动态 overflow）。
- **备选 C（不采纳）**：弹窗最小宽提到 900——治标，XML 长 statementId 仍可破，且强占小屏空间。
- **边界**：不动 DimensionService 尺寸记忆与 640 最小宽；不动 `updateMeta` 的内容拼装（只管显示宽度）；折叠分组按频率一刀切定死，不做宽度自适应档位与用户自定义配置。

---

## 三、实施顺序

1. F1 布局迁移（一行，先行自测）；
2. F2 工具栏折叠：「更多」按钮 + popup 菜单 + i18n（分组展示依赖 F1 后的宽度格局）；
3. F3 截断兜底；
4. F4 测试 + internal 访问器；
5. 定向测试 + 全量回归（当前基线约 3188 tests）；
6. runIde 真机走查（留人工）：窄弹窗 + 性能建议行（本次截图场景）、XML 长 statementId、解析警示 + 复制错误按钮在场、亮/暗主题切换、弹窗拉伸/还原、「更多」菜单三项动作逐一等效验证。

---

## 四、测试与验收

### 4.1 验收标准

- 任意弹窗宽度（640 ~ 最大）下元信息与工具栏零像素重叠，F4 断言绿；
- 「更多」菜单三项动作与原按钮等效，常用四按钮（压缩/格式化/关键字换行/刷新）仍一键直达，复制错误永不折叠；
- 超宽文本截断省略 + tooltip 全文可读，性能建议行颜色语义（红/蓝）保留；
- 压缩/多行切换、格式化、复制错误对号反馈、刷新复位等按钮功能与反馈不回归；
- 全量回归绿；发版时 verifyPlugin 三版本例行门禁。

### 4.2 真机走查清单（runIde，留人工）

按「复现路径」逐条对照截图场景：拖窄 → 文本截断 → tooltip 全文 → 拉宽恢复全文；「更多」展开三项动作各执行一遍确认等效；XML 预览长 statementId 场景同走一遍。

---

## 五、API 权威核实清单与风险

| # | 疑点 | 核实方式 |
|---|---|---|
| 1 | JBLabel HTML 被压窄后的重排/裁剪行为（BasicHTML view 按组件宽度重排，preferred 高度仍按全宽计算） | JDK 源码 BasicLabelUI/BasicHTML 通读 + runIde 实测；F3 截断不依赖该行为的精确性，仅作观感兜底 |
| 2 | JBPopupFactory.createListPopup + BaseListPopupStep（折叠菜单） | 本项目已有两处同 API 现网先例（SqlClickPreview.kt:146、DataSourceSelector.kt:2577-2595），先例即权威；实施时照抄包路径与构造方式 |
| 3 | 截断度量 API | 直接复用 :1997-2007 既有 truncateMiddle 实现与字体度量常量，零新平台 API，无裁剪发行版风险 |
| 4 | ComponentListener 生命周期 | 监听器挂 topRow 自身（面板内部组件），面板随弹窗 Disposer 树级联释放（SqlPreviewDialog.kt:35），无独立泄漏面 |

风险：① HTML 自动换行的中间态观感（换行残行）——F3 截断兜底消除；② 低频功能折叠后可发现性下降——用户主动要求收纳，菜单项文案与原按钮一字不差，后续可在设置页补一句说明；③ resize 高频触发重算——宽度未变短路 + 度量廉价；④ 跑 `./gradlew` 前例行 `parallel_tasks.py check` 并行登记。

---

## 附录：截图取证存档

- 原始截图：`C:\Users\USER\.zcode\cli\image-cache\sess_2b498de0-345e-4345-9a1d-19232cdb141c\image-f3f01aaaaa0e9b5bb33955908ca8a90a.png`（679×487）。
- 关键证据：第二行性能建议「⚠ ORDER BY 无 LIMIT」与「返回大量结果集，建议添加 LIMIT」两段分别止/起于「压缩」按钮左右缘（约 x=147/203），按钮区下方左右两侧叠压方向相反——BorderLayout WEST/EAST 重叠 + 重绘时序竞态的典型混合帧。
- 元信息文本对应：第一行 = `panel.meta.type` + WrapperType 名（:2297）；第二行 = `perf.order_by_without_limit`（buildPerfLine，:2335）。

---

## 实施记录（2026-09-10 回填）

F1~F4 全部落地（工作树，未提交）：

- **F1**：`SqlPreviewPanel.kt` init 顶部行 metaLabel WEST→CENTER；`topRow`/`toolbarPanel` 提升为字段（布局断言与预算计算用）。
- **F2**：`toolbar()` 直显收敛为 复制错误(警示时)/压缩/格式化/关键字换行/刷新/更多；导入参数/差异对比/分享折叠进「更多」JBPopup 列表菜单（`moreActions`/`moreActionsStep`/`showMoreActionsPopup`，锚定按钮下方，RelativePoint 先例 SqlClickPreview）；i18n `panel.more` 两 bundle :78。分组经用户确认：**关键字换行保留直显**。
- **F3**：`updateMeta` 重写为原文分段缓存（`metaLineText`/`metaPerfSegments`）+ `applyMetaTruncation` 宽度预算截断——预算 = topRow 宽 − 工具栏理想宽 − 行边距 16 − 标签边距 16，下限 80px（`MIN_META_BUDGET_PX`）；meta 行复用 T-M26.3 `ellipsize` 中段省略，perf 行按像素逐段累计、丢尾段加省略号、首段放不下尾部省略；未布局（width≤0）先展全文，布局后由 ComponentListener（componentResized/componentShown）按预算重截；tooltip 恒全文（顺带修复旧实现 tooltip 含 `<font>` 标签原文直显的瑕疵）；`clear()` 复位缓存。
- **F4**：新增 `SqlPreviewTopRowLayoutTest` 8 用例（窄宽零重叠 / 最小宽不塌缩 / 窄截断生效 / 宽幅还原全文 / tooltip 恒全文 / 直显集合与菜单三项断言 / 复制错误不折叠 / step 未命中安全返回 FINAL_CHOICE）；`SqlPreviewPanelImportTest.testImportButtonExists` 按折叠语义改写（导入不再直显，改断言菜单项在场）。
- **测试**：定向 6 类 49 用例绿；全量回归 **3196/0/0 绿**（BUILD SUCCESSFUL 3m25s，基线 3188 + 新增 8）。runIde 真机走查留人工：截图场景（窄弹窗 + ORDER BY 无 LIMIT perf 行）、「更多」菜单三项动作等效、亮/暗主题、弹窗拉伸还原。
