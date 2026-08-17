package cn.song.mybatisplus.visualizer.reverse

import cn.song.mybatisplus.visualizer.model.ColumnRef
import cn.song.mybatisplus.visualizer.model.Condition
import cn.song.mybatisplus.visualizer.model.SqlValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * SQL → Java 共享编排单元测试（T-M36.4）：parse/generateCode 组合行为
 * （实体查找为 PSI 索引访问，见 EntityClassFinderHeavyTest）。
 */
class SqlToJavaConverterTest {

    private val result = SqlToJavaConverter.parse(
        "SELECT id, name FROM user WHERE name = '张三' AND age > 18 ORDER BY id ASC LIMIT 10",
    )

    @Test
    fun `解析_结构与生成输入完整`() {
        assertEquals("user", result.tableName)
        assertEquals(listOf(SelectColumn("id"), SelectColumn("name")), result.selectColumns)
        assertEquals(2, result.conditions.size)
        assertEquals(1, result.orderBy.size)
        assertEquals("10", (result.limit?.count as SqlValue.Literal).raw)
    }

    @Test
    fun `无候选_回落字符串QueryWrapper`() {
        val code = SqlToJavaConverter.generateCode(result, null, ReverseWrapperFormat.AUTO)
        assertTrue(code, code.contains("new QueryWrapper<User>()"))
        assertTrue(code, code.contains(".eq(\"name\", \"张三\")"))
    }

    @Test
    fun `有候选_AUTO形态生成Lambda`() {
        val code = SqlToJavaConverter.generateCode(result, candidate("User"), ReverseWrapperFormat.AUTO)
        assertTrue(code, code.contains("new LambdaQueryWrapper<User>()"))
        assertTrue(code, code.contains(".eq(User::getName, \"张三\")"))
        assertTrue(code, code.contains(".gt(User::getAge, 18)"))
        assertTrue(code, code.contains(".orderByAsc(User::getId)"))
        assertTrue(code, code.contains(".last(\"LIMIT 10\")"))
    }

    @Test
    fun `有候选_MPJ形态生成MPJLambdaWrapper`() {
        val code = SqlToJavaConverter.generateCode(result, candidate("User"), ReverseWrapperFormat.MPJ_LAMBDA)
        assertTrue(code, code.contains("new MPJLambdaWrapper<User>()"))
        assertTrue(code, code.contains(".eq(User::getName, \"张三\")"))
    }

    @Test
    fun `有候选_QUERY形态按无实体处理`() {
        // QUERY 形态调用方跳过实体查找（传 null 候选）——generateCode 与无候选一致
        val code = SqlToJavaConverter.generateCode(result, null, ReverseWrapperFormat.QUERY)
        assertTrue(code, code.contains("new QueryWrapper<User>()"))
    }

    @Test
    fun `未映射列_回落字符串并注释`() {
        val result2 = SqlToJavaConverter.parse("SELECT * FROM t WHERE extra_col = 1")
        val code = SqlToJavaConverter.generateCode(
            result2,
            candidate("T"),
            ReverseWrapperFormat.LAMBDA,
        )
        assertTrue(code, code.contains(".eq(\"extra_col\", 1)"))
        assertTrue(code, code.contains("未在实体中找到对应字段，已回落字符串列名：extra_col"))
    }

    @Test
    fun `解析错误_行号计算`() {
        val ex = try {
            SqlToJavaConverter.parse("SELECT * FROM t WHERE a = 'abc")
            throw AssertionError("expected exception")
        } catch (e: SqlReverseException) {
            e
        }
        assertTrue(SqlToJavaConverter.errorLine("SELECT * FROM t WHERE a = 'abc", ex.offset) >= 1)
    }

    private fun candidate(className: String) = SqlToJavaConverter.EntityCandidate(
        psiClass = mockClass(className),
        tableInfo = cn.song.mybatisplus.visualizer.model.TableInfo(
            entityClassName = className,
            tableName = "user",
            columns = listOf(
                cn.song.mybatisplus.visualizer.model.ColumnInfo("id", "id"),
                cn.song.mybatisplus.visualizer.model.ColumnInfo("name", "name"),
                cn.song.mybatisplus.visualizer.model.ColumnInfo("age", "age"),
            ),
        ),
    )

    /** PsiClass 轻量 mock（仅 name 有值；generateCode 只读 className/tableInfo，不触 PSI）。 */
    private fun mockClass(className: String): com.intellij.psi.PsiClass =
        java.lang.reflect.Proxy.newProxyInstance(
            com.intellij.psi.PsiClass::class.java.classLoader,
            arrayOf(com.intellij.psi.PsiClass::class.java),
        ) { _, method, _ ->
            if (method.name == "name") className else null
        } as com.intellij.psi.PsiClass
}
