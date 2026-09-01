package cn.song.mybatisplus.visualizer.log;

import kotlin.Pair;
import java.util.Set;

/**
 * 调用点定位（F-44-6 可靠跳转）：在应用 JVM（SQL 执行线程）中，沿当前线程栈向下跳过
 * JDK / IntelliJ 平台 / 本插件 / MyBatis-Plus / MyBatis / Hibernate / Spring 基础设施帧，
 * 取第一个用户代码帧，还原为「相对项目根目录的源文件路径 + 行号」。
 */
public final class CallerSourceLocator {

    public static final CallerSourceLocator INSTANCE = new CallerSourceLocator();

    private CallerSourceLocator() {
    }

    public static Pair<String, Integer> locate() {
        return locateFrom(Thread.currentThread().getStackTrace(), Set.of());
    }

    public static Pair<String, Integer> locate(Set<String> appPackages) {
        return locateFrom(Thread.currentThread().getStackTrace(), appPackages);
    }

    public static Pair<String, Integer> locateFrom(StackTraceElement[] stack, Set<String> appPackages) {
        for (StackTraceElement frame : stack) {
            String cn = frame.getClassName();
            if (isFrameworkFrame(cn)) continue;
            if (!appPackages.isEmpty() && appPackages.stream().noneMatch(it -> cn.startsWith(it))) continue;
            String fileName = frame.getFileName();
            if (fileName == null) continue;
            String rel = classNameToRelativePath(cn, fileName);
            if (rel == null) continue;
            int line = frame.getLineNumber();
            if (line < 0) continue;
            return new Pair<>(rel, line);
        }
        return null;
    }

    private static boolean isFrameworkFrame(String cn) {
        return cn.startsWith("java.") || cn.startsWith("javax.") || cn.startsWith("jdk.") ||
            cn.startsWith("sun.") || cn.startsWith("com.intellij.") || cn.startsWith("org.jetbrains.") ||
            cn.startsWith("cn.song.mybatisplus.visualizer.") || cn.startsWith("com.baomidou.") ||
            cn.startsWith("org.apache.ibatis.") || cn.startsWith("org.hibernate.") ||
            cn.startsWith("com.zaxxer.") || cn.startsWith("org.springframework.") ||
            cn.startsWith("com.sun.proxy.") || cn.startsWith("$Proxy");
    }

    public static String classNameToRelativePath(String className, String fileName) {
        int lastDot = className.lastIndexOf('.');
        String packagePath = lastDot >= 0 ? className.substring(0, lastDot).replace('.', '/') : "";
        return packagePath.isEmpty() ? fileName : packagePath + "/" + fileName;
    }
}
