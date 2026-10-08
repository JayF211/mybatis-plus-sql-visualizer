package cn.song.mybatisplus.visualizer.log

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import java.io.File
import javax.swing.event.ListDataEvent
import javax.swing.event.ListDataListener

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
     * T-SqlHistoryFull → T-SqlLogPerf（20261006-1020）：全量历史 → 单文件条目成百上千，
     * 二级列表改**滚动追加**填充（首屏 30 + 用户滚动逼近底部时每 30 续写——不再 invokeLater 链式
     * 全量排空）。断言追加后最终条目完整、顺序保持、且不产生幽灵/重复（gen 代际守卫）。
     * headless 无真实滚动事件，经 [HistorySqlLogPanel.appendPendingForTest] 测试缝驱动
     * （与 adjustment 监听同一实现路径）。
     */
    fun test二级大文件_滚动追加最终完整且顺序保持() {
        // 100 条（> 首屏 30），每条独立 SQL 避免去重干扰
        val n = 100
        val entries = (1..n).map { Triple("SELECT * FROM big_table WHERE id = $it", "BigMapper.java:$it", it.toLong()) }
        writeHistoryFile("sql-history-2026-09-17.sql", f44FileContent(entries))

        val panel = HistorySqlLogPanel(project); panel.refreshForTest()
        try {
            panel.openFileForTest(panel.fileListEntries.first())
            // 驱动滚动追加直到队列排空（轮数上限兜底，防死循环）
            var calls = 0
            while (panel.sqlListEntries.size < n && calls++ < 100) {
                panel.appendPendingForTest()
            }
            assertEquals("滚动追加走完后最终条目完整", n, panel.sqlListEntries.size)
            assertEquals("首条顺序保持", "SELECT * FROM big_table WHERE id = 1", panel.sqlListEntries[0].restoredSql)
            assertEquals("末条顺序保持", "SELECT * FROM big_table WHERE id = 100", panel.sqlListEntries[n - 1].restoredSql)
            // 无重复（同一性——分批追加不得重复填同一实例）
            assertEquals("无重复条目", n, panel.sqlListEntries.distinct().size)
        } finally {
            panel.dispose()
        }
    }

    /*
     * 验收项「上千条历史文件双击打开，首屏快显、不冻结」的自动化代理（T-SqlHistoryFull §3.2，
     * T-SqlLogPerf 换滚动追加驱动后语义重钉）。
     *
     * 「不冻结」为什么用「首屏 30 + 每批 30」代理，而不断言耗时：
     *  真机冻结的根因是**单次 EDT 占用时长**——一次性写满上千条会产生上千次模型变更事件 +
     *  全量布局失效，EDT 被独占整个填充过程。耗时的绝对值随机器负载剧烈波动（CI/本机并行构建
     *  下同一代码可差数倍），毫秒阈值断言必然 flaky；而「单次 EDT 占用有上界」是同一根因的
     *  确定性表达，可无时钟断言：
     *   ① 首屏只写 REFRESH_FIRST_BATCH_SIZE 条就返回（打开调用的同一 EDT 回合内即可绘制），
     *      余下条目进待写队列，等用户滚动才进模型 —— 先出一屏，不是一次性填完；
     *   ② 每驱动一次滚动追加，模型增量恰 ≤ REFRESH_CHUNK_SIZE —— 单次 EDT 占用钉在「30 条」量级；
     *   ③ 追加期间条数单调不减、模型不被中途清空 —— 渲染器任意时刻都能画出已到的部分。
     */
    fun test二级大文件_首屏先出再滚动追加_非一次性填满() {
        val n = 1000
        val entries = (1..n).map { Triple("SELECT * FROM huge_history WHERE id = $it", "HugeMapper.java:$it", it.toLong()) }
        writeHistoryFile("sql-history-2026-09-17.sql", f44FileContent(entries))

        val panel = HistorySqlLogPanel(project); panel.refreshForTest()
        try {
            // 记录每次模型变更后的条数 = 追加的「轮次轨迹」（模型变更事件即一次渲染失效）
            val model = panel.sqlListForTest.model
            val sizes = mutableListOf<Int>()
            model.addListDataListener(object : ListDataListener {
                override fun intervalAdded(e: ListDataEvent) { sizes.add(model.size) }
                override fun intervalRemoved(e: ListDataEvent) { sizes.add(model.size) }
                override fun contentsChanged(e: ListDataEvent) { sizes.add(model.size) }
            })

            panel.openFileForTest(panel.fileListEntries.first())

            // ① 首屏先出：open 返回时（同一 EDT 回合）模型只有首屏批，而不是全部 1000 条
            assertEquals(
                "首屏应只写 REFRESH_FIRST_BATCH_SIZE=$REFRESH_FIRST_BATCH_SIZE 条后立即返回（实际 ${panel.sqlListEntries.size}，" +
                    "模型变更轨迹=$sizes）——等于总数 $n 即回退成一次性填满（EDT 长冻结）",
                REFRESH_FIRST_BATCH_SIZE,
                panel.sqlListEntries.size,
            )
            assertEquals(
                "首屏必须是文件前 $REFRESH_FIRST_BATCH_SIZE 条且顺序保持（首屏内容立即可读）",
                entries.take(REFRESH_FIRST_BATCH_SIZE).map { it.first },
                panel.sqlListEntries.map { it.restoredSql },
            )

            // ② 滚动追加：驱动测试缝直到待写队列排空（headless 无真实滚动事件）
            var calls = 0
            while (calls++ < 100 && panel.sqlListEntries.size < n) {
                panel.appendPendingForTest()
                assertTrue("追加后条数不得超总条数（重复追加）", panel.sqlListEntries.size <= n)
            }
            assertEquals("滚动追加走完后应等于总条数", n, panel.sqlListEntries.size)
            assertEquals("末条顺序保持", "SELECT * FROM huge_history WHERE id = $n", panel.sqlListEntries[n - 1].restoredSql)
            assertEquals("无重复条目（代际守卫防幽灵追加）", n, panel.sqlListEntries.distinct().size)

            // ③ 每批规模有界 + 批次数（用常量，不用魔法数字）
            val increments = sizes.zipWithNext { a, b -> b - a }
            val maxIncrement = increments.maxOrNull() ?: 0
            assertTrue(
                "任何一轮模型变更的增量不得超过 REFRESH_CHUNK_SIZE=$REFRESH_CHUNK_SIZE" +
                    "（实际最大 $maxIncrement，轨迹=$sizes）——超界即单次 EDT 占用失控",
                maxIncrement <= REFRESH_CHUNK_SIZE,
            )
            val minRounds = (n - REFRESH_FIRST_BATCH_SIZE + REFRESH_CHUNK_SIZE - 1) / REFRESH_CHUNK_SIZE
            assertTrue(
                "剩余 ${n - REFRESH_FIRST_BATCH_SIZE} 条须分批到达：模型变更轮数 ${sizes.size} 不得少于 $minRounds" +
                    "（一次填完即红，轨迹=$sizes）",
                sizes.size >= minRounds,
            )
            assertTrue("分批轨迹单调不减（轨迹=$sizes）", sizes.zipWithNext().all { (a, b) -> b >= a })
            assertEquals("末次模型变更即全量（轨迹=$sizes）", n, sizes.last())
            assertTrue("首屏之后模型不被中途清空——追加期间渲染器随时可读（轨迹=$sizes）", sizes.drop(1).all { it > 0 })
        } finally {
            panel.dispose()
        }
    }

    /*
     * T-SqlHistoryFull 代际守卫（与首屏/滚动追加同源）：待写队列未排空时触发新一轮填充
     * （此处=二级搜索），旧队列必须立即作废——否则旧文件的剩余条目会继续追加进新结果集（幽灵条目）。
     * 本用例在首屏 30 条已入、970 条仍在队列的中间态改搜索词。
     */
    fun test二级大文件_滚动追加中途重载_旧队列作废无幽灵条目() {
        val n = 1000
        val entries = (1..n).map { Triple("SELECT * FROM huge_history WHERE id = $it", "HugeMapper.java:$it", it.toLong()) }
        writeHistoryFile("sql-history-2026-09-17.sql", f44FileContent(entries))

        val panel = HistorySqlLogPanel(project); panel.refreshForTest()
        try {
            panel.openFileForTest(panel.fileListEntries.first())
            assertEquals("中间态：首屏批已入、剩余仍在待写队列", REFRESH_FIRST_BATCH_SIZE, panel.sqlListEntries.size)

            // 新一轮填充（搜索结果集 1 条）启动 → 旧队列代际失效
            panel.setSearchForTest("id = 1000")
            assertEquals("新结果集应立即生效", 1, panel.sqlListEntries.size)

            // 驱动滚动追加：不得追加任何旧文件条目
            repeat(5) { panel.appendPendingForTest() }
            assertEquals("旧待写队列须已作废，不得追加幽灵条目", 1, panel.sqlListEntries.size)
            assertEquals(
                "命中的应是 id = 1000 那条（而非旧队列残留）",
                "SELECT * FROM huge_history WHERE id = 1000",
                panel.sqlListEntries.single().restoredSql,
            )
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

    /* O-90：同模板计数与聚焦流（右键动作/chip 共用 applyTemplateFocus 入口） */

    fun testO90同模板聚焦_计数聚合与聚焦过滤恢复() {
        writeHistoryFile(
            "sql-history-2026-09-01.sql",
            f44FileContent(
                listOf(
                    Triple("SELECT * FROM t_user WHERE id = 1", "UserMapper.java:10", 3L),
                    Triple("SELECT * FROM t_user WHERE id = 2", "UserMapper.java:11", 3L),
                    Triple("SELECT * FROM t_user WHERE id = 3", "UserMapper.java:12", 3L),
                    Triple("SELECT * FROM t_order WHERE id = 1", "OrderMapper.java:5", 8L),
                ),
            ),
        )
        val panel = HistorySqlLogPanel(project); panel.refreshForTest()
        try {
            panel.openFileForTest(panel.fileListEntries.single())
            assertEquals("聚焦前全量 4 条", 4, panel.sqlListEntries.size)

            // 计数聚合：3 条同模板同键 + 1 条异形 = 2 组
            val counts = panel.templateCountsForTest
            assertEquals("聚合键组数 = 2", 2, counts.size)
            val hotKey = counts.entries.first { it.value == 3 }.key
            assertEquals(3, counts[hotKey])

            // 聚焦：只显同模板 3 条（参数值不同的同形状全保留），异形被隔离
            panel.applyTemplateFocus(hotKey)
            assertEquals("聚焦后仅同模板 3 条", 3, panel.sqlListEntries.size)
            assertTrue("全部命中聚焦键", panel.sqlListEntries.all { it.structureKey == hotKey })
            assertEquals("聚焦键在位", hotKey, panel.templateFocusKeyForTest)

            // 清除恢复全量
            panel.applyTemplateFocus(null)
            assertEquals("清除后恢复全量 4 条", 4, panel.sqlListEntries.size)
            assertNull("聚焦键已清", panel.templateFocusKeyForTest)
        } finally {
            panel.dispose()
        }
    }

    /**
     * T-HistoryTabAlways：设置页「保存目录」为自定义路径时，历史页读的就是该目录。
     * 修复前读端只认默认 `.idea/sql-visualizer/sql-history`——自动保存写进自定义目录的文件
     * 在历史页里根本看不到（写端认自定义目录、读端不认）。
     */
    fun test自定义保存目录_历史页读取该目录() {
        val settings = cn.song.mybatisplus.visualizer.config.PluginSettings.getInstance()
        val savedPath = settings.state.logAutoSavePath
        val customDir = File(project.basePath!!, "custom-history-dir").apply {
            deleteRecursively()
            mkdirs()
        }
        try {
            settings.state.logAutoSavePath = customDir.absolutePath
            File(customDir, "sql-history-2026-09-20.sql").writeText(
                f44FileContent(listOf(Triple("SELECT custom_dir", "A.java:1", 1L))),
            )
            val panel = HistorySqlLogPanel(project); panel.refreshForTest()
            try {
                assertEquals(
                    "自定义保存目录下的历史文件应在历史页可见",
                    listOf("sql-history-2026-09-20.sql"),
                    panel.fileListEntries.map { it.displayName },
                )
            } finally {
                panel.dispose()
            }
        } finally {
            settings.state.logAutoSavePath = savedPath
            customDir.deleteRecursively()
        }
    }

    /** T-HistoryTabAlways：保存目录被改过 → 设置广播需触发重扫；未变 → 只重绘不重扫。 */
    fun test保存目录变更判据_改过才重扫() {
        val settings = cn.song.mybatisplus.visualizer.config.PluginSettings.getInstance()
        val savedPath = settings.state.logAutoSavePath
        val customDir = File(project.basePath!!, "custom-history-dir-2").apply {
            deleteRecursively()
            mkdirs()
        }
        val panel = HistorySqlLogPanel(project); panel.refreshForTest()
        try {
            assertFalse("目录未变时不应重扫", panel.historyDirChangedSinceLastScan())
            settings.state.logAutoSavePath = customDir.absolutePath
            assertTrue("保存目录改过后应判定需重扫", panel.historyDirChangedSinceLastScan())
            panel.refreshForTest()
            assertFalse("重扫后判据复位", panel.historyDirChangedSinceLastScan())
        } finally {
            settings.state.logAutoSavePath = savedPath
            panel.dispose()
            customDir.deleteRecursively()
        }
    }

    /* 20261008 dev-bak 验收（缺陷 #5）：历史页二级列表「同结构 N 条」徽章不渲染——真机面板级计数
     * 正确（右键「只看同结构」可用、chip 显示「同结构 12 条」），但徽章静默消失。徽章渲染数据源是
     * HistoryCellRenderer.templateCounts（applySqlSearch 负责与面板计数同步），此守卫钉两层：
     * 渲染器快照与面板一致 + 达阈值条目确实产出徽章片段。 */
    fun testO90同模板徽章_渲染器计数快照与片段在场() {
        writeHistoryFile(
            "sql-history-2026-09-02.sql",
            f44FileContent(
                listOf(
                    Triple("SELECT * FROM t_user WHERE id = 1", "UserMapper.java:10", 3L),
                    Triple("SELECT * FROM t_user WHERE id = 2", "UserMapper.java:11", 3L),
                    Triple("SELECT * FROM t_user WHERE id = 3", "UserMapper.java:12", 3L),
                ),
            ),
        )
        val panel = HistorySqlLogPanel(project); panel.refreshForTest()
        try {
            panel.openFileForTest(panel.fileListEntries.single())
            assertEquals("前置：3 条同模板", 1, panel.templateCountsForTest.size)
            val renderer = panel.sqlCellRendererForTest
            assertEquals(
                "渲染器计数快照应与面板级计数一致（徽章渲染数据源）",
                panel.templateCountsForTest,
                renderer.templateCounts,
            )
            val entry = panel.sqlListEntries.first()
            val fragment = formatTemplateCountFragment(entry, renderer.templateCounts, isSelected = false)
            assertNotNull("达阈值条目应产出徽章片段", fragment)
            assertTrue("片段应含计数", fragment!!.first.contains("3"))
        } finally {
            panel.dispose()
        }
    }
}
