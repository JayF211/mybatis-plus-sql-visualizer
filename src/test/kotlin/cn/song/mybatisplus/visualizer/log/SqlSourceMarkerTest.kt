package cn.song.mybatisplus.visualizer.log

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [SqlSourceMarker] 纯逻辑测试（F-44-6 实时日志可靠跳转）：标记格式与解析。
 * 标记格式须与 docs/instrumentation/SqlSourceInterceptor.kt 的打印保持一致。
 */
class SqlSourceMarkerTest {

    @Test
    fun format_parse_往返一致() {
        val line = SqlSourceMarker.format("src/main/java/com/example/service/UserService.java", 42)
        assertEquals("SQLVIS-SOURCE|src/main/java/com/example/service/UserService.java|42", line)
        assertEquals("src/main/java/com/example/service/UserService.java" to 42, SqlSourceMarker.parse(line))
    }

    @Test
    fun 相对路径含反斜杠_windows_仍可解析() {
        val path = "src\\main\\java\\com\\example\\Mapper.java"
        assertEquals(path to 7, SqlSourceMarker.parse(SqlSourceMarker.format(path, 7)))
    }

    @Test
    fun 非标记行返回null() {
        assertNull(SqlSourceMarker.parse("==>  Preparing: SELECT * FROM user"))
        assertNull(SqlSourceMarker.parse("SELECT 1"))
    }

    @Test
    fun 畸形标记_前缀不符_返回null() {
        assertNull(SqlSourceMarker.parse("SQLVIS-SRC|com/x.java|3"))
        assertNull(SqlSourceMarker.parse("SQLVIS-SOURCE||3")) // 路径为空
    }

    @Test
    fun 行号非数字_返回null() {
        assertNull(SqlSourceMarker.parse("SQLVIS-SOURCE|com/x.java|abc"))
    }

    @Test
    fun 前后空白被忽略() {
        assertEquals("com/x.java" to 9, SqlSourceMarker.parse("  SQLVIS-SOURCE|com/x.java|9  "))
    }

    // ---------- T-IntrUpg-01：自位污染形态识别 ----------

    @Test
    fun `自位路径识别_包式_项目根式_裸文件名_反斜杠均命中`() {
        assertTrue(SqlSourceMarker.isInterceptorSelfPath("cn/song/mpjdemo/config/SqlSourceInterceptor.java"))
        assertTrue(SqlSourceMarker.isInterceptorSelfPath("src/main/java/cn/song/config/SqlSourceInterceptor.java"))
        assertTrue(SqlSourceMarker.isInterceptorSelfPath("SqlSourceInterceptor.java"))
        assertTrue(SqlSourceMarker.isInterceptorSelfPath("cn\\song\\config\\SqlSourceInterceptor.java"))
    }

    @Test
    fun `自位路径识别_业务类与目录名巧合不误判`() {
        assertFalse(SqlSourceMarker.isInterceptorSelfPath("cn/song/service/UserService.java"))
        // 目录恰名拦截器包但文件名是业务类
        assertFalse(SqlSourceMarker.isInterceptorSelfPath("cn/song/SqlSourceInterceptor/Helper.java"))
        // 名字相近（前缀/后缀）不命中
        assertFalse(SqlSourceMarker.isInterceptorSelfPath("cn/song/config/MySqlSourceInterceptor.java"))
        assertFalse(SqlSourceMarker.isInterceptorSelfPath("cn/song/config/SqlSourceInterceptorUtils.java"))
        // MP COUNT 特殊标记不含 .java，与防线互不干扰
        assertFalse(SqlSourceMarker.isInterceptorSelfPath(SourceMarkers.MP_PAGINATION_COUNT))
    }
}
