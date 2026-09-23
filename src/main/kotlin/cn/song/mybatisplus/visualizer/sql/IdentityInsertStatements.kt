package cn.song.mybatisplus.visualizer.sql

/**
 * `SET IDENTITY_INSERT` 语句的识别与剥离（O-64 / T-RollbackIdentityParse，纯函数零依赖）。
 *
 * 背景（P19，2026-09-23 真机走查）：O-52/O-59 链路为 SQL Server / DM8 自产的 identity 包装回滚产物
 * （[identityInsertWrapper] 形态：`SET IDENTITY_INSERT <表> ON;` + INSERT + `SET … OFF;`，两方言逐字相同）
 * 走无界写静态检查时整体报 PARSE_FAILED「无法解析语句结构」——JSqlParser 5.3 对 T-SQL
 * `SET IDENTITY_INSERT <表> ON|OFF` 抛 `ParseException: Encountered unexpected token: "ON"`。
 * 异常形态已离线五路复现实证（方括号/裸表名/双引号/DM8 产物/单独 `SET … ON` 全部同败，
 * `SET @x = 1` 正常解析为 SetStatement）：**解析期抛错**，不是 AST 缺节点，
 * 故「检查前剥离再解析」（D1=A）是唯一可行路线。
 *
 * 判据刻意收窄为「精确前缀形态」：`SET IDENTITY_INSERT` + 单个表引用 + `ON|OFF` + 可选 `;`，
 * 且语句须位于干净边界（文首 / `;` 或换行之后）。静态检查面同时服务用户手写输入
 * （前提 §二.2 实证：三处确认流调用点 LogToSqlPanel/SqlPreviewPanel.executeSql/executeAdhocSql
 * + MCP CheckSqlTool），不做宽松匹配。大小写不敏感；表引用容 `[t]`、`"t"`、`` `t` ``、裸名（含库限定点）。
 *
 * 剥不到（形态怪异）时的失败方向是「不剥离」＝维持改前行为（整体 PARSE_FAILED fail closed），
 * 绝不产生「假绿」——不会把真写语句从检查眼皮底下放走。
 */
object IdentityInsertStatements {

    /**
     * 单条 `SET IDENTITY_INSERT <表> ON|OFF;`（含前导边界组与语句尾分号）。
     *
     * 边界组（捕获组 1）在剥离时**原样保留**：`^` 对应文首、`;`/换行对应上一条语句的收尾，
     * 保证剥离后前后语句的粘连关系不变（`A;SET … ON;B;` → `A;B;`）。
     * 表引用三种引用形态内禁换行；裸名容库限定点（`dbo.t`）。
     * 已知边界：多行字符串字面量内恰似该形态的文本会被一并剥掉——被剥对象是 SET 开关语句本身
     * （本就不产生任何发现），其后真实语句照常解析，不构成漏检面。
     */
    private val STATEMENT = Regex(
        "(?is)(^|[;\\r\\n])\\s*SET\\s+IDENTITY_INSERT\\s+" +
            "(\\[[^\\]\\r\\n]*\\]|\"[^\"\\r\\n]*\"|`[^`\\r\\n]*`|[A-Za-z_][\\w$#@.]*)" +
            "\\s+(ON|OFF)\\s*;?"
    )

    /** 快判痕迹（大小写不敏感的子串），绝大多数文本在此短路。 */
    private const val MARK = "IDENTITY_INSERT"

    /**
     * 剥离全部 identity 包装语句，返回供**解析用**的文本（不改用户原文）。
     * 无痕迹时返回**原引用**——[cn.song.mybatisplus.visualizer.execute.UnboundedWriteDetector]
     * 的 T-PS5-02 AST 单槽以「引用相等」为首级快判，此处保引用即保快路径（零额外解析）。
     */
    fun stripForParse(sql: String): String {
        if (!sql.contains(MARK, ignoreCase = true)) return sql
        return STATEMENT.replace(sql) { m -> m.groupValues[1] }
    }

    /** 是否含至少一条 `SET IDENTITY_INSERT … ON|OFF` 语句（D2 判据：identity 包装产物识别特征）。 */
    fun containsStatement(sql: String): Boolean =
        sql.contains(MARK, ignoreCase = true) && STATEMENT.containsMatchIn(sql)
}
