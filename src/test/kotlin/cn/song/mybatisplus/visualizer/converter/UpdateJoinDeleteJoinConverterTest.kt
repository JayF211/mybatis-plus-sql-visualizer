package cn.song.mybatisplus.visualizer.converter

import cn.song.mybatisplus.visualizer.model.ColumnInfo
import cn.song.mybatisplus.visualizer.model.ColumnRef
import cn.song.mybatisplus.visualizer.model.DeleteStatement
import cn.song.mybatisplus.visualizer.model.SqlValue
import cn.song.mybatisplus.visualizer.model.TableInfo
import cn.song.mybatisplus.visualizer.model.UpdateStatement
import cn.song.mybatisplus.visualizer.model.WrapperArg
import cn.song.mybatisplus.visualizer.model.WrapperCall
import cn.song.mybatisplus.visualizer.model.WrapperChain
import cn.song.mybatisplus.visualizer.model.WrapperType
import cn.song.mybatisplus.visualizer.sql.SqlGenerator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T-M21-08 转换器单元测试：UpdateJoinWrapper / DeleteJoinWrapper（纯逻辑模型构造，无 PSI）。
 *
 * 对照 MPJ 官方 SQL 输出结构：
 *  - UpdateJoin：`UPDATE user t LEFT JOIN address t1 ON (t1.user_id = t.id) SET t.name = ?, t1.tel = ? WHERE (t.id = ?)`
 *  - DeleteJoin：`DELETE t,t1 FROM user t LEFT JOIN address t1 ON (t1.user_id = t.id) WHERE (t.id = ?)`
 *    （delete(Class...)/delete(String...)/deleteAll() 控制删除哪些表）
 *
 * 覆盖：
 *  - UpdateJoin 全形态：set / setSql{0} / setIncrBy / setDecrBy / join 三参 / 条件带主别名
 *  - setUpdateEntity / setUpdateEntityAndNull 实体 SET 近似（主表非主键全列占位）
 *  - setAlias 主表别名 / on(col1, col2) / 跨表字符串列条件
 *  - DeleteJoin 三形态：delete(Class...) / deleteAll / 无 delete 调用 + 字符串目标
 */
class UpdateJoinDeleteJoinConverterTest {

    /** 主表：user（对应 UserEntity，主别名默认 t）。 */
    private val table = TableInfo(
        entityClassName = "UserEntity",
        tableName = "user",
        columns = listOf(
            ColumnInfo("id", "id", isId = true),
            ColumnInfo("name", "name"),
            ColumnInfo("updateBy", "update_by"),
            ColumnInfo("age", "age"),
        ),
    )

    /** 关联表：address（对应 AddressEntity，psi 层 EntityRefArg 形态，带真实 TableInfo）。 */
    private val addressTable = TableInfo(
        entityClassName = "AddressEntity",
        tableName = "address",
        columns = listOf(
            ColumnInfo("id", "id", isId = true),
            ColumnInfo("userId", "user_id"),
            ColumnInfo("tel", "tel"),
        ),
    )

    /** 便捷构造：eq(column, value)。 */
    private fun eqCall(column: String, value: String) = WrapperCall(
        "eq",
        listOf(WrapperArg.ColumnArg(ColumnRef(columnName = column)), WrapperArg.ValueArg(SqlValue.Literal(value))),
    )

    /** 便捷构造：leftJoin(Class, 关联表字段, 主表字段) 三参。 */
    private fun leftJoinCall() = WrapperCall(
        "leftJoin",
        listOf(
            WrapperArg.EntityRefArg(addressTable),
            WrapperArg.ColumnArg(ColumnRef(columnName = "user_id")),
            WrapperArg.ColumnArg(ColumnRef(columnName = "id")),
        ),
    )

    // ------------------------------------------------------------------
    // UpdateJoin：全形态（对照官方 SQL 输出结构）
    // ------------------------------------------------------------------

    @Test
    fun `updateJoin_set与setSql与setIncrBy与join与条件`() {
        // 全形态：set（主表方法引用列 + 跨表字符串列）、setSql{0} 绑定参数、setIncrBy、
        // leftJoin 三参、eq 条件——SET 跨表列（t1.tel）显式别名原样保留
        val chain = WrapperChain(
            WrapperType.UPDATE_JOIN, "UserEntity",
            listOf(
                WrapperCall("set", listOf(
                    WrapperArg.ColumnArg(ColumnRef(columnName = "name")),
                    WrapperArg.ValueArg(SqlValue.Literal("'张三'")),
                )),
                WrapperCall("set", listOf(
                    WrapperArg.ValueArg(SqlValue.Literal("t1.tel")),
                    WrapperArg.ValueArg(SqlValue.Literal("138")),
                )),
                WrapperCall("setSql", listOf(
                    WrapperArg.ValueArg(SqlValue.Literal("t.update_by = {0}")),
                    WrapperArg.ValueArg(SqlValue.Literal("'admin'")),
                )),
                WrapperCall("setIncrBy", listOf(
                    WrapperArg.ColumnArg(ColumnRef(columnName = "age")),
                    WrapperArg.ValueArg(SqlValue.Literal("1")),
                )),
                leftJoinCall(),
                eqCall("id", "1001"),
            ),
        )
        val statement = UpdateJoinConverter.convert(chain, table) as UpdateStatement
        assertEquals("user", statement.table.tableName)
        assertEquals("t", statement.tableAlias)
        assertEquals(1, statement.joins.size)
        val sql = SqlGenerator.generate(statement)
        assertEquals(
            """
            UPDATE user t
            LEFT JOIN address addressEntity ON addressEntity.user_id = t.id
            SET t.name = '张三', t1.tel = 138, t.update_by = 'admin', t.age = t.age + 1
            WHERE t.id = 1001
            """.trimIndent(),
            sql,
        )
    }

    @Test
    fun `updateJoin_setDecrBy与on两列比较与跨表条件`() {
        // on(col1, col2) 两列比较（left 主表列、right 关联表列）+ 字符串跨表条件原样透传 + setDecrBy
        val chain = WrapperChain(
            WrapperType.UPDATE_JOIN, "UserEntity",
            listOf(
                WrapperCall("leftJoin", listOf(
                    WrapperArg.EntityRefArg(addressTable),
                    WrapperArg.OnConditionArg(
                        ColumnRef(columnName = "id"),
                        ColumnRef(columnName = "user_id"),
                    ),
                )),
                WrapperCall("setDecrBy", listOf(
                    WrapperArg.ColumnArg(ColumnRef(columnName = "age")),
                    WrapperArg.ValueArg(SqlValue.Literal("2")),
                )),
                WrapperCall("eq", listOf(
                    WrapperArg.ValueArg(SqlValue.Literal("t1.status")),
                    WrapperArg.ValueArg(SqlValue.Literal("'1'")),
                )),
            ),
        )
        val sql = SqlGenerator.generate(UpdateJoinConverter.convert(chain, table))
        assertEquals(
            """
            UPDATE user t
            LEFT JOIN address addressEntity ON t.id = addressEntity.user_id
            SET t.age = t.age - 2
            WHERE t1.status = '1'
            """.trimIndent(),
            sql,
        )
    }

    @Test
    fun `updateJoin_setUpdateEntity近似主表非主键全列占位`() {
        // setUpdateEntity(entity)：实体字段静态不可得 → 主表非主键全列 `t.col = ?` 占位（KDoc 近似标注）；
        // setUpdateEntityAndNull 同近似（官方语义含 null 字段，静态不可区分）
        val chain = WrapperChain(
            WrapperType.UPDATE_JOIN, "UserEntity",
            listOf(
                WrapperCall("setUpdateEntity", listOf(WrapperArg.ValueArg(SqlValue.Placeholder("entity")))),
                eqCall("id", "1001"),
            ),
        )
        val sql = SqlGenerator.generate(UpdateJoinConverter.convert(chain, table))
        assertEquals(
            """
            UPDATE user t
            SET t.name = ?, t.update_by = ?, t.age = ?
            WHERE t.id = 1001
            """.trimIndent(),
            sql,
        )

        val andNull = SqlGenerator.generate(UpdateJoinConverter.convert(
            WrapperChain(
                WrapperType.UPDATE_JOIN, "UserEntity",
                listOf(WrapperCall("setUpdateEntityAndNull", listOf(WrapperArg.ValueArg(SqlValue.Placeholder("entity"))))),
            ),
            table,
        ))
        assertEquals(
            """
            UPDATE user t
            SET t.name = ?, t.update_by = ?, t.age = ?
            """.trimIndent(),
            andNull,
        )
    }

    @Test
    fun `updateJoin_setAlias主表别名与lambdaOn`() {
        // setAlias("u") → 主表别名 u；lambda ON（on -> on.eq(关联表字段, 主表字段)）→ ON 列对列
        val chain = WrapperChain(
            WrapperType.UPDATE_JOIN, "UserEntity",
            listOf(
                WrapperCall("setAlias", listOf(WrapperArg.ValueArg(SqlValue.Literal("u")))),
                WrapperCall("leftJoin", listOf(
                    WrapperArg.EntityRefArg(addressTable),
                    WrapperArg.NestedArg(WrapperChain(WrapperType.NESTED, null, listOf(
                        WrapperCall("eq", listOf(
                            WrapperArg.ColumnArg(ColumnRef(columnName = "user_id")),
                            WrapperArg.ColumnArg(ColumnRef(columnName = "id")),
                        )),
                    ))),
                )),
                WrapperCall("set", listOf(
                    WrapperArg.ColumnArg(ColumnRef(columnName = "name")),
                    WrapperArg.ValueArg(SqlValue.Literal("'x'")),
                )),
                eqCall("id", "1"),
            ),
        )
        val sql = SqlGenerator.generate(UpdateJoinConverter.convert(chain, table))
        assertEquals(
            """
            UPDATE user u
            LEFT JOIN address addressEntity ON addressEntity.user_id = u.id
            SET u.name = 'x'
            WHERE u.id = 1
            """.trimIndent(),
            sql,
        )
    }

    @Test
    fun `updateJoin_set布尔前缀剥离与字符串sql join`() {
        // set(boolean, col, val) 布尔前缀剥离；字符串 SQL join（leftJoin("address t1 on ...", args)）原样透传
        val chain = WrapperChain(
            WrapperType.UPDATE_JOIN, "UserEntity",
            listOf(
                WrapperCall("set", listOf(
                    WrapperArg.ValueArg(SqlValue.Literal("true")),
                    WrapperArg.ColumnArg(ColumnRef(columnName = "name")),
                    WrapperArg.ValueArg(SqlValue.Literal("'x'")),
                )),
                WrapperCall("leftJoin", listOf(
                    WrapperArg.ValueArg(SqlValue.Literal("address t1 on t1.user_id = t.id")),
                )),
                eqCall("id", "1"),
            ),
        )
        val sql = SqlGenerator.generate(UpdateJoinConverter.convert(chain, table))
        assertEquals(
            """
            UPDATE user t
            LEFT JOIN address t1 on t1.user_id = t.id
            SET t.name = 'x'
            WHERE t.id = 1
            """.trimIndent(),
            sql,
        )
    }

    // ------------------------------------------------------------------
    // DeleteJoin：删除目标三形态（delete(Class...) / deleteAll / 无 delete）
    // ------------------------------------------------------------------

    @Test
    fun `deleteJoin_deleteAll全表目标`() {
        // deleteAll() → 主别名 + 全部 join 别名：DELETE t, addressEntity FROM user t LEFT JOIN ...
        val chain = WrapperChain(
            WrapperType.DELETE_JOIN, "UserEntity",
            listOf(
                leftJoinCall(),
                WrapperCall("deleteAll", emptyList()),
                eqCall("id", "1001"),
            ),
        )
        val statement = DeleteJoinConverter.convert(chain, table) as DeleteStatement
        assertEquals("deleteAll 目标 = 主别名 + join 别名", listOf("t", "addressEntity"), statement.deleteTargets)
        val sql = SqlGenerator.generate(statement)
        assertEquals(
            """
            DELETE t, addressEntity FROM user t
            LEFT JOIN address addressEntity ON addressEntity.user_id = t.id
            WHERE t.id = 1001
            """.trimIndent(),
            sql,
        )
    }

    @Test
    fun `deleteJoin_deleteClass主表与关联表目标`() {
        // delete(UserEntity.class) → 主别名 t；delete(AddressEntity.class) → 关联表别名 addressEntity
        val mainTarget = WrapperChain(
            WrapperType.DELETE_JOIN, "UserEntity",
            listOf(
                leftJoinCall(),
                WrapperCall("delete", listOf(WrapperArg.ValueArg(SqlValue.Literal("UserEntity.class")))),
                eqCall("id", "1001"),
            ),
        )
        val mainStatement = DeleteJoinConverter.convert(mainTarget, table) as DeleteStatement
        assertEquals(listOf("t"), mainStatement.deleteTargets)
        assertEquals(
            """
            DELETE t FROM user t
            LEFT JOIN address addressEntity ON addressEntity.user_id = t.id
            WHERE t.id = 1001
            """.trimIndent(),
            SqlGenerator.generate(mainStatement),
        )

        // 仅删关联表：delete(AddressEntity.class) → 目标 addressEntity（主表仍参与 JOIN/WHERE）
        val subTarget = WrapperChain(
            WrapperType.DELETE_JOIN, "UserEntity",
            listOf(
                leftJoinCall(),
                WrapperCall("delete", listOf(WrapperArg.ValueArg(SqlValue.Literal("AddressEntity.class")))),
                eqCall("id", "1001"),
            ),
        )
        val subStatement = DeleteJoinConverter.convert(subTarget, table) as DeleteStatement
        assertEquals(listOf("addressEntity"), subStatement.deleteTargets)
        assertEquals(
            """
            DELETE addressEntity FROM user t
            LEFT JOIN address addressEntity ON addressEntity.user_id = t.id
            WHERE t.id = 1001
            """.trimIndent(),
            SqlGenerator.generate(subStatement),
        )
    }

    @Test
    fun `deleteJoin_无delete调用默认主别名与字符串目标`() {
        // 无 delete 调用 → 默认仅主别名 t（DELETE t FROM ...）；字符串目标 delete("t1") 原样
        val defaultChain = WrapperChain(
            WrapperType.DELETE_JOIN, "UserEntity",
            listOf(
                leftJoinCall(),
                eqCall("id", "1001"),
            ),
        )
        val defaultStatement = DeleteJoinConverter.convert(defaultChain, table) as DeleteStatement
        assertEquals(listOf("t"), defaultStatement.deleteTargets)

        val stringTarget = WrapperChain(
            WrapperType.DELETE_JOIN, "UserEntity",
            listOf(
                leftJoinCall(),
                WrapperCall("delete", listOf(WrapperArg.ValueArg(SqlValue.Literal("t1")))),
                eqCall("id", "1001"),
            ),
        )
        val stringStatement = DeleteJoinConverter.convert(stringTarget, table) as DeleteStatement
        assertEquals(listOf("t1"), stringStatement.deleteTargets)
    }

    @Test
    fun `deleteJoin_单表无join保持既有形态`() {
        // 无 join、无 delete 调用 → DELETE t FROM user t WHERE ...（主别名仍在删除目标中，FROM 后带主别名）；
        // 对比：LambdaDeleteWrapper 单表删除（deleteTargets 空）走既有 DELETE FROM 形态
        val chain = WrapperChain(
            WrapperType.DELETE_JOIN, "UserEntity",
            listOf(eqCall("id", "1")),
        )
        val sql = SqlGenerator.generate(DeleteJoinConverter.convert(chain, table))
        assertEquals(
            """
            DELETE t FROM user t
            WHERE t.id = 1
            """.trimIndent(),
            sql,
        )
        assertEquals(
            """
            DELETE FROM user
            WHERE id = 1
            """.trimIndent(),
            SqlGenerator.generate(LambdaDeleteConverter.convert(
                WrapperChain(WrapperType.LAMBDA_DELETE, "UserEntity", listOf(eqCall("id", "1"))), table,
            )),
        )
    }

    // ------------------------------------------------------------------
    // ConverterRegistry 分发（T-M21-08）
    // ------------------------------------------------------------------

    @Test
    fun `注册表_新类型分发到联表更新与删除转换器`() {
        assertTrue(ConverterRegistry.convert(
            WrapperChain(WrapperType.UPDATE_JOIN, "UserEntity", listOf(eqCall("id", "1"))), table,
        ) is UpdateStatement)
        assertTrue(ConverterRegistry.convert(
            WrapperChain(WrapperType.DELETE_JOIN, "UserEntity", listOf(eqCall("id", "1"))), table,
        ) is DeleteStatement)
    }
}
