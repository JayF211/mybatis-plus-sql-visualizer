package cn.song.mybatisplus.visualizer.ui

import cn.song.mybatisplus.visualizer.sql.insight.*
import cn.song.mybatisplus.visualizer.ui.insight.*
import org.junit.Assert.*
import org.junit.Test
import java.awt.image.BufferedImage
import java.util.concurrent.CancellationException
import javax.swing.SwingUtilities
import javax.swing.tree.DefaultMutableTreeNode

class InsightPresentationTest {
    private fun model() = SqlSemanticModel(
        AnalysisSnapshot("SELECT t.id FROM t"),
        listOf(SemanticScope("s", null, "SELECT", SqlOperation.SELECT, listOf("r"), listOf(
            SemanticProjection(0, "id", "t.id", setOf(SemanticColumn("r", "id")), AnalysisQuality.CERTAIN)))),
        listOf(SemanticRelation("r", "s", RelationKind.TABLE, "<html><img src='http://invalid/'>", "t", setOf(TableRole.READ), null)),
        listOf(SemanticDependency(DependencyKind.JOIN, "s", "t.id = u.id", setOf(SemanticColumn("r", "id")))),
        listOf(SemanticDiagnostic(DiagnosticCode.SOURCE_RANGE_UNKNOWN, AnalysisQuality.UNKNOWN, "No exact ranges")), "s", AnalysisQuality.CERTAIN)

    @Test fun `read graph and source tree do not turn JOIN into writes`() {
        val result = InsightPresenter.build(model(), { it })
        assertEquals(1, result.arrows.size)
        assertEquals(InsightArrow("relation:r", "scope:s", "READ"), result.arrows.single())
        assertEquals(AnalysisQuality.UNKNOWN, result.sourceQuality)
        assertTrue(result.items[1].children.single().children.any { "t].id" in it.text })
        assertTrue(result.items[3].children.single().text.contains("SOURCE_RANGE_UNKNOWN"))
    }

    @Test fun `invalid and absent ranges select whole SQL without guessed offsets`() {
        assertEquals(SqlSourceRange(0, 20), InsightPresenter.navigationRange(null, 20))
        assertEquals(SqlSourceRange(0, 20), InsightPresenter.navigationRange(SqlSourceRange(-1, 8), 20))
        assertEquals(SqlSourceRange(0, 20), InsightPresenter.navigationRange(SqlSourceRange(0, 21), 20))
        assertEquals(SqlSourceRange(7, 11), InsightPresenter.navigationRange(SqlSourceRange(7, 11), 20))
        assertEquals(SqlSourceRange(0, 0), InsightPresenter.navigationRange(null, 0))
    }

    @Test fun `large graph is bounded and cancellation stops presentation`() {
        val source = model()
        val large = source.copy(relations = (0..500).map { source.relations.single().copy(id = "r$it") })
        val result = InsightPresenter.build(large, { it })
        assertTrue(result.graphLimited)
        assertTrue(result.vertices.size <= 200)
        assertTrue(result.arrows.size <= 400)
        try { InsightPresenter.build(large, { it }, { true }); fail("must cancel") } catch (_: CancellationException) { }
    }

    @Test fun `offline Swing renders malicious labels as plain text and clears stale content`() {
        SwingUtilities.invokeAndWait {
            val panel = InsightPanel({ it }, {}, {}, {}, {})
            panel.present(InsightPresenter.build(model(), { it }))
            val root = panel.tree.model.root as DefaultMutableTreeNode
            val structure = root.getChildAt(0) as DefaultMutableTreeNode
            val scope = structure.getChildAt(0) as DefaultMutableTreeNode
            val relation = scope.getChildAt(0) as DefaultMutableTreeNode
            val renderer = panel.tree.cellRenderer.getTreeCellRendererComponent(panel.tree, relation, false, false, true, 2, false) as javax.swing.JLabel
            assertTrue(renderer.text.startsWith("<html>"))
            assertNull(renderer.getClientProperty("html"))
            panel.graph.setSize(900, 300)
            val image = BufferedImage(900, 300, BufferedImage.TYPE_INT_ARGB)
            image.createGraphics().let { g -> try { panel.graph.paint(g) } finally { g.dispose() } }
            panel.markStale()
            assertEquals("stale", panel.statusText())
            assertEquals(0, (panel.tree.model.root as DefaultMutableTreeNode).childCount)
        }
    }
}
