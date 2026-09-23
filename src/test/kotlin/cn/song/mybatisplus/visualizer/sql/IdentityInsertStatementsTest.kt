package cn.song.mybatisplus.visualizer.sql

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * O-64 / T-RollbackIdentityParse：`SET IDENTITY_INSERT` 识别与剥离的纯逻辑测试。
 *
 * 形态基准＝[identityInsertWrapper] 自产产物（SQL Server `[user]` 形态 + DM8 形态，
 * 两方言共用一处包装函数、产物逐字同构，仅表引用引用符随方言）。
 */
class IdentityInsertStatementsTest {

    /** O-52/O-59 自产产物形态（P19 实录文本）。 */
    private fun sqlServerProduct(table: String = "user") =
        "SET IDENTITY_INSERT [$table] ON;\n" +
            "INSERT INTO [$table] ([id], [name]) VALUES (1, 'Tom');\n" +
            "SET IDENTITY_INSERT [$table] OFF;"

    // ======================== containsStatement（D2 判据） ========================

    @Test
    fun `identity产物_识别为真`() {
        assertTrue(IdentityInsertStatements.containsStatement(sqlServerProduct()))
    }

    @Test
    fun `大小写混合与裸表名双引号形态_识别为真`() {
        assertTrue(IdentityInsertStatements.containsStatement("set identity_insert dbo.T_USER on;\nINSERT INTO T_USER VALUES (1);\nset identity_insert dbo.T_USER off;"))
        assertTrue(IdentityInsertStatements.containsStatement("SET IDENTITY_INSERT \"user\" ON;"))
        assertTrue(IdentityInsertStatements.containsStatement("SET IDENTITY_INSERT `user` ON;"))
    }

    @Test
    fun `普通多语句与SET变量_不识别`() {
        assertFalse(IdentityInsertStatements.containsStatement("DELETE FROM t WHERE id = 1; DELETE FROM u WHERE id = 2"))
        assertFalse(IdentityInsertStatements.containsStatement("SET @x = 1;"))
        // 词面相近但不是 IDENTITY_INSERT 语句
        assertFalse(IdentityInsertStatements.containsStatement("SELECT 'SET IDENTITY_INSERT x ON' AS note"))
        assertFalse(IdentityInsertStatements.containsStatement(""))
    }

    // ======================== stripForParse（D1 剥离） ========================

    @Test
    fun `产物剥离后只剩真实语句_分号边界保留`() {
        val product = sqlServerProduct()
        val stripped = IdentityInsertStatements.stripForParse(product)
        assertFalse("ON 开关应被剥离", stripped.contains("IDENTITY_INSERT"))
        assertTrue(
            "INSERT 本体必须原样保留",
            stripped.contains("INSERT INTO [user] ([id], [name]) VALUES (1, 'Tom');"),
        )
        // 首语句剥离不吞分号；尾语句剥离后文本仍以 INSERT 的分号收尾
        assertTrue(stripped.trim().startsWith("INSERT INTO"))
        assertTrue(stripped.trim().endsWith(";"))
    }

    @Test
    fun `无identity痕迹_返回原引用（单槽快路径前提）`() {
        val sql = "DELETE FROM t WHERE id = 1; UPDATE u SET a = 1 WHERE id = 2"
        assertSame("无痕迹必须返回同一实例（UnboundedWriteDetector 单槽 === 快判依赖）", sql, IdentityInsertStatements.stripForParse(sql))
    }

    @Test
    fun `有痕迹但形态不完整_不误剥`() {
        val sql = "SET IDENTITY_INSERT [user] PENDING;\nINSERT INTO [user] VALUES (1);"
        // 有痕迹文本走慢路径（Regex.replace 零匹配也产生新实例），契约只保**内容**逐字节不变
        assertEquals(sql, IdentityInsertStatements.stripForParse(sql))
    }

    @Test
    fun `同行分号粘连_边界分号保留`() {
        val sql = "UPDATE t SET a = 1 WHERE id = 1;SET IDENTITY_INSERT [t] ON;INSERT INTO [t] VALUES (1);SET IDENTITY_INSERT [t] OFF;DELETE FROM t WHERE id = 9"
        val stripped = IdentityInsertStatements.stripForParse(sql)
        assertFalse(stripped.contains("IDENTITY_INSERT"))
        assertTrue("前语句分号不得被吞", stripped.contains("WHERE id = 1;"))
        assertTrue("后语句不得粘连受损", stripped.trimEnd().endsWith("DELETE FROM t WHERE id = 9"))
    }

    @Test
    fun `CRLF与无尾分号形态`() {
        val sql = "SET IDENTITY_INSERT [user] ON\r\nINSERT INTO [user] VALUES (1);\r\nSET IDENTITY_INSERT [user] OFF"
        val stripped = IdentityInsertStatements.stripForParse(sql)
        assertFalse(stripped.contains("IDENTITY_INSERT"))
        assertTrue(stripped.contains("INSERT INTO [user] VALUES (1);"))
    }

    @Test
    fun `剥离不改变其他语句的检查语义`() {
        // identity 包装里裹一条无 WHERE 的 UPDATE：剥离开关后真写语句的发现必须仍在（不因剥离漏检）
        val sql = "SET IDENTITY_INSERT [t] ON;\nUPDATE t SET a = 1;\nSET IDENTITY_INSERT [t] OFF;"
        val stripped = IdentityInsertStatements.stripForParse(sql)
        assertTrue(stripped.contains("UPDATE t SET a = 1;"))
    }
}
