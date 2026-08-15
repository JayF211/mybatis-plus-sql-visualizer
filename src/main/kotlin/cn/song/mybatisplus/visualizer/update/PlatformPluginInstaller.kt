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
        if (old != null && !isUnderPluginsDir(old.descriptorPath)) {
            // 开发态/非插件目录安装（如 runIde 从 build/classes 加载）——卸载调度会误删
            // 构建产物，拒绝并提示（正式安装形态下 descriptorPath 必在插件目录内）
            return PluginUpdateRunner.InstallResult.Failed(
                "current installation is not under the IDE plugins directory; update from a regular install"
            )
        }
        val persistent = persistZip(zip)
        if (old != null) {
            PluginInstaller.uninstallAfterRestart(old)
        }
        val targetDir = PathManager.getPluginsDir().resolve(pluginId.idString)
        StartupActionScriptManager.addActionCommand(StartupActionScriptManager.UnzipCommand(persistent, targetDir))
        PluginUpdateRunner.InstallResult.Installed
    }.getOrElse { error ->
        LOG.warn("plugin update install failed — ${error.message}", error)
        PluginUpdateRunner.InstallResult.Failed(error.message ?: error.toString())
    }

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
     * 旧版安装位置是否在插件目录内（跨平台，T-M28.1/28.3）：descriptorPath 归一化后
     * Path **组件级**比较 + `toRealPath()` 解析符号链接（Windows 组件比较大小写不敏感；
     * Mac 解析后两侧均为真实路径必然一致）；`toRealPath` 失败（文件不存在等）回退字符串
     * 比较。拒绝时记日志（含原始路径，便于诊断格式差异）。
     */
    internal fun isUnderPluginsDir(descriptorPath: String?): Boolean {
        if (descriptorPath.isNullOrBlank()) return false
        val raw = normalizeDescriptorPath(descriptorPath)
        if (raw.isBlank()) return false
        val ok = runCatching {
            Path.of(raw).toRealPath().startsWith(PathManager.getPluginsDir().toRealPath())
        }.getOrElse {
            val pluginsRoot = PathManager.getPluginsDir().toAbsolutePath().normalize().toString()
            Path.of(raw).toAbsolutePath().normalize().toString().startsWith(pluginsRoot)
        }
        if (!ok) {
            LOG.warn("plugin update guard rejected: descriptorPath=$descriptorPath (normalized=$raw)")
        }
        return ok
    }
}
