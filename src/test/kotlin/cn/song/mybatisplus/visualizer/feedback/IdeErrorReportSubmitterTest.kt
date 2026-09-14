package cn.song.mybatisplus.visualizer.feedback

import com.intellij.openapi.diagnostic.IdeaLoggingEvent
import com.intellij.openapi.diagnostic.SubmittedReportInfo
import com.intellij.util.Consumer
import java.util.function.Predicate
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

    /** 流程 seam 注入（SAM lambda 构造——k2j 互操作适配，抽到非 test 前缀私有方法）。 */
    private fun flowSpy(flowCalled: BooleanArray): IdeErrorReportSubmitter.OpenReportFlow =
        IdeErrorReportSubmitter.OpenReportFlow { _, _, _, _ -> flowCalled[0] = true }

    /** 去重 seam 注入（SAM lambda 构造——k2j 互操作适配，抽到非 test 前缀私有方法）。 */
    private fun fixedGate(duplicate: Boolean): Predicate<String> = Predicate { duplicate }

    @Test
    fun `无事件返回false且不弹框`() {
        val submitter = IdeErrorReportSubmitter()
        val flowCalled = booleanArrayOf(false)
        submitter.openReportFlow = flowSpy(flowCalled)
        assertFalse(submitter.submit(emptyArray(), null, javax.swing.JPanel(), Consumer { }))
        assertFalse(flowCalled[0])
    }

    @Test
    fun `指纹命中去重_直接DUPLICATE回调且不弹框`() {
        val submitter = IdeErrorReportSubmitter()
        val flowCalled = booleanArrayOf(false)
        submitter.openReportFlow = flowSpy(flowCalled)
        submitter.dedupeGate = fixedGate(true)
        var received: SubmittedReportInfo? = null
        val accepted = submitter.submit(arrayOf(event()), null, javax.swing.JPanel(), Consumer { received = it })
        assertTrue(accepted)
        assertFalse(flowCalled[0])
        assertEquals(SubmittedReportInfo.SubmissionStatus.DUPLICATE, received?.status)
    }

    @Test
    fun `正常路径_流程收到预填堆栈与指纹`() {
        val submitter = IdeErrorReportSubmitter()
        submitter.dedupeGate = fixedGate(false)
        var capturedPrefill: ErrorReportComposer.Prefill? = null
        var capturedStack: String? = null
        var capturedFingerprint: String? = null
        submitter.openReportFlow = IdeErrorReportSubmitter.OpenReportFlow { prefill, stackText, fingerprint, _ ->
            capturedPrefill = prefill
            capturedStack = stackText
            capturedFingerprint = fingerprint
        }
        val accepted = submitter.submit(arrayOf(event()), null, javax.swing.JPanel(), Consumer { })
        assertTrue(accepted)
        assertEquals("RuntimeException: boom", capturedPrefill?.title)
        assertTrue(capturedStack?.contains("Foo.bar") == true)
        assertEquals(ErrorFingerprint.of(throwable.javaClass.name, throwable.stackTraceToString()), capturedFingerprint)
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
    fun `报告按钮与隐私文案来自bundle非键名`() {
        val submitter = IdeErrorReportSubmitter()
        assertFalse(submitter.reportActionText.isNullOrBlank())
        assertFalse((submitter.reportActionText ?: "").startsWith("error.report."))
        assertFalse((submitter.privacyNoticeText ?: "").startsWith("error.report."))
    }
}
