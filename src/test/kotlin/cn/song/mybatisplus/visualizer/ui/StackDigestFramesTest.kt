package cn.song.mybatisplus.visualizer.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T-StackHoverUi §M1：调用栈快照解析器（[StackDigestFrames.parse]）的自动化断言。
 *
 * 样本形态对齐 agent 侧 `StackDigest.build` 逐字契约（`<-` 连接、栈顶在左、行号负数不写、
 * 触顶追加 `…`）与用户真机截图实态（`$Proxy151` 无行号帧、hikari/ibatis 长链）：
 * 帧拆分/行号解析/截断末段丢弃/容错（垃圾段跳过、空入参空表）。
 */
class StackDigestFramesTest {

    /** 用户真机截图实态：agent 埋点两帧 + JDK 代理 + hikari + ibatis（含无行号帧）。 */
    private val realShape =
        "cn.song.mybatisplus.sqlvis.agent.hook.StatementHandler#emitEvent:132" +
            "<-cn.song.mybatisplus.sqlvis.agent.hook.StatementHandler#executeWithCapture:107" +
            "<-com.sun.proxy.\$Proxy151#execute" +
            "<-com.zaxxer.hikari.pool.HikariProxyPreparedStatement#execute:44" +
            "<-org.apache.ibatis.executor.SimpleExecutor#doQuery:63"


    @Test
    fun test真实形态_全链拆回帧列表_栈顶在左() {
        val frames = StackDigestFrames.parse(realShape)
        assertEquals(5, frames.size)
        // 栈顶在顶（串序即展示序）
        assertEquals("cn.song.mybatisplus.sqlvis.agent.hook.StatementHandler", frames[0].fqcn)
        assertEquals("emitEvent", frames[0].method)
        assertEquals(132, frames[0].line)
        assertEquals("org.apache.ibatis.executor.SimpleExecutor", frames[4].fqcn)
        assertEquals("doQuery", frames[4].method)
        assertEquals(63, frames[4].line)
    }


    @Test
    fun test无行号帧_行号为0_方法名完整() {
        val frames = StackDigestFrames.parse(realShape)
        assertEquals(0, frames[2].line)
        assertEquals("com.sun.proxy.\$Proxy151", frames[2].fqcn)
        assertEquals("execute", frames[2].method)
    }


    @Test
    fun test内嵌类与lambda方法名_原样保留() {
        val frames = StackDigestFrames.parse(
            "com.corp.Outer\$Inner#run:42<-com.corp.Biz#lambda\$doIt\$0:7",
        )
        assertEquals(2, frames.size)
        assertEquals("com.corp.Outer\$Inner", frames[0].fqcn)
        assertEquals("run", frames[0].method)
        assertEquals(42, frames[0].line)
        assertEquals("lambda\$doIt\$0", frames[1].method)
        assertEquals(7, frames[1].line)
    }


    @Test
    fun testagent自身帧判定() {
        val frames = StackDigestFrames.parse(realShape)
        assertTrue("埋点链两帧应判 agent 帧", StackDigestFrames.isAgentFrame(frames[0]))
        assertTrue(StackDigestFrames.isAgentFrame(frames[1]))
        assertFalse("业务/框架帧不判 agent 帧", StackDigestFrames.isAgentFrame(frames[2]))
        assertFalse(StackDigestFrames.isAgentFrame(frames[3]))
    }


    @Test
    fun test帧数触顶截断_完整末帧随标记丢弃() {
        // agent 帧数触顶：完整帧后追加 …——末段（第 3 帧）随标记丢弃，前两帧保留
        val frames = StackDigestFrames.parse("a.A#m1:1<-b.B#m2:2<-c.C#m3:3…")
        assertEquals(2, frames.size)
        assertEquals("a.A", frames[0].fqcn)
        assertEquals("b.B", frames[1].fqcn)
    }


    @Test
    fun test长度触顶截断_半帧末段不造假展示() {
        // 2000 字符硬截把末帧切半：`c.C#doQu` 解析出残缺方法名也不展示（末段丢弃）
        val frames = StackDigestFrames.parse("a.A#m1:1<-b.B#m2:2<-c.C#doQu…")
        assertEquals(2, frames.size)
        assertNull(frames.getOrNull(2))
        assertEquals("m1", frames[0].method)
    }


    @Test
    fun test非数字行号段_按无行号处理() {
        val frames = StackDigestFrames.parse("a.A#native<-b.B#run:-1")
        assertEquals(0, frames[0].line)
        // 负号行号（agent 不产出，容错）：`-1` 非全数字 → 整段当方法名，行号 0
        assertEquals("run:-1", frames[1].method)
        assertEquals(0, frames[1].line)
    }


    @Test
    fun test容错_null_空串_纯空白() {
        assertEquals(0, StackDigestFrames.parse(null).size)
        assertEquals(0, StackDigestFrames.parse("").size)
        assertEquals(0, StackDigestFrames.parse("   ").size)
        assertEquals(0, StackDigestFrames.parse("…").size)
    }


    @Test
    fun test容错_无井号垃圾段跳过_有效帧保留() {
        val frames = StackDigestFrames.parse("garbage-without-hash<-a.A#m1:1<-<-b.B#m2:2")
        assertEquals(2, frames.size)
        assertEquals("a.A", frames[0].fqcn)
        assertEquals("b.B", frames[1].fqcn)
    }


    @Test
    fun test容错_整串不可解析返回空表() {
        assertEquals(0, StackDigestFrames.parse("not-a-stack").size)
        assertEquals(0, StackDigestFrames.parse("#-only-method").size)
    }


    @Test
    fun test前后空白与段间空白容忍() {
        // 历史条目/日志链路可能的空格形态：段前后空白 trim 后解析
        val frames = StackDigestFrames.parse(" a.A#m1:1 <- b.B#m2:2 ")
        assertEquals(2, frames.size)
        assertEquals("a.A", frames[0].fqcn)
        assertEquals(1, frames[0].line)
    }

    @Test
    fun test两端采样省略标记段_解析跳过() {
        // T-StackHoverUi A 案：agent 中段省略标记（无 #）按不可解析段跳过，弹层只出帧
        val frames = StackDigestFrames.parse(
            "com.a.F1#m1:1<-com.a.F2#m2:2<-<…中间省略 37 帧…><-com.biz.Controller#list:42",
        )
        assertEquals(3, frames.size)
        assertEquals("com.a.F2", frames[1].fqcn)
        assertEquals("com.biz.Controller", frames[2].fqcn)
        assertEquals(42, frames[2].line)
    }
}
