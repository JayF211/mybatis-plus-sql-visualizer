package cn.song.mybatisplus.visualizer.ui

import cn.song.mybatisplus.visualizer.model.ColumnInfo
import cn.song.mybatisplus.visualizer.model.ColumnRef
import cn.song.mybatisplus.visualizer.model.Condition
import cn.song.mybatisplus.visualizer.model.JoinClause
import cn.song.mybatisplus.visualizer.model.JoinType
import cn.song.mybatisplus.visualizer.model.PreviewResult
import cn.song.mybatisplus.visualizer.model.SelectStatement
import cn.song.mybatisplus.visualizer.model.SqlPreviewModel
import cn.song.mybatisplus.visualizer.model.TableInfo
import cn.song.mybatisplus.visualizer.model.WrapperType
import cn.song.mybatisplus.visualizer.sql.MySqlDialect
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull

/**
 * F-1 修复回归守卫：**无动态条件的联表链**也必须拿到「JOIN 图」标签页。
 *
 * 缺陷形态（2026-10-12 演示录制中发现，诊断报告 `docs/调研报告/20261012-0900-*`）：
 * `updateDiagramTab` 原先只看 `result.previewModel?.statement`，而 `previewModel` 仅在
 * 「有动态条件视图」时才构造（`collectConditionViews` 只收 conditions，joins 不产生视图）——
 * MPJ 的 `selectAll + leftJoin + fill` 这类无 eq/like 的链 `previewModel` 为 null，
 * 于是**预览出 SQL 却没有「JOIN 图」标签页**；带 `.eq/.like` 的同结构链才有。
 *
 * 修复把「是否画图」的判据挪到独立载体 PreviewResult.diagramStatement，与
 * 「是否走模型级重渲染」的 previewModel 解耦。本用例钉三件事：
 *  ① 有 JOIN + previewModel=null + diagramStatement 非空 → 标签页在（修复前为红）；
 *  ② 无 JOIN（两者皆空）→ 标签页不在（负例，防「无条件恒加 Tab」的过度修复）；
 *  ③ 有 JOIN + previewModel 非空（旧路径）→ 标签页仍在（存量行为不许回归）。
 */
class SqlPreviewPanelDiagramStatementTest : BasePlatformTestCase() {

    /** 含 JOIN 的 SELECT 语句（与 SqlPreviewPanelTest#joinResult 同形态）。
     *  k2j 互操作：SelectStatement 为 Java 类（16 参规范构造），具名实参→位置实参，缺省槽按 Kotlin 数据类默认值补齐。 */
    private fun joinStatement(): SelectStatement = SelectStatement(
        tableInfo(),
        emptyList(),
        "u",
        listOf(
            JoinClause(
                JoinType.LEFT,
                "user_address",
                "a",
                listOf(
                    Condition.ColumnComparison(
                        ColumnRef("u", "id"),
                        ColumnRef("a", "user_id"),
                        // Kotlin 数据类默认 toggle = null
                        null,
                    ),
                ),
            ),
        ),
        false,
        emptyList(),
        emptyList(),
        null,
        emptyList(),
        null,
        null,
        null,
        null,
        emptyList(),
        null,
        null,
    )

    private fun tableInfo() = TableInfo(
        "DeviceInfoEntity",
        "car_device_info",
        listOf(
            // k2j 互操作：ColumnInfo 为 Java 类，isId/fieldType 具名实参→（fieldName, columnName, isId, idTypeAuto, fieldType）位置实参
            ColumnInfo("id", "id", true, false, "java.lang.Long"),
            ColumnInfo("name", "name", false, false, "java.lang.String"),
        ),
    )

    /**
     * k2j 互操作：PreviewResult 为 Java 类（29 参规范构造，diagramStatement 第 28 位），
     * 具名实参→位置实参，缺省槽按 Kotlin 数据类默认值补齐（SqlPreviewPanelTest 同款惯例）。
     */
    private fun result(
        statement: SelectStatement?,
        previewModel: SqlPreviewModel?,
    ) = PreviewResult(
        "SELECT u.id FROM car_device_info u LEFT JOIN user_address a ON u.id = a.user_id",
        "SELECT u.id FROM car_device_info u LEFT JOIN user_address a ON u.id = a.user_id",
        emptyList(),
        null,
        WrapperType.MPJ_LAMBDA,
        "car_device_info",
        null,
        listOf("id"),
        emptyMap(),
        null,
        previewModel,
        emptyList(),
        emptyList(),
        null,
        null,
        emptyMap(),
        emptyList(),
        emptyList(),
        null,
        emptyList(),
        emptyMap(),
        null,
        false,
        null,
        null,
        emptyList(),
        emptySet(),
        statement,
        null,
    )

    /** ① 修复主用例：previewModel=null 但有 JOIN → 标签页必须在。 */
    fun testDiagramTabAppearsWithoutPreviewModel() {
        val panel = SqlPreviewPanel(project)
        SqlPreviewPanelResultViewKt.showResult(panel, result(joinStatement(), null))
        val tabPane = panel.contentTabPaneForTest()
        assertNotNull("无动态条件但有 JOIN 时也必须建标签页（F-1 修复点）", tabPane)
        assertEquals("应为 SQL + JOIN 图 两个标签页", 2, tabPane!!.tabCount)
        SqlPreviewPanelSqlViewKt.clear(panel)
    }

    /** ② 负例：无 JOIN 且无 previewModel → 不建标签页（防过度修复）。 */
    fun testNoDiagramTabWithoutJoins() {
        val panel = SqlPreviewPanel(project)
        SqlPreviewPanelResultViewKt.showResult(panel, result(null, null))
        assertNull("无 JOIN 时不应出现标签页", panel.contentTabPaneForTest())
        SqlPreviewPanelSqlViewKt.clear(panel)
    }

    /** ③ 存量回归：previewModel 非空（旧路径）仍须出标签页。 */
    fun testDiagramTabStillAppearsWithPreviewModel() {
        val statement = joinStatement()
        val panel = SqlPreviewPanel(project)
        SqlPreviewPanelResultViewKt.showResult(
            panel,
            result(
                null,
                SqlPreviewModel(
                    statement, MySqlDialect.INSTANCE, false, emptyList(),
                ),
            ),
        )
        val tabPane = panel.contentTabPaneForTest()
        assertNotNull("previewModel 路径（旧行为）不得回归", tabPane)
        assertEquals(2, tabPane!!.tabCount)
        SqlPreviewPanelSqlViewKt.clear(panel)
    }
}
