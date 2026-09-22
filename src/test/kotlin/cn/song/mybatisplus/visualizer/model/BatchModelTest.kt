package cn.song.mybatisplus.visualizer.model

import cn.song.mybatisplus.visualizer.sql.Dialect
import cn.song.mybatisplus.visualizer.sql.DmDialect
import cn.song.mybatisplus.visualizer.sql.H2Dialect
import cn.song.mybatisplus.visualizer.sql.IdentifierCaseSource
import cn.song.mybatisplus.visualizer.sql.KingBaseDialect
import cn.song.mybatisplus.visualizer.sql.MariaDbDialect
import cn.song.mybatisplus.visualizer.sql.MySqlDialect
import cn.song.mybatisplus.visualizer.sql.OracleDialect
import cn.song.mybatisplus.visualizer.sql.PostgreSQLDialect
import cn.song.mybatisplus.visualizer.sql.SqlServerDialect
import cn.song.mybatisplus.visualizer.sql.SqliteDialect
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

    /**
     * 版本列 `= <col> + 1` 的**两侧同一判定**（独立复核 ㉘ 的同型「半包」残留）。
     *
     * 此前表达式右侧是裸写字面量、左侧走 [Dialect.quoteIfNeeded]——名字一旦需要引用就产出
     * 「左侧加引号、右侧裸写」的半包形态，右侧裸写在 UPPER 族被折叠为大写 → 列不存在
     * （与 UPSERT 后缀同款，见 `DialectTest.upsert列名按目录来源同一判定`）。
     *
     * **当前生产路径不可达**：`versionColumn` 只在实体侧产出（[cn.song.mybatisplus.visualizer.psi.EntityResolver]
     * 的 `@Version` 派生名），目录侧 [cn.song.mybatisplus.visualizer.database.DbTableSpecAdapter] 恒 null
     * ——故本用例是契约钉住（防将来接线后复发），非在跑缺陷回归。
     */
    @Test
    fun updateVersionColumnQuotesBothSidesOfTheExpression() {
        fun model(dialect: Dialect, version: String, source: IdentifierCaseSource) = BatchModel(
            kind = BatchKind.UPDATE,
            tableName = "biz_store_coupon",
            toggleColumns = listOf(BatchColumn("name", "entity.name", quote = true, checked = true)),
            fixedColumns = listOf(
                BatchColumn("id", "entity.id", quote = false, checked = true),
                BatchColumn(version, "entity.$version", quote = false, checked = true),
            ),
            versionColumn = version,
            dialect = dialect,
            identifierCaseSource = source,
        )
        // UPPER 族（Oracle / H2）+ 目录来源：字典名含小写 → 两侧都加引号（`id` 含小写，同样加引号）
        listOf(OracleDialect, H2Dialect).forEach { d ->
            assertEquals(
                "方言 ${d.name}：版本列表达式两侧须同一判定",
                """
                UPDATE "biz_store_coupon"
                SET "name" = ?, "lock_ver" = "lock_ver" + 1
                WHERE "id" = ? AND "lock_ver" = ?
                """.trimIndent(),
                model(d, "lock_ver", IdentifierCaseSource.CATALOG).renderSql(setOf("name", "id", "lock_ver")),
            )
        }
        // 保留字命中的版本列名：实体来源两侧都包（此前左侧包、右侧裸写 → 右引用不成立）
        assertEquals(
            """
            UPDATE biz_store_coupon
            SET name = ?, `order` = `order` + 1
            WHERE id = ? AND `order` = ?
            """.trimIndent(),
            model(MySqlDialect, "order", IdentifierCaseSource.ENTITY).renderSql(setOf("name", "id", "order")),
        )
    }

    @Test
    fun upsertConflictKeyFollowsActive() {
        // T-M23.2（用户实测：saveOrUpdate 主键应为可选）：主键为 toggle 列（默认勾选渲染完整 upsert；
        // 取消勾选 → 纯 INSERT 新记录路径，无 ON CONFLICT）
        // T-M43：PG 方言下断言 ON CONFLICT 形态（MySQL 形态断言见 upsertFollowsDialect）
        val model = BatchModel(
            kind = BatchKind.UPSERT,
            tableName = "task_record",
            toggleColumns = listOf(
                BatchColumn("id", "entity.id", quote = false, checked = true),
                BatchColumn("task_name", "entity.task_name", quote = true, checked = true),
                BatchColumn("dic_status", "entity.dic_status", quote = false, checked = true),
            ),
            conflictColumn = "id",
            dialect = PostgreSQLDialect,
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
        // UPSERT（T-M43）：默认 MySQL 方言 → ON DUPLICATE KEY UPDATE + VALUES()；列名按需引用
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
                "ON DUPLICATE KEY UPDATE name = VALUES(name)",
            upsert.renderSql(setOf("table", "name")),
        )
    }

    @Test
    fun upsertFollowsDialect() {
        // T-M43「设置里出现 = 承诺可用」：UPSERT 冲突子句按方言渲染全量断言
        val columns = listOf(
            BatchColumn("id", "entity.id", quote = false, checked = true),
            BatchColumn("name", "entity.name", quote = true, checked = true),
        )
        val active = setOf("id", "name")
        // MySQL（默认）/ MariaDB：ON DUPLICATE KEY UPDATE + VALUES()
        val dupKeySql = "INSERT INTO t (id, name)\nVALUES (?, ?)\nON DUPLICATE KEY UPDATE name = VALUES(name)"
        assertEquals(dupKeySql, BatchModel(BatchKind.UPSERT, "t", columns, conflictColumn = "id").renderSql(active))
        assertEquals(
            dupKeySql,
            BatchModel(BatchKind.UPSERT, "t", columns, conflictColumn = "id", dialect = MariaDbDialect).renderSql(active),
        )
        // PostgreSQL / SQLite / KingBase：ON CONFLICT + EXCLUDED（P-立项-05：H2 移出——
        // ON CONFLICT 在 H2 任何模式均语法错误，改为下方 MERGE 整语句形态）
        val conflictSql = "INSERT INTO t (id, name)\nVALUES (?, ?)\nON CONFLICT (id) DO UPDATE SET name = EXCLUDED.name"
        for (dialect in listOf(PostgreSQLDialect, SqliteDialect, KingBaseDialect)) {
            assertEquals(
                conflictSql,
                BatchModel(BatchKind.UPSERT, "t", columns, conflictColumn = "id", dialect = dialect).renderSql(active),
            )
        }
        // H2（P-立项-05）：原生 upsert 为整语句 MERGE INTO..KEY..VALUES（引擎实证 ON CONFLICT 不可用）
        assertEquals(
            "MERGE INTO t (id, name) KEY (id) VALUES (?, ?)",
            BatchModel(BatchKind.UPSERT, "t", columns, conflictColumn = "id", dialect = H2Dialect).renderSql(active),
        )
        // Oracle / SQL Server / DM：无原生 upsert → 降级纯 INSERT（SQL 在本方言可执行）
        val plainSql = "INSERT INTO t (id, name)\nVALUES (?, ?)"
        for (dialect in listOf(OracleDialect, SqlServerDialect, DmDialect)) {
            assertEquals(
                plainSql,
                BatchModel(BatchKind.UPSERT, "t", columns, conflictColumn = "id", dialect = dialect).renderSql(active),
            )
        }
    }
}
