package cn.song.mybatisplus.visualizer.sql.rollback

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.sql.Connection
import java.sql.DriverManager
import java.sql.ResultSet

/**
 * 回滚往返验证（T-RollbackSql §4.2，dbx `rollback∘rollback == forward` 思路变体）：
 * 建表 → 执行正向 DML → 生成回滚 → 执行回滚 → 断言数据归零/还原。
 *
 * P1 验 INSERT 路径；P2 验快照回填的 DELETE→INSERT 与 UPDATE→反向 UPDATE（含乐观锁
 * 二次执行 no-op）。H2 嵌入式（MySQL 兼容模式）随跑——testcontainers 真库矩阵由
 * DialectDockerVerificationTest 体系承担（无 Docker 自动 Assume 跳过），本类保证本地回归
 * 无 Docker 也覆盖往返语义。
 */
class RollbackRoundTripTest {

    private lateinit var conn: Connection

    @Before
    fun setUp() {
        // 每用例独立内存库（DB_CLOSE_DELAY=-1 保活到显式关闭）
        conn = DriverManager.getConnection("jdbc:h2:mem:rollback_rt_${System.nanoTime()};MODE=MySQL;DB_CLOSE_DELAY=-1")
    }

    @After
    fun tearDown() {
        conn.close()
    }

    private fun execute(sql: String): Int = conn.createStatement().use { it.executeUpdate(sql) }

    private fun queryCount(table: String): Int =
        conn.createStatement().use { st ->
            st.executeQuery("SELECT COUNT(*) FROM $table").use { rs: ResultSet ->
                rs.next()
                rs.getInt(1)
            }
        }

    /** 快照取行（模拟「用户执行快照查询并复制结果网格」：含表头的 TSV；列名归一小写，
     * 对齐常见网格复制保留 DDL 小写形态、回避 H2 元数据全大写差异）。 */
    private fun snapshotAsTsv(selectSql: String): String =
        conn.createStatement().use { st ->
            st.executeQuery(selectSql).use { rs: ResultSet ->
                val meta = rs.metaData
                val columns = (1..meta.columnCount).map { meta.getColumnLabel(it).lowercase() }
                val sb = StringBuilder(columns.joinToString("\t")).append('\n')
                while (rs.next()) {
                    sb.append(columns.indices.joinToString("\t") { i ->
                        rs.getString(i + 1) ?: RollbackSqlGenerator.NULL_LITERAL
                    }).append('\n')
                }
                sb.toString()
            }
        }

    // ======================== P1：INSERT→DELETE ========================

    @Test
    fun `INSERT正向执行后回滚DELETE清空表`() {
        conn.createStatement().use { it.execute("CREATE TABLE rt_user (id INT PRIMARY KEY, name VARCHAR(64))") }
        val insertSql = "INSERT INTO rt_user (id, name) VALUES (1, 'Tom')"
        assertEquals(1, execute(insertSql))
        assertEquals(1, queryCount("rt_user"))

        // 无主键元数据（P1 调用形态）→ 整行谓词 Partial；COUNT 自查应恰命中 1 行
        val rollback = RollbackSqlGenerator.generate(insertSql)
        assertEquals(RollbackSqlGenerator.Rating.PARTIAL, rollback.rating)
        conn.createStatement().use { st ->
            st.executeQuery(rollback.countCheckSql!!.removeSuffix(";")).use { rs ->
                rs.next()
                assertEquals(1, rs.getInt(1))
            }
        }

        rollback.statements.forEach { assertEquals(1, execute(it)) }
        assertEquals("回滚后表应清空", 0, queryCount("rt_user"))
    }

    @Test
    fun `INSERT多行回滚DELETE逐行清除`() {
        conn.createStatement().use { it.execute("CREATE TABLE rt_batch (a INT, b VARCHAR(16))") }
        val insertSql = "INSERT INTO rt_batch (a, b) VALUES (1, 'x'), (2, 'y'), (3, NULL)"
        execute(insertSql)
        assertEquals(3, queryCount("rt_batch"))

        val rollback = RollbackSqlGenerator.generate(insertSql)
        assertEquals(3, rollback.statements.size)
        rollback.statements.forEach { assertEquals(1, execute(it)) }
        assertEquals(0, queryCount("rt_batch"))
    }

    // ======================== P2：快照回填 ========================

    @Test
    fun `DELETE正向执行后粘贴快照回填INSERT还原`() {
        conn.createStatement().use { it.execute("CREATE TABLE rt_del (id INT PRIMARY KEY, name VARCHAR(64))") }
        execute("INSERT INTO rt_del (id, name) VALUES (1, 'Tom')")
        execute("INSERT INTO rt_del (id, name) VALUES (2, NULL)")

        // 执行前取快照（用户路径：快照查询 → 复制结果网格）
        val tsv = snapshotAsTsv("SELECT id, name FROM rt_del")
        assertEquals(3, tsv.trim().lines().size)

        val original = "DELETE FROM rt_del WHERE id <= 2"
        execute(original)
        assertEquals(0, queryCount("rt_del"))

        // 粘贴快照 → 生成回填 INSERT → 执行 → 数据还原（含 NULL 列）
        val parsed = RollbackSqlGenerator.parseSnapshotTsv(tsv)!!
        val rollback = RollbackSqlGenerator.generateFromSnapshotRows(
            "rt_del", parsed.first, parsed.second,
            mode = RollbackSqlGenerator.SnapshotMode.BACKFILL_INSERT,
        )
        assertEquals(RollbackSqlGenerator.Rating.PARTIAL, rollback.rating)
        rollback.statements.forEach { execute(it) }
        assertEquals(2, queryCount("rt_del"))
        conn.createStatement().use { st ->
            st.executeQuery("SELECT name FROM rt_del WHERE id = 2").use { rs ->
                rs.next()
                assertEquals(null, rs.getString(1))
            }
        }
    }

    @Test
    fun `UPDATE正向执行后粘贴快照反向UPDATE还原`() {
        conn.createStatement().use { it.execute("CREATE TABLE rt_upd (id INT PRIMARY KEY, age INT)") }
        execute("INSERT INTO rt_upd (id, age) VALUES (1, 20)")
        execute("INSERT INTO rt_upd (id, age) VALUES (2, 30)")

        val tsv = snapshotAsTsv("SELECT id, age FROM rt_upd")
        val original = "UPDATE rt_upd SET age = 99 WHERE age >= 20"
        assertEquals(2, execute(original))

        val parsed = RollbackSqlGenerator.parseSnapshotTsv(tsv)!!
        val rollback = RollbackSqlGenerator.generateFromSnapshotRows(
            "rt_upd", parsed.first, parsed.second,
            mode = RollbackSqlGenerator.SnapshotMode.REVERT_UPDATE,
            primaryKeyColumns = listOf("id"),
            changedColumns = listOf("age"),
            newValuesByColumn = mapOf("age" to "99"),
        )
        assertEquals(RollbackSqlGenerator.Rating.COMPLETE, rollback.rating)
        assertEquals(2, rollback.statements.size)
        assertTrue(rollback.statements[0].endsWith("WHERE id = 1 AND age = 99;"))

        rollback.statements.forEach { assertEquals(1, execute(it)) }
        conn.createStatement().use { st ->
            st.executeQuery("SELECT age FROM rt_upd ORDER BY id").use { rs ->
                rs.next(); assertEquals(20, rs.getInt(1))
                rs.next(); assertEquals(30, rs.getInt(1))
            }
        }

        // 乐观锁：还原后再次执行同一回滚 → 0 行命中（状态不再等于写入后状态）
        rollback.statements.forEach { assertEquals(0, execute(it)) }
    }

    @Test
    fun `INSERT正向执行后粘贴快照生成DELETE与P1一致`() {
        conn.createStatement().use { it.execute("CREATE TABLE rt_ins (id INT PRIMARY KEY, name VARCHAR(64))") }
        execute("INSERT INTO rt_ins (id, name) VALUES (1, 'Tom')")

        val tsv = snapshotAsTsv("SELECT id, name FROM rt_ins")
        val parsed = RollbackSqlGenerator.parseSnapshotTsv(tsv)!!
        val rollback = RollbackSqlGenerator.generateFromSnapshotRows(
            "rt_ins", parsed.first, parsed.second,
            mode = RollbackSqlGenerator.SnapshotMode.REVERT_INSERT,
            primaryKeyColumns = listOf("id"),
        )
        assertEquals(RollbackSqlGenerator.Rating.COMPLETE, rollback.rating)
        rollback.statements.forEach { assertEquals(1, execute(it)) }
        assertEquals(0, queryCount("rt_ins"))
    }
}
