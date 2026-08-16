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
            selectColumns = listOf("id", "name"),
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
            // SQL → MyBatis-Plus QueryWrapper 反向生成（MyBatis-Plus SQL Visualizer）
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
            // SQL → MyBatis-Plus QueryWrapper 反向生成（MyBatis-Plus SQL Visualizer）
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
            selectColumns = listOf("id", "name"),
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
        assertTrue(code, code.contains("⚠ 无 WHERE 条件，将更新全表数据"))
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

    @Test
    fun `INSERT_生成实体setter链`() {
        val result = SqlReverseResult(
            tableName = "user",
            kind = ReverseStatementKind.INSERT,
            insertColumns = listOf("name", "age"),
            insertValues = listOf(SqlValue.Literal("'张三'", "张三"), SqlValue.Literal("18")),
        )
        val code = JavaCodeGenerator.generate(result, null, GeneratedWrapper.QUERY)
        assertTrue(code, code.contains("new User().setName(\"张三\")\n    .setAge(18);"))
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
        assertTrue(code, code.contains("List<User> list = UserService.list(wrapper);"))
    }

    @Test
    fun `SELECT带Mapper和VO_生成selectVoList`() {
        val result = SqlReverseResult(
            tableName = "user",
            selectColumns = null,
            conditions = listOf(Condition.Simple(ColumnRef(columnName = "name"), "=", SqlValue.Literal("'x'", "x"))),
        )
        val entity = JavaCodeGenerator.EntityTarget(className = "User", columnToField = mapOf("name" to "name"))
        val code = JavaCodeGenerator.generate(
            result, entity, GeneratedWrapper.LAMBDA,
            JavaCodeGenerator.InvocationTarget("UserMapper", voClass = "UserVo"),
        )
        assertTrue(code, code.contains("List<UserVo> list = UserMapper.selectVoList(wrapper, UserVo.class);"))
        assertTrue(code, code.contains("// VO 查询需 MyBatis-Plus-Join"))
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
        assertTrue(code, code.contains("UserMapper.update(null, wrapper);"))
    }

    @Test
    fun `INSERT带Service调用_生成save调用`() {
        val result = SqlReverseResult(
            tableName = "user",
            kind = ReverseStatementKind.INSERT,
            insertColumns = listOf("name"),
            insertValues = listOf(SqlValue.Literal("'张三'", "张三")),
        )
        val code = JavaCodeGenerator.generate(
            result, null, GeneratedWrapper.QUERY,
            JavaCodeGenerator.InvocationTarget("UserService"),
        )
        assertTrue(code, code.contains("User user = new User().setName(\"张三\");"))
        assertTrue(code, code.contains("UserService.save(user);"))
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
        assertTrue(code, code.contains(".leftJoin(\"b t1 ON t1.a_id = t.id AND t1.type = 1\")"))
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
}
