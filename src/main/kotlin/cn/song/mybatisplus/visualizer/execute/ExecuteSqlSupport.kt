package cn.song.mybatisplus.visualizer.execute

import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages

/**
 * 执行 SQL 支持（M17）：SQL 预览面板「执行 SQL」按钮的 core 入口。
 *
 * Community 兼容（M17-R5）：`com.intellij.database`（数据源/控制台）**仅 IntelliJ IDEA Ultimate /
 * DataGrip 捆绑**——本类与面板**零直接引用** database 类；实际实现
 * [cn.song.mybatisplus.visualizer.database.DatabaseConsoleLauncher] 经**反射**加载：
 * launcher 字节码位于主 jar，但仅当 database 插件已加载时才被 JVM 解析（Community 下永不触碰，
 * 无 NoClassDefFoundError）。
 *
 * 流程（Ultimate，M19）：创建临时 .sql 文件（含当前 SQL）→ 打开编辑器（Database 插件
 * SQL 编辑器右上角**数据源选择器**由用户自行选择数据源，Ctrl+Enter 执行——IDE 原生行为）。
 */
object ExecuteSqlSupport {

    /** Database 插件标记类（插件加载时必在 classpath；探测用，版本无关——绕开 PluginId API 迁移）。 */
    private const val DATABASE_MARKER_CLASS = "com.intellij.database.dataSource.LocalDataSourceManager"

    /** 隔离实现类（仅 database 插件存在时反射加载）。 */
    private const val LAUNCHER_CLASS = "cn.song.mybatisplus.visualizer.database.DatabaseConsoleLauncher"

    /**
     * database 插件是否已加载（Ultimate/DataGrip）：`Class.forName` 探测标记类——
     * 不依赖 `PluginManagerCore.isPluginInstalled`/`PluginId` 的版本形态（该 API 在 2025.3~2026.2
     * 间有包迁移），Community/测试沙盒（jar 已排除）下返回 false。
     */
    @JvmStatic
    fun isAvailable(): Boolean = runCatching {
        Class.forName(DATABASE_MARKER_CLASS)
        true
    }.getOrDefault(false)

    /**
     * 执行入口：不可用 → 提示升级 Ultimate；可用 → 反射调用 launcher。
     * 所有 UI 提示均 runCatching 兜底（测试/headless 环境不抛异常）。
     *
     * @param project      当前项目（面板无项目时不调用本方法）
     * @param sql          当前编辑器 SQL（含参数回填与手动编辑）
     * @param onClosePanel T-M23.6：成功提示「关闭 SQL 面板」按钮回调（预览弹窗关闭）；
     *                     null → 成功提示维持单 OK（非预览弹窗入口零影响）
     */
    @JvmStatic
    fun execute(project: Project, sql: String, onClosePanel: (() -> Unit)? = null) {
        if (!isAvailable()) {
            showInfo(project, MpSqlVisualizerBundle.message("execute.noDatabase"))
            return
        }
        runCatching {
            // T-M23.6：第三参 Runnable 透传「关闭 SQL 面板」回调（反射方法签名与 launcher 一致）
            val closeRunnable = onClosePanel?.let { Runnable { it() } }
            val method = Class.forName(LAUNCHER_CLASS)
                .getMethod("execute", Project::class.java, String::class.java, Runnable::class.java)
            method.invoke(null, project, sql, closeRunnable)
        }.onFailure { error ->
            showError(project, MpSqlVisualizerBundle.message("execute.failed", error.message ?: error.toString()))
        }
    }

    private fun showInfo(project: Project, message: String) {
        runCatching { Messages.showInfoMessage(project, message, MpSqlVisualizerBundle.message("execute.title")) }
    }

    private fun showError(project: Project, message: String) {
        runCatching { Messages.showErrorDialog(project, message, MpSqlVisualizerBundle.message("execute.title")) }
    }
}
