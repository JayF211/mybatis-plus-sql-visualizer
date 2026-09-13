package cn.song.mybatisplus.visualizer.sql;

import cn.song.mybatisplus.visualizer.model.LimitClause;
import cn.song.mybatisplus.visualizer.model.SqlValue;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 数据库方言 SPI（T-M5-03/F-14）。
 *
 * <p>方言差异点（M5 实现范围）：
 *  - 分页子句：{@link #renderLimit}（MySQL 用 LIMIT，PostgreSQL 用 LIMIT+OFFSET，Oracle 用 FETCH）
 *  - 标识符引用：{@link #quoteIdentifier}（MySQL 反引号，PG/Oracle 双引号；默认不引用，按需启用）
 *  - 关键字判定：{@link #getReservedWords}（T-M23.4 按方言保留字集合，{@link #quoteIfKeyword} 只判本方言）
 *  - upsert 冲突子句：{@link #renderUpsert} 与 {@link #renderUpsertStatement}（P-立项-05）
 *
 * <p>新增方言：实现本接口并注册进 {@link DialectRegistry}。
 */
public interface Dialect {

    /** 方言名称（与 Settings 下拉项一致）。 */
    String getName();

    /**
     * 渲染完整分页子句（含关键字，如 {@code LIMIT 10} / {@code LIMIT 10 OFFSET 5}）。
     *
     * @param limit 分页模型
     * @return 追加到 SQL 末尾的分页子句
     */
    String renderLimit(LimitClause limit);

    /**
     * 标识符引用（表名/列名包裹）。
     * 默认原样返回（不引用）；需要时子类覆写（如 MySQL 反引号）。
     */
    default String quoteIdentifier(String identifier) {
        return identifier;
    }

    /**
     * 本方言保留字集合（T-M23.4，小写；{@link #quoteIfKeyword} 判定用）。
     */
    Set<String> getReservedWords();

    /**
     * 标识符<b>按需引用</b>（T-M23.4）：命中<b>本方言</b>保留字（大小写不敏感）→ {@link #quoteIdentifier}
     * 包裹；否则原样返回。
     */
    default String quoteIfKeyword(String identifier) {
        if (getReservedWords().contains(identifier.toLowerCase())) return quoteIdentifier(identifier);
        return identifier;
    }

    /**
     * UPSERT 冲突子句（T-M43）。返回 null → 本方言无原生 upsert，渲染降级纯 INSERT。
     *
     * @param conflictKey   冲突键列名（原始值）
     * @param updateColumns 冲突后更新列名（原始值，不含冲突键）
     */
    default String renderUpsert(String conflictKey, List<String> updateColumns) {
        return null;
    }

    /**
     * UPSERT <b>整语句</b>形态（P-立项-05）：方言原生 upsert 非「INSERT + 后缀」结构时使用。
     * 返回非 null → 渲染方直接使用该整语句；默认返回 null → 走 {@link #renderUpsert} 后缀形态。
     *
     * @param tableName   表名（原始值）
     * @param columns     全部列名（原始值，含冲突键）
     * @param conflictKey 冲突键列名（原始值；必在 columns 内）
     */
    default String renderUpsertStatement(String tableName, List<String> columns, String conflictKey) {
        return null;
    }

    /**
     * EXPLAIN 执行计划语句（M37.2 / P-立项-06 N-1）：把任意查询包装为本方言的 EXPLAIN 形态。
     *
     * <p>返回 null → 本方言不支持（调用方按钮降级提示）。支持矩阵真机实证（DBX 通道 2026-09-02）：
     * <ul>
     *   <li>MySQL 5.7/8.4、MariaDB：{@code EXPLAIN FORMAT=JSON}（MySQL 8.4/5.7 实测，MariaDB 同语法族）；</li>
     *   <li>PostgreSQL：{@code EXPLAIN (FORMAT JSON, VERBOSE)}（PG 16 实测）；</li>
     *   <li>KingBase：{@code EXPLAIN (FORMAT JSON)}（KingbaseES V8 实测，PG 兼容完整）；</li>
     *   <li>DM：{@code EXPLAIN &lt;sql&gt;}（DM8 实测，返回 #NSET2 计划文本）；</li>
     *   <li>H2：{@code EXPLAIN PLAN FOR}；SQLite：{@code EXPLAIN QUERY PLAN}（引擎级单测覆盖）；</li>
     *   <li>Oracle（需两步链查 PLAN_TABLE）与 SQL Server（需会话级 SET SHOWPLAN_XML）不接入。</li>
     * </ul>
     *
     * @param sql 不含结尾分号的查询语句
     * @return 可直接提交执行的 EXPLAIN 语句，null = 不支持
     */
    default String renderExplainStatement(String sql) {
        return null;
    }

    /** 共享渲染辅助：MySQL 形态 {@code LIMIT [offset,] count}。 */
    static String renderMySqlStyleLimit(LimitClause limit) {
        String offsetPart = limit.getOffset() != null ? renderLimitValue(limit.getOffset()) + ", " : "";
        return "LIMIT " + offsetPart + renderLimitValue(limit.getCount());
    }

    /** 共享渲染辅助：PostgreSQL 形态 {@code LIMIT {count} OFFSET {offset}}。 */
    static String renderPgStyleLimit(LimitClause limit) {
        String offsetPart = limit.getOffset() != null ? " OFFSET " + renderLimitValue(limit.getOffset()) : "";
        return "LIMIT " + renderLimitValue(limit.getCount()) + offsetPart;
    }

    /** 分页子句数值渲染——字面量原样、运行时值 {@code ?}。 */
    static String renderLimitValue(SqlValue value) {
        if (value instanceof SqlValue.Literal l) return l.getRaw();
        return "?";
    }

    /** 共享 upsert 辅助：MySQL 形态 {@code ON DUPLICATE KEY UPDATE col = VALUES(col), ...}。 */
    static String renderMySqlStyleUpsert(Dialect self, List<String> updateColumns) {
        return "ON DUPLICATE KEY UPDATE " + updateColumns.stream()
                .map(it -> self.quoteIfKeyword(it) + " = VALUES(" + self.quoteIfKeyword(it) + ")")
                .collect(Collectors.joining(", "));
    }

    /** 共享 upsert 辅助：PostgreSQL 形态 {@code ON CONFLICT (pk) DO UPDATE SET col = EXCLUDED.col, ...}。 */
    static String renderPgStyleUpsert(Dialect self, String conflictKey, List<String> updateColumns) {
        return "ON CONFLICT (" + self.quoteIfKeyword(conflictKey) + ") DO UPDATE SET " +
                updateColumns.stream()
                        .map(it -> self.quoteIfKeyword(it) + " = EXCLUDED." + self.quoteIfKeyword(it))
                        .collect(Collectors.joining(", "));
    }
}
