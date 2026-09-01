package cn.song.mybatisplus.visualizer.util;

import cn.song.mybatisplus.visualizer.config.PluginSettings;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.PropertyKey;

import java.nio.charset.StandardCharsets;
import java.text.MessageFormat;
import java.util.Locale;
import java.util.PropertyResourceBundle;
import java.util.ResourceBundle;

/**
 * 消息 Bundle（M6-03 国际化，M14 升级为插件级语言控制）。
 *
 * 由 `DynamicBundle`（跟随 IDE 语言）改为**自管理 ResourceBundle**：
 * 按 [PluginSettings.State.getLanguage] 选择语言（**默认简体中文**，用户要求）：
 *  - `zh`（默认）：简体中文；
 *  - `en`：English；
 *  - `AUTO`：跟随 IDE 界面语言（JVM 默认 locale）；
 *  - 测试环境（settings service 未注册）兜底中文，与默认值一致。
 *
 * properties 文件为 UTF-8 原始中文（非 Unicode 转义形式），需自定义
 * [ResourceBundle.Control] 以 UTF-8 读取，否则乱码。
 * 所有运行时 UI 文案统一经 [message] 获取，禁止硬编码。
 */
public final class MpSqlVisualizerBundle {

    private static final String BUNDLE_NAME = "messages.MpSqlVisualizerBundle";

    /** UTF-8 properties 读取控制（_zh 文件为 UTF-8 原始中文）。 */
    private static final ResourceBundle.Control UTF8_CONTROL = new ResourceBundle.Control() {
        @Override
        public ResourceBundle newBundle(String baseName, Locale locale, String format, ClassLoader loader, boolean reload) {
            if (!"java.properties".equals(format)) return null;
            String bundleName = toBundleName(baseName, locale);
            String resourceName = toResourceName(bundleName, "properties");
            try (java.io.InputStream stream = loader.getResourceAsStream(resourceName)) {
                if (stream == null) return null;
                return new PropertyResourceBundle(new java.io.InputStreamReader(stream, StandardCharsets.UTF_8));
            } catch (java.io.IOException e) {
                return null;
            }
        }

        /**
         * M14.7 修复「中英文切换无效」：禁用 JVM 默认 locale 回退。
         *
         * Java ResourceBundle 的 fallback 链默认包含 [Locale.getDefault]（JVM 语言环境）——
         * 系统为中文时请求 `en` 找不到 `_en` 会回退到系统中文 locale，导致切英文无效。
         * 返回 null 后 fallback 直达 base（英文），`en`/`zh` 由 [currentLocale] 精确控制。
         */
        @Override
        public Locale getFallbackLocale(String baseName, Locale locale) {
            return null;
        }
    };

    private MpSqlVisualizerBundle() {
    }

    /**
     * 取本地化文案。
     *
     * @param key    文案 key（见 messages/MpSqlVisualizerBundle*.properties）
     * @param params 格式化参数（MessageFormat 风格 {0}）
     */
    @NotNull
    public static String message(@NotNull @PropertyKey(resourceBundle = BUNDLE_NAME) String key, Object... params) {
        // 兜底：bundle 加载/key 缺失时返回 key 本身（对齐 IntelliJ BundleBase 行为，避免 UI 抛异常）
        String pattern;
        try {
            pattern = ResourceBundle.getBundle(BUNDLE_NAME, currentLocale(), loader(), UTF8_CONTROL).getString(key);
        } catch (Throwable e) {
            pattern = key;
        }
        if (params == null || params.length == 0) return pattern;
        try {
            return MessageFormat.format(pattern, params);
        } catch (Throwable e) {
            return pattern;
        }
    }

    private static ClassLoader loader() {
        return MpSqlVisualizerBundle.class.getClassLoader();
    }

    /**
     * 当前语言 locale：默认简体中文；`en`→English；`AUTO`→跟随 IDE（JVM 默认）；
     * settings 未注册（测试环境）兜底中文。
     */
    private static Locale currentLocale() {
        try {
            String lang = PluginSettings.getInstance().getState().getLanguage();
            if ("en".equals(lang)) return Locale.ENGLISH;
            if ("AUTO".equals(lang)) return Locale.getDefault();
            return Locale.SIMPLIFIED_CHINESE;
        } catch (Throwable e) {
            return Locale.SIMPLIFIED_CHINESE;
        }
    }
}
