package cn.song.mybatisplus.visualizer.log

import cn.song.mybatisplus.visualizer.config.PluginSettings
import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import java.awt.BorderLayout
import java.awt.Dimension
import javax.swing.JPanel

/**
 * SQL 日志面板布局 / 兼容性 UI 测试（F-44-5 全插件 UI 测试）：
 *
 * 用户反馈「布局错乱 / 不兼容」——工具栏是主战场：
 *  - 关键组件（SQL类型按钮、去重勾选、帮助动作组；捕捉开关 T-CaptureBack 起在面板工具栏首位）构建即存在；
 *  - 默认可见性正确（排序选择器恒可见、搜索条默认隐藏、无慢查询不显示徽章）；
 *  - **T-UiCoord F2（2026-09-21 用户定案「不要两行」）：工具栏恒为单行**——控制组实现高度恒等于
 *    其单行理想高度（FlowLayout 折行时实现高度会翻倍，这就是判据），且任意宽度下无越界。
 *    旧用例 `test窄侧边栏_WrapLayout_类型筛选按钮不被裁切` 已退役：它自建 holder 手工装配组件、
 *    且只对 holder 调 doLayout（Container.doLayout 不递归 → 读到的子件 bounds 是未布局的陈旧值），
 *    实际断言恒真——守的是假形态（本仓「守卫看不见它要防的站点」同族）。
 */
class SqlLogPanelLayoutTest : BasePlatformTestCase() {

    private lateinit var manager: SqlLogManager

    override fun setUp() {
        super.setUp()
        manager = SqlLogManager(project)
        manager.entries.dedupeEnabled = false
    }

    override fun tearDown() {
        manager.dispose()
        super.tearDown()
    }

    private fun waitForEdt() = com.intellij.util.ui.UIUtil.dispatchAllInvocationEvents()

    fun test工具栏关键组件存在_默认可见性正确() {
        val panel = SqlLogPanel(project, manager)
        try {
            waitForEdt()
            // 关键组件均存在（构建即无 NPE / 缺 i18n）
            assertNotNull("SQL类型按钮应存在", panel.typeFilterButtonForTest)
            assertNotNull("去重勾选框应存在", panel.dedupeCheckBoxForTest)
            assertNotNull("搜索条应存在", panel.searchFieldForTest)
            // T-CaptureBack：捕捉开关回归面板工具栏首位（曾迁标题栏致入口不可见），
            // 工具栏组含 5 个动作；捕捉动作存在于组内且为捕捉态
            assertTrue(
                "工具栏动作组应含 ≥5 个动作（捕捉/清空/设置/搜索/帮助）",
                panel.toolbarGroup.childActionsOrStubs.size >= 5,
            )
            assertTrue(
                "捕捉开关动作应在工具栏组内（回迁面板工具栏）",
                panel.toolbarGroup.childActionsOrStubs.contains(panel.captureToggleAction),
            )

            // 默认可见性：排序方式选择器恒可见（默认时间升序）；搜索条默认隐藏；慢查询徽章隐藏
            assertEquals("排序方式选择器应恒可见", true, panel.sortButtonForTest.isVisible)
            assertEquals("搜索条默认隐藏", false, panel.searchFieldForTest.isVisible)
            assertEquals("默认无慢查询，计数徽章应隐藏/为空", false, panel.slowQueryLabelForTest?.isVisible ?: false)
        } finally {
            panel.dispose()
        }
    }

    fun test注入耗时信息_排序方式选择器保持可见() {
        val panel = SqlLogPanel(project, manager)
        try {
            // p6spy 格式带耗时
            manager.entries.addEntry(
                LogSqlEntry(
                    rawSql = "1723456789012|1500|statement|SELECT * FROM t",
                    restoredSql = "SELECT * FROM t",
                    params = emptyList(),
                    timestamp = 1L,
                    executionTimeMs = 1500L,
                ),
            )
            waitForEdt()
            assertEquals("有耗时信息时排序方式选择器仍可见", true, panel.sortButtonForTest.isVisible)
            assertEquals("默认排序方式为时间升序", SortMode.TIME_ASC, panel.currentSortMode)
        } finally {
            panel.dispose()
        }
    }

    // ------------------------------------------------------------------
    // T-UiCoord F2：工具栏单行契约
    // ------------------------------------------------------------------

    /** 把生产工具栏行放进定宽 holder 按 NORTH 布局（NORTH 用理想高 → 行高即单行高），逐层 doLayout。 */
    private fun layoutRowAt(panel: SqlLogPanel, width: Int): JPanel {
        val row = panel.toolbarRowForTest!!
        val holder = JPanel(BorderLayout()).apply { size = Dimension(width, 400) }
        holder.add(row, BorderLayout.NORTH)
        layoutRecursively(holder)
        waitForEdt()
        return row
    }

    /** Container.doLayout 不递归——必须逐层布局，否则子件 bounds 停在未布局状态（旧用例的假绿根因）。 */
    private fun layoutRecursively(c: java.awt.Container) {
        c.doLayout()
        c.components.filterIsInstance<java.awt.Container>().forEach { layoutRecursively(it) }
    }

    /** 控制组的可见子件垂直中心极差（同父坐标系直接可比）：单行应 ≤ 6px，折行会是 20px 以上。 */
    private fun verticalBand(controls: JPanel): Int {
        val centers = controls.components
            .filter { it.isVisible && it.width > 0 && it.height > 0 }
            .map { it.y + it.height / 2 }
        if (centers.isEmpty()) return 0
        return centers.max() - centers.min()
    }

    /**
     * 控制组内可见子件分出的**行数**（按垂直中心聚类）。
     *
     * 两条必须写明的边界（2026-09-21 反向验证实测）：
     *  1. **不能拿「实现高度 == preferredSize 高度」当折行判据**——`WrapLayout.preferredLayoutSize`
     *     本就按折行后的多行高度上报（它存在的意义），拿它与实现高度对比对 WrapLayout 恒等，是空绿；
     *  2. **单测环境复现不出「旧结构折行」**：headless 下 `ActionToolbar` 不建按钮，
     *     `preferredSize` 为 0×0（真机量测约 133×26）——图标组不吃宽，旧的流式行在 460px 下反而放得下。
     *     故「一行放得下」由 [test控制组最坏宽在预算内] 的宽度预算锁守，「折行」的真机确认走 runIde 走查。
     * 零尺寸子件（headless 的工具栏占位）不计入行带，否则其中心 0 会造成假红。
     */
    private fun distinctRowCount(controls: JPanel): Int {
        val centers = controls.components
            .filter { it.isVisible && it.width > 0 && it.height > 0 }
            .map { it.y + it.height / 2 }
            .sorted()
        if (centers.isEmpty()) return 0
        var rows = 1
        var prev = centers.first()
        for (c in centers.drop(1)) {
            if (c - prev > 10) rows++
            prev = c
        }
        return rows
    }

    /** 控制组内最高控件的理想高（单行行的上限基准；两行必然接近它的两倍）。 */
    private fun tallestControlHeight(controls: JPanel): Int =
        controls.components.filter { it.isVisible && it.height > 0 }
            .maxOfOrNull { it.preferredSize.height } ?: 0

    fun test工具栏恒为单行_用户宽度下() {
        val panel = SqlLogPanel(project, manager)
        try {
            // 多选类型 → 按钮文案变长（旧流式行正是在这个状态下把「排序」挤到第二行）
            panel.applyTypeFilterForTest(setOf(StatementType.SELECT, StatementType.UPDATE))
            waitForEdt()

            val row = layoutRowAt(panel, USER_SIDEBAR_WIDTH)
            val controls = panel.toolbarControlsForTest!!
            assertEquals(
                "控制组应单行（实测 ${distinctRowCount(controls)} 行）",
                1,
                distinctRowCount(controls),
            )
            assertTrue("控制组内控件应共轴（垂直中心极差 ${verticalBand(controls)}）", verticalBand(controls) <= 6)
            val tallest = tallestControlHeight(controls)
            assertTrue(
                "工具栏行高 ${row.height} 应明显小于两行高（单行基准 $tallest×2-8）",
                row.height < tallest * 2 - 8,
            )
            // 控制组右缘不越界（勾选在最右，不得被推出面板）
            val controlsRight = controls.x + controls.width
            assertTrue("控制组右缘 $controlsRight 不应超出行宽 ${row.width}", controlsRight <= row.width)
        } finally {
            panel.dispose()
        }
    }

    /**
     * 控制组最坏宽锁：类型多选（显示文本被预算封顶）即控制组的最坏形态。
     *
     * 上限 340 = 用户侧边栏 460 − 图标组保守下限 120（真机量测约 133，取更保守的 120）。
     * 本断言防的是「又往控制组加控件 / 又改长文案」——一旦超限，单行就会挤掉图标组，
     * 而 headless 复现不出挤掉过程（见 [distinctRowCount] 边界 2），故必须用预算锁前置拦住。
     */
    fun test控制组最坏宽在预算内() {
        val panel = SqlLogPanel(project, manager)
        try {
            panel.applyTypeFilterForTest(
                setOf(StatementType.SELECT, StatementType.UPDATE, StatementType.DELETE),
            )
            waitForEdt()
            val controls = panel.toolbarControlsForTest!!
            val worst = controls.preferredSize.width
            assertTrue(
                "控制组最坏理想宽 $worst 应 ≤ $CONTROLS_WIDTH_BUDGET" +
                    "（= 用户侧边栏 $USER_SIDEBAR_WIDTH − 图标组保守下限 $ICON_GROUP_WIDTH_MIN）",
                worst <= CONTROLS_WIDTH_BUDGET,
            )
        } finally {
            panel.dispose()
        }
    }

    /**
     * 控制组结构守卫：单行定构下「列表控制件在 EAST 控制组、动作图标组在 CENTER」。
     * 反向验证的落点——把旧流式行结构放回去时，本用例必红（控制组里会出现图标组与两个标签）。
     *
     * 图标组挂载断言是**实测漏点**：还原反向验证探针时曾把 `row.add(toolbar.component, CENTER)`
     * 一并删掉，而 headless 下工具栏理想宽 0×0、其余用例全绿——图标组整个没进行也无人报错。
     */
    fun test控制组结构_含列表控制件且不含图标组() {
        val panel = SqlLogPanel(project, manager)
        try {
            waitForEdt()
            val row = panel.toolbarRowForTest!!
            val toolbarComponent = panel.toolbarComponentForTest
            assertNotNull("动作图标组组件应存在", toolbarComponent)
            assertTrue(
                "动作图标组必须挂在行宿主上（曾漏挂且全绿）",
                row.components.any { it === toolbarComponent },
            )
            val controls = panel.toolbarControlsForTest!!
            val children = controls.components.filter { it.isVisible }.toList()
            assertTrue("控制组应含 SQL类型按钮", children.contains(panel.typeFilterButtonForTest))
            assertTrue("控制组应含 排序按钮", children.contains(panel.sortButtonForTest))
            assertTrue("控制组应含 去重勾选（仍在最右）", children.contains(panel.dedupeCheckBoxForTest))
            assertEquals(
                "去重勾选应在控制组最右",
                panel.dedupeCheckBoxForTest,
                children.last(),
            )
            assertFalse("动作图标组不应混进控制组（单行定构下它在 CENTER）", children.any { it === toolbarComponent })
        } finally {
            panel.dispose()
        }
    }

    fun test工具栏宽度扫描_恒单行不折行() {
        val panel = SqlLogPanel(project, manager)
        try {
            panel.applyTypeFilterForTest(setOf(StatementType.SELECT, StatementType.UPDATE, StatementType.DELETE))
            waitForEdt()
            for (width in listOf(400, USER_SIDEBAR_WIDTH, 600, 800)) {
                val row = layoutRowAt(panel, width)
                val controls = panel.toolbarControlsForTest!!
                assertEquals(
                    "宽度 ${width}px 下控制组应仍为单行（实测 ${distinctRowCount(controls)} 行）",
                    1,
                    distinctRowCount(controls),
                )
                assertTrue(
                    "宽度 ${width}px 下控制组不应越界（右缘 ${controls.x + controls.width} > ${row.width}）",
                    controls.x + controls.width <= row.width,
                )
            }
        } finally {
            panel.dispose()
        }
    }

    fun test慢查询徽章出现时仍为单行() {
        val saved = PluginSettings.getInstance().state.slowQueryThresholdMs
        PluginSettings.getInstance().state.slowQueryThresholdMs = 1000
        val panel = SqlLogPanel(project, manager)
        try {
            manager.entries.addEntry(
                LogSqlEntry(
                    rawSql = "SELECT slow FROM big_table",
                    restoredSql = "SELECT slow FROM big_table",
                    params = emptyList(),
                    timestamp = 1L,
                    executionTimeMs = 2000L,
                ),
            )
            waitForEdt()
            val label = panel.slowQueryLabelForTest
            assertNotNull("慢查询徽章应存在", label)
            assertEquals("存在慢查询时徽章可见", true, label?.isVisible)
            // T-UiCoord F2：全文「慢查询: N」改短徽章（复用 log.panel.slowQueryBadge），全文进 tooltip
            assertEquals(
                "徽章应为短形态",
                MpSqlVisualizerBundle.message("log.panel.slowQueryBadge") + " 1",
                label?.text,
            )
            assertEquals(
                "tooltip 应为全文（信息不丢）",
                MpSqlVisualizerBundle.message("log.toolbar.slowQueryCount", "1"),
                label?.toolTipText,
            )
            val row = layoutRowAt(panel, USER_SIDEBAR_WIDTH)
            val controls = panel.toolbarControlsForTest!!
            assertEquals(
                "徽章在场时控制组仍为单行",
                1,
                distinctRowCount(controls),
            )
            assertTrue("徽章在场时控制组不越界", controls.x + controls.width <= row.width)
        } finally {
            PluginSettings.getInstance().state.slowQueryThresholdMs = saved
            panel.dispose()
        }
    }

    fun test类型按钮文本封顶_多选后不撑宽() {
        val panel = SqlLogPanel(project, manager)
        try {
            val btn = panel.typeFilterButtonForTest
            // 三选（非全选——全选时按钮显示「全部」，不构成超预算文案）
            panel.applyTypeFilterForTest(
                setOf(StatementType.SELECT, StatementType.UPDATE, StatementType.DELETE),
            )
            waitForEdt()

            val tip = btn.toolTipText ?: ""
            assertTrue(
                "tooltip 应以「SQL类型：」标签键为前缀（标签控件退役后语义由它承载）",
                tip.startsWith(MpSqlVisualizerBundle.message("log.filter.type.label")),
            )
            for (key in listOf("log.filter.select", "log.filter.update", "log.filter.delete")) {
                assertTrue("tooltip 应含 $key 全文", tip.contains(MpSqlVisualizerBundle.message(key)))
            }
            assertTrue("多选组合应被截尾省略（显示文本「${btn.text}」）", btn.text.endsWith("\u2026"))
            val width = btn.getFontMetrics(btn.font).stringWidth(btn.text)
            assertTrue(
                "显示文本宽度 $width 应 ≤ 预算 ${panel.dropdownTextBudgetForTest}",
                width <= panel.dropdownTextBudgetForTest,
            )
            assertTrue("显示文本应短于 tooltip 全文（省略只发生在显示层）", btn.text.length < tip.length)
        } finally {
            panel.dispose()
        }
    }

    fun test去标签后tooltip携带完整状态() {
        val panel = SqlLogPanel(project, manager)
        try {
            waitForEdt()
            assertEquals(
                "类型按钮 tooltip 应为「SQL类型：」+ 当前状态",
                MpSqlVisualizerBundle.message("log.filter.type.label") + MpSqlVisualizerBundle.message("log.filter.all"),
                panel.typeFilterButtonForTest.toolTipText,
            )
            assertEquals(
                "排序按钮 tooltip 应为「排序：」+ 当前方式",
                MpSqlVisualizerBundle.message("log.sort.label") + MpSqlVisualizerBundle.message("log.sort.time.asc"),
                panel.sortButtonForTest.toolTipText,
            )
            // 排序按钮文案在预算内，不应被省略（省略只对超预算的组合生效）
            assertEquals("时间升序", panel.sortButtonTextForTest)
        } finally {
            panel.dispose()
        }
    }

    fun test切换历史Tab_历史面板正常装配() {
        val panel = SqlLogPanel(project, manager)
        try {
            waitForEdt()
            // 写入一个历史文件 → 构造时即显示历史 tab；否则模拟设置开启
            val dir = java.io.File(
                project.basePath + java.io.File.separator + ".idea" +
                    java.io.File.separator + SqlLogAutoSaver.DATA_DIR_NAME +
                    java.io.File.separator + "sql-history",
            )
            dir.mkdirs()
            java.io.File(dir, "sql-history-2026-08-31.sql").writeText(
                "-- source: A.java:1 | time: 1ms | 2026-08-31 17:30:00.000 --\nSELECT 1\n\n",
            )
            // 重新构建面板以纳入历史 tab（构造期基于 .idea 文件判断）
            panel.dispose()
            val panel2 = SqlLogPanel(project, manager)
            waitForEdt()
            // 切到历史 tab 不抛异常（兼容：历史面板正确装配进 JBTabbedPane）
            val tabCount = (panel2.getComponent(0) as? javax.swing.JTabbedPane)?.tabCount ?: -1
            assertTrue("含历史/转换等 tab，tab 数应 ≥ 3", tabCount >= 3)
            panel2.dispose()
        } finally {
            panel.dispose()
        }
    }

    /** R19：历史 tab 常驻——未开启自动保存且无历史文件时 tab 仍在（原 F-44 条件挂载废除），
     *  空态由文件列表引导文案承接（开启自动保存的指引）。 */
    @Test
    fun testR19_历史tab常驻_未开启自动保存也无历史文件时仍在() {
        runCatching { PluginSettings.getInstance().state.logAutoSaveEnabled = false }
        val manager = SqlLogManager(project)
        val panel = SqlLogPanel(project, manager)
        try {
            val tabs = panel.getComponent(0) as? javax.swing.JTabbedPane
            org.junit.Assert.assertNotNull(tabs)
            var found = false
            for (i in 0 until tabs!!.tabCount) {
                if (tabs.getTitleAt(i).contains("历史") || tabs.getTitleAt(i).contains("History")) found = true
            }
            assertTrue("未开启自动保存时历史 tab 也应常驻（R19）", found)
        } finally {
            panel.dispose()
        }
    }
}

/** 用户日常侧边栏宽度（2026-09-21 真机截图量测：内容区约 460px）——单行契约的主验宽度。 */
private const val USER_SIDEBAR_WIDTH = 460

/** 图标组宽度的**保守下限**（真机量测约 133：5 枚图标 + 工具栏内边距）。 */
private const val ICON_GROUP_WIDTH_MIN = 120

/** 控制组理想宽上限 = [USER_SIDEBAR_WIDTH] − [ICON_GROUP_WIDTH_MIN]（超了单行就会挤掉图标组）。 */
private const val CONTROLS_WIDTH_BUDGET = USER_SIDEBAR_WIDTH - ICON_GROUP_WIDTH_MIN
