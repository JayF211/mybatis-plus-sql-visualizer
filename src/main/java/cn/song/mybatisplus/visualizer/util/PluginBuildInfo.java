package cn.song.mybatisplus.visualizer.util;

import java.io.File;
import java.net.JarURLConnection;
import java.net.URL;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.jar.JarFile;

/**
 * 插件内部构建号（T-M23.14）：读取打包产物 jar manifest 的 `MPV-Build` 属性。
 *
 * 背景：对外版本号一日一版（gradle.properties），同日多次构建无法区分——构建脚本对所有 Jar 任务
 * 注入 `MPV-Build`（执行期毫秒时间戳，每次构建唯一，见 build.gradle.kts），此处将其展示在
 * 插件设置页，方便用户反馈问题时直接报构建号定位具体产物。
 *
 * 读取路径：本类所在 jar 的 manifest（codeSource）。开发模式（runIde/测试）类从 classes 目录
 * 加载、无 jar 可读 → 返回 null（显示兜底 "—"，属预期：未打包产物没有构建号）。
 */
public final class PluginBuildInfo {

    /** jar manifest 中的构建号属性名（与 build.gradle.kts 注入名一致）。 */
    public static final String MANIFEST_ATTRIBUTE = "MPV-Build";

    /** 构建号在设置页的显示前缀（label 文本与 bundle 前缀一致，便于用户对照）。 */
    public static final String DISPLAY_PREFIX = "MPV-Build";

    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private PluginBuildInfo() {
    }

    /** 当前插件产物的构建号（毫秒时间戳字符串）；非 jar 环境（开发/测试）返回 null。 */
    public static String mpvBuild() {
        File jar = ownJarFile();
        return jar != null ? readMpvBuild(jar) : null;
    }

    /**
     * 从 jar manifest 读取 `MPV-Build` 属性；文件缺失/非 jar/无该属性均返回 null。
     * 公开便于纯 JUnit 测试（临时 jar 构造）。
     */
    public static String readMpvBuild(File jarFile) {
        try (JarFile jf = new JarFile(jarFile)) {
            return jf.getManifest() != null ? jf.getManifest().getMainAttributes().getValue(MANIFEST_ATTRIBUTE) : null;
        } catch (Throwable e) {
            return null;
        }
    }

    /** 构建号（毫秒时间戳）转本地可读时间；不可解析返回 null。 */
    public static String formatBuildTime(String timestamp) {
        try {
            if (timestamp == null) return null;
            long millis = Long.parseLong(timestamp);
            return TIME_FORMATTER.format(LocalDateTime.ofInstant(Instant.ofEpochMilli(millis), ZoneId.systemDefault()));
        } catch (Throwable e) {
            return null;
        }
    }

    /** 设置页展示文本：`MPV-Build: 1786700024611（2026-08-14 17:33:44）`；无构建号显示 "—"。 */
    public static String displayText() {
        String raw = mpvBuild();
        if (raw == null) return "—";
        String time = formatBuildTime(raw);
        return time != null ? DISPLAY_PREFIX + ": " + raw + "（" + time + "）" : DISPLAY_PREFIX + ": " + raw;
    }

    /**
     * 本类所在 jar（classloader 定位类文件资源）。
     *
     * T-M28.1 修复：原实现用 `protectionDomain.codeSource.location`——2026.2 平台
     * PathClassLoader 下不可靠（用户日常 IDE 实证：已安装 jar manifest 含 MPV-Build
     * 但 codeSource 拿不到 jar 路径 → 构建号恒 "—"）；改为 classloader 定位**插件专属
     * 类文件资源**（`PluginBuildInfo.class` 相对路径——父加载器必然找不到，委托必落到
     * 插件自身 jar）→ JarURLConnection 提取 jar 路径（与 PluginVersion 读 plugin.xml
     * 同款手法，零平台 API 依赖）。目录形态（开发态/测试 classpath，file 协议）返回 null。
     */
    private static File ownJarFile() {
        try {
            URL url = PluginBuildInfo.class.getResource("PluginBuildInfo.class");
            if (url == null) return null;
            File f = jarFileFrom(url);
            return (f != null && f.isFile()) ? f : null;
        } catch (Throwable e) {
            return null;
        }
    }

    /**
     * 从类文件资源 URL 提取所在 jar：`jar:file:/…!/…` 协议返回 jar 文件；
     * file 协议（开发态 classes 目录）/异常返回 null。纯函数（单测直接覆盖）。
     */
    public static File jarFileFrom(URL url) {
        try {
            if (!"jar".equals(url.getProtocol())) return null;
            URL jarFileUrl = ((JarURLConnection) url.openConnection()).getJarFileURL();
            return new File(jarFileUrl.toURI());
        } catch (Throwable e) {
            return null;
        }
    }
}
