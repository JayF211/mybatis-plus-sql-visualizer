package cn.song.mybatisplus.visualizer.update;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

/**
 * 版本忽略纯函数单测（内测通道改造）：isVersionIgnored / ignoreVersion 全分支覆盖。
 */
public class VersionIgnoreTest {

    // ---------- isVersionIgnored ----------

    @Test
    public void isVersionIgnored_foundInList() throws Exception {
        assertTrue(VersionIgnore.isVersionIgnored("2026.8.22-beta.1", "2026.8.22-beta.1;2026.8.22"));
    }

    @Test
    public void isVersionIgnored_notInList() throws Exception {
        assertFalse(VersionIgnore.isVersionIgnored("2026.8.23-beta.1", "2026.8.22-beta.1;2026.8.22"));
    }

    @Test
    public void isVersionIgnored_emptyList() throws Exception {
        assertFalse(VersionIgnore.isVersionIgnored("2026.8.22-beta.1", ""));
    }

    @Test
    public void isVersionIgnored_blankVersion() throws Exception {
        assertFalse(VersionIgnore.isVersionIgnored("", "2026.8.22-beta.1"));
    }

    @Test
    public void isVersionIgnored_handlesWhitespace() throws Exception {
        assertTrue(VersionIgnore.isVersionIgnored("2026.8.22-beta.1", " 2026.8.22-beta.1 ; 2026.8.22 "));
    }

    @Test
    public void isVersionIgnored_singleItem() throws Exception {
        assertTrue(VersionIgnore.isVersionIgnored("2026.8.22", "2026.8.22"));
    }

    // ---------- ignoreVersion ----------

    @Test
    public void ignoreVersion_addsToEmpty() throws Exception {
        assertEquals("2026.8.22-beta.1", VersionIgnore.ignoreVersion("2026.8.22-beta.1", ""));
    }

    @Test
    public void ignoreVersion_appendsToList() throws Exception {
        assertEquals(
                "2026.8.22-beta.1;2026.8.22",
                VersionIgnore.ignoreVersion("2026.8.22", "2026.8.22-beta.1"));
    }

    @Test
    public void ignoreVersion_idempotent() throws Exception {
        String list = "2026.8.22-beta.1;2026.8.22";
        assertEquals(list, VersionIgnore.ignoreVersion("2026.8.22-beta.1", list));
    }

    @Test
    public void ignoreVersion_skipsBlank() throws Exception {
        assertEquals("2026.8.22-beta.1", VersionIgnore.ignoreVersion("", "2026.8.22-beta.1"));
    }

    @Test
    public void ignoreVersion_addsToEmptyList() throws Exception {
        // 空列表 + 非空版本 → 添加该版本
        assertEquals("2026.8.22-beta.1", VersionIgnore.ignoreVersion("2026.8.22-beta.1", ""));
    }
}
