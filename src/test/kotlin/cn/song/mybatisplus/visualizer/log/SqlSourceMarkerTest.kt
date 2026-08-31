package cn.song.mybatisplus.visualizer.log

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
}
