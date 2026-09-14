package cn.song.mybatisplus.visualizer.feedback

import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import javax.swing.JScrollPane

/**
 * 报告对话框布局与校验契约（T-FeedbackDlg）：buildCenterPanel/validateForm 纯函数 +
 * ReportFormFields 控件构造，headless 零 DialogWrapper peer 依赖。
 * 锁定三缺陷防回归：弹窗过宽（标题列数误用 TITLE_MAX）、占位提示缺失、不可拖拽（weighty=0）。
 */
class ErrorReportDialogLayoutTest {

    @Test
    fun `标题与描述空文本占位提示就位`() {
        val fields = ReportFormFields()
        assertEquals(
            MpSqlVisualizerBundle.message("error.report.dialog.subject.placeholder"),
            fields.titleField.emptyText.text,
        )
        assertEquals(
            MpSqlVisualizerBundle.message("error.report.dialog.description.placeholder"),
            fields.descriptionArea.emptyText.text,
        )
    }

    @Test
    fun `中心面板首选宽度收敛防回归`() {
        val panel = ErrorReportDialog.buildCenterPanel(ReportFormFields())
        assertTrue(
            "面板首选宽应≤800px（标题 200 列旧实现 >1200px 必炸），实际 ${panel.preferredSize.width}",
            panel.preferredSize.width <= 800,
        )
    }

    @Test
    fun `描述与堆栈行纵向随拖拽伸缩`() {
        val fields = ReportFormFields()
        val panel = ErrorReportDialog.buildCenterPanel(fields)
        val layout = panel.layout as GridBagLayout
        val stretchRows = panel.components.filter {
            val gbc = layout.getConstraints(it)
            gbc.weighty == 1.0 && gbc.fill == GridBagConstraints.BOTH
        }
        assertEquals("描述/堆栈/批次三段应 weighty=1 + BOTH，实际 $stretchRows", 3, stretchRows.size)
        assertTrue("堆栈预览容器行应可伸缩", fields.stackPreviewContainer in stretchRows)
        val descScroll = stretchRows.filterIsInstance<JScrollPane>()
            .singleOrNull { it.viewport.view === fields.descriptionArea }
        assertNotNull("描述滚动面板行应可伸缩", descScroll)
    }

    @Test
    fun `校验_标题5到200边界`() {
        assertEquals(ErrorReportDialog.VALIDATION_TITLE_KEY, ErrorReportDialog.validateForm("abcd", "0123456789"))
        assertNull(ErrorReportDialog.validateForm("abcde", "0123456789"))
        assertNull(ErrorReportDialog.validateForm("  abcde  ", "0123456789"))
        assertNull(ErrorReportDialog.validateForm("a".repeat(200), "0123456789"))
        assertEquals(
            ErrorReportDialog.VALIDATION_TITLE_KEY,
            ErrorReportDialog.validateForm("a".repeat(201), "0123456789"),
        )
    }

    @Test
    fun `校验_描述10字下限`() {
        assertEquals(ErrorReportDialog.VALIDATION_DESCRIPTION_KEY, ErrorReportDialog.validateForm("abcde", "012345678"))
        assertEquals(ErrorReportDialog.VALIDATION_DESCRIPTION_KEY, ErrorReportDialog.validateForm("abcde", ""))
        assertNull(ErrorReportDialog.validateForm("abcde", "0123456789"))
        assertNull(ErrorReportDialog.validateForm("abcde", "  0123456789  "))
    }

    @Test
    fun `校验与占位文案键都有bundle落地`() {
        val keys = arrayOf(
            ErrorReportDialog.VALIDATION_TITLE_KEY,
            ErrorReportDialog.VALIDATION_DESCRIPTION_KEY,
            "error.report.dialog.subject.placeholder",
            "error.report.dialog.description.placeholder",
        )
        for (key in keys) {
            assertNotEquals("键 $key 缺 bundle 文案（会原样显示键名）", key, MpSqlVisualizerBundle.message(key))
        }
    }

    @Test
    fun `手动形态堆栈导入控件默认态`() {
        // 字段类默认=错误形态基线（只读预览、导入按钮隐藏）；手动形态由对话框 init 翻转，
        // 这里锁定默认态与 T-FeedbackDlg3 新键，init 翻转行为留真机走查
        val fields = ReportFormFields()
        assertFalse("堆栈区默认只读（错误形态基线）", fields.stackPreview.isEditable)
        assertFalse("导入按钮默认隐藏（错误形态）", fields.stackImportButton.isVisible)
        for (key in arrayOf(
                "error.report.dialog.stackImport",
                "error.report.dialog.stackImport.tip",
                "error.report.dialog.stackImport.empty",
                "error.report.dialog.stackPreview.placeholder",
            )
        ) {
            assertNotEquals("键 $key 缺 bundle 文案（会原样显示键名）", key, MpSqlVisualizerBundle.message(key))
        }
    }

    @Test
    fun `堆栈行显隐窗口补偿量_勾选撑高取消缩回`() {
        // 勾选：描述行损失 Δ=(slack+S)/2，撑高 2Δ=slack+S 让描述行分毫不差
        assertEquals(218, ErrorReportDialog.toggleResizeDelta(true, 700, 670, 188, 0))
        // 窗口无纵向富余（贴首选高）：只补堆栈行首选高
        assertEquals(188, ErrorReportDialog.toggleResizeDelta(true, 670, 670, 188, 0))
        // 取消：按堆栈行实占高（含富余分配）整块释放
        assertEquals(-280, ErrorReportDialog.toggleResizeDelta(false, 918, 670, -188, 280))
        // 取消且堆栈行未布局（实占高0）：退化为 preferred 缩减量的负值
        assertEquals(-188, ErrorReportDialog.toggleResizeDelta(false, 918, 670, -188, 0))
        // 取消但测不到任何释放量：不动窗口
        assertEquals(0, ErrorReportDialog.toggleResizeDelta(false, 670, 670, 0, 0))
    }

    // ---------- T-FeedbackErr：提交状态机（状态行 / 结果文案） ----------

    @Test
    fun `状态行在面板最底部且不参与纵向伸缩`() {
        val fields = ReportFormFields()
        val panel = ErrorReportDialog.buildCenterPanel(fields)
        // 状态行 = 包装行（文本区居中 + 复制按钮居东），包装行本身是中心面板的直接子组件
        val row = panel.components.filterIsInstance<java.awt.Container>()
            .singleOrNull { fields.statusLabel in it.components }
        assertNotNull("状态行必须在中心面板里（提交中/失败原因就地可见）", row)
        assertTrue("复制按钮应在状态行内", fields.statusCopyButton in row!!.components)
        val layout = panel.layout as GridBagLayout
        val gbc = layout.getConstraints(row)
        assertEquals("状态行不应参与纵向伸缩（避免与描述/堆栈行争空间）", 0.0, gbc.weighty, 0.0)
    }

    @Test
    fun `失败状态行含服务端原因与重试引导`() {
        val text = ErrorReportDialog.failureStatusText("标题长度需在 5-200 字符之间")
        assertTrue("TEXT=<$text>", text.contains("标题长度需在 5-200 字符之间"))
        assertNotEquals(
            "失败文案须有 bundle 落地（不能原样显示键名）",
            "error.report.dialog.status.failed",
            MpSqlVisualizerBundle.message("error.report.dialog.status.failed", "原因"),
        )
    }

    @Test
    fun `失败状态行附件说明为中性表述`() {
        // 真机第二轮：失败现场同屏出现「提交失败」与「帖子已提交」自相矛盾——状态行只在失败时
        // 出现，附件说明必须中性（不得含「已提交」字样）
        val text = ErrorReportDialog.failureStatusText("请求体不是合法 JSON", "附件超过 5MB，请压缩后上传")
        assertTrue("TEXT=<$text>", text.contains("请求体不是合法 JSON"))
        assertTrue("TEXT=<$text>", text.contains("附件超过 5MB，请压缩后上传"))
        assertFalse("失败语境不得宣称已提交", text.contains("已提交"))
        val noWarning = ErrorReportDialog.failureStatusText("原因", null)
        assertFalse("无 warning 时不应出现附件说明", noWarning.contains("附件"))
    }

    @Test
    fun `复制失败信息按钮就位且文案走bundle`() {
        val fields = ReportFormFields()
        val row = ErrorReportDialog.buildStatusRow(fields)
        assertTrue("复制按钮应在状态行内", fields.statusCopyButton in row.components)
        assertFalse("不能原样显示键名", fields.statusCopyButton.text.startsWith("error.report.dialog."))
        assertNotEquals(
            "复制按钮文案须有 bundle 落地",
            MpSqlVisualizerBundle.message("error.report.dialog.status.copy"),
            "error.report.dialog.status.copy",
        )
        // 处理器默认空实现（对话框注入真实复制逻辑），headless 可安全触发
        fields.statusCopyHandler()
    }

    @Test
    fun `状态行长文本不撑宽面板`() {
        // 真机第二轮：JBLabel 单行渲染长失败消息（Gson 排障链接）把对话框撑到 ~1900px；
        // 状态行改可换行文本区后，preferred 宽度必须被列数封顶
        val fields = ReportFormFields()
        val longText = "提交失败：服务端返回了非 JSON 响应（疑似代理/CDN 拦截页或网关错误页）：" + "很长的诊断片段".repeat(20)
        fields.statusLabel.text = longText
        val panel = ErrorReportDialog.buildCenterPanel(fields)
        assertTrue(
            "面板首选宽应≤800px（实际 ${panel.preferredSize.width}，状态行 ${fields.statusLabel.preferredSize.width}）",
            panel.preferredSize.width <= 800,
        )
        assertTrue("状态行应可换行（lineWrap）", fields.statusLabel.lineWrap)
        assertFalse("状态行不可编辑", fields.statusLabel.isEditable)
    }

    @Test
    fun `提交中状态行文案走bundle`() {
        val text = MpSqlVisualizerBundle.message("error.report.dialog.status.submitting")
        assertNotEquals("键缺 bundle 文案会原样显示键名", "error.report.dialog.status.submitting", text)
        assertNotEquals(
            "提交按钮文案缺 bundle 落地",
            "error.report.dialog.submitting",
            MpSqlVisualizerBundle.message("error.report.dialog.submitting"),
        )
    }

    /**
     * 提交状态机的 headless 契约（T-FeedbackErr）：DialogWrapper 构造断言 EDT
     *（`ThreadingAssertions.assertEventDispatchThread`，真机实测抛 RuntimeExceptionWithAttachments，
     * 与本项目「EDT 慢操作断言」同源），故状态机逻辑抽为纯函数 [submitStatusText] 直接锁定，
     * 对话框实例上的 beginSubmitting/finishSubmitting 行为留真机走查。
     */
    @Test
    fun `提交状态机文案_提交中与失败与附件降级`() {
        // 提交中
        assertEquals(
            MpSqlVisualizerBundle.message("error.report.dialog.status.submitting"),
            ErrorReportDialog.submitStatusText(ErrorReportDialog.Companion.SubmitPhase.SUBMITTING, null, null),
        )
        // 成功（关窗路径）无状态文案
        assertNull(ErrorReportDialog.submitStatusText(ErrorReportDialog.Companion.SubmitPhase.SUCCESS, null, null))
        // 失败：服务端原因 + 重试引导；附件降级另起一行
        val failed = ErrorReportDialog.submitStatusText(
            ErrorReportDialog.Companion.SubmitPhase.FAILED,
            "标题长度需在 5-200 字符之间",
            "附件超过 5MB，请压缩后上传",
        )
        assertTrue("TEXT=<$failed>", failed!!.contains("标题长度需在 5-200 字符之间"))
        assertTrue("TEXT=<$failed>", failed.contains("附件超过 5MB，请压缩后上传"))
        assertNull(
            "warning 缺省时不应出现附件说明",
            ErrorReportDialog.submitStatusText(ErrorReportDialog.Companion.SubmitPhase.FAILED, "原因", null)
                ?.takeIf { it.contains("附件") },
        )
    }

    // ---------- T-FeedbackBatch：批次分节与载荷重写 ----------

    @Test
    fun `批次分节默认隐藏且面板含该行`() {
        val fields = ReportFormFields()
        val panel = ErrorReportDialog.buildCenterPanel(fields)
        assertTrue("批次分节应在中心面板内", fields.batchSection in panel.components)
        assertFalse("无批次条目时默认隐藏", fields.batchSection.isVisible)
        assertNotNull("批次复选框容器非空", fields.batchList)
    }

    @Test
    fun `批次条目文案为简单类名加消息首行`() {
        val item = BatchSubmitter.BatchItem(
            "cn.song.mybatisplus.visualizer.log.Boom",
            "boom\n第二行",
            "stack",
        )
        assertEquals("Boom: boom", ErrorReportDialog.batchItemLabel(item))
        val long = ErrorReportDialog.batchItemLabel(BatchSubmitter.BatchItem("a.B", "x".repeat(300), null))
        assertTrue("len=${long.length}", long.length <= 91)
    }

    @Test
    fun `多条勾选_标题描述附件改为批次形态`() {
        val base = FeedbackApi.Submission(
            request = FeedbackApi.PostRequest(
                title = "RuntimeException: boom 1", description = "boom 1", type = 2, category = "other",
                submitterName = null, submitterEmail = null, isAnonymous = true,
                source = FeedbackApi.SOURCE_IDE, ideVersion = null, pluginVersion = null, attachments = emptyList(),
            ),
            stackAttachment = FeedbackApi.StackAttachment(FeedbackApi.STACK_ATTACHMENT_NAME, "x".toByteArray()),
        )
        val items = (1..3).map {
            BatchSubmitter.BatchItem("cn.song.mybatisplus.visualizer.Boom$it", "boom $it", "stack $it")
        }
        val composed = ErrorReportDialog.composeSubmission(base, items)
        assertTrue("TITLE=<${composed.request.title}>", composed.request.title.endsWith("（等 3 条异常）"))
        assertTrue(composed.request.description.contains("#2 Boom2: boom 2"))
        assertTrue(String(composed.stackAttachment!!.content, Charsets.UTF_8).contains("----- #3 -----"))
    }

    @Test
    fun `单条或空勾选_载荷原样`() {
        val base = FeedbackApi.Submission(
            request = FeedbackApi.PostRequest(
                title = "RuntimeException: boom", description = "boom", type = 2, category = "other",
                submitterName = null, submitterEmail = null, isAnonymous = true,
                source = FeedbackApi.SOURCE_IDE, ideVersion = null, pluginVersion = null, attachments = emptyList(),
            ),
            stackAttachment = null,
        )
        assertEquals(base, ErrorReportDialog.composeSubmission(base, emptyList()))
        assertEquals(
            base,
            ErrorReportDialog.composeSubmission(
                base,
                listOf(BatchSubmitter.BatchItem("a.B", "boom", "stack")),
            ),
        )
    }
}