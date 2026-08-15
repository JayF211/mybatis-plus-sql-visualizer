package cn.song.mybatisplus.visualizer.update

/**
 * 自身插件版本读取（M28）。
 *
 * 从**自身 classloader 的 `META-INF/plugin.xml`** 读取 `<version>`——对外版本号由
 * `patchPluginXml` 在构建期注入（打包 jar 与开发态 classes 资源均存在），无需平台 API。
 *
 * 背景（不依赖 `PluginManagerCore`/`PluginId` 的原因）：该组 API 在 2025.3~2026.2 间有
 * 包迁移（ExecuteSqlSupport 注释实证），本项目编译基线 2025.3.5 / 运行 IDE 2026.2.1，
 * 双端直接引用有 NoClassDefFoundError 风险；读取自身 plugin.xml 零平台依赖（同
 * PluginBuildInfo 的 codeSource/classloader 手法）。
 */
object PluginVersion {

    private const val PLUGIN_XML = "META-INF/plugin.xml"

    /** `<version>…</version>` 子元素（注意：`<idea-plugin version="2">` 是属性，不匹配）。 */
    private val VERSION_REGEX = Regex("<version>([^<]+)</version>")

    @Volatile
    private var cached: String? = null

    /** 当前插件版本（如 `2026.8.14`）；非打包/异常环境返回 null（UI 显示 "—" 兜底）。 */
    fun current(): String? {
        cached?.let { return it }
        val v = readFromResource()
        cached = v
        return v
    }

    /** 从自身 classloader 读取并解析 plugin.xml；任何异常返回 null（不抛出）。 */
    internal fun readFromResource(): String? = runCatching {
        val stream = PluginVersion::class.java.classLoader.getResourceAsStream(PLUGIN_XML) ?: return null
        stream.use { s ->
            parseVersion(s.readBytes().toString(Charsets.UTF_8))
        }
    }.getOrNull()

    /** 解析 plugin.xml 文本中的 `<version>` 子元素；缺失/非法返回 null。纯函数（单测直接覆盖）。 */
    internal fun parseVersion(xml: String): String? =
        VERSION_REGEX.find(xml)?.groupValues?.get(1)?.takeIf { it.isNotBlank() }
}
