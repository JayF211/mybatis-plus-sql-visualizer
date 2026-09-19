package cn.song.mybatisplus.sqlvis.agent.util;

import cn.song.mybatisplus.sqlvis.agent.AgentState;

import java.security.CodeSource;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 调用栈定位（T-M56-04 / T-AgentSourceJump / T-SourceJumpFunnel / T-PS4-09）：
 * execute 时对本次栈快照扫描一次，跳过框架/基础设施帧，取用户代码帧；同一快照还可喂
 * {@link StackDigest} 产摘要——产线经 {@link #locate(StackTraceElement[], ClassLoader)} 携栈出口
 * 一次捕获两用（T-PS4-09，禁每条 SQL 两次 getStackTrace）。
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
 *   <li><b>缓存防合流</b>：整帧缓存仅 L1 目录帧命中时写（安全网/上顶路径的<b>整帧结果</b>不缓存——汇聚场景下
 *       「第一个调用者」的候选列会服务此后一切业务语句，一次错=处处错）；T-PS4-09 起安全网另缓存
 *       「判定」（主位置身份+校验，候选列与行号每执行重建，不复蹈整帧合流缺陷），见 locate 内注释；</li>
 *   <li><b>上顶收敛</b>：主位置优先取首个「带调试信息」的候选，无调试信息的 null-file 帧不再顶上（M4 修复）；</li>
 *   <li><b>诊断打点</b>：{@code logLevel=info} 时输出判定路径（primaryKey / 来源路径 L1|SAFETY / pool 帧），真机定谳用；
 *       仅全量判定路径（缓存双 miss）输出，缓存命中路径静默。</li>
 * </ol>
 *
 * <p>T-PS4-09 两段式扫描与缓存（热点红线 §4.2 #5）：
 * <ol>
 *   <li><b>缓存查询前移</b>：key = 首个非框架帧 class#method，前缀扫描到即查 {@link #CACHE}——
 *       命中路径到此为止，不再全栈建 pool（原实现在 pool 构建之后才查缓存，命中白付全栈成本）；</li>
 *   <li><b>安全网判定缓存</b>：fat jar（全 JAR 来源）恒走安全网，原实现每执行全量重扫 pool 并逐帧
 *       {@code Class.forName} 解析来源；现按 key 缓存「判定」跳过逐帧来源解析（策略 A，详见写入点注释）。</li>
 * </ol>
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

    /** 调用方整帧缓存：key = 首个非框架帧 class#method。有界（512，溢出整体清空——既有粗粒度策略）。 */
    private static final ConcurrentHashMap<String, Frame> CACHE = new ConcurrentHashMap<String, Frame>();
    private static final int CACHE_MAX = 512;

    /**
     * T-PS4-09 安全网「判定」缓存（策略 A）：key → [via, 主位置 className, 主位置 method]。
     * 只存判定不存结果帧——整帧入缓存会冻结行号与候选列（P0-3 缺陷形态，禁）；
     * 命中须经「身份在场」校验（见 locate），有界同 {@link #CACHE_MAX}。
     * 包内可见 = 同包测试断言/清场通道，产线勿外用。
     */
    static final ConcurrentHashMap<String, String[]> SAFETY_DECISIONS = new ConcurrentHashMap<String, String[]>();

    public static final class Frame {
        public final String sourceFile;
        public final int line;
        public final String methodKey;
        /** 栈序候选 [file, line, className, method]（T-SourceJumpFunnel 增厚至四列；≤8 条，排除主位置；IDE 侧解析回退用）。 */
        public final List<String[]> candidates;
        /** 诊断：主位置判定路径（L1=目录帧命中 / SAFETY=安全网，上顶收敛与首帧兜底均计入 SAFETY）。info 日志与测试断言用。 */
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
        return locate((ClassLoader) null);
    }

    /**
     * @param hint 能看见应用类的 ClassLoader（Spring Boot 下 = 上下文 loader / 被织入对象 loader）——
     *             用于判定帧来源是否 JAR；null 时取下线程上下文 loader。
     */
    public static Frame locate(ClassLoader hint) {
        // 自捕获出口保留（兼容/测试）；产线主路径走下方携栈出口（T-PS4-09 一次捕获两用）
        return locate(Thread.currentThread().getStackTrace(), hint);
    }

    /**
     * T-PS4-09 携栈出口（一次捕获两用）：调用方已持有本线程快照时复用之——StatementHandler 用同一
     * 快照喂本入口与 {@link StackDigest#build(StackTraceElement[])}，消除每条 SQL 第二次全栈捕获。
     *
     * @param stack 须为当前线程 {@code Thread.currentThread().getStackTrace()} 的原样产物
     *              （本方法按数组内容扫描，不再自取、不校验线程归属）。
     * @param hint  能看见应用类的 ClassLoader；null 时取下线程上下文 loader。
     */
    public static Frame locate(StackTraceElement[] stack, ClassLoader hint) {
        final ClassLoader loader = resolveLoader(hint);
        // ── 阶段一（廉价前缀扫描）：key = 首个非框架帧 class#method。
        // [0]=getStackTrace [1]=locate 自身帧——从 0 扫描无妨，反射/agent 自身帧被前缀规则跳过
        String entryKey = null;
        int firstNonFramework = -1;
        for (int i = 0; i < stack.length; i++) {
            if (!isFrameworkFrame(stack[i].getClassName())) {
                entryKey = stack[i].getClassName() + "#" + stack[i].getMethodName();
                firstNonFramework = i;
                break;
            }
        }
        if (entryKey == null) {
            return null;   // 全栈皆框架帧（原「pool 为空」语义）
        }
        // 缓存查询前移（T-PS4-09）：命中路径到此为止，不再全栈建 pool。
        // 命中返回缓存整帧（行号/候选列冻结为首次入缓存时的值）——既有 L1 整帧缓存语义，行为等价保留。
        final Frame cached = CACHE.get(entryKey);
        if (cached != null) {
            return cached;
        }

        // ── 阶段二（miss 才付）：非框架帧 pool（栈序）：[file, line, className, method]。
        // 首帧（firstNonFramework 处）必入池，pool 恒非空。
        final List<String[]> pool = new ArrayList<String[]>(8);
        for (int i = firstNonFramework; i < stack.length; i++) {
            final StackTraceElement f = stack[i];
            final String cn = f.getClassName();
            if (isFrameworkFrame(cn)) {
                continue;
            }
            final String file = toRelativePath(cn, f.getFileName());
            final int line = f.getLineNumber() >= 0 ? f.getLineNumber() : 0;
            pool.add(new String[]{file, String.valueOf(line), cn, f.getMethodName()});
        }

        String primaryFile = null;
        int primaryLine = 0;
        String primaryKey = null;
        String via = "SAFETY";

        // 安全网判定缓存（T-PS4-09 策略 A）命中：须通过「身份在场」校验——缓存的主位置身份仍出现在
        // 本执行 pool 中才复用（即 via 一致性的廉价形态：身份在场 = 当前栈形与判定时同构），跳过
        // 逐帧 Class.forName 来源解析。校验失败（引擎内部路径变化、无调试信息链路漂移）落回全量路径
        // 并在写入点刷新判定。
        final String[] decision = SAFETY_DECISIONS.get(entryKey);
        if (decision != null) {
            for (String[] c : pool) {
                if (decision[1].equals(c[2]) && decision[2].equals(c[3])) {
                    primaryFile = c[0];
                    primaryLine = Integer.parseInt(c[1]);
                    primaryKey = c[2] + "#" + c[3];
                    via = decision[0];
                    break;
                }
            }
        }

        // L1 来源过滤：优先取「非 JAR 来源」帧（开发态用户业务类在目录）
        if (primaryFile == null) {
            for (String[] c : pool) {
                if (Boolean.FALSE.equals(loadedFromJar(c[2], loader))) {
                    primaryFile = c[0];
                    primaryLine = Integer.parseInt(c[1]);
                    primaryKey = c[2] + "#" + c[3];
                    via = "L1";
                    break;
                }
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
        writeCaches(entryKey, frame, via, primaryKey);
        diagnose(entryKey, frame, pool, loader);
        return frame;
    }

    /**
     * 缓存写入（T-SourceJumpFunnel P0-3 定案 + T-PS4-09 策略 A），仅全量判定路径到达：
     *
     * <p><b>P0-3 缓存防合流（定案依据，禁回退）</b>：整帧结果缓存会冻结「候选列 + 行号」。自研框架
     * 汇聚门面（不经 MyBatis）下所有业务调用共享同一汇聚帧 → 同一 key——若把安全网/上顶的整帧结果
     * 入缓存，「第一个调用者」的引擎帧与候选列会服务此后一切业务语句（一次错=处处错）。故整帧缓存
     * 仅 L1 目录帧命中才写（主流开发态路径），与 T-PS4-09 前一致。
     *
     * <p><b>T-PS4-09 策略 A（安全网判定缓存）为何不复蹈 P0-3</b>：缓存的是「判定」（via + 主位置身份
     * class#method），不是结果帧——候选列与行号每执行从当前栈重建，各调用方自身的业务帧信息永不冻结，
     * 合流缺陷赖以发生的「整帧服务后续一切调用」不成立。且命中须校验主位置身份仍在本执行 pool 中
     * （不在即全量重算并刷新判定），引擎内部路径变化可自愈。被冻结的身份本身对共享 key 的调用方是
     * 确定性相同的（安全网取首个带调试信息帧，共享汇聚帧在各调用方栈中同形，重算也得到同一身份），
     * 冻结它不产生调用方间分歧。原实现安全网每执行全量重扫的所谓「上界=调用方种类数」并不成立——
     * fat jar 下每条 SQL 都在重扫（per-execute 全栈解析），正是本策略消除的成本。
     *
     * <p>已知残面（如实记录）：混合部署下同 key 先被全 JAR 调用链写成 SAFETY 判定、后被含目录帧的
     * 调用链命中时，判定在场会掩盖一次 L1 机会（主位置落到 JAR 帧）——候选列仍含该目录帧兜底，
     * 可恢复；且共享 key 的合流面在既有 L1 整帧缓存下本就存在（先到的 L1 结果同样服务后续调用者），
     * 非本策略新增。
     */
    private static void writeCaches(String entryKey, Frame frame, String via, String primaryKey) {
        if ("L1".equals(via)) {
            if (CACHE.size() >= CACHE_MAX) {
                CACHE.clear();
            }
            CACHE.putIfAbsent(entryKey, frame);
            // 同 key 的 SAFETY 判定已被 L1 证伪，清除（判定缓存与整帧缓存互斥合流）
            SAFETY_DECISIONS.remove(entryKey);
        } else {
            if (SAFETY_DECISIONS.size() >= CACHE_MAX) {
                SAFETY_DECISIONS.clear();
            }
            // 拆开存 className/method，命中校验按两段比较，免逐帧拼串
            final int hash = primaryKey.indexOf('#');
            SAFETY_DECISIONS.put(entryKey,
                    new String[]{via, primaryKey.substring(0, hash), primaryKey.substring(hash + 1)});
        }
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

    /** T-PS4-09 测试注入口（包内可见）：非 null 时替代默认来源判定（fat jar 模拟/计数探针）；产线恒 null，仅一次 volatile 读。 */
    interface JarOriginProbe {
        Boolean isFromJar(String className, ClassLoader loader);
    }

    static volatile JarOriginProbe jarOriginProbe;

    /**
     * 帧来源是否 JAR（L1）：true=第三方依赖；false=目录（开发态用户业务代码）；null=未知
     * （类不可加载 / codeSource 缺失——不武断判为用户帧，仅在安全网阶段兜底采用）。
     */
    public static Boolean loadedFromJar(String className, ClassLoader loader) {
        final JarOriginProbe probe = jarOriginProbe;
        if (probe != null) {
            return probe.isFromJar(className, loader);
        }
        return defaultLoadedFromJar(className, loader);
    }

    /** 默认来源判定（codeSource 路径形态；注入口非 null 时被替代）。 */
    static Boolean defaultLoadedFromJar(String className, ClassLoader loader) {
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

    /** 仅供同包测试清场（两缓存为全局静态，跨用例隔离用）；产线勿调。 */
    static void clearLocatorCachesForTest() {
        CACHE.clear();
        SAFETY_DECISIONS.clear();
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
