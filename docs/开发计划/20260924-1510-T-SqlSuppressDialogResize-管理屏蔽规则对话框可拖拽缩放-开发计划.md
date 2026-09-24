# T-SqlSuppressDialogResize 管理屏蔽规则对话框可拖拽缩放 — 开发计划

- 日期：2026-09-24 15:10
- 任务编号：**O-80**（占号前已实测 O-80 空闲；全仓 grep 零命中）
- 来源：用户走查（附截图）——「管理 SQL 屏蔽规则 弹框只要有内容就没办法拖拽缩放弹框大小」；用户点名**在主分支上**分析与出计划（实施亦应在 master，小改动面）
- 性质：UI 可用性（单文件小改；不触屏蔽闸门口径、无新 EP/反射面，非大改动）
- 基线：master `728577e`（dev 回流后同点；回流门禁复跑另行进行中，与本计划无耦合）

---

## 一、根因分析（已定案，代码实读非推测）

1. **平台默认不可缩放，本对话框从未开启**：IntelliJ `DialogWrapper` 的 resize policy 默认为 fixed——不显式 `isResizable = true` 弹窗就不可拖拽缩放。`SqlSuppressManagerDialog` 全文 grep `setResizable` 零命中，`init` 里还有 `setSize(DIALOG_WIDTH, DIALOG_HEIGHT)`（760×480）每次打开钉死尺寸。这才是「没办法拖拽缩放」的全部原因——**与有没有内容无关**：空态同样不可缩放，只是有内容时用户才有缩放动机，问题这才暴露。
2. **尺寸不记忆**：未覆写 `getDimensionServiceKey()`，即使开启缩放，关开之间尺寸也会回到 760×480。
3. **仓内惯例齐备（实施=照抄，非自创形态）**：
   - `feedback/ErrorReportDialog.kt` KDoc 明文：「拖拽：isResizable=true + getDimensionServiceKey 记忆尺寸，描述/堆栈行 weighty=1 + BOTH 随拖拽纵向伸缩」——本插件的可拖拽对话框标杆；
   - `ui/RollbackSqlDialog.kt:361`、`ui/SqlPreviewDialog.kt:80` 均有 `DIMENSION_KEY` 尺寸记忆；
   - `ui/SqlPreviewDialog.kt:38-39`：**DialogWrapper 无 setMinimumSize，最小尺寸兜底设在中心面板** `panel.minimumSize = Dimension(MIN_WIDTH, MIN_HEIGHT)`（防拉伸时缩没）。

## 二、方案（预计 ±10 行，单文件）

| # | 动作 | 说明 |
|---|---|---|
| F1 | `isResizable = true` | 根因修复（init 内一行） |
| F2 | `override fun getDimensionServiceKey(): String` | 常量 `"mpsv.sqlsuppress.manager.dialog"`；尺寸/位置记忆走平台 DimensionService，零自持久化。**取代原 O-69 计划 F5「记住尺寸不做」的表述**——用户已提出缩放诉求，按惯例记忆尺寸一并落 |
| F3 | 中心面板 `minimumSize` 兜底 | SqlPreviewDialog 同款；最小值以「按钮行不折行 + 编辑器/列表可读」为下限（量级建议 560×360，实施时真机观感定） |
| F4 | 回归确认 | Splitter 左右分栏随窗口缩放自适应（scroll pane 内容），无需额外改动；既有屏蔽链定向测试全绿即可 |

## 三、待验证前提（实施者先证伪）

1. **非模态 MODELESS 下 resize policy 生效形态与模态一致**——ErrorReportDialog 为模态先例，本对话框 `DialogWrapper(project, false, IdeModalityType.MODELESS)`；平台 resize 判定与模态性无关（代码可判），真机确认一次即可。
2. **按钮行是否成为左栏最小宽度支配项**：按钮行（新增/取消新增/删除/清空/开启结构匹配/全部按结构匹配 共 6 控件）在左栏 SOUTH，其首选宽会顶住 F3 的最小宽。若真机发现「缩不到可读下限以下」或「按钮折行错乱」，备选 = 按钮行换仓内既有 WrapLayout（日志面板同款，窄容器自动折行且标签+控件同进退）。
3. **DimensionService 键的作用域**：照 ErrorReportDialog 用法即与先例一致（键字符串直接用，不研究作用域差异）。
4. **分裂条 proportion**：窗口缩放时左右栏按 0.38 等比伸缩（Splitter 既有行为），确认无异常即可，不做持久化（见 D2）。

## 四、验收标准

1. 拖拽边角可自由改变对话框大小；**空态与有内容（多规则 + 编辑器文本）两态均可**，缩放后左右分栏、滚动条、按钮行均可用。
2. 关闭重开对话框，尺寸与上次拖拽后一致（DimensionService 生效）；首次打开（无记忆）仍为 760×480。
3. 缩到最小尺寸：按钮行完整可用（不折行错乱或按备选方案折行规整）、编辑器与规则列表仍可读可操作。
4. 屏蔽链定向测试（Manager/Buffer/Models/PanelFilter）全绿——本改动不触闸门与匹配口径；无新 bundle 键（无需 parity）。

## 五、决策点

| # | 决策 | 选项 | 倾向 |
|---|---|---|---|
| D1 | 尺寸记忆 | A 做（DimensionService，照 ErrorReportDialog/RollbackSqlDialog 惯例）/ B 只 resizable 不记忆 | **A**（零成本、惯例一致） |
| D2 | 分隔条位置记忆 | A 不做（每次按 0.38 重置）/ B 自持久化 | **A**（低频操作，自持久化成本不匹配） |
| D3 | 按钮行布局 | A 维持现单行（靠 minimumSize 兜底）/ B 换 WrapLayout | 实施时真机观感定，默认 A |

## 六、风险

- 改动面极小（单文件 ±10 行），无生产链路影响；不触门禁敏感面（无新 EP/反射/依赖）。
- 唯一可用性风险 = 最小尺寸与按钮行的挤压交互（前提 3），已在 F3/D3 覆盖。

## 七、实施记录

（2026-09-24 主分支实施完成，用户指令「主分支直接实施」。）

| 步骤 | 落地 |
|---|---|
| F1 `isResizable = true` | init 内，注释引 ErrorReportDialog 惯例原话；`setSize` 保留为首次打开（无 DimensionService 记忆）缺省 |
| F2 尺寸记忆 | `getDimensionServiceKey() = "mpsv.sqlsuppress.manager.dialog"`（DIMENSION_SERVICE_KEY 常量）——取代原 O-69 计划 F5「记住尺寸不做」表述（D1=A） |
| F3 最小尺寸兜底 | 中心面板（Splitter）`minimumSize = 560×360`（MIN_WIDTH/MIN_HEIGHT 常量），SqlPreviewDialog 同款（DialogWrapper 无 setMinimumSize）；D3 默认 A（按钮行维持单行，真机观感待用户复查） |
| F4 回归 | 定向 Manager 16/0 + Buffer 54/0 + Models 11/0（编译+屏蔽链无回归；本改动不触闸门口径） |

**过程记录**：①`Dimension` import 曾在白边返修（去 preferredSize hack）时随之清掉，F3 落地时补回（编译期红拦截，0 风险）；②沙盒隔离照例（MPSV-SuppressResize，用后还原零残留）。

**真机留人工**（计划 §四 验收 1/2/3 的三态）：拖拽缩放两态可用、重开尺寸记忆、最小尺寸下按钮行不折行错乱——headless 测不到观感，待用户重出包（runIde/zip）复查；若按钮行挤压错乱触发 D3-B（换 WrapLayout），另记一笔小改。
