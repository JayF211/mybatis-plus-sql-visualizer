package cn.song.mybatisplus.visualizer.log;

import cn.song.mybatisplus.visualizer.config.PluginSettings;
import com.intellij.execution.filters.ConsoleFilterProvider;
import com.intellij.execution.filters.Filter;
import com.intellij.openapi.project.Project;

/**
 * M56 T-M56-09：consoleFilterProvider EP 双轨——EP 为主捕获通道（平台在控制台创建时自动
 * 装配本 provider 返回的 Filter，无轮询发现面），反射轮询降为兜底。
 *
 * 「EP 首行标志」生效判定：EP Filter 首次真正处理控制台行时置 {@link SqlCaptureGate#epActive}
 * （会话内粘滞）——桥的常驻轮询据此跳过 addMessageFilter 挂载（EP 已覆盖全部控制台，
 * 双挂载会双路解析）；定时器本身保留（控制台摘除检测等职责）。真机门禁：runIde 观测
 * EP 生效且轮询停转——留人工（本验收项计划标注「真机门禁先行」）。
 *
 * 每个 Filter 实例持有独立 {@link BridgeLineContext}（平台每控制台创建一次 provider 调用，
 * 实例即 per-console 状态）；重放守卫降级为直通（EP Filter 不持有 console 引用，取不到
 * 编辑器文档）——rehighlight 重放由去重口径兜底（同 SQL 同指纹）。
 *
 * 捕捉态机：走与桥同款 capturing 守卫（{@link SqlLogManager#isCapturing()}），非捕捉态零处理。
 */
public class SqlConsoleBridgeFilterProvider implements ConsoleFilterProvider {

    @Override
    public Filter[] getDefaultFilters(Project project) {
        SqlLogManager manager = project.getService(SqlLogManager.class);
        return new Filter[]{new EpLineFilter(project, manager)};
    }

    /** EP 行过滤器：实例即 per-console 解析状态。 */
    private static final class EpLineFilter implements Filter {

        private final Project project;
        private final SqlLogManager manager;
        private final BridgeLineContext ctx;
        private boolean firstLineSeen = false;

        EpLineFilter(Project project, SqlLogManager manager) {
            this.project = project;
            this.manager = manager;
            this.ctx = new BridgeLineContext(
                    new MyBatisLogParser(
                            parserConfig().sqlPrefix(),
                            parserConfig().paramPrefix(),
                            parserConfig().mixedPrefix(),
                            parserConfig().maxParseLength()),
                    new HibernateLogParser(
                            parserConfig().hibernateSqlPrefix(),
                            parserConfig().hibernateParamPrefix(),
                            parserConfig().mixedPrefix(),
                            parserConfig().maxParseLength()),
                    new JdbcTemplateLogParser(
                            parserConfig().jdbcEnabled(),
                            parserConfig().maxParseLength()),
                    EpLineFilter::parserConfig,
                    parserConfig().hibernateEnabled());
        }

        private static ParserConfig parserConfig() {
            PluginSettings.State state = PluginSettings.getInstance().getState();
            return new ParserConfig(
                    state.getLogSqlPrefix(),
                    state.getLogParamPrefix(),
                    state.getLogMixedPrefix(),
                    state.getLogMaxParseLength(),
                    state.getLogHibernateSqlPrefix(),
                    state.getLogHibernateParamPrefix(),
                    state.getLogHibernateEnabled(),
                    state.getLogJdbcEnabled());
        }

        @Override
        public Result applyFilter(String line, int entireLength) {
            // 捕捉态守卫（与桥同款状态机）：非捕捉态零处理
            if (!manager.isCapturing()) {
                return null;
            }
            if (!firstLineSeen) {
                firstLineSeen = true;
                // 「EP 首行标志」：EP 通道实证生效 → 桥轮询跳过 filter 挂载（会话内粘滞）
                SqlCaptureGate.epActive = true;
            }
            String rawTimePrefix = PluginSettings.getInstance().getState().getLogTimePrefix();
            String timePrefix = rawTimePrefix != null && !rawTimePrefix.isBlank() ? rawTimePrefix : null;
            for (LogSqlEntry entry : SqlLogConsoleBridgeKt.processConsoleLine(line, ctx, timePrefix, () -> {
            })) {
                manager.getEntries().addEntry(entry);
            }
            return null; // 不做高亮/链接——纯捕获通道
        }
    }
}
