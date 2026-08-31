/*
 * SQL Visualizer —— 实时日志「精确跳转源码」拦截器（可选，运行期注入）。
 *
 * 为什么需要它：
 *   IDE 控制台里的 SQL 日志（p6spy / MyBatis / Hibernate 输出）本身不含 file:line 调用位置，
 *   所以插件此前只能「按表名猜实体」做近似跳转，经常跳不准。本拦截器在**应用 JVM 的 SQL 执行线程**
 *   用调用栈取出真实调用点（哪个 Service/Controller 的哪一行触发了这条 SQL），打印一行标记到 stdout，
 *   插件侧的 SqlLogConsoleBridge 解析该标记后写入条目，即可获得与「SQL 历史」一致的精确 file:line 跳转。
 *
 * 本文件是**自包含示例**：只依赖 MyBatis-Plus + JDK，不依赖 SQL Visualizer 插件本身。
 * 把它复制到你的项目里，注册进 MybatisPlusInterceptor 拦截器链即可（见文件末尾用法）。
 *
 * 标记行格式（须与插件 SqlSourceMarker 保持一致）：
 *     SQLVIS-SOURCE|<相对项目根的路径，正斜杠>|<行号>
 * 例：SQLVIS-SOURCE|src/main/java/com/example/service/UserService.java|87
 */

package com.example.demo.sqlvisualizer // ← 改成你自己的包名

import com.baomidou.mybatisplus.extension.plugins.inner.InnerInterceptor
import org.apache.ibatis.executor.Executor
import org.apache.ibatis.mapping.BoundSql
import org.apache.ibatis.mapping.MappedStatement
import org.apache.ibatis.session.ResultHandler
import org.apache.ibatis.session.RowBounds
import java.sql.SQLException

/**
 * 在 SQL 执行前后，捕获调用栈里的第一个「用户代码帧」，打印源码标记行。
 *
 * @param appPackages 可选：只把调用点限定在这些包前缀内（如 "com.example."），
 *                    避免落到第三方库 / 框架代理上。留空则取第一个非框架帧。
 */
class SqlSourceInterceptor(
    private val appPackages: Set<String> = emptySet(),
) : InnerInterceptor {

    override fun beforeQuery(
        executor: Executor,
        ms: MappedStatement,
        parameter: Any?,
        rowBounds: RowBounds,
        resultHandler: ResultHandler<*>,
        boundSql: BoundSql,
    ) {
        emitSource()
    }

    override fun beforeUpdate(executor: Executor, ms: MappedStatement, parameter: Any?) {
        emitSource()
    }

    /** 把当前线程调用栈里的真实调用点打印成标记行。 */
    private fun emitSource() {
        val src = locateSource() ?: return
        // 必须打印到 stdout —— 插件的控制台桥捕获的就是 stdout/stderr 文本
        println("SQLVIS-SOURCE|${src.first}|${src.second}")
    }

    /** 沿调用栈跳过框架帧，取第一个用户代码帧。 */
    private fun locateSource(): Pair<String, Int>? {
        val stack = Thread.currentThread().stackTrace
        for (frame in stack) {
            val cn = frame.className
            if (isFrameworkFrame(cn)) continue
            if (appPackages.isNotEmpty() && appPackages.none { cn.startsWith(it) }) continue
            val fileName = frame.fileName ?: continue
            val pkg = cn.substringBeforeLast('.').replace('.', '/')
            // 必须用正斜杠：插件侧按 `"$basePath/$sourceFile"` 拼接 VFS 路径，反斜杠在 Windows 下解析失败
            val rel = if (pkg.isNotEmpty()) "$pkg/$fileName" else fileName
            val line = frame.lineNumber
            if (line < 0) continue
            return rel to line
        }
        return null
    }

    /** 框架 / 基础设施帧一律跳过（与插件 CallerSourceLocator 的跳过规则对齐）。 */
    private fun isFrameworkFrame(cn: String): Boolean =
        cn.startsWith("java.") || cn.startsWith("javax.") || cn.startsWith("jdk.") ||
            cn.startsWith("sun.") || cn.startsWith("com.intellij.") || cn.startsWith("org.jetbrains.") ||
            cn.startsWith("com.baomidou.") || cn.startsWith("org.apache.ibatis.") ||
            cn.startsWith("org.hibernate.") || cn.startsWith("com.zaxxer.") ||
            cn.startsWith("org.springframework.") || cn.startsWith("com.sun.proxy.") || cn.startsWith("\$Proxy")

    private fun String.substringBeforeLast(delimiter: Char): String {
        val idx = lastIndexOf(delimiter)
        return if (idx < 0) this else substring(0, idx)
    }
}

/*
 * —— 用法 ——
 *
 * Kotlin（Spring Boot 配置类）：
 *     @Bean
 *     fun mybatisPlusInterceptor(): MybatisPlusInterceptor {
 *         val i = MybatisPlusInterceptor()
 *         i.addInnerInterceptor(SqlSourceInterceptor(setOf("com.example."))) // 限定你的业务包
 *         // 其余拦截器（分页、乐观锁等）按需 addInnerInterceptor(...)
 *         return i
 *     }
 *
 * 原生 MyBatis（无 MP）也可改用 org.apache.ibatis.plugin.Interceptor 在 Executor 的
 * query/update 处做同样的事；原理完全一致。
 *
 * 注册后运行应用，控制台会多出形如 `SQLVIS-SOURCE|...|行号` 的标记行（每条 SQL 一行），
 * 插件会自动识别并让「跳转到源码」精确落到调用处。标记行不影响 SQL 执行，也无性能负担。
 */
