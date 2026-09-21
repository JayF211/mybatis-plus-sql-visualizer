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
     * 标识符<b>按名字大小写来源</b>引用（O-42 / T-OracleQuoteCaseSource）。
     *
     * <p>与 {@link #quoteIfKeyword} 的差别：本方法额外知道该名字<b>从哪来</b>——同一串字符在两种来源下
     * 的「正确引用形态」可以相反。默认实现直接委托 {@link #quoteIfKeyword}（<b>其余方言逐字节不变</b>），
     * 只有对大小写敏感且会折叠的方言（Oracle）覆写。</p>
     *
     * <p>覆写者须遵守的分流口径：</p>
     * <ul>
     *   <li>{@link IdentifierCaseSource#CATALOG}：名字是<b>字典里的真实名字</b>（自省元数据返回，未归一）。
     *       Oracle 裸标识符折叠为大写，故「含小写字母」的名字<b>必须加引号</b>才能命中；全大写才可裸写；</li>
     *   <li>{@link IdentifierCaseSource#ENTITY}：名字是<b>小写派生名</b>（{@code @TableName}/{@code @TableField}
     *       注解值，或 camelCase → snake_case 的 {@code NamingStrategy.toSnakeCase} 产物）——这些正是
     *       「不带引号创建」的名字，折叠后恰好命中，<b>加引号反而必然失败</b>。故实体来源走既有
     *       {@link #quoteIfKeyword} 逐字不变（这是 C 方案「无条件收紧」被否决的依据）。</li>
     * </ul>
     *
     * <p>未接线点（{@link IdentifierCaseSource#ENTITY} 默认）维持旧行为——新增重载 + 逐点接线，
     * 不改既有函数语义。</p>
     */
    default String quoteIfNeeded(String identifier, IdentifierCaseSource caseSource) {
        return quoteIfKeyword(identifier);
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
     *   <li>MySQL 5.7/8.4、MariaDB：{@code EXPLAIN &lt;sql&gt;}（T-ExplainFirstNative 对齐 IDEA 原生
     *    MysqlBaseRawPlanData 表格形态——原生 Explain Plan 对 MySQL 家族跑的即裸 EXPLAIN
     *    表格行，按 id 嵌套建树；M37.2 曾用 {@code EXPLAIN FORMAT=JSON}，属自创形态且全树无
     *    渲染器消费，真机网格只能呈现一坨 JSON 文本）；</li>
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

    /**
     * T-ExplainDbl：幂等 EXPLAIN 包装——文本已以 EXPLAIN 关键字开头（词边界、忽略大小写）时
     * 原样返回（去首尾空白与结尾分号后），否则委托 {@link #renderExplainStatement}。
     *
     * 根因场景：首次点击「执行计划」后包装好的 EXPLAIN 语句经执行链写入控制台文档，
     * 再点一次在已包装文本上二次加前缀 → {@code EXPLAIN FORMAT=JSON EXPLAIN FORMAT=JSON SELECT ...}
     * → MySQL 1064 语法错误。
     */
    default String renderExplainStatementIdempotent(String sql) {
        String trimmed = sql.trim();
        while (trimmed.endsWith(";")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1).trim();
        }
        if (startsWithExplainKeyword(trimmed)) {
            return trimmed;
        }
        return renderExplainStatement(trimmed);
    }

    /**
     * DELETE 语句是否支持 `LIMIT n` 尾段（O-8 缺陷 C）：MySQL 语法族支持
     * （`DELETE FROM t WHERE ... LIMIT n`），PostgreSQL/Oracle/SQL Server 等不支持——
     * DELETE 的 `last("LIMIT n")` 尾段仅在本方言为 true 时渲染，否则静默省略
     * （与分页子句的方言渲染策略一致）。
     */
    default boolean getSupportsDeleteLimit() {
        return false;
    }

    /**
     * T-ExplainDbl：判断语句是否已以 EXPLAIN 关键字开头——紧随字符须非字母/数字/下划线
     * （词边界），避免 {@code EXPLAINING_TABLE} 之类标识符误判。
     */
    private static boolean startsWithExplainKeyword(String sql) {
        if (!sql.regionMatches(true, 0, "EXPLAIN", 0, 7)) {
            return false;
        }
        return sql.length() == 7
                || (!Character.isLetterOrDigit(sql.charAt(7)) && sql.charAt(7) != '_');
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
