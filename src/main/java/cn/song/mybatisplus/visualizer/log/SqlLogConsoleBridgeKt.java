package cn.song.mybatisplus.visualizer.log;

import kotlin.Pair;

import java.awt.Component;
import java.awt.Container;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * {@code SqlLogConsoleBridge.kt} 顶层函数（{@code processConsoleLine} / {@code findConsoleView}）的 Java 承载类。
 */
public final class SqlLogConsoleBridgeKt {
    private SqlLogConsoleBridgeKt() {
    }

    /**
     * 处理一行控制台文本（F-44-6）：
     *  1. 若为本插件源码标记（[SqlSourceInterceptor] 注入）→ 暂存，不喂解析器；
     *  2. 同步解析器实时配置后喂行，取出已配对 SQL 条目；
     *  3. 附加耗时解析结果 + 暂存源码位置，写入 [LogSqlEntry]。
     * 纯函数式、无 IDE 依赖，可在单元测试中直接驱动。
     */
    public static List<LogSqlEntry> processConsoleLine(
            String line,
            BridgeLineContext ctx,
            String timePrefix,
            Runnable syncBufferConfig
    ) {
        // 1) 实时源码标记 → 暂存（不喂解析器，避免被当成 SQL）
        Pair<String, Integer> marker = SqlSourceMarker.parse(line);
        if (marker != null) {
            ctx.pendingSource = marker;
            return List.of();
        }
        // 2) 每次喂行前同步解析器配置（捕捉中实时生效，对齐 T-M31.18）
        ParserConfig cfg = ctx.configProvider.get();
        ctx.myBatisParser.setSqlPrefix(cfg.sqlPrefix());
        ctx.myBatisParser.setParamPrefix(cfg.paramPrefix());
        ctx.myBatisParser.setMixedPrefix(cfg.mixedPrefix());
        ctx.myBatisParser.setMaxParseLength(cfg.maxParseLength());
        ctx.hibernateParser.setSqlPrefix(cfg.hibernateSqlPrefix());
        ctx.hibernateParser.setParamPrefix(cfg.hibernateParamPrefix());
        ctx.hibernateParser.setMixedPrefix(cfg.mixedPrefix());
        ctx.hibernateParser.setMaxParseLength(cfg.maxParseLength());
        ctx.jdbcParser.setEnabled(cfg.jdbcEnabled());
        ctx.jdbcParser.setMaxParseLength(cfg.maxParseLength());
        // 3) 喂行到三个解析器（各自忽略不匹配的行）
        ctx.myBatisParser.feed(line);
        if (cfg.hibernateEnabled()) {
            ctx.hibernateParser.feed(line);
        }
        ctx.jdbcParser.feed(line);
        if (syncBufferConfig != null) {
            syncBufferConfig.run();
        }
        List<LogSqlEntry> taken = new ArrayList<>();
        taken.addAll(ctx.myBatisParser.takeEntries());
        taken.addAll(ctx.hibernateParser.takeEntries());
        taken.addAll(ctx.jdbcParser.takeEntries());
        if (taken.isEmpty()) {
            return List.of();
        }
        // 4) 耗时 + 源码位置（标记不可用时 sourceFile/sourceLine 为 null，回退表名搜索）
        Long executionTimeMs = ExecutionTimeParser.parse(line, timePrefix);
        Pair<String, Integer> src = ctx.pendingSource;
        List<LogSqlEntry> result = new ArrayList<>();
        for (LogSqlEntry e : taken) {
            result.add(new LogSqlEntry(
                    e.rawSql(),
                    e.restoredSql(),
                    e.params(),
                    e.timestamp(),
                    e.summary(),
                    e.source(),
                    executionTimeMs != null ? executionTimeMs : e.executionTimeMs(),
                    src != null ? src.getFirst() : e.sourceFile(),
                    src != null ? src.getSecond() : e.sourceLine()));
        }
        if (src != null) {
            ctx.pendingSource = null;
        }
        return result;
    }

    /** 组件树中查找实现 `com.intellij.execution.ui.ConsoleView` 接口的组件（接口名匹配，无编译期引用）。 */
    public static Object findConsoleView(Component component) {
        if (component == null) {
            return null;
        }
        if (implementsConsoleView(component.getClass())) {
            return component;
        }
        if (component instanceof Container) {
            for (Component child : ((Container) component).getComponents()) {
                Object r = findConsoleView(child);
                if (r != null) {
                    return r;
                }
            }
        }
        return null;
    }

    /** 类（含父类/父接口链）是否实现 ConsoleView 接口（接口名匹配）。 */
    private static boolean implementsConsoleView(Class<?> clazz) {
        if (clazz == null) {
            return false;
        }
        for (Class<?> it : clazz.getInterfaces()) {
            if (CONSOLE_VIEW_IFACE_NAME.equals(it.getName())) {
                return true;
            }
        }
        for (Class<?> it : clazz.getInterfaces()) {
            if (implementsConsoleView(it)) {
                return true;
            }
        }
        Class<?> sup = clazz.getSuperclass();
        return sup != null && implementsConsoleView(sup);
    }

    private static final String CONSOLE_VIEW_IFACE_NAME = "com.intellij.execution.ui.ConsoleView";
}
