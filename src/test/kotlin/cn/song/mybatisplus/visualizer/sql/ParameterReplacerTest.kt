package cn.song.mybatisplus.visualizer.sql

import cn.song.mybatisplus.visualizer.model.ColumnInfo
import cn.song.mybatisplus.visualizer.model.ColumnRef
import cn.song.mybatisplus.visualizer.model.Condition
import cn.song.mybatisplus.visualizer.model.SelectStatement
import cn.song.mybatisplus.visualizer.model.SqlValue
import cn.song.mybatisplus.visualizer.model.TableInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 参数收集 / 替换器 / 单行模式单元测试（M2）。
 */
class ParameterReplacerTest {

    private val table = TableInfo(
        entityClassName = "CarDeviceInfoEntity",
        tableName = "car_device_info",
        columns = listOf(
            ColumnInfo("id", "id", isId = true),
            ColumnInfo("stationId", "station_id"),
            ColumnInfo("shareSchemeId", "share_scheme_id"),
        ),
    )

    @Test
    fun `生成SQL收集未解析参数`() {
        val statement = SelectStatement(
            table = table,
            conditions = listOf(
                Condition.Simple(
                    ColumnRef(columnName = "station_id"),
                    "=",
                    SqlValue.Placeholder("stationId"),
                ),
                Condition.Simple(
                    ColumnRef(columnName = "id"),
                    "=",
                    SqlValue.Literal("1001"),
                ),
            ),
        )
        val generated = SqlGenerator.generateWithParams(statement)
        // 字面量内联，占位符收集
        assertEquals(listOf("stationId"), generated.unresolvedParams)
        assertEquals("?", generated.sql.substringAfter("= ").substringBefore("\n"))
    }

    @Test
    fun `参数对照表_有参数`() {
        val tableText = ParameterReplacer.buildParamTable(listOf("stationId", "deviceId"))
        assertEquals("-- 参数对照: [stationId=?, deviceId=?]", tableText)
    }

    @Test
    fun `参数对照表_无参数返回null`() {
        assertNull(ParameterReplacer.buildParamTable(emptyList()))
    }

    @Test
    fun `来源注释_按序标注`() {
        val sql = "WHERE station_id = ? AND id = ?"
        val annotated = ParameterReplacer.annotate(sql, listOf("stationId", "deviceId"))
        assertEquals("WHERE station_id = ? /*stationId*/ AND id = ? /*deviceId*/", annotated)
    }

    @Test
    fun `来源注释_参数不足时保留问号`() {
        val sql = "WHERE a = ? AND b = ? AND c = ?"
        val annotated = ParameterReplacer.annotate(sql, listOf("a"))
        assertEquals("WHERE a = ? /*a*/ AND b = ? AND c = ?", annotated)
    }

    @Test
    fun `单行模式_子句空格连接`() {
        val statement = SelectStatement(
            table = table,
            conditions = listOf(
                Condition.Simple(ColumnRef(columnName = "id"), "=", SqlValue.Literal("1")),
                Condition.Simple(ColumnRef(columnName = "station_id"), "=", SqlValue.Literal("101")),
            ),
            orderBy = listOf(cn.song.mybatisplus.visualizer.model.OrderByItem(ColumnRef(columnName = "sort"), true)),
        )
        val sql = SqlGenerator.generate(statement, compact = true)
        assertEquals(
            "SELECT id, station_id, share_scheme_id FROM car_device_info WHERE id = 1 AND station_id = 101 ORDER BY sort ASC",
            sql,
        )
    }

    @Test
    fun `多行模式_与单行模式一致内容`() {
        val statement = SelectStatement(
            table = table,
            conditions = listOf(
                Condition.Simple(ColumnRef(columnName = "id"), "=", SqlValue.Literal("1")),
            ),
        )
        val multi = SqlGenerator.generate(statement, compact = false)
        val single = SqlGenerator.generate(statement, compact = true)
        // 去掉空白后应一致
        assertEquals(multi.replace(Regex("\\s+"), " ").trim(), single)
    }
}
