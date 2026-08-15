package cn.song.mybatisplus.visualizer.update

import com.intellij.ide.plugins.PluginInstaller
import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.ide.startup.StartupActionScriptManager
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.extensions.PluginId
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermissions

/**
 * 平台安装封装（M28）：调度「下次启动替换插件」——卸载旧版（[PluginInstaller.uninstallAfterRestart]）
 * + 解压新版（[StartupActionScriptManager.UnzipCommand]），随后由调用方提示重启
 * （[com.intellij.util.ui.RestartDialogImpl.restartWithConfirmation]，环境否决动态卸载，
 * 覆盖更新必重启——T-M23.10 定论，不做动态加载）。
 *
 * 版本兼容（2025.3.5~2026.2.1 探测实证）：`PluginInstaller.uninstallAfterRestart` /
 * `StartupActionScriptManager.addActionCommand` / `PluginId`（openapi.extensions）在两端
 * 签名一致；**单测不调用本类**（编排层 [PluginUpdateRunner] 以 install seam 注入替代），
 * 由 runIde 沙盒端到端验证真实安装链路。
 */
object PlatformPluginInstaller {

    private val LOG = Logger.getInstance(PlatformPluginInstaller::class.java)

    /** 待安装包持久化目录（启动脚本在下次 IDE 启动时执行，临时目录可能被系统清理）。 */
    private const val PENDING_DIR = "mpvs-update"

    /**
     * 调度安装：校验旧版安装位置（防开发态误删 build 产物）→ 持久化下载包 →
     * 卸载旧版 + 解压新版（脚本按序执行：先删后装）。
     */
    fun install(zip: Path): PluginUpdateRunner.InstallResult = runCatching {
        val pluginId = PluginId.findId(UpdateSource.PLUGIN_ID)
            ?: return PluginUpdateRunner.InstallResult.Failed("plugin id not found")
        val old = PluginManagerCore.getPlugin(pluginId)
        if (old != null && isDevInstallation(old.descriptorPath)) {
            // 开发态安装（runIde/测试从 build/classes 加载）——卸载调度会误删构建产物，拒绝；
            // 错误消息带 descriptorPath 便于诊断格式差异（T-M28.3 二修：白名单判定误拒实证）
            LOG.warn("plugin update rejected: dev-mode installation (descriptorPath=${old.descriptorPath})")
            return PluginUpdateRunner.InstallResult.Failed(
                "updates are not supported for a development-mode installation (descriptorPath: " +
                    "${old.descriptorPath ?: "null"}); install from the plugin zip first"
            )
        }
        val persistent = persistZip(zip)
        if (old != null) {
            PluginInstaller.uninstallAfterRestart(old)
        }
        // T-M28.5：解压目标 = 插件目录**根**——发布 zip 顶层自带插件名目录（IPGP buildPlugin
        // 布局 `mybatis-plus-sql-visualizer/lib/…`，Install from Disk 会剥顶层），UnzipCommand
        // 纯解压不剥顶层；解压到 `<plugins>/<pluginId>/` 会多嵌套一层（用户实测：重启后插件
        // 列表消失——平台扫描不到嵌套 jar 内的 plugin.xml）。解压到根后 zip 顶层目录名
        // 与旧安装目录名一致，删旧解新即正确替换。
        StartupActionScriptManager.addActionCommand(
            StartupActionScriptManager.UnzipCommand(persistent, unzipTargetDir())
        )
        PluginUpdateRunner.InstallResult.Installed
    }.getOrElse { error ->
        LOG.warn("plugin update install failed — ${error.message}", error)
        PluginUpdateRunner.InstallResult.Failed(error.message ?: error.toString())
    }

    /**
     * 解压目标目录（T-M28.5）：插件目录**根**——发布 zip 顶层含插件名目录
     * （`mybatis-plus-sql-visualizer/lib/…`），UnzipCommand 不剥顶层，目标必须是根
     * 才能解出正确形态（见 install() 注释）。internal 供测试断言。
     */
    internal fun unzipTargetDir(): Path = PathManager.getPluginsDir()

    /** 下载包复制到 system 目录下持久位置（重启后启动脚本读取；留在原处不删——脚本引用）。 */
    private fun persistZip(zip: Path): Path {
        val dir = Path.of(PathManager.getSystemPath(), PENDING_DIR)
        Files.createDirectories(dir)
        val target = dir.resolve(zip.fileName.toString())
        Files.copy(zip, target, StandardCopyOption.REPLACE_EXISTING)
        ensureReadable(target)
        return target
    }

    /**
     * 显式确保文件可读（T-M28.3 用户要求：下载包权限不得导致安装失败）——POSIX 平台设
     * `rw-r--r--`（Files.copy 保留源 600 权限，sudo/多用户场景下下次启动的启动脚本可能读
     * 不了）；Windows 无 POSIX API（UnsupportedOperationException）静默跳过；其他失败仅
     * 记日志不阻断——真正读取失败由安装环节报错兜底。
     */
    internal fun ensureReadable(file: Path) {
        runCatching {
            Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-r--r--"))
        }.onFailure { error ->
            if (error !is UnsupportedOperationException) {
                LOG.warn("set readable permissions failed: $file — ${error.message}")
            }
        }
    }

    /**
     * 规范化 descriptorPath（T-M28.3）：剥离 `!` 后缀（jar 内路径）、`jar:file:`/`file:` 前缀
     * （大小写不敏感）、Windows 驱动字母前导斜杠（`/C:/` → `C:/`）。
     *
     * 背景：2026.2 平台返回 URL 风格路径（用户日常 IDE 实证——未归一化时
     * `Path.of("/C:/...")` 在 Windows 解析为 `\C:\...`，与 `C:\...` 比较失败 → 守卫误拒
     * 正常安装，更新失败）。纯函数（单测直接覆盖）。
     */
    internal fun normalizeDescriptorPath(raw: String): String {
        var s = raw.substringBefore('!').trim()
        val lower = s.lowercase()
        for (prefix in listOf("jar:file:", "file:")) {
            if (lower.startsWith(prefix)) {
                s = s.substring(prefix.length)
                break
            }
        }
        // Windows 驱动字母前导斜杠：/C:/Users/... → C:/Users/...（Mac 绝对路径 /Users/... 不匹配）
        if (s.length >= 3 && s[0] == '/' && s[2] == ':') {
            s = s.substring(1)
        }
        return s
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
    internal fun isDevInstallation(descriptorPath: String?): Boolean {
        if (descriptorPath.isNullOrBlank()) return false
        val segments = normalizeDescriptorPath(descriptorPath).replace('\\', '/').split('/')
        return segments.any { it in DEV_PATH_MARKERS }
    }

    private val DEV_PATH_MARKERS = setOf("build", "classes", "out", "target", ".gradle")
}
