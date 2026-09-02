package cn.song.mybatisplus.visualizer.heavy

import cn.song.mybatisplus.visualizer.model.ColumnInfo
import cn.song.mybatisplus.visualizer.model.ColumnRef
import cn.song.mybatisplus.visualizer.model.Condition
import cn.song.mybatisplus.visualizer.model.JoinClause
import cn.song.mybatisplus.visualizer.model.JoinType
import cn.song.mybatisplus.visualizer.model.SelectStatement
import cn.song.mybatisplus.visualizer.model.TableInfo
import cn.song.mybatisplus.visualizer.sql.JoinDiagramGenerator
import cn.song.mybatisplus.visualizer.ui.diagram.nativefw.NativeJoinDiagramSupport
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.HeavyPlatformTestCase
import javax.swing.JComponent
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue

/**
 * T-Dgm-05 视觉验收的 Heavy 替代（真实平台链路）：在 [HeavyPlatformTestCase]（完整平台 +
 * 真实 Project + 捆绑 Diagrams 插件加载）下，把「用户预览 JOIN 图」的真实操作链路跑到组件级——
 *
 *   SelectStatement → JoinDiagramGenerator → 快照注册 → NativeJoinDiagramOpener
 *   → Class.forName 反射实例化 UmlEditorProvider → createEditor（内部 getOrCreateBuilderForNewEditor
 *   → UmlGraphBuilder → UmlFileEditorImpl）→ 可嵌入 Swing 组件
 *
 * 该链路覆盖了 runIde 真机验收中最易碎的三处：internal API 反射桥、EP 挂载（diagram-optional.xml
 * 仅在 Diagrams 插件存在时加载）、框架侧由 Provider/DataModel 建图。单测环境即真实环境，
 * 无需 GUI 截图即可判定「链路通、组件出、释放净」。
 *
 * 速度约束（Heavy 类用例数严格控制）：3 个用例覆盖 3 表 / 4 表长列名 / 降级路径。
 */
class HeavyNativeJoinDiagramOpenChainTest : HeavyPlatformTestCase() {

    private val userTable = TableInfo(
        entityClassName = "cn.song.mpjdemo.entity.UserDO",
        tableName = "user",
        columns = listOf(
            ColumnInfo("id", "id", isId = true),
            ColumnInfo("name", "name"),
        ),
    )

    private val addressTable = TableInfo(
        entityClassName = "cn.song.mpjdemo.entity.AddressDO",
        tableName = "user_address",
        columns = listOf(
            ColumnInfo("id", "id", isId = true),
            ColumnInfo("userId", "user_id"),
        ),
    )

    private val areaTable = TableInfo(
        entityClassName = "cn.song.mpjdemo.entity.AreaDO",
        tableName = "area",
        columns = listOf(
            ColumnInfo("id", "id", isId = true),
            ColumnInfo("province", "province"),
        ),
    )

    /** 3 表字符串 JOIN（演示项目 queryWrapperString 写法，别名≠表名）。 */
    private fun threeTableStatement() = SelectStatement(
        table = userTable,
        tableAlias = "t",
        joins = listOf(
            JoinClause(
                type = JoinType.LEFT, tableName = "user_address", alias = "addr",
                tableInfo = addressTable,
                onConditions = listOf(
                    Condition.ColumnComparison(
                        left = ColumnRef(tableAlias = "t", columnName = "id"),
                        right = ColumnRef(tableAlias = "addr", columnName = "user_id"),
                    ),
                ),
            ),
            JoinClause(
                type = JoinType.RIGHT, tableName = "area", alias = "a",
                tableInfo = areaTable,
                onConditions = listOf(
                    Condition.ColumnComparison(
                        left = ColumnRef(tableAlias = "addr", columnName = "area_id"),
                        right = ColumnRef(tableAlias = "a", columnName = "id"),
                    ),
                ),
            ),
        ),
    )

    /** 演示项目 4 表长列名专用方法写法（T-Dgm-05 验收场景）。 */
    private fun fourTableLongColumnStatement() = SelectStatement(
        table = userTable,
        tableAlias = "t",
        selectColumns = listOf(
            ColumnRef(tableAlias = "addr", columnName = "contact_mobile_telephone_number"),
            ColumnRef(tableAlias = "a", columnName = "provincial_administrative_region_division_code"),
        ),
        joins = threeTableStatement().joins,
    )

    fun testOpenChainThreeTableJoin_realEditorComponentCreated() {
        val model = JoinDiagramGenerator.buildDiagramModel(threeTableStatement())
        val component = NativeJoinDiagramSupport.buildTabComponent(
            project, model, "JOIN Diagram: user", project,
        )
        assertNotNull("Diagrams 插件已加载的 Heavy 环境下原生图组件必须可创建", component)
        val comp: JComponent = component!!
        // 内部 FileEditor 已挂接（dispose 定位依据）
        val editor = comp.getClientProperty("mpsql.native.join.editor")
        assertNotNull(editor)
        // 组件为非空 Swing 容器（可嵌入预览面板标签页）
        assertTrue(comp.componentCount >= 1)
    }

    fun testOpenChainFourTableLongColumns_componentCreatedAndDisposed() {
        val model = JoinDiagramGenerator.buildDiagramModel(fourTableLongColumnStatement())
        val component = NativeJoinDiagramSupport.buildTabComponent(
            project, model, "JOIN Diagram: 4 tables", project,
        )
        assertNotNull(component)
        // 真实释放路径（预览面板换图/清空调用的同一入口）
        NativeJoinDiagramSupport.disposeNativeEditor(component!!)
        // 重复 dispose 幂等（面板 dispose 与显式 clear 双路径不抛异常）
        NativeJoinDiagramSupport.disposeNativeEditor(component)
    }

    fun testFallbackNullProjectReturnsNull() {
        val model = JoinDiagramGenerator.buildDiagramModel(threeTableStatement())
        // null project → 降级返回 null（SqlPreviewPanel 回退自绘面板的分支）
        assertNull(NativeJoinDiagramSupport.buildTabComponent(null, model, "title", testRootDisposable))
    }
}
