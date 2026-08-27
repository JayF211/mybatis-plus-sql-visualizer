package cn.song.mybatisplus.visualizer.execute

import cn.song.mybatisplus.visualizer.database.DatabaseConsoleLauncher
import com.intellij.openapi.project.Project

/**
 * SQL 执行引擎（M36，反射隔离）：创建临时 .sql 文件并打开原生编辑器。
 *
 * 策略：委托 [DatabaseConsoleLauncher.execute] → 创建临时文件 → 打开编辑器 →
 * IDEA 原生数据源选择弹窗自动出现（编辑器右上角）→ 用户选择数据源后 Ctrl+Enter 执行。
 *
 * 反射隔离约定：本类仅当 Database 插件已加载时被调用，Community 下永不加载。
 */
object SqlExecutor {

    /**
     * 执行 SQL（核心入口）。
     *
     * @param project    当前项目
     * @param sql        SQL 文本
     * @param dataSource 保留参数（不使用，数据源由原生弹窗选择）
     * @param isQuery    是否为查询语句
     */
    fun execute(project: Project, sql: String, dataSource: Any?, isQuery: Boolean) {
        DatabaseConsoleLauncher.execute(project, sql)
    }
}
