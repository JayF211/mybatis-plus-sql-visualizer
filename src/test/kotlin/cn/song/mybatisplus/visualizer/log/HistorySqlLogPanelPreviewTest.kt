package cn.song.mybatisplus.visualizer.log

import cn.song.mybatisplus.visualizer.config.PluginSettings
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import java.io.File

/**
 * 历史面板预览构建测试（T-HistParam F3/F4）：openPreview 的 PreviewResult 构建路径
 * （[HistorySqlLogPanel.buildPreviewResult]，internal 缝不弹窗）——
 * v2 带参条目参数行与日志页一致；字面量条目走 F4 恢复通道。
 */
class HistorySqlLogPanelPreviewTest : BasePlatformTestCase() {

    private val historyDir: File
        get() = File(project.basePath!!, ".idea${File.separator}${SqlLogAutoSaver.DATA_DIR_NAME}${File.separator}sql-history")

    private var recoveryWasOn: Boolean? = null

    override fun setUp() {
        super.setUp()
        historyDir.mkdirs()
        historyDir.listFiles()?.forEach { it.delete() }
        // 应用级设置跨测试用例共享：显式钉住开关并记录原值，测后还原
        recoveryWasOn = PluginSettings.getInstance().state.literalParamRecovery
        PluginSettings.getInstance().state.literalParamRecovery = true
    }

    override fun tearDown() {
        recoveryWasOn?.let { PluginSettings.getInstance().state.literalParamRecovery = it }
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
}
