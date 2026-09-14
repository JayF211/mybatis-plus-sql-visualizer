package cn.song.mybatisplus.visualizer.actions

import cn.song.mybatisplus.visualizer.actions.CrudSqlPreviewFlow.Candidate
import cn.song.mybatisplus.visualizer.generator.CrudColumn
import cn.song.mybatisplus.visualizer.generator.CrudKind
import cn.song.mybatisplus.visualizer.generator.CrudSqlGenerator
import cn.song.mybatisplus.visualizer.generator.CrudStatement
import cn.song.mybatisplus.visualizer.generator.CrudTableSpec
import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * CrudSqlPreviewFlow 纯函数测试（T-CrudSql-04）：候选摘要（表名 · 语句种类 + 无主键标注）
 * 与第二行 SQL 预览。bundle 为自管理 ResourceBundle，测试环境兜底中文——断言按 zh 文案。
 */
class CrudSqlPreviewFlowTest {

    private val spec = CrudTableSpec("biz_order", listOf(CrudColumn("id", null, true), CrudColumn("name")))
    private val stmts: List<CrudStatement> = CrudSqlGenerator.generate(spec)

    @Test
    fun summaryCombinesTableKindAndMissingPkMark() {
        val normal = Candidate("biz_order", stmts.first { it.kind == CrudKind.SELECT_ALL })
        assertEquals("biz_order · SELECT（全表）", CrudSqlPreviewFlow.summary(normal))

        val noPkSpec = CrudTableSpec("t_nopk", listOf(CrudColumn("name"), CrudColumn("age")))
        val noPkDelete = Candidate("t_nopk", CrudSqlGenerator.generate(noPkSpec).first { it.kind == CrudKind.DELETE })
        assertEquals("t_nopk · DELETE（未识别到主键）", CrudSqlPreviewFlow.summary(noPkDelete))
    }

    @Test
    fun lineTextReturnsFirstSqlLine() {
        val candidate = Candidate("biz_order", stmts.first { it.kind == CrudKind.SELECT_ALL })
        assertEquals("SELECT id, name", CrudSqlPreviewFlow.lineText(candidate))
    }

    @Test
    fun kindLabelsResolveFromBundle() {
        // 六个候选标签键全部可达（bundle 缺键时 message 回落 key 本身，据此防键名漂移）
        assertTrue(CrudKind.entries.all { MpSqlVisualizerBundle.message(it.labelKey) != it.labelKey })
    }

    @Test
    fun popupRendererProducesComponentWithoutException() {
        // T-CrudSql 原生化：JBPopup 候选行渲染器冒烟——选中/非选中两态均产出组件不抛异常
        val renderer = CrudSqlPreviewFlow.CandidateCellRenderer()
        val list = javax.swing.JList<Candidate>()
        for (selected in booleanArrayOf(false, true)) {
            val c = renderer.getListCellRendererComponent(
                list,
                Candidate("biz_order", stmts.first { it.kind == CrudKind.SELECT_ALL }),
                0, selected, false,
            )
            assertTrue("渲染应返回组件", c != null)
        }
    }
}
