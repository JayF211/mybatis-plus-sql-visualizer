package cn.song.mybatisplus.visualizer.converter.jpa

import cn.song.mybatisplus.visualizer.model.AssociationInfo
import cn.song.mybatisplus.visualizer.model.ColumnInfo
import cn.song.mybatisplus.visualizer.model.Condition
import cn.song.mybatisplus.visualizer.model.SelectStatement
import cn.song.mybatisplus.visualizer.model.SqlValue
import cn.song.mybatisplus.visualizer.model.TableInfo
import cn.song.mybatisplus.visualizer.model.UpdateStatement
import cn.song.mybatisplus.visualizer.sql.SqlGenerator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * J2 UPDATE 语句模型测试：JPQL/HQL `UPDATE <Entity> [alias] SET ... [WHERE ...]`
 * 生成既有 MP 侧 [UpdateStatement]（此前 UPDATE 走 SELECT 管道，statement 恒为
 * SelectStatement、SET 子句无展开）。
 *
 * 覆盖：
 *  - JpqlConverter / HqlConverter 双链路 statement 类型与 SET/WHERE 解析
 *  - SET 值形态：`?` 占位符 / 字符串字面量 / 数值 / 布尔 / NULL / 简单算术（raw 降级）
 *  - WHERE 形态：简单比较 / IS [NOT] NULL / IN (?) / OR 与复杂形态整体 Raw 透传
 *  - 实体→表名、属性→列名、关联路径→外键列（既有映射在 SET 上同样生效）
 *  - DELETE 路径回归不变（J2 特判不得影响既有 DELETE 行为）
 */
class JpaUpdateConverterTest {

    private val userTable = TableInfo(
        entityClassName = "UserEntity",
        tableName = "user",
        columns = listOf(
            ColumnInfo("id", "id", isId = true),
            ColumnInfo("username", "username"),
            ColumnInfo("status", "status"),
            ColumnInfo("age", "age"),
        ),
    )

    private val orderTable = TableInfo(
        entityClassName = "OrderEntity",
        tableName = "t_order",
        columns = listOf(
            ColumnInfo("id", "id", isId = true),
            ColumnInfo("totalAmount", "total_amount"),
        ),
    )

    /** 对齐 HqlConverterAssociationTest 的 RuleTableItem 结构（关联路径 SET 用）。 */
    private val ruleTableItemTable = TableInfo(
        entityClassName = "RuleTableItem",
        tableName = "THORN_RULE_TABLE_ITEM",
        columns = listOf(
            ColumnInfo("id", "ID", isId = true),
            ColumnInfo("gradeType", "GRADE_TYPE"),
        ),
        associations = listOf(
            AssociationInfo("ruleTable", "RULE_TABLE_ID", "THORN_RULE_TABLE", "thornRuleTable"),
        ),
    )

    // ------------------------------------------------------------------
    // statement 类型与 SQL 文本
    // ------------------------------------------------------------------

    /** T-J2：JPQL UPDATE（demo updateStatusByJpql 形态）→ UpdateStatement，双 ? 占位。 */
    @Test
    fun `jpqlUpdate_returnsUpdateStatement`() {
        val jpql = "UPDATE UserEntity u SET u.status = :newStatus WHERE u.status = :oldStatus"
        val result = JpqlConverter.convert(jpql, userTable)

        val statement = result.statement
        assertTrue(
            "UPDATE JPQL 应返回 UpdateStatement，实际 ${statement::class.simpleName}",
            statement is UpdateStatement,
        )
        assertEquals("user", statement.table.tableName)
        // SQL 文本全等：实体→表名、参数→?、SELECT 展开器不碰 UPDATE
        assertEquals("UPDATE user u SET u.status = ? WHERE u.status = ?", result.sql)
        assertEquals("应有两个 ? 占位符", 2, result.sql.count { it == '?' })
    }

    /** T-J2：HQL UPDATE 同样生成 UpdateStatement（demo hibernateHqlUpdate 同形态）。 */
    @Test
    fun `hqlUpdate_returnsUpdateStatement`() {
        val hql = "UPDATE UserEntity u SET u.status = :newStatus WHERE u.status = :oldStatus"
        val result = HqlConverter.convert(hql, userTable)

        assertTrue(
            "UPDATE HQL 应返回 UpdateStatement，实际 ${result.statement::class.simpleName}",
            result.statement is UpdateStatement,
        )
        assertEquals("UPDATE user u SET u.status = ? WHERE u.status = ?", result.sql)
        assertEquals(2, result.sql.count { it == '?' })
    }

    /** T-J2：UpdateStatement 模型字段——sets / tableAlias / conditions 逐项校验。 */
    @Test
    fun `jpqlUpdate_statementFieldsPopulated`() {
        val result = JpqlConverter.convert(
            "UPDATE UserEntity u SET u.status = :newStatus WHERE u.status = :oldStatus",
            userTable,
        )
        val update = result.statement as UpdateStatement
        assertEquals("主表别名应解析", "u", update.tableAlias)
        assertEquals("应解析出 1 个 SET 对", 1, update.sets.size)
        val set = update.sets[0]
        assertFalse("参数赋值不应是 raw 形态", set.isRaw)
        assertEquals("status", set.column!!.columnName)
        assertEquals("u", set.column!!.tableAlias)
        assertTrue("SET 值应为 ? 占位符", set.value!!.isPlaceholder)
        assertEquals(1, update.conditions.size)
        val condition = update.conditions[0] as Condition.Simple
        assertEquals("status", condition.column.columnName)
        assertEquals("=", condition.op)
        assertTrue(condition.value.isPlaceholder)
    }

    /** T-J2：模型经 SqlGenerator 渲染可还原 SQL（面板渲染链路可用）。 */
    @Test
    fun `updateStatement_roundTripThroughSqlGenerator`() {
        val result = JpqlConverter.convert(
            "UPDATE UserEntity u SET u.status = :newStatus WHERE u.id = :id",
            userTable,
        )
        val sql = SqlGenerator.generate(result.statement)
        // 表名 `user` 命中方言保留字会被 T-M23.4 反引号包裹（UPDATE `user` u），
        // 引号属渲染层预期行为，此处不校验表名引号形态
        assertTrue("渲染应为 UPDATE 语句: $sql", sql.trimStart().startsWith("UPDATE"))
        assertTrue("渲染应含 SET u.status = ?: $sql", sql.contains("SET u.status = ?"))
        assertTrue("渲染应含 id 条件: $sql", sql.contains("u.id = ?"))
    }

    // ------------------------------------------------------------------
    // SET 多对与值形态
    // ------------------------------------------------------------------

    /** T-J2：多 SET 对 + 字面量值（字符串/数值）混合。 */
    @Test
    fun `update_multipleSetPairs_withLiterals`() {
        val result = JpqlConverter.convert(
            "UPDATE UserEntity u SET u.status = :status, u.username = 'admin', u.age = 30 WHERE u.id = :id",
            userTable,
        )
        assertEquals(
            "UPDATE user u SET u.status = ?, u.username = 'admin', u.age = 30 WHERE u.id = ?",
            result.sql,
        )
        val update = result.statement as UpdateStatement
        assertEquals(3, update.sets.size)
        assertTrue(update.sets[0].value!!.isPlaceholder)
        assertEquals("'admin'", (update.sets[1].value as SqlValue.Literal).raw)
        val usernameLiteral = update.sets[1].value as SqlValue.Literal
        assertEquals("admin", usernameLiteral.unquoted)
        assertEquals("30", (update.sets[2].value as SqlValue.Literal).raw)
    }

    /** T-J2：布尔与 NULL 字面量按 Literal 透传。 */
    @Test
    fun `update_booleanAndNullLiterals`() {
        val result = HqlConverter.convert(
            "UPDATE UserEntity u SET u.status = TRUE, u.username = NULL WHERE u.id = :id",
            userTable,
        )
        assertEquals("UPDATE user u SET u.status = TRUE, u.username = NULL WHERE u.id = ?", result.sql)
        val update = result.statement as UpdateStatement
        assertEquals("TRUE", (update.sets[0].value as SqlValue.Literal).raw)
        assertEquals("NULL", (update.sets[1].value as SqlValue.Literal).raw)
        assertFalse(update.sets[0].isRaw)
    }

    /** T-J2：简单算术 SET（`u.age = u.age + 1`）→ SetClause.rawSql 原样透传。 */
    @Test
    fun `update_arithmeticSet_fallsBackToRawClause`() {
        val result = HqlConverter.convert(
            "UPDATE UserEntity u SET u.age = u.age + 1 WHERE u.id = :id",
            userTable,
        )
        assertEquals("UPDATE user u SET u.age = u.age + 1 WHERE u.id = ?", result.sql)
        val update = result.statement as UpdateStatement
        assertTrue("算术赋值应为 raw 形态", update.sets[0].isRaw)
        assertEquals("u.age = u.age + 1", update.sets[0].rawSql)
    }

    /** T-J2：属性→列名映射在算术 SET 两侧同样生效（totalAmount → total_amount）。 */
    @Test
    fun `update_arithmeticSet_columnsMapped`() {
        val result = HqlConverter.convert(
            "UPDATE OrderEntity o SET o.totalAmount = o.totalAmount * 0.9 WHERE o.id = :id",
            orderTable,
        )
        assertEquals(
            "UPDATE t_order o SET o.total_amount = o.total_amount * 0.9 WHERE o.id = ?",
            result.sql,
        )
        val update = result.statement as UpdateStatement
        assertTrue(update.sets[0].isRaw)
        assertEquals("o.total_amount = o.total_amount * 0.9", update.sets[0].rawSql)
    }

    /** T-J2：关联路径 SET（`rti.ruleTable.id` → 外键列 RULE_TABLE_ID）。 */
    @Test
    fun `update_associationPathSet_mapsToFkColumn`() {
        val result = HqlConverter.convert(
            "UPDATE RuleTableItem rti SET rti.ruleTable.id = :rtId WHERE rti.id = :id",
            ruleTableItemTable,
        )
        assertEquals(
            "UPDATE THORN_RULE_TABLE_ITEM rti SET rti.RULE_TABLE_ID = ? WHERE rti.ID = ?",
            result.sql,
        )
        val update = result.statement as UpdateStatement
        assertEquals("RULE_TABLE_ID", update.sets[0].column!!.columnName)
    }

    /** T-J2：无别名 UPDATE（`UPDATE UserEntity SET ...`）→ tableAlias 为 null。 */
    @Test
    fun `update_withoutAlias_tableAliasNull`() {
        val result = JpqlConverter.convert(
            "UPDATE UserEntity SET status = :status WHERE id = :id",
            userTable,
        )
        assertEquals("UPDATE user SET status = ? WHERE id = ?", result.sql)
        val update = result.statement as UpdateStatement
        assertNull("无别名时 tableAlias 应为 null", update.tableAlias)
        assertNull(update.sets[0].column!!.tableAlias)
    }

    /** T-J2：无 WHERE 的 UPDATE → conditions 为空（UI 层全表更新警示语义）。 */
    @Test
    fun `update_withoutWhere_conditionsEmpty`() {
        val result = HqlConverter.convert("UPDATE UserEntity u SET u.status = 1", userTable)
        assertEquals("UPDATE user u SET u.status = 1", result.sql)
        val update = result.statement as UpdateStatement
        assertEquals(0, update.conditions.size)
    }

    // ------------------------------------------------------------------
    // WHERE 形态
    // ------------------------------------------------------------------

    /** T-J2：IS NULL / IS NOT NULL 解析为 Unary 条件。 */
    @Test
    fun `update_whereIsNull_parsedAsUnary`() {
        val result = JpqlConverter.convert(
            "UPDATE UserEntity u SET u.status = :status WHERE u.username IS NULL AND u.age IS NOT NULL",
            userTable,
        )
        val update = result.statement as UpdateStatement
        assertEquals(2, update.conditions.size)
        assertEquals("IS NULL", (update.conditions[0] as Condition.Unary).op)
        assertEquals("IS NOT NULL", (update.conditions[1] as Condition.Unary).op)
    }

    /** T-J2：WHERE `IN (?)` 解析为 In 条件。 */
    @Test
    fun `update_whereIn_parsedAsInCondition`() {
        val result = HqlConverter.convert(
            "UPDATE UserEntity u SET u.status = :status WHERE u.id IN (:ids)",
            userTable,
        )
        assertTrue("IN 应修正为 IN (?): ${result.sql}", result.sql.contains("u.id IN (?)"))
        val update = result.statement as UpdateStatement
        val inCondition = update.conditions[0] as Condition.In
        assertFalse(inCondition.negated)
        assertEquals(1, inCondition.values.size)
        assertTrue(inCondition.values[0].isPlaceholder)
    }

    /** T-J2：WHERE 含 OR → 整体 Raw 条件透传（参数已替换为 ?）。 */
    @Test
    fun `update_whereOr_fallsBackToRawCondition`() {
        val result = JpqlConverter.convert(
            "UPDATE UserEntity u SET u.status = :status WHERE u.role = :role OR u.age > 18",
            userTable,
        )
        assertEquals(
            "UPDATE user u SET u.status = ? WHERE u.role = ? OR u.age > 18",
            result.sql,
        )
        val update = result.statement as UpdateStatement
        assertEquals(1, update.conditions.size)
        val raw = update.conditions[0] as Condition.Raw
        assertEquals("u.role = ? OR u.age > 18", raw.sqlText)
    }

    /** T-J2：WHERE 含 BETWEEN（AND 歧义形态）→ Raw 透传，不误拆。 */
    @Test
    fun `update_whereBetween_fallsBackToRawCondition`() {
        val result = HqlConverter.convert(
            "UPDATE UserEntity u SET u.status = :status WHERE u.age BETWEEN :min AND :max",
            userTable,
        )
        val update = result.statement as UpdateStatement
        assertEquals(1, update.conditions.size)
        val raw = update.conditions[0] as Condition.Raw
        assertEquals("u.age BETWEEN ? AND ?", raw.sqlText)
    }

    // ------------------------------------------------------------------
    // 边界与回归
    // ------------------------------------------------------------------

    /** T-J2：TableInfo 缺失（实体不可解析）时 UPDATE 优雅降级——属性名原样保留。 */
    @Test
    fun `update_withoutTableInfo_degradesGracefully`() {
        val result = JpqlConverter.convert(
            "UPDATE UserEntity u SET u.status = :status WHERE u.id = :id",
            null,
        )
        val update = result.statement as UpdateStatement
        assertEquals("unknown_table", update.table.tableName)
        assertEquals("u.status", update.sets[0].column!!.let { "${it.tableAlias}.${it.columnName}" })
        assertEquals("占位符仍应替换", 2, result.sql.count { it == '?' })
    }

    /** T-J2：UPDATE 语句不应被误判为复杂语法 SELECT，也不应残留命名参数。 */
    @Test
    fun `update_noNamedParamResidue_noSelectExpand`() {
        val result = JpqlConverter.convert(
            "UPDATE UserEntity u SET u.status = :status WHERE u.age > :minAge",
            userTable,
        )
        assertFalse("不应残留命名参数: ${result.sql}", result.sql.contains(":"))
        assertFalse("SELECT 展开器不得改写 UPDATE: ${result.sql}", result.sql.contains("SELECT"))
        assertTrue(result.statement is UpdateStatement)
    }

    /** T-J2：DELETE 路径回归——UPDATE 特判不影响既有 DELETE 行为。 */
    @Test
    fun `deletePath_unchanged_afterUpdateSupport`() {
        val result = JpqlConverter.convert("DELETE FROM UserEntity u WHERE u.age < :maxAge", userTable)
        assertTrue(result.statement is cn.song.mybatisplus.visualizer.model.DeleteStatement)
        assertEquals("DELETE FROM user u WHERE u.age < ?", result.sql)

        val hqlResult = HqlConverter.convert("DELETE FROM UserEntity u WHERE u.age < :maxAge", userTable)
        assertTrue(hqlResult.statement is cn.song.mybatisplus.visualizer.model.DeleteStatement)
    }

    /** T-J2：SELECT 路径回归——UPDATE 支持不改变普通查询的 statement 类型。 */
    @Test
    fun `selectPath_unchanged_afterUpdateSupport`() {
        val result = JpqlConverter.convert(
            "SELECT u FROM UserEntity u WHERE u.username = :username",
            userTable,
        )
        assertTrue(result.statement is SelectStatement)
    }

    // ------------------------------------------------------------------
    // JpaUpdateParser 直接单测（解析器边界）
    // ------------------------------------------------------------------

    /** T-J2：字符串字面量内含逗号 / AND / = 的 SET 值不被误切分。 */
    @Test
    fun `parser_literalWithCommaAndAnd_notSplit`() {
        val parsed = JpaUpdateParser.parse(
            "UPDATE user u SET u.username = 'a,b AND c=d', u.status = 'x' WHERE u.id = 1",
        )
        assertEquals(2, parsed!!.sets.size)
        assertEquals("a,b AND c=d", (parsed.sets[0].value as SqlValue.Literal).unquoted)
        assertEquals("x", (parsed.sets[1].value as SqlValue.Literal).unquoted)
    }

    /** T-J2：非 UPDATE 文本解析返回 null（调用方维持透传）。 */
    @Test
    fun `parser_nonUpdate_returnsNull`() {
        assertNull(JpaUpdateParser.parse("SELECT * FROM user WHERE id = 1"))
        assertNull(JpaUpdateParser.parse("DELETE FROM user WHERE id = 1"))
        assertNull("缺 SET 关键字应返回 null", JpaUpdateParser.parse("UPDATE user u WHERE u.id = 1"))
    }

    /** T-J2：带 AS 别名形态。 */
    @Test
    fun `parser_asAliasForm_parsed`() {
        val parsed = JpaUpdateParser.parse("UPDATE user AS u SET u.status = ? WHERE u.id = ?")
        assertEquals("u", parsed!!.tableAlias)
        assertEquals(1, parsed.sets.size)
        assertEquals(1, parsed.conditions.size)
    }
}
