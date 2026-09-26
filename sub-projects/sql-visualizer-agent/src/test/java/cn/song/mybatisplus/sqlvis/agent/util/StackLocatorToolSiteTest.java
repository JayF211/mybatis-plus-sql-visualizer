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
 * T-ToolSiteBizFrame：「SQL 执行点在业务工具类」跳转上顶复现与守卫（demo 仓库 PR !1 E 组语料的
 * agent 侧同形最小复现，形状照其 §7.2 桩边界取证口径）。
 *
 * <p>覆盖两个机制：
 * <ol>
 *   <li><b>机制 B′（E34 同形，缺陷复现）</b>：共享引擎帧（JAR 内核，前缀表未收）作入口键；
 *       同一 Service 方法先直发一条 SQL（此时它是最深业务帧，判定被写进缓存），再经业务工具类
 *       发第二条（工具类帧在其栈上是<b>更深</b>的业务帧，而 Service 帧是<b>祖先帧、必然在场</b>）——
 *       旧实现的「身份在场即采用」把主位置钉在 Service 的「调用工具类」那一行，真执行点只在候选列。</li>
 *   <li><b>机制 A（E29/E30→E31 同形）</b>：业务工具类以 JAR 形态运行时 L1 把它当第三方帧，
 *       主位置上顶到第一个目录帧（调用方 Service）；同一共享引擎帧下两个业务方法先后触发
 *       不得跨方法串位（T-AgentSourceLocateStale T-01 回归护栏）。</li>
 * </ol>
 *
 * <p>与 {@link StackLocator} 同包以便使用包内测试缝：{@code jarOriginProbe}（把「内核/工具类来自
 * JAR、业务 Service 来自目录」做成确定性输入）与 {@code clearLocatorCachesForTest}（判定缓存为
 * 全局静态，跨用例清场）。
 */
public class StackLocatorToolSiteTest {

    /** 平台内核帧（JAR，直连 JDBC 的最深引擎帧；前缀表未收 → 作共享入口键）。E34/E29 同形。 */
    private static final String KERNEL = "com.corp.platform.SqlKernel";
    private static final String KERNEL_FILE = "com/corp/platform/SqlKernel.java";
    private static final String ENTRY_KEY = KERNEL + "#query";

    /** 业务工具类（目录态，真执行点所在）。E34 同形。 */
    private static final String TOOL_DIR = "cn.song.selfdevdemo.util.PlanTreeQueryBuilder";
    private static final String TOOL_DIR_FILE = "cn/song/selfdevdemo/util/PlanTreeQueryBuilder.java";
    /** 业务工具类（JAR 态）。E29/E30→E31 同形——机制 A 的主体。 */
    private static final String TOOL_JAR = "com.corp.shared.util.PlanTreeQueryBuilder";
    private static final String TOOL_JAR_FILE = "com/corp/shared/util/PlanTreeQueryBuilder.java";

    /** 调用方 Service（目录态）：direct=直发行、viaTool=调用工具类行、first/second=共用入口对。 */
    private static final String SVC = "cn.song.selfdevdemo.service.impl.SourceCaptureToolSiteServiceImpl";
    private static final String SVC_FILE = "cn/song/selfdevdemo/service/impl/SourceCaptureToolSiteServiceImpl.java";
    private static final String CTRL = "cn.song.selfdevdemo.controller.ToolSiteController";
    private static final String CTRL_FILE = "cn/song/selfdevdemo/controller/ToolSiteController.java";

    /**
     * 合成来源探针：{@code com.corp.} = JAR（内核与 JAR 态工具类）、
     * {@code cn.song.selfdevdemo.} = 开发态目录（业务 Service 与目录态工具类）、其余未知。
     */
    @Before
    public void 清场并接管全局态() throws Exception {
        StackLocator.clearLocatorCachesForTest();
        StackLocator.setExtraSkipPrefixes(new String[0]);
        AgentState.setLogLevel("warn");
        StackLocator.jarOriginProbe = new StackLocator.JarOriginProbe() {
            @Override
            public Boolean isFromJar(String className, ClassLoader loader) {
                if (className.startsWith("com.corp.")) {
                    return Boolean.TRUE;
                }
                if (className.startsWith("cn.song.selfdevdemo.")) {
                    return Boolean.FALSE;
                }
                return null;
            }
        };
        originalErr = System.err;
        sink = new ByteArrayOutputStream();
        System.setErr(new PrintStream(sink, true, "UTF-8"));
    }

    private PrintStream originalErr;
    private ByteArrayOutputStream sink;

    @After
    public void 还原全局态() {
        System.setErr(originalErr);
        StackLocator.jarOriginProbe = null;
        StackLocator.setExtraSkipPrefixes(new String[0]);
        StackLocator.clearLocatorCachesForTest();
        AgentState.setLogLevel("warn");
    }

    // ---------- 机制 B′（E34 同形）：先直发后经工具类，第二条必须落真执行点 ----------

    @Test
    public void 共享引擎帧_先直发后经工具类_第二条落真执行点() {
        // SQL① 直发：内核帧（JAR，入口键）→ Service（最深业务帧，L1 命中 :129）
        final StackLocator.Frame direct = StackLocator.locate(engineStack(
                kernelFrame(), f(SVC, "mixed", "SourceCaptureToolSiteServiceImpl.java", 129), controllerFrame()), null);
        assertNotNull(direct);
        assertEquals(SVC_FILE, direct.sourceFile);
        assertEquals(129, direct.line);
        assertEquals("首次直发应写入判定（主位置身份=Service 方法）",
                "mixed", StackLocator.DECISIONS.get(ENTRY_KEY)[2]);

        // SQL② 经工具类：内核帧（同一入口键）→ 工具类（更深业务帧 :96）→ Service（祖先帧，:132=调用工具类行）
        final StackLocator.Frame viaTool = StackLocator.locate(engineStack(
                kernelFrame(), f(TOOL_DIR, "buildTree", "PlanTreeQueryBuilder.java", 96),
                f(SVC, "mixed", "SourceCaptureToolSiteServiceImpl.java", 132), controllerFrame()), null);
        assertNotNull(viaTool);
        // 修前红：身份（Service#mixed）在场 → 主位置钉在 :132（调用工具类那一行）
        assertEquals("第二条应落工具类内真执行点", TOOL_DIR_FILE, viaTool.sourceFile);
        assertEquals(96, viaTool.line);
        assertEquals("PlanTreeQueryBuilder#buildTree", viaTool.methodKey);
        // 判定应刷新为真执行点（不是沿用浅帧身份）
        assertEquals("判定应刷新为工具类身份",
                "buildTree", StackLocator.DECISIONS.get(ENTRY_KEY)[2]);
        // Service 调用帧仍在候选列（IDE 侧可回溯）
        assertTrue("Service 帧应留在候选列: " + candidates(viaTool),
                candidates(viaTool).contains(SVC_FILE));

        // 第三条回归直发：不得被反向钉在工具类身份上（自愈回 Service 行）
        final StackLocator.Frame directAgain = StackLocator.locate(engineStack(
                kernelFrame(), f(SVC, "mixed", "SourceCaptureToolSiteServiceImpl.java", 129), controllerFrame()), null);
        assertNotNull(directAgain);
        assertEquals("直发 SQL 应回落 Service 自身行", SVC_FILE, directAgain.sourceFile);
        assertEquals(129, directAgain.line);
    }

    // ---------- 机制 A 护栏（E30→E31 同形）：共用引擎帧下两业务方法不得串位 ----------

    @Test
    public void 共享引擎帧_两个业务方法各经JAR工具类_各落自己调用行() {
        // E30：JAR 工具类帧被 L1 上顶（机制 A 现状）→ 落 svcFirst 调用行
        final StackLocator.Frame first = StackLocator.locate(engineStack(
                kernelFrame(), f(TOOL_JAR, "buildTree", "PlanTreeQueryBuilder.java", 38),
                f(SVC, "first", "SourceCaptureToolSiteServiceImpl.java", 82), controllerFrame()), null);
        assertNotNull(first);
        assertEquals("机制 A 现状锚点：JAR 工具类未登记时落调用方", SVC_FILE, first.sourceFile);
        assertEquals(82, first.line);

        // E31：同一入口键、另一业务方法——T-01 后各落自己调用行（整帧缓存时代会串到 82）
        final StackLocator.Frame second = StackLocator.locate(engineStack(
                kernelFrame(), f(TOOL_JAR, "buildTree", "PlanTreeQueryBuilder.java", 53),
                f(SVC, "second", "SourceCaptureToolSiteServiceImpl.java", 94), controllerFrame()), null);
        assertNotNull(second);
        assertEquals(94, second.line);
        assertEquals("SourceCaptureToolSiteServiceImpl#second", second.methodKey);
        // JAR 工具类真执行点保留在候选列（区分机制 A 与 B′ 的判据，PR §7.5）
        assertTrue("JAR 工具类帧应留在候选列: " + candidates(second),
                candidates(second).contains(TOOL_JAR_FILE));
    }

    // ---------- 机制 B′ 缓解路径（E34 P1 档）：登记内核包名后入口键各自独立 ----------

    @Test
    public void 登记内核包名后_两业务方法入口键各自独立_行号互不干扰() {
        StackLocator.setExtraSkipPrefixes(new String[]{"com.corp.platform."});
        final StackLocator.Frame direct = StackLocator.locate(engineStack(
                kernelFrame(), f(SVC, "mixed", "SourceCaptureToolSiteServiceImpl.java", 129), controllerFrame()), null);
        assertNotNull(direct);
        assertEquals(129, direct.line);
        // 入口键越过多带带的内核帧落到业务帧（前缀跳过语义），判定不再被共享键占用
        assertFalse("内核帧应被跳过", StackLocator.DECISIONS.containsKey(ENTRY_KEY));

        final StackLocator.Frame viaTool = StackLocator.locate(engineStack(
                kernelFrame(), f(TOOL_DIR, "buildTree", "PlanTreeQueryBuilder.java", 96),
                f(SVC, "mixed", "SourceCaptureToolSiteServiceImpl.java", 132), controllerFrame()), null);
        assertNotNull(viaTool);
        assertEquals("入口键独立后经工具类的执行落真执行点", TOOL_DIR_FILE, viaTool.sourceFile);
        assertEquals(96, viaTool.line);
    }

    // ---------- 夹具 ----------

    private static StackTraceElement kernelFrame() {
        return f(KERNEL, "query", "SqlKernel.java", 88);
    }

    private static StackTraceElement controllerFrame() {
        return f(CTRL, "mixed", "ToolSiteController.java", 57);
    }

    /**
     * 真机快照形态：[0]=Thread#getStackTrace、[1]=agent 自身帧、[2]=驱动帧（前缀跳过）——
     * 其后为 body，故入口键落在内核帧上（body 首帧）。
     */
    private static StackTraceElement[] engineStack(StackTraceElement... body) {
        final StackTraceElement[] out = new StackTraceElement[body.length + 3];
        out[0] = f("java.lang.Thread", "getStackTrace", "Thread.java", -2);
        out[1] = f("cn.song.mybatisplus.sqlvis.agent.hook.StatementHandler", "emitEvent", "StatementHandler.java", 124);
        out[2] = f("com.mysql.cj.jdbc.ClientPreparedStatement", "execute", "ClientPreparedStatement.java", 100);
        System.arraycopy(body, 0, out, 3, body.length);
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
