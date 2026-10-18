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
import static org.junit.Assert.assertTrue;

/**
 * T-AgentSourceLocateStale T-04：定位缓存「冻结行号」回归守卫（合成栈，形状照真机）。
 *
 * <p>缺陷形态（用户报障：面板显示 {@code DeviceInfoServiceImpl.java:42}，标记行真值 63）：入口键 =
 * 栈上首个非框架帧；当该帧是全应用共享的<b>库帧</b>（真机 = MPJ 1.5.8 经 starter 自动配置注册的
 * {@code com.github.yulichang.interceptor.MPJInterceptor}，每次查询都在栈上且对所有查询是同一帧）
 * 时，旧实现的「整帧 putIfAbsent 缓存」把<b>首个查询的行号与候选列</b>钉死，后续一切业务语句复用。
 *
 * <p>本类用例与修法的对应：
 * <ol>
 *   <li>{@link #共享库帧下的两个业务调用点_行号各自正确}——缺陷最小复现（真机 42/63 同形）；
 *       共享库帧用「前缀表未收的第三方库帧」造（真机上承载该形态的实例就是 T-02 之前的 MPJ 帧，
 *       本用例末尾以 {@code isFrameworkFrame} 断言 T-02 已把真机实例收进跳过表）；</li>
 *   <li>{@link #同一方法内两个调用行_两次行号不同}——同一入口键（业务帧）内两个调用点；</li>
 *   <li>{@link #判定缓存命中但身份不在场_落回全量路径不复用旧行号}——身份在场校验；</li>
 *   <li>{@link #业务帧行号漂移_跟随当前栈}——行号漂移自愈（不再冻结）；</li>
 *   <li>{@link #入口键非业务帧_产线档位零输出}——宿主控制台零噪音契约（2026-10-14 移除 T-05 无条件摘要后补）。</li>
 * </ol>
 *
 * <p>与 {@link StackLocator} 同包以便使用包内测试缝：{@code jarOriginProbe} 来源注入口
 * （把「业务类来自目录 / 库类来自 JAR」这一真机事实做成确定性输入）与 {@code clearLocatorCachesForTest}
 * 清场（判定缓存与一次性摘要去重集合均为全局静态）。
 */
public class StackLocatorCacheStalenessTest {

    /** 共享库帧（第三方 JAR 帧、前缀表未收 → 入口键落在它上面）；真机同形实例 = MPJInterceptor。 */
    private static final String LIB_INTERCEPTOR = "com.lib.mybatisjoin.MybatisJoinInterceptor";
    private static final String LIB_ENTRY_KEY = LIB_INTERCEPTOR + "#intercept";

    /** 业务类（开发态目录来源，L1 命中）。 */
    private static final String BIZ_IMPL = "com.example.service.impl.DeviceInfoServiceImpl";
    private static final String BIZ_FILE = "com/example/service/impl/DeviceInfoServiceImpl.java";
    /** 业务类（安全网/身份校验用例用，与 BIZ_IMPL 不同类以便判定互不干扰）。 */
    private static final String BIZ_SVC = "com.example.service.BizService";

    /** 已移除的无条件摘要前缀（T-05，2026-10-14 删）——仅作「不得复活」的负断言锚点。 */
    private static final String MARK = "[T-AgentSourceLocateStale]";

    /**
     * 合成来源探针：{@code com.example.} = 开发态目录（L1 命中）、{@code com.lib.} = 第三方 JAR
     * （L1 跳过，主位置不落库帧）、其余未知——把真机的来源分布做成确定性输入，并计数以证
     * 「全量判定路径确实重跑」。
     */
    private static final class SyntheticOriginProbe implements StackLocator.JarOriginProbe {
        int calls;

        @Override
        public Boolean isFromJar(String className, ClassLoader loader) {
            calls++;
            if (className.startsWith("com.example.")) {
                return Boolean.FALSE;
            }
            if (className.startsWith("com.lib.")) {
                return Boolean.TRUE;
            }
            return null;
        }
    }

    private SyntheticOriginProbe probe;
    private PrintStream originalErr;
    private ByteArrayOutputStream sink;

    @Before
    public void 清场并接管全局态() throws Exception {
        StackLocator.clearLocatorCachesForTest();
        StackLocator.setExtraSkipPrefixes(new String[0]);
        AgentState.setLogLevel("warn");
        probe = new SyntheticOriginProbe();
        StackLocator.jarOriginProbe = probe;
        originalErr = System.err;
        sink = new ByteArrayOutputStream();
        System.setErr(new PrintStream(sink, true, "UTF-8"));
    }

    @After
    public void 还原全局态() {
        System.setErr(originalErr);
        StackLocator.jarOriginProbe = null;
        StackLocator.setExtraSkipPrefixes(new String[0]);
        StackLocator.clearLocatorCachesForTest();
        AgentState.setLogLevel("warn");
    }

    // ---------- ① 共享库帧下的两个业务调用点：行号各自正确（缺陷最小复现） ----------

    @Test
    public void 共享库帧下的两个业务调用点_行号各自正确() {
        // 真机形态：同一个库帧（MPJInterceptor）先后承载 joinRightAlias() 与 joinExtAndSubTable()
        // 两个业务方法；两者真值行 42 / 63（同一业务类内不同方法）。
        final StackLocator.Frame rightAlias = StackLocator.locate(
                libStack(libFrame(), f(BIZ_IMPL, "joinRightAlias", "DeviceInfoServiceImpl.java", 42),
                        f("com.example.controller.DeviceController", "rightAlias", "DeviceController.java", 70)),
                null);
        final StackLocator.Frame extSub = StackLocator.locate(
                libStack(libFrame(), f(BIZ_IMPL, "joinExtAndSubTable", "DeviceInfoServiceImpl.java", 63),
                        f("com.example.controller.DeviceController", "extSub", "DeviceController.java", 88)),
                null);

        assertNotNull(rightAlias);
        assertNotNull(extSub);
        // 两次行号各自正确（旧实现：第二次复用首次的 42 → 本断言必红）
        assertEquals(BIZ_FILE, rightAlias.sourceFile);
        assertEquals(42, rightAlias.line);
        assertEquals("DeviceInfoServiceImpl#joinRightAlias", rightAlias.methodKey);
        assertEquals(BIZ_FILE, extSub.sourceFile);
        assertEquals(63, extSub.line);
        assertEquals("DeviceInfoServiceImpl#joinExtAndSubTable", extSub.methodKey);
        // 主位置必须是业务帧，不能是共享库帧
        assertFalse("主位置不得落在共享库帧上: " + extSub.methodKey, extSub.methodKey.contains("MybatisJoinInterceptor"));
        assertEquals(LIB_ENTRY_KEY, 入口键(libStack(libFrame(),
                f(BIZ_IMPL, "joinRightAlias", "DeviceInfoServiceImpl.java", 42))));
        // 库帧仍在候选列里（IDE 侧可上溯），只是不再当主位置
        assertTrue("共享库帧应留在候选列: " + candidates(extSub),
                candidates(extSub).contains("com/lib/mybatisjoin/MybatisJoinInterceptor.java"));

        // T-02 锚点：真机上承载本形态的库帧（MPJ 全族）已被收进跳过表——入口键不再落在它上面
        assertTrue("MPJ 拦截器帧应被跳过（T-02）",
                StackLocator.isFrameworkFrame("com.github.yulichang.interceptor.MPJInterceptor"));
    }

    // ---------- ② 同一方法内两个调用行：两次行号不同 ----------

    @Test
    public void 同一方法内两个调用行_两次行号不同() {
        // 真机共享库帧（MPJ，T-02 后已被跳过 → 入口键落业务帧）下的同一方法两个调用点
        final StackLocator.Frame at42 = StackLocator.locate(
                mpjStack(f(BIZ_IMPL, "joinExtAndSubTable", "DeviceInfoServiceImpl.java", 42)), null);
        final StackLocator.Frame at50 = StackLocator.locate(
                mpjStack(f(BIZ_IMPL, "joinExtAndSubTable", "DeviceInfoServiceImpl.java", 50)), null);

        assertNotNull(at42);
        assertNotNull(at50);
        assertEquals(42, at42.line);
        assertEquals(50, at50.line);
        assertEquals("同一方法两个调用点身份相同（键相同）", at42.methodKey, at50.methodKey);
        // 入口键落在业务帧（MPJ 帧被跳过），判定缓存按业务身份写
        assertTrue("判定缓存应记业务身份",
                StackLocator.DECISIONS.containsKey(BIZ_IMPL + "#joinExtAndSubTable"));
        assertFalse("MPJ 帧不得成为入口键",
                StackLocator.DECISIONS.containsKey("com.github.yulichang.interceptor.MPJInterceptor#intercept"));
    }

    // ---------- ③ 判定缓存命中但身份不在场：落回全量路径 ----------

    @Test
    public void 判定缓存命中但身份不在场_落回全量路径不复用旧行号() {
        // 首次：共享库帧 → 入口键 = 库帧；主位置 = BizService#callA（L1 目录帧）
        final StackLocator.Frame first = StackLocator.locate(
                libStack(libFrame(), f(BIZ_SVC, "callA", "BizService.java", 42)), null);
        assertNotNull(first);
        assertEquals(42, first.line);
        final String[] decision = StackLocator.DECISIONS.get(LIB_ENTRY_KEY);
        assertNotNull("首次应写入判定", decision);
        assertEquals(BIZ_SVC, decision[1]);
        assertEquals("callA", decision[2]);
        final int afterFirst = probe.calls;

        // 二次：同一入口键（库帧），但业务身份变了 → 判定身份不在本执行 pool → 必须落回全量路径
        final StackLocator.Frame second = StackLocator.locate(
                libStack(libFrame(), f(BIZ_SVC, "callB", "BizService.java", 63)), null);
        assertNotNull(second);
        assertEquals("不得复用旧行号 42", 63, second.line);
        assertEquals("BizService#callB", second.methodKey);
        assertTrue("身份不在场应重跑全量判定（来源解析重现）: " + afterFirst + " → " + probe.calls,
                probe.calls > afterFirst);
        // 判定刷新为在场身份（自愈）
        assertEquals("callB", StackLocator.DECISIONS.get(LIB_ENTRY_KEY)[2]);
    }

    // ---------- ④ 业务帧行号漂移：跟随当前栈 ----------

    @Test
    public void 业务帧行号漂移_跟随当前栈() {
        final StackLocator.Frame before = StackLocator.locate(
                libStack(libFrame(), f(BIZ_IMPL, "joinExtAndSubTable", "DeviceInfoServiceImpl.java", 42)), null);
        assertNotNull(before);
        assertEquals(42, before.line);

        // 同一入口键（共享库帧）、同一业务身份，仅行号变化（模拟源文件编辑后调用点下移）
        final StackLocator.Frame after = StackLocator.locate(
                libStack(libFrame(), f(BIZ_IMPL, "joinExtAndSubTable", "DeviceInfoServiceImpl.java", 77)), null);
        assertNotNull(after);
        assertEquals("行号应跟随当前栈（旧实现冻结在 42）", 77, after.line);
        assertEquals(before.methodKey, after.methodKey);
        assertEquals(before.sourceFile, after.sourceFile);
    }

    // ---------- ⑤ 产线档位（warn）下：定位过程对宿主控制台零输出 ----------

    @Test
    public void 入口键非业务帧_产线档位零输出() {
        // 产线 premain 注入固定 logLevel=warn。曾有一条「入口键非业务帧」无条件摘要（T-05），
        // 2026-10-14 因前提失效（T-SrcGenFrame 后，Spring 代理下生成类帧当入口键是<b>常态</b>而非异常）
        // 整条移除。本用例锁住契约：该形态下宿主 stderr 不得有任何定位打点。
        assertEquals("warn", AgentState.getLogLevel());

        StackLocator.locate(libStack(libFrame(), f(BIZ_IMPL, "joinRightAlias", "DeviceInfoServiceImpl.java", 42)), null);
        StackLocator.locate(libStack(libFrame(), f(BIZ_IMPL, "joinExtAndSubTable", "DeviceInfoServiceImpl.java", 63)), null);
        assertEquals("warn 档下定位过程不得输出任何内容: " + sink.toString(), 0, sink.size());

        // 对照：info 档是唯一诊断出口（entry/pool 可见）——提升档位才产出，且仅全量判定路径
        AgentState.setLogLevel("info");
        StackLocator.clearLocatorCachesForTest();
        sink.reset();
        final StackLocator.Frame normal = StackLocator.locate(
                mpjStack(f(BIZ_IMPL, "joinExtAndSubTable", "DeviceInfoServiceImpl.java", 63)), null);
        assertNotNull(normal);
        final String out = sink.toString();
        assertTrue("info 档应输出判定路径: " + out, out.contains("[T-SourceJumpFunnel]"));
        assertTrue("info 档摘要应含入口键: " + out, out.contains("entry=" + BIZ_IMPL + "#joinExtAndSubTable"));
        assertEquals("已移除的无条件摘要不得复活", 0, count(out, MARK));
    }

    // ---------- 夹具与断言工具 ----------

    /**
     * 共享库帧本体（第三方 JAR 帧、前缀表未收）——真机上承载该形态的实例是 T-02 之前的 MPJ 拦截器帧：
     * 每次查询都在栈上、对全应用所有查询是<b>同一帧</b>，故它当入口键时整帧缓存会把行号钉死。
     */
    private static StackTraceElement libFrame() {
        return f(LIB_INTERCEPTOR, "intercept", "MybatisJoinInterceptor.java", 30);
    }

    /**
     * 真机快照形态：库帧（前缀表未收，JAR 来源）+ 业务帧 + 调用方帧。
     * [0]=Thread#getStackTrace、[1]=agent 自身帧、[2]=MyBatis 框架帧（前缀跳过）——
     * 其后为 body，故入口键落在 body 首帧上。
     */
    private static StackTraceElement[] libStack(StackTraceElement... body) {
        final StackTraceElement[] out = new StackTraceElement[body.length + 3];
        out[0] = f("java.lang.Thread", "getStackTrace", "Thread.java", -2);
        out[1] = f("cn.song.mybatisplus.sqlvis.agent.hook.StatementHandler", "emitEvent", "StatementHandler.java", 124);
        out[2] = f("org.apache.ibatis.plugin.Plugin", "invoke", "Plugin.java", 63);
        System.arraycopy(body, 0, out, 3, body.length);
        return out;
    }

    /** 真机形态 + MPJ 拦截器帧（T-02 后已被跳过，入口键应越过它落到业务帧）。 */
    private static StackTraceElement[] mpjStack(StackTraceElement... body) {
        final StackTraceElement[] mid = new StackTraceElement[body.length + 1];
        mid[0] = f("com.github.yulichang.interceptor.MPJInterceptor", "intercept",
                "MPJInterceptor.java", 30);
        System.arraycopy(body, 0, mid, 1, body.length);
        return libStack(mid);
    }

    /** 取本次定位的入口键（与 {@code locate} 阶段一同款扫描，仅供断言）。 */
    private static String 入口键(StackTraceElement[] stack) {
        for (StackTraceElement e : stack) {
            if (!StackLocator.isFrameworkFrame(e.getClassName())) {
                return e.getClassName() + "#" + e.getMethodName();
            }
        }
        return null;
    }

    private static String candidates(StackLocator.Frame frame) {
        final StringBuilder sb = new StringBuilder();
        for (String[] c : frame.candidates) {
            sb.append(c[0]).append(' ');
        }
        return sb.toString();
    }

    private static int count(String text, String needle) {
        int n = 0;
        for (int i = text.indexOf(needle); i >= 0; i = text.indexOf(needle, i + needle.length())) {
            n++;
        }
        return n;
    }

    private static StackTraceElement f(String className, String method, String file, int line) {
        return new StackTraceElement(className, method, file, line);
    }
}
