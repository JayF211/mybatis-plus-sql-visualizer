# T-ClickPreviewCopyRestore：单击预览浮层 Ctrl+C 复制失效修复开发计划

> 编号占用：O-121 / R67 / D98~D99 / V122~V123（20261008 grep 实测 O-121/D98+/V122+/R67 全仓未占用）
> 分支建议：`t-clickpreview-copy-restore`（独立工作树，不与在途车道共用）
> 状态：计划待确认——按约定写完停下，未实施

## 一、问题与复现

用户沙盒复现：SQL 日志列表单击一条 SQL → 弹出「单击预览」浮层 → 按 Ctrl+C，剪贴板没有拿到 SQL。用户记忆中之前的版本按 Ctrl+C 可直接复制选中条目，且支持 Ctrl/Shift 多选后批量复制多条。

复现路径三点：

1. 主日志列表单击任意 SQL 条目，等浮层弹出；
2. 不做任何选择，直接按 Ctrl+C → 粘贴，得到的是复制之前剪贴板里的旧内容（复制失败）；
3. 对照老记忆路径：Ctrl+点击多选多条 → Ctrl+C，多选过程会被浮层打断，结果不可预期。

## 二、根因分析

「之前支持」的机制还在代码里，没有被删——它是 [SqlLogPanel.kt:128](../../src/main/kotlin/cn/song/mybatisplus/visualizer/log/SqlEntryList) 的 `SqlEntryList.itemToText` 覆写（Ctrl+C 输出纯 SQL、多选逐条换行拼接）加上 JBList 平台内置的 Ctrl+C 复制链路（CopyPasteManager 写入、按键注册都由平台复用，见该类注释）。坏掉的是这条链路的**可达性**：键盘焦点被预览浮层抢走了。

时间线（git 实测）：

| 时间 | 提交 | 变化 |
|---|---|---|
| 2026-09-08 | `75f2a89` | T-ClickPreview 一轮引入单击预览，**不抢焦点**（「JBPopup 轻量预览不抢焦点点外即关」）——此时代 Ctrl+C 仍直达列表，批量复制不受影响 |
| 2026-09-08 | `66635c3` | T-ClickPreview 四轮：为「弹出即可编辑打字」把弹窗改成 `setRequestFocus(true)`——**焦点改道从此上线**（回归引入点） |
| 2026-09-14 | `b93b36c9` | T-ListPreviewCopy：加「多选 ≥2 行时单击不排程预览」保护 + 右键批量复制 |

根因链三层：

**第一层（主因）：浮层抢焦点后 Ctrl+C 改道。** 浮层弹出即聚焦内部编辑器（`SqlClickPreview.buildPopup` 的 `setRequestFocus(true)` + `setFocusable(true)`）。此后按 Ctrl+C，按键由焦点组件（编辑器）处理：IntelliJ 编辑器的复制动作按选区工作，**无选中时是 no-op**——用户没先圈选文字就直接 Ctrl+C，什么都不会发生。同时列表因焦点不在自己身上收不到 Ctrl+C，`itemToText` 那条批量复制链路在浮层存续期完全不可达。用户视角就是「复制坏了」。

**第二层：多选防误弹只护住了后半程。** `SqlClickPreview.onClick` 的判断是 `selectedIndices.size > 1` 才不排程。Ctrl+点击的**第一击**（选择从 0→1）不满足条件，仍会排程并在 300ms 后弹出浮层抢焦点。用户接着 Ctrl+点第二行时浮层已在屏上：`setCancelOnClickOutside` 会先关浮层，这一次点击是否还透传给列表完成多选是平台行为（待验证前提 2）——无论透传与否，多选节奏被打断、焦点游离，Ctrl+C 的结果都不再可靠。T-ListPreviewCopy 的保护设计时针对的是「已多选后的继续单击」，没覆盖「多选的第一击」。

**第三层：浮层内没有键盘替代路径。** 浮层头部有「复制」图标按钮（走 `UiTextClipboard.copy`，功能正常），但 Ctrl+C 没有绑定到它——键盘用户的习惯路径断了，只剩鼠标按钮一条路。

## 三、修复方案

原则：不回退四轮拍板的「弹出即聚焦可编辑」（`66635c3` 是用户反馈驱动的修复，不能翻案）；不动 `SqlEntryList.itemToText` 纯 SQL 输出契约与 `batchCopySql` 逐字节同构（右键批量与 Ctrl+C 产物一致性红线）。

### S1（D98）：浮层内 Ctrl+C——无选中复制全文，有选中放行原生复制

在 `SqlClickPreview.buildContent` 给编辑器接入 Ctrl+C 语义：

- 编辑器**无选区**：`UiTextClipboard.copy(document.text)`（取编辑后当前文本，与复制按钮同源同内容）+ `flashCopied` 图标反馈，与按钮路径完全一致；
- 编辑器**有选区**：不拦截，交编辑器原生复制（只复制选中片段——保留「可临时改字段值再复制片段」的既有能力）；
- 顺带覆盖 Ctrl+Insert（平台同义复制键），低成本对齐。

实现形态二选一，实施时以沙盒实测为准（优先 b）：

- a. `AnAction` + `CustomShortcutSet(Ctrl+C)` 注册到浮层内容组件——风险在 IDE 按键分派对 custom shortcut 与编辑器内部 InputMap 的先后顺序不确定；
- b. **KeyListener 挂编辑器 content component**：AWT 分派里 listener 先于 InputMap action，不 `consume()` 即放行——纯 Swing 确定性语义，有选中时不消费事件即可，无版本风险。

### S2（D99）：带修饰键的单击不排程预览——多选全程零干扰

`onClick` 签名扩展（转发修饰键），Ctrl/Shift/拖框意图的单击（`CTRL_DOWN_MASK`/`SHIFT_DOWN_MASK`）一律停表关旧、不排程——把 T-ListPreviewCopy 的保护从「≥2 行」前移到「第一击」，多选全程焦点不离列表、浮层永不弹出，Ctrl+C 批量复制恢复到 `75f2a89` 之前时代的行为。

接线点三处核查：

- `SqlLogPanel.kt:1409`（主日志列表 MouseAdapter，`e` 可取修饰键）；
- `HistorySqlLogPanel.kt` 历史 tab 二级列表（同构转发点）；
- **第三宿主核查**：`SqlClickPreview` 类注释声称「主日志/历史 tab 二级/F-7 历史窗」三处共用，实测实例化仅前两处（grep 全仓）。F-7 历史窗（`SqlHistoryManager` 体系，UI 经 `SqlPreviewService`）是否真有列表接了单击预览，实施时落实；若接了且该列表没有 `itemToText` 覆写，那边 Ctrl+C 还在输出 HTML 渲染文本的 T-M42 前老缺陷，须同构补齐（若没有，修正注释）。

### S3（V122）：测试与反向验证

`SqlClickPreviewTest` 扩展（headless 约定不变：`buildContent` 构建不显示、回调直录）：

1. onClick 带修饰键命中真实行 → `isScheduled=false`（不排程）；
2. 已弹出的预览在带修饰键单击后关闭（hide 路径）；
3. S1 拦截器直录：无选中触发 → `copyText` 收到 document 全文；有选中 → 不触发 copyText（放行语义，用判定函数单测）；
4. 既有 10 用例零回退（弹出聚焦可编辑、同源同色、编辑后复制）。

反向验证纪律：临时摘除 S1 拦截器与 S2 修饰键分支 → 对应用例必红，两次读数贴进实施记录。

### S4（V123）：沙盒回验

按用户复现路径三点走查：

1. 单击 SQL → 浮层 → 直接 Ctrl+C → 粘贴得到整条 SQL（格式化态含分号）；
2. Ctrl 多选 N 条（全程无浮层）→ Ctrl+C → 粘贴 N 条逐行拼接，与右键「复制 SQL」批量产物一致；
3. 浮层内圈选片段 Ctrl+C → 只复制片段；弹出即可打字的可编辑性不回退。

## 四、待验证前提

实施者先证伪再动手：

1. **编辑器无选中 Ctrl+C 是 no-op**——按平台默认行为推断；若用户开了「无选中复制整行」类设置，行为不同。以沙盒实测为准（这也决定 S1 是否是必修项，目前判断是）。
2. **JBPopup 弹窗外首击被消费、不透传列表**——影响第二层根因的严重度判定（透传则多选只是慢一拍，不透传则多选被打断）；不影响 S2 的必要性（第一击弹浮层本身就是干扰）。
3. **KeyListener 先于 InputMap action 分派**——Swing 标准语义（listener 不 consume 事件才走 InputMap），高置信；沙盒走查确认编辑器场景一致。
4. **F-7 历史窗第三宿主的存在性**——注释与实测不符（见 S2 接线点第三条）。

## 五、实施顺序与工作量

单车道即可（两件修复都在 `SqlClickPreview.kt` + 两处转发点，紧耦合不拆并行）：

1. S2 先行（签名扩展 + 三处接线 + 守卫用例）——它独立恢复批量复制主路径；
2. S1 跟上（拦截器 + 判定函数 + 直录用例）；
3. S3 定向收口（SqlClickPreviewTest 全绿 + 受影响套件：SqlLogPanel/HistorySqlLogPanel 相关 + 反向验证读数）;
4. testFast 冒烟 + 用户沙盒回验（S4）。

预计改动面：`SqlClickPreview.kt`（主体）、`SqlLogPanel.kt`/`HistorySqlLogPanel.kt`（转发点各一行级）、`SqlClickPreviewTest.kt`、i18n 双语（若无新文案则不动——S1 复用既有按钮反馈，预期零新键）。

## 六、验收标准

- 单击一条 SQL → 浮层弹出 → 直接 Ctrl+C → 粘贴得到该条完整 SQL（与浮层显示一致的格式化态、含结束分号；若用户先编辑过，得编辑后文本）；
- Ctrl/Shift 多选 N 条全程不弹浮层 → Ctrl+C → 粘贴 N 条逐行拼接，与右键批量复制逐字节一致；
- 浮层内圈选片段 Ctrl+C 只复制片段（不全文覆盖）；
- 「弹出即聚焦、可直接打字」的可编辑性不回退（四轮拍板语义）；
- 历史 tab 二级列表同构受益。

## 七、实施记录

分支 `t-clickpreview-copy-restore`（自主仓 master `cf95acc` 拉出）。

**S2（D99）已实施**：`SqlClickPreview.onClick` 签名扩为 `(clickCount, clickPoint, modifiersEx = 0)`（默认参数保既有测试零改动）；顶层纯函数 `isMultiSelectIntent`（只认 `CTRL_DOWN_MASK`/`SHIFT_DOWN_MASK`，真实转发的 `BUTTON1_DOWN_MASK` 位并存不误报）；守卫条件 `selectedIndices.size > 1 || isMultiSelectIntent(modifiersEx)`；`SqlLogPanel`/`HistorySqlLogPanel` 两处转发点带 `e.modifiersEx`。类注释宿主清单修正（F-7 历史窗列表实测不存在，初版三处接线经重构仅存两处）。

**S1（D98）已实施**：实现形态未走计划候选 b（KeyListener）——分析发现编辑器不是普通 Swing 组件，Ctrl+C 在 IDE 层走 keymap action（IdeKeyEventDispatcher 分派），KeyListener 大概率收不到已被 action 消费的按键，待验证前提 3 被证伪。改走平台正规定制点 `EditorActionManager.setActionHandler(IdeActions.ACTION_EDITOR_COPY, ...)`（计划候选 a 的变体，避开 CustomShortcutSet 分派优先级不确定性）：`installCopyHandler` companion 惰性单次全局替换，handler 内按 `PREVIEW_EDITOR_KEY`（编辑器实例 userData）区分——浮层编辑器无选区 → 调 `COPY_ACTION_KEY` 上的共用复制动作（取编辑后文本 + 对号反馈，与头部按钮同一实例）；有选区或非浮层编辑器 → 委托安装时捕获的原生 handler，IDE 全局语义零变化。keymap 中 Ctrl+C 与 Ctrl+Insert 绑同一 action，替换 handler 一处两键生效。`buildContent` 内按钮监听重构为与 handler 共用 `copyCurrent` 闭包。

**S3（V122）**：测试 +6（修饰键判定纯函数含 BUTTON1 位真实组合 / Ctrl·Shift 第一击不排程 / 带修饰键停掉已排程 / Ctrl+C 无选中全文+对号反馈 / 编辑后全文 / 有选中不全文），定向与反向验证读数见下。

**待验证前提核销**：

| 前提 | 结论 |
|---|---|
| 1. 编辑器无选中 Ctrl+C no-op | 成立（平台默认；修复即为此而设） |
| 2. 弹窗外首击是否透传列表 | 不再关键——S2 前移后 Ctrl/Shift 单击根本不弹浮层，透传与否不影响多选 |
| 3. KeyListener 先于 InputMap | 被证伪（编辑器场景走 keymap action 层，KeyListener 不可靠）——S1 改 EditorActionHandler 定制点 |
| 4. F-7 历史窗第三宿主 | 不存在：`SqlClickPreview(` 实例化仅 SqlLogPanel/HistorySqlLogPanel 两处；两列表均为 SqlEntryList（Ctrl+C 纯 SQL 链路同源），类注释已修正 |

**读数回填**：

| 轮次 | 命令 | 读数 |
|---|---|---|
| 首轮 | `test --tests SqlClickPreviewTest` | 编译红：`isEnabled` in EditorActionHandler is final（待验证前提 3 证伪的直接后果，改 `isEnabledForCaret` 修复） |
| 二轮 | 同上 | 22 例 3 失败：243 action 管线拒绝裸匿名 DataContext（`Unknown data context kind`）——测试驱动改 `SimpleDataContext` |
| 三轮 | 同上 | 22 例 1 失败：测试自身时序缺陷（复制后按 tooltip 查按钮，flashCopied 已换「已复制」）——按钮引用前移修复 |
| 四轮（正向终态） | 同上 | **22/0 全绿**（BUILD SUCCESSFUL 1m34s） |
| 反向·摘 S2 | 同上 | **恰好 2 失败**＝testCtrl修饰键单击不排程预览 + test带修饰键单击停掉已排程的预览，其余 20 例保绿（判别力定向） |
| 反向·摘 S1 | 同上 | **恰好 2 失败**＝test编辑器CtrlC无选中复制全文 + test编辑器CtrlC复制用户编辑后的全文，有选中用例保绿 |
| 恢复后 | 同上 | 22/0 全绿（BUILD SUCCESSFUL 42s） |
| testFast | `testFast --rerun` | **3406/0/0 真跑全绿**（BUILD SUCCESSFUL 1m38s；首跑 24 任务 up-to-date 未执行，`--rerun` 强制真跑后读数方有效） |

**实施偏差三处**：

1. **S1 实现形态改 EditorActionHandler 定制点**（计划候选 a 变体）：计划候选 b（KeyListener）基于纯 Swing 语义，分析阶段即被证伪——编辑器按键走 IDE keymap action 层（`IdeKeyEventDispatcher`），KeyListener 收不到已被 action 消费的 Ctrl+C。覆写点首轮写成 `isEnabled` 触碰 243 final 编译红，javap/源码核验后改 `isEnabledForCaret`（243 源码明确标注 implementations override this）。
2. **测试驱动两坑**（headless 新登记）：①243 action 管线拒绝裸匿名 DataContext（`Utils.reportUnexpectedDataContextKind` 硬抛 PluginException，HTML 报告指路三选一），须用 `SimpleDataContext`；②`flashCopied` 会把按钮 tooltip 短暂换「已复制」（1500ms），复制后按 tooltip 找按钮必落空——断言用按钮引用须在复制动作**之前**抓取。
3. **testFast UP-TO-DATE 假绿规避**：`testFast` 无 `outputs.upToDateWhen { false }`（testExternal 有），本轮首跑 24 任务 up-to-date 未执行任何测试——`--rerun` 强制真跑后读数方为有效（终态读数见上表 testFast 行）。

## 八、二轮实施记录（真机反馈「问题没有解决」后重定位）

**触发**：一轮修复随 master 合并（`d332744`）后用户复测仍报「复制不成功」。

**证据链（三处实测 + 一处源码核验）**：

1. **构建核对**：沙盒插件 jar（`mybatis-plus-sql-visualizer-2026.10.9.jar`，09:27:10 构建）解包实测含 `installCopyHandler` / `isEnabledForCaret` / `isMultiSelectIntent` —— 用户跑的确含一轮修复；用户日常 IDE 装的是 2026.10.7 旧版（无修复），两环境在 09:26~09:30 均有 SQL 日志活动。
2. **headless 复现**：新增「平台真实调用点」用例（`EditorEx.copyProvider.performCopy(ctx)` + 真实 `UiTextClipboard` 注入）在修复下**绿**（剪贴板得全文）→ 一轮修复在「provider 被调用」这条链上有效。
3. **但真实按键未必走那条链**（本轮真根因）：`$Copy`（keymap Ctrl+C 绑定，`$default.xml` 实测只有 `$Copy`）先经 DataContext 取 `PlatformDataKeys.COPY_PROVIDER`；而 `EditorComponentImpl.uiDataSnapshot`（243 源码核验）**只提供 `CommonDataKeys.EDITOR`/`CARET`，不提供 COPY_PROVIDER**（`PlatformDataKeys`/`CopyProvider` 命中 0），`EditorImpl.MyEditable.performCopy` 只在有人拿到编辑器的 provider 时才会被调 —— **provider 拿不到时 `$Copy` 不可用，事件落回 Swing 绑定**；编辑器组件是 JTextComponent，**自带的无选中 copy 是 no-op** → 用户现象「浮层里按 Ctrl+C 毫无反应」。一轮的 `EditorCopy` handler 修复挂在这条真机不会走到的链上（headless 直调才「绿」＝假绿形态）。
4. **列表侧勘正**：`JBList.installDefaultCopyAction` 只装 ActionMap["copy"]（`doCopyToClipboardAction` = 选中条目逐条 `itemToText` 换行拼接）；Ctrl+C 的**按键绑定来自平台 LAF**（`List.focusInputMap`，headless 实测 `inputMap.get(control C)` 直接命中 "copy"）。早先据 `InputMap.keys()==null` 判「无绑定」是懒加载 InputMap 的假象；二轮曾据此补绑，反向验证发现摘除后绑定仍在＝冗余，**已回退**（类注释同步勘正）。结论：列表 Ctrl+C 本就可达，断点在浮层抢焦点后焦点组件（编辑器）的 Swing 层。

**修复（二轮）**：
- `SqlClickPreview.buildContent`：在**焦点组件自身（WHEN_FOCUSED）**覆盖 Ctrl+C / Ctrl+Insert 绑定（`editor.component.inputMap.put` + 同名 ActionMap）——无选中复制全文、有选中只复制选区（保留改字段值后复制片段），与头部按钮同反馈（对号）；与一轮的 `installCopyHandler`（keymap 路径）共用同一复制意图，**两条路径归一**，不再依赖对平台内部分派路径的判断。
- `SqlEntryList`：冗余补绑回退，注释勘正为「绑定来自平台 LAF」。

**读数**：

| 轮次 | 命令 | 读数 |
|---|---|---|
| 定向（二轮终态） | `test --tests SqlClickPreviewTest` | **26/0 全绿**（原 22 + provider 直调 1 + Swing 绑定 3），BUILD SUCCESSFUL 1m18s |
| 反向验证 | 摘除两处 Swing InputMap 绑定 | **2 红**＝test编辑器CtrlC绑定覆盖默认copy_无选中复制全文 + test编辑器CtrlC有选中只复制选区；**列表用例未红** → 证明平台 LAF 本就提供列表绑定（二轮补绑冗余，据此回退） |
| testFast（二轮终态） | `testFast --rerun` | **3406/0/0**（报告时间戳 10:00:21 实证真跑，非 UP-TO-DATE 空转） |

**二轮未覆盖（交真机）**：沙盒 GUI 自动化在本机不可用（CUA 对该 IDE 窗口的点击/按键不落地：坐标基准为屏幕像素但合成事件未被应用接收，a11y 树仅窗口级），故「浮层抢焦点后的真实按键落点」由用户在沙盒/真机复验（S4 三点）。

**V123 沙盒回验**：待用户（或 runIde 窗口期）按计划 §三 S4 三点走查。
