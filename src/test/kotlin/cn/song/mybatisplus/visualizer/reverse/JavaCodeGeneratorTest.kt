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
    fun `UI005_双引号表名只在Java类名推导剥壳`() {
        val result = SqlReverseParser.parse("SELECT \"id\" FROM \"ui_probe\" WHERE \"name\" = 'alpha'")
        val code = JavaCodeGenerator.generate(result)
        assertEquals("UiProbe", JavaCodeGenerator.entityClassName(result.tableName))
        GeneratedProjectionCompiler.assertProjection(code, "\"id\"", "\"name\" =")
    }

    @Test
    fun `UI005_聚合投影在所有MP核心形态真实编译且不覆盖`() {
        val sql = "SELECT status, COUNT(*) AS cnt FROM user GROUP BY status HAVING COUNT(*) > 1"
        val result = SqlReverseParser.parse(sql)
        val kinds = listOf(GeneratedWrapper.QUERY, GeneratedWrapper.LAMBDA,
            GeneratedWrapper.WRAPPERS_QUERY, GeneratedWrapper.WRAPPERS_LAMBDA)
        for (mapping in listOf(emptyMap(), mapOf("status" to "status"))) {
            for (kind in kinds) {
                val code = JavaCodeGenerator.generate(result, JavaCodeGenerator.EntityTarget("User", mapping), kind)
                GeneratedProjectionCompiler.assertProjection(code, "status,COUNT(*) AS cnt", "GROUP BY status", "HAVING COUNT(*) > 1")
            }
        }
    }

    @Test
    fun `UI005_别名及无别名聚合与DISTINCT保留查询语义`() {
        val entity = JavaCodeGenerator.EntityTarget("User", mapOf("status" to "status", "id" to "id"))
        val cases = listOf(
            "SELECT status, COUNT(*) FROM user GROUP BY status" to "status,COUNT(*)",
            "SELECT status AS state, id AS uid FROM user" to "status AS state,id AS uid",
            "SELECT DISTINCT status, id FROM user" to "DISTINCT status, id",
            "SELECT status, COUNT(*) AS cnt FROM user WHERE id > 1 GROUP BY status HAVING COUNT(*) > 1 ORDER BY status DESC LIMIT 5" to "status,COUNT(*) AS cnt",
        )
        for ((sql, projection) in cases) {
            for (kind in listOf(GeneratedWrapper.LAMBDA, GeneratedWrapper.QUERY, GeneratedWrapper.WRAPPERS_LAMBDA)) {
                val code = JavaCodeGenerator.generate(SqlReverseParser.parse(sql), entity, kind)
                if (sql.contains("WHERE")) {
                    GeneratedProjectionCompiler.assertProjection(code, projection, "id >", "GROUP BY status", "HAVING COUNT(*) > 1", "ORDER BY status DESC", "LIMIT 5")
                } else {
                    GeneratedProjectionCompiler.assertProjection(code, projection)
                }
            }
        }
    }

    @Test
    fun `UI005_未映射分组整链回落且保留实体类名`() {
        val code = JavaCodeGenerator.generate(
            SqlReverseParser.parse("SELECT id FROM user GROUP BY status"),
            JavaCodeGenerator.EntityTarget("User", mapOf("id" to "id")),
        )
        GeneratedProjectionCompiler.assertProjection(code, "id", "GROUP BY status")
    }

    @Test
    fun `UI005_真实API反对照拒绝Lambda字符串并证实select覆盖`() {
        GeneratedProjectionCompiler.assertDoesNotCompile("new LambdaQueryWrapper<User>().select(\"status\");")
        GeneratedProjectionCompiler.assertDoesNotCompile("new LambdaQueryWrapper<User>().groupBy(\"status\");")
        GeneratedProjectionCompiler.assertProjection(
            "new QueryWrapper<User>().select(\"status\").select(\"COUNT(*) AS cnt\").groupBy(\"status\").having(\"COUNT(*) > 1\");",
            "COUNT(*) AS cnt", "GROUP BY status", "HAVING COUNT(*) > 1",
        )
    }

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
    fun `列名驼峰下划线归一_snake列查camel键`() {
        // T-FixCamelMap：实体列名键为驼峰（如 @TableField("roleId") 显式形态）而 SQL 列为 snake（role_id）
        // → 归一命中生成 lambda，不再回落字符串列名
        val result = SqlReverseResult(
            tableName = "user",
            selectColumns = null,
            conditions = listOf(
                Condition.Simple(ColumnRef(columnName = "role_id"), "=", SqlValue.Literal("1")),
            ),
        )
        val entity = JavaCodeGenerator.EntityTarget(className = "User", columnToField = mapOf("roleId" to "roleId"))
        val code = JavaCodeGenerator.generate(result, entity)
        assertTrue(code, code.contains(".eq(User::getRoleId, 1)"))
        assertTrue(code, !code.contains("\"role_id\""))
    }

    @Test
    fun `列名驼峰下划线归一_camel列查snake键`() {
        // T-FixCamelMap 反向：SQL 列为驼峰（roleId）而实体列名键为 snake（role_id，默认命名策略形态）
        // → 归一命中，不再回落字符串
        val result = SqlReverseResult(
            tableName = "user",
            selectColumns = null,
            conditions = listOf(
                Condition.Simple(ColumnRef(columnName = "roleId"), "=", SqlValue.Literal("1")),
            ),
        )
        val entity = JavaCodeGenerator.EntityTarget(className = "User", columnToField = mapOf("role_id" to "roleId"))
        val code = JavaCodeGenerator.generate(result, entity)
        assertTrue(code, code.contains(".eq(User::getRoleId, 1)"))
        assertTrue(code, !code.contains("\"roleId\""))
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
}
