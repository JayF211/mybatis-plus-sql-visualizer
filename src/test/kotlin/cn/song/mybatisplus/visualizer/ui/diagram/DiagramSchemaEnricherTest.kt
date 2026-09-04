package cn.song.mybatisplus.visualizer.ui.diagram

import cn.song.mybatisplus.visualizer.model.JoinType
import com.intellij.openapi.project.Project
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * T-Embed-02：JOIN 图数据源 schema 富化纯单测。
 *
 * 测试 classpath 无 database 插件（build.gradle 排除 database jars）——
 * `DatabaseSchemaResolver.isDatabaseAvailable()` 恒 false，走静默回落分支；
 * 真实富化路径由 runIde 真机验证（有数据源环境）。
 */
class DiagramSchemaEnricherTest {

    private fun stubProject(): Project = Project::class.java.cast(
        java.lang.reflect.Proxy.newProxyInstance(
            Project::class.java.classLoader,
            arrayOf(Project::class.java),
        ) { _, _, _ -> null },
    )

    private fun sampleModel(): DiagramModel = DiagramModel(
        entities = listOf(
            EntityNode(
                alias = "t", tableName = "car_device_info",
                columns = listOf(
                    ColumnEntry("id", "INT", isPk = true, comment = "主键"),
                    ColumnEntry("device_name", "VARCHAR", isPk = false),
                ),
                isMain = true,
            ),
            EntityNode(
                alias = "t1", tableName = "station_info",
                columns = listOf(ColumnEntry("station_id", "INT", isPk = false)),
                isMain = false,
                joinType = JoinType.LEFT,
            ),
        ),
        relationships = listOf(
            RelationshipLine("t", "t1", JoinType.LEFT, "t1.station_id = t.station_id"),
        ),
    )

    @Test
    fun nullProjectReturnsSameModel() {
        val model = sampleModel()
        assertSame("project 为 null 应原样返回（零开销快路径）", model, DiagramSchemaEnricher.enrich(null, model))
    }

    @Test
    fun emptyModelSkipsEnrichment() {
        val model = DiagramModel(emptyList(), emptyList())
        assertSame("空模型应原样返回", model, DiagramSchemaEnricher.enrich(stubProject(), model))
    }

    @Test
    fun missingDatabasePluginKeepsModelUnchanged() {
        // 测试 classpath 无 database 插件 → resolver 空 Map → 列类型/注释保留实体推断值
        val model = sampleModel()
        val enriched = DiagramSchemaEnricher.enrich(stubProject(), model)
        assertEquals(model.entities.size, enriched.entities.size)
        val main = enriched.entities[0]
        assertEquals("INT", main.columns[0].type)
        assertEquals("主键", main.columns[0].comment)
        assertEquals("VARCHAR", main.columns[1].type)
        assertEquals(null, main.columns[1].comment)
    }
}