<!-- Keep a Changelog guide -> https://keepachangelog.com -->

# Mybatis-plus-sql-visualizer Changelog

## [Unreleased]

### Added
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

### Changed
- M13 动态条件简化 + 参数值类型推断（M12 微调，v1.7）：
  - 模型：删除 `EmptyValueRule` 枚举与 `ConditionToggle.emptyRule`/`ConditionView.emptyRule`（判空语义识别下线）；`ColumnInfo` 新增 `fieldType`（实体字段类型，`EntityResolver` 记录）；新增 `sql/ParamHint`（引号/LIKE 位置回填提示），`GeneratedSql.paramHints`/`PreviewResult.paramHints` 与 `unresolvedParams` 并行
  - 转换：`analyzeFlag` 删除判空语义识别（`isNotBlank`/`isNotNull` 等正则）与判空方法静态求值，`defaultEnabled` 简化为三态（字面量 true→勾选、false→不勾选、运行时布尔→勾选）；**复选框纯手动**——值空不再联动勾选态（M12 的 SKIP_ON_BLANK 空值自动取消勾选、SKIP_ON_NULL 空值拼 `''` 行为移除，空值一律保留 `?`）
  - 生成：渲染链线程化 table + hintSink，为每个 `?` 并行产出 `ParamHint`（String 列 quote=true、数值列 quote=false、未知 null；LIKE 占位符 quote=true + likePosition）；**SQL 文本输出与 M12 基线逐字节一致**（回填提示为附加信息）
  - 展示：`SqlPreviewPanel` 值输入框回填按 hint 渲染——LIKE 自动拼百分号（`'%值%'`/`'%值'`/`'值%'`）、String 列输入数字恒引号（`'123'`）、数值列恒原样（`123`）、类型未知走原数字启发式；`conditionTooltip` 与 bundle 清理 `panel.condition.skipNull`（详见《20260812-2210-动态条件简化与参数值类型推断-开发计划.md》）

### Added
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

### Removed
- M11 移除侧边工具栏面板（工具窗口，v1.5）：删除 `SqlToolWindow`/`SqlPreviewToolWindowFactory`/`SqlPreviewEditorTracker` 三个文件与 plugin.xml 的 `<toolWindow>`/`<editorFactoryListener>` 扩展；移除 `PluginSettings.displayMode`（DIALOG/TOOL_WINDOW/BOTH）与 Settings 页面「显示方式」下拉、bundle `settings.displayMode.*`/`toolwindow.*` 文案；展示收敛为**恒弹窗**（`SqlPreviewService.showResult` 统一走 `SqlPreviewDialog`），只保留右键菜单 / 快捷键 / gutter 图标三入口（详见《20260812-2034-移除侧边工具栏面板-开发计划.md》）

### Fixed
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
