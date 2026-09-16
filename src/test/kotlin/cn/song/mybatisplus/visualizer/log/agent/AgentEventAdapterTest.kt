package cn.song.mybatisplus.visualizer.log.agent

import cn.song.mybatisplus.visualizer.log.LogSqlEntry
import cn.song.mybatisplus.visualizer.log.LogSqlParam
import cn.song.mybatisplus.visualizer.log.SqlSource
import cn.song.mybatisplus.visualizer.log.normalizeSqlWhitespace
import cn.song.mybatisplus.visualizer.log.sqlFingerprint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [AgentEventAdapter] 纯逻辑测试（M56 T-M56-02）：事件→条目映射、参数还原、
 * dedupeKey 与桥通道一致性（接管/回落切换期两通道去重口径统一）。
 */
class AgentEventAdapterTest {

    private fun event(
        sql: String,
        params: List<Triple<Int, String?, String?>> = emptyList(),
        updateCount: Long? = null,
        errorText: String? = null,
        sourceFile: String? = null,
        sourceLine: Int = 0,
        methodKey: String? = null,
        requestTag: String? = null,
        durationNanos: Long = 5_000_000L,
    ) = AgentEvent(
        sql = sql,
        timestamp = 1724567890000L,
        durationNanos = durationNanos,
        updateCount = updateCount,
        errorText = errorText,
        sourceFile = sourceFile,
        sourceLine = sourceLine,
        methodKey = methodKey,
        requestTag = requestTag,
        params = params,
        threadName = "main",
    )

    @Test
    fun 事件解析_全字段() {
        val body = """{"sql":"UPDATE t SET a = ?","ts":1724567890000,"dn":1234567,"u":3,"sf":"a/A.java","sl":9,"mk":"A#do","rt":"req-1","th":"main","p":[[1,"x",null],[2,"7","Long"]]}"""
        val e = AgentEvent.parse(body)!!
        assertEquals("UPDATE t SET a = ?", e.sql)
        assertEquals(1724567890000L, e.timestamp)
        assertEquals(1234567L, e.durationNanos)
        assertEquals(3L, e.updateCount)
        assertEquals("a/A.java", e.sourceFile)
        assertEquals(9, e.sourceLine)
        assertEquals("A#do", e.methodKey)
        assertEquals("req-1", e.requestTag)
        assertEquals(2, e.params.size)
        assertEquals(Triple(1, "x", null as String?), e.params[0])
        assertEquals(Triple(2, "7", "Long"), e.params[1])
    }

    @Test
    fun 事件解析_结构不符与空SQL丢弃() {
        assertNull(AgentEvent.parse("not json"))
        assertNull(AgentEvent.parse("""{"ts":1}"""))
        assertNull(AgentEvent.parse("""{"sql":"  "}"""))
    }

    @Test
    fun 事件解析_候选帧_T_AgentSourceJump() {
        val body = """{"sql":"SELECT 1","ts":1,"dn":1,"sf":"com/github/pagehelper/PageInterceptor.java","sl":132,"cands":[["com/example/service/UserServiceImpl.java",36],["com/example/controller/UserController.java",48]]}"""
        val e = AgentEvent.parse(body)!!
        assertEquals(2, e.sourceCandidates.size)
        assertEquals("com/example/service/UserServiceImpl.java" to 36, e.sourceCandidates[0])
        assertEquals("com/example/controller/UserController.java" to 48, e.sourceCandidates[1])
    }

    @Test
    fun 事件解析_无候选键_空列表容错() {
        // 旧版 agent 无 cands 键：必须空列表（跳转回退主位置），不得抛错
        val e = AgentEvent.parse("""{"sql":"SELECT 1","ts":1,"dn":1,"sf":"a/A.java","sl":3}""")!!
        assertEquals(0, e.sourceCandidates.size)
    }

    @Test
    fun 事件解析_候选畸形项跳过_不污染() {
        val body = """{"sql":"SELECT 1","ts":1,"dn":1,"cands":[["ok/A.java",5],["",9],["noLine.java"],123]}"""
        val e = AgentEvent.parse(body)!!
        assertEquals("空文件名/非数组项应跳过", 2, e.sourceCandidates.size)
        assertEquals("ok/A.java" to 5, e.sourceCandidates[0])
        assertEquals("缺行号项保留且行号归 0", "noLine.java" to 0, e.sourceCandidates[1])
    }

    @Test
    fun 映射_来源AGENT_耗时纳秒转毫秒_四新字段() {
        val entry = AgentEventAdapter.toEntry(
            event(
                "UPDATE t SET a = ?",
                params = listOf(Triple(1, "x", "String")),
                updateCount = 1L,
                errorText = "SQLException: boom",
                sourceFile = "a/A.java",
                sourceLine = 9,
                methodKey = "A#do",
                requestTag = "req-1",
            ),
        )
        assertEquals(SqlSource.AGENT, entry.source)
        assertEquals(5L, entry.executionTimeMs)
        assertEquals(1L, entry.updateCount)
        assertEquals("SQLException: boom", entry.errorText)
        assertEquals("a/A.java", entry.sourceFile)
        assertEquals(9, entry.sourceLine)
        assertEquals("A#do", entry.mapperStatement) // T-M56-06 聚焦键经 mapperStatement 字段承载
        assertEquals("req-1", entry.requestTag)
    }

    @Test
    fun 参数还原_字符串加引号_数值直出_缺失不替换() {
        // 字符串值加引号；数值类型直出
        val restored = AgentEventAdapter.restore(
            "UPDATE t SET a = ? WHERE id = ?",
            listOf(LogSqlParam("x'y", "String"), LogSqlParam("7", "Long")),
        )
        assertEquals("UPDATE t SET a = 'x''y' WHERE id = 7", restored)
        // 占位符数 != 参数数 → 原样返回（防错位）
        val mismatch = AgentEventAdapter.restore("SELECT ?", listOf(LogSqlParam("1", "Long"), LogSqlParam("2", "Long")))
        assertEquals("SELECT ?", mismatch)
        // null 参数 → NULL
        val nullParam = AgentEventAdapter.restore("INSERT INTO t VALUES (?)", listOf(LogSqlParam(null, "NULL")))
        assertEquals("INSERT INTO t VALUES (NULL)", nullParam)
        // 引号字面量内的 ? 不参与替换
        val literal = AgentEventAdapter.restore(
            "SELECT * FROM t WHERE name = 'a?b' AND id = ?",
            listOf(LogSqlParam("9", "Long")),
        )
        assertEquals("SELECT * FROM t WHERE name = 'a?b' AND id = 9", literal)
    }

    @Test
    fun dedupeKey与桥通道一致_同一SQL同指纹() {
        // 口径：dedupeKey 只管面板去重（两侧**手写同一 restoredSql** 时同指纹）。
        // 注意本用例不是「跨通道集成」守卫——它不经过控制台解析链，故过去的失配（布尔参数渲染、
        // 注释内 `?`、参数个数守卫）它一概发现不了；日志时间配对的真实守卫见
        // `AgentLogTimeParityTest`（T-AgentLogTime 立，键已与 dedupeKey 解耦）。
        // agent 通道条目
        val agentEntry = AgentEventAdapter.toEntry(event("SELECT * FROM user WHERE id = 42"))
        // 桥通道同 SQL 条目（restoredSql 相同）
        val bridgeEntry = LogSqlEntry(
            rawSql = "SELECT * FROM user WHERE id = ?",
            restoredSql = "SELECT * FROM user WHERE id = 42",
            params = emptyList(),
            source = SqlSource.MYBATIS,
        )
        assertEquals(bridgeEntry.dedupeKey, agentEntry.dedupeKey)
        assertEquals(sqlFingerprint(normalizeSqlWhitespace("SELECT * FROM user WHERE id = 42")), agentEntry.dedupeKey)
    }

    @Test
    fun `事件解析_url键与缺失容错`() {
        val withUrl = AgentEvent.parse("""{"sql":"SELECT 1","ts":1,"dn":1,"url":"jdbc:dm://localhost:5236"}""")!!
        assertEquals("jdbc:dm://localhost:5236", withUrl.url)
        val withoutUrl = AgentEvent.parse("""{"sql":"SELECT 1","ts":1,"dn":1}""")!!
        assertNull("旧版 agent 无 url 键 → null 容错", withoutUrl.url)
    }

    @Test
    fun `映射_url推断目标库名_缺失为null`() {
        val dm = AgentEventAdapter.toEntry(event("SELECT 1").copy(url = "jdbc:dm://localhost:5236"))
        assertEquals("DM（达梦）", dm.databaseName)
        val mysql = AgentEventAdapter.toEntry(event("SELECT 1").copy(url = "jdbc:mysql://localhost/db"))
        assertEquals("MySQL", mysql.databaseName)
        val unknown = AgentEventAdapter.toEntry(event("SELECT 1").copy(url = "jdbc:weirddb://x"))
        assertNull("未知子协议不显示角标", unknown.databaseName)
        val legacy = AgentEventAdapter.toEntry(event("SELECT 1"))
        assertNull("旧版 agent 无 url → null", legacy.databaseName)
    }
}
