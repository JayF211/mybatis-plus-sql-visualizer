package cn.song.mybatisplus.visualizer.log

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * O-147 W3：跳转 MissingSource 反馈分流判据（[isConsoleFallbackWithoutSource] 纯函数）——
 * 控制台回落通道产出（含融合后仍无位置）的条目走轻提示说明；旧条目 / agent 缺源码维持既有
 * 「开启源码捕获」引导（保守：UNKNOWN 不基于猜测改行为）。
 */
class LogEntryJumpOpsConsoleFallbackTest {

    private fun entry(
        provenance: CaptureProvenance,
        sourceFile: String? = null,
        candidates: List<Pair<String, Int>> = emptyList(),
    ) = LogSqlEntry(
        rawSql = "SELECT 1",
        restoredSql = "SELECT 1",
        params = emptyList(),
        sourceFile = sourceFile,
        sourceCandidates = candidates,
        captureProvenance = provenance,
    )

    @Test
    fun `控制台回落通道无源码走轻提示`() {
        assertTrue(isConsoleFallbackWithoutSource(entry(CaptureProvenance.CONSOLE_BRIDGE)))
        assertTrue(isConsoleFallbackWithoutSource(entry(CaptureProvenance.CONSOLE_EP)))
    }

    @Test
    fun `融合后仍无源码走轻提示`() {
        // 两通道都没带位置：仍是无处可跳的回落形态，不给误导性引导
        assertTrue(isConsoleFallbackWithoutSource(entry(CaptureProvenance.MERGED)))
    }

    @Test
    fun `旧条目与agent条目维持既有引导`() {
        assertFalse(isConsoleFallbackWithoutSource(entry(CaptureProvenance.UNKNOWN)))
        assertFalse(isConsoleFallbackWithoutSource(entry(CaptureProvenance.AGENT)))
    }

    @Test
    fun `有源码或有候选不命中`() {
        assertFalse(isConsoleFallbackWithoutSource(entry(CaptureProvenance.CONSOLE_BRIDGE, sourceFile = "a/A.java")))
        assertFalse(isConsoleFallbackWithoutSource(entry(CaptureProvenance.MERGED, candidates = listOf("b/B.java" to 3))))
    }
}
