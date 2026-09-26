// T-SyncK2JMaster309 自 master sql/rollback/RollbackSqlGenerator.kt 移植
// T-SyncK2JMaster115 自 master src/main/kotlin/cn/song/mybatisplus/visualizer/sql/rollback/RollbackSqlGenerator.kt 移植（P8：快照上限警示补传 {1} 上限实参；同提交的 P11 补键在 resources 侧，本文件未动）
// T-SyncK2JMaster187 c07b8e3 增量：方言需包装而自增列未知时补显式告警 rollback.warn.identityUnknown
// （不再静默产出必失败的 INSERT；探针 SQL 判「本方言有无包装形态」）
package cn.song.mybatisplus.visualizer.sql.rollback;

import cn.song.mybatisplus.visualizer.execute.UnboundedWriteDetector;
import cn.song.mybatisplus.visualizer.sql.Dialect;
import cn.song.mybatisplus.visualizer.sql.SqlCommentStripper;
import kotlin.jvm.functions.Function1;
import net.sf.jsqlparser.expression.DateValue;
import net.sf.jsqlparser.expression.DoubleValue;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.HexValue;
import net.sf.jsqlparser.expression.JdbcNamedParameter;
import net.sf.jsqlparser.expression.JdbcParameter;
import net.sf.jsqlparser.expression.LongValue;
import net.sf.jsqlparser.expression.NullValue;
import net.sf.jsqlparser.expression.SignedExpression;
import net.sf.jsqlparser.expression.StringValue;
import net.sf.jsqlparser.expression.TimeValue;
import net.sf.jsqlparser.expression.TimestampValue;
import net.sf.jsqlparser.expression.operators.relational.ExpressionList;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.schema.Column;
import net.sf.jsqlparser.statement.delete.Delete;
import net.sf.jsqlparser.statement.insert.Insert;
import net.sf.jsqlparser.statement.update.Update;
import net.sf.jsqlparser.statement.update.UpdateSet;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 回滚 SQL 生成器（T-RollbackSql P1-F1/F2，dbx 精华融合）。
 *
 * <p>吸收 t8y2/dbx（调研基线 `dbaa640`）的安全设计核心——「有 before-image 才生成回滚」：
 * 预览窗口里的 SQL 来自应用日志/静态推导，<b>插件没有 before-image</b>，因此本生成器只对
 * 「能从文本诚实推导」的语句产出可执行回滚（INSERT→DELETE 全列等值谓词），其余类型
 * （UPDATE/DELETE 无原值可考、MERGE/DDL 不可逆推）诚实拒绝并给人工恢复指引 + 自查
 * SELECT 模板，不产出危险半成品。
 *
 * <p>贯穿设计（dbx 对应物）：
 * <ul>
 *  <li>完整性三档评级 {@link Rating}（dbx 两档 Complete/Incomplete 的三档化，中间态 Partial =
 *      「可执行但带风险警示，需先跑 {@link RollbackResult#countCheckSql()} 自查」）；</li>
 *  <li>上限防呆（500 语句 / 256KB，对齐 dbx data_grid_sql.rs:41-42），超限截断并降级 Partial；</li>
 *  <li>无主键整行谓词的风险警示 + COUNT 自查模板（dbx keyless guard 的客户端对应物）；</li>
 *  <li>主键偏好留缝：{@link #generate} 接收可选 `primaryKeyColumns`，传入且主键列在语句列清单内时
 *      优先主键定位（评级 Complete）；P1 调用方传 null 走整行谓词。</li>
 * </ul>
 *
 * <p>语句切分用 JSqlParser `parseStatements`（引号内分号安全——回滚工具不允许把字符串字面量
 * 里的 `;` 切成半条语句）；语句类型分发按解析出的 AST 类型（parser 权威），文本级判定
 * 复用 `SqlTypeClassifier`（isQuery/getFirstKeyword）的调用方保持不变。
 *
 * <p>零 PSI / 零 IDE 依赖（仅 JSqlParser + SqlCommentStripper），普通 JUnit 可直接覆盖。
 *
 * <p>双轨别名约定（T-DmlAliasGuard，O-27）：<b>别名只允许出现在查询源位（FROM/JOIN），DML
 * 目标位（INSERT INTO / UPDATE / DELETE FROM 后紧跟的表名）禁别名</b>——本链产物（DELETE
 * 回滚/重插模板/COUNT 自查/快照 SELECT）现状零别名，未来重构引入别名先撞
 * `DmlAliasGuardTest` 红灯（约定全文见 CrudSqlGenerator 类头与计划 20260917-0150）。
 */
public final class RollbackSqlGenerator {

    private RollbackSqlGenerator() {}

    /** 回滚完整性评级（dbx RollbackCompleteness 三档化）。 */
    public enum Rating {
        /** 可直接执行（主键等值定位，唯一命中）。 */
        COMPLETE,
        /** 可执行但带风险警示（整行谓词可能误匹配多行 / 占位符未回填 / 超限截断），先跑 countCheckSql 自查。 */
        PARTIAL,
        /** 不可自动回滚（无 before-image / 类型不可逆推），statements 恒空。 */
        UNABLE,
    }

    /** 警告/指引条目（本地化键 + 参数，PerfWarning 同款结构——生成器零 UI 依赖）。 */
    public record RollbackWarning(
            /** 本地化消息键。 */
            @NotNull String messageKey,
            /** 消息参数（可空清单）。 */
            @NotNull List<String> messageArgs
    ) {
        /** 无参形态（messageArgs 为空清单）。 */
        public RollbackWarning(@NotNull String messageKey) {
            this(messageKey, List.of());
        }
    }

    /**
     * 回滚生成结果。
     *
     * @param rating            完整性评级
     * @param statements        可执行回滚语句（UNABLE 时恒空；多条按执行顺序排列）
     * @param warnings          警示/指引（本地化键，UI 层经 bundle 渲染）
     * @param countCheckSql     执行前自查模板（如 `SELECT COUNT(*) FROM t WHERE …`；无风险时 null）
     * @param targetTable       目标表名（解析成功时非空；UNABLE 的 UPDATE/DELETE 快照工作流用）
     * @param whereText         原语句 WHERE 子句文本（UPDATE/DELETE；无 WHERE 为 null）
     * @param setColumns        UPDATE 的 SET 列名清单（非 UPDATE 恒空）
     * @param setValues         UPDATE 的 SET 列 → 写入后新值（SQL 字面量渲染，如 `'abc'`/`3`/`NULL`）——
     *                          快照回填 REVERT_UPDATE 的乐观锁谓词右值（非 UPDATE 恒空 Map）
     * @param snapshotSelectSql 快照查询模板 `SELECT * FROM t [WHERE …]`（UPDATE/DELETE 时非空，
     *                          P2 粘贴快照回填与自查共用；INSERT/其他恒 null）
     */
    public record RollbackResult(
            /** 完整性评级。 */
            @NotNull Rating rating,
            /** 可执行回滚语句（UNABLE 时恒空；多条按执行顺序排列）。 */
            @NotNull List<String> statements,
            /** 警示/指引（本地化键，UI 层经 bundle 渲染）。 */
            @NotNull List<RollbackWarning> warnings,
            /** 执行前自查模板（如 `SELECT COUNT(*) FROM t WHERE …`；无风险时 null）。 */
            @Nullable String countCheckSql,
            /** 目标表名（解析成功时非空；UNABLE 的 UPDATE/DELETE 快照工作流用）。 */
            @Nullable String targetTable,
            /** 原语句 WHERE 子句文本（UPDATE/DELETE；无 WHERE 为 null）。 */
            @Nullable String whereText,
            /** UPDATE 的 SET 列名清单（非 UPDATE 恒空）。 */
            @NotNull List<String> setColumns,
            /** UPDATE 的 SET 列 → 写入后新值（SQL 字面量渲染，如 `'abc'`/`3`/`NULL`）。 */
            @NotNull Map<String, String> setValues,
            /** 快照查询模板 `SELECT * FROM t [WHERE …]`（UPDATE/DELETE 时非空；INSERT/其他恒 null）。 */
            @Nullable String snapshotSelectSql
    ) {
        /** UNABLE 捷径（无元信息场景：解析失败/类型不可逆推）。 */
        public static RollbackResult unable(RollbackWarning... warnings) {
            return new RollbackResult(Rating.UNABLE, List.of(), List.of(warnings), null);
        }

        /** 常用形态：targetTable 及之后的可选元信息取默认（null/空集合）。 */
        public RollbackResult(@NotNull Rating rating, @NotNull List<String> statements,
                              @NotNull List<RollbackWarning> warnings, @Nullable String countCheckSql) {
            this(rating, statements, warnings, countCheckSql, null, null, List.of(), Map.of(), null);
        }

        /** 常用形态：带 targetTable（whereText/setColumns/setValues/snapshotSelectSql 取默认）。 */
        public RollbackResult(@NotNull Rating rating, @NotNull List<String> statements,
                              @NotNull List<RollbackWarning> warnings, @Nullable String countCheckSql,
                              @Nullable String targetTable) {
            this(rating, statements, warnings, countCheckSql, targetTable, null, List.of(), Map.of(), null);
        }

        /** 常用形态：merge 产物（setValues 取默认空 Map）。 */
        public RollbackResult(@NotNull Rating rating, @NotNull List<String> statements,
                              @NotNull List<RollbackWarning> warnings, @Nullable String countCheckSql,
                              @Nullable String targetTable, @Nullable String whereText,
                              @NotNull List<String> setColumns, @Nullable String snapshotSelectSql) {
            this(rating, statements, warnings, countCheckSql, targetTable, whereText, setColumns, Map.of(),
                    snapshotSelectSql);
        }
    }

    /** enforceLimits 产物。 */
    private record LimitCheck(
            /** 超限截断后的语句清单。 */
            @NotNull List<String> limited,
            /** 是否发生截断。 */
            boolean truncated
    ) {
    }

    /** 回滚语句条数上限（对齐 dbx 合批 500 行）。 */
    public static final int MAX_STATEMENTS = 500;

    /** 回滚 SQL 总字符上限（256KB，对齐 dbx 合批字节预算）。 */
    public static final int MAX_TOTAL_CHARS = 256 * 1024;

    /** 快照查询建议行上限（P2：粘贴回填的快照行数超过此值按截断降级）。 */
    public static final int SNAPSHOT_ROW_LIMIT = 200;

    /** NULL 字面量（TSV 单元格与谓词渲染共用）。 */
    public static final String NULL_LITERAL = "NULL";

    // ------------------------------------------------------------------
    // P1 入口：文本推导
    // ------------------------------------------------------------------

    /**
     * 从 SQL 文本推导回滚语句。
     *
     * @param sql 预览编辑器当前 SQL（可含注释；多条语句逐条推导）
     */
    @NotNull
    public static RollbackResult generate(@NotNull String sql) {
        return generate(sql, null);
    }

    /**
     * 从 SQL 文本推导回滚语句。
     *
     * <p>T-PS5-02：解析走 {@code cn.song.mybatisplus.visualizer.execute.UnboundedWriteDetector.parseStatementsShared}
     * 单槽共享——确认流（{@code ExecuteConfirmMessages.build} → {@code UnboundedWriteDetector.detect}）刚解析过的
     * 同文本在此直接复用 AST（一次大 DML 点击的解析次数由 4 降到 1），<b>AST 只读不改写</b>。</p>
     *
     * @param sql               预览编辑器当前 SQL（可含注释；多条语句逐条推导）
     * @param primaryKeyColumns 主键列名（大小写不敏感匹配；null = 无主键信息，走整行谓词）
     */
    @NotNull
    public static RollbackResult generate(@NotNull String sql, @Nullable List<String> primaryKeyColumns) {
        String cleaned = SqlCommentStripper.strip(sql);
        if (cleaned.isBlank()) return RollbackResult.unable(new RollbackWarning("rollback.unable.empty"));
        // Statements 本身 extends ArrayList<Statement>（getStatements() 在 5.3 已标 deprecated
        // 且是 verifyPlugin deprecated 计数来源）——直接按列表使用
        List<net.sf.jsqlparser.statement.Statement> statements;
        try {
            statements = cn.song.mybatisplus.visualizer.execute.UnboundedWriteDetector
                    .parseStatementsShared(cleaned);
        } catch (Exception e) {
            String msg = e.getMessage();
            return RollbackResult.unable(new RollbackWarning("rollback.unable.parse",
                    List.of(firstKeywordOf(cleaned), msg != null ? msg.substring(0, Math.min(120, msg.length())) : "")));
        }
        return generateFromParsedStatements(cleaned, statements, primaryKeyColumns);
    }

    /**
     * T-PS5-02：携解析入参重载——调用方已持 AST（确认阶段产物）时不再解析。
     * 供预览面板等调用链在<b>同一执行流内</b>显式传递解析产物；{@link #generate} 亦委派此实现。
     * <b>AST 只读契约</b>：本函数只遍历不写节点（改写须先脱离共享槽）。
     *
     * @param cleaned           已剥注释的 SQL 文本（仅用于多语句判定，不再解析）
     * @param statements        已解析语句列表
     * @param primaryKeyColumns 主键列名（同 {@link #generate(String, List)}）
     */
    @NotNull
    public static RollbackResult generateFromParsedStatements(
            @NotNull String cleaned,
            @NotNull List<net.sf.jsqlparser.statement.Statement> statements,
            @Nullable List<String> primaryKeyColumns) {
        List<RollbackResult> perStatement = new ArrayList<>(statements.size());
        for (net.sf.jsqlparser.statement.Statement statement : statements) {
            perStatement.add(generateForStatement(statement, primaryKeyColumns));
        }
        return merge(perStatement, statements.size() > 1);
    }

    /** T-PS5-02：携解析入参重载（primaryKeyColumns 缺省，Kotlin 默认参数的便捷重载）。 */
    @NotNull
    public static RollbackResult generateFromParsedStatements(
            @NotNull String cleaned,
            @NotNull List<net.sf.jsqlparser.statement.Statement> statements) {
        return generateFromParsedStatements(cleaned, statements, null);
    }

    private static RollbackResult generateForStatement(@NotNull net.sf.jsqlparser.statement.Statement statement,
                                                       @Nullable List<String> pk) {
        if (statement instanceof Insert) {
            return generateFromInsert((Insert) statement, pk);
        }
        if (statement instanceof Update) {
            Update update = (Update) statement;
            // 5.x getSetUpdateSets() 未列可空但运行时缺席返回 null（判空内建硬抛实证）——先按可空收窄
            List<UpdateSet> setSets = update.getUpdateSets();
            List<String> setColumns = new ArrayList<>();
            Map<String, String> setValues = new LinkedHashMap<>();
            if (setSets != null) {
                for (UpdateSet set : setSets) {
                    List<Expression> values = null;
                    List<?> rawValues = set.getValues();
                    if (rawValues != null) {
                        values = new ArrayList<>(rawValues.size());
                        for (Object v : rawValues) values.add((Expression) v);
                    }
                    List<Column> cols = set.getColumns();
                    for (int i = 0; i < cols.size(); i++) {
                        Column col = cols.get(i);
                        setColumns.add(col.getColumnName());
                        if (values != null && i < values.size() && values.get(i) != null) {
                            setValues.put(col.getColumnName(), renderExpression(values.get(i)));
                        }
                    }
                }
            }
            String table = update.getTable() != null ? update.getTable().getFullyQualifiedName() : null;
            return unableForDml(table, update.getWhere(), setColumns, setValues, "update");
        }
        if (statement instanceof Delete) {
            Delete delete = (Delete) statement;
            String table = delete.getTable() != null ? delete.getTable().getFullyQualifiedName() : null;
            return unableForDml(table, delete.getWhere(), List.of(), Map.of(), "delete");
        }
        return RollbackResult.unable(
                new RollbackWarning("rollback.unable.type", List.of(statementTypeLabel(statement))));
    }

    /**
     * INSERT→DELETE 推导：每行 VALUES 生成一条 DELETE，谓词 = 列全等值 AND（NULL→IS NULL）。
     * 主键偏好：pk 列全部出现在 INSERT 列清单时仅按 pk 谓词（Complete），否则整行谓词（Partial + COUNT 自查）。
     * 不可推导形态诚实拒绝：无列清单 INSERT（列名未知）/ INSERT…SELECT（无 before-image）/ upsert
     * （ON DUPLICATE KEY UPDATE 可能改过已存在行，DELETE 回不干净）。
     */
    private static RollbackResult generateFromInsert(@NotNull Insert insert, @Nullable List<String> pk) {
        String table = insert.getTable() != null ? insert.getTable().getFullyQualifiedName() : null;
        if (table == null) {
            return RollbackResult.unable(
                    new RollbackWarning("rollback.unable.parse", List.of("INSERT", "table is null")));
        }
        // 5.x getSetUpdateSets()/getDuplicateUpdateSets() 运行时可空（判空内建硬抛实证）——按可空收窄
        List<UpdateSet> setSets = insert.getSetUpdateSets();
        List<UpdateSet> dupSets = insert.getDuplicateUpdateSets();
        if ((setSets != null && !setSets.isEmpty()) || (dupSets != null && !dupSets.isEmpty())) {
            return RollbackResult.unable(new RollbackWarning("rollback.unable.upsert", List.of(table)));
        }
        // 注意 5.x Values extends Select，且 getValues() 对 INSERT…SELECT 直接 ClassCastException
        //（实现是 (Values) getSelect() 强转）——一律经 getSelect() 判别，Values instanceof 收窄
        net.sf.jsqlparser.statement.select.Select select = insert.getSelect();
        if (!(select instanceof net.sf.jsqlparser.statement.select.Values)) {
            if (select != null) {
                return RollbackResult.unable(new RollbackWarning("rollback.unable.insertSelect", List.of(table)));
            }
            return RollbackResult.unable(
                    new RollbackWarning("rollback.unable.parse", List.of("INSERT", "no VALUES/select")));
        }
        net.sf.jsqlparser.statement.select.Values values = (net.sf.jsqlparser.statement.select.Values) select;
        List<Column> columns = insert.getColumns() != null ? insert.getColumns() : List.of();
        if (columns.isEmpty()) {
            return RollbackResult.unable(new RollbackWarning("rollback.unable.noColumns", List.of(table)));
        }
        List<List<Expression>> rows = rowsOf(values);
        if (rows == null) {
            return RollbackResult.unable(new RollbackWarning("rollback.unable.insertSelect", List.of(table)));
        }
        List<String> columnNames = new ArrayList<>(columns.size());
        for (Column c : columns) columnNames.add(c.getColumnName());
        boolean pkUsable = pk != null && !pk.isEmpty();
        if (pkUsable) {
            for (String p : pk) {
                boolean found = false;
                for (String cn : columnNames) {
                    if (cn.equalsIgnoreCase(p)) {
                        found = true;
                        break;
                    }
                }
                if (!found) {
                    pkUsable = false;
                    break;
                }
            }
        }
        Set<String> pkSet = new HashSet<>();
        if (pkUsable) {
            for (String p : pk) pkSet.add(p.toLowerCase(Locale.ROOT));
        }

        List<RollbackWarning> warnings = new ArrayList<>();
        if (!pkUsable) {
            warnings.add(new RollbackWarning("rollback.warn.fullRowPredicate", List.of(table)));
        }
        boolean hasPlaceholder = false;
        outer:
        for (List<Expression> row : rows) {
            for (Expression e : row) {
                if (e instanceof JdbcParameter || e instanceof JdbcNamedParameter) {
                    hasPlaceholder = true;
                    break outer;
                }
            }
        }
        if (hasPlaceholder) {
            warnings.add(new RollbackWarning("rollback.warn.placeholder"));
        }
        boolean partial = !warnings.isEmpty();

        List<String> deletes = new ArrayList<>(rows.size());
        for (List<Expression> row : rows) {
            if (row.size() != columns.size()) {
                return RollbackResult.unable(new RollbackWarning("rollback.unable.parse",
                        List.of("INSERT", "column/value count mismatch")));
            }
            List<String> predicates = new ArrayList<>(columns.size());
            for (int i = 0; i < columns.size(); i++) {
                Column col = columns.get(i);
                if (pkUsable && !pkSet.contains(col.getColumnName().toLowerCase(Locale.ROOT))) continue;
                predicates.add(equalityPredicate(col.getColumnName(), row.get(i)));
            }
            deletes.add("DELETE FROM " + table + " WHERE " + String.join(" AND ", predicates) + ";");
        }
        LimitCheck limitCheck = enforceLimits(deletes);
        if (limitCheck.truncated()) {
            warnings.add(new RollbackWarning("rollback.warn.truncated"));
            partial = true;
        }
        String countSql = null;
        if (!pkUsable) {
            List<String> predicates = new ArrayList<>(columns.size());
            List<Expression> firstRow = rows.isEmpty() ? null : rows.get(0);
            for (int i = 0; i < columns.size(); i++) {
                Column col = columns.get(i);
                Expression expr = (firstRow != null && i < firstRow.size() && firstRow.get(i) != null)
                        ? firstRow.get(i) : new NullValue();
                predicates.add(equalityPredicate(col.getColumnName(), expr));
            }
            countSql = "SELECT COUNT(*) FROM " + table + " WHERE " + String.join(" AND ", predicates) + ";";
        }
        return new RollbackResult(
                partial ? Rating.PARTIAL : Rating.COMPLETE,
                limitCheck.limited(),
                warnings,
                countSql,
                table);
    }

    /**
     * INSERT 行值提取。5.x Values.expressions 形态：多行 = 嵌套（每个元素是 ExpressionList 行），
     * 单行 = 扁平（元素直接是表达式）——按首元素类型归一，两种形态统一产出行列表。
     */
    @Nullable
    private static List<List<Expression>> rowsOf(@NotNull net.sf.jsqlparser.statement.select.Values values) {
        ExpressionList<?> expressions = values.getExpressions();
        if (expressions == null || expressions.isEmpty()) return null;
        Expression first = expressions.get(0);
        List<List<Expression>> rows = new ArrayList<>();
        if (first instanceof ExpressionList) {
            for (Expression row : expressions) {
                ExpressionList<?> rowList = (ExpressionList<?>) row;
                List<Expression> r = new ArrayList<>();
                for (Expression e : rowList) r.add(e);
                rows.add(r);
            }
            return rows;
        }
        List<Expression> single = new ArrayList<>();
        for (Expression e : expressions) single.add(e);
        rows.add(single);
        return rows;
    }

    /**
     * UPDATE/DELETE 路径（P1）：原值不可考，诚实拒绝可执行回滚；产出：
     * <ul>
     *  <li>人工恢复指引（binlog/备份，warnings）；</li>
     *  <li>自查/快照共用模板 `SELECT * FROM t [WHERE 原WHERE]`（尚未执行的用户可先看受影响行，
     *      P2 经执行按钮执行过的语句可粘贴快照结果生成完整回滚）。</li>
     * </ul>
     */
    private static RollbackResult unableForDml(@Nullable String table, @Nullable Expression where,
                                               @NotNull List<String> setColumns,
                                               @NotNull Map<String, String> setValues,
                                               @NotNull String kind) {
        if (table == null) {
            return RollbackResult.unable(
                    new RollbackWarning("rollback.unable.type", List.of(kind.toUpperCase(Locale.ROOT))));
        }
        String t = table;
        String whereText = where != null ? where.toString() : null;
        String snapshotSql = (whereText == null || whereText.isBlank())
                ? "SELECT * FROM " + table + ";"
                : "SELECT * FROM " + table + " WHERE " + whereText + ";";
        List<RollbackWarning> warnings = new ArrayList<>(List.of(
                new RollbackWarning("rollback.unable." + kind, List.of(t)),
                new RollbackWarning("rollback.guidance.recovery")));
        // T-UnboundedWriteGuard（T-UW-03）：原语句弱谓词警示——恒真 / LIKE 纯通配（复用
        // UnboundedWriteDetector.analyzeWhere）。快照 SELECT 直接复用原 WHERE，弱谓词 = 快照
        // 覆盖全表。UPDATE/DELETE 回滚本就 UNABLE（无 before-image），警示只追加不降级评级
        // （诚实原则：不把 UNABLE 虚标成 PARTIAL；计划 §三的「PARTIAL 触发面」按此落记）。
        for (UnboundedWriteDetector.Finding finding : UnboundedWriteDetector.analyzeWhere(where)) {
            switch (finding.kind()) {
                case TAUTOLOGY, OR_TAUTOLOGY -> warnings.add(new RollbackWarning("rollback.warn.tautology"));
                case LIKE_WILDCARD -> warnings.add(new RollbackWarning("rollback.warn.likeWildcard"));
                default -> {
                }
            }
        }
        return new RollbackResult(
                Rating.UNABLE,
                List.of(),
                warnings,
                null,
                t,
                whereText,
                setColumns,
                setValues,
                snapshotSql);
    }

    // ------------------------------------------------------------------
    // P2 入口：快照行回填（粘贴 SELECT 结果 → 完整回滚）
    // ------------------------------------------------------------------

    /** 快照回填模式（按原语句类型分派）。 */
    public enum SnapshotMode {
        /** 原 INSERT：由快照行生成 DELETE（谓词同 P1 规则）。 */
        REVERT_INSERT,
        /** 原 DELETE：由快照行生成 INSERT 全列回填。 */
        BACKFILL_INSERT,
        /** 原 UPDATE：由快照行（旧值镜像）生成反向 UPDATE（SET 旧值 + 乐观锁谓词，dbx 原样吸收）。 */
        REVERT_UPDATE,
    }

    /**
     * 解析粘贴的快照结果（TSV：IDE 数据源结果网格复制格式——首行列名，Tab 分列）。
     *
     * <p>兼容形态：`\r\n` 换行、单列无 Tab、尾随空行；`NULL`（大小写不敏感）单元格 → null；
     * 列数不足的行以 null 补齐（lenient——部分网格复制会吞尾部空单元格）。
     *
     * @return (列名清单, 数据行)；无列名行/无数据行返回 null
     */
    @Nullable
    public static kotlin.Pair<List<String>, List<List<String>>> parseSnapshotTsv(@NotNull String text) {
        String[] rawLines = text.replace("\r\n", "\n").replace("\r", "\n").split("\n");
        List<String> lines = new ArrayList<>(rawLines.length);
        for (String raw : rawLines) {
            lines.add(trimEndNewline(raw));
        }
        while (!lines.isEmpty() && lines.get(lines.size() - 1).isBlank()) {
            lines.remove(lines.size() - 1);
        }
        if (lines.size() < 2) return null;
        String[] headerArr = lines.get(0).split("\t");
        List<String> header = new ArrayList<>(headerArr.length);
        for (String h : headerArr) header.add(h.trim());
        for (String h : header) {
            if (h.isBlank()) return null;
        }
        List<List<String>> rows = new ArrayList<>();
        for (int li = 1; li < lines.size(); li++) {
            String line = lines.get(li);
            String[] cells = line.split("\t");
            List<String> row = new ArrayList<>(header.size());
            for (int i = 0; i < header.size(); i++) {
                String cell = i < cells.length ? cells[i] : null;
                if (cell != null) cell = cell.trim();
                // NULL（大小写不敏感）与空单元格统一归一为 null（单点规范化，渲染层只认 null）
                row.add(cell != null && !cell.isEmpty() && !cell.equalsIgnoreCase(NULL_LITERAL) ? cell : null);
            }
            rows.add(row);
        }
        return new kotlin.Pair<>(header, rows);
    }

    /** Kotlin trimEnd('\n') 等价：去掉尾部换行符。 */
    @NotNull
    private static String trimEndNewline(@NotNull String s) {
        int end = s.length();
        while (end > 0 && s.charAt(end - 1) == '\n') end--;
        return s.substring(0, end);
    }

    /**
     * 由粘贴的快照行生成完整回滚（dbx「有 before-image 才回滚」的落地路径——用户亲手取回的
     * 行镜像即 before-image）。
     *
     * @param table             目标表名（来自原语句解析，非用户输入）
     * @param columns           快照列名（TSV 首行）
     * @param rows              快照行（null = NULL 单元格）
     * @param mode              回填模式
     * @param primaryKeyColumns 主键列（REVERT_INSERT 谓词定位 / REVERT_UPDATE 的 WHERE 定位；null 走整行镜像）
     * @param changedColumns    REVERT_UPDATE 必传：原 UPDATE 的 SET 列清单
     * @param newValuesByColumn REVERT_UPDATE 必传：SET 列 → 写入后的新值（SQL 字面量，如 `'abc'`/`3`/`NULL`）
     * @param dialect           O-52：回滚侧 identity 包装的方言（null = 未知 → 不包装，默认）
     * @param identityColumns   O-52：目标表的自增列名（空 = 未知 → 不包装，默认）——只在
     *                          {@link SnapshotMode#BACKFILL_INSERT} 生效（该模式才生成 INSERT）
     */
    @NotNull
    public static RollbackResult generateFromSnapshotRows(@NotNull String table, @NotNull List<String> columns,
                                                          @NotNull List<List<String>> rows,
                                                          @NotNull SnapshotMode mode) {
        return generateFromSnapshotRows(table, columns, rows, mode, null, List.of(), Map.of());
    }

    /** {@link #generateFromSnapshotRows(String,List,List,SnapshotMode,List,List,Map)} 的带主键形态。 */
    @NotNull
    public static RollbackResult generateFromSnapshotRows(@NotNull String table, @NotNull List<String> columns,
                                                          @NotNull List<List<String>> rows,
                                                          @NotNull SnapshotMode mode,
                                                          @Nullable List<String> primaryKeyColumns) {
        return generateFromSnapshotRows(table, columns, rows, mode, primaryKeyColumns, List.of(), Map.of());
    }

    /** {@link #generateFromSnapshotRows(String,List,List,SnapshotMode,List,List,Map)} 的 REVERT_UPDATE 前置形态。 */
    @NotNull
    public static RollbackResult generateFromSnapshotRows(@NotNull String table, @NotNull List<String> columns,
                                                          @NotNull List<List<String>> rows,
                                                          @NotNull SnapshotMode mode,
                                                          @Nullable List<String> primaryKeyColumns,
                                                          @NotNull List<String> changedColumns) {
        return generateFromSnapshotRows(table, columns, rows, mode, primaryKeyColumns, changedColumns, Map.of());
    }

    /** {@link #generateFromSnapshotRows(String,List,List,SnapshotMode,List,List,Map,Dialect,List)} 的 O-52 两字段落「未知」形态。 */
    @NotNull
    public static RollbackResult generateFromSnapshotRows(@NotNull String table, @NotNull List<String> columns,
                                                          @NotNull List<List<String>> rows,
                                                          @NotNull SnapshotMode mode,
                                                          @Nullable List<String> primaryKeyColumns,
                                                          @NotNull List<String> changedColumns,
                                                          @NotNull Map<String, String> newValuesByColumn) {
        return generateFromSnapshotRows(table, columns, rows, mode, primaryKeyColumns, changedColumns,
                newValuesByColumn, null, List.of());
    }

    @NotNull
    public static RollbackResult generateFromSnapshotRows(@NotNull String table, @NotNull List<String> columns,
                                                          @NotNull List<List<String>> rows,
                                                          @NotNull SnapshotMode mode,
                                                          @Nullable List<String> primaryKeyColumns,
                                                          @NotNull List<String> changedColumns,
                                                          @NotNull Map<String, String> newValuesByColumn,
                                                          @Nullable Dialect dialect,
                                                          @NotNull List<String> identityColumns) {
        if (table.isBlank() || columns.isEmpty() || rows.isEmpty()) {
            return RollbackResult.unable(new RollbackWarning("rollback.snapshot.emptyInput"));
        }
        return switch (mode) {
            case BACKFILL_INSERT -> backfillInsert(table, columns, rows, dialect, identityColumns);
            case REVERT_INSERT -> revertInsertFromSnapshot(table, columns, rows, primaryKeyColumns);
            case REVERT_UPDATE -> revertUpdateFromSnapshot(
                    table, columns, rows, primaryKeyColumns, changedColumns, newValuesByColumn);
        };
    }

    /**
     * DELETE 反向：INSERT 全列回填（多行 VALUES 合批 500 行/256KB；行数达快照上限按截断降级）。
     *
     * <p>O-52：快照列清单含自增列且方言有包装形态（{@link Dialect#renderInsertWrapper} 非 null）时，
     * <b>每条</b> INSERT 各自成对包装——{@code SET IDENTITY_INSERT} 是会话级开关且同一时刻每会话只能有
     * 一个表为 ON，合批出的多条 INSERT 不能只在整段首尾各包一次。identity 未知（空清单/方言为 null）→
     * 不包装，产物与改前逐字节相同。</p>
     */
    @NotNull
    private static RollbackResult backfillInsert(@NotNull String table, @NotNull List<String> columns,
                                                 @NotNull List<List<String>> rows,
                                                 @Nullable Dialect dialect,
                                                 @NotNull List<String> identityColumns) {
        String columnList = String.join(", ", columns);
        // 大小写不敏感匹配（与 REVERT_INSERT 的主键判定同口径）：粘贴 TSV 的列名来自网格，
        // 大小写形态由驱动/表定义决定，不能按字面相等判
        boolean wrapIdentity = false;
        for (String id : identityColumns) {
            for (String c : columns) {
                if (c.equalsIgnoreCase(id)) {
                    wrapIdentity = true;
                    break;
                }
            }
            if (wrapIdentity) break;
        }
        final Dialect wrapperDialect = dialect;
        Function1<String, String> wrapper = (wrapperDialect != null && wrapIdentity)
                ? insertSql -> {
                    String wrapped = wrapperDialect.renderInsertWrapper(table, insertSql);
                    return wrapped != null ? wrapped : insertSql;
                }
                : null;
        // 方言能力探测：探针 SQL 只为问「本方言有没有包装形态」（默认 null，SQL Server/DM 有）。
        boolean dialectNeedsWrapper = dialect != null && dialect.renderInsertWrapper(table,
                "INSERT INTO " + table + " (" + columnList + ") VALUES (…);") != null;
        // 「未知即不包装」是 O-52 D1=A 的既定契约，但**不能静默**：方言明明需要包装、而本次又没取到自增列时，
        // 产物就是一个执行必失败（SQL Server/DM 报 544 一类）的 INSERT。真机走查（2026-09-23）正是在这条路上翻车：
        // DAS 没给出自增列 → 无包装 → 用户只看到 544、且不知道是「没内省」导致的。故补一条显式告警。
        boolean identityUnknown = !wrapIdentity && dialectNeedsWrapper && identityColumns.isEmpty();
        List<String> statements = new ArrayList<>(rows.size());
        StringBuilder sb = new StringBuilder();
        int batchRows = 0;
        for (List<String> row : rows) {
            List<String> rendered = new ArrayList<>(row.size());
            for (String cell : row) rendered.add(renderCell(cell));
            String rowText = "(" + String.join(", ", rendered) + ")";
            if (batchRows == MAX_STATEMENTS || (!sb.isEmpty() && sb.length() + rowText.length() > MAX_TOTAL_CHARS)) {
                statements.add(flushInsertBatch(sb, table, columnList, wrapper));
                batchRows = 0;
            }
            if (sb.isEmpty()) {
                sb.append("INSERT INTO ").append(table).append(" (").append(columnList).append(") VALUES ");
            } else {
                sb.append(", ");
            }
            sb.append(rowText);
            batchRows++;
        }
        if (!sb.isEmpty()) statements.add(flushInsertBatch(sb, table, columnList, wrapper));
        List<RollbackWarning> warnings = new ArrayList<>();
        if (identityUnknown) {
            warnings.add(new RollbackWarning("rollback.warn.identityUnknown",
                    List.of(dialect != null ? dialect.getName() : "")));
        }
        // 文案含两个占位符（{0} 行数 / {1} 上限）——曾只传 {0}，弹窗会原样显示「上限 {1}」
        if (rows.size() >= SNAPSHOT_ROW_LIMIT) {
            warnings.add(new RollbackWarning(
                    "rollback.warn.snapshotRowLimit",
                    List.of(String.valueOf(rows.size()), String.valueOf(SNAPSHOT_ROW_LIMIT))));
        }
        warnings.add(new RollbackWarning("rollback.warn.snapshotCompleteness"));
        return new RollbackResult(Rating.PARTIAL, statements, warnings, null, table);
    }

    @NotNull
    private static String flushInsertBatch(@NotNull StringBuilder sb, @NotNull String table,
                                           @NotNull String columnList,
                                           @Nullable Function1<String, String> wrapper) {
        String text = sb.append(";").toString();
        sb.setLength(0);
        return wrapper != null ? wrapper.invoke(text) : text;
    }

    /** INSERT 反向（快照行版本）：DELETE 谓词定位同 P1（pk 传入且在快照列内 → pk 谓词 Complete）。 */
    @NotNull
    private static RollbackResult revertInsertFromSnapshot(@NotNull String table, @NotNull List<String> columns,
                                                           @NotNull List<List<String>> rows,
                                                           @Nullable List<String> pk) {
        boolean pkUsable = pk != null && !pk.isEmpty();
        if (pkUsable) {
            for (String p : pk) {
                boolean found = false;
                for (String c : columns) {
                    if (c.equalsIgnoreCase(p)) {
                        found = true;
                        break;
                    }
                }
                if (!found) {
                    pkUsable = false;
                    break;
                }
            }
        }
        Set<String> pkSet = new HashSet<>();
        if (pkUsable) {
            for (String p : pk) pkSet.add(p.toLowerCase(Locale.ROOT));
        }
        List<RollbackWarning> warnings = new ArrayList<>();
        if (!pkUsable) warnings.add(new RollbackWarning("rollback.warn.fullRowPredicate", List.of(table)));
        List<String> deletes = new ArrayList<>(rows.size());
        for (List<String> row : rows) {
            List<String> predicates = new ArrayList<>(columns.size());
            for (int i = 0; i < columns.size(); i++) {
                String col = columns.get(i);
                if (pkUsable && !pkSet.contains(col.toLowerCase(Locale.ROOT))) continue;
                predicates.add(cellPredicate(col, i < row.size() ? row.get(i) : null));
            }
            deletes.add("DELETE FROM " + table + " WHERE " + String.join(" AND ", predicates) + ";");
        }
        LimitCheck limitCheck = enforceLimits(deletes);
        if (limitCheck.truncated()) warnings.add(new RollbackWarning("rollback.warn.truncated"));
        String countSql = null;
        if (!pkUsable && !rows.isEmpty()) {
            List<String> predicates = new ArrayList<>(columns.size());
            List<String> firstRow = rows.get(0);
            for (int i = 0; i < columns.size(); i++) {
                predicates.add(cellPredicate(columns.get(i), i < firstRow.size() ? firstRow.get(i) : null));
            }
            countSql = "SELECT COUNT(*) FROM " + table + " WHERE " + String.join(" AND ", predicates) + ";";
        }
        return new RollbackResult(
                warnings.isEmpty() ? Rating.COMPLETE : Rating.PARTIAL,
                limitCheck.limited(),
                warnings,
                countSql,
                table);
    }

    /**
     * UPDATE 反向（dbx 乐观锁谓词原样吸收）：每行 `UPDATE t SET 被改列=旧值 WHERE pk=旧pk AND 被改列=新值`——
     * 只有库内状态仍等于写入后状态才回滚得动，防覆盖他人后续修改。无主键时 WHERE = 整行旧值镜像 AND 被改列=新值。
     */
    @NotNull
    private static RollbackResult revertUpdateFromSnapshot(@NotNull String table, @NotNull List<String> columns,
                                                           @NotNull List<List<String>> rows,
                                                           @Nullable List<String> pk,
                                                           @NotNull List<String> changedColumns,
                                                           @NotNull Map<String, String> newValues) {
        if (changedColumns.isEmpty()) {
            return RollbackResult.unable(new RollbackWarning("rollback.snapshot.emptyInput"));
        }
        List<String> unknown = new ArrayList<>();
        for (String c : changedColumns) {
            boolean found = false;
            for (String col : columns) {
                if (col.equalsIgnoreCase(c)) {
                    found = true;
                    break;
                }
            }
            if (!found) unknown.add(c);
        }
        if (!unknown.isEmpty()) {
            return RollbackResult.unable(new RollbackWarning("rollback.snapshot.columnMismatch",
                    List.of(String.join(", ", unknown))));
        }
        boolean pkUsable = pk != null && !pk.isEmpty();
        if (pkUsable) {
            for (String p : pk) {
                boolean found = false;
                for (String col : columns) {
                    if (col.equalsIgnoreCase(p)) {
                        found = true;
                        break;
                    }
                }
                if (!found) {
                    pkUsable = false;
                    break;
                }
            }
        }
        List<Integer> pkIdx = new ArrayList<>();
        if (pkUsable) {
            for (String p : pk) pkIdx.add(indexOfIgnoreCase(columns, p));
        }
        List<Integer> changedIdx = new ArrayList<>();
        for (String c : changedColumns) changedIdx.add(indexOfIgnoreCase(columns, c));
        List<RollbackWarning> warnings = new ArrayList<>();
        if (!pkUsable) warnings.add(new RollbackWarning("rollback.warn.fullRowPredicate", List.of(table)));

        List<String> updates = new ArrayList<>(rows.size());
        for (List<String> row : rows) {
            List<String> setParts = new ArrayList<>(changedIdx.size());
            for (int idx : changedIdx) {
                setParts.add(columns.get(idx) + " = " + renderCell(idx < row.size() ? row.get(idx) : null));
            }
            String setPart = String.join(", ", setParts);
            List<String> predicates = new ArrayList<>();
            if (pkUsable) {
                for (int idx : pkIdx) {
                    predicates.add(cellPredicate(columns.get(idx), idx < row.size() ? row.get(idx) : null));
                }
            } else {
                for (int idx = 0; idx < columns.size(); idx++) {
                    predicates.add(cellPredicate(columns.get(idx), idx < row.size() ? row.get(idx) : null));
                }
            }
            for (int ci = 0; ci < changedColumns.size(); ci++) {
                String c = changedColumns.get(ci);
                // 新值是调用方渲染好的 SQL 字面量（'abc'/3），原样进谓词；NULL 走 IS NULL
                String nv = newValues.get(c);
                if (nv != null) {
                    int target = changedIdx.get(ci);
                    if (nv.equalsIgnoreCase(NULL_LITERAL)) {
                        predicates.add(columns.get(target) + " IS NULL");
                    } else {
                        predicates.add(columns.get(target) + " = " + nv);
                    }
                }
            }
            updates.add("UPDATE " + table + " SET " + setPart + " WHERE " + String.join(" AND ", predicates) + ";");
        }
        LimitCheck limitCheck = enforceLimits(updates);
        if (limitCheck.truncated()) warnings.add(new RollbackWarning("rollback.warn.truncated"));
        return new RollbackResult(
                pkUsable && !limitCheck.truncated() ? Rating.COMPLETE : Rating.PARTIAL,
                limitCheck.limited(),
                warnings,
                null,
                table);
    }

    /** 大小写不敏感列定位（pk/changed 列在快照列清单中的下标；必命中）。 */
    private static int indexOfIgnoreCase(@NotNull List<String> columns, @NotNull String name) {
        for (int i = 0; i < columns.size(); i++) {
            if (columns.get(i).equalsIgnoreCase(name)) return i;
        }
        return -1;
    }

    // ------------------------------------------------------------------
    // 渲染与工具
    // ------------------------------------------------------------------

    /** 单条谓词：NULL→`col IS NULL`，其余→`col = &lt;字面量&gt;`。 */
    @NotNull
    private static String equalityPredicate(@NotNull String column, @NotNull Expression expr) {
        return expr instanceof NullValue ? column + " IS NULL" : column + " = " + renderExpression(expr);
    }

    /** 快照单元格谓词（文本形态；null/NULL 字面量 → IS NULL）。 */
    @NotNull
    private static String cellPredicate(@NotNull String column, @Nullable String cell) {
        if (cell == null || cell.equalsIgnoreCase(NULL_LITERAL)) return column + " IS NULL";
        return column + " = " + renderCell(cell);
    }

    /** 快照单元格字面量渲染：NULL 裸字面量，数字启发式原样，其余单引号转义（''）。 */
    @NotNull
    public static String renderCell(@Nullable String cell) {
        if (cell == null) return NULL_LITERAL;
        if (cell.equalsIgnoreCase(NULL_LITERAL)) return NULL_LITERAL;
        if (NUMBER_REGEX.matcher(cell).matches()) return cell;
        return "'" + cell.replace("'", "''") + "'";
    }

    /**
     * 表达式字面量渲染。StringValue 的 5.x getValue() 保留原始转义形态（`O''Brien`），
     * 外层补引号即可、不可再转义（实证：再转义产出 `''''` 四引号）；日期/时间族按 JDBC 值
     * 格式化为带引号字面量（DateValue.toString 渲染 `{d '…'}` JDBC 转义，MySQL 不接受）；
     * JdbcParameter 渲染 `?`（占位符未回填由警告承担）；其余 toString 保真。
     */
    @NotNull
    private static String renderExpression(@NotNull Expression expr) {
        if (expr instanceof NullValue) return NULL_LITERAL;
        if (expr instanceof StringValue) return "'" + ((StringValue) expr).getValue() + "'";
        if (expr instanceof SignedExpression) {
            return ((SignedExpression) expr).getSign()
                    + renderExpression(((SignedExpression) expr).getExpression());
        }
        if (expr instanceof JdbcParameter || expr instanceof JdbcNamedParameter) return "?";
        if (expr instanceof DateValue) return "'" + ((DateValue) expr).getValue() + "'";
        if (expr instanceof TimeValue) return "'" + ((TimeValue) expr).getValue() + "'";
        if (expr instanceof TimestampValue) return "'" + ((TimestampValue) expr).getValue() + "'";
        // LongValue / DoubleValue / HexValue 与其余类型统一 toString 保真
        return expr.toString();
    }

    /** 条数/字节上限防呆（对齐 dbx 500 语句/256KB）：超限截断并标记。 */
    @NotNull
    private static LimitCheck enforceLimits(@NotNull List<String> statements) {
        if (statements.size() > MAX_STATEMENTS) {
            return new LimitCheck(new ArrayList<>(statements.subList(0, MAX_STATEMENTS)), true);
        }
        int total = 0;
        for (int index = 0; index < statements.size(); index++) {
            total += statements.get(index).length() + 1;
            if (total > MAX_TOTAL_CHARS) {
                return new LimitCheck(
                        new ArrayList<>(statements.subList(0, Math.max(index, 1))), true);
            }
        }
        return new LimitCheck(statements, false);
    }

    /** 多语句结果合并：任一 UNABLE → 整体 UNABLE（部分回滚比不回滚更危险）；其余取最低评级。 */
    @NotNull
    private static RollbackResult merge(@NotNull List<RollbackResult> results, boolean multi) {
        if (results.size() == 1) return results.get(0);
        RollbackResult firstUnable = null;
        for (RollbackResult r : results) {
            if (r.rating() == Rating.UNABLE) {
                firstUnable = r;
                break;
            }
        }
        if (firstUnable != null) {
            RollbackWarning reason = firstUnable.warnings().isEmpty()
                    ? new RollbackWarning("rollback.unable.type", List.of("SQL"))
                    : firstUnable.warnings().get(0);
            return RollbackResult.unable(reason,
                    new RollbackWarning("rollback.warn.multiStatement", List.of(String.valueOf(results.size()))));
        }
        List<RollbackWarning> warnings = new ArrayList<>();
        for (RollbackResult r : results) warnings.addAll(r.warnings());
        if (multi && results.size() > 1) {
            warnings.add(0, new RollbackWarning("rollback.warn.multiStatement",
                    List.of(String.valueOf(results.size()))));
        }
        Rating rating = Rating.COMPLETE;
        for (RollbackResult r : results) {
            if (r.rating() == Rating.PARTIAL) {
                rating = Rating.PARTIAL;
                break;
            }
        }
        String countCheckSql = null;
        for (RollbackResult r : results) {
            if (r.countCheckSql() != null) {
                countCheckSql = r.countCheckSql();
                break;
            }
        }
        String targetTable = null;
        for (RollbackResult r : results) {
            if (r.targetTable() != null) {
                targetTable = r.targetTable();
                break;
            }
        }
        List<String> statements = new ArrayList<>();
        for (RollbackResult r : results) statements.addAll(r.statements());
        return new RollbackResult(
                rating,
                statements,
                warnings,
                countCheckSql,
                targetTable,
                null,
                List.of(),
                null);
    }

    @NotNull
    private static String statementTypeLabel(@NotNull net.sf.jsqlparser.statement.Statement statement) {
        String simpleName = statement.getClass().getSimpleName();
        return simpleName.isBlank() ? "SQL" : simpleName;
    }

    @NotNull
    private static String firstKeywordOf(@NotNull String sql) {
        String s = sql.stripLeading();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (!Character.isLetter(c)) break;
            sb.append(c);
        }
        String upper = sb.toString().toUpperCase(Locale.ROOT);
        return upper.isBlank() ? "SQL" : upper;
    }

    private static final Pattern NUMBER_REGEX = Pattern.compile("-?\\d+(\\.\\d+)?");
}
