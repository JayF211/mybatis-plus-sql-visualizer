package cn.song.mybatisplus.visualizer.converter.jpa

import cn.song.mybatisplus.visualizer.model.ColumnInfo
import cn.song.mybatisplus.visualizer.model.TableInfo
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [临时复现探针 T-HqlWherePropRepro]：用户反馈「字段没正常自动转换」最小复现。
 *
 * 用户场景：无别名 HQL + 实体继承 VersionalEntity（父类含 @Embedded updateInfo）。
 * TableInfo 按截图形态手工构造（stationCode 自有字段 / beDelete 为 updateInfo 嵌入展平列）。
 * 本测试钉住**当前行为**（非期望行为），用于实证两个现象的机制归因。
 */
class HqlConverterBarePropertyReproTest {

    private val workstationTable = TableInfo(
        entityClassName = "WorkstationStatus",
        tableName = "WORKSTATION_STATUS",
        columns = listOf(
            ColumnInfo("id", "ID", isId = true),
            ColumnInfo("creatorUnikey", "CREATOR_UNIKEY"),
            ColumnInfo("creator", "CREATOR"),
            ColumnInfo("createdTime", "CREATED_TIME"),
            ColumnInfo("lastOperatorUnikey", "LAST_OPERATOR_UNIKEY"),
            ColumnInfo("lastOperator", "LAST_OPERATOR"),
            ColumnInfo("updateTime", "UPDATE_TIME"),
            ColumnInfo("beDelete", "BE_DELETE", embedded = true, embeddedParent = "updateInfo"),
            ColumnInfo("version", "VERSION"),
            ColumnInfo("boxNo", "BOX_NO"),
            ColumnInfo("stationCode", "STATION_CODE"),
            ColumnInfo("errorMsg", "ERROR_MSG"),
        ),
    )

    @Test
    fun testReproUserScenario() {
        val hql = "from WorkstationStatus where stationCode = :stationCode and updateInfo.beDelete = 0"
        val result = HqlConverter.convert(hql, workstationTable)
        println("[REPRO] ===== converted SQL begin =====")
        result.sql.lines().forEach { println("[REPRO] $it") }
        println("[REPRO] ===== converted SQL end =====")

        // 现象 1：裸属性 stationCode（无别名 HQL）保持原样不转列名
        assertTrue(
            "现状应保留裸属性 stationCode（结构性不映射）",
            result.sql.contains("stationCode = ?"),
        )
        // 现象 2：updateInfo.beDelete 尾段转大写但嵌入段前缀 updateInfo. 残留
        assertTrue(
            "现状应残留 updateInfo. 前缀",
            result.sql.contains("updateInfo.BE_DELETE = 0"),
        )
    }

    @Test
    fun testAliasedEquivalentConverts() {
        // 对照组：同语义 HQL 改带别名 → 两处都应正确转换（demo 项目复现不了的原因）
        val hql = "from WorkstationStatus w where w.stationCode = :stationCode and w.updateInfo.beDelete = 0"
        val result = HqlConverter.convert(hql, workstationTable)
        println("[ALIAS] ===== converted SQL begin =====")
        result.sql.lines().forEach { println("[ALIAS] $it") }
        println("[ALIAS] ===== converted SQL end =====")

        assertTrue("带别名时属性应映射为列名", result.sql.contains("w.STATION_CODE = ?"))
        assertTrue("带别名时嵌入段应剥离", result.sql.contains("w.BE_DELETE = 0"))
    }
}
