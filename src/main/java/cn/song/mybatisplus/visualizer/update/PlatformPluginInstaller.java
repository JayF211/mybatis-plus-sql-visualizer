package cn.song.mybatisplus.visualizer.update;

import com.intellij.ide.startup.StartupActionScriptManager;
import com.intellij.openapi.application.PathManager;
import com.intellij.openapi.diagnostic.Logger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 平台安装封装（M28）：调度「下次启动替换插件」——删除旧版（[StartupActionScriptManager.DeleteCommand]）
 * + 解压新版（[StartupActionScriptManager.UnzipCommand]），随后由调用方提示重启
 * （[com.intellij.util.ui.RestartDialogImpl.restartWithConfirmation]，环境否决动态卸载，
 * 覆盖更新必重启——T-M23.10 定论，不做动态加载）。
 *
 * T-M51-01-ALT：使用 [ProtectionDomain] 获取当前插件 jar/目录路径 +
 * [StartupActionScriptManager.DeleteCommand] 删除旧版，避免 PluginManager.getPlugin()
 * （262 标记 @ApiStatus.Internal，无跨版本公开替代）。标准 Java API，三平台可用。
 * **单测不调用本类**（编排层 [PluginUpdateRunner] 以 install seam 注入替代），
 * 由 runIde 沙盒端到端验证真实安装链路。
 */
public final class PlatformPluginInstaller {

    public static final PlatformPluginInstaller INSTANCE = new PlatformPluginInstaller();
    private PlatformPluginInstaller() {}

    private static final Logger LOG = Logger.getInstance(PlatformPluginInstaller.class);

    /** 待安装包持久化目录（启动脚本在下次 IDE 启动时执行，临时目录可能被系统清理）。 */
    private static final String PENDING_DIR = "mpvs-update";

    /** 开发态判定特征段集合。 */
    private static final Set<String> DEV_PATH_MARKERS = Set.of("build", "classes", "out", "target", ".gradle");

    /**
     * 调度安装：校验旧版安装位置（防开发态误删 build 产物）→ 持久化下载包 →
     * 卸载旧版 + 解压新版（脚本按序执行：先删后装）。
     */
    public static PluginUpdateRunner.InstallResult install(Path zip) {
        try {
            // 插件目录定位：优先用 ProtectionDomain 获取精确 JAR 路径；
            // 262 平台 codeSource.location 实证返回 null（日志实证），此时回退到
            // PathManager.getPluginsDir() + PLUGIN_ID 定位插件目录根。
            // 删除整个目录（DeleteCommand 递归删除）——比删单个 JAR 更可靠，
            // 不依赖 classloader 实现细节，三平台（Win/Mac/Linux）通用。
            Path jarPath = getPluginJarPath();
            Path pluginDir = pluginDirPath();
            LOG.info("install: jarPath=" + jarPath + " | pluginDir=" + pluginDir + " | zip=" + zip);

            // 开发态守卫：任一可用路径命中构建特征段即拒绝
            List<String> pathStrs = new ArrayList<>();
            if (jarPath != null) pathStrs.add(jarPath.toString().replace('\\', '/'));
            pathStrs.add(pluginDir.toString().replace('\\', '/'));
            for (String pathStr : pathStrs) {
                if (isDevInstallation(pathStr)) {
                    LOG.warn("plugin update rejected: dev-mode installation (path=" + pathStr + ")");
                    return new PluginUpdateRunner.InstallResult.Failed(
                        "updates are not supported for a development-mode installation (path: " +
                            pathStr + "); install from the plugin zip first"
                    );
                }
            }

            Path persistent = persistZip(zip);
            LOG.info("install: persistent=" + persistent);

            // 删除旧版：优先精确删除 JAR，回退删除整个目录
            Path deleteTarget = jarPath != null ? jarPath : pluginDir;
            LOG.info("install: scheduling DeleteCommand for " + deleteTarget);
            StartupActionScriptManager.addActionCommand(
                new StartupActionScriptManager.DeleteCommand(deleteTarget)
            );

            // T-M28.5：解压目标 = 插件目录**根**——发布 zip 顶层自带插件名目录（IPGP buildPlugin
            // 布局 `mybatis-plus-sql-visualizer/lib/…`，Install from Disk 会剥顶层），UnzipCommand
            // 纯解压不剥顶层；解压到 `<plugins>/<pluginId>/` 会多嵌套一层（用户实测：重启后插件
            // 列表消失——平台扫描不到嵌套 jar 内的 plugin.xml）。解压到根后 zip 顶层目录名
            // 与旧安装目录名一致，删旧解新即正确替换。
            LOG.info("install: scheduling UnzipCommand (target=" + unzipTargetDir() + ")");
            StartupActionScriptManager.addActionCommand(
                new StartupActionScriptManager.UnzipCommand(persistent, unzipTargetDir())
            );
            LOG.info("install: commands scheduled successfully — Installed");
            return PluginUpdateRunner.InstallResult.Installed.INSTANCE;
        } catch (Exception error) {
            LOG.warn("plugin update install failed — " + error.getMessage(), error);
            String msg = error.getMessage() != null ? error.getMessage() : error.toString();
            return new PluginUpdateRunner.InstallResult.Failed(msg);
        }
    }

    /**
     * 插件安装目录（2026-08-20 回退方案）：PathManager.getPluginsDir() / zip 顶层目录名。
     * 不依赖 classloader CodeSource（262 平台实证返回 null），三平台通用。
     * 目录名 = zip 顶层目录名（Install from Disk 剥顶层后的短名），不能用 <id> FQN。
     */
    public static Path pluginDirPath() {
        return PathManager.getPluginsDir().resolve(UpdateSource.INSTALL_DIR_NAME);
    }

    /**
     * 解压目标目录（T-M28.5）：插件目录**根**——发布 zip 顶层含插件名目录
     * （`mybatis-plus-sql-visualizer/lib/…`），UnzipCommand 不剥顶层，目标必须是根
     * 才能解出正确形态（见 install() 注释）。internal 供测试断言。
     */
    public static Path unzipTargetDir() {
        return PathManager.getPluginsDir();
    }

    /**
     * 获取当前插件的 jar/目录路径（T-M51-01-ALT）。
     * 使用标准 Java ProtectionDomain API，三平台（243/253/262）皆可用。
     * 返回 null 表示无法获取（安全管理器限制等极端场景）。
     */
    private static Path getPluginJarPath() {
        try {
            java.security.ProtectionDomain pd = PlatformPluginInstaller.class.getProtectionDomain();
            java.security.CodeSource codeSource = pd.getCodeSource();
            if (codeSource == null) {
                LOG.warn("getPluginJarPath: codeSource.location is null");
                return null;
            }
            java.net.URL location = codeSource.getLocation();
            if (location == null) {
                LOG.warn("getPluginJarPath: codeSource.location is null");
                return null;
            }
            java.net.URI uri = location.toURI();
            String rawPart = substringBefore(uri.getSchemeSpecificPart(), '!');
            String pathStr = normalizeDescriptorPath(rawPart);
            LOG.info("getPluginJarPath: location=" + location + " | schemeSpecificPart=" +
                uri.getSchemeSpecificPart() + " | normalized=" + pathStr);
            return Path.of(pathStr);
        } catch (Exception error) {
            LOG.warn("getPluginJarPath: failed — " + error.getMessage(), error);
            return null;
        }
    }

    /** 下载包复制到 system 目录下持久位置（重启后启动脚本读取；留在原处不删——脚本引用）。 */
    private static Path persistZip(Path zip) throws java.io.IOException {
        Path dir = Path.of(PathManager.getSystemPath(), PENDING_DIR);
        Files.createDirectories(dir);
        Path target = dir.resolve(zip.getFileName().toString());
        Files.copy(zip, target, StandardCopyOption.REPLACE_EXISTING);
        ensureReadable(target);
        return target;
    }

    /**
     * 显式确保文件可读（T-M28.3 用户要求：下载包权限不得导致安装失败）——POSIX 平台设
     * `rw-r--r--`（Files.copy 保留源 600 权限，sudo/多用户场景下下次启动的启动脚本可能读
     * 不了）；Windows 无 POSIX API（UnsupportedOperationException）静默跳过；其他失败仅
     * 记日志不阻断——真正读取失败由安装环节报错兜底。
     */
    public static void ensureReadable(Path file) {
        try {
            Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-r--r--"));
        } catch (Exception error) {
            if (!(error instanceof UnsupportedOperationException)) {
                LOG.warn("set readable permissions failed: " + file + " — " + error.getMessage());
            }
        }
    }

    /**
     * 规范化路径（T-M28.3）：剥离 `!` 后缀（jar 内路径）、`jar:file:`/`file:` 前缀
     * （大小写不敏感）、Windows 驱动字母前导斜杠（`/C:/` → `C:/`）。
     *
     * 背景：2026.2 平台返回 URL 风格路径（用户日常 IDE 实证——未归一化时
     * `Path.of("/C:/...")` 在 Windows 解析为 `\C:\...`，与 `C:\...` 比较失败 → 守卫误拒
     * 正常安装，更新失败）。纯函数（单测直接覆盖）。
     * T-M51-01-ALT：原用于 descriptorPath，现用于 jarPath（ProtectionDomain）。
     */
    public static String normalizeDescriptorPath(String raw) {
        String s = substringBefore(raw, '!').trim();
        String lower = s.toLowerCase();
        for (String prefix : List.of("jar:file:", "file:")) {
            if (lower.startsWith(prefix)) {
                s = s.substring(prefix.length());
                break;
            }
        }
        // 剥离前导斜杠（Windows 驱动器字母路径）——
        // 兼容 file:/C:/…（单斜杠）和 file:///C:/…（三斜杠）等 JVM 差异形态；
        // Mac 绝对路径 /Users/… 不剥离（首段非驱动器字母）
        if (s.startsWith("/")) {
            String stripped = trimStart(s, '/');
            if (stripped.length() >= 2 && stripped.charAt(1) == ':') {
                s = stripped;  // /C:/… 或 ///C:/… → C:/…
            }
        }
        return s;
    }

    /**
     * 开发态安装判定（T-M28.3/28.4）：descriptorPath 归一化后含**构建产物特征段**
     * （build/classes/out/target/.gradle）→ 开发态（runIde/测试从 build/classes 加载，
     * 卸载调度会误删构建产物）拒绝更新；其余一律放行。
     *
     * 背景：白名单式（必须位于插件目录内）判定依赖 descriptorPath 精确格式——2026.2 平台
     * 返回 URL 风格且形态多变（用户日常 IDE 多次实证误拒），黑名单式按内容特征判定
     * 对格式不敏感；**descriptorPath 为 null/空白时放行**——2026.2 已安装插件
     * `getDescriptorPath()` 实证返回 null，无法判断时不得误拒正常安装
     * （开发态防护由非 null 路径的特征段判定承担）。
     */
    public static boolean isDevInstallation(String descriptorPath) {
        if (descriptorPath == null || descriptorPath.isBlank()) return false;
        String normalized = normalizeDescriptorPath(descriptorPath).replace('\\', '/');
        String[] segments = normalized.split("/");
        for (String seg : segments) {
            if (DEV_PATH_MARKERS.contains(seg)) return true;
        }
        return false;
    }

    /** Kotlin `String.substringBefore(delimiter)` 等价：未找到分隔符则返回原串。 */
    private static String substringBefore(String s, char delimiter) {
        int i = s.indexOf(delimiter);
        return i < 0 ? s : s.substring(0, i);
    }

    /** 剥离前导指定字符（Kotlin `trimStart(delimiter)` 等价）。 */
    private static String trimStart(String s, char c) {
        int i = 0;
        while (i < s.length() && s.charAt(i) == c) i++;
        return s.substring(i);
    }
}
