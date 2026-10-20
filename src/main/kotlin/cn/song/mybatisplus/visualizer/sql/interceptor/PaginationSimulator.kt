package cn.song.mybatisplus.visualizer.sql.interceptor

import cn.song.mybatisplus.visualizer.model.LimitClause
import cn.song.mybatisplus.visualizer.model.SqlValue

/**
 * 分页拦截器模拟（R12 T-PaginationSim，对齐 F-6 拦截器模拟族三件先例形态）。
 *
 * 模拟 MP PaginationInnerInterceptor 在 mapper 方法/XML/注解语句路径的两类产出：
 *  - **LIMIT 追加**：按 [cn.song.mybatisplus.visualizer.sql.Dialect.renderLimit] 渲染（调用方执行，
 *    本类负责构造占位 [LimitClause]）；
 *  - **COUNT 包裹**（D1=A：预览附第二条语句）：`SELECT COUNT(*) FROM (原SQL) TOTAL` 基础形态
 *    （D2=A：优化形态——去 ORDER BY 直查表/左联裁剪——登记后续，未实现）。
 *
 * 形态权威来源（V13b 证伪结论，2026-09-30 本地 jar 反编译，非记忆）：
 *  - MP 3.5.17 `com.baomidou.mybatisplus.extension.toolkit.SqlParserUtils.getOriginalCountSql`
 *    常量池 `SELECT COUNT(*) FROM (%s) TOTAL`（别名 TOTAL、大写）；
 *  - MP 3.5.17 `Page()` 无参构造默认 current=1 / size=10 / searchCount=true；
 *  - MP 3.5.17 `IPage.searchCount()` 为 false 时 `PaginationInnerInterceptor.willDoQuery`
 *    不执行 COUNT 查询（D3=A 跳过口径的运行时依据）；
 *  - MP 3.5.17 `BaseMapper` IPage 重载全集 = selectList(IPage,Wrapper) / selectMaps(IPage,Wrapper) /
 *    selectPage(P,Wrapper) / selectMapsPage(P,Wrapper)，**无 selectObjs(IPage,...)**。
 *
 * 本类为纯函数，不依赖 PSI / IDE，可独立单测。
 */
object PaginationSimulator {

    /** COUNT 包裹表别名（MP getOriginalCountSql 固定形态）。 */
    private const val COUNT_ALIAS = "TOTAL"

    /** F60（T-PerfSweep18）：LIMIT 子句探测（原 hasExistingLimit 函数体内现场 Regex，提 object 单例；internal＝同一 Pattern 实例断言观察缝）。 */
    internal val LIMIT_CLAUSE = Regex("(?i)\\bLIMIT\\b")

    /**
     * COUNT 包裹（基础形态，D2=A）：`SELECT COUNT(*) FROM (原SQL) TOTAL`。
     *
     * @param sql 原 SELECT 文本（建议压缩为单行——包裹形态可读性；MP 运行时传 BoundSql 原文）
     */
    fun wrapCountSql(sql: String): String = "SELECT COUNT(*) FROM ($sql) $COUNT_ALIAS"

    /**
     * XML/注解路径的占位 [LimitClause]（mapper 方法 Page 形参运行时值静态不可得）：
     * offset 来源标注 `(形参.current - 1) * 形参.size` 计算公式、size 来源标注 `形参.size`
     * （对齐 T-M29-03 运行时占位口径：面板回填后即真实值；`形参.current/size` 为 IPage 访问器语义）。
     *
     * @param paramName mapper 方法 Page/IPage 形参名
     */
    fun placeholderLimitClause(paramName: String): LimitClause = LimitClause(
        offset = SqlValue.Placeholder("($paramName.current - 1) * $paramName.size"),
        count = SqlValue.Placeholder("$paramName.size"),
    )

    /**
     * V13c 边界判定：语句文本是否已含 LIMIT 子句（顶层或子查询一律从保守——已含即不模拟追加，
     * 避免双 LIMIT 产出；对 MP 运行时「字面拼接不分现况」的偏离已登记为预览侧保守口径）。
     */
    fun hasExistingLimit(sql: String): Boolean = LIMIT_CLAUSE.containsMatchIn(sql)

    /** 分页占位 `?` 的参数来源清单（offset 公式在前、size 在后，顺序与 SQL 中出现序一致）。 */
    fun placeholderParamSources(paramName: String): List<String> = listOf(
        "($paramName.current - 1) * $paramName.size",
        "$paramName.size",
    )
}
