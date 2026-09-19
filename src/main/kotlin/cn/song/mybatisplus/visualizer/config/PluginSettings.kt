package cn.song.mybatisplus.visualizer.config

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.RoamingType
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.util.messages.Topic
import cn.song.mybatisplus.visualizer.log.SqlListTypeColor

/**
 * 插件配置状态（T-M5-01，F-16 配置项持久化）。
 *
 * application-level 轻服务（@Service(Service.Level.APP)，跨项目共享），经 XmlSerializer 持久化
 * 到 `mp-sql-visualizer.xml`。修改后由 [SectionConfigurable.apply]（设置子页基类）写回即生效。
 *
 * T-M16.3（语言切换保存失效）：
 * 官方规范（IntelliJ Platform SDK——Services）要求**应用级轻服务 + PersistentStateComponent 必须
 * 禁用 roaming**（`roamingType = RoamingType.DISABLED`）——默认 RoamingType.DEFAULT 使状态进入
 * 设置同步（Settings Sync）管理通道，与轻服务持久化不兼容，导致「确定后重开/重启设置回滚」。
 */
@State(
    name = "MpSqlVisualizerSettings",
    storages = [Storage("mp-sql-visualizer.xml", roamingType = RoamingType.DISABLED)],
)
@Service(Service.Level.APP)
class PluginSettings : PersistentStateComponent<PluginSettings.State> {

    /**
     * 可持久化状态（XmlSerializer 序列化；默认值即出厂配置）。
     */
    @Suppress("DataClassShouldBeImmutable")
    data class State(
        /**
         * T-DialectAutoInfer：方言模式——true=智能推断（默认，跟随 agent 捕获连接 > IDE 数据源，
         * 未命中回退 MySQL）；false=按 [dialect] 手动指定。
         * 旧配置无此字段 → XmlSerializer 缺省 true（语义变化：从不动方言设置的老用户自动进入
         * 推断模式，CHANGELOG 已明示）。
         */
        var dialectAuto: Boolean = true,

        /** 数据库方言（对应 [cn.song.mybatisplus.visualizer.sql.DialectRegistry] 的名称）；
         * 仅 [dialectAuto]=false 时生效（手动指定值）。 */
        var dialect: String = DEFAULT_DIALECT,

        /** 是否替换参数（F-16 配置项；true 时 psi 层变量追踪内联，false 保留 `?`）。 */
        var replaceParams: Boolean = true,

        /** 是否格式化输出（true=多行缩进，false=压缩）。 */
        var formatSql: Boolean = true,

        /**
         * 空参数填充策略（T-PT-04）：参数值为空时的回填形态——[EMPTY_PARAM_FILL_NULL]（默认，
         * 渲染 `NULL`）或 [EMPTY_PARAM_FILL_EMPTY_STRING]（渲染 `''`）。
         * 消费点 = SqlPreviewPanel.renderParamValue 的空值分支，且仅对**带 mapper 签名类型信息**的
         * 参数行生效（无类型信息路径保留 `?` 占位提示，行为与升级前一致）。
         */
        var emptyParamFill: String = DEFAULT_EMPTY_PARAM_FILL,

        /**
         * 竖向排列 VALUES 行尾配对列名标注（T-VPairAnno）：true=显示（默认，T-VStack 以来行为不变），
         * false=仅竖排不追加标注注释（用户自带注释不受影响）。
         * 仅作用于预览面板竖向排列显示模式（[cn.song.mybatisplus.visualizer.ui.highlight.SqlFormatter.formatVertical]
         * 的唯一生产调用点 applyDisplayMode）。
         */
        var verticalPairAnnotation: Boolean = true,

        /**
         * 字段较多时自动折叠（T-AutoFold）：true = 各处 SQL 预览编辑器渲染后自动折叠超长字段列表
         * （SELECT/INSERT 列清单、VALUES 组、UPDATE SET），默认关——关闭时写入管线零折叠操作、
         * 行为逐字节不变；开启后的下一次程序性写入生效（与 formatSql 同构，不即时刷新已开窗体）。
         */
        var autoFoldEnabled: Boolean = false,

        /**
         * 自动折叠触发的顶层字段数阈值（T-AutoFold）：字段列表元素数 >= 该值且区域跨多行才折叠，
         * 默认 10。
         */
        var autoFoldFieldThreshold: Int = DEFAULT_AUTO_FOLD_FIELD_THRESHOLD,

        /** 代码左侧 gutter 预览 SQL 图标开关（M10；性能/视觉兜底，关闭后不再生成图标）。 */
        var showGutterIcon: Boolean = true,

        /**
         * JOIN 图渲染方式（T-Dgm-04）：true=IDE 原生图框架（Ultimate 的 Diagrams 插件，类图观感，
         * 缩放/布局/导出全框架托管）；false=经典自绘渲染器。
         * Community / Diagrams 插件缺席时无论此值均自动降级自绘。
         */
        var joinDiagramNative: Boolean = true,

        /** 插件界面语言（M14）：默认 `zh`（简体中文）；`en`=English；`AUTO`=跟随 IDE。 */
        var language: String = DEFAULT_LANGUAGE,

        /**
         * 逻辑删除注入（T-M15-07）：实体含 @TableLogic 字段时，查询/删除自动追加 `AND deleted = 0`
         * （对齐 MyBatis-Plus 全局 `logic-delete-field` 配置）；false 时保持纯 wrapper 语义。
         */
        var injectLogicDelete: Boolean = true,

        /**
         * 逻辑删除值（T-M31.18）：注入 `AND deleted = {值}` 的可配置值（默认 0；
         * 用户可按业务调整，如 1——对齐 MP `logic-delete-value` 配置语义）。
         */
        var logicDeleteValue: String = DEFAULT_LOGIC_DELETE_VALUE,

        /**
         * 多租户注入（TenantLineInnerInterceptor 模拟，T-TenantLine）：开启后 SELECT/UPDATE/DELETE 自动追加
         * `AND {tenantColumnName} = ?`、INSERT 列清单追加租户列（对齐 MP 多租户拦截器默认行为）；
         * false 时保持纯 wrapper 语义。
         * 默认关——避免破坏既有测试的 SQL 断言；用户可按需开启。
         */
        var injectTenantLine: Boolean = false,

        /**
         * 租户列名（T-TenantLine）：注入的租户列列名（默认 tenant_id；可按业务调整，如 org_id / shop_id——
         * 对齐 MP `TenantLineInnerInterceptor.column` 配置语义）。
         */
        var tenantColumnName: String = DEFAULT_TENANT_COLUMN_NAME,

        /**
         * 表前缀（T-M16.2-03）：统一加在所有表名**前**（如 `sys_` + device_info → `sys_device_info`）。
         * 全局语义——`@TableName` 显式表名同样被修饰（文档说明）；空字符串表示不修饰。
         */
        var tablePrefix: String = "",

        /**
         * 表后缀（T-M16.2-03）：统一加在所有表名**后**（如 device_info + `_log` → `device_info_log`）。
         * 全局语义——`@TableName` 显式表名同样被修饰（文档说明）；空字符串表示不修饰（"清除"即置空）。
         * T-M19.4：多值升级——`;` 分隔**按序拼接**（`_log;_bak` → `xxx_log_bak`）。
         */
        var tableSuffix: String = "",

        /**
         * 表名推断策略（T-M19.4）：无 `@TableName` 时实体类名 → 表名的推断方式。
         * [INFERENCE_SNAKE]（默认，对齐 MP）= 驼峰转下划线；[INFERENCE_CAMEL] = **纯保留驼峰**——
         * 仅首字母小写、**不去 Entity 后缀**（用户反馈：文案「保留驼峰」与行为一致，
         * BikeDeviceInfoEntity → bikeDeviceInfoEntity，BikeDeviceInfo → bikeDeviceInfo）。
         * 仅作用于**无注解推断**——`@TableName` 显式表名不受影响（M19.4-R1）。
         */
        var tableNameInference: String = INFERENCE_SNAKE,

        /**
         * 忽略字段名（T-M19.4）：`;` 分隔的 **Java 字段名**列表——命中字段不进 SELECT 全列展开、
         * 参数对照、补全列集合（`@TableField(exist=false)` 之外的自行排除手段；两者并存，注解优先）。
         */
        var ignoredFields: String = "",

        /**
         * 移除表名前缀（T-M19.4）：`;` 分隔，最终表名以哪个开头就移除哪个（首个匹配、大小写敏感）。
         * 作用于**最终表名**（含 `@TableName` 显式表名，M19.4-R1 全局语义）；空字符串不修饰。
         */
        var removeTablePrefix: String = "",

        /**
         * 移除表名后缀（T-M19.4）：`;` 分隔，最终表名以哪个结尾就移除哪个（首个匹配、大小写敏感）。
         * 作用于**最终表名**（含 `@TableName` 显式表名，M19.4-R1 全局语义）；空字符串不修饰。
         */
        var removeTableSuffix: String = "",

        /**
         * 抢先体验：启用 Gitee 更新通道（M28 改造）：勾选时 IDE 启动静默检查 Gitee 通道
         * （[update.StartupUpdateCheck]，含 `-beta.N` 内测版与正式版提前推送），
         * 仅在发现可用更新时通知；不勾选则完全跳过启动检查（设置页手动检查不受影响）。
         * 默认关——稳定版更新由 JetBrains Marketplace 推送，本通道仅服务抢先体验。
         * 也可通过 JetBrains Marketplace 获取正式版更新。
         */
        var autoCheckUpdate: Boolean = false,

        /**
         * Gitee 更新通道已忽略的版本号集合（`;` 分隔）；仅抑制这些版本的自动启动通知，
         * 手动在设置页检查更新时不受影响。为空字符串表示无忽略。
         */
        var ignoredUpdateVersions: String = "",

        /**
         * SQL 反向转换的默认 Wrapper 形态（T-M36.3）：[REVERSE_AUTO] 自动 /
         * [REVERSE_QUERY] 字符串列名 / [REVERSE_LAMBDA] Lambda / [REVERSE_MPJ_LAMBDA] MPJ。
         * 分发逻辑见 [cn.song.mybatisplus.visualizer.reverse.resolveWrapperFormat]。
         */
        var reverseWrapperFormat: String = REVERSE_AUTO,

        /**
         * 允许手动选择实体类（默认开）：自动推断（类名推断 + @TableName 反查）未命中或
         * 多表 JOIN 时，弹实体选择弹窗让用户从项目类中手动挑选（借助实体做列→字段映射）；
         * 关闭后保持纯自动推断——未命中直接回落字符串列名形态，不弹任何选择框。
         */
        var reverseManualEntitySelection: Boolean = true,

        // ---------- T-M31.14 SQL 日志配置（参考 MybatisCodeHelper-Pro「MyBatis Log Setting」） ----------

        /** 日志 SQL 前缀（Preparing 行定位关键字）。 */
        var logSqlPrefix: String = DEFAULT_LOG_SQL_PREFIX,

        /** 日志参数前缀（Parameters 行定位关键字）。 */
        var logParamPrefix: String = DEFAULT_LOG_PARAM_PREFIX,

        /** 混合前缀（true=关键字宽容匹配，同时兼容 `==>` 与原生格式；false=行首精确前缀匹配）。 */
        var logMixedPrefix: Boolean = true,

        /** 项目启动时自动开始监听（默认关——手动点「捕捉」）。 */
        var logAutoCaptureOnStart: Boolean = false,

        /** 用户显式停止自动启动监听（默认关）。重启后 logAutoCaptureOnStart 开启时此标志阻止自动启动；项目启动或手动点捕捉时清零。 */
        var logAutoCaptureSuppressed: Boolean = false,

        /** 关闭工具窗口时自动停止监听（默认关——关闭工具窗口不影响监听状态）。 */
        var logStopOnToolWindowClose: Boolean = false,

        /** 发现 SQL 时自动展开日志工具窗口（M37.3 默认开——竞品「每次手动开面板」是普遍抱怨；60s 防抖，show 不抢焦点）。 */
        var logAutoOpenOnCapture: Boolean = true,

        /** 列表条目显示捕获时间戳（默认开）。 */
        var logTimestampEnabled: Boolean = true,

        /** 时间戳格式（对齐参考插件默认）。 */
        var logTimestampFormat: String = DEFAULT_LOG_TIMESTAMP_FORMAT,

        /** 时间戳显示模式：log=日志时间（默认），capture=捕获时间。 */
        var logTimestampSource: String = DEFAULT_LOG_TIMESTAMP_SOURCE,

        /** 日志还原 SQL 格式化（多行缩进；预览编辑器展示用）。 */
        var logFormatSql: Boolean = true,

        /**
         * 字面量参数恢复（T-HistParam F4，默认开）：日志 SQL 无 `?` 占位符（裸 SQL 捕获 /
         * MyBatis `${}` / 上游日志已内联参数值）时，把单引号字符串字面量识别为可编辑参数行。
         * 作用于 [cn.song.mybatisplus.visualizer.log.toPreviewResult] 的统一识别通道。
         */
        var literalParamRecovery: Boolean = true,

        /** 过滤：还原 SQL 包含任一关键字（`;` 分隔）则丢弃。 */
        var logFilterKeywords: String = "",

        /** 去重窗口毫秒（相同 SQL 在该窗口内重复丢弃；默认 60000——T-M31.17 用户实测 1000ms 过短，重复查询仍显示）。 */
        var logDedupeWindowMs: Long = DEFAULT_LOG_DEDUPE_WINDOW_MS,

        /** 日志行解析最大长度（超长截断，防控制台大输出卡顿；默认 20000 对齐参考插件）。 */
        var logMaxParseLength: Int = DEFAULT_LOG_MAX_PARSE_LENGTH,

        /** SQL 日志列表最大条数（默认 200）：超出自动淘汰最早条目，防止日志过多 EDT 卡顿。 */
        var logMaxCount: Int = DEFAULT_LOG_MAX_COUNT,

        /**
         * 自动保存历史 SQL（F-43）：开启后 SQL 超出上限被淘汰时写入保存文件（而非直接丢弃）。
         * 默认关——保持现有「超出即丢」行为。
         */
        var logAutoSaveEnabled: Boolean = false,

        /**
         * 自动保存历史 SQL 的保存目录（F-43）：用户自定义保存位置。
         * 空字符串表示使用默认路径（.idea/sql-visualizer/sql-history/，F-44-4 随插件更名）。
         */
        var logAutoSavePath: String = "",

        /**
         * 自动保存历史 SQL 的文件名模板（F-43）：支持 $yyyy$ $MM$ $dd$ $HH$ $mm$ $ss$ 占位符。
         * 默认 "sql-history-$yyyy-MM-dd$.sql"。
         */
        var logAutoSaveFileNamePattern: String = DEFAULT_LOG_AUTO_SAVE_FILE_NAME_PATTERN,

        /**
         * 历史 SQL 文件保留上限（T-HistoryFileRetention）：保存目录下历史文件（按文件名基名归并
         * 滚动块）超过该数量时，在后台写批之后自动删除最旧文件，防止磁盘空间爆炸。
         * 0 或负数 = 不限制（全量保留）。默认 [DEFAULT_LOG_AUTO_SAVE_MAX_FILES]。
         */
        var logAutoSaveMaxFiles: Int = DEFAULT_LOG_AUTO_SAVE_MAX_FILES,

        // ---------- O-1 日志窗口差异化（执行耗时 / 慢查询） ----------

        /**
         * 日志耗时前缀（O-1 Phase B）：自定义耗时行的前缀关键字（如 `Time:` / `duration:`），
         * 用于从日志文本中解析执行耗时。空串表示不匹配自定义格式。
         * 默认 `Time:`（对齐常见 p6spy / MP TimeCount 格式）。
         */
        var logTimePrefix: String = DEFAULT_LOG_TIME_PREFIX,

        /**
         * 慢查询阈值（O-1 Phase C）：耗时 >= 该值（毫秒）判定为慢查询。
         * 默认 1000ms。
         */
        var slowQueryThresholdMs: Long = DEFAULT_SLOW_QUERY_THRESHOLD_MS,

        /**
         * 是否在 SQL 日志列表中显示耗时列（默认开启）。
         * false 时隐藏耗时，节省列表宽度。
         */
        var showDurationInLogList: Boolean = true,

        // ---------- T-CrudColor：SQL 列表 CRUD 类型颜色标记 ----------
        /**
         * CRUD 颜色标记形态：`bar`=左侧色条（默认）/ `background`=整行淡背景（选中态走描边）/
         * `off`=关闭。未知值按 `bar` 兜底（见 [cn.song.mybatisplus.visualizer.log.SqlListTypeColor.styleOf]）。
         * 默认值常量在 [cn.song.mybatisplus.visualizer.log.SqlListTypeColor]，State 侧只引用，避免双份字面量。
         */
        var crudColorStyle: String = CRUD_COLOR_STYLE_BAR,

        /** SELECT 标记色（`#RRGGBB`；空串/非法 → 默认色）。 */
        var crudColorSelect: String = CRUD_COLOR_DEFAULT_SELECT,

        /** INSERT 标记色（`#RRGGBB`）。 */
        var crudColorInsert: String = CRUD_COLOR_DEFAULT_INSERT,

        /** UPDATE 标记色（`#RRGGBB`）。 */
        var crudColorUpdate: String = CRUD_COLOR_DEFAULT_UPDATE,

        /** DELETE 标记色（`#RRGGBB`）。 */
        var crudColorDelete: String = CRUD_COLOR_DEFAULT_DELETE,

        /**
         * SQL 列表显示模式（T-ListMode）：SQL 日志与 SQL 历史列表条目中 SQL 行的展示形态
         * （枚举 [cn.song.mybatisplus.visualizer.log.SqlListDisplayMode] 的 name，读取走
         * [sqlListDisplayMode] 容错）。默认关键字换行。
         */
        var logListSqlDisplayMode: String = DEFAULT_LOG_LIST_DISPLAY_MODE,

        /**
         * 参数历史缓存（P-05）：JSON 字符串，key = 表名+SQL 指纹，value = Map<source, value>。
         * 持久化到 mp-sql-visualizer.xml，IDE 重启后恢复。
         * 空字符串表示无历史。
         */
        var paramHistoryJson: String = "",

        // ---------- Hibernate/JPA 日志捕获 ----------

        /** 启用 Hibernate/JPA 日志解析（默认开）。 */
        var logHibernateEnabled: Boolean = true,

        /** Hibernate SQL 行前缀（默认 `Hibernate:`）。 */
        var logHibernateSqlPrefix: String = DEFAULT_LOG_HIBERNATE_SQL_PREFIX,

        /** Hibernate 参数行前缀（默认 `binding parameter`）。 */
        var logHibernateParamPrefix: String = DEFAULT_LOG_HIBERNATE_PARAM_PREFIX,

        // ---------- Spring JdbcTemplate 日志捕获（M47） ----------

        /** 启用 Spring JdbcTemplate 日志解析（默认开）。 */
        var logJdbcEnabled: Boolean = true,

        // ---------- JDBC 模板 SQL 字符串预览 ----------

        /** 启用 JdbcTemplate / NamedParameterJdbcTemplate SQL 字符串预览（默认开）。 */
        var jdbcSqlPreviewEnabled: Boolean = true,

        // ---------- T-ExecTimeoutGuard：执行超时护栏 ----------

        /**
         * 执行超时（秒，每语句基础值）：MCP explain 直连链 setQueryTimeout 驱动级建议值；
         * 多语句脚本预算按语句数线性放大（封顶 ×8，见 [cn.song.mybatisplus.visualizer.execute.
         * ExecutionTimeoutGuard]）。0=不限。默认 30。
         */
        var executionTimeoutSeconds: Int = DEFAULT_EXECUTION_TIMEOUT_SECONDS,

        // ---------- F-6 拦截器模拟增强（TenantLine / DynamicTableName / DataPermission） ----------

        /**
         * 租户列默认值（F-6 Phase A）：UPDATE SET 追加 `tenantColumn = {值}` 时的字面量（默认 "1"）；
         * INSERT 追加租户列时同样作为面板回填默认值。对齐 MP 多租户运行时租户 ID 取值语义。
         */
        var tenantColumnDefault: String = DEFAULT_TENANT_COLUMN_DEFAULT,

        /**
         * INSERT 也追加租户列（F-6 Phase A）：开启后 INSERT 列清单追加租户列（对齐 MP
         * TenantLineInnerInterceptor INSERT 含租户列语义）；关闭后 INSERT 不追加。
         * 默认开——与 injectTenantLine 开关独立（WHERE 注入 / INSERT 追加可分别控制）。
         */
        var injectTenantOnInsert: Boolean = true,

        /**
         * 动态表名映射规则（F-6 Phase B）：预览时按规则替换表名（正则 → 替换模板）。
         * 空列表表示不替换。规则按列表顺序逐条应用（首个匹配即停）。
         */
        var dynamicTableNameRules: List<TableNameRule> = emptyList(),

        /**
         * 数据权限占位提示（F-6 Phase C）：开启后 SELECT/UPDATE/DELETE 预览时在警告区显示
         * DataPermissionInterceptor 运行时差异提示；并提供占位条件输入框供用户手动填写模拟条件。
         * 默认关（低频项，需要时手动开启）。
         */
        var dataPermissionHint: Boolean = false,

        /**
         * 数据权限模拟条件（F-6 Phase C）：用户手动填写的 WHERE 片段（如 `dept_id IN (1,2,3)`），
         * 开启 dataPermissionHint 后追加到 SELECT/UPDATE/DELETE 语句末尾。空字符串表示无模拟条件。
         */
        var dataPermissionMockCondition: String = "",

        // ---------- F-7 SQL 历史记录 ----------

        /**
         * SQL 历史记录上限（F-7）：非收藏条目的最大数量（默认 200）。超出自动淘汰最旧非收藏条目。
         * 收藏条目无数量上限。
         */
        var historyMaxSize: Int = DEFAULT_HISTORY_MAX_SIZE,

        // ---------- F-44-7 SqlSourceInterceptor 自动注入 ----------
        // T-SettingsTruth P1：原 var autoInjectSqlSourceInterceptor 字段已删除——该开关为纯动作
        // （勾选立即注入一次），不保存状态，终态恒未勾选；旧 XML 残留属性由 XmlSerializer 自动忽略。

        // ---------- T-ErrReport 错误一键上报官网需求池 ----------

        /**
         * 需求池报告昵称（选填，报告对话框填写后记忆，下次带出；空 = 匿名提交）。
         */
        var reportSubmitterName: String = "",

        /**
         * 需求池报告邮箱（选填，仅用于官方回复推送通知，不公开）。
         */
        var reportSubmitterEmail: String = "",

        // ---------- T-McpToolset2 MCP 工具门控 ----------

        /**
         * 插件 MCP 功能总开关（T-MTS2-00）：false = 全部工具在平台 MCP Server 不可见
         * （McpToolsetSupport.isEnabled 恒 false——平台 getEnabledToolsets 每次
         * getTools 重新求值，262 mcpserver.jar 字节码实证，切换即时生效无需重启）。
         */
        var mcpToolsEnabled: Boolean = true,

        /**
         * 工具级禁用清单（T-MTS2-00）：工具名（sqlv_render_sql 等）→ 不出现在工具面。
         * 清单外默认启用——新增工具向后兼容（用户不配置即全部可用）。
         */
        var mcpDisabledTools: MutableList<String> = mutableListOf(),

        // ---------- M56 增强捕获（agent 字节码通道；对用户不出现 agent 字样） ----------

        /**
         * M56 T-M56-08：增强捕获开关（**默认开**——2026-09-16 用户定案，增强捕获为主通道）。
         * 开启且经注入确认后，agent 接管捕获源（参数实参直取/精确耗时/影响行数/报错同列/
         * 精确跳转）；attach 不可用/连接丢失自动回落现有控制台捕获，零劣化。
         * 注：本项默认开而 [enhancedCaptureConfirmed] 默认 false——首次挂载（项目运行）时弹一次
         * 注入确认，同意后才真正 attach（知情同意不放宽，见 AgentManager 的首次挂载确认请求）。
         */
        var enhancedCaptureEnabled: Boolean = true,

        /**
         * M56 T-M56-08：注入确认记忆（确认后置 true，不再重复弹）。
         * 关闭增强不重置本值（重开不再弹）；确认对话框含采集范围/数据留本机/JEP 451 告警预告。
         * 默认 false：配合 enabled 默认开，首次运行项目时弹一次（设置页手动勾选时亦可确认）。
         */
        var enhancedCaptureConfirmed: Boolean = false,

        /**
         * T-PS4-01：注入确认「拒绝时刻」毫秒时间戳（持久化，随本 XML 跨 IDE 重启存活）；0 = 无拒绝记忆。
         * 拒绝后进入 24h 冷却（窗口在 AgentManager.DEFAULT_REJECT_COOLDOWN_MS）：冷却期内不再重复弹
         * 确认框（冷却只作用于弹窗频率、不改变同意门槛——保守口径，冷却过期后再弹一次）。
         * 设置页「关闭 → 再开启」增强捕获时由 AgentManager 的 SETTINGS_CHANGE_TOPIC 监听清零重置，
         * 保留用户改主意的入口；增强捕获总开关关闭时本值无意义（关闭增强 > 冷却）。
         */
        var enhancedCaptureRejectedAtMs: Long = 0L,

        /**
         * M56 T-M56-08：脱敏开关（**默认关**——2026-09-16 用户定案，默认采集真实参数值）。
         * true 时对疑似敏感形态打码；脱敏在 agent 采集层执行，数据不出应用 JVM 前已处理。
         */
        var enhancedCaptureDesensitize: Boolean = false,

        // ---------- T-UxOnboard T-UX-01 首用锚点引导 ----------

        /**
         * 首用锚点引导通知开关（默认开）：项目内首次打开含可预览 SQL（Wrapper 链/XML mapper/
         * 注解 SQL 任一）的文件时，经预览组弹一次「Alt+Shift+Q 预览」指路通知；关闭后全程静默。
         */
        var onboardingAnchorTipEnabled: Boolean = true,

        /**
         * 首用锚点引导通知已展示（一次性持久化记忆）：展示过一次即置 true，
         * 重开项目/重启 IDE 不再出现——用户全生命期至多一条。
         */
        var onboardingAnchorTipShown: Boolean = false,

        /**
         * T-SourceJumpFunnel P1-3：源码捕获帧过滤前缀（用户登记的自研框架包名，一行一个，
         * 如 `com.corp.engine.`）。agent 侧栈帧判定并入（CONFIG 热下发），IDE 侧候选解析同步过滤。
         * 空 = 未登记。
         */
        var sourceFrameSkipPrefixes: String = "",
    )

    private var state = State()

    override fun getState(): State = state

    override fun loadState(state: State) {
        this.state = state
    }

    /** 当前 SQL 列表显示模式（T-ListMode；旧配置残留非法值时回退默认关键字换行）。 */
    fun sqlListDisplayMode(): cn.song.mybatisplus.visualizer.log.SqlListDisplayMode =
        runCatching { cn.song.mybatisplus.visualizer.log.SqlListDisplayMode.valueOf(state.logListSqlDisplayMode) }
            .getOrDefault(cn.song.mybatisplus.visualizer.log.SqlListDisplayMode.KEYWORD_WRAP)

    companion object {
        const val DEFAULT_DIALECT = "MySQL"

        /** SQL 列表显示模式默认值（T-ListMode，关键字换行）。 */
        const val DEFAULT_LOG_LIST_DISPLAY_MODE = "KEYWORD_WRAP"

        /** 设置变更通知 Topic（订阅方据此即时刷新 UI）。 */
        @JvmField
        val SETTINGS_CHANGE_TOPIC: Topic<SettingsChangeListener> = Topic.create(
            "MpSqlVisualizerSettingsChange",
            SettingsChangeListener::class.java,
        )

        /** 默认界面语言（M14，用户要求默认中文）。 */
        const val DEFAULT_LANGUAGE = "zh"

        // ---------- T-PT-04：空参数填充策略 ----------

        /** 空参数填充策略：NULL 字面量（默认）。 */
        const val EMPTY_PARAM_FILL_NULL = "NULL"

        /** 空参数填充策略：空字符串字面量 `''`。 */
        const val EMPTY_PARAM_FILL_EMPTY_STRING = "EMPTY_STRING"

        /** 空参数填充策略默认值（NULL 字面量，贴近 MyBatis 绑定 null 的真实效果）。 */
        const val DEFAULT_EMPTY_PARAM_FILL = EMPTY_PARAM_FILL_NULL

        /** 表名推断策略：驼峰转下划线（默认，对齐 MP，`BikeDeviceInfoEntity` → `bike_device_info`）。 */
        const val INFERENCE_SNAKE = "SNAKE"

        /** 表名推断策略：纯保留驼峰——仅首字母小写、不去 Entity 后缀（`BikeDeviceInfoEntity` → `bikeDeviceInfoEntity`）。 */
        const val INFERENCE_CAMEL = "CAMEL"

        /** 反向转换 Wrapper 形态：自动（有实体 → Lambda，无实体 → 字符串）。 */
        const val REVERSE_AUTO = "AUTO"

        /** 反向转换 Wrapper 形态：恒字符串列名 QueryWrapper。 */
        const val REVERSE_QUERY = "QUERY"

        /** 反向转换 Wrapper 形态：LambdaQueryWrapper（无实体回落字符串）。 */
        const val REVERSE_LAMBDA = "LAMBDA"

        /** 反向转换 Wrapper 形态：Wrappers 静态工厂（有实体 lambdaQuery / 无实体 query，T-M36.6）。 */
        const val REVERSE_WRAPPERS = "WRAPPERS"

        /** 反向转换 Wrapper 形态：MPJLambdaWrapper（无实体回落字符串）。 */
        const val REVERSE_MPJ_LAMBDA = "MPJ_LAMBDA"

        // ---------- SQL 日志默认值（T-M31.14） ----------
        const val DEFAULT_LOG_SQL_PREFIX = "Preparing:"
        const val DEFAULT_LOG_PARAM_PREFIX = "Parameters:"
        const val DEFAULT_LOG_TIMESTAMP_FORMAT = "yyyy-MM-dd HH:mm:ss"
        const val DEFAULT_LOG_TIMESTAMP_SOURCE = "log"
        const val LOG_TIMESTAMP_SOURCE_LOG = "log"
        const val LOG_TIMESTAMP_SOURCE_CAPTURE = "capture"
        const val DEFAULT_LOG_DEDUPE_WINDOW_MS = 60000L
        const val DEFAULT_LOG_MAX_PARSE_LENGTH = 20000
        const val DEFAULT_LOG_MAX_COUNT = 200

        /** 自动保存历史 SQL 默认文件名模板（F-43）。 */
        const val DEFAULT_LOG_AUTO_SAVE_FILE_NAME_PATTERN = "sql-history-\$yyyy-MM-dd\$.sql"

        /** 历史 SQL 文件保留上限默认值（T-HistoryFileRetention）：默认留最近 30 个文件（按日模板≈约一个月），防磁盘膨胀；0/负 = 全量保留。 */
        const val DEFAULT_LOG_AUTO_SAVE_MAX_FILES = 30

        // ---------- O-1 日志窗口差异化默认值 ----------
        const val DEFAULT_LOG_TIME_PREFIX = "Time:"
        const val DEFAULT_SLOW_QUERY_THRESHOLD_MS = 1000L

        // ---------- Hibernate/JPA 日志默认值 ----------
        const val DEFAULT_LOG_HIBERNATE_SQL_PREFIX = "Hibernate:"
        const val DEFAULT_LOG_HIBERNATE_PARAM_PREFIX = "binding parameter"
        const val DEFAULT_LOGIC_DELETE_VALUE = "0"

        /** 默认租户列名（T-TenantLine，对齐 MP TenantLineInnerInterceptor 默认列名）。 */
        const val DEFAULT_TENANT_COLUMN_NAME = "tenant_id"

        /** 默认租户列值（F-6 Phase A）：UPDATE SET / INSERT 追加租户列时的字面量默认值。 */
        const val DEFAULT_TENANT_COLUMN_DEFAULT = "1"

        /** 默认 SQL 历史记录上限（F-7，非收藏条目）。 */
        const val DEFAULT_HISTORY_MAX_SIZE = 200

        /** 默认执行超时（秒；0=不限）——单一定义点在 ExecutionTimeoutGuard，State 侧引用避免双份字面量。 */
        const val DEFAULT_EXECUTION_TIMEOUT_SECONDS =
            cn.song.mybatisplus.visualizer.execute.ExecutionTimeoutGuard.DEFAULT_TIMEOUT_SECONDS

        // ---------- T-CrudColor：SQL 列表 CRUD 类型颜色标记默认值 ----------
        // 单一真源在 SqlListTypeColor（语义与其渲染同处一处），State 侧只引用，避免双份字面量漂移。
        /** 默认标记形态：左侧色条。 */
        const val CRUD_COLOR_STYLE_BAR = SqlListTypeColor.STYLE_BAR

        /** 备选标记形态：整行淡背景（选中态走描边）。 */
        const val CRUD_COLOR_STYLE_BACKGROUND = SqlListTypeColor.STYLE_BACKGROUND

        /** 关闭标记。 */
        const val CRUD_COLOR_STYLE_OFF = SqlListTypeColor.STYLE_OFF

        const val CRUD_COLOR_DEFAULT_SELECT = SqlListTypeColor.DEFAULT_SELECT
        const val CRUD_COLOR_DEFAULT_INSERT = SqlListTypeColor.DEFAULT_INSERT
        const val CRUD_COLOR_DEFAULT_UPDATE = SqlListTypeColor.DEFAULT_UPDATE
        const val CRUD_COLOR_DEFAULT_DELETE = SqlListTypeColor.DEFAULT_DELETE

        /** 自动折叠触发的顶层字段数阈值（T-AutoFold）。 */
        const val DEFAULT_AUTO_FOLD_FIELD_THRESHOLD = 10

        /**
         * 获取全局配置实例。
         */
        @JvmStatic
        fun getInstance(): PluginSettings =
            ApplicationManager.getApplication().getService(PluginSettings::class.java)

        /**
         * 序列化为 JSON 字符串（O-5：设置导入导出）；委托 [SettingsIO.exportToJson]。
         */
        fun toJson(): String = SettingsIO.exportToJson(getInstance().state)

        /**
         * 从 JSON 字符串导入并覆盖当前全局配置（O-5：设置导入导出）；委托 [SettingsIO.importFromJson]。
         *
         * 以当前状态为底合并（T-Fix-SettingsScope）：文件只承载 [SettingsIO.exportToJson] 的
         * 21 个可覆盖项，State 其余 56 个字段（参数历史 / 增强捕获授权记忆 / 反馈身份 /
         * 日志自动保存目录 / 全局项等）不在文件里，必须保留现值而非回落出厂默认——
         * 全局项（language 等）由此自然保留，无需再单独抄回。
         *
         * @throws SettingsIO.ImportException JSON 格式错误或版本不兼容
         */
        fun fromJson(json: String) {
            val instance = getInstance()
            instance.loadState(SettingsIO.importFromJson(json, instance.state.copy()))
        }
    }
}

/** 设置变更监听器（订阅方据此即时刷新 UI）。 */
fun interface SettingsChangeListener {
    fun settingsChanged()
}

/**
 * 动态表名映射规则（F-6 Phase B）：预览时按正则替换表名。
 *
 * @param pattern     正则表达式（匹配原始表名或带前缀/后缀的表名）
 * @param replacement 替换模板（支持 `$1`/`$2` 引用正则捕获组，`$tableName` 引用完整原始表名）
 * @param enabled     是否启用（false 时跳过该规则）
 */
data class TableNameRule(
    val pattern: String,
    val replacement: String,
    val enabled: Boolean = true,
)
