package cn.song.mybatisplus.visualizer.reverse

import cn.song.mybatisplus.visualizer.model.SqlValue

/**
 * SQL→Java 反向转换往返门禁语料库（T-SqlToJavaPromote M1）。
 *
 * 覆盖：四语句型 × 条件算子全家桶 × JOIN 矩阵 × 三方言分页 × 缺口台账 G1~G12 全形态 × 方言宽容清单。
 *
 * [expectation] 为基线档位——M1 提交时=现状实测档（先红基线）；
 * M2/M3 每修复一项，把对应条目升档并把门禁断言语义升级（先红后绿的自然呈现）。
 * 档位语义见 [RoundTripExpectation]。
 *
 * ROUND_TRIP 语料的 SQL 以 SqlGenerator 规范输出形态书写（关键字大写/括号语义/ON 无括号），
 * 门禁断言「反解→桥接→再渲染」规范化后与输入语义等价；存在已登记非对称的形态
 * （方言分页、SELECT * 无列信息渲染）用 PARSE_OK + [assertResult] 结构化断言钉住。
 */
enum class RoundTripExpectation {
    /** 反解→桥接正向模型→SqlGenerator 渲染，规范化后与输入 SQL 语义等价。 */
    ROUND_TRIP,

    /** 能反解（不抛异常），往返渲染存在已登记的非对称（[assertResult] 钉住语义）。 */
    PARSE_OK,

    /** 能反解且发生了 Raw 降级（warnings 非空，生成 apply 透传）。 */
    RAW_DEGRADE,

    /** 当前解析报错（SqlReverseException）——基线缺口，M2/M3 升级。 */
    PARSE_ERROR,
}

data class RoundTripCorpusCase(
    val name: String,
    val sql: String,
    val expectation: RoundTripExpectation,
    /** 可选：解析结果的结构断言（PARSE_OK 档的语义钉；ROUND_TRIP 档可作补充）。 */
    val assertResult: (SqlReverseResult) -> Unit = {},
)

object ReverseRoundTripCorpus {

    /** 方言分页形态的 LIMIT 模型断言（offset/count 字面量值）。 */
    private fun limitAssert(offset: String?, count: String): (SqlReverseResult) -> Unit = { r ->
        val limit = checkNotNull(r.limit) { "应有 LIMIT 模型" }
        if (offset != null) {
            checkNotNull(limit.offset) { "应有 OFFSET" }
            assert((limit.offset as SqlValue.Literal).raw == offset) { "offset 期望 $offset 实际 ${limit.offset}" }
        } else {
            assert(limit.offset == null) { "offset 应为空" }
        }
        assert((limit.count as SqlValue.Literal).raw == count) { "count 期望 $count 实际 ${limit.count}" }
    }

    /**
     * 全语料清单。M1 基线档位=20261006 主仓 49c3d0e 现状实测；
     * M2/M3 修复时逐条升档（先红后绿）。
     */
    val cases: List<RoundTripCorpusCase> = listOf(
        // ------------------------------------------------------------------
        // SELECT 基础与条件算子全家桶
        // ------------------------------------------------------------------
        RoundTripCorpusCase("select_basic", "SELECT id, name FROM member WHERE status = 1", RoundTripExpectation.ROUND_TRIP),
        RoundTripCorpusCase("select_all", "SELECT * FROM member", RoundTripExpectation.PARSE_OK,
            assertResult = { r -> assert(r.selectColumns == null) { "SELECT * 应建模为 selectColumns=null（MP 全列语义）" } }),
        RoundTripCorpusCase("select_no_where", "SELECT id FROM member ORDER BY id DESC", RoundTripExpectation.ROUND_TRIP),
        RoundTripCorpusCase("select_where_ops",
            "SELECT id FROM member WHERE a = 1 AND b != 2 AND c <> 3 AND d > 4 AND e >= 5 AND f < 6 AND g <= 7",
            RoundTripExpectation.ROUND_TRIP),
        RoundTripCorpusCase("select_null", "SELECT id FROM member WHERE a IS NULL AND b IS NOT NULL", RoundTripExpectation.ROUND_TRIP),
        RoundTripCorpusCase("select_in", "SELECT id FROM member WHERE a IN (1, 2, 3) AND b NOT IN ('x', 'y')", RoundTripExpectation.ROUND_TRIP),
        RoundTripCorpusCase("select_between",
            "SELECT id FROM member WHERE a BETWEEN 1 AND 10 AND b NOT BETWEEN 2 AND 8", RoundTripExpectation.ROUND_TRIP),
        RoundTripCorpusCase("select_like_all",
            "SELECT id FROM member WHERE a LIKE '%abc%' AND b LIKE 'abc%' AND c LIKE '%abc' AND d NOT LIKE '%xyz%'",
            RoundTripExpectation.ROUND_TRIP),
        RoundTripCorpusCase("select_like_placeholder", "SELECT id FROM member WHERE a LIKE ?", RoundTripExpectation.ROUND_TRIP),
        RoundTripCorpusCase("select_placeholder", "SELECT id FROM member WHERE a = ? AND b IN (?, ?)", RoundTripExpectation.ROUND_TRIP),
        RoundTripCorpusCase("select_or_group", "SELECT id FROM member WHERE (a = 1 OR b = 2)", RoundTripExpectation.ROUND_TRIP),
        RoundTripCorpusCase("select_paren_group", "SELECT id FROM member WHERE (a = 1 OR b = 2) AND c = 3", RoundTripExpectation.ROUND_TRIP),
        RoundTripCorpusCase("select_not_group", "SELECT id FROM member WHERE NOT (a = 1 OR b = 2)", RoundTripExpectation.ROUND_TRIP),
        RoundTripCorpusCase("select_and_priority",
            "SELECT id FROM member WHERE ((a = 1 AND b = 2) OR c = 3)", RoundTripExpectation.ROUND_TRIP),
        // T-ReverseGroupLogicDelete：OR 后接括号组（旧生成器把 OR 渲染成 AND 的触发形态）。
        // 语料按正向渲染的规范形书写：顶层 OR 组带外层括号、单条件括号被解包。
        RoundTripCorpusCase("select_or_then_and_group",
            "SELECT id FROM member WHERE (a = 1 OR (b = 2 AND c = 3))", RoundTripExpectation.ROUND_TRIP),
        RoundTripCorpusCase("select_or_then_single_group",
            "SELECT id FROM member WHERE (a = 1 OR b = 2)", RoundTripExpectation.ROUND_TRIP),
        RoundTripCorpusCase("select_and_then_not_group",
            "SELECT id FROM member WHERE a = 1 AND NOT (b = 2 OR c = 3)", RoundTripExpectation.ROUND_TRIP),
        RoundTripCorpusCase("select_or_then_not_group",
            "SELECT id FROM member WHERE (a = 1 OR NOT (b = 2 OR c = 3))", RoundTripExpectation.ROUND_TRIP),
        RoundTripCorpusCase("select_column_alias", "SELECT id AS uid, name AS n FROM member", RoundTripExpectation.ROUND_TRIP),
        RoundTripCorpusCase("select_qualified",
            "SELECT t.id, t.name FROM member t WHERE t.status = 1", RoundTripExpectation.ROUND_TRIP),
        RoundTripCorpusCase("select_backtick_table", "SELECT id FROM `user`", RoundTripExpectation.ROUND_TRIP),
        RoundTripCorpusCase("select_backtick_qualified",
            "SELECT `t`.`id` FROM `member` `t` WHERE `t`.`status` = 1", RoundTripExpectation.ROUND_TRIP),
        RoundTripCorpusCase("select_order_multi",
            "SELECT id FROM member ORDER BY create_time DESC, id ASC", RoundTripExpectation.ROUND_TRIP),
        RoundTripCorpusCase("select_order_limit", "SELECT id FROM member ORDER BY id ASC LIMIT 10", RoundTripExpectation.ROUND_TRIP),
        RoundTripCorpusCase("select_limit_plain", "SELECT id FROM member LIMIT 10", RoundTripExpectation.ROUND_TRIP),
        RoundTripCorpusCase("select_limit_comma", "SELECT id FROM member LIMIT 5, 10", RoundTripExpectation.ROUND_TRIP),
        RoundTripCorpusCase("select_limit_offset", "SELECT id FROM member LIMIT 10 OFFSET 5", RoundTripExpectation.PARSE_OK,
            assertResult = limitAssert(offset = "5", count = "10")),
        RoundTripCorpusCase("select_offset_fetch",
            "SELECT id FROM member OFFSET 5 ROWS FETCH NEXT 10 ROWS ONLY", RoundTripExpectation.PARSE_OK,
            assertResult = limitAssert(offset = "5", count = "10")),
        RoundTripCorpusCase("select_fetch_first", "SELECT id FROM member FETCH FIRST 10 ROWS ONLY", RoundTripExpectation.PARSE_OK,
            assertResult = limitAssert(offset = null, count = "10")),
        RoundTripCorpusCase("select_limit_placeholder", "SELECT id FROM member LIMIT ?, ?", RoundTripExpectation.ROUND_TRIP),
        RoundTripCorpusCase("select_comment_line", "SELECT id FROM member WHERE status = 1 -- 活跃", RoundTripExpectation.ROUND_TRIP),
        RoundTripCorpusCase("select_comment_block", "SELECT id FROM member /* 主查询 */ WHERE status = 1", RoundTripExpectation.ROUND_TRIP),

        // ------------------------------------------------------------------
        // 缺口台账 G1~G8（基线=现状档位，M2/M3 升档）
        // ------------------------------------------------------------------
        RoundTripCorpusCase("g3_select_distinct", "SELECT DISTINCT id, name FROM member", RoundTripExpectation.ROUND_TRIP),
        RoundTripCorpusCase("g1_select_group_by", "SELECT id, COUNT(*) FROM member GROUP BY id", RoundTripExpectation.ROUND_TRIP),
        RoundTripCorpusCase("g2_select_having",
            "SELECT id FROM member GROUP BY id HAVING COUNT(*) > 1", RoundTripExpectation.ROUND_TRIP),
        RoundTripCorpusCase("g4_select_for_update", "SELECT id FROM member WHERE id = 1 FOR UPDATE", RoundTripExpectation.PARSE_OK,
            assertResult = { r -> assert(r.forUpdate) { "FOR UPDATE 应建模为 forUpdate=true" } }),
        RoundTripCorpusCase("g8_select_union",
            "SELECT id FROM member WHERE a = 1 UNION (SELECT id FROM member WHERE a = 2)", RoundTripExpectation.ROUND_TRIP),
        RoundTripCorpusCase("g8_select_union_all",
            "SELECT id FROM member WHERE a = 1 UNION ALL (SELECT id FROM member WHERE a = 2)", RoundTripExpectation.ROUND_TRIP),
        RoundTripCorpusCase("g5_select_agg_count", "SELECT COUNT(*) AS cnt FROM member", RoundTripExpectation.ROUND_TRIP),
        RoundTripCorpusCase("g5_select_agg_func",
            "SELECT MAX(price) AS max_price, MIN(price) AS min_price FROM member", RoundTripExpectation.ROUND_TRIP),
        RoundTripCorpusCase("g5_select_func_mixed",
            "SELECT id, DATE_FORMAT(create_time, '%Y-%m') AS ym FROM member", RoundTripExpectation.ROUND_TRIP),
        RoundTripCorpusCase("g7_select_func_where",
            "SELECT id FROM member WHERE DATE_FORMAT(create_time, '%Y') = '2024'", RoundTripExpectation.RAW_DEGRADE),
        RoundTripCorpusCase("g11_select_exists",
            "SELECT id FROM member WHERE EXISTS (SELECT 1 FROM orders WHERE orders.member_id = member.id)", RoundTripExpectation.RAW_DEGRADE),
        RoundTripCorpusCase("g11_select_in_subquery",
            "SELECT id FROM member WHERE a IN (SELECT id FROM orders)", RoundTripExpectation.RAW_DEGRADE),
        RoundTripCorpusCase("g12_scalar_no_from", "SELECT 1", RoundTripExpectation.PARSE_ERROR),

        // ------------------------------------------------------------------
        // 方言宽容 G10（基线=现状档位，M3 升档）
        // ------------------------------------------------------------------
        RoundTripCorpusCase("g10_double_quoted", "SELECT \"id\" FROM \"member\" WHERE \"status\" = 1", RoundTripExpectation.ROUND_TRIP),
        RoundTripCorpusCase("g10_backslash_escape", "SELECT id FROM member WHERE name = 'a\\'b'", RoundTripExpectation.ROUND_TRIP),
        RoundTripCorpusCase("g10_hex_literal", "SELECT id FROM member WHERE status = 0x1F", RoundTripExpectation.ROUND_TRIP),
        RoundTripCorpusCase("g10_date_literal",
            "SELECT id FROM member WHERE create_time = DATE '2024-01-01'", RoundTripExpectation.ROUND_TRIP),
        RoundTripCorpusCase("g10_timestamp_literal",
            "SELECT id FROM member WHERE create_time = TIMESTAMP '2024-01-01 10:00:00'", RoundTripExpectation.ROUND_TRIP),
        RoundTripCorpusCase("g10_arithmetic", "SELECT id FROM member WHERE price * qty > 100", RoundTripExpectation.RAW_DEGRADE),
        RoundTripCorpusCase("g10_nullsafe_eq", "SELECT id FROM member WHERE a <=> b", RoundTripExpectation.RAW_DEGRADE),
        RoundTripCorpusCase("g10_ilike", "SELECT id FROM member WHERE name ILIKE '%abc%'", RoundTripExpectation.RAW_DEGRADE),

        // ------------------------------------------------------------------
        // JOIN 矩阵（T-M37）
        // ------------------------------------------------------------------
        RoundTripCorpusCase("join_left_basic",
            "SELECT t.id, t1.tel FROM member t LEFT JOIN address t1 ON t1.member_id = t.id WHERE t.status = 1",
            RoundTripExpectation.ROUND_TRIP),
        RoundTripCorpusCase("join_multi",
            "SELECT t.id, t1.tel, t2.name FROM member t " +
                "LEFT JOIN address t1 ON t1.member_id = t.id AND t1.status = 1 " +
                "INNER JOIN region t2 ON t2.id = t1.region_id WHERE t.sex = 1",
            RoundTripExpectation.ROUND_TRIP),
        RoundTripCorpusCase("join_subquery",
            "SELECT t.id, t.name, addr.tel FROM member t " +
                "LEFT JOIN (SELECT user_id, tel FROM address WHERE status >= 0) addr ON addr.user_id = t.id " +
                "WHERE t.id = 1",
            RoundTripExpectation.ROUND_TRIP),
        RoundTripCorpusCase("join_from_subquery",
            "SELECT t.id FROM (SELECT id, name FROM member WHERE id >= 0 LIMIT 100) t WHERE t.id >= 1",
            RoundTripExpectation.PARSE_OK),

        // ------------------------------------------------------------------
        // UPDATE
        // ------------------------------------------------------------------
        RoundTripCorpusCase("update_basic", "UPDATE member SET name = 'x', status = 1 WHERE id = 10", RoundTripExpectation.ROUND_TRIP),
        RoundTripCorpusCase("update_no_where", "UPDATE member SET status = 0", RoundTripExpectation.ROUND_TRIP),
        RoundTripCorpusCase("update_placeholder", "UPDATE member SET name = ? WHERE id = ?", RoundTripExpectation.ROUND_TRIP),
        RoundTripCorpusCase("update_func_set", "UPDATE member SET update_time = NOW() WHERE id = 1", RoundTripExpectation.ROUND_TRIP),
        RoundTripCorpusCase("update_limit", "UPDATE member SET status = 0 WHERE id > 5 LIMIT 10", RoundTripExpectation.PARSE_OK),
        RoundTripCorpusCase("g9_update_join",
            "UPDATE member t LEFT JOIN address t1 ON t1.member_id = t.id SET t.status = 1 WHERE t1.city = 'x'",
            RoundTripExpectation.PARSE_ERROR),

        // ------------------------------------------------------------------
        // DELETE
        // ------------------------------------------------------------------
        RoundTripCorpusCase("delete_basic", "DELETE FROM member WHERE id = 1", RoundTripExpectation.ROUND_TRIP),
        RoundTripCorpusCase("delete_all", "DELETE FROM member", RoundTripExpectation.ROUND_TRIP),
        RoundTripCorpusCase("delete_limit", "DELETE FROM member WHERE id > 10 LIMIT 5", RoundTripExpectation.ROUND_TRIP),
        RoundTripCorpusCase("delete_multi",
            "DELETE t, t1 FROM member t LEFT JOIN address t1 ON t1.member_id = t.id WHERE t1.city = 'x'",
            RoundTripExpectation.ROUND_TRIP),

        // ------------------------------------------------------------------
        // INSERT（无正向模型，门禁走反解字段断言+测试渲染器比对）
        // ------------------------------------------------------------------
        RoundTripCorpusCase("insert_basic", "INSERT INTO member (id, name) VALUES (1, 'a')", RoundTripExpectation.ROUND_TRIP),
        RoundTripCorpusCase("insert_multi_row",
            "INSERT INTO member (id, name) VALUES (1, 'a'), (2, 'b')", RoundTripExpectation.ROUND_TRIP),
        RoundTripCorpusCase("insert_placeholder", "INSERT INTO member (id, name) VALUES (?, ?)", RoundTripExpectation.ROUND_TRIP),
        RoundTripCorpusCase("g6_insert_null", "INSERT INTO member (id, name, age) VALUES (1, 'a', NULL)", RoundTripExpectation.ROUND_TRIP),
        RoundTripCorpusCase("g12_insert_no_cols", "INSERT INTO member VALUES (1, 'a')", RoundTripExpectation.PARSE_ERROR),
        RoundTripCorpusCase("g12_insert_select", "INSERT INTO member (id) SELECT id FROM old_member", RoundTripExpectation.PARSE_ERROR),

        // ------------------------------------------------------------------
        // G12 不适用类（维持报错，文案清晰化）
        // ------------------------------------------------------------------
        RoundTripCorpusCase("g12_ddl_create", "CREATE TABLE member (id INT)", RoundTripExpectation.PARSE_ERROR),
    )

    /** 语料分档计数（基线报告用）。 */
    fun counts(): Map<RoundTripExpectation, Int> = cases.groupingBy { it.expectation }.eachCount()
}
