package cn.song.mybatisplus.visualizer.feedback

import cn.song.mybatisplus.visualizer.share.DiagnosticShareDraft
import com.intellij.testFramework.fixtures.BasePlatformTestCase

class DiagnosticFeedbackReviewTest : BasePlatformTestCase() {
    private fun submit(dialog: ErrorReportDialog) {
        ErrorReportDialog::class.java.getDeclaredMethod("doOKAction").apply { isAccessible = true }.invoke(dialog)
    }

    fun test真实提交入口无同意或修改后零发送_最终所见即所发() {
        val sent = mutableListOf<FeedbackApi.Submission>()
        val draft = DiagnosticShareDraft(listOf(
            DiagnosticShareDraft.Section("sql", "SQL", "SELECT ? FROM identifier_1"),
            DiagnosticShareDraft.Section("summary", "Summary", "Sanitized diagnostic summary"),
        ))
        val dialog = ErrorReportDialog(null, null, null, "test-ide", "test-plugin", draft) { submission, batch, done ->
            assertTrue(batch.isEmpty())
            sent.add(submission)
            done(FeedbackApi.SubmissionOutcome(FeedbackApi.SubmitResult.Failure("Synthetic failure; no network request")))
        }
        try {
            assertTrue("长隐私说明不能撑宽已有反馈表单", dialog.diagnosticReview!!.preferredSize.width <= 800)
            submit(dialog)
            assertTrue(sent.isEmpty())
            dialog.diagnosticReview!!.consent.doClick()
            dialog.fields.descriptionArea.append("\nReviewed edit")
            submit(dialog)
            assertTrue("修改后同意必须失效", sent.isEmpty())
            dialog.diagnosticReview!!.consent.doClick()
            dialog.fields.emailField.text = "synthetic@example.invalid"
            submit(dialog)
            assertTrue("联系信息改变也需重新审阅同意", sent.isEmpty())
            dialog.diagnosticReview!!.sections.getValue("sql").doClick()
            val reviewed = dialog.fields.descriptionArea.text
            assertFalse(reviewed.contains("SELECT"))
            dialog.diagnosticReview!!.consent.doClick()
            submit(dialog)
            assertEquals(1, sent.size)
            assertEquals(reviewed, sent.single().request.description)
            assertNull(sent.single().stackAttachment)
            assertTrue(sent.single().request.attachments.isEmpty())
            submit(dialog)
            assertEquals("失败重试必须重新同意", 1, sent.size)
            dialog.diagnosticReview!!.consent.doClick()
            submit(dialog)
            assertEquals(2, sent.size)
        } finally {
            dialog.close(com.intellij.openapi.ui.DialogWrapper.CANCEL_EXIT_CODE)
        }
    }

    fun test仅打开取消诊断草稿零发送() {
        var sent = 0
        val draft = DiagnosticShareDraft(listOf(DiagnosticShareDraft.Section("summary", "Summary", "Local diagnostic draft")))
        val dialog = ErrorReportDialog(null, null, null, null, null, draft) { _, _, _ -> sent++ }
        dialog.close(com.intellij.openapi.ui.DialogWrapper.CANCEL_EXIT_CODE)
        assertEquals(0, sent)
    }
}
