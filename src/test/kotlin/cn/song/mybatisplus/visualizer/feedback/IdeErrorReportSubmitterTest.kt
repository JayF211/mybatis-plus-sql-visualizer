package cn.song.mybatisplus.visualizer.feedback

import com.intellij.openapi.diagnostic.IdeaLoggingEvent
import com.intellij.openapi.diagnostic.SubmittedReportInfo
import com.intellij.util.Consumer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T-ErrReport-04：errorHandler EP 提交器编排单测（seam 注入，无 EDT/平台依赖）。
 * IdeaLoggingEvent(String, Throwable) 为公开构造（util jar）。
 */
class IdeErrorReportSubmitterTest {

    private val throwable = RuntimeException("boom")
        .apply { stackTrace = arrayOf(StackTraceElement("cn.song.mybatisplus.visualizer.log.Foo", "bar", "Foo.kt", 1)) }

    private fun event() = IdeaLoggingEvent("boom", throwable)

    @Test
    fun `无事件返回false且不弹框`() {
        val submitter = IdeErrorReportSubmitter()
        var flowCalled = false
        submitter.openReportFlow = { _, _, _, _, _ -> flowCalled = true }
        assertFalse(submitter.submit(emptyArray(), null, javax.swing.JPanel(), Consumer { }))
        assertFalse(flowCalled)
    }

    @Test
    fun `指纹命中去重_直接DUPLICATE回调且不弹框`() {
        val submitter = IdeErrorReportSubmitter()
        var flowCalled = false
        submitter.openReportFlow = { _, _, _, _, _ -> flowCalled = true }
        submitter.dedupeGate = { true }
        var received: SubmittedReportInfo? = null
        val accepted = submitter.submit(arrayOf(event()), null, javax.swing.JPanel(), Consumer { received = it })
        assertTrue(accepted)
        assertFalse(flowCalled)
        assertEquals(SubmittedReportInfo.SubmissionStatus.DUPLICATE, received?.status)
    }

    @Test
    fun `正常路径_流程收到预填堆栈与指纹`() {
        val submitter = IdeErrorReportSubmitter()
        submitter.dedupeGate = { false }
        var capturedPrefill: ErrorReportComposer.Prefill? = null
        var capturedStack: String? = null
        var capturedFingerprint: String? = null
        var capturedBatch: List<BatchSubmitter.BatchItem>? = null
        submitter.openReportFlow = { prefill, stackText, fingerprint, batch, _ ->
            capturedPrefill = prefill
            capturedStack = stackText
            capturedFingerprint = fingerprint
            capturedBatch = batch
        }
        val accepted = submitter.submit(arrayOf(event()), null, javax.swing.JPanel(), Consumer { })
        assertTrue(accepted)
        assertEquals("RuntimeException: boom", capturedPrefill?.title)
        assertTrue(capturedStack?.contains("Foo.bar") == true)
        assertEquals(ErrorFingerprint.of(throwable.javaClass.name, throwable.stackTraceToString()), capturedFingerprint)
        // T-FeedbackBatch：勾选的每条错误都作为批次条目传到对话框
        assertEquals(1, capturedBatch?.size)
        assertEquals(capturedBatch?.first()?.message, "boom")
    }

    @Test
    fun `提交结果映射_Success为NEW_ISSUE并带详情链接`() {
        val submitter = IdeErrorReportSubmitter()
        val info = submitter.reportInfo(FeedbackApi.SubmitResult.Success("FR-0042"))
        assertEquals(SubmittedReportInfo.SubmissionStatus.NEW_ISSUE, info.status)
        assertEquals("FR-0042", info.linkText)
        assertTrue(info.url.contains("postNo=FR-0042"))
    }

    @Test
    fun `提交结果映射_Failure为FAILED`() {
        val submitter = IdeErrorReportSubmitter()
        val info = submitter.reportInfo(FeedbackApi.SubmitResult.Failure("429"))
        assertEquals(SubmittedReportInfo.SubmissionStatus.FAILED, info.status)
        assertNull(info.url)
    }

    @Test
    fun `勾选多条事件_每条都成为批次条目`() {
        val submitter = IdeErrorReportSubmitter()
        val second = RuntimeException("second")
            .apply { stackTrace = arrayOf(StackTraceElement("cn.song.mybatisplus.visualizer.log.Bar", "baz", "Bar.kt", 2)) }
        val items = submitter.batchItemsOf(arrayOf(event(), IdeaLoggingEvent("second", second)))
        assertEquals(2, items.size)
        assertEquals(RuntimeException::class.java.name, items[0].title)
        assertEquals("boom", items[0].message)
        assertTrue("条目默认带堆栈", items[0].attachStack)
        assertEquals("second", items[1].message)
    }

    @Test
    fun `报告按钮与隐私文案来自bundle非键名`() {
        val submitter = IdeErrorReportSubmitter()
        assertFalse(submitter.reportActionText.isNullOrBlank())
        assertFalse(submitter.reportActionText.startsWith("error.report."))
        assertFalse(submitter.privacyNoticeText.startsWith("error.report."))
    }
}
