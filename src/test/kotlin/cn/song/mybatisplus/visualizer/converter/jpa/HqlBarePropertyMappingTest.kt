package cn.song.mybatisplus.visualizer.converter.jpa

import cn.song.mybatisplus.visualizer.model.ColumnInfo
import cn.song.mybatisplus.visualizer.model.ColumnRole
import cn.song.mybatisplus.visualizer.model.TableInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * O-71 无别名 HQL 属性→列名映射测试（用户报障场景复现 + T-01/T-02 验证 + 负例矩阵）。
 *
 * 用户报障：`from WorkstationStatus where stationCode = :stationCode and updateInfo.beDelete = 0`
 * 预览产物 WHERE 条件未转列名——`stationCode` 原样残留（T-01：裸属性不进映射）、
 * `updateInfo.BE_DELETE` 嵌入段前缀残留（T-02：嵌入段占限定符位时剥离判据够不着）。
 * 本类用手工 TableInfo 走纯字符串管道（免平台依赖），LightPlatform 夹具侧的同题验证
 * 与双链一致性见 [HqlAliasColumnMappingTest]。
 *
 * 契约变更登记：O-57 以「只收窄不丢失」为由保守排除裸属性（替换正则要求带点号），
 * O-71 实测证明该排除使无别名/漏写前缀的 HQL 的 WHERE/SELECT 投影永不映射，
 * 产物必为无效 SQL（Hibernate 真实生成的列名是大写下划线），故推翻——
 * 裸属性按根实体列清单**精确命中**才替换，不猜命名规则。
 */
class HqlBarePropertyMappingTest {

    /** 用户实体同构 TableInfo：驼峰自有字段 + 父类链 @Embedded updateInfo 展平列 + 同名列。 */
    private val workstationTable = TableInfo(
        "WorkstationStatus",
        "WORKSTATION_STATUS",
        listOf(
            ColumnInfo("id", "ID", true),
            ColumnInfo("creatorUnikey", "CREATOR_UNIKEY"),
            ColumnInfo("creator", "CREATOR"),
            ColumnInfo("createdTime", "CREATED_TIME"),
            ColumnInfo("lastOperatorUnikey", "LAST_OPERATOR_UNIKEY"),
            ColumnInfo("lastOperator", "LAST_OPERATOR"),
            ColumnInfo("updateTime", "UPDATE_TIME"),
            ColumnInfo("beDelete", "BE_DELETE", false, false, null, null, null,
                ColumnRole.BUSINESS, true, true, null, true, false, "updateInfo"),
            ColumnInfo("version", "VERSION"),
            ColumnInfo("boxNo", "BOX_NO"),
            ColumnInfo("stationCode", "STATION_CODE"),
            ColumnInfo("errorMsg", "ERROR_MSG"),
            ColumnInfo("remark", "REMARK"),
            ColumnInfo("status", "status"),
        ),
    )

    private fun convert(hql: String): HqlConverter.Result = HqlConverter.convert(hql, workstationTable)

    // ------------------------------------------------------------------
    // 用户报障场景（改前必红）
    // ------------------------------------------------------------------

    /** T-01 + T-02：裸属性映射为列名、嵌入段限定符位剥离为裸列名。 */
    @Test
    fun test用户场景_裸属性映射嵌入段剥离() {
        val result = convert(
            "from WorkstationStatus where stationCode = :stationCode and updateInfo.beDelete = 0",
        )
        assertTrue("裸属性应映射为列名: ${result.sql}", result.sql.contains("STATION_CODE = ?"))
        assertTrue("嵌入段限定符位应剥离为裸列名: ${result.sql}", result.sql.contains("BE_DELETE = 0"))
        assertFalse("不得残留裸属性名: ${result.sql}", result.sql.contains("stationCode ="))
        assertFalse("不得残留嵌入段（无效 SQL 形态）: ${result.sql}", result.sql.contains("updateInfo"))
    }

    /** 带别名写法的行为与改前逐字一致（零回归面）：属性映射 + 嵌入段展平照旧。 */
    @Test
    fun test带别名对照_行为不变() {
        val result = convert(
            "from WorkstationStatus w where w.stationCode = :stationCode and w.updateInfo.beDelete = 0",
        )
        assertTrue("带别名属性应映射: ${result.sql}", result.sql.contains("w.STATION_CODE = ?"))
        assertTrue("带别名嵌入段应展平: ${result.sql}", result.sql.contains("w.BE_DELETE = 0"))
        assertFalse("不得残留嵌入段: ${result.sql}", result.sql.contains("updateInfo"))
    }

    // ------------------------------------------------------------------
    // T-01 负例矩阵：只替换「精确命中字段名」的裸词
    // ------------------------------------------------------------------

    /** 布尔/常量字面量与 SQL 关键字不误转；字面量值 `true` 原样。 */
    @Test
    fun test布尔与常量关键字不误转() {
        val result = convert("from WorkstationStatus where beDelete = true and version between 1 and 2")
        assertTrue("裸属性仍应映射: ${result.sql}", result.sql.contains("BE_DELETE = true"))
        assertTrue("between/and 关键字原样: ${result.sql}", result.sql.contains("between 1 and 2"))
    }

    /** 函数调用名不误转（词后紧跟括号）；投影里的裸属性同样映射（同源改善）。 */
    @Test
    fun test函数调用不误转_投影裸属性照常映射() {
        val result = convert(
            "select count(stationCode) from WorkstationStatus where stationCode = :code",
        )
        assertTrue("count 函数名原样: ${result.sql}", result.sql.contains("count(STATION_CODE)"))
        assertTrue("WHERE 裸属性应映射: ${result.sql}", result.sql.contains("STATION_CODE = ?"))
        assertFalse("不得残留属性名: ${result.sql}", result.sql.contains("stationCode"))
    }

    /** 单引号字面量内的裸词不得被替换（与点号路径同款字面量保护口径）。 */
    @Test
    fun test字面量内裸词不转() {
        val result = convert("from WorkstationStatus where remark = 'stationCode and beDelete'")
        assertTrue("字面量应逐字未变: ${result.sql}", result.sql.contains("'stationCode and beDelete'"))
    }

    /** 裸词撞别名（knownQualifiers）不误转；其他字段照常映射。 */
    @Test
    fun test别名不误转_裸词撞别名() {
        val result = convert("from WorkstationStatus remark where remark = 'x' and boxNo = 1")
        assertTrue("别名位置的词不得被当属性替换: ${result.sql}", result.sql.contains("remark = 'x'"))
        assertTrue("其他裸属性应映射: ${result.sql}", result.sql.contains("BOX_NO = 1"))
    }

    /** fieldName == columnName 的属性透传（替换无意义），不产生二次变形。 */
    @Test
    fun test同名列透传() {
        val result = convert("from WorkstationStatus where status = 1")
        assertTrue("同名字段应透传: ${result.sql}", result.sql.contains("status = 1"))
    }

    /** 不在列清单的裸词透传（不猜命名规则）。 */
    @Test
    fun test非字段裸词透传() {
        val result = convert("from WorkstationStatus where nonexistentThing = 1")
        assertTrue("非字段词应透传: ${result.sql}", result.sql.contains("nonexistentThing = 1"))
    }

    /** 替换产物是列名，重复调用结果一致（幂等）。 */
    @Test
    fun test幂等_重复调用一致() {
        val once = convert(
            "from WorkstationStatus where stationCode = :stationCode and updateInfo.beDelete = 0",
        ).sql
        val twice = HqlConverter.convert(once, workstationTable).sql
        assertEquals("列名产物不得再次命中映射", once, twice)
    }

    // ------------------------------------------------------------------
    // UPDATE / DELETE 同源场景
    // ------------------------------------------------------------------

    /** UPDATE 的 SET 与 WHERE 裸属性同样生效（同走 mapProperties，改前必红）。 */
    @Test
    fun testUpdate_SET与WHERE裸属性生效() {
        val result = convert("update WorkstationStatus set stationCode = :code, remark = :r where beDelete = 0")
        assertTrue("SET 裸属性应映射: ${result.sql}", result.sql.contains("set STATION_CODE = ?"))
        assertTrue("SET 裸属性应映射: ${result.sql}", result.sql.contains("REMARK = ?"))
        assertTrue("WHERE 裸属性应映射: ${result.sql}", result.sql.contains("BE_DELETE = 0"))
        assertFalse("不得残留属性名: ${result.sql}", result.sql.contains("stationCode ="))
    }
}
