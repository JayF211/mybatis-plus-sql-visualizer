<!-- Keep a Changelog guide -> https://keepachangelog.com -->

# SQL Visualizer Changelog

## [Unreleased]

### Added
- **方言引号不匹配静态告警（T-DialectQuoteWarn，静态规则第 21 条 `dialect_quote_mismatch`）**：SQL 里的标识符用了当前方言不接受的引号字符时给出告警（如 MySQL 方言下出现 `FROM "user" t`）。`@TableName("\"user\"")` 这类达梦风格双引号在 MySQL 真机会报 `[42000][1064]`，插件此前只是如实画出。**只报告不改写**：渲染的仍是 MyBatis-Plus 真实发出的 SQL（改写会让预览偏离真机、把用户代码里的真 bug 静默抹平）。告警同时流到预览面板静态规则区与 MCP `sqlv_perf_advice`；方言从项目设置解析，取不到时不判定（不硬编码 MySQL）。判据按各方言实际宽容度定：SQL Server 的 `QUOTED_IDENTIFIER` 默认 ON（双引号合法）、SQLite/H2 收多种引号，避免误报。**（T-DialectQuoteWarn §十二 调整）**「MySQL 家族（MySQL/MariaDB/OceanBase）+ 双引号」这一档不再一律报 ERROR——双引号是否为标识符取决于连接 `sql_mode` 是否含 `ANSI_QUOTES`，而数据源 URL **只能证明「合法」不能证明「非法」**（URL 未声明该项时服务端全局仍可能开着）：预览按绑定数据源（活控制台 > last-used 记忆）的 URL 取该三态，确知声明了才不报，已知未声明或未知都降级为**带前提的 WARN**（缓存键含该三态，避免串味）；PG/Oracle/DM/KingBase/openGauss 遇反引号、非 SQL Server 遇方括号等无歧义档仍报 ERROR

### Fixed
- 调用栈悬停弹层视口按全量帧数放开（JList 默认 8 行把链尾业务帧折叠进滚动区，「默认锚定最深业务帧、一眼看到 Controller → Service」落空）
- 调用栈弹层锚点跟随悬停落点（原只在 mouseEntered 赋值边缘穿越点，弹层位置随进入方向漂移）
- 实体选择弹窗（SQL→Java / CRUD 预览的「选择要预览的 SQL」）候选副标题改为完整限定类名——原先只显示文件名，不同包/模块的同名实体（如多模块工程的两处 User.java）无法区分
- 「未在光标处找到可预览 SQL 源」提示文案动态化——旧文案只提 MyBatis-Plus Wrapper/IService，未覆盖插件现已支持的 mapper XML / 注解 SQL / MPJ / JPA / Hibernate / JdbcTemplate 等类型面，指引失真
- 帮助按钮打开的在线文档地址改用 canonical 域名与无后缀路径（原 `mybatis-plus-sql-visualizer.jvtools.cn/…/wrapper-preview.html` 实测要走两次跳转：旧域名 301 → 新域名、`.html` 308 → 无后缀；canonical 地址直接 200）
- JOIN 图标签页在**无动态条件的联表链**上不出现（MPJ `selectAll + leftJoin + fill` 这类链没有 `eq/like`，`previewModel` 为 null 而图判据原先只认它——预览出 SQL 却没有「JOIN 图」页签；带条件的同结构链才有）——新增 `PreviewResult.diagramStatement` 独立载体，图判据与「是否走模型级重渲染」解耦，旧渲染路径零改动（JPA 联表派生查询同款缺口一并覆盖）
- 参数回填在**切换逻辑删除 / 多租户注入开关**后丢失（历史缓存键原为 `表名:SHA-256(SQL 全文)`，拦截器模拟会改写 SQL 文本——键一变召回 miss，已回填的实值退回占位符，防抖后新键还会以空值覆盖落库）——新增 `PreviewResult.historyFingerprintSeed` 承载**注入前**语句文本，面板算键优先用它
- `deleteAllInBatch()` 这类 bulk 写路径在 **Repository 接口声明行**光标下无法预览（方法名不含 `By` 且 `*All` 词干白名单不放行 `InBatch` 结尾 → 派生查询线不认；无 `PsiMethodCallExpression` → CRUD 转换线不认；模板本身是完整的，只是两条通道都够不着）——新增声明行入口 `JpaCrudOperationConverter.convertRepositoryDeclaration` + `SqlPreviewService.previewRepositoryDeclaration`，动作回落链补第 5 步
- JOIN 图主表节点字段没带数据库 COMMENT（多数据源下跨库同名表：`@TableName("\"user\"")` 的主表被注册序在前的 `jpa_demo.user` 撞上——那张同名表 9 列且注释全空，而副表只有本库有、解析正常）——整图富化改为**句内同来源**批量取数：先逐表按名解析，再按「各来源持有本句表的数量」投票选主来源，少数来源持有的表若主来源也有则改判给主来源（主来源没有的表仍逐表回落，跨库联接不受影响）



## 历史版本归档

| 归档分册 | 覆盖版本 |
|---|---|
| [changelog-archive-2026-09.md](./变更记录/changelog-archive-2026-09.md) | 2026-09 发布的版本 |
| [changelog-archive-2026-08.md](./变更记录/changelog-archive-2026-08.md) | 2026-08 发布的版本 |

---

## [2026.10.13] - 2026-10-13

### Added

- **mapper XML 语法高亮注入（T-XmlSynHi）**：mapper XML 内语句文本按 SQL 语法着色（关键字/字符串/注释）、`#{}`/`${}` 参数占位加底色、`test`/`value` 属性表达式着色；输入 `#`/`$` 自动补全 `{}`；resultMap `column` 属性补全列名。让位改为**宿主级探测**（任一会给该宿主上色的其他注入器真 `startInjecting` 才让位——在位≠覆盖），与本机其他 SQL 插件共存不打架
- **SQL 高亮分组开关（R2）**：设置页可按语句来源分组控制 gutter SQL 图标显隐——mapper XML / 注解 SQL（@Select 族）/ JPA·HQL 族 / JdbcTemplate 字符串 / 通用 XML 内嵌兜底 / Wrapper 链及其它，默认全开（现行为不变）
- **历史条目钉住与正则检索（R15/R16）**：历史 SQL 条目可钉住防淘汰；二级搜索支持 `regex:` 前缀正则匹配（SQL/摘要/源文件三目标，非法正则回退子串）
- **历史/日志导出（R4）**：历史面板与日志面板右键「导出」CSV / TSV / 纯文本（行集与面板当前可见一致，后台写盘不卡 EDT）
- **导出 SQL 文件格式（T-ExportSqlFormat）**：导出格式由三选扩为四选且默认 SQL——参数已回填、逐条注释头（time/source/duration）＋分号终止的可执行 `.sql` 脚本，可直接在数据库客户端执行；默认名 `sql-export-<时间戳>.sql`
- **抽样 LIMIT 可配置（R5）**：快捷模板「抽样」行数设置页可配（默认 10，钳制 1..10000）
- **分页拦截器模拟（R12 T-PaginationSim，默认开）**：mapper 方法/XML 路径带 `IPage` 形参的查询此前预览不分页——现按方言在 SELECT 尾追 LIMIT（参数取 `(当前页-1)×页大小` 公式，手写 LIMIT 不重复追加），并并列产出 COUNT 包裹语句（`SELECT COUNT(*) FROM (…) TOTAL`）；Wrapper 链 `selectList/selectMaps/selectMapsPage` 的 Page 构造器 `searchCount` 布尔同步识别
- **分页 COUNT 独立开关（T-CountSimToggle）**：COUNT 与 LIMIT 解耦——设置页在「模拟分页拦截器」下一行新增子开关（默认 true 保持现状）；父开关关＝全无（父优先），子开关只关 COUNT、不影响 LIMIT
- **MCP 第十一工具 sqlv_history_search（R14 T-HistorySearch）**：AI agent 可检索本地 SQL 历史落盘——关键词/正则/时间范围/来源文件四维过滤，默认只查当天（`allDates` 跨全量），返回条目摘要（最新在前，maxResults 默认 20 上限 100），只读不执行；agent 使用指南见 `docs/mcp/MCP-Tools-Agent-Guide.md`
- **预览参数预设（R22 T-04）**：参数区新增「预设」行——当前填好的参数可**另存为预设**，下拉一键切换（参数值/OGNL 勾选态/foreach 权威值随预设整组还原），「管理预设…」对话框支持另存为/重命名/删除（默认预设受保护）；**命名预设＝快照语义**（日常编辑只更新工作区，命名预设只在显式写入时改动）；**预设按语句隔离**（下拉只列本条语句的预设，同名预设可在不同语句下各自存值）；预设行可折叠收起、**缺省折叠**（折叠状态跨语句/跨弹窗保留，重启复位）
- **Oracle / SQL Server EXPLAIN 接入（R23，EXPLAIN 支持面 11/11）**：Oracle 走 `EXPLAIN PLAN FOR`（计划登记 PLAN_TABLE，插件内以诚实提示说明查看方式）；SQL Server 走 `SET STATISTICS XML`（官方文法级形态，真机待验如实留账）
- **EXPLAIN 结果结构化渲染（R33，实验特性，默认关）**：MySQL 表格 / PostgreSQL JSON 计划可在预览面板内直接渲染成表格/可折叠树（带方言/数据源/耗时头部与索引建议）；默认关闭——EXPLAIN 默认交原生数据库控制台执行与呈现，设置页显式开启即恢复结构化通道，执行失败或环境不满足自动回退
- **原生 MyBatis 拦截器自动注入补完（R24）**：设置页「注入」对 `mybatis-config.xml` 工程此前只给手动指引——现自动检测配置文件 `<plugins>` 装配同一拦截器（无 config/多处 config/结构不符一律不注入维持引导，fail-closed）
- **动态 SQL 条件求值探镜（R30）**：XML 条件行（`<if>`）旁标注本轮预览的求值结果三态（真/假/未求值）；纯只读探针，渲染行为零变更
- **历史/版本 SQL 对比（R31）**：历史面板多选恰两条右键「对比选中两条」——逐行差异＋结构指纹判定（同指纹提示「结构等价，仅参数不同」），IDEA 原生 Diff 窗口呈现
- **数据源上下文角标（R32）**：SQL 条目角标从纯方言显示名升级为「方言 · 库名@host」（从 JDBC URL 解析；支持 Oracle service_name/SID 双形态与 SQL Server 分号参数；凭据信息不入任何展示）
- **N+1 同模板计数徽章（O-90）**：日志/历史面板对同结构模板的 SQL 计数，≥3 条显示「×N 同模板」琥珀徽章；右键可聚焦该模板全部条目（不做实时 profiler）
- **CRUD/回滚 SQL 可移植导出（O-91 缩范围首版）**：预览面板「更多」菜单新增「导出可移植 SQL」——当前 CRUD 语句与回滚语句串联输出，每条前注释标注方言/种类/UPSERT 形态（回滚链对无法生成的形态诚实跳过不造半成品）
- **日志转 SQL 剪贴板读取按钮（O-92）**：工具窗「日志转 SQL」输入栏新增「从剪贴板读取」——一键贴入并立即转换（有内容才启用，空剪贴板轻提示）
- **设置页与面板可发现性三处（P1-3/P1-4）**：MCP 设置页顶部新增工具面就绪横幅（工具数与门控状态一眼可见）；SQL 日志空面板引导补「增强捕获」第三径；帧过滤前缀入口在 SQL 日志设置子页顶部加指向说明
- **落地页价值展示（R20）**：设置首页新增三行能力矩阵与官网链接
- **引导通知动作化（R21）**：首次引导通知新增「立即试用」按钮，一键直达预览
- **预览列信息表恢复 Ctrl+A 全选（O-85）**
- **ShardingSphere 双段日志捕获（R10，解析器骨架默认开）**：控制台通道新增第四解析器——`Logic SQL:` 作主条目、`Actual SQL:` 逐行折叠为附注（数据源路由可见）
- **语句级织入 attach 即生效（T-StatementWeave）**：钩子挂在驱动语句实现类 execute 族方法体（入口+出口双插桩），attach 时对已加载语句类 retransform——**存量对象的下一次执行即带钩子**，覆盖池化场景启动期已建好的连接；`prepareStatement` 出口登记「PS 实例 → SQL + URL」弱映射；已知边界：attach 前已 prepare 且未再执行的 PS 不上报、织入通道无 setXxx 拦截故存量连接语句参数一期为空
- **日志→SQL 页灰字提示与搜索历史（T-LogToSqlHint）**：日志→SQL 页恒显灰字占位提示；搜索历史接管为面板自有会话历史
- **空态/引导链接点击定位设置项（T-SettingsFocusFromLink）**：日志页空态与引导链接点击后自动定位并高亮目标设置项（复用既有 focusTarget 机制）

- **历史检索正则/大小写开关（T-HistorySearchToggles）**：历史面板搜索框旁新增 Regex / Aa 两枚开关（与 IDE 原生查找同图标同语义，会话级、默认均关）——Regex 开启后整串按正则匹配（`regex:` 前缀保留，或语义不回归），Aa 开启后区分大小写（默认关=忽略大小写）；日志页 Ctrl+F 搜索条同步新增单枚 Aa 开关（随搜索条显隐）；历史一级文件名检索随 Aa 开关（正则不参与），行号 `#N` 精确匹配不受影响。行为变更声明：正则路径从恒大小写敏感改为随开关（默认忽略大小写，与子串路径/日志页/MCP 链三链拉齐）；顺带修掉正则编译 memo 键不含大小写旗的存量隐患（切开关后复用错编译结果）

### Changed

- **描述与文案口径统一：运行期以增强捕获为主、控制台为回落**（配套文档站首页描述改版）：`plugin.xml` 描述把原先并排的两条（「运行期捕获 · 控制台 SQL 日志」＋「增强捕获」）合并为一条「**运行期捕获（默认开启）**」，补入慢查询标红与静态性能建议、调用栈悬停弹层逐帧跳转，控制台三族日志降为「不改工程的回落通道」（中英同段同改；数字类声明未动，结构守卫对账面不变）；README 顶部一句话简介改为「写代码时就看见最终 SQL……跑起来后真实参数与耗时自动捕获，慢查询标红、EXPLAIN 看计划、一键跳回源码……不启服务、不翻日志、不手拼参数」，「运行期」段落同步改为增强捕获前置；Marketplace 自定义页**两版同步**（`src/main/resources/html/marketplace-custom-page.html` 供 IDE 帮助按钮 JCEF 渲染、`docs/发行说明/marketplace-custom-page.md` 供后台粘贴）——「为什么选它」的增强捕获条前置为「运行期捕获（默认开启）」、控制台降为「控制台日志（回落通道）」，标语盒「运行期日志还原」改「运行期捕获真实参数与耗时」。**md 版需在 Marketplace 后台重新粘贴**（两版唯一权威在本仓，见发布流程 §10.2/§11）。
- **README 的 EXPLAIN 方言计数勘正**：原文「EXPLAIN 执行计划（9 种方言，8 种真机 / 引擎实证、OceanBase 文档级验证）」为过期读数——`ExplainSupport` 实为 **11 条**（新增 Oracle 真机实证 Free 23 / XE 11g、SQL Server 按 Microsoft Learn 文法落地·真机待验），改为「覆盖 11 种方言（9 种真机 / 引擎级实证、OceanBase 文档级验证、SQL Server 按官方文法落地待真机）」。文档站 `wrapper-preview` 与 `faq` 中英原写「Oracle / SQL Server 暂不支持 EXPLAIN」同步勘正（该表述自两方言接入后即失真）。

- **数据源上下文角标默认仅显示数据库类型（T-DataSourceBadgeMode）**：SQL 日志/历史两页的目标库角标此前恒显全量「方言 · 库名@host:port」，条目元信息行信息密度偏高。现默认仅显示数据库类型（如 `[MySQL]`）；设置页「SQL 日志 → 数据源角标显示完整上下文（库名@主机:端口）」打开后恢复全量形态。门控做在显示侧——生产/落盘/历史读回/MCP 检索链零改动，开关即时生效（含已读回的历史条目），后开开关不丢任何信息；方言解析失败仅存连接事实的条目关闭态原样保留

### Fixed

- **空态引导英文链接文案大小写回归修复**：`log.panel.empty.link` 英文由 `Open Settings (jump to Enhanced capture)` 回归为 `Open Settings (jump to Enhanced Capture)`（与同 bundle 其余三处 EN 文案一致，修复 `EmptyTextRenderTest` 三径守卫红）。同批修 `DataSourceJdbcSmokeTest` 两处环境门控失效——`assumeTrue` 在 JUnit3 基类 `BasePlatformTestCase` 下抛出的 `AssumptionViolatedException` 被平台 runner 记为 failure 红任务（`DbConsoleIntegrationTest` 已有同款定论），改为「显式绿跳」（`println` + `return`），并把驱动不在测试 classpath 的数据源（demo 的 DM 源）逐源跳过；对驱动可加载的数据源保留连通性与可执行性断言
- **增强捕获织入改写致宿主应用启动失败（P0）**：连接/语句织入器原先用 ASM 默认 `COMPUTE_FRAMES` 重算原方法体栈图帧，其类型公共父类解析按 `Class.forName` 加载类型，而织入回调正运行在**被织类自身 defineClass 进行中**，同线程重入 `loadClass` 时 `findLoadedClass` 看不到进行中定义 → 二次 defineClass → JVM 判重复定义，宿主启动即抛 `LinkageError: attempted duplicate class definition`（Spring Boot 2.6.3 / JDK 8u181 / mysql-connector 8.0.28 现场实录）。修复：两织入器改仅 `COMPUTE_MAXS`（注入块为零分支直线，无新帧合并点）+ `EXPAND_FRAMES` 读入并给织入成功者每帧 locals 尾部补 token 槽 + 新增 `ZeroLoadClassWriter` 零类加载保守实现（防未来重挂 `COMPUTE_FRAMES` 复发）；后续二次修复按**槽数**（long/double 占 2）补齐 TOP 到 token 槽位再追加，并新增「织入产物必须在隔离 ClassLoader 里 define+initialize」回归（旧补丁 3/3 必红）
- **mapper XML 补全 dumb 态整体丢弃（T-XmlCmpl2）**：`XmlSqlCompletionContributor` 未实现 `DumbAware`，索引期（数据库方言 push / 项目初扫等 dumb 窗口）平台 `DumbService.filterByDumbAwareness` 把一切非 DumbAware 贡献器整体丢弃，按补全零响应；同包两兄弟贡献器均已实现 DumbAware。修复＝实现 DumbAware（索引不可用时降级为「只补关键字」）；另修沙盒失效双根因（派发劫持双语言注册 + 宿主还原生产形态）
- **mapper XML 补全：`where` 内裸列零候选 / 别名.列名插入重复（T-XmlCmpl3）**：`where` 内裸列补全零候选（动态标签语义替身 `stripXmlTags`）；别名.列名补全插入重复别名（`o.o.user_id`）
- **attach 跨通道去重吞掉 agent 首批事件（T-AttachZeroEvent）**：增强通道与控制台通道的去重索引未随通道切换重置，agent 挂载后的首批事件被跨通道去重吞掉；现接管时按通道纪元重置去重索引
- **日志 DDL 预览两缺陷（T-DdlLogPreview）**：①字面量恢复此前对零 `?` 的 DDL 也全量改写单引号、占位符化致 DDL 不可执行（`COMMENT '主键'` → `COMMENT ?`）——现加语句类型门禁（只对 `QUERY`/`DML` 恢复，DDL 与 UNKNOWN fail-closed）；②DDL 格式化此前无换行点、点「格式化」毫无变化——现按首关键字分派 DDL 排版（**DML 输出逐字节不变**）；③格式化零变化时给 INFORMATION 轻提示
- **增强捕获重开对 premain 进程 attach 握手失败（T-PremainReattach）**：关增强后 claimed premain 通道转暂停待重连（监听保留、重连 backlog 停泊）；attach 装载进程同病 → 端口登记 pid，恢复时按上升沿在原端口 re-bind（`SO_REUSEADDR`）接住回连
- **日期/时间类型参数还原改 ANSI 类型字面量（R3）**：日志还原链对带日期族类型标注的参数此前渲染裸单引号字符串，在 Oracle 上对日期/时间戳列隐式转换恒失败（ORA-01861/01843）。现按类型标注渲染 `DATE '…'` / `TIMESTAMP '…'`（ISO `T` 归一为空格、缺秒补 `:00`、不匹配回落引号），经多引擎真库逐格实测通用，无需方言分支
- **沙盒验收批缺陷修复（T-DevAcceptFix / T-DevAcceptFix2）**：分页预览两条语句补分号（LIMIT 与 COUNT 粘连）；预设切换参数回写错位（改按输入框 source 锚点显式映射）；预设按语句（方法）隔离；SQL 屏蔽管理对话框打开即 NPE（`createdAtFormat` ThreadLocal 声明位置排在 `init` 之后）；EXPLAIN 直送通道在 EDT 读盘命中慢操作断言；捕获诊断行补「已收事件 N 条」与「屏蔽规则命中 N 条」；XML 预览下参数区「清除」按钮失效；左栏分区按钮文字发虚（非整数磅值派生字体）；MCP 设置页就绪横幅；mapper XML 高亮在 Ultimate / 三方插件环境零着色（让位改宿主级探测）；注入器模板 javac8 编译；历史徽章渲染（`ExpandedItemListCellRendererWrapper` 包装渲染器）
- **性能修缮五件（F47~F51，批次 9 快件批）**：①语法高亮注入器喂入税消解（每元素一次服务查找改快照直读，实测 85→14ns/元素，喂入位查找 3002→0）；②mapper 解析器编程式注册挂应用级 Disposer 宿主（插件动态卸载容器级联摘除）；③参数预设切换只写激活指针不再全量序列化＋方案数上限 20；④两处并发缓存修缮（参数方案顶层结构改并发安全容器；控制台桥方法缓存负缓存此前因 null 值被吞从未生效，改 Optional 承载并补 ClassLoader 身份键）；⑤XML 内嵌 SQL 行标记两道闸并一（每元素查找 2→1，语义逐值等价）

- **增强捕获对运行中的达梦（DM8）应用 attach 后零事件（T-DmWeave）**：用户现场应用（mybatis-plus-join-demo）实际跑 dm profile（`spring.profiles.active: dm` → `jdbc:dm://…`，driver `dm.jdbc.driver.DmDriver`），开启增强捕获后面板持续零条目。根因同 PG 一族但多一层：`StatementWeaver.SPECS` 语句面/prepare 面**完全没有达梦类**（connect 面虽登记 `DmDriver`，attach 路径下存量池连接无法回溯 connect 包装），retransform 名单命中 0、无钩子可落。补登时又暴露织入器一处硬约束：达梦 javac 在出口处对已死局部发 `chop_frame`（`DmdbStatement.execute(String)`、`DmdbPreparedStatement.execute()`、`addBatch(String)`、`clearBatch()` 的出口帧 `locals[0/1]=top`），旧织入形态（出口块读原局部 `this`/首参）字节级 `VerifyError: Bad local variable type`——即使登记也登不上。现改造定时面织入为**入口捕获、出口读自有槽**：方法头把 `this`（EXEC_PARAM 再加首参 SQL）存进超出原 maxLocals 的新槽，出口块一律读自有槽，栈图帧补丁按槽位追加 token(long)/本类类型/String；由此达梦 `DmdbStatement`/`DmdbPreparedStatement` 的 timed 面（EXEC_PARAM/EXEC_CTX/BATCH）与 `DmdbConnection` 的 prepare 面（PREPARE）通过 JVM 校验并落钩，`attach retransform` 由 `matched=1 wove=0 failed=1 [dm.jdbc.driver.DmDriver=VerifyError]` 变为 `matched=4 wove=3`，真库 E2E 事件到达（`sqlHead=SELECT 1 AS attachpool_dm`，`registryUnresolved=0`）。达梦 `ADD_BATCH`/`CLEAR_BATCH` 面出口非 timed（仍读原局部）本轮不登记（同 chop_frame 根因）；达梦 connect 面 `DmDriver.connect` 出口命中同一 chop_frame（残留 `failed=1 [DmDriver=VerifyError]`），因存量连接场景已由语句面/prepare 面覆盖，本轮未改 ConnectWeaver、如实留痕。同批加固：语句面/prepare 面字节级回归守卫扩达梦三面 + 新增**厂商对齐守卫**（连接面已登记的每个厂商，要么在 SPECS 有语句面/prepare 面条目，要么在显式「已知不覆盖」白名单带原因），把「看着支持、attach 后一条不出」的静默缺口钉成红灯

- **增强捕获对运行中的 PostgreSQL 应用 attach 后零条目（T-PgWeave）**：对已在跑的 PG 应用挂载增强捕获后，面板持续零条目（去重窗远未到、事件从未到达 IDE）。根因是语句级织入的登记名单（`StatementWeaver.SPECS`）只覆盖 MySQL 与 H2——attach（agentmain）路径下连接池的存量连接无法回溯 connect 包装（connect 面只包新建连接），语句面又没有 PG 实现类可 retransform，钩子无处落地即零事件（premain 路径因连接建立时已被包装、走代理面，此前 PG 能捕获，掩盖了该缺陷）。现按 javap 实测 + 真实驱动类字节织入/JVM 校验批量扩面：PostgreSQL 语句面 `PgStatement`/`PgPreparedStatement`/`PgCallableStatement` + prepare 面 `PgConnection`（openGauss 2.x 走 PG 驱动同享）；同批评估后一并登记 Oracle（`oracle.jdbc.driver.OracleStatement`/`OraclePreparedStatement`/`OracleCallableStatement`，prepare 面 `PhysicalConnection`）与 SQL Server（`SQLServerStatement`/`SQLServerPreparedStatement`，prepare 面 `SQLServerConnection`）。新增驱动类字节级回归守卫（登记谓词命中 + 织入 changed + 产物过 JVM 校验，含非法帧负向自证）钉住形态，mysql/h2 原用例零漂移。达梦经同一探针暴露织入器帧-局部变量既有局限（出口 `chop_frame` 与出口块读局部变量冲突，VerifyError），本轮如实不登记；该局限已由后续 T-DmWeave 以「入口捕获、出口读自有槽」改造解决并登记达梦 timed/prepare 面

- **预览首次点「执行 SQL」结果区空白、二击正常（T-FirstExecResultInvisible）**：打开预览的准备链（远程数据源需数秒）未落地时点执行，旧代码会再建第二条临时文件/会话/控制台链，两条链的执行代际递增与提交/取消判定交错——提交被取消或结果组件被预览链换掉并折叠，用户见「无 loading 无报错下方空白」。现准备链单飞：在飞时执行意图只排队（不再建第二链），准备完成即按 executePrepared 口径自动消费执行（onResultsReady 齐、错误回调归执行意图自身，排队执行失败不再静默卡死）；另把控制台内嵌的折叠语义从 previewMode 布尔归口为显式 embedMode（EXECUTE 恒展开，PREVIEW 仅结果区无内容时折叠）——旧形态全链执行的换入也带 previewMode=true 被折叠，是同一空白现象的布局侧共犯。单飞位悬挂超 60s 自动接管自愈，执行按钮不会因历史挂死族永久排队

- **个别增强捕获条目源码位置显示 `[<generated>]`（T-SrcGenFrame）**：增强捕获下经 Spring 代理调用 MyBatis-Plus `ServiceImpl` 继承方法（`list`/`getOne`/`saveBatch` 等，真实方法体所在类被框架前缀跳过）时，栈上最深非框架帧是 CGLIB 增强类帧（`$$EnhancerBySpringCGLIB$$` 族）——其 SourceFile 属性是 Enhancer 字节码硬编码的 `<generated>`（spring-core javap 实证）、行号恒 -1，而类名前缀即业务包名、codeSource 随宿主类落业务目录，agent 侧 `StackLocator` 的 L1 来源判据与上顶收敛都把它当业务帧选中，面板遂渲染 `[<generated>]`（真机实证：同批条目直调 mapper 接口的拿到真实业务行、经代理调 Service 继承方法的全部中招）。现主位置选择（L1/上顶收敛/判定缓存命中深帧探测/候选列）一律要求真实源文件，生成类帧让位给池中真实业务帧（=触发本次调用的调用方行）；仅池内无任何真实源文件帧（调度器直发/框架回调等真无主形态）时安全网原样采用，`[<generated>]` 保留作真无主信号

- **预览编辑器行注释前缀统一为 `--` 双横线（T-PreviewLineCommentDash）**：预览/控制台编辑器内 Ctrl+/ 此前插入 `#` 前缀——预览文件被方言预映射强制为 MySQL 方言，平台注释动作经 commenter 链取到 MySQL 的 `#`，对 PostgreSQL 执行报 `[42601] syntax error`。现对预览自建编辑器与原生控制台编辑器接管行注释快捷键：插入一律 `-- `、再按一次剥除，修复前残留的 `#` 行同样可剥（缩进保留、多行选区整段切换、一步撤销）；未打标编辑器直通平台原行为零变化，块注释 Ctrl+Shift+/ 不接管（MySQL/PG 语义本就一致）

## [2026.10.9] - 2026-10-09

### Added

- **SQL 历史标签页常驻（T-HistoryTabAlways）**：历史标签页此前是条件页签——只有「开启自动保存」或默认历史目录下已有 `.sql` 文件时才挂载，且设置变更时会**把已挂载的页签摘掉**，于是未开启自动保存的干净项目里用户根本看不到历史入口。现双门禁整体拆除、构造期无条件挂载，空态沿用既有文案、零新增 UI；顺带修一处既有缺陷：历史文件的读端只认默认 `.idea` 目录，用户改过「保存目录」后写进去的历史文件在历史页里看不到——现目录解析单点化、读写同源，且保存目录变更即触发重扫

- **「最近执行记录」右侧面板可编辑（T-RecentEditable）**：与「SQL 日志单击列表弹出的预览浮窗」同效果——右侧 SQL 面板改为可编辑，可临时改字段值再复制（复制按钮与 Ctrl+C 都取改动后的文本）；自动折叠跟随设置。行号保持开启、内容恒为格式化（原拍板口径不变）
- **「最近执行记录」支持多选（T-RecentMulti）**：与 SQL 日志页同一套语义——Ctrl/Shift 多选、Ctrl+C 批量复制（逐行拼接纯 SQL）、多选时右侧编辑器按行序拼接显示（所见即复制）、状态行显示「已选 N 条」；「打开预览」取最后交互行，右键「复制时间戳」在多选下批量复制。单选行为不变

- **预览面板调用栈悬停调试器风格弹层（T-StackHoverUi）**：预览面板的调用栈区域悬停时弹出调试器风格弹层——结构化解析调用栈帧（`StackDigestFrames`），跳过尾部基础设施帧（Tomcat/线程池尾巴），锚定最深业务帧（Controller→Service 链）；帧数可配置（设置页「调用栈快照帧数」+ agent 侧双通道下发，默认 24 帧，范围 [12,64]）；尾部穿透补 spring web/boot 前缀；验收阻塞修复（日志预览识别/字面量恢复分支丢调用栈快照）
- **SQL → Java 反向转换能力扩展（T-SqlToJavaPromote）**：`GROUP BY` 建模 `.groupBy(...)`；`HAVING` 建模 `.having("...")`（聚合表达式原样透传）；`SELECT DISTINCT` 建模 `.select("DISTINCT ...")`；`FOR UPDATE` 建模 `.last("FOR UPDATE")`（与 LIMIT 并存合并单 last）；聚合/函数列（`COUNT(*) AS cnt`、`MAX(price)` 等）收集原文回落 `.select("expr AS alias")`（此前整体放弃致往返语义丢失）；`INSERT ... NULL` 值建模生成 Java `null`（此前报错）；`UNION / UNION ALL` 子查询解析（SQL 层往返经渲染还原，Java 生成层因 MP core 无 union() 方法降级为原文注释提示）；条件值参数化——`DATE_FORMAT(...) = '2024'` 形态生成 `apply("... = {0}", "2024")`（值/表达式分离）。
- **SQL → Java 方言宽容**：双引号标识符（`"member"`）、`0x` 十六进制字面量、反斜杠字符串转义（`'a\'b'`）、`DATE / TIMESTAMP '...'` ANSI 类型字面量；算术表达式 / `<=>` 空安全比较 / `ILIKE` 等无法静态映射的形态一律降级 `apply()` 透传（「任何条件都不挡路」）。
- **SQL → Java 返回类型自由输入（T-MapResultVo）**：「返回类型」字段（原「返回 VO」）改为可编辑组合框——自由键入任意返回声明（`List<Map<String, Object>>` / `List<HashMap<...>>` / `List<Object>` / VO 类名），一个控件承载全部返回形态语义；生成器按声明派生调用族：含 Map → `selectJoinMaps(wrapper)`/分页 `selectJoinMapsPage(page, wrapper)`（JOIN，MPJ）/`selectMaps`·`listMaps`（单表）族；含 Object → `selectObjs`/`listObjs` 族（单表；JOIN 场景无 `selectJoinObjs` 报错指路）；类名 → VO 语义（`selectJoinList(Vo.class, wrapper)` + `selectAs` 别名驱动）。Maps/Objs 族不带 Class 参数（1.5.8/MP 3.5.17 javap 实证）；正向识别 `selectJoinMaps` 调用与分页 Page 实参；MCP `sqlv_reverse_sql` 加可选 `maps`+`mapperVariable` 参数（默认不传零回归）。返回类型选择器补 Maps 分页声明 `Page<Map<String, Object>>`；返回类型声明项补类图标；返回类型固定候选并入「选择类」弹框前插；返回类型字段改照「调用类」同款形态（灰字占位标签＋点击弹选择）；返回类型字段去下拉（纯输入框＋选类按钮）。

### Changed

- **「最近执行记录」弹框化（T-RecentExecDialog）**：gutter 图标右键菜单「最近执行记录」由二级列表换代为浏览对话框——左侧列表直接复用 SQL 日志面板的条目渲染器（两行形态/类型色条/耗时与来源角标与日志页完全一致），右侧高亮编辑器展示参数还原后的可执行 SQL（后由 T-RecentEditable 由只读改为可编辑），选中切换/双击打开预览（预览后对话框保持在场，便于连续对比多条）；交互对齐「SQL 屏蔽规则管理」对话框（非模态/可拖拽/尺寸位置记忆）。空命中也弹对话框（状态行显示空态文案，原一闪而过的轻通知退役）；右键菜单、锚点快照校验与后台匹配链路不变
- **「最近执行记录」右侧编辑器恒为格式化 SQL（D91，取代 D88 的「切换原文」）**：编辑器显示只剩格式化一种形态（与预览「格式化还原 SQL」同源 formatter，主子句各占一行），长 SQL 不再挤成软换行长条；「查看原文」切换已按用户要求移除；编辑器上方两枚按钮改为**图标按钮**（图标取「日志转 SQL」结果区同款：格式化 `ReformatCode`、复制 `Copy`，文案走悬停），格式化按钮=幂等重排（格式化降级原文后可手动重试），复制取当前显示文本（经平台复制通道进 IDE 复制历史）
- **「最近执行记录」弹框列表右键菜单（T-RecentExecMenu）**：与 SQL 日志页右键同源六件——复制时间戳（受「显示时间戳」开关门禁，关闭时置灰）、屏蔽此 SQL（规则入库后该行即刻从弹框移除，气泡附「管理屏蔽规则…」直达）、发送到 SQL → Java、发送到日志转 SQL（工具窗口未创建时状态行提示）、跳转到 Java、跳转到 Mapper XML（定位不到即隐藏）；**不含**删除（弹框是浏览视图，不越权改全局日志缓冲）、方法级聚焦（锚点本身就是那个方法）、按请求聚焦、复制 SQL（按钮已有）。跳转/屏蔽/复制/送页签一律走日志页同一套实现（新增 `LogEntryJumpOps` 共享跳转原语，顺带消除实时页与历史页既有的一份重复实现）

### Fixed

- **SQL 复制快捷键按界面统一（T-SqlCopyShortcuts）**：此前只有单击预览浮层的 Ctrl+C 能复制，其余带 SQL 编辑器的界面（主预览面板、最近执行记录、屏蔽规则、回滚 SQL、SQL → Java 输入区、参数导入）以及「最近执行记录」的列表都无法用键盘复制——编辑器上按 Ctrl+C 走不到平台的复制动作、列表复制出的是渲染文本。现按同一套语义统一接线：编辑器内**无选中复制全文、圈选后只复制片段**（Ctrl+C 与 Ctrl+Insert 同义键）；「最近执行记录」列表的 Ctrl+C 改为复制纯 SQL（与 SQL 日志列表一致）。各界面原有的复制按钮/菜单不变
- **SQL 日志单击预览浮层 Ctrl+C 复制失效修复（T-ClickPreviewCopyRestore）**：单击 SQL 条目弹出预览浮层后按 Ctrl+C 复制不到 SQL、多选批量复制也被浮层打断。根因两条：①浮层为「弹出即可编辑」抢走键盘焦点（既有拍板，不回退）；②焦点落到浮层编辑器后，平台的复制动作在编辑器上取不到复制能力提供者，按键事件最终落回编辑器的默认复制——而无选中时它是空操作，于是「按了 Ctrl+C 毫无反应」（列表自身的复制链路本身正常，只是焦点已不在列表）。修复：带 Ctrl/Shift 修饰键的单击从第一击起不再弹预览（多选全程焦点不离列表，批量复制恢复）；浮层内 Ctrl+C / Ctrl+Insert 改为在编辑器自身的按键绑定层接管——无选中复制整条 SQL（与头部复制按钮同源同反馈），圈选片段时只复制片段；其余编辑器不受影响

- **单条错误上报「提交」没反应（T-ErrReportSubmitNoOp）**：从 IDE 的错误/冻结通知进入「报告到需求池」对话框后点「提交」，按钮不转「提交中…」、状态行空白、窗也不关——收集批次条目时对空容器做了裸索引（单条事件按设计不出批次分节，容器里没有复选框），抛出的越界异常被点击处理器外层吞掉，只在 IDE 日志里留一条 SEVERE（缺陷自 2026-09-15 起存在于此后的全部发行版）。现在容器访问全部带界，并且请求组装环节整体纳入兜底：万一组装失败，原因会显示在对话框状态行里（可原地改内容重试或转浏览器填写），不再「点了没反应」
- **重复的 SQL 预览图标与点击后弹框卡住（T-GutterDupFreeze）**：MP 链上出现假 HQL 锚点，同一行挂两枚预览图标，点开后弹框卡住关不掉。根因两条：①链内嵌 `.exists("select 1 from ...")` 令整条 wrapper 链源码被兜底当作 HQL 文本、被 `SELECT ... FROM` 正则命中（缺「首参须是字符串」门控）；②批量注册路径与链首 mapper 变量两条收录路径在同一行各挂一枚。修复：补字符串首参门控、源码文本兜底改空、批量注册路径让位；并把「按实体名找类」的全项目短名扫描改为索引构建期降级 + 有界扫描（上限 5000、可取消、命中即止），消除点击时持读锁连续 70 秒把 EDT 卡住、覆盖层窗口关不掉的机制

- **SqlToJavaPanel 两处 Throwable 捕获补 fatal 透传**：`SqlToJavaPanel` 两处 `catch (Throwable)` 未透传 `Error` 子类，守卫由红转绿（整段收口）
- **工厂链裸实参下带参 `exists` 被误判为链终结点，`EXISTS` 子查询条件整条丢失（T-ExistsChainTerminal）**：`exists` 在 MP 里同时是**条件方法**（`Join.exists(String, Object...) / Join.exists(boolean, String, Object...)`）与**链终结点**（`ChainQuery.exists()` 无参），PSI 定位层的终结点判定只按方法名匹配，二者又落在链尾时结构完全同形。于是 `service.list(Wrappers.<T>lambdaQuery().eq(...).exists("SELECT 1 FROM ..."))` 这类**裸工厂链直接作方法实参**（链内无 one/list/count/page 等真终结点）会把带参 `exists` 当终结点剔除——子查询条件从预览 SQL 里整条消失，且整条查询被误包成 `SELECT EXISTS (...)`，复制到数据库工具执行语义完全不同。现按实参个数区分（javap 实证 3.5.7/3.5.15/3.5.17 的 core 侧只有带参形态、extension 的 `ChainQuery.exists()` 才无参）：无参 `exists()` 仍是链终结点，带参一律作条件解析（布尔前缀照旧剥离为动态条件开关）；同一判据以 `isExistsConditionCall` 单点收口，供终结点筛选与 wrapper 变量接受门共用。`var w = Wrappers.<T>lambdaQuery()...exists(sql)` 声明形态同属此修复面（此前被同一名单拒为 wrapper 变量）。变量/分步/`new` 三种写法与 `notExists` 本就不受影响，各有回归用例钉住
- **调用类 MPJ 能力误报「无 MPJ 能力」**（真机实证：`DeviceInfoMapper extends MPJBaseMapper` 仍被误判）：「先贴 SQL 后选调用类」路径拿 stale 的能力判定（此前仅 SQL 解析链反查、选类回调不刷新）——现选类后后台重查 MPJ 能力与方法集再重生成。
- **列名→字段映射驼峰归一 + 多候选实体静默取首明示**（真机实证：`role_id` 等列大列表回落字符串）：列名查询双向归一（snake_case↔camelCase，含 `@TableField` 显式驼峰列）；同表匹配到多个实体时静默取首个现于状态栏列出全部候选与已选项（如不符可开启「手动选择实体」切换）。

## [2026.10.7] - 2026-10-07

### Added

- **参数字面量与枚举常量自动预填（T-JpaParamOrder F6）**：`setParameter("status", OrderStatus.PAID)` 这类字面量/枚举常量调用自动预填参数框，用户追问「为什么这些常量没有回显」后补齐
- **SQL 日志「滚动到底部」+ 尾部跟随（T-SqlLogPerf）**：SQL 日志工具栏新增「滚动到底部」动作——一键跳列表底并恢复跟随；跟随态下新 SQL 到达自动贴底，向上滚动阅读即自动停跟随（滚回底部/点动作恢复，IDE 控制台同款习惯）。此前 log 包无任何滚动控制，并发灌入时新条目只进模型、视口原地不动（用户体感「监听加载有延迟」的主形态）

### Fixed

- **JPA 链式 `setParameter` 参数在 SQL 预览中整体错位修复（T-JpaParamOrder）**：链式 `em.createQuery(...).setParameter(a).setParameter(b)...` 写法下预览渲染的参数值整体串位（用户实测 `setParameter("locationStatus",…)` 写重后五个条件值全部错位、尾参被丢弃，出现 `warehouse_id='TC'` 这类张冠李戴），参数面板行值却显示正确。双根因：①链式调用的 PSI 是嵌套结构，参数收集按前序遍历先外后内，收集顺序＝书写顺序的**逆序**；②渲染/复制时第 i 个 `?` 配第 i 个参数名的纯位置配对，重复参数名把参数序列撑到多于 SQL 占位符数，错切后尾参直接落空。修复三刀：收集后按参数表偏移归位为文档序（非链式分语句写法本就文档序，零影响）；同名命名参数去重保首现序（JPA 覆盖语义）；渲染与「复制可执行 SQL」改按名回填（HQL `:name` 占位符名序，同名占位复用同值），与 setParameter 书写顺序彻底解耦——位置参数混入时保守回退按序回填。附带修复「表： long」：`createQuery(hql, Long.class)` 的结果类型 Class 此前被当实体解析、面板表名误显 `long`，现非 `@Entity` 的结果类型回落语句头实体名解析真表；同名写重时参数区尾部新增灰字提示（运行时以最后一次为准）。测试：先红三例（链式收集序实测逆序实锤、写重 6 set/5 名序列与用户截图反推逐字节一致、乱序 set 渲染错位）＋摘修复复红反向验证，定向 88 例全绿
- **参数占位启发式复合词误报修复（O-111 用户 demo 实测跟进）**：`locationStatus` 这类复合词此前被参数占位启发式误配为集合占位，现不再误配
- **并发大量 SQL 日志下监听与历史加载性能修缮（T-SqlLogPerf，20261006-1020 开发计划）**：日常 IDE 实测并发压测约 65 条/秒持续灌入、8708 条/19MB 历史文件加载 15~16.6s（超线性劣化）后的五处修复——①历史二级列表改**滚动追加**（首屏 30 条 + 滚动逼近底部才续写；原 invokeLater 链对 8708 条排 290 个 EDT 脉冲全量排空，无论看多少全部进模型）；②历史页浏览态 refresh 不再每次**全量重读当前文件**（(lastModified,length) 快照判定，未变直接复用已解析条目——实测同一浏览窗口 19MB 被解析 3 遍）；③历史解析**单遍化**（每块 joinToString→lines()→joinToString 三份拷贝砍到一份，解析语义逐字一致）；④去重判定锁内 O(n) 全表扫描改 dedupeKey→最近时间戳 **O(1) 索引**（并发下三通道写线程与 EDT snapshot 抢锁的大头；入队/淘汰/裁剪/开关/清空/删单六维护面等价性守卫钉死，摘修复必红）；⑤实时列表全量重建链**防抢占**——风暴期 200ms 一次的新刷新此前反复重启重建链致列表卡首屏 30 条，现进差分前同步补完在途链。另热路径新增阈值触发耗时探针（idea.log 检索 `[T-SqlLogPerf]`，单次 >100ms 才打，常态零噪音）供真机对账。agent 接管期桥侧嗅探最小化一项经复核被 T-AgentLogTime 既有定案（日志时间无条件采集）推翻，维持不动

### Changed

- **帮助页 / Marketplace 自定义页文案精简**（`html/marketplace-custom-page.html`，用户令「文字内容多」优化）：功能矩阵 27 个「说明」单元格压缩到一行级——MCP 工具行由十工具逐名枚举＋理念句（约 200 字）收敛为能力面概述（渲染 SQL / 表结构 / EXPLAIN / 日志还原 / 回滚预案等 + agent 兼容与逐工具门控），增强捕获行剥离注入确认 / 24h 冷却细节（FAQ 第 8 条已承载），多租户 / 自动折叠 / 历史定位等长单元格同步瘦身；「为什么选它」要点去与矩阵重复的细则、标语盒三行合两行。全部功能名、New / Ultimate 标签、触发方式、FAQ 八条、快捷键与链接零删减，页面可见文字 5005 → 3973 字符（-21%）；`HelpUiTest` 仅断言资源可加载与非空（无文本断言），零影响。另新增 **Markdown 版 `docs/发行说明/marketplace-custom-page.md`**（整页复制进 Marketplace → Documentation 自定义页编辑器直接使用）——该编辑器仅渲染 Markdown（`<style>` / class / 内联 CSS / 纯 CSS 手风琴均被剥除），原 HTML 版保持不动、专供 IDE 内帮助按钮 JCEF 渲染；New / Ultimate 徽章以官网托管的同款配色 SVG 药丸图内联承载（`sql-visualizer.jvtools.cn/badges/tag-new.svg` / `tag-ultimate.svg`，Markdown 无 CSS 无法做药丸，图片徽章与 HTML 版观感一致），FAQ 由手风琴改为小节标题。发行流程文档（`docs/发行说明/Marketplace发布流程-说明文档.md`）§10.2 / §11 已同步登记自定义页更新步骤与「md + html 两版同步维护」约定，HTML 版头部加配套注释。两版各内联三张演示动图（Wrapper 静态预览 / SQL 日志 / MCP agent 调用），以官网 `/demo/*.gif` URL 引用——插件 zip 体积零变化，帮助页离线时动图不显示、页面其余内容照常（html 侧 lazy 加载 + 暗色边框适配）

## [2026.10.6] - 2026-10-06

> 预先整理于 2026-09-30，发行日 2026-10-06（版本号按「版本号即发行当日日期」规则预定）。范围核算基点＝**v2026.9.23 发行包的打包点 `1872464`**（用户已装 zip 所含内容），而非标签 `2026.9.23` 当前指向——该标签其后曾为源码包一致性两度前移，标签树里已含本版部分内容但发行包不含。

### Added

- **`@Embedded` 嵌入值对象识别补全（T-Embedded）**：用户报障「插件不能识别 @Embedded」，调研定缺口三处全量闭合——①**嵌套点路径覆写（G2）**：JPA 规范要求嵌套嵌入的列名覆写用 `name="zipcode.zip"` 点路径，此前 `@AttributeOverride` 只按裸名查找、点路径覆写静默丢失——现按「实体根点路径（`address.zipcode.zip`）> 嵌入相对点路径（规范例 2 形态 `zipcode.zip`）> 裸名兜底」三级查找、点路径优先，类级覆写键随嵌套链传递、字段级相对键逐层合并；②**Spring Data 形态（G1）**：`@Embedded.Nullable` / `@Embedded.Empty` 捷径注解此前落成伪单列、裸 `@Embedded` 的 `prefix` 丢失——现双轨判定（注解可解析按全名、不可解析按注解文本三形态）接通识别，`prefix` 按官方语义拼在嵌入对象每一列之前（同一值对象多处嵌入防撞车）；③**MP 混合注解实体（G3）**：`@TableName` 与 jakarta `@Embedded`/`@Embeddable`/`@EmbeddedId` 共存的实体此前在 Wrapper 预览、批量模板与 CRUD 生成里产出 `update_info` 伪列——现值对象字段展平为宿主表列。真机走查再抓出派生查询缺口并随轮修复：`findByUpdateInfoCreatedTimeAfter` 这类嵌入段驼峰路径此前在派生查询 WHERE / ORDER BY（含 `Sort.by` 字面量属性）被映射成伪列 `update_info_created_time`（SELECT 全列展开与 `@Query` JPQL 链本就正确，唯派生查询中招）——`TableInfo` 新增 `columnByEmbeddedPath` 按「嵌入字段名 + 首字母大写内层字段名」拼接匹配展平列，`DerivedQueryConverter` / `JpaQueryEnhancer` 两处消费接入，非嵌入的拼接属性仍走蛇形兜底不误判。测试：实体解析五形态 + 点路径覆写 + SD 形态 + 派生查询 WHERE/ORDER BY 映射与反例，先红后绿、反向验证读数在案；沙盒 runIde 真机两用例闭环（`@Query` JPQL 一次通过、派生查询缺陷抓出即修复复测）

- **自研框架场景跳转落点双修复与 JAR 业务帧反向名单（T-ToolSiteBizFrame）**：SQL 执行点在业务自研工具类里的两个「跳转上顶到上层调用方」机制一并收口——①判定缓存「身份在场」粒度不足：同一 Service 方法先直发一条 SQL 后再经业务工具类发第二条时，缓存的主位置身份（该方法）在后续栈上作为祖先帧必然在场，校验照过、主位置被钉在「调用工具类」那一行；现缓存命中后在比缓存身份更深的调用链段里找首个业务来源帧，找到即改用并刷新判定（直发形态快路径零反射，健康调用零成本）。②业务工具类以依赖 JAR 形态运行时被来源判定当第三方帧、主位置上顶到第一个目录帧；现设置页新增**「JAR 业务帧包名前缀」**——语义与「源码捕获帧过滤前缀」相反（名单内包名的帧即使来自 JAR 也按业务帧对待），随 CONFIG 热下发（`bizJarPrefixes` 键 + 回执扩严格四键），agent 侧 L1 与缓存命中路径共用统一业务来源判据；跳过前缀照旧只跳框架帧，把 JAR 业务包登进跳过栏仍会使跳转失效（该语义不变）。复现载体＝自研语料工程 E28~E34（先红后绿），真机走查与语料 HTTP 冒烟待 MySQL 走查轮
- **前缀登记格式校验与一键修复（T-PrefixValidate）**：设置页两栏前缀（源码捕获帧过滤前缀 / JAR 业务帧包名前缀）随输入即时校验——非法行（只允许字母、数字、下划线、$ 与点，建议以「.」结尾）、同栏重复条目、同一前缀跨栏冲突（运行时跳过栏静默优先、业务帧登记不生效）在输入框下方汇总提示，多问题时显示问题数与首条、悬停看全部；**一键修复**一次完成逐行 trim、丢空行与非法行、同栏去重、跨栏冲突按跳过栏优先从业务帧栏剔除，无问题时提示行自动隐藏。判定逻辑独立纯函数（单测覆盖），面板只做展示与回写
- **信创方言补全族：OceanBase 与 openGauss（T-DialectXcFamily，O-63）**：方言注册表 9 → 11——新增 `OceanBaseDialect`（MySQL 模式：backtick 引号、LIMIT、NONE 折叠）与 `OpenGaussDialect`（PG 族：双引号、LOWER 折叠），EXPLAIN 支持面表与 URL 推断三处（IDE 推断 / agent 角标 / MCP 链）同步登记 `jdbc:oceanbase://`、`jdbc:opengauss://`、`jdbc:gaussdb://` 三个前缀（各自官方文档证实）。openGauss 形态经在册真库引擎级验证——并据此推翻「PG 族 = ON CONFLICT」推定：openGauss 的 upsert 实为 `ON DUPLICATE KEY UPDATE` MySQL 形态，`renderUpsert` 按真机证据落地；`LIMIT 1,2` MySQL 形态在 openGauss 被 PG 语义静默错解（0 行）一并钉进用例。OceanBase 全形态为文档级已验、环境受限未验证（在册无 OB 真库），如实留账
- **SQL 日志屏蔽（T-SqlSuppress，O-69）**：保活心跳类 SQL（如 `SELECT 1`）持续刷屏日志列表——现可在日志列表右键【屏蔽此 SQL】一键屏蔽，支持多选（不同语句各成一条规则、已存在自动跳过）；工具栏新增【管理屏蔽规则】（漏斗图标）与右键【管理屏蔽规则…】双入口打开同一管理对话框：规则列表（模式摘要/创建时间/已拦截 N 次计数）+ 右侧 SQL 语法高亮只读预览（带行号），新增/删除/清空即时生效、关闭即生效。匹配口径＝还原 SQL 空白规范化后精确匹配（复用去重指纹），`SELECT 1` 与 `select  1` 命中同一条规则、大小写敏感与既有规范化一致；规则应用级全局生效、独立存储不进设置同步，上限 500 条超限拒新增；屏蔽动作三层反馈——列表存量行立即消失、balloon 可直达管理对话框、管理对话框实时命中计数；删除规则后仍在缓冲内的条目恢复显示（已入队丢弃的不可恢复，界面文案明示）。后续走查返修两段（2026-09-24）：管理对话框分栏与规则行对齐日志列表形态、匹配口径追加尾分号剥离（规则粘贴态带 `;` 与日志还原 SQL 互命中）；并新增可选**结构匹配**（默认关、按规则手动开启或一键全部开启）——开启后按语句形状匹配，参数值不同的同形状 SQL 一并屏蔽（字符串字面量与裸数字参数化为 `?`，引号标识符与标识符内数字保留）。管理对话框支持拖拽缩放与尺寸记忆（O-80，`DialogWrapper` 默认固定尺寸放开 + `DimensionServiceKey` 记忆，最小 560×360 兜底）
- **无别名 HQL 属性映射盲区修复（T-HqlNoAliasProp，O-71）**：预览无别名形态 HQL 时，`WHERE stationCode = ?` 这类裸属性与 `updateInfo.BE_DELETE = 0` 这类嵌入段占限定符位此前残留属性名不转列名（O-57 别名作用域映射只覆盖带别名形态）——现收敛在 `JpaAliasTableResolver` 单文件：新增 `mapBareProperties` 裸词映射（字面量分支前置 + 函数调用负前瞻 + 子句关键字/已知限定符/TRUE·FALSE·MEMBER 等排除），嵌入段占限定符位按 `column.embeddedParent == qualifier` 判据剥离产出裸列名（带别名三段形态逐字不变）。**契约变更（显式登记）**：O-57「裸属性透传不回归」保守契约推翻——透传产物本就不可执行，属修复非回归。测试：探针转正 `HqlBarePropertyMappingTest` 10 例 + `HqlAliasColumnMappingTest` 契约改造 3 例，RED 与反向验证 8 红同形态、实施后定向 31 条全绿；用户场景产物与 Hibernate 真实语义一致（`WHERE STATION_CODE = ? AND BE_DELETE = 0`）。
- **通用 XML 内嵌 SQL 识别（T-XmlInlineSql，O-72）**：RFListPage 这类自研框架 XML（根元素名不固定、SQL/HQL 写在自定义标签的 CDATA/文本里）此前被 mapper/pages/hbm 三套「标签名+父标签名」白名单全部挡下——无 gutter 图标、右键/Alt+Shift+Q 弹「未找到」。现新增作为三套白名单之后的**兜底第四通道，锚定内容不锚定标签名**：叶子标签首段文本以 SELECT/INSERT/UPDATE/DELETE/WITH/REPLACE 开头（大小写不敏感）即认领，任意根元素/任意框架生效；载体标签自身或祖先的 `type="hql"/"jpql"` 走 HQL 转换管道（复用 hbm 命名查询链，别名作用域属性列名映射自动生效），转换失败或实体不可解析**回退原文直显不抛错**，原生 SQL 直显带 `?` 占位符参数表；右键/Alt+Shift+Q 的光标定位、全文件候选与选择区候选同步追加载体兜底，多语句弹选择栏（与既有 XML 候选交互一致）；既有 mapper/pages/hbm 文件由排除集合保证零双图标；设置页「界面与操作」新增总开关（默认开）。附带判别性实测：本平台 `XmlText.text` 对 CDATA 返回带 `<![CDATA[` 包裹的字面全文而非展开内容（首跑 11 例红实证），检测器统一剥包裹；同口径缺口经延伸修复收口——抽共享 `xml/XmlDirectText.kt`（`directTextOf` 直属文本拼接 + CDATA 剥包裹 + trim），`HbmXmlEntityResolver` 与 `previewHbmNamedQueryTag` 两处 hbm 命名查询提取点改走共享口径，旧「CDATA 展开」非判别性断言改判别式（钉死包裹不得残留）。测试：新增 26 例（RF 真实样例/根元素改名/CDATA 与纯文本/排除集合防双图标/HQL 实体转换与回退）+ 既有回归 51 例全绿；反向验证三条读数达标（摘认领→RF 图标红、摘排除→mapper/pages 双图标红、摘 HQL 管道→转换红）；hbm CDATA 延伸修复反向验证 8 例红、定向 5 类全绿。
- **XML 预览 List 参数按签名拆分多值渲染（O-81，T-XmlListParamSplit）**：`List<String>` 签名的集合参数在值框输入 `PI,WP` 此前被渲染成 `IN ('PI,WP')` 整串单值——两个根因：IN 形态探测对 `IN` 与 `(` 之间只容忍恰一个空格，mapper 排版换行即漏判；签名 COLLECTION 信息未参与回填组装，拆分后被集合恒引号档再包一层。现新增 `XmlParamBackfillHints` 纯函数：签名 COLLECTION 无条件按逗号多值渲染，引号按元素类别派生（文本加引号、数值裸渲染，与 O-78 foreach 元素同口径），显式 typeHandler 豁免，无类型信息维持原形态；IN 探测放宽为任意空白串（多 `?` 列表仅首位命中的语义钉住不变）。端到端复现 `IN ('PI,WP')` 先红 3 例，改后定向全绿

### Changed

- **空参数填充策略三态化，默认改为占位符 `?`（T-EmptyParamFillTernary，O-79）**：未填写的参数（含 foreach 集合元素）默认保留 `?` 待填提示，不再默认渲染 `NULL`——NULL 会静默过滤行（如 `amount >= NULL` 恒不匹配），不易察觉有参数未填。需要可直接执行的 SQL 时在 设置 → 空参数填充策略 切换 NULL / 空字符串档；已在设置里持久化过 NULL / 空字符串的用户保持原档不受影响。
- **注入识别不了项目类型的弹窗改引导开启增强捕获（20260924 定案）**：拦截器自动注入无法识别项目类型（非 Spring Boot + MyBatis-Plus 工程）时，结果弹窗此前只给一个「确定」并让用户参考手动配置示例——现改为引导弹框：默认按钮「开启增强捕获」走与缺位置引导（O-66）同一编排（置位开关 + 授权确认 + 重启生效提示一条龙；agent 通道不依赖项目类型、不改工程代码），取消静默返回；已开启已授权的用户维持普通警告。双语基础文案同步去掉手动配置指引（docs/instrumentation/SqlSourceInterceptor.kt 示例文件保留，手工配置路线仍在，只是不再主动指路）。
- **MCP 工具线全动态化，兼容性验证五目标全绿（T-McpDynamicToolset）**：v2026.9.23 的 MCP 工具类以静态类型引用平台 `com.intellij.mcpserver` API，Marketplace 静态验证在 243/251 平台报兼容问题（该通道此前未上架此形态）。现整线改为 jar 字节码零 mcpServer 类型引用——基类反射化桥接、工具集声明退役改运行期 ASM 壳注册（simpleName 保持 `SqlVisualizer` 前缀=分组名不变）、audit 改 Kotlin intrinsics 透传绕开 243 stdlib 缺失的 SpillingKt；243/251/253/262/263 五目标验证全 Compatible，262 沙盒十工具端到端读数与改造前逐字一致。对用户行为零变化（MCP 能力仍需 IDEA 2025.2+，十工具清单与门控不变）
- **源码捕获两前缀输入框可拖拽分栏与默认加大（T-FramePrefixResize）**：设置页「源码捕获帧过滤前缀」与「JAR 业务帧包名前缀」两框由焊死等高改为垂直分栏——拖拽中缝重分配两框高度、比例跨重启记忆；默认 3 行 24 列加大为 5 行 40 列（首选高保底 100px、分栏拖拽下限 60px），宽度随设置对话框拉伸、占位文案不再截断；「自动注入源码捕获拦截器」标签补「（推荐使用增强捕获）」后缀，术语对齐相邻的增强捕获开关

### Fixed

- **MCP 工具真机全失修复：动态壳字节码生成去 ASM 依赖（T-McpDynamicToolset 真机 ASM 不可达）**：20261005 用户把打包插件装入日常 IDEA 2026.2 后，MCP 服务器里本插件十工具全失——真机日志实锤唯一断点＝工具集动态注册生成壳类时 `NoClassDefFoundError: org/objectweb/asm/ClassWriter`（六轮复现；同会话门控过滤注册成功，证明 MCP 插件探测与注册链全通）。根因：平台 262 自带 asm 库，`org.objectweb.asm` 因此是平台已知包，插件 classloader 对该包 parent-first 接管下**插件自带的 asm jar 不可达**、平台 asm 又位于对插件不可见的内部库 jar——「自带 asm 免平台可达性差异」前提被真机证伪；而开发沙盒用的平台不含该库，故动态化上线时的沙盒十工具端到端绿是假绿，该形态此前从未真机验证。修复：壳类字节码（`extends 工具类 implements McpToolset` + 唯一构造器，major 61 与原版逐语义一致）改由自研 `TinyClassFileWriter` 按 class 文件规范手写生成，零外部依赖、不撞平台包名；asm 依赖移除、发行包不再携带 asm jar（−118KB）。工具注册链/「暴露工具」分组名/设置页门控/审计全不变。新增守卫「mcp 包零 `org.objectweb.asm` 编译期引用」（ASM 引用回归即红；先红后绿两读数在案）；真机复验在用户日常 IDE 重装后对账（注册行 + tools/list 十工具 + 端到端调用）
- **premain 注入不再落入 Gradle 测试与 IDE 任务的 fork JVM（T-GradleTaskInjectScope，O-73）**：开启增强捕获后，`-javaagent` 此前会经 init script 传播进 Gradle/Maven 任务 fork 出的全部 JVM——从 IDE 工具窗跑 `test`/`runIde` 等任务的 fork 进程也被挂上 agent。现按任务语义收窄：测试族（test/check/verification/surefire 等）与 IDE 族（runIde/buildSearchableOptions/prepareSandbox 等）不再注入，`bootRun`/`run`/`quarkusDev` 等真实应用运行任务照常注入，非 Gradle 的普通 Java/Spring Boot 运行配置行为完全不变；跳过时提前退出，不落盘 agent jar、不开协商端口，判定异常时按原行为注入兜底（绝不影响任务本身）
- **T-SQL 控制流批次切分两处误合并（T-SqlSplitterTsqlGuard，O-60）**：①`BEGIN TRAN/TRANSACTION/DIALOG/CONVERSATION/DISTRIBUTED` 等非块形态此后方文本中任意位置的 `END` 词（如 CASE 表达式）误武装块计数、后续所有顶层 `;` 不再切分——整段脚本并成一条送执行；此类 BEGIN 不再武装；②`IF @x=1 BEGIN SELECT 1; END` 换行后接下一条语句（T-SQL 惯例 END 后分号可省）此前被并成一条——块深度归零的顶层 `END` 现产生隐式语句边界（声明段内例程体的 END 不受影响，Oracle 匿名块/DELIMITER 存储过程语义逐字节不变）。定向 81 例（先红 5）全绿，切分消费方回归 159 例全绿
- **回滚产物含 `SET IDENTITY_INSERT` 时静态检查误报与默认执行不完整（T-RollbackIdentityParse，O-64，P19 收口）**：①含 `SET IDENTITY_INSERT [user] ON; INSERT …; SET IDENTITY_INSERT [user] OFF;` 的回滚产物走无界写静态检查此前报「无法解析语句结构」（JSqlParser 对 T-SQL `SET` 语句解析期抛错，五种表名形态实证全败）——现检查前按精确形态剥离 identity 包装语句再解析（单表引用 + ON/OFF + 干净边界；该检查面同时服务用户手写输入故不宽剥，剥不到维持 fail-closed），SQL Server 与 DM8 双方言受益；②执行对话框 Statements 选择此前默认只勾第一条（＝第一条 `SET … ON`），不逐项勾选直接执行时只切了开关、INSERT 不跑——identity 包装产物现默认整批直跑（执行期副本覆写，不弹选择层、不改全局设置与缓存），普通多语句产物行为不变
- **MCP SELECT 白名单误杀同名标量函数（T-SelectGuardScalarExempt，O-61）**：`SELECT INSERT(name,1,2,'x') FROM t`（MySQL 串函数）此前被只读闸门粗扫按 `insert` 关键字误拒；`SELECT TRUNCATE(price,2)`（MySQL 数学函数，8.0 手册 12.6.2）同形误拒——现对「写动词后随可选空白 + `(`」的标量函数形态豁免（DBX 同款判据 + 逐词出处登记：insert/replace/truncate 三词，replace 此前已豁免），豁免安全性由结构闸保证（能到达粗扫的输入必已解析为单条 SELECT，真实写语句到不了这一步），`INSERT INTO`/`UPDATE (t)` 等真实写形态回归用例钉住不变
- **SQL Server 方括号 SQL 被 MCP 只读闸门整类拒绝（T-SelectGuardScalarExempt §八解析收拢，用户确认载体）**：产品方言层为 SQL Server 自产的方括号形态（`SELECT * FROM [user]`）此前在 `sqlv_explain` 等只读链路上因解析器不支持方括号而 fail-closed 拒绝——现 MCP 闸门解析入口改走共享 `SqlAstParser`（开方括号支持），方括号 SELECT 可进入既有结构判定（单语句 / 非 INTO / 非锁读 / 粗扫四道闸一条不少，多语句走私等负样本四组判据全数保留），fail-closed 兜底路径原样不动
- **方括号 SQL 在性能建议与批量合并链静默降级（T-PerfSweep7 T-PS7-03）**：`SqlPerfAnalyzer` 与 `BatchPredicateMerge` 的解析入口此前对方括号语句解析失败即降级（AST 性能规则整体不命中 / 合并预览判不可合并）——现统一改走共享 `SqlAstParser.parseSingle`，方括号语句的 AST 规则与合并预览正常生效；连接错误分类器的 DDL 目标提取同步收拢（行为等价）
- **agent 通道慢拨号被误判为正常掉线（T-PerfSweep7 T-PS7-02）**：agent 发送循环的退避复位与 fastDrops 判据此前从拨号开始时刻起算——拨号耗时超阈值才建连的场景（连上即被对端关闭）被误判为「存活超阈值正常掉线」而复位退避；现改按建连成功时刻起算，慢拨号 + 立即关闭正确计入 fastDrops 并触发退避换端口
- **foreach 集合元素渲染双缺陷修复（T-ForeachItemRender，O-78）**：`List<Integer>` 等数值集合在 foreach 值框输入 `1,2,4,5` 后，预览渲染 `IN ('1','2','4',?)`——数值元素被误加引号成字符串，且部分值丢失留裸 `?`。现元素值按集合元素类型渲染（数值裸渲染、字符串/日期保持引号），且任意输入节奏下所填值全部完整回填（渲染帧内播种，不再受界面防抖重投递影响）。连带效果：foreach 元素与普通参数一样纳入「空参数填充策略」（未填写且有签名类型信息时按设置渲染 NULL/''）。
- **XML 预览对无 ew 参数语句误报「无 Wrapper 参数」红色警示修复（T-EwStatementScope，O-77）**：同一 mapper 文件里其他语句带 `${ew.customSqlSegment}` 时，预览普通语句（如 demo 的 selectOrdersByCondition）会误报「方法 … 无 Wrapper 参数（ew），无法展开 MP 占位」——占位识别域从整个文件收窄为当前语句原文（含 include 展开后内容），同文件他语句的占位不再记账到本语句；顺带收敛跨语句不同变量名占位污染与跨命名空间 include 占位漏检，语句外注释里的占位不再记账。
- **控制台捕获在「只开 JdbcTemplate DEBUG、无参数行」的工程里零产出修复（T-ConsolePendingFlush，O-70）**：关闭增强捕获后仅靠控制台通道时，语句行之后没有同线程后续行的语句（未开 TRACE 参数行、Tomcat 按请求新建线程）此前会一直停在解析桶里不产出（面板显示「已扫描 N 行 / 产出 0 条」）——现按「静默 1 秒」判定语句已完整并交出，轮询每拍兜底一次；同时修掉 2026.2 上停止后重启捕捉时旧 filter 摘不掉导致的重复解析。
- **MCP 真机走查三项观察收口（T-McpWalkFindings，真机十工具端到端走查全绿后收口）**：①`sqlv_render_sql` 的 line 参数指向语句标签行（即编辑器 gutter 标注的行号）时报「找不到语句」——定位锚从行首改为**行内首个非空白字符**（标签行行首是缩进空白，语法树沿父级爬不到语句节点），纯空白行顺延最多 2 行，错误文案补操作提示；②`sqlv_mapper_check` 空参模拟对含动态分支的语句 6/10 误报 `DANGEROUS PARSE_FAILED`——真因链＝mapper 渲染产物保留源 XML 换行缩进、注释剥离后空行变「真空行」，JSqlParser 5.3 简单模式解析失败时**静默返回 null 而非抛异常**，落成无归因价值的空指针——共享解析槽入口空白规范化治本（确认流与回滚链同时获得真空行免疫）＋解析入口对 null 响亮防御（残余失败带输入摘录），输出补 `simulate risk note:` 归因线索行；③`sqlv_schema_context` 成功路径首行披露实际命中的数据源（`source: …`），未指名时标注 `auto-resolved` 并附 pin 提示（跨源静默解析两次真机实证：schema 可能来自非意图库）。反向验证＝五处摘除六用例全红；mcp+execute+sql 三包定向 1070/0 绿，testFast 全量绿
- **增强捕获确认去重窗两属性的初始化顺序陷阱（初始化顺序守卫抓出）**：`AgentManager` 的 `consentAskGuardMs` / `lastConsentAskAtMs`（R2 去重窗）声明在 init 块之后，而 init 注册的 SETTINGS_CHANGE 监听上升沿回调链会同步读它们——若设置变更事件在构造期同步到达，去重窗宽度读到 JVM 默认值 0（去重失效、可能双弹）且弹窗时刻表为 null（NPE）。两属性声明上移到 init 块之前（测试缝区块内），初值与语义零变化；守卫回归绿。
- **首次启用增强捕获首轮运行零捕获修复（T-FirstUsePremainConsent，用户报障）**：第一次使用插件、在提示开启增强捕获弹窗点「确定」后 SQL 捕获并未生效，重启项目才正常——两层根因叠加：①时序上注入确认出厂未达成，首轮 Run 启动前 `-javaagent` 注入被跳过；②能力上 agent 只在驱动 `connect` 时包装连接，attach 兜底不能回溯包装 attach 前已存在的连接——Spring Boot/Hikari 池化连接全部建在此之前，该轮所有 SQL 都走旧连接（沙盒复现日志实证：CONFIG 启用三次回执后 4 个 HTTP 请求期间零 SQL 事件）。最终形态为**确认前置到「开启」时刻**：设置页勾选开启（上升沿）与引导弹框「开启增强捕获」在开启那一刻同步弹一次注入确认，授权持久化先于任何 Run——此后每轮启动即带 premain、连接从建池起就被包装，首轮即完整捕获（去重窗吸收引导链「置位+发布+请求确认」毫秒级连发的第二次请求，只弹一个框）。两道兜底：订阅执行监听后**补扫已在跑的主配置进程**（堵「项目刚打开就 Run、事件早于订阅」的静默竞态）；EDT 前提不成立的边缘路径回落 attach 链确认，attach 兜底挂载成功后新增能力边界通知（如实告知「挂载前已创建的连接不在捕获范围，重新运行项目后完整生效」，每轮至多一次）。拒绝/关闭增强语义不变（24h 冷却只管弹窗频率）。**出厂态首启静默修复（R3）**：出厂即「已开启但未授权」的形态不会产生设置变更上升沿，「开启时刻确认」在该形态永不触发——现**项目打开即弹一次授权确认**（先于任何 Run，同意后首轮启动即带 premain、捕获完整）；补扫主配置判据同步勘误（243 平台 `RunContentDescriptor` 无 `getRunProfile`，旧判据致补扫结构性空转零诊断——改 JVM 进程判据 + 逐描述符跳过原因日志与汇总读数）。反向验证：摘上升沿确认 → 行为用例红、摘补扫接线 → 守卫红；agent+config 全包回归 335/0 绿。
- **增强捕获确认流测试隔离缺口修复（发行门禁暴露）**：`AgentManager` 是项目级单例（跨测试类/用例复用同一实例），`AgentManagerFallbackTest` 与 `SourceCaptureGuideEnableFlowTest` 的 setUp 漏复位确认去重窗——T-FirstUsePremainConsent R2 引入去重窗（`consentAskGuardMs` 默认 1500ms）后，前序用例设过的 `lastConsentAskAtMs` 会让本类的确认请求被去重窗吞掉、`consentProvider` 回调不触发（断言收到 null）；`AgentConsentFlowTest` / `AgentPremainConsentTest` 早已按此口径复位，这两个类漏改，R3「项目打开即确认」入口上线后污染路径激活，全量 testShards 首次暴露（此前的 testFast 快测桶不含这两个引用平台类的用例，未覆盖）。补 setUp 复位（`consentAskGuardMs = 0` + `lastConsentAskAtMs.set(0)`）与既有口径一致；**单方法隔离跑通过**坐实为测试隔离缺陷而非生产缺陷（生产单项目单实例，无跨项目污染）。定向四类复跑 26s 绿。
- **JPA/HQL session 通道 UPDATE 实体名提取缺失与嵌入列映射修复（T-HqlUpdateEntityMap，用户报障）**：无 FROM 子句的 HQL UPDATE（`session.createQuery("UPDATE …")` 自定义 DAO 形态）此前表名与 `booking.updateInfo.createdTime` 这类嵌入属性路径原样透传——三处 FROM-only 实体名提取正则全不命中，表名 / 属性映射整链跳过只剩参数替换。现实体名提取收敛单点 `HqlEntityNameExtractor`（语句头优先＝UPDATE/DELETE/INSERT 头 > FROM，规避 UPDATE 带子查询时误命中子查询实体，五份拷贝收敛一处）；单表 UPDATE 渲染剥别名（对齐 Hibernate 真实产物 `UPDATE t SET col = ? WHERE id = ?`；raw 透传文本不剥别名登记为已知边界）
- **JPA 通道 DELETE 全表删除误报修复、UPDATE 警示补齐与字面量保护（T-JpaDeleteWhereGuard 及同构普查批，用户报障 + 「其他功能类似问题一起修复」）**：JPA/HQL/JPQL 链构造 DELETE 时模型条件树恒空、全表删除警示按模型而非 SQL 文本判定——任何带 WHERE 的 JPA DELETE 都误报 BlockAttack。现 `JpaUpdateParser` 新增 DELETE 头解析（顶层 WHERE 白名单解析、列引用剥别名；解析失败维持空透传不抛异常，真无 WHERE 时警示照常触发）。同构普查另修两族：①警示通道不对称——JPA 链无 WHERE 的 bulk UPDATE 此前静默不警示，EasyQuery / MDS / Querydsl 的 update 分支同样缺位，统一补齐（含 EasyQuery `updatable(entity)` 无主键时 notes 失实修正）；②语句头 / 字面量假设类缺陷四处——实体名替换无词边界与字面量保护（`OwnerInfo` 误命中 `OwnerInfoHelper` 前缀成 `unknown_tableHelper`、`like '%UserEntity%'` 被吃成 `'%user%'`、FROM 实体引用扫描吃字面量致驱动错表映射），新增 `JpaSqlLiteralMasking` 按「掩码→替换→还原」统一防护
