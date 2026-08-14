package cn.song.mybatisplus.visualizer.util

import java.io.File
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.jar.JarFile

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
object PluginBuildInfo {

    /** jar manifest 中的构建号属性名（与 build.gradle.kts 注入名一致）。 */
    const val MANIFEST_ATTRIBUTE = "MPV-Build"

    /** 构建号在设置页的显示前缀（label 文本与 bundle 前缀一致，便于用户对照）。 */
    const val DISPLAY_PREFIX = "MPV-Build"

    private val TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

    /** 当前插件产物的构建号（毫秒时间戳字符串）；非 jar 环境（开发/测试）返回 null。 */
    fun mpvBuild(): String? = ownJarFile()?.let { readMpvBuild(it) }

    /**
     * 从 jar manifest 读取 `MPV-Build` 属性；文件缺失/非 jar/无该属性均返回 null。
     * 独立成 internal 便于纯 JUnit 测试（临时 jar 构造）。
     */
    internal fun readMpvBuild(jarFile: File): String? = runCatching {
        JarFile(jarFile).use { it.manifest?.mainAttributes?.getValue(MANIFEST_ATTRIBUTE) }
    }.getOrNull()

    /** 构建号（毫秒时间戳）转本地可读时间；不可解析返回 null。 */
    fun formatBuildTime(timestamp: String?): String? = runCatching {
        val millis = timestamp?.toLong() ?: return null
        TIME_FORMATTER.format(LocalDateTime.ofInstant(Instant.ofEpochMilli(millis), ZoneId.systemDefault()))
    }.getOrNull()

    /** 设置页展示文本：`MPV-Build: 1786700024611（2026-08-14 17:33:44）`；无构建号显示 "—"。 */
    fun displayText(): String {
        val raw = mpvBuild()
        if (raw == null) return "—"
        val time = formatBuildTime(raw)
        return if (time != null) "$DISPLAY_PREFIX: $raw（$time）" else "$DISPLAY_PREFIX: $raw"
    }

    /** 本类所在 jar（codeSource）；目录形态（runIde 增量编译/测试 classpath）返回 null。 */
    private fun ownJarFile(): File? = runCatching {
        val location = PluginBuildInfo::class.java.protectionDomain.codeSource.location
        val file = File(location.toURI())
        file.takeIf { it.isFile && it.name.endsWith(".jar", ignoreCase = true) }
    }.getOrNull()
}
