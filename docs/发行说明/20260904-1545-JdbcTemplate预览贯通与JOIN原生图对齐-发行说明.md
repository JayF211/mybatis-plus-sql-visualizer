# 🎉 SQL Visualizer v2026.9.4

> 发行说明留档：对应 Gitee 发行版 **v2026.9.4**（标签 `v2026.9.4`），附件 `mybatis-plus-sql-visualizer-2026.9.4.zip`。

自 v2026.9.2 后的下一个版本（41 个提交）。本版核心主题是 **JdbcTemplate / NamedParameterJdbcTemplate SQL 字符串源码预览**（命名参数按名回填 + 链式 append 聚合，psource 系列）与 **JOIN 图全面对齐 IDEA 原生显示图**（换用 IDE 原生图框架 → 渲染对齐 Database「显示图」→ 深度对齐数据源富化 → B 路径直接内嵌 Database 原生图 + 虚拟外键 JOIN 线）——同时完成非 Wrapper 预览快捷键贯通 + @NamedQuery 实体级预览、自动保存历史 SQL 无限递归修复、SQL 日志列表 EDT 冻结修复、SQL→Java 调用类选择 EDT 硬抛修复、SqlFormatter 关键字分派缺陷修复链，以及 psource / 原生 JOIN 图家族测试加固。

## ✨ 本版新增（相对 v2026.9.2）

**JdbcTemplate / NamedParameterJdbcTemplate SQL 字符串源码预览（psource 系列）**
- **正向入口**：`jdbcTemplate.queryForList("select ...", ...)` / `namedParameterJdbcTemplate.query("select ... :name ...", params)` 等调用，SQL 字符串首参可直接预览——gutter 图标与注解预览同款模式，点击弹窗查看还原 SQL
- **命名参数按名回填**：`NamedParamParser` 提取 `:name` 占位符 + `ParameterReplacerNamed` 按参数名回填（不再按位置猜值），容忍 SqlFormatter 空格化冒号（`: name`）
- **链式 append 分片聚合**：`SqlStringConcatenator` 对 `StringBuilder` / 字符串拼接初始化做分片聚合——链式 append 多层共享同一 PSI startOffset，按文本长度作排序次键，运行时值经 `resolveRootVariable` 根部变量解析
- **独立纯函数包**：`JdbcTemplateMethodDetector`（方法识别）+ `JdbcTemplateSqlLocator`（定位）+ `JdbcTemplateSqlPreviewService.preview`（预览入口）与既有注解预览路径并列复用
- **收尾加固**：DbHelper 自研 DAO delete/insert 识别 + `isLikelySql` 形态门（防通用字符串方法误触发）+ JPA gutter 误判普通类方法修复（接收者校验 Repository 接口）

**JOIN 图换用 IDEA 原生图框架（T-Dgm 系列）**
- `SqlJoinDiagramProvider` 注册 `com.intellij.diagram.Provider` EP（Ultimate 捆绑 Diagrams 插件，类图同款观感）——缩放 / 自动布局 / 节点折叠 / 导出全框架托管
- 快照注册表 + 图构建工厂纯函数适配 `JoinDiagramGenerator` 现有解析产物，原生 JOIN 图渲染不再依赖自绘引擎
- 设置页新增「使用 IDE 原生图框架渲染 JOIN 图」开关（默认开）；Diagrams 插件缺席（Community）或框架异常自动降级回自绘渲染器

**JOIN 图渲染对齐 IDEA Database「显示图」（T-NativeUI）**
- 表头显示「表名 + 表注释（灰色斜体）」，字段行显示「列名 + 类型 + 字段注释」（主键加粗 + 键图标）
- 连接线按 JOIN 类型区分箭头 / 线型 / ON 标签（INNER / LEFT / RIGHT / FULL）
- 工具栏原生分类切换（列 / 主键列 / 注释）

**JOIN 图深度对齐原生显示图（T-Embed）**
- 字段行改原生「列名 /* 注释 */ + 类型右对齐独立列」布局，主键沉底独立分区（对齐 Database 原生）
- 数据源可用时列类型 / 列注释 / 表头注释升级为 DB 真实值（`int(11)` / DB COMMENT——表头注释数据库 COMMENT 优先，数据库没写才回落实体类注释，无数据源静默回落）
- 修复 JOIN 表节点字段行为空的缺陷（转换器原始别名与显示别名双别名匹配）

**JOIN 图 B 路径——直接内嵌 Database 原生「显示图」（T-Embed-03/04）**
- 数据源可用且全部表定位成功时，直接以真实 DbTable 开原生 Database 图（注释 / 类型 / 主键分区 / 工具栏全原生）
- JOIN 连线经 `com.intellij.database.modelRelationProvider` 扩展点以虚拟外键注入（`JoinVirtualFkProvider` 实现，会话级注册表随图释放回收）
- 含子查询或表定位失败自动降级自实现路径；真机二轮修复初始画布空白（开图链缺原生 `fillDataModel` 等价的逐表 addElement）与数据源富化反射链断裂（243 平台无 `getDasObject()`，改 `DasDataSource.getModel()` 直达）

**非 Wrapper 预览快捷键贯通 + @NamedQuery 实体级预览（T-PreviewShortcut）**
- `ConvertToSqlAction` 兜底链新增 `tryHqlFallback`（HQL/JPQL 运行时 + 自定义 DAO HQL）/ `tryNamedQueryFallback` / `tryJdbcTemplateFallback` 短路，接 `previewHql` / `previewCustomDaoHql` / `JdbcTemplateSqlPreviewService.preview`
- `@NamedQuery` 实体级预览：`NamedQueryLocator` 补 `annotation` 字段 + `findByQueryName`（按 `<实体>.<方法>` 前缀 `JavaPsiFacade.findClasses`），`previewNamedQuery` + 对 `createNamedQuery` 按名解析，新增 `NamedQueryGutterLineMarkerProvider`（`@` token 锚点）

## 🔧 修复（相对 v2026.9.2）

**自动保存历史 SQL 文件切换无限递归修复（T-AutoSaverSOE）**
- 文件切换逻辑在保存回调内重建目录列表，同文件反复触发切换死循环致 `StackOverflowError`——保存回调前判定目标文件已存在即终止递归

**SQL 日志列表 EDT 冻结修复（T-LogListFreeze）**
- 大批量写入逐条 `addElement` 事件风暴 + 渲染全量重算——批量抑制（合并事件）+ 渲染缓存（重复条目缓存）+ 分批写入（限宽分批插入）

**SQL→Java 调用类选择 EDT 读 PSI 硬抛修复（T-CallerPicker）**
- 类选择弹窗在 EDT 直读 PSI（qualifiedName / getIcon 等）触发 262 平台 read access 硬抛——候选类解析包 `ReadAction.compute`

**SqlFormatter 关键字分派缺陷链**
- CASE 后字段粘连（「其余关键字」分支缺失 `atLineStart=false`）
- 逗号后关键字缺空格（`pendingComma` 未重置 + `pendingSpace` 未设置）
- 命名参数冒号被空格化为 `: name`（回填正则容忍 `[:]\s*`）

**原生 JOIN 图框架适配真机修复链**
- `Cannot create DiagramBuilder` 两次修复（绕过 `convertFile` 旧格式转换 → `VirtualFileManager.findFileByUrl` 走注册单例 FS）
- 空白画布（createEditor 后须触发 `queryUpdate` 建图）
- 字段列表不显示（分类系统两次尝试失败后回退 `NO_CATEGORIES` / 直接实现 `DiagramNodeContentManager`）与字段文字不显示（框架 `DiagramNodeItemComponent` 调用 3 参 `getItemName` / `getItemType`）

**SQL 预览关键字选择粘连**
- 原生 SQL 高亮器关键字词选择粘连——自研 `SqlSyntaxHighlighter` 替代原生高亮器（关键字按 token 边界分词）

**历史读回剥除 SQL 结束符（T-SqlDuration）**
- `SqlLogFileReader` 历史文件读回时未剥除 SQL 尾部结束符（`;`），耗时解析与回填偏移——读回时剥除结束符，设置页测试断言同步

## 🧪 测试加固

**psource 家族（6 文件 919 行）**
- `JdbcTemplateMethodDetectorTest`（78）/ `JdbcTemplateSqlLocatorTest`（92）/ `JdbcTemplateSqlPreviewServiceTest`（245）/ `NamedParamParserTest`（171）/ `ParameterReplacerNamedTest`（183）/ `SqlStringConcatenatorTest`（150）

**原生 JOIN 图家族（7 文件）**
- `NativeJoinDiagramAdapterTest`（360）/ `NativeJoinDiagramScenariosTest`（461）/ `HeavyNativeJoinDiagramOpenChainTest`（213）/ `HeavyNativeDatabaseDiagramJoinChainTest`（199）/ `JoinDiagramGeneratorTest`（+165）/ `DiagramSchemaEnricherTest`（116）/ `JoinFkRegistryTest`（68）

**其余新增**
- `SqlSyntaxHighlighterTest`（442）/ `LogListFreezeTest`（110）/ `SqlLogAutoSaverTest`（70）/ `NamedQueryLocatorTest`（56）/ `NamedQueryGutterLineMarkerProviderTest`（74）/ `JpaGutterLineMarkerProviderTest`（35）/ `CustomDaoHqlLocatorTest`（+85）

## 🔧 兼容性

- 支持 IntelliJ IDEA **2024.3+**（Ultimate / Community；执行 SQL 与 Database 原生图需 Ultimate）
- JOIN 原生图框架依赖 Ultimate 捆绑 Diagrams 插件，缺席时自动降级回自绘渲染器，功能不丢失

## 📦 安装

1. 下载附件 `mybatis-plus-sql-visualizer-2026.9.4.zip`
2. IDEA：`Settings → Plugins → ⚙️ → Install Plugin from Disk…` → 选择 zip → 重启 IDE
3. 已装旧版：设置页「更新」分区可直接检查更新下载安装（Gitee 渠道），或按上法手动安装覆盖

## 📚 相关文档

- [功能概览](https://gitee.com/songrenshuo/mybatis-plus-sql-visualizer/blob/master/docs/需求文档/20260812-1058-MP-SQL可视化-需求文档.md)
- [开发计划](https://gitee.com/songrenshuo/mybatis-plus-sql-visualizer/blob/master/docs/开发计划/20260812-1058-MP-SQL可视化-开发计划.md)
- [版本号规范](https://gitee.com/songrenshuo/mybatis-plus-sql-visualizer/blob/master/docs/发行说明/版本号规范-说明文档.md)
- [变更记录.md](https://gitee.com/songrenshuo/mybatis-plus-sql-visualizer/blob/master/docs/变更记录.md)（全量变更记录总册）
- [CHANGELOG.md](https://gitee.com/songrenshuo/mybatis-plus-sql-visualizer/blob/master/CHANGELOG.md)（版本化变更记录，全量版本标题保留）
- [架构设计](https://gitee.com/songrenshuo/mybatis-plus-sql-visualizer/blob/master/docs/架构设计/01-项目架构总览.md)
- [README.md](https://gitee.com/songrenshuo/mybatis-plus-sql-visualizer/blob/master/README.md)（联系作者）

> 📌 版本号说明：自 v2026.8.13 起采用 **YYYY.M.D 日期版本号，一日一版**（详见[版本号规范](https://gitee.com/songrenshuo/mybatis-plus-sql-visualizer/blob/master/docs/发行说明/版本号规范-说明文档.md)）

## 🐛 反馈

遇到问题或有建议，欢迎到 [官网需求池](https://sql-visualizer.jvtools.cn/about/feedback) 提交；也可以扫码加作者微信 / 交流群（见 [README.md](https://gitee.com/songrenshuo/mybatis-plus-sql-visualizer/blob/master/README.md) 底部）。
