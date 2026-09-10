package cn.song.mybatisplus.visualizer.update;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

/**
 * 版本比较单测（T-M28-01 → T-VersionUtil）：委托 IDEA 内置 VersionComparatorUtil 后的
 * 对外契约——纯 JUnit（util_rt.jar 在测试 classpath，无需平台沙盒）。
 */
public class VersionComparatorTest {

    @Test
    public void equalVersions() throws Exception {
        assertEquals(0, VersionComparator.INSTANCE.compare("2026.8.14", "2026.8.14"));
        assertEquals(0, VersionComparator.INSTANCE.compare("2026.8", "2026.8"));
    }

    @Test
    public void newerAndOlder() throws Exception {
        assertTrue(VersionComparator.INSTANCE.compare("2026.8.15", "2026.8.14") > 0);
        assertTrue(VersionComparator.INSTANCE.compare("2026.8.14", "2026.8.15") < 0);
        assertTrue(VersionComparator.INSTANCE.compare("2027.1.1", "2026.12.31") > 0);
    }

    @Test
    public void differentSegmentCount() throws Exception {
        // 缺段视为更小：a.b 比 a.b.1 小
        assertTrue(VersionComparator.INSTANCE.compare("2026.8.14", "2026.8.14.1") < 0);
        assertTrue(VersionComparator.INSTANCE.compare("2026.8.14.1", "2026.8.14") > 0);
    }

    @Test
    public void betaPrereleaseLowerThanRelease() throws Exception {
        // T-VersionUtil 核心修复点：同日 beta 必须判小于正式版（自研点分比较曾判大）
        assertTrue(VersionComparator.INSTANCE.compare("2026.9.10-beta.1", "2026.9.10") < 0);
        assertTrue(VersionComparator.INSTANCE.compare("2026.9.10", "2026.9.10-beta.1") > 0);
    }

    @Test
    public void betaSuffixIncrementsNumerically() throws Exception {
        assertTrue(VersionComparator.INSTANCE.compare("2026.8.22-beta.1", "2026.8.22-beta.2") < 0);
        // 数字段按数值比较：beta.10 > beta.9
        assertTrue(VersionComparator.INSTANCE.compare("2026.8.22-beta.10", "2026.8.22-beta.9") > 0);
        // 次日 beta 大于前一日正式版
        assertTrue(VersionComparator.INSTANCE.compare("2026.8.23-beta.1", "2026.8.22") > 0);
    }

    @Test
    public void nonNumericSegments() throws Exception {
        assertTrue(VersionComparator.INSTANCE.compare("2026.8.a", "2026.8.b") < 0);
        assertTrue(VersionComparator.INSTANCE.compare("2026.8.b", "2026.8.a") > 0);
        // 混合数字/非数字段：同段数值优先
        assertTrue(VersionComparator.INSTANCE.compare("2026.8.9", "2026.8.10") < 0);
    }

    @Test
    public void nullHandling() throws Exception {
        assertEquals(0, VersionComparator.INSTANCE.compare(null, null));
        assertTrue(VersionComparator.INSTANCE.compare(null, "2026.8.14") < 0);
        assertTrue(VersionComparator.INSTANCE.compare("2026.8.14", null) > 0);
    }

    @Test
    public void malformedVersionsDoNotThrow() throws Exception {
        assertEquals(0, VersionComparator.INSTANCE.compare("", ""));
        assertTrue(VersionComparator.INSTANCE.compare("...", "2026.8.14") < 0);
        assertTrue(VersionComparator.INSTANCE.compare("abc", "abc") == 0);
    }

    @Test
    public void isRecognizable() throws Exception {
        assertTrue(VersionComparator.INSTANCE.isRecognizable("2026.8.14"));
        assertTrue(VersionComparator.INSTANCE.isRecognizable("2026.9.10-beta.2"));
        assertTrue(!VersionComparator.INSTANCE.isRecognizable(null));
        assertTrue(!VersionComparator.INSTANCE.isRecognizable(""));
        assertTrue(!VersionComparator.INSTANCE.isRecognizable("   "));
    }
}
