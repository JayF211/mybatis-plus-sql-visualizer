package cn.song.mybatisplus.visualizer.ui.diagram

import cn.song.mybatisplus.visualizer.model.ColumnInfo
import cn.song.mybatisplus.visualizer.model.ColumnRef
import cn.song.mybatisplus.visualizer.model.Condition
import cn.song.mybatisplus.visualizer.model.JoinClause
import cn.song.mybatisplus.visualizer.model.JoinType
import cn.song.mybatisplus.visualizer.model.SelectStatement
import cn.song.mybatisplus.visualizer.model.TableInfo
import cn.song.mybatisplus.visualizer.sql.JoinDiagramGenerator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.awt.image.BufferedImage

class DiagramRendererTest {

    private val userTable = TableInfo("UserEntity", "user", listOf(
            ColumnInfo("id", "id", true),
            ColumnInfo("name", "name"),
            ColumnInfo("email", "email"),
        ))

    private val orderTable = TableInfo("OrderEntity", "order", listOf(
            ColumnInfo("id", "id", true),
            ColumnInfo("userId", "user_id"),
            ColumnInfo("amount", "amount"),
        ))

    private val joinModel: DiagramModel
        get() {
            val stmt = SelectStatement(userTable, emptyList(), "u", listOf(
                    JoinClause(JoinType.LEFT, "order", "o", listOf(
                            Condition.ColumnComparison(ColumnRef("u", "id"), ColumnRef("o", "user_id"), null),
                        )),
                ), false, emptyList(), emptyList(), null, emptyList(), null, null, null, null, emptyList(), null, null)
            return JoinDiagramGenerator.buildDiagramModel(stmt)
        }

    @Test
    fun `render simple join produces non-null image with positive dimensions`() {
        val image = DiagramRenderer.render(joinModel)
        assertNotNull(image)
        assertTrue(image.width > 100 && image.height > 100)
    }

    @Test
    fun `render empty model produces minimal image`() {
        val image = DiagramRenderer.render(DiagramModel(emptyList(), emptyList()))
        assertNotNull(image)
        assertEquals(1, image.width)
        assertEquals(1, image.height)
    }

    @Test
    fun `render produces image with main entity subtle green header`() {
        val image = DiagramRenderer.render(joinModel)
        var greenish = 0
        for (y in 0 until image.height step 3) {
            for (x in 0 until image.width step 3) {
                val rgb = image.getRGB(x, y)
                val r = (rgb shr 16) and 0xFF
                val g = (rgb shr 8) and 0xFF
                val b = rgb and 0xFF
                if (g > 200 && g >= r && g > b && (g - r) < 30 && (g - b) >= 5) greenish++
            }
        }
        assertTrue("should have greenish pixels, found: $greenish", greenish > 0)
    }

    @Test
    fun `render different join types produce valid images`() {
        for (joinType in JoinType.entries) {
            val stmt = SelectStatement(userTable, emptyList(), "u", listOf(
                    JoinClause(joinType, "order", "o", listOf(
                            Condition.ColumnComparison(ColumnRef("u", "id"), ColumnRef("o", "user_id"), null),
                        ), null, null, null, null),
                ), false, emptyList(), emptyList(), null, emptyList(), null, null, null, null, emptyList(), null, null)
            val model = JoinDiagramGenerator.buildDiagramModel(stmt)
            assertEquals(joinType, model.relationships[0].joinType)
            val image = DiagramRenderer.render(model)
            assertTrue("image for $joinType should have positive size", image.width > 0 && image.height > 0)
        }
    }

    @Test
    fun `render to svg produces valid xml with expected structure`() {
        val svg = DiagramRenderer.renderToSvg(joinModel)
        assertTrue(svg.contains("<svg"))
        assertTrue(svg.trimEnd().endsWith("</svg>"))
        assertTrue(svg.contains("user"))
        assertTrue(svg.contains("<rect"))
        assertTrue(svg.contains("<line"))
        assertTrue(svg.contains("<polygon"))
    }

    @Test
    fun `render to svg escapes special xml characters`() {
        val specialTable = TableInfo("SpecialEntity", "special", listOf(
                ColumnInfo("id", "id", true),
                ColumnInfo("name", "name<tag>"),
                ColumnInfo("desc", "a&b\"c"),
            ))
        val stmt = SelectStatement(specialTable, emptyList(), "s", listOf(
                JoinClause(JoinType.INNER, "other<tab>", "o", listOf(Condition.Raw("s.id = o.id AND x < 5"))),
            ), false, emptyList(), emptyList(), null, emptyList(), null, null, null, null, emptyList(), null, null)
        val model = JoinDiagramGenerator.buildDiagramModel(stmt)
        val svg = DiagramRenderer.renderToSvg(model)
        assertTrue(svg.contains("&lt;"))
        assertTrue(svg.contains("&amp;"))
        assertTrue(svg.contains("&quot;"))
        val builder = javax.xml.parsers.DocumentBuilderFactory.newInstance().newDocumentBuilder()
        val doc = builder.parse(java.io.ByteArrayInputStream(svg.toByteArray()))
        assertEquals("svg", doc.documentElement.tagName)
    }

    @Test
    fun `render long column list truncates to PK only`() {
        val manyColumns = (1..60).map { i -> ColumnInfo("field$i", "col_$i", i == 1) }
        val bigTable = TableInfo("BigEntity", "big_table", manyColumns)
        val stmt = SelectStatement(bigTable, emptyList(), "t", listOf(
                JoinClause(JoinType.INNER, "order", "o", listOf(
                        Condition.ColumnComparison(ColumnRef("t", "col_1"), ColumnRef("o", "id"), null),
                    )),
            ), false, emptyList(), emptyList(), null, emptyList(), null, null, null, null, emptyList(), null, null)
        val model = JoinDiagramGenerator.buildDiagramModel(stmt)
        assertEquals(1, model.entities[0].columns.size)
        val image = DiagramRenderer.render(model)
        assertNotNull(image)
    }

    @Test
    fun `render image is TYPE_INT_ARGB`() {
        val image = DiagramRenderer.render(joinModel)
        assertEquals(BufferedImage.TYPE_INT_ARGB, image.type)
    }
}
