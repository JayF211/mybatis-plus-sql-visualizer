package cn.song.mybatisplus.visualizer.ui.highlight;

import cn.song.mybatisplus.visualizer.model.TableInfo;
import kotlin.Pair;

import java.util.List;
import java.util.Map;

/**
 * SQL 补全来源抽象（M9-04，预留 Database 方案 B 切换点）。
 *
 * 方案 A（默认）：关键字 + 当前 Wrapper 实体上下文（{@link SqlCompletionContext}），
 * 保持 Community 兼容、不依赖 `com.intellij.database`。
 * 未来方案 B：引入 `com.intellij.database` 后替换本实现，用数据源 schema 补全，
 * 仅需换 {@link Companion#getInstance()} 的返回（UI/补全器不动）。
 */
public interface SqlCompletionProvider {

    /** 补全用的 SQL 关键字（大写）。 */
    List<String> keywords();

    /** 主表名。 */
    default String tableName(SqlCompletionContext context) {
        return context.tableName;
    }

    /** 列名集合。 */
    default List<String> columns(SqlCompletionContext context) {
        return context.columns;
    }

    /** 列名 + 注释（M16：补全 tailText 展示与注释关键词检索；无注释列返回 null）。 */
    default List<Pair<String, String>> columnsWithComments(SqlCompletionContext context) {
        return context.columns.stream()
                .map(it -> new Pair<>(it, context.columnComments.get(it)))
                .toList();
    }

    /** 主表别名（可为 null）。 */
    default String tableAlias(SqlCompletionContext context) {
        return context.tableAlias;
    }

    /** 表注释（M16：表名补全 tailText 与关键词检索）。 */
    default String tableComment(SqlCompletionContext context) {
        return context.tableComment;
    }

    /** T-M27.4：联表候选表（@TableName 实体，含注释；表名补全全位置提示）。 */
    default List<TableInfo> candidateTables(SqlCompletionContext context) {
        return context.candidateTables;
    }

    /**
     * 当前提供器（默认方案 A）的伴随对象（等价 Kotlin `companion object`）。
     * 对应 Kotlin `SqlCompletionProvider.instance`（可读写，方案 B 切换点）。
     */
    final class Companion {
        /** 当前提供器（默认方案 A）。 */
        public SqlCompletionProvider instance = DefaultSqlCompletionProvider.INSTANCE;

        public SqlCompletionProvider getInstance() {
            return instance;
        }

        public void setInstance(SqlCompletionProvider value) {
            instance = value;
        }
    }

    Companion Companion = new Companion();

    /**
     * 等价 Kotlin `SqlCompletionProvider.instance`（供仍-Kotlin 调用方按属性名访问）。
     */
    static SqlCompletionProvider getInstance() {
        return Companion.instance;
    }

    /**
     * 等价 Kotlin `SqlCompletionProvider.instance = ...`（方案 B 切换点写入）。
     */
    static void setInstance(SqlCompletionProvider value) {
        Companion.instance = value;
    }
}
