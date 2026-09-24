package cn.song.mybatisplus.visualizer.xml

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 语句原文 rawText 测试（O-77 T-EwStatementScope）：parse 在 include 展开后取 element.textContent——
 * MP 占位扫描域对齐解析域：本语句占位在 rawText 可见、他语句占位不串、include 带入的占位计入、
 * 语句外注释里的占位不记账（demo OrderMapper.xml 语句外注释写有 ew 占位的真实形态）。
 */
class XmlMapperParserRawTextTest {

    @Test
    fun `rawText只含本语句文本_他语句占位不串`() {
        val model = XmlMapperParser().parse("""
            <mapper namespace="n">
              <select id="withEw">SELECT * FROM t ${'$'}{ew.customSqlSegment}</select>
              <select id="plain">SELECT * FROM t WHERE a = #{a}</select>
            </mapper>
        """.trimIndent())
        val withEw = model.statements.first { it.id == "withEw" }
        val plain = model.statements.first { it.id == "plain" }
        assertEquals(listOf(EwPlaceholder("ew", "customSqlSegment")), XmlEwExpander.placeholders(withEw.rawText.orEmpty()))
        assertTrue("plain 语句原文不应含 ew 占位：${plain.rawText}", XmlEwExpander.placeholders(plain.rawText.orEmpty()).isEmpty())
    }

    @Test
    fun `include展开后的片段占位计入语句原文`() {
        val model = XmlMapperParser().parse("""
            <mapper namespace="n">
              <sql id="ewFrag">${'$'}{ew.customSqlSegment}</sql>
              <select id="byInclude">SELECT * FROM t <include refid="ewFrag"/></select>
            </mapper>
        """.trimIndent())
        val byInclude = model.statements.first { it.id == "byInclude" }
        assertEquals(listOf(EwPlaceholder("ew", "customSqlSegment")), XmlEwExpander.placeholders(byInclude.rawText.orEmpty()))
    }

    @Test
    fun `语句外注释里的占位不记账`() {
        val model = XmlMapperParser().parse("""
            <mapper namespace="n">
              <!-- ${'$'}{ew.customSqlSegment}：Wrapper 条件段展开（demo OrderMapper.xml 同款注释形态） -->
              <select id="plain">SELECT * FROM t WHERE a = #{a}</select>
            </mapper>
        """.trimIndent())
        val plain = model.statements.first { it.id == "plain" }
        assertTrue("注释占位不应进入语句原文：${plain.rawText}", XmlEwExpander.placeholders(plain.rawText.orEmpty()).isEmpty())
    }
}
