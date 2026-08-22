package cn.song.mybatisplus.visualizer.sql

import cn.song.mybatisplus.visualizer.sql.perf.PerfSeverity
import cn.song.mybatisplus.visualizer.sql.perf.PerfWarning
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 复杂度评分器测试（F-3）：普通 JUnit 测试类（零 IDE fixture），
 * 覆盖 [ComplexityScorer] 的评分计算与各维度提取器。
 *
 * 评分模型：
 *  - 结构维度（Phase A）：表数量、JOIN 数量、子查询嵌套深度
 *  - 风险维度（Phase B）：SELECT *、前导通配符、无 WHERE、函数包裹列等
 */
class ComplexityScorerTest {

    // ========== Phase A 基础评分（结构维度） ==========

    @Test
    fun `computeScore_简单单表查询_低分`() {
        // 单表、无 JOIN、无子查询 → 低分（Simple 0-20）
        // tableCount=1 → 1*3=3
        val sql = "SELECT id, name FROM t_user WHERE id = 1"
        val score = ComplexityScorer.computeScore(sql)
        assertTrue("单表简单查询应在 0-20，实际=$score", score in 0..20)
    }

    @Test
    fun `computeScore_三表JOIN_中等分`() {
        // 4 表 3 JOIN → 4*3+3*5=27（有 WHERE 避免无 WHERE 加分）
        val sql = "SELECT u.id, o.id, p.name, c.name FROM t_user u " +
            "JOIN t_order o ON u.id = o.user_id " +
            "JOIN t_product p ON o.product_id = p.id " +
            "JOIN t_category c ON p.category_id = c.id " +
            "WHERE u.status = 1"
        val score = ComplexityScorer.computeScore(sql)
        assertTrue("4 表 3 JOIN 应在 21-40，实际=$score", score in 21..40)
    }

    @Test
    fun `computeScore_多表JOIN加子查询_高分`() {
        // 8 表、6 JOIN、1 层子查询 → 高分（60+）
        // tableCount=8 → 8*3=24, joinCount=6 → 6*5=30, subqueryDepth=1 → 1*10=10, total=64
        val sql = "SELECT u.id, o.id, r.content FROM t_user u " +
            "JOIN t_order o ON u.id = o.user_id " +
            "JOIN t_product p ON o.product_id = p.id " +
            "JOIN t_category c ON p.category_id = c.id " +
            "JOIN t_address a ON u.address_id = a.id " +
            "JOIN t_tag t ON p.id = t.product_id " +
            "JOIN t_review r ON o.id = r.order_id " +
            "WHERE u.id IN (SELECT user_id FROM t_vip WHERE level > 3)"
        val score = ComplexityScorer.computeScore(sql)
        assertTrue("多表 JOIN + 子查询应 60+，实际=$score", score >= 60)
    }

    @Test
    fun `computeScore_空SQL返回0`() {
        assertEquals(0, ComplexityScorer.computeScore(""))
        assertEquals(0, ComplexityScorer.computeScore("   "))
    }

    @Test
    fun `computeScore_无法解析返回负1`() {
        // 完全无法识别的文本 → -1
        assertEquals(-1, ComplexityScorer.computeScore("THIS IS NOT SQL"))
    }

    // ========== Phase A 评分等级边界 ==========

    @Test
    fun `scoreLevel_简单等级`() {
        assertEquals(ComplexityScorer.ScoreLevel.SIMPLE, ComplexityScorer.scoreLevel(0))
        assertEquals(ComplexityScorer.ScoreLevel.SIMPLE, ComplexityScorer.scoreLevel(20))
    }

    @Test
    fun `scoreLevel_中等等级`() {
        assertEquals(ComplexityScorer.ScoreLevel.MEDIUM, ComplexityScorer.scoreLevel(21))
        assertEquals(ComplexityScorer.ScoreLevel.MEDIUM, ComplexityScorer.scoreLevel(40))
    }

    @Test
    fun `scoreLevel_较复杂等级`() {
        assertEquals(ComplexityScorer.ScoreLevel.COMPLEX, ComplexityScorer.scoreLevel(41))
        assertEquals(ComplexityScorer.ScoreLevel.COMPLEX, ComplexityScorer.scoreLevel(60))
    }

    @Test
    fun `scoreLevel_复杂等级`() {
        assertEquals(ComplexityScorer.ScoreLevel.VERY_COMPLEX, ComplexityScorer.scoreLevel(61))
        assertEquals(ComplexityScorer.ScoreLevel.VERY_COMPLEX, ComplexityScorer.scoreLevel(80))
    }

    @Test
    fun `scoreLevel_极复杂等级`() {
        assertEquals(ComplexityScorer.ScoreLevel.EXTREME, ComplexityScorer.scoreLevel(81))
        assertEquals(ComplexityScorer.ScoreLevel.EXTREME, ComplexityScorer.scoreLevel(100))
    }

    // ========== Phase B 风险维度 ==========

    @Test
    fun `computeScore_无WHERE的UPDATE_高分`() {
        // UPDATE 无 WHERE → 极高风险（Phase B 维度：MISSING_WHERE_DML=50 + JOIN=5 + TABLE=6）
        val sql = "UPDATE t_user u JOIN t_order o ON u.id = o.user_id " +
            "SET u.status = 1, o.updated_at = NOW()"
        val score = ComplexityScorer.computeScore(sql)
        assertTrue("UPDATE 无 WHERE 应 60+，实际=$score", score >= 60)
    }

    @Test
    fun `computeScore_SELECT星号_加分`() {
        val withStar = ComplexityScorer.computeScore("SELECT * FROM t_user WHERE id = 1")
        val withoutStar = ComplexityScorer.computeScore("SELECT id FROM t_user WHERE id = 1")
        assertTrue("SELECT * 应比指定列分数高 (star=$withStar, noStar=$withoutStar)",
            withStar > withoutStar)
    }

    @Test
    fun `computeScore_前导通配符_加分`() {
        val withWildcard = ComplexityScorer.computeScore(
            "SELECT id FROM t_user WHERE name LIKE '%abc'"
        )
        val withoutWildcard = ComplexityScorer.computeScore(
            "SELECT id FROM t_user WHERE name = 'abc'"
        )
        assertTrue("前导通配符应加分 (wildcard=$withWildcard, noWildcard=$withoutWildcard)",
            withWildcard > withoutWildcard)
    }

    @Test
    fun `computeScore_子查询嵌套深度_越深分越高`() {
        val depth1 = ComplexityScorer.computeScore(
            "SELECT id FROM t_user WHERE id IN (SELECT user_id FROM t_vip)"
        )
        val depth2 = ComplexityScorer.computeScore(
            "SELECT id FROM t_user WHERE id IN " +
                "(SELECT user_id FROM t_vip WHERE level IN (SELECT level FROM t_config))"
        )
        assertTrue("2 层嵌套应比 1 层分数高 (d1=$depth1, d2=$depth2)", depth2 > depth1)
    }

    @Test
    fun `computeScore_UNION_加分`() {
        val withUnion = ComplexityScorer.computeScore(
            "SELECT id FROM t_user UNION SELECT id FROM t_admin"
        )
        val withoutUnion = ComplexityScorer.computeScore("SELECT id FROM t_user")
        assertTrue("UNION 应加分 (union=$withUnion, noUnion=$withoutUnion)",
            withUnion > withoutUnion)
    }

    @Test
    fun `computeScore_分数上限100`() {
        // 构造极复杂 SQL，分数不应超过 100
        val sql = "SELECT * FROM t_user u " +
            "JOIN t_order o ON u.id = o.user_id " +
            "JOIN t_product p ON o.product_id = p.id " +
            "JOIN t_category c ON p.category_id = c.id " +
            "JOIN t_address a ON u.address_id = a.id " +
            "JOIN t_tag t ON p.id = t.product_id " +
            "JOIN t_review r ON o.id = r.order_id " +
            "JOIN t_shipping s ON o.id = s.order_id " +
            "WHERE u.id IN (SELECT user_id FROM t_vip WHERE level > 3) " +
            "AND o.id IN (SELECT order_id FROM t_payment WHERE amount > 1000) " +
            "OR u.status = 1 " +
            "ORDER BY RAND()"
        val score = ComplexityScorer.computeScore(sql)
        assertTrue("分数不应超过 100，实际=$score", score <= 100)
        assertTrue("极复杂 SQL 应高分", score >= 60)
    }

    // ========== 评分与警告联动 ==========

    @Test
    fun `computeScoreWithWarnings_警告严重度叠加`() {
        val sql = "SELECT id, name FROM t_user WHERE id = 1"
        val baseScore = ComplexityScorer.computeScore(sql)

        // WARN 应叠加分数
        val withWarning = ComplexityScorer.computeScoreWithWarnings(
            sql,
            listOf(PerfWarning("test", PerfSeverity.WARN, "test.msg")),
        )
        assertTrue("WARN 应叠加分数 (base=$baseScore, warn=$withWarning)",
            withWarning > baseScore)

        // WARN 比 INFO 叠加更多
        val withInfo = ComplexityScorer.computeScoreWithWarnings(
            sql,
            listOf(PerfWarning("test", PerfSeverity.INFO, "test.msg")),
        )
        assertTrue("INFO 应叠加分数 (base=$baseScore, info=$withInfo)", withInfo > baseScore)
        assertTrue("WARN 应比 INFO 叠加更多 (warn=$withWarning, info=$withInfo)",
            withWarning > withInfo)
    }

    // ========== 评分详情 breakdown ==========

    @Test
    fun `scoreBreakdown_返回各维度得分`() {
        val sql = "SELECT u.id, o.id FROM t_user u JOIN t_order o ON u.id = o.user_id"
        val breakdown = ComplexityScorer.scoreBreakdown(sql)
        assertTrue("应含表数量维度", breakdown.containsKey("tableCount"))
        assertTrue("应含JOIN 维度", breakdown.containsKey("joinCount"))
        val total = breakdown["total"]!!
        val dimensionSum = breakdown.filterKeys { it != "total" }.values.sum()
        assertTrue("总分应等于各维度之和 (total=$total, sum=$dimensionSum)", dimensionSum == total)
    }
}
