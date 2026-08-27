package cn.song.mybatisplus.visualizer.execute

import cn.song.mybatisplus.visualizer.database.DatabaseConsoleLauncher
import com.intellij.openapi.project.Project

/**
 * 原生数据源选择器（M36，反射隔离）：打开临时文件触发 IDEA 原生数据源弹窗。
 *
 * 不创建自定义弹窗——直接创建临时 .sql 文件并打开编辑器，
 * IDEA Database 插件自动弹出原生数据源选择弹窗（带图标、搜索框、记住选择），
 * 用户选择后 Ctrl+Enter 执行，结果在编辑器下方原生展示。
 *
 * 反射隔离约定：本类仅当 Database 插件已加载时被调用，Community 下永不加载。
 */
object DataSourceSelector {

    /**
     * 选择数据源并执行 SQL。
     * 创建临时文件 → 打开编辑器 → 原生数据源弹窗自动出现。
     *
     * @param project  当前项目
     * @param sql      SQL 文本
     * @param callback 完成回调（暂不使用，执行由 Database 插件内部处理）
     */
    fun selectAndExecute(project: Project, sql: String, callback: ((Boolean) -> Unit)? = null) {
        if (!ExecuteSqlSupport.isAvailable()) {
            callback?.invoke(false)
            return
        }
        // 委托 DatabaseConsoleLauncher（反射隔离）创建临时文件并打开编辑器 → 原生弹窗自动出现
        DatabaseConsoleLauncher.execute(project, sql)
        callback?.invoke(true)
    }
}