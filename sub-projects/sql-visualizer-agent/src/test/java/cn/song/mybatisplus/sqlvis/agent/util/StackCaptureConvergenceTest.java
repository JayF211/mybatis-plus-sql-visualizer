package cn.song.mybatisplus.sqlvis.agent.util;

import cn.song.mybatisplus.sqlvis.agent.util.StackLocator.Frame;
import cn.song.mybatisplus.sqlvistest.DeepCaller;
import cn.song.mybatisplus.sqlvistest.LocateProbe;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertTrue;

/**
 * T-PS4-09 应用侧栈捕获收敛守卫（七轮审计①②③）：
 * ①一次捕获两用（携栈出口，digest 与 locate 同源）；②缓存查询前移（判定缓存命中不再逐帧来源解析）；
 * ③fat jar 安全网判定缓存（策略 A：身份+校验）。
 *
 * <p>T-AgentSourceLocateStale T-01 修订：L1 与安全网<b>合并为一条判定缓存</b>，命中路径也要建 pool
 * （行号与候选列按当前栈现算），被省掉的是逐帧 {@code Class.forName} 来源解析——本类对应断言已随之
 * 改为「命中不重扫来源 + 命中显著快于 miss」；「命中即免建池」不再是本类的守卫目标。
 *
 * <p>与 {@link StackLocator} 同包放置，以便访问包内测试通道
 * （{@code jarOriginProbe} 注入口 / {@code clearLocatorCachesForTest} 清场）。
 */
public class StackCaptureConvergenceTest {

    /** 全 JAR 来源探针（fat jar 模拟）：一切类判定为 JAR 来源并计数——计数即「逐帧来源解析」成本代理。 */
    private static final class AllJarProbe implements StackLocator.JarOriginProbe {
        int calls;

        @Override
        public Boolean isFromJar(String className, ClassLoader loader) {
            calls++;
            return Boolean.TRUE;
        }
    }

    private static void clearCaches() {
        StackLocator.clearLocatorCachesForTest();
    }

    /** F30 计数 loader：findClass 调用计数（每次尝试解析计 1，恒抛 CNFE）。 */
    private static class CountingLoader extends ClassLoader {
        int attempts = 0;

        CountingLoader() {
            super(CountingLoader.class.getClassLoader());
        }

        @Override
        protected Class<?> findClass(String name) throws ClassNotFoundException {
            attempts++;
            throw new ClassNotFoundException(name);
        }
    }

    /** F30 行为断言：同一 loader 下二次定位，同类名帧不再访问 loader（ORIGIN_MEMO 记忆化命中）。
     *  防回潮形态=摘掉记忆化后第二次定位 loader 访问数回涨。 */
    @org.junit.Test
    public void F30_来源解析记忆化_二次定位不再访问loader() {
        clearCaches();
        final CountingLoader loader = new CountingLoader();
        final StackTraceElement[] stack = new StackTraceElement[6];
        stack[0] = new StackTraceElement("java.lang.Thread", "getStackTrace", "Thread.java", -2);
        stack[1] = new StackTraceElement("cn.song.mybatisplus.sqlvis.agent.util.StackLocator",
                "locate", "StackLocator.java", 165);
        for (int i = 0; i < 4; i++) {
            stack[i + 2] = new StackTraceElement("com.absent.memo.Cls", "m" + i, "Cls.java", 10 + i);
        }
        clearCaches();
        StackLocator.locate(stack, loader);
        final int afterFirst = loader.attempts;
        org.junit.Assert.assertTrue("首次定位应至少访问一次 loader（来源解析）", afterFirst >= 1);
        StackLocator.locate(stack, loader);
        org.junit.Assert.assertEquals(
                "二次定位同类名帧应零 loader 访问（记忆化命中；摘掉 ORIGIN_MEMO 此值回涨）",
                afterFirst, loader.attempts);
        clearCaches();
    }

    // ---------- ① 一次捕获两用：携栈出口 ----------

    @Test
    public void 携栈出口_定位与摘要同源快照() {
        clearCaches();
        final StackTraceElement[] stack = {
                new StackTraceElement("java.lang.Thread", "getStackTrace", "Thread.java", -2),
                new StackTraceElement("cn.song.mybatisplus.sqlvis.agent.hook.StatementHandler",
                        "emitEvent", "StatementHandler.java", 124),
                new StackTraceElement("cn.song.mybatisplus.sqlvistest.OrderService", "createOrder",
                        "OrderService.java", 42),
                new StackTraceElement("cn.song.mybatisplus.sqlvistest.OrderController", "submit",
                        "OrderController.java", 17),
        };
        final Frame f = StackLocator.locate(stack, null);
        assertNotNull(f);
        // 定位出自同一数组：主位置 = 首个业务帧（合成类不可加载 → 来源未知 → 安全网兜底取带调试信息帧）
        assertEquals("cn/song/mybatisplus/sqlvistest/OrderService.java", f.sourceFile);
        assertEquals("OrderService#createOrder", f.methodKey);
        assertEquals(42, f.line);
        // 同一数组喂摘要：业务帧在列、agent 帧不过滤（原规则）、自身帧跳过
        final String digest = StackDigest.build(stack);
        assertNotNull(digest);
        assertTrue("摘要应含业务帧（同源快照）",
                digest.contains("cn.song.mybatisplus.sqlvistest.OrderService#createOrder:42"));
        assertTrue("摘要按原规则不过滤 agent 帧", digest.contains("StatementHandler#emitEvent"));
        assertFalse("Thread#getStackTrace 自身帧应跳过", digest.startsWith("java.lang.Thread#getStackTrace"));
    }

    @Test
    public void 携栈出口_与自捕获出口行为等价() {
        clearCaches();
        final Frame self = StackLocator.locate();
        final StackTraceElement[] captured = Thread.currentThread().getStackTrace();
        final Frame carried = StackLocator.locate(captured, null);
        assertNotNull(self);
        assertNotNull(carried);
        // 两次调用同处本测试方法（测试类在 agent 前缀包下，key 落在 JUnit 运行器帧）——同形即等价
        assertEquals(self.methodKey, carried.methodKey);
        assertEquals(self.via, carried.via);
        assertEquals(self.sourceFile, carried.sourceFile);
    }

    // ---------- ② 缓存查询前移：命中行为等价 + 防回潮 ----------

    @Test
    public void 同key二次调用_行号与候选按当前栈现算() {
        clearCaches();
        final Frame first = LocateProbe.locateHere();
        final Frame second = LocateProbe.locateHere();
        assertNotNull(first);
        // T-AgentSourceLocateStale T-01：判定缓存只存判定，结果帧每执行重建（行号/候选列不冻结）
        assertNotSame("同 key 二次调用不得复用整帧实例（冻结行号即缺陷形态）", first, second);
        assertEquals("L1", first.via);
        assertEquals("LocateProbe#locateHere", first.methodKey);
        assertEquals("cn/song/mybatisplus/sqlvistest/LocateProbe.java", first.sourceFile);
        assertEquals(first.sourceFile, second.sourceFile);
        assertEquals(first.methodKey, second.methodKey);
        assertEquals(first.line, second.line);
        assertEquals("SAFETY".equals(first.via) ? "SAFETY" : first.via, second.via);
        assertEquals("候选列应逐执行重建且等价（当前栈同形）", first.candidates.size(), second.candidates.size());
    }

    @Test
    public void 判定缓存命中不再逐帧来源解析_深栈计时防回潮() {
        clearCaches();
        // 深栈全部用「不可加载的类名」造：默认来源解析对每帧都要付一次 Class.forName 失败
        // （ClassNotFoundException 构造成本 ≫ 池构建），于是「命中省掉来源解析」在计时上可观测。
        // T-AgentSourceLocateStale T-01 后命中路径仍建 pool（行号/候选按当前栈现算），
        // 故本断言的口径是「命中不得重跑逐帧来源解析」，不再是「命中免建池」。
        final int depth = 2000;
        // 真实深栈对照（递归夹具）：命中/未命中两路径在深栈上都不退化（产出结果而非 null/异常）
        assertNotNull(DeepCaller.recurse(depth));
        assertNotNull(DeepCaller.recurse(depth));

        final StackTraceElement[] deep = new StackTraceElement[depth + 2];
        deep[0] = new StackTraceElement("java.lang.Thread", "getStackTrace", "Thread.java", -2);
        deep[1] = new StackTraceElement("cn.song.mybatisplus.sqlvis.agent.util.StackLocator",
                "locate", "StackLocator.java", 165);
        // F30 起类名互异：miss 路径「逐帧来源解析」的计时前提需要每帧都是新类名——同名深栈的
        // 重复 CNFE 已被 ORIGIN_MEMO 记忆化吸收（F30 的生产价值），同名语料的计时口径随之失效。
        for (int i = 0; i < depth; i++) {
            deep[i + 2] = new StackTraceElement("com.absent.deep.DeepFrame" + i, "m" + i, "DeepFrame.java", 100 + i);
        }

        // 预热：JIT 编译 + 两路径缓存状态各自就位
        for (int i = 0; i < 3; i++) {
            clearCaches();
            StackLocator.locate(deep, null);
        }
        for (int i = 0; i < 3; i++) {
            StackLocator.locate(deep, null);
        }
        long missMin = Long.MAX_VALUE;
        for (int i = 0; i < 7; i++) {
            clearCaches();
            final long t0 = System.nanoTime();
            StackLocator.locate(deep, null);
            missMin = Math.min(missMin, System.nanoTime() - t0);
        }
        long hitMin = Long.MAX_VALUE;
        for (int i = 0; i < 7; i++) {
            final long t0 = System.nanoTime();
            StackLocator.locate(deep, null);
            hitMin = Math.min(hitMin, System.nanoTime() - t0);
        }
        // miss = 全栈逐帧 Class.forName（O(depth) 来源解析）；hit = 池构建 + 身份在场校验（无来源解析）。
        // 防回潮：若判定缓存被绕过（或整帧缓存回退），命中路径退化为 O(depth) 来源解析，比值趋近 1。
        assertTrue("命中应显著快于 miss（hit=" + hitMin + "ns, miss=" + missMin + "ns）",
                hitMin * 3 < missMin);
    }

    // ---------- ③ fat jar 模拟：安全网判定缓存（策略 A） ----------

    @Test
    public void fatjar模拟_判定缓存生效_同key第二次不再逐帧来源解析() {
        clearCaches();
        final AllJarProbe probe = new AllJarProbe();
        StackLocator.jarOriginProbe = probe;
        try {
            final Frame first = LocateProbe.locateHere();
            // 全 JAR 来源（模拟 fat jar）：无 L1 目录帧 → 安全网；首个带调试信息帧 = LocateProbe 自身
            assertEquals("SAFETY", first.via);
            assertEquals("LocateProbe#locateHere", first.methodKey);
            assertEquals("cn/song/mybatisplus/sqlvistest/LocateProbe.java", first.sourceFile);
            final int afterFirst = probe.calls;
            assertTrue("首执行应逐帧做来源解析（pool 全扫）", afterFirst > 0);

            final Frame second = LocateProbe.locateHere();
            assertEquals("第二次执行不得再逐帧来源解析（判定缓存生效，防回潮）", afterFirst, probe.calls);
            // 判定缓存不冻结结果帧：新实例、行号/候选列每执行重建（区别于 L1 整帧缓存的同实例语义）
            assertNotSame(first, second);
            assertEquals("SAFETY", second.via);
            assertEquals(first.methodKey, second.methodKey);
            assertEquals(first.sourceFile, second.sourceFile);
            assertEquals("候选列应逐执行重建且等价（当前栈同形）", first.candidates.size(), second.candidates.size());
        } finally {
            StackLocator.jarOriginProbe = null;
            clearCaches();
        }
    }

    @Test
    public void fatjar判定缓存_身份不在场校验失败回退全量重算() {
        clearCaches();
        final AllJarProbe probe = new AllJarProbe();
        StackLocator.jarOriginProbe = probe;
        try {
            LocateProbe.locateHere();
            final int baseline = probe.calls;
            final String key = "cn.song.mybatisplus.sqlvistest.LocateProbe#locateHere";
            final String[] decision = StackLocator.DECISIONS.get(key);
            assertNotNull(decision);
            assertEquals("SAFETY", decision[0]);
            assertEquals("cn.song.mybatisplus.sqlvistest.LocateProbe", decision[1]);
            assertEquals("locateHere", decision[2]);
            // 篡改主位置身份 → 模拟引擎内部路径变化（身份不在本执行 pool）
            decision[1] = "com.corp.engine.GoneFacade";

            final Frame rebuilt = LocateProbe.locateHere();
            assertTrue("校验失败应回退全量重算（逐帧来源解析重现）", probe.calls > baseline);
            assertEquals("SAFETY", rebuilt.via);
            assertEquals("LocateProbe#locateHere", rebuilt.methodKey);
            // 判定应被刷新回在场身份（自愈）
            assertEquals("cn.song.mybatisplus.sqlvistest.LocateProbe",
                    StackLocator.DECISIONS.get(key)[1]);
        } finally {
            StackLocator.jarOriginProbe = null;
            clearCaches();
        }
    }

    @Test
    public void l1判定覆盖同key安全网判定_合并为单槽位() {
        clearCaches();
        StackLocator.jarOriginProbe = new AllJarProbe();
        try {
            LocateProbe.locateHere();   // 全 JAR → SAFETY 判定入缓存
        } finally {
            StackLocator.jarOriginProbe = null;
        }
        final String key = "cn.song.mybatisplus.sqlvistest.LocateProbe#locateHere";
        assertNotNull(StackLocator.DECISIONS.get(key));
        // 篡改身份强制校验失败 → 全量路径 → 真实环境（测试产物为目录）L1 命中
        StackLocator.DECISIONS.get(key)[1] = "com.corp.engine.GoneFacade";
        final Frame f = LocateProbe.locateHere();
        assertEquals("L1", f.via);
        // T-AgentSourceLocateStale T-01：L1 与安全网合并为一条判定缓存 —— L1 结果覆盖同一槽位
        assertEquals("L1 判定应覆盖同 key 的 SAFETY 判定（单槽位）", "L1",
                StackLocator.DECISIONS.get(key)[0]);
        clearCaches();
    }

    @Test
    public void 判定缓存命中不重扫来源_身份在场即复用() {
        // 文档化已知残面（见 StackLocator.writeCaches 注释）：判定命中且身份在场时不重扫来源，
        // 来源环境从全 JAR 切回目录后的首个执行仍走判定缓存——候选列每执行重建可兜底，属可恢复降级。
        clearCaches();
        StackLocator.jarOriginProbe = new AllJarProbe();
        try {
            LocateProbe.locateHere();
        } finally {
            StackLocator.jarOriginProbe = null;
        }
        final Frame masked = LocateProbe.locateHere();
        assertEquals("SAFETY", masked.via);
        assertEquals("LocateProbe#locateHere", masked.methodKey);
        clearCaches();
    }
}
