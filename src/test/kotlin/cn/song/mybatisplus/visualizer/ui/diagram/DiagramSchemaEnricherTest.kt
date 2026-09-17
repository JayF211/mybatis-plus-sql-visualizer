package cn.song.mybatisplus.visualizer.ui.diagram

import cn.song.mybatisplus.visualizer.model.JoinType
import cn.song.mybatisplus.visualizer.ui.highlight.DatabaseSchemaResolver
import com.intellij.openapi.project.Project
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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

    // ---- T-DbSchemaCompletion 同批：实体列无注释时的 DB 回填（用户实测「JOIN 图缺字段注释」）----
    // 场景：MPJ demo 的 UserDO 映射 user，字段一个注释都没写（无 javadoc/无注解），
    // 而库里每个列都有 COMMENT——IDEA 原生「显示图」直接读库所以有注释，我们的 JOIN 图列注释
    // 来自实体 + 本富化，实体侧为空时就只剩数据源这一路。ObjectKind 静默早退期间这路是死的，
    // 于是 JOIN 图整片字段注释为空（类型串也停在实体推断的大写形态）。

    @Test
    fun dbCommentFillsEntityColumnsWithoutComment() {
        val model = sampleModel()
        val enriched = applyTableMeta(model) { tableName ->
            if (tableName == "car_device_info") {
                DatabaseSchemaResolver.TableMeta(
                    comment = "设施信息表",
                    columns = mapOf(
                        "id" to DatabaseSchemaResolver.ColumnMeta("主键", "int(11)"),
                        "device_name" to DatabaseSchemaResolver.ColumnMeta("设施名称", "varchar(64)"),
                    ),
                )
            } else {
                DatabaseSchemaResolver.TableMeta(null, emptyMap())
            }
        }
        val main = enriched.entities[0]
        val deviceName = main.columns.first { it.name == "device_name" }
        assertEquals("实体列没有注释时由 DB COMMENT 回填（JOIN 图字段行补上 /* 设施名称 */）", "设施名称", deviceName.comment)
        assertEquals("类型同时升级为 DB 真实串", "varchar(64)", deviceName.type)
        assertEquals("表头注释同样由 DB COMMENT 补上", "设施信息表", main.tableComment)
    }

    // ---- T-DgmFullCols：数据源独有列补进节点（实体过时/未映射列，观感对齐原生「显示图」）----

    @Test
    fun dbOnlyColumnsAppendedAfterEntityColumns() {
        val model = sampleModel()
        val enriched = applyTableMeta(model) { tableName ->
            if (tableName == "car_device_info") {
                DatabaseSchemaResolver.TableMeta(
                    comment = null,
                    columns = linkedMapOf(
                        // 实体已有（顺序按实体）→ 只覆盖注释/类型
                        "id" to DatabaseSchemaResolver.ColumnMeta("主键", "int(11)"),
                        // 实体未映射 → 追加在后，按库返回顺序
                        "create_time" to DatabaseSchemaResolver.ColumnMeta("创建时间", "datetime"),
                        "deleted" to DatabaseSchemaResolver.ColumnMeta(null, "tinyint(1)"),
                    ),
                )
            } else {
                DatabaseSchemaResolver.TableMeta(null, emptyMap())
            }
        }
        val main = enriched.entities[0]
        assertEquals(
            "实体列在前、库独有列按库顺序追加",
            listOf("id", "device_name", "create_time", "deleted"),
            main.columns.map { it.name },
        )
        val deleted = main.columns.last()
        assertEquals("库独有列带类型串", "tinyint(1)", deleted.type)
        assertNull("库独有列无 COMMENT 时注释为空", deleted.comment)
        assertEquals("库独有列没有键信息 → 不标主键（一期不做）", false, deleted.isPk)
    }

    @Test
    fun columnCapKeepsEntityColumnsWhenDbExtrasExceedLimit() {
        // 实体 49 列 + 库独有 2 列 = 51 > 50 → 放弃追加（宁少不撑爆画布）
        val entityColumns = (1..49).map { ColumnEntry("c$it", "INT", isPk = it == 1) }
        val model = DiagramModel(
            entities = listOf(EntityNode(alias = "t", tableName = "wide", columns = entityColumns, isMain = true)),
            relationships = emptyList(),
        )
        val dbColumns = linkedMapOf<String, DatabaseSchemaResolver.ColumnMeta>()
        (1..49).forEach { dbColumns["c$it"] = DatabaseSchemaResolver.ColumnMeta(null, "int(11)") }
        dbColumns["extra_a"] = DatabaseSchemaResolver.ColumnMeta(null, "int(11)")
        dbColumns["extra_b"] = DatabaseSchemaResolver.ColumnMeta(null, "int(11)")

        val enriched = applyTableMeta(model) { DatabaseSchemaResolver.TableMeta(null, dbColumns) }
        assertEquals("超上限时不追加，实体列原样", 49, enriched.entities[0].columns.size)
        assertFalse("库独有列未混入", enriched.entities[0].columns.any { it.name.startsWith("extra_") })
    }
}