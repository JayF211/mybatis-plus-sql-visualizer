package cn.song.mybatisplus.visualizer.ui

import cn.song.mybatisplus.visualizer.model.PreviewResult
import cn.song.mybatisplus.visualizer.model.WrapperType
import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.util.ui.UIUtil
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue

/**
 * T-PrewarmLateRecover：降级链的**恢复出口**——迟到产物换装（F1）与横幅「重试」动作（F2）。
 *
 * 缺陷形态（真机 2026-09-22 沙盒实测，见 `docs/验收文档/20260920-待分析问题清单.md` P1 补充读数）：
 * 冷启首开预热耗时 3016ms 远超 500ms 预算 → 面板落内存 PSI 降级 + 挂横幅；预热其实成功了，但产物
 * 只落在 `prewarmedTempFile` 字段上（`createEditor` 只在 `editor == null` 时进入），此后无人消费，
 * 横幅在整个对话框生命周期内常驻——用户只能关掉重开。
 *
 * 两条恢复路径分别锁死：
 *  - **F1 迟到换装**：[test迟到产物到达_自动换装并清横幅]（走真实胶水——`await.complete` 返回 false
 *    即「消费侧已放弃」，预热线程据此走迟到通道）、[test用户已编辑_迟到产物不换装且不丢内容]（守卫反向）、
 *    [test面板销毁后_迟到产物不换装不抛]；
 *  - **F2 横幅重试**：[test降级横幅重试动作_点击后重新预热并换装]（动作可见性 + 文案 + 点击后重臂、
 *    就绪换装、清横幅）。
 *
 * **降级态如何确定性构造**：本环境里「真实首开超时」要靠负载（不可复现），故走
 * [degradePanel]——先正常装配消费掉产物，再 `releaseEditor()` 后二次装配：产物已被消费
 * （`prewarmedTempFile === prewarmed` 判假）→ 降级链 + 横幅。这条路线与 P9 记录的同面板二次装配
 * 同源，是 headless 下唯一**与分片顺序无关**的降级入口（不依赖「本 JVM 首个面板」这类脆弱前置）。
 */
class SqlPreviewPanelPrewarmLateRecoverTest : BasePlatformTestCase() {

    private fun result() = PreviewResult(
        sql = "SELECT id\nFROM user\nWHERE username = ?",
        compactSql = "SELECT id FROM user WHERE username = ?",
        unresolvedParams = listOf("username"),
        paramTable = null,
        wrapperType = WrapperType.LAMBDA_QUERY,
        tableName = "user",
        columns = listOf("id", "username"),
    )

    /**
     * 有界等待预热出结果（成功或失败），等待期间持续抽干 EDT 队列——方言预映射把 `setMapping`
     * 经 `invokeLater` 派给 EDT 并有界等待，纯 sleep 会把那 2s 预算烧掉（同
     * [SqlPreviewPanelPrewarmConsumeTest.awaitPrewarmProduct] 的实测教训）。
     */
    private fun awaitPrewarmProduct(
        panel: SqlPreviewPanel,
        budgetMs: Long = 60_000,
    ): Pair<com.intellij.openapi.vfs.VirtualFile, com.intellij.psi.PsiFile>? {
        val await = panel.prewarmAwaitForTest()
        assertNotNull("面板构造期应已发起预热", await)
        val deadline = System.currentTimeMillis() + budgetMs
        while (System.currentTimeMillis() < deadline && !await!!.isDone()) {
            UIUtil.dispatchAllInvocationEvents()
            Thread.sleep(10)
        }
        UIUtil.dispatchAllInvocationEvents()
        assertTrue("预热应在预算内出结果（成功或失败）", await!!.isDone())
        return await.joinOrNull()
    }

    private fun editorDocumentFile(panel: SqlPreviewPanel): com.intellij.openapi.vfs.VirtualFile? {
        val doc = panel.editorForTest()?.document ?: return null
        return FileDocumentManager.getInstance().getFile(doc)
    }

    /** 把面板推进确定性降级态（见类 KDoc），并断言前置成立。 */
    private fun degradePanel(panel: SqlPreviewPanel) {
        panel.showResult(result())
        UIUtil.dispatchAllInvocationEvents()
        panel.releaseEditorForTest()
        panel.showResult(result())
        UIUtil.dispatchAllInvocationEvents()
        assertNull("降级文档不应挂在项目内临时文件上（前置）", editorDocumentFile(panel))
        assertNotNull("降级态应登记降级文档身份（换装判据的身份字段）", panel.degradedDocumentForTest())
        assertEquals(
            "降级态应挂 DEGRADED 横幅（前置）",
            DegradationBanner.Kind.DEGRADED,
            panel.degradationBannerForTest().kindForTest(),
        )
    }

    // ------------------------------------------------------------------
    // F1：迟到产物换装
    // ------------------------------------------------------------------

    fun test迟到产物到达_自动换装并清横幅() {
        // 产物由第二个面板提供：降级态那条路线里 releaseEditor 会删掉本面板的临时文件，
        // 用已删除的文件做「迟到产物」不成立——donor 的产物是磁盘上真实存在的文件。
        val donor = SqlPreviewPanel(project)
        val panel = SqlPreviewPanel(project)
        try {
            val product = awaitPrewarmProduct(donor)
            assertNotNull(
                "headless 下项目内预览文件应预热成功（失败=环境问题，不是本用例的通过条件）",
                product,
            )
            degradePanel(panel)

            // 真实胶水等价驱动：预热线程发布时 `PrewarmAwait.complete` 返回 false（消费侧已放弃）
            // → 面板登记的迟到通道被触发 → 回 EDT 换装。
            val await = panel.prewarmAwaitForTest()
            assertNotNull(await)
            assertFalse(
                "消费侧已放弃后 complete 必须返回 false（胶水据此走迟到通道）",
                await!!.complete(product!!),
            )
            UIUtil.dispatchAllInvocationEvents()

            val file = editorDocumentFile(panel)
            assertNotNull("迟到产物到达应换装成「项目内文件背书」的编辑器（主路径），实际仍为降级文档", file)
            assertTrue("换装后的文档应挂在项目预览目录内，实际：${file!!.path}", file.path.contains("sql-visualizer"))
            assertNull("换装后降级文档身份应清空", panel.degradedDocumentForTest())
            assertNotEquals(
                "换装后应清除 DEGRADED 横幅（主路径装配统一清除）",
                DegradationBanner.Kind.DEGRADED,
                panel.degradationBannerForTest().kindForTest(),
            )
            assertNotNull("换装后 psiFile 应带真实 VFS 文件（内存 PSI 降级产物无 VFS 文件）", panel.psiFileForTest()?.virtualFile)
        } finally {
            panel.clear()
            donor.clear()
        }
    }

    fun test用户已编辑_迟到产物不换装且不丢内容() {
        val donor = SqlPreviewPanel(project)
        val panel = SqlPreviewPanel(project)
        try {
            val product = awaitPrewarmProduct(donor)
            assertNotNull("headless 下项目内预览文件应预热成功（失败=环境问题，不是本用例的通过条件）", product)
            degradePanel(panel)

            val doc = panel.editorForTest()!!.document
            WriteCommandAction.runWriteCommandAction(project) { doc.setText("SELECT 1 /* user edit */") }
            assertFalse(
                "用户编辑后文本应与插件最后写入不同（前置）",
                doc.text == panel.lastRenderedSqlForTest(),
            )

            panel.onLatePrewarmProductForTest(product!!)
            UIUtil.dispatchAllInvocationEvents()

            assertNull("用户已编辑时不得换装（不抢编辑现场）", editorDocumentFile(panel))
            assertEquals("用户文本不得被覆盖", "SELECT 1 /* user edit */", doc.text)
            assertEquals(
                "未换装时横幅应保留",
                DegradationBanner.Kind.DEGRADED,
                panel.degradationBannerForTest().kindForTest(),
            )
            assertNotNull("降级文档身份应保留（下次到达仍可换装）", panel.degradedDocumentForTest())
        } finally {
            panel.clear()
            donor.clear()
        }
    }

    fun test面板销毁后_迟到产物不换装不抛() {
        val donor = SqlPreviewPanel(project)
        val panel = SqlPreviewPanel(project)
        try {
            val product = awaitPrewarmProduct(donor)
            assertNotNull("headless 下项目内预览文件应预热成功（失败=环境问题，不是本用例的通过条件）", product)
            degradePanel(panel)
            panel.dispose()

            // 不得抛（面板销毁后预热线程可能才发布产物）
            panel.onLatePrewarmProductForTest(product!!)
            UIUtil.dispatchAllInvocationEvents()
            assertNull("面板已销毁时不得换装", editorDocumentFile(panel))
        } finally {
            // dispose() 不释放编辑器，归还职责在面板侧（fixture tearDown 会断言「无未释放 Editor」）
            panel.releaseEditorForTest()
            donor.clear()
        }
    }

    // ------------------------------------------------------------------
    // F2：横幅「重试」动作
    // ------------------------------------------------------------------

    fun test降级横幅重试动作_点击后重新预热并换装() {
        val panel = SqlPreviewPanel(project)
        try {
            degradePanel(panel)
            val banner = panel.degradationBannerForTest()
            assertTrue("降级横幅应显示「重试」动作（onRetry 已接上）", banner.retryVisibleForTest())
            assertEquals(
                "动作文案应按当前语言解析 i18n 键",
                MpSqlVisualizerBundle.message("panel.banner.retry"),
                banner.retryTextForTest(),
            )

            banner.clickRetryForTest() // 等价于用户点击
            val await = panel.prewarmAwaitForTest()
            assertNotNull("重试应重新臂一轮预热", await)
            val deadline = System.currentTimeMillis() + 60_000
            while (System.currentTimeMillis() < deadline && !await!!.isDone()) {
                UIUtil.dispatchAllInvocationEvents()
                Thread.sleep(10)
            }
            UIUtil.dispatchAllInvocationEvents()
            assertTrue("重试轮预热应在预算内出结果", await!!.isDone())

            val file = editorDocumentFile(panel)
            assertNotNull("重试就绪后应换装成「项目内文件背书」的编辑器", file)
            assertNotEquals(
                "换装后应清除 DEGRADED 横幅",
                DegradationBanner.Kind.DEGRADED,
                banner.kindForTest(),
            )
        } finally {
            panel.clear()
        }
    }
}
