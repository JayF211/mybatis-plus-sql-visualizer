---
name: pitfall-dbn-output-console-layout-collapse
description: DBN Output 控制台 44px 塌缩——平台 EditorMergedHorizontalScrollBarLayout"输入编辑器优先"策略是根因；一次性 setSize 必败、布局替换才是正解；同类名组件多处存在导致搜索错目标（M36.5.60→76 十六轮踩坑全程）
metadata:
  node_type: memory
  type: pitfall
  originSession: M36.5 方案 A（2026-08-30 ~ 08-31）
  commits: 7294d38, bc9963b
---

# DBN Output 控制台 44px 塌缩（M36.5.60→M36.5.76）

**2026-08-31 解决，插件嵌入 DBN 原生控制台系列最重要的一次踩坑。**
方案 A（整控台嵌入）背景下，Output tab 的控制台历史编辑器只显示 2 行（44px），
内容 pref 高达 1122px，下方大片空白；结果网格本身正常。

## 现象与运行时证据

沙盒日志 `[M36][BOUNDS]`（DataSourceSelector.dumpSplitBounds）反复实证同一形态：

```
MyAutoClosableLanguageConsoleImpl   bounds=864x235   ← 控制台根组件满高 ✓
  ...（中间数层）
EditorImpl$PanelWithFloatingToolbar bounds=864x44    ← Output 历史编辑器被压到 44px
  pref=855x1122                                      ← 内容高 1122px（50 行）
```

## 排查史（失败模式收藏）

| 轮次 | 尝试 | 结果 |
|---|---|---|
| M36.5.60-68 | 多轮 [BOUNDS]/[TEXT] 探针 dump 组件树 | 只确认"中间某层塌了"，层级深、dumpDeep 深度 12 截断，看不到元凶 |
| M36.5.69 | 对结果区内部容器强改布局 | **误伤结果网格**，回退 |
| M36.5.72/73 | 找到 PWT 向上找 >100px 大祖先，一次性 `setSize` | 无效——见根因 4 |
| M36.5.75 | javap 确诊根因，布局替换 `FillTargetLayout` | **68 次 `[OUTFILL] not found; skip`，0 安装**——见根因 5 |
| M36.5.76 | 搜索范围收紧到 bottom | **解决** ✓ |

## 根因（javap 反编译平台 jar 铁证，非猜测）

反编译沙盒 IDE（IU-2024.3.7.1）`lib/app-client.jar` 中
`com.intellij.execution.console.LanguageConsoleImpl` 及其布局管理器：

1. 控制台面板 `ConsoleEditorsPanel` 的布局是平台私有类
   **`EditorMergedHorizontalScrollBarLayout`**，构造参数
   `(myScrollBar, historyViewer, consoleExecutionEditor.editor, forceAdditional, minLineCount)`；
2. 该布局把面板**竖直二分**：上=historyViewer（Output 历史编辑器）、
   下=输入编辑器（consoleExecutionEditor——其组件已被插件收养进 topBlock 顶部编辑器）；
3. `layoutContainer` 的高度分配**输入编辑器优先**（字节码 478-556 解码）：容器高度不足时
   输入编辑器拿 preferred，history 被压到 `myMinLineCount × lineHeight`；
   `getMinHistoryLineCount()` 字节码硬编码 **`iconst_2`** = 2 行 × 22px = **44px**；
4. 平台唯一豁免分支「输入编辑器组件不可见 → history 占满整个容器」（字节码 29-54）走不到：
   输入编辑器组件在插件 topBlock 中 `isVisible()=true`——**isVisible 与挂载位置无关**；
5. 由此推出两个必然结论：
   - **任何一次性 `setSize` 都会被该布局每轮 layout 重算压回**（M36.5.72/73 失败的本质，
     不是"没找对目标"，是"对抗布局管理器必输"）；
   - 唯一正解是**替换布局管理器**。

## 修复（M36.5.75/76，布局替换）

`DataSourceSelector.installOutputHistoryFillLayout`：

- 在**结果区（bottom）**内定位 Output 历史编辑器（类名精确匹配
  `EditorImpl$PanelWithFloatingToolbar`——结果网格类名不同，绝不误伤）；
- 向上收集容器链，锚定到布局类名含 `EditorMergedHorizontalScrollBarLayout` 的面板（含）；
- 链上逐层安装 `FillTargetLayout`（目标子组件占满、其余子组件 0 高、可见 JScrollBar
  保底条；JScrollPane 链节点跳过）——布局随每次 validate 幂等重跑，无"被压回"模式；
- 调用点：`realignDividerOnce`（执行/换入路径）+ `SqlPreviewPanel.embedConsole`（预览路径）；
- 自证日志 `[M36][OUTFILL]`（安装打点 + 前 3 次高度修正打点 + 未锚定祖先链诊断）。

## 踩坑要点（每条都花了真金白银的时间）

1. **探针 dump 有盲区**：组件树嵌套 10+ 层且 `dumpDeep` 深度截断时，继续加探针是浪费轮次。
   布局异常的直接突破口是**反编译布局管理器字节码**——一次 javap 顶十轮探针
   （方法见 [javap-platform-ui-debugging.md](../methodology/javap-platform-ui-debugging.md)）。
2. **一次性 setSize/组件监听强改尺寸，对抗平台布局管理器必败**：布局每轮重算。
   正确姿势是替换布局管理器或用平台提供的扩展点。
3. **`isVisible()` 与挂载位置无关**：组件被"收养"到别处后依然 visible=true，
   不能指望平台"组件不可见就走豁免分支"这类逻辑生效。
4. **同名类组件在树中多处存在**：topBlock 顶部编辑器和 Output 历史编辑器同为
   `EditorImpl$PanelWithFloatingToolbar`，DFS 全树搜索会先命中前者（M36.5.75 的
   68 次 skip 0 安装）。**搜索目标组件必须把范围收紧到目标子树**（bottomComponent），
   且要在日志里区分"找到了但没锚定"与"压根没找到"。
5. **自证日志是布局修复的命门**：安装时打点（面板类/旧布局/子组件/before bounds）、
   运行时打点（target 高度修正）、失败时打点（祖先链诊断）——没有这三类日志，
   一次沙盒验证只能回答"好没好"，回答不了"为什么没好"。

## 验证判据

- `[OUTFILL] installed on <panel> (was EditorMergedHorizontalScrollBarLayout)`；
- `[OUTFILL] layout: parent=... target → N`，N ≈ 结果区高度；
- 有效 SQL 结果网格照常渲染；`divider OK` 稳定 60%。

2026-08-31 15:00 用户实测确认解决。
