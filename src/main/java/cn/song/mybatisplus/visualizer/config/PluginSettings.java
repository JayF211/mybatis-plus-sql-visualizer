package cn.song.mybatisplus.visualizer.config;

import cn.song.mybatisplus.visualizer.log.SqlListDisplayMode;
import cn.song.mybatisplus.visualizer.log.SqlListTypeColor;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.PersistentStateComponent;
import com.intellij.openapi.components.RoamingType;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.components.State;
import com.intellij.openapi.components.Storage;
import com.intellij.util.messages.Topic;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 插件配置状态（T-M5-01，F-16 配置项持久化）。
 *
 * application-level 轻服务（@Service(Service.Level.APP)，跨项目共享），经 XmlSerializer 持久化
 * 到 `mp-sql-visualizer.xml`。修改后由 SectionConfigurable.apply（设置子页基类）写回即生效。
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

    /** 当前 SQL 列表显示模式（T-ListMode；旧配置残留非法值时回退默认关键字换行）。 */
    @NotNull
    public SqlListDisplayMode sqlListDisplayMode() {
        try {
            return SqlListDisplayMode.valueOf(state.getLogListSqlDisplayMode());
        } catch (Throwable e) {
            return SqlListDisplayMode.KEYWORD_WRAP;
        }
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
        /**
         * 竖向排列 VALUES 行尾配对列名标注（T-VPairAnno）：true=显示（默认，T-VStack 以来行为不变），
         * false=仅竖排不追加标注注释（用户自带注释不受影响）。
         * 仅作用于预览面板竖向排列显示模式（SqlFormatter.formatVertical 的唯一生产调用点 applyDisplayMode）。
         */
        private boolean verticalPairAnnotation = true;

        /**
         * 字段较多时自动折叠（T-AutoFold）：true = 各处 SQL 预览编辑器渲染后自动折叠超长字段列表
         * （SELECT/INSERT 列清单、VALUES 组、UPDATE SET），默认关——关闭时写入管线零折叠操作、
         * 行为逐字节不变；开启后的下一次程序性写入生效（与 formatSql 同构，不即时刷新已开窗体）。
         */
        private boolean autoFoldEnabled = false;

        /**
         * 自动折叠触发的顶层字段数阈值（T-AutoFold）：字段列表元素数 >= 该值且区域跨多行才折叠，
         * 默认 10。
         */
        private int autoFoldFieldThreshold = DEFAULT_AUTO_FOLD_FIELD_THRESHOLD;
        private boolean showGutterIcon = true;

        /**
         * JOIN 图渲染方式（T-Dgm-04）：true=IDE 原生图框架（Ultimate 的 Diagrams 插件，类图观感，
         * 缩放/布局/导出全框架托管）；false=经典自绘渲染器。
         * Community / Diagrams 插件缺席时无论此值均自动降级自绘。
         */
        private boolean joinDiagramNative = true;
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
        /** 发现 SQL 时自动展开日志工具窗口（M37.3 默认开——竞品「每次手动开面板」是普遍抱怨；60s 防抖，show 不抢焦点）。 */
        private boolean logAutoOpenOnCapture = true;
        private boolean logTimestampEnabled = true;
        private String logTimestampFormat = DEFAULT_LOG_TIMESTAMP_FORMAT;
        /** 时间戳显示模式：log=日志时间（默认），capture=捕获时间。 */
        private String logTimestampSource = DEFAULT_LOG_TIMESTAMP_SOURCE;
        private boolean logFormatSql = true;
        /**
         * 字面量参数恢复（T-HistParam F4，默认开）：日志 SQL 无 `?` 占位符（裸 SQL 捕获 /
         * MyBatis `${}` / 上游日志已内联参数值）时，把单引号字符串字面量识别为可编辑参数行。
         * 作用于 toPreviewResult 的统一识别通道。
         */
        private boolean literalParamRecovery = true;
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

        // ---------- T-CrudColor：SQL 列表 CRUD 类型颜色标记 ----------
        /**
         * CRUD 颜色标记形态：`bar`=左侧色条（默认）/ `background`=整行淡背景（选中态走描边）/
         * `off`=关闭。未知值按 `bar` 兜底（见 SqlListTypeColor.styleOf）。
         * 默认值常量在 SqlListTypeColor，State 侧只引用，避免双份字面量。
         */
        private String crudColorStyle = CRUD_COLOR_STYLE_BAR;

        /** SELECT 标记色（`#RRGGBB`；空串/非法 → 默认色）。 */
        private String crudColorSelect = CRUD_COLOR_DEFAULT_SELECT;

        /** INSERT 标记色（`#RRGGBB`）。 */
        private String crudColorInsert = CRUD_COLOR_DEFAULT_INSERT;

        /** UPDATE 标记色（`#RRGGBB`）。 */
        private String crudColorUpdate = CRUD_COLOR_DEFAULT_UPDATE;

        /** DELETE 标记色（`#RRGGBB`）。 */
        private String crudColorDelete = CRUD_COLOR_DEFAULT_DELETE;

        /**
         * SQL 列表显示模式（T-ListMode）：SQL 日志与 SQL 历史列表条目中 SQL 行的展示形态
         * （枚举 SqlListDisplayMode 的 name，读取走 sqlListDisplayMode() 容错）。默认关键字换行。
         */
        private String logListSqlDisplayMode = DEFAULT_LOG_LIST_DISPLAY_MODE;

        private String paramHistoryJson = "";
        private boolean logHibernateEnabled = true;
        private String logHibernateSqlPrefix = DEFAULT_LOG_HIBERNATE_SQL_PREFIX;
        private String logHibernateParamPrefix = DEFAULT_LOG_HIBERNATE_PARAM_PREFIX;
        private boolean logJdbcEnabled = true;

        // ---------- JDBC 模板 SQL 字符串预览 ----------

        /** 启用 JdbcTemplate / NamedParameterJdbcTemplate SQL 字符串预览（默认开）。 */
        private boolean jdbcSqlPreviewEnabled = true;
        private List<TableNameRule> dynamicTableNameRules = new ArrayList<>();
        private boolean dataPermissionHint = false;
        private String dataPermissionMockCondition = "";
        private int historyMaxSize = DEFAULT_HISTORY_MAX_SIZE;
        // ---------- F-44-7 SqlSourceInterceptor 自动注入 ----------
        /** F-44-7：自动注入 SqlSourceInterceptor（源码位置捕获）。默认关。 */
        private boolean autoInjectSqlSourceInterceptor = false;

        // ---------- T-ErrReport 错误一键上报官网需求池 ----------

        /**
         * 需求池报告昵称（选填，报告对话框填写后记忆，下次带出；空 = 匿名提交）。
         */
        private String reportSubmitterName = "";

        /**
         * 需求池报告邮箱（选填，仅用于官方回复推送通知，不公开）。
         */
        private String reportSubmitterEmail = "";

        // ---------- M56 增强捕获（agent 字节码通道；对用户不出现 agent 字样） ----------

        /**
         * M56 T-M56-08：增强捕获开关（**默认开**——2026-09-16 用户定案，增强捕获为主通道）。
         * 开启且经注入确认后，agent 接管捕获源（参数实参直取/精确耗时/影响行数/报错同列/
         * 精确跳转）；attach 不可用/连接丢失自动回落现有控制台捕获，零劣化。
         * 注：本项默认开而 {@code enhancedCaptureConfirmed} 默认 false——首次挂载（项目运行）时弹一次
         * 注入确认，同意后才真正 attach（知情同意不放宽，见 AgentManager 的首次挂载确认请求）。
         */
        private boolean enhancedCaptureEnabled = true;

        /**
         * M56 T-M56-08：注入确认记忆（确认后置 true，不再重复弹）。
         * 关闭增强不重置本值（重开不再弹）；确认对话框含采集范围/数据留本机/JEP 451 告警预告。
         * 默认 false：配合 enabled 默认开，首次运行项目时弹一次（设置页手动勾选时亦可确认）。
         */
        private boolean enhancedCaptureConfirmed = false;

        /**
         * M56 T-M56-08：脱敏开关（**默认关**——2026-09-16 用户定案，默认采集真实参数值）。
         * true 时对疑似敏感形态打码；脱敏在 agent 采集层执行，数据不出应用 JVM 前已处理。
         */
        private boolean enhancedCaptureDesensitize = false;

        public String getDialect() { return dialect; }
        public void setDialect(String dialect) { this.dialect = dialect; }

        public boolean getReplaceParams() { return replaceParams; }
        public void setReplaceParams(boolean replaceParams) { this.replaceParams = replaceParams; }

        public boolean getFormatSql() { return formatSql; }
        public void setFormatSql(boolean formatSql) { this.formatSql = formatSql; }

        public boolean getVerticalPairAnnotation() { return verticalPairAnnotation; }
        public void setVerticalPairAnnotation(boolean verticalPairAnnotation) { this.verticalPairAnnotation = verticalPairAnnotation; }

        public boolean getAutoFoldEnabled() { return autoFoldEnabled; }
        public void setAutoFoldEnabled(boolean autoFoldEnabled) { this.autoFoldEnabled = autoFoldEnabled; }

        public int getAutoFoldFieldThreshold() { return autoFoldFieldThreshold; }
        public void setAutoFoldFieldThreshold(int autoFoldFieldThreshold) { this.autoFoldFieldThreshold = autoFoldFieldThreshold; }

        public boolean getShowGutterIcon() { return showGutterIcon; }
        public void setShowGutterIcon(boolean showGutterIcon) { this.showGutterIcon = showGutterIcon; }
        public boolean getJoinDiagramNative() { return joinDiagramNative; }
        public void setJoinDiagramNative(boolean joinDiagramNative) { this.joinDiagramNative = joinDiagramNative; }

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

        public String getLogTimestampSource() { return logTimestampSource; }
        public void setLogTimestampSource(String logTimestampSource) { this.logTimestampSource = logTimestampSource; }

        public boolean getLogFormatSql() { return logFormatSql; }
        public void setLogFormatSql(boolean logFormatSql) { this.logFormatSql = logFormatSql; }

        public boolean getLiteralParamRecovery() { return literalParamRecovery; }
        public void setLiteralParamRecovery(boolean literalParamRecovery) { this.literalParamRecovery = literalParamRecovery; }

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

        public String getCrudColorStyle() { return crudColorStyle; }
        public void setCrudColorStyle(String crudColorStyle) { this.crudColorStyle = crudColorStyle; }

        public String getCrudColorSelect() { return crudColorSelect; }
        public void setCrudColorSelect(String crudColorSelect) { this.crudColorSelect = crudColorSelect; }

        public String getCrudColorInsert() { return crudColorInsert; }
        public void setCrudColorInsert(String crudColorInsert) { this.crudColorInsert = crudColorInsert; }

        public String getCrudColorUpdate() { return crudColorUpdate; }
        public void setCrudColorUpdate(String crudColorUpdate) { this.crudColorUpdate = crudColorUpdate; }

        public String getCrudColorDelete() { return crudColorDelete; }
        public void setCrudColorDelete(String crudColorDelete) { this.crudColorDelete = crudColorDelete; }

        public String getLogListSqlDisplayMode() { return logListSqlDisplayMode; }
        public void setLogListSqlDisplayMode(String logListSqlDisplayMode) { this.logListSqlDisplayMode = logListSqlDisplayMode; }

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
        public boolean getJdbcSqlPreviewEnabled() { return jdbcSqlPreviewEnabled; }
        public void setJdbcSqlPreviewEnabled(boolean jdbcSqlPreviewEnabled) { this.jdbcSqlPreviewEnabled = jdbcSqlPreviewEnabled; }

        public List<TableNameRule> getDynamicTableNameRules() { return dynamicTableNameRules; }
        public void setDynamicTableNameRules(List<TableNameRule> dynamicTableNameRules) { this.dynamicTableNameRules = dynamicTableNameRules; }

        public boolean getDataPermissionHint() { return dataPermissionHint; }
        public void setDataPermissionHint(boolean dataPermissionHint) { this.dataPermissionHint = dataPermissionHint; }

        public String getDataPermissionMockCondition() { return dataPermissionMockCondition; }
        public void setDataPermissionMockCondition(String dataPermissionMockCondition) { this.dataPermissionMockCondition = dataPermissionMockCondition; }

        public int getHistoryMaxSize() { return historyMaxSize; }
        public void setHistoryMaxSize(int historyMaxSize) { this.historyMaxSize = historyMaxSize; }

        public boolean getAutoInjectSqlSourceInterceptor() { return autoInjectSqlSourceInterceptor; }
        public void setAutoInjectSqlSourceInterceptor(boolean autoInjectSqlSourceInterceptor) { this.autoInjectSqlSourceInterceptor = autoInjectSqlSourceInterceptor; }

        public String getReportSubmitterName() { return reportSubmitterName; }
        public void setReportSubmitterName(String reportSubmitterName) { this.reportSubmitterName = reportSubmitterName; }

        public String getReportSubmitterEmail() { return reportSubmitterEmail; }
        public void setReportSubmitterEmail(String reportSubmitterEmail) { this.reportSubmitterEmail = reportSubmitterEmail; }

        public boolean getEnhancedCaptureEnabled() { return enhancedCaptureEnabled; }
        public void setEnhancedCaptureEnabled(boolean enhancedCaptureEnabled) { this.enhancedCaptureEnabled = enhancedCaptureEnabled; }

        public boolean getEnhancedCaptureConfirmed() { return enhancedCaptureConfirmed; }
        public void setEnhancedCaptureConfirmed(boolean enhancedCaptureConfirmed) { this.enhancedCaptureConfirmed = enhancedCaptureConfirmed; }

        public boolean getEnhancedCaptureDesensitize() { return enhancedCaptureDesensitize; }
        public void setEnhancedCaptureDesensitize(boolean enhancedCaptureDesensitize) { this.enhancedCaptureDesensitize = enhancedCaptureDesensitize; }

        /** 浅拷贝（仅复制字段引用/值；供持久化 loadState 触发引用变化）。 */
        @NotNull
        public State copy() {
            State c = new State();
            c.dialect = dialect;
            c.replaceParams = replaceParams;
            c.formatSql = formatSql;
            c.verticalPairAnnotation = verticalPairAnnotation;
            c.autoFoldEnabled = autoFoldEnabled;
            c.autoFoldFieldThreshold = autoFoldFieldThreshold;
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
            c.logTimestampSource = logTimestampSource;
            c.logFormatSql = logFormatSql;
            c.literalParamRecovery = literalParamRecovery;
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
            c.crudColorStyle = crudColorStyle;
            c.crudColorSelect = crudColorSelect;
            c.crudColorInsert = crudColorInsert;
            c.crudColorUpdate = crudColorUpdate;
            c.crudColorDelete = crudColorDelete;
            c.logListSqlDisplayMode = logListSqlDisplayMode;
            c.paramHistoryJson = paramHistoryJson;
            c.logHibernateEnabled = logHibernateEnabled;
            c.logHibernateSqlPrefix = logHibernateSqlPrefix;
            c.logHibernateParamPrefix = logHibernateParamPrefix;
            c.logJdbcEnabled = logJdbcEnabled;
            c.dynamicTableNameRules = dynamicTableNameRules;
            c.dataPermissionHint = dataPermissionHint;
            c.dataPermissionMockCondition = dataPermissionMockCondition;
            c.historyMaxSize = historyMaxSize;
            c.autoInjectSqlSourceInterceptor = autoInjectSqlSourceInterceptor;
            c.reportSubmitterName = reportSubmitterName;
            c.reportSubmitterEmail = reportSubmitterEmail;
            c.enhancedCaptureEnabled = enhancedCaptureEnabled;
            c.enhancedCaptureConfirmed = enhancedCaptureConfirmed;
            c.enhancedCaptureDesensitize = enhancedCaptureDesensitize;
            return c;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof State)) return false;
            State that = (State) o;
            return replaceParams == that.replaceParams
                    && formatSql == that.formatSql
                    && verticalPairAnnotation == that.verticalPairAnnotation
                    && autoFoldEnabled == that.autoFoldEnabled
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
                    && Objects.equals(logTimestampSource, that.logTimestampSource)
                    && logFormatSql == that.logFormatSql
                    && literalParamRecovery == that.literalParamRecovery
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
                    && autoFoldFieldThreshold == that.autoFoldFieldThreshold
                    && autoInjectSqlSourceInterceptor == that.autoInjectSqlSourceInterceptor
                    && enhancedCaptureEnabled == that.enhancedCaptureEnabled
                    && enhancedCaptureConfirmed == that.enhancedCaptureConfirmed
                    && enhancedCaptureDesensitize == that.enhancedCaptureDesensitize
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
                    && Objects.equals(crudColorStyle, that.crudColorStyle)
                    && Objects.equals(crudColorSelect, that.crudColorSelect)
                    && Objects.equals(crudColorInsert, that.crudColorInsert)
                    && Objects.equals(crudColorUpdate, that.crudColorUpdate)
                    && Objects.equals(crudColorDelete, that.crudColorDelete)
                    && Objects.equals(logListSqlDisplayMode, that.logListSqlDisplayMode)
                    && Objects.equals(reportSubmitterName, that.reportSubmitterName)
                    && Objects.equals(reportSubmitterEmail, that.reportSubmitterEmail)
                    && Objects.equals(logHibernateSqlPrefix, that.logHibernateSqlPrefix)
                    && Objects.equals(logHibernateParamPrefix, that.logHibernateParamPrefix)
                    && Objects.equals(dynamicTableNameRules, that.dynamicTableNameRules)
                    && Objects.equals(dataPermissionMockCondition, that.dataPermissionMockCondition);
        }

        @Override
        public int hashCode() {
            return Objects.hash(
                    dialect, replaceParams, formatSql, verticalPairAnnotation, autoFoldEnabled, autoFoldFieldThreshold,
                    showGutterIcon, language, injectLogicDelete,
                    logicDeleteValue, injectTenantLine, tenantColumnName, tenantColumnDefault, injectTenantOnInsert,
                    tablePrefix, tableSuffix, tableNameInference, ignoredFields, removeTablePrefix, removeTableSuffix,
                    autoCheckUpdate, ignoredUpdateVersions, reverseWrapperFormat, reverseManualEntitySelection,
                    logSqlPrefix, logParamPrefix, logMixedPrefix, logAutoCaptureOnStart, logAutoCaptureSuppressed,
                    logStopOnToolWindowClose, logAutoOpenOnCapture, logTimestampEnabled, logTimestampFormat, logTimestampSource,
                    logFormatSql, literalParamRecovery, logFilterKeywords, logDedupeWindowMs, logMaxParseLength, logMaxCount,
                    logAutoSaveEnabled, logAutoSavePath, logAutoSaveFileNamePattern, logTimePrefix, slowQueryThresholdMs, showDurationInLogList,
                    crudColorStyle, crudColorSelect, crudColorInsert, crudColorUpdate, crudColorDelete, logListSqlDisplayMode,
                    paramHistoryJson, logHibernateEnabled, logHibernateSqlPrefix, logHibernateParamPrefix, logJdbcEnabled,
                    dynamicTableNameRules, dataPermissionHint, dataPermissionMockCondition, historyMaxSize,
                    autoInjectSqlSourceInterceptor, reportSubmitterName, reportSubmitterEmail,
                    enhancedCaptureEnabled, enhancedCaptureConfirmed, enhancedCaptureDesensitize);
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

    /** SQL 列表显示模式默认值（T-ListMode，关键字换行）。 */
    public static final String DEFAULT_LOG_LIST_DISPLAY_MODE = "KEYWORD_WRAP";

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
    public static final String DEFAULT_LOG_TIMESTAMP_SOURCE = "log";
    public static final String LOG_TIMESTAMP_SOURCE_LOG = "log";
    public static final String LOG_TIMESTAMP_SOURCE_CAPTURE = "capture";
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

    // ---------- T-CrudColor：SQL 列表 CRUD 类型颜色标记默认值 ----------
    // 单一真源在 SqlListTypeColor（语义与其渲染同处一处），State 侧只引用，避免双份字面量漂移。
    /** 默认标记形态：左侧色条。 */
    public static final String CRUD_COLOR_STYLE_BAR = SqlListTypeColor.STYLE_BAR;

    /** 备选标记形态：整行淡背景（选中态走描边）。 */
    public static final String CRUD_COLOR_STYLE_BACKGROUND = SqlListTypeColor.STYLE_BACKGROUND;

    /** 关闭标记。 */
    public static final String CRUD_COLOR_STYLE_OFF = SqlListTypeColor.STYLE_OFF;

    public static final String CRUD_COLOR_DEFAULT_SELECT = SqlListTypeColor.DEFAULT_SELECT;
    public static final String CRUD_COLOR_DEFAULT_INSERT = SqlListTypeColor.DEFAULT_INSERT;
    public static final String CRUD_COLOR_DEFAULT_UPDATE = SqlListTypeColor.DEFAULT_UPDATE;
    public static final String CRUD_COLOR_DEFAULT_DELETE = SqlListTypeColor.DEFAULT_DELETE;

    /** 自动折叠触发的顶层字段数阈值（T-AutoFold）。 */
    public static final int DEFAULT_AUTO_FOLD_FIELD_THRESHOLD = 10;
}
