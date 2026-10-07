# T-StackHoverUi 预览面板调用栈悬停 UI 调试器风格化 开发计划

- 立项：2026-10-07，用户点名——SQL 预览面板从 SQL 日志触发时，meta 行「调用栈」悬停展示是一整块纯文本墙（用户截图两帧：悬停后 Swing 默认 tooltip 把约 2000 字符的栈串按任意宽度折行，`#`/`:`/`<-` 混排不可读），要求参考 IDEA 调试器 Frames 视图的形态优化
- 定位：纯 UI/UX 改进＋一处文案组装缺陷修复；不触碰采集链（agent 侧零改动）、不动 P5 meta 行宽度预算算法（可见行展示维持现状）
- 写完停等拍板：§三 D84（悬停载体选型）与 D85（点击帧跳源码是否纳入本期）为待拍板决策点，实施随拍板启动

## §一 现状与根因（本会话代码实测）

数据链：agent 侧 `StackDigest.build(StackTraceElement[])`（`sub-projects/sql-visualizer-agent/.../util/StackDigest.java`）把全栈压成单串 `pkg.Class#method:line<-pkg.Class#method:line<-…`（`<-` 连接、栈顶在左、≤16 帧/≤2000 字符、行号负数不写），经事件 JSON 键 `sd` → IDE 侧 `AgentEvent.stackDigest` → `LogSqlEntry.stackDigest`（历史文件持久化）→ 双击预览 `PreviewResult.stackDigest` → `SqlPreviewPanel.updateMeta`。

展示链两个缺陷，均在 `SqlPreviewPanel.kt`：

1. **墙状文本（用户所指）**：`metaTooltipBase` 把 meta/警示/栈全文/perf 拼成一段单行纯文本塞给 `metaLabel.toolTipText`，其中栈全文节点仅前置一个 `\n`。Swing 默认 tooltip 对超长单行任意折行，且 `#`/`:`/`<-` 无任何视觉分层——即截图二形态。
2. **栈内容重复拼接（顺带发现）**：`metaTooltipBase = meta + warningLine + stackText.inlineLine + stackText.tooltipTail + perf…`（`SqlPreviewPanel.kt:4360` 附近）——`inlineLine` 是 120 字符截断摘要、`tooltipTail` 是全文，两者都进 tooltip，用户悬停先读到一遍截断摘要、紧跟着又一遍全文开头，内容重叠且「调用栈:」标签出现两遍（截图二可证）。

可见行（meta 行内联摘要、120 字符截尾、F3/P5 宽度预算）已调校到位，**本期不动**。

## §二 参照物核对（2024.3.7.1 sources.jar 实读，最低支持平台 243）

- 现代调试器 Frames 视图行渲染契约：`ColoredTextContainer.append(fragment, SimpleTextAttributes)` 分片着色（`com/intellij/xdebugger/impl/frame/XFramesView.java` 实读两处 append + GRAYED 用例）。行形态＝方法名（默认前景）＋空格＋全限定类名（灰色）＋`:行号`（灰），超宽左侧省略；栈顶在顶、当前帧选中高亮；点击帧跳源码。
- **「直接复用调试器组件」不可行，已定案**：`JavaStackFrame`/`XFramesView` 绑定活动 XDebugSession（JDI `StackFrameProxyImpl`），本插件的调用栈是 agent 采集的静态字符串快照、无调试会话——只能参照其视觉与交互形态自建，公开 API 足够。
- 载体候选 API 全部经 sources.jar 核验签名在案：
  - `JBPopupFactory.createComponentPopupBuilder`（本仓已有先例 `SqlLogPanel.kt:988`）＋ `ComponentPopupBuilder.setTitle/setMinSize/setFocusable/setCancelOnClickOutside/setCancelOnMouseOutCallback(MouseChecker)`；`MouseChecker.check(MouseEvent): boolean`；
  - `com.intellij.codeInsight.hint.HintManager.showHint(JComponent, RelativePoint, int flags, int timeout)`＋`HIDE_BY_ESCAPE`；`com.intellij.ui.LightweightHint(JComponent)`＋`setForceShowAsPopup/hide`；
  - 渲染层 `SimpleColoredComponent.append(String, SimpleTextAttributes)`／`ColoredListCellRenderer`＋`JBList`＋`JBScrollPane`。
- 跳源码复用既有基建：`OpenFileDescriptor(project, vf, line, 0).navigate(true)`（两日志面板同款）；类→文件解析可用 `JavaPsiFacade.findClass(fqcn, allScope)`（库类落在反编译视图，与调试器行为一致）。

## §三 方案与决策点

### D84 悬停载体选型（三案，推荐 A+C 分步）

| 案 | 载体 | 观感 | 风险 |
|---|---|---|---|
| **A（推荐主目标）** | JBPopup 弹层承载 JBList 帧列表，`setCancelOnMouseOutCallback` 做移出关闭，`setTitle`=「调用栈」 | 最接近调试器 Frames：逐帧一行、三分色、可滚动、可点击 | 浮层管理＋MouseChecker 触发语义需真机验证（待验证前提 2） |
| B | LightweightHint＋HintManager.showHint | 偏 tooltip 味，轻 | 16 行高度定位与屏幕边界自理；与编辑器 hint 抢栈；无原生标题/滚动 |
| C | 维持 Swing tooltip 通道，文本改 HTML 多行（每帧一行 `<br>`，类名/行号 `<font gray>`，逐字段转义） | 立刻消灭墙状文本，零浮层管理 | 无点击、无滚动，超长受 tooltip 尺寸限；本质仍是 tooltip |

推荐组合：**M1 先落 C 止血**（纯文本→HTML 帧行＋修缺陷二去重，当日可交付），**M2 落 A 完整形态**；A 在役后 stackDigest 非空时 `metaLabel.toolTipText` 置 null（浮层全权承担，避免 tooltip 与弹层叠加；meta/警示信息有可见行与横幅兜底），无栈条目 tooltip 维持原样。B 不推荐，留档。

### D85 点击帧跳源码（推荐纳入本期，限定范围）

点击弹层内帧 → `JavaPsiFacade.findClass(fqcn, allScope)` 命中 → `OpenFileDescriptor(project, vf, line-1, 0).navigate(true)`（行号 0 或未命中帧不跳、光标不反馈假成功）；未命中给弹层 ad text 提示。与 T-SourceJumpFunnel「详情面板人工往上追踪」的原始立项意图闭环。若拍板裁掉，弹层纯展示＋复制动作。

### 里程碑

- **M1 解析器＋文案止血（V115）**：新顶层纯函数 `parseStackDigest(String): List<StackFrame>`（`StackFrame(fqcn, method, line)`，放 `ui/` 或 `log/`；容错：`…` 尾缀截断、缺行号、2000 字符截断致尾帧残缺→丢弃残帧，全空→返回空表回退原文案）；修 `metaTooltipBase` 去重（栈非空时不再拼 `inlineLine`）；tooltip 改 HTML 帧行（案 C，逐字段 `XmlEscaper` 转义——帧串含 `<-`、`$Proxy151`、`$$Lambda`，必须转义）。单测：扩展 `SqlPreviewPanelStackDigestTest`＋新增 parser 测试（样本含截图实态 `$Proxy151#execute`、hikari/ibatis 长链、`…` 截断、缺行号帧、空串/null）。
- **M2 调试器风格弹层（V116）**：新组件（建议 `StackFramesPopup.kt`）——metaLabel mouseEntered 触发（Alarm 约 400ms 延迟防误触；stackDigest 非空才接管）；JBList 帧行渲染：`method` 默认前景＋` fqcn` GRAY＋`:line` 灰，栈顶在顶（与串序一致），agent 自身帧（`cn.song.mybatisplus.sqlvis.*`）淡灰降权（模拟调试器库帧）；≤16 行＋JBScrollPane 封顶高度；注册进面板 Disposer 树（弹窗关闭级联释放，D84/A 接线）；点击跳转按 D85 拍板执行；「复制调用栈全文」动作（文本即原 stackDigest）。
- **M3 回归与收口（V117）**：暗色/亮色主题、窄 dialog 宽度（弹层贴边翻转）、无栈条目（桥通道/旧历史）零变化、与 DegradationBanner/其他 popup 互斥；统一全量门禁按三档分层由收口代理跑。

## §四 待验证前提（派工先证伪）

1. **MouseChecker 触发语义**：`setCancelOnMouseOutCallback` 的 `check(MouseEvent)` 是鼠标移出弹层即触发还是移出触发组件即触发，sources 里语义不清——实施时先做最小真机实验再写判定（目标行为：弹层内移动鼠标不关，离开「弹层＋meta 行」区域才关）。
2. **tooltip 接管互斥**：案 A 在役后 stackDigest 非空时 `toolTipText` 置 null，ToolTipManager 不再参与；需真机确认置 null 后无残留 tooltip 闪现（若 ToolTipManager 已缓存旧文本）。
3. **HTML 转义完备**：案 C/M1 的 HTML tooltip 对帧串字段逐段转义；`$`、`<`、`>` 在 UI 树与 parser 测试里都要有样本（快照串经反编译类名与 lambda 会出现富符号形态）。
4. **无栈路径零回归**：`stackDigest == null`（桥通道、旧历史条目、Wrapper/JPA/日志还原链）走原 tooltip 文案，meta 可见行与 P5 预算算法逐字不动。
5. **弹层互斥**：`setBelongsToGlobalPopupStack` 默认 true 应已与平台其他 popup 互斥，真机确认与横幅/执行计划弹层不叠。

## §五 编号台账

占号（现行快照「下一全新号 O-117/R63/D84/V115」进位）：**O-117**（悬停墙状文本＋栈内容重复拼接两缺陷登记）/ **R63**（功能项）/ **D84**（载体选型）/ **D85**（点击跳转范围）/ **V115**（M1 定向单测）/ **V116**（M2 真机悬停走查，对照调试器 Frames 截图）/ **V117**（M3 主题窄窗回归＋收口门禁）。落库前与全景盘点册对账撞号（并行会话纪律）。

## §六 分支与纪律

- 实施基于 dev 拉独立工作树，分支扁平名 `t-stackhoverui`；master 与在途支（rp7、t-sqltojava-promote）零交集。
- 新增 Kotlin 文件将产生 k2j 移植债，随 T-SyncK2JMaster 下一轮同步登记。
- 采集链（agent 子项目）零改动；`PreviewResult` 构造签名零改动（弹层只吃 `stackDigest` 字符串，不新传字段——避免再碰多参构造与 k2j 互操作面）。
- 验证三档分层：定向单测实施者自跑；全量门禁单派只读收口代理；本任务不动 build.gradle.kts/plugin.xml/新平台 EP，够不上构建校验大改动线。
- 计划 §七实施记录回写实施工作树内的本册副本，主仓副本只读。

## §七 实施记录

- 20261007 08:29 主控立项：现状两缺陷定位完毕（`SqlPreviewPanel.kt` tooltip 组装链）、参照物 API 面 243 sources.jar 逐签名核验完毕、计划落库待拍板，未动任何代码。
- 20261007 拍板：**D84=按推荐（M1 止血＋M2 弹层为主形态）；D85=按推荐（点击帧跳源码纳入本期）**；**实施分支=主分支 master 直接实施**（§六分支条目作废；k2j 移植债条目维持）。实施注记：M1/M2 同会话同批交付、无中间观察窗，C 案 HTML tooltip 不再单独落地（留档），M1 收窄为「解析器＋tooltip 去栈去重」，最终 UX=M2 弹层全权承接悬停（stackDigest 非空时 tooltip 不再含栈文本）。
