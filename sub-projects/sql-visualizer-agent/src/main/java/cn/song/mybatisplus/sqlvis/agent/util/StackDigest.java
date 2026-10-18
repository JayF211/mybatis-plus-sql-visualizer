package cn.song.mybatisplus.sqlvis.agent.util;

import cn.song.mybatisplus.sqlvis.agent.AgentState;

import java.util.ArrayList;
import java.util.List;

/**
 * 调用栈快照压缩（T-SourceJumpFunnel P2-2；T-StackHoverUi 两端采样改版）：execute 时把全栈压成
 * 「pkg.Class#method:line」单串（← 连接，栈顶在左），供 IDE 详情面板往上追踪。
 *
 * <p>预算（T-StackHoverUi 用户可配）：总帧数 B=[AgentState#getStackMaxFrames]（默认 24，
 * 钳位 [12,64]）。**栈底锚点先跳过尾部基础设施帧**（线程容器/线程池/JDK，见
 * {@link #isInfraFrame}——用户真机实测：Tomcat 尾巴会把栈底 8 帧占满、Controller 被挤进省略段），
 * 锚定最深业务帧后：深度 ≤B 全链保留（原行为）；&gt;B 两端采样——栈顶取 B-8 帧
 * （引擎/门面侧），栈底取最深业务帧起向上 8 帧（业务/Controller 侧），中段以标记段
 * {@code <…中间省略 N 帧…>} 连接（IDE 新视图展示为省略节点，旧解析器仍可跳过无 {@code #} 的段）。
 * 非白名单尾帧仅在同应用包根的两条调用帧与 Controller 可关联时穿透；
 * 这是 Web 调用链的定向增强，不是对所有业务类的通用身份判定；无法确认则保留原采样兜底。
 *
 * <p>总长硬顶 MAX_LEN=max(2000, B×96)：两端形态下超长先缩栈顶再出标记；
 * 单帧异常超过 200 字符时保留首尾并压缩中间，以防底段本身突破硬顶。
 * 被压缩的极长类名不保证 IDE 侧能解析/跳转；正常长度的栈底业务帧完整保留，
 * 全链形态沿用旧尾截语义。
 *
 * <p>T-PS4-09 一次捕获两用：产线主路径经 locate 同一快照调 {@link #build(StackTraceElement[])}
 * （读 [AgentState] 配置）；显式帧数出口 {@link #build(StackTraceElement[], int)} 为纯函数
 * （单测用）。过滤规则不变：仅跳 Thread#getStackTrace 自身帧，业务帧与 agent 帧均不过滤。
 */
public final class StackDigest {

    /** 栈底固定保留帧数（业务/Controller 侧，T-StackHoverUi A 案）。 */
    private static final int BOTTOM_RESERVED = 8;

    /** 总帧数钳位下限（保证栈顶至少 4 帧）。 */
    private static final int MIN_FRAMES = 12;

    /** 总帧数钳位上限（事件体可控）。 */
    private static final int MAX_FRAMES_CAP = 64;

    /** 无参出口（兼容/测试）：当前线程全栈 + AgentState 配置预算。 */
    public static String build() {
        return build(Thread.currentThread().getStackTrace());
    }

    /** 产线出口：以 AgentState 配置预算压缩给定快照（T-PS4-09 与 locate 共享同一捕获）。 */
    public static String build(StackTraceElement[] stack) {
        return build(stack, AgentState.getStackMaxFrames());
    }

    /** 纯函数（单测用）：maxFrames 为用户配置的总帧数（内部钳位）。 */
    public static String build(StackTraceElement[] stack, int maxFrames) {
        if (stack == null || stack.length == 0) {
            return null;
        }
        final List<StackTraceElement> frames = new ArrayList<>(stack.length);
        for (StackTraceElement f : stack) {
            // [0]=Thread.getStackTrace 自身：跳过
            if (f.getClassName().equals(Thread.class.getName()) && "getStackTrace".equals(f.getMethodName())) {
                continue;
            }
            frames.add(f);
        }
        if (frames.isEmpty()) {
            return null;
        }
        final int budget = clamp(maxFrames);
        final long maxLen = Math.max(2000L, (long) budget * 96L);
        // 栈底锚点先跳过尾部基础设施帧（T-StackHoverUi 用户真机二轮反馈：线程池/Tomcat 尾巴
        // 会把栈底 8 帧占满，Controller 在被省略的中段）——锚定最深业务帧后再判预算
        int lastBusiness = frames.size() - 1;
        while (lastBusiness >= 0 && isInfraFrame(frames.get(lastBusiness))) {
            lastBusiness--;
        }
        if (lastBusiness < 0) {
            // 全 infra 极端形态兜底：退回字面两端
            lastBusiness = frames.size() - 1;
        }
        // 只在 Controller 与同根调用帧同时在场时穿透未知尾帧；
        // 命名空间与来源都无法确认时保留原锚点。
        final int controller = findBusinessController(frames, frames.size() - 1);
        if (controller >= 0) {
            lastBusiness = controller;
        }
        final int depth = lastBusiness + 1;
        if (depth <= budget) {
            return join(frames, 0, depth, maxLen);
        }
        // 两端采样：栈顶 + 中段标记 + 栈底；超长先缩栈顶（缩掉的顶帧计入省略数），业务帧永不因长度被截
        final int topCount = Math.max(1, budget - BOTTOM_RESERVED);
        // 栈底取「最深业务帧起向上」的 BOTTOM_RESERVED 帧（可能含业务链中段夹带的反射/代理帧——
        // 属业务路径上下文，保留）；被跳过的尾部 infra 帧不占省略计数（纯噪音不出现在快照里）
        final int bStart = Math.max(topCount, lastBusiness - (BOTTOM_RESERVED - 1));
        String top = join(frames, 0, topCount, Long.MAX_VALUE);
        String bottom = join(frames, bStart, lastBusiness - bStart + 1, Long.MAX_VALUE);
        int elided = bStart - topCount;
        String marker = elisionMarker(elided);
        int curTop = topCount;
        while (curTop > 1 && top.length() + marker.length() + bottom.length() + 4L > maxLen) {
            curTop--;
            elided++;
            top = join(frames, 0, curTop, Long.MAX_VALUE);
            marker = elisionMarker(elided);
        }
        return top + "<-" + marker + "<-" + bottom;
    }

    /**
     * 在未知尾帧存在时，选择栈中最后一组同应用包根的调用帧与 Controller；
     * 不把 com、org、cn 等顶层命名空间当成应用身份。框架命名空间里的项目类
     * 仅在目录来源或已登记业务 JAR 包名前缀时采用，无法确认则沿用原采样。
     */
    private static int findBusinessController(List<StackTraceElement> frames, int end) {
        for (int i = end; i >= 0; i--) {
            final String controller = frames.get(i).getClassName();
            if (!simpleName(controller).endsWith("Controller") || !isApplicationCandidate(controller)) {
                continue;
            }
            final String root = applicationRoot(controller);
            if (root == null) {
                continue;
            }
            for (int j = i - 1; j >= Math.max(0, i - 24); j--) {
                final String caller = frames.get(j).getClassName();
                if (!caller.equals(controller) && caller.startsWith(root) && isApplicationCandidate(caller)) {
                    return i;
                }
            }
        }
        return -1;
    }

    private static boolean isApplicationCandidate(String className) {
        if (StackLocator.isRegisteredBusinessFrame(className) || !StackLocator.isFrameworkFrame(className)) {
            return true;
        }
        return Boolean.FALSE.equals(StackLocator.loadedFromJar(
                className, Thread.currentThread().getContextClassLoader()));
    }

    private static String simpleName(String name) {
        return name.substring(name.lastIndexOf('.') + 1);
    }

    private static String applicationRoot(String className) {
        int dot = className.indexOf('.');
        if (dot < 0) return null;
        int second = className.indexOf('.', dot + 1);
        if (second < 0) return null;
        int third = className.indexOf('.', second + 1);
        if (className.startsWith("cn.song.") && third >= 0) {
            return className.substring(0, third + 1);
        }
        if (className.startsWith("org.springframework.") && third >= 0) {
            int fourth = className.indexOf('.', third + 1);
            return fourth >= 0 ? className.substring(0, fourth + 1) : null;
        }
        return className.substring(0, second + 1);
    }

    /**
     * 尾部基础设施帧判定（只用于栈底锚点，不改栈顶）——线程容器/线程池/JDK/Servlet 过滤器链的
     * 「尾巴」帧。用户链两轮实测：①尾部 8 帧=Thread/ThreadPoolExecutor/NioEndpoint/coyote；
     * ②线程池跳过后锚点又落到 spring 过滤器链（ApplicationFilterChain/OncePerRequestFilter），
     * Controller 仍在省略段。名单收容器/JDK/Servlet 管道与 spring web（filter+servlet MVC 链）+
     * spring boot（devtools 重启线程等）：这些在栈尾永远是管道；**spring 业务中间件不收**
     * （aop/tx/context——业务栈中段的 AOP/代理链属于上下文，跳了会把 Controller 与 Service 拆断；
     * 栈尾方向 com.sun.proxy 的 JDK 代理帧由既有 com.sun. 前缀覆盖，恰好穿透 AOP 壳锚到业务类）。
     */
    private static boolean isInfraFrame(StackTraceElement f) {
        final String cn = f.getClassName();
        return cn.startsWith("java.") || cn.startsWith("javax.") || cn.startsWith("sun.")
                || cn.startsWith("com.sun.") || cn.startsWith("jdk.")
                || cn.startsWith("org.apache.tomcat.") || cn.startsWith("org.apache.catalina.")
                || cn.startsWith("org.apache.coyote.") || cn.startsWith("org.apache.juli.")
                || cn.startsWith("org.eclipse.jetty.") || cn.startsWith("io.undertow.")
                || cn.startsWith("org.springframework.web.")
                || cn.startsWith("org.springframework.security.web.")
                || cn.startsWith("org.springframework.boot.");
    }

    /** 中段省略标记段（无 {@code #}：新 UI 展示省略节点，旧解析器可跳过）。 */
    private static String elisionMarker(int elided) {
        return "<…中间省略 " + elided + " 帧…>";
    }

    private static int clamp(int maxFrames) {
        if (maxFrames < MIN_FRAMES) {
            return MIN_FRAMES;
        }
        if (maxFrames > MAX_FRAMES_CAP) {
            return MAX_FRAMES_CAP;
        }
        return maxFrames;
    }

    /**
     * 把 frames[from, from+count) 逐帧追加为「类#方法:行」（← 连接）；maxLen 为总长硬顶，
     * 超长截断加 … 后缀（语义与旧实现逐字一致）；maxLen 传 Long.MAX_VALUE 表示不截（由调用方统管）。
     */
    private static String join(List<StackTraceElement> frames, int from, int count, long maxLen) {
        final StringBuilder sb = new StringBuilder(256);
        int used = 0;
        for (int i = from; i < frames.size() && used < count; i++) {
            final StackTraceElement f = frames.get(i);
            if (used > 0) {
                sb.append("<-");
            }
            String text = f.getClassName() + '#' + f.getMethodName();
            final int line = f.getLineNumber();
            if (line >= 0) {
                text += ":" + line;
            }
            // 恶意/生成类名或方法名可能单帧超过整串预算。保留两端及方法尾，
            // 保证底段即使 8 个极长帧也有可控的长度上界。
            if (text.length() > 200) {
                text = text.substring(0, 96) + '…' + text.substring(text.length() - 103);
            }
            sb.append(text);
            used++;
            if (sb.length() >= maxLen) {
                sb.append("…");
                break;
            }
        }
        final String s = sb.length() > maxLen ? sb.substring(0, (int) maxLen) + "…" : sb.toString();
        return used == 0 ? "" : s;
    }

    private StackDigest() {
    }
}
