---
name: pitfall-swing-html-list-renderer
description: 可变行高 JList + JLabel-HTML 渲染器 = 每次布局 O(N) 次 BasicHTML 解析（16s EDT
  冻结根因，字符串缓存无效）；根治 = SimpleColoredComponent 片段 + 固定行高（WideSelectionListUI
  字节码短路已证）
metadata:
  node_type: memory
  type: project
  originSessionId: sess_0f672b82-f6dc-4586-b0ec-4ba706c06266
---

Swing/IntelliJ 平台事实（243 基准 distro javap + 字节码实证，2026-09-08 T-LogListFreeze-04），适用于任何「富文本列表条目」性能问题：

- `JLabel.setText` 且文本以 `<html>` 开头时，**每次都走 BasicHTML.updateRenderer→createHTMLView**（HTML 解析 + 逐 span CSS 属性 + 字体解析 + FlowView 文档布局）——对逐 token 包 `<span>` 的长 SQL，单条数 ms~数十 ms。**缓存 HTML 字符串只省拼接、不省解析**（T-LogListFreeze-02 的教训）。
- JList `fixedCellHeight = -1`（可变行高）时，每次布局状态更新（模型变更/滚动/resize）对**每一条**调 renderer 取 preferred size → O(N) 次 HTML 排版；WideSelectionListCache 在模型变更后整体失效；invokeLater 分批追加在此语义下放大为 O(批数×N)。
- **WideSelectionListUI.updateLayoutState 字节码：`getFixedCellHeight()!=-1 || getFixedCellWidth()!=-1` → goto 跳过逐条 getItemPreferredSize 循环**——固定行高是布局 O(1) 快路径开关。
- 根治组合：`SimpleColoredComponent` 片段渲染 + 实测固定行高（样例条目渲染一次取 `preferredSize.height`，fallback 字体行高×行数+边框常数）。API（app-client.jar 已核）：`append(String, SimpleTextAttributes)`/`clear()`/`iterator()`（ColoredIterator.getFragment/getTextAttributes 供测试断言）、`SimpleTextAttributes(int style, Color)`（fontType 即 Font.BOLD 编码，关键字加粗得以保留——HTML span 只带色值）。`SimpleListCellRenderer` 是单行 JBLabel 底座，多行须自建 JPanel 叠放。
- 代价与产品权衡：SimpleColoredComponent 单行不折行——列表条目恒定高，全文由预览弹窗/单击预览承接；多行换行诉求只有 JTextArea（无着色）或 HTML（昂贵）两条路。
- 测试坑：多 fragment HTML `contains` 断言会「蹭色」——如断言耗时 `#9E9E9E` 实际匹配到时间戳 span；改片段直读属性后才暴露真实分档语义（50ms 正常=绿非灰）。
- **BasicHTML 折叠连续空白**：HTML 视图按 HTML 规则把连续空格/行首缩进塌成单空格——多行文本（`\n`→`<br>`）里的格式化缩进（AND 缩进 4/子查询 +4/AND 前双空格）渲染即丢，须在 HTML 转义时把空格替换为 `&nbsp;`（列表无 word-wrap，无副作用）。T-ListMode（2026-09-08 计划）要点。
- **产品方向分歧（2026-09-08）**：本记忆的根治形态（SimpleColoredComponent+固定行高=恒单行）仅落 master 04；dev 侧用户明确要求列表多行展示（T-TwoLine 两行已交付 e77b696 + T-ListMode 三模式计划），仍走 HTML+cachedListHtml 路线——master→dev 合并此处会语义冲突，以用户多行诉求为准，O(N) 布局成本为已知接受项。

交付实战与 dev 合并预判见 [[t-loglistfreeze04-delivery]]、[[t-twoline-list-meta-line-delivery]]。
