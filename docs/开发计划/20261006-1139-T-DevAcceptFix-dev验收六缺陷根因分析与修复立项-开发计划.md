# T-DevAcceptFix：dev 验收六缺陷根因分析与修复立项——开发计划

> 2026-10-06 用户对 dev 分支验收（回流 master 前置验收批）提出六项缺陷反馈，本计划逐项做根因分析与修复方案设计。分析基线 = dev 工作树 `mybatis-plus-sql-visualizer-dev` @ `caa0e48`（文中行号以该 tip 为准）。**写完停等拍板，未获确认不实施。**
>
> 预定占号：O-112（本批验收缺陷台账）、D67~D70（四处决策点）、V84~V91（待验证前提编号，以全景盘点升版裁决为准）。

## 一、缺陷台账与根因总览

| # | 用户反馈 | 根因定位 | 级别 |
|---|---|---|---|
| 1 | 预设需默认折叠；绑定数据源灰字移到「更多」按钮下方、原生控制台数据源附近 | `presetSectionCollapsed` 缺省 `false`（SqlPreviewPanel.kt:5527）；指示行挂在参数区方案行之下（SqlPreviewPanel.kt:3658、:3672） | UX |
| 2 | R33 EXPLAIN 表格交互不如原生控制台，考虑直接用原生控制台 | 双通道设计，结构化通道默认开（PluginSettings.kt:485 `explainStructuredRender=true`）；原生控制台=既有直送通道（SqlPreviewPanel.kt:1634） | 设计裁决 |
| 3 | 分页语句与计数语句之间缺分号 | `appendCountSection` 拼接处主 SQL 尾无 `;`（SqlPreviewPanel.kt:3133-3141） | 功能缺陷 |
| 4 | T-XmlSynHi 关键字没有着色 | `XmlSynHiGate.shouldYield` 第 68 行「平台注入器类在场即全族让位」——IU + Database 插件环境恒真，本插件注入器结构性零生效；设计前提「平台会补着色」真机证伪 | 功能失效 |
| 5 | 切换预设时下方参数没有变化 | 静态链四步（清场→换桶→召回→回写）代码自洽；两处结构脆弱点待复现裁决（§2.5） | 待复现裁决 |
| 6 | 开启增强捕获后项目无法启动 | `ClassWriter(COMPUTE_FRAMES)` 触发 ASM `getCommonSuperClass` 按 `Class.forName` 解析类型，织入回调运行于被织类自身 defineClass 进行中→同线程重入二次 define→`LinkageError: attempted duplicate class definition` | P0 |

## 二、根因详述

### 2.1 预设折叠缺省值与指示行位置（缺陷 1）

现状两处：

- O-88 折叠件缺省展开：`presetSectionCollapsed = false`（SqlPreviewPanel.kt:5527，companion 静态字段），`buildSchemeRow` 以 `!presetSectionCollapsed` 传参（:3785）。
- O-95 S3 绑定指示行挂在参数区：无参数早退分支与常规分支都在 `buildSchemeRow()` 之后 `paramPanel.add(buildBindStateRow())`（:3658、:3668-3672）。

改动方案：

1. 缺省值翻 `true`（预设区默认折叠，展开态仍由折叠件记忆跨预览重建保留，O-88 语义不变）。
2. 指示行迁出参数区：在 `init` 装配处挂到顶部工具栏行（topRow）之下、编辑区上方右对齐的常驻薄行——视觉上「更多」按钮正下方，与右侧内嵌控制台头部数据源显示相邻。O-96 三级文案与 `refreshBindStateRow` 单点刷新链（renderSql 尾部收口）不动，只换容器；常驻语义反而简化——不再随参数区重建重建。

波及面：O-95 S3 测试缝 `paramAreaComponentsForTest`（:5502，组件序列断言需改指新容器）、O-88 `presetCollapsedForTest` 缺省断言（:5530）翻 true；i18n 无新增键。具体对齐位置（右对齐 vs 左对齐）以真机截图走查定（V91）。

### 2.2 R33 EXPLAIN 交互形态（缺陷 2，决策点 D67）

现状：`submitExplainOrStructured`（SqlPreviewPanel.kt:1660）双通道并存——

- 结构化通道：自建 JDBC 执行 → `ExplainPlanResultView` 只读表格 + 索引建议行 + 「回到原生控制台」回退钮，判定走 `ExplainPanelChannel.decide`，失败 fail-closed 回退直送。设置 `explainStructuredRender` 默认 `true`。
- 直送通道：`submitExplainDirect`（:1634）→ `SqlExecutor.execute` → `embedConsole` 原生 Database 控制台，结果交互全由平台承担（排序/筛选/导出/格式切换）。

用户裁决输入明确：表格形式不如原生控制台，倾向直接使用原生控制台。两案：

- **A 案（彻底退役）**：摘除结构化通道全链——判定、`ExplainPlanResultView`、`ExplainPanelChannel.executeStructured`、`explainStructuredRender` 设置项、回退提示、面板侧索引建议消费与相关测试。动面大（含设置持久化残留清理），不可逆。
- **B 案（默认关闭，观察一版）**：`explainStructuredRender` 默认值翻 `false`，设置项文案标注「实验特性，默认使用原生控制台」；结构化通道代码与测试保留一个版本周期，下版再定摘除。可逆、最小改动、保住 R33 已修缺陷（bf23915）的资产。

推荐 **B 案**。附：`ExplainIndexAdvisor` 的 MCP 侧消费（McpExplainFormatter）与面板无关，两案都不动。

### 2.3 分页两语句缺分号（缺陷 3）

证据：`appendCountSection`（SqlPreviewPanel.kt:3133-3141）拼装为 `主SQL + "\n\n-- 注释头\n" + countSql + "\n"`——主 SQL（含追加的 LIMIT 行）尾部无 `;`，`wrapCountSql` 产物 `SELECT COUNT(*) FROM (…) TOTAL` 尾部也无。Wrapper 链路径与 XML/注解路径的 `countSql` 都汇聚到这一个渲染点，修一处即全覆盖。

改动：主 SQL 尾幂等补 `;`（已含分号不重复）；COUNT 语句尾同步补 `;`——两条独立语句各自收尾，对齐原生控制台多语句执行语义。`PaginationSimTest` 与面板渲染断言补分号形态读数。分号口径（各自收尾 vs 仅中间一处）列 D69 待确认，默认各自收尾。

### 2.4 T-XmlSynHi 关键字不着色（缺陷 4，结构性失效）

证据链：

1. `SqlMapperInjector.getLanguagesToInject` 第一步即问 `XmlSynHiGate.shouldYield`（SqlMapperInjector.kt:37）。
2. `shouldYield` 第 68 行：`if (platformInjectorPresent) return true`——判定方式是 `Class.forName("com.intellij.sql.injector.SqlLanguageInjector")` 类**在场**即让位（XmlSynHiGate.kt:30-32），设计注释「Ultimate Database 线」。
3. 用户环境 = IntelliJ IDEA Ultimate 2026.2 + Database 插件（插件全部验收环境同此形态）→ 该类恒在场 → **T-XmlSynHi 注入器在 Ultimate 环境一票否决，自上线起从未注入过任何一位**。
4. 让位的前提是「平台注入会补上 SQL 着色」，真机验收证伪：mapper XML 语句文本关键字无色——平台注入器对当前 mapper 形态未覆盖，两侧都无着色。

结论：这不是「关键字词集/着色键」的实现缺陷，是让位门槛把整个特性在目标用户环境挡死了。此前单测全在合成样本上自证注入器逻辑（让位被测试环境绕过或显式关门），「真实形态看不见」的守卫盲区又一例。

改动方向两案（D68）：

- ① 让位判定从「平台类在场」改「该宿主已有有效 SQL 注入」的 per-host 探测：注入前查该 XmlText/XmlAttributeValue 是否已有平台 SQL 注入，有则让、无则注入——精准让位，Ultimate 有平台注入的位仍让，没覆盖的位由本插件补位。平台 API 在 243/262 的可用形态待验证（V87）。
- ② `xmlSynHiEnabled` 升格为「着色来源」三态设置（自动让位 / 本插件优先 / 平台优先），用户显式接管。

推荐 ①为主、保留现有开关兜底。附带修正：让位策略变更后，真机验收必须实测用户 mapper 形态（V88 记录平台缺席形态，供让位判定回归）。

### 2.5 切换预设参数不联动（缺陷 5，待复现裁决）

静态链（SqlPreviewPanel.kt）：

```
combo.addActionListener(:3763) → switchToScheme(:3884)
  → clearParamStateForSchemeSwitch(:3895)：可见字段清空 + 状态集/map 清空（顺序敏感注释在案）
  → paramHistoryCache.switchScheme(:153)：只写激活指针（F49）
  → recallFromHistory(:4713)：按激活方案召回 → paramValues 填充 + syncParamFieldText(:4594) 回写可见字段
  → renderSql()
```

四步在代码上闭环，**静态审查未发现确定性断点**。两处结构脆弱点列为嫌疑：

- **a（顺序对位契约）**：`syncParamFieldText` 经 `currentParamFieldIndex` 以 `paramValues.keys` **插入序**对位 `paramFields` **行构建序**（函数注释自认「顺序一致」假设）。方案切换后 map 重填顺序 = 另一方案快照的保存顺序；两个方案的快照键序一旦与当前面板构建序不一致（不同会话录入、条件开关差异），回写错位——值写进错误的输入框或漏写。该契约在非切换路径（showResult 后首次召回）天然成立，切换路径才暴露。
- **b（未命中零反馈）**：目标方案在该 SQL 键下无快照时 `recall` 返回 null 静默返回——字段已被清场置空；若用户原值本来就空（默认值/AUTO 渲染），感知即「没有变化」。另「另存为」以当前值建方案（ParamSchemeManagerDialog.kt:259），新建方案与原方案同值，切换本就无可变——与用户预期是否一致需要对齐。

处置：**阶段 0 面板级红复现先行**（headless 双方案异值 → 切换 → 断言可见字段文本）：红 → 按脆弱点 a 修，回写改「字段挂 source 锚点（clientProperty）显式映射」弃插入序对位，并补跨方案键序漂移用例；不红 → 按脆弱点 b 做语义修缮（未命中轻提示 + 方案空态文案）。

### 2.6 增强捕获致宿主启动失败（缺陷 6，P0）

用户实录（selfdev-framework-demo：Spring Boot 2.6.3 / JDK 1.8.0_181 / mysql-connector-java 8.0.28 / HikariCP 4.0.3）：开启增强捕获后启动即炸，`java.lang.LinkageError: loader (instance of sun/misc/Launcher$AppClassLoader): attempted duplicate class definition for name: "com/mysql/cj/jdbc/ConnectionImpl"`，炸点为启动期首个 `getConnection`（`dataSourceScriptDatabaseInitializer` 取连接 → `NonRegisteringDriver.connect`）。

机制推演（代码链实证）：

1. `ConnectionImpl` 在 prepare 面织入名单（StatementWeaver.java:89 SPECS），类加载时经 `SqlHookTransformer` 进入 `StatementWeaver.weaveSpec`。
2. weaver 以 `ClassWriter(COMPUTE_FRAMES | COMPUTE_MAXS)` 重序列化（StatementWeaver.java:143；ConnectWeaver.java:67 同）。COMPUTE_FRAMES 要为**原方法体**既有分支/异常处理重算 StackMapTable，帧合并需类型公共父类，ASM 默认 `getCommonSuperClass` 实现按 `Class.forName(type, false, ClassWriter 所在 loader)` 解析——ClassWriter 类在 agent jar 里、由宿主 AppClassLoader 装载，forName 即打向宿主加载器。
3. 织入回调（transform）运行于被织类自身 `defineClass1` **进行中**（JVM ClassFileLoadHook 时点）。此线程此刻重入 `loadClass(ConnectionImpl)`：`findLoadedClass` 对尚未注册进字典的进行中定义返回 null → `findClass` 再次 `defineClass` → JVM 判重复定义，抛出用户所见 LinkageError。ConnectionImpl 体量大、`this` 槽大量参与异常处理帧合并，自引用解析概率远高于已在线上跑的驱动面小类。
4. 佐证：`AgentMain` 静态块注释自述「实测双 define LinkageError 规避」（AgentMain.java:27-34）——同族现象此前已在 DriverRegistry 上发生过，当时以 `<clinit>` 抢载做**特例规避**，未触及 COMPUTE_FRAMES 这个结构性根因；本缺陷是同一根因在织入名单扩大（prepare 面）后的再次显形。

日志取证边界：11:06 运行窗在 2026.2 日常 IDE 的 idea.log（含轮转档）零插件条目，各工作树 runIde 沙盒日志均停在 9 月下旬——本次分析证据以宿主崩溃栈 + 代码链为准；V84 复现时补 attach 期 idea.log 对照读数。

修复：

- **F1（根治）**：两织入器 `COMPUTE_FRAMES` 改仅 `COMPUTE_MAXS`。注入块设计本就是「零分支直线、无新帧合并点」（ConnectWeaver javadoc 在案），原 StackMapTable **内容**无需重算；ClassNode 树 API 下帧挂 Label 锚点，插桩直线码后序列化时偏移随 Label 自动平移，无错位面。transform 期除既有的非 system loader 可见性检查外零类加载，递归 define 的结构性风险整体消除。
- **F2（纵深）**：自定义 ClassWriter 覆写 `getCommonSuperClass` 为零类加载实现（保守返回 `java/lang/Object` 或常量池静态上界），防未来维护中重新挂回 COMPUTE_FRAMES 时复发。
- **F3（不动面）**：attach/premain 双通道协同、SPECS 名单、回调五件套签名、幂等标记、AgentMain 抢载（保留无害）全不动。

## 三、实施排期（拍板后）

- 阶段 0（先行）：2.6 demo 真机红基线（开增强捕获复现崩溃）＋ 2.5 面板级复现裁决。
- 阶段 1（P0）：2.6 F1/F2，e2e 织入族 + demo 真机开/关对照转绿 + 捕获事件回归（含 F74 批串语义）。
- 阶段 2（并行 UI 面）：2.1、2.3 与 2.2（按 D67 裁决形态）。
- 阶段 3：2.4（平台 API 验证 + 让位改造 + 用户环境真机走查）。
- 门禁：各缺陷实施代理交定向测试；全部完成后单派只读收口代理跑权威全量；构建校验按大改动判据（本轮动 agent 织入器 + 设置默认值 + 注入门控——`verifyGate` 必跑）。

## 四、待验证前提（实施者先证伪再动手）

- **V84**：COMPUTE_MAXS-only 下织入产物通过宿主 JVM 校验器（原帧内容不变 + Label 平移合法）——e2e 定义/加载织入夹具即刻显形，以 demo 真机启动为准；红/绿对照读数成对贴出（当前 COMPUTE_FRAMES 形态复现红为前提）。
- **V85**：ConnectionImpl 自引用触发面可独立复现（javaagent 最小样先行，不依赖完整 demo）。
- **V86**：2.5 面板级复现能否红（裁决走脆弱点 a 还是 b）。
- **V87**：「宿主已有有效注入」探测 API 在 243/262 的可用形态（InjectedLanguageManager 或等价物），以及双注入叠色的实际危害是否如设计注释担心的「不可接受」。
- **V88**：用户 mapper 形态下平台注入缺席的具体条件（DTD/namespace/数据源关联相关性），作为让位回归基线。
- **V89**：D67——EXPLAIN 形态采纳 B 案（默认原生控制台+设置保留一版）还是 A 案（立即摘码）。
- **V90**：D68/D69——XmlSynHi 让位策略两案取舍；分号形态默认两语句各自收尾的确认。
- **V91**：2.1 指示行新位置视觉验收（更多按钮下方、控制台数据源附近的截图走查）。

## 五、验收标准

1. demo 项目开启增强捕获正常启动，关/开对照读数成对；SQL 捕获事件（连接/语句/批串）与既有基线持平。
2. mapper XML 语句文本关键字/字符串/参数占位着色在用户环境（IU + Database）真机恢复。
3. 预设区缺省折叠；绑定指示行位于「更多」按钮下方、原生控制台数据源显示附近；预览切换/清空不丢行。
4. 分页预览主 SQL 与 COUNT 语句以 `;` 正确分隔。
5. EXPLAIN 呈现形态符合 D67 裁决（B 案=默认原生控制台）。
6. 预设切换行为符合 V86 裁决结论，方案语义与用户预期对齐。
7. 定向测试交付 + 收口全量与基线持平；`verifyGate` 三目标全 Compatible。

## 六、范围外（本轮不做）

- 编号台账与全景盘点升版（收口批统一处理）；CHANGELOG/发行说明同步随实施批。
- R33 结构化通道若走 A 案退役，`ExplainIndexAdvisor` 的 MCP 侧消费不动。
- MP 拦截器模拟其余两件（R48 MetaObjectHandler 等）不在本批。
- attach 时序竞态、EXECUTION_TOPIC 订阅竞态等既有登记项不在本批。

## 七、实施记录（回填占位）

（实施开始后由实施车道回填本节；主仓副本只读。）
