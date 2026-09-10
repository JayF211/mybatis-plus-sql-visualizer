package cn.song.mybatisplus.visualizer.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 版本比较单测（T-M28-01 → T-VersionUtil）：委托 IDEA 内置 VersionComparatorUtil 后的
 * 对外契约——纯 JUnit（util_rt.jar 在测试 classpath，无需平台沙盒）。
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
        // 缺段视为更小：a.b 比 a.b.1 小
        assertTrue(VersionComparator.compare("2026.8.14", "2026.8.14.1") < 0)
        assertTrue(VersionComparator.compare("2026.8.14.1", "2026.8.14") > 0)
    }

    @Test
    fun betaPrereleaseLowerThanRelease() {
        // T-VersionUtil 核心修复点：同日 beta 必须判小于正式版（自研点分比较曾判大）
        assertTrue(VersionComparator.compare("2026.9.10-beta.1", "2026.9.10") < 0)
        assertTrue(VersionComparator.compare("2026.9.10", "2026.9.10-beta.1") > 0)
    }

    @Test
    fun betaSuffixIncrementsNumerically() {
        assertTrue(VersionComparator.compare("2026.8.22-beta.1", "2026.8.22-beta.2") < 0)
        // 数字段按数值比较：beta.10 > beta.9
        assertTrue(VersionComparator.compare("2026.8.22-beta.10", "2026.8.22-beta.9") > 0)
        // 次日 beta 大于前一日正式版
        assertTrue(VersionComparator.compare("2026.8.23-beta.1", "2026.8.22") > 0)
    }

    @Test
    fun nonNumericSegments() {
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
        assertTrue(VersionComparator.isRecognizable("2026.9.10-beta.2"))
        assertTrue(!VersionComparator.isRecognizable(null))
        assertTrue(!VersionComparator.isRecognizable(""))
        assertTrue(!VersionComparator.isRecognizable("   "))
    }
}
