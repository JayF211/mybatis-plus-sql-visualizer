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

    /** {@link #effective(com.intellij.openapi.project.Project)} 的无项目上下文形态（project=null）。 */
    public Dialect effective() {
        return effective(null);
    }

    /**
     * T-DialectAutoInfer：<b>渲染方言统一读取</b>（全部渲染消费点收口此函数）。
     *
     * <p>T-TS-FixC：auto/manual 的<b>模式判定与手动值解析的唯一事实源在本函数内部</b>，按
     * 「项目级 &gt; 全局」逐层解析（项目级语义由
     * {@link cn.song.mybatisplus.visualizer.config.ProjectPluginSettings} 的 effective* 方法承载）：</p>
     *
     * <ol>
     *  <li>模式：项目级 {@link cn.song.mybatisplus.visualizer.config.ProjectPluginSettings#effectiveDialectAuto}
     *      （启用项目设置且 dialectAuto 非 null 时取项目值）&gt; 全局
     *      {@code cn.song.mybatisplus.visualizer.config.PluginSettings.State.dialectAuto}（默认 true）。
     *      为什么项目级是权威：项目级「智能推断」是用户对该项目的<b>显式声明</b>，全局值只是<b>默认值</b>
     *      （与 ProjectPluginSettings.State 的「空/未覆盖=跟随全局」语义同构）。此前本函数直读全局
     *      auto，于是「项目 true + 全局 false」组合下项目声明被静默忽略——调用方已按项目级判为 auto
     *      进入本函数，却被全局 false 拉回手动分支返回全局手动值（T-TS-FixC 验证者实测口径缺陷）。</li>
     *  <li>auto=true → 智能推断（{@link DialectInference#infer}：agent 最近连接 URL &gt; IDE 数据源 URL），
     *      未命中回退 {@link MySqlDialect}（与 {@link #byName} 未知回退语义一致）。</li>
     *  <li>auto=false → 手动值：项目级
     *      {@link cn.song.mybatisplus.visualizer.config.ProjectPluginSettings#effectiveDialect}
     *      （启用且非空）&gt; 全局 {@code cn.song.mybatisplus.visualizer.config.PluginSettings.State.dialect}。</li>
     * </ol>
     *
     * <p>边界（<b>有意为之，非缺陷</b>）：{@code project}=null 时推断链不可用——
     * {@link cn.song.mybatisplus.visualizer.log.agent.AgentManager} 与 {@link DataSourceUrlProbe}
     * 都要求项目上下文，故落回手动值（无项目则取全局手动值）。psi 侧
     * {@code cn.song.mybatisplus.visualizer.psi.PsiDialect#currentDialect} 的调用点全部为无参调用，
     * 因此 PSI 路径 auto 推断不生效（与合流前一致，无回归）；要让 PSI 路径生效，须由调用方传入宿主
     * PSI 项目。</p>
     *
     * @param project 数据源探测与项目级设置读取用项目（null=无项目上下文，见上「边界」）
     */
    public Dialect effective(com.intellij.openapi.project.Project project) {
        cn.song.mybatisplus.visualizer.config.PluginSettings.State settings;
        try {
            settings = cn.song.mybatisplus.visualizer.config.PluginSettings.getInstance().getState();
        } catch (Throwable e) {
            settings = null;
        }
        // 手动值只解析一次：auto 分支的兜底与手动分支必须同源，否则两个分支会再次分叉
        String manualName = projectManualDialectName(project);
        if (manualName == null && settings != null) manualName = settings.getDialect();
        Boolean auto = projectDialectAuto(project);
        if (auto == null && settings != null) auto = settings.getDialectAuto();
        if (auto == null) auto = true;
        if (!auto) return byName(manualName);
        if (project == null) return byName(manualName);
        // T-PS5-04：agentUrl 读轻量持有者（不再经 AgentManager.getInstance 触发项目级服务实例化
        // ——那是「预览一次即驻留 executor+monitor 双守护线程」的根因）；URL 由会话生命周期点回写，
        // 推断优先级语义（agent URL > 数据源 URL > null 回退）逐字不变。
        String agentUrl = cn.song.mybatisplus.visualizer.log.agent.AgentUrlRegistry.lastConnectionUrl(project);
        DialectInference.Result inferred =
                DialectInference.infer(agentUrl, DataSourceUrlProbe.firstUrl(project));
        String name = inferred.dialectName();
        return name != null ? byName(name) : MySqlDialect.INSTANCE;
    }

    /**
     * T-TS-FixC：项目级有效方言模式（null=无项目上下文/项目级服务不可用，调用方按全局判定）。
     *
     * <p>复用 {@link cn.song.mybatisplus.visualizer.config.ProjectPluginSettings#effectiveDialectAuto}
     * 而非直读 {@code state.dialectAuto}：项目级方法内部已内置「总开关关闭 / dialectAuto=null → 跟随全局」
     * 回退语义，直读会把这层回退丢掉（T-TS-06 已在 SqlPreviewService 侧踩过同款）。</p>
     */
    private Boolean projectDialectAuto(com.intellij.openapi.project.Project project) {
        if (project == null) return null;
        try {
            return cn.song.mybatisplus.visualizer.config.ProjectPluginSettings
                    .getInstance(project).effectiveDialectAuto();
        } catch (Throwable e) {
            return null;
        }
    }

    /**
     * T-TS-FixC：项目级有效手动方言名（null=无项目上下文/项目级服务不可用，调用方按全局判定）。
     *
     * <p>同样复用 {@link cn.song.mybatisplus.visualizer.config.ProjectPluginSettings#effectiveDialect}
     * （启用且非空才取项目值，否则已回退全局）。</p>
     */
    private String projectManualDialectName(com.intellij.openapi.project.Project project) {
        if (project == null) return null;
        try {
            return cn.song.mybatisplus.visualizer.config.ProjectPluginSettings
                    .getInstance(project).effectiveDialect();
        } catch (Throwable e) {
            return null;
        }
    }
}
