package cn.song.mybatisplus.visualizer.log;

import cn.song.mybatisplus.visualizer.config.PluginSettings;
import com.intellij.execution.filters.ConsoleFilterProvider;
import com.intellij.execution.filters.Filter;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;

import java.util.List;

/**
 * M56 T-M56-09：consoleFilterProvider EP 双轨——EP 为主捕获通道（平台在控制台创建时自动
 * 装配本 provider 返回的 Filter，无轮询发现面），反射轮询降为兜底。
 *
 * <p>「EP 实证产出」生效判定（T-CaptureTruth T-CT-04 收窄）：EP Filter 真正<b>产出过一条 SQL 条目</b>
 * 时才置 {@link SqlCaptureGate#epActive}（会话内粘滞）——桥据此短路自身的重复解析；桥的<b>挂载面不再
 * 跳过</b>（T-CT-04 起），即 EP 未在链上或对该控制台产不出条目时，桥照常解析保底。
 * 原口径「EP 被调用过即置位 + 桥挂载面跳过」的双重假设从未真机验证（下方真机门禁项），
 * 一旦不成立就是静默零捕获——故改为「先交条目再获信任」。
 * 真机门禁：runIde 观测 EP 与桥的单路化（短路计数递增且条目不重）——留人工。
 *
 * <p>每个 Filter 实例持有独立 {@link BridgeLineContext}（平台每控制台创建一次 provider 调用，
 * 实例即 per-console 状态）；重放守卫降级为直通（EP Filter 不持有 console 引用，取不到
 * 编辑器文档）——rehighlight 重放由去重口径兜底（同 SQL 同指纹）。
 *
 * <p>捕捉态机：走与桥同款 capturing 守卫（{@link SqlLogManager#isCapturing()}），非捕捉态零处理。
 */
public class SqlConsoleBridgeFilterProvider implements ConsoleFilterProvider {

    private static final Logger LOG = Logger.getInstance(SqlConsoleBridgeFilterProvider.class);

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
            // T-PS4-02：timePrefix 不再逐行读 PluginSettings——经 manager 桥侧缓存取（对齐桥侧
            // timePrefixCache 形态，刷新点一致：挂载/设置变更；EP 首行早于桥挂载时惰性补种）。
            // 值语义与原逐行读取一致（blank 归 null）。
            String timePrefix = manager.getConsoleBridge().currentTimePrefix();
            List<LogSqlEntry> produced = SqlLogConsoleBridgeKt.processConsoleLine(
                    line, ctx, timePrefix, () -> {
                    }, this::bindConsoleError);
            // T-CaptureTruth T-CT-04：置位条件由「处理过任意一行」收窄为「实证产出过条目」——
            // EP 被平台调用 ≠ EP 真在链上产出（覆盖不全时原口径会封死桥的回落通道 → 静默零捕获）。
            // 置位前桥侧照常解析；置位后桥只短路重复解析，条目仍由 EP 单路产出。
            if (!produced.isEmpty() && !SqlCaptureGate.epActive) {
                SqlCaptureGate.epActive = true;
            }
            for (LogSqlEntry entry : produced) {
                manager.getEntries().addEntry(entry);
            }
            return null; // 不做高亮/链接——纯捕获通道
        }

        /**
         * T-M55-02：错误反向绑定与桥同款（丢弃留 debug 痕）。EP 通道无重放守卫，
         * rehighlight 重放场景靠同文本幂等绑定兜底（文本相同不再替换实例）。
         */
        private void bindConsoleError(String errorText) {
            SqlLogEntryBuffer.ErrorBindResult result = manager.getEntries().bindErrorToRecent(errorText);
            if (result != SqlLogEntryBuffer.ErrorBindResult.BOUND) {
                // errorText.lineSequence().firstOrNull()?.take(120) 等价：取首行前 120 字符
                String firstLine = errorText;
                int nl = firstLine.indexOf('\n');
                if (nl >= 0) firstLine = firstLine.substring(0, nl);
                if (firstLine.length() > 120) firstLine = firstLine.substring(0, 120);
                LOG.debug("EpLineFilter: 错误块绑定丢弃（" + result + "）：" + firstLine);
            }
        }
    }

}
