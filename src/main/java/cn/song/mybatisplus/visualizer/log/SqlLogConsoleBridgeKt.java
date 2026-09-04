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
     *  2. 喂行到三个解析器（配置快照已由设置变更事件同步——T-LogPerf-04），取出已配对 SQL 条目；
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
        // 2) 喂行到三个解析器（各自忽略不匹配的行；配置快照随设置变更事件刷新，逐行零同步成本）
        ctx.myBatisParser.feed(line);
        if (ctx.hibernateEnabled()) {
            ctx.hibernateParser.feed(line);
        }
        ctx.jdbcParser.feed(line);
        List<LogSqlEntry> taken = new ArrayList<>();
        taken.addAll(ctx.myBatisParser.takeEntries());
        taken.addAll(ctx.hibernateParser.takeEntries());
        taken.addAll(ctx.jdbcParser.takeEntries());
        if (taken.isEmpty()) {
            return List.of();
        }
        // 3) 耗时 + 源码位置（标记不可用时 sourceFile/sourceLine 为 null，回退表名搜索）
        // 解析策略：优先从日志行文本解析（ExecutionTimeParser），命中则统一填入；
        // 未命中时对每个条目尝试从 rawSql/restoredSql 解析（LogDurationParser，覆盖 druid/p6spy 格式）
        Long lineDuration = ExecutionTimeParser.parse(line, timePrefix);
        Pair<String, Integer> src = ctx.pendingSource;
        if (src == null) {
            // F-44-7：无标记时，检测是否为 MP 分页 COUNT 查询 → 标记为内部查询
            boolean isCountQuery = false;
            for (LogSqlEntry t : taken) {
                if (t.restoredSql().stripLeading().toUpperCase(java.util.Locale.ROOT).startsWith("SELECT COUNT(")) {
                    isCountQuery = true;
                    break;
                }
            }
            if (isCountQuery) {
                src = SourceMarkers.mpPaginationCount();
            }
        }
        List<LogSqlEntry> result = new ArrayList<>();
        for (LogSqlEntry e : taken) {
            Long duration = lineDuration != null ? lineDuration : LogDurationParser.parse(e);
            result.add(new LogSqlEntry(
                    e.rawSql(),
                    e.restoredSql(),
                    e.params(),
                    e.timestamp(),
                    e.summary(),
                    e.source(),
                    duration != null ? duration : e.executionTimeMs(),
                    src != null ? src.getFirst() : e.sourceFile(),
                    src != null ? src.getSecond() : e.sourceLine()));
        }
        if (ctx.pendingSource != null) {
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
