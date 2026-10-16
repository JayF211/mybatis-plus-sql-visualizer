package cn.song.mybatisplus.visualizer.log.agent

import cn.song.mybatisplus.visualizer.util.FatalErrors
import java.io.File
import java.util.jar.JarFile

/**
 * UI-007：只判定传入候选的启动入口，不枚举系统进程，不用父 PID/classpath/业务参数猜身份。
 * UNKNOWN 保留既有项目启动/补扫入口的授权边界；它不是“已证业务 JVM”。
 * 命令、参数、文件内容只用于本地判定，不进入日志或长期缓存。
 */
internal object AgentAttachTarget {
    enum class Decision { APPLICATION, HELPER, NON_JAVA, UNKNOWN, NEEDS_FILES }

    private val helperClasses = setOf(
        "com.intellij.idea.Main",
        "com.intellij.database.remote.RemoteJdbcServer",
        "com.intellij.execution.rmi.RemoteServer",
        "org.jetbrains.jps.cmdline.Launcher",
        "org.jetbrains.jps.cmdline.BuildMain",
        "org.jetbrains.kotlin.daemon.KotlinCompileDaemon",
        "org.gradle.wrapper.GradleWrapperMain",
        "org.apache.maven.wrapper.MavenWrapperMain",
        "org.codehaus.plexus.classworlds.launcher.Launcher",
        "org.gradle.launcher.GradleMain",
        "org.gradle.launcher.daemon.bootstrap.GradleDaemon",
        "org.gradle.launcher.daemon.server.bootstrap.GradleDaemon",
        "worker.org.gradle.process.internal.worker.GradleWorkerMain",
        "org.gradle.process.internal.worker.GradleWorkerMain",
        "org.mvndaemon.mvnd.daemon.Server",
    )
    private val operandOptions = setOf(
        "-cp", "-classpath", "--class-path", "-p", "--module-path", "--upgrade-module-path",
        "--add-modules", "--limit-modules", "--add-reads", "--add-exports", "--add-opens",
        "--patch-module", "--enable-native-access", "--source",
    )
    private const val MAX_TEXT = 256 * 1024

    /** ProcessHandle.Info API 已用本地 JDK17 javap 核实；仅访问指定 pid。 */
    fun inspect(pid: Long, handler: Any?, readLaunchFiles: Boolean): Decision {
        if (pid == ProcessHandle.current().pid()) return Decision.HELPER
        val info = safely { ProcessHandle.of(pid).orElse(null)?.info() }
        val command = safely { info?.command()?.orElse(null) }
        val args = safely { info?.arguments()?.orElse(null) }
        val fileReader: (String) -> String? = if (readLaunchFiles) ::readArgFile else { _ -> null }
        val jarReader: (String) -> String? = if (readLaunchFiles) ::readJarMain else { _ -> null }
        val unavailable = if (readLaunchFiles) Decision.UNKNOWN else Decision.NEEDS_FILES
        if (command != null && args != null) {
            val result = classify(listOf(command) + args, fileReader, jarReader, unavailable)
            if (result != Decision.UNKNOWN) return result
        }
        // Windows 的 Info.arguments 经常缺失。243 util-8.jar BaseProcessHandler.getCommandLine 已核实。
        val handlerCommand = safely {
            handler?.javaClass?.getMethod("getCommandLine")?.invoke(handler) as? String
        }
        val raw = handlerCommand ?: safely { info?.commandLine()?.orElse(null) }
        if (raw != null) {
            // Info.commandLine 可能未给带空格的可执行文件补引号，优先使用已知 executable 边界。
            if (command != null && raw.startsWith("$command ")) {
                val tail = tokenize(raw.substring(command.length), false) ?: return Decision.UNKNOWN
                return classify(listOf(command) + tail, fileReader, jarReader, unavailable)
            }
            return classifyCommand(raw, fileReader, jarReader, unavailable)
        }
        return Decision.UNKNOWN
    }

    fun classifyCommand(
        command: String,
        argFile: (String) -> String? = ::readArgFile,
        jarMain: (String) -> String? = ::readJarMain,
        unavailable: Decision = Decision.UNKNOWN,
    ): Decision = tokenize(command, false)?.let { classify(it, argFile, jarMain, unavailable) } ?: Decision.UNKNOWN

    private fun classify(
        command: List<String>, argFile: (String) -> String?, jarMain: (String) -> String?,
        unavailable: Decision,
    ): Decision {
        val executable = command.firstOrNull()?.replace('\\', '/')?.substringAfterLast('/')?.lowercase()
            ?: return Decision.UNKNOWN
        if (executable !in setOf("java", "java.exe", "javaw", "javaw.exe")) return Decision.NON_JAVA
        val tokens = command.drop(1).toMutableList()
        var i = 0
        var launcherMain: String? = null
        var expandFiles = true
        var expandedBytes = 0
        while (i < tokens.size) {
            val token = tokens[i]
            if (expandFiles && token.startsWith("@") && !token.startsWith("@@")) {
                val text = argFile(token.drop(1)) ?: return unavailable
                expandedBytes += text.length
                if (expandedBytes > MAX_TEXT) return Decision.UNKNOWN
                val expanded = tokenize(text, true) ?: return Decision.UNKNOWN
                // Java argfiles 不支持递归。拒绝猜测嵌套形态，避免无限展开或误取后续参数。
                if (expanded.any { it.startsWith("@") }) return Decision.UNKNOWN
                tokens.removeAt(i)
                tokens.addAll(i, expanded)
                continue
            }
            when {
                token == "--disable-@files" -> { expandFiles = false; i++ }
                token.startsWith("-Didea.launcher.main.class=") -> {
                    launcherMain = token.substringAfter('='); i++
                }
                token == "-jar" -> {
                    val jar = tokens.getOrNull(i + 1) ?: return Decision.UNKNOWN
                    return jarMain(jar)?.let { classifyMain(it, emptyList(), null) } ?: unavailable
                }
                token == "-m" || token == "--module" -> {
                    val module = tokens.getOrNull(i + 1) ?: return Decision.UNKNOWN
                    return moduleMain(module)
                }
                token.startsWith("--module=") -> return moduleMain(token.substringAfter('='))
                token in operandOptions -> {
                    if (i + 1 >= tokens.size) return Decision.UNKNOWN
                    i += 2
                }
                operandOptions.any { token.startsWith("$it=") } -> i++
                token.startsWith("-D") || token.startsWith("-X") || token.startsWith("-javaagent:") ||
                    token.startsWith("-agentlib:") || token.startsWith("-agentpath:") ||
                    token in setOf("-ea", "-da", "-esa", "-dsa", "-server", "-client", "--enable-preview", "-showversion") ||
                    token.startsWith("-ea:") || token.startsWith("-da:") -> i++
                token.startsWith('-') || token.startsWith('@') -> return Decision.UNKNOWN
                else -> return classifyMain(token, tokens.drop(i + 1), launcherMain)
            }
        }
        return Decision.UNKNOWN
    }

    private fun moduleMain(module: String): Decision =
        if ('/' in module) classifyMain(module.substringAfter('/'), emptyList(), null) else Decision.UNKNOWN

    private fun classifyMain(main: String, args: List<String>, launcherMain: String?, depth: Int = 0): Decision {
        if (depth > 4 || main.isBlank()) return Decision.UNKNOWN
        // 243 idea_rt.jar javap：AppMainV2 优先 system property，否则 args[0]。
        if (main == "com.intellij.rt.execution.application.AppMainV2" || main == "com.intellij.rt.execution.application.AppMain") {
            val actual = launcherMain ?: args.firstOrNull() ?: return Decision.UNKNOWN
            return classifyMain(actual, if (launcherMain == null) args.drop(1) else args, null, depth + 1)
        }
        if (main == "com.intellij.rt.execution.CommandLineWrapper") {
            // args[0] 是 classpath 文件；可选 @vm_params file、@app_params file，随后才是 main。
            if (args.isEmpty()) return Decision.UNKNOWN
            var index = 1
            if (!args[0].endsWith(".jar")) {
                if (args.getOrNull(index) == "@vm_params") index += 2
                if (args.getOrNull(index) == "@app_params") index += 2
            }
            val actual = args.getOrNull(index) ?: return Decision.UNKNOWN
            return classifyMain(actual, args.drop(index + 1), null, depth + 1)
        }
        if (main in helperClasses || main.startsWith("org.jetbrains.idea.maven.server.")) return Decision.HELPER
        // 不整包封禁 com.intellij/org.jetbrains；JUnitStarter 和合法用户应用仍可捕获。
        return Decision.APPLICATION
    }

    /** 命令行只解引号，不吞 Windows 路径反斜杠；argfile 另按 Java 引号/注释/续行规则解析。 */
    private fun tokenize(text: String, argFile: Boolean): List<String>? {
        if (text.length > MAX_TEXT) return null
        val out = mutableListOf<String>()
        val word = StringBuilder()
        var quote: Char? = null
        var started = false
        var i = 0
        fun flush() { if (started) out.add(word.toString()); word.setLength(0); started = false }
        while (i < text.length) {
            val ch = text[i++]
            when {
                argFile && quote == null && ch == '#' -> {
                    flush()
                    while (i < text.length && text[i] != '\n' && text[i] != '\r') i++
                }
                ch == '\\' && i < text.length && (argFile || text[i] == '"') -> {
                    val next = text[i]
                    when {
                        argFile && (next == '\n' || next == '\r') -> {
                            i++
                            if (next == '\r' && text.getOrNull(i) == '\n') i++
                            while (i < text.length && text[i] in " \t") i++
                        }
                        next == '"' || (argFile && quote != null && next in "\\'nrtf") -> {
                            i++; started = true
                            word.append(if (argFile) when (next) { 'n' -> '\n'; 'r' -> '\r'; 't' -> '\t'; 'f' -> '\u000c'; else -> next } else next)
                        }
                        else -> { word.append(ch); started = true }
                    }
                }
                (ch == '"' || (argFile && ch == '\'')) && (quote == null || quote == ch) -> {
                    quote = if (quote == null) ch else null; started = true
                }
                ch.isWhitespace() && quote == null -> flush()
                else -> { word.append(ch); started = true }
            }
        }
        if (quote != null) return null
        flush()
        return out
    }

    // 不解析相对目标工作目录，不读 UNC/网络文件；已删除的启动文件按 UNKNOWN 保留旧边界。
    private fun localFile(path: String): File? {
        if (path.startsWith("\\\\") || path.startsWith("//")) return null
        return File(path).takeIf { it.isAbsolute && it.isFile }
    }
    private fun readArgFile(path: String): String? = safely {
        val file = localFile(path) ?: return@safely null
        file.inputStream().use { stream ->
            val bytes = stream.readNBytes(MAX_TEXT + 1)
            if (bytes.size > MAX_TEXT) null else bytes.toString(java.nio.charset.Charset.defaultCharset())
        }
    }
    private fun readJarMain(path: String): String? = safely {
        val file = localFile(path) ?: return@safely null
        JarFile(file).use { jar ->
            val entry = jar.getJarEntry("META-INF/MANIFEST.MF") ?: return@use null
            jar.getInputStream(entry).use { stream ->
                val bytes = stream.readNBytes(MAX_TEXT + 1)
                if (bytes.size > MAX_TEXT) null else java.util.jar.Manifest(bytes.inputStream()).mainAttributes.getValue("Main-Class")
            }
        }
    }
    private inline fun <T> safely(block: () -> T): T? = try { block() } catch (t: Throwable) {
        val cause = (t as? java.lang.reflect.InvocationTargetException)?.targetException ?: t
        if (FatalErrors.isFatal(cause)) throw cause
        null // 不带异常文本：文件路径/命令可能包含凭据。
    }
}
