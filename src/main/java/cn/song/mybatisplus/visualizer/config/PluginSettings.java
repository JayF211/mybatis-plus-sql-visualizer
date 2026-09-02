package cn.song.mybatisplus.visualizer.config;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.PersistentStateComponent;
import com.intellij.openapi.components.RoamingType;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.components.State;
import com.intellij.openapi.components.Storage;
import com.intellij.util.messages.Topic;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * 插件配置状态（T-M5-01，F-16 配置项持久化）。
 *
 * application-level 轻服务（@Service(Service.Level.APP)，跨项目共享），经 XmlSerializer 持久化
 * 到 `mp-sql-visualizer.xml`。修改后由 PluginSettingsConfigurable.apply 写回即生效。
 *
 * T-M16.3（语言切换保存失效）：
 * 官方规范（IntelliJ Platform SDK——Services）要求**应用级轻服务 + PersistentStateComponent 必须
 * 禁用 roaming**（`roamingType = RoamingType.DISABLED`）——默认 RoamingType.DEFAULT 使状态进入
 * 设置同步（Settings Sync）管理通道，与轻服务持久化不兼容，导致「确定后重开/重启设置回滚」。
 */
@State(
        name = "MpSqlVisualizerSettings",
        storages = @Storage(value = "mp-sql-visualizer.xml", roamingType = RoamingType.DISABLED)
)
@Service(Service.Level.APP)
public final class PluginSettings implements PersistentStateComponent<PluginSettings.State> {

    private State state = new State();

    @NotNull
    @Override
    public State getState() {
        return state;
    }

    @Override
    public void loadState(State state) {
        this.state = state;
    }

    /** 获取全局配置实例。 */
    @NotNull
    public static PluginSettings getInstance() {
        return ApplicationManager.getApplication().getService(PluginSettings.class);
    }

    /** 序列化为 JSON 字符串（O-5：设置导入导出）；委托 SettingsIO.exportToJson。 */
    public static String toJson() {
        return SettingsIO.exportToJson(getInstance().getState());
    }

    /**
     * 从 JSON 字符串导入并覆盖当前全局配置（O-5：设置导入导出）；委托 SettingsIO.importFromJson。
     *
     * @throws SettingsIO.ImportException JSON 格式错误或版本不兼容
     */
    public static void fromJson(String json) throws SettingsIO.ImportException {
        State imported = SettingsIO.importFromJson(json);
        PluginSettings instance = getInstance();
        // 保留全局项（language 等），仅覆盖可覆盖项
        imported.setLanguage(instance.getState().getLanguage());
        instance.loadState(imported);
    }

    /**
     * 可持久化状态（XmlSerializer 序列化；默认值即出厂配置）。
     */
    public static final class State {

        private String dialect = DEFAULT_DIALECT;
        private boolean replaceParams = true;
        private boolean formatSql = true;
        private boolean showGutterIcon = true;
        private String language = DEFAULT_LANGUAGE;
        private boolean injectLogicDelete = true;
        private String logicDeleteValue = DEFAULT_LOGIC_DELETE_VALUE;
        private boolean injectTenantLine = false;
        private String tenantColumnName = DEFAULT_TENANT_COLUMN_NAME;
        private String tenantColumnDefault = DEFAULT_TENANT_COLUMN_DEFAULT;
        private boolean injectTenantOnInsert = true;
        private String tablePrefix = "";
        private String tableSuffix = "";
        private String tableNameInference = INFERENCE_SNAKE;
        private String ignoredFields = "";
        private String removeTablePrefix = "";
        private String removeTableSuffix = "";
        private boolean autoCheckUpdate = false;
        private String ignoredUpdateVersions = "";
        private String reverseWrapperFormat = REVERSE_AUTO;
        private boolean reverseManualEntitySelection = true;
        private String logSqlPrefix = DEFAULT_LOG_SQL_PREFIX;
        private String logParamPrefix = DEFAULT_LOG_PARAM_PREFIX;
        private boolean logMixedPrefix = true;
        private boolean logAutoCaptureOnStart = false;
        private boolean logAutoCaptureSuppressed = false;
        private boolean logStopOnToolWindowClose = false;
        private boolean logAutoOpenOnCapture = false;
        private boolean logTimestampEnabled = true;
        private String logTimestampFormat = DEFAULT_LOG_TIMESTAMP_FORMAT;
        private boolean logFormatSql = true;
        private String logFilterKeywords = "";
        private long logDedupeWindowMs = DEFAULT_LOG_DEDUPE_WINDOW_MS;
        private int logMaxParseLength = DEFAULT_LOG_MAX_PARSE_LENGTH;
        private int logMaxCount = DEFAULT_LOG_MAX_COUNT;
        private boolean logAutoSaveEnabled = false;
        private String logAutoSavePath = "";
        private String logAutoSaveFileNamePattern = DEFAULT_LOG_AUTO_SAVE_FILE_NAME_PATTERN;
        private String logTimePrefix = DEFAULT_LOG_TIME_PREFIX;
        private long slowQueryThresholdMs = DEFAULT_SLOW_QUERY_THRESHOLD_MS;
        /**
         * 是否在 SQL 日志列表中显示耗时列（默认开启）。
         * false 时隐藏耗时，节省列表宽度。
         */
        private boolean showDurationInLogList = true;
        private String paramHistoryJson = "";
        private boolean logHibernateEnabled = true;
        private String logHibernateSqlPrefix = DEFAULT_LOG_HIBERNATE_SQL_PREFIX;
        private String logHibernateParamPrefix = DEFAULT_LOG_HIBERNATE_PARAM_PREFIX;
        private boolean logJdbcEnabled = true;
        private List<TableNameRule> dynamicTableNameRules = new ArrayList<>();
        private boolean dataPermissionHint = false;
        private String dataPermissionMockCondition = "";
        private int historyMaxSize = DEFAULT_HISTORY_MAX_SIZE;
        private Set<String> collapsedGroups = new HashSet<>();

        public String getDialect() { return dialect; }
        public void setDialect(String dialect) { this.dialect = dialect; }

        public boolean getReplaceParams() { return replaceParams; }
        public void setReplaceParams(boolean replaceParams) { this.replaceParams = replaceParams; }

        public boolean getFormatSql() { return formatSql; }
        public void setFormatSql(boolean formatSql) { this.formatSql = formatSql; }

        public boolean getShowGutterIcon() { return showGutterIcon; }
        public void setShowGutterIcon(boolean showGutterIcon) { this.showGutterIcon = showGutterIcon; }

        public String getLanguage() { return language; }
        public void setLanguage(String language) { this.language = language; }

        public boolean getInjectLogicDelete() { return injectLogicDelete; }
        public void setInjectLogicDelete(boolean injectLogicDelete) { this.injectLogicDelete = injectLogicDelete; }

        public String getLogicDeleteValue() { return logicDeleteValue; }
        public void setLogicDeleteValue(String logicDeleteValue) { this.logicDeleteValue = logicDeleteValue; }

        public boolean getInjectTenantLine() { return injectTenantLine; }
        public void setInjectTenantLine(boolean injectTenantLine) { this.injectTenantLine = injectTenantLine; }

        public String getTenantColumnName() { return tenantColumnName; }
        public void setTenantColumnName(String tenantColumnName) { this.tenantColumnName = tenantColumnName; }

        public String getTenantColumnDefault() { return tenantColumnDefault; }
        public void setTenantColumnDefault(String tenantColumnDefault) { this.tenantColumnDefault = tenantColumnDefault; }

        public boolean getInjectTenantOnInsert() { return injectTenantOnInsert; }
        public void setInjectTenantOnInsert(boolean injectTenantOnInsert) { this.injectTenantOnInsert = injectTenantOnInsert; }

        public String getTablePrefix() { return tablePrefix; }
        public void setTablePrefix(String tablePrefix) { this.tablePrefix = tablePrefix; }

        public String getTableSuffix() { return tableSuffix; }
        public void setTableSuffix(String tableSuffix) { this.tableSuffix = tableSuffix; }

        public String getTableNameInference() { return tableNameInference; }
        public void setTableNameInference(String tableNameInference) { this.tableNameInference = tableNameInference; }

        public String getIgnoredFields() { return ignoredFields; }
        public void setIgnoredFields(String ignoredFields) { this.ignoredFields = ignoredFields; }

        public String getRemoveTablePrefix() { return removeTablePrefix; }
        public void setRemoveTablePrefix(String removeTablePrefix) { this.removeTablePrefix = removeTablePrefix; }

        public String getRemoveTableSuffix() { return removeTableSuffix; }
        public void setRemoveTableSuffix(String removeTableSuffix) { this.removeTableSuffix = removeTableSuffix; }

        public boolean getAutoCheckUpdate() { return autoCheckUpdate; }
        public void setAutoCheckUpdate(boolean autoCheckUpdate) { this.autoCheckUpdate = autoCheckUpdate; }

        public String getIgnoredUpdateVersions() { return ignoredUpdateVersions; }
        public void setIgnoredUpdateVersions(String ignoredUpdateVersions) { this.ignoredUpdateVersions = ignoredUpdateVersions; }

        public String getReverseWrapperFormat() { return reverseWrapperFormat; }
        public void setReverseWrapperFormat(String reverseWrapperFormat) { this.reverseWrapperFormat = reverseWrapperFormat; }

        public boolean getReverseManualEntitySelection() { return reverseManualEntitySelection; }
        public void setReverseManualEntitySelection(boolean reverseManualEntitySelection) { this.reverseManualEntitySelection = reverseManualEntitySelection; }

        public String getLogSqlPrefix() { return logSqlPrefix; }
        public void setLogSqlPrefix(String logSqlPrefix) { this.logSqlPrefix = logSqlPrefix; }

        public String getLogParamPrefix() { return logParamPrefix; }
        public void setLogParamPrefix(String logParamPrefix) { this.logParamPrefix = logParamPrefix; }

        public boolean getLogMixedPrefix() { return logMixedPrefix; }
        public void setLogMixedPrefix(boolean logMixedPrefix) { this.logMixedPrefix = logMixedPrefix; }

        public boolean getLogAutoCaptureOnStart() { return logAutoCaptureOnStart; }
        public void setLogAutoCaptureOnStart(boolean logAutoCaptureOnStart) { this.logAutoCaptureOnStart = logAutoCaptureOnStart; }

        public boolean getLogAutoCaptureSuppressed() { return logAutoCaptureSuppressed; }
        public void setLogAutoCaptureSuppressed(boolean logAutoCaptureSuppressed) { this.logAutoCaptureSuppressed = logAutoCaptureSuppressed; }

        public boolean getLogStopOnToolWindowClose() { return logStopOnToolWindowClose; }
        public void setLogStopOnToolWindowClose(boolean logStopOnToolWindowClose) { this.logStopOnToolWindowClose = logStopOnToolWindowClose; }

        public boolean getLogAutoOpenOnCapture() { return logAutoOpenOnCapture; }
        public void setLogAutoOpenOnCapture(boolean logAutoOpenOnCapture) { this.logAutoOpenOnCapture = logAutoOpenOnCapture; }

        public boolean getLogTimestampEnabled() { return logTimestampEnabled; }
        public void setLogTimestampEnabled(boolean logTimestampEnabled) { this.logTimestampEnabled = logTimestampEnabled; }

        public String getLogTimestampFormat() { return logTimestampFormat; }
        public void setLogTimestampFormat(String logTimestampFormat) { this.logTimestampFormat = logTimestampFormat; }

        public boolean getLogFormatSql() { return logFormatSql; }
        public void setLogFormatSql(boolean logFormatSql) { this.logFormatSql = logFormatSql; }

        public String getLogFilterKeywords() { return logFilterKeywords; }
        public void setLogFilterKeywords(String logFilterKeywords) { this.logFilterKeywords = logFilterKeywords; }

        public long getLogDedupeWindowMs() { return logDedupeWindowMs; }
        public void setLogDedupeWindowMs(long logDedupeWindowMs) { this.logDedupeWindowMs = logDedupeWindowMs; }

        public int getLogMaxParseLength() { return logMaxParseLength; }
        public void setLogMaxParseLength(int logMaxParseLength) { this.logMaxParseLength = logMaxParseLength; }

        public int getLogMaxCount() { return logMaxCount; }
        public void setLogMaxCount(int logMaxCount) { this.logMaxCount = logMaxCount; }

        public boolean getLogAutoSaveEnabled() { return logAutoSaveEnabled; }
        public void setLogAutoSaveEnabled(boolean logAutoSaveEnabled) { this.logAutoSaveEnabled = logAutoSaveEnabled; }

        public String getLogAutoSavePath() { return logAutoSavePath; }
        public void setLogAutoSavePath(String logAutoSavePath) { this.logAutoSavePath = logAutoSavePath; }

        public String getLogAutoSaveFileNamePattern() { return logAutoSaveFileNamePattern; }
        public void setLogAutoSaveFileNamePattern(String logAutoSaveFileNamePattern) { this.logAutoSaveFileNamePattern = logAutoSaveFileNamePattern; }

        public String getLogTimePrefix() { return logTimePrefix; }
        public void setLogTimePrefix(String logTimePrefix) { this.logTimePrefix = logTimePrefix; }

        public long getSlowQueryThresholdMs() { return slowQueryThresholdMs; }
        public void setSlowQueryThresholdMs(long slowQueryThresholdMs) { this.slowQueryThresholdMs = slowQueryThresholdMs; }

        public boolean getShowDurationInLogList() { return showDurationInLogList; }
        public void setShowDurationInLogList(boolean showDurationInLogList) { this.showDurationInLogList = showDurationInLogList; }

        public String getParamHistoryJson() { return paramHistoryJson; }
        public void setParamHistoryJson(String paramHistoryJson) { this.paramHistoryJson = paramHistoryJson; }

        public boolean getLogHibernateEnabled() { return logHibernateEnabled; }
        public void setLogHibernateEnabled(boolean logHibernateEnabled) { this.logHibernateEnabled = logHibernateEnabled; }

        public String getLogHibernateSqlPrefix() { return logHibernateSqlPrefix; }
        public void setLogHibernateSqlPrefix(String logHibernateSqlPrefix) { this.logHibernateSqlPrefix = logHibernateSqlPrefix; }

        public String getLogHibernateParamPrefix() { return logHibernateParamPrefix; }
        public void setLogHibernateParamPrefix(String logHibernateParamPrefix) { this.logHibernateParamPrefix = logHibernateParamPrefix; }

        public boolean getLogJdbcEnabled() { return logJdbcEnabled; }
        public void setLogJdbcEnabled(boolean logJdbcEnabled) { this.logJdbcEnabled = logJdbcEnabled; }

        public List<TableNameRule> getDynamicTableNameRules() { return dynamicTableNameRules; }
        public void setDynamicTableNameRules(List<TableNameRule> dynamicTableNameRules) { this.dynamicTableNameRules = dynamicTableNameRules; }

        public boolean getDataPermissionHint() { return dataPermissionHint; }
        public void setDataPermissionHint(boolean dataPermissionHint) { this.dataPermissionHint = dataPermissionHint; }

        public String getDataPermissionMockCondition() { return dataPermissionMockCondition; }
        public void setDataPermissionMockCondition(String dataPermissionMockCondition) { this.dataPermissionMockCondition = dataPermissionMockCondition; }

        public int getHistoryMaxSize() { return historyMaxSize; }
        public void setHistoryMaxSize(int historyMaxSize) { this.historyMaxSize = historyMaxSize; }

        public Set<String> getCollapsedGroups() { return collapsedGroups; }
        public void setCollapsedGroups(Set<String> collapsedGroups) { this.collapsedGroups = collapsedGroups; }

        /** 浅拷贝（仅复制字段引用/值；供持久化 loadState 触发引用变化）。 */
        @NotNull
        public State copy() {
            State c = new State();
            c.dialect = dialect;
            c.replaceParams = replaceParams;
            c.formatSql = formatSql;
            c.showGutterIcon = showGutterIcon;
            c.language = language;
            c.injectLogicDelete = injectLogicDelete;
            c.logicDeleteValue = logicDeleteValue;
            c.injectTenantLine = injectTenantLine;
            c.tenantColumnName = tenantColumnName;
            c.tenantColumnDefault = tenantColumnDefault;
            c.injectTenantOnInsert = injectTenantOnInsert;
            c.tablePrefix = tablePrefix;
            c.tableSuffix = tableSuffix;
            c.tableNameInference = tableNameInference;
            c.ignoredFields = ignoredFields;
            c.removeTablePrefix = removeTablePrefix;
            c.removeTableSuffix = removeTableSuffix;
            c.autoCheckUpdate = autoCheckUpdate;
            c.ignoredUpdateVersions = ignoredUpdateVersions;
            c.reverseWrapperFormat = reverseWrapperFormat;
            c.reverseManualEntitySelection = reverseManualEntitySelection;
            c.logSqlPrefix = logSqlPrefix;
            c.logParamPrefix = logParamPrefix;
            c.logMixedPrefix = logMixedPrefix;
            c.logAutoCaptureOnStart = logAutoCaptureOnStart;
            c.logAutoCaptureSuppressed = logAutoCaptureSuppressed;
            c.logStopOnToolWindowClose = logStopOnToolWindowClose;
            c.logAutoOpenOnCapture = logAutoOpenOnCapture;
            c.logTimestampEnabled = logTimestampEnabled;
            c.logTimestampFormat = logTimestampFormat;
            c.logFormatSql = logFormatSql;
            c.logFilterKeywords = logFilterKeywords;
            c.logDedupeWindowMs = logDedupeWindowMs;
            c.logMaxParseLength = logMaxParseLength;
            c.logMaxCount = logMaxCount;
            c.logAutoSaveEnabled = logAutoSaveEnabled;
            c.logAutoSavePath = logAutoSavePath;
            c.logAutoSaveFileNamePattern = logAutoSaveFileNamePattern;
            c.logTimePrefix = logTimePrefix;
            c.slowQueryThresholdMs = slowQueryThresholdMs;
            c.showDurationInLogList = showDurationInLogList;
            c.paramHistoryJson = paramHistoryJson;
            c.logHibernateEnabled = logHibernateEnabled;
            c.logHibernateSqlPrefix = logHibernateSqlPrefix;
            c.logHibernateParamPrefix = logHibernateParamPrefix;
            c.logJdbcEnabled = logJdbcEnabled;
            c.dynamicTableNameRules = dynamicTableNameRules;
            c.dataPermissionHint = dataPermissionHint;
            c.dataPermissionMockCondition = dataPermissionMockCondition;
            c.historyMaxSize = historyMaxSize;
            c.collapsedGroups = collapsedGroups;
            return c;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof State)) return false;
            State that = (State) o;
            return replaceParams == that.replaceParams
                    && formatSql == that.formatSql
                    && showGutterIcon == that.showGutterIcon
                    && injectLogicDelete == that.injectLogicDelete
                    && injectTenantLine == that.injectTenantLine
                    && injectTenantOnInsert == that.injectTenantOnInsert
                    && autoCheckUpdate == that.autoCheckUpdate
                    && logMixedPrefix == that.logMixedPrefix
                    && logAutoCaptureOnStart == that.logAutoCaptureOnStart
                    && logAutoCaptureSuppressed == that.logAutoCaptureSuppressed
                    && logStopOnToolWindowClose == that.logStopOnToolWindowClose
                    && logAutoOpenOnCapture == that.logAutoOpenOnCapture
                    && logTimestampEnabled == that.logTimestampEnabled
                    && logFormatSql == that.logFormatSql
                    && logAutoSaveEnabled == that.logAutoSaveEnabled
                    && logHibernateEnabled == that.logHibernateEnabled
                    && logJdbcEnabled == that.logJdbcEnabled
                    && dataPermissionHint == that.dataPermissionHint
                    && logDedupeWindowMs == that.logDedupeWindowMs
                    && logMaxParseLength == that.logMaxParseLength
                    && logMaxCount == that.logMaxCount
                    && slowQueryThresholdMs == that.slowQueryThresholdMs
                    && showDurationInLogList == that.showDurationInLogList
                    && historyMaxSize == that.historyMaxSize
                    && Objects.equals(dialect, that.dialect)
                    && Objects.equals(language, that.language)
                    && Objects.equals(logicDeleteValue, that.logicDeleteValue)
                    && Objects.equals(tenantColumnName, that.tenantColumnName)
                    && Objects.equals(tenantColumnDefault, that.tenantColumnDefault)
                    && Objects.equals(tablePrefix, that.tablePrefix)
                    && Objects.equals(tableSuffix, that.tableSuffix)
                    && Objects.equals(tableNameInference, that.tableNameInference)
                    && Objects.equals(ignoredFields, that.ignoredFields)
                    && Objects.equals(removeTablePrefix, that.removeTablePrefix)
                    && Objects.equals(removeTableSuffix, that.removeTableSuffix)
                    && Objects.equals(ignoredUpdateVersions, that.ignoredUpdateVersions)
                    && Objects.equals(reverseWrapperFormat, that.reverseWrapperFormat)
                    && Objects.equals(reverseManualEntitySelection, that.reverseManualEntitySelection)
                    && Objects.equals(logSqlPrefix, that.logSqlPrefix)
                    && Objects.equals(logParamPrefix, that.logParamPrefix)
                    && Objects.equals(logTimestampFormat, that.logTimestampFormat)
                    && Objects.equals(logFilterKeywords, that.logFilterKeywords)
                    && Objects.equals(logAutoSavePath, that.logAutoSavePath)
                    && Objects.equals(logAutoSaveFileNamePattern, that.logAutoSaveFileNamePattern)
                    && Objects.equals(logTimePrefix, that.logTimePrefix)
                    && Objects.equals(paramHistoryJson, that.paramHistoryJson)
                    && Objects.equals(logHibernateSqlPrefix, that.logHibernateSqlPrefix)
                    && Objects.equals(logHibernateParamPrefix, that.logHibernateParamPrefix)
                    && Objects.equals(dynamicTableNameRules, that.dynamicTableNameRules)
                    && Objects.equals(dataPermissionMockCondition, that.dataPermissionMockCondition)
                    && Objects.equals(collapsedGroups, that.collapsedGroups);
        }

        @Override
        public int hashCode() {
            return Objects.hash(
                    dialect, replaceParams, formatSql, showGutterIcon, language, injectLogicDelete,
                    logicDeleteValue, injectTenantLine, tenantColumnName, tenantColumnDefault, injectTenantOnInsert,
                    tablePrefix, tableSuffix, tableNameInference, ignoredFields, removeTablePrefix, removeTableSuffix,
                    autoCheckUpdate, ignoredUpdateVersions, reverseWrapperFormat, reverseManualEntitySelection,
                    logSqlPrefix, logParamPrefix, logMixedPrefix, logAutoCaptureOnStart, logAutoCaptureSuppressed,
                    logStopOnToolWindowClose, logAutoOpenOnCapture, logTimestampEnabled, logTimestampFormat,
                    logFormatSql, logFilterKeywords, logDedupeWindowMs, logMaxParseLength, logMaxCount,
                    logAutoSaveEnabled, logAutoSavePath, logAutoSaveFileNamePattern, logTimePrefix, slowQueryThresholdMs, showDurationInLogList,
                    paramHistoryJson, logHibernateEnabled, logHibernateSqlPrefix, logHibernateParamPrefix, logJdbcEnabled,
                    dynamicTableNameRules, dataPermissionHint, dataPermissionMockCondition, historyMaxSize, collapsedGroups);
        }

        @Override
        public String toString() {
            return "State(dialect=" + dialect + ")";
        }
    }

    /** 设置变更通知 Topic（订阅方据此即时刷新 UI）。 */
    public static final Topic<SettingsChangeListener> SETTINGS_CHANGE_TOPIC =
            Topic.create("MpSqlVisualizerSettingsChange", SettingsChangeListener.class);

    public static final String DEFAULT_DIALECT = "MySQL";

    /** 默认界面语言（M14，用户要求默认中文）。 */
    public static final String DEFAULT_LANGUAGE = "zh";

    /** 表名推断策略：驼峰转下划线（默认，对齐 MP，`BikeDeviceInfoEntity` → `bike_device_info`）。 */
    public static final String INFERENCE_SNAKE = "SNAKE";

    /** 表名推断策略：纯保留驼峰——仅首字母小写、不去 Entity 后缀（`BikeDeviceInfoEntity` → `bikeDeviceInfoEntity`）。 */
    public static final String INFERENCE_CAMEL = "CAMEL";

    /** 反向转换 Wrapper 形态：自动（有实体 → Lambda，无实体 → 字符串）。 */
    public static final String REVERSE_AUTO = "AUTO";

    /** 反向转换 Wrapper 形态：恒字符串列名 QueryWrapper。 */
    public static final String REVERSE_QUERY = "QUERY";

    /** 反向转换 Wrapper 形态：LambdaQueryWrapper（无实体回落字符串）。 */
    public static final String REVERSE_LAMBDA = "LAMBDA";

    /** 反向转换 Wrapper 形态：Wrappers 静态工厂（有实体 lambdaQuery / 无实体 query，T-M36.6）。 */
    public static final String REVERSE_WRAPPERS = "WRAPPERS";

    /** 反向转换 Wrapper 形态：MPJLambdaWrapper（无实体回落字符串）。 */
    public static final String REVERSE_MPJ_LAMBDA = "MPJ_LAMBDA";

    // ---------- SQL 日志默认值（T-M31.14） ----------
    public static final String DEFAULT_LOG_SQL_PREFIX = "Preparing:";
    public static final String DEFAULT_LOG_PARAM_PREFIX = "Parameters:";
    public static final String DEFAULT_LOG_TIMESTAMP_FORMAT = "yyyy-MM-dd HH:mm:ss";
    public static final long DEFAULT_LOG_DEDUPE_WINDOW_MS = 60000L;
    public static final int DEFAULT_LOG_MAX_PARSE_LENGTH = 20000;
    public static final int DEFAULT_LOG_MAX_COUNT = 200;

    /** 自动保存历史 SQL 默认文件名模板（F-43）。 */
    public static final String DEFAULT_LOG_AUTO_SAVE_FILE_NAME_PATTERN = "sql-history-$yyyy-MM-dd$.sql";

    // ---------- O-1 日志窗口差异化默认值 ----------
    public static final String DEFAULT_LOG_TIME_PREFIX = "Time:";
    public static final long DEFAULT_SLOW_QUERY_THRESHOLD_MS = 1000L;

    // ---------- Hibernate/JPA 日志默认值 ----------
    public static final String DEFAULT_LOG_HIBERNATE_SQL_PREFIX = "Hibernate:";
    public static final String DEFAULT_LOG_HIBERNATE_PARAM_PREFIX = "binding parameter";
    public static final String DEFAULT_LOGIC_DELETE_VALUE = "0";

    /** 默认租户列名（T-TenantLine，对齐 MP TenantLineInnerInterceptor 默认列名）。 */
    public static final String DEFAULT_TENANT_COLUMN_NAME = "tenant_id";

    /** 默认租户列值（F-6 Phase A）：UPDATE SET / INSERT 追加租户列时的字面量默认值。 */
    public static final String DEFAULT_TENANT_COLUMN_DEFAULT = "1";

    /** 默认 SQL 历史记录上限（F-7，非收藏条目）。 */
    public static final int DEFAULT_HISTORY_MAX_SIZE = 200;
}
