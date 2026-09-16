package cn.song.mybatisplus.sqlvis.agent.util;

import java.security.CodeSource;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 调用栈定位（T-M56-04 / T-AgentSourceJump）：execute 时取一次栈，跳过框架/基础设施帧，取用户代码帧。
 *
 * <p>三层判定（T-AgentSourceJump）：
 * <ol>
 *   <li><b>L2 前缀名单</b>：{@link #FRAME_SKIP_PREFIXES} 廉价跳过已知基础设施家族；</li>
 *   <li><b>L1 来源过滤</b>：开发态用户业务类来自<b>目录</b>（target/classes、build/classes），
 *       第三方依赖恒来自 <b>JAR</b>——按 {@code ProtectionDomain.codeSource} 判定，与类名无关，
 *       根治「前缀黑名单打地鼠」（PageHelper 等未收录插件被误选为用户帧）；</li>
 *   <li><b>安全网</b>：L1 过滤后无候选（典型=fat jar 运行，业务类也在 jar 内）→ 回退首个非框架帧，
 *       禁止「过滤到空导致跳转能力消失」的劣化。</li>
 * </ol>
 *
 * <p>热点红线（§4.2 #5）：结果按调用方缓存（key = 首个非框架帧 class#method）——禁 per-execute
 * 重复全栈解析 + JNI 反查代理（DebugTools 热点）。缓存有界（512，超出整表清空——调用方数量
 * 与业务方法数同阶，整表清空语义无损）。
 *
 * <p>框架帧过滤规则与 IDE 侧 CallerSourceLocator 对齐（agent 侧独立副本——agent 不引 IDE 类）。
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

    /** 候选帧上限（IDE 侧解析回退用；过深无意义且加长事件体）。 */
    private static final int MAX_CANDIDATES = 3;

    private static final ConcurrentHashMap<String, Frame> CACHE = new ConcurrentHashMap<String, Frame>();
    private static final int CACHE_MAX = 512;

    public static final class Frame {
        public final String sourceFile;
        public final int line;
        public final String methodKey;
        /** 栈序候选 [file, line]（≤3 条，排除主位置；IDE 侧解析回退用）。 */
        public final List<String[]> candidates;

        Frame(String sourceFile, int line, String methodKey, List<String[]> candidates) {
            this.sourceFile = sourceFile;
            this.line = line;
            this.methodKey = methodKey;
            this.candidates = candidates;
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
        String primaryKey = null;
        int primaryLine = 0;
        final List<String[]> pool = new ArrayList<String[]>(4); // 非框架帧（栈序）——L1 决策与候选来源

        for (StackTraceElement f : stack) {
            final String cn = f.getClassName();
            if (isFrameworkFrame(cn)) {
                continue;
            }
            final String key = cn + "#" + f.getMethodName();
            if (primaryKey == null) {
                primaryKey = key;
                final Frame cached = CACHE.get(key);
                if (cached != null) {
                    return cached;
                }
            }
            final String file = toRelativePath(cn, f.getFileName());
            final int line = f.getLineNumber() >= 0 ? f.getLineNumber() : 0;
            pool.add(new String[]{file, String.valueOf(line), cn});
        }
        if (pool.isEmpty()) {
            return null;
        }

        String primaryFile = null;
        // L1 来源过滤：优先取「非 JAR 来源」帧（开发态用户业务类在目录）
        for (String[] c : pool) {
            if (Boolean.FALSE.equals(loadedFromJar(c[2], loader))) {
                primaryFile = c[0];
                primaryLine = Integer.parseInt(c[1]);
                break;
            }
        }
        // 安全网：L1 无命中（fat jar 运行等）→ 回退首个非框架帧（等价既有行为，不劣化）
        if (primaryFile == null) {
            primaryFile = pool.get(0)[0];
            primaryLine = Integer.parseInt(pool.get(0)[1]);
        }

        final List<String[]> candidates = new ArrayList<String[]>(MAX_CANDIDATES);
        for (String[] c : pool) {
            if (c[0] == null || c[0].equals(primaryFile)) {
                continue;
            }
            candidates.add(new String[]{c[0], c[1]});
            if (candidates.size() >= MAX_CANDIDATES) {
                break;
            }
        }

        // 主位置无调试信息（file 为 null）不可作为定位——候选首位顶上，避免必然失败的位置进列表
        if (primaryFile == null) {
            if (candidates.isEmpty()) {
                return null;
            }
            final String[] first = candidates.remove(0);
            primaryFile = first[0];
            primaryLine = Integer.parseInt(first[1]);
        }

        final Frame frame = new Frame(primaryFile, Math.max(primaryLine, 0),
                simpleMethodKey(primaryKey), candidates);
        if (CACHE.size() >= CACHE_MAX) {
            CACHE.clear();
        }
        CACHE.putIfAbsent(primaryKey, frame);
        return frame;
    }

    public static boolean isFrameworkFrame(String className) {
        for (String p : FRAME_SKIP_PREFIXES) {
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
