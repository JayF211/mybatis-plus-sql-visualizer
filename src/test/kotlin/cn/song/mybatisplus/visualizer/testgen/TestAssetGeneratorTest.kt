package cn.song.mybatisplus.visualizer.testgen

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * M37.4 测试资产生成器单元测试（纯函数快照式断言）。
 */
class TestAssetGeneratorTest {

    private val input = TestAssetGenerator.Input(
        sql = "SELECT * FROM users WHERE id > 1",
        dialectName = "MySQL",
        tableName = "users",
        anchor = "UserController.java:87",
    )

    @Test
    fun `SQL 脚本含头注释与分号结尾目标语句`() {
        val asset = TestAssetGenerator.sqlScript(input, "20260902-120000")
        assertTrue(asset.fileName.startsWith("users-") && asset.fileName.endsWith(".sql"))
        assertTrue(asset.content.contains("-- 来源: UserController.java:87 | 方言: MySQL"))
        assertTrue(asset.content.contains("SELECT * FROM users WHERE id > 1;"))
        assertTrue(asset.content.contains("SELECT COUNT(*) AS matched FROM users;"))
        assertTrue(asset.content.contains("TODO"))
    }

    @Test
    fun `SQL 脚本无表名时断言为 TODO 注释`() {
        val asset = TestAssetGenerator.sqlScript(input.copy(tableName = null), "20260902-120000")
        assertTrue(asset.fileName.startsWith("generated-"))
        assertTrue(asset.content.contains("-- TODO: 补充前置数据与断言查询"))
        assertFalse(asset.content.contains("COUNT(*)"))
    }

    @Test
    fun `SQL 脚本多语句保留中间分号`() {
        val multi = "INSERT INTO t VALUES (1);INSERT INTO t VALUES (2)"
        val asset = TestAssetGenerator.sqlScript(input.copy(sql = multi), "x")
        assertTrue(asset.content.contains("INSERT INTO t VALUES (1);"))
        assertTrue(asset.content.contains("INSERT INTO t VALUES (2);"))
    }

    @Test
    fun `JUnit 骨架类名方法名由表名推导`() {
        val asset = TestAssetGenerator.junit(input, "20260902-120000")
        assertEquals("UsersGeneratedTest.java", asset.fileName)
        assertTrue(asset.content.contains("public class UsersGeneratedTest {"))
        assertTrue(asset.content.contains("void generatedUsers() {"))
        assertTrue(asset.content.contains("@org.junit.jupiter.api.Test"))
    }

    @Test
    fun `JUnit SQL 嵌入转义引号与换行`() {
        val tricky = "SELECT * FROM t WHERE name = \"a\"\n  AND b > 1"
        val asset = TestAssetGenerator.junit(input.copy(sql = tricky), "x")
        assertTrue(asset.content.contains("String sql = \"SELECT * FROM t WHERE name = \\\"a\\\"\\n  AND b > 1;\";"))
        assertFalse(asset.content.contains(tricky)) // 原始换行/引号不得原样嵌入
    }

    @Test
    fun `非法表名字符清洗为下划线`() {
        val asset = TestAssetGenerator.junit(input.copy(tableName = "ipm.RunPlan Project"), "x")
        // 类名首字母大写(合法 Java 标识符)
        assertEquals("Ipm_RunPlan_ProjectGeneratedTest.java", asset.fileName)
    }

    @Test
    fun `全非法表名回落默认名`() {
        val asset = TestAssetGenerator.sqlScript(input.copy(tableName = "!!!"), "x")
        assertTrue(asset.fileName.startsWith("generated-"))
        val j = TestAssetGenerator.junit(input.copy(tableName = "!!!"), "x")
        assertEquals("SqlVisualizerGeneratedTest.java", j.fileName)
    }
}
