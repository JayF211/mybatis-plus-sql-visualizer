package cn.song.mybatisplus.visualizer.log;

import kotlin.Pair;

import java.util.function.Supplier;

/**
 * 每个已挂载控制台持有的一组解析器 + 暂存源码位置（F-44-6）。
 * 解析器跨行保持配对状态（MyBatis 的 Preparing/Parameters 跨多行），故须按 console 持久化。
 * T-LogPerf-04：配置快照在挂载/设置变更时经 {@link SqlLogConsoleBridge#refreshConsoleContexts} 写入，
 * 逐行不再调 configProvider 同步（字段保留供测试直接构造）。
 */
final class BridgeLineContext {
    final MyBatisLogParser myBatisParser;
    final HibernateLogParser hibernateParser;
    final JdbcTemplateLogParser jdbcParser;
    final Supplier<ParserConfig> configProvider;
    Pair<String, Integer> pendingSource;
    /** Hibernate 喂行开关（快照同步自 ParserConfig.hibernateEnabled）。 */
    boolean hibernateEnabled;

    /** T-PreviewFlash：重放守卫（每 console 独立；editor 不可得时降级为恒 Process）。 */
    final ConsoleReplayGuard replayGuard = new ConsoleReplayGuard();

    /** 反射缓存的控制台 Editor（ConsoleView.getEditor，守护判定降级用；null = 守卫不读文档）。 */
    Object replayGuardEditor;

    /** 控制台文档 modificationStamp 读取函数（editor 不可得时为 null）。 */
    Supplier<Long> docStampReader;

    /** 控制台首行文本读取函数（混合态判定用；editor 不可得时为 null）。 */
    Supplier<String> line0Reader;

    /** 重放丢弃行累计计数（debug 日志验证锚点）。 */
    volatile long replayDropCount;

    BridgeLineContext(MyBatisLogParser myBatisParser, HibernateLogParser hibernateParser,
                       JdbcTemplateLogParser jdbcParser, Supplier<ParserConfig> configProvider,
                       boolean hibernateEnabled) {
        this.myBatisParser = myBatisParser;
        this.hibernateParser = hibernateParser;
        this.jdbcParser = jdbcParser;
        this.configProvider = configProvider;
        this.pendingSource = null;
        this.hibernateEnabled = hibernateEnabled;
    }

    boolean hibernateEnabled() {
        return hibernateEnabled;
    }
}
