package cn.song.mybatisplus.visualizer.util

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * P-05.1：[CaseFormatUtil] 驼峰 / 下划线互转单测。
 * 纯函数、无依赖，JUnit 4 即可。
 */
class CaseFormatUtilTest {

    // ------------------------------------------------------------------
    // camelToSnake
    // ------------------------------------------------------------------

    @Test
    fun testCamelToSnake_simple() {
        assertEquals("user_name", CaseFormatUtil.camelToSnake("userName"))
    }

    @Test
    fun testCamelToSnake_multipleWords() {
        assertEquals("create_time", CaseFormatUtil.camelToSnake("createTime"))
    }

    @Test
    fun testCamelToSnake_noChange() {
        // 纯小写无大写 → 不变
        assertEquals("name", CaseFormatUtil.camelToSnake("name"))
    }

    @Test
    fun testCamelToSnake_withNumbers() {
        // user1Name：驼峰边界在 1N 之间 → user1_name
        assertEquals("user1_name", CaseFormatUtil.camelToSnake("user1Name"))
    }

    // ------------------------------------------------------------------
    // snakeToCamel
    // ------------------------------------------------------------------

    @Test
    fun testSnakeToCamel_simple() {
        assertEquals("userName", CaseFormatUtil.snakeToCamel("user_name"))
    }

    @Test
    fun testSnakeToCamel_multipleWords() {
        assertEquals("createTime", CaseFormatUtil.snakeToCamel("create_time"))
    }

    @Test
    fun testSnakeToCamel_noChange() {
        // 纯小写无下划线 → 不变
        assertEquals("name", CaseFormatUtil.snakeToCamel("name"))
    }

    @Test
    fun testSnakeToCamel_withNumbers() {
        assertEquals("user1Name", CaseFormatUtil.snakeToCamel("user_1_name"))
    }

    // ------------------------------------------------------------------
    // 互转对称性
    // ------------------------------------------------------------------

    @Test
    fun testRoundTrip_camelToSnakeToCamel() {
        assertEquals("userName", CaseFormatUtil.snakeToCamel(CaseFormatUtil.camelToSnake("userName")))
    }

    @Test
    fun testRoundTrip_snakeToCamelToSnake() {
        assertEquals("create_time", CaseFormatUtil.camelToSnake(CaseFormatUtil.snakeToCamel("create_time")))
    }
}
