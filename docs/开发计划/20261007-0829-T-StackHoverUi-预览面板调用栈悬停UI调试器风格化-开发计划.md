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

实施后状态跟踪：

1. **MouseChecker 触发语义**——已规避不再依赖：实施改用 `javax.swing.Timer` 单发防抖（悬停 400ms/移出宽限 300ms）＋ `MouseInfo` **实时指针**位置判定（事件坐标是越界瞬间旧值不可靠），`setCancelOnMouseOutCallback` 引用为零；ESC/点击外部/其他弹层由平台 popup 栈兜底。
2. **tooltip 接管互斥**——形态变体：tooltip 未置 null 而是只含 meta/警示/perf（栈文本从来不在其中，无内容叠加）。时序上弹层 400ms 先于 tooltip 500ms 出现、光标随即进入弹层窗口使 label 触发 mouseExited，tooltip 理论上不再出现；**重复悬停时 ToolTipManager 的短重显延迟是否造成两浮层闪现叠加，转 V116 真机判定**——若见叠加，修复=栈非空时 `toolTipText` 置 null（一行，与计划原文一致），修复后复跑受影响分片。与计划「置 null」的偏差及理由（meta/警示/perf 的悬停兜底）记 §七。
3. **HTML 转义完备**——已消解：C 案 HTML tooltip 不落地（M1/M2 同批，拍板注记），弹层走 SimpleColoredComponent 分片渲染无 HTML 通道，无转义面。
4. **无栈路径零回归**——定向绿：`stackDigest == null` 时 tooltip 与可见行逐字同旧（MetaHintTooltipTest 4 例+StackDigestTest 13 例覆盖）；P5 预算算法零改动。
5. **弹层互斥**——`setBelongsToGlobalPopupStack` 默认 true 依赖平台默认互斥，未显式设置；V116 真机走查与横幅/执行计划弹层并存场景确认。

## §五 编号台账

占号（现行快照「下一全新号 O-117/R63/D84/V115」进位）：**O-117**（悬停墙状文本＋栈内容重复拼接两缺陷登记）/ **R63**（功能项）/ **D84**（载体选型）/ **D85**（点击跳转范围）/ **V115**（M1 定向单测）/ **V116**（M2 真机悬停走查，对照调试器 Frames 截图）/ **V117**（M3 主题窄窗回归＋收口门禁）。落库前与全景盘点册对账撞号（并行会话纪律）。

## §六 分支与纪律

- ~~实施基于 dev 拉独立工作树~~（拍板改 master 直接实施，见 §七）；t-sqltojava-promote 在途支已按用户令并入 master 并清除（`7d7b8cd`），三支三树=master/dev/k2j。
- 新增 Kotlin 文件将产生 k2j 移植债，随 T-SyncK2JMaster 下一轮同步登记（本轮新增 StackDigestFrames/StackFrameHover 两件＋LogEntryPreviewRecognizer/AgentProtocol 等改动面）。
- ~~采集链（agent 子项目）零改动~~——V116-A 案（20261007 用户拍板）已动 agent 侧五件（StackDigest 两端采样/AgentState/AgentArgs/AgentMain/SenderLoop），agent jar 随 buildPlugin 重打包，**已 attach 的旧 agent 须重附或重启应用才获得两端采样**（帧数热下发只对含 SenderLoop 新解析的 agent 生效）。
- 验证三档分层：定向单测实施者自跑；全量门禁单路收口；V116-A 动了 agent 协议面（CONFIG body 追加键），走大改动线（verifyGate 视用户指示另行安排）。
- 计划 §七实施记录回写实施工作树内的本册副本，主仓副本只读。

## §七 实施记录

- 20261007 08:29 主控立项：现状两缺陷定位完毕（`SqlPreviewPanel.kt` tooltip 组装链）、参照物 API 面 243 sources.jar 逐签名核验完毕、计划落库待拍板，未动任何代码。
- 20261007 拍板：**D84=按推荐（M1 止血＋M2 弹层为主形态）；D85=按推荐（点击帧跳源码纳入本期）**；**实施分支=主分支 master 直接实施**（§六分支条目作废；k2j 移植债条目维持）。实施注记：M1/M2 同会话同批交付、无中间观察窗，C 案 HTML tooltip 不再单独落地（留档），M1 收窄为「解析器＋tooltip 去栈去重」，最终 UX=M2 弹层全权承接悬停。
- 20261007 实施落地（master 三笔：`178114a` 解析器+单测 / `084dc11` 弹层+接线+契约测试 / `97e116f` 本册）：
  - 新文件 `StackDigestFrames.kt`（解析器）＋`StackFrameHover.kt`（悬停弹层控制器）；`SqlPreviewPanel` 接线（stackHover 字段、init 注册 attach、updateMeta 喂入、清空复位）；bundle 双语两键；测试三处（新增 StackDigestFramesTest 11 例、StackDigestTest 契约翻转 13 例、MetaHintTooltipTest 栈断言翻转 4 例）。
  - **与计划偏差一处**：§三 D84 原文「A 在役后 stackDigest 非空时 toolTipText 置 null」——实施保留 tooltip＝meta/警示/perf 基线（悬停兜底信息可见性；栈文本本就不在其中），叠加风险转 V116 真机判定，见 §四.2。
  - 定向验证：compileTestKotlin 绿（首跑暴露缺 `javax.swing.JList` 导入一处，即修即绿）；定向三类 11+13+4=28 例 0 失败（XML 报告实读计数）。
  - 实施中 API 核验补强：Alarm 类改 `javax.swing.Timer`（仓内 historyAlarm 同款惯用法，少引新 API 面）；ActionLink 构造器、ColoredListCellRenderer.customizeCellRenderer、JBPopupListener.onClosed、RelativePoint(Component,Point) 均经 243 sources.jar/编译门核验。
  - k2j 移植债：新增两 Kotlin 文件随 T-SyncK2JMaster 下一轮同步登记。
- 20261007 收口门禁：
  - **首轮作废（环境事故，非代码红）**：testShards 09:05 启动后 51 分钟无产出——整机提交配额被打满（崩溃时刻物理内存余 577M、页面文件余 7M，commit 顶到 ~76/74.7GB 上限；本机基线常量占用偏高＋本构建自身 5 分片叠加），4 条 OOM：shard1 worker JVM native 分配失败崩溃（hs_err_pid75216.log）、shard3 AttachGateTest 的 8GB 堆子 JVM 起不来（93 例 1 失败）、shard2/4/5 三 JVM 在 OOM 后僵住零产出。处置=TaskStop＋清自家 launcher/分片 JVM/gradle+Kotlin daemon（释放约 7GB，commit 回落 66→59GB，余量 15.7GB），**未动任何代码**。
  - **重跑绿**：testShards **6204/0/0/9**（shard1 1176/6skip、shard2 1098、shard3 1596〔AttachGateTest 本次过，坐实前轮純环境〕、shard4 1172/2skip、shard5 1122；testExternal 40/0/1skip；9 skip=docker 离线常态），parity 对账过（542 类全出 XML），墙钟 3m48s（daemon 冷启含内）。
  - verifyGate（新平台 API 面=大改动线）：**三目标全 Compatible**（IU-243.28141.41 / IU-253.33514.17 / IU-262.10968.63，2m11s）——deprecated/experimental 计数为既有基线噪音，无 incompatible。
- 20261007 用户验收受阻与修复（`eb14ce4`）：用户在主分支沙盒（MPV-Build 10:30:33，确认含新代码）测带参数条目仍无调用栈。根因＝**master 既有缺口，非本轮回归**——`toPreviewResultInternal` 三返回分支只有兜底分支带 `stackDigest`，「占位符数==参数数」识别分支（出 `#paramN` 参数卡的主路径）与字面量恢复分支把快照丢了；零参数条目（兜底分支）才有摘要，与用户「早上 COUNT(*) 看得到、下午带参条目看不到」观察吻合。修复=两处补传 `stackDigest`；测试四例（识别/字面量恢复/兜底三分支透传＋无快照不凭空造）。定向 20+11+13+4=48 例全绿；testShards 复跑 **6208/0/0/9**（parity 过，4m08s）；verifyGate 按用户指示本轮不跑（修复未引入新平台 API 面，前轮三目标 Compatible 覆盖同一 API 面）；安装包重烤 `build/distributions/mybatis-plus-sql-visualizer-2026.10.7.zip`（10:41，含修复）。
- 20261007 真机走查首批反馈与两项跟进（用户带参条目走查通过）：
  - **跳库类触发平台反编译法律框（无害）**：点击 `JdbcTemplate$1` 库帧首次跳转，IDEA 反编译器弹「JetBrains Decompiler」法律声明模态框，其间平台 `DynamicPluginsFrameStateListener.applicationActivated` 的 VFS 刷新撞 write-unsafe 断言报 `Throwable`——平台侧一次性噪音，声明点一次接受后不再现；插件侧 `OpenFileDescriptor.navigate` 与日志面板跳转同链。
  - **V116-A 案立项并当日交付（用户拍板「按推荐 A＋帧数可配置」）**：controller 缺失根因＝agent 旧 16 帧顶格预算自栈顶取帧，JdbcTemplate 深封装链在 spring-jdbc 内部耗尽够不着栈底业务帧。改版：预算 B=`AgentState.stackMaxFrames`（默认 24，钳 [12,64]），≤B 全链（原行为），>B 两端采样=栈顶 B-8＋中段省略标记段（无 `#`，IDE 解析器天然跳过）＋栈底固定 8 帧；超长先缩栈顶、业务帧永不因长度被截。配置双通道=attach/premain 参数 `stackFrames=k`＋CONFIG 帧 `"stackFrames":k` 热下发（**设置页新整数行「调用栈快照帧数（12~64）」，改动保存即生效无需重附**）。落两笔 `e02030a`（agent 五件+单测 8 例）/`1f834ab`（IDE 协议/设置/面板/bundle+测试四处同步）；定向 agent 17/0＋IDE 协议 parser 全绿。
  - **合并 T-SqlToJavaPromote（用户令）**：`7d7b8cd` 真合并并入 master（分支侧 8 笔=M1~M5+实施记录+同步 master+页签改名；bundle 双侧追加自动并、两侧符号在场验证过），master 已 push origin；**t-sqltojava-promote 分支（本地+远端）与工作树已清除**（worktree remove 撞长路径坑按定例 rm -rf+prune 收尾），三支三树恢复 master/dev/k2j。
- 20261007 统一收口门禁（合并后 master）：首轮被 `SourceJumpFunnelTest` 旧十六帧契约用例拦下（T-StackHoverUi 改版前的预算断言，行为变化未同步用例）——用例换代双断言（预算内全链＋超预算两端采样段数有界）`e3cc949`，agent test 全任务复跑绿；第二轮 shard4/5 成片 `NoSuchMethodError: State.copy$default`＋注入注册表断言红＝回归清单第 0 条已知形态（本任务给 `PluginSettings.State` 加了带默认参字段，分片残留旧编译测试类），定例处置 `compileTestKotlin --rerun-tasks` 后消解。**终态：testShards 6218/0/0/9**（543 类 parity 对账过，3m16s，docker 离线 9 skip）。门禁前置 daemon 清理一次（commit 64→60GB，R16 预防）。buildPlugin 重烤安装包（agent jar 含两端采样新形态）。
- 20261007 三轮（用户真机二轮反馈「仍无 controller」＋快照原文）：A 案已生效（标记在场）但**栈底 8 帧被 Tomcat/线程池尾巴占满**——Controller 在省略的 45 帧里，暴露「栈底=字面末 8 帧」的盲点。修复（`471f4c7`）：栈底锚点先跳过尾部 infra 帧（java/javax/sun/jdk/tomcat/catalina/coyote/jetty/undertow；spring 业务链帧刻意不跳防拆断 AOP 链），锚定最深业务帧后判预算——业务深度 ≤B 全链保留、>B 栈底取最深业务帧起向上 8 帧；容器尾巴不占省略计数、全 infra 兜底退字面两端。单测 11 例（+3），agent test 全绿；buildPlugin 重烤（12:43）；testShards 复跑 **6255/0/0/9**（543 类 parity 过，3m41s）。用户重测前提=**重附 agent**（新 StackDigest 在 agent jar 内，帧数热下发对旧 agent 不含 infra 跳过逻辑）。
- 20261007 四轮（用户真机三轮反馈「仍无 controller」＋快照原文）：线程池尾巴已消失（省略 45→27）但锚点又落到 spring 过滤器链——二轮名单刻意漏 spring web 的盲点。修复（`37406ac`）：尾部穿透名单补 `org.springframework.web.`/`org.springframework.boot.`（filter/MVC/devtools 均为栈尾管道），业务栈中段 spring aop/tx 仍不跳（AOP 链上下文保留）。单测 13 例（+2），agent test 全绿；buildPlugin 重烤（12:53）。
- 20261007 统一门禁四轮记录：三轮后首跑挂 3 例纯环境失败（JPA 管道 `Unable to allocate`＋外部签牌子 JVM `Chunk::new`/`net.dll` 加载失败＝commit 水位 62GB 边缘态，三例在上一轮 6255 全绿均过）；清 daemon 重跑全绿。**登记观察项（拟 O-118，随盘点对账）**：`SqlPreviewPanelTest` 被分片清单重复分配（本轮 shard1=35 例/shard5=38 例两份 XML 同轮在场），SHARD TOTAL 随陈旧残留摆动 6218↔6255——对正确性无害（两处全绿）、属既有分片器噪音，修分片器在 buildSrc（R11 代价）不现修。**本轮权威读数：0 失败＋543 类 parity 全在场**。
