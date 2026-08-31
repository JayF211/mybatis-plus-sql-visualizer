package cn.song.mybatisplus.visualizer.log

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import java.io.File

/**
 * 历史面板两级导航真实场景测试（F-44-3）：
 *
 * 在项目 `.idea/sql-visualizer/sql-history/`（F-44-4 新目录名）写入真实格式历史文件，
 * 验证用户操作链路：打开面板 → 一级文件列表（文件名/大小/条数）→ 双击文件 → 二级 SQL 列表
 * → 搜索过滤（一级按文件名、二级按 SQL）→ 返回按钮回一级 → 刷新保持二级视图。
 */
class HistorySqlLogPanelNavigationTest : BasePlatformTestCase() {

    private val historyDir: File
        get() = File(project.basePath!!, ".idea${File.separator}${SqlLogAutoSaver.DATA_DIR_NAME}${File.separator}sql-history")

    /**
     * 测试类内方法共享同一临时项目目录且方法间残留不清（JUnit3 执行顺序非声明序）——
     * 每个测试开头清理历史目录与旧目录，保证自包含、与执行顺序无关。
     */
    override fun setUp() {
        super.setUp()
        cleanHistoryDir()
        // 清理旧目录残留（test旧目录数据不读取 可能先执行）
        val oldDir = File(project.basePath!!, ".idea${File.separator}mybatis-plus-sql-visualizer${File.separator}sql-history")
        oldDir.deleteRecursively()
    }

    override fun tearDown() {
        super.tearDown()
    }

    private fun cleanHistoryDir() {
        val dir = historyDir
        if (dir.isDirectory) {
            dir.listFiles()?.forEach { it.delete() }
        }
    }

    private fun writeHistoryFile(name: String, content: String): File {
        val dir = historyDir
        dir.mkdirs()
        val f = File(dir, name)
        f.writeText(content)
        return f
    }

    private fun f44FileContent(entries: List<Triple<String, String, Long?>>): String =
        entries.joinToString("\n\n") { (sql, source, duration) ->
            val time = duration?.let { "${it}ms" } ?: "—"
            "-- source: $source | time: $time | 2026-08-31 17:30:00.000 --\n$sql"
        } + "\n\n"

    fun test一级展示文件列表_文件名大小条数正确() {
        writeHistoryFile(
            "sql-history-2026-08-31.sql",
            f44FileContent(
                listOf(
                    Triple("SELECT * FROM user WHERE id = 1", "UserMapper.java:10", 3L),
                    Triple("SELECT * FROM user WHERE id = 2", "UserMapper.java:11", 3L),
                    Triple("UPDATE orders SET status = 'PAID' WHERE id = 99", "OrderMapper.java:5", 8L),
                ),
            ),
        )
        writeHistoryFile(
            "sql-history-2026-08-30.sql",
            f44FileContent(
                listOf(
                    Triple("SELECT * FROM user WHERE id = 100", "UserMapper.java:20", null),
                    Triple("DELETE FROM operation_log WHERE id = 7", "LogMapper.java:3", 1L),
                ),
            ),
        )

        val panel = HistorySqlLogPanel(project)
        try {
            // 默认一级：文件列表
            assertTrue("默认应处于一级文件列表视图", panel.isShowingFiles)
            assertEquals("应展示 2 个文件", 2, panel.fileListEntries.size)

            val today = panel.fileListEntries.first { it.displayName == "sql-history-2026-08-31.sql" }
            assertEquals("今天文件 3 条 SQL", 3, today.sqlCount)
            assertTrue("文件大小 > 0", today.sizeBytes > 0)
            assertEquals("displayName 即文件名", "sql-history-2026-08-31.sql", today.displayName)

            val yesterday = panel.fileListEntries.first { it.displayName == "sql-history-2026-08-30.sql" }
            assertEquals("昨天文件 2 条 SQL", 2, yesterday.sqlCount)
        } finally {
            panel.dispose()
        }
    }

    fun test双击文件进入二级_SQL逐条展示_含耗时源码() {
        writeHistoryFile(
            "sql-history-2026-08-31.sql",
            f44FileContent(
                listOf(
                    Triple("SELECT * FROM user WHERE id = 1", "UserMapper.java:10", 3L),
                    Triple("SELECT * FROM user WHERE id = 2", "UserMapper.java:11", null),
                ),
            ),
        )

        val panel = HistorySqlLogPanel(project)
        try {
            val fileEntry = panel.fileListEntries.first()
            panel.openFileForTest(fileEntry) // 等价双击文件

            assertFalse("双击文件后应进入二级 SQL 视图", panel.isShowingFiles)
            assertEquals("二级展示 2 条 SQL", 2, panel.sqlListEntries.size)
            assertEquals("SQL 文本还原", "SELECT * FROM user WHERE id = 1", panel.sqlListEntries[0].restoredSql)
            assertEquals("源码位置保留", "UserMapper.java", panel.sqlListEntries[0].sourceFile)
            assertEquals("耗时保留", 3L, panel.sqlListEntries[0].executionTimeMs)
            assertEquals("无耗时条目 null", null, panel.sqlListEntries[1].executionTimeMs)
        } finally {
            panel.dispose()
        }
    }

    fun test二级搜索过滤SQL内容_返回回一级() {
        writeHistoryFile(
            "sql-history-2026-08-31.sql",
            f44FileContent(
                listOf(
                    Triple("SELECT * FROM user WHERE id = 1", "UserMapper.java:10", 3L),
                    Triple("UPDATE orders SET status = 'PAID' WHERE id = 99", "OrderMapper.java:5", 8L),
                ),
            ),
        )

        val panel = HistorySqlLogPanel(project)
        try {
            panel.openFileForTest(panel.fileListEntries.first())
            assertEquals("未搜索时 2 条", 2, panel.sqlListEntries.size)

            // 二级搜索：按 SQL 内容过滤
            panel.setSearchForTest("user")
            assertEquals("搜 'user' 只剩 SELECT user 那条", 1, panel.sqlListEntries.size)
            assertTrue(panel.sqlListEntries[0].restoredSql.contains("user"))

            panel.setSearchForTest("orders")
            assertEquals("搜 'orders' 只剩 UPDATE orders", 1, panel.sqlListEntries.size)

            panel.setSearchForTest("不存在的内容")
            assertEquals("无匹配时为空", 0, panel.sqlListEntries.size)

            // 清空搜索恢复全部
            panel.setSearchForTest("")
            assertEquals("清空后恢复 2 条", 2, panel.sqlListEntries.size)

            // 返回按钮 → 一级
            panel.backToFilesForTest()
            assertTrue("返回后应回一级", panel.isShowingFiles)
            assertEquals("一级仍展示 1 个文件", 1, panel.fileListEntries.size)
        } finally {
            panel.dispose()
        }
    }

    fun test一级搜索过滤文件名() {
        writeHistoryFile("sql-history-2026-08-31.sql", f44FileContent(listOf(Triple("SELECT 1", "A.java:1", 1L))))
        writeHistoryFile("sql-history-2026-08-30.sql", f44FileContent(listOf(Triple("SELECT 2", "A.java:2", 1L))))

        val panel = HistorySqlLogPanel(project)
        try {
            assertEquals("默认 2 个文件", 2, panel.fileListEntries.size)

            panel.setSearchForTest("2026-08-31")
            assertEquals("按日期过滤剩 1 个", 1, panel.fileListEntries.size)
            assertEquals("sql-history-2026-08-31.sql", panel.fileListEntries[0].displayName)

            panel.setSearchForTest("2026-08-30")
            assertEquals("按另一日期过滤剩 1 个", 1, panel.fileListEntries.size)

            panel.setSearchForTest("")
            assertEquals("清空恢复 2 个", 2, panel.fileListEntries.size)
        } finally {
            panel.dispose()
        }
    }

    fun test刷新保持二级视图_且文件删除后回一级() {
        val f = writeHistoryFile(
            "sql-history-2026-08-31.sql",
            f44FileContent(
                listOf(
                    Triple("SELECT 1", "A.java:1", 1L),
                    Triple("SELECT 2", "A.java:2", 1L),
                    Triple("SELECT 3", "A.java:3", 1L),
                ),
            ),
        )

        val panel = HistorySqlLogPanel(project)
        try {
            panel.openFileForTest(panel.fileListEntries.first())
            assertEquals("二级 3 条", 3, panel.sqlListEntries.size)

            // 文件仍存在 → 刷新保持二级并重载
            panel.refresh()
            assertFalse("文件仍存在时刷新保持二级", panel.isShowingFiles)
            assertEquals("重载后仍 3 条", 3, panel.sqlListEntries.size)

            // 文件被删除（模拟外部清理）→ 刷新回一级
            assertTrue(f.delete())
            panel.refresh()
            assertTrue("文件删除后刷新回一级", panel.isShowingFiles)
            assertEquals("一级列表为空", 0, panel.fileListEntries.size)
        } finally {
            panel.dispose()
        }
    }

    fun test空历史目录_一级显示空列表() {
        // 不写任何文件（setUp 已清理；F-44-4 新目录名，旧目录数据不读取）
        val panel = HistorySqlLogPanel(project)
        try {
            assertTrue(panel.isShowingFiles)
            assertEquals(0, panel.fileListEntries.size)
        } finally {
            panel.dispose()
        }
    }

    fun test旧目录数据不读取_新目录名生效() {
        // 模拟升级前旧目录（mybatis-plus-sql-visualizer）里遗留数据——F-44-4 后不应展示
        val oldDir = File(project.basePath!!, ".idea${File.separator}mybatis-plus-sql-visualizer${File.separator}sql-history")
        oldDir.mkdirs()
        File(oldDir, "legacy.sql").writeText(
            "-- source: Legacy.java:1 | time: 5ms | 2026-08-31 17:00:00.000 --\nSELECT legacy\n\n",
        )

        val panel = HistorySqlLogPanel(project)
        try {
            assertEquals("旧目录数据不应读取（不考虑向前兼容）", 0, panel.fileListEntries.size)
        } finally {
            panel.dispose()
        }
    }
}
