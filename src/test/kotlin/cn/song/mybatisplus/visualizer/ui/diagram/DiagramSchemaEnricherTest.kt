package cn.song.mybatisplus.visualizer.ui.diagram

import cn.song.mybatisplus.visualizer.model.JoinType
import cn.song.mybatisplus.visualizer.ui.highlight.DatabaseSchemaResolver
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

    // ---- T-Embed-09：表头注释数据库 COMMENT 优先，降级实体类注释（applyTableMeta 正向注入）----

    @Test
    fun dbTableCommentOverridesEntityComment() {
        val model = sampleModel().copy(
            entities = sampleModel().entities.map { it.copy(tableComment = "实体类注释(${it.tableName})") },
        )
        val enriched = applyTableMeta(model) { tableName ->
            if (tableName == "car_device_info") {
                DatabaseSchemaResolver.TableMeta("设备信息表", emptyMap())
            } else {
                DatabaseSchemaResolver.TableMeta(null, emptyMap())
            }
        }
        assertEquals("数据库表注释应覆盖实体类注释", "设备信息表", enriched.entities[0].tableComment)
        assertEquals("数据源缺席的表保留实体注释", "实体类注释(station_info)", enriched.entities[1].tableComment)
    }

    @Test
    fun blankDbTableCommentFallsBackToEntityComment() {
        val model = sampleModel().copy(
            entities = sampleModel().entities.map { it.copy(tableComment = "实体类注释") },
        )
        // 数据库表存在但没写 COMMENT（comment=null），列元数据照常富化
        val enriched = applyTableMeta(model) {
            DatabaseSchemaResolver.TableMeta(null, mapOf("id" to DatabaseSchemaResolver.ColumnMeta("主键注释", "int(11)")))
        }
        assertEquals("数据库没写表注释应回落实体注释", "实体类注释", enriched.entities[0].tableComment)
        assertEquals("int(11)", enriched.entities[0].columns[0].type)
        assertEquals("主键注释", enriched.entities[0].columns[0].comment)
    }

    @Test
    fun tableCommentOnlyStillEnriched() {
        // 数据库只有表 COMMENT、列匹配不到（如视图/权限受限）——表注释仍覆盖，列保留
        val model = sampleModel()
        val enriched = applyTableMeta(model) {
            DatabaseSchemaResolver.TableMeta("库表注释", emptyMap())
        }
        assertEquals("库表注释", enriched.entities[0].tableComment)
        assertEquals("INT", enriched.entities[0].columns[0].type)
        assertEquals("主键", enriched.entities[0].columns[0].comment)
    }
}