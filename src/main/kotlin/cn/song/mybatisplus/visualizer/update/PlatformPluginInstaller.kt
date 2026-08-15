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
        return target
    }

    /**
     * 旧版安装位置是否在插件目录内：descriptorPath（plugin.xml 路径）规范化前缀判定；
     * zip 形态的 descriptorPath 可能带 `jar:file:`/`!` 前缀（按包名容忍剥离）。
     */
    private fun isUnderPluginsDir(descriptorPath: String?): Boolean {
        if (descriptorPath.isNullOrBlank()) return false
        val raw = descriptorPath.substringBefore('!').removePrefix("jar:file:")
        val pluginsRoot = PathManager.getPluginsDir().toAbsolutePath().normalize().toString()
        val path = Path.of(raw).toAbsolutePath().normalize().toString()
        return path.startsWith(pluginsRoot)
    }
}
