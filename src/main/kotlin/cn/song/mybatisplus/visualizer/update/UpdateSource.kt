package cn.song.mybatisplus.visualizer.update

/**
 * 更新源配置（M28）：清单 URL 与下载文件名约定集中于此——发布者维护，未来迁移更新源
 * （如改 GitHub 或上架市场）只改本文件。
 *
 * 系统属性 `mpvs.update.manifest.url` 可覆盖清单 URL（沙盒/本地 HTTP server 验证用，
 * 与项目「发布前本地验证」惯例一致）。
 */
object UpdateSource {

    /** 插件 id（与 plugin.xml `<id>` 一致；安装/卸载判定与下载文件命名共用）。 */
    const val PLUGIN_ID = "cn.song.mybatisplus.visualizer.mybatis-plus-sql-visualizer"

    /** 清单在仓库内的固定路径（raw URL 必须稳定，发布流程约定不可改）。 */
    const val MANIFEST_PATH = "update/update.json"

    /** 默认清单地址（Gitee 公开仓库 raw，无需鉴权，国内直连）。 */
    const val DEFAULT_MANIFEST_URL =
        "https://gitee.com/songrenshuo/mybatis-plus-sql-visualizer/raw/master/$MANIFEST_PATH"

    /** 清单 URL 覆盖系统属性名（沙盒验证/换源用）。 */
    const val MANIFEST_URL_OVERRIDE = "mpvs.update.manifest.url"

    /** Gitee 发布页（T-M28.4 变更日志链接目标——设置页/通知可点击跳转）。 */
    const val GITEE_RELEASES_URL = "https://gitee.com/songrenshuo/mybatis-plus-sql-visualizer/releases"

    /** 实际清单 URL（系统属性覆盖优先；属性空白视为未设置）。 */
    fun manifestUrl(): String =
        System.getProperty(MANIFEST_URL_OVERRIDE)?.takeIf { it.isNotBlank() } ?: DEFAULT_MANIFEST_URL

    /** Gitee Release 附件下载文件名（与 buildPlugin 产物命名一致）。 */
    fun downloadFileName(version: String): String = "mybatis-plus-sql-visualizer-$version.zip"

    /**
     * 插件安装目录名（2026-08-20 修复）：zip 顶层目录名（= 插件安装时解压出的目录名）。
     * 实证：zip 顶层为 `mybatis-plus-sql-visualizer/`，Install from Disk 后插件目录为
     * `plugins/mybatis-plus-sql-visualizer`（**短名**，取自 zip 顶层目录名）。
     * 不能直接用 `<id>`（FQN `cn.song.mybatisplus.visualizer.mybatis-plus-sql-visualizer`）——
     * 目录名与 id 不同名，DeleteCommand 按 FQN 删除会扑空，旧 JAR 不删、版本不变。
     */
    const val INSTALL_DIR_NAME = "mybatis-plus-sql-visualizer"
}
