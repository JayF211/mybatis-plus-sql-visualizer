package cn.song.mybatisplus.visualizer.config;

import com.intellij.openapi.components.PersistentStateComponent;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.components.State;
import com.intellij.openapi.components.Storage;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;

import java.util.Objects;

/**
 * 项目级插件设置（O-5 Phase B）。
 *
 * project-level 轻服务（@Service(Service.Level.PROJECT)，按项目隔离），经 XmlSerializer
 * 持久化到 `.idea/mp-sql-visualizer-project.xml`。
 *
 * 职责：
 *  - 存储「使用项目设置」开关 + 可覆盖项（方言/参数替换/逻辑删除/表名映射等）
 *  - 关闭时使用全局 PluginSettings 值；开启时覆盖全局值
 *
 * 可覆盖项与全局 PluginSettings.State 同名同语义，空白/默认值表示「使用全局」。
 */
@State(
        name = "MpSqlVisualizerProjectSettings",
        storages = @Storage("mp-sql-visualizer-project.xml")
)
@Service(Service.Level.PROJECT)
public final class ProjectPluginSettings implements PersistentStateComponent<ProjectPluginSettings.State> {

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

    /** 当前项目是否启用了项目级设置。 */
    public boolean isEnabled() {
        return getState().getUseProjectSettings();
    }

    /**
     * T-DialectAutoInfer：有效方言模式（项目级 dialectAuto 非 null 且已启用项目设置时优先，
     * 否则回退全局 dialectAuto）。
     */
    public boolean effectiveDialectAuto() {
        State s = getState();
        if (s.getUseProjectSettings() && s.getDialectAuto() != null) return s.getDialectAuto();
        return PluginSettings.getInstance().getState().getDialectAuto();
    }

    /** 项目级方言（未启用或空则回退全局）。 */
    public String effectiveDialect() {
        State s = getState();
        if (s.getUseProjectSettings() && !s.getDialect().isBlank()) return s.getDialect();
        return PluginSettings.getInstance().getState().getDialect();
    }

    /** 项目级参数替换开关（未启用或 null 则回退全局）。 */
    public boolean effectiveReplaceParams() {
        State s = getState();
        if (s.getUseProjectSettings()) {
            return s.getReplaceParams() != null ? s.getReplaceParams() : PluginSettings.getInstance().getState().getReplaceParams();
        }
        return PluginSettings.getInstance().getState().getReplaceParams();
    }

    /** 项目级格式化开关（未启用或 null 则回退全局）。 */
    public boolean effectiveFormatSql() {
        State s = getState();
        if (s.getUseProjectSettings()) {
            return s.getFormatSql() != null ? s.getFormatSql() : PluginSettings.getInstance().getState().getFormatSql();
        }
        return PluginSettings.getInstance().getState().getFormatSql();
    }

    /** 项目级逻辑删除注入开关（未启用或 null 则回退全局）。 */
    public boolean effectiveInjectLogicDelete() {
        State s = getState();
        if (s.getUseProjectSettings()) {
            return s.getInjectLogicDelete() != null ? s.getInjectLogicDelete() : PluginSettings.getInstance().getState().getInjectLogicDelete();
        }
        return PluginSettings.getInstance().getState().getInjectLogicDelete();
    }

    /** 项目级逻辑删除值（未启用或空则回退全局）。 */
    public String effectiveLogicDeleteValue() {
        State s = getState();
        if (s.getUseProjectSettings() && !s.getLogicDeleteValue().isBlank()) return s.getLogicDeleteValue();
        return PluginSettings.getInstance().getState().getLogicDeleteValue();
    }

    /** 项目级表前缀（未启用或空则回退全局）。 */
    public String effectiveTablePrefix() {
        State s = getState();
        if (s.getUseProjectSettings() && !s.getTablePrefix().isBlank()) return s.getTablePrefix();
        return PluginSettings.getInstance().getState().getTablePrefix();
    }

    /** 项目级表后缀（未启用或空则回退全局）。 */
    public String effectiveTableSuffix() {
        State s = getState();
        if (s.getUseProjectSettings() && !s.getTableSuffix().isBlank()) return s.getTableSuffix();
        return PluginSettings.getInstance().getState().getTableSuffix();
    }

    /** 项目级表名推断策略（未启用或空则回退全局）。 */
    public String effectiveTableNameInference() {
        State s = getState();
        if (s.getUseProjectSettings() && !s.getTableNameInference().isBlank()) return s.getTableNameInference();
        return PluginSettings.getInstance().getState().getTableNameInference();
    }

    /** 项目级忽略字段名（未启用或空则回退全局）。 */
    public String effectiveIgnoredFields() {
        State s = getState();
        if (s.getUseProjectSettings() && !s.getIgnoredFields().isBlank()) return s.getIgnoredFields();
        return PluginSettings.getInstance().getState().getIgnoredFields();
    }

    /** 项目级可持久化状态。 */
    public static final class State {

        private boolean useProjectSettings = false;
        /** 项目级数据库方言（空字符串表示使用全局；仅项目级 auto 关闭时生效）。 */
        private String dialect = "";

        /**
         * T-DialectAutoInfer：项目级方言模式（null=使用全局——与「空=不覆盖」语义同构）。
         * true=项目强制智能推断；false=项目按 {@code dialect}（非空时）手动指定。
         */
        private Boolean dialectAuto = null;

        private Boolean replaceParams = null;
        private Boolean formatSql = null;
        private Boolean injectLogicDelete = null;
        private String logicDeleteValue = "";
        private String tablePrefix = "";
        private String tableSuffix = "";
        private String tableNameInference = "";
        private String ignoredFields = "";

        public boolean getUseProjectSettings() { return useProjectSettings; }
        public void setUseProjectSettings(boolean useProjectSettings) { this.useProjectSettings = useProjectSettings; }

        public String getDialect() { return dialect; }
        public void setDialect(String dialect) { this.dialect = dialect; }

        /** 项目级方言模式（null=使用全局；true=强制智能推断；false=手动指定）。 */
        public Boolean getDialectAuto() { return dialectAuto; }
        public void setDialectAuto(Boolean dialectAuto) { this.dialectAuto = dialectAuto; }

        public Boolean getReplaceParams() { return replaceParams; }
        public void setReplaceParams(Boolean replaceParams) { this.replaceParams = replaceParams; }

        public Boolean getFormatSql() { return formatSql; }
        public void setFormatSql(Boolean formatSql) { this.formatSql = formatSql; }

        public Boolean getInjectLogicDelete() { return injectLogicDelete; }
        public void setInjectLogicDelete(Boolean injectLogicDelete) { this.injectLogicDelete = injectLogicDelete; }

        public String getLogicDeleteValue() { return logicDeleteValue; }
        public void setLogicDeleteValue(String logicDeleteValue) { this.logicDeleteValue = logicDeleteValue; }

        public String getTablePrefix() { return tablePrefix; }
        public void setTablePrefix(String tablePrefix) { this.tablePrefix = tablePrefix; }

        public String getTableSuffix() { return tableSuffix; }
        public void setTableSuffix(String tableSuffix) { this.tableSuffix = tableSuffix; }

        public String getTableNameInference() { return tableNameInference; }
        public void setTableNameInference(String tableNameInference) { this.tableNameInference = tableNameInference; }

        public String getIgnoredFields() { return ignoredFields; }
        public void setIgnoredFields(String ignoredFields) { this.ignoredFields = ignoredFields; }

        /** 浅拷贝。 */
        @NotNull
    public State copy() {
            State c = new State();
            c.useProjectSettings = useProjectSettings;
            c.dialect = dialect;
            c.dialectAuto = dialectAuto;
            c.replaceParams = replaceParams;
            c.formatSql = formatSql;
            c.injectLogicDelete = injectLogicDelete;
            c.logicDeleteValue = logicDeleteValue;
            c.tablePrefix = tablePrefix;
            c.tableSuffix = tableSuffix;
            c.tableNameInference = tableNameInference;
            c.ignoredFields = ignoredFields;
            return c;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof State)) return false;
            State that = (State) o;
            return useProjectSettings == that.useProjectSettings
                    && Objects.equals(dialect, that.dialect)
                    && Objects.equals(dialectAuto, that.dialectAuto)
                    && Objects.equals(replaceParams, that.replaceParams)
                    && Objects.equals(formatSql, that.formatSql)
                    && Objects.equals(injectLogicDelete, that.injectLogicDelete)
                    && Objects.equals(logicDeleteValue, that.logicDeleteValue)
                    && Objects.equals(tablePrefix, that.tablePrefix)
                    && Objects.equals(tableSuffix, that.tableSuffix)
                    && Objects.equals(tableNameInference, that.tableNameInference)
                    && Objects.equals(ignoredFields, that.ignoredFields);
        }

        @Override
        public int hashCode() {
            return Objects.hash(useProjectSettings, dialect, dialectAuto, replaceParams, formatSql, injectLogicDelete,
                    logicDeleteValue, tablePrefix, tableSuffix, tableNameInference, ignoredFields);
        }

        @Override
        public String toString() {
            return "State(useProjectSettings=" + useProjectSettings + ")";
        }
    }

    /**
     * 获取指定项目的 ProjectPluginSettings 实例。
     *
     * @param project 目标项目
     * @return 项目级设置实例
     */
    @NotNull
    public static ProjectPluginSettings getInstance(Project project) {
        return project.getService(ProjectPluginSettings.class);
    }
}
