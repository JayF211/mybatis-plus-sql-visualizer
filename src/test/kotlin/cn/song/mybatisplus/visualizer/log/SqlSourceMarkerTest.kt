package cn.song.mybatisplus.visualizer.log

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [SqlSourceMarker] 纯逻辑测试（F-44-6 实时日志可靠跳转）：标记格式与解析。
 * 标记格式须与 docs/instrumentation/SqlSourceInterceptor.kt 的打印保持一致。
 * M56 T-M56-00：宽松化 3~5 段——第 4 段 mapper 语句 id、第 5 段请求标签，缺段 null；
 * 旧 3 段拦截器幂等不升级为一等验收项。
 */
class SqlSourceMarkerTest {

    @Test
    fun format_parse_往返一致() {
        val line = SqlSourceMarker.format("src/main/java/com/example/service/UserService.java", 42)
        assertEquals("SQLVIS-SOURCE|src/main/java/com/example/service/UserService.java|42", line)
        assertEquals(
            SourceMarkerData("src/main/java/com/example/service/UserService.java", 42),
            SqlSourceMarker.parse(line),
        )
    }

    @Test
    fun 相对路径含反斜杠_windows_仍可解析() {
        val path = "src\\main\\java\\com\\example\\Mapper.java"
        assertEquals(SourceMarkerData(path, 7), SqlSourceMarker.parse(SqlSourceMarker.format(path, 7)))
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
        assertEquals(SourceMarkerData("com/x.java", 9), SqlSourceMarker.parse("  SQLVIS-SOURCE|com/x.java|9  "))
    }

    // ---------- M56 T-M56-00：宽松化 3~5 段 ----------

    @Test
    fun 四段标记_mapper语句id解析_第5段为null() {
        val line = "SQLVIS-SOURCE|com/x/UserService.java|9|com.x.mapper.UserMapper.selectById"
        assertEquals(
            SourceMarkerData("com/x/UserService.java", 9, "com.x.mapper.UserMapper.selectById", null),
            SqlSourceMarker.parse(line),
        )
    }

    @Test
    fun 五段标记_mapper语句id与请求标签齐解析() {
        val line = "SQLVIS-SOURCE|com/x/UserService.java|9|com.x.mapper.UserMapper.selectById|req-42"
        assertEquals(
            SourceMarkerData("com/x/UserService.java", 9, "com.x.mapper.UserMapper.selectById", "req-42"),
            SqlSourceMarker.parse(line),
        )
    }

    @Test
    fun format_五段_仅四段在场时第5段省略() {
        assertEquals(
            "SQLVIS-SOURCE|com/x.java|1|com.x.M.selectById|req-1",
            SqlSourceMarker.format("com/x.java", 1, "com.x.M.selectById", "req-1"),
        )
        assertEquals(
            "SQLVIS-SOURCE|com/x.java|1|com.x.M.selectById",
            SqlSourceMarker.format("com/x.java", 1, "com.x.M.selectById", null),
        )
        // 第 5 段不可跳位：requestTag 在场而 mapperStatement 缺省时退回 3 段
        assertEquals(
            "SQLVIS-SOURCE|com/x.java|1",
            SqlSourceMarker.format("com/x.java", 1, null, "req-1"),
        )
    }

    @Test
    fun 段内容空白_视同缺段null() {
        assertEquals(
            SourceMarkerData("com/x.java", 1, null, null),
            SqlSourceMarker.parse("SQLVIS-SOURCE|com/x.java|1||"),
        )
        assertEquals(
            SourceMarkerData("com/x.java", 1, null, null),
            SqlSourceMarker.parse("SQLVIS-SOURCE|com/x.java|1| |"),
        )
    }

    @Test
    fun 超过五段_非标记行返回null() {
        assertNull(SqlSourceMarker.parse("SQLVIS-SOURCE|com/x.java|1|m|t|extra"))
    }

    @Test
    fun 旧三段标记_新解析幂等兼容() {
        // 存量注入拦截器不升级：3 段行为与 T-M56-00 前逐字一致
        val legacy = "SQLVIS-SOURCE|src/main/java/com/example/service/UserService.java|87"
        val parsed = SqlSourceMarker.parse(legacy)!!
        assertEquals("src/main/java/com/example/service/UserService.java", parsed.file)
        assertEquals(87, parsed.line)
        assertNull(parsed.mapperStatement)
        assertNull(parsed.requestTag)
    }

    @Test
    fun 跳位第5段缺第4段_不误读() {
        // "p|1||tag"：第 4 段空白视同缺段，第 5 段不可跳位解析——tag 不得落进 mapperStatement
        assertEquals(SourceMarkerData("com/x.java", 1, null, null), SqlSourceMarker.parse("SQLVIS-SOURCE|com/x.java|1||tag"))
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
