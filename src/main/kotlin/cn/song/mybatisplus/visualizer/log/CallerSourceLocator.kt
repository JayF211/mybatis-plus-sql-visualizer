package cn.song.mybatisplus.visualizer.log

/**
 * 调用点定位（F-44-6 可靠跳转）：在**应用 JVM**（SQL 执行线程）中，沿当前线程栈向下跳过
 * JDK / IntelliJ 平台 / 本插件 / MyBatis-Plus / MyBatis / Hibernate / Spring 基础设施帧，
 * 取第一个用户代码帧，还原为「相对项目根目录的源文件路径 + 行号」。
 *
 * 与 [SqlLogConsoleBridge.extractSourceLocation]（在 IDE 控制台线程取栈，永远取不到应用帧）相反，
 * 本定位器在应用线程执行，能拿到真实调用位置。供运行期 [SqlSourceInterceptor] 使用，
 * 并抽为纯函数 [locateFrom] 便于单测。
 *
 * 跳过顺序说明：栈顶是拦截器/MyBatis 框架帧，向下走第一个非框架帧即「调用 Mapper 的用户方法」
 * （通常是 Service / Controller 中触发 SQL 的那一行），正是应跳转到的代码位置。
 */
object CallerSourceLocator {
    /** 在调用线程执行，返回 (相对路径, 行号) 或 null（无应用帧）。 */
    fun locate(appPackages: Set<String> = emptySet()): Pair<String, Int>? =
        locateFrom(Thread.currentThread().stackTrace, appPackages)

    /** 纯函数：从给定栈定位（单测用，避免依赖运行期线程栈）。 */
    fun locateFrom(stack: Array<StackTraceElement>, appPackages: Set<String> = emptySet()): Pair<String, Int>? {
        for (frame in stack) {
            val cn = frame.className
            if (isFrameworkFrame(cn)) continue
            if (appPackages.isNotEmpty() && appPackages.none { cn.startsWith(it) }) continue
            val fileName = frame.fileName ?: continue
            val rel = classNameToRelativePath(cn, fileName) ?: continue
            val line = frame.lineNumber
            if (line < 0) continue
            return rel to line
        }
        return null
    }

    /** 框架 / 基础设施帧（一律跳过，不视为调用点）。 */
    private fun isFrameworkFrame(cn: String): Boolean =
        cn.startsWith("java.") || cn.startsWith("javax.") || cn.startsWith("jdk.") ||
            cn.startsWith("sun.") || cn.startsWith("com.intellij.") || cn.startsWith("org.jetbrains.") ||
            cn.startsWith("cn.song.mybatisplus.visualizer.") || cn.startsWith("com.baomidou.") ||
            cn.startsWith("org.apache.ibatis.") || cn.startsWith("org.hibernate.") ||
            cn.startsWith("com.zaxxer.") || cn.startsWith("org.springframework.") ||
            cn.startsWith("com.sun.proxy.") || cn.startsWith("\$Proxy")

    /** 类名 + 源文件名 → 相对项目根路径（如 com.example.User + User.java → com/example/User.java）。 */
    fun classNameToRelativePath(className: String, fileName: String): String? {
        val packagePath = className.substringBeforeLast('.').replace('.', '/')
        return if (packagePath.isNotEmpty()) "$packagePath/$fileName" else fileName
    }
}
