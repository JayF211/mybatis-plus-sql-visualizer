package cn.song.mybatisplus.visualizer.update;

/**
 * 更新源配置（M28 → 内测通道改造）：清单 URL 与下载文件名约定集中于此。
 *
 * 内部更新通道已转为「Gitee 抢先体验通道」，检查 [MANIFEST_PATH] 指向 [update/update.json]
 *（含 `-beta.N` 内测版与正式版提前推送）；稳定版由 JetBrains Marketplace 接管，本通道不参与。
 * 草稿请写 [update/preview.update.json]（不参与检查），发布时复制到 [update/update.json]。
 *
 * 系统属性 `mpvs.update.manifest.url` 可覆盖清单 URL（沙盒/本地 HTTP server 验证用，
 * 与项目「发布前本地验证」惯例一致）。
 */
public final class UpdateSource {

    public static final UpdateSource INSTANCE = new UpdateSource();
    private UpdateSource() {}

    /** 插件 id（与 plugin.xml `<id>` 一致；安装/卸载判定与下载文件命名共用）。 */
    public static final String PLUGIN_ID = "cn.song.mybatisplus.visualizer.mybatis-plus-sql-visualizer";

    /** 清单在仓库内的固定路径（raw URL 必须稳定，发布流程约定不可改）。 */
    public static final String MANIFEST_PATH = "update/update.json";

    /** 默认清单地址（Gitee 公开仓库 raw，无需鉴权，国内直连）。 */
    public static final String DEFAULT_MANIFEST_URL =
        "https://gitee.com/songrenshuo/mybatis-plus-sql-visualizer/raw/master/" + MANIFEST_PATH;

    /** 清单 URL 覆盖系统属性名（沙盒验证/换源用）。 */
    public static final String MANIFEST_URL_OVERRIDE = "mpvs.update.manifest.url";

    /** Gitee 发布页（T-M28.4 变更日志链接目标——设置页/通知可点击跳转）。 */
    public static final String GITEE_RELEASES_URL = "https://gitee.com/songrenshuo/mybatis-plus-sql-visualizer/releases";

    /** 插件安装目录名（2026-08-20 修复）：zip 顶层目录名（= 插件安装时解压出的目录名）。 */
    public static final String INSTALL_DIR_NAME = "mybatis-plus-sql-visualizer";

    /** 实际清单 URL（系统属性覆盖优先；属性空白视为未设置）。 */
    public String manifestUrl() {
        String prop = System.getProperty(MANIFEST_URL_OVERRIDE);
        if (prop != null && !prop.isBlank()) return prop;
        return DEFAULT_MANIFEST_URL;
    }

    /** Gitee Release 附件下载文件名（与 buildPlugin 产物命名一致）。 */
    public String downloadFileName(String version) {
        return "mybatis-plus-sql-visualizer-" + version + ".zip";
    }
}
