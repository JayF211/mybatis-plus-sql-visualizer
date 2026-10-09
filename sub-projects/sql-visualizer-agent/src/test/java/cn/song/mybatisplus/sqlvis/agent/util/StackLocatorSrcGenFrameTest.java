package cn.song.mybatisplus.sqlvis.agent.util;

import cn.song.mybatisplus.sqlvis.agent.AgentState;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * T-SrcGenFrame：CGLIB 字节码生成类帧（SourceFile=&lt;generated&gt;）不得当选主位置——真机
 * 2026-10「个别条目源码位置显示 <code>[&lt;generated&gt;]</code>」报障的 agent 侧同形最小复现与守卫。
 *
 * <p>现场机制（逐环节实证，见 StackLocator 类注 T-SrcGenFrame 段）：CGLIB 增强帧类名前缀即业务包名、
 * codeSource 随宿主类落业务目录——来源维度与真实业务帧不可区分；经 Spring 代理调 MP
 * {@code ServiceImpl} 继承方法（真实方法体所在类被 {@code com.baomidou.} 前缀跳过）时，生成类帧就是
 * 最深非框架帧（池首），旧逻辑 L1/上顶收敛选中它 → sf=「业务包/&lt;generated&gt;」→ 面板渲染
 * {@code [<generated>]}。修复契约：
 * <ol>
 *   <li><b>回退业务帧</b>：生成类帧让位后 L1 落到池中真实业务帧=触发本次调用的调用方行；</li>
 *   <li><b>真无主保留</b>：池内无任何真实源文件帧（调度器直发/框架回调）时才由安全网原样采用，
 *       {@code [<generated>]} 只出现在真无主形态；</li>
 *   <li><b>命中路径同治</b>：共享代理方法键的判定缓存命中路径深帧探测同判据——不得把全量路径
 *       修对的答案在命中路径再选回生成类帧；</li>
 *   <li><b>候选列不收生成类帧</b>：IDE 侧按路径解析必 404，白占候选预算。</li>
 * </ol>
 *
 * <p>与 {@link StackLocator} 同包以便使用包内测试缝：{@code jarOriginProbe}（业务类目录/JAR 态
 * 做成确定性输入）与 {@code clearLocatorCachesForTest}（判定缓存为全局静态，跨用例清场）。
 * 形状照 {@link StackLocatorToolSiteTest} 桩口径。
 */
public class StackLocatorSrcGenFrameTest {

    /** 业务 Service（MP ServiceImpl 子类，Spring CGLIB 代理态）。真机 MerchantWalletFlowServiceImpl 同形。 */
    private static final String SVC = "com.cycz.app.service.impl.MerchantWalletFlowServiceImpl";
    /** CGLIB 增强类帧（继承方法 list 的代理覆写帧；SourceFile=<generated>，行号 -1）。 */
    private static final String ENHANCER = SVC + "$$EnhancerBySpringCGLIB$$8f2a1b3c";
    private static final String ENHANCER_FILE = "com/cycz/app/service/impl/<generated>";
    /** CGLIB FastClass 帧（MethodProxy 直调目标方法的转发帧，同为 <generated>）。 */
    private static final String FAST = SVC + "$$FastClassBySpringCGLIB$$d4e5f6a7";

    /** 调用方业务帧（目录态）：#31/#34/#35 修复后应落的真实调用点。 */
    private static final String CALLER = "com.cycz.app.service.impl.RefundServiceImpl";
    private static final String CALLER_FILE = "com/cycz/app/service/impl/RefundServiceImpl.java";

    /** 合成来源探针：com.cycz.* = 业务（目录态）；com.corp.* = JAR；其余未知。 */
    @Before
    public void 清场并接管全局态() {
        StackLocator.clearLocatorCachesForTest();
        StackLocator.setExtraSkipPrefixes(new String[0]);
        StackLocator.setExtraBizJarPrefixes(new String[0]);
        AgentState.setLogLevel("warn");
        StackLocator.jarOriginProbe = new StackLocator.JarOriginProbe() {
            @Override
            public Boolean isFromJar(String className, ClassLoader loader) {
                if (className.startsWith("com.corp.")) {
                    return Boolean.TRUE;
                }
                if (className.startsWith("com.cycz.")) {
                    return Boolean.FALSE;
                }
                return null;
            }
        };
    }

    @After
    public void 还原全局态() {
        StackLocator.jarOriginProbe = null;
        StackLocator.setExtraSkipPrefixes(new String[0]);
        StackLocator.setExtraBizJarPrefixes(new String[0]);
        StackLocator.clearLocatorCachesForTest();
        AgentState.setLogLevel("warn");
    }

    // ---------- 契约 1：代理调 ServiceImpl 继承方法 → 主位置回退调用方真实帧 ----------

    @Test
    public void 代理调ServiceImpl继承方法_主位置回退调用方真实帧() {
        // 真机 #31/#34/#35 同形栈：driver/mybatis/MP/ServiceImpl 全在跳过前缀内，
        // 生成类帧（enhancer+FastClass）是最深非框架帧，真实业务帧只有调用方。
        final StackLocator.Frame frame = StackLocator.locate(proxyStack(
                f(ENHANCER, "list", "<generated>", -1),
                f(FAST, "invoke", "<generated>", -1),
                f(CALLER, "refund", "RefundServiceImpl.java", 57),
                controllerFrame()), null);
        assertNotNull(frame);
        assertEquals("生成类帧不得当选主位置，应落调用方真实帧", CALLER_FILE, frame.sourceFile);
        assertEquals(57, frame.line);
        assertEquals("RefundServiceImpl#refund", frame.methodKey);
        // 候选列不收生成类帧（契约 4），且调用链上层帧仍在
        final String cands = candidates(frame);
        assertFalse("生成类帧不得进候选列: " + cands, cands.contains("<generated>"));
        assertTrue("调用链上层帧应留在候选列: " + cands, cands.contains(CTRL_FILE));
    }

    // ---------- 契约 2：真无主形态保留 <generated> 标注 ----------

    @Test
    public void 池内无真实源文件帧_保留generated标注() {
        // 调度器直发同形：栈上除框架帧外只有生成类帧（无调用方业务帧）——真无主。
        final StackLocator.Frame frame = StackLocator.locate(proxyStack(
                f(ENHANCER, "list", "<generated>", -1),
                f(FAST, "invoke", "<generated>", -1)), null);
        assertNotNull(frame);
        assertEquals("真无主形态应原样保留 <generated> 信号",
                ENHANCER_FILE, frame.sourceFile);
        assertEquals("生成类帧无行号（-1→0），面板渲染 [<generated>] 无行号段", 0, frame.line);
    }

    // ---------- 契约 3：判定缓存命中路径深帧探测同判据 ----------

    @Test
    public void 共享代理方法键_命中路径深帧探测不回选生成类帧() {
        // 第一笔：判定缓存写入（主位置身份=调用方 A 的方法）
        final StackLocator.Frame first = StackLocator.locate(proxyStack(
                f(ENHANCER, "list", "<generated>", -1),
                f(FAST, "invoke", "<generated>", -1),
                f(CALLER, "refund", "RefundServiceImpl.java", 57)), null);
        assertEquals(CALLER_FILE, first.sourceFile);
        final String entryKey = ENHANCER + "#list";
        assertTrue("前置：判定应已入缓存", StackLocator.DECISIONS.containsKey(entryKey));

        // 第二笔：同键、另一条业务链——工具类 B 在调用方 A 与代理帧之间（A 是祖先帧必然在场）。
        // 深帧探测若不挡生成类帧，会把全量路径修对的答案再选回 <generated>。
        final StackLocator.Frame second = StackLocator.locate(proxyStack(
                f(ENHANCER, "list", "<generated>", -1),
                f(FAST, "invoke", "<generated>", -1),
                f("com.cycz.app.service.impl.RefundQueryHelper", "pick", "RefundQueryHelper.java", 96),
                f(CALLER, "refund", "RefundServiceImpl.java", 57)), null);
        assertNotNull(second);
        assertEquals("命中路径应落更深真实业务帧（工具类真执行点）",
                "com/cycz/app/service/impl/RefundQueryHelper.java", second.sourceFile);
        assertEquals(96, second.line);
    }

    // ---------- 契约 1 补面：JAR 态业务帧与生成类帧并存，上顶收敛取真实帧 ----------

    @Test
    public void JAR业务帧与生成类帧并存_上顶收敛取真实帧() {
        // 业务类在 JAR（多模块应用形态）且未登记反向名单：L1 全 miss（生成类帧被 T-SrcGenFrame 挡、
        // JAR 帧非业务来源）→ 上顶收敛必须取首个「真实源文件」帧，而不是池首的生成类帧。
        StackLocator.jarOriginProbe = new StackLocator.JarOriginProbe() {
            @Override
            public Boolean isFromJar(String className, ClassLoader loader) {
                return className.startsWith("com.cycz.") ? Boolean.TRUE : null;
            }
        };
        final StackLocator.Frame frame = StackLocator.locate(proxyStack(
                f(ENHANCER, "list", "<generated>", -1),
                f(FAST, "invoke", "<generated>", -1),
                f(CALLER, "refund", "RefundServiceImpl.java", 57)), null);
        assertNotNull(frame);
        assertEquals("JAR 业务真实帧应压过生成类帧", CALLER_FILE, frame.sourceFile);
        assertEquals(57, frame.line);
    }

    // ---------- 夹具 ----------

    private static StackTraceElement controllerFrame() {
        return f("com.cycz.app.controller.RefundController", "submit", "RefundController.java", 42);
    }

    private static final String CTRL_FILE = "com/cycz/app/controller/RefundController.java";

    /**
     * 真机快照形态：[0]=Thread#getStackTrace、[1]=agent 自身帧，其后驱动/MyBatis/MP/ServiceImpl
     * 连续框架帧（全部落在既有跳过前缀内），body 自增强类帧起。
     */
    private static StackTraceElement[] proxyStack(StackTraceElement... body) {
        final StackTraceElement[] out = new StackTraceElement[body.length + 7];
        out[0] = f("java.lang.Thread", "getStackTrace", "Thread.java", -2);
        out[1] = f("cn.song.mybatisplus.sqlvis.agent.hook.StatementHandler", "emitEvent", "StatementHandler.java", 124);
        out[2] = f("org.postgresql.jdbc.PgPreparedStatement", "execute", "PgPreparedStatement.java", 100);
        out[3] = f("com.zaxxer.hikari.pool.HikariProxyPreparedStatement", "execute", null, -1);
        out[4] = f("org.apache.ibatis.executor.statement.PreparedStatementHandler", "query", "PreparedStatementHandler.java", 64);
        out[5] = f("com.baomidou.mybatisplus.extension.service.impl.ServiceImpl", "list", "ServiceImpl.java", 291);
        out[6] = f("com.sun.proxy.$Proxy63", "selectList", null, -1);
        System.arraycopy(body, 0, out, 7, body.length);
        return out;
    }

    private static String candidates(StackLocator.Frame frame) {
        final StringBuilder sb = new StringBuilder();
        for (String[] c : frame.candidates) {
            sb.append(c[0]).append(' ');
        }
        return sb.toString();
    }

    private static StackTraceElement f(String className, String method, String file, int line) {
        return new StackTraceElement(className, method, file, line);
    }
}
