package cn.song.mybatisplus.sqlvis.agent.util;

import java.util.concurrent.ConcurrentHashMap;

/**
 * 调用栈定位（T-M56-04）：execute 时取一次栈，跳过框架/基础设施帧，取第一个用户代码帧。
 * 热点红线（§4.2 #5）：结果按调用方缓存（key = 首个用户帧 class#method）——禁 per-execute
 * 重复全栈解析 + JNI 反查代理（DebugTools 热点）。缓存有界（512，超出整表清空——调用方数量
 * 与业务方法数同阶，整表清空语义无损）。
 *
 * 框架帧过滤规则与 IDE 侧 CallerSourceLocator 对齐（agent 侧独立副本——agent 不引 IDE 类）。
 */
public final class StackLocator {

    /** 框架/基础设施帧前缀（与 CallerSourceLocator 跳过规则对齐 + 驱动/池/ORM 家族）。 */
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
    };

    private static final ConcurrentHashMap<String, Frame> CACHE = new ConcurrentHashMap<String, Frame>();
    private static final int CACHE_MAX = 512;

    public static final class Frame {
        public final String sourceFile;
        public final int line;
        public final String methodKey;

        Frame(String sourceFile, int line, String methodKey) {
            this.sourceFile = sourceFile;
            this.line = line;
            this.methodKey = methodKey;
        }
    }

    public static Frame locate() {
        final StackTraceElement[] stack = Thread.currentThread().getStackTrace();
        // [0]=getStackTrace [1]=locate [2]=executeWithCapture [3]=invoke 反射帧——从 0 扫描无妨，
        // 反射/agent 自身帧被前缀规则跳过
        String firstAcceptedKey = null;
        String pendingFile = null;
        int pendingLine = 0;
        for (StackTraceElement f : stack) {
            final String cn = f.getClassName();
            if (isFrameworkFrame(cn)) {
                continue;
            }
            // 首个用户帧：登记缓存 key；后续用户帧（同栈多业务层）不影响 file:line 取首个
            if (firstAcceptedKey == null) {
                firstAcceptedKey = cn + "#" + f.getMethodName();
                final Frame cached = CACHE.get(firstAcceptedKey);
                if (cached != null) {
                    return cached;
                }
                pendingFile = toRelativePath(cn, f.getFileName());
                pendingLine = f.getLineNumber();
            } else if (pendingFile == null) {
                pendingFile = toRelativePath(cn, f.getFileName());
                pendingLine = f.getLineNumber();
            }
            if (pendingFile != null && pendingLine >= 0) {
                break;
            }
        }
        if (firstAcceptedKey == null) {
            return null;
        }
        final Frame frame = new Frame(pendingFile, Math.max(pendingLine, 0),
                simpleMethodKey(firstAcceptedKey));
        if (CACHE.size() >= CACHE_MAX) {
            CACHE.clear();
        }
        CACHE.putIfAbsent(firstAcceptedKey, frame);
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
