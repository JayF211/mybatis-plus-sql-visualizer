<!-- Keep a Changelog guide -> https://keepachangelog.com -->

# Mybatis-plus-sql-visualizer Changelog

## [Unreleased]

### Added

- **预览 SQL 与预期 SQL 不一致修复**（M29）：① `.and(condition, w -> ...)` 等**分组方法布尔条件重载**不再被静默丢弃——and/or/nested/not/func 的布尔首参剥离为动态条件开关（默认勾选按标志，`and(false, ...)` 默认不渲染），组内 OR 条件正常渲染（如 `AND (code LIKE ? OR user_phone LIKE ?)`）；② **分页 LIMIT 渲染**——`service.page(new Page<>(...), wrapper)` / `selectPage` / 链式 `page()` 识别为分页消费：字面量直接计算 `LIMIT 0, 10`，运行时变量渲染 `LIMIT ?, ?`（参数区可回填，offset 标注 `(pageNum - 1) * pageSize` 公式），全 9 方言适配
- **设置页检查更新与自动安装更新**（M28）：插件设置页新增「更新」分区——当前版本展示、「检查更新」按钮（已最新/发现新版/失败原因状态反馈）、新版变更日志展示、「下载并安装」一键完成（下载进度条）→ 安装完成后提示「重启 IDE 生效」+ 重启按钮（IDE 原生确认框，可选稍后）；「启动时自动检查更新」开关（默认开）——启动静默检查，发现新版弹通知可「立即更新」后台下载安装；更新源 = 发布仓库 `update/update.json` 清单 + Gitee Release 附件（发布流程新增清单维护步骤，`./gradlew writeUpdateManifest`）
- **表名提示 + SQL 格式标准化**（T-M27.4）：预览弹窗补全表名全位置提示（输入表名前缀即命中，含 @TableName 联表候选）；SQL 渲染自动格式化（列清单换行/主子句顶格/子查询缩进/AND 缩进/空行剔除——XML 动态标签残留空行归一）
- **XML 预览补全增强**（T-M27.3）：XML mapper 预览弹窗输入自动弹出表名/列名补全——预览结果注入实体上下文（namespace→Mapper 接口→主实体），与 Java 注解预览一致（列注释/中文检索）
- **SQL 面板输入自动补全**（T-M27.2）：预览弹窗 SQL 编辑器输入字符自动弹出补全——平台 autopopup 对非文件编辑器弹空窗（contributor 未调度），面板自建：文档监听 + commit 后手动补全处理器（explicit=true）接管
- **SQL 面板补全修复**（T-M27.1）：预览弹窗 SQL 编辑器 Ctrl+Space 补全不触发修复——编辑器组件链补 DataProvider（2025.3 平台编辑器改为 UiCompatibleDataProvider，传统 DataContext 查询拿不到 EDITOR 致补全静默失效）
- **XML/注解 SQL 代码补全**（M27）：mapper XML 语句与 Java SQL 注解字符串（@Select/@Insert/@Update/@Delete）内 Ctrl+Space 补全——
  - SQL 关键字、表名/列名（FROM/JOIN/WHERE 等子句位置感知）、`别名.` 前缀列（联表：LEFT JOIN/逗号多表别名绑定）
  - `#{}` 参数名（@Param 感知）；XML 动态标签（`<if>/<foreach>/<where>` 等）与 test/collection 属性 OGNL 补全
  - 列注释 tailText 展示与中文关键词检索；Community 兼容（方案 B 自研、零数据源依赖、实体注解即 schema）
- **MyBatis 注解 SQL 预览**（M25）：Java Mapper 方法上的 `@Select/@Insert/@Update/@Delete`（含 `<script>` 动态标签）与 `@SelectProvider` 等 Provider 注解（L1 常量折叠）——注解行 gutter 图标 + 右键预览，复用 XML 引擎（动态条件勾选/foreach 次数/bind 覆盖）：
  - 数组元素拼接对齐 MyBatis（`String.join(" ", value).trim()`）；`<script>` 与 XML mapper 同一解析路径
  - `@Param` 参数名 mock（`param1..N` 注入）；Provider 常量折叠（字面量/文本块/`+` 拼接/静态 final 引用），动态生成场景降级提示
  - `@Lang` 自定义语言驱动提示不支持
- **XML 多语句候选**（M25）：光标不在语句内时列出全部语句（行号 + 摘要）供选择预览
- **XML/注解预览实测修复**（T-M25.2~25.5，沙盒真实项目验证）：
  - OGNL 静态常量引用 `@类全名@字段名`（bind 表达式常见写法）——解析用户项目常量类字段值注入预览
  - `||表达式||` 非标准包裹兼容（真实 OGNL 运行时会解析失败，预览宽容解析并提示）
  - OGNL 两套判真语义对齐（test 表达式空串为真 vs `||`/`&&` 操作数空串为假）；`||`/`&&` 返回操作数值
  - 预览弹窗「复制错误信息」按钮（有警示时一键复制完整错误文本）
  - foreach 集合值输入（`2,3` → `IN ( 2 , 3 )` 自动回填，唯一入口）；迭代内容 trim（IN 单行紧凑）
  - bind 自动求值回显参数行（静态常量/LIKE 模板算出的值自动填入，可手动覆盖）
- **预览弹窗左右分栏分隔条修复（T-M26.1）**：深色主题下左右分栏间出现白色竖条纹（平台 LAF 委托 Metal，`MetalSplitPaneDivider` 以 Metal 主题浅色直接填充分隔条，与 `setBackground` 无关，原覆盖无效）——`ThemedSplitPane` 覆写 paint 在 LAF 绘制后把分隔条区域覆盖为面板底色，亮/暗主题下与面板融为一体
- **左面板全选/全不选按钮固定右上角（T-M26.2）**：SQL 预览面板左侧参数列表的全选/全不选按钮原在参数滚动区内（`paramPanel` BoxLayout 把按钮行拉伸到最宽参数行宽度），参数名过长时右对齐按钮被推出左面板视口隐藏，须拉宽左面板才能看到——按钮移出滚动区固定到左面板右上角操作行（右对齐贴分隔栏、拖拽分隔栏自动跟随，不再受内容宽度影响），按场景切换按钮组（动态条件/XML 条件开关 ↔ 批量「参数列」），无参数内容时整行隐藏
- **左侧面板布局对齐 IDEA 原生惯例（T-M26.3）**：SQL 预览面板左栏布局优化（参考 IntelliJ Platform UI Guidelines）——① 分区标题统一 TitledSeparator 分组（「动态条件」「foreach 展开」「bind 变量」「参数」，动态条件区补标题）；② 全选/全不选回挂分区标题行右侧（IDEA 组操作惯例，T-M26.2 的固定右上角拆除——长文本按视口宽度截断后按钮不再被推出视口）；③ 表单两列对齐（参数/bind/foreach 行标签列统一宽度、输入框左缘对齐）；④ 长文本中段省略 + tooltip 全名（bind 常量引用、test 表达式），截断上限跟随视口宽度（默认 240 面板即不再横向溢出）
- **删除 bind 变量分区（T-M26.4）**：XML 预览左栏 bind 变量区与「参数」区重复展示同一组值（bind 求值结果已回显在参数区），双入口改同一个值违反单一来源——删除 bind 分区（面板渲染、覆盖输入框、bundle 文案），bind 求值结果由「参数」区唯一承担（输入覆盖、清空恢复自动值，功能等价）；模型层 `bindOverrides` 能力保留、面板不再接线；边界：bind 变量被 `${}` 文本拼接引用时（无 `?` 可回填）走默认求值
- **foreach 行精简 + LIKE 参数类型修复（T-M26.5）**：① foreach 行布局精简——分区标题改「foreach 集合」，内容行去掉「foreach 集合: 」前缀、直接显示集合表达式（如 `deviceIds [输入框] 为 null`）；② 模糊查询参数类型推断修复——XML 渲染 SQL 的 LIKE 参数（`LIKE ?` / `LIKE CONCAT('%', ?, '%')`）回填恒加引号（String 列如 `deviceCode` 的纯数字输入不再被数字启发式误判，`#keyword="111"` → `LIKE CONCAT('%', '111', '%')`），与 Wrapper 场景 SqlGenerator hints 对齐
- **SQL 面板支持格式化快捷键（T-M26.6）**：预览面板内按 Ctrl+Alt+L 快速格式化 SQL（与「格式化」按钮同逻辑）——跟随 IDE keymap 的 Reformat Code 绑定（用户自定义键位跟随，未绑定兜底 Ctrl+Alt+L）；`registerCustomShortcutSet` 注册到面板整体（焦点在 SQL 编辑器或参数输入框均可触发），组件级 custom shortcut 命中即消费按键、不与全局 Reformat Code 双触发
- **文件级多注解候选（M26-A）**：Wrapper 预览未命中（光标不在 Wrapper 链上）时，Java 文件预览回落到文件内全部 SQL 注解——单个直接预览、多个弹候选对话框（注解行号 + SQL 摘要），非 Mapper 文件同样可用
- **MP 占位 `${ew.customSqlSegment}` 展开（M26-B）**：mapper XML 中的 `${ew.customSqlSegment}`/`${ew.sqlSelect}`/`${ew.sqlSet}` 占位经 namespace 关联 Java Mapper 方法 → 调用处 wrapper 实参 → Wrapper 预览全链路 SQL → 提取对应片段回填：
  - `customSqlSegment` = `WHERE (条件)`（含 WHERE 关键字，对齐 MP `SqlHelper`）；sqlSelect = SELECT 列清单；sqlSet = SET 片段
  - @Param 名匹配优先定位 wrapper 参数，其次按类型；多调用处取第一处
  - 降级链逐级提示：缺 namespace / 找不到接口 / 无同名方法 / 无 Wrapper 参数 / 无调用处（可填覆盖值）

- **MyBatis XML mapper 动态 SQL 预览**（M24）：在 `<select|insert|update|delete>` 语句上点击 gutter 图标或右键预览动态 SQL——引擎忠实复刻 MyBatis 3.5.19 渲染算法：
  - 动态标签全兼容：`if` / `choose(when/otherwise)` / `trim` / `where` / `set` / `foreach`（含 `__frch_` 参数重写、Map 迭代、空集合语义）/ `bind` / `sql`+`include`（同文件 property 静态替换）
  - 受限 OGNL 求值：判空/比较/逻辑/算术/成员方法/列表字面量，表达式无法解析时面板提供手动开关兜底
  - 预览面板：动态条件勾选（启发式默认）、foreach 展开次数与 null、bind 覆盖值、`#{}` 参数回填、SQL 高亮/格式化/执行/复制全部复用
  - 参数对照表显示 `#{}` 属性名（foreach 内为 `__frch_` 重写名）；include 缺失/循环/未解析变量等给出警示提示
  - 边界：跨命名空间 include 暂不支持；`${ew.customSqlSegment}` 等 MP 占位展开见 M26-B（占位求值为空提示）；注解 `@Select` 的 `<script>` 暂不支持

## [2026.8.14] - 2026-08-14

### Added

- **官方 API 覆盖差距清零**（M19.5 ~ M22，MP 3.5.17 / MPJ 1.5.9，对齐官方 API 覆盖矩阵）：
  - 实体单条 CRUD：`save` / `getById` / `listByIds` / `selectByIds` / `deleteByIds`
  - 工厂与链式：`JoinWrappers` / `MPJWrappers` / `ChainWrappers`；服务链赋值形态（实体/boolean/long 三形态）gutter 锚点修复；`service.query()/update()` 字符串链
  - 消费方法全集：`selectCount` / `getObj` / `selectJoinMaps` / `selectJoinPage`；Map 条件消费（`selectByMap` / `listByMap` / `removeByMap` / `deleteByMap`）；BaseMapper 批量族（`insert` / `insertOrUpdate`）；`getOptById` / `removeBatchByIds` / `saveOrUpdate(entity, wrapper)`
  - 逻辑删除精确化：六方法主/副表独立开关 + ON/WHERE 位置；`enableLogicDel` / `logicDelToWhere` / `logicDelToOn` / `disableLogicDel` 四形态
  - 联表扩展：join 4/5 参（rightAlias / 子表 MConsumer / ext MFunction）、`selectCollection` prefix/free 形态、`selectFunc` 枚举映射、`from` 子查询主表、`union` / `unionAll`、`selectSub` 子查询列、**UpdateJoinWrapper / DeleteJoinWrapper 全链路**（多表 UPDATE/DELETE）、on 双字符串列对列比较
  - 条件与语句补全：`allEq` 6 重载、`eqOrIsNull`、`setEntity`、`select(Class, Predicate)` / `selectFilter`、`nested` / `not` / `func`、`inSql` / `notInSql`、`eqSql` ~ `leSql`、`notLikeLeft/Right`、`setIncrBy` / `setDecrBy`、`having`、`comment` / `first` / `setAlias`
- **多数据库方言**（M22）：新增 6 种——SQL Server（OFFSET..FETCH）/ H2 / SQLite（LIMIT..OFFSET）/ MariaDB / DM 达梦 / KingBase 人大金仓（共 9 方言，设置下拉自动同步）；`@Version` 乐观锁注入；Db / SimpleQuery / ActiveRecord 识别
- **SQL 关键字方言化引用**（M23.4 ~ M23.5）：五方言保留字集合，判定与符号按方言（反引号 / 双引号 / 方括号），`table` 等关键字字段自动引用——覆盖 SELECT 全列 / WHERE / 批量模板 / 查询路径解析期拼接；`apply` / 原生字面量维持不处理
- **批量操作增强**（M23 系列，用户实测驱动）：
  - 批量逻辑删除/软删除对齐：`@TableLogic` 批量 SELECT 自动追加 `deleted = 0`；有 `@TableLogic` 时 `removeById` 等删除操作自动转 `UPDATE ... SET deleted = 1` 软删除
  - 批量参数面板：`save(entity)` 每个 `?` 登记参数行、实参字面量内联、按列类型回填引号（数值类型白名单外一律加引号）
  - 参数列级勾选：`save` 自增主键（IdType.AUTO）默认不勾选、`updateById` SET 可按需取消、全选/全不选、`saveOrUpdate` 主键可选（取消 → 纯 INSERT）
  - 字段顺序角色分组：主键置顶、自动填充审计字段沉底（BaseEntity 继承场景）
- **触发与选择体验**（M23.6 ~ M23.8）：
  - 任意位置触发：光标在 `eq` 调用处 / `.list()` 链尾也可触发预览
  - 多 SQL 选择：选择代码块右键 → 自动扫描候选 → 多个时弹选择框（摘要 + 代码行预览 + 定位跳转，IDEA Find 风格）；候选扩展至批量方法调用
  - 复制 / 执行 SQL 成功提示新增「关闭 SQL 面板」一键按钮
- 构建：jar manifest 保留 MPV-Build 内部构建号（每次构建唯一）

### Fixed

- **MPJ 联表主表逻辑删除条件补主表别名（T-M23.10）**：`AND deleted = 0` → `AND t.deleted = 0`——多表 JOIN 下裸列名与副表 deleted 歧义（MySQL ambiguous）；DeleteJoin 多表删除、UpdateJoin + @Version 乐观锁（`SET t.version = t.version + 1` / `AND t.version = ?`）同病同治；单表场景（tableAlias=null）输出逐字节不变；顺带 `inferEntityFromChain` 支持宿主文件内类名→PsiClass 解析——服务链/工厂形态 @TableLogic/@Version 注解生效、SELECT 展开列对齐实体全列（与真实 IDE 一致）
- **gutter 锚点误标修复（T-M23.11/12）**：① `selectAll` 同时收录于批量注册表与 MPJ 链方法 → wrapper 链上调用被误标批量锚点产生双图标——场景 D 加链首回溯守卫（内联/工厂形态）+ 撞名白名单变量判定（分步形态），批量形态（Db.selectAll / updateBatchById）不受影响；② 嵌套条件 lambda 参数（`wrapper.and(o -> ...)`，真实 IDE 推断类型为 LambdaQueryWrapper）被场景 B 误标产生三图标——场景 B 排除 lambda 参数，方法参数锚点保留
- **多 SQL 选择弹窗 EDT 慢操作修复**：候选扫描与弹窗显示文本同批后台 `ReadAction.compute` 预计算，弹窗构造只做纯 UI 渲染（不再触碰 PSI）
- **弹窗关闭按钮文案走插件 bundle**：平台 cancelAction 随 IDE 语言包（中文环境显示英文 Cancel）→ 改走插件界面语言
- **M23.9 打包编译警告清零**：9 处（`Messages.showDialog` 弃用替换、`DaemonCodeAnalyzer.restart` 参数泛化、`TextAttributesKey.createTextAttributesKey` 弃用豁免、冗余 cast ×3、恒真条件 ×1），行为零变化

## [2026.8.13] - 2026-08-13

### Added

- M19.4 参数面板布局修复 + 表名配置增强（282 测试全绿）：
  - **参数面板布局修复（用户实测：长参数名显示不全）**：`buildParamRow` 标签**固定 140px** → **按文本自适应**（FontMetrics 计算宽度，夹在 140~300px，`#productInfo.getDicProtocolType()` 这类长名完整显示）；文本超过 300px 上限时 **tooltip 展示全名**；行间距 `(4, 2)` → `(8, 6)`（视觉不再"贴太近"）；标签超出面板宽度时 FlowLayout 自然换行（输入框换到下一行）
  - **表名推断策略**（设置新增下拉）：无 `@TableName` 时类名→表名的推断方式——`SNAKE`（默认，对齐 MP 现状：驼峰转下划线，`Entity` 后缀不剥离）/ `CAMEL`（**纯保留驼峰**：仅首字母小写、**不去 Entity 后缀**，`BikeDeviceInfoEntity` → `bikeDeviceInfoEntity`，`BikeDeviceInfo` → `bikeDeviceInfo`）；显式 `@TableName` 不受影响
  - **忽略字段名**（设置新增文本，`;` 分隔）：按 Java **字段名**匹配，命中字段不进 SELECT 全列展开/参数对照/补全列集合（`@TableField(exist=false)` 之外的自行排除手段；两者并存、注解优先）
  - **移除表名前缀/后缀**（设置新增两文本，`;` 分隔）：推断表名以哪个开头/结尾就移除哪个（**首个匹配、大小写敏感**；仅作用于推断表名）
  - **添加表前缀/添加表后缀**：**单值**（用户反馈定稿——不存在多个，恢复 T-M16.2-03 原语义）；修饰链顺序 = 添加前缀 + 移除后缀(移除前缀(表名)) + 添加后缀
  - **`@TableName` 失效修复（用户实测，两层根因）**：① **缓存**——WrapperCache 按**锚点文件**（wrapper 所在文件）戳失效，实体文件新增/修改 `@TableName` 不触发 → 命中旧解析结果；修复：`LocatedWrapper` 记录**实体文件戳**，命中缓存时校验、变更即重算；② **语义**——显式 `@TableName` 即最终表名、**优先级最高、豁免修饰链**（推断策略与添加/移除前缀后缀均不作用；原 T-M16.2-03「全局语义」按用户反馈收窄为**推断语义**，修饰链仅作用于无注解推断的表名）；回归：`testEntityAnnotationChangeRefreshesPreview`（编辑实体加 @TableName → 预览更新）+ `testLocatedWrapperEntityFileValid`（实体文件变更 → 缓存校验失效，LightPlatform 无法端到端模拟跨文件类型解析，单元覆盖校验逻辑）
  - **设置修改后预览不生效（用户实测，根因：WrapperCache 按文件戳失效、设置变更不触发）**：缓存键新增**表配置签名**维度（推断策略/忽略字段/添加移除表前缀后缀 6 字段拼接）——设置页修改后**同一文件**再次预览不再命中旧结果（既有测试每次重建文件掩盖了此问题）；新增 `testTableConfigChangeInvalidatesPreviewCache` 回归（同一 service + 同一文件，修复前命中旧缓存失败）
  - **兜底路径统一走修饰链（推断语义补全）**：实体类不可解析的文本兜底推断（`WrapperLocator.inferEntityFromChain` / `resolveTableInfo`）与 MPJ 子表推断（`MpjLambdaConverter` 主表兜底/collection 子表/孙表/join 比对 4 处）全部改走 `EntityResolver.inferDecoratedTableName`（companion 静态化：推断策略 + 修饰链），此前这些路径绕过配置直接 `toSnakeCase`
  - **设置页文案与布局（用户反馈调整）**：推断策略选项「保留驼峰（去 Entity 后缀）」→「保留驼峰」且**行为同步改为纯保留驼峰**；「表前缀/表后缀」更名「**添加表前缀/添加表后缀**」并上移至「移除表名前缀/移除表名后缀」上方（行顺序：添加表前缀 → 添加表后缀 → 移除表名前缀 → 移除表名后缀 → 忽略字段名 → 表名推断策略）；移除类 2 栏 + 忽略字段名 **3 个多值输入栏空时显示灰色提示「多个时使用;来分隔」**（JBTextField.emptyText 原生 placeholder，语言切换 rebuild 即时刷新；添加类单值无提示）
  - 测试：`EntityResolverTableConfigTest`（11 例：推断策略 SNAKE/CAMEL/显式表名不受影响、忽略字段跳过/空白容忍/注解优先、移除首匹配/不匹配保持/大小写敏感、添加单值+移除多值、显式表名同样被修饰）+ `PluginSettingsPersistenceTest` 新字段 round-trip + 面板 reset/apply 联动 + 行顺序/placeholder 断言 + `SqlPreviewPanelTest` 标签宽度自适应/tooltip 断言 + `SqlPreviewServiceTest` 缓存失效回归
- M18.2 插件「最新变化」显示修复（用户实测：详情页显示停留在 M12 动态条件支持）：
  - **根因链**：① `plugin.xml` 无显式 `<change-notes>`；② IPGP `patchPluginXml` 未配置时自动用 `org.jetbrains.changelog` 从 CHANGELOG.md `[Unreleased]` 注入 change-notes；③ CHANGELOG 存在多个同名 `### Added` 分区，解析器取到**最后一个分区**（M12/M10）→ 注入内容错误；④ 自动注入**覆盖** plugin.xml 手写值
  - **修复**：`build.gradle.kts` `patchPluginXml.changeNotes` **显式配置**（M13~M18.1 十条倒序摘要，单一可信来源，更新时同步改构建脚本）；移除 plugin.xml 手写 change-notes（不生效且误导）
  - **验证**：完整构建链（jar → composedJar → prepareSandbox → buildPlugin）后解压 zip 核对 jar 内 change-notes = 新内容
  - **附带发现**：`-x prepareSandbox` 跳过时 buildPlugin 从沙盒残留 jar 打包（zip 内为旧内容）——验证产物必须走完整构建链；沙盒 jar 被 IDE 进程占用时 prepareSandbox 报文件锁错误，释放后正常
- M18.1 问题快速反馈入口：
  - `plugin.xml <description>` 末尾追加「问题反馈」链接（`<a>` 跳转 Gitee 仓库 Issues 页，双语文案）；IDE 插件管理页与 Marketplace 网页版均可点击
  - `README.md` 联系作者区块补充 Issues 提交反馈引导
- M18 插件介绍页底部作者微信 + 交流群二维码：
  - `plugin.xml <description>` 末尾追加双语「联系作者」区块（`<img>` 外链 Gitee raw 直链——base64/相对路径在插件描述中不可用，官方推荐外部 URL）；IDE 插件管理页（已安装/浏览市场）与 Marketplace 网页版展示
  - `README.md` 末尾新增「📞 联系作者」表格（仓库相对路径渲染）
  - 二维码成品图：裁剪主体 + 缩放 220×220 + 压缩（44.6KB / 27KB），入库 `docs/images/`（`.gitignore` 放行 `docs/images/`）
  - 注意：二维码为**限时码**（群码 8月20日前有效），换码后重跑图片处理替换入库文件即可（URL 不变，无需改描述）
- M17.4 按钮一行化（用户实测反馈，260 测试全绿）：
  - **弹窗底部按钮区一行三个**：「执行 SQL | 复制 SQL | 取消」（`SqlPreviewDialog.createActions`——执行 SQL/复制 SQL 为 DialogWrapperAction，取消为 cancelAction）
  - 根因：面板底部栏按钮与弹窗 DialogWrapper 自带按钮区（复制 SQL + 取消）叠加成两行；**移除面板底部栏**，按钮统一由弹窗 action 区提供（面板 `executeSql()` 改 internal 供弹窗调用）
  - bundle：删除废弃的 `dialog.copy.button`（弹窗复制改复用 `panel.copySql`）
- M17.3 执行 SQL 按钮位置修正（用户明确要求，260 测试全绿）：
  - 「执行 SQL」「复制 SQL」两个按钮在**面板右下角同一行**（底部栏右对齐），执行 SQL 在左、复制 SQL 在右；
  - 顶部工具栏仅保留：单行多行切换 / 格式化 / 刷新（**顶部无复制按钮**）；
  - bundle：`panel.copy` → `panel.copySql`（复制 SQL / Copy SQL，与执行 SQL 对称）
- M17.2 执行 SQL 体验修正 2（用户实测反馈，260 测试全绿）：
  - **数据源枚举健壮化**：`getDataSources()` 泛型擦除——原 `as? List<LocalDataSource>` 强转不检查元素类型，改为 **`filterIsInstance` 元素级过滤**（非 LocalDataSource 元素不再导致后续异常/空列表）
  - **选择器原生观感**：ChooseByNamePopup 列表自定义 renderer——**数据源类型图标** + 名称（数据库类型），贴近 Database 插件原生数据源下拉
  - **按钮位置**：回到**顶部工具栏「复制」左边**（与复制同一行，移除底部栏）
- M17.1 执行 SQL 体验修正（用户实测反馈，260 测试全绿）：
  - **IDE 原生数据源选择面板**：`Messages.showChooseDialog`（简化列表）→ **`ChooseByNamePopup`**（GotoClass/Search Everywhere 同款原生搜索弹窗——搜索框 + 数据源类型图标 + 列表，`ChooseByNameModel` 2025.3 重构后签名；2025.3/2026.2 双版本核对一致；`Callback` 为抽象类需括号初始化）
  - **编辑器右上角数据源绑定修复**：原 `newConsole().forFile().fromDataSource().build()` 仅建对象、未注册「文件↔数据源」关联——改为 `DatabaseSessionManager.getSession(project, ds)` → `Builder.useSession(session)` → **`JdbcConsoleProvider.attachConsole(project, session, vf)`** 持久化关联，打开后右上角显示所选数据源、Ctrl+Enter 直接执行
  - **按钮位置**：顶部工具栏 → **面板底部栏**（SOUTH，右对齐，用户要求）
- M17 SQL 预览面板「执行 SQL」按钮（260 测试全绿）：
  - **功能**：预览面板工具栏新增「执行 SQL」——取当前编辑器 SQL（含参数回填与手动编辑）→ 选择数据源（0 个引导创建 / 1 个直用 / 多个弹选择框，名称+数据库类型）→ **自动创建临时 .sql 文件**（`mpsql-exec-*.sql`）→ Database 控制台绑定数据源（`JdbcConsole.newConsole(project).forFile(vf).fromDataSource(ds).build()`）→ 打开编辑器（Ctrl+Enter 执行，IDE 原生控制台行为）
  - **兼容性（强制 IDEA 2025.3+，官方规范）**：构建平台 `intellijIdeaUltimate("2025.3.5")`（**最低支持版本编译**，公共 API 向后兼容保证 2026.x 可运行）；`<idea-version since-build="253"/>` 声明最低版本；**Plugin Verifier 已验证 2025.3.1 / 2025.3.5 / 2026.2.1 三版本 0 问题**；`com.intellij.database` 按官方 Plugin Dependencies 规范声明 `<depends optional="true" config-file="database-optional.xml">`（可选插件类不得在主描述文件引用）
  - **Community 兼容（隔离）**：主代码（面板/`ExecuteSqlSupport`）**零直接引用** database 类——运行时 `Class.forName` 探测插件可用性（版本无关，避开 `PluginId` API 迁移）+ 反射加载隔离类 `DatabaseConsoleLauncher`（Community 下永不加载）；按钮点击提示升级 Ultimate；测试沙盒排除 database jar 使降级路径稳定可测
  - **API 实测（2025.3.5 与 2026.2.1 双版本反编译核对一致）**：`LocalDataSourceManager.getInstance().dataSources`、`JdbcConsole.newConsole/Builder.forFile/fromDataSource`、`Messages.showChooseDialog`（返回下标 int）、`ReadAction.compute<T,E>`、`PluginId` 位于 `com.intellij.openapi.extensions`
  - 测试：`ExecuteSqlSupportTest`（isAvailable 降级判定 / execute 不抛异常）+ `SqlPreviewPanelTest`（按钮存在 / 点击降级不崩 / 无项目 no-op）——254 → 260 全绿；`buildPlugin` + `verifyPlugin` 成功
  - 手动验收（Ultimate 2025.3+）：按钮 → 选数据源 → 临时文件打开（绑定数据源）→ Ctrl+Enter 执行出结果；无数据源引导提示；Community 安装不崩溃、点击提示升级

- M16.2 SQL 补全修复 + 实体父类字段 + 表前缀/后缀配置（250 测试全绿）：
  - **SQL 补全修复（M16 回归，T-M16.2-01）**：
    - 回归点 A：M16 重写 Contributor 时 `ctx == null` 直接 return——上下文丢失时**关键字补全也静默失效**（M9 用 `if (ctx != null)` 包裹）。修复：关键字补全不依赖上下文（恢复 M9 语义），实体表/列/别名补全仅在 ctx 非空时执行
    - 回归点 B：上下文原绑定在 PSI 文件 userData，嵌入式编辑器用户输入后 `PsiDocumentManager` commit 会**重建非项目 PSI（userData 丢失）**，而 Editor 对象编辑期间稳定。修复：**Editor userData 优先 + PSI userData 兜底**双载体（`SqlCompletionContributor.resolveContext`，面板创建/刷新编辑器时双写）
    - 回归点 C：`detectClause` 用旧 PSI offset 切新文档文本可能抛 StringIndexOutOfBounds → Contributor 异常被 IDE 吞掉 → 补全无提示。修复：offset clamp 到文本长度 + 空文本/异常回退 UNKNOWN
  - **实体父类字段（T-M16.2-02）**：`EntityResolver` 字段收集**递归父类**（父类字段在前，对齐 MP 声明顺序语义；仅类继承链、`seen` 集合防循环继承）——继承实体（真实项目 `BaseEntity {id, createTime, deleted}`，80 实体 extends）的 SELECT 全列展开含父类列；**@TableLogic 继承识别修复**（父类 deleted 被识别——M15 逻辑删除注入对继承实体此前失效）；@TableId/@TableField(exist=false) 对父类字段同样生效
  - **表前缀/表后缀配置（T-M16.2-03）**：`PluginSettings.State` 新增 `tablePrefix`/`tableSuffix`（默认空=不修饰）；`EntityResolver.resolve` 末尾全局应用（**@TableName 显式表名同样被修饰**——全局语义，文档说明）；设置页新增两行文本输入「表前缀」「表后缀」（可添加/清除=清空输入框）；bundle 中英文案 `settings.tablePrefix`/`settings.tableSuffix`
  - 测试：`SqlCompletionContributorTest`（detectClause 边界 clamp 不抛异常 / 上下文双载体 Editor 优先）、`EntityResolverCommentTest`（继承字段全列含父类 / 多级继承递归 / 父类 exist=false 跳过）、`SqlPreviewServiceTest`（前缀+后缀生效 / 清空恢复原样 / @TableName 显式表名修饰）、`PluginSettingsPersistenceTest`（State 往返保留前缀后缀）——239 → 250 全绿
- M16.1 i18n 大小写合规（Plugin DevKit 检查器）：action `text` 修正为 Title Case（Convert To SQL——每个单词首字母大写）、`description` 修正为 Sentence Case（Convert the mybatis-plus wrapper under the caret to sql——仅首词大写，专有名词词首小写合规）；configurable displayName 已合规无需改动
- M16.1 设置中英文切换失效修复（239 测试全绿）：
  - **移除 PluginSettings 双重注册**：plugin.xml `<applicationService>` 与 `@Service(APP)` 注解并存（M14.7 引入注解时未移除 XML 遗留）——官方规范禁止，双注册导致持久化绑定错实例、语言切换"即时生效但保存失效"（确定后重开/重启回中文的根因）；保留注解式注册（IDE 自动发现）
  - **设置页标题跟随语言**：`applicationConfigurable` 移除 `bundle`/`key` 属性，displayName 由 `getDisplayName()` 动态返回（key 属性由 IDE 默认 locale 解析、不跟随插件语言）
  - **防回归自检**：plugin.xml 无 PluginSettings 的 applicationService 注册 + configurable 声明 displayName/key 属性断言
  - **修正（验证规则）**：覆写 getDisplayName() 的 Configurable 必须声明 displayName 或 key 属性（Plugin DevKit
    验证报错 "Define 'displayName' or 'key' attributes matching the 'Configurable.getDisplayName()' implementation"）——
    恢复 bundle+key 声明（key 由 IDE 默认 locale 解析；getDisplayName 覆写保留作动态兜底），自检断言同步反转
  - **持久化链路测试**：`PluginSettingsPersistenceTest`（getState→loadState 往返保留 language / Configurable apply-reset 链路 / bundle 即时切换回归）
- M16 SQL 面板字段补全 + 注释提示（v1.10，237 测试全绿）：
  - **注释读取（四级优先级 + schema 预留）**：`@Schema(description)` / `@ApiModelProperty(value/description)` / `@ExcelProperty(value)` 等注解白名单（排除 MP 注解 value 误取；`@TableField(comment=)` 例外）> javadoc（多行清洗）> 行注释（LightJava textRange 绑定 + 文档扫描双路径）；`CommentSource` 优先级链（SCHEMA 最高，方案 B 接入即自动覆盖）
  - **表/实体注释**：类级注解/javadoc/行注释 → `TableInfo.comment`，表名补全项展示注释
  - **注释传递**：`PreviewResult.columnComments/tableComment` + `SqlCompletionContext` 同字段（向后兼容）
  - **补全展示与检索**：列名/表名 lookup `withTailText` 灰色注释尾缀 + `withLookupString` 注释关键词检索（输入"设备名"命中 `name` 列）
  - **位置感知补全**：FROM/JOIN 后只提示表名、WHERE/ON/HAVING/GROUP BY/ORDER BY 后只提示列名、SELECT 列位补充聚合函数（COUNT/SUM/MAX/MIN/AVG）；无法判定回退全量
  - 修复：LightPlatform 行注释不在兄弟链/不随 offset 前缀（textRange 绑定注释）——元素文本首行 + 文档扫描双路径提取
- M15 官方 API 增补 3（223 测试全绿）：
  - **union / unionAll**：`union(SubClass.class, w -> ...)` → `UNION (SELECT ... FROM sub_entity WHERE ...)` 子查询（独立解析器 + compact 单行渲染）
  - **selectSub**：`selectSub(SubClass.class, w -> ..., alias)` → `(SELECT ...) AS alias` 子查询列（第三参按别名解析，驼峰属性名）
  - **逻辑删除控制**：`disableLogicDel()` / `logicDelToOn()` 链内出现时跳过 deleted 注入
  - **联表更新删除消费**：`deleteJoin(wrapper)` → DELETE；`updateJoin(entity, wrapper)` / `updateJoinAndNull` → 实体字段展开 SET
  - **列对列比较**：`eq(子表列, 主表列)`（子查询内）→ `列 = 列`（ColumnComparison；子查询内近似无外层别名）
- M15 官方 API 增补 2（220 测试全绿）：
  - **comment / first**：`comment("...")` SQL 注释追加末尾、`first("...")` SQL 最前片段（SelectStatement.rawFirst/comment 模型）
  - **setAlias**：MPJ 主表别名方法（优先级：构造别名 > setAlias > 默认 t）
  - **子查询条件**（官方 sub-query API）：`eq/ne/gt/ge/lt/le/in/notIn(col, SubEntity.class, w -> ...)` → `col op (SELECT ... FROM sub_entity WHERE ...)`，子查询用独立解析器渲染（不继承主表别名）
  - **select(Class, Predicate) / selectFilter**：谓词不可静态求值，按全列近似渲染
  - **selectJoinMaps / selectJoinPage / selectJoinMapsPage** 消费验证（渲染同为 SELECT）
- M15 官方 API 全覆盖（基于官方文档 + 3.5.15/1.5.6 源码核实的《官方 API 清单》，217 测试全绿）：
  - **MP 条件方法补齐**：`notLikeLeft`/`notLikeRight`、`inSql`/`notInSql`、`eqSql`/`gtSql`/`geSql`/`ltSql`/`leSql`（SQL 片段比较）、`nested`（独立括号块）、`not(consumer)`（NOT 包裹）、`func(consumer)`（条件展开）、`orderBy(condition, isAsc, cols)`（双布尔前缀解析）；`setIncrBy`/`setDecrBy`（`SET col = col ± val`）
  - **MPJ 补齐**：`selectSum`/`selectMin`/`selectAvg`/`selectLen`（含别名重载）、`fullJoin`、`join(自定义关键字, ...)`、字符串 SQL join（`leftJoin("sql", args)` 含 `{i}` 参数）、`selectAll(Class, prefix, exclude...)`、`applyFunc`（条件版 selectFunc，`%s`+`{0}` 占位）、`eqIfExists` 等 IfExists 条件族、`selectAsClass`（属性名交集映射）；链式终结点补充 `first`/`mapOne`/`mapFirst`/`mapList`/`mapPage`
  - **渲染修复**：LIKE 字面量百分号位置（`'%x%'` 引号包裹，原 `%'x'%` 非法）；lambda 内 `or()` 分隔符双层括号展平（`AND ((a OR b))` → `AND (a OR b)`）；聚合列与 selectAll 混用保留
  - 行为变更：`not`/`nested`/`func` 由"未知方法忽略"变为官方语义支持（既有测试断言更新）
- M15 真实项目全量语料兼容（v1.9，基于《MPJ 用法收集与兼容性测试清单》2026-08-13 全量复核版）：
  - **服务链式 wrapper 定位（T-M15-01，Q17/U13/U07-④⑤/U11-⑤）**：`service.lambdaQuery()...one()/.list()/.count()/.exists()/.page()` 与 `service.lambdaUpdate()...update()/.remove()` 链全部可预览（真实项目约 264 处调用）；终结点消费语义（count→COUNT(*)、exists→`SELECT EXISTS(...)`、remove→DELETE）；`LambdaQueryChainWrapper`/`LambdaUpdateChainWrapper` 显式声明支持；gutter 锚点同步（`service.lambdaQuery()` 方法名）
  - **字符串版 QueryWrapper / UpdateWrapper 转换器（T-M15-02，F-17，Q03/Q09/Q18/U09）**：注册 QUERY/UPDATE 类型；字符串列/`select(表达式)`/`groupBy(字符串)` 原样透传；`wrapper.lambda()` 动态转换（U09-④）；修复 `analyzeFlag` 把字符串列误判为布尔标志（`between("create_time", ...)` 被剥离的缺陷）
  - **exists 三形态（T-M15-03，Q14）**：链内 `exists(boolean, "SELECT ... {0}", v)` 条件（`{i}` 参数替换 + 布尔剥离 + EXISTS 包裹）、`service.exists(wrapper)` 消费、链式 `lambdaQuery()...exists()`（T-M15-01 已具备）
  - **更新链路补强（T-M15-04，U01-③/U03/U04-③/U05/U07-②）**：`set(boolean, col, val)` 条件 set 布尔剥离（真实项目约 25 处）；`setSql("...{0}", val)` 绑定参数替换；更新 wrapper 上 `last("AND ...")` 非 limit 尾缀透传（UpdateStatement.rawTail，防超发兜底）；`update(entity, wrapper)` 双参 upsert 实体字段展开 SET（排除主键）；无泛型 `Wrappers.lambdaUpdate()` 从链内方法引用推断实体（约 25 处）
  - **跨方法追踪补全（T-M15-05，P03/P15/P16/U08）**：if/else 互斥分支只保留源码序第一个分支（修复 `WHERE a = ? AND a IN (...)` 语义错误）；`update(null, wrapper)`/`selectCount(wrapper)` 行实参锚点回查声明链；工厂 3 级链（`applyWithdrawFilters(baseWithdrawQueryWrapper(), dto)` 参数工厂递归展开）；`wrapper.set(a).set(b)` 链式延续收集
  - **selectCollection ON 修复（T-M15-06，P06/P07）**：子查询 LEFT JOIN 的 ON 复用扩展至三参/四参/on()/lambda ON 四种形态（修复三参形态下无 ON 的笛卡尔积）；joins 按源码顺序合并；同子表普通 join 去重
  - **逻辑删除与实体注解（T-M15-07，X06/X07）**：`@TableLogic` 读取（TableInfo.logicDeleteColumn）；查询/删除自动追加 `AND deleted = 0`（新增设置项 `injectLogicDelete` 默认开，对齐全局 logic-delete-field；UPDATE 不注入）
  - **单实体批量方法（T-M15-08，U11/U12/X09）**：`removeById(id)`（实体从 service 泛型解析）；`updateById(entity)`；`saveOrUpdate`/`saveOrUpdateBatch` PG 方言 `ON CONFLICT` upsert；`insertBatchSomeColumn` 排除 `FieldFill.UPDATE` 填充列；`@TableField(fill)` 读取（ColumnInfo.fill）
  - 行为变更：`service.lambdaQuery()` 等链起点语法级匹配（自定义工具类同名方法也会被识别）；逻辑删除注入改变既有 SELECT/DELETE 输出（可关闭）；if/else 分支语义默认取 then 分支
- M14 设置页增强 + 弹窗自适应 + 插件名统一（v1.8）：
  - **界面语言切换**：`PluginSettings.language`（**默认简体中文**，用户要求）；`MpSqlVisualizerBundle` 由 DynamicBundle 改为自管理 ResourceBundle（UTF-8 Control 读取，zh/en/AUTO 三态，测试环境兜底中文）；设置页「界面语言」下拉，保存后重建表单即时生效（弹窗/tooltip 天然即时）
  - **快捷键入口**：设置页展示 Convert to SQL 当前快捷键（KeymapUtil）+「修改快捷键…」按钮一键跳转 IDE Keymap 设置
  - **弹窗自适应**：`SqlPreviewDialog` 默认尺寸按屏幕比例（宽 62%/高 65%，下限 900×600、上限 85%，小屏不超屏），`getDimensionServiceKey` 记忆用户拉伸尺寸，最小尺寸 640×420
  - **插件名全局统一**：`MyBatis-Plus SQL Visualizer`（bundle 3 键 ×2 语言、代码注释、CHANGELOG/开发计划/需求文档全量替换；内部标识 plugin id/Storage/类名不变）
  - **构建自检**：`PluginDistributionSelfCheckTest`（plugin.xml configurable 注册、META-INF 图标约定路径、插件名、bundle 默认中文断言）+ `SqlPreviewDialogSizeTest`（尺寸策略 mock 屏幕）；插件图标改为主 JAR `META-INF/pluginIcon.svg` 官方规范位置（详见《20260813-0000-设置页修复与弹窗自适应-开发计划.md》）
- M14.1 设置页优化（用户反馈）：
  - **移除悬停预览功能**：删除 `SqlPreviewDocumentationProvider`/`SqlHtmlRenderer` 与 plugin.xml `<documentationProvider>` 注册、`PluginSettings.autoPreview`/`hoverDelay` 配置项及设置页对应行、bundle 文案（`settings.autoPreview`/`settings.hoverDelay`/`documentation.title`）
  - **快捷键内嵌修改**：设置页「快捷键」行改为录制式编辑器（显示当前快捷键 + 修改弹窗捕获按键组合），保存时直接写入 IDE 当前 Keymap（移除旧 KeyboardShortcut + 添加新组合，立即生效并持久化）；替代原「跳转 Keymap 设置」方案
  - **文案清理**：gutter 开关去掉「（M10）」后缀
  - **布局优化**：设置页行距紧凑化 + 底部 weighty 占位，消除内容区上方空白
  - **默认快捷键调整**：`Ctrl+Alt+S`（与 IDE 打开 Settings 冲突）→ 先改 `Ctrl+Alt+Q` → 最终定为 **`Alt+Shift+Q`**（macOS `Option+Shift+Q`，Q=Query；已核对 IDEA 默认 Keymap 未占用），可在 Keymap 或插件设置页录制修改
- M14.6「替换 ? 占位符」开关接通：
  - `replaceParams` 配置此前为"死配置"（仅 UI/持久化，转换链路未读取）——现已接通：关闭时 `ArgResolver` 跳过 `ExpressionEvaluator` 变量追踪，**可静态求值的变量也渲染为 `?`**（左栏输入框可手动填值）；**字面量恒内联**（代码直接写的常量不受开关影响）
  - 参数透传：`WrapperLocator.locateWithTable(resolveVariables)` → `ArgResolver.toWrapperCall/resolve`；`WrapperCache` 缓存键增加配置维度（开关切换后不命中旧结果）
  - 测试：`testReplaceParamsOffKeepsVariablePlaceholder`（变量 → `?`、字面量 → 内联）
- M14.7 配置可用性核验 + 动态插件支持（免重启）：
  - **核验结论**：5 项配置（方言/替换 ?/格式化/gutter 图标/界面语言）**全部已接通业务链路，无死配置**（各附证据读取点）；`autoPreview`/`hoverDelay` 已随 M14.1 悬停删除、非配置项
  - **集成测试补齐**：dialect 分页三方言（PG `LIMIT 10 OFFSET 5`）、formatSql 关闭初始单行、showGutterIcon 关闭不生成 marker（防未来回归为死配置）
  - **动态插件支持（免重启安装/更新）**：移除 `<depends>org.jetbrains.kotlin</depends>` 与 kotlin 扩展命名空间（本插件不处理 Kotlin PSI，为模板遗留；Kotlin 插件不支持动态卸载，依赖非动态插件导致无法免重启）；kotlin-stdlib 由 IDEA 平台自带，不打包不影响运行；`runIde { autoReload = true }` 开发期热重载；自检测试断言依赖清单
  - **设置变更生效时机**（文档化）：设置修改后**重新触发预览**即生效（无需重启 IDE）；弹窗内「单行/多行」为临时视图切换、不写回 `formatSql` 配置；`WrapperCache` 缓存键含开关维度（M14.6）
- M14.7 修复（用户反馈）：
  - **中英文切换无效**：根因是 Java `ResourceBundle` fallback 链默认包含 JVM 默认 locale——系统中文时请求 `en` 找不到 `_en` 回退到系统中文 locale。修复：自定义 Control 覆写 `getFallbackLocale` 返回 null（fallback 直达 base 英文），`en`/`zh` 由插件配置精确控制；新增 fixture 测试 `testLanguageSwitchEnImmediate`
  - **gutter 图标开关不即时生效**：line marker 结果被高亮缓存，设置变化不触发重算。修复：设置保存时若开关变化，遍历所有打开项目编辑器文件触发 `DaemonCodeAnalyzer.restart(file)`（图标立即刷新，无需重开文件）

- M12 动态条件支持（布尔重载复选框 + 空值不拼接，v1.6）：
  - 模型：新增 `ConditionToggle`/`EmptyValueRule`（`SKIP_ON_BLANK` 判空 / `SKIP_ON_NULL` 判非 null / `UNKNOWN`），`Condition` 各子类带 `toggle` 元数据（默认 null，旧逻辑兼容）
  - 转换：`QueryWrapperParser.skipConditionFlag` → `analyzeFlag`/`markConditionFlag`——布尔条件重载（`.eq(true, col, val)` 等）不再丢弃 `false` 条件，改为打 toggle 元数据（`false` 条件进入模型但默认排除，生成层过滤；默认输出与 M11 基线**逐字节一致**）；**判空语义识别**（`isNotBlank`/`isNotEmpty`/`hasText` → 判空，`isNotNull`/`nonNull`/`!= null` → 判非 null）；**判空方法静态求值**（`StrUtil.isNotBlank("")`→默认排除、`"" != null`→默认拼接 `name = ''`、null 判非 null→排除）；`apply(condition, ...)` 布尔前缀同（运行时布尔前缀由丢弃改为动态条件）
  - 生成：`SqlGenerator.generateWithParams`/`generate` 增加 `enabledConditionIds` 过滤（递归 Group，组内全空整组跳过；默认 null 按 `defaultEnabled`，与基线一致）
  - 展示：`PreviewResult.previewModel`（`SqlPreviewModel`/`ConditionView`）；`SqlPreviewPanel` 左栏升级为「动态条件复选框行 + 值输入框」，参数改**按 source 绑定**（取代位置绑定），勾选/填值/清空 → **模型级重生成** SQL；判空条件（SKIP_ON_BLANK）空值自动取消勾选不拼接、填写拼接；判非 null 条件（SKIP_ON_NULL）空值仍拼接为 `''`（空字符串是合法值）、仅手动取消勾选才排除（详见《20260812-2107-动态条件复选框与空值跳过-开发计划.md》）
- M10 代码左侧预览 SQL 图标（gutter 入口，v1.4）：
  - T-M10-01 `WrapperAnchorDetector`：单元素 O(1) 锚点判定器（`new` 关键字 / 变量声明名，含同行去重），快速路径零 resolve、零遍历（性能红线，见 M10 开发计划 §2.4）
  - T-M10-02 `SqlGutterLineMarkerProvider`：Wrapper 锚点行显示预览 SQL 图标（`codeInsight.lineMarkerProvider language="JAVA"`），静态 tooltip、点击复用现有预览链路；新增 `icons/sqlGutter.svg`；锚点覆盖三种形态：`new Wrapper` 构造（new 关键字）、变量声明（变量名）、内联 `Wrappers.<T>lambdaQuery()` 工厂调用（方法名，qualifier 文本匹配，零 resolve）
  - T-M10-03 统一展示入口 `SqlPreviewService.showResult`：ConvertToSqlAction 与 gutter 点击共用，按显示方式配置（F-16）分发弹窗/工具窗口
  - T-M10-04 配置开关 `showGutterIcon`（默认开，Settings 页面可关闭，性能/视觉兜底）+ bundle 中英文案
  - 删除消费识别：`LambdaQueryWrapper` + `service.remove(wrapper)` / `mapper.delete(wrapper)`（MP 惯用删除写法）→ 转 DELETE 而非 SELECT（`WrapperChain.deleteConsumed`，WrapperLocator 三种构造路径均收集，消费调用取最近实参列表——覆盖内联 `remove(new Wrapper...())` 及嵌套场景）
  - 基类型声明支持：`Wrapper<X> w = new LambdaQueryWrapper<X>()...`（抽象基类型变量）→ 变量名仍显示图标，`WrapperLocator` 从初始化表达式链首推断具体 wrapper 类型并正常转换

### Changed

- M13 动态条件简化 + 参数值类型推断（M12 微调，v1.7）：
  - 模型：删除 `EmptyValueRule` 枚举与 `ConditionToggle.emptyRule`/`ConditionView.emptyRule`（判空语义识别下线）；`ColumnInfo` 新增 `fieldType`（实体字段类型，`EntityResolver` 记录）；新增 `sql/ParamHint`（引号/LIKE 位置回填提示），`GeneratedSql.paramHints`/`PreviewResult.paramHints` 与 `unresolvedParams` 并行
  - 转换：`analyzeFlag` 删除判空语义识别（`isNotBlank`/`isNotNull` 等正则）与判空方法静态求值，`defaultEnabled` 简化为三态（字面量 true→勾选、false→不勾选、运行时布尔→勾选）；**复选框纯手动**——值空不再联动勾选态（M12 的 SKIP_ON_BLANK 空值自动取消勾选、SKIP_ON_NULL 空值拼 `''` 行为移除，空值一律保留 `?`）
  - 生成：渲染链线程化 table + hintSink，为每个 `?` 并行产出 `ParamHint`（String 列 quote=true、数值列 quote=false、未知 null；LIKE 占位符 quote=true + likePosition）；**SQL 文本输出与 M12 基线逐字节一致**（回填提示为附加信息）
  - 展示：`SqlPreviewPanel` 值输入框回填按 hint 渲染——LIKE 自动拼百分号（`'%值%'`/`'%值'`/`'值%'`）、String 列输入数字恒引号（`'123'`）、数值列恒原样（`123`）、类型未知走原数字启发式；`conditionTooltip` 与 bundle 清理 `panel.condition.skipNull`（详见《20260812-2210-动态条件简化与参数值类型推断-开发计划.md》）

### Removed

- M11 移除侧边工具栏面板（工具窗口，v1.5）：删除 `SqlToolWindow`/`SqlPreviewToolWindowFactory`/`SqlPreviewEditorTracker` 三个文件与 plugin.xml 的 `<toolWindow>`/`<editorFactoryListener>` 扩展；移除 `PluginSettings.displayMode`（DIALOG/TOOL_WINDOW/BOTH）与 Settings 页面「显示方式」下拉、bundle `settings.displayMode.*`/`toolwindow.*` 文案；展示收敛为**恒弹窗**（`SqlPreviewService.showResult` 统一走 `SqlPreviewDialog`），只保留右键菜单 / 快捷键 / gutter 图标三入口（详见《20260812-2034-移除侧边工具栏面板-开发计划.md》）

### Fixed

- M19 用户实测两处缺陷修复（262 测试全绿）：
  - **设置语言切换仍无效果（根因修正：apply 门控链路 + JDK17 setSelectedItem 匹配语义）**：M16.3 roaming 修复（磁盘持久化）后复测仍失效——① 反编译 IDEA Ultimate 2026.2.1 `ConfigurableEditor.apply()`：Settings 框架 OK 仅对「当前选中页」且 `isModified()==true` 的 configurable 调用 `apply()`（`myApplyAction.isEnabled() ? configurable : null`，未启用传 null 直接跳过）→ 改完语言切到其他设置页再点确定（或修改检测未触发）即被静默丢弃，`state.language` 从未写入、重开设置原样。② 顺带发现 `LanguageOption` 无 equals 覆写（身份判等）+ JDK17 `JComboBox.setSelectedItem` 以 equals 在 model 中查找元素、**找不到即静默 return**（反编译 JDK17 验证）→ `reset()`/选中写入传入的新实例全部匹配失败（选中显示与事件触发一并失效）。修复：语言下拉**写穿式持久化**（选中即写入配置，不依赖 OK→apply 链路；值守卫防循环，`apply()` 幂等）+ `LanguageOption` 改 **data class（按 value 判等）**；回归测试 `testLanguageWriteThroughOnSelection` / `testLanguageWriteThroughApplyIdempotent`（写穿后 apply 幂等、重复选同值 no-op）
  - **执行 SQL 数据源弹框无内容（根因：ChooseByNamePopup 空词不显示列表）**：多数据源时 `ChooseByNamePopup`（GotoClass 同款）在搜索词为空且未调用 `setShowListForEmptyPattern(true)` 时（`ChooseByNameBase.rebuildList`：`!canShowListForEmptyPattern() && text.isEmpty()` → 清空列表 + 隐藏 + 不执行元素计算）→ 弹框无内容，仅输入字符后才出现列表（平台设计行为）。修复（按用户要求）：**移除数据源选择弹框**——直接创建临时 `mpsql-exec-*.sql` 文件并打开编辑器，用户经 Database 插件 SQL 编辑器**右上角数据源选择器**自行选择后 Ctrl+Enter 执行（IDE 原生行为）；删除 `listDataSources/chooseDataSource/DataSourceByNameModel/会话与 attachConsole` 一整套 database 反射面（launcher 现为纯平台 API）
- **M19.1 语言切换体验修正（用户实测反馈）**：
  - **「应用」按钮灰掉**：语言**写穿**后表单与配置一致 → `isModified()` 返回 false → 平台 Apply 按钮启用 = `isModified()`（反编译 2026.2.1 `ConfigurableEditor.updateCurrent` 验证）→ 应用按钮不可点。修复：写穿后置 `languagePending`「待应用」标记——`isModified()` 含该标记（Apply 保持可点），`apply()` 消费后清除（幂等），语言变更仍由写穿保证持久化
  - **切换后右侧面板不实时变（左侧树即时变）**：写穿监听在 `ActionEvent` 派发中途**同步**调用 `rebuild()` 修改组件树——Swing 事件派发期间重建组件 UI 刷新不可靠。修复：**`SwingUtilities.invokeLater { rebuild() }`** 延后到事件派发完成再重建（右侧面板与左侧树同步实时刷新）
- **M19.2 语言切换右侧文本仍不实时变（根因修正：控件实例文本构造时固化）**：用户实测列举四项（参数替换/格式化/预览图标/逻辑删除——**全是复选框**）确定重开才变英文。根因：行标签 `JBLabel` 是 `buildRows` **每次新建**（随语言变——即用户看到的"左侧"），但**复选框/按钮是字段实例**——文本在面板构造时按当时语言固化，`rebuild` 重建布局只是把同一实例重新 add，文本从不更新（M14.7 rebuild 设计即存在此漏洞）。修复：**控件文本设置收进 `buildRows`**（4 个复选框 `apply { text = ... }` + `ShortcutEditor.updateTexts()` 刷新 tooltip/按钮）——每次 rebuild 随语言重设，切换即时生效；回归测试 `testLanguageWriteThroughRefreshesCheckboxTexts`（zh→en rebuild 后遍历组件树断言复选框全英文、无 CJK 残留）
- **M19.3 预览面板刷新后 SQL 停在单行（用户实测）**：`refreshView()` 原语义"保留参数与模式"只重渲染——此前手动切换/设置快照的单行模式被保留，刷新不会回到默认多行，与用户"刷新=默认多行"直觉冲突。修复：**刷新时模式复位为与设置「SQL 格式化输出」对齐**（默认多行，`PluginSettings.state.formatSql` 兜底 true）并同步模式按钮文本，参数与回填保留；回归测试 `testRefreshResetsModeToSettingsDefault`（切单行 → 刷新 → 恢复多行）

- M16.3 用户实测三处缺陷修复（254 测试全绿，反证验证）：
  - **设置语言切换保存失效（根因修正）**：`PluginSettings` 是应用级轻服务（`@Service(Service.Level.APP)`）+ `PersistentStateComponent`——官方规范（SDK Services 文档）要求**必须禁用 roaming**（`roamingType = RoamingType.DISABLED`）；原 `@Storage("mp-sql-visualizer.xml")` 使用默认 `RoamingType.DEFAULT`，状态进入设置同步（Settings Sync）管理通道、与轻服务持久化不兼容 → 切英文确定后重开/重启回中文。修复：`@Storage` 显式声明 `roamingType = RoamingType.DISABLED`（M16.1 移除双注册后仍未生效即此根因）；新增编译期契约测试 `testStorageRoamingDisabledContract` 防回归
  - **补全看不到注释（根因：不可变 builder 返回值被丢弃）**：`SqlCompletionContributor` 中 `LookupElementBuilder` **不可变**——`withTailText`/`withLookupString` 返回**新实例**，原实现 `builder.withTailText(...)` 丢弃返回值后添加原始 builder → 注释 tailText 从不展示（字段补全正常、注释全无）。修复：抽 `withComment` 链式装饰（表名/列名两处）；渲染测试 `testWithCommentCarriesTailTextAndLookupString`（反证：还原丢弃写法时 tailText=null 复现）
  - **SELECT 空列名（`SELECT id, , , deleted, ...`）**：真实项目注解类可解析时 `PsiAnnotation.findAttributeValue("value")` 返回注解**默认值**（`@TableField` 的 `value() default ""`）→ `@TableField(fill=...)` 无 value 的字段列名解析为空串（测试环境注解不可解析故未暴露）。修复：`attributeString` 空白视为未指定（回退命名策略），逻辑删除列计算同源修复；回归测试用**同文件可解析注解**复现（反证：还原旧实现时输出与用户实测完全一致的 `id, , , deleted`）

- **同文件多 Wrapper 串缓存（删除/修改被识别成查询的根因之一）**：`WrapperCache` 原按 `PsiFile` 缓存定位结果，但定位结果依赖锚点——文件含查询+删除+修改多个 wrapper 时，首次点击缓存查询结果，后续点击全部命中查询 → 删除/修改显示 SELECT。缓存键改为 `PsiFile + 锚点起始偏移`，各锚点独立缓存。回归：`SqlPreviewServiceTest.testMultipleWrappersInOneFileDoNotCrossContaminate`
- **内联 wrapper 定位跨方法误命中（根因之二）**：`findWrapperNewExpression`/`findWrapperFactoryCall` 沿祖先链向上时每层都向下扫子树，走到 `PsiClass` 级别会命中类内**其他方法**的 wrapper（如 `remove(Wrappers.<X>lambdaQuery()...)` 定位到同文件 query 方法的 `new LambdaQueryWrapper`）→ 删除/修改被串成查询。改为向下扫子树只在锚点初始位置一次、向上只检查元素自身
- `WrapperLocator.detectWrapperType` 类名提取 bug：原用 `canonicalText.substringAfterLast('.')`，泛型实参带包名时（真实项目常态，如 `LambdaQueryWrapper<com.biz.Entity>`）最后一个 `.` 落在泛型参数内，导致 Wrapper 类型识别失败——gutter 图标不显示，且悬停/快捷键/右键定位全部失效。改为 `PsiClassType.getClassName()`（在 `<` 之前取末段，无需 resolve，快速路径安全）。回归测试：`WrapperAnchorDetectorTest.testQualifiedEntityTypeAnchors`
- 构建：`WrapperChain` 等模型加字段后 Gradle 构建缓存恢复旧编译产物导致 `NoSuchMethodError`，本地验证需 `--no-build-cache`（CHANGELOG v1.0/v1.1 同款问题的持续记录）
- 构建：测试 classpath 排除 Vue 插件（`vuejs-plugin.jar`）——LightPlatform 沙盒加载其 LSP 扩展在无 node 环境初始化失败，随机污染测试结果（JetBrains/intellij-platform-gradle-plugin#1887）

## [1.1.0] - 2026-08-12

### Added
- M7 MPJ 真实语料兼容（对照《MPJ 用法收集与兼容性测试清单》P01~P20 全覆盖）
  - T-M7-01 `selectAs(Entity::getX, VO::getY)` 方法引用别名（AliasArg 保留驼峰属性名）、`selectAs("alias.col", ...)` 字符串列不补主表前缀、字符串拼接表达式求值（`"LIMIT " + N`）
  - T-M7-02 `selectAll(Class)` 主表全列、`distinct`（SELECT DISTINCT）、`selectMax`/`selectCount` 聚合列（ColumnRef.function）
  - T-M7-03 `selectFunc` 自定义聚合：`%s` 列占位 + `{0}/{1}` 绑定参数 + `func.accept/values` + 别名
  - T-M7-04 `selectCollection` 一对多子查询 JOIN（id/result 子映射、嵌套 collection 三层）、`selectAssociation` 一对一（leftJoin 已体现关联）
  - T-M7-05 联表增强：三参 `leftJoin(Class, 关联表字段, 主表字段)`、四参带别名 join、lambda ON `on -> on.eq(...)`、表别名构造 `new MPJLambdaWrapper<>(Class, "alias")`（主表别名不再固定 `t`）、关联实体注解解析（EntityRefArg）、`orderByAsc("alias", col)`
  - T-M7-06 条件结构增强：布尔条件重载（`eq(boolean, col, val)` 字面量 false 忽略）、`apply` 参数化片段（`{0}` 替换）、无参 `or()` 分隔符（OR 分组）、`last("LIMIT " + N)` 表达式
  - T-M7-07 跨方法工厂链追踪：`baseWrapper()` 返回 wrapper + 调用方续链（resolveMethod + return 表达式链合并）
  - T-M7-08 `MPJLambdaQueryWrapper` 类型支持（单表 lambda 语义）；WrapperLocator 支持内联 `new` 表达式锚点（`list(new Wrapper<X>()...)`）
- 脱敏：测试清单/开发计划/项目介绍/测试与主代码中的真实实体类名、模块名统一替换为通用示例名（防敏感信息泄露）

### Fixed
- 条件连接符：无参 `or()` 之前被忽略导致条件误以 AND 连接（现按 OR 分组）
- `selectAs` 字符串列带引号问题：`asColumn` 改用 `unquoted`
- 构建缓存：Kotlin 增量编译/`org.gradle.caching` 污染导致 `NoSuchMethodError`，本地验证需 `--no-build-cache`（CHANGELOG v1.0 同款问题的持续记录）

## [1.0.0] - 2026-08-12

### Added
- M0 基建清理：删除模板示例，重写 `plugin.xml`，建立 8 包结构（actions/psi/model/converter/sql/ui/config/util），README 产品化
- M1 核心转换引擎 v1（F-01/F-13/F-20）：LambdaQueryWrapper → SELECT
  - `model`：TableInfo/ColumnRef/SqlValue/Condition/SelectStatement/WrapperChain 内部模型
  - `psi`：WrapperLocator（链式/分步/混合写法）、ArgResolver（Lambda→列、嵌套链）、
    EntityResolver（@TableName/@TableField/@TableId + 命名策略）、ExpressionEvaluator、WrapperCache
  - `converter`：QueryWrapperParser（全条件/结构操作分派）
  - `sql`：SqlGenerator（SELECT 全子句、多条件缩进、LIKE % 拼接）
- M2 参数替换与格式化（F-02/F-03）：
  - ExpressionEvaluator 变量赋值追踪（`Long deviceId = 1001L;` → `1001`，多级引用链）
  - GeneratedSql 参数收集、ParameterReplacer（来源注释 + 参数对照表）、SqlGenerator 单行/多行双视图
- M3 展示与交互（F-04/05/06/07/08）：
  - SqlPreviewService（project service 整合层）、SqlPreviewPanel（共享展示 + 一键复制）、
    SqlPreviewDialog（弹窗）、SqlPreviewToolWindowFactory（工具窗口 + 复制/清空）、
    SqlPreviewDocumentationProvider（悬停预览）
  - ConvertToSqlAction（右键 + 快捷键 Ctrl+Alt+S / Cmd+Option+S）
- M4 扩展 Wrapper 类型（F-09/F-10/F-11/F-12）：
  - Converter SPI（WrapperConverter + ConverterRegistry，新增类型零 UI 改动）
  - LambdaUpdateConverter（set/setSql）、LambdaDeleteConverter、MpjLambdaConverter、
    MpjQueryConverter（字符串字段版）；JoinClause/ColumnComparison/主表别名 t
  - SqlPreviewService 全类型分发 + 无 WHERE 红色警示
- M5 配置系统（F-16/F-14）：
  - PluginSettings（PersistentStateComponent，持久化到 mp-sql-visualizer.xml）
  - PluginSettingsConfigurable（Settings → Tools → MyBatis-Plus SQL Visualizer）
  - Dialect SPI（MySQL/PostgreSQL/Oracle 分页与标识符差异）
- M6 高级特性与发布（F-15）：
  - 批量操作识别：updateBatchById/saveBatch/insertBatchSomeColumn/removeByIds → 批量 SQL 模板 + 参数组
  - 边界用例补强（非法片段/超长链/空语句）+ 性能基准测试
  - 国际化：MpSqlVisualizerBundle（中英文），运行时 UI 文案全部本地化
  - README 使用说明、CHANGELOG 版本记录

### Fixed
- 构建：`instrumentCode` 在 Windows 中文环境报 `java.home/Packages does not exist`，
  通过 `intellijPlatform { instrumentCode = false }` 禁用（纯 Kotlin 插件无需 @NotNull 插桩）
- Wrapper 链式调用定位：`textOffset` 相同（均从 `new` 起始）导致去重误删 → 改用 `endOffset`
- 字符串参数引号污染 SQL 片段（last/setSql/selectAs）：SqlValue.Literal 增加 `unquoted` 字段
- MPJ `on(col1, col2)` PSI 解析：新增 OnConditionArg 类型
- Kotlin 增量编译缓存导致 NoSuchMethodError：clean 重编译解决
