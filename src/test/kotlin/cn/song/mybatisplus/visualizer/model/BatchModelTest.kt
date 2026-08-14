package cn.song.mybatisplus.visualizer.model

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * BatchModel 渲染测试（T-M23-04）：批量参数列勾选模型的纯函数渲染——
 * INSERT/UPDATE/UPSERT 三种形状的初始 SQL、勾选重建、参数顺序与空集边界。
 */
class BatchModelTest {

    private fun insertModel(idChecked: Boolean = true) = BatchModel(
        kind = BatchKind.INSERT,
        tableName = "biz_store_coupon",
        toggleColumns = listOf(
            BatchColumn("id", "entity.id", quote = false, checked = idChecked),
            BatchColumn("name", "entity.name", quote = true, checked = true),
            BatchColumn("dic_status", "entity.dic_status", quote = false, checked = true),
        ),
    )

    @Test
    fun insertFullCheckedRendersAllColumns() {
        val model = insertModel(idChecked = true)
        val active = model.toggleColumns.filter { it.checked }.map { it.columnName }.toSet()
        assertEquals(
            """
            INSERT INTO biz_store_coupon (id, name, dic_status)
            VALUES (?, ?, ?)
            """.trimIndent(),
            model.renderSql(active),
        )
        assertEquals(listOf("entity.id", "entity.name", "entity.dic_status"), model.orderedSources(active))
        assertEquals(listOf(false, true, false), model.orderedHints(active).map { it.quote })
    }

    @Test
    fun insertAutoIdDefaultUncheckedExcludesId() {
        // T-M23-04（用户建议）：IdType.AUTO 主键默认不勾选 → 初始 SQL 不含 id（对齐 MP INSERT 语义）
        val model = insertModel(idChecked = false)
        val active = model.toggleColumns.filter { it.checked }.map { it.columnName }.toSet()
        assertEquals(
            """
            INSERT INTO biz_store_coupon (name, dic_status)
            VALUES (?, ?)
            """.trimIndent(),
            model.renderSql(active),
        )
        assertEquals(listOf("entity.name", "entity.dic_status"), model.orderedSources(active))
    }

    @Test
    fun insertUncheckColumnDropsFromColumnList() {
        val model = insertModel()
        val active = setOf("id", "dic_status")
        assertEquals(
            """
            INSERT INTO biz_store_coupon (id, dic_status)
            VALUES (?, ?)
            """.trimIndent(),
            model.renderSql(active),
        )
        assertEquals(listOf("entity.id", "entity.dic_status"), model.orderedSources(active))
    }

    @Test
    fun insertEmptyActiveSetRendersEmptyColumns() {
        // 边界（文档化）：活跃集为空 → 空列清单形态，面板不做保护
        val model = insertModel()
        assertEquals("INSERT INTO biz_store_coupon ()\nVALUES ()", model.renderSql(emptySet()))
        assertEquals(emptyList<String>(), model.orderedSources(emptySet()))
    }

    @Test
    fun updateInitialRendersSetAllAndWhereId() {
        val model = BatchModel(
            kind = BatchKind.UPDATE,
            tableName = "biz_store_coupon",
            toggleColumns = listOf(
                BatchColumn("name", "entity.name", quote = true, checked = true),
                BatchColumn("dic_status", "entity.dic_status", quote = false, checked = true),
            ),
            fixedColumns = listOf(BatchColumn("id", "entity.id", quote = false, checked = true)),
        )
        val active = model.toggleColumns.filter { it.checked }.map { it.columnName }.toSet()
        assertEquals(
            """
            UPDATE biz_store_coupon
            SET name = ?, dic_status = ?
            WHERE id = ?
            """.trimIndent(),
            model.renderSql(active),
        )
        // 参数顺序：先 SET 后 WHERE（`?` 顺序与 SQL 一致）
        assertEquals(listOf("entity.name", "entity.dic_status", "entity.id"), model.orderedSources(active))
    }

    @Test
    fun updateUncheckSetColumnKeepsWhereId() {
        val model = BatchModel(
            kind = BatchKind.UPDATE,
            tableName = "biz_store_coupon",
            toggleColumns = listOf(
                BatchColumn("name", "entity.name", quote = true, checked = true),
                BatchColumn("dic_status", "entity.dic_status", quote = false, checked = true),
            ),
            fixedColumns = listOf(BatchColumn("id", "entity.id", quote = false, checked = true)),
        )
        assertEquals(
            """
            UPDATE biz_store_coupon
            SET dic_status = ?
            WHERE id = ?
            """.trimIndent(),
            model.renderSql(setOf("dic_status", "id")),
        )
    }

    @Test
    fun updateWithVersionRendersLiteralAndFixedWhere() {
        val model = BatchModel(
            kind = BatchKind.UPDATE,
            tableName = "biz_store_coupon",
            toggleColumns = listOf(BatchColumn("name", "entity.name", quote = true, checked = true)),
            fixedColumns = listOf(
                BatchColumn("id", "entity.id", quote = false, checked = true),
                BatchColumn("version", "entity.version", quote = false, checked = true),
            ),
            versionColumn = "version",
        )
        assertEquals(
            """
            UPDATE biz_store_coupon
            SET name = ?, version = version + 1
            WHERE id = ? AND version = ?
            """.trimIndent(),
            model.renderSql(setOf("name", "id", "version")),
        )
        assertEquals(listOf("entity.name", "entity.id", "entity.version"), model.orderedSources(setOf("name", "id", "version")))
    }

    @Test
    fun upsertFixedIdFirstAndConflictSetFollowsActive() {
        val model = BatchModel(
            kind = BatchKind.UPSERT,
            tableName = "station_occupy_info",
            toggleColumns = listOf(
                BatchColumn("station_id", "entity.station_id", quote = false, checked = true),
                BatchColumn("dic_status", "entity.dic_status", quote = false, checked = true),
            ),
            fixedColumns = listOf(BatchColumn("id", "entity.id", quote = false, checked = true)),
        )
        assertEquals(
            """
            INSERT INTO station_occupy_info (id, station_id, dic_status)
            VALUES (?, ?, ?)
            ON CONFLICT (id) DO UPDATE SET station_id = EXCLUDED.station_id, dic_status = EXCLUDED.dic_status
            """.trimIndent(),
            model.renderSql(setOf("id", "station_id", "dic_status")),
        )
        // 取消 station_id → INSERT 列与 DO UPDATE SET 同步减列
        assertEquals(
            """
            INSERT INTO station_occupy_info (id, dic_status)
            VALUES (?, ?)
            ON CONFLICT (id) DO UPDATE SET dic_status = EXCLUDED.dic_status
            """.trimIndent(),
            model.renderSql(setOf("id", "dic_status")),
        )
        assertEquals(listOf("entity.id", "entity.station_id", "entity.dic_status"), model.orderedSources(setOf("id", "station_id", "dic_status")))
    }
}
