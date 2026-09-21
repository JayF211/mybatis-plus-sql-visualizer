// T-SyncK2JMaster115 自 master sub-projects/sql-visualizer-agent/src/test/java/cn/song/mybatisplus/sqlvis/agent/util/StackLocatorDiagnoseTest.java 移植
package cn.song.mybatisplus.sqlvis.agent.util;

import cn.song.mybatisplus.sqlvis.agent.AgentState;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/**
 * T-SourceJumpFunnel §3.4 诊断打点守卫（验收判定卡「{@code logLevel=info} 打点确认判定路径，
 * 与复验前对照」）：{@link StackLocator} 全量判定路径在 info 档把
 * {@code primary= / via= / entry= / pool=} 与前 8 帧 jar 判定打到 {@code System.err}，非 info 档零输出。
 *
 * <p>断言落层：打点出口 {@code StackLocator.diagnose} 为 private、void、直接 {@code System.err.println}，
 * 档位早退与字符串拼装都在其中，故本用例<b>不改生产行为</b>，经「接管 {@code System.err} + 调携栈出口
 * {@code locate(StackTraceElement[], ClassLoader)}」观测真实输出——断言覆盖档位闸门与输出内容本身。
 *
 * <p>与 {@link StackLocator} 同包以便清场（{@code clearLocatorCachesForTest}）：L1 整帧缓存命中路径
 * <b>不</b>产生打点（{@code diagnose} 只在全量判定路径被调），用例间必须清场才能确保走到打点路径。
 */
public class StackLocatorDiagnoseTest {

    /** 打点行标记（{@code StackLocator.diagnose} 唯一输出前缀）。 */
    private static final String MARK = "[T-SourceJumpFunnel]";

    private PrintStream originalErr;
    private ByteArrayOutputStream sink;

    @Before
    public void 接管stderr并清场() throws Exception {
        StackLocator.clearLocatorCachesForTest();
        StackLocator.setExtraSkipPrefixes(new String[0]);
        originalErr = System.err;
        sink = new ByteArrayOutputStream();
        System.setErr(new PrintStream(sink, true, "UTF-8"));
    }

    @After
    public void 还原全局态() {
        System.setErr(originalErr);
        AgentState.setLogLevel("warn");
        StackLocator.setExtraSkipPrefixes(new String[0]);
        StackLocator.clearLocatorCachesForTest();
    }

    // ---------- 用例 ----------

    @Test
    public void info档_打点判定键齐全_主位置落业务帧() throws Exception {
        AgentState.setLogLevel("info");
        StackLocator.setExtraSkipPrefixes(new String[]{"com.corp.engine."});

        final Run r = run(funnelStack());

        assertTrue("info 档应输出诊断打点: " + r.out, r.out.contains(MARK));
        // §3.4 步骤 1 原文的四个判定键
        for (String key : new String[]{"primary=", "via=", "entry=", "pool="}) {
            assertTrue("打点应含判定键 " + key + ": " + r.out, r.out.contains(key));
        }
        // 主位置指向业务帧，而非框架汇聚门面
        assertEquals("com/corp/app/service/XxxService.java:36", valueOf(r.out, "primary="));
        assertFalse("打点不得把框架汇聚帧当主位置: " + r.out, r.out.contains("DataUpdateTool"));
        assertEquals("com.corp.app.service.XxxService#getXxxId", valueOf(r.out, "entry="));
        // pool= 为整池非框架帧数（汇聚帧已被前缀跳过）：业务帧 + 控制器帧
        assertEquals("2", valueOf(r.out, "pool="));
        // 打点是判定结果的忠实投影：primary/via 与同一 Frame 一致
        assertEquals(r.frame.sourceFile + ":" + r.frame.line, valueOf(r.out, "primary="));
        assertEquals(r.frame.via, valueOf(r.out, "via="));
    }

    @Test
    public void 默认warn档_同栈零打点_对照info档有输出() throws Exception {
        StackLocator.setExtraSkipPrefixes(new String[]{"com.corp.engine."});
        assertEquals("默认档应为 warn", "warn", AgentState.getLogLevel());

        // 默认档：diagnose 档位早退——同一条全量判定路径零输出
        final String silent = run(funnelStack()).out;
        assertFalse("warn 档不得输出诊断打点: " + silent, silent.contains(MARK));
        assertFalse("warn 档不得输出判定键: " + silent, silent.contains("primary="));

        // 同一栈切 info 档立即有输出——证明上一条不是「栈构造本就不产打点」的空断言
        StackLocator.clearLocatorCachesForTest();
        AgentState.setLogLevel("info");
        assertTrue("info 档应输出诊断打点", run(funnelStack()).out.contains(MARK));
    }

    @Test
    public void 复验前对照_无前缀落汇聚门面_登记前缀后落业务帧() throws Exception {
        AgentState.setLogLevel("info");

        // 复验前形态：未登记自研框架前缀——主位置被取成汇聚门面（跳转落 DataUpdateTool.findByFilter）
        final Run before = run(funnelStack());
        assertEquals("com/corp/engine/DataUpdateTool.java:88", valueOf(before.out, "primary="));
        assertEquals("com.corp.engine.DataUpdateTool#findByFilter", valueOf(before.out, "entry="));
        assertTrue("无前缀时汇聚门面应出现在帧清单（可人工上溯）: " + before.out,
                before.out.contains("DataUpdateTool#findByFilter"));
        assertEquals("打点与判定结果一致", before.frame.via, valueOf(before.out, "via="));

        // 登记前缀（P1-3 CONFIG 热下发）后同一栈：主位置改落业务帧
        StackLocator.clearLocatorCachesForTest();
        StackLocator.setExtraSkipPrefixes(new String[]{"com.corp.engine."});
        final Run after = run(funnelStack());
        assertEquals("com/corp/app/service/XxxService.java:36", valueOf(after.out, "primary="));
        assertFalse("登记前缀后汇聚门面应被整帧跳过: " + after.out, after.out.contains("DataUpdateTool"));
        assertEquals("打点与判定结果一致", after.frame.via, valueOf(after.out, "via="));
    }

    @Test
    public void 前八帧上限_池十二帧只列八条() throws Exception {
        AgentState.setLogLevel("info");
        final StackTraceElement[] body = new StackTraceElement[12];
        for (int i = 0; i < body.length; i++) {
            body[i] = f("com.corp.app.layer" + i + ".Svc" + i, "m" + i, "Svc" + i + ".java", 100 + i);
        }

        final Run r = run(stack(body));

        assertEquals("pool= 应报非框架帧总数（不截断）", "12", valueOf(r.out, "pool="));
        // 代码为 Math.min(pool.size(), 8)——以代码为准的上限 8
        assertEquals("帧清单应恰为 8 条: " + r.out, 8, countOf(r.out, " | #"));
        assertEquals("每条帧清单项应带 jar= 来源判定", 8, countOf(r.out, " jar="));
        assertTrue("第 8 条（#7）应在列", r.out.contains("#7 "));
        assertFalse("第 9 条（#8）及以后不得输出: " + r.out, r.out.contains("#8 "));
    }

    @Test
    public void 判定路径_L1命中目录帧() throws Exception {
        AgentState.setLogLevel("info");

        // 首帧为测试产物目录中的真实类 → L1 来源过滤命中（via=L1 路径的打点）
        final Run r = run(stack(
                f("cn.song.mybatisplus.sqlvistest.LocateProbe", "locateHere", "LocateProbe.java", 11),
                f("com.corp.app.service.XxxService", "getXxxId", "XxxService.java", 36)));

        assertEquals("L1", valueOf(r.out, "via="));
        assertEquals("L1", r.frame.via);
        assertEquals("cn/song/mybatisplus/sqlvistest/LocateProbe.java:11", valueOf(r.out, "primary="));
        assertEquals("LocateProbe#locateHere", r.frame.methodKey);
    }

    @Test
    public void 缓存命中路径静默_同key二次执行零打点() throws Exception {
        AgentState.setLogLevel("info");
        final StackTraceElement[] stack = stack(
                f("cn.song.mybatisplus.sqlvistest.LocateProbe", "locateHere", "LocateProbe.java", 11),
                f("com.corp.app.service.XxxService", "getXxxId", "XxxService.java", 36));

        final Run first = run(stack);
        assertTrue("首次全量判定应有打点", first.out.contains(MARK));
        // L1 命中写整帧缓存：同 key 二次执行在缓存查询处返回，diagnose 不被调 → 零打点（每条 SQL 零噪音）
        final Run second = run(stack);
        assertSame("L1 整帧缓存命中应复用同一 Frame", first.frame, second.frame);
        assertEquals("缓存命中路径应静默: " + second.out, "", second.out);
    }

    @Test
    public void 档位大小写不敏感_非info档一律静默() throws Exception {
        StackLocator.setExtraSkipPrefixes(new String[]{"com.corp.engine."});

        // equalsIgnoreCase：INFO 等同 info
        AgentState.setLogLevel("INFO");
        assertTrue("INFO 档应等同 info（equalsIgnoreCase）", run(funnelStack()).out.contains(MARK));

        for (String level : new String[]{"debug", "error", "warn-extra"}) {
            StackLocator.clearLocatorCachesForTest();
            AgentState.setLogLevel(level);
            assertFalse("非 info 档 " + level + " 应静默", run(funnelStack()).out.contains(MARK));
        }
        // null 归一为默认 warn（AgentState.setLogLevel 契约）
        StackLocator.clearLocatorCachesForTest();
        AgentState.setLogLevel(null);
        assertEquals("warn", AgentState.getLogLevel());
        assertFalse("null 档应静默", run(funnelStack()).out.contains(MARK));
    }

    // ---------- 夹具与断言工具 ----------

    private static final class Run {
        final StackLocator.Frame frame;
        final String out;

        Run(StackLocator.Frame frame, String out) {
            this.frame = frame;
            this.out = out;
        }
    }

    /** 跑一次携栈定位并取本次打点输出（每次先清缓冲：out 恒为单次执行的输出）。 */
    private Run run(StackTraceElement[] stack) throws Exception {
        sink.reset();
        final StackLocator.Frame frame = StackLocator.locate(stack, null);
        assertNotNull("构造栈应产出主位置（主位置为 null 时无打点）", frame);
        return new Run(frame, sink.toString("UTF-8"));
    }

    /** 取打点行中 {@code key} 后的空格分隔值。 */
    private static String valueOf(String output, String key) {
        final int at = output.indexOf(key);
        assertTrue("打点应含 " + key + "，实为: " + output, at >= 0);
        final int from = at + key.length();
        final int to = output.indexOf(' ', from);
        return to < 0 ? output.substring(from) : output.substring(from, to);
    }

    private static int countOf(String text, String needle) {
        int n = 0;
        for (int i = text.indexOf(needle); i >= 0; i = text.indexOf(needle, i + needle.length())) {
            n++;
        }
        return n;
    }

    private static StackTraceElement f(String className, String method, String file, int line) {
        return new StackTraceElement(className, method, file, line);
    }

    /** 真机快照形态：[0]=Thread#getStackTrace、[1]=agent 自身帧（前缀规则跳过），其后为 body。 */
    private static StackTraceElement[] stack(StackTraceElement... body) {
        final StackTraceElement[] out = new StackTraceElement[body.length + 2];
        out[0] = f("java.lang.Thread", "getStackTrace", "Thread.java", -2);
        out[1] = f("cn.song.mybatisplus.sqlvis.agent.util.StackLocator", "locate", "StackLocator.java", 134);
        System.arraycopy(body, 0, out, 2, body.length);
        return out;
    }

    /**
     * 复验现场栈（自研框架汇聚门面形态，栈顶在左）：汇聚门面帧在业务帧<b>之前</b>——SQL 由
     * {@code DataUpdateTool.findByFilter} 执行、它由业务方法调用，故门面先于业务帧入池；门面被选为主位置
     * 即「所有 SQL 跳源码都落同一个门面」的失准形态。
     */
    private static StackTraceElement[] funnelStack() {
        return stack(
                f("com.corp.engine.DataUpdateTool", "findByFilter", "DataUpdateTool.java", 88),
                f("com.corp.app.service.XxxService", "getXxxId", "XxxService.java", 36),
                f("com.corp.app.controller.XxxController", "load", "XxxController.java", 48));
    }
}
