package cn.song.mybatisplus.visualizer.log

import cn.song.mybatisplus.visualizer.model.BatchColumn
import cn.song.mybatisplus.visualizer.model.BatchKind
import cn.song.mybatisplus.visualizer.model.BatchModel
import cn.song.mybatisplus.visualizer.sql.H2Dialect
import cn.song.mybatisplus.visualizer.sql.MySqlDialect
import cn.song.mybatisplus.visualizer.sql.OracleDialect
import cn.song.mybatisplus.visualizer.sql.PostgreSQLDialect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * O-91（缩范围首版）可移植导出判别测试：导出串联（CRUD 行 + 回滚链产物行）＋方言形态标注
 * （`-- dialect: X | kind: CRUD/ROLLBACK | upsert: 形态或 no`）＋ R4 三格式通道（formatPortable）。
 *
 * 判别红线：实现前本类编译红（PortableSqlRow / PortableSqlExport / formatPortable 均未定义）。
 * upsert 形态判定全部出自方言链能力（Dialect.renderUpsert / renderUpsertStatement 非空探测），
 * 与 BatchModel.renderSql UPSERT 分支的降级决策同源，测试断言按方言逐形态对齐。
 */
class PortableSqlExportTest {

    // ---------- upsert 形态标注（方言链降级决策对齐） ----------

    @Test
    fun testUpsert标注_MySQL后缀形态suffix() {
        val sql = "INSERT INTO `user` (`id`, `name`) VALUES (?, ?)\n" +
            "ON DUPLICATE KEY UPDATE `name` = VALUES(`name`)"
        val form = PortableSqlExport.upsertFormOf(MySqlDialect.INSTANCE, sql, null)
        assertEquals(PortableSqlExport.UPSERT_SUFFIX, form)
    }

    @Test
    fun testUpsert标注_PG后缀形态suffix() {
        val sql = "INSERT INTO \"user\" (id, name) VALUES (?, ?)\n" +
            "ON CONFLICT (id) DO UPDATE SET name = EXCLUDED.name"
        val form = PortableSqlExport.upsertFormOf(PostgreSQLDialect.INSTANCE, sql, null)
        assertEquals(PortableSqlExport.UPSERT_SUFFIX, form)
    }

    @Test
    fun testUpsert标注_H2整语句形态statement() {
        val sql = "MERGE INTO \"user\" (id, name) KEY (id) VALUES (?, ?)"
        val form = PortableSqlExport.upsertFormOf(H2Dialect.INSTANCE, sql, null)
        assertEquals(PortableSqlExport.UPSERT_STATEMENT, form)
    }

    @Test
    fun testUpsert标注_Oracle无原生形态降级insert() {
        // UPSERT 模型 + 方言链两形态均 null（Oracle）→ 降级纯 INSERT，标注 insert
        val model = BatchModel(BatchKind.UPSERT, "user",
            listOf(BatchColumn("id", "id", null, true)), java.util.List.of(), null, "id", OracleDialect.INSTANCE)
        val sql = model.renderSql(setOf("id"))
        val form = PortableSqlExport.upsertFormOf(OracleDialect.INSTANCE, sql, model)
        assertEquals(PortableSqlExport.UPSERT_DEGRADED, form)
        assertTrue("Oracle 降级产物应为纯 INSERT：$sql", sql.trimStart().uppercase().startsWith("INSERT"))
    }

    @Test
    fun testUpsert标注_原生链但文本纯INSERT为新记录路径no() {
        // MySQL（后缀能力在）+ UPSERT 模型 + 文本无冲突子句（主键未勾选的新记录路径）→ no（非降级）
        val model = BatchModel(BatchKind.UPSERT, "user",
            listOf(BatchColumn("id", "id", null, true)), java.util.List.of(), null, "id", MySqlDialect.INSTANCE)
        val sql = model.renderSql(emptySet())
        val form = PortableSqlExport.upsertFormOf(MySqlDialect.INSTANCE, sql, model)
        assertEquals(PortableSqlExport.UPSERT_NONE, form)
    }

    @Test
    fun testUpsert标注_非Upsert语句no() {
        assertEquals(PortableSqlExport.UPSERT_NONE, PortableSqlExport.upsertFormOf(MySqlDialect.INSTANCE, "SELECT * FROM t", null))
        assertEquals(PortableSqlExport.UPSERT_NONE, PortableSqlExport.upsertFormOf(MySqlDialect.INSTANCE, "UPDATE t SET a = ? WHERE id = ?", null))
    }

    // ---------- 导出串联（CRUD 行 + 回滚链产物行） ----------

    @Test
    fun test串联_普通INSERT产出CRUD与ROLLBACK两行() {
        val sql = "INSERT INTO user (id, name) VALUES (?, ?)"
        val rows = PortableSqlExport.collectRows(sql, MySqlDialect.INSTANCE, null)
        assertEquals(2, rows.size)
        val crud = rows[0]
        assertEquals(PortableSqlKind.CRUD, crud.kind)
        assertEquals("MySQL", crud.dialect)
        assertEquals(PortableSqlExport.UPSERT_NONE, crud.upsert)
        assertEquals(sql, crud.sql)
        val rollback = rows[1]
        assertEquals(PortableSqlKind.ROLLBACK, rollback.kind)
        assertEquals(PortableSqlExport.UPSERT_NONE, rollback.upsert)
        assertTrue("INSERT 回滚应推导 DELETE：${rollback.sql}", rollback.sql.trimStart().uppercase().startsWith("DELETE"))
    }

    @Test
    fun test串联_UPSERT回滚链诚实拒绝只出CRUD行() {
        // MySQL ON DUPLICATE KEY UPDATE：回滚链显式拒绝（rollback.unable.upsert）→ 导出面不造回滚行
        val sql = "INSERT INTO user (id, name) VALUES (?, ?) ON DUPLICATE KEY UPDATE name = VALUES(name)"
        val rows = PortableSqlExport.collectRows(sql, MySqlDialect.INSTANCE, null)
        assertEquals(1, rows.size)
        assertEquals(PortableSqlKind.CRUD, rows[0].kind)
        assertEquals(PortableSqlExport.UPSERT_SUFFIX, rows[0].upsert)
    }

    @Test
    fun test串联_SELECT无回滚行() {
        val rows = PortableSqlExport.collectRows("SELECT * FROM user WHERE id = ?", MySqlDialect.INSTANCE, null)
        assertEquals(1, rows.size)
        assertEquals(PortableSqlKind.CRUD, rows[0].kind)
    }

    // ---------- R4 三格式通道（formatPortable） ----------

    @Test
    fun testFormatPortable_TXT注释头逐字形态() {
        val rows = listOf(
            PortableSqlRow("MySQL", PortableSqlKind.CRUD, PortableSqlExport.UPSERT_SUFFIX, "INSERT INTO t (id) VALUES (?) ON DUPLICATE KEY UPDATE id = VALUES(id)"),
            PortableSqlRow("MySQL", PortableSqlKind.ROLLBACK, PortableSqlExport.UPSERT_NONE, "DELETE FROM t WHERE id = 1"),
        )
        val out = SqlExportService.formatPortable(rows, SqlExportFormat.TXT)
        assertTrue(out.contains("-- === SQL #1 ==="))
        assertTrue("upsert 标注行断言：$out", out.contains("-- dialect: MySQL | kind: CRUD | upsert: suffix"))
        assertTrue(out.contains("-- === SQL #2 ==="))
        assertTrue(out.contains("-- dialect: MySQL | kind: ROLLBACK | upsert: no"))
        assertTrue(out.contains("ON DUPLICATE KEY UPDATE id = VALUES(id)"))
        assertTrue(out.contains("DELETE FROM t WHERE id = 1"))
    }

    @Test
    fun testFormatPortable_CSV标注作列() {
        val rows = listOf(
            PortableSqlRow("MySQL", PortableSqlKind.CRUD, PortableSqlExport.UPSERT_NONE, "SELECT 'a,b' FROM t"),
        )
        val out = SqlExportService.formatPortable(rows, SqlExportFormat.CSV)
        assertTrue(out.startsWith("dialect,kind,upsert,sql\n"))
        assertTrue("sql 字段含逗号须走既有 CSV 转义：$out", out.contains("\"SELECT 'a,b' FROM t\""))
        assertTrue(out.contains("MySQL,CRUD,no,"))
    }

    @Test
    fun testFormatPortable_TSV标注作列且扁平化() {
        val rows = listOf(
            PortableSqlRow("H2", PortableSqlKind.CRUD, PortableSqlExport.UPSERT_STATEMENT, "MERGE INTO t\nKEY (id)"),
        )
        val out = SqlExportService.formatPortable(rows, SqlExportFormat.TSV)
        assertTrue(out.startsWith("dialect\tkind\tupsert\tsql\n"))
        assertTrue("TSV 字段内换行须扁平化：$out", out.contains("MERGE INTO t KEY (id)"))
        assertTrue(out.contains("H2\tCRUD\tstatement\t"))
    }
}
