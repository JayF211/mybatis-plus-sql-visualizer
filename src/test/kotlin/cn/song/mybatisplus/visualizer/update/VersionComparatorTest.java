package cn.song.mybatisplus.visualizer.update;

import org.junit.Assert.assertEquals;
import org.junit.Assert.assertTrue;
import org.junit.Test;

/**
 * 版本比较单测（T-M28-01）：点分数字比较 + 异常兜底，纯 JUnit（无平台依赖）。
 */
public class VersionComparatorTest {

    @Test
    public void equalVersions() {
        assertEquals(0, VersionComparator.INSTANCE.compare("2026.8.14", "2026.8.14"));
        assertEquals(0, VersionComparator.INSTANCE.compare("2026.8", "2026.8"));
    }

    @Test
    public void newerAndOlder() {
        assertTrue(VersionComparator.INSTANCE.compare("2026.8.15", "2026.8.14") > 0);
        assertTrue(VersionComparator.INSTANCE.compare("2026.8.14", "2026.8.15") < 0);
        assertTrue(VersionComparator.INSTANCE.compare("2027.1.1", "2026.12.31") > 0);
    }

    @Test
    public void differentSegmentCount() {
        // 缺段按空串兜底：a.b 视为 a.b.（比 a.b.1 小）
        assertTrue(VersionComparator.INSTANCE.compare("2026.8.14", "2026.8.14.1") < 0);
        assertTrue(VersionComparator.INSTANCE.compare("2026.8.14.1", "2026.8.14") > 0);
    }

    @Test
    public void nonNumericSegmentsFallbackToString() {
        assertTrue(VersionComparator.INSTANCE.compare("2026.8.a", "2026.8.b") < 0);
        assertTrue(VersionComparator.INSTANCE.compare("2026.8.b", "2026.8.a") > 0);
        // 混合数字/非数字段：同段数值优先
        assertTrue(VersionComparator.INSTANCE.compare("2026.8.9", "2026.8.10") < 0);
    }

    @Test
    public void nullHandling() {
        assertEquals(0, VersionComparator.INSTANCE.compare(null, null));
        assertTrue(VersionComparator.INSTANCE.compare(null, "2026.8.14") < 0);
        assertTrue(VersionComparator.INSTANCE.compare("2026.8.14", null) > 0);
    }

    @Test
    public void malformedVersionsDoNotThrow() {
        assertEquals(0, VersionComparator.INSTANCE.compare("", ""));
        assertTrue(VersionComparator.INSTANCE.compare("...", "2026.8.14") < 0);
        assertTrue(VersionComparator.INSTANCE.compare("abc", "abc") == 0);
    }

    @Test
    public void isRecognizable() {
        assertTrue(VersionComparator.INSTANCE.isRecognizable("2026.8.14"));
        assertTrue(!VersionComparator.INSTANCE.isRecognizable(null));
        assertTrue(!VersionComparator.INSTANCE.isRecognizable(""));
        assertTrue(!VersionComparator.INSTANCE.isRecognizable("   "));
    }
}
