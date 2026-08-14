package cn.song.mybatisplus.visualizer.model

import cn.song.mybatisplus.visualizer.sql.PostgreSQLDialect
import cn.song.mybatisplus.visualizer.sql.SqlServerDialect
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
    fun upsertConflictKeyFollowsActive() {
        // T-M23.2（用户实测：saveOrUpdate 主键应为可选）：主键为 toggle 列（默认勾选渲染完整 upsert；
        // 取消勾选 → 纯 INSERT 新记录路径，无 ON CONFLICT）
        val model = BatchModel(
            kind = BatchKind.UPSERT,
            tableName = "task_record",
            toggleColumns = listOf(
                BatchColumn("id", "entity.id", quote = false, checked = true),
                BatchColumn("task_name", "entity.task_name", quote = true, checked = true),
                BatchColumn("dic_status", "entity.dic_status", quote = false, checked = true),
            ),
            conflictColumn = "id",
        )
        // 主键勾选（默认）：完整 upsert
        assertEquals(
            """
            INSERT INTO task_record (id, task_name, dic_status)
            VALUES (?, ?, ?)
            ON CONFLICT (id) DO UPDATE SET task_name = EXCLUDED.task_name, dic_status = EXCLUDED.dic_status
            """.trimIndent(),
            model.renderSql(setOf("id", "task_name", "dic_status")),
        )
        // 主键取消勾选（新记录插入路径）：纯 INSERT，无 ON CONFLICT
        assertEquals(
            """
            INSERT INTO task_record (task_name, dic_status)
            VALUES (?, ?)
            """.trimIndent(),
            model.renderSql(setOf("task_name", "dic_status")),
        )
        // 取消非主键列 → 同步退出 INSERT 列清单与 DO UPDATE SET
        assertEquals(
            """
            INSERT INTO task_record (id, task_name)
            VALUES (?, ?)
            ON CONFLICT (id) DO UPDATE SET task_name = EXCLUDED.task_name
            """.trimIndent(),
            model.renderSql(setOf("id", "task_name")),
        )
        // 参数顺序与 `?` 顺序一致
        assertEquals(
            listOf("entity.id", "entity.task_name", "entity.dic_status"),
            model.orderedSources(setOf("id", "task_name", "dic_status")),
        )
    }

    // ------------------------------------------------------------------
    // T-M23.4：关键字标识符引用（多方言保留字命中 → 按方言符号包裹）
    // ------------------------------------------------------------------

    @Test
    fun insertKeywordColumnsQuoted() {
        // 用户示例：biz_banner 的 table 字段为 MySQL 保留字 → 反引号包裹；普通列原样
        val model = BatchModel(
            kind = BatchKind.INSERT,
            tableName = "biz_banner",
            toggleColumns = listOf(
                BatchColumn("title", "entity.title", quote = true, checked = true),
                BatchColumn("name", "entity.name", quote = true, checked = true),
                BatchColumn("table", "entity.table", quote = true, checked = true),
                BatchColumn("image_url", "entity.image_url", quote = true, checked = true),
            ),
        )
        val active = model.toggleColumns.map { it.columnName }.toSet()
        assertEquals(
            """
            INSERT INTO biz_banner (title, name, `table`, image_url)
            VALUES (?, ?, ?, ?)
            """.trimIndent(),
            model.renderSql(active),
        )
        // 参数来源/提示不随包裹变化（模型层列名保持原始值）
        assertEquals(
            listOf("entity.title", "entity.name", "entity.table", "entity.image_url"),
            model.orderedSources(active),
        )
    }

    @Test
    fun keywordQuoteFollowsDialect() {
        val columns = listOf(
            BatchColumn("table", "entity.table", quote = true, checked = true),
            BatchColumn("name", "entity.name", quote = true, checked = true),
        )
        val active = setOf("table", "name")
        // PostgreSQL / H2 / SQLite / DM / KingBase 双引号
        assertEquals(
            "INSERT INTO biz_banner (\"table\", name)\nVALUES (?, ?)",
            BatchModel(BatchKind.INSERT, "biz_banner", columns, dialect = PostgreSQLDialect).renderSql(active),
        )
        // SQL Server 方括号
        assertEquals(
            "INSERT INTO biz_banner ([table], name)\nVALUES (?, ?)",
            BatchModel(BatchKind.INSERT, "biz_banner", columns, dialect = SqlServerDialect).renderSql(active),
        )
        // 默认（MySQL 形态）反引号
        assertEquals(
            "INSERT INTO biz_banner (`table`, name)\nVALUES (?, ?)",
            BatchModel(BatchKind.INSERT, "biz_banner", columns).renderSql(active),
        )
    }

    @Test
    fun updateAndUpsertKeywordColumnsQuoted() {
        // UPDATE：SET 列 + WHERE 固定列 + 表名引用
        val update = BatchModel(
            kind = BatchKind.UPDATE,
            tableName = "order",
            toggleColumns = listOf(
                BatchColumn("group", "entity.group", quote = false, checked = true),
                BatchColumn("name", "entity.name", quote = true, checked = true),
            ),
            fixedColumns = listOf(BatchColumn("key", "entity.key", quote = false, checked = false)),
        )
        assertEquals(
            "UPDATE `order`\nSET `group` = ?, name = ?\nWHERE `key` = ?",
            update.renderSql(setOf("group", "name")),
        )
        // UPSERT：ON CONFLICT 冲突键 + EXCLUDED 列名（只包列名本体）
        val upsert = BatchModel(
            kind = BatchKind.UPSERT,
            tableName = "biz_banner",
            toggleColumns = listOf(
                BatchColumn("table", "entity.table", quote = true, checked = true),
                BatchColumn("name", "entity.name", quote = true, checked = true),
            ),
            conflictColumn = "table",
        )
        assertEquals(
            "INSERT INTO biz_banner (`table`, name)\nVALUES (?, ?)\n" +
                "ON CONFLICT (`table`) DO UPDATE SET name = EXCLUDED.name",
            upsert.renderSql(setOf("table", "name")),
        )
    }
}
