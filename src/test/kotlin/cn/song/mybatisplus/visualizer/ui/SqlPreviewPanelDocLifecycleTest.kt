package cn.song.mybatisplus.visualizer.ui

import cn.song.mybatisplus.visualizer.model.PreviewResult
import cn.song.mybatisplus.visualizer.model.WrapperType
import cn.song.mybatisplus.visualizer.ui.diagram.nativefw.db.JoinFkRegistry
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.application.WriteIntentReadAction
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import java.io.File

/**
 * T-PS4-12 / T-PS4-14④：预览面板 document 生命周期与注册表回收守护测试。
 *
 * - T-PS4-12：showSql nativeDoc 分支（T-PreviewConsoleFmt F1 挂载回填渲染）写入 formatted
 *   文本后在同临界区落盘——闸门（T-PreviewFast P4）前提「内容由本写入落盘」成真，document
 *   不再滞留 modified-unsaved（第七轮审计中低发现）。
 * - T-PS4-14④：面板 dispose 补 [JoinFkRegistry] 回收（T-EmbedFkOrder 三点口径的第三点）。
 *
 * 落盘断言用非 exec 路径的临时文件：exec 路径会卷入预热（BGT 异步写「预览原文」）与 F5
 * 防抖链，属独立守护域（ExecDocAutosaveHostTest / ExecDocSync2Test）；本文件只聚焦
 * 「有主文件 document 写入后不滞留 modified、磁盘与 document 一致」这一语义。
 */
class SqlPreviewPanelDocLifecycleTest : BasePlatformTestCase() {

    override fun setUp() {
        super.setUp()
        // O-97 治理（T-TestPerf15）：进态恢复出厂默认，截断跨类全局设置态泄漏
        cn.song.mybatisplus.visualizer.testutil.PluginSettingsTestSupport.resetToFactoryDefaults()
        cn.song.mybatisplus.visualizer.config.PluginSettings.getInstance().state.formatSql = true
    }

    override fun tearDown() {
        runCatching { cn.song.mybatisplus.visualizer.config.PluginSettings.getInstance().state.formatSql = true }
        super.tearDown()
    }

    private fun result(
        sql: String = "select  id ,name  from t  where id=1",
        compact: String = "select id,name from t where id=1",
    ) = PreviewResult(
        sql,
        compact,
        emptyList(),
        null,
        WrapperType.LAMBDA_QUERY,
        "t",
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

    private fun writeAndRefresh(file: File, content: String): VirtualFile? {
        assertTrue(file.parentFile.exists() || file.parentFile.mkdirs())
        file.writeText(content)
        return WriteIntentReadAction.compute<VirtualFile?> {
            LocalFileSystem.getInstance().refreshAndFindFileByIoFile(file)
        }
    }

    // ======================== T-PS4-12：同临界区落盘 ========================
    /**
     * nativeDoc 分支渲染写入后：document 不滞留 modified-unsaved，磁盘与 document 一致
     * （formatted；挂载后磁盘跟随 document 是 T-PreviewConsoleFmt F1 + T-PreviewFast P1 定例）。
     */
    fun testShowSqlNativeDocBranchPersistsAndClearsUnsavedFlag() {
        val target = File(project.basePath!!, "ps12-showsql-target.sql")
        val vf = writeAndRefresh(target, "select  id ,name  from t  where id=1")
        assertNotNull(vf)
        val fdm = FileDocumentManager.getInstance()
        val consoleDoc = fdm.getDocument(vf!!)
        assertNotNull(consoleDoc)

        val panel = SqlPreviewPanel(project)
        try {
            panel.showResult(result())
            // 模拟控制台就绪且已嵌入本面板（showSql 的 nativeDoc 分支要求 consoleAttachedToMe，
            // SqlPreviewPanelTest T-PreviewConsoleFmt 先例同款驱动）
            panel.embedConsoleForTest(javax.swing.JPanel())
            assertTrue("前置：控制台应视为已挂到本面板", panel.consoleAttachedToMeForTest())
            panel.markNativeDocForTest(consoleDoc!!)

            panel.renderSqlForTest() // = 挂载后的回填渲染（走 nativeDoc 分支写入 + 落盘）

            assertEquals(
                "渲染链应把 formatted 回填进 nativeDoc（ConsoleFmt F1 语义保持）",
                consoleDoc.text,
                "SELECT\n    id,\n    name\nFROM t\nWHERE id = 1",
            )
            assertFalse(
                "T-PS4-12 核心：写入后不得滞留 modified-unsaved",
                fdm.isDocumentUnsaved(consoleDoc),
            )
            assertEquals(
                "T-PS4-12：同临界区落盘——磁盘应与 document 一致（行尾按 LF 规范化比对，" +
                    "saveDocument 在 Windows 写 CRLF 属平台行为）",
                consoleDoc.text,
                target.readText().replace("\r\n", "\n"),
            )
        } finally {
            panel.clear()
        }
    }

    /** 孤儿文档（无主文件，测试缝 createDocument 形态）写入后无盘可落，行为保持（不抛、不滞留）。 */
    fun testShowSqlNativeDocOrphanDocumentSkipsPersistGracefully() {
        val panel = SqlPreviewPanel(project)
        try {
            panel.showResult(result())
            panel.embedConsoleForTest(javax.swing.JPanel())
            val orphan = com.intellij.openapi.editor.EditorFactory.getInstance()
                .createDocument("select  id ,name  from t  where id=1")
            panel.markNativeDocForTest(orphan)

            panel.renderSqlForTest()

            assertEquals(
                "孤儿文档内容仍应被回填（显示语义不变）",
                "SELECT\n    id,\n    name\nFROM t\nWHERE id = 1",
                orphan.text,
            )
            assertFalse(
                "孤儿文档无盘可落，不滞留 modified 判定应为 false",
                FileDocumentManager.getInstance().isDocumentUnsaved(orphan),
            )
        } finally {
            panel.clear()
        }
    }

    // ======================== T-PS4-14④：dispose 回收注册表 ========================

    /**
     * 面板 dispose 后 [JoinFkRegistry] 应为空。沙盒无 Database 插件（原生 JOIN 建图链走
     * HeavyNativeDatabaseDiagramJoinChainTest 的 testDatabase 通道），此处按 SqlPreviewPanelTest
     * 惯例直接 put 模拟「最后一张 JOIN 图建图登记」，断言 dispose 的回收语义。
     */
    fun testDisposeClearsJoinFkRegistry() {
        JoinFkRegistry.clearForTest()
        try {
            JoinFkRegistry.put("order", "user", "user_id", "id")
            assertTrue("前置：注册表应非空", JoinFkRegistry.find("order", "user").isNotEmpty())

            val panel = SqlPreviewPanel(project)
            panel.dispose()

            assertTrue(
                "T-PS4-14④ 核心：面板 dispose 后注册表应为空（残留期 EP 不得给用户自开 Database 图补旧连线）",
                JoinFkRegistry.find("order", "user").isEmpty(),
            )
        } finally {
            JoinFkRegistry.clearForTest()
        }
    }
}

