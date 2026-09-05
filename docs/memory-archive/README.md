# 项目记忆归档索引

> 本目录存放从本地记忆系统（`~/.zcode/cli/memories/projects/.../memory/`）归档的历史记忆文件。
> 这些内容已完成/已交付/已修复，不再需要每次会话加载，但作为历史记录保留。
>
> **归档时间**：2026-08-22（首次），2026-08-26（第二次补充），2026-09-04（第三次精简）
> **原始位置**：`~/.zcode/cli/memories/projects/mybatis-plus-sql-visualizer-09474abd1ce62ee6/memory/`
> **全局备份**：`~/.zcode/cli/memories/global/`（63 个通用文件）
> **项目记忆**：保留 19 个核心文件 + MEMORY.md 索引

---

## 已修复 Bug（58 个）

| 文件 | 内容摘要 |
|------|----------|
| [project-exists-bugfix.md](bug-fixes/project-exists-bugfix.md) | exists 条件预览链终结点误判 |
| [project-iscrud-entity-resolution-bug.md](bug-fixes/project-iscrud-entity-resolution-bug.md) | IService/BaseMapper 实体解析后缀硬约束（v2026.8.19 已修复） |
| [project-iscrud-this-and-chain-qualifier-bug.md](bug-fixes/project-iscrud-this-and-chain-qualifier-bug.md) | 隐式 this 与链式调用预览失败 |
| [project-reverse-placeholder-bugfix.md](bug-fixes/project-reverse-placeholder-bugfix.md) | SQL→Java 反向 `?` 占位符识别缺陷 |
| [project-reverse-sql-java-improvements.md](bug-fixes/project-reverse-sql-java-improvements.md) | SQL→Java 反向链路质量大修（4d174c2 + 017d874） |
| [project-reverse-join-fix.md](bug-fixes/project-reverse-join-fix.md) | SQL→Java 反向联表 JOIN 转换修复 |
| [project-reverse-invocation-has-mpj-interface-bug.md](bug-fixes/project-reverse-invocation-has-mpj-interface-bug.md) | invocationHasMpj 漏判接口继承 MPJBaseService |
| [project-reverse-entity-scan-performance.md](bug-fixes/project-reverse-entity-scan-performance.md) | SQL→Java 无调用类性能问题（P0 反查缓存 + P1 启发式 + P2 扫描优化） |
| [project-jpa-hibernate-fix-20260820.md](bug-fixes/project-jpa-hibernate-fix-20260820.md) | JPA/Hibernate 识别缺陷三波并行修复（0bc39d8） |
| [project-jpa-test-hardening-20260821.md](bug-fixes/project-jpa-test-hardening-20260821.md) | JPA 测试体系三层加固 |
| [project-hibernate-jpa-support.md](bug-fixes/project-hibernate-jpa-support.md) | Hibernate/JPA 支持已交付（v2026.8.20） |
| [project-release-jpa-deferred.md](bug-fixes/project-release-jpa-deferred.md) | JPA/Hibernate 随 v2026.8.21 发行 |
| [project-sql-capture-no-project-required.md](bug-fixes/project-sql-capture-no-project-required.md) | SQL 监听手动启动无需等待项目（T-13，v2026.8.20） |
| [project-p051-import-dialog.md](bug-fixes/project-p051-import-dialog.md) | 参数导入增强（P-05.1，v2026.8.20） |
| [project-log-capture-lifecycle-design.md](bug-fixes/project-log-capture-lifecycle-design.md) | 日志捕获生命周期设计 |
| [project-sql-log-filterbar.md](bug-fixes/project-sql-log-filterbar.md) | SQL 日志面板过滤条 |
| [project-screenshot-placeholders.md](bug-fixes/project-screenshot-placeholders.md) | 功能截图占位已就位（7ca3f05） |
| [project-m51-api-cleanup-gaps.md](bug-fixes/project-m51-api-cleanup-gaps.md) | M51 清理后剩余 gap |
| [project-20260820-dev-plan-execution.md](bug-fixes/project-20260820-dev-plan-execution.md) | 2026-08-20 开发计划执行记录 |
| [project-test-speedup.md](bug-fixes/project-test-speedup.md) | 测试提速实测（clean test 带缓存 1m03s 最优） |
| [project-update-channel-beta-refactor.md](bug-fixes/project-update-channel-beta-refactor.md) | 更新机制改为 Gitee 抢先体验通道 |
| [project-architecture-docs.md](bug-fixes/project-architecture-docs.md) | docs/架构设计/ 三篇系列文档指针 |
| [m30-m33-features.md](bug-fixes/m30-m33-features.md) | M30-M33 全量开发完成（2026-08-23）：11 功能模块 |
| [m41-toolbar-i18n-cleanup.md](bug-fixes/m41-toolbar-i18n-cleanup.md) | M41 工具栏国际化修复 + 布局分离（2026-08-23） |
| [project-f42-join-diagram-batik.md](bug-fixes/project-f42-join-diagram-batik.md) | F-42 JOIN 图渲染修复完成（2026-08-25）：PlantUML→自绘 Swing |
| [project-f43-auto-save-sql.md](bug-fixes/project-f43-auto-save-sql.md) | F-43 自动保存历史 SQL（2026-08-25） |
| [project-f44-sql-log-merge-sidebar.md](bug-fixes/project-f44-sql-log-merge-sidebar.md) | F-44 SQL 日志合并主侧边栏 + 源码定位（2026-08-25） |
| [project-ide-freeze-20260824.md](bug-fixes/project-ide-freeze-20260824.md) | IDEA 2026.2 卡顿根因：SqlLogPanel O(N²) 事件风暴（M46 已修复） |
| [project-m28-update-channel.md](bug-fixes/project-m28-update-channel.md) | M28 内部更新机制 Gitee 抢先体验通道改造（2026-08-22） |
| [project-m47-jdbc-template-log.md](bug-fixes/project-m47-jdbc-template-log.md) | M47 Spring JdbcTemplate SQL 日志支持（2026-08-24） |
| [project-pages-xml-preview.md](bug-fixes/project-pages-xml-preview.md) | F-45 低代码框架 XML HQL 预览（2026-08-26） |
| [project-plantuml-render-error.md](bug-fixes/project-plantuml-render-error.md) | PlantUML JOIN 图渲染修复链（2026-08-23~25） |
| [project-plugin-xml-kotlin-optional.md](bug-fixes/project-plugin-xml-kotlin-optional.md) | plugin.xml Kotlin 语言 ID 注册修复（2026-08-25） |
| [project-settings-layout-optimization.md](bug-fixes/project-settings-layout-optimization.md) | 设置页布局优化 Phase 1-3（2026-08-26） |
| [project-settings-no-nested-configurable.md](bug-fixes/project-settings-no-nested-configurable.md) | 设置页不再注册 projectConfigurable（2026-08-26） |
| [toolwindow-button-tooltip-mechanism.md](bug-fixes/toolwindow-button-tooltip-mechanism.md) | F-41 右侧面板按钮 tooltip 机制修复（2026-08-25） |
| [m30-m33-features](bug-fixes/m30-m33-features.md) | M30-M33 全量开发已完成（2026-08-23 合并）——11 功能模块：SQL 诊断/复杂度评分/JOIN |
| [m41-toolbar-i18n-cleanup](bug-fixes/m41-toolbar-i18n-cleanup.md) | M41 完成——SQL 预览工具栏按钮国际化修复、性能警告栏布局分离、Copy Executable SQL 按钮移除 |
| [project-20260820-dev-plan-execution](bug-fixes/project-20260820-dev-plan-execution.md) | 20260820 全量优化与新功能开发计划——11 项立项、波次并行开发执行记录 |
| [project-architecture-docs](bug-fixes/project-architecture-docs.md) | 新增 docs/架构设计/ 三篇系列文档（Mermaid 架构图+流程图），面向新上手开发者与 AI 会话速览 |
| [project-exists-bugfix](bug-fixes/project-exists-bugfix.md) | 用户实测 SQL 预览缺 exists 条件——根因是 WrapperLocator 链终结点误判（exists |
| [project-f42-join-diagram-batik](bug-fixes/project-f42-join-diagram-batik.md) | F-42 JOIN图渲染修复完成（2026-08-25，commit 17bb741）——PlantUML 8059→1.2024.8（Smetana 引擎）；F-xx（2026-08-26，commit c58fc2b）彻底移除 PlantUML + Batik，改用自绘 Swing Graphics2D 引擎，插件体积 42MB→5.3MB |
| [project-f43-auto-save-sql](bug-fixes/project-f43-auto-save-sql.md) | F-43 已完成（2026-08-25）——自动保存历史 SQL：onEvicted 回调 + |
| [project-f44-reliable-jump-and-test-coverage](bug-fixes/project-f44-reliable-jump-and-test-coverage.md) | F-44 实时日志可靠跳转源码方案（InnerInterceptor 标记行）+ 全插件 UI/集成测试覆盖（50+ 文件 200+ 用例，v2026.9.2 发行） |
| [project-f44-sql-log-merge-sidebar](bug-fixes/project-f44-sql-log-merge-sidebar.md) | F-44 已完成（2026-08-25）——SQL 日志合并到主侧边栏 + 源码定位 + 历史读取 |
| [project-hibernate-jpa-support](bug-fixes/project-hibernate-jpa-support.md) | Hibernate/JPA 全面支持已交付（2026-08-20）——4 阶段开发，已合并 master，1682 测试全绿 |
| [project-hql-association-xml-mapping](bug-fixes/project-hql-association-xml-mapping.md) | HQL 关联字段路径解析 + Hibernate XML 映射支持（feature/f45 分支），含 entity resolution 三级降级链 |
| [project-hql-variable-resolution](bug-fixes/project-hql-variable-resolution.md) | HQL 变量引用拼接初始化式解析的两次缺口：HqlQueryLocator |
| [project-ide-freeze-20260824](bug-fixes/project-ide-freeze-20260824.md) | IDEA 2026.2 卡顿根因分析（2026-08-24）——SqlLogPanel 列表刷新导致 20~30 秒 EDT |
| [project-iscrud-entity-resolution-bug](bug-fixes/project-iscrud-entity-resolution-bug.md) | IService/BaseMapper 内置 CRUD 方法（getById 等）预览失败——实体类无 Entity 后缀时 |
| [project-iscrud-this-and-chain-qualifier-bug](bug-fixes/project-iscrud-this-and-chain-qualifier-bug.md) | IService/BaseMapper 内置方法隐式 this 调用和链式调用预览失败——已修复（2026-08-20） |
| [project-jdbctemplate-sql-string-preview](bug-fixes/project-jdbctemplate-sql-string-preview.md) | JdbcTemplate/NamedParameterJdbcTemplate SQL 字符串正向预览（2026-09-03 已完成，feature/jdbc-template-sql-preview 分支）——命名参数 :name 按名回填 + StringBuilder 分片聚合 + 独立 psource 包 |
| [project-jpa-hibernate-fix-20260820](bug-fixes/project-jpa-hibernate-fix-20260820.md) | JPA/Hibernate 识别缺陷三波并行修复已完成并合并到 master |
| [project-jpa-test-hardening-20260821](bug-fixes/project-jpa-test-hardening-20260821.md) | JPA 测试体系三层加固（2026-08-21）——组件契约升级+端到端集成+面板行为测试，防回归 |
| [project-log-capture-lifecycle-design](bug-fixes/project-log-capture-lifecycle-design.md) | SQL 日志自动监听生命周期设计——Project 级 capturing 状态位、多模块/多项目行为、双保险触发机制、100ms 快速轮询、lastConsoleIds 移除检测 |
| [project-m28-update-channel](bug-fixes/project-m28-update-channel.md) | M28 内部更新机制已改造为 Gitee 抢先体验通道（2026-08-22）——双 JSON 文件、忽略此版本、本地测试工具 |
| [project-m36-direct-execute](bug-fixes/project-m36-direct-execute.md) | M36 SQL预览直接执行（2026-08-27~29）——M36.4/M36.5 原生控制台内嵌成功（共享复用+UiDataProvider+runWhenSmart）；M36.5.14~21d 终局解决"执行内容滞后一轮"（执行链切原生两步 findScriptModel→doRunQueryInConsole + TransactionGuard write-safe 事务） |
| [project-m365-console-embed-plan-a](bug-fixes/project-m365-console-embed-plan-a.md) | 方案A（原生控制台整控台嵌入）2026-08-30 落地——JdbcConsole 无 getComponent、持久整控台强制换底、A3 rehost 去抖防死循环、lag-一轮/8s误报修复、File Cache Conflict 三方同步、沙盒验证判据 |
| [project-m37-m38-competitive-flex-absorption](bug-fixes/project-m37-m38-competitive-flex-absorption.md) | M37 竞品精华融合 + M38 Flex 精华吸收均已实施完成(2026-09-02,分支 |
| [project-m47-jdbc-template-log](bug-fixes/project-m47-jdbc-template-log.md) | M47 已完成——Spring JdbcTemplate SQL 日志支持（2026-08-24），含标准 JdbcTemplate + NamedParameterJdbcTemplate 两种格式，修复 LogToSqlPanel 遗漏 |
| [project-m51-api-cleanup-gaps](bug-fixes/project-m51-api-cleanup-gaps.md) | T-M51 废弃API清理已实施完成（2026-08-19），双版本 0 Internal API，0 Scheduled-removal |
| [project-marketplace-publishing](bug-fixes/project-marketplace-publishing.md) | Marketplace 发布流程文档（双仓库方案/签名现状/审核 4 步/自更新联动），首次发布前必读 |
| [project-o4-diff-native](bug-fixes/project-o4-diff-native.md) | O-4 差异对比改用 IDEA 原生 Diff（DiffManager.showDiff），移除自研行内高亮与 SqlDiffDialog |
| [project-p05-dialect-dbx-envs](bug-fixes/project-p05-dialect-dbx-envs.md) | P-立项-05 方言真机验证已完成（2026-08-29，DBX MCP 通道七家族零缺陷）；DBX 测试环境清单 + 「渲染导出→MCP 执行」工作流与陷阱 |
| [project-p051-import-dialog](bug-fixes/project-p051-import-dialog.md) | P-05.1 参数导入弹窗+驼峰/下划线匹配+JSON 高亮+默认回显当前参数已交付（2026-08-20） |
| [project-pages-xml-preview](bug-fixes/project-pages-xml-preview.md) | F-45 已完成——低代码框架 XML（<pages>/<maintainPage>/<datasource> CDATA HQL）预览支持 |
| [project-plantuml-render-error](bug-fixes/project-plantuml-render-error.md) | PlantUML JOIN 图渲染修复链（2026-08-23~25）——!theme plain 不兼容 8059 已修复；后续发现 linetype ortho 依赖 Graphviz、8059 SVG 输出恒为 Graphviz 错误图（详见 F-42） |
| [project-plugin-xml-kotlin-optional](bug-fixes/project-plugin-xml-kotlin-optional.md) | plugin.xml 校验报错修复——Kotlin 语言 ID 需注册到可选依赖描述文件，supportsKotlinPluginMode 必须放在 extensions 内 |
| [project-release-jpa-deferred](bug-fixes/project-release-jpa-deferred.md) | JPA/Hibernate 内容已从 v2026.8.20 排除，2026-08-21 随 v2026.8.21 成功发行（预览是核心亮点） |
| [project-reverse-entity-scan-performance](bug-fixes/project-reverse-entity-scan-performance.md) | SQL→Java 无调用类性能问题已修复（P0 反查缓存 + P1 启发式候选 + P2 扫描优化，1111 全绿）——O(N) 全量扫描降 O(1)，估算 100-500 倍 |
| [project-reverse-invocation-has-mpj-interface-bug](bug-fixes/project-reverse-invocation-has-mpj-interface-bug.md) | SQL→Java 反向 invocationHasMpj 漏判接口继承 MPJBaseService（BikeDeviceInfoService 实证）——getInterfaces() 对 jar-loaded 泛型超类型返回空数组，改用 superTypes+resolve() 回退 |
| [project-reverse-join-fix](bug-fixes/project-reverse-join-fix.md) | SQL→Java 反向联表（JOIN）转换修复——反引号列/换行转义/lambda 联表容忍逻辑删除/selectAs 引用 VO/副表列归属/未映射误报消除，1218 全绿 |
| [project-reverse-placeholder-bugfix](bug-fixes/project-reverse-placeholder-bugfix.md) | SQL→Java 反向 `?` 占位符识别缺陷（round-trip 断点）——已交付（2026-08-16），实施细节并入 project-reverse-sql-java-improvements |
| [project-reverse-sql-java-improvements](bug-fixes/project-reverse-sql-java-improvements.md) | SQL→Java 反向链路质量大修已提交（4d174c2 + 017d874，23+10 文件）——? 占位符/实体推断/调用形态 MP·MPJ 1.5.6 实证/继承链 MPJ 能力/性能修复/缓存管理/交互优化，1111 全绿 |
| [project-sandbox-jar-file-lock](bug-fixes/project-sandbox-jar-file-lock.md) | runIde 沙盒插件 jar 被 Windows 内存映射锁定 → prepareSandbox 静默不刷新 → |
| [project-screenshot-placeholders](bug-fixes/project-screenshot-placeholders.md) | 功能截图占位方案（7ca3f05）——docs/images/screenshot-01~04 |
| [project-settings-layout-optimization](bug-fixes/project-settings-layout-optimization.md) | 设置页布局优化（Phase 1-3 已完成）——折叠面板 + SQL 日志 Tab 拆分 + 搜索过滤 |
| [project-settings-no-nested-configurable](bug-fixes/project-settings-no-nested-configurable.md) | 设置页不再注册 projectConfigurable——移除嵌套的"MyBatis-Plus SQL |
| [project-settings-tree-nav](bug-fixes/project-settings-tree-nav.md) | 设置页左侧树导航 |
| [project-sql-capture-no-project-required](bug-fixes/project-sql-capture-no-project-required.md) | T-13 SQL 监听手动启动无需等待项目运行——Option A armed 态 + manual/auto 区分 + |
| [project-sql-log-filterbar](bug-fixes/project-sql-log-filterbar.md) | SQL 日志面板筛选条 UI 开发——布局修复/按钮样式演进/设置显隐控制/MessageBus 实时通知 |
| [project-sqltogjava-classpicker-edt-psi-read](bug-fixes/project-sqltogjava-classpicker-edt-psi-read.md) | SQL→Java 类选择器（ClassPickerField）EDT 无 ReadAction 直读 PSI 硬抛——已修复（2026-09-03，提交 34c56ee，dev 分支） |
| [project-test-speedup](bug-fixes/project-test-speedup.md) | 测试提速实测数据与方案（命令对比/并行沙盒坑/IPGP 分片 API）——2026-08-15 研究结论 |
| [project-update-channel-beta-refactor](bug-fixes/project-update-channel-beta-refactor.md) | 插件内部更新机制改为 Gitee 抢先体验通道（2026-08-22）——双 JSON 文件共存 + 忽略此版本 + 本地测试方案 |
| [toolwindow-button-tooltip-mechanism](bug-fixes/toolwindow-button-tooltip-mechanism.md) | 右侧面板按钮 tooltip 机制与 F-41 修复：init() 中 setStripeTitle 确保首次 hover 显示可读名称（非内部 ID） |
| [feedback-bundle-param-placeholder](pitfalls/feedback-bundle-param-placeholder.md) | IntelliJ bundle 消息参数占位符是 {0} {1}，不是 __URL__ 等自定义占位符 |
| [feedback-feature-f45-branch-stale-code](pitfalls/feedback-feature-f45-branch-stale-code.md) | feature/f45 分支有预先存在的编译错误（引用已删除的类），已通过创建存根修复 |
| [feedback-join-diagram-visual-validation](pitfalls/feedback-join-diagram-visual-validation.md) | JOIN 图标签重叠修复必须以真实 IDE 截图验证，单元测试通过不代表视觉布局正确 |
| [feedback-m36-native-console-misalignment](pitfalls/feedback-m36-native-console-misalignment.md) | Database 原生控制台内嵌成败两面——M36.2 八轮 0 尺寸失败（组件甄别铁律），M36.5 系列成功条件（UiDataProvider 三键/共享控制台复用/等 smart 提交/执行内容同步与原生两步执行链/write-safe 事务） |
| [feedback-settings-editor-internal-dep](pitfalls/feedback-settings-editor-internal-dep.md) | SettingsEditor 被 @ApiStatus.Internal 标记——须用 Class.forName |
| [feedback-settings-navigation-new-window](pitfalls/feedback-settings-navigation-new-window.md) | 设置页导航新开窗口 + 定位不准的完整根因与修复——Settings.KEY.getData(手工 DataContext) 取不到 settings.editor，须用 DataManager.getDataContext(component) 或反射；SectionConfigurable 须缓存面板 |
| [feedback-settings-titledborder-pitfall](pitfalls/feedback-settings-titledborder-pitfall.md) | 设置页折叠面板边框样式的已知问题与最终方案（无边框、无折叠、仅 TitledSeparator 横线） |
| [pitfall-criteriaquery-in-clause](pitfalls/pitfall-criteriaquery-in-clause.md) | CriteriaQuery IN 子句必须支持链式调用 .value() 并提供占位符——否则显示空 IN () 无法输入值 |
| [pitfall-cross-platform-filename](pitfalls/pitfall-cross-platform-filename.md) | 跨平台文件名生成陷阱——SimpleDateFormat 需指定 Locale.ENGLISH，Windows 文件名不允许冒号 |
| [pitfall-custom-dao-gutter-dedup](pitfalls/pitfall-custom-dao-gutter-dedup.md) | HQL gutter 图标去重——两个 provider 互相排除 + 导航 handler 直接传 PsiMethodCallExpression |
| [pitfall-dataclass-signature-clean-rebuild](pitfalls/pitfall-dataclass-signature-clean-rebuild.md) | Kotlin data class 增减字段后旧测试类触发 NoSuchMethodError——必须 clean 重建 |
| [pitfall-dbn-output-console-layout-collapse](pitfalls/pitfall-dbn-output-console-layout-collapse.md) | DBN Output 控制台 44px 塌缩——平台 EditorMergedHorizontalScrollBarLayout"输入编辑器优先"策略是根因；一次性 setSize 必败、布局替换才是正解；同类名组件多处存在导致搜索错目标（M36.5.60→76 十六轮踩坑全程） |
| [pitfall-dialog-editor-dispose](pitfalls/pitfall-dialog-editor-dispose.md) | DialogWrapper 中使用 IntelliJ Editor 必须 dispose 释放，否则测试报 "Editor hasn't been released" 且生产环境内存泄漏 |
| [pitfall-e2e-testing-gap](pitfalls/pitfall-e2e-testing-gap.md) | 单元测试无法捕获 IDE 集成 bug——必须编写端到端测试（PreviewEndToEndTest）模拟完整预览链路 |
| [pitfall-f42-join-diagram-render](pitfalls/pitfall-f42-join-diagram-render.md) | JOIN 图渲染"始终渲染中/错误图"终极修复（F-42）——PlantUML 8059 硬依赖 Graphviz + 模态弹窗阻塞 invokeLater + hidden 标签页失效，三层根因叠加 |
| [pitfall-filter-wrong-console](pitfalls/pitfall-filter-wrong-console.md) | 一条 SQL 显示两条——attachFilter 同时挂到 ConsoleViewImpl 和 HttpSingleResponseConsole，processTerminated 误触发致 filter 重复挂载 |
| [pitfall-gitee-hotlink-302](pitfalls/pitfall-gitee-hotlink-302.md) | Gitee raw 图片外链被 JetBrains Marketplace 302 防盗链拦截——plugin.xml 描述图全部不可见，推荐 jsDelivr CDN 替代 |
| [pitfall-gutter-provider-receiver-not-checked](pitfalls/pitfall-gutter-provider-receiver-not-checked.md) | gutter LineMarkerProvider 只按方法名判中、不校验接收者类型 → 通用方法名跨 provider 双收/漏收，产生重复或缺失预览图标 |
| [pitfall-hibernate-parser-strict-idx](pitfalls/pitfall-hibernate-parser-strict-idx.md) | HibernateLogParser.extractAfterKeyword 严格模式下 idx 偏移量翻倍 bug（2026-08-21） |
| [pitfall-hql-converter-structure](pitfalls/pitfall-hql-converter-structure.md) | HqlConverter 必须保留原始 HQL 结构——不能用碎片化正则替换，否则 SELECT 列/JOIN/WHERE 全部丢失 |
| [pitfall-hql-jpa-preview-debugging](pitfalls/pitfall-hql-jpa-preview-debugging.md) | HQL/JPA SQL 预览调试踩坑记录——单元测试无法覆盖的集成问题 |
| [pitfall-hql-locate-precision](pitfalls/pitfall-hql-locate-precision.md) | HqlQueryLocator.locate() 必须返回包含 anchor 的 createQuery |
| [pitfall-hql-log-multiline-parse](pitfalls/pitfall-hql-log-multiline-parse.md) | HQL 日志 multiline 解析全链路改造——状态机设计 + 双框架并发干扰 + Hibernate 前缀误收 bug |
| [pitfall-hql-param-extraction](pitfalls/pitfall-hql-param-extraction.md) | HQL 参数名提取——链式调用无变量赋值时，须直接从 HQL 字符串提取参数名 |
| [pitfall-hql-query-locator-compilation](pitfalls/pitfall-hql-query-locator-compilation.md) | HqlQueryLocator.kt 预存编译错误——PsiTreeUtil.collectElementsOfType 返回 Collection 非 List，传 List 参数须 .toList()（2026-08-21 修复） |
| [pitfall-in-clause-comma-split](pitfalls/pitfall-in-clause-comma-split.md) | IN 子句参数回填三层修复——括号补全+inList标记+引号类型判定（JPQL/XML/MP/MPJ 全路径） |
| [pitfall-jpa-converter-delete-and-params](pitfalls/pitfall-jpa-converter-delete-and-params.md) | JPQL 转换器的三个陷阱——①DELETE JPQL 被当 SELECT 处理 ②native SQL 命名参数不转换 ③预览缺 |
| [pitfall-jpa-gutter-anchor](pitfalls/pitfall-jpa-gutter-anchor.md) | JPA gutter 图标的两个锚点陷阱——①`element as? PsiMethod` 导致永远不显示 ②每个叶子都创建 marker 导致重复图标 |
| [pitfall-json-format-quotes](pitfalls/pitfall-json-format-quotes.md) | JSON 格式化时参数值必须统一加引号，不能做数值/布尔类型推断，否则数值引号丢失 |
| [pitfall-log-startup-capture-regression](pitfalls/pitfall-log-startup-capture-regression.md) | 启动自动监听回归——最终根因 Class.forName 使用插件 classloader 而非平台 classloader；修复=显式传入 platformClassLoader |
| [pitfall-model-field](pitfalls/pitfall-model-field.md) | 踩坑：测试引用错误字段名（智能体未编译验证的典型症状） |
| [pitfall-mpj-factory-duplicate-gutter-icon](pitfalls/pitfall-mpj-factory-duplicate-gutter-icon.md) | JoinWrappers.lambda() 工厂变量赋值形态双 gutter 图标——根因是场景 D 批量 selectAll 未去重（initializerHeadIsWrapper 回溯停在 qualifier 引用），已修复（2026-08-21） |
| [pitfall-plugin-update-jar-path-windows](pitfalls/pitfall-plugin-update-jar-path-windows.md) | 插件自更新在 Windows |
| [pitfall-psource-chained-append-ordering](pitfalls/pitfall-psource-chained-append-ordering.md) | SqlStringConcatenator 链式 append 多层共享同一 PSI startOffset，排序须用 textLength 作次键 |
| [pitfall-reverse-subquery-join](pitfalls/pitfall-reverse-subquery-join.md) | SQL→Java 反向解析器子查询 JOIN / FROM 子查询 / ext 条件 lambda / MConsumer 子查询形态全链路修复（2026-08-21） |
| [pitfall-selectcollection-preview-sql-bug](pitfalls/pitfall-selectcollection-preview-sql-bug.md) | selectCollection/selectAssociation 类参必须解析为 EntityRefArg（PSI 层），否则子查询表名推断错误/重复 JOIN/ON 别名不一致（2026-08-21 已修复，commit 68992ce） |
| [pitfall-settings-persist-inplace](pitfalls/pitfall-settings-persist-inplace.md) | PluginSettingsPanel.apply() 原地修改 state 属性导致 IntelliJ XmlSerializer 不触发 XML 持久化 |
| [pitfall-sidebar-merge-confusion](pitfalls/pitfall-sidebar-merge-confusion.md) | F-44 实施时搞反了侧边栏去除对象——应保留主侧边栏（log），去除独立历史侧边栏（history） |
| [pitfall-source-marker-path-resolution](pitfalls/pitfall-source-marker-path-resolution.md) | SqlSourceInterceptor 标记路径相对于源码根目录，resolveEntryJump 需在所有 sourceRoots 中搜索而非仅用 basePath |
| [pitfall-sql-formatter-clause-line](pitfalls/pitfall-sql-formatter-clause-line.md) | SqlFormatter.formatClauseLine() 实现中的 5 个迭代陷阱——AND/OR 双空格、子查询 depth |
| [pitfall-sqlformatter-atlinestart-space](pitfalls/pitfall-sqlformatter-atlinestart-space.md) | SQL 格式化器空格丢失双 bug——atLineStart 缺失 + formatClauseLine pendingComma/pendingSpace 未设置 |
| [pitfall-sqlformatter-namedparam-colon-space](pitfalls/pitfall-sqlformatter-namedparam-colon-space.md) | SqlFormatter 会把命名参数冒号当运算符空格化为“: name”，按名回填正则须容忍冒号后可选空白 [:]\s* |
| [pitfall-sqllogpanel-edt-freeze-html-render](pitfalls/pitfall-sqllogpanel-edt-freeze-html-render.md) | SqlLogPanel.refreshList 在 EDT 同步 addAll 大量日志触发 JLabel HTML 解析 + 全量 |
| [pitfall-startcapture-guard-bare-return](pitfalls/pitfall-startcapture-guard-bare-return.md) | 自动捕获守卫不能裸 return——跳过捕捉但不挂载检测机制，主控制台出现时永远检测不到（图标不刷新闭环断裂） |
| [pitfall-stopcapture-filter-leak](pitfalls/pitfall-stopcapture-filter-leak.md) | SQL 监听 stop 后仍持续捕获的完整根因与三层防御修复（2026-08-22） |
| [pitfall-test-coverage-gaps-jpa](pitfalls/pitfall-test-coverage-gaps-jpa.md) | JPA 功能测试覆盖率不足的三个典型 gap——①只验证"有没有"不验证"多不多/对不对" ②无负面/互斥测试 ③子智能体自写自测盲区 |
| [pitfall-tm51-api-cleanup](pitfalls/pitfall-tm51-api-cleanup.md) | T-M51 废弃API清理——Marketplace Internal API 阻断的解决思路（Kotlin→Java 改写 / |
| [pitfall-tm52-deprecated-api-replacements](pitfalls/pitfall-tm52-deprecated-api-replacements.md) | T-M52 废弃API替代方案全量实施——三版本字节码实证与跨版本陷阱 |
| [sandbox-deployment-pitfalls](pitfalls/sandbox-deployment-pitfalls.md) | 沙盒 IDE 构建部署陷阱（2026-08-25）—— prepareSandbox 文件锁失败、build/libs 陈旧 jar、手动复制注意事项 |
| [javap-platform-ui-debugging](methodology/javap-platform-ui-debugging.md) | 反编译平台 jar 定位 IntelliJ 平台私有布局/Swing UI 异常的完整流水线——一次 javap 顶十轮组件树探针；含沙盒 jar 定位、进程识别、部署与 stamp 校验 |
| [user-grok-api](user-context/user-grok-api.md) | 用户的 grok API 中转服务（api.openachieve.asia）——20 个模型清单、9 |
| [project-dev-merged-to-master-20260903](bug-fixes/project-dev-merged-to-master-20260903.md) | dev 分支合入 master（ecc7361，2026-09-03）——T-CallerPicker + |
| [project-dev-stale-test-failures-20260903](bug-fixes/project-dev-stale-test-failures-20260903.md) | dev 分支 3 |
| [project-help-browser-doc](bug-fixes/project-help-browser-doc.md) | 帮助按钮 → 本地 HTML 主路径零网络依赖（M36.5.80 定稿）；JCEF 不可用降级系统浏览器打开在线文档 |
| [project-hql-select-alias-expand](bug-fixes/project-hql-select-alias-expand.md) | M36.7 HQL SELECT 实体别名展开为列清单——修复 MySQL [1054] Unknown column 'u'（v2026.9.2）；2026-09-04 发现无别名裸 from 不展开缺口，待实施 |
| [project-log-filter-simplify](bug-fixes/project-log-filter-simplify.md) | SQL 日志筛选项精简（2026-08-27）——SQL类型多选按钮+弹窗、去重 ⨉N 徽章、Ctrl+F |
| [project-m36-native-finish](bug-fixes/project-m36-native-finish.md) | M36.6 原生控制台收尾（2026-08-30）——布局漂移根因闭环（分隔保险条件永不触发→首触无条件 60%）+ ▶灰探针假阴性第 |
| [project-native-sql-highlighting](bug-fixes/project-native-sql-highlighting.md) | 预览面板原生 SQL 高亮 + 补全（M34）——完整根因链：no PSI file（in-memory 不追踪）/ EDT SlowOperations（SqlLanguageSubstitutor）/ language=SQL 注册（全语言不被调度） |
| [project-preview-shortcut-bridge](bug-fixes/project-preview-shortcut-bridge.md) | T-PreviewShortcut 非 Wrapper 预览快捷键贯通（convertToSql 兜底链加 |
| [project-settings-layout](bug-fixes/project-settings-layout.md) | 设置页布局优化 |

## 已解决踩坑（49 个）

| 文件 | 内容摘要 |
|------|----------|
| [pitfall-criteriaquery-in-clause.md](pitfalls/pitfall-criteriaquery-in-clause.md) | CriteriaQuery IN 子句参数替换 |
| [pitfall-custom-dao-gutter-dedup.md](pitfalls/pitfall-custom-dao-gutter-dedup.md) | 自定义 DAO 方法 gutter 去重 |
| [pitfall-dataclass-signature-clean-rebuild.md](pitfalls/pitfall-dataclass-signature-clean-rebuild.md) | Data class clean rebuild 签名问题 |
| [pitfall-dialog-editor-dispose.md](pitfalls/pitfall-dialog-editor-dispose.md) | 对话框编辑器释放问题 |
| [pitfall-e2e-testing-gap.md](pitfalls/pitfall-e2e-testing-gap.md) | 端到端测试覆盖缺口 |
| [pitfall-filter-wrong-console.md](pitfalls/pitfall-filter-wrong-console.md) | 一条 SQL 显示两条 + 过滤条误触发 |
| [pitfall-gitee-hotlink-302.md](pitfalls/pitfall-gitee-hotlink-302.md) | Gitee 图片外链 302 重定向 |
| [pitfall-hibernate-parser-strict-idx.md](pitfalls/pitfall-hibernate-parser-strict-idx.md) | Hibernate 解析器严格索引越界 |
| [pitfall-hql-converter-structure.md](pitfalls/pitfall-hql-converter-structure.md) | HQL 转换器结构缺陷 |
| [pitfall-hql-jpa-preview-debugging.md](pitfalls/pitfall-hql-jpa-preview-debugging.md) | HQL/JPA 预览调试踩坑（10+ Bug） |
| [pitfall-hql-locate-precision.md](pitfalls/pitfall-hql-locate-precision.md) | HQL 定位精度问题 |
| [pitfall-hql-log-multiline-parse.md](pitfalls/pitfall-hql-log-multiline-parse.md) | HQL 日志多行 SQL 解析 |
| [pitfall-hql-param-extraction.md](pitfalls/pitfall-hql-param-extraction.md) | HQL 参数提取缺陷 |
| [pitfall-hql-query-locator-compilation.md](pitfalls/pitfall-hql-query-locator-compilation.md) | HQL QueryLocator 编译问题 |
| [pitfall-in-clause-comma-split.md](pitfalls/pitfall-in-clause-comma-split.md) | IN 子句参数回填三层修复（JPQL/XML/MP/MPJ） |
| [pitfall-jpa-converter-delete-and-params.md](pitfalls/pitfall-jpa-converter-delete-and-params.md) | JPA 转换器删除与参数处理 |
| [pitfall-jpa-gutter-anchor.md](pitfalls/pitfall-jpa-gutter-anchor.md) | JPA LineMarker 锚点必须是叶子元素 |
| [pitfall-json-format-quotes.md](pitfalls/pitfall-json-format-quotes.md) | JSON 格式化引号处理 |
| [pitfall-log-startup-capture-regression.md](pitfalls/pitfall-log-startup-capture-regression.md) | 日志启动捕获回归 |
| [pitfall-model-field.md](pitfalls/pitfall-model-field.md) | 测试引用错误字段名 |
| [pitfall-mpj-factory-duplicate-gutter-icon.md](pitfalls/pitfall-mpj-factory-duplicate-gutter-icon.md) | JoinWrappers.lambda() 工厂双图标 |
| [pitfall-plugin-update-jar-path-windows.md](pitfalls/pitfall-plugin-update-jar-path-windows.md) | 插件自更新 Windows 静默失败三断点 |
| [pitfall-reverse-subquery-join.md](pitfalls/pitfall-reverse-subquery-join.md) | 子查询 JOIN 反向解析修复 |
| [pitfall-selectcollection-preview-sql-bug.md](pitfalls/pitfall-selectcollection-preview-sql-bug.md) | selectCollection 预览 SQL 三缺陷 |
| [pitfall-settings-persist-inplace.md](pitfalls/pitfall-settings-persist-inplace.md) | SettingsPanel.apply() 原地修改不持久化 |
| [pitfall-sql-formatter-clause-line.md](pitfalls/pitfall-sql-formatter-clause-line.md) | SQL 格式化关键字换行 |
| [pitfall-startcapture-guard-bare-return.md](pitfalls/pitfall-startcapture-guard-bare-return.md) | SQL 监听全链路陷阱（11 轮迭代） |
| [pitfall-stopcapture-filter-leak.md](pitfalls/pitfall-stopcapture-filter-leak.md) | SQL 监听 stop 后 Filter 未移除 |
| [pitfall-test-coverage-gaps-jpa.md](pitfalls/pitfall-test-coverage-gaps-jpa.md) | JPA 测试覆盖缺口 |
| [pitfall-tm51-api-cleanup.md](pitfalls/pitfall-tm51-api-cleanup.md) | Marketplace Internal API 清理 |
| [pitfall-tm52-deprecated-api-replacements.md](pitfalls/pitfall-tm52-deprecated-api-replacements.md) | 废弃 API 替代方案全量实施 |
| [pitfall-cross-platform-filename.md](pitfalls/pitfall-cross-platform-filename.md) | 跨平台文件名生成陷阱（SimpleDateFormat Locale.ENGLISH + Windows 禁冒号） |
| [pitfall-sidebar-merge-confusion.md](pitfalls/pitfall-sidebar-merge-confusion.md) | F-44 侧边栏去除对象搞反（保留主侧边栏 log，去除独立 history） |
| [sandbox-deployment-pitfalls.md](pitfalls/sandbox-deployment-pitfalls.md) | 沙盒 IDE 构建部署陷阱（prepareSandbox 文件锁 + 陈旧 jar） |
| [pitfall-f42-join-diagram-render.md](pitfalls/pitfall-f42-join-diagram-render.md) | JOIN 图"渲染中/错误图"终极修复（F-42）——PlantUML 8059 硬依赖 Graphviz + 模态弹窗阻塞 invokeLater + hidden 标签页，三层根因（17bb741） |
| [pitfall-dbn-output-console-layout-collapse.md](pitfalls/pitfall-dbn-output-console-layout-collapse.md) | DBN Output 控制台 44px 塌缩（M36.5.60→76）——平台 EditorMergedHorizontalScrollBarLayout"输入编辑器优先"策略；setSize 对抗布局必败、布局替换正解、同名类组件搜索错目标（c54eefc/daf75d1） |

## 方法论（1 个）

| 文件 | 内容摘要 |
|------|----------|
| [javap-platform-ui-debugging.md](methodology/javap-platform-ui-debugging.md) | 反编译平台 jar 定位私有布局/Swing UI 异常——一次 javap 顶十轮探针；含沙盒 jar 定位、沙盒进程精确识别（勿杀主 IDE）、构建产物 stamp 校验 |

## 用户环境（1 个）

| 文件 | 内容摘要 |
|------|----------|
| [user-grok-api.md](user-context/user-grok-api.md) | Grok 中转 API 配置 |

---

## 全局通用记忆（63 个）

> 已复制到 `~/.zcode/cli/memories/global/`，跨项目复用。

**方法论（7 个）**：agent-parallel-test-isolation / agent-task-split / agent-verification / agent-commit-rhythm / wave-parallel-development / methodology-research / methodology-test-first

**用户偏好（9 个）**：user-ide-environment / user-local-proxy / user-token-efficiency / feedback-doc-style-no-ai-flavor / feedback-compilable-generated-code / feedback-batch-param-ux / feedback-icon-button-style / feedback-update-json-manual-only / feedback-grep-first-reading

**平台陷阱（47 个）**：pitfall-actiontoolbar-refresh-api / pitfall-classloader-forname-execution-api / pitfall-contentmanagerlistener-unavailable / pitfall-edt-psi-read-in-callbacks / pitfall-execution-api-missing / pitfall-getJarPathForClass-243-missing / pitfall-gradle-windows / pitfall-headless-validate / pitfall-heavy-psi-interface-resolution / pitfall-ipgp-stripped-ui-classes / pitfall-jblist-copy-itemToText / pitfall-kotlin-incremental / pitfall-kotlin-synthetic-bridge-internal-api / pitfall-lightplatform / pitfall-notification-setlistener-no-2param / pitfall-notificationgroup-displayname / pitfall-panel-disposer-leak / pitfall-parallel-inflight-block / pitfall-plugin-xml-listener-unsupported / pitfall-psi-create-edt-readaccess / pitfall-runide-sandbox-warnings / pitfall-sandbox-corruption / pitfall-script-edit-kotlin-escapes / pitfall-swing-mouse-no-bubble / pitfall-toolwindow-manager-listener / pitfall-uicompatible-dataprovider / pitfall-verifyplugin-proxy / feedback-anaction-icon-update-presentation / feedback-baseplatformtestcase-junit3-runner / feedback-junit3-kotlin-lambda-testname / feedback-popup-chooserbuilder-single-click / feedback-swing-wrap-layout-clipping / pitfall-bash-backtick-inline-corruption / pitfall-editor-colors-scheme-dark-unavailable / pitfall-gradle-cache-locked-by-idea / pitfall-headless-ui-test-gotchas / pitfall-java-code-gen-no-kotlin-ext / pitfall-jcef-css-limitations / pitfall-kotlin-nested-class-prohibited-in-inner / pitfall-native-run-query-chain / pitfall-newbufferedwriter-truncate / pitfall-plugin-xml-configurable-displayname / pitfall-runcatching-debug-log-swallow / pitfall-settings-tree-ampersand-escape / pitfall-slowoperations-edt-readaction / pitfall-verifyplugin-kotlin-mode-optional-dep

---

## 统计

| 位置 | 文件数 |
|------|--------|
| 项目 memory（保留） | 20（含 MEMORY.md） |
| 全局记忆 global/ | 63 |
| 归档 archive/ | 110（bug-fixes 58 + pitfalls 49 + methodology 1 + user-context 1 + README） |
| **合计** | **193** |
