package cn.song.mybatisplus.visualizer.reverse;

import cn.song.mybatisplus.visualizer.model.ColumnRef;
import cn.song.mybatisplus.visualizer.model.Condition;
import cn.song.mybatisplus.visualizer.model.Connector;
import cn.song.mybatisplus.visualizer.model.JoinType;
import cn.song.mybatisplus.visualizer.model.LikePosition;
import cn.song.mybatisplus.visualizer.model.LimitClause;
import cn.song.mybatisplus.visualizer.model.OrderByItem;
import cn.song.mybatisplus.visualizer.model.SqlValue;
import cn.song.mybatisplus.visualizer.reverse.SqlTokenizer.Token;
import cn.song.mybatisplus.visualizer.reverse.SqlTokenizer.TokenType;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * SQL → MyBatis-Plus 代码 反向解析器（P0）。
 *
 * 把单表 SELECT/UPDATE/DELETE/INSERT SQL 文本解析为可复用的条件模型（[Condition] 家族），
 * 供 [JavaCodeGenerator] 生成 QueryWrapper 代码。P0/P1 范围与不支持形态见各方法注释。
 */
public final class SqlReverseParser {

    /** 解析入口。 */
    public static final SqlReverseParser INSTANCE = new SqlReverseParser();

    private SqlReverseParser() {
    }

    /** 解析入口。 @throws SqlReverseException 不支持或语法错误 */
    public SqlReverseResult parse(String sqlText) throws SqlReverseException {
        List<Token> tokens = new SqlTokenizer().tokenize(sqlText);
        if (tokens.isEmpty()) {
            throw new SqlReverseException(0, KEY_PARSE, "SQL 文本为空");
        }
        Cursor cursor = new Cursor(tokens, sqlText);

        Token first = cursor.current();
        if (first != null && isKeyword(first, "SELECT")) {
            cursor.advance();
            return parseSelectBody(cursor);
        }
        if (first != null && isKeyword(first, "UPDATE")) {
            cursor.advance();
            return parseUpdateBody(cursor);
        }
        if (first != null && isKeyword(first, "DELETE")) {
            cursor.advance();
            return parseDeleteBody(cursor);
        }
        if (first != null && isKeyword(first, "INSERT")) {
            cursor.advance();
            return parseInsertBody(cursor);
        }
        throw new SqlReverseException(
                first != null ? first.offset() : 0,
                KEY_NOT_SELECT,
                first != null && first.type() == TokenType.WORD ? first.text() : "?"
        );
    }

    /** SELECT 主体：列清单 + FROM 表 + 子句序列（T-M37：含 JOIN 解析）。 */
    private SqlReverseResult parseSelectBody(Cursor cursor) {
        Pair<List<SelectColumn>, Boolean> selectParsed = parseSelectColumns(cursor);
        List<SelectColumn> selectColumns = selectParsed.first();
        boolean distinct = selectParsed.second();
        if (!cursor.isKeyword(cursor.current(), "FROM")) {
            throw new SqlReverseException(cursor.current() != null ? cursor.current().offset() : 0, KEY_PARSE, "缺少 FROM 子句");
        }
        cursor.advance();
        String tableAlias;
        String tableName;
        if (cursor.current() != null && cursor.current().type() == TokenType.SYMBOL && "(".equals(cursor.current().text())) {
            // FROM 子查询：别名已并入 tableName 文本（parseSubqueryTable 返回含别名的整段），此处无独立别名
            tableAlias = null;
            tableName = parseSubqueryTable(cursor).first;
        } else {
            String name = parseTableName(cursor);
            tableAlias = consumeOptionalAlias(cursor);
            tableName = name;
        }
        TailClauses tail = parseTailClauses(cursor, true);
        return new SqlReverseResult(tableName, selectColumns, tail.conditions(), tail.orderBy(), tail.limit(),
                tail.warnings(), ReverseStatementKind.SELECT, List.of(), List.of(), List.of(), tail.joins(), List.of(),
                tableAlias, distinct, tail.groupBy(), tail.having(), tail.unions(), tail.forUpdate());
    }

    /** UPDATE 主体：表 + SET 赋值 + 子句序列（T-M36.7）。 */
    private SqlReverseResult parseUpdateBody(Cursor cursor) {
        String tableName = parseTableName(cursor);
        String tableAlias = consumeOptionalAlias(cursor);
        if (!cursor.isKeyword(cursor.current(), "SET")) {
            throw new SqlReverseException(cursor.current() != null ? cursor.current().offset() : 0, KEY_PARSE, "UPDATE 缺少 SET 子句");
        }
        cursor.advance();
        List<ReverseSet> sets = parseSets(cursor);
        TailClauses tail = parseTailClauses(cursor);
        return new SqlReverseResult(tableName, null, tail.conditions(), tail.orderBy(), tail.limit(),
                tail.warnings(), ReverseStatementKind.UPDATE, sets, List.of(), List.of(), List.of(), List.of(),
                tableAlias, false, List.of(), null, List.of(), false);
    }

    /**
     * DELETE 主体（T-M36.7 + 多表删除）：
     * `DELETE FROM 表 [WHERE ...]`（无目标清单，单表，现状不变）或
     * `DELETE t, t1 FROM 表 [别名] [JOIN 链] [WHERE ...]`（目标清单 → deleteTargets，联表删除）。
     */
    private SqlReverseResult parseDeleteBody(Cursor cursor) {
        List<String> deleteTargets = new ArrayList<>();
        if (!cursor.isKeyword(cursor.current(), "FROM")) {
            while (true) {
                Token t = cursor.current();
                if (t == null) throw new SqlReverseException(cursor.offsetAtEnd(), KEY_PARSE, "DELETE 缺少 FROM 子句");
                if (t.type() != TokenType.WORD) {
                    throw new SqlReverseException(t.offset(), KEY_PARSE, "DELETE 目标格式不正确");
                }
                deleteTargets.add(stripBackticks(t.text()));
                cursor.advance();
                if (cursor.isSymbol(cursor.current(), ",")) {
                    cursor.advance();
                    continue;
                }
                break;
            }
        }
        if (!cursor.isKeyword(cursor.current(), "FROM")) {
            throw new SqlReverseException(cursor.current() != null ? cursor.current().offset() : 0, KEY_PARSE, "DELETE 缺少 FROM 子句");
        }
        cursor.advance();
        String tableName = parseTableName(cursor);
        String tableAlias = consumeOptionalAlias(cursor);
        // 联表删除：DELETE 的尾子句放开 JOIN（UPDATE 的 JOIN 保持报错不变）
        TailClauses tail = parseTailClauses(cursor, true);
        return new SqlReverseResult(tableName, null, tail.conditions(), tail.orderBy(), tail.limit(),
                tail.warnings(), ReverseStatementKind.DELETE, List.of(), List.of(), List.of(), tail.joins(), deleteTargets,
                tableAlias, false, List.of(), null, List.of(), false);
    }

    /** INSERT 主体：INTO 表 (列清单) VALUES (值清单)。 */
    private SqlReverseResult parseInsertBody(Cursor cursor) {
        if (!cursor.isKeyword(cursor.current(), "INTO")) {
            throw new SqlReverseException(cursor.current() != null ? cursor.current().offset() : 0, KEY_PARSE, "INSERT 缺少 INTO 子句");
        }
        cursor.advance();
        String tableName = parseTableName(cursor);

        expectSymbol(cursor, "(");
        List<String> columns = new ArrayList<>();
        while (true) {
            Token t = cursor.current();
            if (t == null) throw new SqlReverseException(cursor.offsetAtEnd(), KEY_PARSE, "INSERT 列清单不完整");
            if (t.type() != TokenType.WORD) {
                throw new SqlReverseException(t.offset(), KEY_PARSE, "INSERT 列名格式不正确");
            }
            columns.add(stripQualified(consumeQualified(cursor)));
            if (cursor.isSymbol(cursor.current(), ",")) {
                cursor.advance();
                continue;
            }
            break;
        }
        expectSymbol(cursor, ")");
        if (!cursor.isKeyword(cursor.current(), "VALUES")) {
            throw new SqlReverseException(cursor.current() != null ? cursor.current().offset() : 0, KEY_PARSE, "INSERT 缺少 VALUES");
        }
        cursor.advance();
        // VALUES 多行：`(v1, v2), (v3, v4)`——每行独立值列表；行内列数须与列清单一致，不齐报 KEY_PARSE
        List<List<SqlValue>> rows = new ArrayList<>();
        while (true) {
            expectSymbol(cursor, "(");
            List<SqlValue> values = new ArrayList<>();
            while (true) {
                // T-SqlToJavaPromote G6：NULL 字面量（parseValue 返回 null）建模为 Literal("NULL")
                // ——Java 生成 null、SQL 重渲染 NULL；不再报错
                SqlValue value = parseValue(cursor);
                if (value == null) value = new SqlValue.Literal("NULL");
                values.add(value);
                if (cursor.isSymbol(cursor.current(), ",")) {
                    cursor.advance();
                    continue;
                }
                break;
            }
            expectSymbol(cursor, ")");
            if (columns.size() != values.size()) {
                throw new SqlReverseException(cursor.previousOffset(), KEY_PARSE, "INSERT 列数与值数不一致");
            }
            rows.add(values);
            if (cursor.isSymbol(cursor.current(), ",")) {
                cursor.advance();
                continue;
            }
            break;
        }
        return new SqlReverseResult(tableName, null, List.of(), List.of(), null, List.of(),
                ReverseStatementKind.INSERT, List.of(), columns, rows, List.of(), List.of(),
                null, false, List.of(), null, List.of(), false);
    }

    /** SET 赋值清单。 */
    private List<ReverseSet> parseSets(Cursor cursor) {
        List<ReverseSet> sets = new ArrayList<>();
        while (true) {
            ColumnRef column = parseColumnRef(cursor);
            expectSymbol(cursor, "=");
            try {
                SqlValue value = parseValue(cursor);
                sets.add(new ReverseSet(column.getColumnName(), value, null));
            } catch (SqlReverseException e) {
                if (KEY_UNSUPPORTED.equals(e.getKey())) {
                    int startIdx = Math.max(indexOfFirst(cursor.tokens, e.getOffset()), 0);
                    int endIdx = indexOfFirst(cursor.tokens, tk -> tk.offset() > e.getOffset() && tk.type() == TokenType.SYMBOL && ",".equals(tk.text()));
                    int endIdxClamped = endIdx < 0 ? cursor.tokens.size() : endIdx;
                    int endOffset = endIdxClamped > 0 ?
                            endOffsetOf(cursor.tokens.get(endIdxClamped - 1)) : e.getOffset();
                    cursor.pos = endIdxClamped;
                    sets.add(new ReverseSet(column.getColumnName(), null,
                            column.getColumnName() + " = " + cursor.source.substring(e.getOffset(), endOffset).trim()));
                    if (cursor.isSymbol(cursor.current(), ",")) {
                        cursor.advance();
                        continue;
                    }
                    break;
                }
                throw e;
            }
            if (cursor.isSymbol(cursor.current(), ",")) {
                cursor.advance();
                continue;
            }
            break;
        }
        return sets;
    }

    /** 尾子句序列（SELECT/UPDATE/DELETE 共用）。 */
    private TailClauses parseTailClauses(Cursor cursor) {
        return parseTailClauses(cursor, false);
    }

    /**
     * 尾子句序列（SELECT/UPDATE/DELETE 共用）：WHERE / ORDER BY / LIMIT 及不支持子句报错。
     * T-M37：allowJoin=true（SELECT）时解析 JOIN 链；DELETE 多表删除同样放开（UPDATE 保持报错）。
     */
    private TailClauses parseTailClauses(Cursor cursor, boolean allowJoin) {
        List<String> warnings = new ArrayList<>();
        List<Condition> conditions = List.of();
        List<OrderByItem> orderBy = List.of();
        LimitClause limit = null;
        List<ReverseJoin> joins = new ArrayList<>();
        List<ColumnRef> groupBy = List.of();
        Condition having = null;
        boolean forUpdate = false;
        List<ReverseUnion> unions = new ArrayList<>();
        while (true) {
            Token t = cursor.current();
            if (t == null) break;
            if (cursor.isKeyword(t, "WHERE")) {
                cursor.advance();
                conditions = parseConditionList(cursor, warnings);
            } else if (cursor.isKeyword(t, "ORDER")) {
                cursor.advance();
                expectKeyword(cursor, "BY");
                orderBy = parseOrderBy(cursor);
            } else if (cursor.isKeyword(t, "GROUP")) {
                cursor.advance();
                expectKeyword(cursor, "BY");
                groupBy = parseGroupBy(cursor);
            } else if (cursor.isKeyword(t, "HAVING")) {
                cursor.advance();
                having = parseHaving(cursor, warnings);
            } else if (cursor.isKeyword(t, "LIMIT")) {
                cursor.advance();
                limit = parseLimit(cursor);
            } else if (cursor.isKeyword(t, "OFFSET")) {
                cursor.advance();
                limit = parseOffsetFetch(cursor);
            } else if (cursor.isKeyword(t, "FETCH")) {
                cursor.advance();
                limit = parseFetchFirst(cursor);
            } else if (allowJoin && isJoinStart(cursor)) {
                joins.add(parseJoin(cursor, warnings));
            } else if (cursor.isKeyword(t, "FOR")) {
                // FOR UPDATE（MySQL/PostgreSQL 行锁）；FOR 后非 UPDATE → 不支持
                if (cursor.isKeyword(cursor.peek(), "UPDATE")) {
                    cursor.advance();
                    cursor.advance();
                    forUpdate = true;
                } else {
                    unsupported(cursor, "FOR 子句");
                }
            } else if (cursor.isKeyword(t, "UNION")) {
                // T-SqlToJavaPromote G8：UNION [ALL] 子查询——收集 keyword + 子查询原文
                // （MP core 无 union() 方法、MPJ 签名本地不可得 → 模型层原文承载，Java 生成层降级注释）
                cursor.advance();
                boolean all = cursor.isKeyword(cursor.current(), "ALL");
                if (all) cursor.advance();
                unions.add(new ReverseUnion(all ? "UNION ALL" : "UNION", collectUnionSubquery(cursor)));
            } else if (cursor.isKeyword(t, "JOIN") || cursor.isKeyword(t, "LEFT") || cursor.isKeyword(t, "RIGHT")
                    || cursor.isKeyword(t, "INNER") || cursor.isKeyword(t, "FULL") || cursor.isKeyword(t, "CROSS")) {
                unsupported(cursor, "JOIN");
            } else if (t.type() == TokenType.SYMBOL && ";".equals(t.text())) {
                cursor.advance();
                if (cursor.current() != null) unsupported(cursor, "多条语句（一次转换一条）");
                break;
            } else {
                throw new SqlReverseException(t.offset(), KEY_PARSE, "无法识别的子句：" + t.text());
            }
        }
        return new TailClauses(conditions, orderBy, limit, warnings, joins, groupBy, having, forUpdate, unions);
    }

    /** 尾子句解析产物。 */
    private record TailClauses(
            List<Condition> conditions,
            List<OrderByItem> orderBy,
            LimitClause limit,
            List<String> warnings,
            List<ReverseJoin> joins,
            List<ColumnRef> groupBy,
            Condition having,
            boolean forUpdate,
            List<ReverseUnion> unions
    ) {
    }

    /** UNION 后子查询原文收集：推进到下一个 UNION 或语句尾，取原文；首尾括号剥除（渲染 `UNION (子查询)` 单层括号）。 */
    private String collectUnionSubquery(Cursor cursor) {
        Token cur = cursor.current();
        if (cur == null) throw new SqlReverseException(cursor.offsetAtEnd(), KEY_PARSE, "UNION 后缺少子查询");
        int start = cur.offset();
        while (cursor.current() != null && !cursor.isKeyword(cursor.current(), "UNION")) {
            cursor.advance();
        }
        Token last = getOrNull(cursor.tokens, cursor.pos - 1);
        int end = last != null ? last.offset() + last.text().length() : start;
        String raw = cursor.source.substring(start, end).trim();
        if (raw.startsWith("(") && raw.endsWith(")")) {
            return raw.substring(1, raw.length() - 1).trim();
        }
        return raw;
    }

    /** GROUP BY 列清单：`列[, 列...]`（点分/反引号同列引用）。 */
    private List<ColumnRef> parseGroupBy(Cursor cursor) {
        List<ColumnRef> columns = new ArrayList<>();
        while (true) {
            columns.add(parseColumnRef(cursor));
            if (cursor.isSymbol(cursor.current(), ",")) {
                cursor.advance();
                continue;
            }
            break;
        }
        return columns;
    }

    /**
     * HAVING 条件（T-SqlToJavaPromote G2）：单条件直取；多条件包 {@link Condition.Group}(AND)。
     * 聚合表达式（`COUNT(*) > 1`）经 {@link #parseCondition} 的 KEY_UNSUPPORTED 降级为 {@link Condition.Raw}（记 warning）。
     */
    private Condition parseHaving(Cursor cursor, List<String> warnings) {
        List<Condition> conditions = parseConditionList(cursor, warnings);
        if (conditions.isEmpty()) {
            throw new SqlReverseException(cursor.offsetAtEnd(), KEY_PARSE, "HAVING 后缺少条件");
        }
        return conditions.size() == 1 ? conditions.get(0) : new Condition.Group(Connector.AND, conditions, null, false);
    }

    /** JOIN 起始判定。 */
    private boolean isJoinStart(Cursor cursor) {
        Token t = cursor.current();
        if (t == null) return false;
        if (cursor.isKeyword(t, "JOIN")) return true;
        if (!cursor.isKeyword(t, "LEFT") && !cursor.isKeyword(t, "RIGHT") && !cursor.isKeyword(t, "INNER")
                && !cursor.isKeyword(t, "FULL") && !cursor.isKeyword(t, "CROSS")) {
            return false;
        }
        Token next = cursor.peek();
        if (next != null && cursor.isKeyword(next, "JOIN")) return true;
        if (next != null && cursor.isKeyword(next, "OUTER")) {
            Token after = getOrNull(cursor.tokens, cursor.pos + 2);
            return after != null && cursor.isKeyword(after, "JOIN");
        }
        return false;
    }

    /** 解析一个 JOIN 子句。 */
    private ReverseJoin parseJoin(Cursor cursor, List<String> warnings) {
        JoinType type;
        if (cursor.isKeyword(cursor.current(), "JOIN")) {
            cursor.advance();
            type = JoinType.INNER;
        } else {
            String modifier = cursor.current() != null ? cursor.current().text().toUpperCase() : null;
            cursor.advance();
            if (cursor.isKeyword(cursor.current(), "OUTER")) cursor.advance();
            expectKeyword(cursor, "JOIN");
            type = switch (modifier) {
                case "LEFT" -> JoinType.LEFT;
                case "RIGHT" -> JoinType.RIGHT;
                case "FULL" -> JoinType.FULL;
                default -> JoinType.INNER;
            };
        }
        Triple<String, String, JoinSubQuery> tableExpr = parseJoinTableExpression(cursor);
        if (!cursor.isKeyword(cursor.current(), "ON")) {
            throw new SqlReverseException(cursor.current() != null ? cursor.current().offset() : 0, KEY_PARSE, "JOIN 缺少 ON 子句");
        }
        cursor.advance();
        Integer onStart = cursor.current() != null ? cursor.current().offset() : null;
        List<Condition> onConditions = parseOnConditions(cursor, warnings);
        String rawOn = null;
        if (onStart != null && !cursor.tokens.isEmpty()) {
            Token last = getOrNull(cursor.tokens, cursor.pos - 1);
            if (last != null && last.offset() >= onStart) {
                rawOn = cursor.source.substring(onStart, endOffsetOf(last)).trim();
            }
        }
        return new ReverseJoin(type, tableExpr.first, tableExpr.second, onConditions, rawOn, tableExpr.third);
    }

    /** 解析 JOIN 表表达式（普通表名或子查询）。 */
    private Triple<String, String, JoinSubQuery> parseJoinTableExpression(Cursor cursor) {
        Token t = cursor.current();
        if (t != null && t.type() == TokenType.SYMBOL && "(".equals(t.text())) {
            Pair<String, JoinSubQuery> sub = parseSubqueryTable(cursor);
            return new Triple(sub.first, null, sub.second);
        }
        return new Triple(parseTableName(cursor), consumeJoinAlias(cursor), null);
    }

    /** JOIN 副表别名（ON 为边界；无别名返回 null）。 */
    private String consumeJoinAlias(Cursor cursor) {
        Token t = cursor.current();
        if (t == null) return null;
        if (t.type() == TokenType.WORD && !cursor.isKeyword(t, "ON") && !cursor.isKeyword(t, "WHERE")
                && !cursor.isKeyword(t, "GROUP") && !cursor.isKeyword(t, "ORDER") && !cursor.isKeyword(t, "LIMIT")
                && !cursor.isKeyword(t, "LEFT") && !cursor.isKeyword(t, "RIGHT") && !cursor.isKeyword(t, "INNER")
                && !cursor.isKeyword(t, "FULL") && !cursor.isKeyword(t, "CROSS") && !cursor.isKeyword(t, "JOIN")) {
            String alias = stripBackticks(t.text());
            cursor.advance();
            return alias;
        }
        return null;
    }

    /** ON 条件列表（AND 连接）。 */
    private List<Condition> parseOnConditions(Cursor cursor, List<String> warnings) {
        List<Condition> conditions = new ArrayList<>();
        while (true) {
            Token t = cursor.current();
            if (t == null) break;
            if (isJoinStart(cursor)) break;
            if (t.type() == TokenType.WORD && (isClauseStart(cursor) || cursor.isKeyword(t, "WHERE"))) break;
            if (t.type() == TokenType.SYMBOL && ";".equals(t.text())) break;
            if (cursor.isKeyword(t, "AND")) {
                cursor.advance();
                continue;
            }
            if (cursor.isKeyword(t, "OR")) unsupported(cursor, "ON 内 OR 嵌套条件");
            conditions.add(parseCondition(cursor, warnings));
        }
        return conditions;
    }

    /**
     * 解析 SELECT 与 FROM 之间的列清单。
     *
     * 收集「简单列」（标识符 / 点分 / 反引号，可带 AS 别名）与「函数/聚合列」
     * （T-SqlToJavaPromote G5：`COUNT(*) AS cnt` 等收集原文，不再放弃）；`*` 或裸括号
     * 表达式时整体放弃（返回 null——生成器不生成 select() 调用，MP 全列语义）。
     * T-M37：保留原文（含表别名前缀与反引号）——多表列归属由生成器拆分（renderSelectRef）。
     * T-M46：捕获 AS 别名（SelectColumn.alias）——生成 selectAs。
     * 返回 (列清单, DISTINCT)（T-SqlToJavaPromote G3：DISTINCT 建模为 distinct=true，不放弃列）。
     */
    private Pair<List<SelectColumn>, Boolean> parseSelectColumns(Cursor cursor) {
        List<SelectColumn> columns = new ArrayList<>();
        boolean anyComplex = false;
        boolean distinct = false;
        while (true) {
            Token t = cursor.current();
            if (t == null) break;
            if (cursor.isKeyword(t, "FROM")) break;
            if (t.type() == TokenType.SYMBOL && ",".equals(t.text())) {
                cursor.advance();
                continue;
            }
            if (t.type() == TokenType.SYMBOL && "*".equals(t.text())) {
                anyComplex = true;
                cursor.advance();
                continue;
            }
            if (t.type() == TokenType.WORD) {
                // DISTINCT：建模 distinct=true，继续收集列（T-SqlToJavaPromote G3）
                if (cursor.isKeyword(t, "DISTINCT")) {
                    distinct = true;
                    cursor.advance();
                    continue;
                }
                if (isFunctionCall(cursor)) {
                    // 函数/聚合列：收集原文（含平衡括号）+ 可选 AS 别名（T-SqlToJavaPromote G5）
                    String expr = collectFunctionExpr(cursor);
                    String alias = consumeSelectAlias(cursor);
                    columns.add(new SelectColumn(expr, alias));
                    continue;
                }
                String expr = consumeQualified(cursor);
                String alias = consumeSelectAlias(cursor);
                columns.add(new SelectColumn(expr, alias));
            } else if (t.type() == TokenType.SYMBOL && "(".equals(t.text())) {
                // 裸括号表达式（少见）→ 放弃 select 生成，整体跳过
                anyComplex = true;
                skipBalanced(cursor);
            } else {
                break;
            }
        }
        return new Pair<>(anyComplex ? null : columns, distinct);
    }

    /** 当前 WORD 后紧跟 `(` → 函数调用。 */
    private boolean isFunctionCall(Cursor cursor) {
        Token next = cursor.peek();
        return next != null && next.type() == TokenType.SYMBOL && "(".equals(next.text());
    }

    /**
     * 收集函数列原文：函数名 + 平衡括号内容（`COUNT(*)` / `DATE_FORMAT(create_time, '%Y-%m')`）。
     * 消费到顶层 `)` 结束；返回源文本（不含 AS 别名）。
     */
    private String collectFunctionExpr(Cursor cursor) {
        Token startToken = cursor.current();
        if (startToken == null) return "";
        int start = startToken.offset();
        cursor.advance(); // 函数名
        int depth = 0;
        while (true) {
            Token t = cursor.advance();
            if (t == null) break;
            if (t.type() == TokenType.SYMBOL && "(".equals(t.text())) depth++;
            if (t.type() == TokenType.SYMBOL && ")".equals(t.text())) {
                depth--;
                if (depth == 0) break;
            }
        }
        Token last = getOrNull(cursor.tokens, cursor.pos - 1);
        int end = last != null ? last.offset() + last.text().length() : cursor.source.length();
        return cursor.source.substring(start, end);
    }

    /** 消费 `AS 别名` 或直接别名。 */
    private String consumeSelectAlias(Cursor cursor) {
        Token t = cursor.current();
        if (t == null || t.type() != TokenType.WORD) return null;
        if (cursor.isKeyword(t, "AS")) {
            cursor.advance();
            Token after = cursor.advance();
            return after != null ? stripBackticks(after.text()) : null;
        }
        if (!cursor.isKeyword(t, "FROM")) {
            cursor.advance();
            return stripBackticks(t.text());
        }
        return null;
    }

    /** 跳过整个表达式（平衡括号 + 词）直到顶层逗号或 FROM。 */
    private void skipExpression(Cursor cursor) {
        int depth = 0;
        while (true) {
            Token t = cursor.current();
            if (t == null) break;
            if (depth == 0 && cursor.isKeyword(t, "FROM")) break;
            cursor.advance();
            if (t.type() == TokenType.SYMBOL && "(".equals(t.text())) {
                depth++;
                continue;
            }
            if (t.type() == TokenType.SYMBOL && ")".equals(t.text())) {
                depth--;
                if (depth < 0) break;
                continue;
            }
            if (depth == 0 && t.type() == TokenType.SYMBOL && ",".equals(t.text())) break;
        }
    }

    /** 跳过一对平衡括号。 */
    private void skipBalanced(Cursor cursor) {
        int depth = 0;
        while (true) {
            Token t = cursor.advance();
            if (t == null) break;
            if (t.type() == TokenType.SYMBOL && "(".equals(t.text())) depth++;
            if (t.type() == TokenType.SYMBOL && ")".equals(t.text())) {
                depth--;
                if (depth == 0) break;
            }
        }
    }

    /** 表名：`表` / `库.表`。 */
    private String parseTableName(Cursor cursor) {
        Token t = cursor.current();
        if (t == null) throw new SqlReverseException(cursor.offsetAtEnd(), KEY_PARSE, "缺少表名");
        if (t.type() != TokenType.WORD) {
            throw new SqlReverseException(t.offset(), KEY_PARSE, "表名格式不正确");
        }
        return stripQualified(consumeQualified(cursor));
    }

    /** 解析子查询表表达式。 */
    private Pair<String, JoinSubQuery> parseSubqueryTable(Cursor cursor) {
        Token startToken = cursor.current();
        if (startToken == null) throw new SqlReverseException(cursor.offsetAtEnd(), KEY_PARSE, "缺少子查询");
        int startOffset = startToken.offset();
        int depth = 0;
        while (cursor.current() != null) {
            Token tk = cursor.current();
            if (tk.type() == TokenType.SYMBOL && "(".equals(tk.text())) depth++;
            if (tk.type() == TokenType.SYMBOL && ")".equals(tk.text())) {
                depth--;
                if (depth == 0) {
                    int endOffset = endOffsetOf(tk);
                    cursor.advance();
                    String subqueryText = cursor.source.substring(startOffset, endOffset);
                    String alias = consumeJoinAlias(cursor);
                    JoinSubQuery structure = parseSubqueryStructure(subqueryText, alias != null ? alias : "");
                    return new Pair<>((alias != null ? subqueryText + " " + alias : subqueryText), structure);
                }
            }
            cursor.advance();
        }
        throw new SqlReverseException(startOffset, KEY_PARSE, "子查询括号不匹配");
    }

    /** 尝试解析子查询内部结构（简单单表查询）。 */
    private JoinSubQuery parseSubqueryStructure(String subqueryText, String alias) {
        String inner = subqueryText.trim();
        if (!inner.startsWith("(") || !inner.endsWith(")")) return null;
        String selectSql = inner.substring(1, inner.length() - 1).trim();
        if (!selectSql.toUpperCase().startsWith("SELECT")) return null;
        try {
            SqlReverseResult result = parse(selectSql);
            if (!result.joins().isEmpty()) return null;
            if (result.tableName().startsWith("(")) return null;
            List<String> selectColumns = result.selectColumns() != null
                    ? result.selectColumns().stream().map(c -> stripQualified(c.expr())).toList()
                    : List.of();
            boolean selectAll = result.selectColumns() == null;
            return new JoinSubQuery(result.tableName(), alias, selectAll, selectColumns, result.conditions());
        } catch (SqlReverseException e) {
            return null;
        }
    }

    /** 可选表别名：`FROM user u`——u 非子句关键字时视为别名消费（T-M36.7：SET/INTO 亦为边界）；
     * 返回别名（无别名返回 null）。T-SqlToJavaPromote M1：返回值供主表别名建模。 */
    private String consumeOptionalAlias(Cursor cursor) {
        Token t = cursor.current();
        if (t == null) return null;
        if (t.type() == TokenType.WORD && !cursor.isKeyword(t, "WHERE") && !cursor.isKeyword(t, "GROUP") &&
                !cursor.isKeyword(t, "ORDER") && !cursor.isKeyword(t, "LIMIT") && !cursor.isKeyword(t, "HAVING") &&
                !cursor.isKeyword(t, "UNION") && !cursor.isKeyword(t, "JOIN") && !cursor.isKeyword(t, "FOR") &&
                !cursor.isKeyword(t, "SET") && !cursor.isKeyword(t, "INTO") &&
                // T-M43：分页关键字不当作表别名（`FROM t OFFSET 5 ROWS...` 的 OFFSET 是分页子句）
                !cursor.isKeyword(t, "OFFSET") && !cursor.isKeyword(t, "FETCH") &&
                !cursor.isKeyword(t, "LEFT") && !cursor.isKeyword(t, "RIGHT") && !cursor.isKeyword(t, "INNER") &&
                !cursor.isKeyword(t, "FULL") && !cursor.isKeyword(t, "CROSS")) {
            cursor.advance();
            return stripBackticks(t.text());
        }
        return null;
    }

    /** 条件序列（顶层按 OR 分段）。 */
    private List<Condition> parseConditionList(Cursor cursor, List<String> warnings) {
        List<List<Condition>> orSegments = new ArrayList<>();
        orSegments.add(parseAndSegment(cursor, warnings));
        while (cursor.isKeyword(cursor.current(), "OR")) {
            cursor.advance();
            orSegments.add(parseAndSegment(cursor, warnings));
        }
        if (orSegments.size() == 1) return orSegments.get(0);
        List<Condition> children = new ArrayList<>();
        for (List<Condition> segment : orSegments) {
            children.add(segment.size() == 1 ? segment.get(0) : new Condition.Group(Connector.AND, segment, null, false));
        }
        return List.of(new Condition.Group(Connector.OR, children, null, false));
    }

    /** AND 连接的条件段。 */
    private List<Condition> parseAndSegment(Cursor cursor, List<String> warnings) {
        List<Condition> conditions = new ArrayList<>();
        while (true) {
            Token t = cursor.current();
            if (t == null) break;
            if (t.type() == TokenType.WORD && isClauseStart(cursor)) break;
            if (t.type() == TokenType.SYMBOL && (";".equals(t.text()) || ")".equals(t.text()))) break;
            if (cursor.isKeyword(t, "AND")) {
                cursor.advance();
                continue;
            }
            if (cursor.isKeyword(t, "OR")) break;
            conditions.add(parseCondition(cursor, warnings));
        }
        return conditions;
    }

    /** 子句起点判定。 */
    private boolean isClauseStart(Cursor cursor) {
        Token t = cursor.current();
        if (t == null) return false;
        boolean by = cursor.isKeyword(cursor.peek(), "BY");
        return (cursor.isKeyword(t, "ORDER") && by) || (cursor.isKeyword(t, "GROUP") && by) ||
                cursor.isKeyword(t, "HAVING") || cursor.isKeyword(t, "LIMIT") ||
                cursor.isKeyword(t, "UNION") || cursor.isKeyword(t, "FOR") ||
                (cursor.isKeyword(t, "OFFSET") && isLimitValueStart(cursor.peek())) ||
                (cursor.isKeyword(t, "FETCH") &&
                        (cursor.isKeyword(cursor.peek(), "FIRST") || cursor.isKeyword(cursor.peek(), "NEXT")));
    }

    /** 分页子句值起点判定。 */
    private boolean isLimitValueStart(Token t) {
        return t != null && (t.type() == TokenType.NUMBER || (t.type() == TokenType.SYMBOL && "?".equals(t.text())));
    }

    /** 单个条件（含括号组 / NOT 组 / 普通条件）。 */
    private Condition parseCondition(Cursor cursor, List<String> warnings) {
        Integer startOffset = cursor.current() != null ? cursor.current().offset() : null;
        try {
            return parseConditionInternal(cursor, warnings);
        } catch (SqlReverseException e) {
            if (KEY_UNSUPPORTED.equals(e.getKey()) && startOffset != null) {
                return degradeToRaw(cursor, startOffset, warnings);
            }
            throw e;
        }
    }

    private Condition parseConditionInternal(Cursor cursor, List<String> warnings) {
        Token head = cursor.current();
        if (head == null) throw new SqlReverseException(cursor.offsetAtEnd(), KEY_PARSE, "条件不完整");
        if (head.type() == TokenType.SYMBOL && "(".equals(head.text())) {
            cursor.advance();
            return parseGroup(cursor, warnings, false);
        }
        if (cursor.isKeyword(head, "NOT") && cursor.isSymbol(cursor.peek(), "(")) {
            cursor.advance();
            cursor.advance();
            return parseGroup(cursor, warnings, true);
        }
        ColumnRef column = parseColumnRef(cursor);
        Token t = cursor.current();
        if (t == null) throw new SqlReverseException(cursor.offsetAtEnd(), KEY_PARSE, "条件不完整");
        if (t.type() == TokenType.SYMBOL && "=".equals(t.text())) {
            cursor.advance();
            return parseBinary(cursor, column, "=");
        }
        if (t.type() == TokenType.SYMBOL && "!".equals(t.text())) {
            cursor.advance();
            expectSymbol(cursor, "=");
            return parseBinary(cursor, column, "!=");
        }
        if (t.type() == TokenType.SYMBOL && "<".equals(t.text())) {
            cursor.advance();
            String nt = cursor.current() != null ? cursor.current().text() : null;
            if ("=".equals(nt)) {
                cursor.advance();
                if (cursor.isSymbol(cursor.current(), ">")) {
                    // T-SqlToJavaPromote G10：`<=>` NULL-safe 等值——MP 无对应方法 → 降级 Raw
                    cursor.advance();
                    unsupported(cursor, "<=> 空安全比较");
                }
                return parseBinary(cursor, column, "<=");
            }
            if (">".equals(nt)) { cursor.advance(); return parseBinary(cursor, column, "<>"); }
            return parseBinary(cursor, column, "<");
        }
        if (t.type() == TokenType.SYMBOL && ">".equals(t.text())) {
            cursor.advance();
            String nt = cursor.current() != null ? cursor.current().text() : null;
            if ("=".equals(nt)) { cursor.advance(); return parseBinary(cursor, column, ">="); }
            return parseBinary(cursor, column, ">");
        }
        if (cursor.isKeyword(t, "IS")) {
            cursor.advance();
            boolean negated = cursor.isKeyword(cursor.current(), "NOT");
            if (negated) cursor.advance();
            if (!cursor.isKeyword(cursor.current(), "NULL")) {
                throw new SqlReverseException(cursor.current() != null ? cursor.current().offset() : t.offset(), KEY_PARSE, "IS 后应为 NULL");
            }
            cursor.advance();
            return new Condition.Unary(column, negated ? "IS NOT NULL" : "IS NULL", null);
        }
        if (cursor.isKeyword(t, "NOT")) {
            cursor.advance();
            return parseNegated(cursor, column, t.offset(), warnings);
        }
        if (cursor.isKeyword(t, "IN")) {
            cursor.advance();
            return parseIn(cursor, column, false, t.offset());
        }
        if (cursor.isKeyword(t, "BETWEEN")) {
            cursor.advance();
            return parseBetween(cursor, column, false, t.offset());
        }
        if (cursor.isKeyword(t, "LIKE")) {
            cursor.advance();
            return parseLike(cursor, column, false, t.offset(), warnings);
        }
        if (t.type() == TokenType.SYMBOL && "(".equals(t.text())) {
            unsupported(cursor, "函数表达式 " + column.getColumnName() + "(...)");
        }
        // T-SqlToJavaPromote G10：列名后无法识别的操作符（算术表达式 `price*qty`、ILIKE 等）
        // → 抛 KEY_UNSUPPORTED 走降级 Raw 透传（「任何条件都不挡路」原则）
        return unsupported(cursor, "条件操作符 " + t.text());
    }

    /**
     * 括号组解析（T-M36.1）：当前位于 `(` 之后；组内递归走 parseConditionList（OR 分段语义一致），
     * 直到 `)`。组内为单个 Group（如 `(a = 1 OR b = 2)`）时直接复用该组（避免双重括号）；
     * 组内仅 1 个非 Group 条件且非取反（T-ReverseParenWira：MPJ 渲染 ON/WHERE 自带括号，
     * `(st.station_id = t.id)`/`(t.id = 1)` 单条件括号无语义增量）时直接解包返回该条件——
     * 否则下游 extractJoinKey 认不出 ColumnComparison 致 MPJ lambda 形态退化、
     * WHERE 多渲染一层 nested；`NOT (a = 1)` 取反组仍保留 Group 承载 negated 标记。
     * 其余多条件情况包 `Group(AND, ...)`（保留括号，round-trip 等价）。空组报语法错误。
     */
    private Condition parseGroup(Cursor cursor, List<String> warnings, boolean negated) {
        List<Condition> inner = parseConditionList(cursor, warnings);
        expectSymbol(cursor, ")");
        if (inner.isEmpty()) {
            throw new SqlReverseException(cursor.previousOffset(), KEY_PARSE, "空括号组");
        }
        Condition single = inner.size() == 1 ? inner.get(0) : null;
        if (single instanceof Condition.Group g) {
            return negated ? new Condition.Group(g.getConnector(), g.getChildren(), g.getToggle(), true) : single;
        }
        if (single != null && !negated) {
            return single;
        }
        return new Condition.Group(Connector.AND, inner, null, negated);
    }

    /** 表达式形态降级为 Condition.Raw。 */
    private Condition.Raw degradeToRaw(Cursor cursor, int startOffset, List<String> warnings) {
        int startIdx = Math.max(indexOfFirst(cursor.tokens, startOffset), 0);
        int depth = 0;
        int idx = startIdx;
        while (idx < cursor.tokens.size()) {
            Token tk = cursor.tokens.get(idx);
            if (depth == 0 && (
                    (tk.type() == TokenType.WORD && (isKeyword(tk, "AND") || isKeyword(tk, "OR") || isClauseToken(tk, getOrNull(cursor.tokens, idx + 1)))) ||
                            (tk.type() == TokenType.SYMBOL && (";".equals(tk.text()) || ")".equals(tk.text())))
            )) {
                break;
            }
            if (tk.type() == TokenType.SYMBOL && "(".equals(tk.text())) depth++;
            if (tk.type() == TokenType.SYMBOL && ")".equals(tk.text())) depth--;
            idx++;
        }
        int endOffset = idx > startIdx ? endOffsetOf(cursor.tokens.get(idx - 1)) : startOffset;
        cursor.pos = idx;
        String rawText = cursor.source.substring(startOffset, endOffset).trim();
        warnings.add("条件「" + rawText + "」无法静态转换为 Wrapper 方法，已用 apply() 透传");
        return new Condition.Raw(rawText, null);
    }

    /** 降级扫描的子句边界。 */
    private boolean isClauseToken(Token t, Token next) {
        return isKeyword(t, "ORDER") || isKeyword(t, "GROUP") || isKeyword(t, "HAVING") ||
                isKeyword(t, "LIMIT") || isKeyword(t, "UNION") || isKeyword(t, "FOR") ||
                (isKeyword(t, "OFFSET") && next != null && isLimitValueStart(next)) ||
                (isKeyword(t, "FETCH") && next != null &&
                        (isKeyword(next, "FIRST") || isKeyword(next, "NEXT")));
    }

    /** 二元比较（= 右侧裸标识符 → 列对列）。 */
    private Condition parseBinary(Cursor cursor, ColumnRef column, String sqlOp) {
        if ("=".equals(sqlOp)) {
            Token next = cursor.current();
            if (next != null && next.type() == TokenType.WORD && !cursor.isKeyword(next, "TRUE") &&
                    !cursor.isKeyword(next, "FALSE") && !cursor.isKeyword(next, "NULL") &&
                    // T-SqlToJavaPromote G10：DATE/TIMESTAMP 类型字面量（后随字符串）排除出列对列判定
                    !cursor.isKeyword(next, "DATE") && !cursor.isKeyword(next, "TIMESTAMP")) {
                Token after = cursor.peek();
                boolean isFunction = after != null && after.type() == TokenType.SYMBOL && "(".equals(after.text());
                if (!isFunction) {
                    ColumnRef right = parseColumnRef(cursor);
                    return new Condition.ColumnComparison(column, right, null);
                }
            }
        }
        SqlValue value = parseValue(cursor);
        if (value == null) {
            return switch (sqlOp) {
                case "=" -> new Condition.Unary(column, "IS NULL", null);
                case "!=", "<>" -> new Condition.Unary(column, "IS NOT NULL", null);
                default -> throw new SqlReverseException(cursor.previousOffset(), KEY_PARSE, "NULL 只能与 = / != 比较");
            };
        }
        return new Condition.Simple(column, sqlOp, value, null);
    }

    /** NOT 变体分发。 */
    private Condition parseNegated(Cursor cursor, ColumnRef column, int notOffset, List<String> warnings) {
        Token t = cursor.current();
        if (t == null) throw new SqlReverseException(notOffset, KEY_PARSE, "NOT 后缺少条件");
        if (cursor.isKeyword(t, "IN")) {
            cursor.advance();
            return parseIn(cursor, column, true, t.offset());
        }
        if (cursor.isKeyword(t, "BETWEEN")) {
            cursor.advance();
            return parseBetween(cursor, column, true, t.offset());
        }
        if (cursor.isKeyword(t, "LIKE")) {
            cursor.advance();
            return parseLike(cursor, column, true, t.offset(), warnings);
        }
        return unsupported(cursor, "NOT 表达式（仅支持 NOT IN / NOT BETWEEN / NOT LIKE）");
    }

    /** IN (v1, v2, ...)。 */
    private Condition.In parseIn(Cursor cursor, ColumnRef column, boolean negated, int opOffset) {
        expectSymbol(cursor, "(");
        List<SqlValue> values = new ArrayList<>();
        while (true) {
            Token t = cursor.current();
            if (t != null && t.type() == TokenType.SYMBOL && ")".equals(t.text())) {
                cursor.advance();
                break;
            }
            SqlValue value = parseValue(cursor);
            if (value == null) throw new SqlReverseException(t != null ? t.offset() : opOffset, KEY_PARSE, "IN 列表不支持 NULL");
            values.add(value);
            if (cursor.isSymbol(cursor.current(), ",")) {
                cursor.advance();
            } else if (!cursor.isSymbol(cursor.current(), ")")) {
                throw new SqlReverseException(cursor.current() != null ? cursor.current().offset() : opOffset, KEY_PARSE, "IN 列表格式不正确");
            }
        }
        if (values.isEmpty()) {
            throw new SqlReverseException(opOffset, KEY_PARSE, "IN 列表为空");
        }
        return new Condition.In(column, negated, values, null);
    }

    /** BETWEEN lo AND hi。 */
    private Condition.Between parseBetween(Cursor cursor, ColumnRef column, boolean negated, int opOffset) {
        SqlValue lo = parseValue(cursor);
        if (lo == null) throw new SqlReverseException(cursor.previousOffset(), KEY_PARSE, "BETWEEN 不支持 NULL 边界");
        if (!cursor.isKeyword(cursor.current(), "AND")) {
            throw new SqlReverseException(cursor.current() != null ? cursor.current().offset() : opOffset, KEY_PARSE, "BETWEEN 缺少 AND");
        }
        cursor.advance();
        SqlValue hi = parseValue(cursor);
        if (hi == null) throw new SqlReverseException(cursor.previousOffset(), KEY_PARSE, "BETWEEN 不支持 NULL 边界");
        return new Condition.Between(column, negated, lo, hi, null);
    }

    /** LIKE '模式'：映射 LikePosition；无法映射 → 降级 Raw。 */
    private Condition parseLike(Cursor cursor, ColumnRef column, boolean negated, int opOffset, List<String> warnings) {
        Token t = cursor.current();
        if (t == null) throw new SqlReverseException(opOffset, KEY_PARSE, "LIKE 后缺少模式");
        if (t.type() == TokenType.SYMBOL && "?".equals(t.text())) {
            cursor.advance();
            return new Condition.Like(column, negated, new SqlValue.Placeholder("?"), LikePosition.NONE, null);
        }
        if (t.type() != TokenType.STRING) {
            throw new SqlReverseException(t.offset(), KEY_PARSE, "LIKE 模式必须是字符串字面量");
        }
        cursor.advance();
        String pattern = decodeString(t.text());
        LikePosition position = likePositionOf(pattern);
        if (position == null) {
            warnings.add("LIKE 模式 " + pattern + " 无法映射为 like 家族方法，已用 apply() 透传");
            return new Condition.Raw(column.getColumnName() + (negated ? " NOT LIKE" : " LIKE") + " " + t.text(), null);
        }
        return new Condition.Like(column, negated, new SqlValue.Literal(t.text(), stripWildcards(pattern)), position, null);
    }

    /** 模式 → LikePosition；无法映射返回 null。 */
    private LikePosition likePositionOf(String pattern) {
        if (pattern.indexOf('_') >= 0 || pattern.indexOf('\\') >= 0) return null;
        if (pattern.indexOf('%') < 0) return null;
        if (pattern.startsWith("%") && pattern.endsWith("%") && pattern.length() >= 2) return LikePosition.ANYWHERE;
        if (pattern.startsWith("%")) return LikePosition.LEFT;
        if (pattern.endsWith("%")) return LikePosition.RIGHT;
        return null;
    }

    /** 去掉模式首尾通配符。 */
    private static String stripWildcards(String pattern) {
        int start = 0;
        int end = pattern.length();
        while (start < end && pattern.charAt(start) == '%') start++;
        while (end > start && pattern.charAt(end - 1) == '%') end--;
        return pattern.substring(start, end);
    }

    /** 解析条件值。null 表示 NULL 字面量。 */
    private SqlValue parseValue(Cursor cursor) {
        Token t = cursor.current();
        if (t == null) throw new SqlReverseException(cursor.offsetAtEnd(), KEY_PARSE, "条件缺少值");
        return switch (t.type()) {
            case STRING -> {
                cursor.advance();
                yield new SqlValue.Literal(t.text(), decodeString(t.text()));
            }
            case NUMBER -> {
                cursor.advance();
                yield new SqlValue.Literal(t.text(), t.text());
            }
            case WORD -> {
                String up = t.text().toUpperCase();
                if ("TRUE".equals(up) || "FALSE".equals(up)) {
                    cursor.advance();
                    yield new SqlValue.Literal(t.text().toLowerCase(), t.text().toLowerCase());
                }
                if ("NULL".equals(up)) {
                    cursor.advance();
                    yield null;
                }
                if ("DATE".equals(up) || "TIMESTAMP".equals(up)) {
                    // ANSI 类型字面量（T-SqlToJavaPromote G10）：DATE '2024-01-01' / TIMESTAMP '...'
                    // raw 保留前缀（往返渲染一致）；后续须是字符串字面量
                    Token next = cursor.peek();
                    if (next == null || next.type() != TokenType.STRING) {
                        throw new SqlReverseException(t.offset(), KEY_UNSUPPORTED, "类型字面量 " + t.text() + " 后缺少字符串");
                    }
                    String prefix = t.text().toUpperCase();
                    cursor.advance();
                    cursor.advance();
                    yield new SqlValue.Literal(prefix + " " + next.text(), decodeString(next.text()));
                }
                throw new SqlReverseException(t.offset(), KEY_UNSUPPORTED, "标识符值 " + t.text() + "（列对列比较/函数表达式暂不支持）");
            }
            case SYMBOL -> {
                if ("?".equals(t.text())) {
                    cursor.advance();
                    yield new SqlValue.Placeholder("?");
                }
                if ("(".equals(t.text())) throw new SqlReverseException(t.offset(), KEY_UNSUPPORTED, "子查询/表达式");
                throw new SqlReverseException(t.offset(), KEY_PARSE, "条件缺少值");
            }
        };
    }

    /** ORDER BY 列 [ASC|DESC]。 */
    private List<OrderByItem> parseOrderBy(Cursor cursor) {
        List<OrderByItem> items = new ArrayList<>();
        while (true) {
            ColumnRef column = parseColumnRef(cursor);
            boolean asc = true;
            Token t = cursor.current();
            if (t != null && t.type() == TokenType.WORD) {
                String up = t.text().toUpperCase();
                if ("ASC".equals(up)) cursor.advance();
                else if ("DESC".equals(up)) { asc = false; cursor.advance(); }
            }
            items.add(new OrderByItem(column, asc));
            if (cursor.isSymbol(cursor.current(), ",")) {
                cursor.advance();
                continue;
            }
            break;
        }
        return items;
    }

    /** LIMIT 形态。 */
    private LimitClause parseLimit(Cursor cursor) {
        SqlValue first = parseLimitValue(cursor);
        if (cursor.isSymbol(cursor.current(), ",")) {
            cursor.advance();
            SqlValue second = parseLimitValue(cursor);
            return new LimitClause(first, second);
        }
        if (cursor.isKeyword(cursor.current(), "OFFSET")) {
            cursor.advance();
            return new LimitClause(parseLimitValue(cursor), first);
        }
        return new LimitClause(null, first);
    }

    /** OFFSET n ROWS FETCH NEXT m ROWS ONLY。 */
    private LimitClause parseOffsetFetch(Cursor cursor) {
        SqlValue offset = parseLimitValue(cursor);
        expectKeyword(cursor, "ROWS");
        expectKeyword(cursor, "FETCH");
        expectKeyword(cursor, "NEXT");
        SqlValue count = parseLimitValue(cursor);
        expectKeyword(cursor, "ROWS");
        expectKeyword(cursor, "ONLY");
        return new LimitClause(offset, count);
    }

    /** FETCH FIRST n ROWS ONLY。 */
    private LimitClause parseFetchFirst(Cursor cursor) {
        Token t = cursor.current();
        if (t == null || !(isKeyword(t, "FIRST") || isKeyword(t, "NEXT"))) {
            throw new SqlReverseException(t != null ? t.offset() : cursor.offsetAtEnd(), KEY_PARSE, "FETCH 后缺少 FIRST/NEXT");
        }
        cursor.advance();
        SqlValue count = parseLimitValue(cursor);
        expectKeyword(cursor, "ROWS");
        expectKeyword(cursor, "ONLY");
        return new LimitClause(null, count);
    }

    /** LIMIT 值：数字字面量或 ? 占位符。 */
    private SqlValue parseLimitValue(Cursor cursor) {
        Token t = cursor.current();
        if (t == null) throw new SqlReverseException(cursor.offsetAtEnd(), KEY_PARSE, "LIMIT 缺少行数");
        if (t.type() == TokenType.NUMBER) {
            cursor.advance();
            return new SqlValue.Literal(t.text(), t.text());
        }
        if (t.type() == TokenType.SYMBOL && "?".equals(t.text())) {
            cursor.advance();
            return new SqlValue.Placeholder("?");
        }
        throw new SqlReverseException(t.offset(), KEY_PARSE, "LIMIT 必须是数字或 ? 占位符");
    }

    /** 列引用（保留表别名供多表归属）。 */
    private ColumnRef parseColumnRef(Cursor cursor) {
        String text = consumeQualified(cursor);
        List<String> parts = Arrays.asList(text.split("\\."));
        String alias = null;
        if (parts.size() > 1) {
            String joined = String.join(".", parts.subList(0, parts.size() - 1));
            joined = stripBackticks(joined);
            alias = joined.isEmpty() ? null : joined;
        }
        return new ColumnRef(alias, stripBackticks(parts.get(parts.size() - 1)), null, null, null, null, null);
    }

    /** 消费 `词[.词]` 组合。 */
    private String consumeQualified(Cursor cursor) {
        Token t = cursor.current();
        if (t == null) throw new SqlReverseException(cursor.offsetAtEnd(), KEY_PARSE, "缺少列名/表名");
        if (t.type() != TokenType.WORD) {
            throw new SqlReverseException(t.offset(), KEY_PARSE, "缺少列名/表名");
        }
        StringBuilder sb = new StringBuilder(t.text());
        cursor.advance();
        if (cursor.isSymbol(cursor.current(), ".")) {
            cursor.advance();
            Token after = cursor.current();
            if (after != null && after.type() == TokenType.WORD) {
                sb.append('.').append(after.text());
                cursor.advance();
            }
        }
        return sb.toString();
    }

    /** 剥点分前缀与反引号，取末段。 */
    private static String stripQualified(String text) {
        int idx = text.lastIndexOf('.');
        String last = idx >= 0 ? text.substring(idx + 1) : text;
        return stripBackticks(last);
    }

    /** 去首尾反引号。 */
    private static String stripBackticks(String s) {
        int start = 0;
        int end = s.length();
        while (start < end && s.charAt(start) == '`') start++;
        while (end > start && s.charAt(end - 1) == '`') end--;
        return s.substring(start, end);
    }

    /** 字符串解转义：去首尾引号 + '' → '。 */
    private static String decodeString(String quoted) {
        String inner = quoted.substring(1, quoted.length() - 1);
        return inner.replace("''", "'");
    }

    private void expectKeyword(Cursor cursor, String kw) {
        Token t = cursor.current();
        if (t == null || t.type() != TokenType.WORD || !t.text().equalsIgnoreCase(kw)) {
            throw new SqlReverseException(t != null ? t.offset() : cursor.offsetAtEnd(), KEY_PARSE, "缺少关键字 " + kw);
        }
        cursor.advance();
    }

    private void expectSymbol(Cursor cursor, String symbol) {
        Token t = cursor.current();
        if (t == null || t.type() != TokenType.SYMBOL || !t.text().equals(symbol)) {
            throw new SqlReverseException(t != null ? t.offset() : cursor.offsetAtEnd(), KEY_PARSE, "缺少符号 " + symbol);
        }
        cursor.advance();
    }

    /** 不支持形态报错（恒抛；返回 Condition 仅用于 when 表达式占位）。 */
    private Condition unsupported(Cursor cursor, String what) {
        Token t = cursor.current();
        throw new SqlReverseException(t != null ? t.offset() : cursor.offsetAtEnd(), KEY_UNSUPPORTED, what);
    }

    /** 偏移量 → 行号（1 起）。 */
    public static int lineOf(String text, int offset) {
        int o = Math.max(0, Math.min(offset, text.length()));
        int line = 1;
        for (int i = 0; i < o; i++) {
            if (text.charAt(i) == '\n') line++;
        }
        return line;
    }

    /** bundle key 常量。 */
    public static final String KEY_NOT_SELECT = "reverse.error.notSelect";
    public static final String KEY_UNSUPPORTED = "reverse.error.unsupported";
    public static final String KEY_PARSE = "reverse.error.parse";

    /** Token 游标。 */
    private static final class Cursor {
        final List<Token> tokens;
        final String source;
        int pos = 0;

        Cursor(List<Token> tokens, String source) {
            this.tokens = tokens;
            this.source = source;
        }

        Token current() {
            return getOrNull(tokens, pos);
        }

        Token peek() {
            return getOrNull(tokens, pos + 1);
        }

        Token advance() {
            Token t = current();
            if (t != null) pos++;
            return t;
        }

        int previousOffset() {
            Token t = getOrNull(tokens, pos - 1);
            return t != null ? t.offset() : 0;
        }

        int offsetAtEnd() {
            Token t = tokens.isEmpty() ? null : tokens.get(tokens.size() - 1);
            return t != null ? t.offset() : 0;
        }

        boolean isKeyword(Token t, String kw) {
            return SqlReverseParser.isKeyword(t, kw);
        }

        boolean isSymbol(Token t, String symbol) {
            return SqlReverseParser.isSymbol(t, symbol);
        }
    }

    /** 静态关键字判定。 */
    private static boolean isKeyword(Token t, String kw) {
        return t != null && t.type() == TokenType.WORD && t.text().equalsIgnoreCase(kw);
    }

    private static boolean isSymbol(Token t, String symbol) {
        return t != null && t.type() == TokenType.SYMBOL && t.text().equals(symbol);
    }

    private static <T> T getOrNull(List<T> list, int i) {
        return (i >= 0 && i < list.size()) ? list.get(i) : null;
    }

    private static int indexOfFirst(List<Token> list, int offset) {
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).offset() == offset) return i;
        }
        return -1;
    }

    private static int indexOfFirst(List<Token> list, java.util.function.Predicate<Token> pred) {
        for (int i = 0; i < list.size(); i++) {
            if (pred.test(list.get(i))) return i;
        }
        return -1;
    }

    private static int endOffsetOf(Token t) {
        return t.offset() + t.text().length();
    }

    // ------------------------------------------------------------------
    // 解析结果模型（与正向 SqlGenerator 同一 Condition 模型，round-trip 自洽）
    // 见同包 SqlReverseResult / SelectColumn / ReverseStatementKind / ReverseSet /
    // JoinSubQuery / ReverseJoin / SqlReverseException 顶层类型定义。
    // ------------------------------------------------------------------

    /** 简单三元组（解析中间产物）。 */
    private record Pair<A, B>(A first, B second) {
    }

    /** 简单三元组（JOIN 表表达式解析中间产物）。 */
    private record Triple<A, B, C>(A first, B second, C third) {
    }
}
