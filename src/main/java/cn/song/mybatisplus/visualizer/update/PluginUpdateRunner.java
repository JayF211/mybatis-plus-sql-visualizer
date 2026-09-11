package cn.song.mybatisplus.visualizer.update;

import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.util.io.FileUtil;
import java.io.File;
import java.nio.file.Path;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.zip.ZipFile;

/**
 * 更新编排（M28 → T-DynUpdate）：检查（fetch seam）与下载安装（download/install seam）的编排层。
 *
 * 网络（UpdateApi）与平台安装（DynamicInstallSupport 动态热替换优先、PlatformPluginInstaller
 * 脚本兜底）不在此类——所有外部动作经**函数参数注入**，单测以 fake seam 全分支覆盖；
 * UI 层（设置页/启动通知）只与本类交互，不在 UI 类里写网络/文件逻辑。
 */
public final class PluginUpdateRunner {

    public static final PluginUpdateRunner INSTANCE = new PluginUpdateRunner();
    private PluginUpdateRunner() {}

    private static final Logger LOG = Logger.getInstance(PluginUpdateRunner.class);

    /** 检查结果。 */
    public sealed interface CheckResult {
        /** 已是最新（清单缺失/解析失败/比较异常等一切降级，不打扰用户）。 */
        public static final class UpToDate implements CheckResult {
            public static final UpToDate INSTANCE = new UpToDate();
            private UpToDate() {}
        }

        /** 发现新版本。 */
        public record Available(UpdateManifest manifest) implements CheckResult {}

        /** 明确失败（网络不可达等），message 面向用户展示。 */
        public record Failure(String message) implements CheckResult {}
    }

    /** 安装结果（T-DynUpdate 扩容：热生效 / 回退重启 / 失败三分支）。 */
    public sealed interface InstallResult {
        /** 已动态热生效：旧版已卸载、新版已装载，无需重启（T-DynUpdate 主路径成功）。 */
        public record InstalledDynamic(String version) implements InstallResult {}

        /** 已调度启动脚本替换（平台否决/动态链失败回退——重启生效，M28 原行为）。 */
        public static final class InstalledRestartRequired implements InstallResult {
            public static final InstalledRestartRequired INSTANCE = new InstalledRestartRequired();
            private InstalledRestartRequired() {}
        }

        public record Failed(String message) implements InstallResult {}
    }

    /**
     * 检查更新：拉取清单 → 解析 → 与当前版本比较（使用默认 fetch seam）。
     *
     * @param currentVersion 当前插件版本（[PluginVersion.current]）；不可读视为失败。
     */
    public static CheckResult check(String currentVersion) {
        return check(currentVersion, it -> UpdateApi.INSTANCE.fetchManifest(it));
    }

    /**
     * 检查更新：拉取清单 → 解析 → 与当前版本比较。
     *
     * @param currentVersion 当前插件版本（[PluginVersion.current]）；不可读视为失败。
     * @param fetch 清单获取 seam（默认 [UpdateApi.fetchManifest]）。
     */
    public static CheckResult check(String currentVersion, Function<String, String> fetch) {
        if (!VersionComparator.INSTANCE.isRecognizable(currentVersion)) {
            return new CheckResult.Failure("current version unavailable");
        }
        String json = fetch.apply(UpdateSource.INSTANCE.manifestUrl());
        if (json == null) return new CheckResult.Failure("network unavailable");
        UpdateManifest manifest = UpdateManifest.parseManifest(json);
        if (manifest == null) return new CheckResult.Failure("manifest malformed");
        if (VersionComparator.INSTANCE.compare(manifest.version(), currentVersion) > 0) {
            return new CheckResult.Available(manifest);
        } else {
            return CheckResult.UpToDate.INSTANCE;
        }
    }

    /**
     * 下载并安装（使用默认 download/install seam）。
     *
     * @param indicator 进度（下载 fraction）；可 null。
     */
    public static InstallResult downloadAndInstall(UpdateManifest manifest, ProgressIndicator indicator) {
        return downloadAndInstall(manifest, indicator,
            (url, target) -> {
                try {
                    UpdateApi.INSTANCE.download(url, target, indicator);
                } catch (java.io.IOException e) {
                    throw new java.io.UncheckedIOException(e);
                }
            },
            (path) -> DynamicInstallSupport.INSTANCE.install(path));
    }

    /**
     * 下载并安装：下载到临时目录 → zip 校验（存在/非空/可打开）→ install seam →
     * 无论成败清理临时下载文件（动态热替换或脚本调度均由安装层自行持久化/解压）。
     *
     * @param indicator 进度（下载 fraction）；可 null。
     * @param download 下载 seam（默认 UpdateApi.download）。
     * @param install 安装 seam（默认 DynamicInstallSupport.install——动态热替换优先、
     *   脚本兜底；**单测不触达**，注入 fake 全分支覆盖）。
     */
    public static InstallResult downloadAndInstall(
        UpdateManifest manifest,
        ProgressIndicator indicator,
        BiConsumer<String, Path> download,
        Function<Path, InstallResult> install
    ) {
        File temp;
        try {
            temp = FileUtil.createTempFile("mpvs-update-", ".zip", false);
        } catch (Exception e) {
            String msg = e.getMessage() != null ? e.getMessage() : e.toString();
            return new InstallResult.Failed(msg);
        }
        try {
            LOG.info("downloadAndInstall: downloading " + manifest.downloadUrl());
            download.accept(manifest.downloadUrl(), temp.toPath());
            LOG.info("downloadAndInstall: downloaded to " + temp + " (" + temp.length() + " bytes)");
            if (!isValidZip(temp)) {
                return new InstallResult.Failed("download is not a valid plugin archive");
            } else {
                LOG.info("downloadAndInstall: zip valid, calling install");
                return install.apply(temp.toPath());
            }
        } catch (Exception e) {
            LOG.warn("downloadAndInstall: failed — " + e.getMessage(), e);
            String msg = e.getMessage() != null ? e.getMessage() : e.toString();
            return new InstallResult.Failed(msg);
        } finally {
            FileUtil.delete(temp);
        }
    }

    /** zip 校验：存在、非空、可被 ZipFile 打开（防下载中断/HTML 错误页落盘）。 */
    public static boolean isValidZip(File file) {
        try {
            if (!file.isFile() || file.length() == 0L) return false;
            try (ZipFile zf = new ZipFile(file)) {
                return true;
            }
        } catch (Exception e) {
            return false;
        }
    }
}
