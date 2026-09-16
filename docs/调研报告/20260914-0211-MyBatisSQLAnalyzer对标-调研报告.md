# MyBatis SQL Analyzer 对标调研报告

**创建时间**：2026-09-14
**文档定位**：第三轮竞品横向对比（`20260914-0211-竞品横向对比与功能建议-第三轮调研报告.md`）的专项深挖。新竞品搜索初筛时，MyBatis SQL Analyzer（Marketplace ID 32766）被判定为「基线后唯一功能正面撞车者」——gutter icon 点击预览动态标签解析后的 MyBatis SQL，与本插件核心场景重叠度最高。本文回答：实现深度如何、与本插件差距多大、是否构成威胁、有没有可吸收的精华。

**数据来源**：Marketplace API（2026-09-14 实时抓取）+ GitHub 源码仓库全量阅读（`kmingyan/mybatis-sql-analyzer`，已克隆至 `F:\code\Java\Github\mybatis-sql-analyzer`）。

---

## 1. 档案

| 维度 | 事实 |
|------|------|
| 身份 | MyBatis SQL Analyzer，ID 32766，xmlId `io.github.kmingyan.mybatis-sql-analyzer` |
| 版本 | v1.0.0（2026-07-06）→ v1.0.1（2026-07-13），此后停更（截至 2026-09-14 已 2 个月） |
| 量级 | 1,131 下载，无评分，0 评论（comments API 实证），8 天零增长 |
| vendor | kmingyan（个人组织，kmingyan@163.com，未认证） |
| 兼容 | since 232 / **until 251.\***——2025.2 起的全部 IDEA（含 2026.x）装不上 |
| 源码 | Marketplace 未挂 sourceCodeLink、updates 无 changeNotes，但 GitHub 有同名仓库（Java，0 star，最后 push 2026-07-13 与 v1.0.1 同日）；仓库无 README、无 LICENSE（公开但无开源许可） |
| 规模 | 22 个 Java 文件共 3,747 行；依赖仅 platform + java + maven 三个模块 |
| 定位描述 | 「Parse and preview MyBatis SQL with dynamic SQL tags resolved, right inside your IDE」+ 注解/XML 双模式、SQL 执行、EXPLAIN、AI 分析、SQL 格式化 |

## 2. 功能实现深挖（源码证据）

### 2.1 触发面：三个 LineMarker，两类入口

- `MyBatisSqlLineMarkerProvider`（JAVA 语言）：挂在 `@Select/@Insert/@Update/@Delete` 注解上（`SqlExtractor.java:22-56`，全限定名匹配 + 简名 + import 核对的回退判断）。
- `MyBatisMapperMethodLineMarkerProvider`：mapper 方法行第二锚点。
- `MyBatisXmlLineMarkerProvider`：XML mapper 内 `<select>/<insert>/<update>/<delete>` 四标签（`MyBatisXmlLineMarkerProvider.java:29`）。

合计 3 类触发面。没有 Wrapper 链、没有 HQL/JPA/JdbcTemplate、没有其他 ORM。

### 2.2 「动态 SQL 解析」实质是正则注释标注器，不是渲染器

核心类 `DynamicSqlParser.java`（281 行）的实现方式：一个 DOTALL 正则匹配 `<if|where|set|foreach|choose|trim|bind>` 标签（`TAG_PATTERN:18-22`），递归把标签体转成 SQL 注释夹注——`<if test="...">` 变成 `/* IF ... */ ... /* END IF */`，`<foreach>` 变成 `/* FOREACH x AS item */ ... /* SEPARATOR: ',' */`。关键性质：

- **条件不求值**。文件头注释直言「由于无法在 IDE 中获取运行时参数值，动态标签以注释形式标注条件和结构」——没有 OGNL、没有模拟参数，`<if>` 的所有分支原样保留，产物不是可执行 SQL。
- `<where>` 的 AND/OR 前缀清理、`<set>` 的尾逗号清理都是对整段文本做一次 `stripLeading().replaceFirst(...)`（`processWhere`/`processSet`），内层多分支时清理不到位。
- 同名标签嵌套（如 `<foreach>` 套 `<foreach>`）会被非贪婪正则截断。
- 不解析 `<include>`/`<sql>` 片段（全仓库无引用）。
- 参数形态：`#{x}` → `:x`；`${x}` 原样保留并追加 `/* WARNING: SQL注入风险 */`（`formatParams:249-253`）。

对比本插件：M24 复刻 MyBatis 渲染算法、开关式模拟参数、81 场景金样矩阵 + 竞品翻车场景防御（X1~X8），产出的是参数可编辑、可复制执行的真实 SQL。两者差一个代际：它输出「带注释的结构说明」，本插件输出「可执行的最终形态」。

### 2.3 执行与 EXPLAIN：自带 JDBC 栈

- 连接靠用户在插件设置页手工配置驱动 jar 路径 + URL + 账密（`DatabaseSettings`），`DriverShim` 类加载器包装注册驱动（`DatabaseConnector.java:43-47`），与 IDE 数据源体系完全无关。
- `ParameterFiller`：按参数名关键词猜默认值——含 `id`→自增数字、含 `name/title/desc`→`'test'`、含 `date/time`→`'2024-01-01'`（`generateDefaults:53-73`），纯字符串 replace 回填。
- `ExplainExecutor`（191 行）：MySQL/H2/PG 标准 `EXPLAIN`，Oracle 走 `EXPLAIN PLAN FOR` + `DBMS_XPLAN.DISPLAY`，SQL Server 单列；无达梦/金仓等国产方言。

### 2.4 AI 分析：唯一有差异点的功能

`LlmClient.java`（171 行）：OpenAI 兼容 `chat/completions`，SSE 流式读取，用户自配 API URL / Key / 模型名 / 系统提示词（`LlmSettings`），手写 JSON delta 解析。预览弹窗第三个 Tab 即 AI 流式分析，配 `MarkdownPanel` 渲染。这是它功能清单里本插件没有的唯一一项。

### 2.5 UI

`SqlPreviewPopup`（267 行）：JTabbedPane 三页——「原始 SQL / 解析后的 SQL / SQL 执行」（`SqlPreviewPopup.java:86-109`），支持复制、跳转回 XML 元素、AI 流式分析。

## 3. 对照矩阵

| 维度 | MyBatis SQL Analyzer | SQL Visualizer（master v2026.9.14-beta.2） |
|------|---------------------|--------------------------------------------|
| 触发面 | 3 类（注解 4 种 / XML 4 标签） | 13 类 gutter（Wrapper 链/XML/HQL/JdbcTemplate/自研 DAO/hbm/xbatis/Easy-Query/MDS/Querydsl 等） |
| 动态标签处理 | 8 种标签转注释标注，条件不求值 | 复刻 MyBatis 渲染算法，开关式模拟参数求值 |
| 产物 | 伪 SQL（注释夹注，多数不可执行） | 可执行真实 SQL（参数可编辑、复制进控制台） |
| `<include>`/`<sql>` | 不解析 | 解析 |
| 正确性保障 | 无回归测试体系 | 81 场景金样矩阵 + 竞品防御 X1~X8 + 3967 用例 |
| 日志捕获还原 | 无 | 有（执行期 SQLVIS-SOURCE 标记双向跳转） |
| 执行通道 | 自带 JDBC 独立配置 | 内嵌 IDE 原生 Database 控制台 |
| EXPLAIN | 5 库，无国产方言 | 7 方言（金仓/达梦真机验证） |
| AI SQL 分析 | 有（SSE 流式，自配端点） | 无 |
| `${}` 注入警示 | 有 | 无 |
| IDE 兼容 | 232–251（2025.2+ 全装不上） | 243+ 无上限，三目标 Compatible |
| 活跃度 | 2026-07-13 后停更 | 高频迭代（8 月至今 6 个版本） |

## 4. 威胁评级：低

理由：①停更两个月且 v1.0.1 后无任何动作；②兼容墙自绝于 2025.2+/2026.x，等于放弃增量市场（同类先例 MyBatis SQL Analyzer 卡 251 后 8 天零增长，与它自身一致）；③量级 1.1k、0 评论、GitHub 0 star；④渲染深度差一个代际，「标注」与「求值渲染」不可同日而语；⑤闭源 jar 无更新说明，社区通道缺失。唯一独有点（AI 分析）是可后补的薄功能，不构成护城河。

## 5. 可吸收精华与响应动作

1. **`${}` 注入风险标注**（P2）：它在渲染输出里对 `${}` 占位追加显式警示。本插件渲染 `${}` 时目前无风险提示，可在预览面板对该类占位给出标注（如高亮 + tooltip「字符串直替，注意注入风险」），小成本强化「正确性人设」。
2. **AI SQL 分析的可选集成印证**（P3，承接第三轮报告 O-11）：3,747 行的单人项目愿意分配 171 行客户端 + 设置页 + Markdown 面板给 AI 分析，与 DBX（多模型接入 + AI 生成 SQL 先过安全审查）相互印证「SQL 工具配 AI 解释」是真实需求。定位保持：用户自带 key 的可选集成，零弹窗红线不变，不做订阅制 LLM 服务。
3. **防御性记录**（零成本）：把它的两个实现缺陷场景——同名标签嵌套正则截断、`<where>` 多分支 AND/OR 清理失效——列入金样矩阵的已知竞品翻车场景清单，核对本插件行为正确即可，无需立项。
4. **不吸收**：自带 JDBC 配置栈（与「不做连接管理」定案一致，IDE 数据源复用是既定路线）；参数名猜默认值（本插件的模拟参数 + 历史参数持久化 + 字面量恢复机制更强）。

---

*本报告与 `20260914-0211-DBX数据库工具深度调研与MCP吸收规划-调研报告.md` 同为第三轮对比报告的专项输入；结论已并入第三轮报告 §4（新竞品）与 §5.3（建议）。*
