package cn.song.mybatisplus.visualizer.update;

import java.nio.charset.StandardCharsets;
import java.io.InputStream;
import java.util.regex.Pattern;

/**
 * 自身插件版本读取（M28）。
 *
 * 从**自身 classloader 的 `META-INF/plugin.xml`** 读取 `<version>`——对外版本号由
 * `patchPluginXml` 在构建期注入（打包 jar 与开发态 classes 资源均存在），无需平台 API。
 *
 * 背景（不依赖 `PluginManagerCore`/`PluginId` 的原因）：该组 API 在 2025.3~2026.2 间有
 * 包迁移（ExecuteSqlSupport 注释实证），本项目编译基线 2024.3.7.1 / 运行 IDE 2026.2.1，
 * 双端直接引用有 NoClassDefFoundError 风险；读取自身 plugin.xml 零平台依赖（同
 * PluginBuildInfo 的 codeSource/classloader 手法）。
 */
public final class PluginVersion {

    public static final PluginVersion INSTANCE = new PluginVersion();
    private PluginVersion() {}

    private static final String PLUGIN_XML = "META-INF/plugin.xml";

    /** `<version>…</version>` 子元素（注意：`<idea-plugin version="2">` 是属性，不匹配）。 */
    private static final Pattern VERSION_REGEX = Pattern.compile("<version>([^<]+)</version>");

    private static volatile String cached = null;

    /** 当前插件版本（如 `2026.8.14`）；非打包/异常环境返回 null（UI 显示 "—" 兜底）。 */
    public static String current() {
        if (cached != null) return cached;
        String v = readFromResource();
        cached = v;
        return v;
    }

    /** 从自身 classloader 读取并解析 plugin.xml；任何异常返回 null（不抛出）。 */
    public static String readFromResource() {
        try {
            InputStream stream = PluginVersion.class.getClassLoader().getResourceAsStream(PLUGIN_XML);
            if (stream == null) return null;
            try (InputStream s = stream) {
                return parseVersion(new String(s.readAllBytes(), StandardCharsets.UTF_8));
            }
        } catch (Exception e) {
            return null;
        }
    }

    /** 解析 plugin.xml 文本中的 `<version>` 子元素；缺失/非法返回 null。纯函数（单测直接覆盖）。 */
    public static String parseVersion(String xml) {
        java.util.regex.Matcher m = VERSION_REGEX.matcher(xml);
        if (!m.find()) return null;
        String g = m.group(1);
        if (g == null || g.isBlank()) return null;
        return g;
    }
}
