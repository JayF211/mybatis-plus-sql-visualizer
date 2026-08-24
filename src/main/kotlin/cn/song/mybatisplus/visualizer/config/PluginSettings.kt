package cn.song.mybatisplus.visualizer.config

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.RoamingType
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.util.messages.Topic

/**
 * 插件配置状态（T-M5-01，F-16 配置项持久化）。
 *
 * application-level 轻服务（@Service(Service.Level.APP)，跨项目共享），经 XmlSerializer 持久化
 * 到 `mp-sql-visualizer.xml`。修改后由 [PluginSettingsConfigurable.apply] 写回即生效。
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
        /** 数据库方言（对应 [cn.song.mybatisplus.visualizer.sql.DialectRegistry] 的名称）。 */
        var dialect: String = DEFAULT_DIALECT,

        /** 是否替换参数（F-16 配置项；true 时 psi 层变量追踪内联，false 保留 `?`）。 */
        var replaceParams: Boolean = true,

        /** 是否格式化输出（true=多行缩进，false=单行）。 */
        var formatSql: Boolean = true,

        /** 代码左侧 gutter 预览 SQL 图标开关（M10；性能/视觉兜底，关闭后不再生成图标）。 */
        var showGutterIcon: Boolean = true,

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

        /** 发现 SQL 时自动打开日志工具窗口（默认关）。 */
        var logAutoOpenOnCapture: Boolean = false,

        /** 列表条目显示捕获时间戳（默认开）。 */
        var logTimestampEnabled: Boolean = true,

        /** 时间戳格式（对齐参考插件默认）。 */
        var logTimestampFormat: String = DEFAULT_LOG_TIMESTAMP_FORMAT,

        /** 日志还原 SQL 格式化（多行缩进；预览编辑器展示用）。 */
        var logFormatSql: Boolean = true,

        /** 过滤：还原 SQL 包含任一关键字（`;` 分隔）则丢弃。 */
        var logFilterKeywords: String = "",

        /** 去重窗口毫秒（相同 SQL 在该窗口内重复丢弃；默认 60000——T-M31.17 用户实测 1000ms 过短，重复查询仍显示）。 */
        var logDedupeWindowMs: Long = DEFAULT_LOG_DEDUPE_WINDOW_MS,

        /** 日志行解析最大长度（超长截断，防控制台大输出卡顿；默认 20000 对齐参考插件）。 */
        var logMaxParseLength: Int = DEFAULT_LOG_MAX_PARSE_LENGTH,

        /** SQL 日志列表最大条数（默认 200）：超出自动淘汰最早条目，防止日志过多 EDT 卡顿。 */
        var logMaxCount: Int = DEFAULT_LOG_MAX_COUNT,

        // ---------- P-06 日志智能分组 / 高频折叠 ----------

        /**
         * 筛选条显隐开关（P-06）：开启后 SQL 日志面板顶部显示类型筛选条
         * （全部/SELECT/INSERT/UPDATE/DELETE + 高频折叠 + 耗时排序）。
         * 默认关（低频项，需要时手动开启）。
         */
        var logFilterBarEnabled: Boolean = false,

        /**
         * 日志高频折叠阈值（P-06）：同一 SQL 模板出现次数 >= 该值时启用折叠。
         * 默认 3。
         */
        var logFrequencyThreshold: Int = DEFAULT_LOG_FREQUENCY_THRESHOLD,

        /**
         * 日志高频折叠开关（P-06）：启用后高频 SQL 模板折叠显示。
         * 默认关（用户手动启用）。
         */
        var logFrequencyFoldEnabled: Boolean = false,

        /**
         * 日志耗时排序开关（P-06）：启用后按耗时降序排序。
         * 默认关。
         */
        var logDurationSortEnabled: Boolean = false,

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
    )

    private var state = State()

    override fun getState(): State = state

    override fun loadState(state: State) {
        this.state = state
    }

    companion object {
        const val DEFAULT_DIALECT = "MySQL"

        /** 设置变更通知 Topic（订阅方据此即时刷新 UI）。 */
        @JvmField
        val SETTINGS_CHANGE_TOPIC: Topic<SettingsChangeListener> = Topic.create(
            "MpSqlVisualizerSettingsChange",
            SettingsChangeListener::class.java,
        )

        /** 默认界面语言（M14，用户要求默认中文）。 */
        const val DEFAULT_LANGUAGE = "zh"

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
        const val DEFAULT_LOG_DEDUPE_WINDOW_MS = 60000L
        const val DEFAULT_LOG_MAX_PARSE_LENGTH = 20000
        const val DEFAULT_LOG_MAX_COUNT = 200
        const val DEFAULT_LOG_FREQUENCY_THRESHOLD = 3

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
         * @throws SettingsIO.ImportException JSON 格式错误或版本不兼容
         */
        fun fromJson(json: String) {
            val imported = SettingsIO.importFromJson(json)
            val instance = getInstance()
            // 保留全局项（language 等），仅覆盖可覆盖项
            imported.language = instance.state.language
            instance.loadState(imported)
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
