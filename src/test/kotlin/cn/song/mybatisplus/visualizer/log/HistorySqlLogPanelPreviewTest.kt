package cn.song.mybatisplus.visualizer.log

import cn.song.mybatisplus.visualizer.config.PluginSettings
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import java.io.File

/**
 * 历史面板预览构建测试（T-HistParam F3/F4；T-ListPreviewCopy 格式化跟随设置）：
 * openPreview 的 PreviewResult 构建路径（HistorySqlLogPanel.buildPreviewResult，internal 缝不弹窗）——
 * v2 带参条目参数行与日志页一致；字面量条目走 F4 恢复通道；
 * formatSql 跟随「预览时格式化还原 SQL」（logFormatSql）：关闭=原文（识别正交），开启=多行格式化。
 */
class HistorySqlLogPanelPreviewTest : BasePlatformTestCase() {

    private val historyDir: File
        get() = File(project.basePath!!, ".idea${File.separator}${SqlLogAutoSaver.DATA_DIR_NAME}${File.separator}sql-history")

    private var recoveryWasOn: Boolean? = null
    private var formatWasOn: Boolean? = null

    public override fun setUp() {
        super.setUp()
        historyDir.mkdirs()
        historyDir.listFiles()?.forEach { it.delete() }
        // 应用级设置跨测试用例共享：显式钉住开关并记录原值，测后还原
        recoveryWasOn = PluginSettings.getInstance().state.literalParamRecovery
        PluginSettings.getInstance().state.literalParamRecovery = true
        // T-ListPreviewCopy：buildPreviewResult 改读 logFormatSql——识别断言钉关闭态（与格式化正交）
        formatWasOn = PluginSettings.getInstance().state.logFormatSql
        PluginSettings.getInstance().state.logFormatSql = false
    }

    override fun tearDown() {
        recoveryWasOn?.let { PluginSettings.getInstance().state.literalParamRecovery = it }
        formatWasOn?.let { PluginSettings.getInstance().state.logFormatSql = it }
        super.tearDown()
    }

    private fun writeHistoryFile(name: String, content: String): File {
        val f = File(historyDir, name)
        f.writeText(content)
        return f
    }

    fun testV2带参条目预览构建参数行与日志页一致() {
        val payload = SqlLogParamCodec.encode(
            "SELECT * FROM user WHERE id = ?",
            listOf(LogSqlParam("42", "Long")),
        )
        writeHistoryFile(
            "sql-history-2026-09-11.sql",
            "-- source: UserMapper.java:10 | time: 3ms | 2026-09-11 09:00:00.000 --\n" +
                "-- pd: $payload --\n" +
                "SELECT * FROM user WHERE id = 42\n\n",
        )

        val panel = HistorySqlLogPanel(project)
        panel.refreshForTest() // F87 懒首扫：构造不再自动扫盘，测试显式触发（同步路径）
        try {
            val fileEntry = panel.fileListEntries.single()
            panel.openFileForTest(fileEntry)
            val entry = panel.sqlListEntries.single()
            assertEquals("读回条目 params 齐全", listOf(LogSqlParam("42", "Long")), entry.params)

            val result = panel.buildPreviewResult(entry)
            assertEquals("参数行来源与日志页一致", listOf("param1"), result.unresolvedParams)
            assertEquals(mapOf("param1" to "42"), result.initialParamValues)
            assertNull("识别成功无警告", result.warning)
        } finally {
            panel.dispose()
        }
    }

    fun test字面量条目预览构建走恢复通道() {
        writeHistoryFile(
            "sql-history-2026-09-11.sql",
            "-- source: OrderMapper.java:5 | time: 2ms | 2026-09-11 09:00:00.000 --\n" +
                "SELECT * FROM tableA WHERE (\"id\" = '3251')\n\n",
        )

        val panel = HistorySqlLogPanel(project)
        panel.refreshForTest() // F87 懒首扫：构造不再自动扫盘，测试显式触发（同步路径）
        try {
            panel.openFileForTest(panel.fileListEntries.single())
            val entry = panel.sqlListEntries.single()

            val result = panel.buildPreviewResult(entry)
            assertEquals("字面量原位改写为 ?", "SELECT * FROM tableA WHERE (\"id\" = ?);", result.sql)
            assertEquals(listOf("param1"), result.unresolvedParams)
            assertEquals(mapOf("param1" to "3251"), result.initialParamValues)
            assertTrue(result.paramHints.single().quote == true)
        } finally {
            panel.dispose()
        }
    }

    fun test预览构建跟随格式化设置开启时多行显示() {
        // T-ListPreviewCopy：默认开启 logFormatSql → 双击预览多行格式化（与日志 tab 一致）
        PluginSettings.getInstance().state.logFormatSql = true
        writeHistoryFile(
            "sql-history-2026-09-14.sql",
            "-- source: OrderMapper.java:5 | time: 2ms | 2026-09-14 10:00:00.000 --\n" +
                "SELECT t.id,t.name FROM car_device_info t LEFT JOIN station_info st ON (st.station_id = t.id) WHERE (t.id = 1)\n\n",
        )

        val panel = HistorySqlLogPanel(project)
        panel.refreshForTest() // F87 懒首扫：构造不再自动扫盘，测试显式触发（同步路径）
        try {
            panel.openFileForTest(panel.fileListEntries.single())
            val entry = panel.sqlListEntries.single()

            val result = panel.buildPreviewResult(entry)
            assertTrue("开启设置时预览应为多行格式化", result.sql.contains('\n'))
            assertTrue("分号收尾", result.sql.endsWith(";"))
        } finally {
            panel.dispose()
            PluginSettings.getInstance().state.logFormatSql = formatWasOn!!
        }
    }
}
