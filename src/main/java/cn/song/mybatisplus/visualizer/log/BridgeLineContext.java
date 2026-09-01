package cn.song.mybatisplus.visualizer.log;

import kotlin.Pair;

import java.util.function.Supplier;

/**
 * 每个已挂载控制台持有的一组解析器 + 暂存源码位置（F-44-6）。
 * 解析器跨行保持配对状态（MyBatis 的 Preparing/Parameters 跨多行），故须按 console 持久化。
 */
final class BridgeLineContext {
    final MyBatisLogParser myBatisParser;
    final HibernateLogParser hibernateParser;
    final JdbcTemplateLogParser jdbcParser;
    final Supplier<ParserConfig> configProvider;
    Pair<String, Integer> pendingSource;

    BridgeLineContext(MyBatisLogParser myBatisParser, HibernateLogParser hibernateParser,
                       JdbcTemplateLogParser jdbcParser, Supplier<ParserConfig> configProvider,
                       Pair<String, Integer> pendingSource) {
        this.myBatisParser = myBatisParser;
        this.hibernateParser = hibernateParser;
        this.jdbcParser = jdbcParser;
        this.configProvider = configProvider;
        this.pendingSource = pendingSource;
    }
}
