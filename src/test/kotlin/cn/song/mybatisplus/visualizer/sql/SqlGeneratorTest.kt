package cn.song.mybatisplus.visualizer.sql

import cn.song.mybatisplus.visualizer.model.ColumnInfo
import cn.song.mybatisplus.visualizer.model.ColumnRef
import cn.song.mybatisplus.visualizer.model.Condition
import cn.song.mybatisplus.visualizer.model.ConditionToggle
import cn.song.mybatisplus.visualizer.model.Connector
import cn.song.mybatisplus.visualizer.model.FillClause
import cn.song.mybatisplus.visualizer.model.LikePosition
import cn.song.mybatisplus.visualizer.model.LimitClause
import cn.song.mybatisplus.visualizer.model.OrderByItem
import cn.song.mybatisplus.visualizer.model.SelectStatement
import cn.song.mybatisplus.visualizer.model.SqlValue
import cn.song.mybatisplus.visualizer.model.TableInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * SQL 生成器单元测试（M1-04）：验证模型 → SQL 文本渲染。
 */
class SqlGeneratorTest {

    /** 测试用实体表信息（对齐 docs/项目介绍.md 的 car_device_info）。 */
    private val table = TableInfo("DeviceInfoEntity", "car_device_info", listOf(
            ColumnInfo("id", "id", true),
            ColumnInfo("stationId", "station_id"),
            ColumnInfo("shareSchemeId", "share_scheme_id"),
            ColumnInfo("sort", "sort"),
        ))

    @Test
    fun `基本查询_无条件无排序`() {
        val statement = SelectStatement(table, emptyList(), null, emptyList(), false, emptyList(), emptyList(), null, emptyList(), null, null, null, null, emptyList(), null, null)
        val sql = SqlGenerator.generate(statement)
        assertEquals(
            """
            SELECT id, station_id, share_scheme_id, sort
            FROM car_device_info
            """.trimIndent(),
            sql,
        )
    }

    @Test
    fun `带条件_多条件AND缩进`() {
        val statement = SelectStatement(table, emptyList(), null, emptyList(), false, listOf(
                Condition.Simple(ColumnRef("id"), "=", SqlValue.Literal("1001"), null),
                Condition.Unary(ColumnRef("share_scheme_id"), "IS NOT NULL", null),
            ), emptyList(), null, emptyList(), null, null, null, null, emptyList(), null, null)
        val sql = SqlGenerator.generate(statement)
        assertEquals(
            """
            SELECT id, station_id, share_scheme_id, sort
            FROM car_device_info
            WHERE id = 1001
                AND share_scheme_id IS NOT NULL
            """.trimIndent(),
            sql,
        )
    }

    @Test
    fun `占位符参数_变量渲染为问号`() {
        val statement = SelectStatement(table, emptyList(), null, emptyList(), false, listOf(
                Condition.Simple(ColumnRef("station_id"), "=", SqlValue.Placeholder("stationId"), null),
            ), emptyList(), null, emptyList(), null, null, null, null, emptyList(), null, null)
        val sql = SqlGenerator.generate(statement)
        assertEquals(
            """
            SELECT id, station_id, share_scheme_id, sort
            FROM car_device_info
            WHERE station_id = ?
            """.trimIndent(),
            sql,
        )
    }

    @Test
    fun `in与between条件`() {
        val statement = SelectStatement(table, emptyList(), null, emptyList(), false, listOf(
                Condition.In(ColumnRef("id"), false, listOf(SqlValue.Literal("1"), SqlValue.Literal("2"), SqlValue.Literal("3")), null),
                Condition.Between(ColumnRef("sort"), false, SqlValue.Literal("1"), SqlValue.Literal("10"), null),
            ), emptyList(), null, emptyList(), null, null, null, null, emptyList(), null, null)
        val sql = SqlGenerator.generate(statement)
        assertEquals(
            """
            SELECT id, station_id, share_scheme_id, sort
            FROM car_device_info
            WHERE id IN (1, 2, 3)
                AND sort BETWEEN 1 AND 10
            """.trimIndent(),
            sql,
        )
    }

    @Test
    fun `like条件_不同位置百分号`() {
        val tableWithName = TableInfo("UserEntity", "user", listOf(ColumnInfo("name", "name")))
        fun likeSql(position: LikePosition, negated: Boolean = false): String {
            val statement = SelectStatement(tableWithName, emptyList(), null, emptyList(), false, listOf(
                    Condition.Like(ColumnRef("name"), negated, SqlValue.Literal("张"), position, null),
                ), emptyList(), null, emptyList(), null, null, null, null, emptyList(), null, null)
            return SqlGenerator.generate(statement).substringAfter("WHERE ")
        }
        assertEquals("name LIKE '%张%'", likeSql(LikePosition.ANYWHERE))
        assertEquals("name LIKE '%张'", likeSql(LikePosition.LEFT))
        assertEquals("name LIKE '张%'", likeSql(LikePosition.RIGHT))
        assertEquals("name NOT LIKE '%张%'", likeSql(LikePosition.ANYWHERE, negated = true))
    }

    @Test
    fun `逻辑分组_嵌套括号`() {
        val statement = SelectStatement(table, emptyList(), null, emptyList(), false, listOf(
                Condition.Simple(ColumnRef("id"), "=", SqlValue.Literal("1"), null),
                Condition.Group(cn.song.mybatisplus.visualizer.model.Connector.OR, listOf(
                        Condition.Simple(ColumnRef("station_id"), "=", SqlValue.Literal("101"), null),
                        Condition.Simple(ColumnRef("share_scheme_id"), "=", SqlValue.Literal("5"), null),
                    ), null, false),
            ), emptyList(), null, emptyList(), null, null, null, null, emptyList(), null, null)
        val sql = SqlGenerator.generate(statement)
        assertEquals(
            """
            SELECT id, station_id, share_scheme_id, sort
            FROM car_device_info
            WHERE id = 1
                AND (station_id = 101 OR share_scheme_id = 5)
            """.trimIndent(),
            sql,
        )
    }

    @Test
    fun `完整查询_分组排序分页`() {
        val statement = SelectStatement(table, listOf(
                ColumnRef("station_id"),
                ColumnRef("share_scheme_id"),
            ), null, emptyList(), false, listOf(
                Condition.Simple(ColumnRef("id"), "=", SqlValue.Placeholder("deviceId"), null),
            ), listOf(ColumnRef("station_id")), null, listOf(
                OrderByItem(ColumnRef("sort"), true),
                OrderByItem(ColumnRef("id"), false),
            ), LimitClause(null, 10), null, null, null, emptyList(), null, null)
        val sql = SqlGenerator.generate(statement)
        assertEquals(
            """
            SELECT station_id, share_scheme_id
            FROM car_device_info
            WHERE id = ?
            GROUP BY station_id
            ORDER BY sort ASC, id DESC
            LIMIT 10
            """.trimIndent(),
            sql,
        )
    }

    @Test
    fun `带偏移量分页`() {
        val statement = SelectStatement(table, emptyList(), null, emptyList(), false, emptyList(), emptyList(), null, emptyList(), LimitClause(20, 10), null, null, null, emptyList(), null, null)
        val sql = SqlGenerator.generate(statement)
        assertEquals(
            """
            SELECT id, station_id, share_scheme_id, sort
            FROM car_device_info
            LIMIT 20, 10
            """.trimIndent(),
            sql,
        )
    }

    @Test
    fun `分页占位符_参数收集与回填提示`() {
        // T-M29-03：service.page 的运行时 pageNum/pageSize → `?` + 参数来源（面板参数区回填）
        val statement = SelectStatement(table, emptyList(), null, emptyList(), false, emptyList(), emptyList(), null, emptyList(), LimitClause(SqlValue.Placeholder("(pageNum - 1) * pageSize"), SqlValue.Placeholder("pageSize")), null, null, null, emptyList(), null, null)
        val generated = SqlGenerator.generateWithParams(statement, compact = false)
        assertEquals(
            """
            SELECT id, station_id, share_scheme_id, sort
            FROM car_device_info
            LIMIT ?, ?
            """.trimIndent(),
            generated.sql,
        )
        assertEquals(listOf("(pageNum - 1) * pageSize", "pageSize"), generated.unresolvedParams)
        assertEquals(listOf(ParamHint(false), ParamHint(false)), generated.paramHints)
    }

    @Test
    fun `组带开关_enabledIds不含子条件时组内容仍渲染`() {
        // T-M29.1：组开关行合并子条件后，子条件 id 不进面板 enabledIds——
        // 组带开关时子条件按各自 defaultEnabled 渲染（enabledIds 仅控制组本身显隐）
        val group = Condition.Group(Connector.OR, listOf(
                Condition.Like(ColumnRef("code"), false, SqlValue.Placeholder("keyword"), LikePosition.RIGHT, null)
                    .withToggle(ConditionToggle("c1", null, true, "code LIKE ?")),
                Condition.Like(ColumnRef("user_phone"), false, SqlValue.Placeholder("keyword"), LikePosition.RIGHT, null)
                    .withToggle(ConditionToggle("c2", null, true, "user_phone LIKE ?")),
            ), ConditionToggle("c0", "isNotBlank(keyword)", true, "(code LIKE ? OR user_phone LIKE ?)"))
        val statement = SelectStatement(table, emptyList(), null, emptyList(), false, listOf(group), emptyList(), null, emptyList(), null, null, null, null, emptyList(), null, null)
        val sql = SqlGenerator.generate(statement, compact = false, enabledConditionIds = setOf("c0"))
        assertEquals(
            """
            SELECT id, station_id, share_scheme_id, sort
            FROM car_device_info
            WHERE (code LIKE ? OR user_phone LIKE ?)
            """.trimIndent(),
            sql,
        )
        // 组未勾选（enabledIds 不含 c0）→ 整组不渲染
        val disabledSql = SqlGenerator.generate(statement, compact = false, enabledConditionIds = emptySet())
        assertTrue("组未勾选应整组消失：$disabledSql", !disabledSql.contains("LIKE"))
    }

    @Test
    fun `having子条件_enabledIds不含时仍渲染`() {
        // T-M29.1：HAVING 子条件不在面板展示（无开关行）——按 defaultEnabled 渲染，
        // 面板 enabledIds 过滤不再丢失 HAVING（M19.5 预存缺陷）
        val having = Condition.Group(Connector.AND, listOf(
                Condition.Simple(ColumnRef("amount"), ">", SqlValue.Literal("100"), null)
                    .withToggle(ConditionToggle("h0", null, true, "amount > 100")),
            ), null, false)
        val statement = SelectStatement(table, emptyList(), null, emptyList(), false, listOf(
                Condition.Simple(ColumnRef("id"), "=", SqlValue.Literal("1"), null)
                    .withToggle(ConditionToggle("w0", null, true, "id = 1")),
            ), listOf(ColumnRef("station_id")), having, emptyList(), null, null, null, null, emptyList(), null, null)
        val sql = SqlGenerator.generate(statement, compact = false, enabledConditionIds = setOf("w0"))
        assertTrue("HAVING 应渲染：$sql", sql.contains("HAVING (amount > 100)"))
    }

    // ------------------------------------------------------------------
    // M13：回填提示（paramHints 与 unresolvedParams 并行；SQL 文本不变）
    // ------------------------------------------------------------------

    @Test
    fun `占位符参数_回填提示按列类型与LIKE位置`() {
        // id 数值列、name 字符串列、code 无类型（未知）
        val typedTable = TableInfo("DeviceInfoEntity", "car_device_info", listOf(
                ColumnInfo("id", "id", true, false, "java.lang.Long"),
                ColumnInfo("name", "name", false, false, "java.lang.String"),
                ColumnInfo("code", "code"),
            ))
        val statement = SelectStatement(typedTable, emptyList(), null, emptyList(), false, listOf(
                Condition.Simple(ColumnRef("name"), "=", SqlValue.Placeholder("nameVar"), null),
                Condition.Simple(ColumnRef("id"), "=", SqlValue.Placeholder("idVar"), null),
                Condition.Simple(ColumnRef("code"), "=", SqlValue.Placeholder("codeVar"), null),
                Condition.Like(ColumnRef("name"), false, SqlValue.Placeholder("likeVar"), LikePosition.ANYWHERE, null),
                Condition.Like(ColumnRef("name"), false, SqlValue.Placeholder("likeLeftVar"), LikePosition.LEFT, null),
                Condition.Like(ColumnRef("name"), false, SqlValue.Placeholder("likeRightVar"), LikePosition.RIGHT, null),
            ), emptyList(), null, emptyList(), null, null, null, null, emptyList(), null, null)
        val generated = SqlGenerator.generateWithParams(statement)

        // SQL 文本与 M12 基线一致（`?` 无引号/百分号，提示为附加信息）
        assertEquals(
            """
            SELECT id, name, code
            FROM car_device_info
            WHERE name = ?
                AND id = ?
                AND code = ?
                AND name LIKE ?
                AND name LIKE ?
                AND name LIKE ?
            """.trimIndent(),
            generated.sql,
        )
        // hints 与 unresolvedParams 一一对应
        assertEquals(
            listOf("nameVar", "idVar", "codeVar", "likeVar", "likeLeftVar", "likeRightVar"),
            generated.unresolvedParams,
        )
        assertEquals(
            listOf(
                ParamHint(true),                          // String 列恒引号
                ParamHint(false),                         // 数值列恒原样
                ParamHint(null),                          // 类型未知
                ParamHint(true, LikePosition.ANYWHERE), // LIKE 恒引号 + %位置
                ParamHint(true, LikePosition.LEFT),
                ParamHint(true, LikePosition.RIGHT),
            ),
            generated.paramHints,
        )
    }

    @Test
    fun `无占位符_无回填提示`() {
        val statement = SelectStatement(table, emptyList(), null, emptyList(), false, listOf(
                Condition.Simple(ColumnRef("id"), "=", SqlValue.Literal("1001"), null),
            ), emptyList(), null, emptyList(), null, null, null, null, emptyList(), null, null)
        val generated = SqlGenerator.generateWithParams(statement)
        assertEquals(emptyList<String>(), generated.unresolvedParams)
        assertEquals(emptyList<ParamHint>(), generated.paramHints)
    }

    // ------------------------------------------------------------------
    // T-M23.4：关键字标识符引用（保留字列名/表名 → 按方言符号包裹）
    // ------------------------------------------------------------------

    /** 含关键字列（table）与关键字表名（order）的实体表信息。 */
    private val keywordTable = TableInfo("BizBannerEntity", "order", listOf(
            ColumnInfo("id", "id", true),
            ColumnInfo("title", "title"),
            ColumnInfo("table", "table"),
            ColumnInfo("imageUrl", "image_url"),
        ))

    @Test
    fun `关键字列_全列展开与条件反引号`() {
        val statement = SelectStatement(keywordTable, emptyList(), null, emptyList(), false, listOf(
                Condition.Simple(ColumnRef("table"), "=", SqlValue.Placeholder("table"), null),
            ), emptyList(), null, emptyList(), null, null, null, null, emptyList(), null, null)
        val sql = SqlGenerator.generate(statement)
        assertEquals(
            """
            SELECT id, title, `table`, image_url
            FROM `order`
            WHERE `table` = ?
            """.trimIndent(),
            sql,
        )
    }

    @Test
    fun `关键字列_表别名形态只包列名`() {
        val statement = SelectStatement(keywordTable, emptyList(), "t", emptyList(), false, listOf(
                Condition.Simple(ColumnRef("t", "table"), "=", SqlValue.Literal("1001"), null),
            ), emptyList(), null, emptyList(), null, null, null, null, emptyList(), null, null)
        val sql = SqlGenerator.generate(statement)
        assertEquals(
            """
            SELECT t.id, t.title, t.`table`, t.image_url
            FROM `order` t
            WHERE t.`table` = 1001
            """.trimIndent(),
            sql,
        )
    }

    @Test
    fun `关键字列_排序与分组`() {
        val statement = SelectStatement(keywordTable, emptyList(), null, emptyList(), false, emptyList(), listOf(ColumnRef("table")), null, listOf(OrderByItem(ColumnRef("table"), true)), null, null, null, null, emptyList(), null, null)
        val sql = SqlGenerator.generate(statement)
        assertEquals(
            """
            SELECT id, title, `table`, image_url
            FROM `order`
            GROUP BY `table`
            ORDER BY `table` ASC
            """.trimIndent(),
            sql,
        )
    }

    @Test
    fun `关键字引用_符号按方言切换`() {
        val statement = SelectStatement(keywordTable, emptyList(), null, emptyList(), false, listOf(
                Condition.Simple(ColumnRef("table"), "=", SqlValue.Literal("1001"), null),
            ), emptyList(), null, emptyList(), null, null, null, null, emptyList(), null, null)
        // PostgreSQL / H2 / SQLite / DM / KingBase 双引号
        assertEquals(
            "SELECT id, title, \"table\", image_url\nFROM \"order\"\nWHERE \"table\" = 1001",
            SqlGenerator.generate(statement, compact = false, dialect = PostgreSQLDialect.INSTANCE).trim(),
        )
        // SQL Server 方括号
        assertEquals(
            "SELECT id, title, [table], image_url\nFROM [order]\nWHERE [table] = 1001",
            SqlGenerator.generate(statement, compact = false, dialect = SqlServerDialect.INSTANCE).trim(),
        )
        // MySQL（默认）反引号
        assertEquals(
            "SELECT id, title, `table`, image_url\nFROM `order`\nWHERE `table` = 1001",
            SqlGenerator.generate(statement, compact = false, dialect = MySqlDialect.INSTANCE).trim(),
        )
    }

    @Test
    fun `关键字列_参数对照不受引用影响`() {
        // 模型层列名不包裹：quoteHint 按原始 columnName 匹配 → 参数来源/提示与未引用时一致
        val statement = SelectStatement(keywordTable, emptyList(), null, emptyList(), false, listOf(
                Condition.Simple(ColumnRef("table"), "=", SqlValue.Placeholder("table"), null),
            ), emptyList(), null, emptyList(), null, null, null, null, emptyList(), null, null)
        val generated = SqlGenerator.generateWithParams(statement)
        assertEquals(listOf("table"), generated.unresolvedParams)
        // keywordTable 列无 fieldType → 引号提示 null（列类型未知，面板走数字启发式）
        assertEquals(listOf(ParamHint(null)), generated.paramHints)
    }

    // ------------------------------------------------------------------
    // T-MpjFill：fill 附加 SELECT 生成
    // ------------------------------------------------------------------

    /** 测试用关联表。 */
    private val stationTable = TableInfo("StationEntity", "station", listOf(
            ColumnInfo("id", "id", true),
            ColumnInfo("stationName", "station_name"),
        ))

    @Test
    fun `generateFill_基础形态`() {
        val fill = FillClause(stationTable, "s", listOf(
                ColumnRef("s", "id"),
                ColumnRef("s", "station_name"),
            ), "id", null, null, null)
        assertEquals(
            "SELECT s.id, s.station_name FROM station s WHERE s.id IN (?)",
            SqlGenerator.generateFill(fill),
        )
    }

    @Test
    fun `generateFill_带limit`() {
        val fill = FillClause(stationTable, "s", listOf(ColumnRef("s", "id")), "id", 10, null, null)
        assertEquals(
            "SELECT s.id FROM station s WHERE s.id IN (?) LIMIT 10",
            SqlGenerator.generateFill(fill),
        )
    }

    @Test
    fun `generateFill_默认方言为MySQL`() {
        // 验证默认参数：不传方言时走 MySQL（标识符引用反引号）
        val fill = FillClause(stationTable, "s", listOf(ColumnRef("s", "id")), "id", null, null, null)
        // 非关键字列名 → 不引用
        val sql = SqlGenerator.generateFill(fill)
        assertEquals("SELECT s.id FROM station s WHERE s.id IN (?)", sql)
    }
}
