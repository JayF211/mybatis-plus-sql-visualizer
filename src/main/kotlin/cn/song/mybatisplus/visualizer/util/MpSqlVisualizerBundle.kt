package cn.song.mybatisplus.visualizer.util

import cn.song.mybatisplus.visualizer.config.PluginSettings
import org.jetbrains.annotations.PropertyKey
import java.nio.charset.StandardCharsets
import java.text.MessageFormat
import java.util.Locale
import java.util.PropertyResourceBundle
import java.util.ResourceBundle

/**
 * 消息 Bundle（M6-03 国际化，M14 升级为插件级语言控制）。
 *
 * 由 `DynamicBundle`（跟随 IDE 语言）改为**自管理 ResourceBundle**：
 * 按 [PluginSettings.state.language] 选择语言（**默认简体中文**，用户要求）：
 *  - `zh`（默认）：简体中文；
 *  - `en`：English；
 *  - `AUTO`：跟随 IDE 界面语言（JVM 默认 locale）；
 *  - 测试环境（settings service 未注册）兜底中文，与默认值一致。
 *
 * properties 文件为 UTF-8 原始中文（非 `\uXXXX` 转义），需自定义
 * [ResourceBundle.Control] 以 UTF-8 读取，否则乱码。
 * 所有运行时 UI 文案统一经 [message] 获取，禁止硬编码。
 */
object MpSqlVisualizerBundle {

    private const val BUNDLE_NAME = "messages.MpSqlVisualizerBundle"

    /** UTF-8 properties 读取控制（_zh 文件为 UTF-8 原始中文）。 */
    private val utf8Control = object : ResourceBundle.Control() {
        override fun newBundle(
            baseName: String,
            locale: Locale,
            format: String,
            loader: ClassLoader,
            reload: Boolean,
        ): ResourceBundle? {
            if (format != "java.properties") return null
            val bundleName = toBundleName(baseName, locale)
            val resourceName = toResourceName(bundleName, "properties")
            val stream = loader.getResourceAsStream(resourceName) ?: return null
            return stream.use { PropertyResourceBundle(it.reader(StandardCharsets.UTF_8)) }
        }
    }

    /**
     * 取本地化文案。
     *
     * @param key    文案 key（见 messages/MpSqlVisualizerBundle*.properties）
     * @param params 格式化参数（MessageFormat 风格 {0}）
     */
    @JvmStatic
    fun message(@PropertyKey(resourceBundle = BUNDLE_NAME) key: String, vararg params: Any?): String {
        val pattern = ResourceBundle.getBundle(BUNDLE_NAME, currentLocale(), loader, utf8Control).getString(key)
        return if (params.isEmpty()) pattern else MessageFormat.format(pattern, *params)
    }

    private val loader: ClassLoader get() = MpSqlVisualizerBundle::class.java.classLoader

    /**
     * 当前语言 locale：默认简体中文；`en`→English；`AUTO`→跟随 IDE（JVM 默认）；
     * settings 未注册（测试环境）兜底中文。
     */
    private fun currentLocale(): Locale = runCatching {
        when (PluginSettings.getInstance().state.language) {
            "en" -> Locale.ENGLISH
            "AUTO" -> Locale.getDefault()
            else -> Locale.SIMPLIFIED_CHINESE
        }
    }.getOrElse { Locale.SIMPLIFIED_CHINESE }
}
