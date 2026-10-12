package cn.song.mybatisplus.visualizer.ui.highlight

import cn.song.mybatisplus.visualizer.xml.ParamKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T-XmlGap W4/W5 硬编码表纯逻辑测试（无平台依赖）。
 *
 * 判据取自本地 MyBatis 3.5.19 jar 的**实际读取结果**（javap / mapper DTD / sources jar），
 * 与 [MyBatisXmlCompletionData] KDoc 里登记的核对命令一一对应：
 *  - jdbcType = `org.apache.ibatis.type.JdbcType` 枚举（41 项）；
 *  - mode = `ParameterMode`（IN/OUT/INOUT）；
 *  - statementType = `StatementType`（PREPARED/STATEMENT/CALLABLE）；
 *  - 标签属性表 = `mybatis-3-mapper.dtd` 的逐条 ATTLIST。
 */
class MyBatisXmlCompletionDataTest {

    @Test
    fun `param属性键_7项且与SqlSourceBuilder消费键一致`() {
        assertEquals(
            listOf("javaType", "jdbcType", "typeHandler", "resultMap", "mode", "numericScale", "jdbcTypeName"),
            MyBatisXmlCompletionData.PARAM_ATTRIBUTE_KEYS,
        )
        assertEquals(
            listOf("javaType=", "jdbcType=", "typeHandler=", "resultMap=", "mode=", "numericScale=", "jdbcTypeName="),
            MyBatisXmlCompletionData.paramAttributeKeyHints(),
        )
    }

    @Test
    fun `jdbcType值_来自MyBatis枚举而非JDK的JDBCType`() {
        val values = MyBatisXmlCompletionData.JDBC_TYPE_VALUES
        assertTrue("VARCHAR 在场", "VARCHAR" in values)
        assertTrue("TIMESTAMP_WITH_TIMEZONE 在场（MyBatis 枚举尾部）", "TIMESTAMP_WITH_TIMEZONE" in values)
        assertTrue("UNDEFINED 在场（MyBatis 独有）", "UNDEFINED" in values)
        // MyBatis JdbcType 枚举总数 41（javap 实测）；JDK java.sql.JDBCType 形如 CLOB/BLOB 之外还有 STRUCT 等，
        // 关键判别位：MyBatis 有 UNDEFINED、JDK 无（JDK 是 NULL 之外的 OTHER/REF_CURSOR 等）
        assertEquals("MyBatis JdbcType 枚举总数", 41, values.size)
        assertEquals("字符串枚举名形态（大写）", values, values.map { it.uppercase() })
    }

    @Test
    fun `paramAttributeValues_仅jdbcType与mode有界_其余空`() {
        assertEquals(MyBatisXmlCompletionData.JDBC_TYPE_VALUES, MyBatisXmlCompletionData.paramAttributeValues("jdbcType"))
        assertEquals(listOf("IN", "OUT", "INOUT"), MyBatisXmlCompletionData.paramAttributeValues("mode"))
        assertTrue("javaType 无有界候选", MyBatisXmlCompletionData.paramAttributeValues("javaType").isEmpty())
        assertTrue("numericScale 无有界候选", MyBatisXmlCompletionData.paramAttributeValues("numericScale").isEmpty())
        assertTrue("jdbcTypeName 无有界候选", MyBatisXmlCompletionData.paramAttributeValues("jdbcTypeName").isEmpty())
    }

    @Test
    fun `statementType与resultSetType_枚举名逐项`() {
        assertEquals(listOf("PREPARED", "STATEMENT", "CALLABLE"), MyBatisXmlCompletionData.STATEMENT_TYPE_VALUES)
        assertEquals(
            listOf("FORWARD_ONLY", "SCROLL_INSENSITIVE", "SCROLL_SENSITIVE", "DEFAULT"),
            MyBatisXmlCompletionData.RESULT_SET_TYPE_VALUES,
        )
        assertEquals(listOf("lazy", "eager"), MyBatisXmlCompletionData.FETCH_TYPE_VALUES)
        assertEquals(listOf("BEFORE", "AFTER"), MyBatisXmlCompletionData.ORDER_VALUES)
    }

    @Test
    fun `标签属性表_语句标签按DTD补齐`() {
        val select = MyBatisXmlCompletionData.tagAttributes("select")
        listOf("id", "resultType", "resultMap", "parameterType", "statementType", "flushCache", "useCache").forEach {
            assertTrue("select 应含属性 $it：$select", it in select)
        }
        val insert = MyBatisXmlCompletionData.tagAttributes("insert")
        listOf("id", "keyProperty", "useGeneratedKeys", "keyColumn").forEach {
            assertTrue("insert 应含属性 $it：$insert", it in insert)
        }
        // delete 无 keyProperty/useGeneratedKeys（DTD 实证）
        assertFalse("delete 无 keyProperty", "keyProperty" in MyBatisXmlCompletionData.tagAttributes("delete"))
        val resultMap = MyBatisXmlCompletionData.tagAttributes("resultMap")
        assertEquals(listOf("id", "type", "extends", "autoMapping"), resultMap)
        val result = MyBatisXmlCompletionData.tagAttributes("result")
        assertEquals(listOf("property", "javaType", "column", "jdbcType", "typeHandler"), result)
    }

    @Test
    fun `foreach属性表_补index与nullable`() {
        val foreach = MyBatisXmlCompletionData.tagAttributes("foreach")
        listOf("collection", "item", "index", "separator", "nullable", "open", "close").forEach {
            assertTrue("foreach 应含属性 $it：$foreach", it in foreach)
        }
    }

    @Test
    fun `布尔属性集合_命中给truefalse且不含无候选属性`() {
        assertTrue("useGeneratedKeys" in MyBatisXmlCompletionData.BOOLEAN_ATTRIBUTES)
        assertTrue("flushCache" in MyBatisXmlCompletionData.BOOLEAN_ATTRIBUTES)
        assertTrue("nullable" in MyBatisXmlCompletionData.BOOLEAN_ATTRIBUTES)
        assertFalse("timeout 不是布尔属性（无合理候选）", "timeout" in MyBatisXmlCompletionData.BOOLEAN_ATTRIBUTES)
        assertFalse("fetchSize 不是布尔属性", "fetchSize" in MyBatisXmlCompletionData.BOOLEAN_ATTRIBUTES)
        assertEquals(listOf("true", "false"), MyBatisXmlCompletionData.BOOLEAN_VALUES)
    }

    @Test
    fun `类名候选属性集合_六项`() {
        assertEquals(
            setOf("resultType", "type", "ofType", "parameterType", "typeHandler", "javaType"),
            MyBatisXmlCompletionData.CLASS_NAME_ATTRIBUTES,
        )
    }

    @Test
    fun `SQL函数表_19项且含独立于关键字的函数`() {
        assertEquals(19, MyBatisXmlCompletionData.SQL_FUNCTIONS.size)
        listOf("COUNT", "SUM", "UPPER", "LOWER", "ROUND", "SUBSTR").forEach {
            assertTrue("函数表应含 $it", it in MyBatisXmlCompletionData.SQL_FUNCTIONS)
        }
    }

    @Test
    fun `单字母捷径_一键多值_仅单字符触发`() {
        assertTrue("s → SELECT", "SELECT" in MyBatisXmlCompletionData.singleLetterShortcuts("s"))
        assertTrue("s → SET", "SET" in MyBatisXmlCompletionData.singleLetterShortcuts("S"))
        assertTrue("c → COUNT", "COUNT" in MyBatisXmlCompletionData.singleLetterShortcuts("c"))
        assertTrue("两个字符不触发", MyBatisXmlCompletionData.singleLetterShortcuts("se").isEmpty())
        assertTrue("空串不触发", MyBatisXmlCompletionData.singleLetterShortcuts("").isEmpty())
    }

    @Test
    fun `OGNL运算符_含in与not_in_不含nin`() {
        assertTrue("in 在场", "in" in MyBatisXmlCompletionData.OGNL_OPERATORS)
        assertTrue("not in 在场", "not in" in MyBatisXmlCompletionData.OGNL_OPERATORS)
        assertFalse("nin 不是 OGNL 运算符（竞品是 live template）", "nin" in MyBatisXmlCompletionData.OGNL_OPERATORS)
    }

    @Test
    fun `OGNL智能片段_按类型择需_推不出给通用五条`() {
        assertEquals(
            listOf("x != null", "x != ''", "x.length != 0", "'%' + x + '%'"),
            MyBatisXmlCompletionData.ognlSnippets("x", ParamKind.TEXT),
        )
        assertEquals(listOf("x != null", "x.size != 0"), MyBatisXmlCompletionData.ognlSnippets("x", ParamKind.COLLECTION))
        assertEquals(listOf("x != null"), MyBatisXmlCompletionData.ognlSnippets("x", ParamKind.NUMBER))
        assertEquals(
            listOf("x != null", "x != ''", "x.length != 0", "x.size != 0", "'%' + x + '%'"),
            MyBatisXmlCompletionData.ognlSnippets("x", null),
        )
        assertTrue("空变量名不产片段", MyBatisXmlCompletionData.ognlSnippets("", ParamKind.TEXT).isEmpty())
    }
}
