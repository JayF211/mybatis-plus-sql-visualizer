package cn.song.mybatisplus.visualizer.update;

import org.junit.Assert.assertEquals;
import org.junit.Assert.assertFalse;
import org.junit.Assert.assertTrue;
import org.junit.Test;

/**
 * 版本忽略纯函数单测（内测通道改造）：isVersionIgnored / ignoreVersion 全分支覆盖。
 */
public class VersionIgnoreTest {

    // ---------- isVersionIgnored ----------

    @Test
    public void isVersionIgnored_foundInList() {
        assertTrue(VersionIgnore.isVersionIgnored("2026.8.22-beta.1", "2026.8.22-beta.1;2026.8.22"));
    }

    @Test
    public void isVersionIgnored_notInList() {
        assertFalse(VersionIgnore.isVersionIgnored("2026.8.23-beta.1", "2026.8.22-beta.1;2026.8.22"));
    }

    @Test
    public void isVersionIgnored_emptyList() {
        assertFalse(VersionIgnore.isVersionIgnored("2026.8.22-beta.1", ""));
    }

    @Test
    public void isVersionIgnored_blankVersion() {
        assertFalse(VersionIgnore.isVersionIgnored("", "2026.8.22-beta.1"));
    }

    @Test
    public void isVersionIgnored_handlesWhitespace() {
        assertTrue(VersionIgnore.isVersionIgnored("2026.8.22-beta.1", " 2026.8.22-beta.1 ; 2026.8.22 "));
    }

    @Test
    public void isVersionIgnored_singleItem() {
        assertTrue(VersionIgnore.isVersionIgnored("2026.8.22", "2026.8.22"));
    }

    // ---------- ignoreVersion ----------

    @Test
    public void ignoreVersion_addsToEmpty() {
        assertEquals("2026.8.22-beta.1", VersionIgnore.ignoreVersion("2026.8.22-beta.1", ""));
    }

    @Test
    public void ignoreVersion_appendsToList() {
        assertEquals(
                "2026.8.22-beta.1;2026.8.22",
                VersionIgnore.ignoreVersion("2026.8.22", "2026.8.22-beta.1"));
    }

    @Test
    public void ignoreVersion_idempotent() {
        String list = "2026.8.22-beta.1;2026.8.22";
        assertEquals(list, VersionIgnore.ignoreVersion("2026.8.22-beta.1", list));
    }

    @Test
    public void ignoreVersion_skipsBlank() {
        assertEquals("2026.8.22-beta.1", VersionIgnore.ignoreVersion("", "2026.8.22-beta.1"));
    }

    @Test
    public void ignoreVersion_addsToEmptyList() {
        // 空列表 + 非空版本 → 添加该版本
        assertEquals("2026.8.22-beta.1", VersionIgnore.ignoreVersion("2026.8.22-beta.1", ""));
    }
}
