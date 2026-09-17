package cn.song.mybatisplus.sqlvis.agent.util;

import cn.song.mybatisplus.sqlvis.agent.AgentState;

import java.security.CodeSource;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 调用栈定位（T-M56-04 / T-AgentSourceJump / T-SourceJumpFunnel）：execute 时取一次栈，跳过框架/基础设施帧，取用户代码帧。
 *
 * <p>三层判定（T-AgentSourceJump）：
 * <ol>
 *   <li><b>L2 前缀名单</b>：{@link #FRAME_SKIP_PREFIXES} 廉价跳过已知基础设施家族；</li>
 *   <li><b>L1 来源过滤</b>：开发态用户业务类来自<b>目录</b>（target/classes、build/classes），
 *       第三方依赖恒来自 <b>JAR</b>——按 {@code ProtectionDomain.codeSource} 判定，与类名无关，
 *       根治「前缀黑名单打地鼠」（PageHelper 等未收录插件被误选为用户帧）；</li>
 *   <li><b>安全网</b>：L1 过滤后无候选（典型=fat jar 运行，业务类也在 jar 内）→ 回退首个带调试信息的非框架帧，
 *       禁止「过滤到空导致跳转能力消失」的劣化。</li>
 * </ol>
 *
 * <p>T-SourceJumpFunnel（自研框架汇聚门面适配）：公司内自研数据访问框架（不经 MyBatis）的引擎全链帧
 * 不在 L2 名单、且可能来自目录（同工程多模块）——L1 会把「最深引擎帧」选成主位置，且所有业务调用共用
 * 同一个汇聚帧。对策：
 * <ol>
 *   <li><b>候选增厚</b>：{@link Frame#candidates} 每条含 [file, line, className, method] 四列（列尾追加，旧 IDE 只读前两列兼容）；</li>
 *   <li><b>缓存防合流</b>：仅 L1 目录帧命中时写缓存（安全网/上顶路径结果不缓存，每次重算——汇聚场景下一次错不能处处错）；</li>
 *   <li><b>上顶收敛</b>：主位置优先取首个「带调试信息」的候选，无调试信息的 null-file 帧不再顶上（M4 修复）；</li>
 *   <li><b>诊断打点</b>：{@code logLevel=info} 时输出判定路径（primaryKey / 来源路径 L1|SAFETY|PROMOTE / pool 帧），真机定谳用。</li>
 * </ol>
 *
 * <p>热点红线（§4.2 #5）：L1 命中的主流路径仍走调用方缓存（key = 选中帧 class#method）；缓存有界（512）。
 * 安全网路径不缓存——其重算频率上界 = 走安全网的调用方种类数（有界），不触 per-execute 全栈解析红线。
 *
 * <p>框架帧过滤规则与 IDE 侧 CallerSourceLocator 对齐（agent 侧独立副本——agent 不引 IDE 类）；
 * 动态前缀（用户登记的自研框架包名）经 CONFIG 下发，见 {@link #setExtraSkipPrefixes(String[])}。
 */
public final class StackLocator {

    /** 框架/基础设施帧前缀（与 CallerSourceLocator 跳过规则对齐 + 驱动/池/ORM/插件家族）。 */
    private static final String[] FRAME_SKIP_PREFIXES = {
            "java.", "javax.", "jdk.", "sun.", "com.sun.", "jdk.internal.",
            "com.intellij.", "org.jetbrains.",
            // 本 agent
            "cn.song.mybatisplus.sqlvis.",
            // 驱动家族
            "com.mysql.", "org.mariadb.", "org.postgresql.", "org.h2.", "org.hsqldb.",
            "org.sqlite.", "com.microsoft.sqlserver.", "oracle.jdbc.", "dm.jdbc.",
            "com.gbasedbt.", "com.kingbase8.",
            // 连接池 / ORM / Spring 数据层
            "com.zaxxer.", "com.alibaba.druid.", "com.mchange.", "org.apache.commons.dbcp2.",
            "org.apache.tomcat.",
            "org.apache.ibatis.", "com.baomidou.", "org.hibernate.",
            "org.springframework.", "org.aspectj.", "net.sf.cglib.", "org.objectweb.asm.",
            "com.sun.proxy.", "org.jboss.",
            // T-AgentSourceJump：数据层插件/基础设施家族补齐（漏收即被误选为用户帧——PageHelper 实证）
            "com.github.pagehelper.", "org.mybatis.", "net.sf.jsqlparser.",
            "com.alibaba.", "org.apache.", "com.google.", "com.fasterxml.",
            "io.netty.", "io.lettuce.", "redis.clients.", "io.micrometer.", "io.seata.",
            "org.slf4j.", "ch.qos.", "org.ehcache.", "net.sf.ehcache.", "com.github.benmanes.",
            "org.quartz.", "com.netflix.", "org.elasticsearch.", "com.mongodb.",
            // 注：刻意不收通用工具库（如 cn.hutool.）——工具类可能被用户业务代码直接调用，误收即跳错
    };

    /** T-SourceJumpFunnel P1-3：用户登记的动态跳过前缀（CONFIG 热下发，volatile 整组替换）。 */
    private static volatile String[] EXTRA_SKIP_PREFIXES = new String[0];

    /** 候选帧上限（IDE 侧解析回退用；过深无意义且加长事件体）。T-SourceJumpFunnel：3→8——汇聚门面下深层业务帧须可见。 */
    private static final int MAX_CANDIDATES = 8;

    private static final ConcurrentHashMap<String, Frame> CACHE = new ConcurrentHashMap<String, Frame>();
    private static final int CACHE_MAX = 512;

    public static final class Frame {
        public final String sourceFile;
        public final int line;
        public final String methodKey;
        /** 栈序候选 [file, line, className, method]（T-SourceJumpFunnel 增厚至四列；≤8 条，排除主位置；IDE 侧解析回退用）。 */
        public final List<String[]> candidates;
        /** 诊断：主位置判定路径（L1 / SAFETY / PROMOTE），info 日志与测试断言用。 */
        public final String via;

        Frame(String sourceFile, int line, String methodKey, List<String[]> candidates, String via) {
            this.sourceFile = sourceFile;
            this.line = line;
            this.methodKey = methodKey;
            this.candidates = candidates;
            this.via = via;
        }
    }

    public static Frame locate() {
        return locate(null);
    }

    /**
     * @param hint 能看见应用类的 ClassLoader（Spring Boot 下 = 上下文 loader / 被织入对象 loader）——
     *             用于判定帧来源是否 JAR；null 时取下线程上下文 loader。
     */
    public static Frame locate(ClassLoader hint) {
        final ClassLoader loader = resolveLoader(hint);
        final StackTraceElement[] stack = Thread.currentThread().getStackTrace();
        // [0]=getStackTrace [1]=locate [2]=executeWithCapture [3]=invoke 反射帧——从 0 扫描无妨，
        // 反射/agent 自身帧被前缀规则跳过
        final List<String[]> pool = new ArrayList<String[]>(8);   // 非框架帧（栈序）：[file, line, className, method]
        String lastNonFrameworkKey = null;

        for (StackTraceElement f : stack) {
            final String cn = f.getClassName();
            if (isFrameworkFrame(cn)) {
                continue;
            }
            if (lastNonFrameworkKey == null) {
                lastNonFrameworkKey = cn + "#" + f.getMethodName();
            }
            final String file = toRelativePath(cn, f.getFileName());
            final int line = f.getLineNumber() >= 0 ? f.getLineNumber() : 0;
            pool.add(new String[]{file, String.valueOf(line), cn, f.getMethodName()});
        }
        if (pool.isEmpty()) {
            return null;
        }

        final Frame cached = lastNonFrameworkKey == null ? null : CACHE.get(lastNonFrameworkKey);
        if (cached != null) {
            return cached;
        }

        // L1 来源过滤：优先取「非 JAR 来源」帧（开发态用户业务类在目录）
        String primaryFile = null;
        int primaryLine = 0;
        String primaryKey = null;
        String via = "SAFETY";
        for (String[] c : pool) {
            if (Boolean.FALSE.equals(loadedFromJar(c[2], loader))) {
                primaryFile = c[0];
                primaryLine = Integer.parseInt(c[1]);
                primaryKey = c[2] + "#" + c[3];
                via = "L1";
                break;
            }
        }
        // P0-4 上顶收敛：L1 无命中 → 优先取首个「带调试信息」的非框架帧（安全网不劣化、
        // 且无调试信息的帧不可作为定位——旧逻辑会拿 null-file 帧再从候选上顶，可达汇聚门面）
        if (primaryFile == null) {
            for (String[] c : pool) {
                if (c[0] != null) {
                    primaryFile = c[0];
                    primaryLine = Integer.parseInt(c[1]);
                    primaryKey = c[2] + "#" + c[3];
                    break;
                }
            }
        }
        // 全无调试信息 → 原样取首个非框架帧（file 为 null，本次不产出位置）
        if (primaryFile == null) {
            final String[] first = pool.get(0);
            primaryFile = first[0];
            primaryLine = Integer.parseInt(first[1]);
            primaryKey = first[2] + "#" + first[3];
        }

        final List<String[]> candidates = new ArrayList<String[]>(MAX_CANDIDATES);
        for (String[] c : pool) {
            if (c[0] == null || c[0].equals(primaryFile)) {
                continue;
            }
            // T-SourceJumpFunnel P0-2：候选增厚为 [file, line, className, method] 四列
            candidates.add(new String[]{c[0], c[1], c[2], c[3]});
            if (candidates.size() >= MAX_CANDIDATES) {
                break;
            }
        }

        if (primaryFile == null) {
            // 全链无调试信息：本次不产出位置、不缓存（下次重试）
            return null;
        }

        final Frame frame = new Frame(primaryFile, Math.max(primaryLine, 0),
                simpleMethodKey(primaryKey), candidates, via);
        // T-SourceJumpFunnel P0-3 缓存防合流：仅 L1 目录帧命中才写缓存——安全网结果若被缓存，
        // 汇聚场景下「第一个调用者」的引擎帧会服务此后一切业务语句（一次错=处处错）。
        // L1 命中（主流路径）仍走缓存热路径；安全网路径重算频率上界=调用方种类数，有界。
        if ("L1".equals(via) && CACHE.size() >= CACHE_MAX) {
            CACHE.clear();
        }
        if ("L1".equals(via)) {
            CACHE.putIfAbsent(lastNonFrameworkKey, frame);
        }
        diagnose(lastNonFrameworkKey, frame, pool, loader);
        return frame;
    }

    /** T-SourceJumpFunnel P0-1 诊断打点：logLevel=info 时输出判定路径（真机定谳 M2/M3/M4 用），默认档零输出。 */
    private static void diagnose(String entryKey, Frame frame, List<String[]> pool, ClassLoader loader) {
        if (!"info".equalsIgnoreCase(AgentState.getLogLevel())) {
            return;
        }
        final StringBuilder sb = new StringBuilder(256);
        sb.append("[T-SourceJumpFunnel] primary=").append(frame.sourceFile).append(':').append(frame.line)
                .append(" via=").append(frame.via).append(" entry=").append(entryKey)
                .append(" pool=").append(pool.size());
        for (int i = 0; i < Math.min(pool.size(), 8); i++) {
            final String[] c = pool.get(i);
            sb.append(" | #").append(i).append(' ').append(c[2]).append('#').append(c[3])
                    .append(':').append(c[1]).append(" jar=").append(loadedFromJar(c[2], loader));
        }
        System.err.println(sb);
    }

    /** T-SourceJumpFunnel P1-3：CONFIG 下发的用户前缀登记（整组替换，空/畸形输入安全）。 */
    public static void setExtraSkipPrefixes(String[] prefixes) {
        if (prefixes == null || prefixes.length == 0) {
            EXTRA_SKIP_PREFIXES = new String[0];
            return;
        }
        final List<String> cleaned = new ArrayList<String>(prefixes.length);
        for (String p : prefixes) {
            if (p != null && !p.trim().isEmpty()) {
                cleaned.add(p.trim());
            }
        }
        EXTRA_SKIP_PREFIXES = cleaned.toArray(new String[0]);
    }

    public static String[] getExtraSkipPrefixes() {
        return EXTRA_SKIP_PREFIXES;
    }

    public static boolean isFrameworkFrame(String className) {
        for (String p : FRAME_SKIP_PREFIXES) {
            if (className.startsWith(p)) {
                return true;
            }
        }
        for (String p : EXTRA_SKIP_PREFIXES) {
            if (className.startsWith(p)) {
                return true;
            }
        }
        return className.contains("$Proxy");
    }

    /**
     * 帧来源是否 JAR（L1）：true=第三方依赖；false=目录（开发态用户业务代码）；null=未知
     * （类不可加载 / codeSource 缺失——不武断判为用户帧，仅在安全网阶段兜底采用）。
     */
    public static Boolean loadedFromJar(String className, ClassLoader loader) {
        if (className == null) {
            return null;
        }
        try {
            final Class<?> c = Class.forName(className, false, loader);
            final CodeSource cs = c.getProtectionDomain().getCodeSource();
            if (cs == null || cs.getLocation() == null) {
                return null;
            }
            final String loc = cs.getLocation().toString();
            return loc.endsWith(".jar") || loc.contains(".jar!") || loc.contains(".jar/");
        } catch (Throwable t) {
            return null;
        }
    }

    private static ClassLoader resolveLoader(ClassLoader hint) {
        if (hint != null) {
            return hint;
        }
        final ClassLoader ctx = Thread.currentThread().getContextClassLoader();
        return ctx != null ? ctx : StackLocator.class.getClassLoader();
    }

    /** FQCN + 文件名 → 相对路径（pkg/.../File.java，正斜杠；与标记行格式一致）。 */
    public static String toRelativePath(String className, String fileName) {
        if (fileName == null) {
            return null;
        }
        final int dot = className.lastIndexOf('.');
        final String pkg = dot < 0 ? "" : className.substring(0, dot).replace('.', '/');
        return pkg.isEmpty() ? fileName : pkg + "/" + fileName;
    }

    /** com.example.service.UserService#query → UserService#query。 */
    private static String simpleMethodKey(String fqMethod) {
        final int hash = fqMethod.indexOf('#');
        final String cn = hash < 0 ? fqMethod : fqMethod.substring(0, hash);
        final int dot = cn.lastIndexOf('.');
        final String simple = dot < 0 ? cn : cn.substring(dot + 1);
        return hash < 0 ? simple : simple + fqMethod.substring(hash);
    }

    private StackLocator() {
    }
}
