package cn.song.mybatisplus.visualizer.execute

import cn.song.mybatisplus.visualizer.execute.UnboundedWriteDetector.FindingKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 无界写探测器表驱动测试（T-UnboundedWriteGuard / O-9，T-UW-01/04）：
 * 每条规则 ≥3 正例 + 反例 + 解析失败样例 + 多语句取最高风险。
 * 普通 JUnit（零 IDE fixture）——探测器仅依赖 JSqlParser + SqlCommentStripper。
 *
 * 反例选用真实业务形态（有界 WHERE / LIKE 前缀匹配 / 注释内干扰词），锁「不误报」语义。
 */
class UnboundedWriteDetectorTest {

    private fun kindsOf(sql: String): Set<FindingKind> = UnboundedWriteDetector.detect(sql).kinds()

    private fun dangerous(sql: String): Boolean = UnboundedWriteDetector.detect(sql).dangerous

    // ======================== WHERE 缺失 ========================

    @Test
    fun `WHERE缺失_DELETE三正例`() {
        assertTrue(kindsOf("DELETE FROM t").contains(FindingKind.MISSING_WHERE))
        assertTrue(kindsOf("DELETE FROM t;").contains(FindingKind.MISSING_WHERE))
        assertTrue(kindsOf("delete from `order`").contains(FindingKind.MISSING_WHERE))
    }

    @Test
    fun `WHERE缺失_UPDATE正例与带WHERE反例`() {
        assertTrue(kindsOf("UPDATE t SET a = 1").contains(FindingKind.MISSING_WHERE))
        assertTrue(kindsOf("UPDATE t SET a = 1 WHERE id = 1").isEmpty())
    }

    @Test
    fun `INSERT不判定_忽略`() {
        assertTrue("INSERT 不适用无界写规则", UnboundedWriteDetector.detect("INSERT INTO t (a) VALUES (1)").findings.isEmpty())
    }

    // ======================== 恒真谓词 ========================

    @Test
    fun `恒真谓词_三形态正例`() {
        assertTrue(kindsOf("DELETE FROM t WHERE 1 = 1").contains(FindingKind.TAUTOLOGY))
        assertTrue(kindsOf("DELETE FROM t WHERE a = a").contains(FindingKind.TAUTOLOGY))
        assertTrue(kindsOf("DELETE FROM t WHERE 'x' = 'x'").contains(FindingKind.TAUTOLOGY))
    }

    @Test
    fun `恒真谓词_TRUE字面量`() {
        assertTrue(kindsOf("DELETE FROM t WHERE TRUE").contains(FindingKind.TAUTOLOGY))
        assertTrue(kindsOf("UPDATE t SET a = 1 WHERE true").contains(FindingKind.TAUTOLOGY))
    }

    @Test
    fun `恒真谓词_AND链内嵌`() {
        val kinds = kindsOf("DELETE FROM t WHERE id = 1 AND 1 = 1")
        assertTrue(kinds.contains(FindingKind.TAUTOLOGY))
    }

    @Test
    fun `恒真谓词_业务形态反例`() {
        assertTrue(kindsOf("DELETE FROM t WHERE id = 1").isEmpty())
        assertTrue(kindsOf("DELETE FROM t WHERE id = 1 AND status = 2").isEmpty())
        assertTrue(kindsOf("DELETE FROM t WHERE a = b").isEmpty()) // 列对列不等名——非恒真
    }

    // ======================== OR 恒真 ========================

    @Test
    fun `OR恒真_正例`() {
        assertTrue(kindsOf("DELETE FROM t WHERE id = 1 OR 1 = 1").contains(FindingKind.OR_TAUTOLOGY))
        assertTrue(kindsOf("DELETE FROM t WHERE id = 1 OR id = id").contains(FindingKind.OR_TAUTOLOGY))
        assertTrue(kindsOf("UPDATE t SET a = 1 WHERE status = 1 OR 'a' = 'a'").contains(FindingKind.OR_TAUTOLOGY))
    }

    @Test
    fun `OR恒真_括号内嵌形态`() {
        assertTrue(kindsOf("DELETE FROM t WHERE (b = 2 OR 1 = 1) AND c = 3").contains(FindingKind.OR_TAUTOLOGY))
    }

    @Test
    fun `OR恒真_两侧均非常真反例`() {
        assertTrue(kindsOf("DELETE FROM t WHERE id = 1 OR id = 2").isEmpty())
    }

    // ======================== LIKE 全匹配 ========================

    @Test
    fun `LIKE纯通配_正例`() {
        assertTrue(kindsOf("DELETE FROM t WHERE name LIKE '%'").contains(FindingKind.LIKE_WILDCARD))
        assertTrue(kindsOf("DELETE FROM t WHERE name LIKE '%%'").contains(FindingKind.LIKE_WILDCARD))
        assertTrue(kindsOf("UPDATE t SET a = 1 WHERE name LIKE '%' AND tenant = 5").contains(FindingKind.LIKE_WILDCARD))
    }

    @Test
    fun `LIKE纯通配_前缀匹配反例`() {
        assertTrue(kindsOf("DELETE FROM t WHERE name LIKE 'abc%'").isEmpty())
        assertTrue(kindsOf("DELETE FROM t WHERE name LIKE '%abc'").isEmpty())
        assertTrue("NOT LIKE '%' 匹配空集不算危险", kindsOf("DELETE FROM t WHERE name NOT LIKE '%'").isEmpty())
    }

    // ======================== 无列引用 ========================

    @Test
    fun `无列引用_常量比较正例`() {
        assertTrue(kindsOf("DELETE FROM t WHERE 1 < 2").contains(FindingKind.NO_COLUMN_REF))
        assertTrue(kindsOf("UPDATE t SET a = 1 WHERE 5 > 3").contains(FindingKind.NO_COLUMN_REF))
    }

    @Test
    fun `无列引用_恒真时不重复报`() {
        // WHERE 1=1 → TAUTOLOGY 已报，不再叠 NO_COLUMN_REF
        assertEquals(setOf(FindingKind.TAUTOLOGY), kindsOf("DELETE FROM t WHERE 1 = 1"))
        // WHERE 1=2 → 常量比较不恒真 → 仅报无列引用
        assertEquals(setOf(FindingKind.NO_COLUMN_REF), kindsOf("DELETE FROM t WHERE 1 = 2"))
    }

    // ======================== 子查询（仅提示） ========================

    @Test
    fun `子查询_IN与标量两形态`() {
        val inSub = UnboundedWriteDetector.detect("DELETE FROM t WHERE id IN (SELECT id FROM u)")
        assertTrue(inSub.kinds().contains(FindingKind.SUBQUERY))
        assertFalse("子查询仅提示不算危险", inSub.dangerous)

        val scalar = UnboundedWriteDetector.detect("UPDATE t SET a = 1 WHERE b = (SELECT MAX(x) FROM u)")
        assertTrue(scalar.kinds().contains(FindingKind.SUBQUERY))
        assertFalse(scalar.dangerous)
    }

    // ======================== 解析失败 fail closed ========================

    @Test
    fun `解析失败_按危险处理`() {
        val risk = UnboundedWriteDetector.detect("DELETE FROM t WHERE") // 残缺语句
        assertTrue(risk.kinds().contains(FindingKind.PARSE_FAILED))
        assertTrue("PARSE_FAILED fail closed", risk.dangerous)
    }

    // ======================== O-64/T-RollbackIdentityParse：identity 包装产物 ========================

    /**
     * P19 正例（D1=A）：自产 identity 包装产物不再误报「无法解析」。
     * JSqlParser 5.3 对 `SET IDENTITY_INSERT <表> ON|OFF` 抛 ParseException（离线五形态实证），
     * 剥离开关语句后只剩 INSERT（不适用无界写规则）→ 无发现、不危险。
     */
    @Test
    fun `identity包装产物_SQLServer形态_不再报无法解析`() {
        val product = "SET IDENTITY_INSERT [user] ON;\n" +
            "INSERT INTO [user] ([id], [name]) VALUES (1, 'Tom');\n" +
            "SET IDENTITY_INSERT [user] OFF;"
        val risk = UnboundedWriteDetector.detect(product)
        assertTrue("P19 误报必须消除: ${risk.kinds()}", risk.findings.isEmpty())
        assertFalse(risk.dangerous)
    }

    /** 双方言覆盖（前提 §二.4）：DM8 与 SQL Server 共用 identityInsertWrapper；DM8 产物为裸表名形态（RollbackSqlGeneratorTest rt_del 同款）。 */
    @Test
    fun `identity包装产物_DM8形态_不再报无法解析`() {
        val product = "SET IDENTITY_INSERT T_AUTOINC ON;\n" +
            "INSERT INTO T_AUTOINC (ID, NAME) VALUES (1, 'a');\n" +
            "SET IDENTITY_INSERT T_AUTOINC OFF;"
        val risk = UnboundedWriteDetector.detect(product)
        assertTrue("DM8 形态同享修复: ${risk.kinds()}", risk.findings.isEmpty())
        assertFalse(risk.dangerous)
    }

    /** 剥离只作用于开关语句——包装内真写语句的发现必须原样浮出（剥离不得变成洗白）。 */
    @Test
    fun `identity包装内真写语句_发现不被剥离吞掉`() {
        val product = "SET IDENTITY_INSERT [t] ON;\n" +
            "UPDATE t SET a = 1;\n" +
            "SET IDENTITY_INSERT [t] OFF;"
        val risk = UnboundedWriteDetector.detect(product)
        assertTrue("无 WHERE 的 UPDATE 必须照报: ${risk.kinds()}", risk.kinds().contains(FindingKind.MISSING_WHERE))
        assertTrue(risk.dangerous)
    }

    // ======================== 多语句取最高风险 ========================

    @Test
    fun `多语句_任一语句命中即聚合`() {
        val risk = UnboundedWriteDetector.detect("DELETE FROM t WHERE id = 1; DELETE FROM u")
        assertTrue(risk.kinds().contains(FindingKind.MISSING_WHERE))
        assertTrue(risk.dangerous)
    }

    @Test
    fun `多语句_全部有界则无发现`() {
        val risk = UnboundedWriteDetector.detect("UPDATE t SET a = 1 WHERE id = 1; DELETE FROM u WHERE id = 2")
        assertTrue(risk.findings.isEmpty())
        assertFalse(risk.dangerous)
    }

    // ======================== 注释剥离 ========================

    @Test
    fun `注释内干扰不误判`() {
        // 注释里的 1=1 / % 不参与判定；注释剥离后语句无 WHERE
        val risk = UnboundedWriteDetector.detect("DELETE FROM t /* WHERE 1=1 LIKE '%' */")
        assertEquals(setOf(FindingKind.MISSING_WHERE), risk.kinds())
    }
}
