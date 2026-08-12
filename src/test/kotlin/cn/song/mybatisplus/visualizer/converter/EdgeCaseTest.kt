package cn.song.mybatisplus.visualizer.converter

import cn.song.mybatisplus.visualizer.model.ColumnInfo
import cn.song.mybatisplus.visualizer.model.ColumnRef
import cn.song.mybatisplus.visualizer.model.SqlValue
import cn.song.mybatisplus.visualizer.model.TableInfo
import cn.song.mybatisplus.visualizer.model.WrapperArg
import cn.song.mybatisplus.visualizer.model.WrapperCall
import cn.song.mybatisplus.visualizer.model.WrapperChain
import cn.song.mybatisplus.visualizer.model.WrapperType
import cn.song.mybatisplus.visualizer.sql.SqlGenerator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 边界用例与性能基准测试（M6-02，T-M6-02）。
 *
 * 覆盖健壮性要求（F-20）：
 *  - 非法 SQL 片段（last 无法解析）安全降级
 *  - 超长调用链不崩溃、不丢条件
 *  - 大量生成操作的性能基准（悬停 < 200ms 目标的一个数量级余量）
 */
class EdgeCaseTest {

    private val table = TableInfo(
        entityClassName = "CarDeviceInfoEntity",
        tableName = "car_device_info",
        columns = listOf(ColumnInfo("id", "id", isId = true), ColumnInfo("name", "name")),
    )

    /** 便捷构造：eq(column, value)。 */
    private fun eqCall(column: String, value: String) = WrapperCall(
        "eq",
        listOf(WrapperArg.ColumnArg(ColumnRef(columnName = column)), WrapperArg.ValueArg(SqlValue.Literal(value))),
    )

    @Test
    fun `last非法片段_安全忽略`() {
        val chain = WrapperChain(
            WrapperType.LAMBDA_QUERY, "E",
            listOf(
                eqCall("id", "1"),
                // 非 limit 文本 / 非法格式：parseLimit 无法解析 → 忽略，不影响其他条件
                WrapperCall("last", listOf(WrapperArg.ValueArg(SqlValue.Literal("order by name")))),
                WrapperCall("last", listOf(WrapperArg.ValueArg(SqlValue.Literal("limit abc")))),
            ),
        )
        val sql = SqlGenerator.generate(QueryWrapperParser(table).parse(chain))
        assertTrue("条件应保留", sql.contains("WHERE id = 1"))
        assertTrue("非法 limit 不输出", !sql.contains("LIMIT"))
    }

    @Test
    fun `超长链_100个条件不丢失`() {
        val calls = (1..100).map { eqCall("id", it.toString()) }
        val chain = WrapperChain(WrapperType.LAMBDA_QUERY, "E", calls)
        val statement = QueryWrapperParser(table).parse(chain)
        assertEquals(100, statement.conditions.size)
        val sql = SqlGenerator.generate(statement)
        // 每个条件都出现
        (1..100).forEach { assertTrue("缺条件 $it", sql.contains("id = $it")) }
    }

    @Test
    fun `未知方法_不影响后续`() {
        val chain = WrapperChain(
            WrapperType.LAMBDA_QUERY, "E",
            listOf(
                WrapperCall("apply", listOf(WrapperArg.ValueArg(SqlValue.Literal("custom")))),
                WrapperCall("not", listOf(WrapperArg.NestedArg(WrapperChain(WrapperType.NESTED, "E", listOf(eqCall("id", "1")))))),
                eqCall("name", "张"),
            ),
        )
        val statement = QueryWrapperParser(table).parse(chain)
        // 未知方法（apply/not）M1 简化忽略；后续条件正常解析
        assertEquals(1, statement.conditions.size)
    }

    @Test
    fun `性能基准_千次生成`() {
        val chain = WrapperChain(
            WrapperType.LAMBDA_QUERY, "E",
            (1..10).map { eqCall("id", it.toString()) },
        )
        val statement = QueryWrapperParser(table).parse(chain)

        // 预热
        repeat(100) { SqlGenerator.generate(statement) }

        val start = System.nanoTime()
        repeat(1000) { SqlGenerator.generate(statement) }
        val elapsedMs = (System.nanoTime() - start) / 1_000_000

        // 1000 次生成 < 500ms（单次 < 0.5ms，远低于悬停 200ms 目标）
        assertTrue("1000 次生成耗时 $elapsedMs ms，应 < 500ms", elapsedMs < 500)
    }

    @Test
    fun `空条件语句_正常渲染`() {
        val statement = QueryWrapperParser(table).parse(WrapperChain(WrapperType.LAMBDA_QUERY, "E", emptyList()))
        val sql = SqlGenerator.generate(statement)
        assertEquals(
            """
            SELECT id, name
            FROM car_device_info
            """.trimIndent(),
            sql,
        )
        // limit 为 null
        assertNull(statement.limit)
    }
}
