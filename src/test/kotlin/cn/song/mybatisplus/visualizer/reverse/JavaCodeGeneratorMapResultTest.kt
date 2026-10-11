package cn.song.mybatisplus.visualizer.reverse

import cn.song.mybatisplus.visualizer.model.ColumnRef
import cn.song.mybatisplus.visualizer.model.Condition
import cn.song.mybatisplus.visualizer.model.Connector
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
 * JavaCodeGenerator 的 Map 响应 / 返回类型 / ext 条件 / 占位符用例（自 JavaCodeGeneratorTest 拆出）
 * （T-M53-II 2026-10-10：逐字符原样搬运，零逻辑改动。）
 * （T-SyncK2JMaster482 C9b-1：Kotlin 调 Java 生产类，具名实参改位置实参；copy 改显式重建。）
 */
class JavaCodeGeneratorMapResultTest {

    @Test
    fun `分页VO组合Mapper_生成selectJoinPage`() {
        val result = SqlReverseResult(
            "user", null, emptyList(),
            emptyList(), LimitClause(SqlValue.Placeholder("?"), SqlValue.Placeholder("?")),
            emptyList(), ReverseStatementKind.SELECT,
            emptyList(), emptyList(), emptyList(), emptyList(), emptyList(),
        )
        val code = JavaCodeGenerator.generate(
            result, null, GeneratedWrapper.QUERY,
            JavaCodeGenerator.InvocationTarget("UserMapper", "UserVo", true),
        )
        assertTrue(code, code.contains("Page<UserVo> page = userMapper.selectJoinPage(new Page<>(1, 10), UserVo.class, wrapper);"))
    }

    @Test
    fun `VO场景_强制MPJ wrapper`() {
        // selectJoinList 参数类型为 MPJBaseJoin——LambdaQueryWrapper 无法编译，须强制 MPJ wrapper
        val result = SqlReverseResult(
            "user",
            null,
            listOf(Condition.Simple(ColumnRef("name"), "=", SqlValue.Literal("'x'", "x"), null)),
            emptyList(), null, emptyList(), ReverseStatementKind.SELECT,
            emptyList(), emptyList(), emptyList(), emptyList(), emptyList(),
        )
        val entity = JavaCodeGenerator.EntityTarget("User", mapOf("name" to "name"))
        val code = JavaCodeGenerator.generate(
            result, entity, GeneratedWrapper.LAMBDA,
            JavaCodeGenerator.InvocationTarget("UserService", "UserVo", true),
        )
        assertTrue(code, code.contains("new MPJLambdaWrapper<User>()"))
        assertTrue(code, code.contains("userService.selectJoinList(UserVo.class, wrapper);"))
        // 无实体 + VO → MPJQueryWrapper（字符串列）同样满足 MPJBaseJoin
        val plain = JavaCodeGenerator.generate(
            result, null, GeneratedWrapper.QUERY,
            JavaCodeGenerator.InvocationTarget("UserService", "UserVo", true),
        )
        assertTrue(plain, plain.contains("new MPJQueryWrapper<>(User.class)"))
    }

    @Test
    fun `非MPJ调用类_VO回落实体查询并注释`() {
        // 调用类无 MPJ 能力（mpj=false，继承链实证由调用方解析）→ 严禁生成 selectJoinList，
        // 回落 list + 明确注释；wrapper 保持普通形态（不强制 MPJ）
        val result = SqlReverseResult(
            "user",
            null,
            listOf(Condition.Simple(ColumnRef("id"), "=", SqlValue.Literal("1"), null)),
            emptyList(), null, emptyList(), ReverseStatementKind.SELECT,
            emptyList(), emptyList(), emptyList(), emptyList(), emptyList(),
        )
        val entity = JavaCodeGenerator.EntityTarget("User", mapOf("id" to "id"))
        val code = JavaCodeGenerator.generate(
            result, entity, GeneratedWrapper.LAMBDA,
            JavaCodeGenerator.InvocationTarget("UserService", "UserVo", false),
        )
        assertTrue(code, code.contains("List<User> list = userService.list(wrapper);"))
        assertTrue(code, code.contains("// 调用类无 MyBatis-Plus-Join（MPJ）能力，VO 查询不可用，已回落实体查询"))
        assertTrue(code, code.contains("new LambdaQueryWrapper<User>()"))
        assertTrue(code, !code.contains("selectJoinList"))
    }

    @Test
    fun `非MPJ调用类_分页VO回落实体page`() {
        val result = SqlReverseResult(
            "user", null, emptyList(),
            emptyList(), LimitClause(SqlValue.Placeholder("?"), SqlValue.Placeholder("?")),
            emptyList(), ReverseStatementKind.SELECT,
            emptyList(), emptyList(), emptyList(), emptyList(), emptyList(),
        )
        val code = JavaCodeGenerator.generate(
            result, null, GeneratedWrapper.QUERY,
            JavaCodeGenerator.InvocationTarget("UserMapper", "UserVo", false),
        )
        assertTrue(code, code.contains("Page<User> page = userMapper.selectPage(new Page<>(1, 10), wrapper);"))
        assertTrue(code, !code.contains("selectJoinPage"))
    }

    @Test
    fun `非MPJ调用类_多表JOIN报错`() {
        val result = SqlReverseResult(
            "user", null, emptyList(),
            emptyList(), null, emptyList(), ReverseStatementKind.SELECT,
            emptyList(), emptyList(), emptyList(),
            listOf(
                ReverseJoin(
                    cn.song.mybatisplus.visualizer.model.JoinType.LEFT,
                    "address",
                    "t1",
                    listOf(
                        Condition.ColumnComparison(ColumnRef("t1", "user_id"), ColumnRef("t", "id"), null),
                    ),
                    "t1.user_id = t.id",
                    null,
                ),
            ),
            emptyList(),
        )
        val entity = JavaCodeGenerator.EntityTarget(
            "User",
            mapOf("id" to "id"),
            mapOf("t1" to JavaCodeGenerator.EntityTarget("Address", mapOf("user_id" to "userId"))),
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
            "user",
            listOf(SelectColumn("id", null), SelectColumn("serial_version_u_i_d", null), SelectColumn("username", null)),
            listOf(
                Condition.Simple(ColumnRef("username"), "=", SqlValue.Literal("'admin'", "admin"), null),
            ),
            emptyList(), null, emptyList(), ReverseStatementKind.SELECT,
            emptyList(), emptyList(), emptyList(), emptyList(), emptyList(),
        )
        val code = JavaCodeGenerator.generate(result)
        assertTrue(code, code.contains(".select(\"id\", \"username\")"))
        assertTrue(code, !code.contains("serial_version_u_i_d"))
        assertTrue(code, !code.contains("serialversionuid"))
    }

    @Test
    fun `SELECT含serialVersionUID列_有实体_不生成getter引用`() {
        val result = SqlReverseResult(
            "user",
            listOf(SelectColumn("id", null), SelectColumn("serial_version_u_i_d", null), SelectColumn("username", null)),
            emptyList(), emptyList(), null, emptyList(), ReverseStatementKind.SELECT,
            emptyList(), emptyList(), emptyList(), emptyList(), emptyList(),
        )
        // 模拟旧行为：实体映射仍含该列（层 2 兜底须跳过，防 User::getSerialVersionUID 编译失败）
        val entity = JavaCodeGenerator.EntityTarget(
            "User",
            mapOf(
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
            "user",
            null,
            listOf(
                Condition.Simple(ColumnRef("id"), "=", SqlValue.Literal("1"), null),
                Condition.Simple(ColumnRef("serial_version_u_i_d"), "=", SqlValue.Literal("2"), null),
            ),
            emptyList(), null, emptyList(), ReverseStatementKind.SELECT,
            emptyList(), emptyList(), emptyList(), emptyList(), emptyList(),
        )
        val code = JavaCodeGenerator.generate(result)
        assertTrue(code, code.contains(".eq(\"id\", 1)"))
        assertTrue(code, !code.contains(".eq(\"serial_version_u_i_d\""))
        assertTrue(code, !code.contains("serial_version_u_i_d"))
    }

    @Test
    fun `WHERE仅serial_version_u_i_d_条件整组跳过`() {
        val result = SqlReverseResult(
            "user",
            null,
            listOf(
                Condition.Simple(ColumnRef("serial_version_u_i_d"), "=", SqlValue.Literal("1"), null),
            ),
            emptyList(), null, emptyList(), ReverseStatementKind.SELECT,
            emptyList(), emptyList(), emptyList(), emptyList(), emptyList(),
        )
        val code = JavaCodeGenerator.generate(result)
        assertTrue(code, !code.contains(".eq("))
        assertTrue(code, !code.contains("serial_version_u_i_d"))
    }

    @Test
    fun `WHERE_Group内serial_version_u_i_d跳过且不产生or分隔`() {
        val result = SqlReverseResult(
            "user",
            null,
            listOf(
                Condition.Group(
                    Connector.OR,
                    listOf(
                        Condition.Simple(ColumnRef("a"), "=", SqlValue.Literal("1"), null),
                        Condition.Simple(ColumnRef("serial_version_u_i_d"), "=", SqlValue.Literal("2"), null),
                    ),
                    null,
                ),
            ),
            emptyList(), null, emptyList(), ReverseStatementKind.SELECT,
            emptyList(), emptyList(), emptyList(), emptyList(), emptyList(),
        )
        val code = JavaCodeGenerator.generate(result)
        assertTrue(code, code.contains(".nested(w -> w\n        .eq(\"a\", 1))"))
        assertTrue(code, !code.contains(".or()"))
    }

    @Test
    fun `WHERE_Group仅serial_version_u_i_d_整组跳过`() {
        val result = SqlReverseResult(
            "user",
            null,
            listOf(
                Condition.Group(
                    Connector.OR,
                    listOf(
                        Condition.Simple(ColumnRef("serial_version_u_i_d"), "=", SqlValue.Literal("1"), null),
                    ),
                    null,
                ),
            ),
            emptyList(), null, emptyList(), ReverseStatementKind.SELECT,
            emptyList(), emptyList(), emptyList(), emptyList(), emptyList(),
        )
        val code = JavaCodeGenerator.generate(result)
        assertTrue(code, !code.contains("nested("))
        assertTrue(code, !code.contains("serial_version_u_i_d"))
    }

    @Test
    fun `ORDER BY含serial_version_u_i_d_跳过`() {
        val result = SqlReverseResult(
            "user", null, emptyList(),
            listOf(
                OrderByItem(ColumnRef("create_time"), false),
                OrderByItem(ColumnRef("serial_version_u_i_d"), true),
            ),
            null, emptyList(), ReverseStatementKind.SELECT,
            emptyList(), emptyList(), emptyList(), emptyList(), emptyList(),
        )
        val code = JavaCodeGenerator.generate(result)
        assertTrue(code, code.contains(".orderByDesc(\"create_time\")"))
        assertTrue(code, !code.contains("serial_version_u_i_d"))
    }

    @Test
    fun `ORDER BY仅serial_version_u_i_d_不生成orderBy调用`() {
        val result = SqlReverseResult(
            "user", null, emptyList(),
            listOf(OrderByItem(ColumnRef("serial_version_u_i_d"), true)),
            null, emptyList(), ReverseStatementKind.SELECT,
            emptyList(), emptyList(), emptyList(), emptyList(), emptyList(),
        )
        val code = JavaCodeGenerator.generate(result)
        assertTrue(code, !code.contains("orderBy"))
        assertTrue(code, !code.contains("serial_version_u_i_d"))
    }

    @Test
    fun `INSERT含serial_version_u_i_d_不生成setter且值对齐`() {
        val result = SqlReverseResult(
            "user", null, emptyList(),
            emptyList(), null, emptyList(), ReverseStatementKind.INSERT,
            emptyList(), listOf("name", "serial_version_u_i_d", "age"),
            listOf(
                listOf(SqlValue.Literal("'张三'", "张三"), SqlValue.Literal("1"), SqlValue.Literal("18")),
            ),
            emptyList(), emptyList(),
        )
        val code = JavaCodeGenerator.generate(result, null, GeneratedWrapper.QUERY, null)
        // 过滤后保留列 (name, age) 取值于原行对应下标（age 值 18 而非被跳过的 1）——值对齐
        assertTrue(code, code.contains("new User().setName(\"张三\")\n    .setAge(18);"))
        assertTrue(code, !code.contains("SerialVersionUid"))
        assertTrue(code, !code.contains("serial_version_u_i_d"))
    }

    @Test
    fun `INSERT仅serial_version_u_i_d_生成空实体保语句合法`() {
        val result = SqlReverseResult(
            "user", null, emptyList(),
            emptyList(), null, emptyList(), ReverseStatementKind.INSERT,
            emptyList(), listOf("serial_version_u_i_d"),
            listOf(listOf(SqlValue.Literal("1"))),
            emptyList(), emptyList(),
        )
        val code = JavaCodeGenerator.generate(result, null, GeneratedWrapper.QUERY, null)
        assertTrue(code, code.contains("new User();"))
        assertTrue(code, !code.contains("serial_version_u_i_d"))
    }

    @Test
    fun `UPDATE_SET含serial_version_u_i_d_跳过set`() {
        val result = SqlReverseResult(
            "user", null,
            listOf(Condition.Simple(ColumnRef("id"), "=", SqlValue.Literal("1"), null)),
            emptyList(), null, emptyList(), ReverseStatementKind.UPDATE,
            listOf(
                ReverseSet("name", SqlValue.Literal("'李四'", "李四"), null),
                ReverseSet("serial_version_u_i_d", SqlValue.Literal("2"), null),
            ),
            emptyList(), emptyList(), emptyList(), emptyList(),
        )
        val code = JavaCodeGenerator.generate(result, null, GeneratedWrapper.QUERY, null)
        assertTrue(code, code.contains(".set(\"name\", \"李四\")"))
        assertTrue(code, code.contains(".eq(\"id\", 1)"))
        assertTrue(code, !code.contains("serial_version_u_i_d"))
    }

    @Test
    fun `SELECT仅含serial_version_u_i_d_不生成select调用`() {
        // 全部列被过滤 → selectColumns 空 → 现有 isNotEmpty 分支自然回落（不生成 select，MP 全列语义）
        val result = SqlReverseResult(
            "user", listOf(SelectColumn("serial_version_u_i_d", null)),
            emptyList(), emptyList(), null, emptyList(), ReverseStatementKind.SELECT,
            emptyList(), emptyList(), emptyList(), emptyList(), emptyList(),
        )
        val code = JavaCodeGenerator.generate(result)
        assertTrue(code, !code.contains(".select("))
        assertTrue(code, !code.contains("serial_version_u_i_d"))
    }

    @Test
    fun `SELECT反引号与别名形态_同过滤`() {
        val result = SqlReverseResult(
            "user",
            listOf(SelectColumn("id", null), SelectColumn("`serial_version_u_i_d`", null), SelectColumn("t.serial_version_u_i_d", null), SelectColumn("SERIAL_VERSION_UID", null)),
            emptyList(), emptyList(), null, emptyList(), ReverseStatementKind.SELECT,
            emptyList(), emptyList(), emptyList(), emptyList(), emptyList(),
        )
        val code = JavaCodeGenerator.generate(result)
        assertTrue(code, code.contains(".select(\"id\")"))
        assertTrue(code, !code.contains("serial_version_u_i_d"))
        assertTrue(code, !code.contains("serialversionuid"))
    }

    @Test
    fun `正常业务列不受影响_serial_version_uid同形态过滤`() {
        val result = SqlReverseResult(
            "user",
            listOf(SelectColumn("username", null), SelectColumn("serial_version_uid", null)),
            listOf(
                Condition.Simple(ColumnRef("username"), "=", SqlValue.Literal("'admin'", "admin"), null),
            ),
            listOf(OrderByItem(ColumnRef("id"), true)),
            null, emptyList(), ReverseStatementKind.SELECT,
            emptyList(), emptyList(), emptyList(), emptyList(), emptyList(),
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
            "user", null, emptyList(),
            emptyList(), LimitClause(SqlValue.Placeholder("?"), SqlValue.Placeholder("?")),
            emptyList(), ReverseStatementKind.SELECT,
            emptyList(), emptyList(), emptyList(), emptyList(), emptyList(),
        )
        val code = JavaCodeGenerator.generate(
            result, null, GeneratedWrapper.QUERY,
            JavaCodeGenerator.InvocationTarget("UserMapper", null, false, setOf("selectPage", "selectList")),
        )
        assertTrue(code, code.contains("Page<User> page = userMapper.selectPage(new Page<>(1, 10), wrapper);"))
    }

    @Test
    fun `mapper方法集仅join_分页回落selectJoinPage带Class参数`() {
        val result = SqlReverseResult(
            "user", null, emptyList(),
            emptyList(), LimitClause(SqlValue.Placeholder("?"), SqlValue.Placeholder("?")),
            emptyList(), ReverseStatementKind.SELECT,
            emptyList(), emptyList(), emptyList(), emptyList(), emptyList(),
        )
        val code = JavaCodeGenerator.generate(
            result, null, GeneratedWrapper.QUERY,
            JavaCodeGenerator.InvocationTarget("UserMapper", null, false, setOf("selectJoinPage", "selectJoinList")),
        )
        assertTrue(code, code.contains("Page<User> page = userMapper.selectJoinPage(new Page<>(1, 10), User.class, wrapper);"))
    }

    @Test
    fun `mapper方法集空_保守生成普通方法`() {
        val result = SqlReverseResult(
            "user", null, emptyList(),
            emptyList(), LimitClause(SqlValue.Placeholder("?"), SqlValue.Placeholder("?")),
            emptyList(), ReverseStatementKind.SELECT,
            emptyList(), emptyList(), emptyList(), emptyList(), emptyList(),
        )
        val code = JavaCodeGenerator.generate(
            result, null, GeneratedWrapper.QUERY,
            JavaCodeGenerator.InvocationTarget("UserMapper"),
        )
        assertTrue(code, code.contains("Page<User> page = userMapper.selectPage(new Page<>(1, 10), wrapper);"))
    }

    @Test
    fun `mapper列表回落selectJoinList`() {
        val result = SqlReverseResult(
            "user", null, emptyList(), emptyList(), null, emptyList(), ReverseStatementKind.SELECT,
            emptyList(), emptyList(), emptyList(), emptyList(), emptyList(),
        )
        val code = JavaCodeGenerator.generate(
            result, null, GeneratedWrapper.QUERY,
            JavaCodeGenerator.InvocationTarget("UserMapper", null, false, setOf("selectJoinList")),
        )
        assertTrue(code, code.contains("List<User> list = userMapper.selectJoinList(User.class, wrapper);"))
    }

    @Test
    fun `service分页回落selectJoinListPage`() {
        val result = SqlReverseResult(
            "user", null, emptyList(),
            emptyList(), LimitClause(SqlValue.Placeholder("?"), SqlValue.Placeholder("?")),
            emptyList(), ReverseStatementKind.SELECT,
            emptyList(), emptyList(), emptyList(), emptyList(), emptyList(),
        )
        val code = JavaCodeGenerator.generate(
            result, null, GeneratedWrapper.QUERY,
            JavaCodeGenerator.InvocationTarget("UserService", null, false, setOf("selectJoinListPage")),
        )
        assertTrue(code, code.contains("Page<User> page = userService.selectJoinListPage(new Page<>(1, 10), User.class, wrapper);"))
    }

    @Test
    fun `service列表回落selectJoinList`() {
        val result = SqlReverseResult(
            "user", null, emptyList(), emptyList(), null, emptyList(), ReverseStatementKind.SELECT,
            emptyList(), emptyList(), emptyList(), emptyList(), emptyList(),
        )
        val code = JavaCodeGenerator.generate(
            result, null, GeneratedWrapper.QUERY,
            JavaCodeGenerator.InvocationTarget("UserService", null, false, setOf("selectJoinList")),
        )
        assertTrue(code, code.contains("List<User> list = userService.selectJoinList(User.class, wrapper);"))
    }

    @Test
    fun `update缺失回落updateJoin`() {
        val result = SqlReverseResult(
            "user", null,
            listOf(Condition.Simple(ColumnRef("id"), "=", SqlValue.Literal("1"), null)),
            emptyList(), null, emptyList(), ReverseStatementKind.UPDATE,
            listOf(ReverseSet("name", SqlValue.Literal("'x'", "x"), null)),
            emptyList(), emptyList(), emptyList(), emptyList(),
        )
        val mapper = JavaCodeGenerator.generate(
            result, null, GeneratedWrapper.QUERY,
            JavaCodeGenerator.InvocationTarget("UserMapper", null, false, setOf("updateJoin")),
        )
        assertTrue(mapper, mapper.contains("userMapper.updateJoin(null, wrapper);"))
        val service = JavaCodeGenerator.generate(
            result, null, GeneratedWrapper.QUERY,
            JavaCodeGenerator.InvocationTarget("UserService", null, false, setOf("updateJoin")),
        )
        assertTrue(service, service.contains("userService.updateJoin(null, wrapper);"))
    }

    @Test
    fun `delete缺失回落deleteJoin`() {
        val result = SqlReverseResult(
            "user", null,
            listOf(Condition.Simple(ColumnRef("id"), "=", SqlValue.Literal("1"), null)),
            emptyList(), null, emptyList(), ReverseStatementKind.DELETE,
            emptyList(), emptyList(), emptyList(), emptyList(), emptyList(),
        )
        val mapper = JavaCodeGenerator.generate(
            result, null, GeneratedWrapper.QUERY,
            JavaCodeGenerator.InvocationTarget("UserMapper", null, false, setOf("deleteJoin")),
        )
        assertTrue(mapper, mapper.contains("userMapper.deleteJoin(wrapper);"))
        val service = JavaCodeGenerator.generate(
            result, null, GeneratedWrapper.QUERY,
            JavaCodeGenerator.InvocationTarget("UserService", null, false, setOf("deleteJoin")),
        )
        assertTrue(service, service.contains("userService.deleteJoin(wrapper);"))
    }

    @Test
    fun `VO分页方法集含selectJoinPage_保持不变`() {
        val result = SqlReverseResult(
            "user", null, emptyList(),
            emptyList(), LimitClause(SqlValue.Placeholder("?"), SqlValue.Placeholder("?")),
            emptyList(), ReverseStatementKind.SELECT,
            emptyList(), emptyList(), emptyList(), emptyList(), emptyList(),
        )
        val code = JavaCodeGenerator.generate(
            result, null, GeneratedWrapper.QUERY,
            JavaCodeGenerator.InvocationTarget("UserMapper", "UserVo", true, setOf("selectJoinPage")),
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
            cn.song.mybatisplus.visualizer.model.JoinType.LEFT,
            "(SELECT tt.id, tt.station_id, tt.station_name, tt.merchant_id\n    FROM station_info tt\n    WHERE tt.merchant_id >= 0)",
            null,
            listOf(
                Condition.ColumnComparison(
                    ColumnRef("tt", "station_id"),
                    ColumnRef("t", "station_id"),
                    null,
                ),
            ),
            "tt.station_id = t.station_id",
            null,
        )
        val result = SqlReverseResult(
            "car_device_info",
            listOf(SelectColumn("t.id", null), SelectColumn("t.station_id", null)),
            listOf(
                Condition.Simple(ColumnRef("t", "id"), "=", SqlValue.Literal("1"), null),
            ),
            emptyList(), null, emptyList(), ReverseStatementKind.SELECT,
            emptyList(), emptyList(), emptyList(),
            listOf(join),
            emptyList(),
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
            "StationInfoEntity",
            mapOf("id" to "id", "station_name" to "stationName"),
        )
        val entity = JavaCodeGenerator.EntityTarget(
            "DeviceInfoEntity",
            mapOf("id" to "id", "station_id" to "stationId"),
            mapOf("ext" to sub),
        )
        val join = ReverseJoin(
            cn.song.mybatisplus.visualizer.model.JoinType.LEFT,
            "station_info",
            "ext",
            listOf(
                Condition.ColumnComparison(
                    ColumnRef("ext", "id"),
                    ColumnRef("t", "station_id"),
                    null,
                ),
                Condition.Simple(
                    ColumnRef("ext", "station_name"),
                    "=",
                    SqlValue.Literal("'中心站'", "中心站"),
                    null,
                ),
            ),
            "ext.id = t.station_id AND ext.station_name = '中心站'",
            null,
        )
        val result = SqlReverseResult(
            "car_device_info",
            listOf(SelectColumn("t.id", null)),
            emptyList(), emptyList(), null, emptyList(), ReverseStatementKind.SELECT,
            emptyList(), emptyList(), emptyList(),
            listOf(join),
            emptyList(),
        )
        val code = JavaCodeGenerator.generate(result, entity)
        assertTrue("应生成 4 参 leftJoin", code.contains("leftJoin(StationInfoEntity.class, StationInfoEntity::getId, DeviceInfoEntity::getStationId,"))
        assertTrue("应包含 ext lambda", code.contains("ext -> ext.eq(StationInfoEntity::getStationName,"))
        assertTrue("应包含 ext 值", code.contains("\"中心站\""))
    }

    @Test
    fun `ext条件_无ext仍生成3参`() {
        val sub = JavaCodeGenerator.EntityTarget(
            "AddressDO",
            mapOf("user_id" to "userId"),
        )
        val entity = JavaCodeGenerator.EntityTarget(
            "UserDO",
            mapOf("id" to "id"),
            mapOf("t1" to sub),
        )
        val join = ReverseJoin(
            cn.song.mybatisplus.visualizer.model.JoinType.LEFT,
            "user_address",
            "t1",
            listOf(
                Condition.ColumnComparison(
                    ColumnRef("t1", "user_id"),
                    ColumnRef("t", "id"),
                    null,
                ),
            ),
            "t1.user_id = t.id",
            null,
        )
        val result = SqlReverseResult(
            "user",
            listOf(SelectColumn("t.id", null)),
            emptyList(), emptyList(), null, emptyList(), ReverseStatementKind.SELECT,
            emptyList(), emptyList(), emptyList(),
            listOf(join),
            emptyList(),
        )
        val code = JavaCodeGenerator.generate(result, entity)
        // 无 ext 条件 → 3 参，无 ext lambda
        assertTrue("应生成 3 参 leftJoin", code.contains("leftJoin(AddressDO.class, AddressDO::getUserId, UserDO::getId)"))
        assertFalse("不应包含 ext lambda", code.contains("ext ->"))
    }

    @Test
    fun `ext条件_多ext条件`() {
        val sub = JavaCodeGenerator.EntityTarget(
            "StationInfoEntity",
            mapOf("id" to "id", "station_name" to "stationName", "merchant_id" to "merchantId"),
        )
        val entity = JavaCodeGenerator.EntityTarget(
            "DeviceInfoEntity",
            mapOf("id" to "id", "station_id" to "stationId"),
            mapOf("ext" to sub),
        )
        val join = ReverseJoin(
            cn.song.mybatisplus.visualizer.model.JoinType.LEFT,
            "station_info",
            "ext",
            listOf(
                Condition.ColumnComparison(
                    ColumnRef("ext", "id"),
                    ColumnRef("t", "station_id"),
                    null,
                ),
                Condition.Simple(
                    ColumnRef("ext", "station_name"),
                    "=",
                    SqlValue.Literal("'中心站'", "中心站"),
                    null,
                ),
                Condition.Simple(
                    ColumnRef("ext", "merchant_id"),
                    ">=",
                    SqlValue.Literal("0"),
                    null,
                ),
            ),
            "ext.id = t.station_id AND ext.station_name = '中心站' AND ext.merchant_id >= 0",
            null,
        )
        val result = SqlReverseResult(
            "car_device_info",
            listOf(SelectColumn("t.id", null)),
            emptyList(), emptyList(), null, emptyList(), ReverseStatementKind.SELECT,
            emptyList(), emptyList(), emptyList(),
            listOf(join),
            emptyList(),
        )
        val code = JavaCodeGenerator.generate(result, entity)
        assertTrue("应生成 4 参 leftJoin", code.contains("leftJoin(StationInfoEntity.class,"))
        assertTrue("应包含 stationName ext", code.contains("ext.eq(StationInfoEntity::getStationName,"))
        assertTrue("应包含 merchantId ext", code.contains("ext.ge(StationInfoEntity::getMerchantId,"))
    }

    // ------------------------------------------------------------------
    // T-MapResultVo：Map 响应形态（selectJoinMaps/selectMaps/listMaps 族）
    // ------------------------------------------------------------------

    private fun mapJoinResult() = SqlReverseResult(
        "car_device_info",
        null,
        listOf(Condition.Simple(ColumnRef("t", "id"), "=", SqlValue.Literal("1"), null)),
        emptyList(), null, emptyList(), ReverseStatementKind.SELECT,
        emptyList(), emptyList(), emptyList(),
        listOf(
            ReverseJoin(
                cn.song.mybatisplus.visualizer.model.JoinType.LEFT,
                "station_info",
                "t1",
                listOf(
                    Condition.ColumnComparison(ColumnRef("t1", "id"), ColumnRef("t", "station_id"), null),
                ),
                "t1.id = t.station_id",
                null,
            ),
        ),
        emptyList(),
    )

    @Test
    fun `Map响应_join生成selectJoinMaps_无Class参数`() {
        val entity = JavaCodeGenerator.EntityTarget(
            "DeviceInfoEntity",
            mapOf("id" to "id", "station_id" to "stationId"),
            mapOf("t1" to JavaCodeGenerator.EntityTarget("StationInfoEntity", mapOf("id" to "id"))),
        )
        val code = JavaCodeGenerator.generate(
            mapJoinResult(), entity, GeneratedWrapper.MPJ_LAMBDA,
            JavaCodeGenerator.InvocationTarget("DeviceInfoMapper", null, true, setOf(), true, null),
        )
        assertTrue(code, code.contains("List<Map<String, Object>> list = deviceInfoMapper.selectJoinMaps(wrapper);"))
        // Maps 族不带 Class 参数（1.5.8 javap 实证）
        assertTrue(code, !code.contains(".class, wrapper)"))
    }

    @Test
    fun `Map响应_分页生成selectJoinMapsPage`() {
        val entity = JavaCodeGenerator.EntityTarget(
            "DeviceInfoEntity",
            mapOf("id" to "id"),
            mapOf("t1" to JavaCodeGenerator.EntityTarget("StationInfoEntity", mapOf("id" to "id"))),
        )
        // mapJoinResult().copy(limit = ...) 的 Java 形态：显式重建（record 无 data-class copy）
        val base = mapJoinResult()
        val result = SqlReverseResult(
            base.tableName(), base.selectColumns(), base.conditions(), base.orderBy(),
            LimitClause(null, SqlValue.Literal("10")),
            base.warnings(), base.kind(), base.sets(), base.insertColumns(), base.insertValues(),
            base.joins(), base.deleteTargets(), base.tableAlias(), base.distinct(),
            base.groupBy(), base.having(), base.unions(), base.forUpdate(),
        )
        val code = JavaCodeGenerator.generate(
            result, entity, GeneratedWrapper.MPJ_LAMBDA,
            JavaCodeGenerator.InvocationTarget("DeviceInfoMapper", null, true, setOf(), true, null),
        )
        assertTrue(code, code.contains("Page<Map<String, Object>> page = deviceInfoMapper.selectJoinMapsPage(new Page<>(1, 10), wrapper);"))
    }

    @Test
    fun `Map响应_单表Mapper回落selectMaps`() {
        val result = SqlReverseResult(
            "user",
            null,
            listOf(Condition.Simple(ColumnRef("status"), "=", SqlValue.Literal("1"), null)),
            emptyList(), null, emptyList(), ReverseStatementKind.SELECT,
            emptyList(), emptyList(), emptyList(), emptyList(), emptyList(),
        )
        val code = JavaCodeGenerator.generate(
            result, null, GeneratedWrapper.QUERY,
            JavaCodeGenerator.InvocationTarget("UserMapper", null, false, setOf(), true, null),
        )
        assertTrue(code, code.contains("List<Map<String, Object>> list = userMapper.selectMaps(wrapper);"))
    }

    @Test
    fun `Map响应_VO驱动select列别名_不进调用行Class`() {
        val entity = JavaCodeGenerator.EntityTarget(
            "DeviceInfoEntity",
            mapOf("id" to "id", "station_id" to "stationId"),
            mapOf("t1" to JavaCodeGenerator.EntityTarget("StationInfoEntity", mapOf("id" to "id", "station_name" to "stationName"))),
        )
        // VO 驱动 selectAs 列别名（Map key）需显式列+AS 别名（SelectColumn.alias 作 VO 属性名，T-M47 语义）
        val base = mapJoinResult()
        val result = SqlReverseResult(
            base.tableName(),
            listOf(
                SelectColumn("t.id", "id"),
                SelectColumn("t1.station_name", "stationName"),
            ),
            base.conditions(), base.orderBy(), base.limit(), base.warnings(), base.kind(), base.sets(),
            base.insertColumns(), base.insertValues(), base.joins(), base.deleteTargets(), base.tableAlias(),
            base.distinct(), base.groupBy(), base.having(), base.unions(), base.forUpdate(),
        )
        val code = JavaCodeGenerator.generate(
            result, entity, GeneratedWrapper.MPJ_LAMBDA,
            JavaCodeGenerator.InvocationTarget("DeviceInfoMapper", "DeviceInfoVo", true, setOf(), true, null),
        )
        assertTrue(code, code.contains("selectAs(DeviceInfoEntity::getId, DeviceInfoVo::getId)"))
        assertTrue(code, code.contains("selectAs(StationInfoEntity::getStationName, DeviceInfoVo::getStationName)"))
        assertTrue(code, code.contains("selectJoinMaps(wrapper);"))
        assertTrue(code, !code.contains("DeviceInfoVo.class"))
    }

    // ------------------------------------------------------------------
    // T-MapResultVo v2：返回类型声明派生（自由键入形态）
    // ------------------------------------------------------------------

    @Test
    fun `返回类型_HashMap声明_归Maps族且前缀按方法真实返回类型`() {
        val entity = JavaCodeGenerator.EntityTarget(
            "DeviceInfoEntity",
            mapOf("id" to "id"),
            mapOf("t1" to JavaCodeGenerator.EntityTarget("StationInfoEntity", mapOf("id" to "id"))),
        )
        val code = JavaCodeGenerator.generate(
            mapJoinResult(), entity, GeneratedWrapper.MPJ_LAMBDA,
            JavaCodeGenerator.InvocationTarget("DeviceInfoMapper", null, true, setOf(), false, "List<HashMap<String, Object>>"),
        )
        // 动态泛型（HashMap）归 Maps 族；声明前缀按方法真实返回类型（List<Map<..>>——编译正确形态）
        assertTrue(code, code.contains("selectJoinMaps(wrapper);"))
        assertTrue(code, code.contains("List<Map<String, Object>> list ="))
        assertTrue(code, !code.contains("HashMap"))
    }

    @Test
    fun `返回类型_Object声明单表_生成selectObjs`() {
        val result = SqlReverseResult(
            "user",
            null,
            listOf(Condition.Simple(ColumnRef("status"), "=", SqlValue.Literal("1"), null)),
            emptyList(), null, emptyList(), ReverseStatementKind.SELECT,
            emptyList(), emptyList(), emptyList(), emptyList(), emptyList(),
        )
        val code = JavaCodeGenerator.generate(
            result, null, GeneratedWrapper.QUERY,
            JavaCodeGenerator.InvocationTarget("UserMapper", null, false, setOf(), false, "List<Object>"),
        )
        assertTrue(code, code.contains("List<Object> list = userMapper.selectObjs(wrapper);"))
    }

    @Test
    fun `返回类型_Object声明JOIN场景_报错指路`() {
        val entity = JavaCodeGenerator.EntityTarget(
            "DeviceInfoEntity",
            mapOf("id" to "id"),
            mapOf("t1" to JavaCodeGenerator.EntityTarget("StationInfoEntity", mapOf("id" to "id"))),
        )
        val ex = try {
            JavaCodeGenerator.generate(
                mapJoinResult(), entity, GeneratedWrapper.MPJ_LAMBDA,
                JavaCodeGenerator.InvocationTarget("DeviceInfoMapper", null, true, setOf(), false, "List<Object>"),
            )
            throw AssertionError("expected SqlReverseException")
        } catch (e: cn.song.mybatisplus.visualizer.reverse.SqlReverseException) {
            e
        }
        assertTrue(ex.detail, ex.detail.contains("selectJoinObjs"))
        assertTrue(ex.detail, ex.detail.contains("Map"))
    }

    @Test
    fun `返回类型_类名文本_走VO语义含Class参数与别名`() {
        val entity = JavaCodeGenerator.EntityTarget(
            "DeviceInfoEntity",
            mapOf("id" to "id", "station_id" to "stationId"),
            mapOf("t1" to JavaCodeGenerator.EntityTarget("StationInfoEntity", mapOf("id" to "id", "station_name" to "stationName"))),
        )
        val base = mapJoinResult()
        val result = SqlReverseResult(
            base.tableName(),
            listOf(SelectColumn("t1.station_name", "stationName")),
            base.conditions(), base.orderBy(), base.limit(), base.warnings(), base.kind(), base.sets(),
            base.insertColumns(), base.insertValues(), base.joins(), base.deleteTargets(), base.tableAlias(),
            base.distinct(), base.groupBy(), base.having(), base.unions(), base.forUpdate(),
        )
        val code = JavaCodeGenerator.generate(
            result, entity, GeneratedWrapper.MPJ_LAMBDA,
            JavaCodeGenerator.InvocationTarget("DeviceInfoMapper", null, true, setOf(), false, "DeviceInfoVo"),
        )
        assertTrue(code, code.contains("selectJoinList(DeviceInfoVo.class, wrapper);"))
        assertTrue(code, code.contains("List<DeviceInfoVo> list ="))
        assertTrue(code, code.contains("selectAs(StationInfoEntity::getStationName, DeviceInfoVo::getStationName)"))
    }
}
