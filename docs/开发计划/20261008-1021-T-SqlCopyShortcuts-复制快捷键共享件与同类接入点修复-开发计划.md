# T-SqlCopyShortcuts：复制快捷键共享件与同类接入点统一开发计划

> 编号占用：O-122 / R68 / D100~D103 / V124~V127（20261008 grep 实测全仓未占用）
> 来源：用户指令「再看下其他类似 sql 列表的地方是否存在相同问题，有的话一起修」+「把『无选中复制全文、有选中复制片段』抽成共享小工具，能复用就出计划并实施」
> 前序：[[T-ClickPreviewCopyRestore]]（master `d332744` 一轮 + `8bc5406` 二轮）已修单击预览浮层；本任务做**横向铺开与去重**
> 状态：计划已出，按用户令直接实施

## 一、排查结果（全仓 SQL 列表与 SQL 编辑器逐处判定）

判定依据（二轮已源码实证）：① 编辑器上 Ctrl+C 经 keymap `$Copy` 取 `PlatformDataKeys.COPY_PROVIDER`，而 `EditorComponentImpl.uiDataSnapshot` **不提供** COPY_PROVIDER ⇒ 事件落回 Swing、JTextComponent 自带的无选中 copy 是 no-op ⇒ **任何未注册 Ctrl+C 的编辑器，无选中按 Ctrl+C 都无反应**；② 列表 Ctrl+C 的按键绑定来自平台 LAF，动作落到 `JBList` 的 ActionMap["copy"]（= `itemToText` 逐条拼接）⇒ **未覆写 `itemToText` 的列表，复制出的是渲染组件文本而非条目正文**。

### 编辑器（`EditorFactory…createEditor` 全部 9 处）

| 位置 | 现状 | 本轮动作 |
|---|---|---|
| `log/SqlClickPreview.kt:290`（单击预览浮层，可写） | 二轮已手写 WHEN_FOCUSED 绑定 | **改用共享件**（消除唯一的手写实现） |
| `ui/SqlPreviewPanel.kt:2950/2952`（主预览面板 SQL 编辑器，可写；双击预览/多入口） | 无绑定 | **接入** |
| `log/RecentExecutionsDialog.kt:186`（「最近执行记录」右侧只读高亮编辑器） | 无绑定 | **接入** |
| `log/SqlSuppressManagerDialog.kt:255`（屏蔽规则 SQL 编辑器，viewer/新增时临时可写） | 无绑定 | **接入** |
| `ui/RollbackSqlDialog.kt:128`（回滚 SQL 编辑器，viewer） | 无绑定 | **接入** |
| `ui/SqlInputEditor.kt:67/77`（SQL → Java 输入区，可写，同方法两分支） | 无绑定 | **接入** |
| `ui/ParamImportDialog.kt:105`（参数导入 **JSON** 编辑器） | 无绑定 | **接入**（非 SQL 但同型，复制全文需求一致） |

### 列表（`JBList`/`JList` 全部 7 处）

| 位置 | 条目类型 | 现状 | 本轮动作 |
|---|---|---|---|
| `log/SqlLogPanel.kt:134` `SqlEntryList`（主日志 + 历史二级共用） | `LogSqlEntry` | 已覆写 `itemToText` = 纯 SQL（多选换行拼接） | **改为继承共享基类**（语义不变） |
| `log/RecentExecutionsDialog.kt:79` `entryList` | `LogSqlEntry` | **未覆写 ⇒ Ctrl+C 复制渲染文本**（同类问题） | **改用 `SqlEntryList`** |
| `ui/SqlSelectionDialog.kt:61` `list`（多 SQL 候选选择弹窗） | `Row(summary, detail)` | **未覆写 ⇒ 复制出渲染组件文本**（同类问题） | **不做**，登记候选 R68（见 §四） |
| `log/HistorySqlLogPanel.kt:65` `fileList` | `HistoryFileEntry` | 未覆写（复制条目渲染文本） | 不做：非 SQL 条目，复制价值低 |
| `ui/StackFrameHover.kt:144` `list` | `StackFrameEntry` | 未覆写 | 不做：非 SQL（调用栈帧） |
| `log/SqlSuppressManagerDialog.kt:73` `ruleList` | `SqlSuppressRule` | 未覆写 | 不做：规则名列表，非 SQL 正文 |
| `config/PluginSettingsFolderConfigurable.kt:52` `list` | 设置项 | 未覆写 | 不做：设置页非 SQL |

## 二、复用性结论（回答用户提问）

**可以复用，且收益明确**：

- **编辑器侧 7 处 + 潜在后续接入共用 1 个安装件**——「无选中复制全文 / 有选中复制片段 / Ctrl+Insert 同义键 / 可选复制后反馈」是一套与「编辑器里装什么」无关的行为契约，写成 `EditorCopyShortcuts.install(editor, copyText, onCopied?)` 后每处只有一行接线；浮层那份二轮的手写实现随之删除（**净减代码**）。
- **列表侧 3 处共用 1 个基类**——`TextCopyList<E>`（覆写 `itemToText` 委派给 `copyTextFor(element)`）；`SqlEntryList` 与「最近执行记录」列表共用「纯 SQL + 结尾分号」语义。
- 不抽的代价：同类问题已在本仓出现三次（浮层、最近执行记录列表、候选选择弹窗），每处都靠各自觉悟去绑 → 正是本轮要做成共享件的理由。

## 三、方案

### S1（D100）`util/EditorCopyShortcuts.kt`

```kotlin
internal object EditorCopyShortcuts {
    /** 在焦点组件自身的 WHEN_FOCUSED InputMap 上覆盖 control C / control INSERT：
     *  无选区 → editor.document.text（含用户编辑后文本）；有选区 → 选区文本；
     *  复制后回调 onCopied（用于闪对号之类的反馈，null = 无反馈）。 */
    fun install(editor: Editor, copyText: (String) -> Unit, onCopied: (() -> Unit)? = null)
}
```
- 落点必须是**焦点组件本身**的 InputMap（同 key `put` 覆盖 JTextComponent 默认绑定）——二轮实证的正确层位；
- `copyText` 由调用方注入（生产传 `UiTextClipboard::copy`，测试直录断言）。

### S2（D101）`util/TextCopyList.kt`

```kotlin
internal abstract class TextCopyList<E>(model: ListModel<E>) : JBList<E>(model) {
    /** 条目复制文本（Ctrl+C 逐条拼接的单元）；不复用渲染文本。 */
    protected abstract fun copyTextFor(element: E): String
    override fun itemToText(index: Int, element: E): String = copyTextFor(element)
}
```
- `SqlEntryList` 改为 `class SqlEntryList(model) : TextCopyList<LogSqlEntry>(model)`，`copyTextFor` = `withTerminator(restoredSql)`（现语义逐字节不变）；
- `RecentExecutionsDialog.entryList` 改 `SqlEntryList(listModel)`（同包同类型，一行）；其原 `entryList.cellRenderer = entryRenderer` 等既有接线不动。

### S3（D102）各接入点接线

编辑器 7 处（§一表）统一调用 `EditorCopyShortcuts.install(editor, UiTextClipboard::copy)`；有对号反馈的（浮层、最近执行记录等带图标按钮的）传 `onCopied = { flashCopied(button) }`。
浮层改接共享件后，删除其内联的 `shortcutCopyKey` 块（保留 keymap 侧 `installCopyHandler`，两层仍归一）。

### S4（D103）测试与反向验证

- 共享件单测（新建 `util/EditorCopyShortcutsTest`、`util/TextCopyListTest`）：无选中→全文、有选中→选区、Ctrl+Insert 同绑、`onCopied` 触发、`copyTextFor` 委派；
- 接入点各钉一条「绑定在场 + 触发后剪贴板内容正确」断言（`SqlPreviewPanelTest`/`RecentExecutionsDialogTest`/`SqlSuppressManagerDialogTest`/`RollbackSqlDialogTest`/`SqlToJavaPanelTest`/`ParamImportDialogTest` 各按其既有测试类归位）；
- 列表：`RecentExecutionsDialog` 列表多选 Ctrl+C → 纯 SQL 换行拼接；`SqlEntryList` 既有契约用例不回退；
- **反向验证**：摘掉 `EditorCopyShortcuts.install` 内的 InputMap 绑定 → 共享件用例 + 至少两个接入点用例必红（贴两次读数）；列表侧摘 `itemToText` 覆写 → 列表用例必红；
- 定向套件 + `testFast --rerun` 冒烟。

## 四、待验证前提

1. **各编辑器组件都是 `JTextComponent`（`EditorComponentImpl`）**——`EditorCopyShortcuts` 依赖其 InputMap 可写；降级实现（非 EditorEx）也满足，但需在实施时确认 `editor.component` 类型（若某处返回非 JComponent 则不接线）。
2. **`SqlSelectionDialog` 复制语义与改造成本**：需调用方提供「候选正文」（现结构只有 `Row(summary, detail)` 且 detail 为截断预览），接入要新增构造参数 + 12 处调用方同步 → **登记候选 R68 不现做**，等用户点名（若点名，届时用 `copyTextOf: (T) -> String` 参数收口）。
3. **`SqlPreviewPanel` 是否只有 `createEditor` 一处**（已 grep 确认 2950/2952 两分支同方法）——若运行期存在其它编辑器实例路径（如控制台嵌入 `embedConsole`），实施时复核并一并接线。
4. **反馈回调的适用性**：仅给「有图标按钮的界面」传 `onCopied`（浮层/最近执行记录），纯文本区（输入区/回滚对话框）不传，避免无载体反馈。

## 五、验收标准

- 上述 7 处编辑器：无选中按 Ctrl+C / Ctrl+Insert 复制全文；圈选后只复制片段；不改动任何既有按钮/菜单行为。
- 两处 SQL 列表（主日志/历史二级共用 + 最近执行记录）：Ctrl+C 得纯 SQL，多选逐行拼接，与右键「复制 SQL」逐字节一致。
- 浮层手写绑定块被共享件取代，行为契约不回退（含「有选中只复制片段」）。
- 定向 + 反向验证 + testFast 读数在册。

## 六、实施记录

**共享件**：新增 `util/EditorCopyShortcuts.kt`（编辑器键盘复制：焦点组件 WHEN_FOCUSED 覆盖 Ctrl+C/Ctrl+Insert，无选区→全文、有选区→选区，可选 `onCopied` 反馈）与 `util/TextCopyList.kt`（列表复制基类：`copyTextFor` 委派 `itemToText`）。

**接入落地**：

| 位置 | 改动 |
|---|---|
| `SqlClickPreview`（浮层） | 内联绑定块 → 共享件（**净减代码**，行为契约不变） |
| `SqlPreviewPanel`（主预览面板 SQL 编辑器） | 接线（无反馈） |
| `RecentExecutionsDialog` | 编辑器接线（对号反馈）+ 列表 `JBList` → `SqlEntryList`（复制纯 SQL） |
| `SqlSuppressManagerDialog` | 接线 |
| `RollbackSqlDialog` | 接线（viewer 态亦可复制） |
| `SqlInputEditor`（SQL → Java 输入区） | 接线 |
| `ParamImportDialog`（JSON 参数编辑器） | 接线 |
| 测试访问器 | `RecentExecutionsDialog.editorForTest/entryListForTest`、`RollbackSqlDialog.editorForTest` 新增 |

**读数**：

| 轮次 | 命令 | 读数 |
|---|---|---|
| 编译 | `compileKotlin` | 首轮红：`SqlInputEditor.kt` 缺两个 import（该文件无 `util` 组，脚本插入未命中）→ 手工补齐后 BUILD SUCCESSFUL |
| 定向（主） | 六个测试类 | 首轮 221 例 1 失败：`test列表CtrlC多选复制纯SQL` 实得单条 —— **该列表是单选列表**（`SINGLE_SELECTION`），断言改单条语义后 **130/0 全绿**（第二批次 6 类） |
| **反向验证** | 摘共享件两条 `inputMap.put` | **130 例恰 9 红**：`EditorCopyShortcutsTest` 4 全红 + `SqlClickPreviewTest` 2 + `RecentExecutionsDialogTest` 1 + `RollbackSqlDialogTest` 1 + `SqlPreviewPanelTest` 1；列表复制/格式化/右键菜单等 121 例保绿 —— 判别力精确跨 5 类 |
| 恢复后 | 同六类 | **130/0 全绿**（BUILD SUCCESSFUL 1m14s） |
| testFast | `testFast --rerun` | **3406/0/0**（报告时间戳 10:42:22 实证真跑） |

**实施偏差**：

1. `SqlInputEditor.kt` 无 `util` 包 import 组，批量脚本的 import 插入对其静默失效（编译期才暴露）——已手工补齐；后续批量接线脚本应加「插入后校验」。
2. `RecentExecutionsDialog` 的列表为**单选**语义（设计如此），原「多选拼接」断言不成立，改单条；结论：该处修复价值在「复制出纯 SQL 而非渲染文本」，批量复制语义仅日志/历史两列表需要（且已具备）。
3. 测试脚本经 JSON→shell→python 三层转义，字符串里的 `\n` 被逐层吃掉成真换行 —— 用 `chr(92)` 构造字面量规避（新增通用坑，见记忆）。
4. **`SqlSelectionDialog`（多 SQL 候选选择弹窗）未纳入**：接入需新增「候选正文」构造参数 + 12 处调用方同步，成本远高于收益（该弹窗用途是「选一个」，复制需求弱）→ 登记 **R68**，等用户点名（届时用 `copyTextOf: (T) -> String` 收口）。

## 八、追加需求：「最近执行记录」支持多选（T-RecentMulti，用户令）

用户令「「最近执行记录」也支持下多选」——语义**照抄 SQL 日志页**（本仓 UI 惯例：同交互照抄既有同类实现）：

| 交互 | 多选语义 |
|---|---|
| 选择 | `MULTIPLE_INTERVAL_SELECTION`（Ctrl/Shift 多选） |
| Ctrl+C | 逐行拼接纯 SQL（`SqlEntryList.itemToText` 已具备，多选自动生效） |
| 编辑器显示 | 按行序逐条格式化后换行拼接——与 Ctrl+C 产物同源（**所见即复制**）；单选逐字节不变 |
| 状态行 | 多选显示「已选 N 条」（新键 `log.recent.dialog.selected`，zh/en 双语）；单选/空态沿用原文案 |
| 打开预览 | 取 **lead**（最后交互行）——与「我点的那条」意图一致；单选等价原 `selectedValue` |
| 右键菜单 | 命中行已在多选内 → 保留多选（`resolveContextMenuSelection`）；「复制时间戳」走批量（`batchCopyTimestamps` + `copyMenuText` 带条数）；其余动作仍单条（与日志页一致） |
| 复制/格式化按钮 | 作用于编辑器当前文本（多选即拼接文本） |

**读数**：`RecentExecutionsDialogTest` 20 例全绿（新增 4 条：多选批量复制、编辑器拼接+状态行含收回单选回落、打开预览取 lead、复制时间戳带条数）；**反向验证**把 `selectionMode` 改回单选 → **20 例恰 3 红**（批量复制 / 拼接+状态行 / 菜单条数），并按反向口暴露一处**测试自省**：lead 用例在单选模式下也通过（无判别力）→ 补「前提：多选确实生效（选中数==2）」断言后固化；`testFast --rerun` 3406/0/0。

## 七、验收标准（回填）

- 7 处编辑器：无选中 Ctrl+C / Ctrl+Insert 复制全文、有选中只复制片段 —— 共享件 6 例 + 5 处接入类各 1 例断言在册；按钮/菜单路径零改动。
- 两处 SQL 列表 Ctrl+C 得纯 SQL；最近执行记录列表由「渲染文本」修正为纯 SQL（单选语义）。
- 浮层手写绑定被共享件取代，行为契约由既有 26 例锁住不回退。

