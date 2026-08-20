package cn.song.mybatisplus.visualizer.database

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import java.io.File

/**
 * DatabaseConsoleLauncher 文件操作测试（Wave2-P2）：
 *  - createTempFile() 文件创建成功、文件名以 .sql 结尾
 *  - writeSqlToFile() 写入内容正确读取
 *
 * 纯文件 I/O 测试，不需要 Database 插件（测试沙盒无 database 插件 jar）。
 */
class DatabaseConsoleLauncherTest : BasePlatformTestCase() {

    fun testCreateTempFile_fileExists() {
        val file = DatabaseConsoleLauncher.createTempFile()
        assertTrue("临时文件应存在", file.exists())
        assertTrue("临时文件应以 .sql 结尾", file.name.endsWith(".sql"))
        assertTrue("文件名应以 mpsql-exec- 开头", file.name.startsWith("mpsql-exec-"))
        // 清理
        file.delete()
    }

    fun testCreateTempFile_fileIsEmpty() {
        val file = DatabaseConsoleLauncher.createTempFile()
        assertEquals("新创建的临时文件应为空", 0L, file.length())
        file.delete()
    }

    fun testWriteSqlToFile_contentRoundTrip() {
        val file = DatabaseConsoleLauncher.createTempFile()
        try {
            val sql = "SELECT id, name FROM t_user WHERE id = 1"
            DatabaseConsoleLauncher.writeSqlToFile(file, sql)
            val read = file.readText()
            assertEquals("写入后读取内容应一致", sql, read)
        } finally {
            file.delete()
        }
    }

    fun testWriteSqlToFile_overwrite() {
        val file = DatabaseConsoleLauncher.createTempFile()
        try {
            DatabaseConsoleLauncher.writeSqlToFile(file, "SELECT 1")
            DatabaseConsoleLauncher.writeSqlToFile(file, "SELECT 2 FROM dual")
            val read = file.readText()
            assertEquals("覆盖写入后应读取最新内容", "SELECT 2 FROM dual", read)
        } finally {
            file.delete()
        }
    }

    fun testWriteSqlToFile_multilineSql() {
        val file = DatabaseConsoleLauncher.createTempFile()
        try {
            val sql = """
                SELECT id, name
                FROM t_user
                WHERE status = 1
                ORDER BY id
            """.trimIndent()
            DatabaseConsoleLauncher.writeSqlToFile(file, sql)
            val read = file.readText()
            assertEquals("多行 SQL 写入后读取应一致", sql, read)
        } finally {
            file.delete()
        }
    }
}
