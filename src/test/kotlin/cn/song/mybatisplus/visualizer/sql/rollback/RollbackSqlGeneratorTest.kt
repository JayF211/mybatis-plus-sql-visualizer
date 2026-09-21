package cn.song.mybatisplus.visualizer.sql.rollback

import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 回滚 SQL 生成器测试（T-RollbackSql P1-F1/F2）：普通 JUnit（零 IDE fixture），
 * 覆盖开发计划 §4.1 用例清单——单行/多行 INSERT、上限截断、谓词形态、转义、
 * 注释剥离、类型拒绝、UPDATE/DELETE 指引、多语句、主键偏好、评级与自查模板，
 * 以及 P2 快照回填（TSV 解析 / INSERT 回填 / UPDATE 反向乐观锁）。
 */
class RollbackSqlGeneratorTest {

    private fun generate(sql: String, pk: List<String>? = null) = RollbackSqlGenerator.generate(sql, pk)

    // ======================== INSERT→DELETE（P1 主路径） ========================

    @Test
    fun `单行INSERT生成全列等值谓词DELETE`() {
        val result = generate("INSERT INTO user (id, name, age) VALUES (1, 'Tom', 20)")
        assertEquals(RollbackSqlGenerator.Rating.PARTIAL, result.rating)
        assertEquals(listOf("DELETE FROM user WHERE id = 1 AND name = 'Tom' AND age = 20;"), result.statements)
        assertEquals("user", result.targetTable)
        assertNotNull("整行谓词应带 COUNT 自查模板", result.countCheckSql)
        assertTrue(result.countCheckSql!!.startsWith("SELECT COUNT(*) FROM user WHERE"))
    }

    @Test
    fun `多行INSERT生成批量DELETE`() {
        val result = generate("INSERT INTO t (a, b) VALUES (1, 'x'), (2, 'y'), (3, NULL)")
        assertEquals(3, result.statements.size)
        assertEquals("DELETE FROM t WHERE a = 1 AND b = 'x';", result.statements[0])
        assertEquals("DELETE FROM t WHERE a = 2 AND b = 'y';", result.statements[1])
        assertEquals("DELETE FROM t WHERE a = 3 AND b IS NULL;", result.statements[2])
    }

    @Test
    fun `NULL值渲染为IS NULL谓词`() {
        val result = generate("INSERT INTO t (a, b) VALUES (NULL, 'x')")
        assertEquals(listOf("DELETE FROM t WHERE a IS NULL AND b = 'x';"), result.statements)
    }

    @Test
    fun `主键偏好传入时按主键定位评级Complete`() {
        val result = generate("INSERT INTO t (id, name, age) VALUES (1, 'Tom', 20)", pk = listOf("id"))
        assertEquals(RollbackSqlGenerator.Rating.COMPLETE, result.rating)
        assertEquals(listOf("DELETE FROM t WHERE id = 1;"), result.statements)
        assertNull("主键定位无需 COUNT 自查", result.countCheckSql)
    }

    @Test
    fun `主键列不在语句列清单时回落整行谓词`() {
        // 主键声明为 missing_col 但语句没写该列（自增主键形态）→ 整行谓词 Partial
        val result = generate("INSERT INTO t (name) VALUES ('Tom')", pk = listOf("missing_col"))
        assertEquals(RollbackSqlGenerator.Rating.PARTIAL, result.rating)
        assertEquals(listOf("DELETE FROM t WHERE name = 'Tom';"), result.statements)
    }

    @Test
    fun `字符串单引号与反斜杠转义`() {
        val result = generate("INSERT INTO t (v) VALUES ('O''Brien')")
        assertEquals(listOf("DELETE FROM t WHERE v = 'O''Brien';"), result.statements)
    }

    @Test
    fun `注释剥离后仍可解析`() {
        val result = generate("-- 插入用户\nINSERT INTO t (a) VALUES (1) /* block */")
        assertEquals(listOf("DELETE FROM t WHERE a = 1;"), result.statements)
    }

    @Test
    fun `语句尾分号与多行格式容忍`() {
        val sql = """
            INSERT INTO t (a, b)
            VALUES (
                1,
                'x'
            );
        """.trimIndent()
        val result = generate(sql)
        assertEquals(listOf("DELETE FROM t WHERE a = 1 AND b = 'x';"), result.statements)
    }

    @Test
    fun `字符串字面量内分号不切断语句`() {
        val result = generate("INSERT INTO t (v) VALUES ('a;b')")
        assertEquals(listOf("DELETE FROM t WHERE v = 'a;b';"), result.statements)
    }

    @Test
    fun `占位符未回填降级Partial并告警`() {
        val result = generate("INSERT INTO t (a, b) VALUES (?, 'x')")
        assertEquals(RollbackSqlGenerator.Rating.PARTIAL, result.rating)
        assertEquals(listOf("DELETE FROM t WHERE a = ? AND b = 'x';"), result.statements)
        assertTrue(result.warnings.any { it.messageKey == "rollback.warn.placeholder" })
    }

    @Test
    fun `多行INSERT超500条上限截断并降级`() {
        val rows = (1..520).joinToString(", ") { "($it, 'v')" }
        val result = generate("INSERT INTO t (a, b) VALUES $rows")
        assertEquals(RollbackSqlGenerator.MAX_STATEMENTS, result.statements.size)
        assertTrue(result.warnings.any { it.messageKey == "rollback.warn.truncated" })
        assertEquals(RollbackSqlGenerator.Rating.PARTIAL, result.rating)
        // 截断保留首条与末条形态正确
        assertEquals("DELETE FROM t WHERE a = 1 AND b = 'v';", result.statements[0])
    }

    @Test
    fun `无列清单INSERT诚实拒绝`() {
        val result = generate("INSERT INTO t VALUES (1, 'x')")
        assertEquals(RollbackSqlGenerator.Rating.UNABLE, result.rating)
        assertTrue(result.statements.isEmpty())
        assertTrue(result.warnings.any { it.messageKey == "rollback.unable.noColumns" })
    }

    @Test
    fun `INSERT_SELECT诚实拒绝`() {
        val result = generate("INSERT INTO t (a) SELECT a FROM src")
        assertEquals(RollbackSqlGenerator.Rating.UNABLE, result.rating)
        assertTrue(result.warnings.any { it.messageKey == "rollback.unable.insertSelect" })
    }

    @Test
    fun `upsert形态诚实拒绝`() {
        val result = generate("INSERT INTO t (a) VALUES (1) ON DUPLICATE KEY UPDATE a = 2")
        assertEquals(RollbackSqlGenerator.Rating.UNABLE, result.rating)
        assertTrue(result.warnings.any { it.messageKey == "rollback.unable.upsert" })
    }

    // ======================== UPDATE / DELETE（诚实拒绝 + 指引） ========================

    @Test
    fun `UPDATE不可自动回滚但给指引与快照模板`() {
        val result = generate("UPDATE user SET name = 'x', age = 3 WHERE id = 1")
        assertEquals(RollbackSqlGenerator.Rating.UNABLE, result.rating)
        assertTrue(result.statements.isEmpty())
        assertEquals("user", result.targetTable)
        assertTrue(result.warnings.any { it.messageKey == "rollback.unable.update" })
        assertTrue(result.warnings.any { it.messageKey == "rollback.guidance.recovery" })
        assertEquals("SELECT * FROM user WHERE id = 1;", result.snapshotSelectSql)
        assertEquals(listOf("name", "age"), result.setColumns)
    }

    @Test
    fun `DELETE不可自动回滚但给快照模板`() {
        val result = generate("DELETE FROM user WHERE created < '2020-01-01'")
        assertEquals(RollbackSqlGenerator.Rating.UNABLE, result.rating)
        assertEquals("SELECT * FROM user WHERE created < '2020-01-01';", result.snapshotSelectSql)
        assertEquals(emptyList<String>(), result.setColumns)
    }

    @Test
    fun `无WHERE全表UPDATE给全表快照模板`() {
        val result = generate("UPDATE t SET a = 1")
        assertEquals("SELECT * FROM t;", result.snapshotSelectSql)
        assertNull(result.whereText)
    }

    // ======================== 类型拒绝 / 多语句 ========================

    @Test
    fun `SELECT与DDL不可回滚`() {
        val select = generate("SELECT * FROM t")
        assertEquals(RollbackSqlGenerator.Rating.UNABLE, select.rating)
        val ddl = generate("CREATE TABLE t (a INT)")
        assertEquals(RollbackSqlGenerator.Rating.UNABLE, ddl.rating)
        assertTrue(ddl.warnings.any { it.messageKey == "rollback.unable.type" })
    }

    @Test
    fun `MERGE不可回滚`() {
        val result = generate("MERGE INTO t USING src ON t.a = src.a WHEN MATCHED THEN UPDATE SET t.b = src.b")
        assertEquals(RollbackSqlGenerator.Rating.UNABLE, result.rating)
    }

    @Test
    fun `无法解析的文本诚实拒绝`() {
        val result = generate("这不是SQL")
        assertEquals(RollbackSqlGenerator.Rating.UNABLE, result.rating)
        assertTrue(result.warnings.any { it.messageKey == "rollback.unable.parse" })
    }

    @Test
    fun `空输入诚实拒绝`() {
        assertEquals(RollbackSqlGenerator.Rating.UNABLE, generate("").rating)
        assertEquals(RollbackSqlGenerator.Rating.UNABLE, generate("-- 仅注释").rating)
    }

    @Test
    fun `多语句混合任一不可推导整体拒绝`() {
        val result = generate("INSERT INTO t (a) VALUES (1); CREATE TABLE x (b INT)")
        assertEquals(RollbackSqlGenerator.Rating.UNABLE, result.rating)
        assertTrue(result.statements.isEmpty())
    }

    @Test
    fun `多语句全部可推导时合并产出`() {
        val result = generate(
            "INSERT INTO t (id, a) VALUES (1, 'x'); INSERT INTO t (id, a) VALUES (2, 'y')",
            pk = listOf("id"),
        )
        assertEquals(RollbackSqlGenerator.Rating.COMPLETE, result.rating)
        assertEquals(2, result.statements.size)
        assertTrue(result.warnings.any { it.messageKey == "rollback.warn.multiStatement" })
    }

    // ======================== P2：TSV 解析 + 快照回填 ========================

    @Test
    fun `TSV解析_网格复制格式`() {
        val parsed = RollbackSqlGenerator.parseSnapshotTsv("id\tname\tage\n1\tTom\t20\n2\tNULL\t\n")
        assertNotNull(parsed)
        val (columns, rows) = parsed!!
        assertEquals(listOf("id", "name", "age"), columns)
        assertEquals(listOf(listOf("1", "Tom", "20"), listOf("2", null, null)), rows)
    }

    @Test
    fun `TSV解析_空文本与单行返回null`() {
        assertNull(RollbackSqlGenerator.parseSnapshotTsv(""))
        assertNull(RollbackSqlGenerator.parseSnapshotTsv("a\tb"))
        assertNull(RollbackSqlGenerator.parseSnapshotTsv("\n\n"))
    }

    @Test
    fun `DELETE原语句经快照回填生成INSERT`() {
        val result = RollbackSqlGenerator.generateFromSnapshotRows(
            "user",
            listOf("id", "name"),
            listOf(listOf("1", "Tom"), listOf("2", "NULL")),
            RollbackSqlGenerator.SnapshotMode.BACKFILL_INSERT,
        )
        assertEquals(listOf("INSERT INTO user (id, name) VALUES (1, 'Tom'), (2, NULL);"), result.statements)
        assertTrue(result.warnings.any { it.messageKey == "rollback.warn.snapshotCompleteness" })
    }

    @Test
    fun `快照回填INSERT_单引号转义与NULL`() {
        val result = RollbackSqlGenerator.generateFromSnapshotRows(
            "t",
            listOf("v"),
            listOf(listOf("O'Brien")),
            RollbackSqlGenerator.SnapshotMode.BACKFILL_INSERT,
        )
        assertEquals(listOf("INSERT INTO t (v) VALUES ('O''Brien');"), result.statements)
    }

    @Test
    fun `快照回填INSERT_多行合批与上限`() {
        val rows = (1..510).map { listOf(it.toString(), "v") }
        val result = RollbackSqlGenerator.generateFromSnapshotRows(
            "t",
            listOf("a", "b"),
            rows,
            RollbackSqlGenerator.SnapshotMode.BACKFILL_INSERT,
        )
        // 500 行合批上限 → 两条 INSERT（500 + 10）
        assertEquals(2, result.statements.size)
        assertTrue(result.statements[0].startsWith("INSERT INTO t (a, b) VALUES (1, 'v'),"))
        assertTrue(result.statements[0].endsWith("(500, 'v');"))
        assertTrue(result.statements[1].startsWith("INSERT INTO t (a, b) VALUES (501, 'v'),"))
        assertTrue(result.warnings.any { it.messageKey == "rollback.warn.snapshotRowLimit" })
    }

    @Test
    fun `UPDATE原语句经快照回填生成反向UPDATE乐观锁谓词`() {
        val result = RollbackSqlGenerator.generateFromSnapshotRows(
            "user",
            listOf("id", "age"),
            listOf(listOf("1", "20")),
            RollbackSqlGenerator.SnapshotMode.REVERT_UPDATE,
            listOf("id"),
            listOf("age"),
            mapOf("age" to "3"),
        )
        assertEquals(RollbackSqlGenerator.Rating.COMPLETE, result.rating)
        assertEquals(listOf("UPDATE user SET age = 20 WHERE id = 1 AND age = 3;"), result.statements)
    }

    @Test
    fun `UPDATE反向_无主键走整行旧值镜像降级Partial`() {
        val result = RollbackSqlGenerator.generateFromSnapshotRows(
            "t",
            listOf("a", "b"),
            listOf(listOf("1", "old")),
            RollbackSqlGenerator.SnapshotMode.REVERT_UPDATE,
            null,
            listOf("b"),
            mapOf("b" to "'new'"),
        )
        assertEquals(RollbackSqlGenerator.Rating.PARTIAL, result.rating)
        assertEquals(listOf("UPDATE t SET b = 'old' WHERE a = 1 AND b = 'old' AND b = 'new';"), result.statements)
    }

    @Test
    fun `UPDATE反向_SET列不在快照中拒绝`() {
        val result = RollbackSqlGenerator.generateFromSnapshotRows(
            "t",
            listOf("a"),
            listOf(listOf("1")),
            RollbackSqlGenerator.SnapshotMode.REVERT_UPDATE,
            null,
            listOf("missing"),
            emptyMap(),
        )
        assertEquals(RollbackSqlGenerator.Rating.UNABLE, result.rating)
        assertTrue(result.warnings.any { it.messageKey == "rollback.snapshot.columnMismatch" })
    }

    // ======================== 告警文案渲染（P8/P11 回归） ========================

    /** UI 层同款渲染（`RollbackSqlDialog:303-305` / `SqlPreviewPanel:860-861` 口径）。 */
    private fun render(warning: RollbackSqlGenerator.RollbackWarning): String =
        if (warning.messageArgs().isEmpty()) {
            MpSqlVisualizerBundle.message(warning.messageKey())
        } else {
            MpSqlVisualizerBundle.message(warning.messageKey(), *warning.messageArgs().toTypedArray())
        }

    @Test
    fun `告警文案渲染_不留未回填占位符也不显裸键名`() {
        // 只断言 messageKey 会从两类缺陷旁边走过去：①实参给少了（弹窗显示「上限 {1}」，P8）；
        // ②键名在 bundle 里不存在（弹窗显示裸键名，P11）。此处走真实渲染路径核对——
        // 静态核对另见 convention/BundlePlaceholderArityTest（覆盖全部 30 处告警站点）。
        val rowLimit = RollbackSqlGenerator.generateFromSnapshotRows(
            "t",
            listOf("a"),
            (1..250).map { listOf(it.toString()) },
            RollbackSqlGenerator.SnapshotMode.BACKFILL_INSERT,
        )
        val rowLimitWarning = rowLimit.warnings().first { it.messageKey() == "rollback.warn.snapshotRowLimit" }
        val rowLimitText = render(rowLimitWarning)
        assertTrue("应回填实际行数: $rowLimitText", rowLimitText.contains("250"))
        assertTrue(
            "应回填上限数值: $rowLimitText",
            rowLimitText.contains(RollbackSqlGenerator.SNAPSHOT_ROW_LIMIT.toString()),
        )
        assertFalse("不得残留未回填占位符: $rowLimitText", rowLimitText.contains("{"))

        val mismatch = RollbackSqlGenerator.generateFromSnapshotRows(
            "t",
            listOf("a"),
            listOf(listOf("1")),
            RollbackSqlGenerator.SnapshotMode.REVERT_UPDATE,
            null,
            listOf("missing"),
            emptyMap(),
        )
        val mismatchWarning = mismatch.warnings().first { it.messageKey() == "rollback.snapshot.columnMismatch" }
        val mismatchText = render(mismatchWarning)
        assertFalse("不得残留未回填占位符（键缺失时会渲染成裸键名）: $mismatchText", mismatchText.contains("{"))
        assertTrue("应回填缺失列名: $mismatchText", mismatchText.contains("missing"))
    }

    @Test
    fun `INSERT原语句经快照回填生成DELETE主键Complete`() {
        val result = RollbackSqlGenerator.generateFromSnapshotRows(
            "t",
            listOf("id", "name"),
            listOf(listOf("1", "Tom")),
            RollbackSqlGenerator.SnapshotMode.REVERT_INSERT,
            listOf("id"),
        )
        assertEquals(RollbackSqlGenerator.Rating.COMPLETE, result.rating)
        assertEquals(listOf("DELETE FROM t WHERE id = 1;"), result.statements)
        assertNull(result.countCheckSql)
    }

    @Test
    fun `空快照输入拒绝`() {
        val result = RollbackSqlGenerator.generateFromSnapshotRows(
            "t",
            listOf("a"),
            emptyList(),
            RollbackSqlGenerator.SnapshotMode.BACKFILL_INSERT,
        )
        assertEquals(RollbackSqlGenerator.Rating.UNABLE, result.rating)
        assertTrue(result.warnings.any { it.messageKey == "rollback.snapshot.emptyInput" })
    }

    @Test
    fun `renderCell数字启发式与NULL`() {
        assertEquals("NULL", RollbackSqlGenerator.renderCell(null))
        assertEquals("NULL", RollbackSqlGenerator.renderCell("null"))
        assertEquals("123", RollbackSqlGenerator.renderCell("123"))
        assertEquals("1.5", RollbackSqlGenerator.renderCell("1.5"))
        assertEquals("'abc'", RollbackSqlGenerator.renderCell("abc"))
        assertFalse(RollbackSqlGenerator.renderCell("12x").startsWith("12"))
    }

    @Test
    fun `日期字面量渲染为带引号日期`() {
        val result = generate("INSERT INTO t (d) VALUES ({d '2020-01-31'})")
        assertEquals(listOf("DELETE FROM t WHERE d = '2020-01-31';"), result.statements)
    }

    // ======================== 弱谓词警示（T-UnboundedWriteGuard / T-UW-03） ========================

    @Test
    fun `恒真WHERE的UPDATE回滚附弱谓词警示`() {
        val result = generate("UPDATE t SET a = 1 WHERE 1 = 1")
        assertEquals(RollbackSqlGenerator.Rating.UNABLE, result.rating)
        assertTrue(
            "恒真谓词 → rollback.warn.tautology（快照 SELECT 复用原 WHERE 将覆盖全表）",
            result.warnings.any { it.messageKey == "rollback.warn.tautology" },
        )
    }

    @Test
    fun `OR恒真WHERE的DELETE回滚附弱谓词警示`() {
        val result = generate("DELETE FROM t WHERE id = 1 OR id = id")
        assertEquals(RollbackSqlGenerator.Rating.UNABLE, result.rating)
        assertTrue(result.warnings.any { it.messageKey == "rollback.warn.tautology" })
    }

    @Test
    fun `LIKE纯通配WHERE的DELETE回滚附弱谓词警示`() {
        val result = generate("DELETE FROM t WHERE name LIKE '%'")
        assertTrue(result.warnings.any { it.messageKey == "rollback.warn.likeWildcard" })
    }

    @Test
    fun `有界WHERE不附弱谓词警示`() {
        val bounded = generate("UPDATE t SET a = 1 WHERE id = 7")
        assertFalse(bounded.warnings.any { it.messageKey == "rollback.warn.tautology" || it.messageKey == "rollback.warn.likeWildcard" })
        val prefixLike = generate("DELETE FROM t WHERE name LIKE 'abc%'")
        assertFalse(prefixLike.warnings.any { it.messageKey == "rollback.warn.likeWildcard" })
    }
}
