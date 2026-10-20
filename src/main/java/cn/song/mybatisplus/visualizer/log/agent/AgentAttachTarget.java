package cn.song.mybatisplus.visualizer.log.agent;

import cn.song.mybatisplus.visualizer.util.FatalErrors;

import java.io.File;
import java.lang.reflect.InvocationTargetException;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
import java.util.jar.JarFile;

/**
 * UI-007：只判定传入候选的启动入口，不枚举系统进程，不用父 PID/classpath/业务参数猜身份。
 * UNKNOWN 保留既有项目启动/补扫入口的授权边界；它不是「已证业务 JVM」。
 * 命令、参数、文件内容只用于本地判定，不进入日志或长期缓存。
 *
 * <p>master 的 {@code internal object AgentAttachTarget} 的 Java 承载（全 static 形态）。
 */
final class AgentAttachTarget {

    private AgentAttachTarget() {
    }

    enum Decision { APPLICATION, HELPER, NON_JAVA, UNKNOWN, NEEDS_FILES }

    private static final Set<String> HELPER_CLASSES = Set.of(
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
            "org.mvndaemon.mvnd.daemon.Server"
    );
    private static final Set<String> OPERAND_OPTIONS = Set.of(
            "-cp", "-classpath", "--class-path", "-p", "--module-path", "--upgrade-module-path",
            "--add-modules", "--limit-modules", "--add-reads", "--add-exports", "--add-opens",
            "--patch-module", "--enable-native-access", "--source"
    );
    private static final Set<String> JAVA_EXECUTABLES = Set.of("java", "java.exe", "javaw", "javaw.exe");
    private static final Set<String> SIMPLE_FLAGS = Set.of(
            "-ea", "-da", "-esa", "-dsa", "-server", "-client", "--enable-preview", "-showversion");
    private static final int MAX_TEXT = 256 * 1024;

    /** ProcessHandle.Info API 已用本地 JDK17 javap 核实；仅访问指定 pid。 */
    static Decision inspect(long pid, Object handler, boolean readLaunchFiles) {
        if (pid == ProcessHandle.current().pid()) {
            return Decision.HELPER;
        }
        ProcessHandle handle = safely(() -> ProcessHandle.of(pid).orElse(null));
        ProcessHandle.Info info = handle == null ? null : safely(handle::info);
        String command = info == null ? null : safely(() -> info.command().orElse(null));
        String[] args = info == null ? null : safely(() -> info.arguments().orElse(null));
        Function<String, String> fileReader = readLaunchFiles ? AgentAttachTarget::readArgFile : p -> null;
        Function<String, String> jarReader = readLaunchFiles ? AgentAttachTarget::readJarMain : p -> null;
        Decision unavailable = readLaunchFiles ? Decision.UNKNOWN : Decision.NEEDS_FILES;
        if (command != null && args != null) {
            List<String> full = new ArrayList<>(args.length + 1);
            full.add(command);
            full.addAll(List.of(args));
            Decision result = classify(full, fileReader, jarReader, unavailable);
            if (result != Decision.UNKNOWN) {
                return result;
            }
        }
        // Windows 的 Info.arguments 经常缺失。243 util-8.jar BaseProcessHandler.getCommandLine 已核实。
        String handlerCommand = safely(() -> {
            if (handler == null) {
                return null;
            }
            try {
                Object invoked = handler.getClass().getMethod("getCommandLine").invoke(handler);
                return invoked instanceof String s ? s : null;
            } catch (Throwable t) {
                // 与 safely 同语义：InvocationTargetException 解包到真实 cause，fatal 透传、其余 null。
                Throwable cause = t instanceof InvocationTargetException ite ? ite.getTargetException() : t;
                if (FatalErrors.isFatal(cause)) {
                    throw (Error) cause;
                }
                return null;
            }
        });
        String raw = handlerCommand != null ? handlerCommand
                : (info == null ? null : safely(() -> info.commandLine().orElse(null)));
        if (raw != null) {
            // Info.commandLine 可能未给带空格的可执行文件补引号，优先使用已知 executable 边界。
            if (command != null && raw.startsWith(command + " ")) {
                List<String> tail = tokenize(raw.substring(command.length()), false);
                if (tail == null) {
                    return Decision.UNKNOWN;
                }
                List<String> full = new ArrayList<>(tail.size() + 1);
                full.add(command);
                full.addAll(tail);
                return classify(full, fileReader, jarReader, unavailable);
            }
            return classifyCommand(raw, fileReader, jarReader, unavailable);
        }
        return Decision.UNKNOWN;
    }

    static Decision classifyCommand(String command) {
        return classifyCommand(command, AgentAttachTarget::readArgFile, AgentAttachTarget::readJarMain,
                Decision.UNKNOWN);
    }

    static Decision classifyCommand(String command, Function<String, String> argFile,
                                    Function<String, String> jarMain, Decision unavailable) {
        List<String> tokens = tokenize(command, false);
        if (tokens == null) {
            return Decision.UNKNOWN;
        }
        return classify(tokens, argFile, jarMain, unavailable);
    }

    private static Decision classify(List<String> command, Function<String, String> argFile,
                                     Function<String, String> jarMain, Decision unavailable) {
        String first = command.isEmpty() ? null : command.get(0);
        if (first == null) {
            return Decision.UNKNOWN;
        }
        String executable = first.replace('\\', '/');
        int slash = executable.lastIndexOf('/');
        executable = (slash >= 0 ? executable.substring(slash + 1) : executable).toLowerCase();
        if (!JAVA_EXECUTABLES.contains(executable)) {
            return Decision.NON_JAVA;
        }
        List<String> tokens = new ArrayList<>(command.subList(1, command.size()));
        int i = 0;
        String launcherMain = null;
        boolean expandFiles = true;
        int expandedBytes = 0;
        while (i < tokens.size()) {
            String token = tokens.get(i);
            if (expandFiles && token.startsWith("@") && !token.startsWith("@@")) {
                String text = argFile.apply(token.substring(1));
                if (text == null) {
                    return unavailable;
                }
                expandedBytes += text.length();
                if (expandedBytes > MAX_TEXT) {
                    return Decision.UNKNOWN;
                }
                List<String> expanded = tokenize(text, true);
                if (expanded == null) {
                    return Decision.UNKNOWN;
                }
                // Java argfiles 不支持递归。拒绝猜测嵌套形态，避免无限展开或误取后续参数。
                for (String e : expanded) {
                    if (e.startsWith("@")) {
                        return Decision.UNKNOWN;
                    }
                }
                tokens.remove(i);
                tokens.addAll(i, expanded);
                continue;
            }
            if (token.equals("--disable-@files")) {
                expandFiles = false;
                i++;
            } else if (token.startsWith("-Didea.launcher.main.class=")) {
                launcherMain = token.substring(token.indexOf('=') + 1);
                i++;
            } else if (token.equals("-jar")) {
                String jar = i + 1 < tokens.size() ? tokens.get(i + 1) : null;
                if (jar == null) {
                    return Decision.UNKNOWN;
                }
                String main = jarMain.apply(jar);
                return main != null ? classifyMain(main, List.of(), null, 0) : unavailable;
            } else if (token.equals("-m") || token.equals("--module")) {
                String module = i + 1 < tokens.size() ? tokens.get(i + 1) : null;
                if (module == null) {
                    return Decision.UNKNOWN;
                }
                return moduleMain(module);
            } else if (token.startsWith("--module=")) {
                return moduleMain(token.substring(token.indexOf('=') + 1));
            } else if (OPERAND_OPTIONS.contains(token)) {
                if (i + 1 >= tokens.size()) {
                    return Decision.UNKNOWN;
                }
                i += 2;
            } else if (startsWithOperandOption(token)) {
                i++;
            } else if (token.startsWith("-D") || token.startsWith("-X") || token.startsWith("-javaagent:")
                    || token.startsWith("-agentlib:") || token.startsWith("-agentpath:")
                    || SIMPLE_FLAGS.contains(token)
                    || token.startsWith("-ea:") || token.startsWith("-da:")) {
                i++;
            } else if (token.startsWith("-") || token.startsWith("@")) {
                return Decision.UNKNOWN;
            } else {
                List<String> rest = i + 1 <= tokens.size() ? List.copyOf(tokens.subList(i + 1, tokens.size())) : List.of();
                return classifyMain(token, rest, launcherMain, 0);
            }
        }
        return Decision.UNKNOWN;
    }

    private static boolean startsWithOperandOption(String token) {
        for (String option : OPERAND_OPTIONS) {
            if (token.startsWith(option + "=")) {
                return true;
            }
        }
        return false;
    }

    private static Decision moduleMain(String module) {
        if (module.indexOf('/') >= 0) {
            return classifyMain(module.substring(module.indexOf('/') + 1), List.of(), null, 0);
        }
        return Decision.UNKNOWN;
    }

    private static Decision classifyMain(String main, List<String> args, String launcherMain, int depth) {
        if (depth > 4 || main.isBlank()) {
            return Decision.UNKNOWN;
        }
        // 243 idea_rt.jar javap：AppMainV2 优先 system property，否则 args[0]。
        if (main.equals("com.intellij.rt.execution.application.AppMainV2")
                || main.equals("com.intellij.rt.execution.application.AppMain")) {
            String actual = launcherMain != null ? launcherMain
                    : (!args.isEmpty() ? args.get(0) : null);
            if (actual == null) {
                return Decision.UNKNOWN;
            }
            List<String> rest = launcherMain == null && !args.isEmpty()
                    ? List.copyOf(args.subList(1, args.size()))
                    : args;
            return classifyMain(actual, rest, null, depth + 1);
        }
        if (main.equals("com.intellij.rt.execution.CommandLineWrapper")) {
            // args[0] 是 classpath 文件；可选 @vm_params file、@app_params file，随后才是 main。
            if (args.isEmpty()) {
                return Decision.UNKNOWN;
            }
            int index = 1;
            if (!args.get(0).endsWith(".jar")) {
                if (index < args.size() && args.get(index).equals("@vm_params")) {
                    index += 2;
                }
                if (index < args.size() && args.get(index).equals("@app_params")) {
                    index += 2;
                }
            }
            if (index >= args.size()) {
                return Decision.UNKNOWN;
            }
            String actual = args.get(index);
            List<String> rest = index + 1 <= args.size()
                    ? List.copyOf(args.subList(index + 1, args.size()))
                    : List.of();
            return classifyMain(actual, rest, null, depth + 1);
        }
        if (HELPER_CLASSES.contains(main) || main.startsWith("org.jetbrains.idea.maven.server.")) {
            return Decision.HELPER;
        }
        // 不整包封禁 com.intellij/org.jetbrains；JUnitStarter 和合法用户应用仍可捕获。
        return Decision.APPLICATION;
    }

    /** 命令行只解引号，不吞 Windows 路径反斜杠；argfile 另按 Java 引号/注释/续行规则解析。 */
    private static List<String> tokenize(String text, boolean argFile) {
        if (text.length() > MAX_TEXT) {
            return null;
        }
        List<String> out = new ArrayList<>();
        StringBuilder word = new StringBuilder();
        Character quote = null;
        boolean[] started = {false};
        int[] index = {0};
        Runnable flush = () -> {
            if (started[0]) {
                out.add(word.toString());
            }
            word.setLength(0);
            started[0] = false;
        };
        String s = text;
        int len = s.length();
        while (index[0] < len) {
            char ch = s.charAt(index[0]++);
            if (argFile && quote == null && ch == '#') {
                flush.run();
                while (index[0] < len && s.charAt(index[0]) != '\n' && s.charAt(index[0]) != '\r') {
                    index[0]++;
                }
            } else if (ch == '\\' && index[0] < len && (argFile || s.charAt(index[0]) == '"')) {
                char next = s.charAt(index[0]);
                if (argFile && (next == '\n' || next == '\r')) {
                    index[0]++;
                    if (next == '\r' && index[0] < len && s.charAt(index[0]) == '\n') {
                        index[0]++;
                    }
                    while (index[0] < len && (s.charAt(index[0]) == ' ' || s.charAt(index[0]) == '\t')) {
                        index[0]++;
                    }
                } else if (next == '"' || (argFile && quote != null && ("\\'nrtf".indexOf(next) >= 0))) {
                    index[0]++;
                    started[0] = true;
                    if (argFile) {
                        switch (next) {
                            case 'n' -> word.append('\n');
                            case 'r' -> word.append('\r');
                            case 't' -> word.append('\t');
                            case 'f' -> word.append('\u000c');
                            default -> word.append(next);
                        }
                    } else {
                        word.append(next);
                    }
                } else {
                    word.append(ch);
                    started[0] = true;
                }
            } else if ((ch == '"' || (argFile && ch == '\'')) && (quote == null || quote == ch)) {
                quote = quote == null ? ch : null;
                started[0] = true;
            } else if (Character.isWhitespace(ch) && quote == null) {
                flush.run();
            } else {
                word.append(ch);
                started[0] = true;
            }
        }
        if (quote != null) {
            return null;
        }
        flush.run();
        return out;
    }

    // 不解析相对目标工作目录，不读 UNC/网络文件；已删除的启动文件按 UNKNOWN 保留旧边界。
    private static File localFile(String path) {
        if (path.startsWith("\\\\") || path.startsWith("//")) {
            return null;
        }
        File file = new File(path);
        return file.isAbsolute() && file.isFile() ? file : null;
    }

    private static String readArgFile(String path) {
        return safely(() -> {
            File file = localFile(path);
            if (file == null) {
                return null;
            }
            try (java.io.InputStream stream = java.nio.file.Files.newInputStream(file.toPath())) {
                byte[] bytes = stream.readNBytes(MAX_TEXT + 1);
                if (bytes.length > MAX_TEXT) {
                    return null;
                }
                return new String(bytes, Charset.defaultCharset());
            }
        });
    }

    private static String readJarMain(String path) {
        return safely(() -> {
            File file = localFile(path);
            if (file == null) {
                return null;
            }
            try (JarFile jar = new JarFile(file)) {
                java.util.zip.ZipEntry entry = jar.getJarEntry("META-INF/MANIFEST.MF");
                if (entry == null) {
                    return null;
                }
                try (java.io.InputStream stream = jar.getInputStream(entry)) {
                    byte[] bytes = stream.readNBytes(MAX_TEXT + 1);
                    if (bytes.length > MAX_TEXT) {
                        return null;
                    }
                    return new java.util.jar.Manifest(new java.io.ByteArrayInputStream(bytes))
                            .getMainAttributes().getValue("Main-Class");
                }
            }
        });
    }

    /** 可抛受检异常的 Supplier（对齐 Kotlin {@code () -> T} 函数类型）。 */
    private interface ThrowingSupplier<T> {
        T get() throws Exception;
    }

    /** 不带异常文本：文件路径/命令可能包含凭据。 */
    private static <T> T safely(ThrowingSupplier<T> block) {
        try {
            return block.get();
        } catch (Throwable t) {
            Throwable cause = t instanceof InvocationTargetException ite ? ite.getTargetException() : t;
            if (FatalErrors.isFatal(cause)) {
                throw (Error) cause;
            }
            return null;
        }
    }
}
