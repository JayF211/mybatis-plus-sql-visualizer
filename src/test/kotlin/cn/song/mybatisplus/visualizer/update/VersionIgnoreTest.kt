package cn.song.mybatisplus.visualizer.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 版本忽略纯函数单测（内测通道改造）：isVersionIgnored / ignoreVersion 全分支覆盖。
 */
class VersionIgnoreTest {

    // ---------- isVersionIgnored ----------

    @Test
    fun isVersionIgnored_foundInList() {
        assertTrue(isVersionIgnored("2026.8.22-beta.1", "2026.8.22-beta.1;2026.8.22"))
    }

    @Test
    fun isVersionIgnored_notInList() {
        assertFalse(isVersionIgnored("2026.8.23-beta.1", "2026.8.22-beta.1;2026.8.22"))
    }

    @Test
    fun isVersionIgnored_emptyList() {
        assertFalse(isVersionIgnored("2026.8.22-beta.1", ""))
    }

    @Test
    fun isVersionIgnored_blankVersion() {
        assertFalse(isVersionIgnored("", "2026.8.22-beta.1"))
    }

    @Test
    fun isVersionIgnored_handlesWhitespace() {
        assertTrue(isVersionIgnored("2026.8.22-beta.1", " 2026.8.22-beta.1 ; 2026.8.22 "))
    }

    @Test
    fun isVersionIgnored_singleItem() {
        assertTrue(isVersionIgnored("2026.8.22", "2026.8.22"))
    }

    // ---------- ignoreVersion ----------

    @Test
    fun ignoreVersion_addsToEmpty() {
        assertEquals("2026.8.22-beta.1", ignoreVersion("2026.8.22-beta.1", ""))
    }

    @Test
    fun ignoreVersion_appendsToList() {
        assertEquals(
            "2026.8.22-beta.1;2026.8.22",
            ignoreVersion("2026.8.22", "2026.8.22-beta.1"),
        )
    }

    @Test
    fun ignoreVersion_idempotent() {
        val list = "2026.8.22-beta.1;2026.8.22"
        assertEquals(list, ignoreVersion("2026.8.22-beta.1", list))
    }

    @Test
    fun ignoreVersion_skipsBlank() {
        assertEquals("2026.8.22-beta.1", ignoreVersion("", "2026.8.22-beta.1"))
    }

    @Test
    fun ignoreVersion_addsToEmptyList() {
        // 空列表 + 非空版本 → 添加该版本
        assertEquals("2026.8.22-beta.1", ignoreVersion("2026.8.22-beta.1", ""))
    }
}
