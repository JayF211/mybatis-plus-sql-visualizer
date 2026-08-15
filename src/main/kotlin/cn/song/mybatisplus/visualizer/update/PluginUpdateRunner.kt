package cn.song.mybatisplus.visualizer.update

import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.util.io.FileUtil
import java.io.File
import java.nio.file.Path
import java.util.zip.ZipFile

/**
 * 更新编排（M28）：检查（fetch seam）与下载安装（download/install seam）的编排层。
 *
 * 网络（[UpdateApi]）与平台安装（[PlatformPluginInstaller]）不在此类——所有外部动作
 * 经**函数参数注入**，单测以 fake seam 全分支覆盖；UI 层（设置页/启动通知）只与
 * 本类交互，不在 UI 类里写网络/文件逻辑。
 */
object PluginUpdateRunner {

    /** 检查结果。 */
    sealed interface CheckResult {
        /** 已是最新（清单缺失/解析失败/比较异常等一切降级，不打扰用户）。 */
        data object UpToDate : CheckResult

        /** 发现新版本。 */
        data class Available(val manifest: UpdateManifest) : CheckResult

        /** 明确失败（网络不可达等），message 面向用户展示。 */
        data class Failure(val message: String) : CheckResult
    }

    /** 安装结果。 */
    sealed interface InstallResult {
        /** 安装已调度（下次重启生效——环境否决动态卸载，T-M23.10 定论）。 */
        data object Installed : InstallResult

        data class Failed(val message: String) : InstallResult
    }

    /**
     * 检查更新：拉取清单 → 解析 → 与当前版本比较。
     *
     * @param currentVersion 当前插件版本（[PluginVersion.current]）；不可读视为失败。
     * @param fetch 清单获取 seam（默认 [UpdateApi.fetchManifest]）。
     */
    fun check(
        currentVersion: String?,
        fetch: (String) -> String? = { UpdateApi.fetchManifest(it) },
    ): CheckResult {
        if (!VersionComparator.isRecognizable(currentVersion)) {
            return CheckResult.Failure("current version unavailable")
        }
        val json = fetch(UpdateSource.manifestUrl()) ?: return CheckResult.Failure("network unavailable")
        val manifest = parseManifest(json) ?: return CheckResult.Failure("manifest malformed")
        return if (VersionComparator.compare(manifest.version, currentVersion) > 0) {
            CheckResult.Available(manifest)
        } else {
            CheckResult.UpToDate
        }
    }

    /**
     * 下载并安装：下载到临时目录 → zip 校验（存在/非空/可打开）→ install seam →
     * 无论成败清理临时下载文件（安装已调度时由 [PlatformPluginInstaller] 先持久化副本）。
     *
     * @param indicator 进度（下载 fraction）；可 null。
     * @param download 下载 seam（默认 [UpdateApi.download]）。
     * @param install 安装 seam（默认 [PlatformPluginInstaller.install]；**单测不触达**）。
     */
    fun downloadAndInstall(
        manifest: UpdateManifest,
        indicator: ProgressIndicator?,
        download: (String, Path) -> Unit = { url, target -> UpdateApi.download(url, target, indicator) },
        install: (Path) -> InstallResult = { PlatformPluginInstaller.install(it) },
    ): InstallResult {
        val temp: File = try {
            FileUtil.createTempFile("mpvs-update-", ".zip", false)
        } catch (e: Exception) {
            return InstallResult.Failed(e.message ?: e.toString())
        }
        return try {
            download(manifest.downloadUrl, temp.toPath())
            if (!isValidZip(temp)) {
                InstallResult.Failed("download is not a valid plugin archive")
            } else {
                install(temp.toPath())
            }
        } catch (e: Exception) {
            InstallResult.Failed(e.message ?: e.toString())
        } finally {
            FileUtil.delete(temp)
        }
    }

    /** zip 校验：存在、非空、可被 ZipFile 打开（防下载中断/HTML 错误页落盘）。 */
    internal fun isValidZip(file: File): Boolean = runCatching {
        if (!file.isFile || file.length() == 0L) return false
        ZipFile(file).use { true }
    }.getOrDefault(false)
}
