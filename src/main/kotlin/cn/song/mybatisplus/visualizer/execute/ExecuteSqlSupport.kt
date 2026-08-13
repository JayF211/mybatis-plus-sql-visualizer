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
 * 流程（Ultimate）：数据源枚举/选择 → 创建临时 .sql 文件（含当前 SQL）→ Database 控制台绑定
 * 数据源 → 打开编辑器（用户 Ctrl+Enter 执行，IDE 原生控制台行为）。
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
     * @param project 当前项目（面板无项目时不调用本方法）
     * @param sql     当前编辑器 SQL（含参数回填与手动编辑）
     */
    @JvmStatic
    fun execute(project: Project, sql: String) {
        if (!isAvailable()) {
            showInfo(project, MpSqlVisualizerBundle.message("execute.noDatabase"))
            return
        }
        runCatching {
            Class.forName(LAUNCHER_CLASS)
                .getMethod("execute", Project::class.java, String::class.java)
                .invoke(null, project, sql)
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
