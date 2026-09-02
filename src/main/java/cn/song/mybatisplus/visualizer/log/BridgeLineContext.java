package cn.song.mybatisplus.visualizer.log;

import kotlin.Pair;

import java.util.function.Supplier;

/**
 * 每个已挂载控制台持有的一组解析器 + 暂存源码位置（F-44-6）。
 * 解析器跨行保持配对状态（MyBatis 的 Preparing/Parameters 跨多行），故须按 console 持久化。
 * T-LogPerf-04：配置快照在挂载/设置变更时经 [SqlLogConsoleBridge.refreshConsoleContexts] 写入，
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
