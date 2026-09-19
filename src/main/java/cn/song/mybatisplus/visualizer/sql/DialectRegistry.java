package cn.song.mybatisplus.visualizer.sql;

import java.util.List;

/**
 * 方言注册表：按名称获取方言，未知名称回退 MySQL。
 */
public final class DialectRegistry {

    /** 单例。 */
    public static final DialectRegistry INSTANCE = new DialectRegistry();

    private static final List<Dialect> DIALECTS = List.of(
            MySqlDialect.INSTANCE,
            PostgreSQLDialect.INSTANCE,
            OracleDialect.INSTANCE,
            SqlServerDialect.INSTANCE,
            H2Dialect.INSTANCE,
            SqliteDialect.INSTANCE,
            MariaDbDialect.INSTANCE,
            DmDialect.INSTANCE,
            KingBaseDialect.INSTANCE);

    private DialectRegistry() {}

    /** 全部支持方言（顺序即 Settings 下拉顺序）。 */
    public List<Dialect> getDialects() {
        return DIALECTS;
    }

    /**
     * 按名称取方言。
     *
     * @param name 方言名称（对应 {@link Dialect#getName()}）
     * @return 匹配方言；未知名称返回 {@link MySqlDialect}
     */
    public Dialect byName(String name) {
        if (name != null) {
            for (Dialect d : DIALECTS) {
                if (d.getName().equals(name)) return d;
            }
        }
        return MySqlDialect.INSTANCE;
    }

    /** {@link #effective(String, com.intellij.openapi.project.Project)} 的全默认形态。 */
    public Dialect effective() {
        return effective(null, null);
    }

    /** {@link #effective(String, com.intellij.openapi.project.Project)} 的仅手动方言名形态。 */
    public Dialect effective(String manualDialectName) {
        return effective(manualDialectName, null);
    }

    /**
     * T-DialectAutoInfer：<b>渲染方言统一读取</b>（全部渲染消费点收口此函数）。
     *
     * <ul>
     *  <li>{@code cn.song.mybatisplus.visualizer.config.PluginSettings.State.dialectAuto}=false →
     *      手动方言（{@code byName(state.dialect)}，项目级覆盖由调用方先解析成传入名）。</li>
     *  <li>true → 智能推断（{@link DialectInference#infer}：agent 最近连接 URL &gt; IDE 数据源 URL），
     *      未命中回退 {@link MySqlDialect}（与 {@link #byName} 未知回退语义一致）。</li>
     * </ul>
     *
     * @param manualDialectName 手动模式下的方言名（null=按全局设置自查）
     * @param project           数据源探测用项目（null=跳过数据源层，仅 agent URL）
     */
    public Dialect effective(String manualDialectName, com.intellij.openapi.project.Project project) {
        cn.song.mybatisplus.visualizer.config.PluginSettings.State settings;
        try {
            settings = cn.song.mybatisplus.visualizer.config.PluginSettings.getInstance().getState();
        } catch (Throwable e) {
            settings = null;
        }
        boolean auto = settings != null ? settings.getDialectAuto() : true;
        if (!auto) {
            return byName(manualDialectName != null ? manualDialectName : settings.getDialect());
        }
        if (project == null) {
            return fallbackFrom(settings);
        }
        String agentUrl;
        try {
            agentUrl = cn.song.mybatisplus.visualizer.log.agent.AgentManager
                    .getInstance(project).lastConnectionUrl();
        } catch (Throwable e) {
            agentUrl = null;
        }
        DialectInference.Result inferred =
                DialectInference.infer(agentUrl, DataSourceUrlProbe.firstUrl(project));
        String name = inferred.dialectName();
        return name != null ? byName(name) : MySqlDialect.INSTANCE;
    }

    /** 手动模式无项目上下文时的兜底（按全局手动值）。 */
    private Dialect fallbackFrom(cn.song.mybatisplus.visualizer.config.PluginSettings.State settings) {
        return byName(settings != null ? settings.getDialect() : null);
    }
}
