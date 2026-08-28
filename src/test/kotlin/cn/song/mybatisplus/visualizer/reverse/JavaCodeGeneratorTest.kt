package cn.song.mybatisplus.visualizer.reverse

import cn.song.mybatisplus.visualizer.model.ColumnRef
import cn.song.mybatisplus.visualizer.model.Condition
import cn.song.mybatisplus.visualizer.model.Connector
import cn.song.mybatisplus.visualizer.model.LikePosition
import cn.song.mybatisplus.visualizer.model.LimitClause
import cn.song.mybatisplus.visualizer.model.OrderByItem
import cn.song.mybatisplus.visualizer.model.SqlValue
import cn.song.mybatisplus.visualizer.reverse.ReverseJoin
import cn.song.mybatisplus.visualizer.reverse.ReverseSet
import cn.song.mybatisplus.visualizer.reverse.ReverseStatementKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * QueryWrapper Java 代码生成器单元测试（P0）：[SqlReverseResult] → 代码文本。
 */
class JavaCodeGeneratorTest {

    @Test
    fun `完整链路_条件全家与排序分页`() {
        val result = SqlReverseResult(
            tableName = "user",
            selectColumns = listOf(SelectColumn("id"), SelectColumn("name")),
            conditions = listOf(
                Condition.Simple(ColumnRef(columnName = "name"), "=", SqlValue.Literal("'张三'", "张三")),
                Condition.Simple(ColumnRef(columnName = "age"), ">", SqlValue.Literal("18")),
                Condition.Simple(ColumnRef(columnName = "flag"), "=", SqlValue.Literal("true")),
                Condition.Unary(ColumnRef(columnName = "deleted"), "IS NULL"),
                Condition.In(ColumnRef(columnName = "status"), negated = false, listOf(SqlValue.Literal("1"), SqlValue.Literal("2"))),
                Condition.In(ColumnRef(columnName = "exclude"), negated = true, listOf(SqlValue.Literal("'a'", "a"))),
                Condition.Between(ColumnRef(columnName = "create_time"), negated = false, SqlValue.Literal("'2024-01-01'", "2024-01-01"), SqlValue.Literal("'2024-01-31'", "2024-01-31")),
                Condition.Like(ColumnRef(columnName = "remark"), negated = false, SqlValue.Literal("'%abc%'", "abc"), LikePosition.ANYWHERE),
                Condition.Like(ColumnRef(columnName = "nick"), negated = true, SqlValue.Literal("'张%'", "张"), LikePosition.RIGHT),
            ),
            orderBy = listOf(
                OrderByItem(ColumnRef(columnName = "create_time"), asc = false),
                OrderByItem(ColumnRef(columnName = "id"), asc = false),
            ),
            limit = LimitClause(count = SqlValue.Literal("10")),
        )
        val code = JavaCodeGenerator.generate(result)
        assertEquals(
            """
            // SQL → MyBatis-Plus QueryWrapper 反向生成（SQL Visualizer）
            // 实体类名 User 由表名 user 推断，请按实际类名调整
            new QueryWrapper<User>()
                .select("id", "name")
                .eq("name", "张三")
                .gt("age", 18)
                .eq("flag", true)
                .isNull("deleted")
                .in("status", 1, 2)
                .notIn("exclude", "a")
                .between("create_time", "2024-01-01", "2024-01-31")
                .like("remark", "abc")
                .notLikeRight("nick", "张")
                .orderByDesc("create_time", "id")
                .last("LIMIT 10");
            """.trimIndent(),
            code,
        )
    }

    @Test
    fun `无条件_最小形态`() {
        val code = JavaCodeGenerator.generate(SqlReverseResult(tableName = "user", selectColumns = null))
        assertEquals(
            """
            // SQL → MyBatis-Plus QueryWrapper 反向生成（SQL Visualizer）
            // 实体类名 User 由表名 user 推断，请按实际类名调整
            new QueryWrapper<User>();
            """.trimIndent(),
            code,
        )
    }

    @Test
    fun `字符串值转义_双引号与反斜杠`() {
        val result = SqlReverseResult(
            tableName = "t",
            selectColumns = null,
            conditions = listOf(
                Condition.Simple(ColumnRef(columnName = "path"), "=", SqlValue.Literal("'a\"b\\c'", "a\"b\\c")),
            ),
        )
        val code = JavaCodeGenerator.generate(result)
        assertTrue(code.contains(".eq(\"path\", \"a\\\"b\\\\c\")"))
    }

    @Test
    fun `LIKE降级Raw_渲染apply`() {
        val result = SqlReverseResult(
            tableName = "t",
            selectColumns = null,
            conditions = listOf(Condition.Raw("a LIKE 'a_b'")),
            warnings = listOf("LIKE 模式 a_b 无法映射为 like 家族方法，已用 apply() 透传"),
        )
        val code = JavaCodeGenerator.generate(result)
        assertTrue(code.contains(".apply(\"a LIKE 'a_b'\")"))
        assertTrue(code.contains("// 注意：LIKE 模式 a_b 无法映射为 like 家族方法，已用 apply() 透传"))
    }

    @Test
    fun `ORDER BY_异向拆分与同向合并`() {
        val result = SqlReverseResult(
            tableName = "t",
            selectColumns = null,
            orderBy = listOf(
                OrderByItem(ColumnRef(columnName = "a"), asc = true),
                OrderByItem(ColumnRef(columnName = "b"), asc = true),
                OrderByItem(ColumnRef(columnName = "c"), asc = false),
                OrderByItem(ColumnRef(columnName = "d"), asc = true),
            ),
        )
        val code = JavaCodeGenerator.generate(result)
        assertTrue(code.contains(".orderByAsc(\"a\", \"b\")"))
        assertTrue(code.contains(".orderByDesc(\"c\")"))
        assertTrue(code.contains(".orderByAsc(\"d\")"))
    }

    @Test
    fun `LIMIT偏移形态_渲染last`() {
        val result = SqlReverseResult(
            tableName = "t",
            selectColumns = null,
            limit = LimitClause(offset = SqlValue.Literal("5"), count = SqlValue.Literal("10")),
        )
        assertTrue(JavaCodeGenerator.generate(result).contains(".last(\"LIMIT 5, 10\")"))
    }

    @Test
    fun `实体类名推断_下划线转驼峰`() {
        assertEquals("CarDeviceInfo", JavaCodeGenerator.entityClassName("car_device_info"))
        assertEquals("User", JavaCodeGenerator.entityClassName("user"))
        assertEquals("UserInfo", JavaCodeGenerator.entityClassName("userInfo"))
        assertEquals("OrderItem2", JavaCodeGenerator.entityClassName("order_item_2"))
    }

    @Test
    fun `javaEscape_转义集合`() {
        assertEquals("a\\\"b\\\\c\\n", JavaCodeGenerator.javaEscape("a\"b\\c\n"))
    }

    // ------------------------------------------------------------------
    // 组渲染（T-M36.1）
    // ------------------------------------------------------------------

    @Test
    fun `OR组_渲染nested与or分隔`() {
        val result = SqlReverseResult(
            tableName = "t",
            selectColumns = null,
            conditions = listOf(
                Condition.Group(
                    Connector.OR,
                    listOf(
                        Condition.Simple(ColumnRef(columnName = "a"), "=", SqlValue.Literal("1")),
                        Condition.Simple(ColumnRef(columnName = "b"), "=", SqlValue.Literal("'x'", "x")),
                    ),
                ),
            ),
        )
        val code = JavaCodeGenerator.generate(result)
        assertTrue(code, code.contains(".nested(w -> w\n        .eq(\"a\", 1)\n        .or()\n        .eq(\"b\", \"x\"))"))
    }

    @Test
    fun `AND组与前置条件_渲染nested`() {
        val result = SqlReverseResult(
            tableName = "t",
            selectColumns = null,
            conditions = listOf(
                Condition.Simple(ColumnRef(columnName = "c"), "=", SqlValue.Literal("3")),
                Condition.Group(
                    Connector.AND,
                    listOf(
                        Condition.Simple(ColumnRef(columnName = "a"), "=", SqlValue.Literal("1")),
                        Condition.Simple(ColumnRef(columnName = "b"), "=", SqlValue.Literal("2")),
                    ),
                ),
            ),
        )
        val code = JavaCodeGenerator.generate(result)
        assertTrue(code, code.contains(".eq(\"c\", 3)\n    .nested(w -> w\n        .eq(\"a\", 1)\n        .eq(\"b\", 2))"))
    }

    @Test
    fun `NOT组_渲染not`() {
        val result = SqlReverseResult(
            tableName = "t",
            selectColumns = null,
            conditions = listOf(
                Condition.Group(
                    Connector.OR,
                    listOf(
                        Condition.Simple(ColumnRef(columnName = "a"), "=", SqlValue.Literal("1")),
                        Condition.Simple(ColumnRef(columnName = "b"), "=", SqlValue.Literal("2")),
                    ),
                    negated = true,
                ),
            ),
        )
        val code = JavaCodeGenerator.generate(result)
        assertTrue(code, code.contains(".not(w -> w\n        .eq(\"a\", 1)\n        .or()\n        .eq(\"b\", 2))"))
    }

    @Test
    fun `嵌套组_渲染递归lambda`() {
        val result = SqlReverseResult(
            tableName = "t",
            selectColumns = null,
            conditions = listOf(
                Condition.Group(
                    Connector.OR,
                    listOf(
                        Condition.Group(
                            Connector.AND,
                            listOf(
                                Condition.Simple(ColumnRef(columnName = "a"), "=", SqlValue.Literal("1")),
                                Condition.Simple(ColumnRef(columnName = "b"), "=", SqlValue.Literal("2")),
                            ),
                        ),
                        Condition.Simple(ColumnRef(columnName = "c"), "=", SqlValue.Literal("3")),
                    ),
                ),
            ),
        )
        val code = JavaCodeGenerator.generate(result)
        // 嵌套子组 → .and(w2 -> w2 ...)；OR 组第二子条件前 .or()（嵌套内统一 8 空格缩进）
        assertTrue(
            code,
            code.contains(".nested(w -> w\n        .and(w2 -> w2\n        .eq(\"a\", 1)\n        .eq(\"b\", 2))\n        .or()\n        .eq(\"c\", 3))"),
        )
    }

    // ------------------------------------------------------------------
    // Lambda 模式（T-M36.2）
    // ------------------------------------------------------------------

    @Test
    fun `lambda模式_列映射为Entity方法引用`() {
        val result = SqlReverseResult(
            tableName = "user",
            selectColumns = listOf(SelectColumn("id"), SelectColumn("name")),
            conditions = listOf(
                Condition.Simple(ColumnRef(columnName = "name"), "=", SqlValue.Literal("'张三'", "张三")),
                Condition.Unary(ColumnRef(columnName = "deleted"), "IS NULL"),
                Condition.Like(ColumnRef(columnName = "nick_name"), negated = false, SqlValue.Literal("'%张%'", "张"), LikePosition.ANYWHERE),
            ),
            orderBy = listOf(OrderByItem(ColumnRef(columnName = "create_time"), asc = false)),
        )
        val entity = JavaCodeGenerator.EntityTarget(
            className = "User",
            columnToField = mapOf(
                "id" to "id",
                "name" to "name",
                "deleted" to "deleted",
                "nick_name" to "nickName",
                "create_time" to "createTime",
            ),
        )
        val code = JavaCodeGenerator.generate(result, entity)
        assertTrue(code, code.contains("new LambdaQueryWrapper<User>()"))
        assertTrue(code, code.contains(".select(User::getId, User::getName)"))
        assertTrue(code, code.contains(".eq(User::getName, \"张三\")"))
        assertTrue(code, code.contains(".isNull(User::getDeleted)"))
        assertTrue(code, code.contains(".like(User::getNickName, \"张\")"))
        assertTrue(code, code.contains(".orderByDesc(User::getCreateTime)"))
        assertTrue(code, code.contains("// 实体类 User 由表名 user 匹配"))
    }

    @Test
    fun `lambda模式_未映射列回落字符串并注释警告`() {
        val result = SqlReverseResult(
            tableName = "user",
            selectColumns = null,
            conditions = listOf(
                Condition.Simple(ColumnRef(columnName = "name"), "=", SqlValue.Literal("'x'", "x")),
                Condition.Simple(ColumnRef(columnName = "extra_col"), "=", SqlValue.Literal("1")),
            ),
        )
        val entity = JavaCodeGenerator.EntityTarget(className = "User", columnToField = mapOf("name" to "name"))
        val code = JavaCodeGenerator.generate(result, entity)
        assertTrue(code, code.contains(".eq(User::getName, \"x\")"))
        assertTrue(code, code.contains(".eq(\"extra_col\", 1)"))
        assertTrue(code, code.contains("// 注意：以下列未在实体中找到对应字段，已回落字符串列名：extra_col"))
    }

    @Test
    fun `lambda模式_组内列同样映射`() {
        val result = SqlReverseResult(
            tableName = "user",
            selectColumns = null,
            conditions = listOf(
                Condition.Group(
                    Connector.OR,
                    listOf(
                        Condition.Simple(ColumnRef(columnName = "a"), "=", SqlValue.Literal("1")),
                        Condition.Simple(ColumnRef(columnName = "b"), "=", SqlValue.Literal("2")),
                    ),
                ),
            ),
        )
        val entity = JavaCodeGenerator.EntityTarget(
            className = "User",
            columnToField = mapOf("a" to "a", "b" to "b"),
        )
        val code = JavaCodeGenerator.generate(result, entity)
        assertTrue(code, code.contains(".nested(w -> w\n        .eq(User::getA, 1)\n        .or()\n        .eq(User::getB, 2))"))
    }

    // ------------------------------------------------------------------
    // MPJ 形态（T-M36.3）
    // ------------------------------------------------------------------

    @Test
    fun `MPJ形态_生成MPJLambdaWrapper与lambda列`() {
        val result = SqlReverseResult(
            tableName = "user",
            selectColumns = null,
            conditions = listOf(
                Condition.Simple(ColumnRef(columnName = "name"), "=", SqlValue.Literal("'x'", "x")),
            ),
        )
        val entity = JavaCodeGenerator.EntityTarget(className = "User", columnToField = mapOf("name" to "name"))
        val code = JavaCodeGenerator.generate(result, entity, GeneratedWrapper.MPJ_LAMBDA)
        assertTrue(code, code.contains("new MPJLambdaWrapper<User>()"))
        assertTrue(code, code.contains(".eq(User::getName, \"x\")"))
        assertTrue(code, code.contains("// SQL → MyBatis-Plus QueryWrapper 反向生成") && code.contains("（MPJ 形态）"))
        assertTrue(code, code.contains("// 需引入 MyBatis-Plus-Join（MPJ）依赖"))
    }

    @Test
    fun `MPJ形态_无实体恒QueryWrapper`() {
        val result = SqlReverseResult(tableName = "user", selectColumns = null)
        val code = JavaCodeGenerator.generate(result, null, GeneratedWrapper.MPJ_LAMBDA)
        assertTrue(code, code.contains("new QueryWrapper<User>()"))
        assertTrue(code, !code.contains("MPJLambdaWrapper"))
    }

    // ------------------------------------------------------------------
    // Wrappers 工厂形态（T-M36.6）
    // ------------------------------------------------------------------

    @Test
    fun `Wrappers形态_有实体生成lambdaQuery工厂`() {
        val result = SqlReverseResult(
            tableName = "user",
            selectColumns = null,
            conditions = listOf(
                Condition.Simple(ColumnRef(columnName = "name"), "=", SqlValue.Literal("'x'", "x")),
            ),
        )
        val entity = JavaCodeGenerator.EntityTarget(className = "User", columnToField = mapOf("name" to "name"))
        val code = JavaCodeGenerator.generate(result, entity, GeneratedWrapper.WRAPPERS_LAMBDA)
        assertTrue(code, code.contains("Wrappers.<User>lambdaQuery()"))
        assertTrue(code, code.contains(".eq(User::getName, \"x\")"))
        assertTrue(code, code.contains("// SQL → MyBatis-Plus QueryWrapper 反向生成") && code.contains("（Wrappers 工厂形态）"))
    }

    @Test
    fun `Wrappers形态_无实体生成query工厂字符串列`() {
        val result = SqlReverseResult(
            tableName = "user",
            selectColumns = null,
            conditions = listOf(
                Condition.Simple(ColumnRef(columnName = "name"), "=", SqlValue.Literal("'x'", "x")),
            ),
        )
        val code = JavaCodeGenerator.generate(result, null, GeneratedWrapper.WRAPPERS_QUERY)
        assertTrue(code, code.contains("Wrappers.<User>query()"))
        assertTrue(code, code.contains(".eq(\"name\", \"x\")"))
        assertTrue(code, !code.contains("LambdaQueryWrapper"))
    }

    // ------------------------------------------------------------------
    // 增删改查（T-M36.7）
    // ------------------------------------------------------------------

    @Test
    fun `UPDATE_字符串形态生成UpdateWrapper链`() {
        val result = SqlReverseResult(
            tableName = "user",
            kind = ReverseStatementKind.UPDATE,
            conditions = listOf(Condition.Simple(ColumnRef(columnName = "id"), "=", SqlValue.Literal("1"))),
            sets = listOf(
                ReverseSet("name", SqlValue.Literal("'李四'", "李四")),
                ReverseSet("update_time", null, rawSql = "update_time = NOW()"),
            ),
        )
        val code = JavaCodeGenerator.generate(result, null, GeneratedWrapper.QUERY)
        assertTrue(code, code.contains("new UpdateWrapper<User>()"))
        assertTrue(code, code.contains(".set(\"name\", \"李四\")"))
        assertTrue(code, code.contains(".setSql(\"update_time = NOW()\")"))
        assertTrue(code, code.contains(".eq(\"id\", 1)"))
    }

    @Test
    fun `UPDATE_lambda形态生成LambdaUpdateWrapper`() {
        val result = SqlReverseResult(
            tableName = "user",
            kind = ReverseStatementKind.UPDATE,
            conditions = listOf(Condition.Simple(ColumnRef(columnName = "id"), "=", SqlValue.Literal("1"))),
            sets = listOf(ReverseSet("name", SqlValue.Literal("'李四'", "李四"))),
        )
        val entity = JavaCodeGenerator.EntityTarget(className = "User", columnToField = mapOf("id" to "id", "name" to "name"))
        val code = JavaCodeGenerator.generate(result, entity, GeneratedWrapper.LAMBDA)
        assertTrue(code, code.contains("new LambdaUpdateWrapper<User>()"))
        assertTrue(code, code.contains(".set(User::getName, \"李四\")"))
        assertTrue(code, code.contains(".eq(User::getId, 1)"))
    }

    @Test
    fun `UPDATE_无WHERE_全表更新警告注释`() {
        val result = SqlReverseResult(
            tableName = "user",
            kind = ReverseStatementKind.UPDATE,
            sets = listOf(ReverseSet("name", SqlValue.Literal("'x'", "x"))),
        )
        val code = JavaCodeGenerator.generate(result, null, GeneratedWrapper.QUERY)
        assertTrue(code, code.contains("⚠ [BlockAttack] 无 WHERE 条件，将更新全表数据"))
    }

    @Test
    fun `DELETE_生成条件wrapper`() {
        val result = SqlReverseResult(
            tableName = "user",
            kind = ReverseStatementKind.DELETE,
            conditions = listOf(Condition.Simple(ColumnRef(columnName = "id"), "=", SqlValue.Literal("1"))),
        )
        val entity = JavaCodeGenerator.EntityTarget(className = "User", columnToField = mapOf("id" to "id"))
        val code = JavaCodeGenerator.generate(result, entity, GeneratedWrapper.LAMBDA)
        assertTrue(code, code.contains("new LambdaQueryWrapper<User>()"))
        assertTrue(code, code.contains(".eq(User::getId, 1)"))
    }

    // ------------------------------------------------------------------
    // 多表删除（DELETE_JOIN → MPJ DeleteJoinWrapper）
    // ------------------------------------------------------------------

    /** 多表删除结果样本：DELETE t, t1 FROM user t LEFT JOIN address t1 ON t1.user_id = t.id WHERE t.status = 1。 */
    private fun multiTableDeleteResult() = SqlReverseResult(
        tableName = "user",
        kind = ReverseStatementKind.DELETE,
        deleteTargets = listOf("t", "t1"),
        conditions = listOf(
            Condition.Simple(ColumnRef(tableAlias = "t", columnName = "status"), "=", SqlValue.Literal("1")),
        ),
        joins = listOf(
            ReverseJoin(
                type = cn.song.mybatisplus.visualizer.model.JoinType.LEFT,
                tableName = "address",
                alias = "t1",
                onConditions = listOf(
                    Condition.ColumnComparison(
                        ColumnRef(tableAlias = "t1", columnName = "user_id"),
                        ColumnRef(tableAlias = "t", columnName = "id"),
                    ),
                ),
            ),
        ),
    )

    @Test
    fun `DELETE_多表_生成DeleteJoinWrapper与join链`() {
        val main = JavaCodeGenerator.EntityTarget(
            className = "User",
            columnToField = mapOf("id" to "id", "status" to "status"),
            subEntities = mapOf(
                "t1" to JavaCodeGenerator.EntityTarget(
                    className = "Address",
                    columnToField = mapOf("user_id" to "userId"),
                ),
            ),
        )
        val code = JavaCodeGenerator.generate(multiTableDeleteResult(), main, GeneratedWrapper.MPJ_LAMBDA)
        assertTrue(code, code.contains("new DeleteJoinWrapper<>(User.class)"))
        assertTrue(code, code.contains(".delete(\"t\", \"t1\")"))
        assertTrue(code, code.contains(".leftJoin(Address.class, Address::getUserId, User::getId)"))
        assertTrue(code, code.contains(".eq(User::getStatus, 1)"))
        assertTrue(code, code.contains("（MPJ 形态）"))
    }

    @Test
    fun `DELETE_多表_副实体缺失_报错`() {
        // 1.5.6 实证：DeleteJoinWrapper 无 leftJoin(String)（仅 QueryJoin lambda 形态）——
        // 副实体缺失无法生成合法 join 链，明确报错优于生成编译不过的代码
        val result = multiTableDeleteResult()
        val main = JavaCodeGenerator.EntityTarget(className = "User", columnToField = mapOf("id" to "id", "status" to "status"))
        val ex = try {
            JavaCodeGenerator.generate(result, main, GeneratedWrapper.MPJ_LAMBDA)
            throw AssertionError("expected SqlReverseException")
        } catch (e: SqlReverseException) {
            e
        }
        assertTrue(ex.detail, ex.detail.contains("无法映射为 lambda join"))
    }

    @Test
    fun `DELETE_多表_主实体不可用_报错`() {
        val ex = try {
            JavaCodeGenerator.generate(multiTableDeleteResult(), null, GeneratedWrapper.QUERY)
            throw AssertionError("expected SqlReverseException")
        } catch (e: SqlReverseException) {
            e
        }
        assertEquals(SqlReverseParser.KEY_UNSUPPORTED, ex.key)
        assertTrue(ex.detail.contains("多表删除需要实体类上下文"))
    }

    @Test
    fun `DELETE_多表带Mapper_生成deleteJoin调用`() {
        val main = JavaCodeGenerator.EntityTarget(
            className = "User",
            columnToField = mapOf("id" to "id", "status" to "status"),
            subEntities = mapOf(
                "t1" to JavaCodeGenerator.EntityTarget(
                    className = "Address",
                    columnToField = mapOf("user_id" to "userId"),
                ),
            ),
        )
        val code = JavaCodeGenerator.generate(
            multiTableDeleteResult(), main, GeneratedWrapper.MPJ_LAMBDA,
            JavaCodeGenerator.InvocationTarget("UserMapper", mpj = true),
        )
        assertTrue(code, code.contains("DeleteJoinWrapper<User> wrapper = new DeleteJoinWrapper<>(User.class)"))
        assertTrue(code, code.contains(".delete(\"t\", \"t1\")"))
        assertTrue(code, code.contains("userMapper.deleteJoin(wrapper);"))
    }

    @Test
    fun `DELETE_多表带Service_生成deleteJoin调用`() {
        val main = JavaCodeGenerator.EntityTarget(
            className = "User",
            columnToField = mapOf("id" to "id", "status" to "status"),
            subEntities = mapOf(
                "t1" to JavaCodeGenerator.EntityTarget(
                    className = "Address",
                    columnToField = mapOf("user_id" to "userId"),
                ),
            ),
        )
        val code = JavaCodeGenerator.generate(
            multiTableDeleteResult(), main, GeneratedWrapper.MPJ_LAMBDA,
            JavaCodeGenerator.InvocationTarget("UserService", mpj = true),
        )
        assertTrue(code, code.contains("DeleteJoinWrapper<User> wrapper = new DeleteJoinWrapper<>(User.class)"))
        assertTrue(code, code.contains(".delete(\"t\", \"t1\")"))
        assertTrue(code, code.contains("userService.deleteJoin(wrapper);"))
    }

    @Test
    fun `DELETE_多表_无调用类纯表达式分号结尾`() {
        val main = JavaCodeGenerator.EntityTarget(
            className = "User",
            columnToField = mapOf("id" to "id", "status" to "status"),
            subEntities = mapOf(
                "t1" to JavaCodeGenerator.EntityTarget(
                    className = "Address",
                    columnToField = mapOf("user_id" to "userId"),
                ),
            ),
        )
        val code = JavaCodeGenerator.generate(multiTableDeleteResult(), main, GeneratedWrapper.MPJ_LAMBDA)
        assertTrue(code, code.endsWith(".eq(User::getStatus, 1);"))
    }

    @Test
    fun `INSERT_生成实体setter链`() {
        val result = SqlReverseResult(
            tableName = "user",
            kind = ReverseStatementKind.INSERT,
            insertColumns = listOf("name", "age"),
            insertValues = listOf(
                listOf(SqlValue.Literal("'张三'", "张三"), SqlValue.Literal("18")),
            ),
        )
        val code = JavaCodeGenerator.generate(result, null, GeneratedWrapper.QUERY)
        assertTrue(code, code.contains("new User().setName(\"张三\")\n    .setAge(18);"))
    }

    @Test
    fun `INSERT_多行_逐行setter语句`() {
        val result = SqlReverseResult(
            tableName = "user",
            kind = ReverseStatementKind.INSERT,
            insertColumns = listOf("name", "age"),
            insertValues = listOf(
                listOf(SqlValue.Literal("'张三'", "张三"), SqlValue.Literal("18")),
                listOf(SqlValue.Literal("'李四'", "李四"), SqlValue.Literal("20")),
            ),
        )
        val code = JavaCodeGenerator.generate(result, null, GeneratedWrapper.QUERY)
        assertTrue(
            code,
            code.contains(
                "new User().setName(\"张三\")\n    .setAge(18);\nnew User().setName(\"李四\")\n    .setAge(20);",
            ),
        )
    }

    @Test
    fun `INSERT_多行_lambda实体字段映射`() {
        val result = SqlReverseResult(
            tableName = "user",
            kind = ReverseStatementKind.INSERT,
            insertColumns = listOf("name", "nick_name"),
            insertValues = listOf(
                listOf(SqlValue.Literal("'a'", "a"), SqlValue.Literal("'b'", "b")),
                listOf(SqlValue.Literal("'c'", "c"), SqlValue.Literal("'d'", "d")),
            ),
        )
        val entity = JavaCodeGenerator.EntityTarget(
            className = "User",
            columnToField = mapOf("name" to "name", "nick_name" to "nickName"),
        )
        val code = JavaCodeGenerator.generate(result, entity, GeneratedWrapper.LAMBDA)
        // 每行实体独立语句；多列 setter 换行（对齐单行 INSERT 既有格式）
        assertTrue(
            code,
            code.contains("new User().setName(\"a\")\n    .setNickName(\"b\");\nnew User().setName(\"c\")\n    .setNickName(\"d\");"),
        )
    }

    @Test
    fun `INSERT_多行带Service_生成saveBatch`() {
        val result = SqlReverseResult(
            tableName = "user",
            kind = ReverseStatementKind.INSERT,
            insertColumns = listOf("name", "age"),
            insertValues = listOf(
                listOf(SqlValue.Literal("'张三'", "张三"), SqlValue.Literal("18")),
                listOf(SqlValue.Literal("'李四'", "李四"), SqlValue.Literal("20")),
            ),
        )
        val code = JavaCodeGenerator.generate(
            result, null, GeneratedWrapper.QUERY,
            JavaCodeGenerator.InvocationTarget("UserService"),
        )
        assertTrue(
            code,
            code.contains(
                "User user1 = new User().setName(\"张三\")\n    .setAge(18);\n" +
                    "User user2 = new User().setName(\"李四\")\n    .setAge(20);\n\n" +
                    "userService.saveBatch(Arrays.asList(user1, user2));",
            ),
        )
    }

    @Test
    fun `INSERT_多行带Mapper_逐条insert`() {
        val result = SqlReverseResult(
            tableName = "user",
            kind = ReverseStatementKind.INSERT,
            insertColumns = listOf("name"),
            insertValues = listOf(
                listOf(SqlValue.Literal("'a'", "a")),
                listOf(SqlValue.Literal("'b'", "b")),
                listOf(SqlValue.Literal("'c'", "c")),
            ),
        )
        val code = JavaCodeGenerator.generate(
            result, null, GeneratedWrapper.QUERY,
            JavaCodeGenerator.InvocationTarget("UserMapper"),
        )
        assertTrue(code, code.contains("User user1 = new User().setName(\"a\");\nUser user2 = new User().setName(\"b\");\nUser user3 = new User().setName(\"c\");\n\n"))
        assertTrue(code, code.contains("userMapper.insert(user1);\nuserMapper.insert(user2);\nuserMapper.insert(user3);"))
    }

    // ------------------------------------------------------------------
    // 调用类 / VO（T-M36.7）
    // ------------------------------------------------------------------

    @Test
    fun `SELECT带Service调用_生成完整语句`() {
        val result = SqlReverseResult(
            tableName = "user",
            selectColumns = null,
            conditions = listOf(Condition.Simple(ColumnRef(columnName = "name"), "=", SqlValue.Literal("'x'", "x"))),
        )
        val entity = JavaCodeGenerator.EntityTarget(className = "User", columnToField = mapOf("name" to "name"))
        val code = JavaCodeGenerator.generate(
            result, entity, GeneratedWrapper.LAMBDA,
            JavaCodeGenerator.InvocationTarget("UserService"),
        )
        assertTrue(code, code.contains("LambdaQueryWrapper<User> wrapper = new LambdaQueryWrapper<User>()"))
        assertTrue(code, code.contains("List<User> list = userService.list(wrapper);"))
    }

    @Test
    fun `SELECT带Mapper和VO_生成selectJoinList`() {
        val result = SqlReverseResult(
            tableName = "user",
            selectColumns = null,
            conditions = listOf(Condition.Simple(ColumnRef(columnName = "name"), "=", SqlValue.Literal("'x'", "x"))),
        )
        val entity = JavaCodeGenerator.EntityTarget(className = "User", columnToField = mapOf("name" to "name"))
        val code = JavaCodeGenerator.generate(
            result, entity, GeneratedWrapper.LAMBDA,
            JavaCodeGenerator.InvocationTarget("UserMapper", voClass = "UserVo", mpj = true),
        )
        // MPJ 1.5.6 实证：selectVoList 不存在，真实方法 = selectJoinList(Vo.class, wrapper)
        assertTrue(code, code.contains("List<UserVo> list = userMapper.selectJoinList(UserVo.class, wrapper);"))
        // VO 场景强制 MPJ wrapper（selectJoinList 参数类型为 MPJBaseJoin，LambdaQueryWrapper 编译不过）
        assertTrue(code, code.contains("new MPJLambdaWrapper<User>()"))
        assertTrue(code, code.contains("// VO 查询需 MyBatis-Plus-Join（MPJ）依赖"))
    }

    @Test
    fun `UPDATE带Mapper调用_生成update调用`() {
        val result = SqlReverseResult(
            tableName = "user",
            kind = ReverseStatementKind.UPDATE,
            conditions = listOf(Condition.Simple(ColumnRef(columnName = "id"), "=", SqlValue.Literal("1"))),
            sets = listOf(ReverseSet("name", SqlValue.Literal("'x'", "x"))),
        )
        val code = JavaCodeGenerator.generate(
            result, null, GeneratedWrapper.QUERY,
            JavaCodeGenerator.InvocationTarget("UserMapper"),
        )
        assertTrue(code, code.contains("UpdateWrapper<User> wrapper = new UpdateWrapper<User>()"))
        assertTrue(code, code.contains("userMapper.update(null, wrapper);"))
    }

    @Test
    fun `INSERT带Service调用_生成save调用`() {
        val result = SqlReverseResult(
            tableName = "user",
            kind = ReverseStatementKind.INSERT,
            insertColumns = listOf("name"),
            insertValues = listOf(listOf(SqlValue.Literal("'张三'", "张三"))),
        )
        val code = JavaCodeGenerator.generate(
            result, null, GeneratedWrapper.QUERY,
            JavaCodeGenerator.InvocationTarget("UserService"),
        )
        assertTrue(code, code.contains("User user = new User().setName(\"张三\");"))
        assertTrue(code, code.contains("userService.save(user);"))
    }

    // ------------------------------------------------------------------
    // 调用类特殊值（T-M42：this=service 内、baseMapper=ServiceImpl 字段）
    // ------------------------------------------------------------------

    /** SELECT 单表样本（条件 + 实体映射）。 */
    private fun specialValueSelectResult() = SqlReverseResult(
        tableName = "user",
        selectColumns = null,
        conditions = listOf(Condition.Simple(ColumnRef(columnName = "name"), "=", SqlValue.Literal("'x'", "x"))),
    )

    @Test
    fun `this调用_SELECT生成list`() {
        val code = JavaCodeGenerator.generate(
            specialValueSelectResult(), null, GeneratedWrapper.QUERY,
            JavaCodeGenerator.InvocationTarget("this"),
        )
        assertTrue(code, code.contains("List<User> list = this.list(wrapper);"))
    }

    @Test
    fun `this调用_分页生成page`() {
        val result = SqlReverseResult(
            tableName = "user",
            limit = LimitClause(offset = SqlValue.Placeholder("?"), count = SqlValue.Placeholder("?")),
        )
        val code = JavaCodeGenerator.generate(
            result, null, GeneratedWrapper.QUERY,
            JavaCodeGenerator.InvocationTarget("this"),
        )
        assertTrue(code, code.contains("Page<User> page = this.page(new Page<>(1, 10), wrapper);"))
    }

    @Test
    fun `this调用_UPDATE生成update`() {
        val result = SqlReverseResult(
            tableName = "user",
            kind = ReverseStatementKind.UPDATE,
            conditions = listOf(Condition.Simple(ColumnRef(columnName = "id"), "=", SqlValue.Literal("1"))),
            sets = listOf(ReverseSet("name", SqlValue.Literal("'x'", "x"))),
        )
        val code = JavaCodeGenerator.generate(
            result, null, GeneratedWrapper.QUERY,
            JavaCodeGenerator.InvocationTarget("this"),
        )
        assertTrue(code, code.contains("this.update(wrapper);"))
    }

    @Test
    fun `this调用_DELETE生成remove`() {
        val result = SqlReverseResult(
            tableName = "user",
            kind = ReverseStatementKind.DELETE,
            conditions = listOf(Condition.Simple(ColumnRef(columnName = "id"), "=", SqlValue.Literal("1"))),
        )
        val code = JavaCodeGenerator.generate(
            result, null, GeneratedWrapper.QUERY,
            JavaCodeGenerator.InvocationTarget("this"),
        )
        assertTrue(code, code.contains("this.remove(wrapper);"))
    }

    @Test
    fun `this调用_INSERT单行生成save`() {
        val result = SqlReverseResult(
            tableName = "user",
            kind = ReverseStatementKind.INSERT,
            insertColumns = listOf("name"),
            insertValues = listOf(listOf(SqlValue.Literal("'张三'", "张三"))),
        )
        val code = JavaCodeGenerator.generate(
            result, null, GeneratedWrapper.QUERY,
            JavaCodeGenerator.InvocationTarget("this"),
        )
        assertTrue(code, code.contains("User user = new User().setName(\"张三\");"))
        assertTrue(code, code.contains("this.save(user);"))
    }

    @Test
    fun `this调用_INSERT多行生成saveBatch`() {
        val result = SqlReverseResult(
            tableName = "user",
            kind = ReverseStatementKind.INSERT,
            insertColumns = listOf("name"),
            insertValues = listOf(
                listOf(SqlValue.Literal("'a'", "a")),
                listOf(SqlValue.Literal("'b'", "b")),
            ),
        )
        val code = JavaCodeGenerator.generate(
            result, null, GeneratedWrapper.QUERY,
            JavaCodeGenerator.InvocationTarget("this"),
        )
        assertTrue(code, code.contains("this.saveBatch(Arrays.asList(user1, user2));"))
    }

    @Test
    fun `baseMapper调用_SELECT生成selectList`() {
        val code = JavaCodeGenerator.generate(
            specialValueSelectResult(), null, GeneratedWrapper.QUERY,
            JavaCodeGenerator.InvocationTarget("baseMapper"),
        )
        assertTrue(code, code.contains("List<User> list = baseMapper.selectList(wrapper);"))
    }

    @Test
    fun `baseMapper调用_分页生成selectPage`() {
        val result = SqlReverseResult(
            tableName = "user",
            limit = LimitClause(count = SqlValue.Literal("10")),
        )
        val code = JavaCodeGenerator.generate(
            result, null, GeneratedWrapper.QUERY,
            JavaCodeGenerator.InvocationTarget("baseMapper"),
        )
        assertTrue(code, code.contains("Page<User> page = baseMapper.selectPage(new Page<>(1, 10), wrapper);"))
    }

    @Test
    fun `baseMapper调用_UPDATE生成update`() {
        val result = SqlReverseResult(
            tableName = "user",
            kind = ReverseStatementKind.UPDATE,
            conditions = listOf(Condition.Simple(ColumnRef(columnName = "id"), "=", SqlValue.Literal("1"))),
            sets = listOf(ReverseSet("name", SqlValue.Literal("'x'", "x"))),
        )
        val code = JavaCodeGenerator.generate(
            result, null, GeneratedWrapper.QUERY,
            JavaCodeGenerator.InvocationTarget("baseMapper"),
        )
        assertTrue(code, code.contains("baseMapper.update(null, wrapper);"))
    }

    @Test
    fun `baseMapper调用_DELETE生成delete`() {
        val result = SqlReverseResult(
            tableName = "user",
            kind = ReverseStatementKind.DELETE,
            conditions = listOf(Condition.Simple(ColumnRef(columnName = "id"), "=", SqlValue.Literal("1"))),
        )
        val code = JavaCodeGenerator.generate(
            result, null, GeneratedWrapper.QUERY,
            JavaCodeGenerator.InvocationTarget("baseMapper"),
        )
        assertTrue(code, code.contains("baseMapper.delete(wrapper);"))
    }

    @Test
    fun `baseMapper调用_INSERT单行生成insert`() {
        val result = SqlReverseResult(
            tableName = "user",
            kind = ReverseStatementKind.INSERT,
            insertColumns = listOf("name"),
            insertValues = listOf(listOf(SqlValue.Literal("'张三'", "张三"))),
        )
        val code = JavaCodeGenerator.generate(
            result, null, GeneratedWrapper.QUERY,
            JavaCodeGenerator.InvocationTarget("baseMapper"),
        )
        assertTrue(code, code.contains("baseMapper.insert(user);"))
    }

    @Test
    fun `特殊调用值_多表JOIN不报错生成普通调用`() {
        // mpj=true 放开（invocationHasMpj 对特殊值直出 true）——JOIN 场景不再报「无 MPJ 能力」，
        // 单表形态方法（list/selectList）正常生成
        val result = SqlReverseResult(
            tableName = "user",
            selectColumns = null,
            conditions = listOf(
                Condition.Simple(ColumnRef(tableAlias = "t", columnName = "status"), "=", SqlValue.Literal("1")),
            ),
            joins = listOf(
                ReverseJoin(
                    type = cn.song.mybatisplus.visualizer.model.JoinType.LEFT,
                    tableName = "address",
                    alias = "t1",
                    onConditions = listOf(
                        Condition.ColumnComparison(
                            ColumnRef(tableAlias = "t1", columnName = "user_id"),
                            ColumnRef(tableAlias = "t", columnName = "id"),
                        ),
                    ),
                ),
            ),
        )
        val main = JavaCodeGenerator.EntityTarget(
            className = "User",
            columnToField = mapOf("id" to "id", "status" to "status"),
            subEntities = mapOf(
                "t1" to JavaCodeGenerator.EntityTarget(
                    className = "Address",
                    columnToField = mapOf("user_id" to "userId"),
                ),
            ),
        )
        val thisCode = JavaCodeGenerator.generate(
            result, main, GeneratedWrapper.MPJ_LAMBDA,
            JavaCodeGenerator.InvocationTarget("this", mpj = true),
        )
        assertTrue(thisCode, thisCode.contains("List<User> list = this.list(wrapper);"))
        val mapperCode = JavaCodeGenerator.generate(
            result, main, GeneratedWrapper.MPJ_LAMBDA,
            JavaCodeGenerator.InvocationTarget("baseMapper", mpj = true),
        )
        assertTrue(mapperCode, mapperCode.contains("List<User> list = baseMapper.selectList(wrapper);"))
    }

    @Test
    fun `特殊调用值_VO查询生成selectJoinList`() {
        // VO 查询（mpj=true）：this/baseMapper 均生成 MPJ 的 selectJoinList(Vo.class, wrapper)
        val result = specialValueSelectResult()
        val thisCode = JavaCodeGenerator.generate(
            result, null, GeneratedWrapper.QUERY,
            JavaCodeGenerator.InvocationTarget("this", voClass = "UserVo", mpj = true),
        )
        assertTrue(thisCode, thisCode.contains("List<UserVo> list = this.selectJoinList(UserVo.class, wrapper);"))
        val mapperCode = JavaCodeGenerator.generate(
            result, null, GeneratedWrapper.QUERY,
            JavaCodeGenerator.InvocationTarget("baseMapper", voClass = "UserVo", mpj = true),
        )
        assertTrue(mapperCode, mapperCode.contains("List<UserVo> list = baseMapper.selectJoinList(UserVo.class, wrapper);"))
    }

    // ------------------------------------------------------------------
    // 多表 JOIN（T-M37）
    // ------------------------------------------------------------------

    @Test
    fun `多表JOIN_生成MPJLambdaWrapper与三参leftJoin`() {
        val result = SqlReverseResult(
            tableName = "user",
            selectColumns = null,
            conditions = listOf(
                Condition.Simple(ColumnRef(tableAlias = "t", columnName = "status"), "=", SqlValue.Literal("1")),
                Condition.Simple(ColumnRef(tableAlias = "t1", columnName = "city"), "=", SqlValue.Literal("'北京'", "北京")),
            ),
            joins = listOf(
                ReverseJoin(
                    type = cn.song.mybatisplus.visualizer.model.JoinType.LEFT,
                    tableName = "address",
                    alias = "t1",
                    onConditions = listOf(
                        Condition.ColumnComparison(
                            ColumnRef(tableAlias = "t1", columnName = "user_id"),
                            ColumnRef(tableAlias = "t", columnName = "id"),
                        ),
                    ),
                ),
            ),
        )
        val main = JavaCodeGenerator.EntityTarget(
            className = "User",
            columnToField = mapOf("id" to "id", "status" to "status"),
            subEntities = mapOf(
                "t1" to JavaCodeGenerator.EntityTarget(
                    className = "Address",
                    columnToField = mapOf("user_id" to "userId", "city" to "city"),
                ),
            ),
        )
        val code = JavaCodeGenerator.generate(result, main, GeneratedWrapper.MPJ_LAMBDA)
        assertTrue(code, code.contains("new MPJLambdaWrapper<User>()"))
        assertTrue(code, code.contains(".selectAll(User.class)"))
        assertTrue(code, code.contains(".leftJoin(Address.class, Address::getUserId, User::getId)"))
        assertTrue(code, code.contains(".eq(User::getStatus, 1)"))
        assertTrue(code, code.contains(".eq(Address::getCity, \"北京\")"))
    }

    @Test
    fun `多表JOIN_ON多条件_回落raw join字符串`() {
        val result = SqlReverseResult(
            tableName = "a",
            selectColumns = null,
            joins = listOf(
                ReverseJoin(
                    type = cn.song.mybatisplus.visualizer.model.JoinType.LEFT,
                    tableName = "b",
                    alias = "t1",
                    onConditions = listOf(
                        Condition.ColumnComparison(ColumnRef(tableAlias = "t1", columnName = "a_id"), ColumnRef(tableAlias = "t", columnName = "id")),
                        Condition.Simple(ColumnRef(tableAlias = "t1", columnName = "type"), "=", SqlValue.Literal("1")),
                    ),
                    rawOn = "t1.a_id = t.id AND t1.type = 1",
                ),
            ),
        )
        val main = JavaCodeGenerator.EntityTarget(
            className = "A",
            columnToField = mapOf("id" to "id"),
            subEntities = mapOf("t1" to JavaCodeGenerator.EntityTarget("B", mapOf("a_id" to "aId", "type" to "type"))),
        )
        val code = JavaCodeGenerator.generate(result, main, GeneratedWrapper.MPJ_LAMBDA)
        // ON 多条件（join key + ext 条件）→ 4 参 lambda leftJoin(Class, left, right, ext -> ext.eq(...))
        assertTrue(code, code.contains("new MPJLambdaWrapper<A>()"))
        assertTrue(code, code.contains(".leftJoin(B.class, B::getAId, A::getId,"))
        assertTrue(code, code.contains("ext -> ext.eq(B::getType, 1)"))
    }

    @Test
    fun `多表JOIN_主表无实体生成MPJQueryWrapper`() {
        val result = SqlReverseResult(
            tableName = "user",
            selectColumns = null,
            joins = listOf(
                ReverseJoin(
                    type = cn.song.mybatisplus.visualizer.model.JoinType.LEFT,
                    tableName = "address",
                    alias = "t1",
                    onConditions = listOf(
                        Condition.ColumnComparison(ColumnRef(tableAlias = "t1", columnName = "user_id"), ColumnRef(tableAlias = "t", columnName = "id")),
                    ),
                    rawOn = "t1.user_id = t.id",
                ),
            ),
        )
        val code = JavaCodeGenerator.generate(result, null, GeneratedWrapper.MPJ_QUERY)
        assertTrue(code, code.contains("new MPJQueryWrapper<>(User.class)"))
        assertTrue(code, code.contains(".selectAll(User.class)"))
        assertTrue(code, code.contains(".leftJoin(\"address t1 ON t1.user_id = t.id\")"))
    }

    @Test
    fun `多表JOIN_未映射副表列回落带前缀字符串`() {
        val result = SqlReverseResult(
            tableName = "user",
            selectColumns = null,
            conditions = listOf(
                Condition.Simple(ColumnRef(tableAlias = "t1", columnName = "extra_col"), "=", SqlValue.Literal("1")),
            ),
            joins = listOf(
                ReverseJoin(
                    type = cn.song.mybatisplus.visualizer.model.JoinType.LEFT,
                    tableName = "address",
                    alias = "t1",
                    onConditions = listOf(
                        Condition.ColumnComparison(ColumnRef(tableAlias = "t1", columnName = "user_id"), ColumnRef(tableAlias = "t", columnName = "id")),
                    ),
                ),
            ),
        )
        val main = JavaCodeGenerator.EntityTarget(
            className = "User",
            columnToField = mapOf("id" to "id"),
            subEntities = mapOf("t1" to JavaCodeGenerator.EntityTarget("Address", mapOf("user_id" to "userId"))),
        )
        val code = JavaCodeGenerator.generate(result, main, GeneratedWrapper.MPJ_LAMBDA)
        assertTrue(code, code.contains(".eq(\"t1.extra_col\", 1)"))
    }

    @Test
    fun `多表JOIN_ON含副表逻辑删除_生成三参leftJoin`() {
        // 用户场景：ON 里带 MPJ 自动追加的副表 deleted = 0，仍应三参 lambda 化（MPJ 自动补，不当多条件拦截）
        val result = SqlReverseResult(
            tableName = "biz_bike_device_info",
            selectColumns = null,
            joins = listOf(
                ReverseJoin(
                    type = cn.song.mybatisplus.visualizer.model.JoinType.LEFT,
                    tableName = "biz_bike_device_product_info",
                    alias = "bikeDeviceProductInfoEntity",
                    onConditions = listOf(
                        Condition.ColumnComparison(
                            ColumnRef(tableAlias = "bikeDeviceProductInfoEntity", columnName = "id"),
                            ColumnRef(tableAlias = "t", columnName = "product_id"),
                        ),
                        Condition.Simple(
                            ColumnRef(tableAlias = "bikeDeviceProductInfoEntity", columnName = "deleted"),
                            "=", SqlValue.Literal("0"),
                        ),
                    ),
                ),
            ),
        )
        val main = JavaCodeGenerator.EntityTarget(
            className = "BikeDeviceInfoEntity",
            columnToField = mapOf("id" to "id", "product_id" to "productId"),
            subEntities = mapOf(
                "bikeDeviceProductInfoEntity" to JavaCodeGenerator.EntityTarget(
                    className = "BikeDeviceProductInfoEntity",
                    columnToField = mapOf("id" to "id", "deleted" to "deleted"),
                ),
            ),
        )
        val code = JavaCodeGenerator.generate(result, main, GeneratedWrapper.MPJ_LAMBDA)
        assertTrue(code, code.contains("new MPJLambdaWrapper<BikeDeviceInfoEntity>()"))
        assertTrue(
            code,
            code.contains(
                ".leftJoin(BikeDeviceProductInfoEntity.class, BikeDeviceProductInfoEntity::getId, BikeDeviceInfoEntity::getProductId)",
            ),
        )
    }

    @Test
    fun `多表JOIN_字符串形态_多行ON换行折叠转义`() {
        val result = SqlReverseResult(
            tableName = "a",
            selectColumns = null,
            joins = listOf(
                ReverseJoin(
                    type = cn.song.mybatisplus.visualizer.model.JoinType.LEFT,
                    tableName = "b",
                    alias = "t1",
                    onConditions = listOf(
                        Condition.ColumnComparison(ColumnRef(tableAlias = "t1", columnName = "a_id"), ColumnRef(tableAlias = "t", columnName = "id")),
                        Condition.Simple(ColumnRef(tableAlias = "t1", columnName = "type"), "=", SqlValue.Literal("1")),
                    ),
                    rawOn = "t1.a_id = t.id\n    AND t1.type = 1",
                ),
            ),
        )
        val main = JavaCodeGenerator.EntityTarget(
            className = "A",
            columnToField = mapOf("id" to "id"),
            subEntities = mapOf("t1" to JavaCodeGenerator.EntityTarget("B", mapOf("a_id" to "aId", "type" to "type"))),
        )
        val code = JavaCodeGenerator.generate(result, main, GeneratedWrapper.MPJ_LAMBDA)
        // 真过滤条件（type=1 非逻辑删除）→ 4 参 lambda leftJoin(Class, left, right, ext -> ext.eq(...))
        assertTrue(code, code.contains("new MPJLambdaWrapper<A>()"))
        assertTrue(code, code.contains(".leftJoin(B.class, B::getAId, A::getId,"))
        assertTrue(code, code.contains("ext -> ext.eq(B::getType, 1)"))
    }

    @Test
    fun `SELECT列带别名_生成selectAs`() {
        val result = SqlReverseResult(
            tableName = "user",
            selectColumns = listOf(SelectColumn("t.name", "productName"), SelectColumn("id")),
        )
        val code = JavaCodeGenerator.generate(result, JavaCodeGenerator.EntityTarget("User", mapOf("id" to "id", "name" to "name")), GeneratedWrapper.LAMBDA)
        assertTrue(code, code.contains(".selectAs(User::getName, \"productName\")"))
        assertTrue(code, code.contains(".select(User::getId)"))
    }

    @Test
    fun `SELECT列_主表别名下副表列_归属副实体`() {
        // 用户场景：t.model 主实体无该列，仅产品副实体有 → 归属副实体；主表有 name → 仍归属主实体（SQL 字面正确）
        val result = SqlReverseResult(
            tableName = "biz_bike_device_info",
            selectColumns = listOf(SelectColumn("t.name", "productName"), SelectColumn("t.model", "productModel")),
            joins = listOf(
                ReverseJoin(
                    type = cn.song.mybatisplus.visualizer.model.JoinType.LEFT,
                    tableName = "biz_bike_device_product_info",
                    alias = "bikeDeviceProductInfoEntity",
                    onConditions = listOf(
                        Condition.ColumnComparison(
                            ColumnRef(tableAlias = "bikeDeviceProductInfoEntity", columnName = "id"),
                            ColumnRef(tableAlias = "t", columnName = "product_id"),
                        ),
                    ),
                ),
            ),
        )
        val main = JavaCodeGenerator.EntityTarget(
            className = "BikeDeviceInfoEntity",
            columnToField = mapOf("id" to "id", "product_id" to "productId", "name" to "name"),
            subEntities = mapOf(
                "bikeDeviceProductInfoEntity" to JavaCodeGenerator.EntityTarget(
                    className = "BikeDeviceProductInfoEntity",
                    columnToField = mapOf("id" to "id", "model" to "model"),
                ),
            ),
        )
        val code = JavaCodeGenerator.generate(result, main, GeneratedWrapper.MPJ_LAMBDA)
        assertTrue(code, code.contains(".selectAs(BikeDeviceInfoEntity::getName, \"productName\")"))
        assertTrue(code, code.contains(".selectAs(BikeDeviceProductInfoEntity::getModel, \"productModel\")"))
        // t.model 已归属副实体 → 不再误报「未在实体中找到对应字段」
        assertTrue(code, !code.contains("已回落字符串列名"))
        assertTrue(code, !code.contains("t.model"))
    }

    @Test
    fun `SELECT列_选中VO_selectAs引用VO方法`() {
        // 用户场景：选了调用类 + VO 后，selectAs 第二参应为 VO 属性方法引用（对齐正向 selectAs(列, VO::getXxx)）
        val result = SqlReverseResult(
            tableName = "biz_bike_device_info",
            selectColumns = listOf(SelectColumn("t.name", "productName"), SelectColumn("t.model", "productModel")),
            joins = listOf(
                ReverseJoin(
                    type = cn.song.mybatisplus.visualizer.model.JoinType.LEFT,
                    tableName = "biz_bike_device_product_info",
                    alias = "bikeDeviceProductInfoEntity",
                    onConditions = listOf(
                        Condition.ColumnComparison(
                            ColumnRef(tableAlias = "bikeDeviceProductInfoEntity", columnName = "id"),
                            ColumnRef(tableAlias = "t", columnName = "product_id"),
                        ),
                    ),
                ),
            ),
        )
        val main = JavaCodeGenerator.EntityTarget(
            className = "BikeDeviceInfoEntity",
            columnToField = mapOf("id" to "id", "product_id" to "productId", "name" to "name"),
            subEntities = mapOf(
                "bikeDeviceProductInfoEntity" to JavaCodeGenerator.EntityTarget(
                    className = "BikeDeviceProductInfoEntity",
                    columnToField = mapOf("id" to "id", "model" to "model"),
                ),
            ),
        )
        val invocation = JavaCodeGenerator.InvocationTarget(
            callerClass = "BikeDeviceInfoService",
            voClass = "BikeDeviceInfoVO",
            mpj = true,
        )
        val code = JavaCodeGenerator.generate(result, main, GeneratedWrapper.MPJ_LAMBDA, invocation)
        assertTrue(code, code.contains(".selectAs(BikeDeviceInfoEntity::getName, BikeDeviceInfoVO::getProductName)"))
        assertTrue(code, code.contains(".selectAs(BikeDeviceProductInfoEntity::getModel, BikeDeviceInfoVO::getProductModel)"))
        // VO 查询调用形态（selectJoinList 族）
        assertTrue(code, code.contains("bikeDeviceInfoService.selectJoinList(BikeDeviceInfoVO.class, wrapper)"))
        // 选 VO 后不再用字符串别名（无 VO 时字符串别名行为由 SELECT列带别名_生成selectAs 覆盖）
        assertTrue(code, !code.contains("BikeDeviceInfoVO::getProductName\"") && !code.contains("\"productName\""))
    }

    @Test
    fun `多表JOIN_调用类有MPJ能力_不报错生成selectJoinList`() {
        // 端到端：调用类具备 MPJ 能力（mpj=true）时，多表 JOIN 不应抛「无 MPJ 能力」，应生成 selectJoinList 族
        val result = SqlReverseResult(
            tableName = "biz_bike_device_info",
            selectColumns = null,
            joins = listOf(
                ReverseJoin(
                    type = cn.song.mybatisplus.visualizer.model.JoinType.LEFT,
                    tableName = "biz_bike_device_product_info",
                    alias = "bikeDeviceProductInfoEntity",
                    onConditions = listOf(
                        Condition.ColumnComparison(
                            ColumnRef(tableAlias = "bikeDeviceProductInfoEntity", columnName = "id"),
                            ColumnRef(tableAlias = "t", columnName = "product_id"),
                        ),
                    ),
                ),
            ),
        )
        val main = JavaCodeGenerator.EntityTarget(
            className = "BikeDeviceInfoEntity",
            columnToField = mapOf("id" to "id", "product_id" to "productId"),
            subEntities = mapOf(
                "bikeDeviceProductInfoEntity" to JavaCodeGenerator.EntityTarget(
                    className = "BikeDeviceProductInfoEntity",
                    columnToField = mapOf("id" to "id"),
                ),
            ),
        )
        val invocation = JavaCodeGenerator.InvocationTarget(
            callerClass = "BikeDeviceInfoService",
            voClass = "BikeDeviceInfoVO",
            mpj = true,
        )
        val code = JavaCodeGenerator.generate(result, main, GeneratedWrapper.MPJ_LAMBDA, invocation)
        assertTrue(code, code.contains("new MPJLambdaWrapper<BikeDeviceInfoEntity>()"))
        assertTrue(code, code.contains(".leftJoin(BikeDeviceProductInfoEntity.class, BikeDeviceProductInfoEntity::getId, BikeDeviceInfoEntity::getProductId)"))
        assertTrue(code, code.contains("bikeDeviceInfoService.selectJoinList(BikeDeviceInfoVO.class, wrapper)"))
    }

    @Test
    fun `多表JOIN_仅选VO_生成selectAs引用VO方法且不生成调用行`() {
        // T-M50：只选 VO 不选调用类 -> selectAs 第二参引用 VO 属性方法，不生成调用行
        val result = SqlReverseResult(
            tableName = "biz_bike_device_info",
            selectColumns = listOf(SelectColumn("t.name", "productName"), SelectColumn("t.model", "productModel")),
            joins = listOf(
                ReverseJoin(
                    type = cn.song.mybatisplus.visualizer.model.JoinType.LEFT,
                    tableName = "biz_bike_device_product_info",
                    alias = "bikeDeviceProductInfoEntity",
                    onConditions = listOf(
                        Condition.ColumnComparison(
                            ColumnRef(tableAlias = "bikeDeviceProductInfoEntity", columnName = "id"),
                            ColumnRef(tableAlias = "t", columnName = "product_id"),
                        ),
                    ),
                ),
            ),
        )
        val main = JavaCodeGenerator.EntityTarget(
            className = "BikeDeviceInfoEntity",
            columnToField = mapOf("id" to "id", "product_id" to "productId", "name" to "name"),
            subEntities = mapOf(
                "bikeDeviceProductInfoEntity" to JavaCodeGenerator.EntityTarget(
                    className = "BikeDeviceProductInfoEntity",
                    columnToField = mapOf("id" to "id", "model" to "model"),
                ),
            ),
        )
        // 仅选 VO，无调用类（callerClass 为空）
        val voOnly = JavaCodeGenerator.InvocationTarget(callerClass = "", voClass = "BikeDeviceInfoVO", mpj = true)
        val code = JavaCodeGenerator.generate(result, main, GeneratedWrapper.MPJ_LAMBDA, voOnly)
        // lambda 形态 + VO 方法引用
        assertTrue(code, code.contains("new MPJLambdaWrapper<BikeDeviceInfoEntity>()"))
        assertTrue(code, code.contains(".selectAs(BikeDeviceInfoEntity::getName, BikeDeviceInfoVO::getProductName)"))
        assertTrue(code, code.contains(".selectAs(BikeDeviceProductInfoEntity::getModel, BikeDeviceInfoVO::getProductModel)"))
        // 无调用行（无调用类）
        assertTrue(code, !code.contains("bikeDeviceInfoService."))
        assertTrue(code, !code.contains("selectJoinList"))
    }

    @Test
    fun `多表JOIN_ON含逻辑删除_生成lambda形态selectAs引用VO`() {
        // isSubLogicalDelete 过滤 ON 里副表 deleted = 0 → 可 lambda 化 → MPJLambdaWrapper。
        // selectAs 第二参引用 VO 属性方法（对齐正向 selectAs(Sub::getX, VO::getY)）
        val result = SqlReverseResult(
            tableName = "biz_bike_device_info",
            selectColumns = listOf(SelectColumn("t.name", "productName"), SelectColumn("t.model", "productModel")),
            joins = listOf(
                ReverseJoin(
                    type = cn.song.mybatisplus.visualizer.model.JoinType.LEFT,
                    tableName = "biz_bike_device_product_info",
                    alias = "bikeDeviceProductInfoEntity",
                    onConditions = listOf(
                        Condition.ColumnComparison(
                            ColumnRef(tableAlias = "bikeDeviceProductInfoEntity", columnName = "id"),
                            ColumnRef(tableAlias = "t", columnName = "product_id"),
                        ),
                        // ON 额外条件 deleted = 0 被 isSubLogicalDelete 过滤 → 可 lambda 化
                        Condition.Simple(ColumnRef(tableAlias = "bikeDeviceProductInfoEntity", columnName = "deleted"), "=", SqlValue.Literal("0")),
                    ),
                ),
            ),
        )
        val main = JavaCodeGenerator.EntityTarget(
            className = "BikeDeviceInfoEntity",
            columnToField = mapOf("id" to "id", "product_id" to "productId", "name" to "name"),
            subEntities = mapOf(
                "bikeDeviceProductInfoEntity" to JavaCodeGenerator.EntityTarget(
                    className = "BikeDeviceProductInfoEntity",
                    columnToField = mapOf("id" to "id", "model" to "model", "deleted" to "deleted"),
                ),
            ),
        )
        val invocation = JavaCodeGenerator.InvocationTarget(
            callerClass = "BikeDeviceInfoService",
            voClass = "BikeDeviceInfoVO",
            mpj = true,
        )
        val code = JavaCodeGenerator.generate(result, main, GeneratedWrapper.MPJ_LAMBDA, invocation)
        // lambda 联接形态（deleted = 0 被过滤 → 可 lambda 化）
        assertTrue(code, code.contains("new MPJLambdaWrapper<BikeDeviceInfoEntity>()"))
        assertTrue(code, code.contains(".selectAs(BikeDeviceInfoEntity::getName, BikeDeviceInfoVO::getProductName)"))
        assertTrue(code, code.contains(".selectAs(BikeDeviceProductInfoEntity::getModel, BikeDeviceInfoVO::getProductModel)"))
        assertTrue(code, code.contains(".leftJoin(BikeDeviceProductInfoEntity.class, BikeDeviceProductInfoEntity::getId, BikeDeviceInfoEntity::getProductId)"))
        assertTrue(code, code.contains("bikeDeviceInfoService.selectJoinList(BikeDeviceInfoVO.class, wrapper)"))
    }

    @Test
    fun `多表JOIN_字符串形态_非逻辑删除条件_别名列渲染为selectAS别名`() {
        // T-M48：ON 含非逻辑删除额外条件（type = 1）→ 无法 lambda 化 → MPJQueryWrapper（字符串联接）。
        // MPJQueryWrapper 无 selectAs(String, SFunction) 重载，别名列应渲染为 .select("col AS alias") 字符串形式
        val result = SqlReverseResult(
            tableName = "biz_bike_device_info",
            selectColumns = listOf(SelectColumn("t.name", "productName"), SelectColumn("t.model", "productModel")),
            joins = listOf(
                ReverseJoin(
                    type = cn.song.mybatisplus.visualizer.model.JoinType.LEFT,
                    tableName = "biz_bike_device_product_info",
                    alias = "bikeDeviceProductInfoEntity",
                    onConditions = listOf(
                        Condition.ColumnComparison(
                            ColumnRef(tableAlias = "bikeDeviceProductInfoEntity", columnName = "id"),
                            ColumnRef(tableAlias = "t", columnName = "product_id"),
                        ),
                        // ON 额外条件 type = 1（非逻辑删除）→ 挡住 lambda 化 → 回落 MPJQueryWrapper
                        Condition.Simple(ColumnRef(tableAlias = "bikeDeviceProductInfoEntity", columnName = "type"), "=", SqlValue.Literal("1")),
                    ),
                ),
            ),
        )
        val main = JavaCodeGenerator.EntityTarget(
            className = "BikeDeviceInfoEntity",
            columnToField = mapOf("id" to "id", "product_id" to "productId", "name" to "name"),
            subEntities = mapOf(
                "bikeDeviceProductInfoEntity" to JavaCodeGenerator.EntityTarget(
                    className = "BikeDeviceProductInfoEntity",
                    columnToField = mapOf("id" to "id", "model" to "model", "type" to "type"),
                ),
            ),
        )
        val invocation = JavaCodeGenerator.InvocationTarget(
            callerClass = "BikeDeviceInfoService",
            voClass = "BikeDeviceInfoVO",
            mpj = true,
        )
        val code = JavaCodeGenerator.generate(result, main, GeneratedWrapper.MPJ_LAMBDA, invocation)
        // lambda 形态：VO 查询 + 4 参 lambda join
        assertTrue(code, code.contains("new MPJLambdaWrapper<BikeDeviceInfoEntity>()"))
        assertTrue(code, code.contains(".selectAs(BikeDeviceInfoEntity::getName, BikeDeviceInfoVO::getProductName)"))
        assertTrue(code, code.contains(".selectAs(BikeDeviceProductInfoEntity::getModel, BikeDeviceInfoVO::getProductModel)"))
        // 4 参 lambda leftJoin
        assertTrue(code, code.contains(".leftJoin(BikeDeviceProductInfoEntity.class, BikeDeviceProductInfoEntity::getId, BikeDeviceInfoEntity::getProductId,"))
        assertTrue(code, code.contains("ext -> ext.eq(BikeDeviceProductInfoEntity::getType, 1)"))
        // 不可出现非法的 selectAs(String, SFunction)
        assertTrue(code, !code.contains("selectAs(\"t.name\""))
        assertTrue(code, !code.contains("selectAs(\"t.model\""))
    }

    @Test
    fun `SELECT列_多副实体同名列_不归属回落字符串`() {
        // name 在多个副实体都有 → singleOrNull 判歧义 → 回落字符串（不误判归属）
        val result = SqlReverseResult(
            tableName = "biz_bike_device_info",
            selectColumns = listOf(SelectColumn("t.model")),
            joins = listOf(
                ReverseJoin(
                    type = cn.song.mybatisplus.visualizer.model.JoinType.LEFT,
                    tableName = "biz_bike_device_product_info",
                    alias = "p",
                    onConditions = listOf(
                        Condition.ColumnComparison(ColumnRef(tableAlias = "p", columnName = "id"), ColumnRef(tableAlias = "t", columnName = "product_id")),
                    ),
                ),
            ),
        )
        val main = JavaCodeGenerator.EntityTarget(
            className = "BikeDeviceInfoEntity",
            columnToField = mapOf("id" to "id", "product_id" to "productId"),
            subEntities = mapOf(
                "p" to JavaCodeGenerator.EntityTarget("BikeDeviceProductInfoEntity", mapOf("id" to "id", "model" to "model")),
                "s" to JavaCodeGenerator.EntityTarget("BikeStationInfoEntity", mapOf("id" to "id", "model" to "model")),
            ),
        )
        val code = JavaCodeGenerator.generate(result, main, GeneratedWrapper.MPJ_LAMBDA)
        assertTrue(code, code.contains(".select(\"t.model\")"))
    }

    // ------------------------------------------------------------------
    // ? 参数占位符（P0+P1：WHERE/SET/INSERT/LIMIT）
    // ------------------------------------------------------------------

    @Test
    fun `占位符条件_渲染eq占位与头注释`() {
        val result = SqlReverseResult(
            tableName = "sys_account_role_rel",
            selectColumns = listOf(SelectColumn("id"), SelectColumn("account_id"), SelectColumn("role_id")),
            conditions = listOf(
                Condition.Simple(ColumnRef(columnName = "role_id"), "=", SqlValue.Placeholder("?")),
                Condition.Simple(ColumnRef(columnName = "status"), ">", SqlValue.Placeholder("?")),
                Condition.In(ColumnRef(columnName = "id"), false, listOf(SqlValue.Placeholder("?"), SqlValue.Placeholder("?"))),
            ),
        )
        val code = JavaCodeGenerator.generate(result, null, GeneratedWrapper.QUERY)
        assertTrue(code, code.contains(".eq(\"role_id\", \"?\")"))
        assertTrue(code, code.contains(".gt(\"status\", \"?\")"))
        assertTrue(code, code.contains(".in(\"id\", \"?\", \"?\")"))
        assertTrue(code, code.contains("// 注意：SQL 含参数占位符 ?，无法恢复实际参数，已生成 \"?\" 占位，请替换为实际参数"))
    }

    @Test
    fun `占位符_LIKE渲染like与BETWEEN渲染between`() {
        val result = SqlReverseResult(
            tableName = "user",
            conditions = listOf(
                Condition.Like(ColumnRef(columnName = "name"), false, SqlValue.Placeholder("?"), LikePosition.NONE),
                Condition.Between(ColumnRef(columnName = "age"), false, SqlValue.Placeholder("?"), SqlValue.Placeholder("?")),
            ),
        )
        val code = JavaCodeGenerator.generate(result, null, GeneratedWrapper.QUERY)
        assertTrue(code, code.contains(".like(\"name\", \"?\")"))
        assertTrue(code, code.contains(".between(\"age\", \"?\", \"?\")"))
    }

    @Test
    fun `占位符_UPDATE_SET渲染set`() {
        val result = SqlReverseResult(
            tableName = "user",
            kind = ReverseStatementKind.UPDATE,
            conditions = listOf(Condition.Simple(ColumnRef(columnName = "id"), "=", SqlValue.Placeholder("?"))),
            sets = listOf(ReverseSet("name", SqlValue.Placeholder("?"))),
        )
        val code = JavaCodeGenerator.generate(result, null, GeneratedWrapper.QUERY)
        assertTrue(code, code.contains(".set(\"name\", \"?\")"))
        assertTrue(code, code.contains(".eq(\"id\", \"?\")"))
    }

    @Test
    fun `占位符_INSERT渲染实体setter`() {
        val result = SqlReverseResult(
            tableName = "user",
            kind = ReverseStatementKind.INSERT,
            insertColumns = listOf("name", "age"),
            insertValues = listOf(listOf(SqlValue.Placeholder("?"), SqlValue.Placeholder("?"))),
        )
        val code = JavaCodeGenerator.generate(result, null, GeneratedWrapper.QUERY)
        assertTrue(code, code.contains("new User().setName(\"?\")"))
        assertTrue(code, code.contains(".setAge(\"?\")"))
    }

    @Test
    fun `占位符_LIMIT渲染last保留偏移`() {
        val result = SqlReverseResult(
            tableName = "user",
            conditions = listOf(Condition.Simple(ColumnRef(columnName = "role_id"), "=", SqlValue.Placeholder("?"))),
            limit = LimitClause(offset = SqlValue.Placeholder("?"), count = SqlValue.Placeholder("?")),
        )
        val code = JavaCodeGenerator.generate(result, null, GeneratedWrapper.QUERY)
        assertTrue(code, code.contains(".last(\"LIMIT ?, ?\")"))
        assertTrue(code, code.contains(".eq(\"role_id\", \"?\")"))
    }

    @Test
    fun `无占位符_不产生占位注释`() {
        val result = SqlReverseResult(
            tableName = "user",
            conditions = listOf(Condition.Simple(ColumnRef(columnName = "id"), "=", SqlValue.Literal("1"))),
        )
        val code = JavaCodeGenerator.generate(result, null, GeneratedWrapper.QUERY)
        assertTrue(code, !code.contains("参数占位符"))
    }

    // ------------------------------------------------------------------
    // SELECT 调用形态（分页 page / VO 同名回落）
    // ------------------------------------------------------------------

    @Test
    fun `SELECT分页带Service_生成page调用`() {
        val result = SqlReverseResult(
            tableName = "user",
            conditions = listOf(Condition.Simple(ColumnRef(columnName = "id"), "=", SqlValue.Literal("1"))),
            limit = LimitClause(offset = SqlValue.Placeholder("?"), count = SqlValue.Placeholder("?")),
        )
        val code = JavaCodeGenerator.generate(
            result, null, GeneratedWrapper.QUERY,
            JavaCodeGenerator.InvocationTarget("UserService"),
        )
        assertTrue(code, code.contains("Page<User> page = userService.page(new Page<>(1, 10), wrapper);"))
        // T-M45：说明注释为独立行注释（不再行尾注释）——独立行位于调用行上方
        assertTrue(code, code.contains("// 分页 current/size 已占位，请替换为实际参数\nPage<User> page"))
        assertTrue(code, !code.contains("wrapper);  //"))
        assertTrue(code, !code.contains(".last(\"LIMIT"))
    }

    @Test
    fun `SELECT分页带Mapper_生成selectPage`() {
        val result = SqlReverseResult(
            tableName = "user",
            limit = LimitClause(count = SqlValue.Literal("10")),
        )
        val code = JavaCodeGenerator.generate(
            result, null, GeneratedWrapper.QUERY,
            JavaCodeGenerator.InvocationTarget("UserMapper"),
        )
        assertTrue(code, code.contains("Page<User> page = userMapper.selectPage(new Page<>(1, 10), wrapper);"))
    }

    @Test
    fun `SELECT分页字面量_推断current`() {
        val paged = SqlReverseResult(
            tableName = "user",
            limit = LimitClause(offset = SqlValue.Literal("20"), count = SqlValue.Literal("10")),
        )
        val code = JavaCodeGenerator.generate(
            paged, null, GeneratedWrapper.QUERY,
            JavaCodeGenerator.InvocationTarget("UserService"),
        )
        assertTrue(code, code.contains("new Page<>(3, 10)"))
        // 无调用类（纯 wrapper 表达式）→ 保持 last 形态不变
        val plain = JavaCodeGenerator.generate(paged, null, GeneratedWrapper.QUERY)
        assertTrue(plain, plain.contains(".last(\"LIMIT 20, 10\")"))
    }

    @Test
    fun `VO与实体同名_回落普通list调用`() {
        val result = SqlReverseResult(tableName = "user")
        val code = JavaCodeGenerator.generate(
            result, null, GeneratedWrapper.QUERY,
            JavaCodeGenerator.InvocationTarget("UserService", voClass = "User"),
        )
        assertTrue(code, code.contains("List<User> list = userService.list(wrapper);"))
        assertTrue(code, !code.contains("listVo"))
    }

    @Test
    fun `分页VO组合_生成selectJoinListPage`() {
        val result = SqlReverseResult(
            tableName = "user",
            limit = LimitClause(offset = SqlValue.Placeholder("?"), count = SqlValue.Placeholder("?")),
        )
        val code = JavaCodeGenerator.generate(
            result, null, GeneratedWrapper.QUERY,
            JavaCodeGenerator.InvocationTarget("UserService", voClass = "UserVo", mpj = true),
        )
        // MPJ 1.5.6 实证：pageVo 不存在，真实方法 = selectJoinListPage(page, Vo.class, wrapper)
        assertTrue(code, code.contains("Page<UserVo> page = userService.selectJoinListPage(new Page<>(1, 10), UserVo.class, wrapper);"))
        assertTrue(code, code.contains("// VO 查询需 MyBatis-Plus-Join（MPJ）依赖"))
    }

    @Test
    fun `分页VO组合Mapper_生成selectJoinPage`() {
        val result = SqlReverseResult(
            tableName = "user",
            limit = LimitClause(offset = SqlValue.Placeholder("?"), count = SqlValue.Placeholder("?")),
        )
        val code = JavaCodeGenerator.generate(
            result, null, GeneratedWrapper.QUERY,
            JavaCodeGenerator.InvocationTarget("UserMapper", voClass = "UserVo", mpj = true),
        )
        assertTrue(code, code.contains("Page<UserVo> page = userMapper.selectJoinPage(new Page<>(1, 10), UserVo.class, wrapper);"))
    }

    @Test
    fun `VO场景_强制MPJ wrapper`() {
        // selectJoinList 参数类型为 MPJBaseJoin——LambdaQueryWrapper 无法编译，须强制 MPJ wrapper
        val result = SqlReverseResult(
            tableName = "user",
            conditions = listOf(Condition.Simple(ColumnRef(columnName = "name"), "=", SqlValue.Literal("'x'", "x"))),
        )
        val entity = JavaCodeGenerator.EntityTarget(className = "User", columnToField = mapOf("name" to "name"))
        val code = JavaCodeGenerator.generate(
            result, entity, GeneratedWrapper.LAMBDA,
            JavaCodeGenerator.InvocationTarget("UserService", voClass = "UserVo", mpj = true),
        )
        assertTrue(code, code.contains("new MPJLambdaWrapper<User>()"))
        assertTrue(code, code.contains("userService.selectJoinList(UserVo.class, wrapper);"))
        // 无实体 + VO → MPJQueryWrapper（字符串列）同样满足 MPJBaseJoin
        val plain = JavaCodeGenerator.generate(
            result, null, GeneratedWrapper.QUERY,
            JavaCodeGenerator.InvocationTarget("UserService", voClass = "UserVo", mpj = true),
        )
        assertTrue(plain, plain.contains("new MPJQueryWrapper<>(User.class)"))
    }

    @Test
    fun `非MPJ调用类_VO回落实体查询并注释`() {
        // 调用类无 MPJ 能力（mpj=false，继承链实证由调用方解析）→ 严禁生成 selectJoinList，
        // 回落 list + 明确注释；wrapper 保持普通形态（不强制 MPJ）
        val result = SqlReverseResult(
            tableName = "user",
            conditions = listOf(Condition.Simple(ColumnRef(columnName = "id"), "=", SqlValue.Literal("1"))),
        )
        val entity = JavaCodeGenerator.EntityTarget(className = "User", columnToField = mapOf("id" to "id"))
        val code = JavaCodeGenerator.generate(
            result, entity, GeneratedWrapper.LAMBDA,
            JavaCodeGenerator.InvocationTarget("UserService", voClass = "UserVo"),
        )
        assertTrue(code, code.contains("List<User> list = userService.list(wrapper);"))
        assertTrue(code, code.contains("// 调用类无 MyBatis-Plus-Join（MPJ）能力，VO 查询不可用，已回落实体查询"))
        assertTrue(code, code.contains("new LambdaQueryWrapper<User>()"))
        assertTrue(code, !code.contains("selectJoinList"))
    }

    @Test
    fun `非MPJ调用类_分页VO回落实体page`() {
        val result = SqlReverseResult(
            tableName = "user",
            limit = LimitClause(offset = SqlValue.Placeholder("?"), count = SqlValue.Placeholder("?")),
        )
        val code = JavaCodeGenerator.generate(
            result, null, GeneratedWrapper.QUERY,
            JavaCodeGenerator.InvocationTarget("UserMapper", voClass = "UserVo"),
        )
        assertTrue(code, code.contains("Page<User> page = userMapper.selectPage(new Page<>(1, 10), wrapper);"))
        assertTrue(code, !code.contains("selectJoinPage"))
    }

    @Test
    fun `非MPJ调用类_多表JOIN报错`() {
        val result = SqlReverseResult(
            tableName = "user",
            joins = listOf(
                ReverseJoin(
                    type = cn.song.mybatisplus.visualizer.model.JoinType.LEFT,
                    tableName = "address",
                    alias = "t1",
                    onConditions = listOf(
                        Condition.ColumnComparison(ColumnRef(tableAlias = "t1", columnName = "user_id"), ColumnRef(tableAlias = "t", columnName = "id")),
                    ),
                    rawOn = "t1.user_id = t.id",
                ),
            ),
        )
        val entity = JavaCodeGenerator.EntityTarget(
            className = "User",
            columnToField = mapOf("id" to "id"),
            subEntities = mapOf("t1" to JavaCodeGenerator.EntityTarget("Address", mapOf("user_id" to "userId"))),
        )
        val ex = try {
            JavaCodeGenerator.generate(
                result, entity, GeneratedWrapper.MPJ_LAMBDA,
                JavaCodeGenerator.InvocationTarget("UserService"),
            )
            throw AssertionError("expected SqlReverseException")
        } catch (e: SqlReverseException) {
            e
        }
        assertTrue(ex.detail, ex.detail.contains("无 MPJ 能力"))
    }

    // ------------------------------------------------------------------
    // serialVersionUID 默认忽略（T-M42，层 2 生成兜底）
    // ------------------------------------------------------------------

    @Test
    fun `SELECT含serial_version_u_i_d_无实体_不生成该列引用`() {
        val result = SqlReverseResult(
            tableName = "user",
            selectColumns = listOf(SelectColumn("id"), SelectColumn("serial_version_u_i_d"), SelectColumn("username")),
            conditions = listOf(
                Condition.Simple(ColumnRef(columnName = "username"), "=", SqlValue.Literal("'admin'", "admin")),
            ),
        )
        val code = JavaCodeGenerator.generate(result)
        assertTrue(code, code.contains(".select(\"id\", \"username\")"))
        assertTrue(code, !code.contains("serial_version_u_i_d"))
        assertTrue(code, !code.contains("serialversionuid"))
    }

    @Test
    fun `SELECT含serialVersionUID列_有实体_不生成getter引用`() {
        val result = SqlReverseResult(
            tableName = "user",
            selectColumns = listOf(SelectColumn("id"), SelectColumn("serial_version_u_i_d"), SelectColumn("username")),
        )
        // 模拟旧行为：实体映射仍含该列（层 2 兜底须跳过，防 User::getSerialVersionUID 编译失败）
        val entity = JavaCodeGenerator.EntityTarget(
            className = "User",
            columnToField = mapOf(
                "id" to "id",
                "serial_version_u_i_d" to "serialVersionUID",
                "username" to "username",
            ),
        )
        val code = JavaCodeGenerator.generate(result, entity)
        assertTrue(code, code.contains(".select(User::getId, User::getUsername)"))
        assertTrue(code, !code.contains("getSerialVersionUID"))
        assertTrue(code, !code.contains("serial_version_u_i_d"))
    }

    @Test
    fun `WHERE含serial_version_u_i_d_条件跳过`() {
        val result = SqlReverseResult(
            tableName = "user",
            conditions = listOf(
                Condition.Simple(ColumnRef(columnName = "id"), "=", SqlValue.Literal("1")),
                Condition.Simple(ColumnRef(columnName = "serial_version_u_i_d"), "=", SqlValue.Literal("2")),
            ),
        )
        val code = JavaCodeGenerator.generate(result)
        assertTrue(code, code.contains(".eq(\"id\", 1)"))
        assertTrue(code, !code.contains(".eq(\"serial_version_u_i_d\""))
        assertTrue(code, !code.contains("serial_version_u_i_d"))
    }

    @Test
    fun `WHERE仅serial_version_u_i_d_条件整组跳过`() {
        val result = SqlReverseResult(
            tableName = "user",
            conditions = listOf(
                Condition.Simple(ColumnRef(columnName = "serial_version_u_i_d"), "=", SqlValue.Literal("1")),
            ),
        )
        val code = JavaCodeGenerator.generate(result)
        assertTrue(code, !code.contains(".eq("))
        assertTrue(code, !code.contains("serial_version_u_i_d"))
    }

    @Test
    fun `WHERE_Group内serial_version_u_i_d跳过且不产生or分隔`() {
        val result = SqlReverseResult(
            tableName = "user",
            conditions = listOf(
                Condition.Group(
                    Connector.OR,
                    listOf(
                        Condition.Simple(ColumnRef(columnName = "a"), "=", SqlValue.Literal("1")),
                        Condition.Simple(ColumnRef(columnName = "serial_version_u_i_d"), "=", SqlValue.Literal("2")),
                    ),
                ),
            ),
        )
        val code = JavaCodeGenerator.generate(result)
        assertTrue(code, code.contains(".nested(w -> w\n        .eq(\"a\", 1))"))
        assertTrue(code, !code.contains(".or()"))
    }

    @Test
    fun `WHERE_Group仅serial_version_u_i_d_整组跳过`() {
        val result = SqlReverseResult(
            tableName = "user",
            conditions = listOf(
                Condition.Group(
                    Connector.OR,
                    listOf(
                        Condition.Simple(ColumnRef(columnName = "serial_version_u_i_d"), "=", SqlValue.Literal("1")),
                    ),
                ),
            ),
        )
        val code = JavaCodeGenerator.generate(result)
        assertTrue(code, !code.contains("nested("))
        assertTrue(code, !code.contains("serial_version_u_i_d"))
    }

    @Test
    fun `ORDER BY含serial_version_u_i_d_跳过`() {
        val result = SqlReverseResult(
            tableName = "user",
            orderBy = listOf(
                OrderByItem(ColumnRef(columnName = "create_time"), asc = false),
                OrderByItem(ColumnRef(columnName = "serial_version_u_i_d"), asc = true),
            ),
        )
        val code = JavaCodeGenerator.generate(result)
        assertTrue(code, code.contains(".orderByDesc(\"create_time\")"))
        assertTrue(code, !code.contains("serial_version_u_i_d"))
    }

    @Test
    fun `ORDER BY仅serial_version_u_i_d_不生成orderBy调用`() {
        val result = SqlReverseResult(
            tableName = "user",
            orderBy = listOf(OrderByItem(ColumnRef(columnName = "serial_version_u_i_d"), asc = true)),
        )
        val code = JavaCodeGenerator.generate(result)
        assertTrue(code, !code.contains("orderBy"))
        assertTrue(code, !code.contains("serial_version_u_i_d"))
    }

    @Test
    fun `INSERT含serial_version_u_i_d_不生成setter且值对齐`() {
        val result = SqlReverseResult(
            tableName = "user",
            kind = ReverseStatementKind.INSERT,
            insertColumns = listOf("name", "serial_version_u_i_d", "age"),
            insertValues = listOf(
                listOf(SqlValue.Literal("'张三'", "张三"), SqlValue.Literal("1"), SqlValue.Literal("18")),
            ),
        )
        val code = JavaCodeGenerator.generate(result, null, GeneratedWrapper.QUERY)
        // 过滤后保留列 (name, age) 取值于原行对应下标（age 值 18 而非被跳过的 1）——值对齐
        assertTrue(code, code.contains("new User().setName(\"张三\")\n    .setAge(18);"))
        assertTrue(code, !code.contains("SerialVersionUid"))
        assertTrue(code, !code.contains("serial_version_u_i_d"))
    }

    @Test
    fun `INSERT仅serial_version_u_i_d_生成空实体保语句合法`() {
        val result = SqlReverseResult(
            tableName = "user",
            kind = ReverseStatementKind.INSERT,
            insertColumns = listOf("serial_version_u_i_d"),
            insertValues = listOf(listOf(SqlValue.Literal("1"))),
        )
        val code = JavaCodeGenerator.generate(result, null, GeneratedWrapper.QUERY)
        assertTrue(code, code.contains("new User();"))
        assertTrue(code, !code.contains("serial_version_u_i_d"))
    }

    @Test
    fun `UPDATE_SET含serial_version_u_i_d_跳过set`() {
        val result = SqlReverseResult(
            tableName = "user",
            kind = ReverseStatementKind.UPDATE,
            conditions = listOf(Condition.Simple(ColumnRef(columnName = "id"), "=", SqlValue.Literal("1"))),
            sets = listOf(
                ReverseSet("name", SqlValue.Literal("'李四'", "李四")),
                ReverseSet("serial_version_u_i_d", SqlValue.Literal("2")),
            ),
        )
        val code = JavaCodeGenerator.generate(result, null, GeneratedWrapper.QUERY)
        assertTrue(code, code.contains(".set(\"name\", \"李四\")"))
        assertTrue(code, code.contains(".eq(\"id\", 1)"))
        assertTrue(code, !code.contains("serial_version_u_i_d"))
    }

    @Test
    fun `SELECT仅含serial_version_u_i_d_不生成select调用`() {
        // 全部列被过滤 → selectColumns 空 → 现有 isNotEmpty 分支自然回落（不生成 select，MP 全列语义）
        val result = SqlReverseResult(tableName = "user", selectColumns = listOf(SelectColumn("serial_version_u_i_d")))
        val code = JavaCodeGenerator.generate(result)
        assertTrue(code, !code.contains(".select("))
        assertTrue(code, !code.contains("serial_version_u_i_d"))
    }

    @Test
    fun `SELECT反引号与别名形态_同过滤`() {
        val result = SqlReverseResult(
            tableName = "user",
            selectColumns = listOf(SelectColumn("id"), SelectColumn("`serial_version_u_i_d`"), SelectColumn("t.serial_version_u_i_d"), SelectColumn("SERIAL_VERSION_UID")),
        )
        val code = JavaCodeGenerator.generate(result)
        assertTrue(code, code.contains(".select(\"id\")"))
        assertTrue(code, !code.contains("serial_version_u_i_d"))
        assertTrue(code, !code.contains("serialversionuid"))
    }

    @Test
    fun `正常业务列不受影响_serial_version_uid同形态过滤`() {
        val result = SqlReverseResult(
            tableName = "user",
            selectColumns = listOf(SelectColumn("username"), SelectColumn("serial_version_uid")),
            conditions = listOf(
                Condition.Simple(ColumnRef(columnName = "username"), "=", SqlValue.Literal("'admin'", "admin")),
            ),
            orderBy = listOf(OrderByItem(ColumnRef(columnName = "id"), asc = true)),
        )
        val code = JavaCodeGenerator.generate(result)
        assertTrue(code, code.contains(".select(\"username\")"))
        assertTrue(code, code.contains(".eq(\"username\", \"admin\")"))
        assertTrue(code, code.contains(".orderByAsc(\"id\")"))
        // serial_version_uid（非逐字母拆分形态）归一化后同样命中
        assertTrue(code, !code.contains("serial_version_uid"))
    }

    // ------------------------------------------------------------------
    // T-M44：调用方法按调用类真实方法集选择（selectPage 缺失回落 selectJoin 族）
    // ------------------------------------------------------------------

    @Test
    fun `mapper方法集含selectPage_保持普通分页`() {
        val result = SqlReverseResult(
            tableName = "user",
            limit = LimitClause(offset = SqlValue.Placeholder("?"), count = SqlValue.Placeholder("?")),
        )
        val code = JavaCodeGenerator.generate(
            result, null, GeneratedWrapper.QUERY,
            JavaCodeGenerator.InvocationTarget("UserMapper", methods = setOf("selectPage", "selectList")),
        )
        assertTrue(code, code.contains("Page<User> page = userMapper.selectPage(new Page<>(1, 10), wrapper);"))
    }

    @Test
    fun `mapper方法集仅join_分页回落selectJoinPage带Class参数`() {
        val result = SqlReverseResult(
            tableName = "user",
            limit = LimitClause(offset = SqlValue.Placeholder("?"), count = SqlValue.Placeholder("?")),
        )
        val code = JavaCodeGenerator.generate(
            result, null, GeneratedWrapper.QUERY,
            JavaCodeGenerator.InvocationTarget("UserMapper", methods = setOf("selectJoinPage", "selectJoinList")),
        )
        assertTrue(code, code.contains("Page<User> page = userMapper.selectJoinPage(new Page<>(1, 10), User.class, wrapper);"))
    }

    @Test
    fun `mapper方法集空_保守生成普通方法`() {
        val result = SqlReverseResult(
            tableName = "user",
            limit = LimitClause(offset = SqlValue.Placeholder("?"), count = SqlValue.Placeholder("?")),
        )
        val code = JavaCodeGenerator.generate(
            result, null, GeneratedWrapper.QUERY,
            JavaCodeGenerator.InvocationTarget("UserMapper"),
        )
        assertTrue(code, code.contains("Page<User> page = userMapper.selectPage(new Page<>(1, 10), wrapper);"))
    }

    @Test
    fun `mapper列表回落selectJoinList`() {
        val result = SqlReverseResult(tableName = "user")
        val code = JavaCodeGenerator.generate(
            result, null, GeneratedWrapper.QUERY,
            JavaCodeGenerator.InvocationTarget("UserMapper", methods = setOf("selectJoinList")),
        )
        assertTrue(code, code.contains("List<User> list = userMapper.selectJoinList(User.class, wrapper);"))
    }

    @Test
    fun `service分页回落selectJoinListPage`() {
        val result = SqlReverseResult(
            tableName = "user",
            limit = LimitClause(offset = SqlValue.Placeholder("?"), count = SqlValue.Placeholder("?")),
        )
        val code = JavaCodeGenerator.generate(
            result, null, GeneratedWrapper.QUERY,
            JavaCodeGenerator.InvocationTarget("UserService", methods = setOf("selectJoinListPage")),
        )
        assertTrue(code, code.contains("Page<User> page = userService.selectJoinListPage(new Page<>(1, 10), User.class, wrapper);"))
    }

    @Test
    fun `service列表回落selectJoinList`() {
        val result = SqlReverseResult(tableName = "user")
        val code = JavaCodeGenerator.generate(
            result, null, GeneratedWrapper.QUERY,
            JavaCodeGenerator.InvocationTarget("UserService", methods = setOf("selectJoinList")),
        )
        assertTrue(code, code.contains("List<User> list = userService.selectJoinList(User.class, wrapper);"))
    }

    @Test
    fun `update缺失回落updateJoin`() {
        val result = SqlReverseResult(
            tableName = "user",
            kind = ReverseStatementKind.UPDATE,
            conditions = listOf(Condition.Simple(ColumnRef(columnName = "id"), "=", SqlValue.Literal("1"))),
            sets = listOf(ReverseSet("name", SqlValue.Literal("'x'", "x"))),
        )
        val mapper = JavaCodeGenerator.generate(
            result, null, GeneratedWrapper.QUERY,
            JavaCodeGenerator.InvocationTarget("UserMapper", methods = setOf("updateJoin")),
        )
        assertTrue(mapper, mapper.contains("userMapper.updateJoin(null, wrapper);"))
        val service = JavaCodeGenerator.generate(
            result, null, GeneratedWrapper.QUERY,
            JavaCodeGenerator.InvocationTarget("UserService", methods = setOf("updateJoin")),
        )
        assertTrue(service, service.contains("userService.updateJoin(null, wrapper);"))
    }

    @Test
    fun `delete缺失回落deleteJoin`() {
        val result = SqlReverseResult(
            tableName = "user",
            kind = ReverseStatementKind.DELETE,
            conditions = listOf(Condition.Simple(ColumnRef(columnName = "id"), "=", SqlValue.Literal("1"))),
        )
        val mapper = JavaCodeGenerator.generate(
            result, null, GeneratedWrapper.QUERY,
            JavaCodeGenerator.InvocationTarget("UserMapper", methods = setOf("deleteJoin")),
        )
        assertTrue(mapper, mapper.contains("userMapper.deleteJoin(wrapper);"))
        val service = JavaCodeGenerator.generate(
            result, null, GeneratedWrapper.QUERY,
            JavaCodeGenerator.InvocationTarget("UserService", methods = setOf("deleteJoin")),
        )
        assertTrue(service, service.contains("userService.deleteJoin(wrapper);"))
    }

    @Test
    fun `VO分页方法集含selectJoinPage_保持不变`() {
        val result = SqlReverseResult(
            tableName = "user",
            limit = LimitClause(offset = SqlValue.Placeholder("?"), count = SqlValue.Placeholder("?")),
        )
        val code = JavaCodeGenerator.generate(
            result, null, GeneratedWrapper.QUERY,
            JavaCodeGenerator.InvocationTarget("UserMapper", voClass = "UserVo", mpj = true, methods = setOf("selectJoinPage")),
        )
        assertTrue(code, code.contains("Page<UserVo> page = userMapper.selectJoinPage(new Page<>(1, 10), UserVo.class, wrapper);"))
    }

    // ------------------------------------------------------------------
    // 子查询 JOIN 生成测试
    // ------------------------------------------------------------------

    @Test
    fun `子查询JOIN_生成rawJoin字符串`() {
        // 构造一个含子查询 JOIN 的解析结果
        val join = ReverseJoin(
            type = cn.song.mybatisplus.visualizer.model.JoinType.LEFT,
            tableName = "(SELECT tt.id, tt.station_id, tt.station_name, tt.merchant_id\n    FROM station_info tt\n    WHERE tt.merchant_id >= 0)",
            alias = null,
            onConditions = listOf(
                Condition.ColumnComparison(
                    ColumnRef("tt", "station_id"),
                    ColumnRef("t", "station_id"),
                ),
            ),
            rawOn = "tt.station_id = t.station_id",
        )
        val result = SqlReverseResult(
            tableName = "car_device_info",
            selectColumns = listOf(SelectColumn("t.id"), SelectColumn("t.station_id")),
            joins = listOf(join),
            conditions = listOf(
                Condition.Simple(ColumnRef("t", "id"), "=", SqlValue.Literal("1")),
            ),
        )
        val code = JavaCodeGenerator.generate(result)
        // 子查询 JOIN 应生成 raw join 字符串
        assertTrue("应生成 leftJoin 字符串", code.contains("leftJoin(\""))
        assertTrue("应包含子查询文本", code.contains("(") && code.contains("SELECT"))
        assertTrue("应包含 ON 条件", code.contains("tt.station_id = t.station_id"))
    }

    // ------------------------------------------------------------------
    // ext 条件 lambda 化测试
    // ------------------------------------------------------------------

    @Test
    fun `ext条件_生成4参lambda`() {
        val sub = JavaCodeGenerator.EntityTarget(
            className = "StationInfoEntity",
            columnToField = mapOf("id" to "id", "station_name" to "stationName"),
        )
        val entity = JavaCodeGenerator.EntityTarget(
            className = "DeviceInfoEntity",
            columnToField = mapOf("id" to "id", "station_id" to "stationId"),
            subEntities = mapOf("ext" to sub),
        )
        val join = ReverseJoin(
            type = cn.song.mybatisplus.visualizer.model.JoinType.LEFT,
            tableName = "station_info",
            alias = "ext",
            onConditions = listOf(
                Condition.ColumnComparison(
                    ColumnRef("ext", "id"),
                    ColumnRef("t", "station_id"),
                ),
                Condition.Simple(
                    ColumnRef("ext", "station_name"),
                    "=",
                    SqlValue.Literal("'中心站'", "中心站"),
                ),
            ),
            rawOn = "ext.id = t.station_id AND ext.station_name = '中心站'",
        )
        val result = SqlReverseResult(
            tableName = "car_device_info",
            selectColumns = listOf(SelectColumn("t.id")),
            joins = listOf(join),
        )
        val code = JavaCodeGenerator.generate(result, entity)
        assertTrue("应生成 4 参 leftJoin", code.contains("leftJoin(StationInfoEntity.class, StationInfoEntity::getId, DeviceInfoEntity::getStationId,"))
        assertTrue("应包含 ext lambda", code.contains("ext -> ext.eq(StationInfoEntity::getStationName,"))
        assertTrue("应包含 ext 值", code.contains("\"中心站\""))
    }

    @Test
    fun `ext条件_无ext仍生成3参`() {
        val sub = JavaCodeGenerator.EntityTarget(
            className = "AddressDO",
            columnToField = mapOf("user_id" to "userId"),
        )
        val entity = JavaCodeGenerator.EntityTarget(
            className = "UserDO",
            columnToField = mapOf("id" to "id"),
            subEntities = mapOf("t1" to sub),
        )
        val join = ReverseJoin(
            type = cn.song.mybatisplus.visualizer.model.JoinType.LEFT,
            tableName = "user_address",
            alias = "t1",
            onConditions = listOf(
                Condition.ColumnComparison(
                    ColumnRef("t1", "user_id"),
                    ColumnRef("t", "id"),
                ),
            ),
            rawOn = "t1.user_id = t.id",
        )
        val result = SqlReverseResult(
            tableName = "user",
            selectColumns = listOf(SelectColumn("t.id")),
            joins = listOf(join),
        )
        val code = JavaCodeGenerator.generate(result, entity)
        // 无 ext 条件 → 3 参，无 ext lambda
        assertTrue("应生成 3 参 leftJoin", code.contains("leftJoin(AddressDO.class, AddressDO::getUserId, UserDO::getId)"))
        assertFalse("不应包含 ext lambda", code.contains("ext ->"))
    }

    @Test
    fun `ext条件_多ext条件`() {
        val sub = JavaCodeGenerator.EntityTarget(
            className = "StationInfoEntity",
            columnToField = mapOf("id" to "id", "station_name" to "stationName", "merchant_id" to "merchantId"),
        )
        val entity = JavaCodeGenerator.EntityTarget(
            className = "DeviceInfoEntity",
            columnToField = mapOf("id" to "id", "station_id" to "stationId"),
            subEntities = mapOf("ext" to sub),
        )
        val join = ReverseJoin(
            type = cn.song.mybatisplus.visualizer.model.JoinType.LEFT,
            tableName = "station_info",
            alias = "ext",
            onConditions = listOf(
                Condition.ColumnComparison(
                    ColumnRef("ext", "id"),
                    ColumnRef("t", "station_id"),
                ),
                Condition.Simple(
                    ColumnRef("ext", "station_name"),
                    "=",
                    SqlValue.Literal("'中心站'", "中心站"),
                ),
                Condition.Simple(
                    ColumnRef("ext", "merchant_id"),
                    ">=",
                    SqlValue.Literal("0"),
                ),
            ),
            rawOn = "ext.id = t.station_id AND ext.station_name = '中心站' AND ext.merchant_id >= 0",
        )
        val result = SqlReverseResult(
            tableName = "car_device_info",
            selectColumns = listOf(SelectColumn("t.id")),
            joins = listOf(join),
        )
        val code = JavaCodeGenerator.generate(result, entity)
        assertTrue("应生成 4 参 leftJoin", code.contains("leftJoin(StationInfoEntity.class,"))
        assertTrue("应包含 stationName ext", code.contains("ext.eq(StationInfoEntity::getStationName,"))
        assertTrue("应包含 merchantId ext", code.contains("ext.ge(StationInfoEntity::getMerchantId,"))
    }
}
