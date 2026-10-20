package cn.song.mybatisplus.visualizer.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * R60（T-LaneB D95/D96）：DisplayMasker 双条件规则与展示链脱敏纯逻辑测试。
 *
 * 规则对齐采集侧 agent Desensitizer.java:10-30（V112.1 落档）：
 * ①列名/参数名 hint 小写 contains 关键词表（24 项）→ "***"；
 * ②值形态兜底：18 位身份证 / 11 位手机号 → "***"；
 * 空/null 值原样返回；非敏感原样返回。
 */
class DisplayMaskerTest {

    // ---------- 条件①：列名关键词 hint ----------

    @Test
    fun test列名hint命中关键词_打码() {
        for (hint in listOf("password", "user_pwd", "ACCESS_TOKEN", "api_key", "手机号", "id_card", "secret")) {
            assertEquals("hint=$hint", "***", DisplayMasker.mask(hint, "any-value"))
        }
    }

    @Test
    fun test列名hint不命中_非敏感值原样() {
        assertEquals("abc", DisplayMasker.mask("user_name", "abc"))
        assertEquals("42", DisplayMasker.mask("age", "42"))
    }

    // ---------- 条件②：值形态兜底（无 hint） ----------

    @Test
    fun test身份证形态_无hint也打码() {
        assertEquals("***", DisplayMasker.mask(null, "110101199001011234"))
        assertEquals("***", DisplayMasker.mask("idno", "11010119900101123X"))
        assertEquals("***", DisplayMasker.mask("idno", "11010119900101123x"))
    }

    @Test
    fun test手机号形态_无hint也打码() {
        assertEquals("***", DisplayMasker.mask(null, "13812345678"))
        assertEquals("***", DisplayMasker.mask("contact", "13812345678"))
    }

    @Test
    fun test形态不匹配_原样() {
        assertEquals("12345", DisplayMasker.mask(null, "12345")) // 5 位非手机
        assertEquals("11010119900101123", DisplayMasker.mask(null, "11010119900101123")) // 17 位非身份证
        assertEquals("23812345678", DisplayMasker.mask(null, "23812345678")) // 11 位首尾非 1
        assertEquals("1381234567a", DisplayMasker.mask(null, "1381234567a")) // 含字母
    }

    @Test
    fun test空与null值_原样返回() {
        assertEquals("", DisplayMasker.mask("password", ""))
        assertNullValue()
        assertEquals("", DisplayMasker.mask(null, ""))
    }

    private fun assertNullValue() {
        assertEquals(null, DisplayMasker.mask("password", null))
    }

    // ---------- SQL 文本级脱敏（展示/导出/MCP 链共用入口） ----------

    @Test
    fun testSQL文本_引号字面量按列hint打码() {
        val sql = "SELECT id FROM user WHERE password = 'p@ss' AND name = 'tom'"
        val masked = DisplayMasker.maskSqlText(sql)
        assertTrue(masked.contains("password = '***'"))
        assertTrue(masked.contains("name = 'tom'")) // 非敏感列不打码
    }

    @Test
    fun testSQL文本_值形态兜底打码() {
        val sql = "SELECT * FROM t WHERE contact = '13812345678' OR cert_no = '110101199001011234'"
        val masked = DisplayMasker.maskSqlText(sql)
        assertTrue(masked.contains("'***'"))
        assertFalse(masked.contains("13812345678"))
        assertFalse(masked.contains("110101199001011234"))
    }

    @Test
    fun testSQL文本_IN列表逐项判() {
        // 同一敏感列（phone）的 IN 多值全部打码——对齐采集侧「该列全部参数值打码」语义
        val sql = "SELECT 1 FROM t WHERE phone IN ('13812345678', '021-8888') "
        val masked = DisplayMasker.maskSqlText(sql)
        assertTrue(masked.contains("'***', '***'"))
        assertFalse(masked.contains("13812345678"))
        assertFalse(masked.contains("021-8888"))
        // 非敏感列（city）的 IN 值保留
        val plain = DisplayMasker.maskSqlText("SELECT 1 FROM t WHERE city IN ('sh', 'bj')")
        assertEquals("SELECT 1 FROM t WHERE city IN ('sh', 'bj')", plain)
    }

    @Test
    fun testSQL文本_裸数字形态打码_非敏感数字保留() {
        val sql = "SELECT * FROM t WHERE mobile = 13812345678 AND age = 30"
        val masked = DisplayMasker.maskSqlText(sql)
        assertFalse(masked.contains("13812345678"))
        assertTrue(masked.contains("age = 30"))
    }

    @Test
    fun testSQL文本_引号内子串手机号不打码_对齐采集侧整体值语义() {
        // 采集侧 Desensitizer 对参数值整体判形态；值内子串命中的手机号不视为敏感
        val sql = "SELECT * FROM t WHERE note = 'order 13812345678 done'"
        val masked = DisplayMasker.maskSqlText(sql)
        assertTrue(masked.contains("'order 13812345678 done'"))
    }

    @Test
    fun testSQL文本_转义引号字面量() {
        val sql = "UPDATE t SET pwd = 'a''b' WHERE id = 1"
        val masked = DisplayMasker.maskSqlText(sql)
        assertTrue(masked.contains("'***'"))
        assertFalse(masked.contains("'a''b'"))
    }

    @Test
    fun testSQL文本_无敏感_逐字原样() {
        val sql = "SELECT id, name FROM user WHERE age > 18 AND city = 'sh'"
        assertEquals(sql, DisplayMasker.maskSqlText(sql))
    }

    // ---------- 链入口：maskDisplay（enabled 开关） ----------

    @Test
    fun testMaskDisplay_开打码关原文() {
        val sql = "SELECT * FROM t WHERE phone = '13812345678'"
        val on = DisplayMasker.maskDisplay(sql, true)
        val off = DisplayMasker.maskDisplay(sql, false)
        assertTrue(on.contains("'***'"))
        assertFalse(on.contains("13812345678"))
        assertEquals(sql, off)
    }

    // ---------- R37：敏感字面量参数化 ----------

    @Test
    fun test参数化_敏感字面量换占位符并出脱敏参数表() {
        val sql = "SELECT id FROM user WHERE phone = '13812345678' AND name = 'tom' AND pwd = 'x'"
        val p = DisplayMasker.parameterize(sql)
        assertTrue(p.sql.contains("phone = ?"))
        assertTrue(p.sql.contains("name = 'tom'")) // 非敏感保留
        assertTrue(p.sql.contains("pwd = ?"))
        assertEquals(listOf("***", "***"), p.maskedParams)
        assertFalse(p.sql.contains("13812345678"))
    }

    @Test
    fun test参数化_零敏感_原文无参数表() {
        val sql = "SELECT id FROM user WHERE name = 'tom'"
        val p = DisplayMasker.parameterize(sql)
        assertEquals(sql, p.sql)
        assertTrue(p.maskedParams.isEmpty())
    }

    @Test
    fun test参数化_裸数字手机号也参数化() {
        val sql = "SELECT 1 FROM t WHERE mobile = 13812345678"
        val p = DisplayMasker.parameterize(sql)
        assertEquals("SELECT 1 FROM t WHERE mobile = ?", p.sql)
        assertEquals(listOf("***"), p.maskedParams)
    }

    // ---------- UI-021：INSERT VALUES 列名-值位置对位 ----------

    @Test
    fun test_INSERT_VALUES_列名对位_密码列打码且后继值不误打() {
        val sql = "INSERT INTO user (username, password, email) " +
            "VALUES ('ui_mask_probe', 'Secret123X', 'mask@probe.test')"
        assertEquals(
            "INSERT INTO user (username, password, email) " +
                "VALUES ('ui_mask_probe', '***', 'mask@probe.test')",
            DisplayMasker.maskSqlText(sql),
        )
    }

    @Test
    fun test_INSERT_VALUES_多列对位_关键词列与形态值各按其位() {
        val sql = "INSERT INTO t (a, secret_col, b) VALUES ('x', 'y', '13400000000')"
        // a/b 非敏感列 → 'x' 原样、手机号形态 '13400000000' 走形态兜底；secret_col → 'y' 打码
        assertEquals("INSERT INTO t (a, secret_col, b) VALUES ('x', '***', '***')", DisplayMasker.maskSqlText(sql))
    }

    @Test
    fun test_INSERT_VALUES_值数超出列数_逐位回退旧规则() {
        val sql = "INSERT INTO t (a, b) VALUES ('x', 'y') ON DUPLICATE KEY UPDATE password = 'Secret123X'"
        // 前两位按列清单（a/b 均非敏感）；第三位超列数回退旧 hint（UPDATE 后 password → 打码）
        assertEquals(
            "INSERT INTO t (a, b) VALUES ('x', 'y') ON DUPLICATE KEY UPDATE password = '***'",
            DisplayMasker.maskSqlText(sql),
        )
    }

    @Test
    fun test_parameterize_INSERT_VALUES_对位一致() {
        val r = DisplayMasker.parameterize("INSERT INTO u (username, pwd) VALUES ('u1', 'p1')")
        assertEquals("INSERT INTO u (username, pwd) VALUES ('u1', ?)", r.sql)
        assertEquals(listOf("***"), r.maskedParams)
    }

    @Test
    fun test_非INSERT语句_旧规则不变() {
        assertEquals(
            "SELECT * FROM t WHERE password = '***'",
            DisplayMasker.maskSqlText("SELECT * FROM t WHERE password = 'x'"),
        )
    }
}
