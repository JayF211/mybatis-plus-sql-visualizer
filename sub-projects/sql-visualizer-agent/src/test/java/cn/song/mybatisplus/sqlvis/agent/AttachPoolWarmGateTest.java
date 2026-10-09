package cn.song.mybatisplus.sqlvis.agent;

import java.io.File;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * T-AttachPoolWarm：真机复现级端到端（MySQL 8 + 存量池连接复用）——harness 照抄
 * {@code AttachGateTest}（子 JVM + loopback server + loadAgent + 读 CMD 帧），换真实 MySQL 驱动，
 * 分四模式逐一判「attach 后事件是否到达」。这是 {@code StatementWeaveEndToEndTest} 明确留档的
 * 「先建池再 retransform 的真机时序」判别用例（单测 JVM 拿不到 Instrumentation）：
 *
 * <ul>
 *   <li><b>C</b>（对照，预期通）：attach 后**新建**连接执行 SQL——确认 MySQL 名单/harness 无误。</li>
 *   <li><b>A</b>（主场景）：attach 前建好一条连接并执行一次查询（warm=存量池物理连接），attach 后
 *       在**同一条连接**上每条新 PS → executeQuery()（MyBatis + Hikari 复用形态）。</li>
 *   <li><b>B</b>：attach 前 prepareStatement 拿到 PS 并执行一次，attach 后复用**同一 PS 对象**——
 *       一期盲区（attach 前 prepare 无登记）实测。</li>
 *   <li><b>D</b>：语义同 A，叠加第三方 -javaagent（CoolRequest）——观察 retransform 是否被拖红。</li>
 * </ul>
 *
 * 每条模式把子进程 stdout/stderr 落盘到 {@code build/attachpool-out/<mode>.log}（redirectOutput），
 * 并原文摘出 {@code [SqlVisAgent] attach retransform:} 行与可能的首条事件行。
 *
 * 环境：MySQL localhost:3306（root/root，库 mpj_demo）。不可达时整类 Assume 跳过（不伪造读数）。
 */
public class AttachPoolWarmGateTest {

    private static final int WAIT_SECONDS = 30;
    private static final String JDBC_URL = "jdbc:mysql://localhost:3306/mpj_demo";
    private static final String DB_USER = "root";
    private static final String DB_PASS = "root";

    /** CoolRequest agent jar（runIde 沙盒装、疑似被注入应用 JVM）——模式 D 用。 */
    private static final Path COOL_REQUEST_AGENT =
            Paths.get("C:/Users/USER/.config/.cool-request/request/lib/cool-request-agent.jar");

    /** CoolRequest 原生 hook agent dll（attach 在跑进程的真正入口；jar 无 agentmain，见 D2）。 */
    private static final Path COOL_REQUEST_HOOK_DLL =
            Paths.get("C:/Users/USER/.config/.cool-request/request/lib/cool-request-hook-agentlib.dll");

    private Process child;

    @org.junit.After
    public void cleanupChild() {
        if (child != null) {
            child.destroyForcibly();
            child = null;
        }
    }

    // ---------- 四模式 ----------

    @org.junit.Test
    public void 模式C_对照_attach后新建连接_事件到达() throws Exception {
        RunResult r = runMode("C", "attachpool_c", new ArrayList<String>(), WAIT_SECONDS * 1000L);
        report("C", r);
        org.junit.Assert.assertTrue("模式C（对照，attach 后新建连接）应收到事件——MySQL 名单/harness 自检失败\n"
                + r.asBlock(), r.eventReceived);
    }

    @org.junit.Test
    public void 模式A_主场景_存量连接上新建PS_事件到达() throws Exception {
        RunResult r = runMode("A", "attachpool_a", new ArrayList<String>(), WAIT_SECONDS * 1000L);
        report("A", r);
        org.junit.Assert.assertTrue("模式A（主场景，存量池连接复用）应收到事件——"
                + "attach 前建好连接、attach 后同连接新建 PS executeQuery\n" + r.asBlock(), r.eventReceived);
    }

    @org.junit.Test
    public void 模式B_attach前prepare的PS复用_一期盲区表现() throws Exception {
        RunResult r = runMode("B", "attachpool_b", new ArrayList<String>(), 15_000L, null);
        report("B", r);
        // 已知盲区：attach 前 prepare 的 PS 无登记 → 零事件且跳过计数（unresolvedCount 递增）。
        // 此处只记录实测形态，不断言零事件（若某天二期兜底落地，事件出现即代表能力扩张）。
        org.junit.Assert.assertNotNull("模式B 必须产出 attach retransform 行", r.attachRetransformLine);
        org.junit.Assert.assertNotNull("模式B 必须命中盲区取证行（宿主侧「钩子在跑、SQL 取不到」证据）",
                r.blindSpotLine);
    }

    @org.junit.Test
    public void 模式D_第三方agent干扰_存量连接复用() throws Exception {
        org.junit.Assume.assumeTrue("CoolRequest agent jar 不在位，模式 D 跳过",
                Files.isRegularFile(COOL_REQUEST_AGENT));
        List<String> jvmArgs = new ArrayList<String>();
        jvmArgs.add("-javaagent:" + COOL_REQUEST_AGENT.toString());
        RunResult r = runMode("D", "attachpool_d", jvmArgs, WAIT_SECONDS * 1000L, null);
        report("D", r);
        // 不断言必通——本模式的目的就是把「加了 CoolRequest 是否零事件」实测出来（读数进报告）。
        org.junit.Assert.assertNotNull("模式D 必须产出 attach retransform 行（或子进程早期死亡，见日志）",
                r.attachRetransformLine);
    }

    /**
     * D2（更贴真机）：CoolRequest 先经 {@code VirtualMachine.loadAgent} 挂到**在跑进程**（= 用户现场
     * 「沙盒 IDE 里装了 CoolRequest、已注入应用 JVM」），随后我们的 agent 再挂——检验第三方 transformer
     * 先注册是否拖红我们的 retransform。语义同模式 A（存量连接 + 新 PS）。
     */
    @org.junit.Test
    public void 模式D2_CoolRequest先attach在跑进程_再挂本agent() throws Exception {
        org.junit.Assume.assumeTrue("CoolRequest agent jar 不在位，模式 D2 跳过",
                Files.isRegularFile(COOL_REQUEST_AGENT));
        RunResult r = runMode("D2", "attachpool_d2", new ArrayList<String>(), WAIT_SECONDS * 1000L,
                COOL_REQUEST_AGENT);
        report("D2", r);
        org.junit.Assert.assertNotNull("模式D2 必须产出 attach retransform 行", r.attachRetransformLine);
    }

    /**
     * D3（真机注入入口）：CoolRequest 的原生 hook agent 经 {@code VirtualMachine.loadAgentPath}
     * 挂到在跑进程（= 沙盒 IDE 里 CoolRequest 对应用 JVM 的真实注入方式；jar 的 Agent-Class 无
     * {@code agentmain}，D2 已实证标准 jar attach 走不通）。随后我们的 agent 再挂。
     */
    @org.junit.Test(timeout = 120_000L)
    public void 模式D3_CoolRequest原生dll_attach在跑进程_再挂本agent() throws Exception {
        org.junit.Assume.assumeTrue("CoolRequest hook dll 不在位，模式 D3 跳过",
                Files.isRegularFile(COOL_REQUEST_HOOK_DLL));
        RunResult r = runMode("D3", "attachpool_d3", new ArrayList<String>(), WAIT_SECONDS * 1000L,
                COOL_REQUEST_HOOK_DLL, true);
        report("D3", r);
        org.junit.Assert.assertNotNull("模式D3 必须产出 attach retransform 行（或子进程早期死亡，见日志）",
                r.attachRetransformLine);
    }

    // ---------- 核心运行 ----------

    private RunResult runMode(String mode, String keyword, List<String> extraJvmArgs, long waitMs)
            throws Exception {
        return runMode(mode, keyword, extraJvmArgs, waitMs, null, false);
    }

    private RunResult runMode(String mode, String keyword, List<String> extraJvmArgs, long waitMs,
                              Path preAttachAgentJar) throws Exception {
        return runMode(mode, keyword, extraJvmArgs, waitMs, preAttachAgentJar, false);
    }

    private RunResult runMode(String mode, String keyword, List<String> extraJvmArgs, long waitMs,
                              Path preAttachFile, boolean preAttachAsPath) throws Exception {
        final String javaBin = Paths.get(System.getProperty("java.home"), "bin", "java.exe").toFile().exists()
                ? Paths.get(System.getProperty("java.home"), "bin", "java.exe").toString()
                : Paths.get(System.getProperty("java.home"), "bin", "java").toString();
        final File agentJar = locateAgentJar();
        final Path mysqlJar = locateMysqlJar();
        final String childClasses = Paths.get("build", "classes", "java", "childapp").toAbsolutePath().toString();

        // 1) loopback server（IDE 角色）
        java.net.ServerSocket server =
                new java.net.ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress());
        server.setSoTimeout(WAIT_SECONDS * 1000);

        // 2) 子 JVM：classpath = mysql + childapp 源集（刻意不含 agent 类）
        final Path outDir = Paths.get("build", "attachpool-out");
        Files.createDirectories(outDir);
        final Path childLog = outDir.resolve("mode-" + mode + ".log");
        List<String> cmd = new ArrayList<String>();
        cmd.add(javaBin);
        cmd.addAll(extraJvmArgs);
        cmd.add("-Dfile.encoding=UTF-8");
        cmd.add("-cp");
        cmd.add(mysqlJar.toAbsolutePath() + File.pathSeparator + childClasses);
        cmd.add("cn.song.mybatisplus.sqlvis.agent.ChildPoolApp");
        cmd.add(mode);
        cmd.add(JDBC_URL);
        cmd.add(DB_USER);
        cmd.add(DB_PASS);
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(true);
        pb.redirectOutput(childLog.toFile());
        child = pb.start();

        // 2.5) 等子 JVM 报 READY <pid>（warm 资源已在位 = 存量连接/PS 已建）
        String pidLine = waitForLine(childLog, "READY ");
        org.junit.Assert.assertNotNull("子 JVM 未就绪（mode=" + mode + "）:\n" + tail(childLog), pidLine);
        final long pid = Long.parseLong(pidLine.split(" ")[1].trim());

        // 3) attach + loadAgent（可选：先挂第三方 agent 到在跑进程，模拟 CoolRequest 已注入）
        com.sun.tools.attach.VirtualMachine vm =
                com.sun.tools.attach.VirtualMachine.attach(Long.toString(pid));
        String preAttachResult = null;
        if (preAttachFile != null) {
            try {
                if (preAttachAsPath) {
                    vm.loadAgentPath(preAttachFile.toString());
                } else {
                    vm.loadAgent(preAttachFile.toString(), "");
                }
                preAttachResult = (preAttachAsPath ? "loadAgentPath" : "loadAgent") + ":ok";
            } catch (Throwable t) {
                preAttachResult = (preAttachAsPath ? "loadAgentPath" : "loadAgent")
                        + ":" + t.getClass().getName() + ": " + t.getMessage();
            }
        }
        try {
            vm.loadAgent(agentJar.getAbsolutePath(), "port=" + server.getLocalPort() + ";logLevel=info");
        } catch (com.sun.tools.attach.AgentLoadException e) {
            // 已知形态 AgentLoadException("0")：以握手为准，继续
            if (!"0".equals(e.getMessage())) {
                org.junit.Assert.fail("loadAgent 失败且非已知形态: " + e);
            }
        }

        // 4) accept 会话 + 读帧 + 下发 CONFIG disabled=false（对齐真机，压住 15s 看门狗）
        java.net.Socket session = server.accept();
        CopyOnWriteArrayList<String> events = new CopyOnWriteArrayList<String>();
        List<String> hellos = new CopyOnWriteArrayList<String>();
        CopyOnWriteArrayList<String> acks = new CopyOnWriteArrayList<String>();
        Thread reader = readerThread(session, events, hellos, acks);
        sendConfig(session, false);

        // 5) 等事件（或超时）
        boolean got = waitForEvent(events, keyword, waitMs);
        Thread.sleep(500); // 让 attach retransform 行落盘

        RunResult r = new RunResult();
        r.mode = mode;
        r.eventReceived = got;
        r.eventSample = firstEventContaining(events, keyword);
        r.helloCount = hellos.size();
        r.attachRetransformLine = lineContaining(childLog, "[SqlVisAgent] attach retransform:");
        r.firstFireLine = lineContaining(childLog, "[SqlVisAgent] 织入通道首条事件已产出");
        r.watchdogLine = lineContaining(childLog, "未收到任何 CONFIG");
        r.blindSpotLine = lineContaining(childLog, "织入通道登记盲区");
        r.preAttachResult = preAttachResult;
        r.childLogPath = childLog.toAbsolutePath().toString();
        r.childTail = tail(childLog, 30);

        // 6) 清理
        try { vm.detach(); } catch (Throwable ignore) { }
        try { session.close(); } catch (Throwable ignore) { }
        try { server.close(); } catch (Throwable ignore) { }
        if (child != null) { child.destroy(); }
        return r;
    }

    // ---------- 结果承载 ----------

    private static final class RunResult {
        String mode;
        boolean eventReceived;
        String eventSample;
        int helloCount;
        String attachRetransformLine;
        String firstFireLine;
        String watchdogLine;
        String blindSpotLine;
        String preAttachResult;
        String childLogPath;
        String childTail;

        String asBlock() {
            StringBuilder sb = new StringBuilder();
            sb.append("\n===== ATTACH-POOL MODE ").append(mode).append(" =====\n");
            sb.append("eventReceived=").append(eventReceived).append('\n');
            sb.append("helloCount=").append(helloCount).append('\n');
            sb.append("eventSample=").append(eventSample).append('\n');
            sb.append("attachRetransform=").append(attachRetransformLine).append('\n');
            sb.append("firstFire=").append(firstFireLine).append('\n');
            sb.append("blindSpot=").append(blindSpotLine).append('\n');
            sb.append("watchdog=").append(watchdogLine).append('\n');
            sb.append("preAttach=").append(preAttachResult).append('\n');
            sb.append("childLog=").append(childLogPath).append('\n');
            sb.append("--- child tail ---\n").append(childTail).append('\n');
            sb.append("=====================");
            return sb.toString();
        }
    }

    private static void report(String mode, RunResult r) {
        System.out.println(r.asBlock());
        System.out.flush();
    }

    // ---------- 辅助 ----------

    private static void sendConfig(java.net.Socket session, boolean disabled) throws Exception {
        OutputStream out = session.getOutputStream();
        byte[] body = ("{\"disabled\":" + disabled + ",\"desensitize\":true,\"stackFrames\":24}")
                .getBytes(StandardCharsets.UTF_8);
        java.io.DataOutputStream d = new java.io.DataOutputStream(out);
        d.writeInt(body.length);
        d.writeByte(3); // CMD_CONFIG
        d.write(body);
        d.flush();
    }

    private static Thread readerThread(java.net.Socket session, final List<String> events,
                                       final List<String> hellos, final List<String> acks) {
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    java.io.DataInputStream in = new java.io.DataInputStream(session.getInputStream());
                    while (true) {
                        int len = in.readInt();
                        int cmd = in.readUnsignedByte();
                        byte[] body = new byte[len];
                        in.readFully(body);
                        String s = new String(body, StandardCharsets.UTF_8);
                        if (cmd == 4) {
                            hellos.add(s);
                        } else if (cmd == 1) {
                            events.add(s);
                        } else if (cmd == 5) {
                            acks.add(s);
                        }
                    }
                } catch (Throwable ignore) {
                    // 会话结束
                }
            }
        }, "attachpool-reader");
        t.setDaemon(true);
        t.start();
        return t;
    }

    private static boolean waitForEvent(List<String> events, String keyword, long timeoutMs)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (firstEventContaining(events, keyword) != null) {
                return true;
            }
            Thread.sleep(200);
        }
        return false;
    }

    private static String firstEventContaining(List<String> events, String keyword) {
        for (String e : events) {
            if (e.contains(keyword)) {
                return e.length() > 400 ? e.substring(0, 400) : e;
            }
        }
        return null;
    }

    private static String waitForLine(Path log, String prefix) throws Exception {
        long deadline = System.currentTimeMillis() + WAIT_SECONDS * 1000L;
        while (System.currentTimeMillis() < deadline) {
            String line = lineContaining(log, prefix);
            if (line != null) {
                return line;
            }
            Thread.sleep(200);
        }
        return null;
    }

    private static String lineContaining(Path log, String marker) {
        try {
            if (!Files.exists(log)) {
                return null;
            }
            for (String line : readLinesUtf8(log)) {
                if (line.contains(marker)) {
                    return line;
                }
            }
        } catch (Exception ignore) {
            // 读取失败视为未命中
        }
        return null;
    }

    private static String tail(Path log) {
        return tail(log, 10);
    }

    private static String tail(Path log, int n) {
        try {
            List<String> lines = readLinesUtf8(log);
            return String.join("\n", lines.subList(Math.max(0, lines.size() - n), lines.size()));
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * 子进程 stdout 按 UTF-8 读（子进程以 -Dfile.encoding=UTF-8 启动）——**不能用 ISO_8859_1**：
     * 中文取证行（织入通道首条/登记盲区）按 ISO_8859_1 解码成 mojibake，contains 恒不命中
     * （实证：附件日志里行在场，测试侧搜索却为 null）。用 byte→String 解码（畸形字节替换，
     * 不抛 MalformedInputException）。
     */
    private static List<String> readLinesUtf8(Path log) throws Exception {
        String all = new String(Files.readAllBytes(log), StandardCharsets.UTF_8);
        List<String> out = new ArrayList<String>();
        for (String s : all.split("\r?\n", -1)) {
            out.add(s);
        }
        return out;
    }

    private static File locateAgentJar() {
        Path p = Paths.get("build", "dist", "mps-sql-agent.jar");
        if (!Files.exists(p)) {
            p = Paths.get("..", "agent", "build", "dist", "mps-sql-agent.jar");
        }
        if (!Files.exists(p)) {
            throw new AssertionError("agent fat jar 未找到（agentFatJar 任务产物）");
        }
        return p.toFile();
    }

    private static Path locateMysqlJar() {
        for (String e : System.getProperty("java.class.path").split(File.pathSeparator)) {
            if (e.contains("mysql-connector-j") && e.endsWith(".jar")) {
                return Paths.get(e);
            }
        }
        throw new AssertionError("mysql-connector-j jar 不在测试 classpath");
    }

    /** 供其它用例复用的探活：仅当 MySQL 可达才跑（不可达时 Assume 跳过，不伪造读数）。 */
    static boolean mysqlReachable() {
        try (java.net.Socket s = new java.net.Socket()) {
            s.connect(new java.net.InetSocketAddress("127.0.0.1", 3306), 1000);
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
