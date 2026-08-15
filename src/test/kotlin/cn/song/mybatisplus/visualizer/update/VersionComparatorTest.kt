package cn.song.mybatisplus.visualizer.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 版本比较单测（T-M28-01）：点分数字比较 + 异常兜底，纯 JUnit（无平台依赖）。
 */
class VersionComparatorTest {

    @Test
    fun equalVersions() {
        assertEquals(0, VersionComparator.compare("2026.8.14", "2026.8.14"))
        assertEquals(0, VersionComparator.compare("2026.8", "2026.8"))
    }

    @Test
    fun newerAndOlder() {
        assertTrue(VersionComparator.compare("2026.8.15", "2026.8.14") > 0)
        assertTrue(VersionComparator.compare("2026.8.14", "2026.8.15") < 0)
        assertTrue(VersionComparator.compare("2027.1.1", "2026.12.31") > 0)
    }

    @Test
    fun differentSegmentCount() {
        // 缺段按空串兜底：a.b 视为 a.b.（比 a.b.1 小）
        assertTrue(VersionComparator.compare("2026.8.14", "2026.8.14.1") < 0)
        assertTrue(VersionComparator.compare("2026.8.14.1", "2026.8.14") > 0)
    }

    @Test
    fun nonNumericSegmentsFallbackToString() {
        assertTrue(VersionComparator.compare("2026.8.a", "2026.8.b") < 0)
        assertTrue(VersionComparator.compare("2026.8.b", "2026.8.a") > 0)
        // 混合数字/非数字段：同段数值优先
        assertTrue(VersionComparator.compare("2026.8.9", "2026.8.10") < 0)
    }

    @Test
    fun nullHandling() {
        assertEquals(0, VersionComparator.compare(null, null))
        assertTrue(VersionComparator.compare(null, "2026.8.14") < 0)
        assertTrue(VersionComparator.compare("2026.8.14", null) > 0)
    }

    @Test
    fun malformedVersionsDoNotThrow() {
        assertEquals(0, VersionComparator.compare("", ""))
        assertTrue(VersionComparator.compare("...", "2026.8.14") < 0)
        assertTrue(VersionComparator.compare("abc", "abc") == 0)
    }

    @Test
    fun isRecognizable() {
        assertTrue(VersionComparator.isRecognizable("2026.8.14"))
        assertTrue(!VersionComparator.isRecognizable(null))
        assertTrue(!VersionComparator.isRecognizable(""))
        assertTrue(!VersionComparator.isRecognizable("   "))
    }
}
