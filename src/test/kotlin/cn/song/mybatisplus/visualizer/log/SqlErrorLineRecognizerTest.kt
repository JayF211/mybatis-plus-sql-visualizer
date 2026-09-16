package cn.song.mybatisplus.visualizer.log

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [SqlErrorLineRecognizer] 多形态 fixture 测试（T-M55-02）：
 * MyBatis `###` 形态 / Spring DAO / JDBC / 参数绑定 / 非 SQL 行不误报；
 * 词表常量守护（逐行热路径——词表须为 object 常量，不得逐行构造）。
 */
class SqlErrorLineRecognizerTest {

    // ---------- 命中：错误块首行（多形态） ----------

    @Test
    fun `MyBatis裸异常首行_命中`() {
        assertTrue(
            SqlErrorLineRecognizer.isErrorLine(
                "org.apache.ibatis.exceptions.PersistenceException: ",
            ),
        )
    }

    @Test
    fun `Spring包装的MyBatis错误首行_命中`() {
        assertTrue(
            SqlErrorLineRecognizer.isErrorLine(
                "org.springframework.dao.BadSqlGrammarException: " +
                    "### Error querying database.  Cause: com.mysql.cj.jdbc.exceptions.MySQLSyntaxErrorException: Unknown column 'name' in 'field list'",
            ),
        )
    }

    @Test
    fun `MyBatis错误块续行_命中`() {
        assertTrue(SqlErrorLineRecognizer.isErrorLine("### The error may exist in file [com/demo/UserMapper.xml]"))
        assertTrue(SqlErrorLineRecognizer.isErrorLine("### The error may involve com.demo.mapper.UserMapper.selectById-Inline"))
        assertTrue(SqlErrorLineRecognizer.isErrorLine("### SQL: SELECT * FROM t_user WHERE id = ?"))
        assertTrue(SqlErrorLineRecognizer.isErrorLine("### Cause: com.mysql.cj.jdbc.exceptions.MySQLSyntaxErrorException: Unknown column 'x'"))
    }

    @Test
    fun `SpringDAO体系_命中`() {
        assertTrue(SqlErrorLineRecognizer.isErrorLine("org.springframework.dao.DuplicateKeyException: could not execute statement; SQL [n/a]"))
        assertTrue(SqlErrorLineRecognizer.isErrorLine("org.springframework.dao.DataIntegrityViolationException: could not execute statement"))
        assertTrue(SqlErrorLineRecognizer.isErrorLine("org.springframework.jdbc.UncategorizedSQLException: ### Error updating database. Cause: java.sql.SQLException"))
        assertTrue(SqlErrorLineRecognizer.isErrorLine("org.springframework.transaction.CannotCreateTransactionException: Could not open JDBC Connection"))
    }

    @Test
    fun `JDBC异常家族_命中`() {
        assertTrue(SqlErrorLineRecognizer.isErrorLine("java.sql.SQLException: Unknown column 'x' in 'where clause'"))
        assertTrue(SqlErrorLineRecognizer.isErrorLine("java.sql.SQLSyntaxErrorException: Table 'demo.t_user' doesn't exist"))
        assertTrue(SqlErrorLineRecognizer.isErrorLine("java.sql.SQLIntegrityConstraintViolationException: Duplicate entry '1' for key 'PRIMARY'"))
        assertTrue(SqlErrorLineRecognizer.isErrorLine("com.mysql.cj.jdbc.exceptions.CommunicationsException: Communications link failure"))
    }

    @Test
    fun `Hibernate异常_命中`() {
        assertTrue(SqlErrorLineRecognizer.isErrorLine("org.hibernate.exception.SQLGrammarException: could not execute query"))
        assertTrue(SqlErrorLineRecognizer.isErrorLine("org.hibernate.exception.ConstraintViolationException: could not execute statement"))
        assertTrue(SqlErrorLineRecognizer.isErrorLine("javax.persistence.PersistenceException: org.hibernate.exception.SQLGrammarException: could not execute query"))
    }

    @Test
    fun `参数绑定异常_命中`() {
        // JDBC setter 报错（Ultra 4.0.14 自述不支持，本识别器一并覆盖）
        assertTrue(
            SqlErrorLineRecognizer.isErrorLine(
                "java.sql.SQLException: Parameter index out of range (2 > number of parameters, which is 1).",
            ),
        )
        // MyBatis 参数映射 setter 报错
        assertTrue(
            SqlErrorLineRecognizer.isErrorLine(
                "org.apache.ibatis.type.TypeException: Could not set parameters for mapping: ParameterMapping{property='name'}",
            ),
        )
        // MyBatis 参数名缺失
        assertTrue(
            SqlErrorLineRecognizer.isErrorLine(
                "org.apache.ibatis.binding.BindingException: Parameter 'name' not found. Available parameters are [arg0, param1]",
            ),
        )
        // 数值转换失败（MySQL 形态）
        assertTrue(
            SqlErrorLineRecognizer.isErrorLine(
                "java.sql.SQLException: Invalid argument value: java.lang.NumberFormatException: For input string: \"abc\"",
            ),
        )
    }

    @Test
    fun `带日志前缀的异常行_命中`() {
        // Spring Boot logger 打印形态：前缀 + 异常（常见于 @ExceptionHandler 兜底打印）
        assertTrue(
            SqlErrorLineRecognizer.isErrorLine(
                "2026-09-16 10:00:00.123 ERROR 12345 --- [nio-8080-exec-1] c.d.s.UserService : org.springframework.dao.BadSqlGrammarException: ### Error querying database",
            ),
        )
    }

    // ---------- 非误报：普通控制台行 ----------

    @Test
    fun `非SQL行不误报`() {
        assertFalse(SqlErrorLineRecognizer.isErrorLine("==>  Preparing: SELECT * FROM user WHERE id = 1"))
        assertFalse(SqlErrorLineRecognizer.isErrorLine("==> Parameters: 1(Long)"))
        assertFalse(SqlErrorLineRecognizer.isErrorLine("<==      Total: 3"))
        assertFalse(SqlErrorLineRecognizer.isErrorLine("Hibernate: select * from t_user"))
        assertFalse(SqlErrorLineRecognizer.isErrorLine("binding parameter [1] as [INTEGER] - [1]"))
        assertFalse(
            SqlErrorLineRecognizer.isErrorLine(
                "2026-09-16 10:00:00.123 DEBUG org.springframework.jdbc.core.JdbcTemplate - Executing prepared SQL statement [SELECT 1]",
            ),
        )
        assertFalse(
            SqlErrorLineRecognizer.isErrorLine(
                "2026-09-08 09:38:03.587 DEBUG org.mybatis.spring.SqlSessionUtils - Creating a new SqlSession",
            ),
        )
        assertFalse(SqlErrorLineRecognizer.isErrorLine("SQLVIS-SOURCE|src/main/java/A.java|87"))
        assertFalse(SqlErrorLineRecognizer.isErrorLine("Tomcat started on port(s): 8080 (http)"))
        assertFalse(SqlErrorLineRecognizer.isErrorLine("2026-09-16 10:00:00.123  INFO Started Application in 5.2 seconds"))
        assertFalse(SqlErrorLineRecognizer.isErrorLine("select 'sqlsyntaxerror' as tag from dual"))
    }

    // ---------- 续行判定 ----------

    @Test
    fun `堆栈续行判定`() {
        assertTrue(SqlErrorLineRecognizer.isContinuationLine("\tat org.springframework.jdbc.core.JdbcTemplate.execute(JdbcTemplate.java:650)"))
        assertTrue(SqlErrorLineRecognizer.isContinuationLine("Caused by: java.sql.SQLException: Unknown column 'x' in 'where clause'"))
        assertTrue(SqlErrorLineRecognizer.isContinuationLine("\t... 83 more"))
        assertTrue(SqlErrorLineRecognizer.isContinuationLine("Suppressed: java.sql.SQLException"))
        assertTrue(SqlErrorLineRecognizer.isContinuationLine("### The error may exist in file [UserMapper.xml]"))
        assertTrue(SqlErrorLineRecognizer.isContinuationLine("### Cause: java.sql.SQLException: Lock wait timeout"))
        assertFalse(SqlErrorLineRecognizer.isContinuationLine("==>  Preparing: SELECT 1"))
        assertFalse(SqlErrorLineRecognizer.isContinuationLine("org.springframework.dao.QueryTimeoutException: ### Error querying database"))
    }

    // ---------- 热路径守护：词表为 object 常量（防逐行构造回潮，LogRouterHotPathRegexGuardTest 同款口径） ----------

    @Test
    fun `错误词表走object常量_防回潮`() {
        val field = SqlErrorLineRecognizer::class.java.getDeclaredField("ERROR_SIGNATURES")
        field.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val signatures = field.get(null) as? List<String>
        assertNotNull("ERROR_SIGNATURES 须以 object 常量在位（逐行热路径复用，不得逐行构造）", signatures)
        assertTrue("词表不可为空", signatures!!.isNotEmpty())
        signatures.forEach { assertEquals("词表项须全小写（isErrorLine 整行 lowercase 后 contains）", it, it.lowercase()) }
        // 行为抽检：常量词表对真实错误形态正常命中
        assertTrue(SqlErrorLineRecognizer.isErrorLine("java.sql.SQLException: Parameter index out of range (2 > 1)."))
    }
}
