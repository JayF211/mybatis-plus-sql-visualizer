package cn.song.mybatisplus.visualizer.log

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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

        val panel = HistorySqlLogPanel(project); panel.refreshForTest()
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

    /*
     * T-SqlHistoryFull：全量历史 → 单文件条目成百上千，二级列表分批填充（首屏 30 + 每 30 追加）。
     * 断言分批后最终条目完整、顺序保持、且不产生幽灵/重复（gen 代际守卫）。
     */
    fun test二级大文件_分批填充最终完整且顺序保持() {
        // 100 条（> 首屏 30），每条独立 SQL 避免去重干扰
        val n = 100
        val entries = (1..n).map { Triple("SELECT * FROM big_table WHERE id = $it", "BigMapper.java:$it", it.toLong()) }
        writeHistoryFile("sql-history-2026-09-17.sql", f44FileContent(entries))

        val panel = HistorySqlLogPanel(project); panel.refreshForTest()
        try {
            panel.openFileForTest(panel.fileListEntries.first())
            // 反复 pump EDT，驱动 invokeLater 分块追加走完（与 SqlLogPanel 验证同法）
            repeat(20) { com.intellij.util.ui.UIUtil.dispatchAllInvocationEvents() }
            assertEquals("分批后最终条目完整", n, panel.sqlListEntries.size)
            assertEquals("首条顺序保持", "SELECT * FROM big_table WHERE id = 1", panel.sqlListEntries[0].restoredSql)
            assertEquals("末条顺序保持", "SELECT * FROM big_table WHERE id = 100", panel.sqlListEntries[n - 1].restoredSql)
            // 无重复（同一性——分批追加不得重复填同一实例）
            assertEquals("无重复条目", n, panel.sqlListEntries.distinct().size)
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

        val panel = HistorySqlLogPanel(project); panel.refreshForTest()
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

        val panel = HistorySqlLogPanel(project); panel.refreshForTest()
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

    fun test二级SQL列表_安装速度搜索() {
        // T-UiNative T-UN-05：历史二级 SQL 列表构造于 CardLayout 隐藏卡，速度搜索须可见后生效。
        // 断言「已安装」用两参 getSupply(comp, true)（checkShowing=false 直读安装的 supply——
        // 单参版含 isPopupActive() 过滤，headless 无活动弹窗恒 null，不适用于安装断言）。
        writeHistoryFile("sql-history-2026-08-31.sql", f44FileContent(listOf(Triple("SELECT 1", "A.java:1", 1L))))

        val panel = HistorySqlLogPanel(project); panel.refreshForTest()
        try {
            panel.openFileForTest(panel.fileListEntries.first())
            assertTrue(
                "二级 SQL 列表应已安装速度搜索（checkShowing=false 可解析到 supply），但未命中",
                com.intellij.ui.speedSearch.SpeedSearchSupply.getSupply(panel.sqlListForTest, true) != null,
            )
        } finally {
            panel.dispose()
        }
    }

    fun test一级搜索过滤文件名() {
        writeHistoryFile("sql-history-2026-08-31.sql", f44FileContent(listOf(Triple("SELECT 1", "A.java:1", 1L))))
        writeHistoryFile("sql-history-2026-08-30.sql", f44FileContent(listOf(Triple("SELECT 2", "A.java:2", 1L))))

        val panel = HistorySqlLogPanel(project); panel.refreshForTest()
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

        val panel = HistorySqlLogPanel(project); panel.refreshForTest()
        try {
            panel.openFileForTest(panel.fileListEntries.first())
            assertEquals("二级 3 条", 3, panel.sqlListEntries.size)

            // 文件仍存在 → 刷新保持二级并重载
            panel.refreshForTest()
            assertFalse("文件仍存在时刷新保持二级", panel.isShowingFiles)
            assertEquals("重载后仍 3 条", 3, panel.sqlListEntries.size)

            // 文件被删除（模拟外部清理）→ 刷新回一级
            assertTrue(f.delete())
            panel.refreshForTest()
            assertTrue("文件删除后刷新回一级", panel.isShowingFiles)
            assertEquals("一级列表为空", 0, panel.fileListEntries.size)
        } finally {
            panel.dispose()
        }
    }

    fun test空历史目录_一级显示空列表() {
        // 不写任何文件（setUp 已清理；F-44-4 新目录名，旧目录数据不读取）
        val panel = HistorySqlLogPanel(project); panel.refreshForTest()
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

        val panel = HistorySqlLogPanel(project); panel.refreshForTest()
        try {
            assertEquals("旧目录数据不应读取（不考虑向前兼容）", 0, panel.fileListEntries.size)
        } finally {
            panel.dispose()
        }
    }

    fun test显示模式变更_行高重设_回同步combo_残留缓存按模式键失效() {
        writeHistoryFile(
            "sql-history-2026-08-31.sql",
            f44FileContent(
                listOf(Triple("SELECT * FROM user WHERE id = 1", "UserMapper.java:10", 3L)),
            ),
        )

        val panel = HistorySqlLogPanel(project); panel.refreshForTest()
        try {
            panel.openFileForTest(panel.fileListEntries.first())
            val entry = panel.sqlListEntries.single()
            // 模拟旧模式残留缓存（键=FORMATTED，与当前模式不同——渲染读取处按键比对自动失效）
            entry.cachedListLineFragments = listOf(emptyList())
            entry.cachedListLineFragmentsMode = SqlListDisplayMode.FORMATTED

            panel.onDisplayModeChanged() // 等价 SETTINGS_CHANGE_TOPIC 广播后的处理

            assertEquals(
                "行高应随模式重设（单行=固定高，多行=-1 可变）",
                cn.song.mybatisplus.visualizer.config.PluginSettings.getInstance().sqlListDisplayMode() == SqlListDisplayMode.SINGLE_LINE,
                panel.sqlListFixedCellHeightForTest != -1,
            )
            // T-MergeFollowup-03：模式切换改走 applySqlSearch 全量重建（repaint 不重算 JList 行高布局缓存）
            assertEquals(
                "模式切换后二级列表条目应经全量重建保持不丢",
                1,
                panel.sqlListEntries.size,
            )
        } finally {
            panel.dispose()
        }
    }

    /**
     * T-MergeFollowup-03（复查二轮）防回归：多次切换显示模式后 BasicListUI 的 cellHeights 布局缓存
     * 必须失效（updateUI 重装 UI）——旧行高框内画新多行内容即真机「文字重叠/间距挤压/字号错乱」。
     * 判据：每次切换后 [javax.swing.JList.getCellBounds] 行高须等于当前模式渲染器 preferred 高
     * （缓存未失效时 getCellBounds 返回旧缓存高度，与多行展开的新渲染高度不一致）。
     */
    fun test多次切换显示模式后行高布局缓存失效() {
        val settings = cn.song.mybatisplus.visualizer.config.PluginSettings.getInstance()
        val savedMode = settings.state.logListSqlDisplayMode
        // 多列 SELECT：FORMATTED 竖排展开后渲染行数远大于单行，行高差可判
        writeHistoryFile(
            "sql-history-2026-09-14.sql",
            f44FileContent(
                listOf(Triple("SELECT id, station_id, share_scheme_id, device_name, station_name FROM car_device_info", "SqlSourceInterceptor.java:621", 3L)),
            ),
        )

        val panel = HistorySqlLogPanel(project); panel.refreshForTest()
        try {
            panel.openFileForTest(panel.fileListEntries.first())
            assertEquals(1, panel.sqlListEntries.size)

            fun assertCellHeightMatchesRenderer(stage: String) {
                @Suppress("UNCHECKED_CAST")
                val renderer = panel.sqlListForTest.cellRenderer as javax.swing.ListCellRenderer<Any?>
                panel.sqlListForTest.getCellBounds(0, 0)!!.let { bounds ->
                    val renderedPreferred = renderer
                        .getListCellRendererComponent(panel.sqlListForTest, panel.sqlListEntries.single(), 0, false, false)
                        .preferredSize.height
                    assertEquals(
                        "$stage：切换后 JList 行高布局缓存应失效（cellBounds 高度 = 当前模式渲染 preferred）",
                        renderedPreferred,
                        bounds.height,
                    )
                }
            }

            listOf(
                SqlListDisplayMode.SINGLE_LINE,
                SqlListDisplayMode.FORMATTED,
                SqlListDisplayMode.KEYWORD_WRAP,
                SqlListDisplayMode.FORMATTED,
                SqlListDisplayMode.SINGLE_LINE,
            ).forEach { mode ->
                settings.state.logListSqlDisplayMode = mode.name
                panel.onDisplayModeChanged() // 等价 SETTINGS_CHANGE_TOPIC 广播后的处理
                assertCellHeightMatchesRenderer("切至 $mode")
            }
        } finally {
            settings.state.logListSqlDisplayMode = savedMode
            panel.dispose()
        }
    }
}
