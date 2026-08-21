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
         * 启动时自动检查更新（M28）：勾选时 IDE 启动静默检查（[update.StartupUpdateCheck]），
         * 仅在发现新版本时通知；不勾选则完全跳过启动检查（设置页手动检查不受影响）。
         * 默认开（用户拍板）。
         */
        var autoCheckUpdate: Boolean = true,

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
        const val DEFAULT_LOG_FREQUENCY_THRESHOLD = 3

        // ---------- Hibernate/JPA 日志默认值 ----------
        const val DEFAULT_LOG_HIBERNATE_SQL_PREFIX = "Hibernate:"
        const val DEFAULT_LOG_HIBERNATE_PARAM_PREFIX = "binding parameter"
        const val DEFAULT_LOGIC_DELETE_VALUE = "0"

        /** 默认租户列名（T-TenantLine，对齐 MP TenantLineInnerInterceptor 默认列名）。 */
        const val DEFAULT_TENANT_COLUMN_NAME = "tenant_id"

        /**
         * 获取全局配置实例。
         */
        @JvmStatic
        fun getInstance(): PluginSettings =
            ApplicationManager.getApplication().getService(PluginSettings::class.java)
    }
}

/** 设置变更监听器（订阅方据此即时刷新 UI）。 */
fun interface SettingsChangeListener {
    fun settingsChanged()
}
