package cn.song.mybatisplus.visualizer.ui

import cn.song.mybatisplus.visualizer.model.BatchColumn
import cn.song.mybatisplus.visualizer.model.BatchKind
import cn.song.mybatisplus.visualizer.model.BatchModel
import cn.song.mybatisplus.visualizer.model.PreviewResult
import cn.song.mybatisplus.visualizer.model.WrapperType
import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import java.awt.datatransfer.StringSelection
import javax.swing.JButton
import cn.song.mybatisplus.visualizer.testutil.FakeClipboardManager
import cn.song.mybatisplus.visualizer.testutil.installFakeClipboard

/**
 * P-05：[SqlPreviewPanel] 参数批量导入 / 历史召回集成测试。
 *
 * 需要 IDE fixture（编辑器/PSI 依赖应用环境），在 EDT 上运行。
 * 注意：BasePlatformTestCase 测试已在 EDT 执行，无需 invokeAndWait。
 */
class SqlPreviewPanelImportTest : BasePlatformTestCase() {

    /** T-ClipboardIso：内存剪贴板（测试全程不碰系统剪贴板）。 */
    private lateinit var clipboard: FakeClipboardManager

    override fun setUp() {
        super.setUp()
        runCatching { cn.song.mybatisplus.visualizer.config.PluginSettings.getInstance().state.formatSql = false }
        clipboard = installFakeClipboard(testRootDisposable)
    }

    override fun tearDown() {
        runCatching { cn.song.mybatisplus.visualizer.config.PluginSettings.getInstance().state.formatSql = true }
        super.tearDown()
    }

    private fun result(
        sql: String = "SELECT id\nFROM tbl",
        compact: String = "SELECT id FROM tbl",
        params: List<String> = emptyList(),
        tableName: String? = "tbl",
    ) = PreviewResult(
        sql,
        compact,
        params,
        if (params.isEmpty()) null else "-- 参数对照: [${params.joinToString(", ") { "$it=?" }}]",
        WrapperType.LAMBDA_QUERY,
        tableName,
        null,
        listOf("id", "name"),
        emptyMap(),
        null,
        null,
        emptyList(),
        emptyList(),
        null,
        null,
        emptyMap(),
        null,
        null,
        null,
        null,
    )

    // ------------------------------------------------------------------
    // 导入按钮存在性
    // ------------------------------------------------------------------

    fun testImportButtonExists() {
        // P-05 + T-PreviewTopOverlap F2：导入按钮折叠进「更多」菜单（不再是工具栏直显按钮）
        val panel = SqlPreviewPanel(project)
        panel.showResult(result())
        assertNull("导入按钮不应再直显于工具栏", findToolbarButton(panel, "panel.param.import"))
        val moreItems = panel.moreActionsForTest()
        assertTrue("「更多」菜单应含导入参数项", moreItems.contains(MpSqlVisualizerBundle.message("panel.param.import")))
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

    // ------------------------------------------------------------------
    // P-05.1：驼峰/下划线自动匹配
    // ------------------------------------------------------------------

    fun testImportCamelToSnakeMatch() {
        // P-05.1：source 为 snake_case `user_name`，导入 key 为 camelCase `userName` → 应匹配
        val panel = SqlPreviewPanel(project)
        val sql = "SELECT id FROM tbl WHERE user_name = ?"
        panel.showResult(result(sql = sql, compact = sql, params = listOf("user_name")))
        panel.importParamsForTest(mapOf("userName" to "zhang"))
        assertEquals("camelCase key 应匹配 snake_case source", "zhang", panel.paramFieldForTest(0)!!.text)
        panel.clear()
    }

    fun testImportSnakeToCamelMatch() {
        // P-05.1：source 为 camelCase `createTime`，导入 key 为 snake_case `create_time` → 应匹配
        val panel = SqlPreviewPanel(project)
        val sql = "SELECT id FROM tbl WHERE createTime = ?"
        panel.showResult(result(sql = sql, compact = sql, params = listOf("createTime")))
        panel.importParamsForTest(mapOf("create_time" to "2024-01-01"))
        assertEquals("snake_case key 应匹配 camelCase source", "2024-01-01", panel.paramFieldForTest(0)!!.text)
        panel.clear()
    }

    fun testImportCamelMatchWithHashPrefix() {
        // P-05.1：key 为 `#userName`（带 # 前缀 + 驼峰）→ 应匹配 snake_case source `user_name`
        val panel = SqlPreviewPanel(project)
        val sql = "SELECT id FROM tbl WHERE user_name = ?"
        panel.showResult(result(sql = sql, compact = sql, params = listOf("user_name")))
        panel.importParamsForTest(mapOf("#userName" to "zhang"))
        assertEquals("#前缀 + camelCase 应匹配 snake_case source", "zhang", panel.paramFieldForTest(0)!!.text)
        panel.clear()
    }

    fun testImportExactMatchTakesPriority() {
        // P-05.1：精确匹配优先于驼峰/下划线互转
        val panel = SqlPreviewPanel(project)
        val sql = "SELECT id FROM tbl WHERE user_name = ? AND userName = ?"
        panel.showResult(result(sql = sql, compact = sql, params = listOf("user_name", "userName")))
        panel.importParamsForTest(mapOf("userName" to "exact"))
        // userName 精确命中第二个参数（互转会命中第一个，但精确优先）
        assertEquals("精确匹配优先：userName 命中自身", "exact", panel.paramFieldForTest(1)!!.text)
        assertEquals("精确匹配优先：user_name 不受影响", "", panel.paramFieldForTest(0)!!.text)
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
    // 清除按钮（批量参数列分区）
    // ------------------------------------------------------------------

    fun testClearButtonClearsBatchValues() {
        // 清除按钮：填写列值 → 点击清除 → 输入框清空、勾选态保留
        val model = BatchModel(
            BatchKind.INSERT,
            "biz_store_coupon",
            listOf(
                BatchColumn("name", "entity.name", true, true),
                BatchColumn("dic_status", "entity.dic_status", false, true),
            ),
        )
        val active = model.toggleColumns.filter { it.checked }.map { it.columnName }.toSet()
        val panel = SqlPreviewPanel(project)
        panel.showResult(
            PreviewResult(
                model.renderSql(active),
                model.renderSql(active),
                model.orderedSources(active),
                null,
                null,
                model.tableName,
                null,
                model.toggleColumns.map { it.columnName },
                emptyMap(),
                null,
                null,
                emptyList(),
                emptyList(),
                model,
                null,
                emptyMap(),
                null,
                null,
                null,
                null,
            ),
        )
        // 清除按钮存在
        assertNotNull("批量清除按钮应存在", panel.batchClearButtonForTest())
        // 填写列值
        panel.paramFieldForTest(0)!!.text = "testName"
        panel.paramFieldForTest(1)!!.text = "1"
        // 点击清除
        panel.batchClearButtonForTest()!!.doClick()
        // 输入框清空
        assertEquals("清除后输入框应为空", "", panel.paramFieldForTest(0)!!.text)
        assertEquals("清除后输入框应为空", "", panel.paramFieldForTest(1)!!.text)
        // paramValues 清空（key 为 BatchColumn.source，即 SpEL 表达式）
        assertEquals("paramValues 应为空字符串", "", panel.paramValuesForTest()["entity.name"])
        assertEquals("paramValues 应为空字符串", "", panel.paramValuesForTest()["entity.dic_status"])
        // 勾选态保留
        assertTrue("清除不影响勾选态", panel.batchCheckboxForTest(0)!!.isSelected)
        assertTrue("清除不影响勾选态", panel.batchCheckboxForTest(1)!!.isSelected)
        panel.clear()
    }

    // ------------------------------------------------------------------
    // 剪贴板导入（端到端）
    // ------------------------------------------------------------------

    fun testImportFromClipboardShowsDialog() {
        // P-05.1：点击导入参数 → 弹框（不读取剪贴板，用户手动粘贴）
        val panel = SqlPreviewPanel(project)
        val sql = "SELECT id FROM tbl WHERE name = ? AND age = ?"
        panel.showResult(result(sql = sql, compact = sql, params = listOf("name", "age")))
        // 触发导入 → 弹框（不直接回填，用户需确认）
        panel.importParamsFromClipboard()
        // 弹框态：参数框尚未填充（等待用户在弹框中确认）
        assertEquals("弹框态：name 不应直接填充", "", panel.paramFieldForTest(0)!!.text)
        assertEquals("弹框态：age 不应直接填充", "", panel.paramFieldForTest(1)!!.text)
        panel.clear()
    }

    // ------------------------------------------------------------------
    // P-05.1：默认回显当前参数（JSON 格式）
    // ------------------------------------------------------------------

    fun testImportDialogPrefill_currentParamsAsJson() {
        // P-05.1：有参数值时 → 弹框预填 JSON
        val panel = SqlPreviewPanel(project)
        val sql = "SELECT id FROM tbl WHERE name = ? AND age = ?"
        panel.showResult(result(sql = sql, compact = sql, params = listOf("name", "age")))
        // 模拟用户输入参数值
        panel.paramFieldForTest(0)!!.text = "张三"
        panel.paramFieldForTest(1)!!.text = "28"
        // 触发导入 → 弹框应预填 JSON
        val dialog = ParamImportDialog(project, panel.serializeParamsToJsonForTest()) {}
        try {
            assertTrue("弹框应预填 name", dialog.textForTest().contains("\"name\""))
            assertTrue("弹框应预填 张三", dialog.textForTest().contains("张三"))
            assertTrue("弹框应预填 age", dialog.textForTest().contains("\"age\""))
            assertTrue("弹框应预填 28", dialog.textForTest().contains("28"))
        } finally {
            dialog.dispose() // 释放 Editor
        }
        panel.clear()
    }

    fun testImportDialogPrefill_emptyParamsNoPrefill() {
        // P-05.1：无参数值时 → 弹框为空 + 占位示例
        val panel = SqlPreviewPanel(project)
        val sql = "SELECT id FROM tbl WHERE name = ? AND age = ?"
        panel.showResult(result(sql = sql, compact = sql, params = listOf("name", "age")))
        // 不输入参数值
        assertEquals("无参数值时 serializeParamsToJson 应返回空串", "", panel.serializeParamsToJsonForTest())
        panel.clear()
    }

    fun testSerializeParamsToJson_filtersEmptyValues() {
        // P-05.1：部分参数有值 → 仅序列化非空参数
        val panel = SqlPreviewPanel(project)
        val sql = "SELECT id FROM tbl WHERE name = ? AND age = ?"
        panel.showResult(result(sql = sql, compact = sql, params = listOf("name", "age")))
        panel.paramFieldForTest(0)!!.text = "张三"
        // age 保持空串
        val json = panel.serializeParamsToJsonForTest()
        assertTrue("应包含 name", json.contains("\"name\""))
        assertTrue("应包含 张三", json.contains("张三"))
        assertFalse("不应包含 age（空值过滤）", json.contains("\"age\""))
        panel.clear()
    }

    fun testSerializeParamsToJson_escapesQuotes() {
        // P-05.1：参数值含引号 → 应转义
        val panel = SqlPreviewPanel(project)
        val sql = "SELECT id FROM tbl WHERE name = ?"
        panel.showResult(result(sql = sql, compact = sql, params = listOf("name")))
        panel.paramFieldForTest(0)!!.text = "say \"hello\""
        val json = panel.serializeParamsToJsonForTest()
        assertTrue("应转义引号", json.contains("\\\"hello\\\""))
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
