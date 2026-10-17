package cn.song.mybatisplus.visualizer.ui.annotate

import cn.song.mybatisplus.visualizer.execute.DataSourceSelector
import cn.song.mybatisplus.visualizer.execute.DataSourceSettings
import cn.song.mybatisplus.visualizer.execute.SchemaValidationGate
import cn.song.mybatisplus.visualizer.sql.DataSourceUrlProbe
import cn.song.mybatisplus.visualizer.sql.schema.SchemaValidator
import cn.song.mybatisplus.visualizer.ui.highlight.DatabaseSchemaResolver
import com.intellij.openapi.project.Project

/**
 * UI029：预览面板**自身已确定的目标**回落（仅当旧入口
 * [DataSourceSelector.schemaValidationTarget] 返回 null 时使用）。
 *
 * ## 为什么需要回落
 *
 * 从 SQL 日志条目打开的预览由面板自建编辑器展示 SQL（`SqlLogPanel.openPreview` →
 * `SqlPreviewService.showResult` → `SqlPreviewDialog`），整条链路**不准备控制台**；
 * 旧入口要求「当前执行文档 === 已备控制台文档」，因此 target 恒为 null，错列标注整链被跳过。
 * 面板此时仍在绑定指示行展示「数据源（上次使用）：mp_demo@localhost」
 * （[DataSourceSelector.rememberedDataSourceName]，`panel.bindstate.associated`）——这是面板
 * 对当前 SQL 已确定的唯一数据源事实，可作为回落来源。
 *
 * ## fail-closed 五道闸（任一不满足 → null）
 *
 *  1. **只认 last-used 记忆**：活控制台绑定由旧入口负责；此处不借其他面板/其他预览的控制台；
 *  2. **在籍数据源可解析**（[DataSourceSettings.getAllDataSources] 按名唯一命中，拿对象身份——
 *     [DatabaseSchemaResolver.columnNamesCachedForTarget] 按 `===` 认源）；
 *  3. **方言可证为 MySQL/MariaDB**（[DataSourceUrlProbe.urlByName] 的 JDBC URL 前缀）；
 *  4. **库名非空**（[DatabaseSchemaResolver.databaseNameOf]，与预热缓存键同源）；
 *  5. **缓存已热**（[DatabaseSchemaResolver.columnNamesCachedForTarget] 对本 SQL 的表命中）。
 *
 * ## 红线
 *
 * 全程只读：记忆读 PropertiesComponent、数据源枚举/URL 走反射、列集只读已预热缓存——
 * **不建连、不内省、不发任何 DB 请求**。SQL 作用域（JOIN/CTE/子查询/限定列/多语句/显式库名）
 * 仍由 [SchemaValidationGate.check] 三参入口判定；本对象只负责「目标是否存在」。
 */
internal object PreviewFallbackTarget {

    /**
     * 测试缝：按名解析在籍数据源对象（headless 无 Database 插件时
     * [DataSourceSettings.getAllDataSources] 恒空）。生产为 null 走真链。
     */
    internal var dataSourceByNameForTest: ((Project, String) -> Any?)? = null

    /**
     * 解析预览面板自身已确定的校验目标；证据不足一律 null（调用方保持「不标注」）。
     *
     * @param project 当前项目
     * @param sql     预览编辑器当前文本（只为读表名做缓存热度证明，不参与作用域判定）
     */
    fun resolve(project: Project?, sql: String): SchemaValidationGate.Target? {
        if (project == null || project.isDisposed || sql.isBlank()) return null
        // 1) 面板指示行第二优先级：last-used 记忆（只读，歧义即弃）。
        val name = runCatching { DataSourceSelector.rememberedDataSourceName(project) }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }
            ?: return null
        // 2) 方言必须可证为 MySQL/MariaDB；取不到 URL 视为未知 → 不猜。
        val url = runCatching { DataSourceUrlProbe.urlByName(project, name) }
            .getOrNull()
            ?.takeIf { isMySqlFamily(it) }
            ?: return null
        // 3) 解析在籍数据源对象（缓存按对象身份匹配）。
        val dataSource = runCatching {
            dataSourceByNameForTest?.invoke(project, name)
                ?: DataSourceSettings.getAllDataSources(project)
                    .firstOrNull { DataSourceSettings.getName(it) == name }
        }.getOrNull() ?: return null
        // 4) 库名非空（与预热缓存键同源）。
        val database = runCatching { DatabaseSchemaResolver.databaseNameOf(dataSource) }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }
            ?: return null
        // 5) 缓存必须已热：至少命中本 SQL 引用的一个表，否则交给调用方保持零标注。
        val tables = runCatching { SchemaValidator.tableNames(sql) }.getOrNull().orEmpty()
        if (tables.isEmpty()) return null
        val warm = runCatching {
            DatabaseSchemaResolver.columnNamesCachedForTarget(dataSource, database, tables)
        }.getOrNull().orEmpty()
        if (warm.isEmpty()) return null
        return SchemaValidationGate.Target(dataSource, database)
    }

    /** MySQL 家族判定（与 [cn.song.mybatisplus.visualizer.execute.ExecConsoleContext] 同口径）。 */
    private fun isMySqlFamily(url: String): Boolean =
        url.startsWith("jdbc:mysql:", ignoreCase = true) ||
            url.startsWith("jdbc:mariadb:", ignoreCase = true)
}
