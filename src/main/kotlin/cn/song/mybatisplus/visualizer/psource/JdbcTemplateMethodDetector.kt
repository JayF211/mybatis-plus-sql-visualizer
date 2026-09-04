package cn.song.mybatisplus.visualizer.psource

/**
 * JdbcTemplate / NamedParameterJdbcTemplate 方法名检测器（JDBC 模板 SQL 字符串预览）。
 *
 * 纯函数（O(1) 文本匹配，零 PSI / 零 resolve），供 gutter 水印快速路径与 action 守卫共用。
 *
 * 识别规则（对齐 Spring JDBC 模板的字符串 SQL 重载）：
 *  - `query*` 族（query / queryForObject / queryForList / queryForMap / queryForRowSet /
 *    queryForLong / queryForInt / queryForStream）
 *  - `update` / `batchUpdate`
 *  - `execute`（字符串 SQL 重载，非 CallableStatementCreator 回调）
 *
 * 排除（避免误标非 SQL 语义）：
 *  - `execute(CallableStatementCreator)` / `execute(ConnectionCallback)` 等回调重载 →
 *    由调用方在定位器层按实参类型二次判定（本检测器仅做方法名 O(1) 判定）
 *
 * 与既有 `CustomDaoHqlLocator`（`findByQuery`/`executeHql`）、HQL `createQuery` 互不冲突：
 * 方法名集合天然区分。
 */
object JdbcTemplateMethodDetector {

    /**
     * JdbcTemplate / NamedParameterJdbcTemplate / 团队自研 DB 工具层（如 DbHelper）的字符串 SQL 方法名集合。
     *
     * 大小写不敏感（Spring 方法名全小写，但用户可能写扩展方法）。
     * 除 Spring 原生方法外，含自研工具层常见的 `delete`/`insert`（Spring JdbcTemplate 无这两个
     * 方法，但团队 DAO 常用）——只要**第 1 实参为字符串 SQL**，方法名在此集合即预览候选。
     *
     * 注意：第 1 实参约束在定位器层保证（非字符串实参 → 不聚合 → 不预览）；本集合仅做 O(1) 方法名判定。
     */
    val JDBC_TEMPLATE_METHODS: Set<String> = setOf(
        // query 族
        "query",
        "queryForObject",
        "queryForList",
        "queryForMap",
        "queryForRowSet",
        "queryForLong",
        "queryForInt",
        "queryForStream",
        // 更新 / 删除 / 插入
        "update",
        "batchUpdate",
        "delete",
        "insert",
        // 通用执行
        "execute",
    )

    /**
     * 判定方法名引用是否为 JdbcTemplate 模板方法候选。
     *
     * @param referenceName 方法名（如 `queryForList`、`update`、`delete`）
     * @return true = 候选；false = 非候选
     */
    fun isJdbcTemplateMethodReference(referenceName: String?): Boolean {
        if (referenceName == null) return false
        return referenceName in JDBC_TEMPLATE_METHODS
    }
}
