package cn.song.mybatisplus.visualizer.reverse

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T-AliasDualTrack（O-18）别名双轨定案防御测试。
 *
 * DBX 吸收清单 #22 的对应面自查定案：本插件无「查询结果生成 INSERT」入口（crud_sql 仅从
 * MP 实体 PSI 生成，入参只有 filePath/className），DBX 踩坑前提不存在；别名双轨自 T-M46
 * 起就是分离设计——展示轨（SELECT 列别名）与实名轨（列归属/条件/排序）互不读写。
 * 本文件按四路径把现状钉死，后续重构碰坏即红：
 *
 *  - 路径 1 MP 核心 lambda 请求：别名投影必须整链回落 QueryWrapper，单次 select 保留全部列；
 *    MP 核心没有 selectAs。MPJ_LAMBDA 才保留 selectAs(实名ref, "别名") 正对照。
 *  - 路径 2 rawMode 回落：`JavaCodeGenerator.kt` :872-875，MPJQueryWrapper 无
 *    selectAs(String, SFunction) 重载，`"expr AS alias"` 整串保留——**有意行为，非缺陷**。
 *  - 路径 3 表达式/未映射列回落：运算表达式列（`a+b AS total`）在解析层即被拒绝（列清单扫描
 *    在运算符 token 处中断，FROM 判定落空抛 SqlReverseException）——计划预设的「整串回落」
 *    实为「解析拒绝」，表达式列从不携带别名进入生成器（审计如实偏离记录）；「字符串回落」
 *    真正的触达面是未映射列：MP 核心整链回落字符串投影，保留表限定名和 AS 别名；
 *    不生成 MP 核心不存在的 selectAs(String, String)。
 *  - 路径 4 WHERE/ORDER BY 列：SqlReverseParser.parseColumnRef（:1199-1204）只消费限定名、
 *    从不读 AS 别名；renderCondition（:929-941）/renderOrderBy（:1027）走实名 ColumnRef——
 *    条件轨与展示轨天然隔离（含反向：条件里误写 SELECT 别名也不会被「纠偏」成实名）。
 */
class AliasDualTrackDefenseTest {

    /** 人员表实体映射（psn_no → psnNo）。 */
    private val personnel = JavaCodeGenerator.EntityTarget(
        className = "Personnel",
        columnToField = mapOf("psn_no" to "psnNo", "psn_name" to "psnName", "age" to "age"),
    )

    private fun assertThrows(block: () -> Unit): SqlReverseException =
        try {
            block()
            throw AssertionError("期望抛出 SqlReverseException")
        } catch (e: SqlReverseException) {
            e
        }

    // ---------- 路径 1：lambda 生成（实名 ref + 别名第二参，双轨同现不互染） ----------

    @Test
    fun `路径1_MP核心别名整链回落保留全部投影_MPJ仍有selectAs`() {
        val result = SqlReverseParser.parse("SELECT psn_no AS psn, psn_name FROM personnel")
        assertEquals(listOf(SelectColumn("psn_no", "psn"), SelectColumn("psn_name")), result.selectColumns)
        for (kind in listOf(GeneratedWrapper.LAMBDA, GeneratedWrapper.WRAPPERS_LAMBDA)) {
            val code = JavaCodeGenerator.generate(result, personnel, kind)
            assertFalse("MP核心不存在selectAs", code.contains(".selectAs("))
            GeneratedProjectionCompiler.assertProjection(code, "psn_no AS psn,psn_name")
        }
        val mpj = JavaCodeGenerator.generate(result, personnel, GeneratedWrapper.MPJ_LAMBDA)
        assertTrue(mpj, mpj.contains(".selectAs(Personnel::getPsnNo, \"psn\")"))
        assertTrue(mpj, mpj.contains(".select(Personnel::getPsnName)"))
        assertFalse("展示别名不得替代实名字段", mpj.contains("Personnel::getPsn)"))
    }

    // ---------- 路径 2：rawMode 整串保留（有意行为，测试按现状锁） ----------

    @Test
    fun `路径2_rawMode回落_select整串保留别名_不产selectAs`() {
        val result = SqlReverseParser.parse("SELECT psn_no AS psn FROM personnel")
        val code = JavaCodeGenerator.generate(result, personnel, GeneratedWrapper.MPJ_QUERY)
        assertTrue(code, code.contains(".select(\"psn_no AS psn\")"))
        assertFalse("MPJQueryWrapper 无 selectAs(String, SFunction) 重载，不得产出 selectAs", code.contains("selectAs"))
    }

    // ---------- 路径 3：表达式列拒绝 + 未映射列字符串回落（双轨形态不变） ----------

    @Test
    fun `路径3a_运算表达式列_解析层拒绝_不产半截列清单`() {
        // `a + b AS total`：列清单扫描在运算符 token 中断，FROM 判定落空 → 整语句拒绝。
        // 表达式列（含别名）从不进入生成器——DBX #22 担心的「表达式结果列名错乱」无触达面。
        assertThrows { SqlReverseParser.parse("SELECT a + b AS total FROM personnel") }
        assertThrows { SqlReverseParser.parse("SELECT age * 2 AS doubleAge FROM personnel WHERE age > 1") }
    }

    @Test
    fun `路径3b_函数列_回落字符串select_别名保留`() {
        // T-SqlToJavaPromote G5：聚合列不再放弃——回落字符串 select("MAX(age) AS maxAge")（lambda 形态亦可，
        // 聚合列不可 lambda 引用）。别名作为 SQL 串内 AS 保留，不产生 selectAs。
        val result = SqlReverseParser.parse("SELECT MAX(age) AS maxAge FROM personnel")
        assertEquals(listOf(SelectColumn("MAX(age)", "maxAge")), result.selectColumns)
        val code = JavaCodeGenerator.generate(result, personnel, GeneratedWrapper.LAMBDA)
        assertTrue("聚合列回落字符串 select 且保留 AS 别名", code.contains("select(\"MAX(age) AS maxAge\")"))
        assertFalse("聚合列不可 lambda 引用，不得产出 selectAs", code.contains("selectAs"))
        GeneratedProjectionCompiler.assertProjection(code, "MAX(age) AS maxAge")
    }

    @Test
    fun `路径3c_未映射列MP核心整链回落_保留表限定名与展示别名`() {
        val result = SqlReverseParser.parse("SELECT t.unknown_col AS total FROM personnel t")
        assertEquals(listOf(SelectColumn("t.unknown_col", "total")), result.selectColumns)
        for (kind in listOf(GeneratedWrapper.LAMBDA, GeneratedWrapper.WRAPPERS_LAMBDA)) {
            val code = JavaCodeGenerator.generate(result, personnel, kind)
            assertFalse(code, code.contains(".selectAs("))
            GeneratedProjectionCompiler.assertProjection(code, "t.unknown_col AS total")
        }
    }

    // ---------- 路径 4：WHERE / ORDER BY 取实名，别名不污染条件轨 ----------

    @Test
    fun `路径4_MP核心整链回落_WHERE与ORDER仍取实名`() {
        val result = SqlReverseParser.parse("SELECT psn_no AS psn FROM personnel WHERE psn_no = '1' ORDER BY psn_no")
        for (kind in listOf(GeneratedWrapper.LAMBDA, GeneratedWrapper.WRAPPERS_LAMBDA)) {
            val code = JavaCodeGenerator.generate(result, personnel, kind)
            assertFalse(code, code.contains(".selectAs("))
            GeneratedProjectionCompiler.assertProjection(code, "psn_no AS psn", "psn_no =", "ORDER BY psn_no ASC")
            assertFalse("展示别名不得进入条件或排序", code.contains(".eq(\"psn\"") || code.contains("orderByAsc(\"psn\")"))
        }
        val mpj = JavaCodeGenerator.generate(result, personnel, GeneratedWrapper.MPJ_LAMBDA)
        assertTrue(mpj, mpj.contains(".selectAs(Personnel::getPsnNo, \"psn\")"))
        assertTrue(mpj, mpj.contains(".eq(Personnel::getPsnNo, \"1\")"))
        assertTrue(mpj, mpj.contains("orderByAsc(Personnel::getPsnNo)"))
    }

    @Test
    fun `路径4b_条件里误写SELECT别名_不反向纠偏成实名_按未映射回落`() {
        // 双轨不交叉的反向证明：条件轨只认 WHERE 原文列名，即便写了 SELECT 别名（非法 SQL 语义）
        // 也不会被改写成 psn_no——按未映射列诚实回落字符串，展示轨与条件轨互不读写
        val result = SqlReverseParser.parse("SELECT psn_no AS psn FROM personnel WHERE psn = '1'")
        val code = JavaCodeGenerator.generate(result, personnel, GeneratedWrapper.LAMBDA)
        assertTrue(code, code.contains(".eq(\"psn\", \"1\")"))
        assertFalse("别名条件不得被反向改写为实名 lambda", code.contains("Personnel::getPsnNo, \"1\""))
        GeneratedProjectionCompiler.assertProjection(code, "psn_no AS psn", "psn =")
    }
}
