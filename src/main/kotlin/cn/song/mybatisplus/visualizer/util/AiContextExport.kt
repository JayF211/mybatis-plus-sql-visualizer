package cn.song.mybatisplus.visualizer.util

/**
 * R37（T-LaneB D95/D97）：安全 AI 上下文交递——「复制为 AI 上下文」动作的输出块组装。
 *
 * 出口背景（V51 核实）：批次 9 后 MCP 接入方已有 sqlv_schema_context/sqlv_explain 等结构化
 * 工具，但**未接 MCP 的用户**没有「把 SQL 安全交给任意 AI」的插件内出口。本对象把当前预览
 * SQL 组装为**脱敏参数化**的文本块：敏感字面量替换为 `?`（[DisplayMasker.parameterize]），
 * 参数表只落 `***`——原值不出本方法（对齐 agent 侧 Desensitizer「数据不出应用 JVM」姿态与
 * sqlv_schema_context「credentials never leave the IDE」口径）。
 *
 * 块格式（V51.4 定案）：
 * ```
 * -- SQL Visualizer AI context (sensitive literals masked)
 * -- dialect: MySQL
 * -- masked literals: 2
 * SELECT id FROM user WHERE phone = ? AND password = ?
 * -- params (masked): 1=*** 2=***
 * ```
 * 方言缺失省略方言行；零敏感无参数表行。EXPLAIN 计划/表列摘要不入首版（计划文本面板侧无
 * 稳定引用，见计划册 §五）。纯 Kotlin 零平台依赖。
 */
object AiContextExport {

    /** 组装产物：[block] 完整文本块（可直接进剪贴板）；[maskedCount] 敏感字面量计数。 */
    data class Result(val block: String, val maskedCount: Int)

    /** 头部来源声明（固定，AI 可溯源到插件）。 */
    private const val HEADER = "-- SQL Visualizer AI context (sensitive literals masked)"

    /**
     * 组装 AI 上下文块。
     *
     * @param sql 当前预览 SQL 原文（含敏感字面量；只在本方法内参数化，不外泄）
     * @param dialect 方言名（如 `MySQL`；null 省略方言行）
     */
    fun build(sql: String, dialect: String?): Result {
        val parameterized = DisplayMasker.parameterize(sql)
        val block = buildString {
            append(HEADER).append('\n')
            if (!dialect.isNullOrBlank()) append("-- dialect: ").append(dialect).append('\n')
            append("-- masked literals: ").append(parameterized.maskedParams.size).append('\n')
            append(parameterized.sql)
            if (parameterized.maskedParams.isNotEmpty()) {
                append('\n')
                append("-- params (masked): ")
                append(parameterized.maskedParams.mapIndexed { i, v -> "${i + 1}=$v" }.joinToString(" "))
            }
        }
        return Result(block, parameterized.maskedParams.size)
    }
}
