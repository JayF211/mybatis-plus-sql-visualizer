package cn.song.mybatisplus.visualizer.sql.perf

import net.sf.jsqlparser.statement.Statement

/**
 * 性能规则引擎（F-2 SQL 性能分析）。
 *
 * 注册 + 执行所有规则。每条规则在独立的 try-catch 中执行——单规则异常不阻塞其他规则。
 *
 * 执行流程：
 *  1. 遍历所有已注册规则；
 *  2. 每条规则独立 try-catch，异常时记录并跳过；
 *  3. 合并所有规则的警告列表返回。
 */
object PerfRuleEngine {

    private val rules = mutableListOf<PerfRule>()

    /**
     * 注册一条规则（幂等：同 ruleId 重复注册覆盖旧规则）。
     */
    fun register(rule: PerfRule) {
        rules.removeAll { it.ruleId == rule.ruleId }
        rules.add(rule)
    }

    /**
     * 批量注册规则。
     */
    fun registerAll(newRules: List<PerfRule>) {
        newRules.forEach { register(it) }
    }

    /**
     * 应用所有规则到 SQL。
     *
     * @param sql 原始 SQL 文本
     * @param ast JSqlParser 解析后的语句树；可为 null（降级模式）
     * @return 所有规则的警告列表（按规则注册顺序）
     */
    fun apply(sql: String, ast: Statement?): List<PerfWarning> {
        val warnings = mutableListOf<PerfWarning>()
        for (rule in rules) {
            try {
                val result = rule.check(sql, ast)
                if (result.isNotEmpty()) warnings.addAll(result)
            } catch (e: Exception) {
                // 规则隔离：单规则异常不阻塞其他规则
            }
        }
        return warnings
    }

    /** 当前注册规则数量（测试用）。 */
    fun ruleCount(): Int = rules.size
}
