package cn.song.mybatisplus.visualizer.ui

import cn.song.mybatisplus.visualizer.model.PreviewResult
import cn.song.mybatisplus.visualizer.model.WrapperType
import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.awt.datatransfer.Transferable
import javax.swing.JButton

/**
 * P-05：[SqlPreviewPanel] 参数批量导入 / 历史召回集成测试。
 *
 * 需要 IDE fixture（编辑器/PSI 依赖应用环境），在 EDT 上运行。
 * 注意：BasePlatformTestCase 测试已在 EDT 执行，无需 invokeAndWait。
 */
class SqlPreviewPanelImportTest : BasePlatformTestCase() {

    // 测试前快照系统剪贴板，tearDown 恢复，避免剪贴板导入测试污染用户剪贴板
    private var clipboardSnapshot: Transferable? = null

    override fun setUp() {
        super.setUp()
        runCatching { cn.song.mybatisplus.visualizer.config.PluginSettings.getInstance().state.formatSql = false }
        clipboardSnapshot = Toolkit.getDefaultToolkit().systemClipboard.getContents(null)
    }

    override fun tearDown() {
        runCatching { cn.song.mybatisplus.visualizer.config.PluginSettings.getInstance().state.formatSql = true }
        runCatching { Toolkit.getDefaultToolkit().systemClipboard.setContents(clipboardSnapshot, null) }
        super.tearDown()
    }

    private fun result(
        sql: String = "SELECT id\nFROM tbl",
        compact: String = "SELECT id FROM tbl",
        params: List<String> = emptyList(),
        tableName: String? = "tbl",
    ) = PreviewResult(
        sql = sql,
        compactSql = compact,
        unresolvedParams = params,
        paramTable = if (params.isEmpty()) null else "-- 参数对照: [${params.joinToString(", ") { "$it=?" }}]",
        wrapperType = WrapperType.LAMBDA_QUERY,
        tableName = tableName,
        columns = listOf("id", "name"),
    )

    // ------------------------------------------------------------------
    // 导入按钮存在性
    // ------------------------------------------------------------------

    fun testImportButtonExists() {
        // P-05：工具栏含「导入」按钮（位于「格式化」与「刷新」之间）
        val panel = SqlPreviewPanel(project)
        panel.showResult(result())
        val importButton = findToolbarButton(panel, "panel.param.import")
        assertNotNull("工具栏应含导入按钮", importButton)
        panel.clear()
    }

    // ------------------------------------------------------------------
    // 导入匹配回填
    // ------------------------------------------------------------------

    fun testImportMatchesAndBackfills() {
        // P-05：导入 `{"name":"test"}` → name 参数框显示 "test"
        val panel = SqlPreviewPanel(project)
        val sql = "SELECT id FROM tbl WHERE name = ? AND age = ?"
        panel.showResult(result(sql = sql, compact = sql, params = listOf("name", "age")))
        // 通过测试入口注入参数（绕过剪贴板）
        panel.importParamsForTest(mapOf("name" to "test"))
        assertEquals("name 参数框应显示 test", "test", panel.paramFieldForTest(0)!!.text)
        // 未导入的 age 不受影响
        assertEquals("age 参数框应为空", "", panel.paramFieldForTest(1)!!.text)
        panel.clear()
    }

    fun testImportMatchesWithHashPrefix() {
        // P-05：模糊匹配 key ≡ `#source`
        val panel = SqlPreviewPanel(project)
        val sql = "SELECT id FROM tbl WHERE name = ?"
        panel.showResult(result(sql = sql, compact = sql, params = listOf("name")))
        panel.importParamsForTest(mapOf("#name" to "zhang"))
        assertEquals("模糊匹配 #name → name", "zhang", panel.paramFieldForTest(0)!!.text)
        panel.clear()
    }

    fun testImportOverwritesExistingValue() {
        // P-05：importParams 是用户主动触发，应覆盖已有值
        val panel = SqlPreviewPanel(project)
        val sql = "SELECT id FROM tbl WHERE name = ?"
        panel.showResult(result(sql = sql, compact = sql, params = listOf("name")))
        panel.paramFieldForTest(0)!!.text = "userValue"
        panel.importParamsForTest(mapOf("name" to "imported"))
        assertEquals("importParams 应覆盖已有值", "imported", panel.paramFieldForTest(0)!!.text)
        panel.clear()
    }

    // ------------------------------------------------------------------
    // 导入未匹配
    // ------------------------------------------------------------------

    fun testImportUnmatchedKeysIgnored() {
        // P-05：导入 `{"unknown":"x"}` → 未匹配项不影响已有参数
        val panel = SqlPreviewPanel(project)
        val sql = "SELECT id FROM tbl WHERE name = ?"
        panel.showResult(result(sql = sql, compact = sql, params = listOf("name")))
        panel.importParamsForTest(mapOf("unknown" to "x"))
        // name 参数框不受影响
        assertEquals("未匹配导入不影响已有参数", "", panel.paramFieldForTest(0)!!.text)
        panel.clear()
    }

    // ------------------------------------------------------------------
    // 历史召回自动填充
    // ------------------------------------------------------------------

    fun testHistoryRecallAutoFill() {
        // P-05：第二次打开相同表+SQL → 参数框显示上次值
        val sql = "SELECT id FROM tbl WHERE name = ? AND age = ?"
        // 第一次打开：输入参数值
        val panel1 = SqlPreviewPanel(project)
        panel1.showResult(result(sql = sql, compact = sql, params = listOf("name", "age"), tableName = "recall_tbl"))
        panel1.paramFieldForTest(0)!!.text = "zhang"
        panel1.paramFieldForTest(1)!!.text = "28"
        // 强制同步刷写（绕过 debounce Timer，测试用）
        panel1.flushHistoryForTest()
        panel1.clear()

        // 第二次打开相同表+SQL：应自动召回
        val panel2 = SqlPreviewPanel(project)
        panel2.showResult(result(sql = sql, compact = sql, params = listOf("name", "age"), tableName = "recall_tbl"))
        assertEquals("历史召回应填充 name", "zhang", panel2.paramFieldForTest(0)!!.text)
        assertEquals("历史召回应填充 age", "28", panel2.paramFieldForTest(1)!!.text)
        panel2.clear()
        // 清理历史缓存
        runCatching { panel2.paramHistoryCacheForTest().clear() }
    }

    fun testHistoryRecallDoesNotOverwriteUserInput() {
        // P-05：用户手动输入后重新打开 → 不覆盖用户输入
        val sql = "SELECT id FROM tbl WHERE name = ?"
        // 第一次打开
        val panel1 = SqlPreviewPanel(project)
        panel1.showResult(result(sql = sql, compact = sql, params = listOf("name"), tableName = "recall_tbl2"))
        panel1.paramFieldForTest(0)!!.text = "first"
        panel1.flushHistoryForTest()
        panel1.clear()

        // 第二次打开
        val panel2 = SqlPreviewPanel(project)
        panel2.showResult(result(sql = sql, compact = sql, params = listOf("name"), tableName = "recall_tbl2"))
        // 召回填充（此时为空，召回 "first"）
        assertEquals("召回应填充首次值", "first", panel2.paramFieldForTest(0)!!.text)
        // 用户手动覆盖
        panel2.paramFieldForTest(0)!!.text = "userOverride"
        panel2.flushHistoryForTest()
        panel2.clear()

        // 第三次打开：应召回用户覆盖后的值（"userOverride"），不覆盖
        val panel3 = SqlPreviewPanel(project)
        panel3.showResult(result(sql = sql, compact = sql, params = listOf("name"), tableName = "recall_tbl2"))
        assertEquals("应保留用户覆盖后的值", "userOverride", panel3.paramFieldForTest(0)!!.text)
        panel3.clear()
        runCatching { panel3.paramHistoryCacheForTest().clear() }
    }

    // ------------------------------------------------------------------
    // 无表名不缓存
    // ------------------------------------------------------------------

    fun testNoTableNameNoCache() {
        // P-05：纯文本预览（tableName=null）→ 不读写缓存
        val sql = "SELECT id FROM tbl WHERE name = ?"
        val panel = SqlPreviewPanel(project)
        panel.showResult(result(sql = sql, compact = sql, params = listOf("name"), tableName = null))
        panel.paramFieldForTest(0)!!.text = "test"
        panel.flushHistoryForTest()
        // 无表名时 computeKey 返回 null → 不缓存
        val cache = panel.paramHistoryCacheForTest()
        val key = cache.computeKey(null, sql)
        assertEquals("无表名 computeKey 应返回 null", null, key)
        panel.clear()
    }

    // ------------------------------------------------------------------
    // 剪贴板导入（端到端）
    // ------------------------------------------------------------------

    fun testImportFromClipboardEndToEnd() {
        // P-05：端到端——设置剪贴板 → importParamsFromClipboard → 回填
        val panel = SqlPreviewPanel(project)
        val sql = "SELECT id FROM tbl WHERE name = ? AND age = ?"
        panel.showResult(result(sql = sql, compact = sql, params = listOf("name", "age")))
        // 模拟剪贴板
        val clipboard = Toolkit.getDefaultToolkit().systemClipboard
        clipboard.setContents(StringSelection("""{"name":"clip","age":99}"""), null)
        // 触发导入
        panel.importParamsFromClipboard()
        assertEquals("剪贴板导入应填充 name", "clip", panel.paramFieldForTest(0)!!.text)
        assertEquals("剪贴板导入应填充 age", "99", panel.paramFieldForTest(1)!!.text)
        panel.clear()
    }

    // ------------------------------------------------------------------
    // 工具方法
    // ------------------------------------------------------------------

    /** 按 i18n key 查找工具栏按钮。 */
    private fun findToolbarButton(panel: SqlPreviewPanel, i18nKey: String): JButton? {
        val text = MpSqlVisualizerBundle.message(i18nKey)
        return findButtonIn(panel, text)
    }

    /** 沿组件树递归查找文本匹配的 JButton。 */
    private fun findButtonIn(component: java.awt.Component, text: String): JButton? {
        if (component is JButton && component.text == text) return component
        if (component is java.awt.Container) {
            for (c in component.components) {
                findButtonIn(c, text)?.let { return it }
            }
        }
        return null
    }
}
